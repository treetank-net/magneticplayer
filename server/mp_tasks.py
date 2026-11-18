"""Background file tasks (plan-library.md, stage 3 + storage profiles): moving between disks and shrinking videos.

Flow, always with a preview: a planner (`plan_free`, `plan_shrink`) proposes items without touching anything; the
owner confirms (all or some items); that becomes one task with progress and one notification at the end.
Tasks are persisted in `tasks.json`; after an agent restart an interrupted task continues with its remaining items
(an item half done is redone from scratch: copies go to `*.mp-part`, encodes to `.mp-shrink.part.mkv`).

Moving (`op: move`), an item is a top-level entry of a library folder (a movie folder, a series, a single file with
its subtitles):
- a torrent still in the client is moved by the client (`move_storage` / `torrent-set-location`), so it keeps
  seeding; a folder that merely contains torrents is skipped;
- the same filesystem: rename (instant);
- another disk: copy to `<name>.mp-part`, compare sizes, rename, delete the source.

Shrinking (`op: shrink`), storage profiles set per library folder or per subfolder/file (`profile`, `profiles`):
- `original` (default, always for `kopia`), `tidy` (lossless: the picture is copied, audio tracks in other languages
  dropped, DTS/TrueHD → E-AC3), `good` (HEVC ≤1080p), `compact` (HEVC ≤720p), `tiny` (HEVC ≤480p);
- only files outside the torrent client; HEVC/AV1 already at or below the target height and files whose estimate
  saves less than 15% are skipped;
- x265 on the CPU at nice 19 / idle I/O (or NVENC when `shrink_encoder` is "gpu"), paused while somebody streams;
- the result is checked (duration, a full decode, really smaller) and only then replaces the original; the original
  goes to `<library folder>/.mp-trash/` for `trash_days` days (default 14, 0 = delete right away).

Replication (decision 5): a library folder with `replicate_to` = another folder's id gets its top-level items copied
there quietly (every 30 minutes and after each task): new or changed items only, never deletions (a copy, not a
mirror), not while a torrent is unfinished, and the round stops when somebody starts streaming.
"""
import json
import os
import re
import secrets
import shutil
import signal
import stat
import subprocess
import sys
import threading
import time

import mp_media
from mp_i18n import T
import mp_library
from mp_library import HIDDEN_TAGS, SUB_EXT, VIDEO_EXT

PROFILES = {  # target height, x265 CRF, NVENC CQ, estimated video kb/s at that height, audio
    # lossless for the picture: only audio (other languages out, DTS/TrueHD/PCM → E-AC3/AAC), subtitles and chapters kept
    "tidy": {"copy": True, "audio": "lean", "min_saving": 0.03},
    "good": {"height": 1080, "crf": 24, "cq": 27, "kbps": 1800, "audio": "keep"},
    "compact": {"height": 720, "crf": 25, "cq": 28, "kbps": 900, "audio": "stereo"},
    "tiny": {"height": 480, "crf": 26, "cq": 29, "kbps": 450, "audio": "small"},
}
PROFILE_NAMES = ("original", *PROFILES)
LOSSLESS_AUDIO = {"truehd", "dts", "flac", "mlp"} | {f"pcm_{x}" for x in ("s16le", "s24le", "s32le", "s16be", "s24be")}
MIN_SAVING = 0.15
MIN_SIZE = 50 << 20  # smaller videos (samples, extras) aren't worth shrinking
TRASH = ".mp-trash"
KEEP = 50  # finished tasks kept in tasks.json


class TaskError(Exception):
    def __init__(self, msg, status=400):
        super().__init__(msg)
        self.status = status


def tree_size(path):
    """(bytes, files) of a file or a directory (os.stat, not DirEntry: see mp_library.browse about the GIL)."""
    try:
        st = os.stat(path)
    except OSError:
        return 0, 0
    if not stat.S_ISDIR(st.st_mode):
        return st.st_size, 1
    size = n = 0
    for dp, dirs, files in os.walk(path):
        dirs[:] = [d for d in dirs if not d.startswith(".mp-")]
        for f in files:
            try:
                size += os.stat(os.path.join(dp, f)).st_size
                n += 1
            except OSError:
                pass
    return size, n


def companions(full):
    """Files that belong to a single video in the same folder: `Name.srt`, `Name.en.srt`, `Name.nfo`, `Name.jpg`…"""
    d, base = os.path.split(full)
    stem = os.path.splitext(base)[0]
    try:
        names = os.listdir(d)
    except OSError:
        return []
    return sorted(n for n in names if n != base and n.startswith(stem + ".") and not os.path.isdir(os.path.join(d, n))
                  and os.path.splitext(n)[1].lower() not in VIDEO_EXT)


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


def _dev(path):
    try:
        return os.stat(path).st_dev
    except OSError:
        return None


class Tasks:
    def __init__(self, path, cfg, save_cfg, library, backend, streaming, renderers, log):
        """streaming(): True while somebody watches (encodes pause); backend(): the torrent client."""
        self.path, self.cfg, self.save_cfg, self.library = path, cfg, save_cfg, library
        self.backend, self.streaming, self.renderers, self.log = backend, streaming, renderers, log
        self.lock = threading.Lock()
        self.plans = {}  # id → {state, progress, result | error}; in memory, an hour
        self.proc = None
        self.replicating = False
        self.cancel_ev = threading.Event()
        self.wake = threading.Event()
        try:
            with open(path) as f:
                self.items = json.load(f)
        except (OSError, ValueError):
            self.items = []
        for t in self.items:  # interrupted by a restart: continue
            if t["state"] == "running":
                t["state"] = "queued"
        threading.Thread(target=self._loop, daemon=True).start()

    # ---- state

    def _save(self):
        tmp = self.path + ".tmp"
        with open(tmp, "w") as f:
            json.dump(self.items, f, ensure_ascii=False)
        os.replace(tmp, self.path)

    def public(self, t):
        return {k: v for k, v in t.items() if k != "items"} | {
            "items": [{k: v for k, v in i.items() if not k.startswith("_")} for i in t["items"]]}

    def list(self):
        with self.lock:
            return [self.public(t) for t in reversed(self.items)]

    def get(self, tid):
        with self.lock:
            t = next((t for t in self.items if t["id"] == tid), None)
            if not t:
                raise TaskError(T("no such task", "nie ma takiego zadania"), 404)
            return self.public(t)

    def busy(self):
        with self.lock:
            return self.replicating or any(t["state"] in ("queued", "running") for t in self.items)

    def cancel(self, tid):
        with self.lock:
            t = next((t for t in self.items if t["id"] == tid), None)
            if not t:
                raise TaskError(T("no such task", "nie ma takiego zadania"), 404)
            if t["state"] == "queued":
                t["state"] = "cancelled"
                t["finished"] = time.time()
            elif t["state"] == "running":
                t["cancel"] = True
                self.cancel_ev.set()
                if self.proc:
                    try:
                        self.proc.send_signal(signal.SIGCONT)
                        self.proc.terminate()
                    except OSError:
                        pass
            self._save()
            return self.public(t)

    def create(self, plan_id, pick=None):
        """A task from a finished plan; `pick` = indexes of the items to do (default: all)."""
        p = self.plans.get(plan_id)
        if not p or p["state"] != "done":
            raise TaskError(T("the preview has expired, make it again", "podgląd wygasł, zrób go jeszcze raz"), 404)
        res = p["result"]
        items = [dict(i) for n, i in enumerate(res["items"]) if (pick is None or n in pick) and not i.get("skip")]
        if not items:
            raise TaskError(T("nothing selected", "nic nie wybrano"))
        for i in items:
            i["state"] = "pending"
        t = {"id": secrets.token_hex(4), "kind": res["kind"], "title": res["title"], "state": "queued",
             "created": time.time(), "started": None, "finished": None, "items": items,
             "total": sum(i["size"] for i in items), "done_bytes": 0, "current": None, "pct": 0,
             "saved": 0, "errors": 0}
        with self.lock:
            self.items.append(t)
            done = [x for x in self.items if x["state"] not in ("queued", "running")]
            if len(done) > KEEP:
                drop = {x["id"] for x in done[:len(done) - KEEP]}
                self.items = [x for x in self.items if x["id"] not in drop]
            self._save()
        self.plans.pop(plan_id, None)
        self.wake.set()
        return self.public(t)

    # ---- library helpers

    def _roots(self):
        return {r["id"]: r for r in self.library.roots()}

    def _torrents(self):
        """content path (realpath) → torrent id, for every torrent in the client."""
        out = {}
        try:
            for t in self.backend().list():
                out[os.path.realpath(os.path.join(t["save_path"], t["name"]))] = t["id"]
        except Exception:  # client unavailable: treat nothing as a torrent would be wrong, so refuse to plan
            raise TaskError(T("the torrent client doesn't answer: can't tell which files are still seeding",
                              "klient torrentów nie odpowiada: nie wiadomo, co jeszcze się seeduje"), 502)
        return out

    @staticmethod
    def _inside(path, torrents):
        """'self' when `path` is a torrent's content, 'contains' when torrents are inside it, 'in' when it is inside one."""
        rp = os.path.realpath(path)
        if rp in torrents:
            return "self"
        if any(c.startswith(rp + os.sep) for c in torrents):
            return "contains"
        if any(rp.startswith(c + os.sep) for c in torrents):
            return "in"
        return None

    def _plan(self, kind, fn):
        pid = secrets.token_hex(4)
        p = {"id": pid, "kind": kind, "state": "running", "progress": 0, "created": time.time()}
        now = time.time()
        for k in [k for k, v in self.plans.items() if now - v["created"] > 3600]:
            del self.plans[k]
        self.plans[pid] = p

        def run():
            try:
                p["result"] = fn(p)
                p["state"] = "done"
            except Exception as e:  # noqa: BLE001 — reported to the app
                p.update(state="error", error=str(e)[:300])
                self.log("plan failed:", kind, e)
        threading.Thread(target=run, daemon=True).start()
        return dict(p)

    def plan_status(self, pid):
        p = self.plans.get(pid)
        if not p:
            raise TaskError(T("the preview has expired, make it again", "podgląd wygasł, zrób go jeszcze raz"), 404)
        return dict(p)

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
        disks = sorted(out.values(), key=lambda d: d["free"] / max(d["total"], 1))
        for d in disks:
            d["name"] = os.path.basename(d["mount"].rstrip("/")) or d["mount"]
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
            return {"kind": "move", "title": T(f"Free up {roots[root_index]['name']}", f"Zwolnij {roots[root_index]['name']}"),
                    "need": want, "free_before": u.free, "free_after": u.free + freed, "total": u.total,
                    "items": chosen, "bytes": freed, "skipped": skipped[:50]}
        return self._plan("move", run)

    def plan_move(self, items):
        """Explicit moves ({root, path, to_root, to_sub}), e.g. from an AI assistant over MCP; checked like plan_free."""
        def run(p):
            roots, torrents, out, skipped = self.library.roots(), self._torrents(), [], []
            for x in items:
                try:
                    r, d = roots[int(x["root"])], roots[int(x["to_root"])]
                    full = self.library.resolve(r["index"], x["path"])
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
            return {"kind": "move", "title": T(f"Move {len(out)} items", f"Przenieś {len(out)} pozycji"),
                    "items": out, "bytes": sum(i["size"] for i in out), "skipped": skipped}
        return self._plan("move", run)

    # ---- planner: tidying up (stage 4): recognition says the item belongs to a folder with another tag

    def plan_tidy(self, ident):
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
                for name in names:
                    if name.startswith("."):
                        continue
                    full = os.path.join(r["path"], name)
                    if os.path.isfile(full) and os.path.splitext(name)[1].lower() not in VIDEO_EXT:
                        continue
                    n += 1
                    p["progress"] = int(100 * k / max(len(roots), 1))
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
            return {"kind": "move", "title": T("Tidy up the library", "Porządki w bibliotece"), "checked": n,
                    "items": items, "bytes": sum(i["size"] for i in items), "skipped": skipped[:100]}
        return self._plan("tidy", run)

    # ---- planner: duplicates (stage 4): size, then the beginning and end of the file, then the whole file

    def plan_dupes(self):
        def run(p):
            import hashlib
            by_size = {}
            for r in self.library.roots():
                if not r["exists"] or set(r["tags"]) & HIDDEN_TAGS:  # `kopia` is a copy on purpose
                    continue
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
                        if sz >= 50 << 20:
                            by_size.setdefault(sz, []).append((r, fp))
            groups = [g for g in by_size.values() if len({os.path.realpath(fp) for _, fp in g}) > 1]

            def quick(fp, sz):
                h = hashlib.sha1()
                with open(fp, "rb") as f:
                    h.update(f.read(4 << 20))
                    f.seek(max(sz - (4 << 20), 0))
                    h.update(f.read(4 << 20))
                return h.hexdigest()

            def full(fp):
                h = hashlib.sha1()
                with open(fp, "rb") as f:
                    while chunk := f.read(8 << 20):
                        h.update(chunk)
                return h.hexdigest()
            out = []
            for k, g in enumerate(groups):
                p["progress"] = int(100 * k / max(len(groups), 1))
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
                                                       "path": os.path.relpath(fp, os.path.realpath(r["path"]))}
                                                      for r, fp in g]})
            out.sort(key=lambda g: -g["size"] * (len(g["files"]) - 1))
            return {"kind": "dupes", "title": T("Duplicates", "Duplikaty"), "groups": out, "items": [],
                    "wasted": sum(g["size"] * (len(g["files"]) - 1) for g in out)}
        return self._plan("dupes", run)

    def plan_trash(self, items):
        """Files the owner picked to delete (e.g. a duplicate): they go to the trash of their library folder."""
        def run(p):
            roots, torrents, out, skipped = self.library.roots(), self._torrents(), [], []
            for x in items:
                try:
                    r = roots[int(x["root"])]
                    full = self.library.resolve(r["index"], x["path"])
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
            return {"kind": "trash", "title": T(f"Delete {len(out)} items", f"Usuń {len(out)} pozycji"), "items": out,
                    "bytes": sum(i["size"] for i in out), "skipped": skipped, "trash_days": self.trash_days()}
        return self._plan("trash", run)

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

    def plan_shrink(self, root_index, rel=""):
        def run(p):
            roots = self.library.roots()
            if not 0 <= root_index < len(roots):
                raise TaskError(T("no such library folder", "nie ma takiego katalogu biblioteki"), 404)
            r = roots[root_index]
            base = self.library.resolve(root_index, rel or "")
            torrents = self._torrents()
            files = []
            if os.path.isfile(base):
                files = [base]
            else:
                for dp, dirs, fs in os.walk(base):
                    dirs[:] = sorted(d for d in dirs if not d.startswith("."))
                    files += [os.path.join(dp, f) for f in sorted(fs)
                              if os.path.splitext(f)[1].lower() in VIDEO_EXT and not f.startswith(".")]
            root_real = os.path.realpath(r["path"])
            items, skipped = [], []
            for k, full in enumerate(files):
                p["progress"] = int(100 * k / max(len(files), 1))
                relf = os.path.relpath(os.path.realpath(full), root_real)
                prof = effective_profile(r, relf)
                size = os.stat(full).st_size
                item = {"op": "shrink", "root_id": r["id"], "root": r["index"], "root_name": r["name"],
                        "path": relf, "name": os.path.basename(full), "size": size, "profile": prof}
                if prof == "original":
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
                if not v or not dur:
                    skipped.append(dict(item, reason=T("no video stream or length", "brak obrazu albo długości")))
                    continue
                pr = PROFILES[prof]
                if pr.get("copy"):  # tidy: the size minus what the audio changes save
                    h = v.get("height")
                    before = sum(_audio_src_kbps(a) for a in info.get("audio") or [])
                    after = sum(_audio_kbps(a, pr["audio"]) for a in _audio_keep(info, pr["audio"]))
                    # bitrates are guesses (Matroska keeps none per stream): never promise more than half the file
                    est = int(max(size - max(before - after, 0) * dur * 1000 / 8, size * 0.5))
                else:
                    h = min(v.get("height") or pr["height"], pr["height"])
                    if v.get("codec") in ("hevc", "av1") and (v.get("height") or 0) <= pr["height"]:
                        skipped.append(dict(item, reason=T("already HEVC/AV1 at this size", "już HEVC/AV1 w tym rozmiarze")))
                        continue
                    vkbps = pr["kbps"] * (h / pr["height"]) ** 1.5
                    akbps = sum(_audio_kbps(a, pr["audio"]) for a in _audio_keep(info, pr["audio"]))
                    est = int(dur * (vkbps + akbps) * 1000 / 8 * 1.02)
                if est > size * (1 - pr.get("min_saving", MIN_SAVING)):
                    skipped.append(dict(item, reason=T("already small, little to gain", "już mały, niewiele do zyskania"),
                                        est=est))
                    continue
                items.append(dict(item, est=est, codec=v.get("codec"), height=v.get("height"), to_height=h,
                                  duration=dur, bitrate=int(size * 8 / dur / 1000)))
            saved = sum(i["size"] - i["est"] for i in items)
            hours = sum(i["duration"] for i in items) / 3600
            return {"kind": "shrink", "title": T(f"Shrink {r['name']}" + (f"/{rel}" if rel else ""),
                                                 f"Zmniejsz {r['name']}" + (f"/{rel}" if rel else "")),
                    "items": items, "bytes": sum(i["size"] for i in items), "est": sum(i["est"] for i in items),
                    "saved": saved, "hours": round(hours, 1), "encoder": self._encoder_label(),
                    "warnings": self._codec_warnings(), "skipped": skipped[:100],
                    "trash_days": self.trash_days()}
        return self._plan("shrink", run)

    def trash_days(self):
        return int(self.cfg.get("trash_days", 14))

    def _encoder(self):
        return "gpu" if self.cfg.get("shrink_encoder") == "gpu" and "nvenc" in str(
            (mp_media.encoder_info() or {}).get("encoder")) else "cpu"

    def _encoder_label(self):
        return "NVIDIA NVENC (HEVC)" if self._encoder() == "gpu" else T("CPU (x265, in the background)", "CPU (x265, w tle)")

    # ---- worker

    def _loop(self):
        last_purge = last_repl = 0
        while True:
            self.wake.wait(60)
            self.wake.clear()
            if time.time() - last_repl > 1800:
                last_repl = time.time()
                try:
                    self.replicate()
                except Exception as e:  # noqa: BLE001
                    self.log("replication:", e)
            if time.time() - last_purge > 6 * 3600:
                last_purge = time.time()
                try:
                    self.purge_trash()
                except Exception as e:  # noqa: BLE001
                    self.log("trash purge:", e)
            while True:
                with self.lock:
                    t = next((t for t in self.items if t["state"] == "queued"), None)
                    if not t:
                        break
                    t.update(state="running", started=t["started"] or time.time())
                    self.cancel_ev.clear()
                    self._save()
                self._run(t)

    def _run(self, t):
        self.log(f"task {t['id']} ({t['kind']}): {t['title']}, {len(t['items'])} items")
        done_before = sum(i["size"] for i in t["items"] if i["state"] in ("done", "error", "skipped"))
        t["done_bytes"] = done_before
        for i in t["items"]:
            if i["state"] in ("done", "error", "skipped"):
                continue
            if t.get("cancel"):
                break
            t["current"] = i["name"]
            i["state"] = "running"
            with self.lock:
                self._save()
            base = t["done_bytes"]

            def progress(n, i=i, base=base):
                t["done_bytes"] = base + min(n, i["size"])
                t["pct"] = int(100 * t["done_bytes"] / max(t["total"], 1))
            try:
                if i["op"] == "move":
                    self._move(i, progress)
                elif i["op"] == "trash":
                    self._trash(i)
                else:
                    self._shrink(i, progress, t)
                    t["saved"] += i.get("saved", 0)
                i["state"] = "done" if i["state"] == "running" else i["state"]
            except _Cancelled:
                i["state"] = "pending"
                break
            except Exception as e:  # noqa: BLE001 — one item doesn't stop the task
                i.update(state="error", error=str(e)[:300])
                t["errors"] += 1
                self.log(f"task {t['id']}: {i['name']}: {e}")
            t["done_bytes"] = base + i["size"]
            t["pct"] = int(100 * t["done_bytes"] / max(t["total"], 1))
            with self.lock:
                self._save()
        with self.lock:
            t["current"] = None
            if t.get("cancel"):
                t["state"] = "cancelled"
            else:
                t["state"] = "done"
            t["finished"] = time.time()
            self._save()
        self.library.invalidate()
        try:
            self.replicate()
        except Exception as e:  # noqa: BLE001
            self.log("replication:", e)
        self.log(f"task {t['id']} {t['state']}: {sum(i['state'] == 'done' for i in t['items'])} done, {t['errors']} errors")

    def _check_cancel(self, t=None):
        if self.cancel_ev.is_set():
            raise _Cancelled()

    # ---- replication

    def replicate(self):
        roots = {r["id"]: r for r in self.library.roots()}
        try:
            unfinished = {os.path.realpath(os.path.join(t["save_path"], t["name"]))
                          for t in self.backend().list() if t.get("progress", 100) < 100}
        except Exception:  # noqa: BLE001 — no client: nothing is downloading as far as we can tell
            unfinished = set()
        for r in roots.values():
            to = roots.get(r.get("replicate_to") or "")
            if not to:
                mp_library.REPLICA.pop(r["id"], None)
                continue
            st = mp_library.REPLICA.setdefault(r["id"], {"pending": 0, "copied": 0, "last": None, "error": None})
            if not r["exists"] or not to["exists"]:
                st["error"] = T("a disk is disconnected", "dysk jest odłączony")
                continue
            todo = []
            for name in sorted(os.listdir(r["path"])):
                if name.startswith(".") or name.endswith(".mp-part"):
                    continue
                src, dst = os.path.join(r["path"], name), os.path.join(to["path"], name)
                if os.path.realpath(src) in unfinished:
                    continue
                if os.path.exists(dst) and tree_size(src) == tree_size(dst):
                    continue
                todo.append((src, dst))
            st.update(pending=len(todo), error=None)
            for src, dst in todo:
                if self.streaming() or any(t["state"] == "queued" for t in self.items):
                    return  # somebody watches, or the owner started a task: next round
                need = tree_size(src)[0]
                if shutil.disk_usage(to["path"]).free < need + (1 << 30):
                    st["error"] = T("not enough room for the copy", "za mało miejsca na kopię")
                    break
                part = dst + ".mp-part"
                with self.lock:
                    self.replicating = True
                try:
                    if os.path.exists(part):
                        (shutil.rmtree if os.path.isdir(part) else os.remove)(part)
                    _copy(src, part, lambda n: None)
                    if tree_size(src) != tree_size(part):
                        raise OSError(T("the copy differs from the source", "kopia różni się od źródła"))
                    if os.path.exists(dst):
                        (shutil.rmtree if os.path.isdir(dst) else os.remove)(dst)
                    os.rename(part, dst)
                    st["copied"] += 1
                    st["pending"] -= 1
                    st["last"] = time.time()
                except OSError as e:
                    st["error"] = str(e)[:200]
                    self.log("replication:", os.path.basename(src), e)
                    if os.path.exists(part):
                        (shutil.rmtree if os.path.isdir(part) else os.remove)(part)
                finally:
                    with self.lock:
                        self.replicating = False

    # ---- moving

    def _root_path(self, rid):
        r = self._roots().get(rid)
        if not r or not r["exists"]:
            raise TaskError(T("the library folder is gone or its disk is disconnected",
                              "katalogu biblioteki już nie ma albo dysk jest odłączony"))
        return r["path"]

    def _move(self, i, progress):
        src_root, dst_root = self._root_path(i["root_id"]), self._root_path(i["to_root_id"])
        src = os.path.join(src_root, i["path"])
        dst_dir = os.path.join(dst_root, i.get("to_sub") or "")
        dst = os.path.join(dst_dir, os.path.basename(i["path"]))
        if not os.path.exists(src):
            if os.path.exists(dst):
                return  # already moved before a restart
            raise TaskError(T("the source no longer exists", "źródła już nie ma"))
        os.makedirs(dst_dir, exist_ok=True)
        if i.get("torrent"):
            return self._move_torrent(i, src, dst_dir, dst, progress)
        if os.path.exists(dst):
            raise TaskError(T("the destination already exists", "w miejscu docelowym już coś jest"))
        pairs = [(src, dst)] + [(os.path.join(os.path.dirname(src), e), os.path.join(dst_dir, e)) for e in i.get("extra") or []]
        if _dev(src) == _dev(dst_dir):
            for a, b in pairs:
                if os.path.exists(a) and not os.path.exists(b):
                    os.rename(a, b)
            progress(i["size"])
            return
        copied = [0]

        def tick(n):
            copied[0] += n
            progress(copied[0])
            self._check_cancel()
        for a, b in pairs:
            if not os.path.exists(a):
                continue
            part = b + ".mp-part"
            if os.path.exists(part):
                (shutil.rmtree if os.path.isdir(part) else os.remove)(part)
            _copy(a, part, tick)
            if tree_size(a) != tree_size(part):
                raise TaskError(T("the copy differs from the source, the source was left alone",
                                  "kopia różni się od źródła, źródło zostało nietknięte"))
            os.rename(part, b)
        for a, _ in pairs:
            if os.path.isdir(a):
                shutil.rmtree(a)
            elif os.path.exists(a):
                os.remove(a)
        mp_media.remove_variant(src)

    def _move_torrent(self, i, src, dst_dir, dst, progress):
        be = self.backend()
        be.move(i["torrent"], dst_dir)
        deadline = time.time() + 12 * 3600
        while time.time() < deadline:
            time.sleep(5)
            self._check_cancel()
            progress(tree_size(dst)[0] if os.path.exists(dst) else 0)
            t = next((t for t in be.list() if t["id"] == i["torrent"]), None)
            if not t:
                raise TaskError(T("the torrent disappeared from the client while moving", "torrent zniknął z klienta w trakcie przenoszenia"))
            if os.path.realpath(t["save_path"]) == os.path.realpath(dst_dir) and not os.path.exists(src) \
                    and "moving" not in str(t.get("state", "")).lower():
                for e in i.get("extra") or []:  # files next to a single-file torrent that aren't part of it
                    a = os.path.join(os.path.dirname(src), e)
                    if os.path.exists(a):
                        shutil.move(a, os.path.join(dst_dir, e))
                progress(i["size"])
                return
        raise TaskError(T("the torrent client didn't finish moving in 12 hours", "klient torrentów nie skończył przenosić w 12 godzin"))

    def _trash(self, i):
        root = self._root_path(i["root_id"])
        full = os.path.join(root, i["path"])
        if not os.path.exists(full):
            return
        dest = os.path.join(root, TRASH, time.strftime("%Y-%m-%d"), os.path.dirname(i["path"]))
        os.makedirs(dest, exist_ok=True)
        for name in [os.path.basename(full)] + (i.get("extra") or []):
            a = os.path.join(os.path.dirname(full), name)
            if os.path.exists(a):
                if self.trash_days() > 0:
                    os.rename(a, os.path.join(dest, name))
                elif os.path.isdir(a):
                    shutil.rmtree(a)
                else:
                    os.remove(a)
        mp_media.remove_variant(full)

    # ---- shrinking

    def _shrink(self, i, progress, t):
        root = self._root_path(i["root_id"])
        full = os.path.join(root, i["path"])
        if not os.path.exists(full):
            raise TaskError(T("the file no longer exists", "pliku już nie ma"))
        st = os.stat(full)
        if st.st_size != i["size"]:
            raise TaskError(T("the file changed since the preview", "plik zmienił się od podglądu"))
        if self._inside(full, self._torrents()):
            raise TaskError(T("the file is back in the torrent client", "plik jest z powrotem w kliencie torrentów"))
        info = mp_media.probe(full)
        pr = PROFILES[i["profile"]]
        stem, ext = os.path.splitext(full)
        out_final = stem + ".mkv"
        part = os.path.join(os.path.dirname(full), f".{os.path.basename(stem)}.mp-shrink.part.mkv")
        cmd = self._shrink_cmd(full, part, info, pr)
        dur = info["duration"]
        self.log("shrink:", os.path.basename(full), i["profile"], self._encoder())
        p = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
                             start_new_session=True)
        with self.lock:
            self.proc = p
        err_tail = []
        threading.Thread(target=lambda: err_tail.extend(p.stderr.read().splitlines()[-5:]), daemon=True).start()
        paused = False
        try:
            for line in p.stdout:
                if line.startswith("out_time_us=") and dur:
                    try:
                        progress(int(i["size"] * min(int(line.split("=")[1]) / 1e6 / dur, 1)))
                    except ValueError:
                        pass
                want_pause = bool(self.streaming())
                if want_pause != paused and sys.platform != "win32":
                    p.send_signal(signal.SIGSTOP if want_pause else signal.SIGCONT)
                    paused = want_pause
                    t["paused"] = paused
                if self.cancel_ev.is_set():
                    raise _Cancelled()
            p.wait()
        except _Cancelled:
            p.send_signal(signal.SIGCONT)
            p.terminate()
            p.wait()
            _rm(part)
            raise
        finally:
            t["paused"] = False
            with self.lock:
                self.proc = None
        if self.cancel_ev.is_set():
            _rm(part)
            raise _Cancelled()
        if p.returncode != 0:
            _rm(part)
            raise TaskError(T("ffmpeg failed: ", "ffmpeg się nie udał: ") + " ".join(err_tail)[-200:])
        new = os.path.getsize(part)
        try:
            ninfo = mp_media.probe(part)
        except Exception as e:  # noqa: BLE001
            _rm(part)
            raise TaskError(T(f"the result can't be read: {e}", f"wyniku nie da się odczytać: {e}"))
        if abs((ninfo.get("duration") or 0) - dur) > max(2.0, dur * 0.005):
            _rm(part)
            raise TaskError(T("the result has a different length, the original was kept", "wynik ma inną długość, oryginał został"))
        if new > st.st_size * (1 - pr.get("min_saving", MIN_SAVING) / 2):
            _rm(part)
            i["state"] = "skipped"
            i["error"] = T("the result wasn't much smaller, the original was kept", "wynik nie był wyraźnie mniejszy, oryginał został")
            return
        dec = subprocess.run(_nice(["ffmpeg", "-hide_banner", "-nostdin", "-v", "error", "-i", part, "-map", "0:v:0",
                                    "-f", "null", "-"]), capture_output=True, text=True)
        if dec.returncode != 0 or dec.stderr.strip():
            _rm(part)
            raise TaskError(T("the result doesn't decode cleanly, the original was kept", "wynik nie dekoduje się czysto, oryginał został"))
        # swap: the original to the trash (same disk: a rename), the result in its place
        trash = os.path.join(root, TRASH, time.strftime("%Y-%m-%d"), os.path.dirname(i["path"]))
        os.makedirs(trash, exist_ok=True)
        if self.trash_days() > 0:
            os.rename(full, os.path.join(trash, os.path.basename(full)))
        else:
            os.remove(full)
        os.rename(part, out_final)
        mp_media.remove_variant(full)
        i.update(saved=st.st_size - new, new_size=new, new_path=os.path.relpath(out_final, root))
        self.log(f"shrink done: {os.path.basename(full)} {st.st_size >> 20} → {new >> 20} MB")

    def _shrink_cmd(self, full, out, info, pr):
        v = info["video"]
        h = min(v.get("height") or pr.get("height", 0), pr.get("height", 1 << 16))
        vf = f"scale=-2:{h}" if (v.get("height") or 0) > h else "null"
        cmd = ["ffmpeg", "-hide_banner", "-nostdin", "-y", "-loglevel", "error", "-i", full, "-map", "0:v:0"]
        keep = _audio_keep(info, pr["audio"])
        for a in keep:
            cmd += ["-map", f"0:a:{a['index']}"]
        cmd += ["-map", "0:s?", "-map_chapters", "0"]
        if pr.get("copy"):
            cmd += ["-c:v", "copy"]
        elif self._encoder() == "gpu":
            cmd += ["-vf", f"{vf},format=yuv420p", "-c:v", "hevc_nvenc", "-preset", "p6", "-rc", "vbr", "-cq", str(pr["cq"]),
                    "-b:v", "0"]
        else:
            cmd += ["-vf", f"{vf},format=yuv420p", "-c:v", "libx265", "-preset", self.cfg.get("shrink_preset", "fast"),
                    "-crf", str(pr["crf"]), "-x265-params", "log-level=error"]
        if not pr.get("copy"):
            cmd += ["-tag:v", "hvc1"]
        for n, a in enumerate(keep):
            how = _audio_codec(a, pr["audio"])
            cmd += [f"-c:a:{n}", *how]
        cmd += ["-c:s", "copy", "-max_muxing_queue_size", "4096", "-progress", "pipe:1", "-nostats", "-f", "matroska", out]
        return _nice(cmd)

    # ---- trash

    def trash(self):
        out = []
        for r in self.library.roots():
            d = os.path.join(r["path"], TRASH)
            if r["exists"] and os.path.isdir(d):
                for day in sorted(os.listdir(d)):
                    size, n = tree_size(os.path.join(d, day))
                    out.append({"root": r["index"], "root_name": r["name"], "day": day, "size": size, "files": n})
        return out

    def purge_trash(self, everything=False):
        days, now, freed = self.trash_days(), time.time(), 0
        for r in self.library.roots():
            d = os.path.join(r["path"], TRASH)
            if not r["exists"] or not os.path.isdir(d):
                continue
            for day in os.listdir(d):
                try:
                    age = (now - time.mktime(time.strptime(day, "%Y-%m-%d"))) / 86400
                except ValueError:
                    continue
                if everything or age > days:
                    freed += tree_size(os.path.join(d, day))[0]
                    shutil.rmtree(os.path.join(d, day), ignore_errors=True)
            try:
                os.rmdir(d)
            except OSError:
                pass
        if freed:
            self.log(f"trash: freed {freed >> 20} MB")
        return freed


class _Cancelled(Exception):
    pass


def _mount(path):
    p = os.path.realpath(path)
    while not os.path.ismount(p):
        p = os.path.dirname(p)
    return p


def _nice(cmd):
    if sys.platform != "win32":
        if shutil.which("ionice"):
            cmd = ["ionice", "-c", "3", *cmd]
        if shutil.which("nice"):
            cmd = ["nice", "-n", "19", *cmd]
    return cmd


def _rm(path):
    try:
        os.remove(path)
    except OSError:
        pass


def _copy(src, dst, tick):
    """Copies a file or a tree, reporting bytes; metadata where the filesystem allows it (ntfs-3g refuses some)."""
    if os.path.isdir(src):
        os.makedirs(dst)
        for name in sorted(os.listdir(src)):
            if name.startswith(".mp-cast"):
                continue  # "for Chromecast" versions: regenerated on demand
            _copy(os.path.join(src, name), os.path.join(dst, name), tick)
        return
    with open(src, "rb") as a, open(dst, "wb") as b:
        while chunk := a.read(8 << 20):
            b.write(chunk)
            tick(len(chunk))
        b.flush()
        os.fsync(b.fileno())
    try:
        shutil.copystat(src, dst)
    except OSError:
        pass


def _audio_keep(info, mode):
    """Audio tracks to keep: all for `keep`; for smaller profiles Polish, English, undefined and the default one."""
    audio = info.get("audio") or []
    if mode == "keep" or len(audio) <= 1:  # "lean" (tidy) filters languages like the smaller profiles
        return audio
    keep = [a for a in audio if (a.get("lang") or "und") in ("pol", "pl", "eng", "en", "und") or a.get("default")]
    return keep or audio[:1]


def _audio_codec(a, mode):
    c, ch = a.get("codec") or "", a.get("channels") or 2
    if mode in ("keep", "lean"):
        if c in LOSSLESS_AUDIO or c.startswith("pcm_"):
            return ["eac3", "-b:a", "640k"] if ch > 2 else ["aac", "-b:a", "192k"]
        return ["copy"]
    if mode == "stereo":
        return ["copy"] if c in ("aac", "opus") and ch <= 2 else ["aac", "-b:a", "160k", "-ac", "2"]
    return ["aac", "-b:a", "96k", "-ac", "2"]


def _audio_src_kbps(a):
    """A guess of a source track's bitrate (Matroska rarely stores it per stream)."""
    c, ch = a.get("codec") or "", a.get("channels") or 2
    if c.startswith("pcm_"):
        return 768 * ch
    return {"truehd": 3500, "mlp": 3500, "dts": 1509 if ch > 2 else 768, "flac": 700 * max(ch // 2, 1),
            "ac3": 448 if ch > 2 else 192, "eac3": 640 if ch > 2 else 192}.get(c, 160 if ch <= 2 else 384)


def _audio_kbps(a, mode):
    c, ch = a.get("codec") or "", a.get("channels") or 2
    how = _audio_codec(a, mode)
    if how[0] != "copy":
        return int(how[2].rstrip("k"))
    return _audio_src_kbps(a)
