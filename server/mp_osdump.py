"""Subtitles from the OpenSubtitles.org archive on the Internet Archive (agent 1.22+), a subtitle provider without
limits.

archive.org keeps a copy of OpenSubtitles.org as of 2022-07-25 ("opensubtitles.org Actually Open Edition", 5.7
million subtitles): `opensubs.db`, one 130 GB SQLite file with one table `subz(num INTEGER PRIMARY KEY, name, file)`
(`num` = OpenSubtitles' IDSubtitle, `file` = the zip OpenSubtitles served), and `subtitles_all.txt.gz` (308 MB),
OpenSubtitles' own export of what each IDSubtitle is (IMDb id, language, release, frame rate, season/episode).

The export is downloaded once and turned into a small local index (`osdump-index.db`: IMDb id / series + episode /
title + year → IDSubtitle). One subtitle is then read straight out of the remote database with a few HTTP Range
requests (~6 requests, ~40 KB, a few seconds): page 1 gives the table's root, the B-tree is walked by rowid, the
row's zip comes from its overflow pages (usually consecutive, one request). The interior pages are cached in memory.
A local copy of `opensubs.db` (the item's torrent, if archive.org ever drops it) can be named in `osarchive.local`;
then it's read with sqlite3 directly.

Owner's rule (2026-11-26): archive.org is treated as an open source; this is a provider like any other, tried first
by the automatic subtitles. Newer than July 2022 isn't there: the other providers cover that."""
import gzip
import io
import json
import os
import re
import sqlite3
import struct
import threading
import time
import urllib.error
import urllib.request
import zipfile

from mp_i18n import T

ITEM = "https://archive.org/download/subscene-arabic-subtitles/opensubtitles.org.Actually.Open.Edition.2022.07.25/"
DEFAULTS = {
    "enabled": True,
    "db_url": ITEM + "opensubs.db",
    "index_url": ITEM + "subtitles_all.txt.gz",
    "local": "",  # a local copy of opensubs.db (optional)
    "dir": "",  # where the index lives ("" = the config folder); e.g. a hidden folder on a big data disk
    "languages": [],  # only these languages in the index ([] = all, ~0.7 GB; ["en", "pl"] is about a fifth)
}
UA = {"User-Agent": "MagneticPlayer (home media agent; https://gitlab.com/treetank/magneticplayer)"}
NAME = "osarchive"
INDEX_V = 2
_state = {"app_cfg": {}, "dir": None, "log": print, "building": False, "error": None, "progress": None}


def settings(cfg):
    s = dict(DEFAULTS)
    s.update({k: v for k, v in (cfg.get("osarchive") or {}).items() if k in DEFAULTS})
    return s


def _cfg():
    return settings(_state["app_cfg"])


def setup(cfg, config_dir, log):
    """Called once by the agent: where the index lives, and its build in the background when it's missing."""
    _state.update(app_cfg=cfg, dir=config_dir, log=log)
    if _cfg()["enabled"] and not ready():
        threading.Timer(90, build).start()  # after the agent's start-up work


def changed():
    """The settings changed: start the move or rebuild at once rather than at the next search."""
    if _cfg()["enabled"] and not ready() and not _state["building"]:
        threading.Thread(target=build, daemon=True).start()


def _index_dir():
    return os.path.expanduser(_cfg().get("dir") or "") or _state["dir"] or "."


def _index_path():
    return os.path.join(_index_dir(), "osdump-index.db")


def _dir_missing():
    """The chosen folder's disk isn't there (an unplugged or unmounted USB disk): the provider waits for it instead of
    building a new index elsewhere. The folder itself is created when its parent exists."""
    d = _index_dir()
    if os.path.isdir(d):
        return False
    parent = os.path.dirname(d.rstrip("/\\"))
    if parent and os.path.isdir(parent):
        try:
            os.makedirs(d, exist_ok=True)
            return False
        except OSError:
            pass
    return True


def _where_file():
    return os.path.join(_state["dir"] or ".", "osdump-where.txt")


def _remember_dir():
    """The folder a ready index was last seen in (the next `dir` change moves it from there)."""
    try:
        with open(_where_file()) as f:
            if f.read().strip() == _index_dir():
                return
    except OSError:
        pass
    try:
        with open(_where_file(), "w") as f:
            f.write(_index_dir())
    except OSError:
        pass


def _move_old():
    """After `dir` changed: move the index from where it was (the last folder it was ready in, or the config folder)
    instead of downloading the export again."""
    new = _index_path()
    if os.path.exists(new):
        return False
    places = [_state["dir"] or "."]
    try:
        with open(_where_file()) as f:
            places.insert(0, f.read().strip())
    except OSError:
        pass
    for d in places:
        old = os.path.join(d, "osdump-index.db")
        if old == new or not os.path.exists(old):
            continue
        import shutil
        _state["progress"] = "move"
        shutil.move(old, new + ".tmp")
        os.replace(new + ".tmp", new)
        for junk in (new + ".src.gz", new + ".tmp-journal"):  # a download the move made unnecessary
            try:
                os.remove(junk)
            except OSError:
                pass
        _state["log"](f"osarchive: index moved from {d} to {_index_dir()}")
        return ready()
    return False


def _ver():
    """The index's version: the format, plus the languages when only some are kept (a change rebuilds it)."""
    langs = sorted({str(x).lower() for x in _cfg().get("languages") or []})
    return str(INDEX_V) + ("/" + ",".join(langs) if langs else "")


def ready():
    p = _index_path()
    if not os.path.exists(p):
        return False
    try:
        with sqlite3.connect(f"file:{p}?mode=ro", uri=True) as c:
            ok = c.execute("SELECT v FROM meta WHERE k='version'").fetchone()[0] == _ver()
        if ok:
            _remember_dir()
        return ok
    except sqlite3.Error:
        return False


def status():
    return {"enabled": _cfg()["enabled"], "ready": ready(), "building": _state["building"],
            "progress": _state["progress"], "error": _state["error"]}


def norm(t):
    return re.sub(r"[^a-z0-9]+", " ", (t or "").lower()).strip()


def name_hash(t):
    """A title as a number for the index (a CRC of its normalised form: 4 bytes instead of the text, and with the year
    a collision doesn't matter)."""
    import zlib
    return zlib.crc32(norm(t).encode())


def build():
    """Downloads the export and writes the index (a few minutes; the old index stays until the new one is complete)."""
    if _state["building"]:
        return
    if _dir_missing():
        _state["error"] = f"the folder {_index_dir()} isn't available (disk not connected?)"
        return
    _state.update(building=True, error=None, progress="download")
    try:
        if _move_old():
            _state.update(building=False, progress=None)
            return
    except OSError as e:
        _state["log"]("osarchive: moving the index failed, building it anew:", e)
        _state["progress"] = "download"
    tmp = _index_path() + ".tmp"
    gz = _index_path() + ".src.gz"
    log = _state["log"]
    try:
        t0 = time.time()
        req = urllib.request.Request(_cfg()["index_url"], headers=UA)
        with urllib.request.urlopen(req, timeout=60) as r, open(gz, "wb") as out:
            while True:
                b = r.read(1 << 20)
                if not b:
                    break
                out.write(b)
        _state["progress"] = "index"
        if os.path.exists(tmp):
            os.remove(tmp)
        c = sqlite3.connect(tmp)
        c.executescript("""
            CREATE TABLE meta (k TEXT PRIMARY KEY, v TEXT);
            CREATE TABLE subs (id INTEGER PRIMARY KEY, imdb INTEGER, lang TEXT, nh INTEGER, year INTEGER,
                               rel TEXT, fps REAL, kind TEXT, season INTEGER, episode INTEGER, parent INTEGER);
        """)
        rows, n = [], 0
        only = {str(x).lower() for x in _cfg().get("languages") or []}
        with gzip.open(gz, "rt", encoding="utf-8", errors="replace") as f:
            head = f.readline().rstrip("\n").split("\t")
            col = {k: i for i, k in enumerate(head)}
            for line in f:
                x = line.rstrip("\n").split("\t")
                if len(x) < len(head):
                    continue
                try:
                    if x[col["SubSumCD"]] != "1":  # split over several CDs: useless for one file
                        continue
                    if only and x[col["ISO639"]] not in only:
                        continue
                    kind = x[col["MovieKind"]]
                    name = x[col["MovieName"]]
                    if kind == "episode":  # '"Series" Episode title'
                        m = re.match(r'^"([^"]+)"', name)
                        name = m.group(1) if m else name
                    rel = x[col["MovieReleaseName"]].strip()[:100]
                    rows.append((int(x[col["IDSubtitle"]]), int(x[col["ImdbID"]] or 0), x[col["ISO639"]][:3],
                                 name_hash(name), int(x[col["MovieYear"]] or 0),
                                 rel if norm(rel) != norm(name) else "", float(x[col["MovieFPS"]] or 0) or None,
                                 kind[:1], int(x[col["SeriesSeason"]] or 0) or None,
                                 int(x[col["SeriesEpisode"]] or 0) or None, int(x[col["SeriesIMDBParent"]] or 0) or None))
                except (ValueError, KeyError):
                    continue
                if len(rows) >= 50000:
                    c.executemany("INSERT OR REPLACE INTO subs VALUES (?,?,?,?,?,?,?,?,?,?,?)", rows)
                    n += len(rows)
                    rows = []
                    time.sleep(0.01)  # let the agent's threads breathe
            c.executemany("INSERT OR REPLACE INTO subs VALUES (?,?,?,?,?,?,?,?,?,?,?)", rows)
            n += len(rows)
        c.executescript("""
            CREATE INDEX by_imdb ON subs (imdb, lang);
            CREATE INDEX by_ep ON subs (parent, season, episode, lang);
            CREATE INDEX by_name ON subs (nh, year);
        """)
        c.execute("INSERT INTO meta VALUES ('version', ?)", (_ver(),))
        c.execute("INSERT INTO meta VALUES ('built', ?)", (str(int(time.time())),))
        c.commit()
        c.execute("VACUUM")
        c.close()
        os.replace(tmp, _index_path())
        log(f"osarchive: index of {n} subtitles built in {time.time() - t0:.0f} s")
    except Exception as e:  # noqa: BLE001 — no network, no disk space: searches skip this provider
        _state["error"] = str(e)[:300]
        log("osarchive: index build failed:", e)
    finally:
        for p in (gz, tmp):
            try:
                os.remove(p)
            except OSError:
                pass
        _state.update(building=False, progress=None)


def lookup(langs, imdb=None, parent=None, season=None, episode=None, title=None, year=None, limit=40):
    """Index rows for a film (IMDb id, else title + year) or an episode (series IMDb id + season/episode, else the
    series' title)."""
    if not ready():
        return []
    langs = list(langs)
    q = ",".join("?" * len(langs))
    with sqlite3.connect(f"file:{_index_path()}?mode=ro", uri=True) as c:
        c.row_factory = sqlite3.Row
        if season and episode:
            if parent:
                rows = c.execute(f"SELECT * FROM subs WHERE parent=? AND season=? AND episode=? AND lang IN ({q}) LIMIT ?",
                                 (parent, season, episode, *langs, limit)).fetchall()
                if rows:
                    return [dict(r) for r in rows]
            if title:
                return [dict(r) for r in c.execute(
                    f"SELECT * FROM subs WHERE nh=? AND kind='e' AND season=? AND episode=? AND lang IN ({q}) LIMIT ?",
                    (name_hash(title), season, episode, *langs, limit))]
            return []
        if imdb:
            rows = c.execute(f"SELECT * FROM subs WHERE imdb=? AND lang IN ({q}) LIMIT ?", (imdb, *langs, limit)).fetchall()
            if rows:
                return [dict(r) for r in rows]
        if title:
            if year:
                sql = f"SELECT * FROM subs WHERE nh=? AND year BETWEEN ? AND ? AND kind!='e' AND lang IN ({q}) LIMIT ?"
                args = (name_hash(title), int(year) - 1, int(year) + 1, *langs, limit)
            else:
                sql = f"SELECT * FROM subs WHERE nh=? AND kind!='e' AND lang IN ({q}) LIMIT ?"
                args = (name_hash(title), *langs, limit)
            return [dict(r) for r in c.execute(sql, args)]
    return []


# ---------------------------------------------------------------- reading one row of the remote database

class _Remote:
    """A read-only SQLite table read by rowid over HTTP Range (or from a local copy)."""
    PAGES_KEPT = 4000  # interior pages cached (16 MB)

    def __init__(self):
        self.lock = threading.Lock()
        self.cache, self.url, self.U, self.root, self.src = {}, None, None, None, None

    def _get(self, a, n):
        if self.src and self.src.startswith("file:"):
            with open(self.src[5:], "rb") as f:
                f.seek(a)
                d = f.read(n)
        else:
            req = urllib.request.Request(self.url, headers={**UA, "Range": f"bytes={a}-{a + n - 1}"})
            with urllib.request.urlopen(req, timeout=60) as r:
                if r.status != 206:
                    raise IOError(f"archive.org answered HTTP {r.status} to a range request")
                self.url = r.geturl()  # the storage node archive.org redirected to
                d = r.read(n + 1)
        if len(d) != n:
            raise IOError(f"short read ({len(d)} of {n} bytes)")
        return d

    def _page(self, no):
        p = self.cache.get(no)
        if p is None:
            p = self._get((no - 1) * self.U, self.U)
            if len(self.cache) < self.PAGES_KEPT and p[100 if no == 1 else 0] in (0x05, 0x02):
                self.cache[no] = p
        return p

    def _open(self):
        s = _cfg()
        src = ("file:" + s["local"]) if s.get("local") and os.path.isfile(s["local"]) else s["db_url"]
        if src == self.src and self.root:
            return
        self.cache, self.src = {}, src
        self.url = None if src.startswith("file:") else src
        head = self._get(0, 100)
        if head[:16] != b"SQLite format 3\x00":
            raise IOError("not an SQLite database")
        self.U = struct.unpack(">H", head[16:18])[0] or 65536
        p1 = self._page(1)
        if p1[100] != 0x0D:
            raise IOError("unexpected schema page")
        for k in range(struct.unpack(">H", p1[103:105])[0]):
            off = struct.unpack(">H", p1[108 + 2 * k:110 + 2 * k])[0]
            size, i = _varint(p1, off)
            _, i = _varint(p1, i)
            v = _record(self._payload(p1, i, size))
            if v[0] == "table" and v[1] == "subz":
                self.root = v[3]
        if not self.root:
            raise IOError("no subz table")

    def _payload(self, p, i, size):
        U = self.U
        X = U - 35
        if size <= X:
            return p[i:i + size]
        M = ((U - 12) * 32 // 255) - 23
        K = M + ((size - M) % (U - 4))
        local = K if K <= X else M
        out = bytearray(p[i:i + local])
        nxt = struct.unpack(">I", p[i + local:i + local + 4])[0]
        while nxt and len(out) < size:
            start = nxt
            need = (size - len(out) + U - 5) // (U - 4)
            blob = self._get((start - 1) * U, need * U)  # overflow pages are usually consecutive: one request
            for k in range(need):
                pg = blob[k * U:(k + 1) * U]
                out += pg[4:]
                nxt = struct.unpack(">I", pg[:4])[0]
                if len(out) >= size or not nxt:
                    break
                if nxt != start + k + 1:
                    break  # not consecutive after all: the next round fetches from there
        if len(out) < size:
            raise IOError("row cut short")
        return bytes(out[:size])

    def row(self, rowid):
        with self.lock:
            self._open()
            no = self.root
            for _ in range(64):
                p = self._page(no)
                h = 100 if no == 1 else 0
                kind, ncell = p[h], struct.unpack(">H", p[h + 3:h + 5])[0]
                if kind == 0x05:  # interior: (left child, largest rowid in it) in order, then the right-most child
                    nxt = struct.unpack(">I", p[h + 8:h + 12])[0]
                    lo, hi = 0, ncell - 1
                    while lo <= hi:  # first cell whose key >= rowid
                        mid = (lo + hi) // 2
                        off = struct.unpack(">H", p[h + 12 + 2 * mid:h + 14 + 2 * mid])[0]
                        key, _ = _varint(p, off + 4)
                        if key >= rowid:
                            nxt = struct.unpack(">I", p[off:off + 4])[0]
                            hi = mid - 1
                        else:
                            lo = mid + 1
                    no = nxt
                elif kind == 0x0D:
                    for k in range(ncell):
                        off = struct.unpack(">H", p[h + 8 + 2 * k:h + 10 + 2 * k])[0]
                        size, i = _varint(p, off)
                        key, i = _varint(p, i)
                        if key == rowid:
                            return _record(self._payload(p, i, size))
                    return None
                else:
                    raise IOError(f"page {no}: unexpected type {kind}")
            raise IOError("the tree is too deep")


def _varint(b, i):
    v = 0
    for k in range(9):
        c = b[i + k]
        if k == 8:
            return (v << 8) | c, i + 9
        v = (v << 7) | (c & 0x7F)
        if c < 0x80:
            return v, i + k + 1


def _record(b):
    hl, i = _varint(b, 0)
    types = []
    while i < hl:
        t, i = _varint(b, i)
        types.append(t)
    vals, j = [], hl
    for t in types:
        if t == 0:
            vals.append(None)
        elif 1 <= t <= 6:
            n = (0, 1, 2, 3, 4, 6, 8)[t]
            vals.append(int.from_bytes(b[j:j + n], "big", signed=True))
            j += n
        elif t == 7:
            vals.append(struct.unpack(">d", b[j:j + 8])[0])
            j += 8
        elif t in (8, 9):
            vals.append(t - 8)
        elif t >= 12:
            n = (t - 12) // 2 if t % 2 == 0 else (t - 13) // 2
            v = b[j:j + n]
            j += n
            vals.append(v if t % 2 == 0 else v.decode("utf-8", "replace"))
        else:
            raise IOError(f"serial type {t}")
    return vals


_remote = _Remote()


def fetch(sub_id):
    """The subtitle file's bytes (the largest subtitle inside the zip OpenSubtitles served)."""
    row = _remote.row(int(sub_id))
    if not row or not isinstance(row[2], (bytes, bytearray)):
        raise IOError(f"subtitle {sub_id} isn't in the archive")
    blob = bytes(row[2])
    try:
        z = zipfile.ZipFile(io.BytesIO(blob))
    except zipfile.BadZipFile:
        return blob
    names = [n for n in z.namelist() if re.search(r"\.(srt|sub|txt|ssa|ass|smi|vtt|mpl)$", n, re.I)]
    if not names:
        raise IOError("no subtitle file in the archive's zip")
    return z.read(max(names, key=lambda n: z.getinfo(n).file_size))


# ---------------------------------------------------------------- the subliminal provider

def register(subliminal):
    """Adds the provider to subliminal (once)."""
    try:
        subliminal.provider_manager.register(f"{NAME} = mp_osdump:OsArchiveProvider")
    except ValueError:
        pass  # already registered


def _classes():
    from babelfish import Language
    from subliminal.matches import guess_matches
    from subliminal.providers import Provider
    from subliminal.subtitle import Subtitle
    from subliminal.video import Episode, Movie
    from guessit import guessit

    class OsArchiveSubtitle(Subtitle):
        provider_name = NAME

        def __init__(self, language, row):
            super().__init__(language, str(row["id"]), fps=row["fps"] or None)
            self.row = row

        @property
        def info(self):
            return self.row["rel"] or self.row.get("shown") or ""

        def get_matches(self, video):
            r, m = self.row, set()
            if isinstance(video, Episode):
                if r["parent"] and str(r["parent"]) == str((video.external_ids or {}).get("series_imdb_id") or "").lstrip("t"):
                    m.add("series_imdb_id")
                if r["nh"] == name_hash(video.series):
                    m.add("series")
                if r["season"] == video.season:
                    m.add("season")
                if video.episode == r["episode"] or (isinstance(video.episode, list) and r["episode"] in video.episode):
                    m.add("episode")
                if r["imdb"] and str(r["imdb"]) == str((video.external_ids or {}).get("imdb_id") or "").lstrip("t"):
                    m.add("imdb_id")
            else:
                if r["imdb"] and str(r["imdb"]) == str((video.external_ids or {}).get("imdb_id") or "").lstrip("t"):
                    m.add("imdb_id")
                if r["nh"] == name_hash(video.title):
                    m.add("title")
                if r["year"] and video.year == r["year"]:
                    m.add("year")
            if r["rel"]:
                try:
                    m |= guess_matches(video, guessit(r["rel"], {"type": "episode" if isinstance(video, Episode) else "movie"}))
                except Exception:  # noqa: BLE001
                    pass
            return m

    class OsArchiveProvider(Provider):
        languages = frozenset()  # set below: every language babelfish knows by 2 letters
        video_types = (Episode, Movie)
        subtitle_class = OsArchiveSubtitle

        def initialize(self):
            pass

        def terminate(self):
            pass

        def query(self, video, languages):
            from subliminal.exceptions import ServiceUnavailable
            if not _cfg()["enabled"]:
                return []
            if _dir_missing():
                raise ServiceUnavailable("the archive's index folder isn't available (disk not connected?)")
            if not ready():
                if not _state["building"]:
                    threading.Thread(target=build, daemon=True).start()
                raise ServiceUnavailable("the archive's index is being prepared (first use)")
            codes = {}
            for lang in languages:
                try:
                    codes[lang.alpha2] = lang
                except Exception:  # noqa: BLE001 — no two-letter code: the archive files by those
                    pass
            imdb = lambda x: int(str(x).lstrip("t")) if x and str(x).lstrip("t").isdigit() else None  # noqa: E731
            if isinstance(video, Episode):
                rows = lookup(codes, parent=imdb((video.external_ids or {}).get("series_imdb_id")), season=video.season,
                              episode=video.episode if isinstance(video.episode, int) else (video.episode or [None])[0],
                              title=video.series)
            else:
                rows = lookup(codes, imdb=imdb((video.external_ids or {}).get("imdb_id")), title=video.title, year=video.year)
            for r in rows:  # the index keeps no release name equal to the title: show the title then
                if not r["rel"]:
                    r["shown"] = (f"{video.series} S{r['season'] or 0:02d}E{r['episode'] or 0:02d}" if isinstance(video, Episode)
                                  else f"{video.title} ({r['year']})" if r["year"] else str(video.title))
            return [OsArchiveSubtitle(codes[r["lang"]], r) for r in rows if r["lang"] in codes]

        def list_subtitles(self, video, languages):
            return self.query(video, languages)

        def download_subtitle(self, subtitle):
            subtitle.content = fetch(subtitle.subtitle_id)

    langs = set()
    from babelfish import LANGUAGES
    for code in LANGUAGES:
        try:
            l2 = Language(code)
            if l2.alpha2:
                langs.add(l2)
        except Exception:  # noqa: BLE001 — no two-letter code
            continue
    OsArchiveProvider.languages = frozenset(langs)
    return OsArchiveSubtitle, OsArchiveProvider


def __getattr__(name):  # subliminal loads "mp_osdump:OsArchiveProvider" lazily
    if name in ("OsArchiveProvider", "OsArchiveSubtitle"):
        sub, prov = _classes()
        globals().update(OsArchiveSubtitle=sub, OsArchiveProvider=prov)
        return globals()[name]
    raise AttributeError(name)
