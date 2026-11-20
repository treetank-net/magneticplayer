"""Folder layout (A2) and balance between disks (A3) of docs/plan-ux-tasks.md, agent 1.17+: where each title should be.

Both work on whole titles as the catalog sees them (a film's folder or file, a series' folder, a title inside a grouping
folder) and only compute; mp_tasks turns the result into a plan of `move` items (a rename on the same disk, a copy to
another disk, a torrent moved by its client) that the owner previews and confirms.

Folder layout. `levels` is the ordered list the owner tapped: `kind` (films / series), `age` (age group), `category`
(a grouping folder such as "Anime", or an "anime" folder tag), `genre`, `collection` (a film series from Wikidata,
P179) and `decade`. Kind, age group and category share the top folder: a category wins, then an age group mapped to
"kids" or "teens", then the kind; an age group mapped to "films" adds no folder of its own (it stays with the films).
Genre, film series and decade nest under the top folder in the order tapped, for films only (a series stays
"<top>/<series>/"). A top folder that is a library folder of its own on the same disk (by its tag or its name) is used
as it is, so a library with "Films" and "Series" folders tagged `filmy` and `seriale` is already in order. Every title
stays on its disk.

Which library folder (agent 1.17+): several folders on a disk can take the same top folder (Kids, Kids PL, Kids EN).
A folder's tags work like a CSS selector: the folder fits a title only when the title has every one of its tags, and
the folder with the most fitting tags wins ("bajki" + "pl" beats "bajki" for a cartoon with Polish sound). A title's
tags: its kind and top folder (the synonyms below), "anime", the languages of its sound tracks from the background
scan ("pl", "en"…) and the tags set by hand for the title (`title_tags` in the config, e.g. from an AI assistant). A
tag the title can't be judged on (sound tracks without a language, a file not scanned yet) keeps it where it is when
its current folder needs that tag; a tie keeps it where it is too. Tags outside this vocabulary ("archiwum",
"kopia") don't take part.

Folders the owner arranged by hand are the folders that hold two or more titles and aren't one of the layout's own
names (Films, Series, Kids…): grouping folders and collections. They are detected here and confirmed by the owner once
(`layout_keep` in the config); until then all of them are left alone.

Balance. `goal` is `even` (the same share of free space on every disk), `kind_per_disk` (series on one disk, films and
the rest on the others, by the folders' tags) or `fill_newer` (the largest disk, taken as the newer one, is filled up to
FILL_FREE free from the others). A title moves to the library folder with the same tag (or name) on the other disk,
into the same subfolder, so the layout on every disk stays the same.

Seeding torrents (decision 13): a title that is a torrent's content moves through the torrent client; titles inside a
torrent that holds several of them move only together, as the torrent; a folder with torrents inside isn't moved.
"""
import os
import re

from mp_i18n import T
import mp_scope

LEVELS = ("kind", "age", "category", "genre", "collection", "decade")
SUB_LEVELS = ("genre", "collection", "decade")
BUCKETS = ("kids", "teens", "films")
DEFAULT_AGES = {"0": "kids", "7": "kids", "12": "teens", "16": "films", "18": "films"}
DEFAULT_LEVELS = ["kind", "age", "category"]
GOALS = ("even", "kind_per_disk", "fill_newer")
FILL_FREE = 0.10  # fill_newer leaves this share of the newer disk free
RESERVE = 0.05  # no move leaves a disk under this share free
COPY_SPEED = 60 << 20  # bytes per second of a copy between two USB disks (the estimate)
DECADE = re.compile(r"\d{4}[–-]\d{4}")  # a decade folder of the layout ("1960–1969")
# folder tags (and English names) that mean the same top folder
SYNONYMS = {"movie": {"filmy", "films", "movies", "film"}, "series": {"seriale", "series", "tv"},
            "kids": {"bajki", "kids", "children"}, "teens": {"mlodziez", "młodzież", "teens", "youth"}}


# a folder tag that is a sound language (ISO 639-1) and the ISO 639-2 codes ffprobe reports for it
LANGS = {"pl": {"pol", "pl"}, "en": {"eng", "en"}, "de": {"ger", "deu", "de"}, "fr": {"fre", "fra", "fr"},
         "es": {"spa", "es"}, "it": {"ita", "it"}, "ru": {"rus", "ru"}, "ja": {"jpn", "ja"}, "uk": {"ukr", "uk"},
         "cs": {"cze", "ces", "cs"}}
_LANG_OF = {c: k for k, v in LANGS.items() for c in v}
OTHER_TAGS = {"anime", "muzyka", "music"}


def vocabulary():
    """Every folder tag that takes part in choosing a folder (the rest are notes for the owner)."""
    out = set(LANGS) | OTHER_TAGS
    for v in SYNONYMS.values():
        out |= v
    return out


def sound_langs(facts, root_id, rel, is_dir, index=None):
    """The sound languages ("pl", "en") of a title from the background scan's facts, or None when they can't be told
    (not scanned yet, or any track without a language)."""
    keys = (index or {}).get((root_id, rel)) if is_dir else [f"{root_id}/{rel}"]
    langs, unknown = set(), False
    for k in keys or []:
        info = ((facts or {}).get(k) or {}).get("info")
        if not info:
            unknown = True
            continue
        for a in info.get("audio") or []:
            code = (a.get("lang") or "").lower()
            if code in _LANG_OF:
                langs.add(_LANG_OF[code])
            elif code in ("", "und", "unk"):
                unknown = True
    if not keys or unknown:
        return None  # a track without a language could be any (a Polish dub tagged "und"): no guessing
    return langs


def facts_index(facts):
    """{(root id, folder): [fact keys of the videos under it]} for sound_langs on folders."""
    out = {}
    for k in facts or {}:
        rid, _, rel = k.partition("/")
        parts = rel.split("/")[:-1]
        for n in range(1, len(parts) + 1):
            out.setdefault((rid, "/".join(parts[:n])), []).append(k)
    return out


def fit(root, u):
    """How a library folder fits a title by its tags: None (it doesn't), or (fitting tags, undecided tags)."""
    voc = vocabulary()
    have = set(u.get("facts") or ())
    ok, undecided = [], []
    for t in {x.lower() for x in root.get("tags") or []} & voc:
        if t in LANGS and t in have:
            ok.append(t)  # set by hand for the title
        elif t in LANGS:
            if u.get("audio") is None:
                undecided.append(t)
            elif t in u["audio"]:
                ok.append(t)
            else:
                return None
        elif t in have:
            ok.append(t)
        else:
            return None
    return sorted(ok), sorted(undecided)


def names():
    """The layout's folder names in the request's language."""
    return {"movie": T("Films", "Filmy"), "series": T("Series", "Seriale"), "kids": T("Kids", "Bajki"),
            "teens": T("Teens", "Młodzież")}


def own_names():
    """Every name a layout folder can have (both languages and the tag synonyms), lower case."""
    out = set()
    for v in SYNONYMS.values():
        out |= v
    out |= {"filmy", "seriale", "bajki", "młodzież", "films", "series", "kids", "teens"}
    return out


def norm_ages(raw):
    """An age group → bucket mapping from the config or a request; unknown groups and buckets fall back to the default."""
    out = dict(DEFAULT_AGES)
    for k, v in (raw or {}).items():
        k = str(k).rstrip("+")
        if k in out and v in BUCKETS:
            out[k] = v
    return out


def norm_levels(raw):
    if isinstance(raw, str):
        raw = [x for x in raw.split(",") if x]
    out = []
    for x in raw or []:
        if x in LEVELS and x not in out:
            out.append(x)
    return out


def units(m, roots_by_id, catalog=None, lang="en", facts=None, title_tags=None):
    """The titles in the scope of matcher `m` (whose catalog snapshot it holds), as dicts for layout and balance.
    `facts`: the background scan (sound languages); `title_tags`: {imdb id: [tags]} set by hand."""
    out, own = [], own_names()
    fidx = facts_index(facts)
    title_tags = title_tags or {}
    per_dir = {}  # (root id, folder) → titles the catalog sees directly in it
    for it in m.items.values():
        if it.get("rel") and "/" in it["rel"]:
            k = (it.get("root_id"), it["rel"].rsplit("/", 1)[0])
            per_dir[k] = per_dir.get(k, 0) + 1
    for key, it in m.items.items():
        r = roots_by_id.get(it.get("root_id"))
        if not r or not it.get("rel") or not m.match(r, it["rel"]):
            continue
        if it["rel"].rsplit("/", 1)[-1].lower() in own:
            continue  # a layout folder ("Kids/") the catalog took for a title: never moved as one
        rel, is_dir = it["rel"], bool(it.get("dir"))
        if not is_dir and "/" in rel:
            # a film's file in a folder of its own ("Comedy/Sintel (2010)/Sintel.mkv", a folder of films): the title
            # is the folder, which moves with its subtitles and covers
            parent = rel.rsplit("/", 1)[0]
            if per_dir.get((it.get("root_id"), parent)) == 1 and parent.count("/") >= 1 \
                    and parent.rsplit("/", 1)[-1].lower() not in own and not DECADE.fullmatch(parent.rsplit("/", 1)[-1]):
                rel, is_dir = parent, True
        d = m.wd.get(it.get("imdb") or "") or {}
        coll = None
        if d.get("series") and catalog is not None:
            try:
                coll = catalog.label(d["series"][0], lang)
            except Exception:  # noqa: BLE001 — a label is only a folder name
                coll = None
        tags = [t.lower() for t in r.get("tags") or []]
        g = it.get("group") or ""
        group = g if g.lower() not in own and not DECADE.fullmatch(g) else None  # "Films/" is no category
        cat = group or ("Anime" if "anime" in tags or "anime" in {g.lower() for g in it.get("genres") or []} else None)
        kids_folder = bool(SYNONYMS["kids"] & set(tags))
        out.append({"key": key, "root_id": r["id"], "root": r["index"], "root_name": r["name"], "rel": rel,
                    "full": os.path.join(r["path"], rel), "dir": is_dir, "size": it.get("size") or 0,
                    "kind": it.get("kind") or "movie", "imdb": it.get("imdb"), "title": it.get("title"),
                    "year": it.get("year"), "genres": it.get("genres") or [], "category": cat,
                    "age": mp_scope.age_group(m.age_of(it)), "collection": coll, "tags": tags,
                    "for_kids": for_kids(it.get("genres"), d, kids_folder, m.age_of(it)),
                    "audio": sound_langs(facts, r["id"], rel, is_dir, fidx),
                    "hand_tags": [str(t).lower() for t in title_tags.get(it.get("imdb") or "", [])]})
    return out


DARK = {"Horror", "Thriller", "Crime", "War"}


def for_kids(genres, wd, in_kids_folder, age=None):
    """Whether a title belongs with the cartoons ("kids"), not just whether its age rating is low (a comedy rated 6
    is no cartoon): Wikidata calls it a children's film or its intended public children, or it is animated without
    dark genres (and either "Family" or an age rating: unrated animation may be adult anime). "Family" alone
    (live-action fairy tales) is undecided: it stays where it is, as does a title nothing is known about. A title
    already in a kids folder stays a kids title: the catalog only adds cartoons, it never takes one out."""
    g, wd = set(genres or []), wd or {}
    if in_kids_folder:
        return True  # put there by a person; the catalog's match may be wrong (a 1961 cartoon taken for its remake)
    if wd.get("kids"):
        return True
    if "Animation" in g or wd.get("animated"):
        if g & DARK:
            return False
        return True if "Family" in g or age is not None else in_kids_folder
    if "Family" in g and not g & DARK:
        return in_kids_folder
    if g or "animated" in wd:
        return False
    return in_kids_folder


def bucket(u, ages):
    """The age folder of a title: "kids", "teens" or None. The `ages` mapping applies to the age rating, but "kids"
    only takes titles for children (for_kids): a live-action film rated 6 stays with the films, and a cartoon rated 12
    or without a rating goes to the kids."""
    age = u.get("age")
    b = ages.get(str(age)) if age is not None else None
    kids = u.get("for_kids")
    if kids and (age is None or age <= 12) and b != "kids":
        return "kids"
    if b == "kids" and kids is False:
        return None
    return b if b in ("kids", "teens") else None


def title_facts(u, top, ages):
    """The tags a title has for fit(): its kind, its top folder's synonyms, anime, and the ones set by hand."""
    f = set(SYNONYMS["series" if u["kind"] == "series" else "movie"])
    if top:
        f |= SYNONYMS.get(top[0], set()) | ({top[0][4:]} if top[0].startswith("cat:") else set())
    if bucket(u, ages):
        f |= SYNONYMS[bucket(u, ages)]
    if (u.get("category") or "").lower() == "anime":
        f.add("anime")
    f |= set(u.get("hand_tags") or [])
    return f


def _clean(name):
    """A folder name from catalog text: no slashes, no leading dots, not too long."""
    s = str(name or "").replace("/", "-").replace("\\", "-").strip().lstrip(".")
    return s[:80].strip()


def components(u, levels, ages, nm):
    """(top, subs) folder names for a title: `top` is (key, name) or None; `subs` the nested folders."""
    top = None
    if "category" in levels and u["category"]:
        top = ("cat:" + u["category"].lower(), _clean(u["category"]))
    elif "age" in levels and bucket(u, ages):
        b = bucket(u, ages)
        top = (b, nm[b])
    elif "kind" in levels:
        top = (u["kind"], nm["series" if u["kind"] == "series" else "movie"])
    subs = []
    if u["kind"] != "series":
        for lv in levels:
            if lv == "genre" and u["genres"]:
                subs.append(_clean(u["genres"][0]))
            elif lv == "collection" and u["collection"]:
                subs.append(_clean(u["collection"]))
            elif lv == "decade" and u["year"]:
                y = int(u["year"]) // 10 * 10
                subs.append(f"{y}–{y + 9}")
    return top, [s for s in subs if s]


def root_for(top, dev, roots_on_dev, own_root, u=None):
    """(library folder, why) on the same disk that is the top folder itself (its tag or name), or (None, None).
    Among several, the most specific fitting one (see the module docstring); undecided or a tie: the current one."""
    key, name = top
    syn = SYNONYMS.get(key, set()) | ({key[4:]} if key.startswith("cat:") else set())
    cands = [r for r in roots_on_dev.get(dev, []) if syn & {t.lower() for t in r.get("tags") or []}
             or (r.get("name") or "").lower() == name.lower()]
    if not cands:
        return None, None
    if u is None:
        return next((r for r in cands if r["id"] == own_root["id"]), cands[0]), None
    fits = [(r, fit(r, u)) for r in cands]
    fits = [(r, f) for r, f in fits if f is not None]
    if not fits:
        return None, None
    own = next(((r, f) for r, f in fits if r["id"] == own_root["id"]), None)
    if own and own[1][1]:
        return own[0], None  # its own folder needs a tag the title can't be judged on: it stays
    best = max(len(f[0]) for _, f in fits)
    top_fits = [(r, f) for r, f in fits if len(f[0]) == best and not f[1]]
    if not top_fits:
        return (own[0], None) if own else (None, None)
    if own and own in top_fits:
        return own[0], None
    if len(top_fits) > 1:
        return (own[0], None) if own else (top_fits[0][0], None)
    r, f = top_fits[0]
    return r, " + ".join(f[0]) if len(f[0]) > 1 else None


def hand_folders(us, roots_by_id):
    """Folders holding 2+ titles that aren't the layout's own names (the owner arranged them): outermost only."""
    count, size = {}, {}
    own = own_names()
    for u in us:
        parts = u["rel"].split("/")[:-1]
        for k in range(1, len(parts) + 1):
            p = "/".join(parts[:k])
            count[(u["root_id"], p)] = count.get((u["root_id"], p), 0) + 1
            size[(u["root_id"], p)] = size.get((u["root_id"], p), 0) + u["size"]
    found = []
    for (rid, p), n in count.items():
        if n < 2 or p.rsplit("/", 1)[-1].lower() in own:
            continue
        found.append((rid, p))
    found.sort(key=lambda x: (x[0], x[1].count("/"), x[1]))
    out = []
    for rid, p in found:
        if any(o[0] == rid and p.startswith(o[1] + "/") for o in out):
            continue
        out.append((rid, p))
    res = []
    for rid, p in out:
        r = roots_by_id.get(rid)
        if r:
            res.append({"root": r["index"], "root_id": rid, "root_name": r["name"], "path": p,
                        "name": p.rsplit("/", 1)[-1], "titles": count[(rid, p)], "size": size[(rid, p)]})
    return res


def torrent_of(full, torrents):
    """('self', id) for a torrent's content, ('in', content path) inside one, ('contains', None), or (None, None)."""
    if not torrents:
        return None, None
    rp = os.path.realpath(full)
    if rp in torrents:
        return "self", torrents[rp]
    for c in torrents:
        if rp.startswith(c + os.sep):
            return "in", c
    if any(c.startswith(rp + os.sep) for c in torrents):
        return "contains", None
    return None, None


def group_torrents(us, roots_by_id, torrents):
    """Titles with their torrent relation: titles inside one multi-title torrent become one unit (the torrent's folder,
    `members` = the titles), a title whose folder contains torrents gets `blocked`. Returns the new list."""
    out, inside = [], {}
    for u in us:
        how, x = torrent_of(u["full"], torrents)
        if how == "self":
            out.append(dict(u, torrent=x))
        elif how == "in":
            inside.setdefault(x, []).append(u)
        elif how == "contains":
            out.append(dict(u, blocked=True))
        else:
            out.append(u)
    for c, members in inside.items():
        u0 = members[0]
        r = roots_by_id[u0["root_id"]]
        rp = os.path.realpath(r["path"])
        if not c.startswith(rp + os.sep):
            for u in members:
                out.append(dict(u, blocked=True))
            continue
        rel = os.path.relpath(c, rp).replace(os.sep, "/")
        out.append(dict(u0, key=f"{u0['root_id']}/{rel}", rel=rel, full=os.path.join(r["path"], rel), dir=os.path.isdir(c),
                        size=sum(x["size"] for x in members), torrent=torrents[c], members=members,
                        busy=any(x.get("busy") for x in members),
                        title=os.path.basename(rel)))
    return out


def layout(us, levels, ages, keep, roots, nm, keep_unsure=True, keep_seeding=False, devs=None):
    """The moves of a folder layout. `us` from group_torrents; `keep`: [(root id, path)] folders left alone; `roots`:
    the job roots (writable ones can take titles). Returns (moves, skipped, stats): a move is (unit, dest root, sub),
    a skipped entry (unit, reason key), stats counts `unsure`, `seeding`, `kept`, `together`."""
    devs = devs or {}
    roots_on_dev = {}
    for r in roots:
        if r.get("writable", True):
            roots_on_dev.setdefault(devs.get(r["id"]), []).append(r)
    by_id = {r["id"]: r for r in roots}
    moves, skipped = [], []
    stats = {"unsure": 0, "seeding": 0, "kept": 0, "together": 0}
    taken = set()

    def dest_of(u):
        top, subs = components(u, levels, ages, nm)
        own = by_id[u["root_id"]]
        dev = devs.get(u["root_id"])
        dr, why = root_for(top, dev, roots_on_dev, own, dict(u, facts=title_facts(u, top, ages))) if top else (None, None)
        u["why_root"] = why
        if top and dr is None:
            dr, sub = own, [top[1]] + subs
        else:
            dr, sub = dr or own, subs
        return dr, "/".join(sub)

    for u in us:
        if any(u["root_id"] == rid and (u["rel"] == p or u["rel"].startswith(p + "/")) for rid, p in keep):
            stats["kept"] += 1
            skipped.append((u, "kept"))
            continue
        if u.get("blocked"):
            skipped.append((u, "contains_torrents"))
            continue
        if u.get("busy"):
            skipped.append((u, "busy"))
            continue
        if u.get("torrent") is not None:
            stats["seeding"] += 1
            if keep_seeding:
                skipped.append((u, "seeding"))
                continue
        members = u.get("members")
        if members:
            dests = {(d[0]["id"], d[1]) for d in (dest_of(x) for x in members)}
            if len(dests) > 1:
                stats["together"] += 1
                skipped.append((u, "together"))
                continue
            dr, sub = dest_of(members[0])
            unsure = any(not x.get("imdb") for x in members)
        else:
            dr, sub = dest_of(u)
            unsure = not u.get("imdb")
        if unsure:
            stats["unsure"] += 1
            if keep_unsure:
                skipped.append((u, "unsure"))
                continue
        cur = u["rel"].rsplit("/", 1)[0] if "/" in u["rel"] else ""
        if dr["id"] == u["root_id"] and cur == sub:
            continue
        target = (dr["id"], (sub + "/" if sub else "") + u["rel"].rsplit("/", 1)[-1])
        if target in taken or (sub and (u["rel"] == sub or sub.startswith(u["rel"] + "/")) and dr["id"] == u["root_id"]):
            skipped.append((u, "exists"))
            continue
        taken.add(target)
        moves.append((dict(u, unsure=unsure), dr, sub))
    return moves, skipped, stats


def balance(goal, us, disks, roots, devs, rules=None, watched=None):
    """Moves between disks for `goal`. `disks`: {dev: {"name", "total", "free"}}; `rules`: series_whole (always: units
    are whole titles here), skip_watching, seeding (True = a torrent moves whole through its client, False = left
    alone), backup (a title of a replicated folder never moves onto its replica's disk). Returns (moves, skipped,
    after = {dev: free after}): a move is (unit, dest root, sub)."""
    rules = dict({"skip_watching": True, "seeding": True, "backup": False}, **(rules or {}))
    watched = watched or set()
    free = {d: v["free"] for d, v in disks.items()}
    total = {d: v["total"] for d, v in disks.items()}
    roots_on_dev = {}
    for r in roots:
        if r.get("writable", True):
            roots_on_dev.setdefault(devs.get(r["id"]), []).append(r)
    by_id = {r["id"]: r for r in roots}
    moves, skipped, cands = [], [], {}
    for u in us:
        dev = devs.get(u["root_id"])
        if dev not in disks:
            continue
        if u.get("blocked"):
            skipped.append((u, "contains_torrents"))
            continue
        if u.get("busy"):
            skipped.append((u, "busy"))
            continue
        if u.get("torrent") is not None and not rules["seeding"]:
            skipped.append((u, "seeding"))
            continue
        rp = os.path.realpath(u["full"])  # `watched` holds real paths
        if rules["skip_watching"] and any(rp == w or rp.startswith(w + os.sep) or w.startswith(rp + os.sep) for w in watched):
            skipped.append((u, "watching"))
            continue
        cands.setdefault(dev, []).append(u)

    def dest_root(u, dev):
        own = by_id[u["root_id"]]
        tags = [t.lower() for t in own.get("tags") or []]
        for t in tags:
            r = next((r for r in roots_on_dev.get(dev, []) if t in {x.lower() for x in r.get("tags") or []}), None)
            if r:
                return r
        return next((r for r in roots_on_dev.get(dev, []) if (r.get("name") or "").lower() == (own.get("name") or "").lower()), None)

    def allowed(u, dev):
        if not rules["backup"]:
            return True
        rep = by_id[u["root_id"]].get("replicate_to")
        return not rep or devs.get(rep) != dev

    def room(dev, keep=RESERVE):
        return free[dev] - int(total[dev] * keep)

    def put(u, src, dst, keep=RESERVE):
        r = dest_root(u, dst)
        if r is None:
            return "no_folder"
        if not allowed(u, dst):
            return "backup"
        if room(dst, keep) < u["size"]:
            return "no_room"
        sub = u["rel"].rsplit("/", 1)[0] if "/" in u["rel"] else ""
        moves.append((u, r, sub))
        free[dst] -= u["size"]
        free[src] += u["size"]
        return None

    devlist = list(disks)
    if len(devlist) < 2:
        return [], skipped, dict(free)
    if goal == "even":
        target = sum(free.values()) / max(sum(total.values()), 1)
        for src in sorted(devlist, key=lambda d: free[d] / max(total[d], 1)):
            need = target * total[src] - free[src]
            if need <= total[src] * 0.01:
                continue
            for u in sorted(cands.get(src, []), key=lambda x: -x["size"]):
                need = target * total[src] - free[src]
                if need <= total[src] * 0.005:
                    break
                if u["size"] > need * 1.1:
                    continue
                dsts = [d for d in devlist if d != src and free[d] / max(total[d], 1) > target]
                for dst in sorted(dsts, key=lambda d: -(free[d] - target * total[d])):
                    if free[dst] - u["size"] < target * total[dst] - total[dst] * 0.01:
                        continue
                    if put(u, src, dst) is None:
                        break
    elif goal == "kind_per_disk":
        def series(u):
            return u["kind"] == "series" or "seriale" in u.get("tags", [])
        sb = {d: sum(u["size"] for u in cands.get(d, []) if series(u)) for d in devlist}
        s_disk = max(devlist, key=lambda d: (sb[d], total[d]))
        for d in devlist:
            if d == s_disk:
                continue
            for u in sorted((u for u in cands.get(d, []) if series(u)), key=lambda x: -x["size"]):
                why = put(u, d, s_disk)
                if why:
                    skipped.append((u, why))
        for u in sorted((u for u in cands.get(s_disk, []) if not series(u)), key=lambda x: -x["size"]):
            others = sorted((d for d in devlist if d != s_disk), key=lambda d: -room(d))
            why = put(u, s_disk, others[0]) if others else "no_room"
            if why:
                skipped.append((u, why))
    elif goal == "fill_newer":
        newer = max(devlist, key=lambda d: (total[d], free[d]))
        for d in sorted((d for d in devlist if d != newer), key=lambda d: total[d]):
            for u in sorted(cands.get(d, []), key=lambda x: -x["size"]):
                if room(newer, FILL_FREE) < u["size"]:
                    skipped.append((u, "no_room"))
                    continue
                why = put(u, d, newer, FILL_FREE)
                if why and why != "no_room":
                    skipped.append((u, why))
    return moves, skipped, dict(free)


def reason(key):
    """A skipped title's reason, localized."""
    return {
        "kept": T("a folder you arranged yourself", "folder ułożony przez Ciebie"),
        "contains_torrents": T("contains torrents still in the client", "zawiera torrenty, które są jeszcze w kliencie"),
        "seeding": T("seeding in the torrent client", "seeduje w kliencie torrentów"),
        "together": T("a torrent holding several titles stays together: its titles belong in different folders",
                      "torrent z kilkoma tytułami zostaje w całości: jego tytuły pasują do różnych folderów"),
        "unsure": T("not surely recognised", "bez pewnego rozpoznania"),
        "exists": T("the same name already exists there", "tam już jest coś o tej nazwie"),
        "watching": T("being watched", "ktoś to teraz ogląda"),
        "no_folder": T("no folder with the same tag on that disk", "brak folderu z tym samym tagiem na tamtym dysku"),
        "no_room": T("not enough room on the other disk", "za mało miejsca na drugim dysku"),
        "backup": T("its backup copy is on that disk", "jego kopia zapasowa jest na tamtym dysku"),
        "busy": T("another task still works on it", "inne zadanie jeszcze na nim pracuje"),
    }.get(key, key)
