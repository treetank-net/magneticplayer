"""Rozpoznawanie filmów i seriali: nazwa (guessit) + zawartość + mały lokalny indeks IMDb.

Indeks: darmowe zrzuty IMDb (datasets.imdbws.com, użytek osobisty i niekomercyjny) przerobione na SQLite z samymi
filmami i serialami, które mają co najmniej MIN_VOTES głosów. Gatunki jako maska bitów (GENRES), tytuły
znormalizowane (`key`) do szybkiego dopasowania. Kilka MB zamiast 230 MB, działa offline.
"""
import gzip
import io
import os
import re
import sqlite3
import time
import unicodedata
import urllib.request

DATASETS = "https://datasets.imdbws.com/"
MIN_VOTES = 300
KINDS = {"movie": 0, "tvMovie": 0, "tvSeries": 1, "tvMiniSeries": 1}  # 0 film, 1 serial
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
    """Klucz tytułu: małe litery bez akcentów, interpunkcji, spacji i słów the/a/an/and, żeby „12:01” z dysku trafiło
    na „1201”, a nadmiarowe „The” z nazwy wydania nie psuło dopasowania."""
    s = unicodedata.normalize("NFKD", s).encode("ascii", "ignore").decode().lower().replace("&", " and ")
    return "".join(w for w in re.sub(r"[^a-z0-9]+", " ", s).split() if w not in STOP)


# ---- indeks

def _lines(url):
    with urllib.request.urlopen(url, timeout=60) as r:
        with gzip.GzipFile(fileobj=r) as g:
            for line in io.TextIOWrapper(g, encoding="utf-8"):
                yield line.rstrip("\n").split("\t")


def build(path, src=DATASETS, log=print):
    """Pobiera zrzuty i buduje indeks w `path` (podmiana atomowa). `src`: URL albo katalog z plikami .tsv.gz."""
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
    log(f"indeks IMDb: {n} tytułów, {os.path.getsize(path) // 1024} KB, {time.time() - t0:.0f} s")
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
        """Najlepszy tytuł: dokładny klucz, potem rok (±1) i rodzaj, na końcu popularność."""
        if not self.db or not title:
            return None
        rows = self.db.execute("SELECT t.* FROM k JOIN t USING(id) WHERE k.key=?", (norm(title),)).fetchall()
        if not rows:
            return None

        def score(r):
            s = r[6] ** 0.3  # popularność: łagodnie
            if year and r[3]:
                s += 1000 if r[3] == year else 400 if abs(r[3] - year) == 1 else -300
            if kind is not None and r[1] == kind:
                s += 150
            return s
        return _row(max(rows, key=score))


def _row(r):
    return {"imdb": f"tt{r[0]:07d}", "kind": "series" if r[1] else "movie", "title": r[2], "year": r[3], "end": r[4],
            "genres": genres(r[5]), "votes": r[6]}


# ---- rozpoznanie

def content(files):
    """Co jest w środku: lista (ścieżka, rozmiar) → rodzaj zawartości i ile odcinków."""
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
        return {"what": "leftover"}  # same napisy i obrazki, np. po usuniętym filmie
    big = [(p, s) for p, s in vids if s > 0.15 * max(s for _, s in vids)]
    eps = [p for p, _ in big if EPISODE.search(os.path.basename(p)) or EPISODE.search(p)]
    return {"what": "video", "videos": len(big), "episodes": len(eps)}


def identify(name, files=None, index=None, nfo_text=None):
    """Nazwa (+ lista plików, + treść .nfo) → propozycja: tag, tytuł, rok, IMDb, pewność i powody."""
    import guessit  # w venv agenta (zależność subliminal)
    why = []
    c = content(files) if files else {"what": "video", "videos": 1, "episodes": 0}
    if c["what"] == "music":
        return {"tag": "muzyka", "confidence": "high", "why": ["audio"]}
    if c["what"] == "other":
        return {"tag": "inne", "confidence": "high", "why": ["programy, archiwa, komiksy"]}
    if c["what"] == "leftover":
        return {"tag": None, "confidence": "high", "why": ["bez filmów: nie przenosimy"]}
    g = guessit.guessit(name)
    title, year = g.get("title"), g.get("year")
    # serial tylko po oznaczeniach odcinków: sama liczba plików to też kolekcja filmów
    series_hint = (g.get("type") == "episode" and "episode" in g or "season" in g) or c.get("episodes", 0) >= 2
    if series_hint:
        why.append(f"odcinki ({c.get('episodes', 0)}/{c.get('videos', 0)})" if files else "nazwa jak odcinek")
    hit = None
    if index:
        m = re.search(r"tt\d{7,8}", nfo_text or "")
        if m:
            hit = index.get(m.group(0))
            if hit:
                why.append("IMDb z .nfo")
        # guessit bywa mylony liczbą na początku tytułu (bierze ją za numer odcinka): druga próba z tekstem przed rokiem
        alt = re.split(r"[\[(]?\b(?:19|20)\d\d\b", re.sub(r"[._]", " ", name))[0].strip(" -[(") if year else None
        for t in dict.fromkeys(x for x in (title, alt) if x):
            if hit:
                break
            hit = index.find(t, year, 1 if series_hint else None)
            if hit:
                why.append(f"IMDb: {hit['title']} ({hit['year']})")
    kind = hit["kind"] if hit else ("series" if series_hint else "movie")
    if hit and hit["kind"] == "movie" and series_hint and c.get("episodes", 0) >= 2:
        kind = "series"  # zawartość ma pierwszeństwo przed bazą (np. zbiór odcinków o tytule jak film)
    gen = set(hit["genres"]) if hit else set()
    # IMDb trzyma 3 gatunki, więc „Family” często wypada: animowany film bez mrocznych gatunków traktujemy jak bajkę;
    # animowany serial tylko z „Family” (seriale animowane dla dorosłych to zwykle „Animation, Adventure, Comedy”)
    dark = gen & {"Horror", "Thriller", "Crime", "War"}
    anim, family = "Animation" in gen, "Family" in gen
    kids = not dark and ((anim and family) or (anim and kind == "movie") or (family and kind == "movie"))
    if kids:
        why.append("animacja/familijny: " + ",".join(sorted(gen)))
    tag = "bajki" if kids else "seriale" if kind == "series" else "filmy"
    conf = "high" if hit and (hit.get("year") == year or "IMDb z .nfo" in why) else "medium" if hit else "low"
    if kids and not (anim and family):
        conf = "low"  # sama animacja (anime dla dorosłych) albo sam „Family” (aktorskie remaki) to za mało: pytamy
    return {"tag": tag, "kind": kind, "title": hit["title"] if hit else title, "year": (hit or {}).get("year") or year,
            "imdb": (hit or {}).get("imdb"), "genres": sorted(gen), "confidence": conf, "why": why}
