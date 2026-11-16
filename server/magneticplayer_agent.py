#!/usr/bin/env python3
"""MagneticPlayer: agent na komputerze (Linux Mint).

HTTP API dla apki na telefonie:
  - dodawanie magnet linków do Deluge (deluge-web JSON-RPC) albo Transmission (RPC),
  - lista torrentów i plików, strumieniowanie plików (HTTP Range) do odtwarzacza w telefonie,
  - zdalne sterowanie mpv na komputerze (JSON IPC): play/pauza, przewijanie, fullscreen, głośność, napisy,
  - blokada usypiania, gdy coś się pobiera albo leci film; uśpienie na żądanie.

Tylko biblioteka standardowa Pythona. Konfiguracja: ~/.config/magneticplayer/config.json
"""
import json
import mimetypes
import os
import re
import secrets
import shutil
import socket
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from http.cookiejar import CookieJar
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import mp_platform as platform
from mp_devices import Devices
from mp_jobs import AFTER, Jobs
from mp_library import Library, LibError, sidecar_subs
from mp_subs import Subtitles, SubsError

VERSION = "1.1.0"
API_LEVEL = 2  # /api/info: id, features, power; tokeny urządzeń, bilety, player/open z url
CONFIG_PATH = os.environ.get("MAGNETICPLAYER_CONFIG") or os.path.expanduser("~/.config/magneticplayer/config.json")
CONFIG_DIR = os.path.dirname(CONFIG_PATH)
WEB_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "web")  # klient webowy (server/web)
APK_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "apk")  # tu publish.sh wrzuca apkę


def apk_version():
    """Wersja apki opublikowanej na komputerze (phone/tools/publish.sh) — telefony same się aktualizują."""
    try:
        with open(os.path.join(APK_DIR, "version.json")) as f:
            v = json.load(f)
        if os.path.isfile(os.path.join(APK_DIR, "MagneticPlayer.apk")):
            return v
    except (OSError, ValueError):
        pass
    return {"version_code": 0}
RUNTIME_DIR = os.environ.get("XDG_RUNTIME_DIR") or f"/tmp/magneticplayer-{os.getuid()}"
MPV_SOCKET = os.path.join(RUNTIME_DIR, "magneticplayer-mpv.sock")
VIDEO_EXT = {".mkv", ".mp4", ".avi", ".m4v", ".mov", ".webm", ".wmv", ".mpg", ".mpeg", ".ts", ".m2ts", ".flv", ".ogv"}
AUDIO_EXT = {".mp3", ".flac", ".m4a", ".ogg", ".opus", ".wav", ".aac"}

DEFAULT_CONFIG = {
    "port": 8765,
    "token": "",
    "id": "",  # stały identyfikator serwera (apka rozpoznaje po nim serwer, nie po IP)
    "name": "",  # nazwa w apce; puste = hostname
    "backend": "deluge",  # deluge | transmission | none (serwer bez torrentów, np. tylko ekran)
    "deluge_url": "http://127.0.0.1:8112/json",
    "deluge_password": "deluge",
    "transmission_url": "http://127.0.0.1:9091/transmission/rpc",
    "transmission_user": "",
    "transmission_password": "",
    "keep_awake": True,
    # odblokowanie ekranu blokady przy "Na komputerze" i "Włącz ekran"; każdy z tokenem odblokuje wtedy pulpit
    "auto_unlock": True,
    "mpv_args": ["--fs", "--save-position-on-quit"],
}


def log(*a):
    print(time.strftime("%H:%M:%S"), *a, file=sys.stderr, flush=True)


def load_config():
    cfg = dict(DEFAULT_CONFIG)
    if os.path.exists(CONFIG_PATH):
        with open(CONFIG_PATH) as f:
            cfg.update(json.load(f))
    if not cfg["token"] or not cfg["id"]:
        cfg["token"] = cfg["token"] or secrets.token_urlsafe(18)
        cfg["id"] = cfg["id"] or secrets.token_hex(8)
        os.makedirs(CONFIG_DIR, exist_ok=True)
        with open(CONFIG_PATH, "w") as f:
            json.dump(cfg, f, indent=2)
        os.chmod(CONFIG_PATH, 0o600)
    return cfg


def save_config(cfg):
    os.makedirs(CONFIG_DIR, exist_ok=True)
    tmp = CONFIG_PATH + ".tmp"
    with open(tmp, "w") as f:
        json.dump(cfg, f, indent=2, ensure_ascii=False)
    os.chmod(tmp, 0o600)
    os.replace(tmp, CONFIG_PATH)


class ApiError(Exception):
    def __init__(self, msg, status=500):
        super().__init__(msg)
        self.status = status


# ---------------------------------------------------------------- backendy torrentowe

def is_media(path):
    ext = os.path.splitext(path)[1].lower()
    return "video" if ext in VIDEO_EXT else "audio" if ext in AUDIO_EXT else None


def ready_end(pieces, start, size):
    """Do którego bajtu (wyłącznie) plik jest pobrany ciągiem od `start`. pieces = (offset pliku w torrencie,
    rozmiar kawałka, lista pobranych kawałków albo None, gdy torrent kompletny)."""
    if pieces is None or pieces[2] is None:
        return size
    offset, piece_len, have = pieces
    if start >= size:
        return size
    k = (offset + start) // piece_len
    while k < len(have) and have[k]:
        k += 1
    return max(start, min(size, k * piece_len - offset))


def contiguous_ready(have, piece_len, offset, size):
    """Ile bajtów pliku jest pobrane ciągiem od jego początku (tyle da się obejrzeć bez dziur)."""
    if size <= 0:
        return 0
    k = offset // piece_len
    while k < len(have) and have[k]:
        k += 1
    return max(0, min(size, k * piece_len - offset))


_durations = {}
_durations_lock = threading.Lock()


_probe_slots = threading.Semaphore(2)


def _probe_duration(path):
    dur = None
    with _probe_slots:
        if os.path.exists(path) and shutil.which("mpv"):
            try:
                out = subprocess.run(["mpv", "--no-config", "--vo=null", "--ao=null", "--frames=1", "--quiet",
                                      "--term-playing-msg=DUR=${=duration}", path],
                                     capture_output=True, text=True, timeout=30).stdout
                m = re.search(r"DUR=([0-9.]+)", out)
                dur = float(m.group(1)) if m else None
            except (subprocess.TimeoutExpired, OSError):
                pass
    with _durations_lock:
        _durations[path] = (dur, time.time())


def media_duration(path):
    """Długość filmu w sekundach albo None (wtedy odczyt rusza w tle — mpv czyta nagłówek, także niepełnego pliku)."""
    with _durations_lock:
        if path in _durations:
            dur, when = _durations[path]
            if dur is not None or when == 0 or time.time() - when < 60:  # when == 0: odczyt w toku
                return dur
        _durations[path] = (None, 0)
    threading.Thread(target=_probe_duration, args=(path,), daemon=True).start()
    return None


class DelugeBackend:
    """deluge-web JSON-RPC (http://127.0.0.1:8112/json)."""

    FIELDS = ["name", "progress", "state", "download_payload_rate", "upload_payload_rate", "eta",
              "total_wanted", "save_path", "sequential_download", "num_peers", "num_seeds", "time_added"]

    def __init__(self, cfg):
        self.url = cfg["deluge_url"]
        self.password = cfg["deluge_password"]
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(CookieJar()))
        self.lock = threading.Lock()
        self.req_id = 0

    def _raw(self, method, params):
        self.req_id += 1
        body = json.dumps({"method": method, "params": params, "id": self.req_id}).encode()
        req = urllib.request.Request(self.url, body, {"Content-Type": "application/json"})
        try:
            with self.opener.open(req, timeout=15) as r:
                data = json.loads(r.read())
        except (urllib.error.URLError, OSError) as e:
            raise ApiError(f"deluge-web niedostępny ({self.url}): {e}", 502)
        if data.get("error"):
            err = data["error"]
            raise ApiError(f"deluge: {err.get('message', err)}", 502 if err.get("code") != 1 else 401)
        return data["result"]

    def _ensure(self):
        if not self._raw("auth.check_session", []):
            if not self._raw("auth.login", [self.password]):
                raise ApiError("deluge-web: złe hasło (deluge_password w config.json)", 502)
        if not self._raw("web.connected", []):
            hosts = self._raw("web.get_hosts", [])
            if not hosts:
                raise ApiError("deluge-web: brak hostów deluged", 502)
            local = [h for h in hosts if h[1] in ("127.0.0.1", "localhost")] or hosts
            self._raw("web.connect", [local[0][0]])
            for _ in range(20):
                if self._raw("web.connected", []):
                    break
                time.sleep(0.25)
            else:
                raise ApiError("deluge-web nie połączył się z deluged (czy deluged działa?)", 502)

    def call(self, method, *params):
        with self.lock:
            try:
                self._ensure()
                return self._raw(method, list(params))
            except ApiError as e:
                if e.status == 401:  # sesja wygasła
                    self._raw("auth.login", [self.password])
                    return self._raw(method, list(params))
                raise

    def default_dirs(self):
        c = self.call("core.get_config_values", ["download_location", "move_completed_path"])
        dirs = [c.get("download_location"), c.get("move_completed_path")]
        return dirs + sorted({t["save_path"] for t in self.list()})

    def add_magnet(self, uri, sequential):
        opts = {"sequential_download": bool(sequential), "prioritize_first_last_pieces": bool(sequential)}
        tid = self.call("core.add_torrent_magnet", uri, opts)
        if not tid:  # Deluge zwraca None, gdy torrent już jest na liście
            m = re.search(r"btih:([0-9a-fA-F]{40})", uri)
            tid = m.group(1).lower() if m else None
        return tid

    def list(self):
        res = self.call("core.get_torrents_status", {}, self.FIELDS)
        out = []
        for tid, t in res.items():
            out.append({
                "id": tid, "name": t["name"], "progress": round(t["progress"], 1), "state": t["state"],
                "down_rate": t["download_payload_rate"], "up_rate": t["upload_payload_rate"],
                "eta": t["eta"], "size": t["total_wanted"], "save_path": t["save_path"],
                "sequential": t.get("sequential_download", False),
                "peers": t["num_peers"], "seeds": t["num_seeds"], "added": t["time_added"],
            })
        out.sort(key=lambda t: -t["added"])
        return out

    def files(self, tid):
        st = self.call("core.get_torrent_status", tid,
                       ["files", "file_progress", "save_path", "name", "pieces", "piece_length"])
        if not st:
            raise ApiError("nie ma takiego torrenta", 404)
        prog = st.get("file_progress") or []
        pieces = st.get("pieces")  # None, gdy torrent kompletny; inaczej stan kawałka, 3 = pobrany
        have = [p == 3 for p in pieces] if isinstance(pieces, list) else None
        files = []
        for f in st["files"]:
            i = f["index"]
            pr = prog[i] if i < len(prog) else 0
            ready = f["size"] if pr >= 1 or have is None else \
                contiguous_ready(have, st["piece_length"], f["offset"], f["size"])
            files.append({"index": i, "path": f["path"], "size": f["size"],
                          "progress": round(100 * pr, 1), "ready": ready, "media": is_media(f["path"]),
                          "_pieces": (f["offset"], st["piece_length"], None if ready >= f["size"] else have)})
        return st["save_path"], files

    def action(self, tid, action):
        if action == "pause":
            self.call("core.pause_torrent", [tid])
        elif action == "resume":
            self.call("core.resume_torrent", [tid])
        elif action in ("remove", "remove_data"):
            self.call("core.remove_torrent", tid, action == "remove_data")
        elif action in ("sequential_on", "sequential_off"):
            on = action == "sequential_on"
            self.call("core.set_torrent_options", [tid], {"sequential_download": on, "prioritize_first_last_pieces": on})
        else:
            raise ApiError(f"nieznana akcja {action}", 400)


class TransmissionBackend:
    STATUS = {0: "Paused", 1: "Checking", 2: "Checking", 3: "Queued", 4: "Downloading", 5: "Queued", 6: "Seeding"}

    def __init__(self, cfg):
        self.url = cfg["transmission_url"]
        self.auth = None
        if cfg.get("transmission_user"):
            import base64
            self.auth = "Basic " + base64.b64encode(
                f"{cfg['transmission_user']}:{cfg['transmission_password']}".encode()).decode()
        self.session = ""

    def call(self, method, args=None):
        body = json.dumps({"method": method, "arguments": args or {}}).encode()
        for _ in range(2):
            headers = {"Content-Type": "application/json", "X-Transmission-Session-Id": self.session}
            if self.auth:
                headers["Authorization"] = self.auth
            try:
                with urllib.request.urlopen(urllib.request.Request(self.url, body, headers), timeout=15) as r:
                    data = json.loads(r.read())
            except urllib.error.HTTPError as e:
                if e.code == 409:
                    self.session = e.headers.get("X-Transmission-Session-Id", "")
                    continue
                raise ApiError(f"transmission: HTTP {e.code}", 502)
            except (urllib.error.URLError, OSError) as e:
                raise ApiError(f"transmission niedostępny ({self.url}): {e}", 502)
            if data.get("result") != "success":
                raise ApiError(f"transmission: {data.get('result')}", 502)
            return data.get("arguments", {})
        raise ApiError("transmission: brak sesji", 502)

    def default_dirs(self):
        return [self.call("session-get", {"fields": ["download-dir"]}).get("download-dir")] + \
            sorted({t["save_path"] for t in self.list()})

    def add_magnet(self, uri, sequential):
        args = {"filename": uri}
        if sequential:
            args["sequential_download"] = True  # Transmission >= 4.1, starsze ignorują
        r = self.call("torrent-add", args)
        t = r.get("torrent-added") or r.get("torrent-duplicate") or {}
        return t.get("hashString")

    def list(self):
        fields = ["hashString", "name", "percentDone", "status", "rateDownload", "rateUpload", "eta",
                  "sizeWhenDone", "downloadDir", "peersConnected", "addedDate", "error", "errorString"]
        r = self.call("torrent-get", {"fields": fields})
        out = [{
            "id": t["hashString"], "name": t["name"], "progress": round(100 * t["percentDone"], 1),
            "state": "Error" if t["error"] else self.STATUS.get(t["status"], "?"),
            "down_rate": t["rateDownload"], "up_rate": t["rateUpload"], "eta": t["eta"],
            "size": t["sizeWhenDone"], "save_path": t["downloadDir"], "sequential": False,
            "peers": t["peersConnected"], "seeds": 0, "added": t["addedDate"],
        } for t in r.get("torrents", [])]
        out.sort(key=lambda t: -t["added"])
        return out

    def files(self, tid):
        r = self.call("torrent-get", {"ids": [tid], "fields": ["files", "downloadDir", "pieces", "pieceSize", "pieceCount"]})
        if not r.get("torrents"):
            raise ApiError("nie ma takiego torrenta", 404)
        t = r["torrents"][0]
        import base64
        bits = base64.b64decode(t.get("pieces") or "")
        have = [bool(bits[k >> 3] >> (7 - (k & 7)) & 1) if (k >> 3) < len(bits) else False
                for k in range(t.get("pieceCount") or 0)]
        out, offset = [], 0
        for i, f in enumerate(t["files"]):
            done = f["bytesCompleted"] >= f["length"]
            out.append({
                "index": i, "path": f["name"], "size": f["length"],
                "progress": round(100 * f["bytesCompleted"] / f["length"], 1) if f["length"] else 100,
                "ready": f["length"] if done else contiguous_ready(have, t["pieceSize"], offset, f["length"]),
                "media": is_media(f["name"]),
                "_pieces": (offset, t["pieceSize"], None if done else have),
            })
            offset += f["length"]
        return t["downloadDir"], out

    def action(self, tid, action):
        m = {"pause": ("torrent-stop", {}), "resume": ("torrent-start", {}),
             "remove": ("torrent-remove", {"delete-local-data": False}),
             "remove_data": ("torrent-remove", {"delete-local-data": True}),
             "sequential_on": ("torrent-set", {"sequential_download": True}),
             "sequential_off": ("torrent-set", {"sequential_download": False})}
        if action not in m:
            raise ApiError(f"nieznana akcja {action}", 400)
        method, args = m[action]
        self.call(method, dict(args, ids=[tid]))


class NoBackend:
    """backend: none, serwer bez klienta torrentów (np. tylko ekran albo tylko biblioteka)."""

    def __init__(self, cfg):
        pass

    def default_dirs(self):
        return []

    def list(self):
        return []

    def add_magnet(self, uri, sequential):
        raise ApiError("ten serwer nie pobiera torrentów", 409)

    def files(self, tid):
        raise ApiError("ten serwer nie pobiera torrentów", 409)

    def action(self, tid, action):
        raise ApiError("ten serwer nie pobiera torrentów", 409)


# ---------------------------------------------------------------- sesja graficzna, mpv

graphical_env = platform.graphical_env
AUTO_UNLOCK = True  # nadpisywane z configu w App.__init__
POWER = True  # config "power": false — z sieci nie da się uśpić ani wyłączyć (np. laptop do pracy)


def wake_screen(unlock=None):
    return platform.wake_screen(AUTO_UNLOCK if unlock is None else unlock)


class Mpv:
    def __init__(self, cfg):
        self.args = cfg.get("mpv_args", [])
        self.proc = None
        self.lock = threading.Lock()
        self.req = 0

    def running(self):
        return self.proc is not None and self.proc.poll() is None

    def cmd(self, *command, timeout=3):
        if not self.running():
            raise ApiError("mpv nie działa", 409)
        with self.lock:
            self.req += 1
            rid = self.req
        s = socket.socket(socket.AF_UNIX)
        s.settimeout(timeout)
        try:
            s.connect(MPV_SOCKET)
            s.sendall(json.dumps({"command": list(command), "request_id": rid}).encode() + b"\n")
            buf = b""
            while True:
                chunk = s.recv(65536)
                if not chunk:
                    raise ApiError("mpv zamknął połączenie", 502)
                buf += chunk
                while b"\n" in buf:
                    line, buf = buf.split(b"\n", 1)
                    msg = json.loads(line)
                    if msg.get("request_id") == rid:
                        if msg.get("error") not in ("success", None):
                            raise ApiError(f"mpv: {msg['error']}", 400)
                        return msg.get("data")
        except (OSError, socket.timeout) as e:
            raise ApiError(f"mpv IPC: {e}", 502)
        finally:
            s.close()

    def prop(self, name, default=None):
        try:
            return self.cmd("get_property", name)
        except ApiError:
            return default

    def open(self, path, title=None, subs=(), start=None):
        """Plik lokalny albo URL (film z innego serwera, z biletem). `subs`: URL-e napisów, pierwsze wybrane."""
        wake_screen()
        if self.running():
            self.cmd("set_property", "force-media-title", title or "")
            self.cmd("loadfile", path, "replace")
            self.cmd("set_property", "pause", False)
        else:
            self._start(path, title)
        if subs or start:
            self._after_load(path, subs, start)

    def _after_load(self, path, subs, start):
        """Czeka, aż mpv wczyta plik (po sieci to potrwa), potem napisy i przewinięcie."""
        for _ in range(150):
            if self.prop("path") == path and self.prop("duration") is not None:
                break
            if not self.running():
                return
            time.sleep(0.1)
        for i, u in enumerate(subs):
            try:
                self.cmd("sub-add", u, "select" if i == 0 else "auto", timeout=15)
            except ApiError as e:
                log("napisy z", u.split("?")[0], e)
        if start:
            self.cmd("seek", float(start), "absolute")

    def _start(self, path, title):
        if not shutil.which("mpv"):
            raise ApiError("brak mpv na komputerze (sudo apt install mpv)", 500)
        try:
            os.unlink(MPV_SOCKET)
        except FileNotFoundError:
            pass
        argv = ["mpv", f"--input-ipc-server={MPV_SOCKET}", "--force-window=yes", "--idle=once",
                "--keep-open=yes", *self.args, *([f"--force-media-title={title}"] if title else []), path]
        log("start:", " ".join(re.sub(r"(ticket|token)=[^&\s]+", r"\1=…", a) for a in argv))
        self.proc = subprocess.Popen(argv, env=graphical_env(), stdin=subprocess.DEVNULL,
                                     stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True)
        for _ in range(50):
            if os.path.exists(MPV_SOCKET):
                return
            if not self.running():
                raise ApiError("mpv nie wystartował (czy jest sesja graficzna?)", 500)
            time.sleep(0.1)

    def status(self):
        if not self.running():
            return {"running": False}
        return {
            "running": True,
            "title": self.prop("media-title"),
            "path": self.prop("path"),
            "pos": self.prop("time-pos"),
            "duration": self.prop("duration"),
            "paused": self.prop("pause"),
            "volume": self.prop("volume"),
            "muted": self.prop("mute"),
            "fullscreen": self.prop("fullscreen"),
            "sub": self.prop("current-tracks/sub/title") or self.prop("current-tracks/sub/lang"),
            "audio": self.prop("current-tracks/audio/title") or self.prop("current-tracks/audio/lang"),
            "idle": self.prop("idle-active"),
            "tracks": self.tracks(),
        }

    def tracks(self):
        """Ścieżki wideo/audio/napisów (wbudowane i zewnętrzne) do wyboru w pilocie."""
        out = []
        for t in self.prop("track-list") or []:
            if t.get("type") not in ("video", "audio", "sub") or t.get("albumart"):
                continue
            out.append({k: t.get(k) for k in ("id", "type", "title", "lang", "codec", "selected", "external",
                                               "default", "forced", "demux-channel-count", "demux-w", "demux-h")})
        return out

    def control(self, c, value=None):
        if c == "toggle_pause":
            self.cmd("cycle", "pause")
        elif c in ("pause", "play"):
            self.cmd("set_property", "pause", c == "pause")
        elif c == "seek":
            self.cmd("seek", float(value), "relative")
        elif c == "seek_to":
            self.cmd("seek", float(value), "absolute")
        elif c == "fullscreen":
            if value is None:
                self.cmd("cycle", "fullscreen")
            else:
                self.cmd("set_property", "fullscreen", bool(value))
        elif c == "volume":
            self.cmd("set_property", "volume", max(0.0, min(130.0, float(value))))
        elif c == "volume_rel":
            self.cmd("add", "volume", float(value))
        elif c == "mute":
            self.cmd("cycle", "mute")
        elif c == "sub_cycle":
            self.cmd("cycle", "sub")
        elif c == "audio_cycle":
            self.cmd("cycle", "audio")
        elif c == "track":
            prop = {"video": "vid", "audio": "aid", "sub": "sid"}.get((value or {}).get("type"))
            if not prop:
                raise ApiError("zły typ ścieżki", 400)
            tid = value.get("id")
            self.cmd("set_property", prop, "no" if tid in (None, "no") else int(tid))
        elif c == "sub_delay":
            self.cmd("add", "sub-delay", float(value))
        elif c == "stop":
            self.cmd("quit")
        else:
            raise ApiError(f"nieznane polecenie {c}", 400)


# ---------------------------------------------------------------- blokada usypiania

class SleepGuard(threading.Thread):
    """Trzyma systemd-inhibit (sleep), gdy coś się pobiera albo gra mpv."""

    def __init__(self, app):
        super().__init__(daemon=True)
        self.app = app
        self.proc = None
        self.reason = ""
        self.paused_until = 0

    def hold(self, why):
        if self.proc and self.proc.poll() is None:
            return
        self.proc = platform.inhibit_sleep(why)
        if self.proc:
            log("blokada usypiania:", why)

    def release(self):
        if self.proc and self.proc.poll() is None:
            self.proc.terminate()
            log("blokada usypiania zdjęta")
        self.proc = None

    def run(self):
        while True:
            try:
                why = None
                if time.time() >= self.paused_until:
                    if self.app.mpv.running() and self.app.mpv.prop("pause") is False:
                        why = "odtwarzanie"
                    else:
                        active = [t for t in self.app.backend.list()
                                  if t["state"] in ("Downloading", "Checking", "Allocating", "Moving")]
                        if active:
                            why = f"pobieranie ({len(active)})"
                if why:
                    self.hold(why)
                else:
                    self.release()
                self.reason = why or ""
                playing = self.app.mpv.running() and self.app.mpv.prop("pause") is False
                self.app.jobs.tick(self.app.backend.list(), playing, self.app.machine_action)
            except Exception as e:  # noqa: BLE001 — wątek tła nie może paść
                log("SleepGuard:", e)
            time.sleep(20)


def poweroff(guard):
    if not POWER:
        raise ApiError("ten serwer ma wyłączone wyłączanie z sieci (config: power)", 403)
    guard.paused_until = time.time() + 120
    guard.release()
    err = platform.power("poweroff")
    if err:
        raise ApiError(f"wyłączanie nie wyszło: {err}", 500)


def suspend(guard):
    if not POWER:
        raise ApiError("ten serwer ma wyłączone usypianie z sieci (config: power)", 403)
    guard.paused_until = time.time() + 120
    guard.release()
    err = platform.power("suspend")
    if err:
        raise ApiError(f"usypianie nie wyszło: {err}", 500)


# ---------------------------------------------------------------- HTTP

class App:
    def __init__(self, cfg):
        global AUTO_UNLOCK, POWER
        self.cfg = cfg
        POWER = bool(cfg.get("power", True))
        AUTO_UNLOCK = bool(cfg.get("auto_unlock", True))
        self.backend = {"deluge": DelugeBackend, "transmission": TransmissionBackend}.get(cfg["backend"], NoBackend)(cfg)
        self.mpv = Mpv(cfg)
        self.guard = SleepGuard(self)
        self.library = Library(cfg, lambda: save_config(cfg), self._default_dirs)
        self.subs = Subtitles(cfg, lambda: save_config(cfg))
        self.jobs = Jobs(os.path.join(CONFIG_DIR, "jobs.json"), log)
        self.devices = Devices(os.path.join(CONFIG_DIR, "devices.json"), cfg["token"])
        self._subs_ok = None

    def name(self):
        return self.cfg.get("name") or socket.gethostname()

    def wake_info(self, net):
        """Ocena z mp_platform + „yes”, gdy budzenie przez sieć już raz zadziałało (na tej samej karcie)."""
        w = platform.wake_info(net)
        v = self.cfg.get("wake_verified") or {}
        if v.get("at") and w.get("mac") and v.get("mac") in (None, w["mac"]) and not net.get("wireless"):
            day = time.strftime("%d.%m.%Y", time.localtime(v["at"]))
            w = {**w, "confidence": "yes", "reason": f"budzenie przez sieć zadziałało ({day})", "verified_at": v["at"]}
        return w

    def is_screen(self):
        """Czy wolno tu odtwarzać: config "player" (domyślnie tak) + mpv i sesja graficzna."""
        return bool(self.cfg.get("player", True)) and platform.has_display()

    def subs_available(self):
        if self._subs_ok is None:
            try:
                from mp_subs import _subliminal
                _subliminal()
                self._subs_ok = True
            except Exception:  # noqa: BLE001 — brak venv/subliminal
                self._subs_ok = False
        return self._subs_ok

    def info(self, client_ip, device):
        """Kim jest ten serwer i co umie (apka chowa to, czego nie ma)."""
        net = platform.net_info(client_ip)
        has_torrent = not isinstance(self.backend, NoBackend)
        try:
            library = bool(self.library.roots())
        except LibError:
            library = False
        return {
            "id": self.cfg["id"], "name": self.name(), "hostname": socket.gethostname(), "version": VERSION,
            "api": API_LEVEL, "backend": self.cfg["backend"], "keep_awake": self.guard.reason, "device": device,
            **net,
            "features": {
                "torrent": self.cfg["backend"] if has_torrent else None,
                "library": library,
                "player": self.is_screen(),
                "subs_search": self.subs_available(),
                "unlock": AUTO_UNLOCK and platform.can_unlock(),
                "apk": apk_version().get("version_code", 0) > 0,
                "wake_others": True,
            },
            "power": {"suspend": POWER and platform.can_power(), "poweroff": POWER and platform.can_power(),
                      "wake": self.wake_info(net)},
        }

    def ref_scope(self, d):
        """Zakres biletu dla wskazania pliku (ten sam dla filmu i jego napisów)."""
        if d.get("torrent_id"):
            return f"t:{d['torrent_id'].lower()}/{int(d['file_index'])}"
        if d.get("root") is not None and d.get("root") != "":
            return f"l:{int(d['root'])}/{d.get('path', '')}"
        return None

    def _default_dirs(self):
        try:
            return [d for d in self.backend.default_dirs() if d]
        except ApiError:
            return []

    def resolve_ref(self, d):
        """Plik wskazany przez torrent (torrent_id + file_index) albo bibliotekę (root + path)."""
        if d.get("playing") in (True, "1", "true"):  # plik, który właśnie gra w mpv
            path = self.mpv.prop("path") if self.mpv.running() else None
            if not path:
                raise ApiError("na komputerze nic nie gra", 409)
            if not os.path.isfile(path):
                raise ApiError("film gra z innego serwera, napisy szukaj tam (w Bibliotece albo przy torrencie)", 409)
            return path
        if d.get("torrent_id"):
            return self.resolve_file(d["torrent_id"], int(d["file_index"]))[0]
        if d.get("root") is not None and d.get("root") != "":
            return self.library.resolve(int(d["root"]), d.get("path", ""))
        raise ApiError("brak wskazania pliku", 400)

    def machine_action(self, tid, action):
        if tid:
            self.backend.action(tid, action)
        elif action == "suspend":
            suspend(self.guard)
        elif action == "poweroff":
            poweroff(self.guard)

    def resolve_file(self, tid, index):
        save_path, files = self.backend.files(tid)
        f = next((f for f in files if f["index"] == index), None)
        if not f:
            raise ApiError("nie ma takiego pliku", 404)
        base = os.path.realpath(save_path)
        full = os.path.realpath(os.path.join(base, f["path"]))
        if not full.startswith(base + os.sep):
            raise ApiError("ścieżka poza katalogiem pobierania", 403)
        return full, f


class Handler(BaseHTTPRequestHandler):
    server_version = f"MagneticPlayer/{VERSION}"
    protocol_version = "HTTP/1.1"
    app: App = None

    def log_message(self, fmt, *args):
        if not self.path.startswith(("/api/player/status", "/api/torrents?")):
            log(self.client_address[0], re.sub(r"(token|ticket)=[^&\s\"]+", r"\1=…", fmt % args))

    def send_json(self, obj, status=200):
        body = json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def authorized(self, query):
        """Id sparowanego urządzenia (albo "legacy" dla starego wspólnego tokenu) lub None."""
        h = self.headers.get("Authorization", "")
        given = h[7:] if h.startswith("Bearer ") else (query.get("token") or [""])[0]
        return self.app.devices.check(given)

    def ticket_ok(self, p, q):
        """Bilet (?ticket=) otwiera tylko jeden plik i jego napisy: /media, /lib, /sub."""
        t = (q.get("ticket") or [""])[0]
        if not t:
            return False
        m = re.fullmatch(r"/media/([0-9a-fA-F]+)/(\d+)(/.*)?", p)
        if m:
            scope = self.app.ref_scope({"torrent_id": m.group(1), "file_index": m.group(2)})
        elif (m := re.fullmatch(r"/lib/(\d+)/(.+)", p)):
            scope = self.app.ref_scope({"root": m.group(1), "path": urllib.parse.unquote(m.group(2))})
        elif p == "/sub":
            scope = self.app.ref_scope({k: v[0] for k, v in q.items()})
        else:
            return False
        return bool(scope) and self.app.devices.check_ticket(t, scope)

    def base_url(self):
        """Adres tego serwera tak, jak widzi go klient (do URL-i dla innego ekranu w tej samej sieci)."""
        host = self.headers.get("Host") or f"{self.server.server_address[0]}:{self.server.server_address[1]}"
        return "http://" + host

    def body(self):
        n = int(self.headers.get("Content-Length") or 0)
        return json.loads(self.rfile.read(n)) if n else {}

    def do_GET(self):
        self.dispatch("GET")

    def do_POST(self):
        self.dispatch("POST")

    def do_HEAD(self):
        self.dispatch("HEAD")

    def dispatch(self, method):
        u = urllib.parse.urlsplit(self.path)
        q = urllib.parse.parse_qs(u.query)
        p = u.path.rstrip("/")
        try:
            if p == "/api/ping":
                # id: apka sprawdza, czy pod znanym adresem jest ten sam serwer, zanim wyśle mu token
                return self.send_json({"ok": True, "app": "magneticplayer", "version": VERSION, "id": self.app.cfg["id"]})
            if p in ("", "/index.html") and method in ("GET", "HEAD"):
                # klient webowy (laptop/tablet bez apki); statyczny plik, dane z API i tak wymagają tokenu
                return self.send_static(os.path.join(WEB_DIR, "index.html"), "text/html; charset=utf-8", method == "HEAD")
            a = self.app
            if p == "/api/pair" and method == "POST":
                # bez tokenu: jednorazowy kod (z apki innego urządzenia albo --pair na komputerze) → token urządzenia
                b = self.body()
                res, err = a.devices.pair(b.get("code"), b.get("name"))
                if err:
                    raise ApiError(err, 403)
                log("sparowano urządzenie:", (b.get("name") or "?")[:60])
                return self.send_json({**res, "id": a.cfg["id"], "name": a.name()})
            self.device = self.authorized(q)
            if not self.device and not (method in ("GET", "HEAD") and self.ticket_ok(p, q)):
                raise ApiError("zły token", 401)
            if p == "/api/info" and method == "GET":
                return self.send_json(a.info(self.client_address[0], self.device))
            if p == "/api/pair/code" and method == "POST":
                code, ttl = a.devices.new_code()
                host, _, port = (self.headers.get("Host") or "").rpartition(":")
                return self.send_json({"code": code, "expires_in": ttl, "host": host or None,
                                       "port": int(port) if port.isdigit() else a.cfg["port"],
                                       "id": a.cfg["id"], "name": a.name()})
            if p == "/api/devices" and method == "GET":
                return self.send_json(a.devices.list(self.device))
            if p == "/api/devices/revoke" and method == "POST":
                dev = str(self.body().get("id") or "")
                if not a.devices.revoke(dev):
                    raise ApiError("nie ma takiego urządzenia", 404)
                log("odebrano dostęp urządzeniu", dev)
                return self.send_json({"ok": True})
            if p == "/api/wake/verified" and method == "POST":
                # apka obudziła ten komputer przez sieć (wysłała WoL, potem odpowiedział): pamiętamy dla wszystkich
                net = platform.net_info(self.client_address[0])
                a.cfg["wake_verified"] = {"at": time.time(), "mac": net.get("mac"), "by": self.device}
                save_config(a.cfg)
                log(f"budzenie przez sieć potwierdzone (urządzenie {self.device})")
                return self.send_json({"ok": True})
            if p == "/api/wake" and method == "POST":
                b = self.body()
                try:
                    platform.send_wol(str(b.get("mac") or ""), b.get("ip"))
                except ValueError as e:
                    raise ApiError(str(e), 400)
                return self.send_json({"ok": True})
            if p == "/api/media/ticket" and method == "POST":
                b = self.body()
                full = a.resolve_ref(b)
                scope = a.ref_scope(b)
                if not scope:
                    raise ApiError("bilet tylko na plik z torrenta albo biblioteki", 400)
                ticket, exp = a.devices.ticket(scope)
                base = self.base_url()
                tq = "ticket=" + urllib.parse.quote(ticket)
                if b.get("torrent_id"):
                    url = f"{base}/media/{b['torrent_id']}/{int(b['file_index'])}/{urllib.parse.quote(os.path.basename(full))}?{tq}"
                    rq = f"torrent_id={b['torrent_id']}&file_index={int(b['file_index'])}"
                else:
                    path = b.get("path", "")
                    url = f"{base}/lib/{int(b['root'])}/{urllib.parse.quote(path)}?{tq}"
                    rq = f"root={int(b['root'])}&path={urllib.parse.quote(path)}"
                subs = [{"name": x["name"], "lang": x.get("lang"),
                         "url": f"{base}/sub?{rq}&name={urllib.parse.quote(x['name'])}&{tq}"} for x in sidecar_subs(full)]
                return self.send_json({"url": url, "subs": subs, "title": os.path.basename(full), "expires": exp})
            if p == "/api/app/version" and method == "GET":
                return self.send_json(apk_version())
            if p == "/app/MagneticPlayer.apk" and method in ("GET", "HEAD"):
                return self.stream(os.path.join(APK_DIR, "MagneticPlayer.apk"), method == "HEAD")
            if p == "/api/torrents" and method == "GET":
                jobs = {j["id"]: j for j in a.jobs.all()}
                out = a.backend.list()
                for t in out:
                    j = jobs.get(t["id"])
                    t["mine"] = j is not None
                    t["after"] = j.get("after", "none") if j else "none"
                return self.send_json(out)
            if p == "/api/jobs" and method == "GET":
                present = {t["id"]: t for t in a.backend.list()}
                out = []
                for j in a.jobs.all():
                    t = present.get(j["id"])
                    out.append({"id": j["id"], "name": j.get("name") or (t or {}).get("name") or j["id"][:12],
                                "added": j.get("added"), "after": j.get("after", "none"),
                                "after_done": j.get("after_done", False), "magnet": j.get("magnet"),
                                "in_client": t is not None, "progress": t["progress"] if t else None,
                                "state": t["state"] if t else None})
                return self.send_json(out)
            m = re.fullmatch(r"/api/torrents/([0-9a-fA-F]+)/after", p)
            if m and method == "POST":
                after = self.body().get("after")
                if after not in AFTER:
                    raise ApiError("after: " + "/".join(AFTER), 400)
                a.jobs.set_after(m.group(1).lower(), after)
                return self.send_json({"ok": True})
            if p == "/api/torrents" and method == "POST":
                b = self.body()
                uri = (b.get("magnet") or "").strip()
                if not uri.startswith("magnet:?"):
                    raise ApiError("to nie jest magnet link", 400)
                tid = a.backend.add_magnet(uri, b.get("sequential", True))
                if tid:
                    a.jobs.add(tid.lower(), uri, b.get("after", "none"))
                log("dodano magnet:", tid)
                return self.send_json({"id": tid})
            m = re.fullmatch(r"/api/torrents/([0-9a-fA-F]+)/files", p)
            if m and method == "GET":
                save_path, files = a.backend.files(m.group(1))
                for f in files:
                    if f["media"] == "video" and f["ready"] > 0:
                        dur = media_duration(os.path.join(save_path, f["path"]))
                        if dur:
                            f["duration"] = dur
                            f["ready_sec"] = dur * f["ready"] / f["size"]
                return self.send_json([{k: v for k, v in f.items() if not k.startswith("_")} for f in files])
            m = re.fullmatch(r"/api/torrents/([0-9a-fA-F]+)/action", p)
            if m and method == "POST":
                a.backend.action(m.group(1), self.body().get("action"))
                return self.send_json({"ok": True})
            m = re.fullmatch(r"/media/([0-9a-fA-F]+)/(\d+)(/.*)?", p)
            if m and method in ("GET", "HEAD"):
                tid, idx = m.group(1), int(m.group(2))
                full, f = a.resolve_file(tid, idx)
                # tylko pobrane kawałki: zamiast zer z niepobranego pliku czekamy na Deluge (patrz stream())
                return self.stream(full, method == "HEAD", f.get("_pieces"),
                                   lambda: a.resolve_file(tid, idx)[1].get("_pieces"))
            if p == "/api/player/open" and method == "POST":
                if not a.is_screen():
                    raise ApiError(f"{a.name()} nie jest ekranem (brak mpv/sesji albo config: player)", 409)
                b = self.body()
                if b.get("url"):  # film z innego serwera (URL z biletem z /api/media/ticket)
                    url = str(b["url"])
                    subs = [str(u) for u in b.get("subs") or [] if str(u).startswith(("http://", "https://"))]
                    if not url.startswith(("http://", "https://")):
                        raise ApiError("url musi być http(s)", 400)
                    a.mpv.open(url, b.get("title"), subs, b.get("start"))
                else:
                    a.mpv.open(a.resolve_ref(b), start=b.get("start"))
                return self.send_json({"ok": True})
            if p == "/api/player/status" and method == "GET":
                return self.send_json(a.mpv.status())
            if p == "/api/player/cmd" and method == "POST":
                b = self.body()
                a.mpv.control(b.get("cmd"), b.get("value"))
                return self.send_json({"ok": True})
            if p == "/api/system/suspend" and method == "POST":
                if not POWER:
                    raise ApiError(f"{a.name()} ma wyłączone usypianie z sieci", 403)
                if a.mpv.running():
                    a.mpv.cmd("quit")
                self.send_json({"ok": True})
                threading.Timer(1.0, lambda: suspend(a.guard)).start()
                return
            if p == "/api/system/screen_on" and method == "POST":
                return self.send_json({"ok": True, "unlocked": wake_screen()})
            if p == "/api/system/unlock" and method == "POST":
                return self.send_json({"ok": True, "unlocked": wake_screen(unlock=True)})
            # ---- biblioteka
            qd = {k: v[0] for k, v in q.items()}
            if p == "/api/library" and method == "GET":
                return self.send_json(a.library.roots())
            if p == "/api/library" and method == "POST":
                b = self.body()
                if b.get("op") == "remove":
                    a.library.remove_root(int(b["index"]))
                else:
                    a.library.add_root(b["path"], b.get("name"))
                return self.send_json(a.library.roots())
            if p == "/api/library/browse" and method == "GET":
                return self.send_json(a.library.browse(int(qd.get("root", 0)), qd.get("path", ""), qd.get("all") == "1"))
            if p == "/api/fs" and method == "GET":
                return self.send_json(a.library.fs_dirs(qd.get("path", "")))
            m = re.fullmatch(r"/lib/(\d+)/(.+)", p)
            if m and method in ("GET", "HEAD"):
                full = a.library.resolve(int(m.group(1)), urllib.parse.unquote(m.group(2)))
                return self.stream(full, method == "HEAD")
            # ---- napisy
            if p == "/api/subs/list" and method == "GET":
                return self.send_json(sidecar_subs(a.resolve_ref(qd)))
            if p == "/sub" and method in ("GET", "HEAD"):
                full = a.resolve_ref(qd)
                names = {s["name"]: s for s in sidecar_subs(full)}
                if qd.get("name") not in names:
                    raise ApiError("nie ma takich napisów", 404)
                if qd.get("vtt") == "1":  # przeglądarki (<track>) rozumieją tylko WebVTT
                    return self.send_vtt(os.path.join(os.path.dirname(full), qd["name"]))
                return self.stream(os.path.join(os.path.dirname(full), qd["name"]), method == "HEAD")
            if p == "/api/subs/search" and method == "POST":
                b = self.body()
                langs = [l.strip().lower() for l in (b.get("languages") or "en,pl").split(",") if l.strip()]
                args = (a.resolve_ref(b), langs, (b.get("title") or "").strip() or None, b.get("year") or None)
                if b.get("async"):  # apka pokazuje postęp, odpytując /api/subs/job
                    return self.send_json({"job": a.subs.start_search(*args)})
                return self.send_json(a.subs.search(*args))
            if p == "/api/subs/job" and method == "GET":
                return self.send_json(a.subs.job(qd.get("id", "")))
            if p == "/api/subs/job/stop" and method == "POST":
                a.subs.stop(self.body().get("id", ""))
                return self.send_json({"ok": True})
            if p == "/api/subs/download" and method == "POST":
                b = self.body()
                full = a.resolve_ref(b)
                langs = [l.strip().lower() for l in (b.get("languages") or "en,pl").split(",") if l.strip()]
                res = a.subs.download(full, str(b["id"]), b["provider"], langs, (b.get("title") or "").strip() or None,
                                      b.get("year") or None)
                if res.get("name") and a.mpv.running() and a.mpv.prop("path") == full:  # gra właśnie ten film — od razu włączamy
                    try:
                        a.mpv.cmd("sub-add", os.path.join(os.path.dirname(full), res["name"]), "select")
                    except ApiError:
                        pass
                return self.send_json(res)
            if p == "/api/config/opensubtitles" and method == "GET":
                return self.send_json({"api_key": bool(a.cfg.get("opensubtitles_api_key")),
                                       "username": a.cfg.get("opensubtitles_username", ""),
                                       "password": bool(a.cfg.get("opensubtitles_password"))})
            if p == "/api/config/opensubtitles" and method == "POST":
                b = self.body()
                a.subs.set_credentials(b.get("api_key"), b.get("username"), b.get("password"))
                return self.send_json({"ok": True})
            raise ApiError("nie ma takiego endpointu", 404)
        except (LibError, SubsError) as e:
            self.send_json({"error": str(e)}, e.status)
        except ApiError as e:
            self.send_json({"error": str(e)}, e.status)
        except (BrokenPipeError, ConnectionResetError):
            pass
        except Exception as e:  # noqa: BLE001
            log("błąd:", repr(e))
            try:
                self.send_json({"error": repr(e)}, 500)
            except OSError:
                pass

    def send_static(self, path, ctype, head_only=False):
        try:
            with open(path, "rb") as f:
                body = f.read()
        except OSError:
            raise ApiError("brak pliku strony — uruchom ponownie install.sh", 404)
        self.send_response(200)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-cache")
        self.end_headers()
        if not head_only:
            self.wfile.write(body)

    def send_vtt(self, path):
        with open(path, "rb") as f:
            raw = f.read()
        for enc in ("utf-8-sig", "cp1250", "latin-1"):
            try:
                text = raw.decode(enc)
                break
            except UnicodeDecodeError:
                continue
        if not path.lower().endswith(".vtt"):
            text = re.sub(r"(\d{2}:\d{2}:\d{2}),(\d{3})", r"\1.\2", text.replace("\r\n", "\n"))
            text = "WEBVTT\n\n" + text
        body = text.encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "text/vtt; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    STALL_WAIT = 20  # s czekania na kolejne kawałki, potem zrywamy połączenie (odtwarzacz ponowi)

    def stream(self, full, head_only, pieces=None, refresh=None):
        # diagnostyka: `touch ~/.config/magneticplayer/debug-stream` loguje każde żądanie (zakres, czas, wysłane bajty)
        if not os.path.exists(os.path.join(os.path.dirname(CONFIG_PATH), "debug-stream")):
            return self._stream(full, head_only, pieces, refresh)
        t0, self._sent = time.time(), 0
        try:
            return self._stream(full, head_only, pieces, refresh)
        finally:
            log("stream", self.client_address[0], self.command, os.path.basename(full)[-30:],
                self.headers.get("Range"), f"{self._sent >> 10} KB", f"{time.time() - t0:.1f}s")

    def _stream(self, full, head_only, pieces=None, refresh=None):
        if not os.path.isfile(full):
            raise ApiError("pliku jeszcze nie ma na dysku", 404)
        size = os.path.getsize(full)
        ctype = mimetypes.guess_type(full)[0] or "application/octet-stream"
        if full.endswith(".apk"):
            ctype = "application/vnd.android.package-archive"
        if full.lower().endswith(".mkv"):
            ctype = "video/x-matroska"
        start, end = 0, size - 1
        rng = self.headers.get("Range")
        status = 200
        if rng:
            m = re.fullmatch(r"bytes=(\d*)-(\d*)", rng.strip())
            if m:
                if m.group(1):
                    start = int(m.group(1))
                    if m.group(2):
                        end = min(int(m.group(2)), size - 1)
                elif m.group(2):
                    start = max(0, size - int(m.group(2)))
                if start > end:
                    self.send_response(416)
                    self.send_header("Content-Range", f"bytes */{size}")
                    self.send_header("Content-Length", "0")
                    self.end_headers()
                    return
                status = 206
        length = end - start + 1
        self.send_response(status)
        self.send_header("Content-Type", ctype)
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Length", str(length))
        if status == 206:
            self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        # do którego bajtu da się czytać od `start` bez czekania (bufor na dysku w apce dociąga tylko tyle)
        self.send_header("X-Ready-End", str(ready_end(pieces, start, size)))
        self.end_headers()
        if head_only:
            return
        pos, stop = start, end + 1
        with open(full, "rb") as f:
            f.seek(start)
            while pos < stop:
                avail = ready_end(pieces, pos, size)
                waited = 0
                while avail <= pos and refresh and waited < self.STALL_WAIT:
                    time.sleep(1)
                    waited += 1
                    pieces = refresh()
                    avail = ready_end(pieces, pos, size)
                if avail <= pos:
                    self.close_connection = True  # krócej niż Content-Length: klient wie, że ma ponowić
                    return
                chunk = f.read(min(1 << 20, avail - pos, stop - pos))
                if not chunk:
                    break
                self.wfile.write(chunk)
                pos += len(chunk)
                self._sent = getattr(self, "_sent", 0) + len(chunk)


class QuietServer(ThreadingHTTPServer):
    daemon_threads = True

    def handle_error(self, request, client_address):
        if isinstance(sys.exc_info()[1], (ConnectionResetError, BrokenPipeError, TimeoutError)):
            return  # telefon zerwał połączenie (przewijanie, zamknięcie playera) — normalne
        super().handle_error(request, client_address)


def lan_ip():
    """Adres w sieci lokalnej (bez wysyłania czegokolwiek: connect na UDP tylko wybiera trasę)."""
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(("192.168.0.1", 9))
            return s.getsockname()[0]
    except OSError:
        return socket.gethostbyname(socket.gethostname())


def cli_pair(cfg, devices):
    """Kod parowania dla pierwszego telefonu (albo gdy nie ma pod ręką sparowanego): QR w terminalu."""
    code, ttl = devices.new_code()
    host = lan_ip()
    name = cfg.get("name") or socket.gethostname()
    link = "magneticplayer://pair?" + urllib.parse.urlencode(
        {"host": host, "port": cfg["port"], "code": code, "id": cfg["id"], "name": name})
    print(f"Sparuj telefon z „{name}”: w apce MagneticPlayer Ustawienia → „Dodaj serwer”,")
    print(f"zeskanuj kod aparatem albo wpisz ręcznie. Kod działa raz, przez {ttl // 60} min.\n")
    if shutil.which("qrencode"):
        subprocess.run(["qrencode", "-t", "ANSIUTF8", link])
    else:
        print("(QR w terminalu po: sudo apt install qrencode)")
    print(f"  adres: {host}   port: {cfg['port']}   kod: {code[:3]}-{code[3:]}")
    print(f"  link:  {link}")
    print(f"  przeglądarka: http://{host}:{cfg['port']}/#p={code}")


def main():
    cfg = load_config()
    if "--print-token" in sys.argv:
        print(cfg["token"])
        return
    devices = Devices(os.path.join(CONFIG_DIR, "devices.json"), cfg["token"])
    if "--pair" in sys.argv:
        return cli_pair(cfg, devices)
    if "--devices" in sys.argv:
        for d in devices.list():
            seen = time.strftime("%Y-%m-%d %H:%M", time.localtime(d["last_seen"])) if d.get("last_seen") else "nigdy"
            print(f"{d['id']:14} {d['name']:30} ostatnio: {seen}")
        return
    if "--revoke" in sys.argv:
        i = sys.argv.index("--revoke")
        dev = sys.argv[i + 1] if i + 1 < len(sys.argv) else ""
        print("odebrano dostęp" if devices.revoke(dev) else f"nie ma urządzenia {dev!r} (lista: --devices)")
        return
    app = App(cfg)
    Handler.app = app
    if cfg.get("keep_awake", True):
        app.guard.start()
    srv = QuietServer(("0.0.0.0", int(cfg["port"])), Handler)
    srv.daemon_threads = True
    log(f"MagneticPlayer {VERSION} na porcie {cfg['port']}, backend {cfg['backend']}")
    srv.serve_forever()


if __name__ == "__main__":
    main()
