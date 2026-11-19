#!/usr/bin/env python3
"""MagneticPlayer: the agent running on the computer (Linux Mint, also Windows).

HTTP API for the phone app:
  - adding magnet links to Deluge (deluge-web JSON-RPC) or Transmission (RPC),
  - list of torrents and files, streaming files (HTTP Range) to the player on the phone,
  - remote control of mpv on the computer (JSON IPC): play/pause, seek, fullscreen, volume, subtitles,
  - sleep inhibition while something is downloading or a video is playing; suspend on request.

Python standard library only. Configuration: ~/.config/magneticplayer/config.json
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

import mp_media
import mp_platform as platform
from mp_cast import Casts, CastError
import mp_renderers
from mp_announce import Announcer
import mp_upload
import mp_install
import mp_access
import mp_appupdate
import mp_selfupdate
import mp_tasks
import mp_mcp
import mp_i18n
from mp_i18n import T, bind

# DLNA: byte seeking (OP=01), no conversion, streaming + background (FLAGS)
DLNA_FEATURES = "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"
from mp_devices import Devices
from mp_jobs import AFTER, Jobs, magnet_name
import mp_catalog
import mp_ident
import mp_library
from mp_library import Library, LibError, media_kind, sidecar_subs
from mp_subs import Subtitles, SubsError, readable_srt
import mp_subsync
import mp_segments
import mp_subfiles

VERSION = "1.12.0"
API_LEVEL = 2  # /api/info: id, features, power; device tokens, tickets, player/open with a url
CONFIG_PATH = os.environ.get("MAGNETICPLAYER_CONFIG") or os.path.expanduser("~/.config/magneticplayer/config.json")
CONFIG_DIR = os.path.dirname(CONFIG_PATH)
WEB_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "web")  # web client (server/web)
APK_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "apk")  # publish.sh puts the APK here


def apk_version():
    """Version of the app published on the computer (phone/tools/publish.sh); phones update themselves."""
    try:
        with open(os.path.join(APK_DIR, "version.json")) as f:
            v = json.load(f)
        if os.path.isfile(os.path.join(APK_DIR, "MagneticPlayer.apk")):
            return v
    except (OSError, ValueError):
        pass
    return {"version_code": 0}
WINDOWS = os.name == "nt"
if WINDOWS:  # mpv on Windows: IPC over a named pipe instead of a unix socket
    RUNTIME_DIR = os.path.join(os.environ.get("TEMP") or os.path.expanduser("~"), "magneticplayer")
    MPV_SOCKET = r"\\.\pipe\magneticplayer-mpv"
else:
    RUNTIME_DIR = os.environ.get("XDG_RUNTIME_DIR") or f"/tmp/magneticplayer-{os.getuid()}"
    MPV_SOCKET = os.path.join(RUNTIME_DIR, "magneticplayer-mpv.sock")
VIDEO_EXT = {".mkv", ".mp4", ".avi", ".m4v", ".mov", ".webm", ".wmv", ".mpg", ".mpeg", ".ts", ".m2ts", ".flv", ".ogv"}
AUDIO_EXT = {".mp3", ".flac", ".m4a", ".ogg", ".opus", ".wav", ".aac"}

DEFAULT_CONFIG = {
    "port": 8765,
    "token": "",
    "id": "",  # stable server identifier (the app recognises a server by it, not by IP)
    "name": "",  # name shown in the app; empty = hostname
    "backend": "deluge",  # deluge | transmission | none (a server without torrents, e.g. screen only)
    "deluge_url": "http://127.0.0.1:8112/json",
    "deluge_password": "deluge",
    "transmission_url": "http://127.0.0.1:9091/transmission/rpc",
    "transmission_user": "",
    "transmission_password": "",
    "keep_awake": True,
    # unlock the lock screen on "On the computer" and "Turn on screen"; anyone with a token can then unlock the desktop
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


# Guest (role "guest", mp_devices.py): watching only. An allow list, not a deny list, so new endpoints
# stay closed to guests until someone deliberately adds them here.
GUEST_ALLOWED = {
    "GET": ("/api/info", "/api/announce", "/api/app/version", "/app/MagneticPlayer.apk", "/api/library",
            "/api/library/browse", "/api/library/episodes", "/api/library/segments", "/api/library/search", "/api/library/cover", "/lib/", "/media/", "/sub", "/api/subs/list", "/api/subs/job", "/api/media/probe",
            "/api/media/converts", "/api/jobs", "/api/player/status", "/api/cast/status", "/api/cast/devices",
            "/api/renderers", "/api/torrents"),
    "POST": ("/api/media/ticket", "/api/player/open", "/api/player/cmd", "/api/cast/play", "/api/cast/cmd",
             "/api/subs/search", "/api/subs/download", "/api/subs/job/stop", "/api/system/screen_on", "/api/wake",
             "/api/media/convert"),
}


def guest_allowed(method, p):
    allowed = GUEST_ALLOWED.get("GET" if method == "HEAD" else method, ())
    if p.startswith("/api/torrents/"):  # the torrent's file list is allowed, actions (start, remove, "after download") are not
        return method in ("GET", "HEAD") and p.endswith("/files")
    return any(p == a or (a.endswith("/") and p.startswith(a)) for a in allowed)


class ApiError(Exception):
    def __init__(self, msg, status=500):
        super().__init__(msg)
        self.status = status


# ---------------------------------------------------------------- torrent backends

def is_media(path):
    ext = os.path.splitext(path)[1].lower()
    return "video" if ext in VIDEO_EXT else "audio" if ext in AUDIO_EXT else None


def ready_end(pieces, start, size):
    """Up to which byte (exclusive) the file is downloaded contiguously from `start`. pieces = (offset of the file
    in the torrent, piece size, list of downloaded pieces or None when the torrent is complete)."""
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
    """How many bytes of the file are downloaded contiguously from its start (that much can be watched without gaps)."""
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
    """Video length in seconds, or None (then a read starts in the background: mpv reads the header, even of an incomplete file)."""
    with _durations_lock:
        if path in _durations:
            dur, when = _durations[path]
            if dur is not None or when == 0 or time.time() - when < 60:  # when == 0: read in progress
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
            raise ApiError(T(f"deluge-web unavailable ({self.url}): {e}", f"deluge-web niedostępny ({self.url}): {e}"), 502)
        if data.get("error"):
            err = data["error"]
            raise ApiError(f"deluge: {err.get('message', err)}", 502 if err.get("code") != 1 else 401)
        return data["result"]

    def _ensure(self):
        if not self._raw("auth.check_session", []):
            if not self._raw("auth.login", [self.password]):
                raise ApiError(T("deluge-web: wrong password (deluge_password in config.json)",
                                 "deluge-web: złe hasło (deluge_password w config.json)"), 502)
        if not self._raw("web.connected", []):
            hosts = self._raw("web.get_hosts", [])
            if not hosts:
                raise ApiError(T("deluge-web: no deluged hosts", "deluge-web: brak hostów deluged"), 502)
            local = [h for h in hosts if h[1] in ("127.0.0.1", "localhost")] or hosts
            self._raw("web.connect", [local[0][0]])
            for _ in range(20):
                if self._raw("web.connected", []):
                    break
                time.sleep(0.25)
            else:
                raise ApiError(T("deluge-web didn't connect to deluged (is deluged running?)",
                                 "deluge-web nie połączył się z deluged (czy deluged działa?)"), 502)

    def call(self, method, *params):
        with self.lock:
            try:
                self._ensure()
                return self._raw(method, list(params))
            except ApiError as e:
                if e.status == 401:  # session expired
                    self._raw("auth.login", [self.password])
                    return self._raw(method, list(params))
                raise

    def default_dirs(self):
        c = self.call("core.get_config_values", ["download_location", "move_completed_path"])
        dirs = [c.get("download_location"), c.get("move_completed_path")]
        return dirs + sorted({t["save_path"] for t in self.list()})

    def add_magnet(self, uri, sequential, dest=None):
        opts = {"sequential_download": bool(sequential), "prioritize_first_last_pieces": bool(sequential)}
        if dest:
            opts["download_location"] = dest
        m = re.search(r"btih:([0-9a-fA-F]{40})", uri)
        if m and self.call("core.get_torrent_status", m.group(1).lower(), ["name"]):
            return m.group(1).lower()  # already in the client: with download_location Deluge raises instead of returning None
        tid = self.call("core.add_torrent_magnet", uri, opts)
        if not tid:  # Deluge returns None when the torrent is already in the list
            m = re.search(r"btih:([0-9a-fA-F]{40})", uri)
            tid = m.group(1).lower() if m else None
        return tid

    def move(self, tid, dest):
        self.call("core.move_storage", [tid], dest)

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
            raise ApiError(T("no such torrent", "nie ma takiego torrenta"), 404)
        prog = st.get("file_progress") or []
        pieces = st.get("pieces")  # None when the torrent is complete; otherwise piece state, 3 = downloaded
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
            raise ApiError(T(f"unknown action {action}", f"nieznana akcja {action}"), 400)


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
                raise ApiError(T(f"transmission unavailable ({self.url}): {e}", f"transmission niedostępny ({self.url}): {e}"), 502)
            if data.get("result") != "success":
                raise ApiError(f"transmission: {data.get('result')}", 502)
            return data.get("arguments", {})
        raise ApiError(T("transmission: no session", "transmission: brak sesji"), 502)

    def default_dirs(self):
        return [self.call("session-get", {"fields": ["download-dir"]}).get("download-dir")] + \
            sorted({t["save_path"] for t in self.list()})

    def add_magnet(self, uri, sequential, dest=None):
        args = {"filename": uri}
        if dest:
            args["download-dir"] = dest
        if sequential:
            args["sequential_download"] = True  # Transmission >= 4.1, older versions ignore it
        r = self.call("torrent-add", args)
        t = r.get("torrent-added") or r.get("torrent-duplicate") or {}
        return t.get("hashString")

    def move(self, tid, dest):
        self.call("torrent-set-location", {"ids": [tid], "location": dest, "move": True})

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
            raise ApiError(T("no such torrent", "nie ma takiego torrenta"), 404)
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
            raise ApiError(T(f"unknown action {action}", f"nieznana akcja {action}"), 400)
        method, args = m[action]
        self.call(method, dict(args, ids=[tid]))


class NoBackend:
    """backend: none, a server without a torrent client (e.g. screen only or library only)."""

    def __init__(self, cfg):
        pass

    def default_dirs(self):
        return []

    def list(self):
        return []

    def add_magnet(self, uri, sequential, dest=None):
        raise ApiError(T("this server doesn't download torrents", "ten serwer nie pobiera torrentów"), 409)

    def move(self, tid, dest):
        raise ApiError(T("this server doesn't download torrents", "ten serwer nie pobiera torrentów"), 409)

    def files(self, tid):
        raise ApiError(T("this server doesn't download torrents", "ten serwer nie pobiera torrentów"), 409)

    def action(self, tid, action):
        raise ApiError(T("this server doesn't download torrents", "ten serwer nie pobiera torrentów"), 409)


# ---------------------------------------------------------------- graphical session, mpv

graphical_env = platform.graphical_env
AUTO_UNLOCK = True  # overridden from config in App.__init__
POWER = True  # config "power": false: no suspend or power-off over the network (e.g. a work laptop)


def wake_screen(unlock=None):
    return platform.wake_screen(AUTO_UNLOCK if unlock is None else unlock)


def socket_exists():
    """Whether mpv has created the IPC socket. On Windows via the pipe list: os.path.exists on a pipe connects to it
    and can return False when all instances are busy."""
    if WINDOWS:
        return os.path.basename(MPV_SOCKET) in os.listdir("\\\\.\\pipe\\")
    return os.path.exists(MPV_SOCKET)


class Mpv:
    def __init__(self, cfg):
        self.args = cfg.get("mpv_args", [])
        self.proc = None
        self.lock = threading.Lock()
        self.req = 0
        self.pipe, self.pipe_buf, self.pipe_lock = None, b"", threading.Lock()  # Windows (_cmd_pipe)

    def running(self):
        if self.proc is not None:
            return self.proc.poll() is None
        # mpv from before the agent restart (it lives in a separate systemd scope): running if its socket answers
        if WINDOWS:  # os.path.exists on a pipe can be False when all its instances are busy
            return self.pipe is not None or socket_exists()
        s = socket.socket(socket.AF_UNIX)
        s.settimeout(0.3)
        try:
            s.connect(MPV_SOCKET)
            return True
        except OSError:
            return False
        finally:
            s.close()

    def cmd(self, *command, timeout=3):
        if not self.running():
            raise ApiError(T("mpv isn't running", "mpv nie działa"), 409)
        with self.lock:
            self.req += 1
            rid = self.req
        if WINDOWS:
            return self._cmd_pipe(command, rid, timeout)
        s = socket.socket(socket.AF_UNIX)
        s.settimeout(timeout)
        try:
            s.connect(MPV_SOCKET)
            s.sendall(json.dumps({"command": list(command), "request_id": rid}).encode() + b"\n")
            buf = b""
            while True:
                chunk = s.recv(65536)
                if not chunk:
                    raise ApiError(T("mpv closed the connection", "mpv zamknął połączenie"), 502)
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

    @staticmethod
    def _answer(buf, rid):
        while b"\n" in buf:
            line, buf = buf.split(b"\n", 1)
            msg = json.loads(line)
            if msg.get("request_id") == rid:
                if msg.get("error") not in ("success", None):
                    raise ApiError(f"mpv: {msg['error']}", 400)
                return True, msg.get("data"), buf
        return False, None, buf

    def _cmd_pipe(self, command, rid, timeout):
        """Windows: mpv's named pipe. One persistent connection: mpv takes up to 3 s to accept a new one, and the Remote polls 4x/s.
        Reading from the pipe has no timeout, so it runs in a thread; after an error or no answer we reconnect."""
        out = {}

        def talk():
            try:
                with self.pipe_lock:
                    if self.pipe is None:
                        self.pipe = open(MPV_SOCKET, "r+b", buffering=0)
                    f = out["f"] = self.pipe
                    f.write(json.dumps({"command": list(command), "request_id": rid}).encode() + b"\n")
                    if command[0] == "quit":  # mpv won't exit while a client holds the pipe: disconnect right away
                        self._drop_pipe()
                        return
                    buf = self.pipe_buf
                    while True:
                        done, data, buf = self._answer(buf, rid)
                        if done:
                            if self.pipe is f:
                                self.pipe_buf = buf
                            out["data"] = data
                            return
                        chunk = f.read(65536)
                        if not chunk:
                            raise OSError(T("mpv closed the connection", "mpv zamknął połączenie"))
                        buf += chunk
            except ApiError as e:
                out["err"] = e
            except OSError as e:
                with self.pipe_lock:
                    if self.pipe is out.get("f"):  # after a timeout this may already be a new connection
                        self._drop_pipe()
                out["err"] = ApiError(f"mpv IPC: {e}", 502)

        t = threading.Thread(target=talk, daemon=True)
        t.start()
        t.join(timeout)
        if t.is_alive():
            # don't close from this thread (CloseHandle during a pending read can hang): detach, the thread will finish by itself
            self.pipe, self.pipe_buf = None, b""
            raise ApiError(T("mpv IPC: no response", "mpv IPC: brak odpowiedzi"), 502)
        if "err" in out:
            raise out["err"]
        return out.get("data")

    def _drop_pipe(self):
        f, self.pipe, self.pipe_buf = self.pipe, None, b""
        if f is not None:
            try:
                f.close()
            except OSError:
                pass

    def prop(self, name, default=None):
        try:
            return self.cmd("get_property", name)
        except ApiError:
            return default

    def open(self, path, title=None, subs=(), start=None):
        """A local file or a URL (a video from another server, with a ticket). `subs`: subtitle URLs, the first one is selected."""
        wake_screen()
        started = False
        if self.running():
            try:
                self.cmd("set_property", "force-media-title", title or "")
                self.cmd("loadfile", path, "replace")
                self.cmd("set_property", "pause", False)
                started = True
            except ApiError:
                # mpv is just closing ("Stop" a moment ago): wait until it is gone and start a new one
                for _ in range(50):
                    if not self.running():
                        break
                    time.sleep(0.1)
                else:
                    raise
        if not started:
            self._start(path, title)
        if subs or start:
            self._after_load(path, subs, start)

    def _after_load(self, path, subs, start):
        """Waits until mpv has loaded the file (over the network it takes a while), then subtitles and seeking."""
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
                log("subtitles from", u.split("?")[0], e)
        if start:
            self.cmd("seek", float(start), "absolute")

    def _start(self, path, title):
        mpv = platform.mpv_path()
        if not mpv:
            raise ApiError(T("mpv isn't installed on the computer (sudo apt install mpv)", "brak mpv na komputerze (sudo apt install mpv)"), 500)
        if WINDOWS:
            with self.pipe_lock:
                self._drop_pipe()  # the old connection belongs to the previous mpv
        else:
            try:
                os.unlink(MPV_SOCKET)
            except FileNotFoundError:
                pass
        argv = [mpv, f"--input-ipc-server={MPV_SOCKET}", "--force-window=yes", "--idle=once",
                "--keep-open=yes", *self.args, *([f"--force-media-title={title}"] if title else []), path]
        # separate systemd scope: restarting/updating the agent doesn't close the video (otherwise systemd kills the whole service)
        if shutil.which("systemd-run") and os.environ.get("XDG_RUNTIME_DIR"):
            argv = ["systemd-run", "--user", "--scope", "--quiet", "--collect", "--unit", f"magneticplayer-mpv-{os.getpid()}-{int(time.time())}", *argv]
        log("starting:", " ".join(re.sub(r"(ticket|token)=[^&\s]+", r"\1=…", a) for a in argv))
        extra = ({"creationflags": subprocess.CREATE_NEW_PROCESS_GROUP | subprocess.CREATE_BREAKAWAY_FROM_JOB}
                 if WINDOWS else {"start_new_session": True})
        self.proc = subprocess.Popen(argv, env=graphical_env(), stdin=subprocess.DEVNULL,
                                     stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, **extra)
        for _ in range(50):
            if socket_exists():
                return
            if not self.running():
                raise ApiError(T("mpv didn't start (is there a graphical session?)", "mpv nie wystartował (czy jest sesja graficzna?)"), 500)
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
        """Video/audio/subtitle tracks (embedded and external) to choose from in the remote."""
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
                raise ApiError(T("invalid track type", "zły typ ścieżki"), 400)
            tid = value.get("id")
            self.cmd("set_property", prop, "no" if tid in (None, "no") else int(tid))
        elif c == "sub_delay":
            self.cmd("add", "sub-delay", float(value))
        elif c == "stop":
            self.cmd("quit")
        else:
            raise ApiError(T(f"unknown command {c}", f"nieznane polecenie {c}"), 400)


# ---------------------------------------------------------------- sleep inhibition

class SleepGuard(threading.Thread):
    """Holds systemd-inhibit (sleep) while something is downloading or mpv is playing."""

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
            log("sleep inhibited:", why)

    def release(self):
        if self.proc and self.proc.poll() is None:
            self.proc.terminate()
            log("sleep inhibition released")
        self.proc = None

    def run(self):
        while True:
            try:
                why = None
                if time.time() >= self.paused_until:
                    if self.app.mpv.running() and self.app.mpv.prop("pause") is False:
                        why = "playback"
                    else:
                        active = [t for t in self.app.backend.list()
                                  if t["state"] in ("Downloading", "Checking", "Allocating", "Moving")]
                        if active:
                            why = f"downloading ({len(active)})"
                if why:
                    self.hold(why)
                else:
                    self.release()
                self.reason = why or ""
                playing = self.app.mpv.running() and self.app.mpv.prop("pause") is False
                self.app.jobs.tick(self.app.backend.list(), playing, self.app.machine_action)
            except Exception as e:  # noqa: BLE001 — a background thread must not die
                log("SleepGuard:", e)
            time.sleep(20)


def poweroff(guard):
    if not POWER:
        raise ApiError(T("this server has power-off over the network disabled (config: power)",
                   "ten serwer ma wyłączone wyłączanie z sieci (config: power)"), 403)
    guard.paused_until = time.time() + 120
    guard.release()
    err = platform.power("poweroff")
    if err:
        raise ApiError(T(f"power-off failed: {err}", f"wyłączanie nie wyszło: {err}"), 500)


def suspend(guard):
    if not POWER:
        raise ApiError(T("this server has sleep over the network disabled (config: power)",
                   "ten serwer ma wyłączone usypianie z sieci (config: power)"), 403)
    guard.paused_until = time.time() + 120
    guard.release()
    err = platform.power("suspend")
    if err:
        raise ApiError(T(f"sleep failed: {err}", f"usypianie nie wyszło: {err}"), 500)


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
        self.subsync = mp_subsync.Store(CONFIG_DIR, log)
        self.subsources = mp_subfiles.Sources(CONFIG_DIR)  # where each subtitle file came from (agent 1.12+)
        self.segments = mp_segments.Segments(log)
        self.jobs = Jobs(os.path.join(CONFIG_DIR, "jobs.json"), log)
        self.devices = Devices(os.path.join(CONFIG_DIR, "devices.json"), cfg["token"])
        self._subs_ok = None
        self.convert = mp_media.Converter(log, os.path.join(CONFIG_DIR, "encodes"))
        self.renderers = mp_renderers.Renderers(os.path.join(CONFIG_DIR, "renderers.json"), log)
        self.casts = Casts(log, self.renderers)
        # announcing "what is playing" between remotes (LAN multicast): this server's mpv + a list from others for the page
        self.announce = Announcer(log, lambda: {"id": self.cfg["id"], "name": self.name()}, self._announce_mine)
        self.announce.start()
        # uploading videos from the phone, with a history of what landed where (Downloads screen)
        self.uploads = mp_upload.Uploads(cfg, self.library, log, os.path.join(CONFIG_DIR, "uploads.json"))
        # magnet identification (movie/series/kids' show → folder): a small IMDb index refreshed in the background + Wikidata
        self.ident = mp_ident.Service(cfg, os.path.join(CONFIG_DIR, "ident-memory.json"), log)
        self.ident.start()
        # the library as films and series (genres, age, languages, people, topics; Wikidata by IMDb id), shared wishlist
        self.catalog = mp_catalog.Catalog(os.path.join(CONFIG_DIR, "catalog.json"), cfg, self.library, self.ident, log)
        self.catalog.start()
        mp_library.ON_CHANGE.append(self.catalog.refresh)
        self.wishlist = mp_catalog.Wishlist(os.path.join(CONFIG_DIR, "wishlist.json"))
        self.installs = mp_install.Installs(log)  # installation on another computer (join code)
        self.access = mp_access.AccessRequests(log)  # access requests from the home network (mp_access.py)
        # app for phones from GitHub releases (mp_appupdate.py); the phone sets the mode
        self.app_updates = mp_appupdate.AppUpdates(cfg, lambda: save_config(cfg), APK_DIR, apk_version, log)
        self.app_updates.start()
        # the agent itself from GitHub releases (mp_selfupdate.py): installed when nobody is streaming or converting
        self.streams, self.stream_last, self.stream_lock = 0, 0.0, threading.Lock()
        self.watch = {}  # real path of a video → {active GETs, last request, last GET} (see watched())
        # background file tasks: moving between disks, shrinking by storage profiles (mp_tasks.py); encodes pause while
        # somebody streams from the library
        self.tasks = mp_tasks.Tasks(os.path.join(CONFIG_DIR, "tasks.json"), cfg, lambda: save_config(cfg), self.library,
                                    lambda: self.backend, self.watched,
                                    self.renderers, log)
        self.tasks.subsync = self.subsync
        self.agent_updates = mp_selfupdate.AgentUpdates(cfg, lambda: save_config(cfg), os.path.dirname(os.path.abspath(__file__)),
                                                        VERSION, cfg["port"], self.busy, log)
        self.agent_updates.start()
        # MCP server for an AI assistant in the LAN, approved on the phone (mp_mcp.py)
        self.mcp = mp_mcp.Mcp(self, VERSION, CONFIG_DIR, log)
        # discover Chromecasts from startup: the renderer registry knows the model before anyone opens the device picker
        threading.Thread(target=self._cast_warmup, daemon=True).start()
        # which H.264 encoder works on this hardware (NVENC/VAAPI/...): a trial encode takes seconds, so in the background
        threading.Thread(target=mp_media.detect_encoder, daemon=True).start()
        # HEVC encoders for shrinking (processor and graphics card, with a speed trial): after the one above
        threading.Thread(target=mp_media.detect_hevc, daemon=True).start()
        # importing subliminal is slow (~30 s on a fresh Windows): in the background so the first /api/info (pairing) doesn't wait
        threading.Thread(target=self.subs_available, daemon=True).start()

    def name(self):
        return self.cfg.get("name") or socket.gethostname()

    def watched(self):
        """Videos in use now, for shrink tasks: [{"path", "kind"}]. A GET in the last 30 s = stream; only HEADs in the
        last 60 s = probe (a phone playing its own copy still asks about the file); mpv on this screen; a conversion."""
        now, out = time.time(), []
        with self.stream_lock:
            for path, w in list(self.watch.items()):
                if w["active"] or now - w["read"] < 30:
                    out.append({"path": path, "kind": "stream"})
                elif now - w["last"] < 60:
                    out.append({"path": path, "kind": "probe"})
                elif now - w["last"] > 3600:
                    del self.watch[path]
        try:
            if self.mpv.running() and (path := self.mpv.prop("path")) and os.path.isfile(path):
                out.append({"path": os.path.realpath(path), "kind": "mpv"})
        except Exception:  # noqa: BLE001 — mpv just ending
            pass
        out += [{"path": j["path"], "kind": "convert"} for j in self.convert.all() if j["state"] in ("queued", "running")]
        return out

    def fit_subs(self, full, name):
        """Fits a subtitle file next to `full` to the film's speech (mp_subsync). A file of a seeded torrent is only
        assessed. If mpv plays this video, a changed file is loaded again."""
        path = os.path.join(os.path.dirname(full), name)
        locked = self.tasks.torrent_owned(path)
        try:
            res = self.subsync.fit(full, path, change=not locked)
        except (mp_subsync.SyncError, OSError, ValueError) as e:
            log("subtitle fitting:", name, e)
            return {"verdict": "error", "error": str(e), "name": name}
        res["locked"] = locked
        if res.get("changed"):
            try:
                if self.mpv.running() and self.mpv.prop("path") == full:
                    new = os.path.join(os.path.dirname(full), res["name"])
                    self.mpv.cmd("sub-add", new, "select")
            except ApiError:
                pass
        return res

    def busy(self):
        """Why a restart (self-update) should wait now, or None: a stream or upload in the last 5 minutes, a queued
        conversion, a copy or move. Running encodes are detached (mp_detached) and survive the restart."""
        with self.stream_lock:
            if self.streams or time.time() - self.stream_last < 300:
                return "stream"
        if any(j["state"] == "queued" for j in self.convert.all()):
            return "conversion"
        if self.tasks.busy():
            return "task"
        return None

    def wake_info(self, net):
        """Assessment from mp_platform + "yes" once wake over the network has worked (on the same NIC)."""
        if self.cfg.get("wake") is False:  # lite mode: someone else's computer, nobody wakes it over the network
            return {"method": None, "mac": None, "confidence": "no", "reason": T("wake disabled (lite install)", "budzenie wyłączone (instalacja lekka)")}
        w = platform.wake_info(net)
        v = self.cfg.get("wake_verified") or {}
        if v.get("at") and w.get("mac") and v.get("mac") in (None, w["mac"]) and not net.get("wireless"):
            day = time.strftime("%d.%m.%Y", time.localtime(v["at"]))
            w = {**w, "confidence": "yes", "reason": T(f"wake over the network worked ({day})", f"budzenie przez sieć zadziałało ({day})"), "verified_at": v["at"]}
        return w

    def is_screen(self):
        """Whether playback is allowed here: config "player" (default yes) + mpv and a graphical session."""
        return bool(self.cfg.get("player", True)) and platform.has_display()

    def subs_available(self, wait=True):
        if self._subs_ok is None and not wait:  # the import is still running: it is enough that the package exists
            import importlib.util
            from mp_subs import add_venv_path
            add_venv_path()
            return importlib.util.find_spec("subliminal") is not None
        if self._subs_ok is None:
            try:
                from mp_subs import _subliminal
                _subliminal()
                self._subs_ok = True
            except Exception:  # noqa: BLE001 — no venv/subliminal
                self._subs_ok = False
        return self._subs_ok

    def info(self, client_ip, device, role="owner"):
        """Who this server is and what it can do (the app hides what is missing; what a guest may not do is removed for them)."""
        out = self._info(client_ip, device)
        out["role"] = role
        if role == "guest":
            out["features"].update(upload=False, install=False, unlock=False, apk=out["features"]["apk"])
            out["power"].update(suspend=False, poweroff=False)
        return out

    def _info(self, client_ip, device):
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
                "library_tags": True,  # folder tags, visibility and exclusions (agent 1.4+)
                "library_search": True,  # GET /api/library/search (agent 1.5+)
                "library_guess": True,  # magnet identification and downloading straight into a folder (agent 1.5+)
                # disks, background tasks (moving, shrinking), storage profiles, trash (agent 1.6+)
                "library_tasks": True,
                "shrink_encoder": self.cfg.get("shrink_encoder") or "auto",
                "shrink_while_watching": self.cfg.get("shrink_while_watching") or "continue",
                "library_episodes": True, "segments": mp_segments.available(), "subs_versions": True,  # intro / credits (agent 1.11+)
                "library_covers": True,  # GET /api/library/cover (agent 1.6+)
                # /api/catalog, /api/wishlist, /api/uploads, /api/config/search (agent 1.8+)
                "catalog": True,
                "library_modernize": True,  # POST /api/library/plan {"kind": "modernize"} (agent 1.8.2+)
                "subs_fit": mp_subsync.available(),  # subtitles fitted to the film's speech (agent 1.9+)
                "search_engines": len(self.cfg.get("search_engines") or []),
                "library_profiles": list(mp_tasks.PROFILE_NAMES),
                "player": self.is_screen(),
                "subs_search": self.subs_available(wait=False),
                "unlock": AUTO_UNLOCK and platform.can_unlock(),
                "apk": apk_version().get("version_code", 0) > 0,
                "app_updates": self.app_updates.mode(),  # "auto" (from GitHub) or "manual" (publish.sh)
                # the agent's own updates (agent 1.6+): mode, or absent where unsupported (Windows); newer version if any
                **({"agent_updates": self.agent_updates.mode(), "agent_available": self.agent_updates.available or ""}
                   if self.agent_updates.supported() else {}),
                "wake_others": True,
                # the "for Chromecast" version: encoder name (hardware or CPU) or None (no ffmpeg)
                "transcode": mp_media.encoder_label(mp_media.encoder_info()),
                "transcode_hw": bool((mp_media.encoder_info() or {}).get("hw")),
                "upload": self.uploads.available(),  # PUT /api/upload/<id> (mp_upload.py)
                "install": self.installs.available(),  # /api/install/* and /i/<code> (mp_install.py)
                "access_requests": True,  # /api/access/* (mp_access.py)
                "mcp": True,  # /mcp for an AI assistant, OAuth approved on the phone (agent 1.6+)
            },
            "power": {"suspend": POWER and platform.can_power(), "poweroff": POWER and platform.can_power(),
                      "wake": self.wake_info(net)},
        }

    def ref_scope(self, d):
        """Ticket scope for a file reference (the same for a video and its subtitles)."""
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
        """A file given by a torrent (torrent_id + file_index) or by the library (root + path)."""
        if d.get("playing") in (True, "1", "true"):  # the file currently playing in mpv
            path = self.mpv.prop("path") if self.mpv.running() else None
            if not path:
                raise ApiError(T("nothing is playing on the computer", "na komputerze nic nie gra"), 409)
            if not os.path.isfile(path):
                raise ApiError(T("the video is playing from another server, search for subtitles there (in Library or at the torrent)",
                                 "film gra z innego serwera, napisy szukaj tam (w Bibliotece albo przy torrencie)"), 409)
            return path
        if d.get("torrent_id"):
            return self.resolve_file(d["torrent_id"], int(d["file_index"]))[0]
        if d.get("root") is not None and d.get("root") != "":
            return self.library.resolve(int(d["root"]), d.get("path", ""))
        raise ApiError(T("no file specified", "brak wskazania pliku"), 400)

    def ref_complete(self, d):
        """Whether the file is entirely on disk (torrent downloaded), since converting an incomplete one would produce garbage."""
        if d.get("torrent_id"):
            _, f = self.resolve_file(d["torrent_id"], int(d["file_index"]))
            return f.get("ready", 0) >= f.get("size", 1) or f.get("progress", 0) >= 100
        return True

    def _announce_mine(self):
        """What is playing on this server's screen (mpv), as a "playing" message for other remotes."""
        if not self.is_screen() or not self.mpv.running():
            return []
        st = self.mpv.status()
        if not st.get("running") or st.get("idle"):
            return []
        return [{"type": "playing", "target": {"kind": "screen", "id": self.cfg["id"], "name": self.name(),
                                                "ctl": {"agent": f"http://{lan_ip()}:{self.cfg['port']}"}},
                 "title": st.get("title") or "", "state": "PAUSED" if st.get("paused") else "PLAYING",
                 "pos": st.get("pos") or 0, "dur": st.get("duration") or 0, "ts": int(time.time())}]

    def _cast_warmup(self):
        try:
            self.casts.devices()
            time.sleep(10)
            self.casts.devices()  # devices that answered later
        except Exception as e:  # noqa: BLE001  (e.g. no pychromecast)
            log("chromecast: discovery failed to start:", e)

    def ticket_urls(self, b, base, variant=None):
        """URLs of the video and subtitles with a ticket for another device (screen, Chromecast, DLNA TV)."""
        full = self.resolve_ref(b)
        scope = self.ref_scope(b)
        if not scope:
            raise ApiError(T("tickets only for a file from a torrent or the library", "bilet tylko na plik z torrenta albo biblioteki"), 400)
        ticket, exp = self.devices.ticket(scope)
        tq = "ticket=" + urllib.parse.quote(ticket)
        if b.get("torrent_id"):
            url = f"{base}/media/{b['torrent_id']}/{int(b['file_index'])}/{urllib.parse.quote(os.path.basename(full))}?{tq}"
            rq = f"torrent_id={b['torrent_id']}&file_index={int(b['file_index'])}"
        else:
            path = b.get("path", "")
            url = f"{base}/lib/{int(b['root'])}/{urllib.parse.quote(path)}?{tq}"
            rq = f"root={int(b['root'])}&path={urllib.parse.quote(path)}"
        cast_v = variant == "cast" and mp_media.variant(full)
        if cast_v:
            url += "&v=cast"
        subs = [{"name": x["name"], "lang": x.get("lang"),
                 "url": f"{base}/sub?{rq}&name={urllib.parse.quote(x['name'])}&{tq}",
                 "vtt": f"{base}/sub?{rq}&name={urllib.parse.quote(x['name'])}&vtt=1&{tq}"} for x in sidecar_subs(full)]
        return {"url": url, "subs": subs, "title": os.path.basename(full), "expires": exp,
                "variant": "cast" if cast_v else None, "content_type": "video/mp4" if cast_v else None}

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
            raise ApiError(T("no such file", "nie ma takiego pliku"), 404)
        base = os.path.realpath(save_path)
        full = os.path.realpath(os.path.join(base, f["path"]))
        if not full.startswith(base + os.sep):
            raise ApiError(T("path outside the download folder", "ścieżka poza katalogiem pobierania"), 403)
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

    def mcp_route(self, method, p, qd, q):
        a, base = self.app, self.base_url()
        try:
            if p.startswith("/.well-known/oauth-protected-resource") and method == "GET":
                return self.send_json(a.mcp.metadata_resource(base))
            if p.startswith("/.well-known/oauth-authorization-server") and method == "GET":
                return self.send_json(a.mcp.metadata_server(base))
            if p == "/oauth/register" and method == "POST":
                return self.send_json(a.mcp.register(self.body()), 201)
            if p == "/oauth/authorize" and method == "GET":
                return self.send_bytes(a.mcp.authorize_page(qd, self.client_address[0]).encode(), "text/html; charset=utf-8")
            if p == "/oauth/authorize/wait" and method == "GET":
                return self.send_json(a.mcp.authorize_wait(qd.get("session", "")))
            if p == "/oauth/token" and method == "POST":
                n = int(self.headers.get("Content-Length") or 0)
                raw = self.rfile.read(n).decode() if n else ""
                form = json.loads(raw) if raw.lstrip().startswith("{") else dict(urllib.parse.parse_qsl(raw))
                self.extra_headers.append(("Cache-Control", "no-store"))
                return self.send_json(a.mcp.token(form))
            if p == "/mcp":
                self.device = self.authorized(q)
                if not self.device:
                    self.extra_headers.append(("WWW-Authenticate", f'Bearer resource_metadata="{base}/.well-known/oauth-protected-resource"'))
                    return self.send_json({"error": "invalid_token"}, 401)
                role = a.devices.role(self.device)
                mp_library.set_viewer(self.device, role)
                if method != "POST":
                    self.extra_headers.append(("Allow", "POST"))
                    return self.send_json({"error": "POST only (no server-initiated stream)"}, 405)
                msg = self.body()
                out = [r for r in (a.mcp.handle(m, role) for m in (msg if isinstance(msg, list) else [msg])) if r]
                if not out:
                    self.send_response(202)
                    self.send_header("Content-Length", "0")
                    self.end_headers()
                    return
                return self.send_json(out if isinstance(msg, list) else out[0])
            raise ApiError(T("no such endpoint", "nie ma takiego endpointu"), 404)
        except mp_mcp.McpError as e:
            return self.send_json({"error": "invalid_request", "error_description": str(e)}, e.code if 400 <= e.code < 600 else 400)
        except mp_access.AccessError as e:
            return self.send_json({"error": "access_denied", "error_description": str(e)}, e.status)

    def authorized(self, query):
        """Id of the paired device (or "legacy" for the old shared token), or None."""
        h = self.headers.get("Authorization", "")
        given = h[7:] if h.startswith("Bearer ") else (query.get("token") or [""])[0]
        return self.app.devices.check(given)

    def ticket_ok(self, p, q):
        """A ticket (?ticket=) opens only one file and its subtitles: /media, /lib, /sub."""
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
        """This server's address as the client sees it (for URLs for another screen on the same network)."""
        host = self.headers.get("Host") or f"{self.server.server_address[0]}:{self.server.server_address[1]}"
        return "http://" + host

    def device_name(self):
        """Name of the asking device (paired phone), or None."""
        dev = getattr(self, "device", None)
        return next((d["name"] for d in self.app.devices.list() if d["id"] == dev), None) if dev else None

    def body(self):
        n = int(self.headers.get("Content-Length") or 0)
        return json.loads(self.rfile.read(n)) if n else {}

    def do_GET(self):
        self.dispatch("GET")

    def do_POST(self):
        self.dispatch("POST")

    def do_PUT(self):
        self.dispatch("PUT")

    def do_OPTIONS(self):
        # preflight from web players (Chromecast) before Range on /media, /lib, /sub
        self.send_response(204)
        self.send_header("Access-Control-Allow-Methods", "GET, HEAD, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Range, Authorization")
        self.send_header("Content-Length", "0")
        self.end_headers()

    def end_headers(self):
        # files (video, subtitles) are also read by the Chromecast receiver from another origin; without CORS it skips subtitles
        if urllib.parse.urlsplit(self.path).path.startswith(("/media/", "/lib/", "/sub")) or self.command == "OPTIONS":
            self.send_header("Access-Control-Allow-Origin", "*")
            self.send_header("Access-Control-Expose-Headers", "Content-Length, Content-Range, Accept-Ranges, X-Ready-End")
        for k, v in getattr(self, "extra_headers", ()):
            self.send_header(k, v)
        self.extra_headers = []
        super().end_headers()

    def do_HEAD(self):
        self.dispatch("HEAD")

    def caption_header(self, q, rq):
        """Samsung (DLNA) asks for subtitles with the getcaptionInfo.sec header; we give the first SRT next to the video (`&cap=`)."""
        cap, t = (q.get("cap") or [""])[0], (q.get("ticket") or [""])[0]
        if cap and t and self.headers.get("getcaptionInfo.sec"):
            self.extra_headers.append(("CaptionInfo.sec", f"{self.base_url()}/sub?{rq}&name={urllib.parse.quote(cap)}"
                                                          f"&ticket={urllib.parse.quote(t)}"))

    def dispatch(self, method):
        # streams and uploads: a self-update and library encodes wait until they have been quiet for a while (App.busy).
        # Only requests that carry data count: the app's HEAD probes (size, X-Ready-End) keep coming every few
        # seconds while it plays from its own buffer or a downloaded copy, and would pause tasks for nothing.
        if method == "HEAD" or not urllib.parse.urlsplit(self.path).path.startswith(("/lib/", "/media/", "/api/upload/")):
            return self._dispatch(method)
        a = self.app
        with a.stream_lock:
            a.streams += 1
        try:
            return self._dispatch(method)
        finally:
            with a.stream_lock:
                a.streams -= 1
                a.stream_last = time.time()

    def _dispatch(self, method):
        self.extra_headers = []
        mp_i18n.set_from_header(self.headers.get("Accept-Language"))  # message language (errors in JSON)
        u = urllib.parse.urlsplit(self.path)
        q = urllib.parse.parse_qs(u.query)
        p = u.path.rstrip("/")
        try:
            if p == "/api/ping":
                # id: the app checks that the same server is at a known address before sending it the token
                return self.send_json({"ok": True, "app": "magneticplayer", "version": VERSION, "id": self.app.cfg["id"]})
            if p in ("", "/index.html") and method in ("GET", "HEAD"):
                # web client (laptop/tablet without the app); a static file, API data requires a token anyway
                return self.send_static(os.path.join(WEB_DIR, "index.html"), "text/html; charset=utf-8", method == "HEAD")
            a = self.app
            # installation on a new computer: no token, but with a valid join code (mp_install.py)
            if (m := re.fullmatch(r"/i/([A-Za-z0-9-]{4,12})(/bundle\.tar\.gz|/ps)?", p)) and method == "GET":
                if m.group(2) == "/ps":
                    return self.send_bytes(a.installs.script_ps(m.group(1), self.base_url()).encode(), "text/plain; charset=utf-8")
                if m.group(2):
                    return self.send_bytes(a.installs.bundle(m.group(1)), "application/gzip")
                full = (q.get("full") or ["0"])[0] not in ("0", "")
                return self.send_bytes(a.installs.script(m.group(1), self.base_url(), full).encode(),
                                       "text/x-shellscript; charset=utf-8")
            if p == "/api/install/join" and method == "POST":
                return self.send_json(a.installs.join(self.body(), self.client_address[0]))
            if p == "/api/pair" and method == "POST":
                # no token: a one-time code (from another device's app or --pair on the computer) → device token
                b = self.body()
                res, err = a.devices.pair(b.get("code"), b.get("name"))
                if err:
                    raise ApiError(err, 403)
                log("device paired:", (b.get("name") or "?")[:60])
                return self.send_json({**res, "id": a.cfg["id"], "name": a.name()})
            # access request: no token, home network only; after a household member accepts it returns a pairing code
            if p == "/api/access/request" and method == "POST":
                return self.send_json(a.access.request(self.body().get("name"), self.client_address[0]))
            if (m := re.fullmatch(r"/api/access/request/([0-9a-f]{16})", p)) and method == "GET":
                return self.send_json(a.access.poll(m.group(1), (q.get("secret") or [""])[0]))
            # ---- MCP for an AI assistant (mp_mcp.py): OAuth with approval on the phone, then the device token
            if p.startswith(("/.well-known/oauth-", "/oauth/")) or p == "/mcp":
                return self.mcp_route(method, p, {k: v[0] for k, v in q.items()}, q)
            mp_library.set_viewer(None)
            self.device = self.authorized(q)
            qd0 = {k: v[0] for k, v in q.items()}
            if not self.device and not (method in ("GET", "HEAD") and self.ticket_ok(p, q)):
                raise ApiError(T("invalid token", "zły token"), 401)
            self.role = a.devices.role(self.device)
            mp_library.set_viewer(self.device, self.role)  # library folder visibility for this device
            if self.role == "guest" and not guest_allowed(method, p):
                raise ApiError(T("guests don't have access to this (ask a household member)", "gość nie ma do tego dostępu (poproś domownika)"), 403)
            if p.startswith("/api/upload/") and method in ("GET", "PUT"):
                return mp_upload.handle(self, method, p[len("/api/upload/"):], qd0)
            if p == "/api/info" and method == "GET":
                return self.send_json(a.info(self.client_address[0], self.device, self.role))
            if p == "/api/access/requests" and method == "GET":
                return self.send_json({"requests": a.access.pending()})
            if (m := re.fullmatch(r"/api/access/requests/([0-9a-f]{16})", p)) and method == "POST":
                return self.send_json(a.access.decide(m.group(1), self.body(), a.devices, self.device))
            if p == "/api/pair/code" and method == "POST":
                b = self.body()
                try:  # {"role": "guest", "days": 7}: a guest invitation (code valid for a day, access for `days`)
                    code, ttl = a.devices.new_code(str(b.get("role") or "owner"), float(b.get("days") or 0) or None)
                except ValueError as e:
                    raise ApiError(str(e), 400)
                host, _, port = (self.headers.get("Host") or "").rpartition(":")
                return self.send_json({"code": code, "expires_in": ttl, "host": host or None,
                                       "port": int(port) if port.isdigit() else a.cfg["port"],
                                       "id": a.cfg["id"], "name": a.name()})
            if p == "/api/install/code" and method == "POST":
                code, ttl = a.installs.new_code(self.device)
                url = f"{self.base_url()}/i/{code}"
                return self.send_json({"code": code, "expires_in": ttl, "url": url,
                                       "command": f"curl -fsSL {url} | bash",
                                       "command_full": f"curl -fsSL '{url}?full=1' | bash",
                                       "command_windows": f"irm {url}/ps | iex"})
            if p == "/api/install/status" and method == "GET":
                return self.send_json(a.installs.status(qd0.get("code"), self.device))
            if p == "/api/devices" and method == "GET":
                return self.send_json(a.devices.list(self.device))
            if p == "/api/devices/revoke" and method == "POST":
                dev = str(self.body().get("id") or "")
                if not a.devices.revoke(dev):
                    raise ApiError(T("no such device", "nie ma takiego urządzenia"), 404)
                log("access revoked for device", dev)
                return self.send_json({"ok": True})
            if p == "/api/wake/verified" and method == "POST":
                # the app woke this computer over the network (sent WoL, then it answered): remembered for everyone
                net = platform.net_info(self.client_address[0])
                a.cfg["wake_verified"] = {"at": time.time(), "mac": net.get("mac"), "by": self.device}
                save_config(a.cfg)
                log(f"wake over the network confirmed (device {self.device})")
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
                return self.send_json(a.ticket_urls(b, self.base_url(), b.get("variant")))
            # ---- file format and the version for Chromecast
            if p == "/api/media/probe" and method == "GET":
                full = a.resolve_ref(qd0)
                if not mp_media.detect_encoder():
                    return self.send_json({"ffmpeg": False})
                try:
                    info = mp_media.probe(full)
                except (ValueError, OSError, subprocess.TimeoutExpired) as e:
                    raise ApiError(T(f"can't read the file: {e}", f"nie da się odczytać pliku: {e}"), 422)
                v = mp_media.variant(full)
                # assessment for every known Chromecast (renderer registry with the model)
                per = []
                for r in a.renderers.casts():
                    prof = mp_renderers.CAST_PROFILES.get(r.get("profile") or "")
                    if prof:
                        per.append({"id": r["id"], "name": r.get("name"), "label": mp_renderers.label(prof),
                                    **mp_media.cast_check_for(info, prof)})
                return self.send_json({
                    "ffmpeg": True, "file": info, "cast": mp_media.cast_check(info), "cast_devices": per,
                    "complete": a.ref_complete(qd0),
                    "variant": {"size": os.path.getsize(v), "cast": mp_media.cast_check(mp_media.probe(v))} if v else None,
                    "job": a.convert.status(full), "encoder": mp_media.encoder_info()})
            if p == "/api/media/convert" and method == "POST":
                b = self.body()
                full = a.resolve_ref(b)
                if b.get("cancel"):
                    a.convert.cancel(full)
                    return self.send_json({"ok": True})
                if b.get("remove"):
                    a.convert.cancel(full)
                    mp_media.remove_variant(full)
                    return self.send_json({"ok": True})
                if not a.ref_complete(b):
                    raise ApiError(T("the video is still downloading; it will be prepared once complete",
                                     "film jeszcze się pobiera; przygotuję go, gdy będzie cały"), 409)
                try:
                    return self.send_json(a.convert.start(full, os.path.basename(full)))
                except (RuntimeError, ValueError, OSError) as e:
                    raise ApiError(str(e), 409)
            if p == "/api/media/converts" and method == "GET":
                return self.send_json(a.convert.all())
            # ---- Chromecast controlled from the agent (web client)
            if p == "/api/cast/devices" and method == "GET":
                return self.send_json(a.casts.devices())
            if p == "/api/cast/play" and method == "POST":
                b = self.body()
                port = self.server.server_address[1]
                t = a.ticket_urls(b, f"http://{lan_ip()}:{port}", "cast")
                subs = [{"url": x["vtt"], "lang": x["lang"]} for x in t["subs"]]
                return self.send_json(a.casts.play(str(b.get("device") or ""), t["url"], t["title"],
                                                   t["content_type"] or "video/mp4", subs, b.get("start")))
            if p == "/api/announce" and method == "GET":
                return self.send_json(a.announce.list())
            if p == "/api/renderers" and method == "GET":
                return self.send_json(a.renderers.casts())
            if p == "/api/cast/status" and method == "GET":
                return self.send_json(a.casts.status())
            if p == "/api/cast/cmd" and method == "POST":
                b = self.body()
                return self.send_json(a.casts.control(b.get("cmd"), b.get("value"), b.get("device") or None))
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
                    raise ApiError(T("this isn't a magnet link", "to nie jest magnet link"), 400)
                dest = a.library.dest(int(b["root"]), str(b.get("sub") or "")) if b.get("root") is not None else None
                tid = a.backend.add_magnet(uri, b.get("sequential", True), dest)
                if tid:
                    a.jobs.add(tid.lower(), uri, b.get("after", "none"))
                    if dest:
                        a.jobs.set_meta(tid.lower(), dest=dest, guess=b.get("guess"))
                log("magnet added:", tid, "→", dest or "(client default folder)")
                return self.send_json({"id": tid, "path": dest})
            m = re.fullmatch(r"/api/torrents/([0-9a-fA-F]+)/move", p)
            if m and method == "POST":  # changing the folder after identification: the client moves it (keeps seeding)
                b, tid = self.body(), m.group(1).lower()
                dest = a.library.dest(int(b["root"]), str(b.get("sub") or ""))
                a.backend.move(tid, dest)
                j = a.jobs.get(tid) or {}
                if b.get("learn") and j.get("guess"):  # next time this title goes straight here
                    tags = a.library.roots()[int(b["root"])]["tags"]
                    if tags:
                        a.ident.learn(j["guess"], tags[0])
                a.jobs.set_meta(tid, dest=dest)
                log("torrent moved:", tid, "→", dest)
                return self.send_json({"ok": True, "path": dest})
            if p == "/api/library/guess" and method == "POST":
                b = self.body()
                name, files = b.get("name"), None
                if b.get("magnet"):
                    name = magnet_name(b["magnet"]) or name
                if b.get("torrent"):  # already in the client (e.g. a magnet without a name, once the metadata is there)
                    t = next((t for t in a.backend.list() if t["id"] == b["torrent"]), None)
                    if t:
                        name = t["name"]
                        files = [(f["path"], f["size"]) for f in a.backend.files(b["torrent"])[1]] or None
                g = a.ident.guess(name, files) if name else {"tag": None, "confidence": "low", "why": []}
                kind = g.get("kind") if g.get("tag") in ("seriale", "bajki") else None
                return self.send_json(dict(a.library.targets(g.get("tag"), g.get("title"), kind), guess=g, name=name))
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
                act = self.body().get("action")
                if act == "remove_data":  # the "for Chromecast" versions live in the torrent's folder: delete them along with it
                    try:
                        save_path, files = a.backend.files(m.group(1))
                        for f in files:
                            mp_media.remove_variant(os.path.join(save_path, f["path"]))
                    except ApiError:
                        pass
                a.backend.action(m.group(1), act)
                return self.send_json({"ok": True})
            m = re.fullmatch(r"/media/([0-9a-fA-F]+)/(\d+)(/.*)?", p)
            if m and method in ("GET", "HEAD"):
                tid, idx = m.group(1), int(m.group(2))
                full, f = a.resolve_file(tid, idx)
                self.caption_header(q, f"torrent_id={tid}&file_index={idx}")
                if qd0.get("v") == "cast" and mp_media.variant(full):
                    return self.stream(mp_media.variant(full), method == "HEAD", watch=full)
                # downloaded pieces only: instead of zeros from a not-yet-downloaded file we wait for Deluge (see stream())
                return self.stream(full, method == "HEAD", f.get("_pieces"),
                                   lambda: a.resolve_file(tid, idx)[1].get("_pieces"), watch=full)
            if p == "/api/player/open" and method == "POST":
                if not a.is_screen():
                    raise ApiError(T(f"{a.name()} isn't a screen (no mpv/session or config: player)",
                                     f"{a.name()} nie jest ekranem (brak mpv/sesji albo config: player)"), 409)
                b = self.body()
                if b.get("url"):  # a video from another server (URL with a ticket from /api/media/ticket)
                    url = str(b["url"])
                    subs = [str(u) for u in b.get("subs") or [] if str(u).startswith(("http://", "https://"))]
                    if not url.startswith(("http://", "https://")):
                        raise ApiError(T("url must be http(s)", "url musi być http(s)"), 400)
                    a.mpv.open(url, b.get("title"), subs, b.get("start"))
                else:
                    full = a.resolve_ref(b)
                    # mpv finds subtitles next to the video by itself, but not those in a subfolder (Subs/English.srt from torrents)
                    extra = [os.path.join(os.path.dirname(full), x["name"]) for x in sidecar_subs(full) if os.sep in x["name"]]
                    a.mpv.open(full, start=b.get("start"), subs=extra)
                return self.send_json({"ok": True})
            if p == "/api/player/status" and method == "GET":
                return self.send_json(a.mpv.status())
            if p == "/api/player/cmd" and method == "POST":
                b = self.body()
                a.mpv.control(b.get("cmd"), b.get("value"))
                return self.send_json({"ok": True})
            if p == "/api/system/suspend" and method == "POST":
                if not POWER:
                    raise ApiError(T(f"{a.name()} has sleep over the network disabled", f"{a.name()} ma wyłączone usypianie z sieci"), 403)
                if a.mpv.running():
                    a.mpv.cmd("quit")
                self.send_json({"ok": True})
                threading.Timer(1.0, lambda: suspend(a.guard)).start()
                return
            if p == "/api/system/screen_on" and method == "POST":
                return self.send_json({"ok": True, "unlocked": wake_screen()})
            if p == "/api/system/unlock" and method == "POST":
                return self.send_json({"ok": True, "unlocked": wake_screen(unlock=True)})
            # ---- library
            qd = {k: v[0] for k, v in q.items()}
            if p == "/api/library" and method == "GET":
                return self.send_json(a.library.visible_roots(qd.get("manage") == "1"))
            if p == "/api/library" and method == "POST":
                b = self.body()
                if b.get("op") == "remove":
                    a.library.remove_root(int(b["index"]))
                elif b.get("op") == "update":
                    a.library.update_root(int(b["index"]), b)
                else:
                    a.library.add_root(b["path"], b.get("name"))
                return self.send_json(a.library.visible_roots(True))
            # ---- disks and background file tasks (mp_tasks.py)
            if p == "/api/library/disks" and method == "GET":
                return self.send_json({"disks": a.tasks.disks(), "trash": a.tasks.trash(), "trash_days": a.tasks.trash_days()})
            if p == "/api/library/plan" and method == "POST":
                b = self.body()
                kind = b.get("kind")
                if kind == "free":
                    return self.send_json(a.tasks.plan_free(int(b["root"]), b.get("need")))
                if kind == "shrink":
                    if b.get("profile"):  # choosing a profile for this folder/file is remembered (new files follow it)
                        a.tasks.set_profile(int(b["root"]), b.get("path", ""), b["profile"])
                    return self.send_json(a.tasks.plan_shrink(int(b["root"]), b.get("path", ""), b.get("encoder")))
                if kind == "modernize":  # old picture formats in the whole library (agent 1.8.2+)
                    return self.send_json(a.tasks.plan_shrink(-1, "", b.get("encoder"), modernize=True))
                if kind == "move":
                    return self.send_json(a.tasks.plan_move(b.get("items") or []))
                if kind == "tidy":
                    return self.send_json(a.tasks.plan_tidy(a.ident))
                if kind == "dupes":
                    return self.send_json(a.tasks.plan_dupes())
                if kind == "subs":  # subtitles fitted to the films (agent 1.9+)
                    return self.send_json(a.tasks.plan_subs(bool(b.get("every"))))
                if kind == "trash":
                    return self.send_json(a.tasks.plan_trash(b.get("items") or []))
                raise ApiError(T("kind must be free, shrink, modernize, move, tidy, dupes, subs or trash",
                                 "kind to free, shrink, modernize, move, tidy, dupes, subs albo trash"), 400)
            if (m := re.fullmatch(r"/api/library/plan/([0-9a-f]{8})", p)) and method == "GET":
                return self.send_json(a.tasks.plan_status(m.group(1)))
            if p == "/api/library/profile" and method == "POST":
                b = self.body()
                a.tasks.set_profile(int(b["root"]), b.get("path", ""), b.get("profile"))
                return self.send_json({"ok": True})
            if p == "/api/library/trash/empty" and method == "POST":
                return self.send_json({"freed": a.tasks.purge_trash(everything=True)})
            if p == "/api/tasks" and method == "GET":
                return self.send_json(a.tasks.list())
            if p == "/api/tasks" and method == "POST":
                b = self.body()
                pick = b.get("pick")
                return self.send_json(a.tasks.create(str(b.get("plan")), None if pick is None else {int(x) for x in pick}))
            if (m := re.fullmatch(r"/api/tasks/([0-9a-f]{8})", p)) and method == "GET":
                return self.send_json(a.tasks.get(m.group(1)))
            if (m := re.fullmatch(r"/api/tasks/([0-9a-f]{8})/cancel", p)) and method == "POST":
                return self.send_json(a.tasks.cancel(m.group(1)))
            if p == "/api/library/cover" and method in ("GET", "HEAD"):
                # a folder or a video: poster.jpg/folder.jpg/<name>.jpg next to it, otherwise a frame (mp_media.cover)
                pic = mp_media.cover(a.library.resolve(int(qd.get("root", 0)), qd.get("path", "")),
                                     720 if qd.get("w") == "720" else 360)
                if not pic:
                    raise ApiError(T("no cover", "brak okładki"), 404)
                return self.send_static(pic, "image/png" if pic.endswith(".png") else "image/webp" if pic.endswith(".webp")
                                        else "image/jpeg", method == "HEAD", "private, max-age=86400",
                                        {"X-Cover-Kind": "frame" if mp_media.is_frame(pic) else "picture"})
            if p == "/api/library/search" and method == "GET":
                return self.send_json(a.library.search(qd.get("q", "")))
            if p == "/api/library/browse" and method == "GET":
                return self.send_json(a.library.browse(int(qd.get("root", 0)), qd.get("path", ""), qd.get("all") == "1"))
            if p == "/api/library/episodes" and method == "GET":
                return self.send_json(a.library.episodes(int(qd.get("root", 0)), qd.get("path", "")))
            if p == "/api/library/segments" and method == "GET":
                full = a.library.resolve(int(qd.get("root", 0)), qd.get("path", ""))
                d = os.path.dirname(full)
                try:
                    names = [n for n in os.listdir(d) if not n.startswith(".") and media_kind(n) == "video"
                             and os.path.isfile(os.path.join(d, n))]
                except OSError:
                    names = []
                eps = [os.path.join(d, x["path"]) for x in mp_ident.episodes([(n, 0) for n in names])[0]]
                return self.send_json(a.segments.request(full, eps))
            if p == "/api/fs" and method == "GET":
                return self.send_json(a.library.fs_dirs(qd.get("path", "")))
            m = re.fullmatch(r"/lib/(\d+)/(.+)", p)
            if m and method in ("GET", "HEAD"):
                full = a.library.resolve(int(m.group(1)), urllib.parse.unquote(m.group(2)))
                self.caption_header(q, f"root={int(m.group(1))}&path={urllib.parse.quote(urllib.parse.unquote(m.group(2)))}")
                if qd0.get("v") == "cast" and mp_media.variant(full):
                    return self.stream(mp_media.variant(full), method == "HEAD", watch=full)
                return self.stream(full, method == "HEAD", watch=full)
            # ---- subtitles
            if p == "/api/subs/list" and method == "GET":
                full = a.resolve_ref(qd)
                out = []
                for x in sidecar_subs(full):
                    sp = os.path.join(os.path.dirname(full), x["name"])
                    if x["ext"] == "sub" and mp_subsync.is_text_sub(sp):
                        x["ext"] = "srt"  # served converted (/sub): MicroDVD/MPL2/TMPlayer as SubRip
                    r = a.subsync.get(sp)
                    if r:  # fitted or assessed before (agent 1.9+): good / unsure / bad for this copy of the film
                        x["fit"] = {"verdict": r["verdict"], "z": r["z"]}
                    src = a.subsources.get(sp)
                    if src:  # downloaded by the agent (1.12+): search results it matches are marked "you have it"
                        x["source"] = {"provider": src["provider"], "id": src["id"], "release": src.get("release", "")}
                    out.append(x)
                return self.send_json(out)
            if p == "/sub" and method in ("GET", "HEAD"):
                full = a.resolve_ref(qd)
                names = {s["name"]: s for s in sidecar_subs(full)}
                if qd.get("name") not in names:
                    raise ApiError(T("no such subtitles", "nie ma takich napisów"), 404)
                if qd.get("vtt") == "1":  # browsers (<track>) understand only WebVTT
                    return self.send_vtt(os.path.join(os.path.dirname(full), qd["name"]), full)
                sp = os.path.join(os.path.dirname(full), qd["name"])
                if qd["name"].lower().endswith(".srt") or (qd["name"].lower().endswith(".sub") and mp_subsync.is_text_sub(sp)):
                    # MicroDVD/cp1250 named .srt, or a text .sub: the phone gets real UTF-8 SubRip
                    return self.send_text(readable_srt(os.path.join(os.path.dirname(full), qd["name"]), full),
                                          "application/x-subrip", method == "HEAD")
                return self.stream(os.path.join(os.path.dirname(full), qd["name"]), method == "HEAD")
            if p == "/api/subs/search" and method == "POST":
                b = self.body()
                langs = [l.strip().lower() for l in (b.get("languages") or "en,pl").split(",") if l.strip()]
                args = (a.resolve_ref(b), langs, (b.get("title") or "").strip() or None, b.get("year") or None)
                if b.get("async"):  # the app shows progress by polling /api/subs/job
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
                # the new file becomes the default of its language; the current default moves to Subtitles/
                res = a.subs.download(full, str(b["id"]), b["provider"], langs, (b.get("title") or "").strip() or None,
                                      b.get("year") or None, place=lambda target, src: mp_subfiles.place(
                                          full, target, src, a.subsources, a.tasks.torrent_owned, a.subsync.move))
                if res.get("path"):
                    a.subsources.put(res["path"], b["provider"], b["id"], res.get("release"))
                res.pop("path", None)
                # fitted to this copy of the film (agent 1.9+): "fit": "wait" (the app waits, ~30 s the first time for a
                # film) returns the outcome in "fit"; otherwise it happens in the background
                fit = res.get("name") and mp_subsync.available()
                if fit and b.get("fit") == "wait":
                    res["fit"] = a.fit_subs(full, res["name"])
                    res["name"] = res["fit"].get("name") or res["name"]
                if res.get("name") and a.mpv.running() and a.mpv.prop("path") == full:  # this very video is playing: enable the subtitles right away
                    try:
                        a.mpv.cmd("sub-add", os.path.join(os.path.dirname(full), res["name"]), "select")
                    except ApiError:
                        pass
                if fit and b.get("fit") != "wait":
                    threading.Thread(target=bind(a.fit_subs), args=(full, res["name"]), daemon=True).start()
                    res["fit"] = {"verdict": "pending"}
                return self.send_json(res)
            if p == "/api/subs/default" and method == "POST":  # this version becomes its language's default (1.12+)
                b = self.body()
                full = a.resolve_ref(b)
                if b.get("name") not in {x["name"] for x in sidecar_subs(full)}:
                    raise ApiError(T("no such subtitles", "nie ma takich napisów"), 404)
                try:
                    name = mp_subfiles.promote(full, b["name"], a.subsources, a.tasks.torrent_owned, a.subsync.move)
                except mp_subfiles.FileError as e:
                    raise ApiError(str(e), e.status)
                except OSError as e:
                    raise ApiError(e.strerror or str(e), 500)
                if a.mpv.running() and a.mpv.prop("path") == full:  # the Remote: this video plays in mpv, turn it on
                    try:
                        a.mpv.cmd("sub-add", os.path.join(os.path.dirname(full), name), "select")
                    except ApiError:
                        pass
                return self.send_json({"name": name})
            if p == "/api/subs/fit" and method == "POST":  # one subtitle file next to the video (agent 1.9+)
                b = self.body()
                full = a.resolve_ref(b)
                if b.get("name") not in {x["name"] for x in sidecar_subs(full)}:
                    raise ApiError(T("no such subtitles", "nie ma takich napisów"), 404)
                return self.send_json(a.fit_subs(full, b["name"]))
            if p == "/api/config/app_updates" and method == "GET":
                return self.send_json(a.app_updates.status())
            if p == "/api/config/app_updates" and method == "POST":
                mode = self.body().get("mode")
                if mode not in mp_appupdate.MODES:
                    raise ApiError(T("mode must be auto or manual", "tryb to auto albo manual"), 400)
                return self.send_json(a.app_updates.set_mode(mode))
            # ---- catalog: films and series instead of folders (mp_catalog.py)
            if p == "/api/catalog" and method == "GET":
                if qd.get("since") and qd["since"] == str(a.catalog.version):
                    return self.send_json({"version": a.catalog.version, "same": True})
                return self.send_json(a.catalog.public(mp_i18n.lang()))
            if p == "/api/catalog/title" and method == "GET":
                try:
                    return self.send_json(a.catalog.title(qd.get("imdb", ""), mp_i18n.lang()))
                except ValueError:
                    raise ApiError(T("bad IMDb id", "zły numer IMDb"), 400)
            if p == "/api/catalog/person" and method == "GET":
                try:
                    return self.send_json(a.catalog.person(qd.get("id", ""), mp_i18n.lang()))
                except ValueError:
                    raise ApiError(T("bad person id", "zły identyfikator osoby"), 400)
            if p == "/api/catalog/next" and method == "GET":
                return self.send_json(a.catalog.next(qd.get("imdb", ""), mp_i18n.lang()))
            if p == "/api/wishlist" and method == "GET":
                return self.send_json({"items": a.wishlist.list(a.catalog, mp_i18n.lang())})
            if p == "/api/wishlist" and method == "POST":
                try:
                    a.wishlist.add(self.body(), self.device_name())
                except ValueError:
                    raise ApiError(T("bad IMDb id", "zły numer IMDb"), 400)
                return self.send_json({"items": a.wishlist.list(a.catalog, mp_i18n.lang())})
            if p == "/api/wishlist/remove" and method == "POST":
                a.wishlist.remove(str(self.body().get("imdb") or ""))
                return self.send_json({"items": a.wishlist.list(a.catalog, mp_i18n.lang())})
            if p == "/api/config/search" and method == "GET":
                return self.send_json({"engines": a.cfg.get("search_engines") or []})
            if p == "/api/config/search" and method == "POST":
                eng = []
                for e in (self.body().get("engines") or [])[:10]:
                    name, url = str(e.get("name") or "").strip()[:40], str(e.get("url") or "").strip()[:500]
                    if not name or not re.match(r"https?://[^\s/]+", url) or not re.search(r"\{(title|imdb)\}", url):
                        raise ApiError(T("each search needs a name and an http(s) address with {title} or {imdb}",
                                         "każda wyszukiwarka potrzebuje nazwy i adresu http(s) z {title} albo {imdb}"), 400)
                    eng.append({"name": name, "url": url})
                a.cfg["search_engines"] = eng
                save_config(a.cfg)
                return self.send_json({"engines": eng})
            # ---- files that phones sent to the server (mp_upload.py history)
            if p == "/api/uploads" and method == "GET":
                items = a.uploads.list()
                for h in items:
                    if h["state"] == "new":
                        g = a.ident.guess(h["name"])
                        kind = g.get("kind") if g.get("tag") in ("seriale", "bajki") else None
                        h.update(a.library.targets(g.get("tag"), g.get("title"), kind), guess=g)
                        here = os.path.realpath(h["dir"])
                        for t in h["targets"]:  # where it lies now is not a suggestion
                            t["here"] = os.path.realpath(t["path"]) == here
                        if h.get("pick") is not None and h["targets"][h["pick"]]["here"]:
                            h["pick"] = None
                    h.pop("dir", None)
                    h.pop("path", None)
                return self.send_json({"items": items, "new": sum(h["state"] == "new" for h in items)})
            if p == "/api/uploads/keep" and method == "POST":
                return self.send_json(a.uploads.keep(str(self.body().get("id") or "")))
            if p == "/api/uploads/move" and method == "POST":
                b = self.body()
                root = a.library.roots()[int(b["root"])]
                dest = a.library.dest(int(b["root"]), str(b.get("sub") or ""))
                if b.get("learn") and b.get("guess") and root["tags"]:
                    a.ident.learn(b["guess"], root["tags"][0])
                return self.send_json(a.uploads.move(str(b.get("id") or ""), dest, root["name"]))
            if p == "/api/config/shrink" and method == "GET":
                return self.send_json(a.tasks.shrink_config())
            if p == "/api/config/shrink" and method == "POST":
                return self.send_json(a.tasks.set_shrink_config(self.body()))
            if p == "/api/config/agent_updates" and method == "GET":
                return self.send_json(a.agent_updates.status())
            if p == "/api/config/agent_updates" and method == "POST":
                mode = self.body().get("mode")
                if mode not in mp_selfupdate.MODES:
                    raise ApiError(T("mode must be auto or manual", "tryb to auto albo manual"), 400)
                return self.send_json(a.agent_updates.set_mode(mode))
            if p == "/api/agent/update" and method == "POST":
                if not a.agent_updates.supported():
                    raise ApiError(T("this system can't update the agent by itself yet", "ten system nie umie jeszcze sam zaktualizować agenta"), 409)
                return self.send_json(a.agent_updates.update_now())
            if p == "/api/config/opensubtitles" and method == "GET":
                return self.send_json({"api_key": bool(a.cfg.get("opensubtitles_api_key")),
                                       "username": a.cfg.get("opensubtitles_username", ""),
                                       "password": bool(a.cfg.get("opensubtitles_password"))})
            if p == "/api/config/opensubtitles" and method == "POST":
                b = self.body()
                a.subs.set_credentials(b.get("api_key"), b.get("username"), b.get("password"))
                return self.send_json({"ok": True})
            raise ApiError(T("no such endpoint", "nie ma takiego endpointu"), 404)
        except (LibError, SubsError, CastError, mp_install.InstallError, mp_access.AccessError, mp_tasks.TaskError,
                mp_upload.UploadError) as e:
            self.send_json({"error": str(e)}, e.status)
        except ApiError as e:
            self.send_json({"error": str(e)}, e.status)
        except (BrokenPipeError, ConnectionResetError):
            pass
        except Exception as e:  # noqa: BLE001
            log("error:", repr(e))
            try:
                self.send_json({"error": repr(e)}, 500)
            except OSError:
                pass

    def send_bytes(self, body, ctype):
        self.send_response(200)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def send_static(self, path, ctype, head_only=False, cache="no-cache", headers=None):
        try:
            with open(path, "rb") as f:
                body = f.read()
        except OSError:
            raise ApiError(T("the web page file is missing — run install.sh again", "brak pliku strony — uruchom ponownie install.sh"), 404)
        self.send_response(200)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", cache)
        for k, v in (headers or {}).items():
            self.send_header(k, v)
        self.end_headers()
        if not head_only:
            self.wfile.write(body)

    def send_vtt(self, path, video=None):
        text = readable_srt(path, video)
        if not path.lower().endswith(".vtt"):
            text = re.sub(r"(\d{2}:\d{2}:\d{2}),(\d{3})", r"\1.\2", text)
            text = "WEBVTT\n\n" + text
        self.send_text(text, "text/vtt")

    def send_text(self, text, ctype, head_only=False):
        body = text.encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", ctype + "; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        if not head_only:
            self.wfile.write(body)

    STALL_WAIT = 20  # seconds of waiting for further pieces, then we drop the connection (the player retries)

    def stream(self, full, head_only, pieces=None, refresh=None, watch=None):
        if watch:  # which video is in use (shrink tasks slow down on its disk and don't swap it): App.watched
            with self.app.stream_lock:
                w = self.app.watch.setdefault(os.path.realpath(watch), {"active": 0, "last": 0.0, "read": 0.0})
                w["active"] += 0 if head_only else 1
            try:
                return self._stream_dbg(full, head_only, pieces, refresh)
            finally:
                with self.app.stream_lock:
                    now = time.time()
                    w["last"] = now
                    if not head_only:
                        w["active"] -= 1
                        w["read"] = now
        return self._stream_dbg(full, head_only, pieces, refresh)

    def _stream_dbg(self, full, head_only, pieces=None, refresh=None):
        # diagnostics: `touch ~/.config/magneticplayer/debug-stream` logs every request (range, time, bytes sent)
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
            raise ApiError(T("the file isn't on disk yet", "pliku jeszcze nie ma na dysku"), 404)
        size = os.path.getsize(full)
        ctype = mimetypes.guess_type(full)[0] or "application/octet-stream"
        if full.endswith(".apk"):
            ctype = "application/vnd.android.package-archive"
        dlna = self.headers.get("getcontentFeatures.dlna.org") or "dlna" in (self.headers.get("User-Agent") or "").lower()
        if full.lower().endswith(".mkv"):
            ctype = "video/x-mkv" if dlna else "video/x-matroska"  # Samsung (DLNA) doesn't know x-matroska
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
        # up to which byte we can read from `start` without waiting (the app's on-disk buffer fetches only that much)
        self.send_header("X-Ready-End", str(ready_end(pieces, start, size)))
        if dlna:  # without this, DLNA TVs can get stuck in TRANSITIONING
            self.send_header("transferMode.dlna.org", "Streaming")
            self.send_header("contentFeatures.dlna.org", DLNA_FEATURES)
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
                    self.close_connection = True  # shorter than Content-Length: the client knows to retry
                    return
                chunk = f.read(min(1 << 20, avail - pos, stop - pos))
                if not chunk:
                    break
                self.wfile.write(chunk)
                pos += len(chunk)
                self._sent = getattr(self, "_sent", 0) + len(chunk)


class QuietServer(ThreadingHTTPServer):
    daemon_threads = True
    # Windows: SO_REUSEADDR lets us take a port someone is already listening on (e.g. on 127.0.0.1), and local
    # requests then go to that listener; there the port must be exclusively ours
    allow_reuse_address = not WINDOWS

    def server_bind(self):
        if WINDOWS:
            self.socket.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
        super().server_bind()

    def handle_error(self, request, client_address):
        if isinstance(sys.exc_info()[1], (ConnectionResetError, BrokenPipeError, TimeoutError)):
            return  # the phone dropped the connection (seeking, closing the player): normal
        super().handle_error(request, client_address)


def lan_ip():
    """Address on the local network (nothing is sent: a UDP connect only picks the route)."""
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(("192.168.0.1", 9))
            return s.getsockname()[0]
    except OSError:
        return socket.gethostbyname(socket.gethostname())


def cli_pair(cfg, devices):
    """Pairing code for the first phone (or when no paired one is at hand): a QR in the terminal."""
    code, ttl = devices.new_code()
    host = lan_ip()
    name = cfg.get("name") or socket.gethostname()
    link = "magneticplayer://pair?" + urllib.parse.urlencode(
        {"host": host, "port": cfg["port"], "code": code, "id": cfg["id"], "name": name})
    print(T(f"Pair a phone with “{name}”: in the MagneticPlayer app, Settings → “Add server”,",
            f"Sparuj telefon z „{name}”: w apce MagneticPlayer Ustawienia → „Dodaj serwer”,"))
    print(T(f"scan the code with the camera or type it in. The code works once, for {ttl // 60} min.\n",
            f"zeskanuj kod aparatem albo wpisz ręcznie. Kod działa raz, przez {ttl // 60} min.\n"))
    if shutil.which("qrencode"):
        subprocess.run(["qrencode", "-t", "ANSIUTF8", link])
    else:
        try:  # lite mode: the qrcode module from the agent's venv (install.sh calls --pair with the venv's python)
            import qrcode
            q = qrcode.QRCode(border=2)
            q.add_data(link)
            q.print_ascii(invert=True)
        except ImportError:
            print(T("(QR in the terminal after: sudo apt install qrencode)", "(QR w terminalu po: sudo apt install qrencode)"))
    print(T(f"  address: {host}   port: {cfg['port']}   code: {code[:3]}-{code[3:]}",
            f"  adres: {host}   port: {cfg['port']}   kod: {code[:3]}-{code[3:]}"))
    print(f"  link:  {link}")
    print(T(f"  browser: http://{host}:{cfg['port']}/#p={code}", f"  przeglądarka: http://{host}:{cfg['port']}/#p={code}"))


def cli_join(cfg, devices, url):
    """After installing from another server (`JOIN=<server>/i/<code>`): register with a pairing code for the phone."""
    m = re.fullmatch(r"(https?://[^/]+)/i/([A-Za-z0-9-]+)/?", url.strip())
    if not m:
        print(T(f"invalid join address: {url}", f"zły adres dołączenia: {url}"))
        return 1
    pair_code, _ = devices.new_code()
    body = json.dumps({"code": m.group(2), "id": cfg["id"], "name": cfg.get("name") or socket.gethostname(),
                       "port": cfg["port"], "pair_code": pair_code, "version": VERSION}).encode()
    req = urllib.request.Request(m.group(1) + "/api/install/join", data=body, method="POST",
                                 headers={"Content-Type": "application/json", "Accept-Language": mp_i18n.lang()})
    try:
        with urllib.request.urlopen(req, timeout=10):
            pass
    except urllib.error.HTTPError as e:
        try:
            err = json.loads(e.read()).get("error")
        except ValueError:
            err = e.reason
        print(T(f"Couldn't register with {m.group(1)}: {err}", f"Nie udało się zgłosić do {m.group(1)}: {err}"))
        return 1
    except OSError as e:
        print(T(f"Couldn't register with {m.group(1)}: {e}", f"Nie udało się zgłosić do {m.group(1)}: {e}"))
        return 1
    print(T(f"Registered with {m.group(1)}: the phone that started the install will pair by itself (within 10 min).",
            f"Zgłoszono się do {m.group(1)}: telefon, który zaczął instalację, sparuje się sam (do 10 min)."))
    return 0


def main():
    if sys.stderr is None or sys.stdout is None:  # pythonw on Windows (autostart): no console, log to a file
        f = open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "agent.log"), "a", encoding="utf-8", buffering=1)
        sys.stderr = sys.stderr or f
        sys.stdout = sys.stdout or f
    cfg = load_config()
    mp_i18n.set_from_env()  # terminal messages (--pair, --join, --devices); HTTP requests have their own language
    if "--print-token" in sys.argv:
        print(cfg["token"])
        return
    devices = Devices(os.path.join(CONFIG_DIR, "devices.json"), cfg["token"])
    if "--pair" in sys.argv:
        return cli_pair(cfg, devices)
    if "--join" in sys.argv:
        i = sys.argv.index("--join")
        sys.exit(cli_join(cfg, devices, sys.argv[i + 1] if i + 1 < len(sys.argv) else ""))
    if "--devices" in sys.argv:
        for d in devices.list():
            seen = time.strftime("%Y-%m-%d %H:%M", time.localtime(d["last_seen"])) if d.get("last_seen") else T("never", "nigdy")
            print(f"{d['id']:14} {d['name']:30} " + T("last seen: ", "ostatnio: ") + seen)
        return
    if "--revoke" in sys.argv:
        i = sys.argv.index("--revoke")
        dev = sys.argv[i + 1] if i + 1 < len(sys.argv) else ""
        print(T("access revoked", "odebrano dostęp") if devices.revoke(dev)
              else T(f"no device {dev!r} (list: --devices)", f"nie ma urządzenia {dev!r} (lista: --devices)"))
        return
    app = App(cfg)
    Handler.app = app
    if cfg.get("keep_awake", True):
        app.guard.start()
    srv = QuietServer(("0.0.0.0", int(cfg["port"])), Handler)
    srv.daemon_threads = True
    log(f"MagneticPlayer {VERSION} on port {cfg['port']}, backend {cfg['backend']}")
    srv.serve_forever()


if __name__ == "__main__":
    main()
