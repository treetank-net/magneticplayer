"""Background file tasks (plan-library.md, stage 3 + storage profiles): moving between disks and shrinking videos.

Flow, always with a preview: a planner (`plan_free`, `plan_shrink`) proposes items without touching anything; the
owner confirms (all or some items); that becomes one task with progress and one notification at the end.
Tasks are persisted in `tasks.json`; after an agent restart an interrupted task continues with its remaining items.
An encode runs detached from the agent (mp_detached: its own systemd scope / a detached process on Windows), so it
goes on through a restart or a self-update and the new agent attaches to it again (`job` in the item); a half-done
copy is redone from scratch (`*.mp-part`), and so is an encode whose keeper died (a reboot).

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
- the encoder is chosen per task (`encoder`: `cpu` = x265 at nice 19, `gpu` = the graphics card's HEVC encoder from
  `mp_media.detect_hevc`; `auto`, the default `shrink_encoder`, takes the card only when it can do B-frames);
- while somebody watches (`shrink_while_watching`, default `continue`): an encode on another disk goes on at full speed,
  one on the disk being read runs a third of the time if it reads more than 8 MB/s itself (a graphics card; x265
  reads far less and runs on), a conversion for a TV pauses it, and a film that is being
  watched (or probed by a phone that plays its own copy) is swapped only once nobody has touched it for a minute;
  `pause` = the old behaviour, any stream pauses the encode;
- the result is checked (duration, a full decode, really smaller) and only then replaces the original; the original
  goes to `<library folder>/.mp-trash/` for `trash_days` days (default 14, 0 = delete right away); a disk under its
  reserve sends it (and, before an encode, its older trash days) to the trash of another disk with room (1.14.2).

Replication (decision 5): a library folder with `replicate_to` = another folder's id gets its top-level items copied
there quietly (every 30 minutes and after each task): new or changed items only, never deletions (a copy, not a
mirror), not while a torrent is unfinished, and the round stops when somebody starts streaming.
"""
import collections
import json
import os
import re
import secrets
import signal
import subprocess
import threading
import time

import mp_arrange
import mp_detached
import mp_i18n
import mp_library
from mp_i18n import T
from mp_tasks_common import SUBS_PER_DAY, TaskError, _Cancelled, _Held, _dev, _mount, _no_space, _rm, _title
from mp_tasks_plan import PlanMixin
from mp_tasks_jobs import JobsMixin
from mp_tasks_layout import LayoutMixin
from mp_tasks_facts import FactsMixin
from mp_tasks_ops import OpsMixin
from mp_tasks_shrink import ShrinkMixin
from mp_tasks_reencode import ReencodeMixin
from mp_tasks_common import (  # noqa: F401 (re-exported)
    AUDIO_CHECK_BUDGET, AUDIO_SUSPECT, ENCODERS, GPU_SIZE, LOSSLESS_AUDIO, MIN_SAVING, MIN_SIZE, MODERN, OLD_GROWTH,
    OLD_VIDEO, PLAYABLE_AUDIO, PROFILES, RESERVE, WATCHING_DAYS, companions, is_old, tree_size
)
from mp_tasks_plan import DATA_FS, FILM_TAGS, PROFILE_NAMES, effective_profile  # noqa: F401 (re-exported)
from mp_tasks_jobs import AAC_QUALITIES, AUDIO_MODES, HIGH_BITRATE, QUALITIES, TARGETS  # noqa: F401 (re-exported)
from mp_tasks_layout import CAST_SPEED, TEXT_SUBS  # noqa: F401 (re-exported)
from mp_tasks_facts import FACTS_RESCAN, FACTS_V  # noqa: F401 (re-exported)
from mp_tasks_ops import TRASH, free_name  # noqa: F401 (re-exported)
from mp_tasks_shrink import AUDIO_FIX, SAME_DISK_DUTY, SAME_DISK_FREE, WATCH_MODES  # noqa: F401 (re-exported)


def _stopped(t):
    """A cancelled task's items that never ran: "skipped" (the app showed them "waiting" for ever)."""
    for i in t.get("items") or []:
        if i.get("state") in (None, "pending", "running"):
            i["state"] = "skipped"
            i["note"] = T("not done: the task was stopped", "niezrobione: zadanie zatrzymano")


KEEP = 50  # finished tasks kept in tasks.json


def _space_error(err):
    e = (err or "").lower()
    return any(x in e for x in ("no space left", "brak miejsca", "not enough room", "za mało miejsca"))


def _reason(err):
    """An item's error as a grouping key: older tasks kept ffmpeg's raw tail (cut mid-word, with temp paths)."""
    if "No space left on device" in err or "no space left" in err.lower() or "brak miejsca" in err:
        return _no_space() if "not enough room" not in err and "za mało miejsca" not in err else re.sub(r"\d+ MB", "… MB", err)
    return re.sub(r"\d+ MB", "… MB", err)


def _scope_filter(res, m, roots):
    """Drops a plan's items, skipped entries and duplicate files outside the scope (the old planners walk the whole
    library), and recounts the totals."""
    by_id = {r["id"]: r for r in roots}
    by_index = {r["index"]: r for r in roots}

    def inside(x):
        r = by_id.get(x.get("root_id")) or by_index.get(x.get("root"))
        return bool(r) and x.get("path") is not None and m.match(r, x["path"])
    if isinstance(res.get("items"), list):
        res["items"] = [i for i in res["items"] if inside(i)]
        res["bytes"] = sum(i.get("size", 0) for i in res["items"]) if res.get("kind") != "subs_get" else 0
        if "est" in res and all("est" in i for i in res["items"]):
            res["est"] = sum(i["est"] for i in res["items"])
            res["saved"] = res["bytes"] - res["est"]
    if isinstance(res.get("skipped"), list):
        res["skipped"] = [i for i in res["skipped"] if inside(i)]
    if isinstance(res.get("groups"), list) and not res.get("what"):  # the new duplicates plan only looks in the scope
        groups = []
        for g in res["groups"]:
            files = [x for x in g.get("files") or [] if inside(x)]
            if len(files) > 1:
                groups.append(dict(g, files=files))
        res["groups"] = groups
        if "wasted" in res and groups and "size" in groups[0]:
            res["wasted"] = sum(g["size"] * (len(g["files"]) - 1) for g in groups)
        elif "wasted" in res:
            res["wasted"] = 0


class Tasks(PlanMixin, JobsMixin, LayoutMixin, FactsMixin, OpsMixin, ShrinkMixin, ReencodeMixin):
    def __init__(self, path, cfg, save_cfg, library, backend, watching, renderers, log):
        """watching(): files in use now, [{"path", "kind"}] with kind stream (a GET from the library or a torrent),
        probe (HEAD only: a phone playing its own copy), mpv (the computer's screen) or convert (a version for a TV);
        backend(): the torrent client."""
        self.path, self.cfg, self.save_cfg, self.library = path, cfg, save_cfg, library
        self.encodes = os.path.join(os.path.dirname(path), "encodes")  # detached encodes (mp_detached)
        self.moves_path = os.path.join(os.path.dirname(path), "moves.json")  # journal of moved items (phones re-key)
        # audio decode checks: real path → [size, mtime, [damaged track indexes]]
        self.achecks_path = os.path.join(os.path.dirname(path), "audio-checks.json")
        try:
            with open(self.achecks_path) as f:
                self.achecks = json.load(f)
        except (OSError, ValueError):
            self.achecks = {}
        self.alock = threading.Lock()
        self.backend, self.watching, self.renderers, self.log = backend, watching, renderers, log
        self.lock = threading.Lock()
        self.plans = {}  # id → {state, progress, result | error}; in memory, an hour
        self.fetch_subs = None  # (full, lang, prefer_provider) → download result or None; set by the agent
        self.subs_ok = None  # () → whether subtitle search works here; set by the agent
        self.catalog = None  # the agent's mp_catalog.Catalog: scopes by title, tag and age group (1.16+)
        self.subsources = None  # mp_subfiles.Sources: subtitles that don't fit are set aside with it (1.16+)
        self.subsync = None
        # what the background scan knows about every library video (1.16+): the counters never probe in a request
        self.facts_path = os.path.join(os.path.dirname(path), "job-facts.json")
        self.flock = threading.Lock()
        self.facts_wake = threading.Event()
        self._load_facts()
        mp_library.ON_CHANGE.append(self.facts_wake.set)
        # videos streamed from this server: real path → last time (free up "watched", subtitles "being watched")
        self.played_path = os.path.join(os.path.dirname(path), "played.json")
        try:
            with open(self.played_path) as f:
                self.played = json.load(f)
        except (OSError, ValueError):
            self.played = {}
        self._played_saved = 0
        self._tcache = (0, None)
        self._trash_cache = (0, None)
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
            t.pop("hold", None)
            t["paused"] = False
            if t["state"] == "running":
                t["state"] = "queued"
        # encodes nobody owns any more (a task cancelled or finished while the agent was down): stop and forget them
        owned = {i.get("job") for t in self.items for i in t.get("items", []) if t["state"] == "queued"}

        def sweep():
            for j in mp_detached.Detached.all(self.encodes, "shrink"):
                if j.id not in owned:
                    self.log("shrink: stopping an encode no task owns:", j.id)
                    j.terminate()
                    self._drop_part(j)
                    j.remove()
        threading.Thread(target=sweep, daemon=True).start()
        if any(t["state"] == "queued" for t in self.items):
            self.wake.set()  # an interrupted task goes on right away (its encode may have been running all along)
        threading.Thread(target=self._loop, daemon=True).start()
        threading.Thread(target=self._audio_scan, daemon=True).start()
        threading.Thread(target=self._facts_loop, daemon=True).start()

    # ---- state

    def _save(self):
        tmp = self.path + ".tmp"
        with open(tmp, "w") as f:
            json.dump(self.items, f, ensure_ascii=False)
        os.replace(tmp, self.path)

    def streaming(self):
        """Somebody really reads a video now (not just a phone probing its own copy)."""
        return any(w["kind"] != "probe" for w in self.watching())

    def public(self, t, brief=False):
        """A task for the API; `brief` leaves the items out (only their counts): a finished task with hundreds of
        files is ~400 kB, and phones read the list often."""
        out = {k: v for k, v in t.items() if k not in ("items", "hold", "enc", "titles", "scope_labels", "bad_subs", "_run",
                                                     "held_at", "held_secs")} | {
            "item_count": len(t["items"]), "items_done": sum(1 for i in t["items"] if i.get("state") == "done")}
        # the same reason once with a count (a full disk once listed 457 identical errors, one per file)
        errs = [i for i in t["items"] if i.get("state") == "error" and i.get("error")]
        reasons = collections.Counter(_reason(i["error"]) for i in errs)
        if reasons:
            out["error_groups"] = []
            for e, n in reasons.most_common(5):
                g = {"error": e, "count": n}
                if _space_error(e) and t["state"] in ("done", "cancelled"):
                    # agent 1.17+: the fix as a link on the task screen ("Empty the trash and retry")
                    first = next((i for i in errs if _reason(i["error"]) == e), None)
                    root = self._roots().get((first or {}).get("root_id")) or {}
                    g.update(fix="empty_trash_retry", trash=self._trash_size(),
                             disk=os.path.basename(_mount(root["path"]).rstrip("/")) if root.get("path") else None)
                out["error_groups"].append(g)
        # agent 1.17+ (F4): paused by a person, the place in the queue, a quick task, the time left / of work
        out["held"] = bool(t.get("held"))
        out["paused"] = bool(t.get("paused") or t.get("held"))
        out["quick"] = bool(t.get("quick"))
        if t["state"] == "queued":
            q = [x for x in self.items if x["state"] == "queued"]
            out["queue"] = next((n + 1 for n, x in enumerate(q) if x is t), None)
        out["eta"] = self._eta(t)
        out["estimate"] = (T("Quick: starts in a break between files", "Szybkie: ruszy w przerwie między plikami")
                           if t.get("quick") and t["state"] == "queued" else
                           T(f"About {SUBS_PER_DAY} a day (OpenSubtitles limit)", f"Ok. {SUBS_PER_DAY} dziennie (limit OpenSubtitles)")
                           if t["kind"] == "subs_get" and t["state"] == "queued" else None)
        if not brief:
            out["items"] = [{k: v for k, v in i.items() if not k.startswith("_")} for i in t["items"]]
        if t.get("titles"):
            out["title"] = t["titles"].get(mp_i18n.lang()) or t["title"]
        out["scope_label"] = (t.get("scope_labels") or {}).get(mp_i18n.lang())  # 1.16+, null for the whole computer
        if t.get("enc"):
            out["encoder"] = self.encoder_label(t["enc"])
        if t.get("hold"):
            out["note"] = self.hold_text(t["hold"])
        elif t.get("held") and t["state"] in ("queued", "running"):
            out["note"] = self.hold_text("held")
        return out

    def _eta(self, t):
        """Seconds left of a running task (from its pace in this run, without paused time), or of work for a queued
        one (the plan's estimates; copies between disks by COPY_SPEED); None when unknown."""
        now = time.time()
        if t["state"] == "running" and t.get("_run"):
            t0, b0, h0 = t["_run"]
            el = now - t0 - (t.get("held_secs", 0) - h0) - ((now - t["held_at"]) if t.get("held_at") else 0)
            done = t["done_bytes"] - b0
            if el > 30 and done > 0:
                return int(el / done * max(t["total"] - t["done_bytes"], 0))
            return None
        if t["state"] != "queued":
            return None
        work = 0.0
        for i in t["items"]:
            if i.get("state") not in (None, "pending", "running"):
                continue
            if i.get("work") is not None:
                work += i["work"]
            elif i["op"] == "move":
                work += 1 if i.get("same_disk") else i["size"] / mp_arrange.COPY_SPEED
            elif i["op"] == "trash":
                work += 0.2
            elif i["op"] == "subs":
                work += 30
            elif i["op"] == "subs_get":
                work += 60
            else:
                return None
        return int(work)

    @staticmethod
    def hold_text(hold):
        return {"watching": T("Paused, somebody is watching", "Wstrzymane, ktoś ogląda"),
                "conversion": T("Paused while a version for a TV is being prepared",
                                "Wstrzymane, trwa przygotowanie wersji dla telewizora"),
                "same_disk": T("Slower: somebody is watching from the same disk", "Wolniej: ktoś ogląda z tego samego dysku"),
                "in_use": T("Waiting to swap: this film is being watched", "Czeka z podmianą: ten film jest właśnie oglądany"),
                "held": T("Paused", "Wstrzymane"),
                }.get(hold, "")

    def list(self, brief=False):
        with self.lock:
            return [self.public(t, brief) for t in reversed(self.items)]

    def last_finished(self):
        """When the latest task finished (epoch s, 0 = none): in /api/ping, so phones read the list only after it changes."""
        with self.lock:
            return max((t.get("finished") or 0 for t in self.items), default=0)

    def get(self, tid):
        with self.lock:
            t = next((t for t in self.items if t["id"] == tid), None)
            if not t:
                raise TaskError(T("no such task", "nie ma takiego zadania"), 404)
            return self.public(t)

    def busy(self):
        """Whether a restart now would lose work: a copy or a move under way, or replication. An encode runs detached
        and the new agent attaches to it, and a queued task simply starts after the restart."""
        with self.lock:
            if self.replicating:
                return True
            for t in self.items:
                if t["state"] != "running":
                    continue
                cur = next((i for i in t["items"] if i["state"] == "running"), None)
                if cur is None or cur["op"] != "shrink" or not cur.get("job"):
                    return True
            return False

    def _drop_part(self, j):
        part = (j.meta or {}).get("part")
        if part and part.endswith((".mp-shrink.part.mkv", ".mp-shrink.part.mp4")):  # .mp4: re-encode (1.18+)
            try:
                j.wait(30)
            except subprocess.TimeoutExpired:
                pass
            _rm(part)

    def remove(self, tid):
        """A finished or cancelled task off the list (agent 1.12.1+); a running one has to be cancelled first. Agent
        1.17+: a waiting task is taken off the queue (cancelled and removed at once, C4's ✕)."""
        with self.lock:
            t = next((t for t in self.items if t["id"] == tid), None)
            if not t:
                raise TaskError(T("no such task", "nie ma takiego zadania"), 404)
            if t["state"] == "running":
                raise TaskError(T("cancel the task first", "najpierw anuluj zadanie"), 409)
            if t["state"] == "queued":
                self._drop_queued(t)
            self.items.remove(t)
            self._save()
            return {"ok": True}

    def _find(self, tid):
        t = next((t for t in self.items if t["id"] == tid), None)
        if not t:
            raise TaskError(T("no such task", "nie ma takiego zadania"), 404)
        return t

    def _drop_queued(self, t):
        """A waiting task cancelled (under the lock): its items skipped, and an encode it paused (F4) stopped for good."""
        t["state"] = "cancelled"
        t["finished"] = time.time()
        jobs = [{"job": i.pop("job")} for i in t["items"] if i.get("job")]
        _stopped(t)

        def stop():
            for x in jobs:
                self._drop_job(x)
        if jobs:
            threading.Thread(target=stop, daemon=True).start()

    # ---- pause, resume and queue order (agent 1.17+, docs/plan-ux-tasks.md F4)

    def pause(self, tid):
        """Pauses a task: a waiting one keeps its place and isn't started; a running one stops at the next file, or its
        encode is suspended where it is (it then waits in the queue and the next task runs). Kept across restarts."""
        with self.lock:
            t = self._find(tid)
            if t["state"] not in ("queued", "running"):
                raise TaskError(T("only a waiting or running task can be paused", "wstrzymać można tylko zadanie czekające albo trwające"), 409)
            if not t.get("held"):
                t["held"], t["held_at"] = True, time.time()
                self._save()
            return self.public(t)

    def resume(self, tid):
        with self.lock:
            t = self._find(tid)
            if t.get("held"):
                t["held"] = False
                t["held_secs"] = t.get("held_secs", 0) + time.time() - (t.pop("held_at", None) or time.time())
                self._save()
            out = self.public(t)
        self.wake.set()
        return out

    def move(self, tid, to="up"):
        """A waiting task one place up or down the queue, or to its top (`to`: up, down, top). The running task stays."""
        if to not in ("up", "down", "top"):
            raise TaskError(T("to must be up, down or top", "to to up, down albo top"))
        with self.lock:
            t = self._find(tid)
            if t["state"] != "queued":
                raise TaskError(T("only a waiting task can be moved", "przesunąć można tylko zadanie czekające"), 409)
            q = [x for x in self.items if x["state"] == "queued"]
            k = q.index(t)
            if to == "top" and k > 0:
                self.items.remove(t)
                self.items.insert(self.items.index(q[0]), t)
            elif (to == "up" and k > 0) or (to == "down" and k < len(q) - 1):
                o = q[k - 1] if to == "up" else q[k + 1]
                a, b = self.items.index(t), self.items.index(o)
                self.items[a], self.items[b] = o, t
            self._save()
            return [self.public(x, True) for x in self.items if x["state"] == "queued"]

    def order(self, ids):
        """The waiting tasks in this order (`ids` first, the rest after them as they were)."""
        with self.lock:
            q = [x for x in self.items if x["state"] == "queued"]
            pos = [n for n, x in enumerate(self.items) if x["state"] == "queued"]
            by = {x["id"]: x for x in q}
            new = [by[i] for i in dict.fromkeys(ids or []) if i in by]
            new += [x for x in q if x not in new]
            for n, x in zip(pos, new):
                self.items[n] = x
            self._save()
            return [self.public(x, True) for x in self.items if x["state"] == "queued"]

    def retry(self, tid):
        """A new task with the items of a finished task that failed (after the trash was emptied, for example)."""
        with self.lock:
            t = self._find(tid)
            if t["state"] not in ("done", "cancelled"):
                raise TaskError(T("the task hasn't finished yet", "zadanie jeszcze się nie skończyło"), 409)
            drop = ("state", "error", "job", "saved", "new_size", "new_path", "note", "result", "trash_on")
            items = [{k: v for k, v in i.items() if k not in drop} for i in t["items"] if i.get("state") == "error"]
            res = {"kind": t["kind"], "title": t["title"], "titles": t.get("titles"), "encoder_id": t.get("enc"),
                   "scope_labels": t.get("scope_labels")}
        if not items:
            raise TaskError(T("nothing failed in this task", "w tym zadaniu żaden plik nie skończył się błędem"))
        return self.public(self._add_task(res, items))

    def cancel(self, tid):
        with self.lock:
            t = next((t for t in self.items if t["id"] == tid), None)
            if not t:
                raise TaskError(T("no such task", "nie ma takiego zadania"), 404)
            if t["state"] == "queued":
                self._drop_queued(t)
            elif t["state"] == "running":
                t["cancel"] = True
                self.cancel_ev.set()
                if self.proc:
                    try:
                        if hasattr(signal, "SIGCONT"):  # a paused encode must wake up to die; not on Windows
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
        t = self._add_task(res, items)
        self.plans.pop(plan_id, None)
        return self.public(t)

    def _add_task(self, res, items):
        """Queues a task with `items` (a plan's result, or one made by the agent itself for new files)."""
        roots = self._roots()
        for i in items:
            i["state"] = "pending"
            if i.get("op") == "move" and "same_disk" not in i:
                src = roots.get(i.get("root_id"), {}).get("path")
                dst = roots.get(i.get("to_root_id"), {}).get("path") if i.get("to_root_id") else src
                i["same_disk"] = bool(src and dst and _dev(src) is not None and _dev(src) == _dev(dst))
        # quick (agent 1.17+): only the trash, or moves within a disk outside torrents; runs between another task's files
        quick = all(i.get("op") == "trash" or (i.get("op") == "move" and i.get("same_disk") and not i.get("torrent"))
                    for i in items)
        t = {"id": secrets.token_hex(4), "kind": res["kind"], "title": res["title"], "titles": res.get("titles"), "state": "queued",
             "created": time.time(), "started": None, "finished": None, "items": items,
             "total": sum(i["size"] for i in items), "done_bytes": 0, "current": None, "pct": 0,
             "saved": 0, "errors": 0, "lang": mp_i18n.lang(), **({"quick": True} if quick else {}),
             **({"enc": res["encoder_id"]} if res.get("encoder_id") else {}),
             **({"scope_labels": res["scope_labels"]} if res.get("scope_labels") else {})}
        with self.lock:
            self.items.append(t)
            done = [x for x in self.items if x["state"] not in ("queued", "running")]
            if len(done) > KEEP:
                drop = {x["id"] for x in done[:len(done) - KEEP]}
                self.items = [x for x in self.items if x["id"] not in drop]
            self._save()
        self.wake.set()
        return t

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

    def _plan(self, kind, fn, m=None):
        """Runs planner `fn` in the background. With a scope matcher `m` (1.16+) the result carries `scope_label` (and
        both languages for the task), and items, skipped entries and duplicate groups outside the scope are dropped
        (the old planners walk everything; the new ones only the scope)."""
        pid = secrets.token_hex(4)
        p = {"id": pid, "kind": kind, "state": "running", "progress": 0, "created": time.time()}
        now = time.time()
        for k in [k for k, v in self.plans.items() if now - v["created"] > 3600]:
            del self.plans[k]
        self.plans[pid] = p

        def run():
            try:
                res = fn(p)
                if m is not None and not m.whole:
                    _scope_filter(res, m, self.library.roots())
                    res["scope_labels"] = {"en": m.label("en"), "pl": m.label("pl")}
                res["scope_label"] = m.label(mp_i18n.lang()) if m is not None else None
                p["result"] = res
                p["state"] = "done"
            except Exception as e:  # noqa: BLE001 — reported to the app
                p.update(state="error", error=str(e)[:300])
                self.log("plan failed:", kind, e)
        threading.Thread(target=mp_i18n.bind(run), daemon=True).start()  # titles and notes in the request's language
        return dict(p)

    def plan_status(self, pid):
        p = self.plans.get(pid)
        if not p:
            raise TaskError(T("the preview has expired, make it again", "podgląd wygasł, zrób go jeszcze raz"), 404)
        return dict(p)


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
                    t = next((t for t in self.items if t["state"] == "queued" and not t.get("held")), None)
                    if not t:
                        break
                    t.update(state="running", started=t["started"] or time.time())
                    self.cancel_ev.clear()
                    self._save()
                try:
                    self._run(t)
                except Exception as e:  # noqa: BLE001 — the queue goes on with the next task
                    self.log(f"task {t['id']} failed:", e)
                    with self.lock:
                        t.update(state="error", current=None, finished=time.time())
                        self._save()

    def _run(self, t, nested=False):
        """Does a task's items in order. `nested`: a quick task run in a break between another task's files."""
        mp_i18n.set_lang(t.get("lang"))  # item errors in the language of whoever started the task
        self.log(f"task {t['id']} ({t['kind']}): {t['title']}, {len(t['items'])} items")
        done_before = sum(i["size"] for i in t["items"] if i["state"] in ("done", "error", "skipped"))
        t["done_bytes"] = done_before
        t["_run"] = (time.time(), done_before, t.get("held_secs", 0))
        held = False
        for i in t["items"]:
            if i["state"] in ("done", "error", "skipped"):
                continue
            if t.get("cancel"):
                break
            if t.get("held"):  # paused by the owner (F4): stop at the file boundary
                held = True
                break
            if not nested:
                self._run_quick(t)
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
                    self._record_move(i)
                    if i.get("prune"):
                        self._prune(i)
                elif i["op"] == "cast_ready":
                    self._cast_ready(i, progress, t)
                elif i["op"] == "subs":
                    self._subs(i, t)
                elif i["op"] == "subs_get":
                    self._subs_get(i, t)
                elif i["op"] == "trash":
                    self._trash(i)
                else:
                    self._shrink(i, progress, t)
                    t["saved"] += i.get("saved", 0)
                i["state"] = "done" if i["state"] == "running" else i["state"]
            except _Cancelled:
                i["state"] = "pending"
                break
            except _Held:
                i["state"] = "pending"
                held = True
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
            t.pop("_run", None)
            if t.get("cancel"):
                t["state"] = "cancelled"
                _stopped(t)
                t["finished"] = time.time()
            elif (held and t.get("held")) or (nested and any(i["state"] == "pending" for i in t["items"])):
                t["state"] = "queued"  # waits, paused, in its place; resume puts it back to work
            else:
                t["state"] = "done"
                t["finished"] = time.time()
            self._save()
        if t["state"] == "queued":
            self.log(f"task {t['id']} paused")
            return
        if t.get("bad_subs") and t["state"] == "done":  # "add the ones that don't fit to Download missing" (1.16+)
            want = {}
            for rid, idx, rname, path, code in t.pop("bad_subs"):
                want.setdefault((rid, idx, rname, path), [])
                if code not in want[(rid, idx, rname, path)]:
                    want[(rid, idx, rname, path)].append(code)
            items = [{"op": "subs_get", "root_id": rid, "root": idx, "root_name": rname, "path": path,
                      "name": os.path.basename(path), "size": 1, "langs": langs} for (rid, idx, rname, path), langs in want.items()]
            self._add_task({"kind": "subs_get", **_title("Download subtitles that didn't fit",
                                                         "Pobierz napisy, które nie pasowały")}, items)
        self.library.invalidate()
        if not nested:
            try:
                self.replicate()
            except Exception as e:  # noqa: BLE001
                self.log("replication:", e)
        self.log(f"task {t['id']} {t['state']}: {sum(i['state'] == 'done' for i in t['items'])} done, {t['errors']} errors")

    def _run_quick(self, cur):
        """Quick tasks (the trash, moves within a disk) waiting behind `cur` run now, between its files (F4)."""
        while True:
            with self.lock:
                q = next((x for x in self.items if x["state"] == "queued" and x.get("quick") and not x.get("held")
                          and x is not cur), None)
                if not q:
                    return
                q.update(state="running", started=q["started"] or time.time())
                self._save()
            self._run(q, nested=True)
            if not cur.get("cancel"):
                self.cancel_ev.clear()  # a cancelled quick task doesn't cancel the one it interrupted
            mp_i18n.set_lang(cur.get("lang"))

    def _prune(self, i):
        """After a move by the folder layout: the folders it left empty go, up to the library folder."""
        try:
            root = self._root_path(i["root_id"])
        except TaskError:
            return
        d = os.path.dirname(os.path.join(root, i["path"]))
        while os.path.realpath(d).startswith(os.path.realpath(root) + os.sep):
            try:
                os.rmdir(d)  # only an empty one
            except OSError:
                return
            d = os.path.dirname(d)

    def _check_cancel(self, t=None):
        if self.cancel_ev.is_set():
            raise _Cancelled()
