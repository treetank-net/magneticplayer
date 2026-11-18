"""Identification of movies and series: name (guessit) + content + a small local IMDb index.

Index: the free IMDb dumps (datasets.imdbws.com, personal and non-commercial use) converted to SQLite, holding only
movies and series with at least MIN_VOTES votes. Genres are a bit mask (GENRES), titles are normalised (`key`) for
fast matching. A few MB instead of 230 MB, works offline.

Uncertain cases (missing from the index, title known only in Polish, animation without "Family") are completed from
Wikidata: age ratings (FSK, MPAA), "animated film", "children's film". Only the title and year are sent to Wikidata,
and the result is kept in an in-memory cache. User choices (changing the directory after identification) are
remembered by `Service.learn`.
"""
import gzip
import io
import json
import os
import re
import sqlite3
import sys
import threading
import time
import unicodedata
import urllib.parse
import urllib.request

DATASETS = "https://datasets.imdbws.com/"
MIN_VOTES = 300
KINDS = {"movie": 0, "tvMovie": 0, "tvSeries": 1, "tvMiniSeries": 1}  # 0 movie, 1 series
GENRES = ["Action", "Adult", "Adventure", "Animation", "Biography", "Comedy", "Crime", "Documentary", "Drama",
          "Family", "Fantasy", "Film-Noir", "Game-Show", "History", "Horror", "Music", "Musical", "Mystery", "News",
          "Reality-TV", "Romance", "Sci-Fi", "Short", "Sport", "Talk-Show", "Thriller", "War", "Western"]
G = {g: 1 << i for i, g in enumerate(GENRES)}

VIDEO = {".mkv", ".mp4", ".avi", ".m4v", ".mov", ".webm", ".wmv", ".mpg", ".mpeg", ".ts", ".m2ts", ".flv", ".ogv"}
AUDIO = {".mp3", ".flac", ".m4a", ".ogg", ".opus", ".wav", ".aac"}
OTHER = {".iso", ".dmg", ".exe", ".deb", ".zip", ".rar", ".7z", ".cbr", ".cbz", ".pdf", ".epub"}
EPISODE = re.compile(r"(?i)\bS\d{1,2}\s?E\d{1,3}\b|\b\d{1,2}x\d{2}\b|(?:^|[\s._-])E(?:p(?:isode)?)?\s?\d{1,3}(?:[\s._-]|$)|\bSeason\s?\d")


def genres(mask):
    return [g for g in GENRES if mask & G[g]]


STOP = {"the", "a", "an", "and"}


def norm(s):
    """Title key: lowercase without accents, punctuation, spaces and the words the/a/an/and, so that "12:01" from the
    disk matches "1201" and a stray "The" in the release name does not spoil the match."""
    s = unicodedata.normalize("NFKD", s).encode("ascii", "ignore").decode().lower().replace("&", " and ")
    return "".join(w for w in re.sub(r"[^a-z0-9]+", " ", s).split() if w not in STOP)


# ---- index

def _lines(url):
    with urllib.request.urlopen(url, timeout=60) as r:
        with gzip.GzipFile(fileobj=r) as g:
            for line in io.TextIOWrapper(g, encoding="utf-8"):
                yield line.rstrip("\n").split("\t")


def build(path, src=DATASETS, log=print):
    """Downloads the dumps and builds the index at `path` (atomic replace). `src`: URL or a directory with .tsv.gz files."""
    t0 = time.time()
    get = (lambda f: _lines(src + f)) if src.startswith("http") else (lambda f: _local(os.path.join(src, f)))
    votes = {}
    for row in get("title.ratings.tsv.gz"):
        if row[0].startswith("tt") and row[2].isdigit() and int(row[2]) >= MIN_VOTES:
            votes[row[0]] = int(row[2])
    tmp = path + ".tmp"
    if os.path.exists(tmp):
        os.remove(tmp)
    db = sqlite3.connect(tmp)
    db.executescript("""
        CREATE TABLE t (id INTEGER PRIMARY KEY, kind INTEGER, title TEXT, year INTEGER, end INTEGER, g INTEGER, votes INTEGER);
        CREATE TABLE k (key TEXT, id INTEGER);
    """)
    n = 0
    for row in get("title.basics.tsv.gz"):
        if row[0] not in votes or row[1] not in KINDS or row[4] == "1":  # isAdult
            continue
        tid = int(row[0][2:])
        mask = sum(G.get(x, 0) for x in row[8].split(","))
        db.execute("INSERT INTO t VALUES (?,?,?,?,?,?,?)", (tid, KINDS[row[1]], row[2], _int(row[5]), _int(row[6]), mask, votes[row[0]]))
        keys = {norm(row[2]), norm(row[3])}
        db.executemany("INSERT INTO k VALUES (?,?)", [(k, tid) for k in keys if k])
        n += 1
    db.execute("CREATE INDEX k_key ON k(key)")
    db.commit()
    db.execute("VACUUM")
    db.close()
    os.replace(tmp, path)
    log(f"IMDb index: {n} titles, {os.path.getsize(path) // 1024} KB, {time.time() - t0:.0f} s")
    return n


def _local(p):
    with gzip.open(p, "rt", encoding="utf-8") as f:
        for line in f:
            yield line.rstrip("\n").split("\t")


def _int(x):
    return int(x) if x.isdigit() else None


class Index:
    def __init__(self, path):
        self.path = path
        self.db = sqlite3.connect(path, check_same_thread=False) if os.path.exists(path) else None

    def get(self, imdb_id):
        if not self.db:
            return None
        r = self.db.execute("SELECT * FROM t WHERE id=?", (int(str(imdb_id).lstrip("t")),)).fetchone()
        return _row(r) if r else None

    def find(self, title, year=None, kind=None):
        """Best title: exact key first, then year (±1) and kind, finally popularity."""
        if not self.db or not title:
            return None
        rows = self.db.execute("SELECT t.* FROM k JOIN t USING(id) WHERE k.key=?", (norm(title),)).fetchall()
        if not rows:
            return None

        def score(r):
            s = r[6] ** 0.3  # popularity: gently
            if year and r[3]:
                s += 1000 if r[3] == year else 400 if abs(r[3] - year) == 1 else -300
            if kind is not None and r[1] == kind:
                s += 150
            return s
        return _row(max(rows, key=score))


def _row(r):
    return {"imdb": f"tt{r[0]:07d}", "kind": "series" if r[1] else "movie", "title": r[2], "year": r[3], "end": r[4],
            "genres": genres(r[5]), "votes": r[6]}


# ---- identification

def content(files):
    """What is inside: list of (path, size) → kind of content and number of episodes."""
    vids = [(p, s) for p, s in files if os.path.splitext(p)[1].lower() in VIDEO and "sample" not in p.lower()]
    audio = sum(s for p, s in files if os.path.splitext(p)[1].lower() in AUDIO)
    other = sum(s for p, s in files if os.path.splitext(p)[1].lower() in OTHER)
    vsize = sum(s for _, s in vids)
    total = max(1, vsize + audio + other)
    if audio / total > 0.6:
        return {"what": "music"}
    if other / total > 0.6:
        return {"what": "other"}
    if not vids:
        return {"what": "leftover"}  # only subtitles and images, e.g. after the movie was deleted
    big = [(p, s) for p, s in vids if s > 0.15 * max(s for _, s in vids)]
    eps = [p for p, _ in big if EPISODE.search(os.path.basename(p)) or EPISODE.search(p)]
    return {"what": "video", "videos": len(big), "episodes": len(eps)}


# ---- Wikidata

WD_API = "https://www.wikidata.org/w/api.php"
UA = {"User-Agent": "MagneticPlayer (home media agent; https://gitlab.com/treetank/magneticplayer)"}
WD_FILM = {"Q11424", "Q202866", "Q20650540", "Q24869", "Q506240", "Q229390", "Q24862", "Q17517379", "Q93204"}
WD_SERIES = {"Q5398426", "Q581714", "Q63952888", "Q117467246", "Q1259759", "Q526877", "Q21191270", "Q15416"}
WD_ANIM = {"Q202866", "Q20650540", "Q581714", "Q63952888", "Q117467246", "Q11425", "Q17517379", "Q1107"}
WD_KIDS = {"Q2143665", "Q1273568", "Q4006590"}  # children's film, children's series, children's programme
WD_FAMILY = {"Q1361932"}
# age ratings → minimum age: FSK 0/6/12/16/18, MPAA G/PG/PG-13/R/NC-17
WD_AGE = {"Q20644794": 0, "Q20644795": 6, "Q20644796": 12, "Q20644797": 16, "Q20644798": 18,
          "Q18665330": 0, "Q18665334": 6, "Q18665339": 12, "Q18665344": 16, "Q18665349": 18}


def _wd(params, timeout=8):
    u = WD_API + "?" + urllib.parse.urlencode(dict(params, format="json"))
    with urllib.request.urlopen(urllib.request.Request(u, headers=UA), timeout=timeout) as r:
        return json.load(r)


def wikidata(title, year=None, kind=None):
    """Movie/series from Wikidata by title (Polish or original) and year. None when there is no confident candidate."""
    cands = []
    for lang in ("pl", "en"):
        r = _wd({"action": "wbsearchentities", "search": title, "language": lang, "uselang": lang, "type": "item", "limit": 7})
        ids = [x["id"] for x in r.get("search", [])]
        if not ids:
            continue
        ents = _wd({"action": "wbgetentities", "ids": "|".join(ids), "props": "claims|labels", "languages": "pl|en"})["entities"]
        for qid in ids:
            e = ents.get(qid) or {}
            c = e.get("claims", {})

            def vals(prop):
                return [x["mainsnak"].get("datavalue", {}).get("value") for x in c.get(prop, [])]

            def items(prop):
                return {v["id"] for v in vals(prop) if isinstance(v, dict) and "id" in v}
            p31, p136 = items("P31"), items("P136")
            k = "movie" if p31 & WD_FILM else "series" if p31 & WD_SERIES else None
            if not k:
                continue
            years = [int(v["time"][1:5]) for p in ("P577", "P580") for v in vals(p) if isinstance(v, dict) and v.get("time", "")[1:5].isdigit()]
            ages = [WD_AGE[q] for q in items("P1981") | items("P1657") if q in WD_AGE]
            label = (e.get("labels", {}).get("pl") or e.get("labels", {}).get("en") or {}).get("value") or title
            cands.append({"qid": qid, "kind": k, "title": label, "year": min(years) if years else None,
                          "imdb": next((v for v in vals("P345") if isinstance(v, str) and v.startswith("tt")), None),
                          "animated": bool((p31 | p136) & WD_ANIM), "children": bool(p136 & WD_KIDS),
                          "family": bool(p136 & WD_FAMILY), "age": max(ages) if ages else None})
        if cands:
            break

    def score(c):
        s = 50 if c["imdb"] else 0
        if year and c["year"]:
            s += 1000 if c["year"] == year else 400 if abs(c["year"] - year) == 1 else -1000
        if kind and c["kind"] == kind:
            s += 150
        return s
    best = max(cands, key=score, default=None)
    if best and year and best["year"] and abs(best["year"] - year) > 1:
        return None  # a different film with the same title: better nothing than a wrong suggestion
    return best


def wd_kids(w):
    """Whether it is for children according to Wikidata: True/False, or None when there is no data."""
    if w["children"]:
        return True
    if w["age"] is not None:
        return w["age"] <= 6 if w["animated"] else w["age"] == 0 and w["family"]
    return True if w["animated"] and w["family"] else None


def _guessit(name):
    try:
        import guessit  # in the agent venv (a subliminal dependency)
        return dict(guessit.guessit(name))
    except ImportError:  # without guessit: title up to the year, season/episode from the name
        n = re.sub(r"[._]", " ", name)
        m = re.search(r"\b(19\d\d|20\d\d)\b", n)
        g = {"title": re.split(r"[\[(]?\b(?:19|20)\d\d\b|\bS\d{1,2}E\d|\b(?:720|1080|2160)p\b", n)[0].strip(" -[("),
             "type": "episode" if EPISODE.search(n) else "movie"}
        if m:
            g["year"] = int(m.group(1))
        s = re.search(r"(?i)\bS(\d{1,2})|Season\s?(\d+)", n)
        if s:
            g["season"] = int(s.group(1) or s.group(2))
        return g


def mem_key(r):
    return r.get("imdb") or ("t:" + norm(r.get("title") or "") if r.get("title") else None)


def identify(name, files=None, index=None, nfo_text=None, wd=None, memory=None):
    """Name (+ file list, + .nfo text) → proposal: tag, title, year, IMDb, confidence and reasons.
    `wd(title, year, kind)`: Wikidata for uncertain cases (or None offline), `memory`: {key: tag} from user choices."""
    why = []
    c = content(files) if files else {"what": "video", "videos": 1, "episodes": 0}
    if c["what"] == "music":
        return {"tag": "muzyka", "confidence": "high", "why": ["audio"]}
    if c["what"] == "other":
        return {"tag": "inne", "confidence": "high", "why": ["programs, archives, comics"]}
    if c["what"] == "leftover":
        return {"tag": None, "confidence": "high", "why": ["no videos: not moving"]}
    g = _guessit(name)
    title, year = g.get("title"), g.get("year")
    # series only from episode markers: a large number of files alone may also be a movie collection
    series_hint = (g.get("type") == "episode" and "episode" in g or "season" in g) or c.get("episodes", 0) >= 2
    if series_hint:
        why.append(f"episodes ({c.get('episodes', 0)}/{c.get('videos', 0)})" if files else "name looks like an episode")
    hit = None
    # guessit can be fooled by a number at the start of the title (takes it for an episode number): second try with the text before the year
    alt = re.split(r"[\[(]?\b(?:19|20)\d\d\b", re.sub(r"[._]", " ", name))[0].strip(" -[(") if year else None
    if index:
        m = re.search(r"tt\d{7,8}", nfo_text or "")
        if m:
            hit = index.get(m.group(0))
            if hit:
                why.append("IMDb from .nfo")
        for t in dict.fromkeys(x for x in (title, alt) if x):
            if hit:
                break
            hit = index.find(t, year, 1 if series_hint else None)
            if hit:
                why.append(f"IMDb: {hit['title']} ({hit['year']})")
    kind = hit["kind"] if hit else ("series" if series_hint else "movie")
    if hit and hit["kind"] == "movie" and series_hint and c.get("episodes", 0) >= 2:
        kind = "series"  # content takes precedence over the database (e.g. a set of episodes titled like a movie)
    gen = set(hit["genres"]) if hit else set()
    # IMDb keeps 3 genres, so "Family" often drops out: an animated movie without dark genres is treated as a cartoon;
    # an animated series only with "Family" (adult animated series are usually "Animation, Adventure, Comedy")
    dark = gen & {"Horror", "Thriller", "Crime", "War"}
    anim, family = "Animation" in gen, "Family" in gen
    kids = not dark and ((anim and family) or (anim and kind == "movie") or (family and kind == "movie"))
    conf = "high" if hit and (hit.get("year") == year or "IMDb from .nfo" in why) else "medium" if hit else "low"
    if kids and not (anim and family):
        conf = "low"  # animation alone (adult anime) or "Family" alone (live-action remakes) is not enough
    # uncertain: ask Wikidata (age ratings, "children's film", Polish titles)
    if wd and (title or alt) and (conf == "low" or not hit or (anim and not family)):
        w = None
        for t in dict.fromkeys(x for x in (title, alt) if x):
            w = wd(t, year, kind if hit or series_hint else None)
            if w:
                break
        if w:
            why.append(f"Wikidata: {w['title']} ({w['year']})")
            if not hit:
                hit = (index.get(w["imdb"]) if index and w["imdb"] else None) or \
                    {"imdb": w["imdb"], "kind": w["kind"], "title": w["title"], "year": w["year"], "genres": []}
                kind = "series" if series_hint and c.get("episodes", 0) >= 2 else hit["kind"]
                gen = set(hit["genres"])
            k = wd_kids(w)
            if k is not None:
                kids = k and not dark
                why.append(("for children" if kids else "not for children") + (f" (age {w['age']}+)" if w["age"] is not None else ""))
                conf = "high" if w["year"] == year or not year else "medium"
            elif conf == "low" and not kids:
                conf = "medium"
    if kids:
        why.append("animation/family: " + ",".join(sorted(gen)))
    tag = "bajki" if kids else "seriale" if kind == "series" else "filmy"
    out = {"tag": tag, "kind": kind, "title": hit["title"] if hit else title, "year": (hit or {}).get("year") or year,
           "imdb": (hit or {}).get("imdb"), "genres": sorted(gen), "confidence": conf, "why": why}
    if g.get("season") is not None:
        out["season"] = g["season"]
    k = mem_key(out)
    if memory and k in memory:
        out.update(tag=memory[k], confidence="high")
        why.append("your earlier choice")
    return out


# ---- service in the agent

def cache_dir():
    if sys.platform == "win32":
        base = os.path.join(os.environ.get("LOCALAPPDATA") or os.path.expanduser("~"), "magneticplayer", "cache")
    else:
        base = os.path.join(os.environ.get("XDG_CACHE_HOME") or os.path.expanduser("~/.cache"), "magneticplayer")
    return os.path.join(base, "imdb")


class Service:
    """Index (refreshed in the background every `refresh_days`), Wikidata cache and memory of user choices."""

    def __init__(self, cfg, memory_path, log):
        self.cfg, self.log = cfg, log
        self.dir = cache_dir()
        self.path = os.path.join(self.dir, "index.sqlite")
        self.memory_path = memory_path
        self.lock = threading.Lock()
        self.index = Index(self.path)
        self.building = False
        try:
            with open(memory_path) as f:
                self.memory = json.load(f)
        except (OSError, ValueError):
            self.memory = {}
        self._wdcache = {}

    @property
    def online(self):
        return self.cfg.get("ident_online", True)

    def start(self):
        threading.Thread(target=self._refresh_loop, daemon=True).start()

    def _refresh_loop(self):
        time.sleep(120)  # after the agent starts, more important things come first
        while True:
            days = float(self.cfg.get("ident_refresh_days", 7))
            try:
                age = time.time() - os.path.getmtime(self.path)
            except OSError:
                age = None
            if self.online and days > 0 and (age is None or age > days * 86400):
                self.rebuild()
            time.sleep(6 * 3600)

    def rebuild(self):
        if self.building:
            return
        self.building = True
        try:
            os.makedirs(self.dir, exist_ok=True)
            build(self.path, log=self.log)
            with self.lock:
                self.index = Index(self.path)
        except Exception as e:  # network, disk: we will retry on the next round
            self.log("IMDb index: failed:", e)
        finally:
            self.building = False

    def _wd(self, title, year, kind):
        if not self.online:
            return None
        k = (norm(title), year, kind)
        hit = self._wdcache.get(k)
        if hit and time.time() - hit[0] < 86400:
            return hit[1]
        try:
            w = wikidata(title, year, kind)
        except Exception as e:
            self.log("wikidata:", e)
            return None
        self._wdcache[k] = (time.time(), w)
        return w

    def guess(self, name, files=None, nfo_text=None):
        with self.lock:
            index = self.index if self.index.db else None
        r = identify(name or "", files, index, nfo_text, self._wd, self.memory)
        r["index"] = bool(index)
        return r

    def learn(self, guess, tag):
        """The user picked a different directory than suggested: next time the same title goes there right away."""
        k = mem_key(guess)
        if not k or not tag or self.memory.get(k) == tag:
            return
        self.memory[k] = tag
        if len(self.memory) > 5000:
            self.memory = dict(list(self.memory.items())[-5000:])
        tmp = self.memory_path + ".tmp"
        with open(tmp, "w") as f:
            json.dump(self.memory, f, ensure_ascii=False)
        os.replace(tmp, self.memory_path)
