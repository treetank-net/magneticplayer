"""Library: browsing selected directories of the computer (only video/music + subtitles next to them)."""
import os
import re
import secrets
import stat
import threading

from mp_i18n import T

SEARCH_TTL = 300
_search_cache = {}  # real path of a library folder -> (time, [(relative path, is_dir, size)])
_search_lock = threading.Lock()


def _fold(s):
    """Lowercase without diacritics and punctuation, for search ("Żółw.2016" ~ "zolw 2016")."""
    import unicodedata
    s = unicodedata.normalize("NFKD", s.replace("ł", "l").replace("Ł", "L")).encode("ascii", "ignore").decode().lower()
    return " ".join(re.sub(r"[^a-z0-9]+", " ", s).split())

VIDEO_EXT = {".mkv", ".mp4", ".avi", ".m4v", ".mov", ".webm", ".wmv", ".mpg", ".mpeg", ".ts", ".m2ts", ".flv", ".ogv"}
AUDIO_EXT = {".mp3", ".flac", ".m4a", ".ogg", ".opus", ".wav", ".aac"}
SUB_EXT = {".srt", ".ass", ".ssa", ".vtt", ".sub"}


TAGS = ("filmy", "seriale", "bajki", "kopia")  # suggestions in the app; custom tags are allowed too
HIDDEN_TAGS = {"kopia"}  # a directory with such a tag is not shown in the library (only in management)
VISIBLE = ("all", "owners")  # or a list of device ids

# Who is asking in this thread (the handler sets it after checking the token). None = an internal call or a
# single-file ticket, i.e. access was already checked earlier; directory visibility then does not restrict anything.
_viewer = threading.local()


def set_viewer(device=None, role=None):
    _viewer.v = (device, role) if device else None


def current_viewer():
    return getattr(_viewer, "v", None)


def can_see(r, viewer, manage=False):
    """Whether the device sees the directory. `manage`: management list (a household member then sees all)."""
    if viewer is None:
        return True
    dev, role = viewer
    if manage and role == "owner":
        return True
    if HIDDEN_TAGS & set(r.get("tags") or []):
        return False
    vis = r.get("visible") or "all"
    if vis == "all":
        return True
    if vis == "owners":
        return role == "owner"
    return dev in vis


def excluded(r, rel):
    """Whether the path (relative to the directory) lies in an excluded subdirectory."""
    rel = rel.strip("/")
    return any(rel == x or rel.startswith(x + "/") for x in (e.strip("/") for e in r.get("exclude") or []) if x)


class LibError(Exception):
    def __init__(self, msg, status=400):
        super().__init__(msg)
        self.status = status


def media_kind(name):
    ext = os.path.splitext(name)[1].lower()
    return "video" if ext in VIDEO_EXT else "audio" if ext in AUDIO_EXT else None


def sidecar_subs(full):
    """Subtitles next to the movie: <name>.srt, <name>.pl.srt etc."""
    d, base = os.path.split(full)
    stem = os.path.splitext(base)[0]
    out = []
    try:
        for n in sorted(os.listdir(d)):
            s, ext = os.path.splitext(n)
            if ext.lower() in SUB_EXT and (s == stem or s.startswith(stem + ".")):
                lang = s[len(stem) + 1:] if s != stem else ""
                out.append({"name": n, "lang": lang, "ext": ext.lower()[1:]})
    except OSError:
        pass
    # torrents: Subs/English.srt or Subs/<name>/2_English.srt (name with a path relative to the movie directory)
    seen = set()  # NTFS/exFAT: "subs" and "Subs" are the same directory
    for sd in ("Subs", "subs", "Subtitles", "subtitles", "Sub", "sub"):
        for rel in (sd, os.path.join(sd, stem)):
            try:
                real = os.path.join(d, rel)
                st = os.stat(real)
                if (st.st_dev, st.st_ino) in seen or not os.path.isdir(real):
                    continue
                seen.add((st.st_dev, st.st_ino))
                names = sorted(os.listdir(real))
            except OSError:
                continue
            for n in names:
                s, ext = os.path.splitext(n)
                if ext.lower() in SUB_EXT and os.path.isfile(os.path.join(d, rel, n)):
                    out.append({"name": os.path.join(rel, n), "lang": _lang_guess(s), "ext": ext.lower()[1:]})
    return out


_LANGS = {"english": "en", "eng": "en", "polish": "pl", "pol": "pl", "polski": "pl", "german": "de", "french": "fr",
          "spanish": "es", "italian": "it", "russian": "ru", "portuguese": "pt", "dutch": "nl", "czech": "cs"}


def _lang_guess(s):
    """\"2_English\" → \"en\"; unknown names stay as they are (used as a label)."""
    w = re.sub(r"^[\d_\-. ]+", "", s).strip()
    return _LANGS.get(w.lower().split(" ")[0].split(".")[0], w)


def safe_join(base, rel):
    base = os.path.realpath(base)
    full = os.path.realpath(os.path.join(base, rel.lstrip("/")))
    if full != base and not full.startswith(base + os.sep):
        raise LibError(T("path outside the library", "ścieżka poza biblioteką"), 403)
    return full


class Library:
    def __init__(self, cfg, save_cfg, default_dirs):
        self.cfg = cfg
        self.save_cfg = save_cfg
        self.default_dirs = default_dirs  # () -> list of directories (from the torrent client)

    def roots(self):
        lib = self.cfg.get("library")
        if not lib:  # first run: download directories + ~/Wideo
            seen, lib = set(), []
            cands = list(self.default_dirs()) + [os.path.expanduser(p) for p in ("~/Wideo", "~/Videos", "~/Filmy")]
            downloads = {os.path.realpath(os.path.expanduser(p)) for p in ("~/Downloads", "~/Pobrane")}
            for p in cands:  # without ~/Downloads: everything from the browser lands there, not only movies
                if p and os.path.isdir(p) and os.path.realpath(p) not in seen | downloads:
                    seen.add(os.path.realpath(p))
                    lib.append({"name": os.path.basename(p.rstrip("/")) or p, "path": p})
            self.cfg["library"] = lib
            self.save_cfg()
        if any("id" not in r for r in lib):  # stable identifier (replication; later the API instead of the index)
            for r in lib:
                r.setdefault("id", secrets.token_hex(3))
            self.save_cfg()
        return [dict(r, index=i, exists=os.path.isdir(r["path"]), tags=r.get("tags") or [], visible=r.get("visible") or "all",
                     exclude=r.get("exclude") or []) for i, r in enumerate(lib)]

    def visible_roots(self, manage=False):
        """Directories visible to the asking device (indexes stay global)."""
        v = current_viewer()
        return [dict(r, open=can_see(r, v)) for r in self.roots() if can_see(r, v, manage)]

    def root(self, i):
        roots = self.roots()
        if not 0 <= i < len(roots) or not can_see(roots[i], current_viewer()):
            raise LibError(T("no such library folder", "nie ma takiego katalogu biblioteki"), 404)
        if not roots[i]["exists"]:
            raise LibError(T(f"folder {roots[i]['path']} is unavailable (disk disconnected?)",
                             f"katalog {roots[i]['path']} niedostępny (dysk odłączony?)"), 404)
        return roots[i]["path"]

    def resolve(self, i, rel):
        base = self.root(i)
        full = safe_join(base, rel or "")
        if current_viewer() is not None and excluded(self.roots()[i], os.path.relpath(full, os.path.realpath(base))):
            raise LibError(T("no such file", "nie ma takiego pliku"), 404)
        return full

    def browse(self, i, rel, show_all=False):
        full = self.resolve(i, rel)
        if not os.path.isdir(full):
            raise LibError(T("not a folder", "to nie katalog"), 400)
        dirs, files = [], []
        # os.stat instead of DirEntry.is_dir()/stat(): in Python 3.10 (jmpc) those call stat() without releasing the GIL, and a
        # waking-up old USB disk (ntfs-3g) takes a few seconds to respond; the whole agent then froze, /api/ping included
        for e in os.scandir(full):
            if e.name.startswith("."):
                continue
            try:
                st = os.stat(e.path)
                if stat.S_ISDIR(st.st_mode):
                    dirs.append(e.name)
                elif stat.S_ISREG(st.st_mode):
                    kind = media_kind(e.name)
                    if kind or show_all:
                        files.append({"name": e.name, "size": st.st_size, "mtime": st.st_mtime, "media": kind,
                                      "subs": [s["lang"] or s["ext"] for s in sidecar_subs(e.path)] if kind == "video" else []})
            except OSError:
                continue
        if current_viewer() is not None:
            r = self.roots()[i]
            dirs = [d for d in dirs if not excluded(r, f"{(rel or '').strip('/')}/{d}")]
        dirs.sort(key=str.lower)
        files.sort(key=lambda f: f["name"].lower())
        return {"root": i, "path": (rel or "").strip("/"), "dirs": dirs, "files": files}

    def add_root(self, path, name=None):
        path = os.path.realpath(os.path.expanduser(path))
        if not os.path.isdir(path):
            raise LibError(T("no such folder", "nie ma takiego katalogu"), 404)
        self.roots()
        if any(os.path.realpath(r["path"]) == path for r in self.cfg["library"]):
            raise LibError(T("this folder is already in the library", "ten katalog już jest w bibliotece"), 409)
        self.cfg["library"].append({"id": secrets.token_hex(3), "name": name or os.path.basename(path) or path, "path": path})
        self.save_cfg()

    def update_root(self, i, b):
        """Changes the name, tags, visibility and exclusions of a directory (only the given fields)."""
        self.roots()
        if not 0 <= i < len(self.cfg["library"]):
            raise LibError(T("no such folder", "nie ma takiego katalogu"), 404)
        r = self.cfg["library"][i]
        if "name" in b:
            name = str(b["name"] or "").strip()[:80]
            if not name:
                raise LibError(T("the name can't be empty", "nazwa nie może być pusta"))
            r["name"] = name
        if "tags" in b:
            tags = []
            for t in b["tags"] or []:
                t = str(t).strip().lower()[:30]
                if t and t not in tags:
                    tags.append(t)
            r["tags"] = tags[:10]
        if "visible" in b:
            v = b["visible"]
            if isinstance(v, list):
                v = [str(x) for x in v][:50]
            elif v not in VISIBLE:
                raise LibError(T("visible must be all, owners or a list of devices",
                                 "widoczność: all, owners albo lista urządzeń"))
            r["visible"] = v
        if "exclude" in b:
            r["exclude"] = [x for x in (str(e).strip().strip("/") for e in b["exclude"] or []) if x and ".." not in x.split("/")][:100]
        self.save_cfg()

    def targets(self, tag=None, title=None, kind=None):
        """Where to download: directories the asker can see and write to (without `kopia`), with free space.
        `pick`: the directory with the tag from identification; a series goes where it already is (its subdirectory), otherwise where there is most free space."""
        import shutil
        from mp_ident import norm
        v, out, key = current_viewer(), [], norm(title or "")
        for r in self.roots():
            if not r["exists"] or set(r["tags"]) & HIDDEN_TAGS or not can_see(r, v) or not os.access(r["path"], os.W_OK):
                continue
            try:
                free = shutil.disk_usage(r["path"]).free
            except OSError:
                continue
            t = {"root": r["index"], "id": r["id"], "name": r["name"], "path": r["path"], "tags": r["tags"],
                 "free": free, "sub": "", "existing": False}
            if kind == "series" and key and tag in r["tags"]:
                try:
                    names = os.listdir(r["path"])
                except OSError:
                    names = []
                d = next((n for n in names if norm(n) == key and os.path.isdir(os.path.join(r["path"], n))), None)
                if d:
                    t.update(sub=d, existing=True)
            out.append(t)
        pick = None
        if tag:
            cand = [i for i, t in enumerate(out) if tag in t["tags"]]
            if cand:
                pick = max(cand, key=lambda i: (out[i]["existing"], out[i]["free"]))
                t = out[pick]
                if kind == "series" and title and not t["sub"]:
                    t["sub"] = re.sub(r'[\\/:*?"<>|]+', " ", title).strip(" .")[:100]  # new series: Seriale/<title>/
        return {"targets": out, "pick": pick}

    def dest(self, i, sub=""):
        """Download directory inside a library directory (created if missing)."""
        full = self.resolve(i, sub or "")
        os.makedirs(full, exist_ok=True)
        return full

    def remove_root(self, i):
        self.roots()
        if not 0 <= i < len(self.cfg["library"]):
            raise LibError(T("no such folder", "nie ma takiego katalogu"), 404)
        del self.cfg["library"][i]
        self.save_cfg()

    # ---- search

    def _names(self, r):
        """All folders and media files under a library folder as (relative path, is_dir, size), cached for a few
        minutes so typing a query doesn't spin up USB disks on every letter. os.listdir + os.stat (not DirEntry),
        see browse()."""
        import time
        key = os.path.realpath(r["path"])
        with _search_lock:
            hit = _search_cache.get(key)
            if hit and time.time() - hit[0] < SEARCH_TTL:
                return hit[1]
        out, stack, t0 = [], [""], time.time()
        while stack and len(out) < 200000 and time.time() - t0 < 60:
            rel = stack.pop()
            full = os.path.join(r["path"], rel)
            try:
                names = os.listdir(full)
            except OSError:
                continue
            for n in names:
                if n.startswith("."):
                    continue
                p = f"{rel}/{n}" if rel else n
                try:
                    st = os.stat(os.path.join(full, n))
                except OSError:
                    continue
                if stat.S_ISDIR(st.st_mode):
                    out.append((p, True, 0))
                    stack.append(p)
                elif stat.S_ISREG(st.st_mode) and media_kind(n):
                    out.append((p, False, st.st_size))
        with _search_lock:
            _search_cache[key] = (time.time(), out)
        return out

    def search(self, q, limit=100):
        """Folders and media files whose name (or folder path) contains every word of the query; case and
        diacritics don't matter. Only folders the asking device sees, without excluded subfolders."""
        words = _fold(q).split()
        if not words:
            return []
        v, res = current_viewer(), []
        for r in self.roots():
            if not r["exists"] or not can_see(r, v) or set(r["tags"]) & HIDDEN_TAGS:
                continue
            for p, is_dir, size in self._names(r):
                folded = _fold(p)
                if not all(w in folded for w in words):
                    continue
                if not any(w in _fold(p.rsplit("/", 1)[-1]) for w in words):
                    continue  # at least one word in the item's own name, not only in its parent folders
                if v is not None and excluded(r, p):
                    continue
                res.append({"root": r["index"], "root_name": r["name"], "path": p, "dir": is_dir, "size": size,
                            "media": None if is_dir else media_kind(p)})
        # whole-name matches and shallow items first, folders before their files
        res.sort(key=lambda x: (x["path"].count("/"), not x["dir"], x["path"].lower()))
        return res[:limit]

    @staticmethod
    def fs_dirs(path):
        """List of subdirectories for choosing a new library directory."""
        if not path:
            user = os.environ.get("USER", "")
            starts = [os.path.expanduser("~")] + [os.path.join(b, n) for b in (f"/media/{user}", "/mnt")
                                                  if os.path.isdir(b) for n in sorted(os.listdir(b))]
            return {"path": "", "parent": None, "dirs": [p for p in starts if os.path.isdir(p)]}
        path = os.path.realpath(path)
        try:
            dirs = sorted((e.path for e in os.scandir(path)  # os.path.isdir releases the GIL (see browse)
                           if not e.name.startswith(".") and os.path.isdir(e.path)), key=str.lower)
        except OSError as e:
            raise LibError(T(f"can't open: {e.strerror}", f"nie da się otworzyć: {e.strerror}"), 403)
        return {"path": path, "parent": os.path.dirname(path) if path != "/" else "", "dirs": dirs}
