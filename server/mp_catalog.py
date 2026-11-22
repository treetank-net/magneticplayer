"""Catalog: the library seen as films and series instead of folders (browsing by genre, age, language, people, topic).

A background thread walks the library folders (not `kopia`, not music), groups media files into titles (a top-level
file or folder of a library folder; a folder with several films and no episodes is split into its films), identifies
each one with mp_ident (local IMDb index, Wikidata only for uncertain cases) and reads the audio and subtitle languages
from the main video (ffprobe, once per file) and the subtitle files next to it.

Details come from Wikidata by IMDb id, never by title: cast in billing order (statement order), directors, main
subjects (P921), age ratings (FSK/MPAA) and the Polish label. Only IMDb ids and Wikidata ids are sent. They are kept
for 30 days. People and topics are referenced by Wikidata id; their labels are stored once.

The Polish title is returned only when the film has Polish audio or Polish subtitles: otherwise the original title is
the one that helps.

"What next" mixes titles from the collection with ones that are not on the disks (same actors, same genres, same
topic): the app shows the latter greyed out, with the shared "want to watch" list and an optional search link that the
user configures (no search sites are built in). "Suggest" (Discover's "not on your disks" row) keeps only the latter,
scored by shared people, genres and era, and cached per watched title.

State: catalog.json in the config directory (items, Wikidata details, labels).
"""
import json
import math
import os
import re
import threading
import time
import urllib.parse
import urllib.request

import mp_arrange
import mp_ident
from mp_library import HIDDEN_TAGS, SUB_EXT, VIDEO_EXT, can_see, current_viewer, excluded, sidecar_subs

RESCAN = 1800          # seconds between library walks (a change in the library triggers one sooner)
WD_TTL = 30 * 86400    # Wikidata details are refreshed after a month
WD_BATCH = 40
SPARQL = "https://query.wikidata.org/sparql"
CAST_MAX = 12
ITEM_V = 9             # bump when the rules for an item change (languages, identification): items are redone (no ffprobe)
SKIP_TAGS = {"muzyka", "inne"}
LANG2 = {"pol": "pl", "eng": "en", "jpn": "ja", "ger": "de", "deu": "de", "fre": "fr", "fra": "fr", "spa": "es",
         "ita": "it", "rus": "ru", "por": "pt", "dut": "nl", "nld": "nl", "cze": "cs", "ces": "cs", "chi": "zh",
         "zho": "zh", "kor": "ko", "swe": "sv", "nor": "no", "dan": "da", "fin": "fi", "hun": "hu", "ukr": "uk"}
PL_AUDIO = re.compile(r"(?i)\b(lektor|dubbing|pldub|dub\.?pl|pl\.?dub)\b")
SUGGEST_TTL = 86400    # suggestions per watched title are kept for a day
SUGGEST_KEEP = 64      # ... for this many watched titles
SUGGEST_POOL = 40      # candidates kept per title (an answer drops those the library has meanwhile)
SUGGEST_MIN_VOTES = 5000  # "popular": IMDb votes a suggestion needs
SUGGEST_PER_REASON = 4   # one director or actor fills at most this many places before others get theirs
SUGGEST_GENERIC = {"Drama", "Comedy", "Romance"}  # genres that alone say little about a title
PL_SUBS = re.compile(r"(?i)\b(napisy|plsub|pl\.?sub|sub\.?pl)\b")


KNOWN2 = set(LANG2.values()) | {"ar", "bg", "ca", "el", "et", "he", "hi", "hr", "id", "is", "lt", "lv", "ro", "sk", "sl",
                                "sr", "th", "tr", "vi", "fa", "ms", "ta", "te"}
LANG2.update({"ara": "ar", "bul": "bg", "cat": "ca", "gre": "el", "ell": "el", "est": "et", "heb": "he", "hin": "hi",
              "hrv": "hr", "cro": "hr", "ind": "id", "ice": "is", "isl": "is", "lit": "lt", "lav": "lv", "rum": "ro",
              "ron": "ro", "slo": "sk", "slk": "sk", "slv": "sl", "srp": "sr", "scc": "sr", "tha": "th", "tur": "tr",
              "vie": "vi", "per": "fa", "fas": "fa", "may": "ms", "msa": "ms", "tam": "ta", "tel": "te"})


def lang2(code):
    """A language code or name from a track tag or a file name ("eng", "pl", "eng.forced", "English") → 2 letters;
    None when it says nothing (und, a release group, "sdh", "forced")."""
    for w in re.split(r"[^a-z]+", (code or "").lower()):
        w = {"english": "en", "polish": "pl", "polski": "pl", "german": "de", "french": "fr", "spanish": "es",
             "italian": "it", "russian": "ru", "japanese": "ja", "dutch": "nl", "czech": "cs"}.get(w, w)
        c = LANG2.get(w, w)
        if c in KNOWN2:
            return c
    return None


def _sparql(query, timeout=30):
    u = SPARQL + "?" + urllib.parse.urlencode({"query": query, "format": "json"})
    with urllib.request.urlopen(urllib.request.Request(u, headers=mp_ident.UA), timeout=timeout) as r:
        return json.load(r)["results"]["bindings"]


def _val(b, k):
    v = (b.get(k) or {}).get("value")
    return v.rsplit("/", 1)[-1] if v and v.startswith("http://www.wikidata.org/entity/") else v


# subfolder names of one series split into seasons, parts or extras (not separate titles)
SEASONISH = re.compile(r"(^\d{1,2}$|\bs\d{1,2}\b|season|sezon|seria|series\s*\d|staffel|saison|temporada|\bpart\b|"
                       r"extras?\b|specials?\b|bonus|featurettes?|\bova\b|movies?\b)", re.I)


def _norm(s):
    return re.sub(r"[^a-z0-9]+", " ", s.lower()).strip()


def _group_of(top, fs):
    """A grouping folder: no episodes directly in it, and at least two subfolders that are series of their own (2+
    episodes each), none named like a season, a part or extras, nor containing the folder's own name ("Show/Show Next"
    are parts of one show). {subfolder: [(rel file, size)]} or None."""
    subs, loose = {}, []
    for p, s in fs:
        parts = p.split("/")
        (loose if len(parts) == 2 else subs.setdefault(parts[1], [])).append((p, s))
    if len(subs) < 2 or (loose and mp_ident.content(loose).get("episodes", 0) >= 2):
        return None
    t = _norm(top)
    if any(SEASONISH.search(n) or (t and t in _norm(n)) for n in subs):
        return None
    series = sum(1 for sfs in subs.values()
                 if mp_ident.content(sfs).get("episodes", 0) >= 2 or mp_ident.numbered(sfs) >= 3)
    return subs if series >= 2 else None


class Catalog:
    def __init__(self, path, cfg, library, ident, log):
        self.path, self.cfg, self.library, self.ident, self.log = path, cfg, library, ident, log
        self.lock = threading.Lock()
        self.wake = threading.Event()
        try:
            with open(path) as f:
                d = json.load(f)
        except (OSError, ValueError):
            d = {}
        self.items = d.get("items") or {}     # key "<root id>/<rel>" → item
        self.wd = d.get("wd") or {}           # imdb → details
        self.labels = d.get("labels") or {}   # Wikidata id → {"en", "pl"}
        self._relabelled = False              # names left empty retried once per start (enrich)
        self.version = d.get("version") or 0
        self.state = {"scanning": False, "done": 0, "total": 0, "last": d.get("last")}
        self._ext = {}                         # (kind, key) → (time, result): on-demand Wikidata queries
        self._suggest = {}                     # imdb → (valid until, source, candidates): suggest() per watched title
        self.hash_ids = None                   # full path → [(imdb, n)] from OpenSubtitles by file hash (set by the agent)

    # ---- persistence

    def _save(self):
        with self.lock:
            data = json.dumps({"items": self.items, "wd": self.wd, "labels": self.labels, "version": self.version,
                               "last": self.state.get("last")}, ensure_ascii=False)
        tmp = self.path + ".tmp"
        with open(tmp, "w") as f:
            f.write(data)
        os.replace(tmp, self.path)

    def start(self):
        threading.Thread(target=self._loop, daemon=True, name="catalog").start()

    def refresh(self):
        """Library changed (folder added, files moved): walk it again soon."""
        self.wake.set()

    def _loop(self):
        time.sleep(30)
        while True:
            self.wake.clear()
            try:
                self.scan()
            except Exception as e:  # noqa: BLE001 (a disk that went away mid-walk must not stop the thread)
                self.log("catalog: scan failed:", e)
            try:
                self.hash_check()
            except Exception as e:  # noqa: BLE001
                self.log("catalog: hash check failed:", e)
            try:
                self.enrich()
            except Exception as e:  # noqa: BLE001
                self.log("catalog: Wikidata failed:", e)
            self.wake.wait(RESCAN)

    # ---- walking the library

    def _units(self, r):
        """Titles under a library folder: [(rel of the title, [(rel file, size)], is_dir, group)]; `group` is the name of
        a grouping folder (_group_of) the title sits in, else None."""
        fs = [(p, size) for p, is_dir, size in self.library._names(r)  # cached walk, shared with the search
              if not is_dir and os.path.splitext(p)[1].lower() in VIDEO_EXT and "sample" not in p.lower()]
        return self._units_in("", fs, r, None)

    def _units_in(self, base, fs, r, group):
        """_units for the files `fs` under folder `base` ("" = the library folder). A folder named like the folder
        layout's own ones ("Films/", "Kids/", "1960–1969/", agent 1.17+) is looked into: its subfolders are titles
        as at the top, with the layout folder as their group."""
        files = {}
        cut = len(base) + 1 if base else 0
        for p, size in fs:
            files.setdefault(p[cut:].split("/", 1)[0], []).append((p, size))
        out = []
        for name, fs in files.items():
            top = f"{base}/{name}" if base else name
            if len(fs) == 1 and fs[0][0] == top:
                out.append((top, fs, False, group))
                continue
            if name.lower() in mp_arrange.own_names() or mp_arrange.DECADE.fullmatch(name):
                out += self._units_in(top, fs, r, group if mp_arrange.DECADE.fullmatch(name) else name)
                continue
            rel = [(p[cut:], s) for p, s in fs]  # _group_of and content() look at paths from this level
            subs = _group_of(name, rel)
            if subs is not None:
                # a grouping folder ("Anime/" with a folder per series): every subfolder and every loose video is a title
                for sub, sfs in subs.items():
                    out.append((f"{top}/{sub}", [(base + "/" + p if base else p, s) for p, s in sfs], True, name))
                out += [(p, [(p, sz)], False, name) for p, sz in fs if p[cut:].count("/") == 1]
                continue
            c = mp_ident.content(fs)
            biggest = max(s for _, s in fs)
            big = [(p, s) for p, s in fs if s > 0.15 * biggest]
            if c.get("videos", 0) >= 3 and c.get("episodes", 0) < 2 and "seriale" not in r["tags"]:
                # a folder of films (a category or a collection): every big video is a title
                for p, s in big:
                    out.append((p, [(p, s)], False, group))
            else:
                out.append((top, fs, True, group))
        return out

    def _item_for(self, full):
        """(rel, catalog item) of a library video, or (None, None)."""
        full = os.path.realpath(full)
        for r in self.library.roots():
            rp = os.path.realpath(r["path"])
            if not full.startswith(rp + os.sep):
                continue
            rel = os.path.relpath(full, rp).replace(os.sep, "/")
            parts = rel.split("/")
            with self.lock:
                it = next((self.items.get(f"{r['id']}/{'/'.join(parts[:k])}") for k in range(len(parts), 0, -1)
                           if f"{r['id']}/{'/'.join(parts[:k])}" in self.items), None)
            return rel, it
        return None, None

    def title_for(self, full):
        """A library video's recognised name for screens (mpv's title, the Remote): "Series · S2 E3", "Film (1968)";
        None when the catalog doesn't know it (the file name stays)."""
        rel, it = self._item_for(full)
        if not it or not it.get("title"):
            return None
        t = it["title"]
        if it.get("kind") == "series":
            d = rel.rsplit("/", 1)[0] if "/" in rel else ""
            try:  # the other episodes beside it tell "Show 105" (S1E05) from the 105th episode
                sib = [f"{d}/{n}" if d else n for n in os.listdir(os.path.dirname(full))
                       if os.path.splitext(n)[1].lower() in mp_ident.VIDEO]
            except OSError:
                sib = []
            se, ep = mp_ident.episode_no(rel, sib)
            return f"{t} · S{se} E{ep}" if se is not None and ep is not None else f"{t} · E{ep}" if ep is not None else t
        return f"{t} ({it['year']})" if it.get("year") else t

    def known_for(self, full):
        """What the catalog knows of a video for a subtitle search (agent 1.17.2+): {"imdb", "kind", "year", "titles"
        (IMDb's, then the English and Polish Wikidata labels)}, or None when it has no IMDb id."""
        _, it = self._item_for(full)
        if not it or not it.get("imdb"):
            return None
        with self.lock:
            d = self.wd.get(it["imdb"]) or {}
        titles = list(dict.fromkeys(x for x in (it.get("title"), d.get("en"), d.get("pl")) if x))
        return {"imdb": it["imdb"], "kind": it.get("kind") or "movie", "year": it.get("year"), "titles": titles}

    def scan(self):
        roots = [r for r in self.library.roots() if r["exists"] and not set(r["tags"]) & (HIDDEN_TAGS | SKIP_TAGS)]
        self.state.update(scanning=True, done=0, total=0)
        seen, changed = set(), False
        try:
            todo = []
            for r in roots:
                for rel, fs, is_dir, group in self._units(r):
                    key = f"{r['id']}/{rel}"
                    seen.add(key)
                    size = sum(s for _, s in fs)
                    old = self.items.get(key)
                    if old and old.get("size") == size and old.get("n") == len(fs) and old.get("v") == ITEM_V:
                        continue
                    todo.append((r, key, rel, fs, is_dir, size, old, group))
            self.state["total"] = len(todo)
            for k, (r, key, rel, fs, is_dir, size, old, group) in enumerate(todo):
                self.state["done"] = k
                try:
                    it = self._item(r, rel, fs, is_dir, size, old, group)
                    if group:
                        it["group"] = group
                except Exception as e:  # noqa: BLE001 (one odd file must not stop the walk)
                    self.log(f"catalog: skipped {rel[:80]}: {e!r}")
                    continue
                with self.lock:
                    self.items[key] = it
                changed = True
                if k % 25 == 24:
                    self.version += 1
                    self._save()
            gone = [k for k in self.items if k not in seen and k.split("/", 1)[0] in {r["id"] for r in roots}]
            gone += [k for k in self.items if k.split("/", 1)[0] not in {r["id"] for r in self.library.roots()}]
            if gone:
                with self.lock:
                    for k in set(gone):
                        self.items.pop(k, None)
                changed = True
        finally:
            self.state.update(scanning=False, done=self.state["total"], last=time.time())
        if changed:
            self.version += 1
        self._save()

    # ---- the file's hash as a second opinion (subtitles uploaded for exactly this file name its film)

    HASH_OVERRIDE, HASH_FILL = 3, 2  # votes needed to replace the name's film / to fill a missing one

    def _apply_hash(self, it):
        """Use `it["hc"]` (OpenSubtitles by hash): agreeing → sure; 3+ subtitles for another film → that film (a name
        like "Episode IV" for a copy of episode VI); 2+ when the name found nothing. One stray subtitle is ignored.
        A series is asked about its first episode, whose subtitles name the series (agent 1.17.6+)."""
        ids = (it.get("hc") or {}).get("ids") or []
        it.pop("sure", None)
        if not ids or it.get("kind") not in ("movie", "series"):
            return
        top, n = ids[0]
        if it.get("imdb") in {i for i, _ in ids if _ >= 2} or (it.get("imdb") == top):
            it["sure"] = True
            return
        if n < (self.HASH_OVERRIDE if it.get("imdb") else self.HASH_FILL) or (len(ids) > 1 and ids[1][1] * 2 > n):
            return
        row = self.ident.index.get(top) if self.ident.index.db else None
        if not row or row.get("kind") != it.get("kind"):
            return
        it.setdefault("name_imdb", it.get("imdb"))
        it.update(imdb=top, title=row["title"], year=row.get("year"), genres=row.get("genres") or [], sure=True,
                  by_hash=True)

    def hash_check(self):
        """Ask OpenSubtitles about each film's file once (by its hash; a search, no download quota)."""
        if not self.hash_ids:
            return
        roots = {r["id"]: r for r in self.library.roots() if r["exists"]}
        with self.lock:
            todo = [(k, it) for k, it in self.items.items() if it.get("kind") in ("movie", "series") and it.get("root_id") in roots
                    and (it.get("hc") or {}).get("sig") != it.get("probe", {}).get("sig")]
        done = 0
        for k, it in todo:
            if self.wake.is_set():
                break  # the library changed: rescan first
            full = os.path.join(roots[it["root_id"]]["path"], it["file"])
            ids = self.hash_ids(full) if os.path.isfile(full) else None
            if ids is None:
                time.sleep(5)
                continue
            before = it.get("imdb")
            with self.lock:
                it["hc"] = {"sig": it.get("probe", {}).get("sig"), "ids": ids[:3]}
                self._apply_hash(it)
            if it.get("imdb") != before:
                self.log(f"catalog: {it['rel'][:60]}: the file's hash says {it['imdb']}, not {before}")
            done += 1
            if done % 25 == 0:
                self.version += 1
                self._save()
            time.sleep(1.5)  # OpenSubtitles: a few requests a second at most
        if done:
            self.version += 1
            self._save()

    @staticmethod
    def _alone(base, rel):
        """Whether the video `rel` is the only one in its folder (samples aside)."""
        try:
            d = os.path.join(base, rel.rsplit("/", 1)[0])
            return sum(1 for x in os.listdir(d) if os.path.splitext(x)[1].lower() in mp_ident.VIDEO
                       and "sample" not in x.lower()) <= 1
        except OSError:
            return False

    def _item(self, r, rel, fs, is_dir, size, old, group=None):
        base = r["path"]
        eps = mp_ident.content(fs).get("episodes", 0) if is_dir else 0
        if is_dir and eps < 2 and (group or "seriale" in r["tags"]):
            eps = mp_ident.numbered(fs)  # "Show - 01" episodes (anime) in a series folder
        if eps >= 2:  # a series starts at its first episode (not at an extra that sorts first)
            order = mp_ident.episodes(fs)[0]
            main = order[0]["path"] if order else sorted(fs)[0][0]
        else:
            main = max(fs, key=lambda x: x[1])[0]
        name = rel.rsplit("/", 1)[-1]
        nfo = None
        if is_dir:
            try:
                n = next((x for x in os.listdir(os.path.join(base, rel)) if x.lower().endswith(".nfo")), None)
                if n:
                    with open(os.path.join(base, rel, n), errors="ignore") as f:
                        nfo = f.read(65536)
            except OSError:
                pass
        g = self.ident.guess(name, fs if is_dir else None, nfo)
        if not g.get("imdb") and "/" in rel and not is_dir and self._alone(base, rel):
            # a film alone in a folder of its own: the folder's name may say more. Not a folder of several films
            # (a franchise folder with three of them gave each the franchise's newest film), nor against the file's own year
            g2 = self.ident.guess(rel.rsplit("/", 2)[-2], None)
            if g2.get("imdb") and not (g.get("year") and g2.get("year") and abs(g["year"] - g2["year"]) > 1):
                g = g2
        full = os.path.join(base, main)
        st = os.stat(full)
        it = {"v": ITEM_V, "root_id": r["id"], "rel": rel, "file": main, "dir": is_dir, "size": size, "n": len(fs),
              "added": (old or {}).get("added") or st.st_mtime, "kind": g.get("kind") or "movie",
              "imdb": g.get("imdb"), "title": g.get("title") or os.path.splitext(name)[0], "year": g.get("year"),
              "genres": g.get("genres") or [], "kids": g.get("tag") == "bajki" or "bajki" in r["tags"],
              "episodes": eps}
        if ("seriale" in r["tags"] or group) and it["episodes"] >= 2:
            it["kind"] = "series"
        # languages: tracks of the main video (ffprobe once per file) + subtitle files next to it
        probe = (old or {}).get("probe")
        if not probe or probe.get("sig") != [st.st_size, int(st.st_mtime)]:
            probe = {"sig": [st.st_size, int(st.st_mtime)]}
            try:
                import mp_media
                info = mp_media.probe(full)
                probe.update(duration=info.get("duration"), height=(info.get("video") or {}).get("height"),
                             audio=[lang2(a.get("lang")) for a in info.get("audio") or []],
                             subs=[lang2(s.get("lang")) for s in info.get("subs") or []])
            except Exception:  # noqa: BLE001 (a broken or half-downloaded file)
                pass
        it["probe"] = probe
        side = []
        for s in sidecar_subs(full):
            side.append(lang2(s.get("lang")) or "?")
        if is_dir:  # subtitles lying loose in the title's folder (a series, or names that don't match the video)
            try:
                for n in os.listdir(os.path.join(base, rel)):
                    stem, ext = os.path.splitext(n)
                    if ext.lower() in SUB_EXT:
                        m = re.search(r"[._ -]([a-z]{2,3})$", stem.lower())
                        side.append((lang2(m.group(1)) if m else None) or "?")
            except OSError:
                pass
        audio = [x for x in probe.get("audio") or [] if x]
        subs = [x for x in (probe.get("subs") or []) + side if x]
        if PL_AUDIO.search(rel) and "pl" not in audio:
            audio.append("pl")
        if PL_SUBS.search(rel) and "pl" not in subs:
            subs.append("pl")
        it["audio"], it["subs"] = sorted(set(audio)), sorted(set(subs))
        hc = (old or {}).get("hc")
        if hc and hc.get("sig") == probe.get("sig"):  # the same file: the hash's answer still holds
            it["hc"] = hc
            self._apply_hash(it)
        return it

    # ---- Wikidata (by IMDb id)

    def enrich(self, ids=None):
        """Fetches details for titles without them (or older than WD_TTL). `ids`: just these (on demand)."""
        if not self.ident.online:
            return
        now = time.time()
        if ids is None:
            with self.lock:
                ids = sorted({i["imdb"] for i in self.items.values() if i.get("imdb")})
            # agent 1.17+: entries fetched before the film series (P179, folder layout) was asked for are fetched again
            ids = [i for i in ids if now - (self.wd.get(i) or {}).get("at", 0) > WD_TTL or "animated" not in (self.wd.get(i) or {})]
        for k in range(0, len(ids), WD_BATCH):
            self._fetch(ids[k:k + WD_BATCH])
            self.version += 1
            self._save()
        # names left empty: Wikidata keeps many well-known people's names only as "mul" (one label for every
        # language), which agents before 1.14.6 didn't ask for; once per start, also for titles not refreshed above
        if not self._relabelled:
            self._relabelled = True
            with self.lock:
                empty = [q for q, v in self.labels.items() if not v]
            if empty and self._labels(empty):
                self.version += 1
                self._save()

    def _fetch(self, ids):
        rows = _sparql("SELECT ?imdb ?film WHERE { VALUES ?imdb { %s } ?film wdt:P345 ?imdb }"
                       % " ".join(f'"{i}"' for i in ids if re.fullmatch(r"tt\d+", i)))
        qid = {}
        for b in rows:
            qid.setdefault(_val(b, "imdb"), _val(b, "film"))
        now = time.time()
        ents = {}
        qs = list(qid.values())
        for k in range(0, len(qs), 50):
            ents.update(mp_ident._wd({"action": "wbgetentities", "ids": "|".join(qs[k:k + 50]),
                                      "props": "claims|labels", "languages": "pl|en|mul"}, timeout=30).get("entities", {}))
        need = set()
        for imdb in ids:
            e = ents.get(qid.get(imdb) or "", {})
            c = e.get("claims", {})

            def items(prop, claims=c):
                return [x["mainsnak"].get("datavalue", {}).get("value", {}).get("id") for x in claims.get(prop, [])
                        if x.get("rank") != "deprecated" and isinstance(x["mainsnak"].get("datavalue", {}).get("value"), dict)]
            cast = []
            for x in c.get("P161", [])[:CAST_MAX]:
                v = x["mainsnak"].get("datavalue", {}).get("value")
                if not isinstance(v, dict):
                    continue
                q = x.get("qualifiers", {})
                role = next((y["datavalue"]["value"] for y in q.get("P4633", []) if "datavalue" in y), None)
                role_q = next((y["datavalue"]["value"]["id"] for y in q.get("P453", []) if "datavalue" in y), None)
                cast.append([v["id"], role or (("@" + role_q) if role_q else None)])
                if role_q and not role:
                    need.add(role_q)
            ages = [mp_ident.WD_AGE[a] for a in items("P1657") + items("P1981") if a in mp_ident.WD_AGE]
            genre, inst = set(items("P136")), set(items("P31"))
            d = {"kids": bool(genre & mp_ident.WD_KIDS or set(items("P2360")) & mp_ident.WD_CHILDREN),  # 1.17.1+
                 "animated": bool((genre | inst) & mp_ident.WD_ANIM),
                 "at": now, "qid": qid.get(imdb), "cast": cast, "dir": [x for x in items("P57") if x][:3],
                 "topics": [x for x in items("P921") if x][:8], "age": max(ages) if ages else None,
                 "series": [x for x in items("P179") if x][:2],  # film series ("part of the series"), 1.17+
                 "pl": (e.get("labels", {}).get("pl") or {}).get("value"),
                 "en": (e.get("labels", {}).get("en") or e.get("labels", {}).get("mul") or {}).get("value")}
            need.update(p for p, _ in cast)
            need.update(d["dir"] + d["topics"] + d["series"])
            with self.lock:
                self.wd[imdb] = d
        self._labels(need)

    def _labels(self, ids):
        """Names of Wikidata items not known yet (or known without one); "mul" counts as English. The number named."""
        ids = [i for i in ids if i and not self.labels.get(i)]
        named = 0
        for k in range(0, len(ids), 50):
            ents = mp_ident._wd({"action": "wbgetentities", "ids": "|".join(ids[k:k + 50]), "props": "labels",
                                 "languages": "en|pl|mul"}, timeout=30).get("entities", {})
            with self.lock:
                for q, e in ents.items():
                    lb = {x: v["value"] for x, v in e.get("labels", {}).items()}
                    if "en" not in lb and "mul" in lb:
                        lb["en"] = lb["mul"]
                    self.labels[q] = {x: lb[x] for x in ("en", "pl") if x in lb}
                    named += bool(self.labels[q])
        return named

    def label(self, q, lang="en"):
        lb = self.labels.get(q) or {}
        return lb.get(lang) or lb.get("en") or lb.get("pl") or q

    def _topics(self, d):
        """Topics that have a label: an unlabelled Wikidata item is usually a wrong statement."""
        return [q for q in d.get("topics") or [] if self.labels.get(q)]

    # ---- API

    def _visible(self):
        """Items the asking device may see, with the index of their library folder."""
        v = current_viewer()
        roots = {r["id"]: r for r in self.library.roots()}
        out = []
        with self.lock:
            items = list(self.items.items())
        for key, it in items:
            r = roots.get(it["root_id"])
            if not r or not r["exists"] or not can_see(r, v) or set(r["tags"]) & HIDDEN_TAGS:
                continue
            if v is not None and excluded(r, it["file"]):
                continue
            out.append((key, it, r))
        return out

    def public(self, lang="en"):
        """Everything the app needs to browse, in one response (a few hundred KB for a thousand titles)."""
        people, topics, out = {}, {}, []
        vis = self._visible()
        # IMDb data (original title, rating, runtime) read live: the index is rebuilt weekly, the catalog isn't
        rows = self.ident.index.many([it["imdb"] for _, it, _ in vis if it.get("imdb")]) if self.ident.index.db else {}
        for key, it, r in vis:
            d = self.wd.get(it.get("imdb") or "") or {}
            p = it.get("probe") or {}
            row = rows.get(it.get("imdb")) or {}
            title = row.get("title") or it["title"]
            if not row and d.get("en") and d.get("pl") and title == d["pl"]:
                title = d["en"]  # found only through Wikidata: its Polish label is not the original title
            x = {"id": key, "root": r["index"], "root_name": r["name"], "path": it["file"], "dir": it["rel"] if it["dir"] else None,
                 "title": title, "year": it.get("year"), "kind": it["kind"], "imdb": it.get("imdb"),
                 "genres": it.get("genres") or row.get("genres") or [], "rating": row.get("rating"), "votes": row.get("votes"),
                 "minutes": round(p["duration"] / 60) if p.get("duration") and it["kind"] == "movie" else row.get("minutes"),
                 "height": p.get("height"), "audio": it.get("audio") or [], "subs": it.get("subs") or [],
                 "size": it["size"], "added": it["added"], "kids": it.get("kids", False), "episodes": it.get("episodes", 0),
                 "age": d.get("age") if d.get("age") is not None else (0 if it.get("kids") else None),
                 "cast": [c[0] for c in d.get("cast") or []], "roles": [self._role(c[1], lang) for c in d.get("cast") or []],
                 "directors": d.get("dir") or [], "topics": self._topics(d)}
            if it.get("group"):
                x["group"] = it["group"]  # the grouping folder it sits in ("Anime"), agent 1.15.0+
            if d.get("pl") and ("pl" in x["audio"] or "pl" in x["subs"]) and d["pl"] != title:
                x["title_pl"] = d["pl"]
            for q in x["cast"] + x["directors"]:
                people[q] = self.label(q, "en")
            for q in x["topics"]:
                topics[q] = self.label(q, lang)
            out.append(x)
        out.sort(key=lambda x: -x["added"])
        return {"version": self.version, "scanning": self.state["scanning"], "done": self.state["done"],
                "total": self.state["total"], "last": self.state.get("last"), "items": out, "people": people,
                "topics": topics}

    def _role(self, role, lang):
        if not role:
            return None
        return self.label(role[1:], lang) if role.startswith("@") else role

    def _ext_query(self, kind, key, query, pick=lambda b: _val(b, "imdb")):
        hit = self._ext.get((kind, key))
        if hit and time.time() - hit[0] < 86400:
            return hit[1]
        try:
            res = [pick(b) for b in _sparql(query)]
        except Exception as e:  # noqa: BLE001
            self.log("catalog: Wikidata query failed:", e)
            self._ext[(kind, key)] = (time.time() - 86400 + 300, [])  # a failure is retried after 5 minutes
            return []
        self._ext[(kind, key)] = (time.time(), res)
        return res

    def _person_films(self, q):
        """IMDb ids of films with this person; ids of the films they directed are marked with a leading "d:"."""
        # VALUES, not UNION + BIND: the latter makes the query service time out
        return self._ext_query("person", q, "SELECT DISTINCT ?imdb ?p WHERE { VALUES ?p { wdt:P161 wdt:P57 } "
                                            "?f ?p wd:%s . ?f wdt:P345 ?imdb } LIMIT 400" % q,
                               lambda b: ("d:" if (_val(b, "p") or "").endswith("P57") else "") + _val(b, "imdb"))

    def _topic_films(self, q):
        return self._ext_query("topic", q, "SELECT DISTINCT ?imdb WHERE { ?f wdt:P921 wd:%s ; wdt:P345 ?imdb } LIMIT 300" % q)

    def _card(self, row, have, why_en, why_pl, lang):
        x = {"imdb": row["imdb"], "title": row["title"], "year": row.get("year"), "kind": row.get("kind"),
             "genres": row.get("genres") or [], "rating": row.get("rating"), "votes": row.get("votes"),
             "minutes": row.get("minutes"), "why": why_pl if lang == "pl" else why_en}
        if have:
            x.update(have=True, id=have["id"], root=have["root"], path=have["path"], title=have["title"],
                     audio=have["audio"], subs=have["subs"], **({"title_pl": have["title_pl"]} if have.get("title_pl") else {}))
        else:
            x["have"] = False
        return x

    def title(self, imdb, lang="en"):
        """A title by IMDb id, also one that is not on the disks: index row + Wikidata details."""
        if not re.fullmatch(r"tt\d+", imdb or ""):
            raise ValueError("bad imdb id")
        row = self.ident.index.get(imdb) if self.ident.index.db else None
        if imdb not in self.wd and self.ident.online:
            try:
                self._fetch([imdb])
            except Exception as e:  # noqa: BLE001
                self.log("catalog: Wikidata failed:", e)
        d = self.wd.get(imdb) or {}
        have = [x for x in self.public(lang)["items"] if x["imdb"] == imdb]
        out = dict(row or {"imdb": imdb, "title": d.get("pl") or imdb}, have=bool(have), copies=have,
                   cast=[[q, self.label(q), self._role(r, lang)] for q, r in d.get("cast") or []],
                   directors=[[q, self.label(q)] for q in d.get("dir") or []],
                   topics=[[q, self.label(q, lang)] for q in self._topics(d)], age=d.get("age"))
        if not have and d.get("qid"):
            about = self._about(d["qid"], lang)  # agent 1.17+: a title you don't have gets its description
            if about:
                out["about"], out["about_source"] = about
        return out

    def _about(self, qid, lang):
        """The lead of the title's Wikipedia article (Polish for a Polish phone when there is one, else English), as
        (text, "Wikipedia"), or None. Asked only for a title page the user opened; kept for a day like other lookups."""
        if not self.ident.online or not re.fullmatch(r"Q\d+", qid):
            return None

        def fetch():
            sites = ["plwiki", "enwiki"] if lang == "pl" else ["enwiki", "plwiki"]
            e = mp_ident._wd({"action": "wbgetentities", "ids": qid, "props": "sitelinks",
                              "sitefilter": "|".join(sites)}).get("entities", {}).get(qid, {})
            links = e.get("sitelinks") or {}
            for site in sites:
                title = (links.get(site) or {}).get("title")
                if not title:
                    continue
                u = (f"https://{site[:2]}.wikipedia.org/api/rest_v1/page/summary/"
                     + urllib.parse.quote(title.replace(" ", "_"), safe=""))
                with urllib.request.urlopen(urllib.request.Request(u, headers=mp_ident.UA), timeout=8) as r:
                    text = (json.load(r).get("extract") or "").strip()
                if text:
                    return [text[:1500]]
            return []
        hit = self._ext.get(("about", qid, lang))
        if hit and time.time() - hit[0] < 86400:
            res = hit[1]
        else:
            try:
                res = fetch()
            except Exception as e:  # noqa: BLE001 (network: the page shows the rest)
                self.log("catalog: Wikipedia failed:", e)
                self._ext[("about", qid, lang)] = (time.time() - 86400 + 300, [])  # retried after 5 minutes
                return None
            self._ext[("about", qid, lang)] = (time.time(), res)
        return (res[0], "Wikipedia") if res else None

    def search(self, query, limit=20):
        """Titles from the local IMDb index matching `query` (agent 1.17+), each marked `have` when one of the
        library's visible titles has the same IMDb id (with its catalog `id`). No network: the index is on disk."""
        query = str(query or "").strip()[:100]
        with self.ident.lock:
            index = self.ident.index
        if not query or not index.db:
            return {"items": [], "index": bool(index.db)}
        try:
            limit = max(1, min(int(limit), 50))
        except (TypeError, ValueError):
            limit = 20
        mine = {}
        for key, it, _ in self._visible():
            if it.get("imdb"):
                mine.setdefault(it["imdb"], key)
        out = []
        for r in index.search(query, limit):
            x = {k: r.get(k) for k in ("imdb", "title", "year", "end", "kind", "genres", "rating", "votes", "minutes")}
            x["have"] = r["imdb"] in mine
            if x["have"]:
                x["id"] = mine[r["imdb"]]
            out.append(x)
        return {"items": out, "index": True}

    def person(self, qid, lang="en"):
        """Films with this person in the collection (with roles) and the best known ones that are not on the disks."""
        if not re.fullmatch(r"Q\d+", qid or ""):
            raise ValueError("bad id")
        self._labels([qid])
        items = self.public(lang)["items"]
        mine = []
        for x in items:
            if qid in x["cast"]:
                mine.append(dict(x, role=x["roles"][x["cast"].index(qid)], as_director=False))
            elif qid in x["directors"]:
                mine.append(dict(x, role=None, as_director=True))
        found = self._person_films(qid)
        directed = {i[2:] for i in found if i.startswith("d:")}
        ids = list(dict.fromkeys(i[2:] if i.startswith("d:") else i for i in found))
        acted = len(set(ids) - directed)
        have = {x["imdb"] for x in items}
        rows = self.ident.index.many(ids) if self.ident.index.db else {}
        other = sorted((r for r in rows.values() if r["imdb"] not in have and r["kind"] == "movie"), key=lambda r: -(r["votes"] or 0))[:20]
        mine_dir = sum(1 for x in mine if x["as_director"])
        role = "director" if (len(directed) > acted if found else mine and mine_dir == len(mine)) else "actor"
        return {"id": qid, "name": self.label(qid), "role": role, "films": mine,
                "missing": [self._card(r, None, "", "", lang) for r in other]}

    def next(self, imdb, lang="en", limit=24):
        """What to watch after `imdb`: titles from the collection first, then well known ones that are not on the disks.
        Reasons: same actors, same genres, same topic."""
        items = self.public(lang)["items"]
        by_imdb = {x["imdb"]: x for x in items if x["imdb"]}
        me = by_imdb.get(imdb)
        if me is None and re.fullmatch(r"tt\d+", imdb or ""):
            t = self.title(imdb, lang)
            me = {"imdb": imdb, "cast": [c[0] for c in t["cast"]], "topics": [c[0] for c in t["topics"]],
                  "genres": t.get("genres") or [], "year": t.get("year")}
        if not me:
            return {"items": []}
        # Wikidata queries are slow (5-15 s each): all of them at once, then the loops below hit the cache
        from concurrent.futures import ThreadPoolExecutor
        with ThreadPoolExecutor(5) as ex:
            for q in me["cast"][:3]:
                ex.submit(self._person_films, q)
            for q in me["topics"][:2]:
                ex.submit(self._topic_films, q)
        out, seen = [], {imdb}

        def add(row, why_en, why_pl, why):
            if not row or row["imdb"] in seen:
                return
            seen.add(row["imdb"])
            c = self._card(row, by_imdb.get(row["imdb"]), why_en, why_pl, lang)
            c["reason"] = why
            out.append(c)
        index = self.ident.index if self.ident.index.db else None
        # same actors: the first three billed
        for q in me["cast"][:3]:
            name = self.label(q)
            for x in items:
                if q in x["cast"] and x["imdb"]:
                    add(x, f"also {name}", f"też {name}", "actor")
            ids = [i[2:] if i.startswith("d:") else i for i in self._person_films(q)]
            rows = sorted((index.many(ids) if index else {}).values(), key=lambda r: -(r["votes"] or 0))
            for r in [r for r in rows if r["kind"] == "movie"][:4]:
                add(r, f"also {name}", f"też {name}", "actor")
        # same topic
        for q in me["topics"][:2]:
            name = self.label(q, lang)
            for x in items:
                if q in x["topics"] and x["imdb"]:
                    add(x, name, name, "topic")
            ids = self._topic_films(q)
            rows = sorted((index.many(ids) if index else {}).values(), key=lambda r: -(r["votes"] or 0))
            for r in [r for r in rows if r["kind"] == "movie"][:4]:
                add(r, name, name, "topic")
        # same genres: the collection by shared genres and rating, then popular titles of the same genres and era
        gs = set(me.get("genres") or []) - {"Drama"} or set(me.get("genres") or [])
        if gs:
            mine = sorted((x for x in items if x["imdb"] and len(gs & set(x["genres"])) >= min(2, len(gs))),
                          key=lambda x: (-len(gs & set(x["genres"])), -(x["rating"] or 0)))
            for x in mine[:8]:
                add(x, ", ".join(sorted(gs & set(x["genres"]))), ", ".join(sorted(gs & set(x["genres"]))), "genre")
            if index:
                mask = sum(mp_ident.G[g] for g in list(gs)[:2] if g in mp_ident.G)
                y = me.get("year")
                for r in index.popular(mask, 0, (y - 15, y + 15) if y else None, 8, exclude=seen):
                    add(r, ", ".join(sorted(gs & set(r["genres"]))), ", ".join(sorted(gs & set(r["genres"]))), "genre")
        out.sort(key=lambda c: (not c["have"], {"actor": 0, "topic": 1, "genre": 2}[c["reason"]]))
        return {"items": out[:limit]}

    def suggest(self, imdb, lang="en", limit=20):
        """G4, "Because you watched <title>: not on your disks" (agent 1.18+): popular titles from the local IMDb index
        that share people (directors, the first billed actors), genres and era with `imdb` and that no library folder
        has. The candidates are computed once per watched title and kept for a day (`SUGGEST_TTL`); each answer only
        drops what the library has meanwhile. Names are those of the title page (Wikidata details, cached)."""
        if not re.fullmatch(r"tt\d+", imdb or ""):
            raise ValueError("bad imdb id")
        with self.ident.lock:
            index = self.ident.index
        if not index.db:
            return {"items": [], "index": False}
        try:
            limit = max(1, min(int(limit), 40))
        except (TypeError, ValueError):
            limit = 20
        hit = self._suggest.get(imdb)
        if hit and time.time() < hit[0]:
            src, cands = hit[1], hit[2]
        else:
            src, cands, ttl = self._suggest_compute(imdb, index)
            self._suggest[imdb] = (time.time() + ttl, src, cands)
            if len(self._suggest) > SUGGEST_KEEP:  # the oldest answers go first
                for k in sorted(self._suggest, key=lambda k: self._suggest[k][0])[:len(self._suggest) - SUGGEST_KEEP]:
                    self._suggest.pop(k, None)
        with self.lock:
            have = {it.get("imdb") for it in self.items.values() if it.get("imdb")}
        pl = lang == "pl"
        out = []
        for c in cands:
            if c["imdb"] in have:
                continue
            x = {k: v for k, v in c.items() if k not in ("why_en", "why_pl", "score")}
            x["why"] = c["why_pl"] if pl else c["why_en"]
            out.append(x)
            if len(out) >= limit:
                break
        return {"items": out, "index": True, "source": src}

    def _suggest_compute(self, imdb, index):
        """(source title, ranked candidates, seconds to keep them) for `suggest`. Scores: a shared director 4, each
        shared actor 2.5, each shared genre 1.5 (a generic one like Drama 0.5), the same era (within 10 years) 1, and
        popularity log10(votes). Only titles of the same kind (film / series) with SUGGEST_MIN_VOTES or more; for a
        kids' title only animation and family titles."""
        me = index.get(imdb) or {}
        with self.lock:
            mine = next((it for it in self.items.values() if it.get("imdb") == imdb), None)
        if imdb not in self.wd and self.ident.online:
            try:
                self._fetch([imdb])
            except Exception as e:  # noqa: BLE001 (network: genres and era still work)
                self.log("catalog: Wikidata failed:", e)
        d = self.wd.get(imdb) or {}
        kind = me.get("kind") or (mine or {}).get("kind") or "movie"
        genres = set(me.get("genres") or (mine or {}).get("genres") or [])
        year = me.get("year") or (mine or {}).get("year")
        kids = bool(d.get("kids") or (mine or {}).get("kids") or d.get("age") == 0)
        src = {"imdb": imdb, "title": me.get("title") or (mine or {}).get("title") or d.get("en") or imdb, "year": year,
               "kind": kind}
        cand = {}  # imdb → {"row", "dirs", "cast", "genres"}

        def note(r):
            return cand.setdefault(r["imdb"], {"row": r, "dirs": [], "cast": []})
        dirs, cast = (d.get("dir") or [])[:2], [q for q, _ in d.get("cast") or []][:4]
        ttl = SUGGEST_TTL
        if self.ident.online and (dirs or cast):
            from concurrent.futures import ThreadPoolExecutor
            with ThreadPoolExecutor(3) as ex:  # more at once and the query service answers 429
                found = dict(zip(dirs + cast, ex.map(self._person_films, dirs + cast)))
            for q, ids in found.items():
                hit = self._ext.get(("person", q))
                if not hit or time.time() - hit[0] > 86400 - 600:  # _ext_query stamps a failure a day minus 5 min old
                    ttl = 300  # a failed Wikidata query: try again soon instead of keeping a list without people
                directed = {i[2:] for i in ids if i.startswith("d:")}
                rows = index.many(list(dict.fromkeys(i[2:] if i.startswith("d:") else i for i in ids)))
                for r in rows.values():
                    if q in dirs and r["imdb"] in directed:
                        note(r)["dirs"].append(q)
                    elif q in cast and r["imdb"] not in directed:
                        note(r)["cast"].append(q)
        specific = genres - SUGGEST_GENERIC or genres
        g = sorted(specific)[:3]
        masks = {mp_ident.G[a] | mp_ident.G[b] for i, a in enumerate(g) for b in g[i + 1:] if a in mp_ident.G and b in mp_ident.G}
        if not masks and g and g[0] in mp_ident.G:
            masks = {mp_ident.G[g[0]]}
        years = (year - 15, year + 15) if year else None
        for m in masks:
            for r in index.popular(m, 1 if kind == "series" else 0, years, 40):
                note(r)
        out = []
        for i, c in cand.items():
            r = c["row"]
            if i == imdb or (r.get("votes") or 0) < SUGGEST_MIN_VOTES or r.get("kind") != kind:
                continue
            rg = set(r.get("genres") or [])
            if kids and not rg & {"Animation", "Family"}:
                continue
            shared = genres & rg
            if not shared and not c["dirs"] and not c["cast"]:
                continue
            score = (4 * len(c["dirs"]) + 2.5 * len(c["cast"]) + sum(0.5 if x in SUGGEST_GENERIC else 1.5 for x in shared)
                     + (1 if year and r.get("year") and abs(r["year"] - year) <= 10 else 0) + math.log10(r["votes"]))
            if c["dirs"]:
                n = self.label(c["dirs"][0])
                why = (f"directed by {n}", f"reżyseria: {n}", "director")
            elif c["cast"]:
                n = ", ".join(self.label(q) for q in c["cast"][:2])
                why = (f"also {n}", f"też {n}", "actor")
            else:
                n = ", ".join(sorted(shared))
                why = (n, n, "genre")
            x = {k: r.get(k) for k in ("imdb", "title", "year", "kind", "genres", "rating", "votes", "minutes")}
            x.update(have=False, reason=why[2], why_en=why[0], why_pl=why[1], score=round(score, 2))
            out.append(((c["dirs"][:1] or c["cast"][:1] or ["genre"])[0], x))
        out.sort(key=lambda kx: -kx[1]["score"])
        # variety: at most SUGGEST_PER_REASON titles for one person (or for genres alone) before the rest
        first, rest, per = [], [], {}
        for k, x in out:
            per[k] = per.get(k, 0) + 1
            (first if per[k] <= SUGGEST_PER_REASON else rest).append(x)
        return src, (first + rest)[:SUGGEST_POOL], ttl


class Wishlist:
    """Shared "want to watch" list (every phone sees the same one): wishlist.json in the config directory."""

    def __init__(self, path):
        self.path = path
        self.lock = threading.Lock()
        try:
            with open(path) as f:
                self.items = json.load(f)
        except (OSError, ValueError):
            self.items = []

    def _save(self):
        tmp = self.path + ".tmp"
        with open(tmp, "w") as f:
            json.dump(self.items, f, ensure_ascii=False)
        os.replace(tmp, self.path)

    def list(self, catalog, lang="en"):
        have = {}
        for x in catalog.public(lang)["items"]:
            if x["imdb"]:
                have.setdefault(x["imdb"], x)
        out = []
        for w in self.items:
            h = have.get(w["imdb"])
            out.append(dict(w, have=bool(h), **({"id": h["id"], "root": h["root"], "path": h["path"],
                                                 "where": h["root_name"]} if h else {})))
        return out

    def add(self, b, by):
        imdb = str(b.get("imdb") or "")
        if not re.fullmatch(r"tt\d+", imdb):
            raise ValueError("bad imdb id")
        with self.lock:
            if not any(w["imdb"] == imdb for w in self.items):
                self.items.insert(0, {"imdb": imdb, "title": str(b.get("title") or imdb)[:200], "year": b.get("year"),
                                      "genres": b.get("genres") or [], "why": str(b.get("why") or "")[:200],
                                      "by": by, "at": time.time()})
                self._save()

    def remove(self, imdb):
        with self.lock:
            self.items = [w for w in self.items if w["imdb"] != imdb]
            self._save()
