"""Uploading movies from the phone to the server (resumable).

The phone sends the file in chunks: `PUT /api/upload/<id>?name=&size=&offset=` (body = chunk), and after an
interruption asks `GET /api/upload/<id>` for `received` and continues from that point. Chunks go to
`<target directory>/.mp-upload/<id>.part` (same disk, so a rename is enough at the end), and next to it lies `<id>.json`
with the name, the size and, once finished, the final name (so that a request repeated after a dropped connection
gets "done" instead of starting over). A directory starting with a dot is not shown in the Library.

Target directory: `upload_dir` from config.json, by default "Z telefonu" (a data value, the folder name) in the first library directory.

History (uploads.json in the config directory): every finished upload with the phone it came from, so the Downloads
screen can list files that landed on the server and would otherwise be forgotten there. Each one is "new" until the
user moves it into the library (to the folder identification suggests, like a magnet) or decides to keep it where it is.
Video files found in the target directory without a history entry (sent before 1.8.0) are listed as "new" too.
"""
import hashlib
import json
import os
import re
import shutil
import threading
import time

from mp_i18n import T
from mp_library import LibError, safe_join

DEFAULT_SUBDIR = "Z telefonu"
STATE_DIR = ".mp-upload"
RESERVE = 512 << 20  # free space we leave on the disk on top of what is still to come
CHUNK = 1 << 20
READ_TIMEOUT = 60  # seconds of silence during the body, then we drop the connection (the phone left Wi-Fi) so the thread doesn't hang
DONE_TTL = 7 * 86400  # how long we remember finished uploads (a repeated request gets "done")
ID_RE = re.compile(r"[A-Za-z0-9_-]{8,80}")


class UploadError(Exception):
    def __init__(self, msg, status=400, extra=None):
        super().__init__(msg)
        self.status = status
        self.extra = extra or {}


def clean_name(name):
    """Bare file name: no directories, control characters or leading dot (hidden file)."""
    name = (name or "").replace("\\", "/").rsplit("/", 1)[-1]
    name = re.sub(r"[\x00-\x1f\x7f]", "", name).strip().lstrip(".").strip()
    if not name:
        raise UploadError(T("missing file name", "brak nazwy pliku"), 400)
    if len(name.encode()) > 240:  # ext4: 255 bytes, we leave room for " (2)"
        stem, ext = os.path.splitext(name)
        while len((stem + ext).encode()) > 240:
            stem = stem[:-1]
        name = stem + ext
    return name


def free_name(directory, name):
    """\"film.mkv\", or when taken: \"film (2).mkv\", \"film (3).mkv\"..."""
    stem, ext = os.path.splitext(name)
    cand, n = name, 1
    while os.path.lexists(safe_join(directory, cand)):
        n += 1
        cand = f"{stem} ({n}){ext}"
    return cand


class Uploads:
    def __init__(self, cfg, library, log, history=None):
        self.cfg = cfg
        self.library = library
        self.log = log
        self._locks = {}
        self._guard = threading.Lock()
        self.history_path = history
        self.hlock = threading.Lock()
        try:
            with open(history) as f:
                self.history = json.load(f)
        except (OSError, TypeError, ValueError):
            self.history = []

    # ---- target directory
    def target_dir(self):
        """Directory for uploaded movies, or None (no `upload_dir` and no existing library directory)."""
        d = self.cfg.get("upload_dir")
        if d:
            return os.path.realpath(os.path.expanduser(d))
        try:
            roots = [r for r in self.library.roots() if r["exists"] and not {"kopia"} & set(r["tags"])]
        except LibError:
            roots = []
        return os.path.join(os.path.realpath(roots[0]["path"]), DEFAULT_SUBDIR) if roots else None

    def available(self):
        return self.target_dir() is not None

    def _dirs(self):
        target = self.target_dir()
        if not target:
            raise UploadError(T("the server has no folder for uploaded files (no upload_dir or library folder)",
                                "serwer nie ma katalogu na przesłane pliki (upload_dir ani katalogu biblioteki)"), 409)
        state = os.path.join(target, STATE_DIR)
        try:
            os.makedirs(state, exist_ok=True)
        except OSError as e:
            raise UploadError(T(f"can't create folder {target}: {e.strerror}", f"nie da się założyć katalogu {target}: {e.strerror}"), 500)
        return target, state

    def _lock(self, uid):
        with self._guard:
            return self._locks.setdefault(uid, threading.Lock())

    @staticmethod
    def _check_id(uid):
        if not ID_RE.fullmatch(uid or ""):
            raise UploadError(T("invalid upload id", "zły identyfikator przesyłania"), 400)

    @staticmethod
    def _read_meta(path):
        try:
            with open(path) as f:
                return json.load(f)
        except (OSError, ValueError):
            return None

    @staticmethod
    def _write_meta(path, meta):
        tmp = path + ".tmp"
        with open(tmp, "w") as f:
            json.dump(meta, f, ensure_ascii=False)
        os.replace(tmp, path)

    def _cleanup(self, state):
        """Forgets finished uploads after a week and abandoned chunks after a month."""
        now = time.time()
        try:
            names = os.listdir(state)
        except OSError:
            return
        for n in names:
            if not n.endswith(".json"):
                continue
            p = os.path.join(state, n)
            m = self._read_meta(p) or {}
            age = now - (m.get("done_at") or m.get("updated") or 0)
            if (m.get("done") and age > DONE_TTL) or (not m.get("done") and age > 4 * DONE_TTL):
                for x in (p, p[:-5] + ".part"):
                    try:
                        os.remove(x)
                    except OSError:
                        pass

    @staticmethod
    def _discard(rfile, length, connection):
        """Unneeded body: a small one is read to the end (the connection stays), for a large one we drop the connection."""
        if length > 16 * CHUNK:
            raise UploadError(T("file already uploaded", "plik już przesłany"), 409, {"done": True})
        connection.settimeout(READ_TIMEOUT)
        while length > 0:
            b = rfile.read(min(CHUNK, length))
            if not b:
                break
            length -= len(b)

    # ---- API
    def status(self, uid):
        """GET /api/upload/<id> → {received, size, done, name}; 404 when the server knows nothing about it."""
        self._check_id(uid)
        target, state = self._dirs()
        meta = self._read_meta(os.path.join(state, uid + ".json"))
        if not meta:
            raise UploadError(T("no such upload", "nie ma takiego przesyłania"), 404, {"received": 0})
        if meta.get("done"):
            return {"received": meta["size"], "size": meta["size"], "done": True, "name": meta.get("final")}
        try:
            received = os.path.getsize(os.path.join(state, uid + ".part"))
        except OSError:
            received = 0
        return {"received": received, "size": meta["size"], "done": False, "name": meta.get("name")}

    def put(self, uid, q, rfile, length, connection, device=None):
        """PUT /api/upload/<id>?name=&size=&offset=: appends the body to .part; the last chunk finishes the file."""
        self._check_id(uid)
        try:
            size = int(q.get("size", ""))
            offset = int(q.get("offset", "0"))
        except ValueError:
            raise UploadError(T("size and offset must be numbers", "size i offset muszą być liczbami"), 400)
        if length is None:
            raise UploadError(T("missing Content-Length", "brak Content-Length"), 411)
        if size < 0 or offset < 0 or length < 0 or offset + length > size:
            raise UploadError(T("chunk goes past the file size", "kawałek wychodzi poza rozmiar pliku"), 400)
        name = clean_name(q.get("name"))
        target, state = self._dirs()
        lock = self._lock(uid)
        # the previous connection for the same file may still be hanging (the phone lost Wi-Fi): wait until it expires
        if not lock.acquire(timeout=READ_TIMEOUT + 10):
            raise UploadError(T("this file is being uploaded right now", "ten plik jest właśnie przesyłany"), 409)
        try:
            meta_p = os.path.join(state, uid + ".json")
            part_p = os.path.join(state, uid + ".part")
            meta = self._read_meta(meta_p)
            if meta and meta.get("done"):
                if meta.get("size") == size:  # already done (the previous response never arrived)
                    self._discard(rfile, length, connection)
                    return {"received": size, "size": size, "done": True, "name": meta.get("final")}
                meta = None  # same id, different file: start over
            if meta and meta.get("size") != size:
                meta = None
            received = os.path.getsize(part_p) if meta and os.path.exists(part_p) else 0
            if offset != received:
                raise UploadError(T(f"the server has {received} B, not {offset}", f"serwer ma {received} B, nie {offset}"), 409, {"received": received})
            need = size - received
            free = shutil.disk_usage(target).free
            if need + RESERVE > free:
                raise UploadError(T(f"not enough space on the server ({free >> 20} MB free, {need >> 20} MB needed)",
                                    f"za mało miejsca na serwerze (wolne {free >> 20} MB, potrzeba {need >> 20} MB)"), 507,
                                  {"received": received, "free": free})
            if not meta:
                meta = {"name": name, "size": size, "started": time.time(), "device": device}
                with open(part_p, "wb"):
                    pass
                self._cleanup(state)
                self.log(f"upload from phone: {name} ({size >> 20} MB)")
            meta.update(name=name, updated=time.time())
            self._write_meta(meta_p, meta)
            # streamed to disk; an interrupted connection leaves what arrived (the next PUT starts at `received`)
            old_timeout = connection.gettimeout()
            connection.settimeout(READ_TIMEOUT)
            left = length
            try:
                with open(part_p, "r+b") as f:
                    f.seek(received)
                    while left > 0:
                        try:
                            chunk = rfile.read(min(CHUNK, left))
                        except OSError:  # dropped, or silence longer than READ_TIMEOUT
                            chunk = b""
                        if not chunk:
                            break
                        f.write(chunk)
                        left -= len(chunk)
            finally:
                try:
                    connection.settimeout(old_timeout)
                except OSError:
                    pass
            received = os.path.getsize(part_p)
            if left > 0:
                raise UploadError(T("connection interrupted", "połączenie przerwane w trakcie"), 400, {"received": received})
            if received < size:
                return {"received": received, "size": size, "done": False, "name": name}
            final = free_name(target, name)
            os.replace(part_p, safe_join(target, final))
            meta.update(done=True, done_at=time.time(), final=final)
            self._write_meta(meta_p, meta)
            self._remember({"id": uid, "name": final, "dir": target, "device": meta.get("device") or device,
                            "size": size, "at": time.time(), "state": "new"})
            self.log(f"uploaded from phone: {final} → {target}")
            return {"received": size, "size": size, "done": True, "name": final}
        finally:
            lock.release()

    # ---- history: what landed on the server and where it went

    def _save_history(self):
        if not self.history_path:
            return
        tmp = self.history_path + ".tmp"
        with open(tmp, "w") as f:
            json.dump(self.history[-500:], f, ensure_ascii=False)
        os.replace(tmp, self.history_path)

    def _remember(self, entry):
        with self.hlock:
            self.history = [h for h in self.history if h["id"] != entry["id"]] + [entry]
            self._save_history()

    def _update(self, hid, **kw):
        with self.hlock:
            for h in self.history:
                if h["id"] == hid:
                    h.update(kw)
            self._save_history()

    def list(self):
        """History entries (newest first) + video files in the target directory that have none (sent earlier).
        A "new" entry whose file is gone (moved or deleted by hand) becomes "gone"."""
        from mp_library import VIDEO_EXT
        target = self.target_dir()
        with self.hlock:
            hist = [dict(h) for h in self.history]
        known = {(h["dir"], h["name"]) for h in hist}
        for h in hist:
            if h["state"] == "new" and not os.path.isfile(os.path.join(h["dir"], h["name"])):
                h["state"] = "gone"
        if target:
            try:
                names = os.listdir(target)
            except OSError:
                names = []
            for n in names:
                full = os.path.join(target, n)
                if n.startswith(".") or (target, n) in known or os.path.splitext(n)[1].lower() not in VIDEO_EXT:
                    continue
                try:
                    st = os.stat(full)
                except OSError:
                    continue
                hist.append({"id": "f-" + hashlib.sha1(full.encode()).hexdigest()[:16], "name": n, "dir": target,
                             "device": None, "size": st.st_size, "at": st.st_mtime, "state": "new", "untracked": True})
        hist.sort(key=lambda h: -(h.get("moved_at") or h["at"]))
        return hist

    def entry(self, hid):
        h = next((h for h in self.list() if h["id"] == hid), None)
        if not h:
            raise UploadError(T("no such file", "nie ma takiego pliku"), 404)
        if h.get("untracked"):  # acting on an older file: it gets a history entry from now on
            h = {k: v for k, v in h.items() if k != "untracked"}
            self._remember(h)
        return h

    def keep(self, hid):
        h = self.entry(hid)
        self._update(h["id"], state="kept", moved_at=time.time())
        return {"ok": True}

    def move(self, hid, dest, where):
        """Moves the file (and subtitles named like it) into a library folder in the background; a move to another
        disk is a copy, so the entry shows "moving" until it is done."""
        from mp_library import sidecar_subs
        h = self.entry(hid)
        if h["state"] == "moving":
            raise UploadError(T("already being moved", "już jest przenoszony"), 409)
        src = os.path.join(h["dir"], h["name"])
        if not os.path.isfile(src):
            raise UploadError(T("the file is no longer there", "pliku już tam nie ma"), 404)
        extra = [s["name"] for s in sidecar_subs(src) if "/" not in s["name"]]
        if shutil.disk_usage(dest).free < os.path.getsize(src) + RESERVE and os.stat(dest).st_dev != os.stat(src).st_dev:
            raise UploadError(T("not enough space there", "za mało tam miejsca"), 507)
        self._update(h["id"], state="moving", to=where)

        def run():
            try:
                final = free_name(dest, h["name"])
                shutil.move(src, os.path.join(dest, final))
                for n in extra:
                    try:
                        shutil.move(os.path.join(h["dir"], n), os.path.join(dest, free_name(dest, n)))
                    except OSError:
                        pass
                self._update(h["id"], state="moved", moved_at=time.time(), to=where, path=os.path.join(dest, final))
                self.log(f"uploaded file moved: {h['name']} → {dest}")
            except OSError as e:
                self._update(h["id"], state="new", error=str(e))
                self.log(f"uploaded file: move failed: {e}")
            try:
                self.library.invalidate()
            except Exception:  # noqa: BLE001
                pass
        threading.Thread(target=run, daemon=True).start()
        return {"ok": True}


def handle(handler, method, uid, q):
    """Handles /api/upload/<id> in the agent Handler (token already checked)."""
    up = handler.app.uploads
    try:
        if method == "GET":
            return handler.send_json(up.status(uid))
        if method == "PUT":
            n = handler.headers.get("Content-Length")
            dev = next((d["name"] for d in handler.app.devices.list() if d["id"] == handler.device), None) \
                if getattr(handler, "device", None) else None
            res = up.put(uid, q, handler.rfile, int(n) if n and n.isdigit() else None, handler.connection, dev)
            return handler.send_json(res)
        raise UploadError(T("no such endpoint", "nie ma takiego endpointu"), 404)
    except UploadError as e:
        if method == "PUT":
            # the body may have been left unread: without closing, the next request on this connection would be garbage
            handler.close_connection = True
        try:
            handler.send_json({"error": str(e), **e.extra}, e.status)
        except OSError:
            pass
