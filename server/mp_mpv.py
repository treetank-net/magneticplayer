"""mpv on this computer's screen: starting it in the graphical session, the IPC socket (a named pipe on Windows),
commands and status, and picking the audio track the phone remembered."""
import json
import os
import re
import shutil
import socket
import subprocess
import threading
import time

import mp_platform as platform
from mp_i18n import T
from mp_core import ApiError, WINDOWS, log
from mp_power import wake_screen


if WINDOWS:  # mpv on Windows: IPC over a named pipe instead of a unix socket
    RUNTIME_DIR = os.path.join(os.environ.get("TEMP") or os.path.expanduser("~"), "magneticplayer")
    MPV_SOCKET = r"\\.\pipe\magneticplayer-mpv"
else:
    RUNTIME_DIR = os.environ.get("XDG_RUNTIME_DIR") or f"/tmp/magneticplayer-{os.getuid()}"
    MPV_SOCKET = os.path.join(RUNTIME_DIR, "magneticplayer-mpv.sock")


graphical_env = platform.graphical_env


def socket_exists():
    """Whether mpv has created the IPC socket. On Windows via the pipe list: os.path.exists on a pipe connects to it
    and can return False when all instances are busy."""
    if WINDOWS:
        return os.path.basename(MPV_SOCKET) in os.listdir("\\\\.\\pipe\\")
    return os.path.exists(MPV_SOCKET)


def _iso2(code):
    """"pol" → "pl" (mpv reports a Matroska track's language in ISO 639-2), else the code as it is."""
    try:
        from babelfish import Language
        return Language.fromcode(code, "alpha3b" if len(code) == 3 else "alpha2").alpha2
    except Exception:  # noqa: BLE001  (no babelfish, an unknown code, a language without a 2-letter code)
        return code


def audio_kind(title, visual_impaired=False):
    """The kind of an audio track from its title, as the app reads it (AudioSheet.kt `audioKind`): "commentary",
    "description" (audio description), "lector" (Polish voice-over), "dubbing", "original" or "" (nothing said)."""
    t = (title or "").lower()
    if re.search(r"comment|komentarz", t):
        return "commentary"
    if visual_impaired or re.search(r"audiodeskrypc|audio descr|\bad\b|descriptive", t):
        return "description"
    if re.search(r"lekt|lector|voice.?over|\bvo\b", t):
        return "lector"
    if "dub" in t:
        return "dubbing"
    if "orig" in t:
        return "original"
    return ""


def pick_audio(tracks, pref):
    """The mpv audio track id matching the phone's remembered choice `pref` ("kind|lang", AudioSheet.kt `pickAudio`),
    or None (leave the file's default): the same kind and language, then the same kind ("original" = the track that is
    neither lector nor dubbing), then the same language without description or commentary."""
    kind, _, lang = str(pref).partition("|")
    audio = [t for t in tracks if t.get("type") == "audio"]
    if len(audio) < 2:
        return None
    opts = [(t["id"], audio_kind(t.get("title"), t.get("visual-impaired")), _iso2((t.get("lang") or "").lower()))
            for t in audio]
    plain = [o for o in opts if o[1] not in ("description", "commentary")]
    voiced = any(o[1] in ("lector", "dubbing") for o in opts)
    hit = None
    if kind == "original":
        hit = next((o for o in plain if o[1] == "original"), None) or (
            next((o for o in plain if o[1] == ""), None) if voiced else None)
    elif kind == "":
        hit = next((o for o in plain if o[2] == lang), None)
    else:
        hit = (next((o for o in opts if o[1] == kind and o[2] == lang), None)
               or next((o for o in opts if o[1] == kind), None)
               or next((o for o in plain if o[2] == lang and o[1] == ""), None))
    return hit and hit[0]


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

    def open(self, path, title=None, subs=(), start=None, sub_lang=None, audio=None):
        """A local file or a URL (a video from another server, with a ticket). `subs`: subtitle URLs, the first one is
        selected. `sub_lang` (agent 1.12.1+): the phone's last subtitle choice, a language code or "off". `audio`
        (agent 1.13.3+): the phone's remembered audio choice, "kind|lang" (see `pick_audio`)."""
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
        if subs or start or sub_lang or audio:
            self._after_load(path, subs, start, sub_lang, audio)

    def _after_load(self, path, subs, start, sub_lang=None, audio=None):
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
        if sub_lang:
            self._pick_subs(str(sub_lang))
        if audio:
            try:
                aid = pick_audio(self.prop("track-list") or [], audio)
                if aid is not None:
                    self.cmd("set_property", "aid", aid)
            except ApiError as e:
                log("audio choice", audio, e)
        if start:
            self.cmd("seek", float(start), "absolute")

    def _pick_subs(self, lang):
        """Subtitles off, or the first track in `lang` (mpv reads it from the track or from "<name>.pl.srt")."""
        try:
            if lang == "off":
                self.cmd("set_property", "sid", "no")
                return
            want = lang.lower().split("-")[0]
            for t in self.prop("track-list") or []:
                tl = (t.get("lang") or "").lower().split("-")[0]
                if t.get("type") == "sub" and tl and (tl == want or _iso2(tl) == want):
                    self.cmd("set_property", "sid", t["id"])
                    return
        except ApiError as e:
            log("subtitle language", lang, e)

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
