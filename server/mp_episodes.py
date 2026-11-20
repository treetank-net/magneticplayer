"""Episode lists of series (seasons, episodes, titles, air dates) for the "have / don't have" series page.

Sources, by IMDb id of the series only (no titles are sent anywhere):

1. Wikidata, one SPARQL query per series: every item "part of the series" (P179) of the item with that IMDb id (P345).
   Items that other items name as their season (P4908) are the seasons; a season's number is the series ordinal
   (P1545 qualifier on its P179). An episode's number is the P1545 qualifier on its P4908; when a series has no season
   items at all (miniseries), the P179 ordinal is the episode number in season 1. Air date = the earliest P577
   (Wikidata keeps one per country). Titles are the labels in the asked language, then English. The series' own
   "number of episodes" (P1113) comes back in the same query and says whether the list is complete.

2. IMDb's free datasets (datasets.imdbws.com, personal and non-commercial use) when Wikidata lacks the series' episodes
   or has fewer than 90% of what it or IMDb says the series has, and for running series, whose newest episodes
   Wikidata adds late (IMDb's rows then only fill the episodes Wikidata lacks). `title.episode.tsv.gz` (≈ 55 MB, 10 M rows: episode
   id, series id, season, episode) and `title.basics.tsv.gz` (≈ 230 MB: title and year of every title) are streamed
   through gzip and filtered on the fly to the series that needed the fallback; nothing of them is written to disk
   except one small JSON per such series (a few KB to ≈ 60 KB for a series with 800+ episodes). IMDb has no air dates
   in the datasets, only years; titles are the primary (usually English or original) ones, so Wikidata's labels and
   dates are laid over IMDb's list wherever an episode's IMDb id or number matches.

   Cost: one pass = ≈ 285 MB downloaded, ≈ 10 s of the network on a fast line, ≈ 30-60 s of one niced CPU core (gzip
   and splitting lines), a few MB of memory, no temporary files. A pass runs in a child process under `nice -n 19
   ionice -c3` (Windows: below-normal priority) at most once every IMDB_REFRESH (7 days) for all series that use the
   fallback, plus a catch-up pass for a series seen for the first time, at most once every IMDB_CATCHUP (12 hours;
   a pass waits IMDB_BATCH = 60 s for more series, and series asked for while it ran get one follow-up pass).
   Series not asked for in IMDB_FORGET (180 days) leave the list. Worst case ≈ 2 passes a day only while new
   fallback series keep appearing; normally 1 pass a week (≈ 1.2 GB a month).

Cache (under `path`):
- `series/<tt>.json`: the parsed Wikidata answer ({fetched, langs, expected, end, eps: [...]});
- `imdb/<tt>.json`: the filtered IMDb rows of a fallback series; `imdb/state.json`: last pass, series asked for.
A listing is rebuilt from these two on every `get` (cheap). Wikidata is asked again after 7 days for a running series
(an episode aired within a year, or one not aired yet) and after 60 days otherwise, in the background: `get` answers
from the cache at once and blocks (Wikidata, up to WD_TIMEOUT) only for a series it has never seen.

No user-facing text here: the caller translates states into words.
"""
import copy
import datetime
import gzip
import io
import json
import os
import re
import shutil
import subprocess
import sys
import threading
import time
import urllib.parse
import urllib.request

SPARQL = "https://query.wikidata.org/sparql"
DATASETS = "https://datasets.imdbws.com/"
UA = {"User-Agent": "MagneticPlayer (home media agent; https://gitlab.com/treetank/magneticplayer)"}
WD_TIMEOUT = 30
TTL_RUNNING = 7 * 86400
TTL_ENDED = 60 * 86400
IMDB_REFRESH = 7 * 86400
IMDB_CATCHUP = 12 * 3600
IMDB_FORGET = 180 * 86400
IMDB_PASS_TIMEOUT = 1800
IMDB_BATCH = 60     # seconds a pass waits for more series to ask for it
ENOUGH = 0.9           # Wikidata is used when it has at least this share of the episodes the series is said to have
TT = re.compile(r"tt\d{6,10}")
PLACEHOLDER = re.compile(r"(?i)^(episode\s*(#\s*)?\d+(\.\d+)?|episode dated .*|odcinek\s*\d+)$")
LANG = re.compile(r"[a-z]{2,3}")


def _sparql(query, timeout=WD_TIMEOUT):
    u = SPARQL + "?" + urllib.parse.urlencode({"query": query, "format": "json"})
    with urllib.request.urlopen(urllib.request.Request(u, headers=UA), timeout=timeout) as r:
        return json.load(r)["results"]["bindings"]


def _v(b, k):
    v = (b.get(k) or {}).get("value")
    return v.rsplit("/", 1)[-1] if v and v.startswith("http://www.wikidata.org/entity/") else v


def _int(x):
    try:
        return int(x)
    except (TypeError, ValueError):
        return None


def _title(t):
    t = (t or "").strip()
    return None if not t or PLACEHOLDER.match(t) else t


def _write(p, data):
    tmp = p + ".tmp"
    with open(tmp, "w") as f:
        json.dump(data, f, ensure_ascii=False, separators=(",", ":"))
    os.replace(tmp, p)


def _read(p):
    try:
        with open(p) as f:
            return json.load(f)
    except (OSError, ValueError):
        return None


# ---- Wikidata

def wikidata(imdb, lang="en"):
    """One query → {"expected": P1113 or None, "end": "YYYY-MM-DD" or None, "eps": [[season, episode, imdb, aired,
    {lang: title}], ...]} (numbered episodes only), or None when Wikidata has no item with this IMDb id."""
    langs = ["en"] + ([lang] if lang != "en" else [])
    labels = " ".join(f'OPTIONAL {{ ?ep rdfs:label ?l_{x} FILTER(lang(?l_{x}) = "{x}") }}' for x in langs)
    q = f"""SELECT ?series ?n ?end ?ep ?season ?e ?e0 ?d ?i {' '.join('?l_' + x for x in langs)} WHERE {{
  ?series wdt:P345 "{imdb}" .
  {{ ?series wdt:P1113 ?n }} UNION {{ ?series wdt:P582 ?end }} UNION
  {{ ?ep p:P179 ?st . ?st ps:P179 ?series .
    OPTIONAL {{ ?st pq:P1545 ?e0 }}
    OPTIONAL {{ ?ep p:P4908 ?ss . ?ss ps:P4908 ?season . OPTIONAL {{ ?ss pq:P1545 ?e }} }}
    OPTIONAL {{ ?ep wdt:P577 ?d }}
    OPTIONAL {{ ?ep wdt:P345 ?i }}
    {labels} }}
}}"""
    rows = _sparql(q)
    if not rows:
        return None
    expected, end, items = None, None, {}
    for b in rows:
        if b.get("n"):
            expected = max(expected or 0, _int(_v(b, "n")) or 0) or expected
        if b.get("end"):
            end = (_v(b, "end") or "")[:10] or end
        q_ep = _v(b, "ep")
        if not q_ep:
            continue
        it = items.setdefault(q_ep, {"seasons": {}, "e0": None, "d": None, "i": None, "t": {}})
        if b.get("season") and it["seasons"].get(_v(b, "season")) is None:
            it["seasons"][_v(b, "season")] = _int(_v(b, "e"))
        if b.get("e0") and it["e0"] is None:
            it["e0"] = _int(_v(b, "e0"))
        d = (_v(b, "d") or "")[:10]
        if re.fullmatch(r"\d{4}-\d\d-\d\d", d) and not d.startswith("0") and (it["d"] is None or d < it["d"]):
            it["d"] = d.replace("-00", "-01")
        if b.get("i") and TT.fullmatch(_v(b, "i")):
            it["i"] = _v(b, "i")
        for x in langs:
            if b.get("l_" + x):
                it["t"][x] = _v(b, "l_" + x)
    # seasons: the items other items name as their season; their number is their own P179 ordinal (or the label)
    season_no = {}
    for it in items.values():
        for sq in it["seasons"]:
            if sq not in season_no:
                s = items.get(sq) or {}
                n = s.get("e0")
                if n is None:
                    m = re.search(r"(?i)\bseason\s*(\d+)", (s.get("t") or {}).get("en") or "")
                    n = int(m.group(1)) if m else None
                season_no[sq] = n
    eps, seen = [], set()
    for q_ep, it in sorted(items.items(), key=lambda kv: (kv[1]["d"] or "9999", kv[0])):
        if q_ep in season_no:
            continue
        se = ep = None
        for sq, e in it["seasons"].items():
            if season_no.get(sq) is not None and e is not None:
                se, ep = season_no[sq], e
                break
        if se is None and not season_no and it["e0"] is not None:
            se, ep = 1, it["e0"]  # a miniseries: no season items, the series ordinal is the episode number
        if se is None or ep is None or (se, ep) in seen:
            continue
        seen.add((se, ep))
        eps.append([se, ep, it["i"], it["d"], {k: v for k, v in it["t"].items() if _title(v)}])
    eps.sort(key=lambda x: (x[0], x[1]))
    return {"expected": expected, "end": end, "eps": eps}


# ---- IMDb datasets (runs in a child process: see imdb_pass)

def _rows(url):
    with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=60) as r:
        with gzip.GzipFile(fileobj=r) as g:
            for line in io.TextIOWrapper(g, encoding="utf-8"):
                yield line.rstrip("\n").split("\t")


def imdb_pass(out_dir, series, src=DATASETS):
    """Streams the two datasets once and writes `out_dir/<tt>.json` for every series id in `series`:
    {"series", "fetched", "start", "end", "eps": [[season, episode, imdb, title, year], ...]}. Returns a summary."""
    t0 = time.time()
    want = set(series)
    parent = {}                     # episode id → [series, season, episode]
    for row in _rows(src + "title.episode.tsv.gz"):
        if len(row) >= 4 and row[1] in want:
            parent[row[0]] = [row[1], _int(row[2]), _int(row[3])]
    t1 = time.time()
    eps = {s: [] for s in want}
    info = {}
    for row in _rows(src + "title.basics.tsv.gz"):
        if len(row) < 7:
            continue
        p = parent.get(row[0])
        if p:
            eps[p[0]].append([p[1], p[2], row[0], _title(row[2]), _int(row[5])])
        elif row[0] in want:
            info[row[0]] = (_int(row[5]), _int(row[6]))
    now = time.time()
    for s in want:
        start, end = info.get(s, (None, None))
        rows = sorted(eps[s], key=lambda x: (x[0] is None, x[0] or 0, x[1] is None, x[1] or 0))
        _write(os.path.join(out_dir, s + ".json"), {"series": s, "fetched": now, "start": start, "end": end, "eps": rows})
    return {"series": len(want), "episodes": len(parent), "episode_s": round(t1 - t0, 1), "basics_s": round(now - t1, 1)}


# ---- the service

class Episodes:
    def __init__(self, path, log):
        self.path, self.log = path, log
        self.wd_dir = os.path.join(path, "series")
        self.imdb_dir = os.path.join(path, "imdb")
        os.makedirs(self.wd_dir, exist_ok=True)
        os.makedirs(self.imdb_dir, exist_ok=True)
        self.lock = threading.Lock()
        self.busy = set()            # series with a Wikidata refresh running
        self.imdb_running = False
        self.state_path = os.path.join(self.imdb_dir, "state.json")
        self.state = _read(self.state_path) or {}
        self.state.setdefault("last", 0)       # last finished IMDb pass
        self.state.setdefault("tried", 0)      # last started IMDb pass (also failed ones)
        self.state.setdefault("series", {})    # fallback series → last asked for

    # -- public

    def get(self, imdb_series_id, lang="en", on_disk=0):
        """The episode listing of a series (see the module docstring), or None when no source knows the series yet
        (offline, or not on Wikidata and no IMDb pass has run yet: one is then queued). `pending` is True while an IMDb
        pass the listing waits for is queued or running. `on_disk`: how many numbered episode files the server has; a
        Wikidata list shorter than that is incomplete (for some anime Wikidata lists the seasons as the "episodes")."""
        tt = imdb_series_id
        if not TT.fullmatch(tt or ""):
            raise ValueError("bad imdb id")
        lang = lang if LANG.fullmatch(lang or "") else "en"
        wd = _read(self._wd_path(tt))
        if wd is None:
            wd = self._refresh_wd(tt, lang)  # never seen: wait for Wikidata once
        elif self._stale(wd, tt) or (lang not in wd.get("langs", [])):
            self._background(tt, lang)
        return self._compose(tt, wd, lang, on_disk)

    @staticmethod
    def merge(listing, files):
        """A copy of `listing` with every episode marked `have`, `path` and `state` ("have" | "missing" | "upcoming"),
        per-season and total counts (`have`, `missing`, `upcoming`, `total`), and `other`: paths that match no
        episode. `files`: [(season, episode, path)] as mp_ident.episode_no gives for each video (season may be None:
        "Show - 07" is episode 7 of a one-season list, or the 7th episode overall when there are several seasons)."""
        out = copy.deepcopy(listing) if listing else {"seasons": []}
        today = datetime.date.today().isoformat()
        year = datetime.date.today().year
        index, order = {}, []
        for s in out["seasons"]:
            for e in s["episodes"]:
                index[(e["season"], e["episode"])] = e
                if e["season"] > 0:
                    order.append(e)
        numbered = [s for s in out["seasons"] if s["season"] > 0]
        other = []
        for se, ep, path in files:
            if ep is None:
                other.append(path)
                continue
            e = index.get((se, ep)) if se is not None else None
            if se is None:
                if len(numbered) == 1:
                    e = index.get((numbered[0]["season"], ep))
                elif 0 < ep <= len(order):
                    e = order[ep - 1]
            if e is None:
                other.append(path)
            elif not e.get("path"):
                e["path"] = path
            else:
                other.append(path)  # a second copy of the same episode
        running = out.get("running", False)
        last_known = max((i for i, e in enumerate(order) if e.get("aired") or e.get("year")), default=-1)
        pos = {id(e): i for i, e in enumerate(order)}
        tot = {"have": 0, "missing": 0, "upcoming": 0, "total": 0}
        for s in out["seasons"]:
            cnt = {"have": 0, "missing": 0, "upcoming": 0, "total": 0}
            for e in s["episodes"]:
                e["have"] = bool(e.get("path"))
                e.setdefault("path", None)
                if e["have"]:
                    st = "have"
                elif e.get("aired"):
                    st = "upcoming" if e["aired"] > today else "missing"
                elif e.get("year"):
                    st = "upcoming" if e["year"] > year else "missing"
                else:  # no date at all: not aired yet when it comes after the last dated one of a running series
                    st = "upcoming" if running and pos.get(id(e), -1) > last_known else "missing"
                e["state"] = st
                cnt[st] += 1
                cnt["total"] += 1
            s.update(cnt)
            for k in tot:
                tot[k] += cnt[k]
        out.update(tot)
        out["other"] = sorted(other)
        return out

    # -- Wikidata cache

    def _wd_path(self, tt):
        return os.path.join(self.wd_dir, tt + ".json")

    def _refresh_wd(self, tt, lang):
        """Asks Wikidata (blocking) and stores the answer; on failure returns what the cache had."""
        old = _read(self._wd_path(tt))
        t0 = time.time()
        try:
            r = wikidata(tt, lang)
        except Exception as e:  # noqa: BLE001 (network, timeout, 5xx: the cache or IMDb answers instead)
            self.log(f"episodes: Wikidata failed for {tt}: {e!r}")
            return old
        langs = ["en"] + ([lang] if lang != "en" else [])
        if r is None:
            r = {"expected": None, "end": None, "eps": []}
        elif old and old.get("eps"):
            # keep titles in other languages asked for earlier (refreshed when asked for again)
            prev = {(x[0], x[1]): x[4] for x in old["eps"]}
            for x in r["eps"]:
                x[4] = {**prev.get((x[0], x[1]), {}), **x[4]}
            langs = sorted(set(langs) | set(old.get("langs", [])))
        r.update(fetched=time.time(), langs=langs)
        _write(self._wd_path(tt), r)
        self.log(f"episodes: Wikidata {tt}: {len(r['eps'])} episodes (series says {r['expected']}), "
                 f"{time.time() - t0:.1f} s")
        return r

    def _background(self, tt, lang):
        with self.lock:
            if tt in self.busy:
                return
            self.busy.add(tt)

        def run():
            try:
                self._refresh_wd(tt, lang)
            finally:
                with self.lock:
                    self.busy.discard(tt)
        threading.Thread(target=run, daemon=True, name="episodes-wd").start()

    def _stale(self, wd, tt):
        im = _read(os.path.join(self.imdb_dir, tt + ".json"))
        ttl = TTL_RUNNING if self._running(wd, im) else TTL_ENDED
        return time.time() - (wd.get("fetched") or 0) > ttl

    @staticmethod
    def _running(wd, im):
        """An episode aired within the last year or one still to come (Wikidata dates, else IMDb years)."""
        today = datetime.date.today()
        dates = [x[3] for x in (wd or {}).get("eps", []) if x[3]]
        if dates and max(dates) >= (today - datetime.timedelta(days=365)).isoformat():
            return True
        years = [x[4] for x in (im or {}).get("eps", []) if x[4]]
        if years and max(years) >= today.year - (0 if (im or {}).get("end") else 1):
            return True
        return bool(im and im.get("end") is None and im.get("eps") and not years)  # announced, nothing aired yet

    # -- IMDb fallback

    def _want_imdb(self, tt):
        """Notes that `tt` uses the fallback and starts a pass when its data is missing or a week old."""
        now = time.time()
        with self.lock:
            dirty = now - self.state["series"].get(tt, 0) > 86400
            self.state["series"][tt] = now
            for s, t in list(self.state["series"].items()):
                if now - t > IMDB_FORGET:
                    del self.state["series"][s]
            have = os.path.exists(os.path.join(self.imdb_dir, tt + ".json"))
            due = now - self.state["last"] > IMDB_REFRESH or (not have and now - self.state["tried"] > IMDB_CATCHUP)
            start = due and not self.imdb_running
            if start:
                self.imdb_running = True
                self.state["tried"] = now
            if dirty or start:
                _write(self.state_path, self.state)
        if start:
            threading.Thread(target=self._imdb_run, daemon=True, name="episodes-imdb").start()
        return not have  # the series' rows come with the next pass

    def _imdb_run(self):
        """Waits IMDB_BATCH so that series opened one after another share a pass, runs it, and runs one more right away
        when series without data were asked for while it ran (they would otherwise wait for IMDB_CATCHUP)."""
        try:
            time.sleep(IMDB_BATCH)
            for _ in range(2):
                with self.lock:
                    ids = sorted(self.state["series"])
                self._imdb_once(ids)
                with self.lock:
                    late = [s for s in self.state["series"] if s not in ids
                            and not os.path.exists(os.path.join(self.imdb_dir, s + ".json"))]
                    if late:
                        self.state["tried"] = time.time()
                if not late:
                    break
        finally:
            with self.lock:
                self.imdb_running = False

    def _imdb_once(self, ids):
        try:
            cmd = [sys.executable, os.path.abspath(__file__), "imdb-pass", self.imdb_dir, *ids]
            kw = {}
            if sys.platform == "win32":
                kw["creationflags"] = getattr(subprocess, "BELOW_NORMAL_PRIORITY_CLASS", 0)
            else:
                if shutil.which("ionice"):
                    cmd = ["ionice", "-c3", *cmd]
                if shutil.which("nice"):
                    cmd = ["nice", "-n", "19", *cmd]
            t0 = time.time()
            p = subprocess.run(cmd, capture_output=True, text=True, timeout=IMDB_PASS_TIMEOUT, **kw)
            if p.returncode:
                self.log(f"episodes: IMDb pass failed: {(p.stderr or '').strip()[-300:]}")
                return
            with self.lock:
                self.state["last"] = time.time()
                _write(self.state_path, self.state)
            self.log(f"episodes: IMDb pass for {len(ids)} series: {p.stdout.strip()}, {time.time() - t0:.0f} s")
        except Exception as e:  # noqa: BLE001 (timeout, no python: try again on the next request after the gap)
            self.log(f"episodes: IMDb pass failed: {e!r}")

    # -- building a listing

    def _compose(self, tt, wd, lang, on_disk=0):
        """Wikidata's list when it is complete enough; otherwise IMDb's. Whichever is the base, the other fills the
        episodes it lacks (Wikidata lags behind on new episodes of running series, IMDb has no air dates), and
        Wikidata's titles (in `lang`) and air dates are laid over IMDb's rows."""
        im = _read(os.path.join(self.imdb_dir, tt + ".json"))
        wd_eps = (wd or {}).get("eps") or []
        im_eps = [x for x in (im or {}).get("eps", []) if x[0] is not None and x[1] is not None]
        expected = (wd or {}).get("expected") or 0
        imdb_count = len([x for x in im_eps if x[0] > 0])
        wd_count = len([x for x in wd_eps if x[0] > 0])
        # fewer than the files on disk: Wikidata's "episodes" are something else (seasons, for some anime), so its rows
        # are matched to IMDb's only by IMDb id, never by number, and none is added on its own
        wd_odd = wd_count < on_disk
        enough = wd_count > 0 and wd_count >= ENOUGH * max(expected, imdb_count) and not wd_odd
        running = self._running(wd, im)
        pending = False
        if not enough or running or tt in self.state["series"]:
            pending = self._want_imdb(tt) and not enough  # running series: IMDb also checks for new episodes
        if not wd_eps and not im_eps:
            return None if pending or im is None else {
                "series": tt, "source": "imdb", "fetched": im["fetched"], "running": running, "pending": False,
                "seasons": []}
        use_imdb = imdb_count > wd_count and not enough

        def title(t):
            return t.get(lang) or t.get("en") or next(iter(t.values()), None)

        wd_rows = {(se, ep): {"season": se, "episode": ep, "title": title(t), "imdb": eid, "aired": d,
                              "year": int(d[:4]) if d else None} for se, ep, eid, d, t in wd_eps}
        wd_by_id = {e["imdb"]: e for e in wd_rows.values() if e["imdb"]}
        rows = {}
        for se, ep, eid, t, y in im_eps:
            w = wd_by_id.get(eid) or (None if wd_odd else wd_rows.get((se, ep)))
            rows[(se, ep)] = {"season": se, "episode": ep, "title": (w or {}).get("title") or t, "imdb": eid,
                              "aired": (w or {}).get("aired"), "year": (w or {}).get("year") or y}
        if use_imdb:
            matched = {r["imdb"] for r in rows.values()}
            for k, e in wd_rows.items():  # Wikidata rows IMDb lacks (not the ones already matched by IMDb id)
                if not wd_odd and k not in rows and (not e["imdb"] or e["imdb"] not in matched):
                    rows[k] = e
        else:
            im_ids = {r["imdb"]: k for k, r in rows.items()}
            base = dict(wd_rows)
            for k, e in wd_rows.items():  # IMDb's numbering wins where both know the episode under other numbers
                if e["imdb"] in im_ids and im_ids[e["imdb"]] != k:
                    del base[k]
            for k, r in rows.items():
                if k not in base:
                    base[k] = r
            rows = base
        seasons = {}
        for e in rows.values():
            seasons.setdefault(e["season"], []).append(e)
        out = []
        for n in sorted(seasons, key=lambda n: (n == 0, n)):  # specials (season 0) last
            el = sorted(seasons[n], key=lambda e: e["episode"])
            years = [e["year"] for e in el if e["year"]]
            out.append({"season": n, "year": min(years) if years else None, "episodes": el})
        return {"series": tt, "source": "imdb" if use_imdb else "wikidata",
                "fetched": (im if use_imdb else wd or im)["fetched"], "running": running, "pending": pending, "seasons": out}


if __name__ == "__main__" and len(sys.argv) >= 3 and sys.argv[1] == "imdb-pass":
    print(json.dumps(imdb_pass(sys.argv[2], [s for s in sys.argv[3:] if TT.fullmatch(s)])))
