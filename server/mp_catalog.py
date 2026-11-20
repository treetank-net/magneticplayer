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
user configures (no search sites are built in).

State: catalog.json in the config directory (items, Wikidata details, labels).
"""
import json
import os
import re
import threading
import time
import urllib.parse
import urllib.request

import mp_ident
from mp_library import HIDDEN_TAGS, SUB_EXT, VIDEO_EXT, can_see, current_viewer, excluded, sidecar_subs

RESCAN = 1800          # seconds between library walks (a change in the library triggers one sooner)
WD_TTL = 30 * 86400    # Wikidata details are refreshed after a month
WD_BATCH = 40
SPARQL = "https://query.wikidata.org/sparql"
CAST_MAX = 12
ITEM_V = 6             # bump when the rules for an item change (languages, identification): items are redone (no ffprobe)
SKIP_TAGS = {"muzyka", "inne"}
LANG2 = {"pol": "pl", "eng": "en", "jpn": "ja", "ger": "de", "deu": "de", "fre": "fr", "fra": "fr", "spa": "es",
         "ita": "it", "rus": "ru", "por": "pt", "dut": "nl", "nld": "nl", "cze": "cs", "ces": "cs", "chi": "zh",
         "zho": "zh", "kor": "ko", "swe": "sv", "nor": "no", "dan": "da", "fin": "fi", "hun": "hu", "ukr": "uk"}
PL_AUDIO = re.compile(r"(?i)\b(lektor|dubbing|pldub|dub\.?pl|pl\.?dub)\b")
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
                self.enrich()
            except Exception as e:  # noqa: BLE001
                self.log("catalog: Wikidata failed:", e)
            self.wake.wait(RESCAN)

    # ---- walking the library

    def _units(self, r):
        """Titles under a library folder: [(rel of the title, [(rel file, size)], is_dir, group)]; `group` is the name of
        a grouping folder (_group_of) the title sits in, else None."""
        files = {}
        for p, is_dir, size in self.library._names(r):  # cached walk, shared with the search
            if is_dir or os.path.splitext(p)[1].lower() not in VIDEO_EXT or "sample" in p.lower():
                continue
            files.setdefault(p.split("/", 1)[0], []).append((p, size))
        out = []
        for top, fs in files.items():
            if len(fs) == 1 and fs[0][0] == top:
                out.append((top, fs, False, None))
                continue
            subs = _group_of(top, fs)
            if subs is not None:
                # a grouping folder ("Anime/" with a folder per series): every subfolder and every loose video is a title
                for name, sfs in subs.items():
                    out.append((f"{top}/{name}", sfs, True, top))
                out += [(p, [(p, sz)], False, top) for p, sz in fs if p.count("/") == 1]
                continue
            c = mp_ident.content(fs)
            biggest = max(s for _, s in fs)
            big = [(p, s) for p, s in fs if s > 0.15 * biggest]
            if c.get("videos", 0) >= 3 and c.get("episodes", 0) < 2 and "seriale" not in r["tags"]:
                # a folder of films (a category or a collection): every big video is a title
                for p, s in big:
                    out.append((p, [(p, s)], False, None))
            else:
                out.append((top, fs, True, None))
        return out

    def title_for(self, full):
        """A library video's recognised name for screens (mpv's title, the Remote): "Series · S2 E3", "Film (1968)";
        None when the catalog doesn't know it (the file name stays)."""
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
            if not it or not it.get("title"):
                return None
            t = it["title"]
            if it.get("kind") == "series":
                se, ep = mp_ident.episode_no(rel)
                return f"{t} · S{se} E{ep}" if se is not None and ep is not None else f"{t} · E{ep}" if ep is not None else t
            return f"{t} ({it['year']})" if it.get("year") else t
        return None

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
        if not g.get("imdb") and "/" in rel and not is_dir:  # a film inside a category folder: its folder may say more
            g2 = self.ident.guess(rel.rsplit("/", 2)[-2], None)
            if g2.get("imdb"):
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
            ids = [i for i in ids if now - (self.wd.get(i) or {}).get("at", 0) > WD_TTL]
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
            d = {"at": now, "qid": qid.get(imdb), "cast": cast, "dir": [x for x in items("P57") if x][:3],
                 "topics": [x for x in items("P921") if x][:8], "age": max(ages) if ages else None,
                 "pl": (e.get("labels", {}).get("pl") or {}).get("value"),
                 "en": (e.get("labels", {}).get("en") or e.get("labels", {}).get("mul") or {}).get("value")}
            need.update(p for p, _ in cast)
            need.update(d["dir"] + d["topics"])
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
        return out

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
