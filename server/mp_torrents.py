"""Torrent clients behind one interface (Deluge, Transmission, none) and what can be watched of a file still
downloading (contiguous pieces, the video's duration)."""
import json
import os
import re
import shutil
import subprocess
import threading
import time
import urllib.request
from http.cookiejar import CookieJar

from mp_i18n import T
from mp_core import ApiError


VIDEO_EXT = {".mkv", ".mp4", ".avi", ".m4v", ".mov", ".webm", ".wmv", ".mpg", ".mpeg", ".ts", ".m2ts", ".flv", ".ogv"}
AUDIO_EXT = {".mp3", ".flac", ".m4a", ".ogg", ".opus", ".wav", ".aac"}


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

    def add_torrent(self, data, filename, sequential, dest=None, priorities=None, renames=None, name=None, tid=None):
        """A .torrent file (bytes). Added paused with `priorities` (Deluge file_priorities, 0 = skip) already in the add
        options, so nothing unwanted starts downloading from a fast web seed; checked and set again if Deluge didn't
        take them, the files renamed (`renames`: [(index, old path, new path)]), then resumed. `name`: the name shown in
        the client. `tid`: the info hash, to return the torrent already in the client instead of an error."""
        import base64
        if tid and self.call("core.get_torrent_status", tid, ["name"]):
            return tid
        opts = {"sequential_download": bool(sequential), "prioritize_first_last_pieces": bool(sequential), "add_paused": True}
        if dest:
            opts["download_location"] = dest
        if priorities:
            opts["file_priorities"] = list(priorities)
        if name:
            opts["name"] = name
        new = self.call("core.add_torrent_file", filename, base64.b64encode(data).decode(), opts) or tid
        if not new:
            raise ApiError(T("the torrent client didn't take the torrent", "klient torrentów nie przyjął torrenta"), 502)
        try:
            if priorities:
                st = self.call("core.get_torrent_status", new, ["file_priorities"]) or {}
                if list(st.get("file_priorities") or []) != list(priorities):
                    self.call("core.set_torrent_options", [new], {"file_priorities": list(priorities)})
            if renames:
                self.call("core.rename_files", new, [[i, path] for i, _old, path in renames])
        finally:
            self.call("core.resume_torrent", [new])
        return new

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

    def add_torrent(self, data, filename, sequential, dest=None, priorities=None, renames=None, name=None, tid=None):
        """A .torrent file (bytes): added paused with the skipped files unwanted, renamed, then started. Transmission
        renames one path component at a time: the file's name, then its top folder."""
        import base64
        args = {"metainfo": base64.b64encode(data).decode(), "paused": True}
        if dest:
            args["download-dir"] = dest
        if sequential:
            args["sequential_download"] = True  # Transmission >= 4.1, older versions ignore it
        if priorities:
            args["files-unwanted"] = [i for i, p in enumerate(priorities) if not p]
        r = self.call("torrent-add", args)
        added = r.get("torrent-added")
        new = (added or r.get("torrent-duplicate") or {}).get("hashString") or tid
        if added and new:
            try:
                for _i, old, path in renames or []:
                    old_parts, new_parts = old.split("/"), path.split("/")
                    if new_parts[-1] != old_parts[-1]:
                        self.call("torrent-rename-path", {"ids": [new], "path": old, "name": new_parts[-1]})
                    if len(old_parts) == len(new_parts) == 2 and new_parts[0] != old_parts[0]:
                        self.call("torrent-rename-path", {"ids": [new], "path": old_parts[0], "name": new_parts[0]})
            finally:
                self.call("torrent-start", {"ids": [new]})
        return new

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

    def add_torrent(self, data, filename, sequential, dest=None, priorities=None, renames=None, name=None, tid=None):
        raise ApiError(T("this server doesn't download torrents", "ten serwer nie pobiera torrentów"), 409)

    def move(self, tid, dest):
        raise ApiError(T("this server doesn't download torrents", "ten serwer nie pobiera torrentów"), 409)

    def files(self, tid):
        raise ApiError(T("this server doesn't download torrents", "ten serwer nie pobiera torrentów"), 409)

    def action(self, tid, action):
        raise ApiError(T("this server doesn't download torrents", "ten serwer nie pobiera torrentów"), 409)
