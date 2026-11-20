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
import json
import collections
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

import mp_detached
import mp_media
import mp_i18n
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
# picture formats today's phones, TVs and Chromecasts no longer decode (Android has no DivX 3/WMV/RealVideo decoder):
# such files are re-encoded even when it saves little, so the collection keeps playing everywhere
OLD_VIDEO = {"msmpeg4v1", "msmpeg4v2", "msmpeg4v3", "wmv1", "wmv2", "wmv3", "vc1", "rv10", "rv20", "rv30", "rv40",
             "flv1", "vp6", "vp6f", "vp6a", "theora", "cinepak", "indeo2", "indeo3", "indeo4", "indeo5", "svq1", "svq3",
             "mpeg1video", "h261",
             # MPEG-4 Part 2 (XviD, DivX 4+): Chromecasts don't take it, and phones play it badly (in AVI the B-frames carry
             # no presentation time: the Pixel's hardware decoder drops them, its software one shows them out of order)
             "mpeg4"}
# audio the phone's player takes as it is (media3 + its FFmpeg extension); anything else (WMA, Cook, AMR…) is
# re-encoded even by the profiles that keep audio, or the video would play silent
PLAYABLE_AUDIO = {"aac", "mp3", "mp2", "mp1", "ac3", "eac3", "dts", "opus", "vorbis"}


def is_old(v):
    """A picture phones, TVs and Chromecasts don't decode (or decode wrong): an OLD_VIDEO codec, or H.264 with more than
    8 bits or chroma other than 4:2:0 (anime "Hi10P": no phone has a hardware decoder for it)."""
    c, pix = v.get("codec"), v.get("pix_fmt") or ""
    return c in OLD_VIDEO or (c == "h264" and (v.get("bits", 8) > 8 or "422" in pix or "444" in pix))


OLD_GROWTH = 1.25  # an old-format file may come out up to this much bigger (it then plays, which is the point)
MIN_SIZE = 50 << 20  # smaller videos (samples, extras) aren't worth shrinking
TRASH = ".mp-trash"
RESERVE = 0.05  # of a disk kept free; under it a shrink's originals go to the trash of another disk
ENCODERS = ("auto", "cpu", "gpu")
WATCH_MODES = ("continue", "pause")
GPU_SIZE = {True: 1.1, False: 1.3}  # video size from a graphics card vs x265 at the same quality (rough guesses)
SAME_DISK_DUTY = (1.0, 2.0)  # an encode on the disk somebody watches from: seconds running, seconds stopped
SAME_DISK_FREE = 8 << 20  # ... unless it reads less than this per second (x265 does), then it runs at full speed
FILM_TAGS = {"filmy", "bajki"}  # films for adults and for children: moving between them is only a suggestion
KEEP = 50  # finished tasks kept in tasks.json



def _title(en, pl):
    """A task title in the current language plus both versions, so a list read in the other language shows its own."""
    return {"title": T(en, pl), "titles": {"en": en, "pl": pl}}

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
    return sorted(n for n in names if n != base and n.lower().startswith(stem.lower() + ".") and not os.path.isdir(os.path.join(d, n))
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
    def __init__(self, path, cfg, save_cfg, library, backend, watching, renderers, log):
        """watching(): files in use now, [{"path", "kind"}] with kind stream (a GET from the library or a torrent),
        probe (HEAD only: a phone playing its own copy), mpv (the computer's screen) or convert (a version for a TV);
        backend(): the torrent client."""
        self.path, self.cfg, self.save_cfg, self.library = path, cfg, save_cfg, library
        self.encodes = os.path.join(os.path.dirname(path), "encodes")  # detached encodes (mp_detached)
        self.moves_path = os.path.join(os.path.dirname(path), "moves.json")  # journal of moved items (phones re-key)
        self.backend, self.watching, self.renderers, self.log = backend, watching, renderers, log
        self.lock = threading.Lock()
        self.plans = {}  # id → {state, progress, result | error}; in memory, an hour
        self.fetch_subs = None  # (full, lang, prefer_provider) → download result or None; set by the agent
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
        out = {k: v for k, v in t.items() if k not in ("items", "hold", "enc", "titles")} | {
            "item_count": len(t["items"]), "items_done": sum(1 for i in t["items"] if i.get("state") == "done")}
        # the same reason once with a count (a full disk once listed 457 identical errors, one per file)
        reasons = collections.Counter(_reason(i["error"]) for i in t["items"] if i.get("state") == "error" and i.get("error"))
        if reasons:
            out["error_groups"] = [{"error": e, "count": n} for e, n in reasons.most_common(5)]
        if not brief:
            out["items"] = [{k: v for k, v in i.items() if not k.startswith("_")} for i in t["items"]]
        if t.get("titles"):
            out["title"] = t["titles"].get(mp_i18n.lang()) or t["title"]
        if t.get("enc"):
            out["encoder"] = self.encoder_label(t["enc"])
        if t.get("hold"):
            out["note"] = self.hold_text(t["hold"])
        return out

    @staticmethod
    def hold_text(hold):
        return {"watching": T("Paused, somebody is watching", "Wstrzymane, ktoś ogląda"),
                "conversion": T("Paused while a version for a TV is being prepared",
                                "Wstrzymane, trwa przygotowanie wersji dla telewizora"),
                "same_disk": T("Slower: somebody is watching from the same disk", "Wolniej: ktoś ogląda z tego samego dysku"),
                "in_use": T("Waiting to swap: this film is being watched", "Czeka z podmianą: ten film jest właśnie oglądany"),
                }.get(hold, "")

    # ---- encoders (shrinking)

    def encoders(self):
        """Encoders to choose from, labelled in the request's language (the card only once detection has found it)."""
        det = mp_media.hevc_info() or {}
        out = [{"id": "cpu", "label": self.encoder_label("cpu"), "bframes": True,
                "note": T("smallest files, slowest", "najmniejsze pliki, najwolniej")}]
        if g := det.get("gpu"):
            out.append({"id": "gpu", "label": self.encoder_label("gpu"), "bframes": g["bframes"],
                        "note": T("fast, files a little larger than from the processor",
                                  "szybko, pliki trochę większe niż z procesora") if g["bframes"] else
                        T("fast, but an older card without B-frames: files about 30% larger",
                          "szybko, ale starsza karta bez B-klatek: pliki o ok. 30% większe")})
        return out

    @staticmethod
    def encoder_label(enc):
        if enc == "gpu":
            return ((mp_media.hevc_info() or {}).get("gpu") or {}).get("label") or T("graphics card", "karta graficzna")
        return T("Processor (x265)", "Procesor (x265)")

    def encoder_auto(self):
        g = (mp_media.hevc_info() or {}).get("gpu")
        return "gpu" if g and g["bframes"] else "cpu"

    def resolve_encoder(self, choice=None):
        choice = choice or self.cfg.get("shrink_encoder") or "auto"
        if choice == "auto":
            return self.encoder_auto()
        return "gpu" if choice == "gpu" and (mp_media.hevc_info() or {}).get("gpu") else "cpu"

    def shrink_config(self):
        return {"encoder": self.cfg.get("shrink_encoder") or "auto", "auto": self.encoder_auto(),
                "while_watching": self.cfg.get("shrink_while_watching") or "continue",
                "detecting": mp_media.hevc_info() is None, "encoders": self.encoders()}

    def set_shrink_config(self, b):
        if "encoder" in b:
            if b["encoder"] not in ENCODERS:
                raise TaskError(T("encoder must be auto, cpu or gpu", "koder to auto, cpu albo gpu"))
            self.cfg["shrink_encoder"] = b["encoder"]
        if "while_watching" in b:
            if b["while_watching"] not in WATCH_MODES:
                raise TaskError(T("while_watching must be continue or pause", "while_watching to continue albo pause"))
            self.cfg["shrink_while_watching"] = b["while_watching"]
        self.save_cfg()
        return self.shrink_config()

    def _speed(self, enc):
        """Pixel·seconds of video per second of work: learned from real encodes, else the detection trial."""
        learned = (self.cfg.get("shrink_speed") or {}).get(enc)
        return learned or ((mp_media.hevc_info() or {}).get(enc) or {}).get("speed")

    def _learn_speed(self, enc, px_seconds, work):
        if work < 30:
            return
        v = px_seconds / work
        sp = self.cfg.setdefault("shrink_speed", {})
        sp[enc] = round(v if not sp.get(enc) else 0.7 * sp[enc] + 0.3 * v)
        self.save_cfg()

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
        if part and part.endswith(".mp-shrink.part.mkv"):
            try:
                j.wait(30)
            except subprocess.TimeoutExpired:
                pass
            _rm(part)

    def remove(self, tid):
        """A finished or cancelled task off the list (agent 1.12.1+); a queued or running one has to be cancelled first."""
        with self.lock:
            t = next((t for t in self.items if t["id"] == tid), None)
            if not t:
                raise TaskError(T("no such task", "nie ma takiego zadania"), 404)
            if t["state"] in ("queued", "running"):
                raise TaskError(T("cancel the task first", "najpierw anuluj zadanie"), 409)
            self.items.remove(t)
            self._save()
            return {"ok": True}

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
        t = {"id": secrets.token_hex(4), "kind": res["kind"], "title": res["title"], "titles": res.get("titles"), "state": "queued",
             "created": time.time(), "started": None, "finished": None, "items": items,
             "total": sum(i["size"] for i in items), "done_bytes": 0, "current": None, "pct": 0,
             "saved": 0, "errors": 0, "lang": mp_i18n.lang(),
             **({"enc": res["encoder_id"]} if res.get("encoder_id") else {})}
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
        threading.Thread(target=mp_i18n.bind(run), daemon=True).start()  # titles and notes in the request's language
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
            return {"kind": "move", **_title(f"Free up {roots[root_index]['name']}", f"Zwolnij {roots[root_index]['name']}"),
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
            return {"kind": "move", **_title(f"Move {len(out)} items", f"Przenieś {len(out)} pozycji"),
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
        return self._plan("tidy", run)

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
                    files += [(r, os.path.join(dp, f)) for f in sorted(fs)
                              if os.path.splitext(f)[1].lower() in VIDEO_EXT and not f.startswith(".")]
            items, have = [], 0
            for k, (r, full) in enumerate(files):
                p["progress"] = int(100 * k / max(len(files), 1))
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

    def _subs_get(self, i, t):
        """Downloads the missing languages of one video (the best result that fits, see the agent's
        fetch_best_subs); in a folder the provider that worked for the previous episode goes first."""
        if not self.fetch_subs:
            raise TaskError(T("subtitle downloading is not available", "pobieranie napisów jest niedostępne"))
        full = os.path.join(self._root_path(i["root_id"]), i["path"])
        if not os.path.exists(full):
            raise TaskError(T("the video no longer exists", "filmu już nie ma"))
        prefer = t.setdefault("prefer", {})
        out, missing = [], []
        for lang in i["langs"]:
            self._check_cancel()
            key = f"{os.path.dirname(full)}|{lang}"
            r = self.fetch_subs(full, lang, prefer.get(key))
            if not r:
                missing.append(lang)
                continue
            prefer[key] = r.get("provider")
            v = (r.get("fit") or {}).get("verdict")
            out.append(f"{lang}: {r.get('release') or r.get('provider')}" + (f" ({v})" if v and v != "good" else ""))
        i["note"] = "; ".join(out + [T(f"{l}: none found", f"{l}: nie znaleziono") for l in missing])
        if missing and not out:
            raise TaskError(i["note"])

    def _subs(self, i, t):
        """Fits every subtitle file of one video; a file inside a seeded torrent is only assessed."""
        root = self._root_path(i["root_id"])
        full = os.path.join(root, i["path"])
        if not os.path.exists(full):
            raise TaskError(T("the video no longer exists", "filmu już nie ma"))
        try:
            dev = os.stat(full).st_dev
        except OSError:
            dev = None
        # reading the whole file for its sound: not while somebody watches from the same disk
        while self._hold(dev) is not None:
            t["note"] = self.hold_text("same_disk")
            if self.cancel_ev.wait(10):
                t.pop("note", None)
                raise _Cancelled()
        t.pop("note", None)
        import mp_subsync
        out, errors = [], []
        for x in i["subs"]:
            path = os.path.join(os.path.dirname(full), x["name"])
            if not os.path.exists(path):
                continue
            try:
                r = self.subsync.fit(full, path, change=not x.get("locked"))
            except (mp_subsync.SyncError, OSError, ValueError) as e:
                errors.append(f"{x['name']}: {e}")
                continue
            out.append({"name": r["name"], "lang": x.get("lang"), "verdict": r["verdict"], "z": r["z"],
                        "changed": r["changed"], "locked": bool(x.get("locked")), "converted": r["name"] != x["name"],
                        "shift": r.get("fixed_offset", r["offset"]), "ratio": r.get("fixed_ratio", r["ratio"])})
            t["fixed"] = t.get("fixed", 0) + (1 if r["changed"] else 0)
            t["unfit"] = t.get("unfit", 0) + (1 if r["verdict"] == "bad" else 0)
            self._check_cancel()
        i["result"] = out
        i["note"] = "; ".join(_fit_text(x) for x in out)
        if errors and not out:
            raise TaskError("; ".join(errors)[:300])
        if errors:
            i["note"] += "; " + "; ".join(errors)

    def plan_dupes(self):
        def run(p):
            import hashlib
            by_size = {}
            roots = [r for r in self.library.roots() if r["exists"] and not set(r["tags"]) & HIDDEN_TAGS]  # `kopia`: on purpose
            for n, r in enumerate(roots):
                p["progress"] = int(30 * n / max(len(roots), 1))  # walking the folders: 0–30 %
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
            out = []
            for k, g in enumerate(groups):
                p["progress"] = 30 + int(70 * k / max(len(groups), 1))
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
            return {"kind": "dupes", **_title("Duplicates", "Duplikaty"), "groups": out, "items": [],
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
            return {"kind": "trash", **_title(f"Delete {len(out)} items", f"Usuń {len(out)} pozycji"), "items": out,
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

    def plan_shrink(self, root_index, rel="", encoder=None, modernize=False):
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
            for k, (r, full) in enumerate(files):
                p["progress"] = int(100 * k / max(len(files), 1))
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
                    h = min(v.get("height") or pr["height"], pr["height"])
                    if v.get("codec") in ("hevc", "av1") and (v.get("height") or 0) <= pr["height"]:
                        skipped.append(dict(item, reason=T("already HEVC/AV1 at this size", "już HEVC/AV1 w tym rozmiarze")))
                        continue
                    vkbps = pr["kbps"] * (h / pr["height"]) ** 1.5
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
            if modernize:
                title = _title("Update old formats", "Unowocześnij stare formaty")
            else:
                r = bases[0][0]
                title = _title(f"Shrink {r['name']}" + (f"/{rel}" if rel else ""), f"Zmniejsz {r['name']}" + (f"/{rel}" if rel else ""))
            return {"kind": "shrink", **title, "modernize": modernize,
                    "items": items, "bytes": sum(i["size"] for i in items), "est": sum(i["est"] for i in items),
                    "saved": saved, "hours": round(hours, 1), "encoder": self.encoder_label(enc), "encoder_id": enc,
                    "encoders": [dict(e, est=est_by[e["id"]], saved=sum(i["size"] for i in items) - est_by[e["id"]],
                                      work_hours=None if work_by[e["id"]] is None else round(work_by[e["id"]] / 3600, 1))
                                 for e in self.encoders()],
                    "warnings": self._codec_warnings(), "skipped": skipped[:100],
                    "trash_days": self.trash_days()}
        return self._plan("shrink", run)

    def trash_days(self):
        return int(self.cfg.get("trash_days", 14))


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
        mp_i18n.set_lang(t.get("lang"))  # item errors in the language of whoever started the task
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
                    self._record_move(i)
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

    def _record_move(self, i):
        """Phones keep positions and "watched" under the library folder and path, so every move is journalled:
        GET /api/library/moves lets them re-key what they saved (agent 1.14+)."""
        to = "/".join(x for x in ((i.get("to_sub") or "").strip("/"), os.path.basename(i["path"])) if x)
        with self.lock:
            try:
                with open(self.moves_path) as f:
                    j = json.load(f)
            except (OSError, ValueError):
                j = []
            j.append({"at": time.time(), "from_root_id": i["root_id"], "from": i["path"],
                      "to_root_id": i["to_root_id"], "to": to})
            j = j[-5000:]
            tmp = self.moves_path + ".tmp"
            with open(tmp, "w") as f:
                json.dump(j, f)
            os.replace(tmp, self.moves_path)

    def moves(self, since=0.0):
        """Moves after `since` (epoch s) with the library folders' current numbers; unknown folders are left out."""
        try:
            with open(self.moves_path) as f:
                j = json.load(f)
        except (OSError, ValueError):
            j = []
        idx = {r["id"]: r["index"] for r in self.library.roots()}
        out = [{"at": m["at"], "from_root": idx[m["from_root_id"]], "from": m["from"], "to_root": idx[m["to_root_id"]],
                "to": m["to"]} for m in j if m["at"] > since and m["from_root_id"] in idx and m["to_root_id"] in idx]
        return {"moves": out, "now": time.time()}

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
        if not mp_detached.Detached.find(self.encodes, i.get("job")):
            # the new file is written next to the old one before the old one goes to the trash (on the same disk):
            # without room, ffmpeg dies at the end with ENOSPC, file after file (457 such errors on a full disk)
            need = int(i.get("est") or st.st_size) + (256 << 20)
            u = shutil.disk_usage(os.path.dirname(full))
            if u.free - need < int(u.total * RESERVE):
                self._relieve(root, need + int(u.total * RESERVE) - u.free)
            free = shutil.disk_usage(os.path.dirname(full)).free
            if free < need:
                raise TaskError(T(f"not enough room on the disk: about {need >> 20} MB needed, {free >> 20} MB free "
                                  f"(the trash of its library folders may hold old originals)",
                                  f"za mało miejsca na dysku: potrzeba ok. {need >> 20} MB, wolne {free >> 20} MB "
                                  f"(kosz folderów biblioteki może trzymać stare oryginały)"))
        info = mp_media.probe(full)
        pr = PROFILES[i["profile"]]
        stem, ext = os.path.splitext(full)
        out_final = stem + ".mkv"
        part = os.path.join(os.path.dirname(full), f".{os.path.basename(stem)}.mp-shrink.part.mkv")
        enc = t.get("enc") or "cpu"  # tasks from before 1.7 ran on the processor
        if enc == "gpu" and not mp_media.detect_hevc().get("gpu"):
            self.log("shrink: the graphics card's encoder is gone, using the processor")
            enc = "cpu"
        dur = info["duration"]
        # an encode started before an agent restart: attach to it (it went on meanwhile) instead of starting over
        p = mp_detached.Detached.find(self.encodes, i.get("job"))
        if p and (p.meta.get("part") != part or p.poll() not in (None, 0)):
            p.terminate()
            p.remove()
            p = None
        if p:
            self.log("shrink: attached to the running encode of", os.path.basename(full))
            enc = p.meta.get("enc") or enc
        else:
            _rm(part)
            cmd = self._shrink_cmd(full, part, info, pr, enc)
            self.log("shrink:", os.path.basename(full), i["profile"], enc)
            os.makedirs(self.encodes, exist_ok=True)
            p = mp_detached.Detached.start(self.encodes, cmd, {"part": part, "full": full, "enc": enc}, "shrink")
            i["job"] = p.id
            with self.lock:
                self._save()
        with self.lock:
            self.proc = p
        done = threading.Event()
        work = [0.0]  # seconds the encoder was allowed to run (to learn its speed)
        threading.Thread(target=self._govern, args=(p, t, full, done, work), daemon=True).start()
        try:
            while p.poll() is None:
                us = p.progress().get("out_time_us", "")
                if us.isdigit() and dur:
                    progress(int(i["size"] * min(int(us) / 1e6 / dur, 1)))
                if self.cancel_ev.wait(1):
                    raise _Cancelled()
        except _Cancelled:
            done.set()
            p.terminate()
            try:
                p.wait(60)
            except subprocess.TimeoutExpired:
                pass
            p.remove()
            i.pop("job", None)
            _rm(part)
            raise
        finally:
            done.set()
            _signal(p, "SIGCONT")
            t.update(paused=False, hold=None)
            with self.lock:
                self.proc = None
        err_tail = p.err_tail()
        p.remove()
        i.pop("job", None)
        if self.cancel_ev.is_set():
            _rm(part)
            raise _Cancelled()
        if p.returncode != 0:
            _rm(part)
            if p.returncode == -1:
                raise TaskError(T("the encode was interrupted (the computer restarted?)", "kodowanie zostało przerwane (restart komputera?)"))
            raise TaskError(_ffmpeg_reason(err_tail))
        new = os.path.getsize(part)
        try:
            ninfo = mp_media.probe(part)
        except Exception as e:  # noqa: BLE001
            _rm(part)
            raise TaskError(T(f"the result can't be read: {e}", f"wyniku nie da się odczytać: {e}"))
        got = ninfo.get("duration") or 0
        if abs(got - dur) > max(2.0, dur * 0.005) and abs(got - _readable_length(full)) > max(2.0, got * 0.005):
            _rm(part)
            raise TaskError(T("the result has a different length, the original was kept", "wynik ma inną długość, oryginał został"))
        if new > st.st_size * (OLD_GROWTH if i.get("old_format") else 1 - pr.get("min_saving", MIN_SAVING) / 2):
            _rm(part)
            i["state"] = "skipped"
            i["error"] = T("the result wasn't much smaller, the original was kept", "wynik nie był wyraźnie mniejszy, oryginał został")
            return
        dec = subprocess.run(_nice(["ffmpeg", "-hide_banner", "-nostdin", "-v", "error", "-i", part, "-map", "0:v:0",
                                    "-f", "null", "-"]), capture_output=True, text=True)
        # the null muxer's own complaints (repeated timestamps from AVI sources) are not decoding errors
        bad = [ln for ln in dec.stderr.splitlines() if ln.strip() and not ln.startswith("[null @")]
        if dec.returncode != 0 or bad:
            _rm(part)
            raise TaskError(T("the result doesn't decode cleanly, the original was kept", "wynik nie dekoduje się czysto, oryginał został"))
        if not pr.get("copy"):
            h = (ninfo.get("video") or {}).get("height") or 0
            self._learn_speed(enc, dur * h * h * 16 / 9, work[0])
        # a film being watched (or probed by a phone that plays its own copy) keeps its name until it's left alone:
        # the player asks for byte ranges of the original
        real = os.path.realpath(full)
        while any(os.path.realpath(w["path"]) == real for w in self.watching()):
            t["hold"] = "in_use"
            if self.cancel_ev.wait(5):
                t["hold"] = None
                _rm(part)
                raise _Cancelled()
        t["hold"] = None
        # swap: the original to the trash (its own disk's: a rename, or another disk's when this one runs short),
        # the result in its place
        if self.trash_days() > 0:
            self._to_trash(full, root, i)
        else:
            os.remove(full)
        os.rename(part, out_final)
        mp_media.remove_variant(full)
        i.update(saved=st.st_size - new, new_size=new, new_path=os.path.relpath(out_final, root))
        self.log(f"shrink done: {os.path.basename(full)} {st.st_size >> 20} → {new >> 20} MB")
        # the new name (.avi → .mkv) shows in search at once; the catalog walks again at most every 10 minutes
        mp_library.forget_names()
        if time.time() - getattr(self, "_walked", 0) > 600:
            self._walked = time.time()
            self.library.invalidate()

    def _hold(self, full_dev):
        """Why an encode should not run at full speed now: None, watching / conversion (stop), same_disk (slower)."""
        w = [x for x in self.watching() if x["kind"] != "probe"]
        if not w:
            return None
        if (self.cfg.get("shrink_while_watching") or "continue") == "pause":
            return "watching"
        if any(x["kind"] == "convert" for x in w):
            return "conversion"
        for x in w:
            try:
                if os.stat(x["path"]).st_dev == full_dev:
                    return "same_disk"
            except OSError:
                pass
        return None

    def _govern(self, p, t, full, done, work):
        """Runs next to an encode: stops it, slows it down on a busy disk (SIGSTOP/SIGCONT) or lets it run.
        On the disk somebody watches from, only an encode that reads a lot (a graphics card on a big file) is slowed
        down: x265 reads a few MB/s and a USB disk serves a stream next to it with plenty to spare."""
        try:
            dev = os.stat(full).st_dev
        except OSError:
            dev = None
        can = sys.platform != "win32"
        last, rate = _read_bytes(p.pid), None
        while not done.is_set():
            hold = self._hold(dev) if can else None
            if hold == "same_disk" and rate is not None and rate < SAME_DISK_FREE:
                hold = None
            t["hold"] = hold
            t["paused"] = hold in ("watching", "conversion")
            if hold in ("watching", "conversion"):
                _signal(p, "SIGSTOP")
                done.wait(2)
                continue
            run = SAME_DISK_DUTY[0] if hold == "same_disk" else 1.0
            _signal(p, "SIGCONT")
            done.wait(run)
            work[0] += run
            now = _read_bytes(p.pid)
            if now is not None and last is not None:
                rate = (now - last) / run  # bytes read while it was allowed to run
            last = now
            if hold == "same_disk" and not done.is_set():
                _signal(p, "SIGSTOP")
                done.wait(SAME_DISK_DUTY[1])

    def _shrink_cmd(self, full, out, info, pr, enc="cpu"):
        v = info["video"]
        h = min(v.get("height") or pr.get("height", 0), pr.get("height", 1 << 16))
        # even dimensions: old AVIs can be 351 px wide, and 4:2:0 HEVC/H.264 needs both even
        vf = f"scale=-2:{h}" if (v.get("height") or 0) > h else "scale=trunc(iw/2)*2:trunc(ih/2)*2"
        cmd = ["ffmpeg", "-hide_banner", "-nostdin", "-y", "-loglevel", "error", "-i", full, "-map", "0:v:0"]
        keep = _audio_keep(info, pr["audio"])
        for a in keep:
            cmd += ["-map", f"0:a:{a['index']}"]
        cmd += ["-map", "0:s?", "-map_chapters", "0"]
        if pr.get("copy"):
            cmd += ["-c:v", "copy"]
        elif enc == "gpu":
            pre, vfx, vargs = mp_media.hevc_cmd(mp_media.detect_hevc()["gpu"], pr["cq"])
            cmd[6:6] = pre  # input options (e.g. the VAAPI device) go before -i
            cmd += ["-vf", f"{vf},{vfx}", *vargs]
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

    def _spare_disk(self, avoid_dev, size):
        """A library folder on another disk with `size` to spare above its reserve (the most room first): a trash that
        doesn't fit on its own disk goes there. None when no disk has the room."""
        best = None
        for r in self.library.roots():
            d = _dev(r["path"]) if r["exists"] else None
            if d is None or d == avoid_dev:
                continue
            try:
                u = shutil.disk_usage(r["path"])
            except OSError:
                continue
            room = u.free - int(u.total * RESERVE) - size
            if room > 0 and (best is None or room > best[0]):
                best = (room, r)
        return best[1] if best else None

    def _trash_name(self, root):
        """The folder for one library folder's originals inside another disk's trash day."""
        r = next((x for x in self.library.roots() if x["path"] == root), None)
        return ((r or {}).get("name") or os.path.basename(root)).replace("/", "_")

    def _to_trash(self, full, root, i):
        """A replaced original to the trash. On its own disk that's a rename, but the disk then keeps the original and
        the new file both: a big run filled a disk that way (hundreds of "no space left"). When the disk would stay
        under its reserve, the original goes to the trash of a library folder on another disk with room instead."""
        day, rel_dir = time.strftime("%Y-%m-%d"), os.path.dirname(i["path"])
        u = shutil.disk_usage(root)
        size = os.path.getsize(full)
        other = self._spare_disk(_dev(root), size) if u.free < int(u.total * RESERVE) else None
        if other is None:
            dest = os.path.join(root, TRASH, day, rel_dir)
            os.makedirs(dest, exist_ok=True)
            os.rename(full, os.path.join(dest, os.path.basename(full)))
            return
        dest = os.path.join(other["path"], TRASH, day, self._trash_name(root), rel_dir)
        os.makedirs(dest, exist_ok=True)
        shutil.move(full, os.path.join(dest, os.path.basename(full)))
        i["trash_on"] = other["name"]
        self.log(f"trash: {os.path.basename(full)} to the trash of {other['name']} (its disk is short of room)")

    def _relieve(self, root, need):
        """Make `need` bytes on the disk of `root` by moving its trash (oldest days first) to another disk with room.
        Nothing is deleted. Returns the bytes moved."""
        dev, moved = _dev(root), 0
        days = []
        for r in self.library.roots():
            d = os.path.join(r["path"], TRASH)
            if r["exists"] and _dev(r["path"]) == dev and os.path.isdir(d):
                days += [(day, r["path"]) for day in os.listdir(d) if os.path.isdir(os.path.join(d, day))]
        for day, rpath in sorted(days):
            if moved >= need:
                break
            src = os.path.join(rpath, TRASH, day)
            size = tree_size(src)[0]
            other = self._spare_disk(dev, size)
            if other is None:
                continue
            dest = os.path.join(other["path"], TRASH, day, self._trash_name(rpath))
            try:
                shutil.copytree(src, dest, dirs_exist_ok=True)
                shutil.rmtree(src)
            except OSError as e:
                self.log("trash: moving", src, "failed:", e)
                continue
            moved += size
            self.log(f"trash: {day} of {self._trash_name(rpath)} ({size >> 20} MB) moved to {other['name']} for room")
        return moved

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


def _no_space():
    return T("no space left on the disk", "brak miejsca na dysku")


def _ffmpeg_reason(tail):
    """ffmpeg's last lines as a reason a person can read: known causes in words, otherwise the last line without
    paths (hidden temp files), cut at the end."""
    text = " ".join(tail)
    if "No space left on device" in text:
        return _no_space()
    last = next((ln for ln in reversed(tail) if ln.strip()), "").strip()
    last = re.sub(r"(?:^|(?<=\s))/[^:]*/([^/:]+)", r"\1", last)
    if len(last) > 160:
        last = last[:160].rsplit(" ", 1)[0] + "…"
    return T("ffmpeg failed: ", "ffmpeg się nie udał: ") + last


def _reason(err):
    """An item's error as a grouping key: older tasks kept ffmpeg's raw tail (cut mid-word, with temp paths)."""
    if "No space left on device" in err or "no space left" in err.lower() or "brak miejsca" in err:
        return _no_space() if "not enough room" not in err and "za mało miejsca" not in err else re.sub(r"\d+ MB", "… MB", err)
    return re.sub(r"\d+ MB", "… MB", err)


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


def _mount(path):
    p = os.path.realpath(path)
    while not os.path.ismount(p):
        p = os.path.dirname(p)
    return p


def _read_bytes(pid):
    """Bytes a process has read (Linux /proc; with the page cache, which is fine as an upper bound), or None."""
    try:
        with open(f"/proc/{pid}/io") as f:
            for line in f:
                if line.startswith("rchar:"):  # read_bytes misses reads through FUSE (ntfs-3g USB disks)
                    return int(line.split()[1])
    except (OSError, ValueError):
        return None
    return None


def _fit_text(x):
    """One subtitle file's outcome, for the task's item list."""
    who = x.get("lang") or x["name"]
    if x["verdict"] == "bad":
        return T(f"{who}: doesn't fit this copy of the film", f"{who}: nie pasuje do tej wersji filmu")
    if x["verdict"] == "unsure":
        return T(f"{who}: unsure, left as it was", f"{who}: niepewne, zostawione bez zmian")
    how = []
    if abs(x["ratio"] - 1) > 1e-4:
        how.append(T("other frame rate", "inna liczba klatek"))
    if abs(x["shift"]) >= 0.4:
        how.append(f"{x['shift']:+.1f} s")
    if x["changed"] and not how and x.get("converted"):
        return T(f"{who}: fits, converted to SRT", f"{who}: pasują, zamienione na SRT")
    if x["changed"]:
        return T(f"{who}: fitted", f"{who}: dopasowane") + (f" ({', '.join(how)})" if how else "")
    if how and x.get("locked"):
        return T(f"{who}: off ({', '.join(how)}), but part of a seeded torrent, left alone",
                 f"{who}: przesunięte ({', '.join(how)}), ale należą do seedowanego torrenta, bez zmian")
    return T(f"{who}: fits", f"{who}: pasują")


def _signal(p, name):
    """SIGSTOP/SIGCONT to an encode (not on Windows; a process that has just ended is fine)."""
    if sys.platform == "win32" or p.poll() is not None:
        return
    try:
        p.send_signal(getattr(signal, name))
    except (OSError, ProcessLookupError):
        pass


def _nice(cmd):
    if sys.platform != "win32":
        if shutil.which("ionice"):
            cmd = ["ionice", "-c", "3", *cmd]
        if shutil.which("nice"):
            cmd = ["nice", "-n", "19", *cmd]
    return cmd


def _readable_length(full):
    """Seconds of picture that can really be read from a file. An interrupted download keeps its full length in the
    header (AVI) but ends early; the encode then has only what is there, and that is all right."""
    try:
        r = subprocess.run(_nice(["ffmpeg", "-hide_banner", "-nostdin", "-i", full, "-map", "0:v:0", "-c", "copy",
                                  "-f", "null", "-"]), capture_output=True, timeout=600)
    except (OSError, subprocess.SubprocessError):
        return 0.0
    t = re.findall(r"time=(\d+):(\d\d):(\d\d(?:\.\d+)?)", r.stderr.decode("utf-8", "replace"))
    return int(t[-1][0]) * 3600 + int(t[-1][1]) * 60 + float(t[-1][2]) if t else 0.0


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


def _lang2(code):
    """"pl" of "pl", "pol", "napiprojekt.pl", "pl-PL"; "" when none."""
    c = (code or "").rsplit(".", 1)[-1].split("-")[0].lower()
    if len(c) == 3:
        try:
            from babelfish import Language
            c = Language.fromcode(c, "alpha3b").alpha2
        except Exception:  # noqa: BLE001
            pass
    return "" if c in ("und", "mul", "zxx") else c


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
        if c in LOSSLESS_AUDIO or c.startswith("pcm_") or c not in PLAYABLE_AUDIO:
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
