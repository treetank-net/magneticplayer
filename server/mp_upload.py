"""Przesyłanie filmów z telefonu na serwer (wznawialne).

Telefon wysyła plik kawałkami: `PUT /api/upload/<id>?name=&size=&offset=` (ciało = kawałek), a po przerwie pyta
`GET /api/upload/<id>` o `received` i ciągnie dalej od tego miejsca. Kawałki trafiają do
`<katalog docelowy>/.mp-upload/<id>.part` (ten sam dysk, więc na końcu wystarczy rename), a obok leży `<id>.json`
z nazwą, rozmiarem i, po zakończeniu, nazwą końcową (żeby powtórzone po zerwanym połączeniu zapytanie dostało
„gotowe”, a nie zaczynało od nowa). Katalog z kropką nie pokazuje się w Bibliotece.

Katalog docelowy: `upload_dir` z config.json, domyślnie „Z telefonu” w pierwszym katalogu biblioteki.
"""
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
RESERVE = 512 << 20  # tyle zostawiamy wolnego na dysku ponad to, co jeszcze przyjdzie
CHUNK = 1 << 20
READ_TIMEOUT = 60  # s ciszy w trakcie ciała, potem zrywamy (telefon wyszedł z Wi-Fi), wątek nie wisi
DONE_TTL = 7 * 86400  # tyle pamiętamy zakończone przesyłania (powtórzone zapytanie dostaje „gotowe”)
ID_RE = re.compile(r"[A-Za-z0-9_-]{8,80}")


class UploadError(Exception):
    def __init__(self, msg, status=400, extra=None):
        super().__init__(msg)
        self.status = status
        self.extra = extra or {}


def clean_name(name):
    """Sama nazwa pliku: bez katalogów, znaków sterujących i kropki na początku (ukryty plik)."""
    name = (name or "").replace("\\", "/").rsplit("/", 1)[-1]
    name = re.sub(r"[\x00-\x1f\x7f]", "", name).strip().lstrip(".").strip()
    if not name:
        raise UploadError(T("missing file name", "brak nazwy pliku"), 400)
    if len(name.encode()) > 240:  # ext4: 255 bajtów, zostawiamy miejsce na „ (2)”
        stem, ext = os.path.splitext(name)
        while len((stem + ext).encode()) > 240:
            stem = stem[:-1]
        name = stem + ext
    return name


def free_name(directory, name):
    """„film.mkv”, a gdy zajęte: „film (2).mkv”, „film (3).mkv”…"""
    stem, ext = os.path.splitext(name)
    cand, n = name, 1
    while os.path.lexists(safe_join(directory, cand)):
        n += 1
        cand = f"{stem} ({n}){ext}"
    return cand


class Uploads:
    def __init__(self, cfg, library, log):
        self.cfg = cfg
        self.library = library
        self.log = log
        self._locks = {}
        self._guard = threading.Lock()

    # ---- katalog docelowy
    def target_dir(self):
        """Katalog na przesłane filmy albo None (brak `upload_dir` i żadnego istniejącego katalogu biblioteki)."""
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
        """Zapomina zakończone przesyłania po tygodniu, a porzucone kawałki po miesiącu."""
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
        """Niepotrzebne ciało: małe czytamy do końca (połączenie zostaje), przy dużym zrywamy połączenie."""
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
        """GET /api/upload/<id> → {received, size, done, name}; 404, gdy serwer nic o nim nie wie."""
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

    def put(self, uid, q, rfile, length, connection):
        """PUT /api/upload/<id>?name=&size=&offset=: dopisuje ciało do .part; ostatni kawałek kończy plik."""
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
        # poprzednie połączenie tego samego pliku może jeszcze wisieć (telefon zgubił Wi-Fi): czekamy, aż wygaśnie
        if not lock.acquire(timeout=READ_TIMEOUT + 10):
            raise UploadError(T("this file is being uploaded right now", "ten plik jest właśnie przesyłany"), 409)
        try:
            meta_p = os.path.join(state, uid + ".json")
            part_p = os.path.join(state, uid + ".part")
            meta = self._read_meta(meta_p)
            if meta and meta.get("done"):
                if meta.get("size") == size:  # już gotowe (odpowiedź poprzednio nie doszła)
                    self._discard(rfile, length, connection)
                    return {"received": size, "size": size, "done": True, "name": meta.get("final")}
                meta = None  # ten sam id, inny plik: od nowa
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
                meta = {"name": name, "size": size, "started": time.time()}
                with open(part_p, "wb"):
                    pass
                self._cleanup(state)
                self.log(f"przesyłanie z telefonu: {name} ({size >> 20} MB)")
            meta.update(name=name, updated=time.time())
            self._write_meta(meta_p, meta)
            # strumieniowo na dysk; przerwane połączenie zostawia to, co doszło (następny PUT od `received`)
            old_timeout = connection.gettimeout()
            connection.settimeout(READ_TIMEOUT)
            left = length
            try:
                with open(part_p, "r+b") as f:
                    f.seek(received)
                    while left > 0:
                        try:
                            chunk = rfile.read(min(CHUNK, left))
                        except OSError:  # zerwane albo cisza dłuższa niż READ_TIMEOUT
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
            self.log(f"przesłano z telefonu: {final} → {target}")
            return {"received": size, "size": size, "done": True, "name": final}
        finally:
            lock.release()


def handle(handler, method, uid, q):
    """Obsługa /api/upload/<id> w Handlerze agenta (token sprawdzony wcześniej)."""
    up = handler.app.uploads
    try:
        if method == "GET":
            return handler.send_json(up.status(uid))
        if method == "PUT":
            n = handler.headers.get("Content-Length")
            res = up.put(uid, q, handler.rfile, int(n) if n and n.isdigit() else None, handler.connection)
            return handler.send_json(res)
        raise UploadError(T("no such endpoint", "nie ma takiego endpointu"), 404)
    except UploadError as e:
        if method == "PUT":
            # ciało mogło zostać nieprzeczytane: bez zamknięcia następne zapytanie w tym połączeniu byłoby śmieciem
            handler.close_connection = True
        try:
            handler.send_json({"error": str(e), **e.extra}, e.status)
        except OSError:
            pass
