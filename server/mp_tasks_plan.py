"""Planners of the first task screen (agent < 1.16 and MCP): disks, free space by moving, move, tidy, subtitles,
duplicates, trash, shrink by storage profiles. Previews only."""
import os
import shutil
import time

import mp_ident
import mp_library
import mp_media
from mp_i18n import T
from mp_library import HIDDEN_TAGS, VIDEO_EXT
from mp_tasks_common import (
    AUDIO_CHECK_BUDGET, AUDIO_SUSPECT, ENCODERS, GPU_SIZE, MIN_SAVING, MIN_SIZE, OLD_GROWTH, PROFILES, TaskError,
    _audio_kbps, _audio_keep, _audio_src_kbps, _dev, _lang2, _mount, _title, companions, is_old, tree_size
)


PROFILE_NAMES = ("original", *PROFILES)
FILM_TAGS = {"filmy", "bajki"}  # films for adults and for children: moving between them is only a suggestion


def effective_profile(root, rel):
    """The profile for a path inside a library folder: the longest matching `profiles` entry, else `profile`."""
    if set(root.get("tags") or []) & HIDDEN_TAGS:
        return "original"
    best, best_len = root.get("profile") or "original", -1
    rel = rel.strip("/")
    for k, v in (root.get("profiles") or {}).items():
        k = k.strip("/")
        if (rel == k or rel.startswith(k + "/") or k == "") and len(k) > best_len:
            best, best_len = v, len(k)
    return best if best in PROFILE_NAMES else "original"


DATA_FS = {"ext4", "ext3", "btrfs", "xfs", "zfs", "f2fs", "ntfs", "ntfs3", "fuseblk", "exfat", "vfat", "jfs", "reiserfs"}


def _data_mounts():
    """Mount points of disks that could hold films: / and /home, /media/*, /mnt/*, /run/media/*, /srv/*
    (Linux; elsewhere nothing extra). Not /boot, snaps, containers or network shares."""
    out = []
    try:
        with open("/proc/mounts") as f:
            lines = f.read().splitlines()
    except OSError:
        return out
    for line in lines:
        parts = line.split()
        if len(parts) < 3 or parts[2] not in DATA_FS:
            continue
        mnt = parts[1].replace("\\040", " ")
        if mnt in ("/", "/home") or mnt.startswith(("/media/", "/mnt/", "/run/media/", "/srv/", "/data")):
            if mnt not in out:
                out.append(mnt)
    return out


class PlanMixin:

    # ---- disks

    def disks(self):
        """Disks with library folders: total/free and how much each folder takes."""
        out = {}
        for r in self.library.roots():
            if not r["exists"]:
                continue
            dev = _dev(r["path"])
            try:
                u = shutil.disk_usage(r["path"])
            except OSError:
                continue
            d = out.setdefault(dev, {"id": str(dev), "total": u.total, "free": u.free, "used": u.used,
                                     "mount": _mount(r["path"]), "roots": []})
            size, n = tree_size(r["path"])
            d["roots"].append({"root": r["index"], "id": r["id"], "name": r["name"], "tags": r["tags"],
                               "size": size, "files": n, "profile": r.get("profile") or "original",
                               "profiles": r.get("profiles") or {}})
        for mnt in _data_mounts():  # disks without library folders too (the system disk, a new one): room for them
            dev = _dev(mnt)
            if dev is None or dev in out:
                continue
            try:
                u = shutil.disk_usage(mnt)
            except OSError:
                continue
            if u.total < 8 << 30:  # small partitions (boot, recovery, USB sticks for installers)
                continue
            out[dev] = {"id": str(dev), "total": u.total, "free": u.free, "used": u.used, "mount": mnt, "roots": []}
        disks = sorted(out.values(), key=lambda d: (not d["roots"], d["free"] / max(d["total"], 1)))
        home = _dev(os.path.expanduser("~"))
        for d in disks:
            d["name"] = os.path.basename(d["mount"].rstrip("/")) or d["mount"]
            d["system"] = d["mount"] == "/" or (str(home) == d["id"] and d["mount"] in ("/home", os.path.expanduser("~")))
            d["home"] = os.path.expanduser("~") if str(home) == d["id"] else d["mount"]  # where "add folder" starts
        return disks

    # ---- planner: free space on a disk by moving to other disks

    def plan_free(self, root_index, need=None):
        def run(p):
            roots = self.library.roots()
            if not 0 <= root_index < len(roots) or not roots[root_index]["exists"]:
                raise TaskError(T("no such library folder", "nie ma takiego katalogu biblioteki"), 404)
            src_dev = _dev(roots[root_index]["path"])
            u = shutil.disk_usage(roots[root_index]["path"])
            want = int(need) if need else max(int(u.total * 0.15) - u.free, 0)
            torrents = self._torrents()
            targets = {}
            for r in roots:
                if not r["exists"] or _dev(r["path"]) == src_dev or set(r["tags"]) & HIDDEN_TAGS \
                        or not os.access(r["path"], os.W_OK):
                    continue
                tu = shutil.disk_usage(r["path"])
                for tag in r["tags"]:
                    targets.setdefault(tag, []).append({"root": r, "room": tu.free - int(tu.total * 0.05)})
            cands, skipped = [], []
            src_roots = [r for r in roots if r["exists"] and _dev(r["path"]) == src_dev and not set(r["tags"]) & HIDDEN_TAGS]
            for k, r in enumerate(src_roots):
                p["progress"] = int(100 * k / max(len(src_roots), 1))
                p["step"] = r["name"]  # what is being looked at, under the preview's progress bar
                try:
                    names = sorted(os.listdir(r["path"]))
                except OSError:
                    continue
                for name in names:
                    if name.startswith("."):
                        continue
                    full = os.path.join(r["path"], name)
                    if os.path.isfile(full) and os.path.splitext(name)[1].lower() not in VIDEO_EXT:
                        continue  # subtitles and the like go with their video
                    size, n = tree_size(full)
                    extra = companions(full) if os.path.isfile(full) else []
                    size += sum(tree_size(os.path.join(r["path"], e))[0] for e in extra)
                    item = {"op": "move", "root_id": r["id"], "root": r["index"], "root_name": r["name"],
                            "path": name, "name": name, "size": size, "files": n, "extra": extra}
                    where = self._inside(full, torrents)
                    if where in ("contains", "in"):
                        skipped.append(dict(item, reason=T("contains torrents still in the client",
                                                             "zawiera torrenty, które są jeszcze w kliencie")))
                        continue
                    if where == "self":
                        item["torrent"] = torrents[os.path.realpath(full)]
                    tag = next((t for t in r["tags"] if t in targets), None)
                    if not tag:
                        skipped.append(dict(item, reason=T("no folder with the same tag on another disk",
                                                             "brak folderu z tym samym tagiem na innym dysku")))
                        continue
                    item["tag"] = tag
                    cands.append(item)
            # greedy: the smallest item that covers the rest on its own, otherwise the largest, until enough
            chosen, freed = [], 0
            pool = sorted(cands, key=lambda i: i["size"])
            while freed < want and pool:
                rest = want - freed
                pick = next((i for i in pool if i["size"] >= rest), pool[-1])
                pool.remove(pick)
                dest = max(targets[pick["tag"]], key=lambda t: t["room"])
                if dest["room"] < pick["size"]:
                    skipped.append(dict(pick, reason=T("not enough room on the other disk", "za mało miejsca na drugim dysku")))
                    continue
                if os.path.exists(os.path.join(dest["root"]["path"], pick["name"])):
                    skipped.append(dict(pick, reason=T("the same name already exists there", "tam już jest coś o tej nazwie")))
                    continue
                dest["room"] -= pick["size"]
                pick.update(to_root_id=dest["root"]["id"], to_root=dest["root"]["index"], to_name=dest["root"]["name"])
                chosen.append(pick)
                freed += pick["size"]
            chosen.sort(key=lambda i: (i["root_name"], i["name"].lower()))
            warnings = []
            if not want:  # "up to 15% free" on a disk that has more: an empty preview needs a reason
                pct = round(100 * u.free / max(u.total, 1))
                warnings.append(T(f"This disk is already {pct}% free, more than 15%: choose how many GB to free instead.",
                                  f"Ten dysk ma już {pct}% wolnego, więcej niż 15%: wybierz raczej, ile GB zwolnić."))
            return {"kind": "move", **_title(f"Free up {roots[root_index]['name']}", f"Zwolnij {roots[root_index]['name']}"),
                    "need": want, "free_before": u.free, "free_after": u.free + freed, "total": u.total,
                    "items": chosen, "bytes": freed, "skipped": skipped[:50], "warnings": warnings}
        return self._plan("move", run)

    def plan_move(self, items, m=None):
        """Explicit moves ({root, path, to_root, to_sub}), e.g. from an AI assistant over MCP; checked like plan_free."""
        def run(p):
            roots, torrents, out, skipped = self.library.roots(), self._torrents(), [], []
            for x in items:
                try:
                    r, d = roots[int(x["root"])], roots[int(x["to_root"])]
                    full = self.library.resolve(r["index"], x["path"])
                    if os.path.realpath(full) == os.path.realpath(r["path"]):
                        raise ValueError(T("that is the whole library folder", "to cały folder biblioteki"))
                    to_sub = str(x.get("to_sub") or "").strip("/")
                    if os.path.isabs(to_sub) or ".." in to_sub.replace("\\", "/").split("/"):
                        raise ValueError(T("bad target folder", "zły folder docelowy"))
                except Exception as e:  # noqa: BLE001 — a bad entry is reported, the rest goes on
                    skipped.append({"path": x.get("path"), "reason": str(e)})
                    continue
                name = os.path.basename(full.rstrip("/"))
                size, n = tree_size(full)
                item = {"op": "move", "root_id": r["id"], "root": r["index"], "root_name": r["name"],
                        "path": os.path.relpath(full, os.path.realpath(r["path"])), "name": name, "size": size,
                        "files": n, "extra": companions(full) if os.path.isfile(full) else [],
                        "to_root_id": d["id"], "to_root": d["index"], "to_name": d["name"],
                        "to_sub": str(x.get("to_sub") or "").strip("/")}
                where = self._inside(full, torrents)
                if where in ("contains", "in"):
                    skipped.append(dict(item, reason=T("contains torrents still in the client",
                                                         "zawiera torrenty, które są jeszcze w kliencie")))
                    continue
                if where == "self":
                    item["torrent"] = torrents[os.path.realpath(full)]
                out.append(item)
            return {"kind": "move", **_title(f"Move {len(out)} items", f"Przenieś {len(out)} pozycji"),
                    "items": out, "bytes": sum(i["size"] for i in out), "skipped": skipped}
        return self._plan("move", run, m)

    # ---- planner: tidying up (stage 4): recognition says the item belongs to a folder with another tag

    def plan_tidy(self, ident, m=None):
        def run(p):
            roots = [r for r in self.library.roots() if r["exists"] and not set(r["tags"]) & HIDDEN_TAGS]
            torrents = self._torrents()
            by_tag = {}
            for r in roots:
                if os.access(r["path"], os.W_OK):
                    for tag in r["tags"]:
                        by_tag.setdefault(tag, []).append(r)
            items, skipped, n = [], [], 0
            for k, r in enumerate(roots):
                try:
                    names = sorted(os.listdir(r["path"]))
                except OSError:
                    continue
                for j, name in enumerate(names):
                    if name.startswith("."):
                        continue
                    full = os.path.join(r["path"], name)
                    if os.path.isfile(full) and os.path.splitext(name)[1].lower() not in VIDEO_EXT:
                        continue
                    n += 1
                    # within the folder too: one big folder held the bar still for most of the preview
                    p["progress"] = int(100 * (k + j / max(len(names), 1)) / max(len(roots), 1))
                    p["step"] = name  # what is being looked at, under the preview's progress bar
                    files, nfo = [], None
                    if os.path.isdir(full):
                        for dp, dirs, fs in os.walk(full):
                            dirs[:] = [d for d in dirs if not d.startswith(".")]
                            for f in fs:
                                fp = os.path.join(dp, f)
                                try:
                                    files.append((os.path.relpath(fp, full), os.stat(fp).st_size))
                                except OSError:
                                    continue
                                if f.lower().endswith(".nfo") and nfo is None:
                                    try:
                                        with open(fp, errors="replace") as fh:
                                            nfo = fh.read(65536)
                                    except OSError:
                                        pass
                            if len(files) > 2000:
                                break
                    g = ident.guess(name, files or None, nfo)
                    tag = g.get("tag")
                    if not tag or tag in r["tags"] or tag not in by_tag:
                        continue
                    size = sum(sz for _, sz in files) if files else tree_size(full)[0]
                    item = {"op": "move", "root_id": r["id"], "root": r["index"], "root_name": r["name"], "path": name,
                            "name": name, "size": size, "files": len(files) or 1,
                            "extra": companions(full) if os.path.isfile(full) else [], "tag": tag,
                            "guess": {k2: g.get(k2) for k2 in ("kind", "title", "year", "confidence", "why")},
                            "unsure": g.get("confidence") == "low"}
                    if tag in FILM_TAGS and set(r["tags"]) & FILM_TAGS:
                        # a film either way, only "for children" differs: the user's choice of folder wins unless confirmed
                        item["unsure"] = True
                        item["guess"]["confidence"] = "low"
                    where = self._inside(full, torrents)
                    if where in ("contains", "in"):
                        skipped.append(dict(item, reason=T("contains torrents still in the client",
                                                             "zawiera torrenty, które są jeszcze w kliencie")))
                        continue
                    if where == "self":
                        item["torrent"] = torrents[os.path.realpath(full)]
                    dev = _dev(r["path"])
                    # the same disk first (an instant rename), otherwise the one with most free space
                    cands = sorted(by_tag[tag], key=lambda d: (_dev(d["path"]) != dev, -shutil.disk_usage(d["path"]).free))
                    d = cands[0]
                    if os.path.exists(os.path.join(d["path"], name)):
                        skipped.append(dict(item, reason=T("the same name already exists there", "tam już jest coś o tej nazwie")))
                        continue
                    items.append(dict(item, to_root_id=d["id"], to_root=d["index"], to_name=d["name"]))
            items.sort(key=lambda i: (i["unsure"], i["root_name"], i["name"].lower()))
            return {"kind": "move", **_title("Tidy up the library", "Porządki w bibliotece"), "checked": n,
                    "items": items, "bytes": sum(i["size"] for i in items), "skipped": skipped[:100]}
        return self._plan("tidy", run, m)

    # ---- planner: duplicates (stage 4): size, then the beginning and end of the file, then the whole file

    # ---- subtitles fitted to the films (mp_subsync)

    def torrent_owned(self, path, torrents=None, cache=None):
        """Whether this file is part of a torrent in the client: changing it would corrupt what is being seeded.
        When the client can't be asked, assume it is."""
        try:
            torrents = self._torrents() if torrents is None else torrents
        except TaskError:
            return True
        rp = os.path.realpath(path)
        for content, tid in torrents.items():
            if rp == content:
                return True
            if rp.startswith(content + os.sep):
                cache = {} if cache is None else cache
                if tid not in cache:
                    try:
                        save, files = self.backend().files(tid)
                        cache[tid] = {os.path.realpath(os.path.join(save, f["path"])) for f in files}
                    except Exception:  # noqa: BLE001
                        cache[tid] = None
                return cache[tid] is None or rp in cache[tid]
        return False

    def plan_subs(self, every=False):
        """Videos with subtitle files (SRT, text SUB) next to them, in every library folder except backups. Files assessed
        before (and unchanged since) are left out unless `every`."""
        import mp_subsync

        def run(p):
            if not mp_subsync.available():
                raise TaskError(T("speech detection is not installed on this computer (numpy, webrtcvad)",
                                  "na tym komputerze nie ma wykrywania mowy (numpy, webrtcvad)"))
            torrents, cache = self._torrents(), {}
            roots = [x for x in self.library.roots() if x["exists"] and not set(x["tags"]) & HIDDEN_TAGS]
            files = []
            for r in roots:
                for dp, dirs, fs in os.walk(r["path"]):
                    dirs[:] = sorted(d for d in dirs if not d.startswith("."))
                    files += [(r, os.path.join(dp, f)) for f in sorted(fs)
                              if os.path.splitext(f)[1].lower() in VIDEO_EXT and not f.startswith(".")]
            items, checked = [], 0
            for k, (r, full) in enumerate(files):
                p["progress"] = int(100 * k / max(len(files), 1))
                p["step"] = os.path.basename(full)  # what is being looked at, under the preview's progress bar
                subs = []
                for x in mp_library.sidecar_subs(full):
                    sp = os.path.join(os.path.dirname(full), x["name"])
                    ext = os.path.splitext(sp)[1].lower()
                    if ext not in (".srt", ".sub") or (ext == ".sub" and not mp_subsync.is_text_sub(sp)):
                        continue
                    if not every and self.subsync.get(sp):
                        checked += 1
                        continue
                    subs.append({"name": x["name"], "lang": x["lang"],
                                 "locked": self.torrent_owned(sp, torrents, cache)})
                if not subs:
                    continue
                size = sum(os.path.getsize(os.path.join(os.path.dirname(full), x["name"])) for x in subs)
                items.append({"op": "subs", "root_id": r["id"], "root_name": r["name"],
                              "path": os.path.relpath(full, r["path"]), "name": os.path.basename(full),
                              "size": size, "subs": subs})
            p["progress"] = 100
            return {"kind": "subs", **_title("Fit subtitles to the films", "Dopasuj napisy do filmów"),
                    "items": items, "skipped": [], "checked": checked, "bytes": sum(i["size"] for i in items),
                    "minutes": -(-len(items) // 2)}
        return self._plan("subs", run)

    def plan_subs_get(self, languages, root=None, path=None):
        """Videos without subtitles in `languages` (no file next to them, no track in the video) in the whole library
        (except backups), or in one library folder or file (`root` + `path`: a series, a season, a film). Agent 1.12.1+."""
        langs = [l.strip().lower() for l in languages if l.strip()]
        if not langs:
            raise TaskError(T("give languages, e.g. pl,en", "podaj języki, np. pl,en"))

        def run(p):
            files = []
            if root is not None:
                roots = self.library.roots()  # `root` is the folder's index, as in /lib/<index>/ and the app's refs
                r = roots[int(root)] if 0 <= int(root) < len(roots) else None
                if not r:
                    raise TaskError(T("no such library folder", "nie ma takiego folderu biblioteki"), 404)
                base = os.path.realpath(os.path.join(r["path"], path or ""))
                if not (base + os.sep).startswith(os.path.realpath(r["path"]) + os.sep) and base != os.path.realpath(r["path"]):
                    raise TaskError(T("path outside the library", "ścieżka poza biblioteką"))
                scope = [(r, base)]
            else:
                scope = [(r, r["path"]) for r in self.library.roots() if r["exists"] and not set(r["tags"]) & HIDDEN_TAGS]
            for r, base in scope:
                if os.path.isfile(base):
                    files.append((r, base))
                    continue
                for dp, dirs, fs in os.walk(base):
                    dirs[:] = sorted(d for d in dirs if not d.startswith(".") and d.lower() not in ("subs", "sub", "subtitles"))
                    # samples, trailers, featurettes: nobody watches them with subtitles (each download counts)
                    files += [(r, os.path.join(dp, f)) for f in sorted(fs)
                              if os.path.splitext(f)[1].lower() in VIDEO_EXT and not f.startswith(".")
                              and not mp_ident.is_extra(os.path.relpath(os.path.join(dp, f), base))]
            items, have = [], 0
            for k, (r, full) in enumerate(files):
                p["progress"] = int(100 * k / max(len(files), 1))
                p["step"] = os.path.basename(full)  # what is being looked at, under the preview's progress bar
                side = mp_library.sidecar_subs(full)
                # a file without a language in its name ("Film.srt"): the language from its text, so a film that has
                # subtitles is not counted as missing them (each download uses up the daily OpenSubtitles limit)
                got = set()
                for x in side:
                    code = _lang2(x.get("lang"))
                    got.add(code if len(code) == 2 else mp_library.text_lang(os.path.join(os.path.dirname(full), x["name"])))
                try:
                    import mp_media
                    got |= {_lang2(s.get("lang")) for s in (mp_media.probe(full).get("subs") or [])}
                except Exception:  # noqa: BLE001  (no ffprobe, an unreadable file: the files next to it decide)
                    pass
                missing = [l for l in langs if l not in got]
                if not missing:
                    have += 1
                    continue
                items.append({"op": "subs_get", "root_id": r["id"], "root": r["index"], "root_name": r["name"],
                              "path": os.path.relpath(full, r["path"]), "name": os.path.basename(full),
                              "size": 1, "langs": missing})
            p["progress"] = 100
            return {"kind": "subs_get", **_title("Download missing subtitles", "Pobierz brakujące napisy"),
                    "items": items, "skipped": [], "checked": have, "bytes": 0, "languages": langs,
                    "minutes": len(items)}
        return self._plan("subs_get", run)

    def plan_dupes(self, m=None):
        def run(p):
            import hashlib
            by_size = {}
            roots = [r for r in self.library.roots() if r["exists"] and not set(r["tags"]) & HIDDEN_TAGS]  # `kopia`: on purpose
            for n, r in enumerate(roots):
                p["progress"] = int(30 * n / max(len(roots), 1))  # walking the folders: 0–30 %
                p["step"] = r["name"]  # what is being looked at, under the preview's progress bar
                for dp, dirs, fs in os.walk(r["path"]):
                    dirs[:] = [d for d in dirs if not d.startswith(".")]
                    for f in fs:
                        if os.path.splitext(f)[1].lower() not in VIDEO_EXT:
                            continue
                        fp = os.path.join(dp, f)
                        try:
                            sz = os.stat(fp).st_size
                        except OSError:
                            continue
                        if sz >= MIN_SIZE:
                            by_size.setdefault(sz, []).append((r, fp))
            def distinct(g):
                # one entry per file on disk: symlinks and hard links to the same data are not copies (nothing to free)
                seen, out = set(), []
                for r, fp in g:
                    try:
                        st = os.stat(fp)
                    except OSError:
                        continue
                    if (st.st_dev, st.st_ino) not in seen:
                        seen.add((st.st_dev, st.st_ino))
                        out.append((r, fp))
                return out
            groups = [g for g in (distinct(g) for g in by_size.values() if len(g) > 1) if len(g) > 1]

            def quick(fp, sz):
                h = hashlib.sha1()
                with open(fp, "rb") as f:
                    h.update(f.read(4 << 20))
                    f.seek(max(sz - (4 << 20), 0))
                    h.update(f.read(4 << 20))
                return h.hexdigest()

            def full(fp):
                # two more 4 MB samples from the middle instead of reading gigabytes over USB: with the same size, start
                # and end that is as good as certain for video, and what gets deleted goes to the trash first anyway
                h = hashlib.sha1()
                with open(fp, "rb") as f:
                    for frac in (1 / 3, 2 / 3):
                        f.seek(int(sz * frac))
                        h.update(f.read(4 << 20))
                return h.hexdigest()
            try:  # files of torrents still in the client can't be deleted here (the trash plan skips them): say so now
                torrents = self._torrents()
            except TaskError:
                torrents = {}
            out = []
            for k, g in enumerate(groups):
                p["progress"] = 30 + int(70 * k / max(len(groups), 1))
                p["step"] = os.path.basename(g[0][1])  # what is being looked at, under the preview's progress bar
                sz = os.stat(g[0][1]).st_size
                for hf in (lambda fp: quick(fp, sz), full):
                    buckets = {}
                    for r, fp in g:
                        try:
                            buckets.setdefault(hf(fp), []).append((r, fp))
                        except OSError:
                            pass
                    g = max(buckets.values(), key=len) if buckets else []
                    if len(g) < 2:
                        break
                if len(g) >= 2:
                    out.append({"size": sz, "files": [{"root": r["index"], "root_id": r["id"], "root_name": r["name"],
                                                       "path": os.path.relpath(fp, os.path.realpath(r["path"])),
                                                       "torrent": bool(self._inside(fp, torrents))}
                                                      for r, fp in g]})
            out.sort(key=lambda g: -g["size"] * (len(g["files"]) - 1))
            return {"kind": "dupes", **_title("Duplicates", "Duplikaty"), "groups": out, "items": [],
                    "wasted": sum(g["size"] * (len(g["files"]) - 1) for g in out)}
        return self._plan("dupes", run, m)

    def plan_trash(self, items, m=None):
        """Files the owner picked to delete (e.g. a duplicate): they go to the trash of their library folder."""
        def run(p):
            roots, torrents, out, skipped = self.library.roots(), self._torrents(), [], []
            for x in items:
                try:
                    r = roots[int(x["root"])]
                    full = self.library.resolve(r["index"], x["path"])
                    if os.path.realpath(full) == os.path.realpath(r["path"]):
                        raise ValueError(T("that is the whole library folder", "to cały folder biblioteki"))
                except Exception as e:  # noqa: BLE001
                    skipped.append({"path": x.get("path"), "reason": str(e)})
                    continue
                item = {"op": "trash", "root_id": r["id"], "root": r["index"], "root_name": r["name"],
                        "path": os.path.relpath(full, os.path.realpath(r["path"])), "name": os.path.basename(full),
                        "size": tree_size(full)[0], "extra": companions(full) if os.path.isfile(full) else []}
                if self._inside(full, torrents):
                    skipped.append(dict(item, reason=T("still in the torrent client: remove it there",
                                                         "jest jeszcze w kliencie torrentów: usuń go tam")))
                    continue
                out.append(item)
            return {"kind": "trash", **_title(f"Delete {len(out)} items", f"Usuń {len(out)} pozycji"), "items": out,
                    "bytes": sum(i["size"] for i in out), "skipped": skipped, "trash_days": self.trash_days()}
        return self._plan("trash", run, m)

    # ---- planner: shrinking by storage profiles

    def set_profile(self, root_index, rel, profile):
        if profile not in PROFILE_NAMES:
            raise TaskError(T("unknown profile", "nieznany profil"))
        self.library.roots()
        lib = self.cfg["library"]
        if not 0 <= root_index < len(lib):
            raise TaskError(T("no such library folder", "nie ma takiego katalogu biblioteki"), 404)
        r, rel = lib[root_index], (rel or "").strip("/")
        if not rel:
            r["profile"] = profile
        else:
            r.setdefault("profiles", {})[rel] = profile
        self.save_cfg()

    def _codec_warnings(self):
        out = []
        from mp_renderers import CAST_PROFILES, label
        for c in self.renderers.casts():
            prof = CAST_PROFILES.get(c.get("profile") or "")
            if prof and "hevc" not in prof["video"]:
                out.append(T(f"{c.get('name')} ({label(prof)}) can't play HEVC: it will need \"Prepare\" before watching",
                             f"{c.get('name')} ({label(prof)}) nie odtwarza HEVC: przed oglądaniem trzeba będzie użyć \"Przygotuj\""))
        return out

    def plan_shrink(self, root_index, rel="", encoder=None, modernize=False, m=None):
        """Re-encoding by storage profiles of one folder; `modernize`: only old picture formats (OLD_VIDEO), in every
        library folder except backups, whatever the folder's profile ("original" and lossless "tidy" → "good")."""
        if encoder is not None and encoder not in ENCODERS:
            raise TaskError(T("encoder must be auto, cpu or gpu", "koder to auto, cpu albo gpu"))

        def run(p):
            mp_media.detect_hevc()  # the choice needs to know the card (seconds, once)
            enc = self.resolve_encoder(encoder)
            choices = [e["id"] for e in self.encoders()]
            est_by = {e: 0 for e in choices}
            work_by = {e: 0.0 for e in choices}
            roots = self.library.roots()
            if modernize:
                bases = [(x, x["path"]) for x in roots if x["exists"] and not set(x["tags"]) & HIDDEN_TAGS]
            else:
                if not 0 <= root_index < len(roots):
                    raise TaskError(T("no such library folder", "nie ma takiego katalogu biblioteki"), 404)
                bases = [(roots[root_index], self.library.resolve(root_index, rel or ""))]
            torrents = self._torrents()
            files = []
            for r, base in bases:
                if os.path.isfile(base):
                    files.append((r, base))
                    continue
                for dp, dirs, fs in os.walk(base):
                    dirs[:] = sorted(d for d in dirs if not d.startswith("."))
                    files += [(r, os.path.join(dp, f)) for f in sorted(fs)
                              if os.path.splitext(f)[1].lower() in VIDEO_EXT and not f.startswith(".")]
            items, skipped = [], []
            unchecked, deadline = 0, time.time() + AUDIO_CHECK_BUDGET
            for k, (r, full) in enumerate(files):
                p["progress"] = int(100 * k / max(len(files), 1))
                p["step"] = os.path.basename(full)  # what is being looked at, under the preview's progress bar
                relf = os.path.relpath(os.path.realpath(full), os.path.realpath(r["path"]))
                prof = effective_profile(r, relf)
                if modernize and (prof == "original" or PROFILES[prof].get("copy")):
                    prof = "good"
                try:
                    size = os.stat(full).st_size
                except OSError:
                    continue  # gone meanwhile (a running task has just replaced it)
                item = {"op": "shrink", "root_id": r["id"], "root": r["index"], "root_name": r["name"],
                        "path": relf, "name": os.path.basename(full), "size": size, "profile": prof}
                if prof == "original" and not modernize:
                    continue
                if self._inside(full, torrents):
                    skipped.append(dict(item, reason=T("still seeding in the torrent client", "jeszcze seeduje w kliencie torrentów")))
                    continue
                if size < MIN_SIZE:
                    continue  # samples, extras
                try:
                    info = mp_media.probe(full)
                except Exception as e:  # noqa: BLE001
                    skipped.append(dict(item, reason=str(e)[:120]))
                    continue
                v, dur = info.get("video") or {}, info.get("duration") or 0
                old = is_old(v)
                if modernize and not old:
                    if not v or not dur or not any(a.get("codec") in AUDIO_SUSPECT for a in info.get("audio") or []):
                        continue
                    bad = self.audio_check(full, info, deadline)
                    if bad is None:
                        unchecked += 1
                    elif bad:
                        names = sorted({(a.get("codec") or "").upper() for a in info["audio"] if a["index"] in bad})
                        items.append(dict(item, est=size, codec=v.get("codec"), height=v.get("height"), to_height=0,
                                          duration=dur, bitrate=int(size * 8 / dur / 1000), audio_fix=True,
                                          broken=bad, audio_codecs=names))
                        for e in choices:
                            est_by[e] += size
                            work_by[e] = None if work_by[e] is None else work_by[e] + size / (40 << 20)
                    continue
                if not v or not dur:
                    skipped.append(dict(item, reason=T("no video stream or length", "brak obrazu albo długości")))
                    continue
                if old and PROFILES[prof].get("copy"):  # copying an old picture would keep it unplayable
                    prof = item["profile"] = "good"
                pr = PROFILES[prof]
                if pr.get("copy"):  # tidy: the size minus what the audio changes save
                    h = v.get("height")
                    before = sum(_audio_src_kbps(a) for a in info.get("audio") or [])
                    after = sum(_audio_kbps(a, pr["audio"]) for a in _audio_keep(info, pr["audio"]))
                    # bitrates are guesses (Matroska keeps none per stream): never promise more than half the file
                    est = int(max(size - max(before - after, 0) * dur * 1000 / 8, size * 0.5))
                else:
                    ph = pr.get("height") or v.get("height") or 1080  # "same": the file's own height
                    h = min(v.get("height") or ph, ph)
                    if v.get("codec") in ("hevc", "av1") and (v.get("height") or 0) <= ph:
                        skipped.append(dict(item, reason=T("already HEVC/AV1 at this size", "już HEVC/AV1 w tym rozmiarze")))
                        continue
                    vkbps = pr["kbps"] * (h / (pr.get("height") or 1080)) ** 1.5
                    akbps = sum(_audio_kbps(a, pr["audio"]) for a in _audio_keep(info, pr["audio"]))
                    g = ((mp_media.hevc_info() or {}).get("gpu") or {})
                    ests = {e: int(dur * (vkbps * (GPU_SIZE[g.get("bframes", False)] if e == "gpu" else 1) + akbps)
                                   * 1000 / 8 * 1.02) for e in choices}
                    est = ests[enc]
                if est > size * (OLD_GROWTH if old else 1 - pr.get("min_saving", MIN_SAVING)):
                    skipped.append(dict(item, reason=T("already small, little to gain", "już mały, niewiele do zyskania"),
                                        est=est))
                    continue
                items.append(dict(item, est=est, codec=v.get("codec"), height=v.get("height"), to_height=h,
                                  duration=dur, bitrate=int(size * 8 / dur / 1000), **({"old_format": True} if old else {})))
                for e in choices:
                    if pr.get("copy"):
                        est_by[e] += est
                        work_by[e] += size / (40 << 20)  # a copy of the picture: about disk speed
                    else:
                        est_by[e] += ests[e]
                        sp = self._speed(e)
                        work_by[e] = work_by[e] + dur * h * h * 16 / 9 / sp if sp and work_by[e] is not None else None
            saved = sum(i["size"] - i["est"] for i in items)
            hours = sum(i["duration"] for i in items) / 3600
            warnings = self._codec_warnings()
            if modernize:
                title = _title("Update old formats", "Unowocześnij stare formaty")
                if unchecked:
                    warnings.insert(0, T(f"{unchecked} files haven't been checked for damaged sound yet: the server checks "
                                         f"them in the background, a later preview will include them",
                                         f"{unchecked} plików nie sprawdzono jeszcze pod kątem uszkodzonego dźwięku: serwer "
                                         f"sprawdza je w tle, późniejszy podgląd je uwzględni"))
            else:
                r = bases[0][0]
                title = _title(f"Shrink {r['name']}" + (f"/{rel}" if rel else ""), f"Zmniejsz {r['name']}" + (f"/{rel}" if rel else ""))
            return {"kind": "shrink", **title, "modernize": modernize,
                    "items": items, "bytes": sum(i["size"] for i in items), "est": sum(i["est"] for i in items),
                    "saved": saved, "hours": round(hours, 1), "encoder": self.encoder_label(enc), "encoder_id": enc,
                    "encoders": [dict(e, est=est_by[e["id"]], saved=sum(i["size"] for i in items) - est_by[e["id"]],
                                      work_hours=None if work_by[e["id"]] is None else round(work_by[e["id"]] / 3600, 1))
                                 for e in self.encoders()],
                    "warnings": warnings, "skipped": skipped[:100], "audio_unchecked": unchecked,
                    "trash_days": self.trash_days()}
        return self._plan("shrink", run, m)
