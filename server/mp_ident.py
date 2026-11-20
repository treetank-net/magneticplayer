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

# folders and names of what comes with a series or a film but isn't part of it
EXTRAS = re.compile(r"(?i)^(extras?|featurettes?|bonus(es)?|special[ ._-]?features?|behind[ ._-]?the[ ._-]?scenes|"
                    r"deleted[ ._-]?scenes|interviews?|trailers?|samples?|shorts?|making[ ._-]?of|dodatki|"
                    r"materia(l|ł)y[ ._-]?dodatkowe)$")
EXTRA_NAME = re.compile(r"(?i)(^|[\s._-])(sample|trailer|featurette|behind[ ._-]the[ ._-]scenes|making[ ._-]of|"
                        r"deleted[ ._-]scenes?|gag[ ._-]?reel|bloopers?)([\s._-]|$)")
_SE = re.compile(r"(?i)(?<![A-Za-z0-9])S(\d{1,2})\s?E(\d{1,3})(?!\d)|(?<![A-Za-z0-9])(\d{1,2})x(\d{2})(?!\d)|"
                 r"(?<![A-Za-z0-9])(?:season|sezon|series)[\s._-]?(\d{1,2})[\s._-]*(?:episode|ep|odcinek|odc)[\s._-]?(\d{1,3})(?!\d)|"
                 r"(?:^|\s-\s)(\d{1,2})\.(\d{1,2})(?=\s|$)")
_SEASON = re.compile(r"(?i)(?<![A-Za-z0-9])(?:season|sezon|series|staffel|saison)[\s._-]?(\d{1,2})(?!\d)|^S(\d{1,2})$")
_EP = re.compile(r"(?i)(?:^|[\s._-])(?:E|Ep|Episode|Odc(?:inek)?)[\s._-]?(\d{1,3})(?:[\s._-]|$)")
_LEAD = re.compile(r"^(\d{1,3})(?=[\s._-]|$)")  # "1-She's Not There", "03 - Title"
_BARE = re.compile(r"(?:^|[\s_-])(\d{1,3})(?=[\s_-]|$)")  # "Show_01_Title", "Show - 07 - Title"


def is_extra(rel):
    """A video that is an extra (in an Extras/Featurettes… folder, or a sample/trailer by name)."""
    parts = rel.replace("\\", "/").split("/")
    return any(EXTRAS.match(d.strip()) for d in parts[:-1]) or bool(EXTRA_NAME.search(os.path.splitext(parts[-1])[0]))


def _natural(s):
    return [int(t) if t.isdigit() else t for t in re.split(r"(\d+)", s.lower())]


def episode_no(rel):
    """(season, episode) from the path, either may be None."""
    parts = rel.replace("\\", "/").split("/")
    name = os.path.splitext(parts[-1])[0]
    m = _SE.search(name)
    if m:
        g = [x for x in m.groups() if x is not None]
        return int(g[0]), int(g[1])
    if re.search(r"(?i)\bspecial\b", name):
        return None, None  # a special without SxxEyy: after the numbered episodes
    season = None
    for d in [name, *reversed(parts[:-1])]:
        m = _SEASON.search(d.strip())
        if m:
            season = int(m.group(1) or m.group(2))
            break
    bare = re.sub(r"\[[^\]]*\]|\([^)]*\)", " ", name)  # no [group]/(year) numbers
    bare = _SEASON.sub(" ", bare)
    # "Show - 07 - Title", and "[Group].Show.07.Title" (dots for spaces)
    m = _EP.search(name) or _LEAD.search(bare.strip()) or _BARE.search(bare) or _BARE.search(bare.replace(".", " "))
    return season, int(m.group(1)) if m else None


def numbered(files):
    """Distinct episode numbers of a folder's big videos when they read as a numbered run ("Show - 01", "01.mp4",
    "[Group] Show - 12 [720p]": anime rarely says SxxEyy); 0 unless there are 3+ and 80% of the videos carry one.
    Only for folders already taken as a series or a group of series: film collections number their films too."""
    vids = [(p, s) for p, s in files if os.path.splitext(p)[1].lower() in VIDEO and not is_extra(p)]
    if len(vids) < 3:
        return 0
    top = max(s for _, s in vids)
    nums = [episode_no(p) for p, s in vids if s >= 0.15 * top]
    have = {x for x in nums if x[1] is not None}
    return len(have) if len(have) >= 3 and sum(x[1] is not None for x in nums) >= 0.8 * len(nums) else 0


def film_years(files):
    """Distinct years in the paths of a folder's big videos ("01. Film (2001)/…", "02. Film 2 (2002)/…"): 2+ means a
    collection of films, since episodes of one series don't each carry their own year."""
    vids = [(p, s) for p, s in files if os.path.splitext(p)[1].lower() in VIDEO and not is_extra(p)]
    if len(vids) < 2:
        return 0
    top = max(s for _, s in vids)
    big = [p for p, s in vids if s >= 0.15 * top]
    ys = [re.findall(r"(?<!\d)(?:19|20)\d\d(?!\d)", p) for p in big]
    if sum(bool(y) for y in ys) < 0.8 * len(big):
        return 0
    return len({y[0] for y in ys if y})


def episodes(files):
    """(path, size) videos of a series folder → (episodes in watching order, extras). Each: {path, season, episode, size}.
    Small leftovers (< 15% of the biggest video, e.g. samples) count as extras too."""
    vids = [(p, s) for p, s in files if os.path.splitext(p)[1].lower() in VIDEO]
    if not vids:
        return [], []
    top = max(s for _, s in vids)
    eps, extras = [], []
    for p, s in vids:
        se, ep = episode_no(p)
        x = {"path": p, "season": se, "episode": ep, "size": s}
        (extras if is_extra(p) or (s < 0.15 * top and ep is None) else eps).append(x)
    # numbered ones by season and episode; ones with no number at all (specials, films of the series) at the end
    eps.sort(key=lambda x: (x["season"] is None and x["episode"] is None, x["season"] or 0,
                            x["episode"] if x["episode"] is not None else 1 << 20, _natural(x["path"])))
    extras.sort(key=lambda x: _natural(x["path"]))
    return eps, extras


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
    votes, rating = {}, {}
    for row in get("title.ratings.tsv.gz"):
        if row[0].startswith("tt") and row[2].isdigit() and int(row[2]) >= MIN_VOTES:
            votes[row[0]] = int(row[2])
            try:
                rating[row[0]] = round(float(row[1]) * 10)
            except ValueError:
                pass
    tmp = path + ".tmp"
    if os.path.exists(tmp):
        os.remove(tmp)
    db = sqlite3.connect(tmp)
    db.executescript("""
        CREATE TABLE t (id INTEGER PRIMARY KEY, kind INTEGER, title TEXT, year INTEGER, end INTEGER, g INTEGER, votes INTEGER,
                        r INTEGER, min INTEGER);
        CREATE TABLE k (key TEXT, id INTEGER);
    """)
    n = 0
    for row in get("title.basics.tsv.gz"):
        if row[0] not in votes or row[1] not in KINDS or row[4] == "1":  # isAdult
            continue
        tid = int(row[0][2:])
        mask = sum(G.get(x, 0) for x in row[8].split(","))
        db.execute("INSERT INTO t VALUES (?,?,?,?,?,?,?,?,?)", (tid, KINDS[row[1]], row[2], _int(row[5]), _int(row[6]), mask,
                                                              votes[row[0]], rating.get(row[0]), _int(row[7])))
        keys = {norm(row[2]), norm(row[3])}
        db.executemany("INSERT INTO k VALUES (?,?)", [(k, tid) for k in keys if k])
        n += 1
    db.execute("CREATE INDEX k_key ON k(key)")
    db.execute("CREATE INDEX t_votes ON t(votes)")
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

    @property
    def rated(self):
        """Whether the index has ratings and runtimes (built by 1.8.0 or later); an older one is rebuilt early."""
        if not self.db:
            return False
        return any(c[1] == "r" for c in self.db.execute("PRAGMA table_info(t)"))

    def get(self, imdb_id):
        if not self.db:
            return None
        try:
            r = self.db.execute("SELECT * FROM t WHERE id=?", (int(str(imdb_id).lstrip("t")),)).fetchone()
        except ValueError:
            return None
        return _row(r) if r else None

    def many(self, ids):
        """{imdb id: row} for a list of ids (missing ones are left out)."""
        if not self.db:
            return {}
        nums = [int(str(i)[2:]) for i in ids if str(i)[:2] == "tt" and str(i)[2:].isdigit()]
        out = {}
        for k in range(0, len(nums), 500):
            chunk = nums[k:k + 500]
            for r in self.db.execute(f"SELECT * FROM t WHERE id IN ({','.join('?' * len(chunk))})", chunk):
                row = _row(r)
                out[row["imdb"]] = row
        return out

    def popular(self, mask, kind=0, years=None, limit=30, exclude=()):
        """The most voted titles sharing all genres of `mask` (movies by default), optionally within `years`
        (from, to). For "what next" suggestions from outside the collection."""
        if not self.db:
            return []
        q, args = "SELECT * FROM t WHERE kind=? AND (g & ?)=?", [kind, mask, mask]
        if years:
            q += " AND year BETWEEN ? AND ?"
            args += list(years)
        q += " ORDER BY votes DESC LIMIT ?"
        args.append(limit + len(exclude))
        ex = set(exclude)
        return [x for x in (_row(r) for r in self.db.execute(q, args)) if x["imdb"] not in ex][:limit]

    def find(self, title, year=None, kind=None, only=False):
        """Best title: exact key first, then year (closer is better) and kind, finally popularity. `only`: titles of
        that kind only (a single video file is never a whole series)."""
        if not self.db or not title:
            return None
        rows = self.db.execute("SELECT t.* FROM k JOIN t USING(id) WHERE k.key=?", (norm(title),)).fetchall()
        if only and kind is not None:
            rows = [r for r in rows if r[1] == kind]
        if not rows:
            return None

        def score(r):
            s = r[6] ** 0.3  # popularity: gently
            if year and r[3]:
                d = abs(r[3] - year)
                # a year off by 2 is a common naming slip (the year a film was dubbed, released here); far off is
                # another title of the same name (a remake), the farther the worse
                s += 1000 if d == 0 else 400 if d == 1 else 150 if d == 2 else -300 - 10 * d
            if kind is not None and r[1] == kind:
                s += 150
            return s
        return _row(max(rows, key=score))


def _row(r):
    out = {"imdb": f"tt{r[0]:07d}", "kind": "series" if r[1] else "movie", "title": r[2], "year": r[3], "end": r[4],
           "genres": genres(r[5]), "votes": r[6]}
    if len(r) > 8:  # an index from before 1.8.0 has no rating and runtime
        out["rating"] = r[7] / 10 if r[7] is not None else None
        out["minutes"] = r[8]
    return out


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


RANGE1 = re.compile(r"(?<=[\s._])(\d)-(\d{2,3})(?=[\s._\[(]|$)")
COLLECTION = re.compile(r"^(?P<c>.+?\d)\s+-\s+\d{1,3}\s+-\s+(?P<t>.+)$")
LEADYEAR = re.compile(r"^(?P<y>(?:19|20)\d\d)\s*-\s+(?P<t>.+)$")


def _guessit(name):
    # "<Collection 2> - 19 - <Title> (1995)": numbered films of a collection; guessit takes the collection's name for
    # the title (every film got the collection's name). The film's own part counts when it has a year or the collection
    # ends with a number (a series' "Show - 01 - Pilot" has neither and stays as it was).
    # "Show.1-25 [1080p]": an episode range guessit reads as part of the title ("Show 1", episode 25) unless padded
    name = RANGE1.sub(r"0\1-\2", name)
    # "1961 - <Title>.avi": the year first (a collection sorted by year); guessit takes the year for the title and
    # reads an episode. The rest is the title, the leading year the film's unless the rest has its own
    m = LEADYEAR.match(name)
    if m and not EPISODE.search(m.group("t")):
        g = _guessit_raw(m.group("t"))
        # a number starting the title ("101 <Title>") is read as an episode and cut off: keep the name's own text
        t = re.split(r"[\[(]|\b(?:480|576|720|1080|2160)p\b", os.path.splitext(m.group("t"))[0])[0].strip(" -._")
        if g.get("title") and norm(t) != norm(g["title"]) and norm(t).endswith(norm(g["title"])):
            g["title"] = t
        elif g.get("title") and (n := re.match(r"\d+\s", t)) and not g["title"].startswith(n.group(0)):
            g["title"] = n.group(0) + g["title"]  # "101 <Title> 2 - <subtitle>": the number goes back
        if g.get("title"):
            if not g.get("year"):
                g["year"] = int(m.group("y"))
            g["type"] = "movie"
            for k in ("season", "episode", "episode_title"):
                g.pop(k, None)
            return g
    m = COLLECTION.match(name)
    if m:
        g = _guessit_raw(m.group("t"))
        if g.get("title") and (g.get("year") or re.search(r"\d$", m.group("c"))):
            g["type"] = "movie"
            for k in ("season", "episode", "episode_title"):
                g.pop(k, None)
            return g
    g = _guessit_raw(name)
    # a title that is just a number ("300", "1917"): guessit reads a season and an episode (or a year) and no title
    stem = os.path.splitext(name)[0] if os.path.splitext(name)[1].lower() in VIDEO else name
    if not g.get("title") and re.fullmatch(r"\d{1,4}", stem.strip()):
        g["title"] = stem.strip()
        g["type"] = "movie"
        for k in ("season", "episode", "episode_title"):
            g.pop(k, None)
        if str(g.get("year")) == g["title"]:
            g.pop("year")
    return g


def _guessit_raw(name):
    try:
        import guessit  # in the agent venv (a subliminal dependency)
        g = dict(guessit.guessit(name))
        for k in ("title", "year", "season", "type"):  # some names give a list ("A / B", two years): the first counts
            if isinstance(g.get(k), list):
                g[k] = g[k][0] if g[k] else None
        return g
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
    # a single video without episode markers is never a whole series: an exact title match of a series of the same
    # name ("<Film> (2014)" → a 1967 series) is ignored. Folders of several videos keep the old way (webisodes,
    # numbered parts of a film series can go either way)
    single = (not files and os.path.splitext(name or "")[1].lower() in VIDEO) or c.get("videos", 0) == 1 and bool(files)
    films_only = not series_hint and single
    # what is inside is known and nothing in it looks like an episode (one film, or a set of films)
    # ("Show - 01" numbering counts as episodes here: anime series folders)
    no_eps = not series_hint and (single or bool(files) and c.get("videos", 0) >= 2 and (numbered(files) < 3 or film_years(files) >= 2))
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
            hit = index.find(t, year, 1 if series_hint else 0 if films_only else None, only=films_only)
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
    near = not year or not hit or not hit.get("year") or abs(hit["year"] - year) <= 2
    conf = "high" if hit and (hit.get("year") == year or "IMDb from .nfo" in why) else "medium" if hit and near else "low"
    if kids and not (anim and family):
        conf = "low"  # animation alone (adult anime) or "Family" alone (live-action remakes) is not enough
    # uncertain: ask Wikidata (age ratings, "children's film", Polish titles)
    if wd and (title or alt) and (conf == "low" or not hit or (anim and not family)):
        w = None
        for t in dict.fromkeys(x for x in (title, alt) if x):
            w = wd(t, year, kind if hit or series_hint else None)
            if w:
                break
        # videos without a single episode marker: a series of the same name doesn't make them a series (a film of a
        # cartoon series, or another title), it only tells whether it is for children
        info_only = bool(w) and w["kind"] == "series" and no_eps
        if w:
            why.append(f"Wikidata: {w['title']} ({w['year']})" + (", a series: kind not taken" if info_only else ""))
            if not hit and not info_only:
                hit = (index.get(w["imdb"]) if index and w["imdb"] else None) or \
                    {"imdb": w["imdb"], "kind": w["kind"], "title": w["title"], "year": w["year"], "genres": []}
                kind = "series" if series_hint and c.get("episodes", 0) >= 2 else hit["kind"]
                gen = set(hit["genres"])
            k = wd_kids(w)
            if k is not None:
                kids = k and not dark
                why.append(("for children" if kids else "not for children") + (f" (age {w['age']}+)" if w["age"] is not None else ""))
                if not info_only:
                    conf = "high" if w["year"] == year or not year else "medium"
            elif conf == "low" and not kids and not info_only:
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
            stale = age is None or age > days * 86400 or not self.index.rated
            if self.online and days > 0 and stale:
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
