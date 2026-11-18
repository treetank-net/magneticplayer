"""Biblioteka: przeglądanie wybranych katalogów komputera (tylko filmy/muzyka + napisy obok)."""
import os
import stat
import re

from mp_i18n import T

VIDEO_EXT = {".mkv", ".mp4", ".avi", ".m4v", ".mov", ".webm", ".wmv", ".mpg", ".mpeg", ".ts", ".m2ts", ".flv", ".ogv"}
AUDIO_EXT = {".mp3", ".flac", ".m4a", ".ogg", ".opus", ".wav", ".aac"}
SUB_EXT = {".srt", ".ass", ".ssa", ".vtt", ".sub"}


class LibError(Exception):
    def __init__(self, msg, status=400):
        super().__init__(msg)
        self.status = status


def media_kind(name):
    ext = os.path.splitext(name)[1].lower()
    return "video" if ext in VIDEO_EXT else "audio" if ext in AUDIO_EXT else None


def sidecar_subs(full):
    """Napisy leżące obok filmu: <nazwa>.srt, <nazwa>.pl.srt itd."""
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
    # torrenty: Subs/English.srt albo Subs/<nazwa>/2_English.srt (nazwa ze ścieżką względem katalogu filmu)
    seen = set()  # NTFS/exFAT: „subs” i „Subs” to ten sam katalog
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
    """„2_English” → „en”; nieznane zostaje jak jest (do etykiety)."""
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
        self.default_dirs = default_dirs  # () -> lista katalogów (z klienta torrentów)

    def roots(self):
        lib = self.cfg.get("library")
        if not lib:  # pierwsze uruchomienie: katalogi pobierania + ~/Wideo
            seen, lib = set(), []
            cands = list(self.default_dirs()) + [os.path.expanduser(p) for p in ("~/Wideo", "~/Videos", "~/Filmy")]
            for p in cands:
                if p and os.path.isdir(p) and os.path.realpath(p) not in seen:
                    seen.add(os.path.realpath(p))
                    lib.append({"name": os.path.basename(p.rstrip("/")) or p, "path": p})
            self.cfg["library"] = lib
            self.save_cfg()
        return [dict(r, index=i, exists=os.path.isdir(r["path"])) for i, r in enumerate(lib)]

    def root(self, i):
        roots = self.roots()
        if not 0 <= i < len(roots):
            raise LibError(T("no such library folder", "nie ma takiego katalogu biblioteki"), 404)
        if not roots[i]["exists"]:
            raise LibError(T(f"folder {roots[i]['path']} is unavailable (disk disconnected?)",
                             f"katalog {roots[i]['path']} niedostępny (dysk odłączony?)"), 404)
        return roots[i]["path"]

    def resolve(self, i, rel):
        return safe_join(self.root(i), rel or "")

    def browse(self, i, rel, show_all=False):
        full = self.resolve(i, rel)
        if not os.path.isdir(full):
            raise LibError(T("not a folder", "to nie katalog"), 400)
        dirs, files = [], []
        # os.stat zamiast DirEntry.is_dir()/stat(): te w Pythonie 3.10 (jmpc) robią stat() bez zwolnienia GIL, a budzący się
        # stary dysk USB (ntfs-3g) odpowiada po kilka sekund; cały agent stał wtedy w miejscu, także /api/ping
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
        self.cfg["library"].append({"name": name or os.path.basename(path) or path, "path": path})
        self.save_cfg()

    def remove_root(self, i):
        self.roots()
        if not 0 <= i < len(self.cfg["library"]):
            raise LibError(T("no such folder", "nie ma takiego katalogu"), 404)
        del self.cfg["library"][i]
        self.save_cfg()

    @staticmethod
    def fs_dirs(path):
        """Lista podkatalogów do wybierania nowego katalogu biblioteki."""
        if not path:
            user = os.environ.get("USER", "")
            starts = [os.path.expanduser("~")] + [os.path.join(b, n) for b in (f"/media/{user}", "/mnt")
                                                  if os.path.isdir(b) for n in sorted(os.listdir(b))]
            return {"path": "", "parent": None, "dirs": [p for p in starts if os.path.isdir(p)]}
        path = os.path.realpath(path)
        try:
            dirs = sorted((e.path for e in os.scandir(path)  # os.path.isdir: zwalnia GIL (patrz browse)
                           if not e.name.startswith(".") and os.path.isdir(e.path)), key=str.lower)
        except OSError as e:
            raise LibError(T(f"can't open: {e.strerror}", f"nie da się otworzyć: {e.strerror}"), 403)
        return {"path": path, "parent": os.path.dirname(path) if path != "/" else "", "dirs": dirs}
