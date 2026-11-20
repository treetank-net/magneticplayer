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

import mp_arrange
import mp_detached
import mp_media
import mp_i18n
import mp_ident
from mp_i18n import T
import mp_library
import mp_scope
from mp_library import HIDDEN_TAGS, SUB_EXT, VIDEO_EXT

PROFILES = {  # target height, x265 CRF, NVENC CQ, estimated video kb/s at that height, audio
    # lossless for the picture: only audio (other languages out, DTS/TrueHD/PCM → E-AC3/AAC), subtitles and chapters kept
    "tidy": {"copy": True, "audio": "lean", "min_saving": 0.03},
    "good": {"height": 1080, "crf": 24, "cq": 27, "kbps": 1800, "audio": "keep"},
    "compact": {"height": 720, "crf": 25, "cq": 28, "kbps": 900, "audio": "stereo"},
    "tiny": {"height": 480, "crf": 26, "cq": 29, "kbps": 450, "audio": "small"},
    "mini": {"height": 360, "crf": 27, "cq": 30, "kbps": 260, "audio": "small"},  # an archive of old series (1.16+)
    # HEVC at the file's own resolution (compression, 1.16+): `kbps` is for 1080p, scaled to the file's height
    "same": {"same": True, "crf": 24, "cq": 27, "kbps": 1800, "audio": "keep"},
}
# downscale targets (agent 1.16+) and the profile each one uses
TARGETS = {1080: "good", 720: "compact", 480: "tiny", 360: "mini"}
# compression qualities (agent 1.16+): x265 CRF, graphics card CQ and the bitrate factor of the estimate
QUALITIES = {"transparent": {"crf": 20, "cq": 23, "k": 1.6}, "balanced": {"crf": 24, "cq": 27, "k": 1.0},
             "saving": {"crf": 27, "cq": 30, "k": 0.7}}
HIGH_BITRATE = 3 << 30  # bytes per hour of video: "only high bitrate" in compression
# old picture → H.264 at the same resolution (modernize with `audio`, agent 1.16+): plays on every phone, TV and
# Chromecast; `kbps` is for 1080p
MODERN = {"same": True, "codec": "h264", "crf": 20, "cq": 23, "kbps": 3500, "audio": "keep"}
AUDIO_MODES = {"keep_if_ok": "keep", "always_aac": "aac"}
AAC_QUALITIES = ("standard", "high")
SUBS_PER_DAY = 20  # OpenSubtitles downloads a day without an account (what the counters plan with)
WATCHING_DAYS = 14  # a folder something was streamed from this recently is "being watched"
FACTS_RESCAN = 1800  # seconds between the background scans behind the counters (sooner after a library change)
FACTS_V = 2  # the scan's fact format: 2 (1.17) keeps the codec of embedded subtitles (For Chromecast); older facts are probed again
TEXT_SUBS = {"subrip", "srt", "ass", "ssa", "mov_text", "webvtt", "text"}  # embedded subtitles that can become .srt
CAST_SPEED = 80 << 20  # bytes per second of a remux (For Chromecast), for the estimates
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


# audio codecs whose damaged frames turned up in old rips ("Header missing"): ffmpeg and VLC skip such frames, a phone's
# decoder stops on them and the film goes silent or stalls. Files carrying them are decoded once in the background
# (audio only, at the lowest priority) and "Update old formats" re-encodes just their sound.
AUDIO_SUSPECT = {"mp3", "mp2", "mp1", "ac3"}
AUDIO_FIX = {"copy": True, "audio": "keep"}  # the picture copied, the damaged tracks re-encoded, the rest copied
AUDIO_CHECK_BUDGET = 90  # seconds a preview spends checking files the background scan hasn't reached yet
OLD_GROWTH = 1.25  # an old-format file may come out up to this much bigger (it then plays, which is the point)
def _stopped(t):
    """A cancelled task's items that never ran: "skipped" (the app showed them "waiting" for ever)."""
    for i in t.get("items") or []:
        if i.get("state") in (None, "pending", "running"):
            i["state"] = "skipped"
            i["note"] = T("not done: the task was stopped", "niezrobione: zadanie zatrzymano")


MIN_SIZE = int(os.environ.get("MP_TASKS_MIN_SIZE", 50 << 20))  # smaller videos (samples, extras) aren't worth shrinking
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


def free_name(path):
    """`path`, or `name (2).ext`, `name (3).ext`… when something with that name is already there (the trash keeps
    every original: a second job on the same file the same day must not overwrite the first one's original)."""
    if not os.path.lexists(path):
        return path
    stem, ext = os.path.splitext(path)
    if os.path.isdir(path):
        stem, ext = path, ""
    for n in range(2, 1000):
        cand = f"{stem} ({n}){ext}"
        if not os.path.lexists(cand):
            return cand
    return f"{stem} ({secrets.token_hex(3)}){ext}"


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
            if r["verdict"] == "bad" and not x.get("locked"):
                # 1.16+: set aside to Subtitles/ (kept, no longer the default), and/or downloaded again afterwards
                if i.get("mark_bad") and self.subsources is not None and "/" not in r["name"]:
                    import mp_subfiles
                    moved = mp_subfiles.demote(full, os.path.join(os.path.dirname(full), r["name"]), self.subsources,
                                               self.torrent_owned, self.subsync.move)
                    if moved:
                        out[-1]["aside"] = os.path.relpath(moved, os.path.dirname(full))
                code = _lang2(x.get("lang"))
                if i.get("queue_bad") and len(code) == 2:
                    t.setdefault("bad_subs", []).append([i["root_id"], i.get("root"), i["root_name"], i["path"], code])
            t["fixed"] = t.get("fixed", 0) + (1 if r["changed"] else 0)
            t["unfit"] = t.get("unfit", 0) + (1 if r["verdict"] == "bad" else 0)
            self._check_cancel()
        i["result"] = out
        i["note"] = "; ".join(_fit_text(x) for x in out)
        if errors and not out:
            raise TaskError("; ".join(errors)[:300])
        if errors:
            i["note"] += "; " + "; ".join(errors)

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

    def trash_days(self):
        return int(self.cfg.get("trash_days", 14))


    # ---- jobs v2 (agent 1.16+, docs/plan-ux-tasks.md F1/F2): scopes, counters, one plan kind per task

    def _load_facts(self):
        try:
            with open(self.facts_path) as f:
                d = json.load(f)
        except (OSError, ValueError):
            d = {}
        self.facts = d.get("files") or {}  # "<root id>/<rel>" → what the background scan knows about the video
        self.facts_list = d.get("list")  # [[root id, rel, size]] of the last walk (also files not probed yet)
        self.facts_at = d.get("at")  # when the last scan finished
        self.dupe_groups = d.get("dupes") or []  # [[key, …]] identical files (size + sampled hashes)

    def _save_facts(self):
        with self.flock:
            data = json.dumps({"files": self.facts, "list": self.facts_list, "at": self.facts_at,
                               "dupes": self.dupe_groups}, ensure_ascii=False)
        tmp = self.facts_path + ".tmp"
        with open(tmp, "w") as f:
            f.write(data)
        os.replace(tmp, self.facts_path)

    def _facts_loop(self):
        """The background scan behind the counters: every half hour (sooner after a library change) the library is
        walked; new or changed videos are probed (ffprobe, once per file), their subtitles listed and identical files
        found. New files then get what the owner asked for (`audio_fix_auto`, `subs_auto`)."""
        time.sleep(float(os.environ.get("MP_FACTS_SCAN_DELAY", 60)))
        while True:
            self.facts_wake.clear()
            try:
                self.scan_facts()
            except Exception as e:  # noqa: BLE001 — a disk that went away mid-walk must not stop the thread
                self.log("jobs scan failed:", e)
            self.facts_wake.wait(FACTS_RESCAN)

    def _job_roots(self):
        return [r for r in self.library.roots() if r["exists"] and not set(r["tags"]) & HIDDEN_TAGS]

    def _make_fact(self, r, rel, full, st, real, low=False):
        try:
            info = mp_media.probe(full, low=low)
            info = {"container": info.get("container"), "duration": info.get("duration"), "video": info.get("video"),
                    "audio": [{k: a.get(k) for k in ("index", "codec", "channels", "lang", "default")}
                              for a in info.get("audio") or []],
                    "subs": [{"lang": s.get("lang"), "codec": s.get("codec")} for s in info.get("subs") or []]}
            err = None
        except Exception as e:  # noqa: BLE001 — an unreadable or half-written file
            info, err = None, str(e)[:200]
        return {"root_id": r["id"], "rel": rel, "size": st.st_size, "mtime": int(st.st_mtime), "real": real,
                "ino": [st.st_dev, st.st_ino], "info": info, "err": err, "seen": time.time(), "v": FACTS_V}

    def _scan_wait(self, dev):
        """The background scan gives way: while somebody watches from the same disk it waits (a USB disk spinning
        through thousands of ffprobes next to a stream stuttered it), and it never probes faster than ~5 files/s."""
        for _ in range(720):  # at most an hour per file, then it goes on slowly anyway
            if self._hold(dev) not in ("same_disk", "watching"):
                break
            time.sleep(5)
        time.sleep(0.2)

    def _fresh_fact(self, r, rel, full, dsigs, store=True, scan=False, upgrade=False):
        """The fact of one video, from the scan when the file is unchanged, else probed now; subtitles listed again
        when its folder changed. None when the file is gone. A fact of an older format is probed again by the
        background scan, or now with `upgrade` (a plan that needs the new fields)."""
        try:
            st = os.stat(full)
        except OSError:
            return None
        key = f"{r['id']}/{rel}"
        with self.flock:
            f = self.facts.get(key)
        old = f is not None and f.get("v", 1) < FACTS_V and (scan or upgrade)
        if not f or f["size"] != st.st_size or f["mtime"] != int(st.st_mtime) or old:
            if scan:
                self._scan_wait(st.st_dev)
            f = self._make_fact(r, rel, full, st, os.path.realpath(full), low=scan)
            if store:
                with self.flock:
                    self.facts[key] = f
        d = os.path.dirname(full)
        if d not in dsigs:
            dsigs[d] = _dir_sig(d)
        if f.get("dsig") != dsigs[d]:
            f["side"], f["dsig"] = _side(full), dsigs[d]
        return f

    def scan_facts(self):
        roots = self._job_roots()
        try:  # a torrent still downloading: its files are looked at once they are complete
            unfinished = {os.path.realpath(os.path.join(t["save_path"], t["name"]))
                          for t in self.backend().list() if t.get("progress", 100) < 100}
        except Exception:  # noqa: BLE001
            unfinished = set()
        first = self.facts_at is None
        listing, new, dsigs, n = [], [], {}, 0
        for r in roots:
            for rel, is_dir, size in self.library._names(r):
                if is_dir or os.path.splitext(rel)[1].lower() not in VIDEO_EXT:
                    continue
                listing.append([r["id"], rel, size])
                full = os.path.join(r["path"], rel)
                real = os.path.realpath(full)
                if real in unfinished or any(real.startswith(u + os.sep) for u in unfinished):
                    continue
                key = f"{r['id']}/{rel}"
                with self.flock:
                    known = key in self.facts
                f = self._fresh_fact(r, rel, full, dsigs, scan=True)
                if f is None:
                    continue
                if not known:
                    n += 1
                    if not first:
                        new.append(key)
                    if n % 200 == 0:
                        self._save_facts()
        keys = {f"{a}/{b}" for a, b, _ in listing}
        with self.flock:
            for k in [k for k in self.facts if k not in keys]:
                del self.facts[k]
            self.facts_list = listing
            facts = dict(self.facts)
        rpath = {r["id"]: r["path"] for r in roots}
        groups = self._find_dupes([(k, os.path.join(rpath[f["root_id"]], f["rel"]), f) for k, f in facts.items()
                                   if f["size"] >= MIN_SIZE and f["root_id"] in rpath])
        with self.flock:
            self.dupe_groups = groups
            self.facts_at = time.time()
        self._save_facts()
        if n:
            self.log(f"jobs scan: {n} new videos looked at, {len(listing)} in the library")
        if new:
            self._auto_new(new)

    def _find_dupes(self, files, p=None):
        """Groups of identical files among [(key, full, fact)]: the same size, then the same first and last 4 MB, then
        two more samples from the middle (as plan_dupes). Hashes are kept in the facts while the file is unchanged."""
        by_size = {}
        for k, full, f in files:
            by_size.setdefault(f["size"], []).append((k, full, f))
        cands = []
        for g in by_size.values():
            seen, uniq = set(), []
            for x in g:  # hard links and symlinks to the same data are not copies
                ino = tuple(x[2].get("ino") or ()) or (x[1],)
                if ino not in seen:
                    seen.add(ino)
                    uniq.append(x)
            if len(uniq) > 1:
                cands.append(uniq)
        out = []
        for n, g in enumerate(cands):
            if p is not None:
                p["progress"] = 30 + int(70 * n / max(len(cands), 1))
                p["step"] = os.path.basename(g[0][1])
            for which in ("hq", "hf"):
                buckets = {}
                for k, full, f in g:
                    if not f.get(which):
                        try:
                            f[which] = _quick_hash(full, f["size"]) if which == "hq" else _mid_hash(full, f["size"])
                        except OSError:
                            continue
                    buckets.setdefault(f[which], []).append((k, full, f))
                g = max(buckets.values(), key=len) if buckets else []
                if len(g) < 2:
                    break
            if len(g) >= 2:
                out.append([k for k, _, _ in g])
        return out

    # ---- played videos (streamed from this server): "watched" for free up, "being watched" for subtitles

    def note_played(self, path):
        now = time.time()
        with self.flock:
            self.played[os.path.realpath(path)] = now
            if now - self._played_saved < 300:
                return
            self._played_saved = now
            cut = now - 400 * 86400
            self.played = {k: v for k, v in self.played.items() if v > cut}
            data = json.dumps(self.played, ensure_ascii=False)
        tmp = self.played_path + ".tmp"
        try:
            with open(tmp, "w") as f:
                f.write(data)
            os.replace(tmp, self.played_path)
        except OSError:
            pass

    def _last_played(self, real):
        """When a file, or anything under a folder, was last streamed (epoch s), or None."""
        with self.flock:
            items = list(self.played.items())
        best = None
        for p, t in items:
            if (p == real or p.startswith(real + os.sep)) and (best is None or t > best):
                best = t
        return best

    def _watched_dirs(self):
        cut = time.time() - WATCHING_DAYS * 86400
        with self.flock:
            return {os.path.dirname(p) for p, t in self.played.items() if t > cut}

    # ---- settings of the task screens

    def jobs_config(self):
        st = self.layout_settings()
        idx = {r["id"]: r["index"] for r in self.library.roots()}
        return {"audio_fix_auto": bool(self.cfg.get("audio_fix_auto")), "subs_auto": self.cfg.get("subs_auto") or "",
                "layout_levels": st["levels"], "layout_ages": st["ages"],  # 1.17+
                "layout_keep": None if st["keep"] is None else [{"root": idx[a], "path": b} for a, b in st["keep"] if a in idx]}

    def set_jobs_config(self, b):
        if "audio_fix_auto" in b:
            self.cfg["audio_fix_auto"] = bool(b["audio_fix_auto"])
        if "subs_auto" in b:
            self.cfg["subs_auto"] = ",".join(_langs(b["subs_auto"] or ""))
        if "layout_levels" in b:
            self.cfg["layout_levels"] = mp_arrange.norm_levels(b["layout_levels"]) or list(mp_arrange.DEFAULT_LEVELS)
        if "layout_ages" in b:
            self.cfg["layout_ages"] = mp_arrange.norm_ages(b["layout_ages"])
        if "layout_keep" in b:
            if b["layout_keep"] is None:
                self.cfg.pop("layout_keep", None)
            else:
                ids = {r["index"]: r["id"] for r in self.library.roots()}
                self.cfg["layout_keep"] = [{"root_id": ids[int(k["root"])], "path": str(k.get("path") or "").strip("/")}
                                           for k in b["layout_keep"] if str(k.get("root", "")).isdigit() and int(k["root"]) in ids]
        self.save_cfg()
        return self.jobs_config()

    # ---- what a kind would do with one file (shared by the counters and the plans, so they agree)

    def _cost(self, info, pr, to_h, enc, k=1.0):
        """(estimated bytes, seconds of work or None) of encoding a video with `pr` at height `to_h` on `enc`."""
        dur = info.get("duration") or 0
        vk = pr["kbps"] * k * (to_h / (pr.get("height") or 1080)) ** 1.5
        if enc == "gpu":
            vk *= 1.2 if pr.get("codec") == "h264" else GPU_SIZE[((mp_media.hevc_info() or {}).get("gpu") or {}).get("bframes", False)]
        ak = sum(_audio_kbps(a, pr["audio"]) for a in _audio_keep(info, pr["audio"]))
        est = int(dur * (vk + ak) * 1000 / 8 * 1.02)
        sp = self._speed(enc)
        work = dur * to_h * to_h * 16 / 9 / sp if sp else None
        if work is not None and pr.get("codec") == "h264" and enc == "cpu":
            work *= 0.6  # x264 is quicker than x265
        return est, work

    def _pick(self, kind, f, full, o, ctx):
        """One video for a kind: None (not for this task), {"pending"} (the background check hasn't reached it),
        {"ok"} (checked, nothing to do), {"skip": reason, …} (would qualify, but can't or isn't worth it) or
        {"item": extra item fields, "cost": e → (bytes, seconds), "pr", "to_h"}."""
        info = f.get("info") or {}
        v, dur, size = info.get("video") or {}, info.get("duration") or 0, f["size"]
        if not v or not dur or size < MIN_SIZE:
            return None
        seeding = _in_torrent(f.get("real") or full, ctx["torrents"])
        if kind == "audio_fix":
            if is_old(v) or not any(a.get("codec") in AUDIO_SUSPECT for a in info.get("audio") or []):
                return None
            chk = ctx.get("check")
            if chk is None:
                bad = self._acheck_cached(f)
            else:
                try:
                    bad = self.audio_check(full, info, None if chk is True else chk)
                except OSError:
                    return None
            if bad is None:
                return {"pending": True}
            if not bad:
                return {"ok": True}
            if seeding:
                return {"skip": _seeding_text()}
            names = sorted({(a.get("codec") or "").upper() for a in info["audio"] if a["index"] in bad})
            errs = self._acheck_errors(f)
            return {"item": {"audio_fix": True, "broken": bad, "audio_codecs": names, "to_height": 0, "profile": "original",
                             "aac": o.get("quality") or "standard", "errors": errs},
                    "cost": lambda e: (size, size / (40 << 20))}
        if kind == "modernize":
            if not is_old(v):
                return None
            if seeding:
                return {"skip": _seeding_text()}
            pr = dict(MODERN, audio=AUDIO_MODES[o.get("audio") or "keep_if_ok"])
            h = v.get("height") or 480
            cost = lambda e: self._cost(info, pr, h, e)  # noqa: E731
            if cost(ctx["enc"])[0] > size * OLD_GROWTH:
                return {"skip": T("the result would be much bigger", "wynik byłby dużo większy"), "est": cost(ctx["enc"])[0]}
            return {"item": {"modern": True, "old_format": True, "profile": "modern", "audio_mode": pr["audio"],
                             "to_height": h - h % 2}, "cost": cost}
        if kind == "downscale":
            target = int(o["target"])
            cls = _res_class(v)
            if cls <= target * 1.05:
                return None
            if seeding:
                return {"skip": _seeding_text()}
            prof = TARGETS[target]
            pr = PROFILES[prof]
            h = int(round((v.get("height") or cls) * target / cls))
            h -= h % 2
            cost = lambda e: self._cost(info, pr, h, e)  # noqa: E731
            if cost(ctx["enc"])[0] > size * (1 - MIN_SAVING):
                return {"skip": T("already small, little to gain", "już mały, niewiele do zyskania"), "est": cost(ctx["enc"])[0]}
            return {"item": {"profile": prof, "to_height": h}, "cost": cost}
        if kind == "compress":
            if is_old(v):
                return None  # old pictures: "modernise old picture"
            if o.get("skip_hevc_av1", True) and v.get("codec") in ("hevc", "av1"):
                return None
            if o.get("only_high_bitrate", True) and size / dur * 3600 < HIGH_BITRATE:
                return None
            if seeding:
                return None if o.get("skip_seeding", True) else {"skip": _seeding_text()}
            q = QUALITIES[o.get("quality") or "balanced"]
            pr = dict(PROFILES["same"], crf=q["crf"], cq=q["cq"])
            h = v.get("height") or 1080
            h -= h % 2
            cost = lambda e: self._cost(info, pr, h, e, q["k"])  # noqa: E731
            if cost(ctx["enc"])[0] > size * (1 - MIN_SAVING):
                return {"skip": T("already small, little to gain", "już mały, niewiele do zyskania"), "est": cost(ctx["enc"])[0]}
            return {"item": {"profile": "same", "q": {"crf": q["crf"], "cq": q["cq"]}, "to_height": h}, "cost": cost}
        raise TaskError(f"unknown kind {kind}")

    def _acheck_cached(self, f):
        with self.alock:
            c = self.achecks.get(f.get("real") or "")
        if c and c[0] == f["size"] and c[1] == f["mtime"]:
            return c[2]
        return None

    def _acheck_errors(self, f):
        """Damaged frames found in the sound (all tracks), or None for a check from before 1.16."""
        with self.alock:
            c = self.achecks.get(f.get("real") or "")
        return sum(c[3].values()) if c and len(c) > 3 and isinstance(c[3], dict) else None

    def _torrents_soft(self):
        """The torrent list for counters: cached a minute; {} when the client doesn't answer."""
        at, v = self._tcache
        if time.time() - at < 60 and v is not None:
            return v
        try:
            v = self._torrents()
        except TaskError:
            v = {}
        self._tcache = (time.time(), v)
        return v

    def _matcher(self, scope):
        return mp_scope.Matcher(scope, self.library.roots(), self.catalog)

    def _plan_facts(self, m, p, probe=True, upgrade=False):
        """[(root, fact, full)] of the scope's videos, fresh, for a plan (the walk is the search's, cached minutes)."""
        listing = []
        for r in self._job_roots():
            for rel, is_dir, size in self.library._names(r):
                if not is_dir and os.path.splitext(rel)[1].lower() in VIDEO_EXT and m.match(r, rel):
                    listing.append((r, rel))
        out, dsigs = [], {}
        for k, (r, rel) in enumerate(listing):
            p["progress"] = int((100 if probe else 30) * k / max(len(listing), 1))
            p["step"] = os.path.basename(rel)
            full = os.path.join(r["path"], rel)
            if probe:
                f = self._fresh_fact(r, rel, full, dsigs, upgrade=upgrade)
            else:
                try:
                    st = os.stat(full)
                except OSError:
                    continue
                with self.flock:
                    f = self.facts.get(f"{r['id']}/{rel}")
                if not f or f["size"] != st.st_size or f["mtime"] != int(st.st_mtime):
                    f = {"root_id": r["id"], "rel": rel, "size": st.st_size, "mtime": int(st.st_mtime),
                         "real": os.path.realpath(full), "ino": [st.st_dev, st.st_ino]}
            if f:
                out.append((r, f, full))
        return out

    def _encoder_choices(self):
        return [e["id"] for e in self.encoders()]

    def plan_job(self, kind, o, scope=None):
        """audio_fix, modernize (with `audio`), downscale and compress (agent 1.16+): re-encodes in the scope, shown and
        run like `shrink` (items with op "shrink"), but each kind on its own."""
        if o.get("encoder") is not None and o["encoder"] not in ENCODERS:
            raise TaskError(T("encoder must be auto, cpu or gpu", "koder to auto, cpu albo gpu"))
        if kind == "audio_fix" and (o.get("quality") or "standard") not in AAC_QUALITIES:
            raise TaskError(T("quality must be standard or high", "quality to standard albo high"))
        if kind == "modernize" and o.get("audio") not in AUDIO_MODES:
            raise TaskError(T("audio must be keep_if_ok or always_aac", "audio to keep_if_ok albo always_aac"))
        if kind == "downscale":
            try:
                o["target"] = int(o.get("target"))
            except (TypeError, ValueError):
                o["target"] = None
            if o["target"] not in TARGETS:
                raise TaskError(T("target must be 1080, 720, 480 or 360", "target to 1080, 720, 480 albo 360"))
            if o.get("remember"):
                for x in (scope or {}).get("roots") or []:
                    self.set_profile(x["root"], x["path"], TARGETS[o["target"]])
        if kind == "compress" and (o.get("quality") or "balanced") not in QUALITIES:
            raise TaskError(T("quality must be transparent, balanced or saving",
                              "quality to transparent, balanced albo saving"))
        m = self._matcher(scope)

        def run(p):
            mp_media.detect_hevc()
            enc = self.resolve_encoder(o.get("encoder"))
            choices = self._encoder_choices()
            ctx = {"torrents": self._torrents(), "enc": enc,
                   "check": time.time() + AUDIO_CHECK_BUDGET if kind == "audio_fix" else None}
            items, skipped, unchecked = [], [], 0
            est_by, work_by = {e: 0 for e in choices}, {e: 0.0 for e in choices}
            for r, f, full in self._plan_facts(m, p):
                c = self._pick(kind, f, full, o, ctx)
                if not c or c.get("ok"):
                    continue
                if c.get("pending"):
                    unchecked += 1
                    continue
                info = f["info"]
                v, dur = info["video"], info["duration"]
                base = {"op": "shrink", "root_id": r["id"], "root": r["index"], "root_name": r["name"], "path": f["rel"],
                        "name": os.path.basename(f["rel"]), "size": f["size"]}
                if c.get("skip"):
                    skipped.append(dict(base, reason=c["skip"], **({"est": c["est"]} if c.get("est") else {})))
                    continue
                est, work = c["cost"](enc)
                items.append(dict(base, est=est, work=work, codec=v.get("codec"), height=v.get("height"), duration=dur,
                                  bitrate=int(f["size"] * 8 / dur / 1000), **c["item"]))
                for e in choices:
                    b, w = c["cost"](e)
                    est_by[e] += b
                    work_by[e] = None if w is None or work_by[e] is None else work_by[e] + w
            p["progress"] = 100
            warnings = self._codec_warnings() if kind in ("downscale", "compress") else []
            if unchecked:
                warnings.insert(0, T(f"{unchecked} files haven't been checked for damaged sound yet: the server checks "
                                     f"them in the background, a later preview will include them",
                                     f"{unchecked} plików nie sprawdzono jeszcze pod kątem uszkodzonego dźwięku: serwer "
                                     f"sprawdza je w tle, późniejszy podgląd je uwzględni"))
            total = sum(i["size"] for i in items)
            title = {"audio_fix": _title("Fix sound", "Napraw dźwięk"),
                     "modernize": _title("Modernise old picture", "Unowocześnij stary obraz"),
                     "downscale": _title(f"Downscale to {o.get('target')}p", f"Zmniejsz do {o.get('target')}p"),
                     "compress": _title("Compression", "Kompresja")}[kind]
            return {"kind": kind, **title, "items": items, "bytes": total, "est": sum(i["est"] for i in items),
                    "saved": total - sum(i["est"] for i in items), "hours": round(sum(i["duration"] for i in items) / 3600, 1),
                    "encoder": self.encoder_label(enc), "encoder_id": None if kind == "audio_fix" else enc,
                    "encoders": [dict(e, est=est_by[e["id"]], saved=total - est_by[e["id"]],
                                      work_hours=None if work_by[e["id"]] is None else round(work_by[e["id"]] / 3600, 1))
                                 for e in self.encoders()],
                    "warnings": warnings, "skipped": skipped[:100], "audio_unchecked": unchecked,
                    "trash_days": self.trash_days(), "options": {k: o.get(k) for k in o}}
        return self._plan(kind, run, m)

    def _keep_order(self, keep, files, roots_by_id):
        """The copy of a duplicate group that stays first: a seeding torrent's copy (it can't be deleted here), then by
        `keep`: folder (a folder whose tag fits the title, any tagged folder), quality (height, size) or space (the disk
        with the most free room)."""
        def fits(x):
            tags = set((roots_by_id.get(x["root_id"]) or {}).get("tags") or [])
            it = x.get("title") or {}
            want = "seriale" if it.get("kind") == "series" else "bajki" if it.get("kids") else "filmy"
            return 2 if want in tags else 1 if tags else 0
        free = {}

        def room(x):
            rid = x["root_id"]
            if rid not in free:
                try:
                    free[rid] = shutil.disk_usage(roots_by_id[rid]["path"]).free
                except (OSError, KeyError):
                    free[rid] = 0
            return free[rid]
        if keep == "quality":
            key = lambda x: (not x["torrent"], -(x.get("height") or 0), -x["size"], -fits(x))  # noqa: E731
        elif keep == "space":
            key = lambda x: (not x["torrent"], -room(x), -fits(x))  # noqa: E731
        else:
            key = lambda x: (not x["torrent"], -fits(x), x["path"].count("/"), x["path"].lower())  # noqa: E731
        return sorted(files, key=key)

    def _same_titles(self, m, roots_by_id):
        """Catalog titles present more than once (the same IMDb id) in the scope: [[file dicts]]."""
        by = {}
        for key, it in m.items.items():
            r = roots_by_id.get(it.get("root_id"))
            if not r or not it.get("imdb") or not m.match(r, it["rel"]):
                continue
            by.setdefault(it["imdb"], []).append({
                "root": r["index"], "root_id": r["id"], "root_name": r["name"], "path": it["rel"], "size": it["size"],
                "height": (it.get("probe") or {}).get("height"), "title": it, "dir": bool(it.get("dir"))})
        out = []
        for g in by.values():
            # one copy inside another (a film folder and its file) is not a second copy
            g = [x for x in g if not any(y is not x and y["root_id"] == x["root_id"] and x["path"].startswith(y["path"] + "/")
                                         for y in g)]
            if len(g) > 1:
                out.append(g)
        return out

    def plan_dupes2(self, what, keep, scope=None):
        """Duplicates (agent 1.16+): `what` same_files (identical files) or same_title (the same catalog title in other
        versions); `keep` folder / quality / space chooses the copy that stays and the others become trash items;
        `manual` leaves the choice to the owner (groups only, as plan_dupes)."""
        if what not in ("same_files", "same_title"):
            raise TaskError(T("what must be same_files or same_title", "what to same_files albo same_title"))
        if keep not in ("folder", "quality", "space", "manual"):
            raise TaskError(T("keep must be folder, quality, space or manual", "keep to folder, quality, space albo manual"))
        m = self._matcher(scope)

        def run(p):
            roots_by_id = {r["id"]: r for r in self._job_roots()}
            torrents = self._torrents_soft()
            if what == "same_files":
                files = [(f"{r['id']}/{f['rel']}", full, f) for r, f, full in self._plan_facts(m, p, probe=False)
                         if f["size"] >= MIN_SIZE]
                info = {k: (f, full) for k, full, f in files}
                groups = []
                for g in self._find_dupes(files, p):
                    gs = []
                    for k in g:
                        f, full = info[k]
                        r = roots_by_id[f["root_id"]]
                        gs.append({"root": r["index"], "root_id": r["id"], "root_name": r["name"], "path": f["rel"],
                                   "size": f["size"], "height": ((f.get("info") or {}).get("video") or {}).get("height"),
                                   "title": m.title(r["id"], f["rel"]), "dir": False})
                    groups.append(gs)
            else:
                p["progress"] = 50
                groups = self._same_titles(m, roots_by_id)
            out, items, skipped = [], [], []
            for g in groups:
                for x in g:
                    x["torrent"] = _in_torrent(os.path.realpath(os.path.join(roots_by_id[x["root_id"]]["path"], x["path"])),
                                               torrents)
                g = self._keep_order(keep, g, roots_by_id)
                size = g[0]["size"]
                pub = [{k: v for k, v in dict(x, keep=(n == 0 and keep != "manual")).items() if k not in ("title",)}
                       for n, x in enumerate(g)]
                out.append({"size": size, "files": pub, "wasted": sum(x["size"] for x in g[1:])})
                if keep == "manual":
                    continue
                for x in g[1:]:
                    full = os.path.join(roots_by_id[x["root_id"]]["path"], x["path"])
                    item = {"op": "trash", "root_id": x["root_id"], "root": x["root"], "root_name": x["root_name"],
                            "path": x["path"], "name": os.path.basename(x["path"]), "size": x["size"],
                            "extra": companions(full) if not x["dir"] and os.path.isfile(full) else [],
                            "kept": g[0]["path"]}
                    if x["torrent"]:
                        skipped.append(dict(item, reason=T("still in the torrent client: remove it there",
                                                             "jest jeszcze w kliencie torrentów: usuń go tam")))
                    else:
                        items.append(item)
            out.sort(key=lambda g: -g["wasted"])
            p["progress"] = 100
            return {"kind": "dupes", **_title("Duplicates", "Duplikaty"), "what": what, "keep": keep, "groups": out,
                    "items": items, "bytes": sum(i["size"] for i in items), "wasted": sum(g["wasted"] for g in out),
                    "skipped": skipped[:100], "trash_days": self.trash_days()}
        return self._plan("dupes", run, m)

    def plan_free_space(self, root_index, amount=None, to_percent=None, order="watched_oldest", skip_kept=True):
        """Free up (agent 1.16+): deletes whole titles (top-level items) from the disk of library folder `root_index`
        to the trash, `amount` bytes or until `to_percent` % of the disk is free (default 15). `order`: watched_oldest
        (only what was streamed from this server, the longest ago first) or largest. `skip_kept`: what somebody
        streamed in the last WATCHING_DAYS days stays. Moving and re-encoding are other tasks."""
        if order not in ("watched_oldest", "largest"):
            raise TaskError(T("order must be watched_oldest or largest", "order to watched_oldest albo largest"))

        def run(p):
            roots = self.library.roots()
            if not 0 <= root_index < len(roots) or not roots[root_index]["exists"]:
                raise TaskError(T("no such library folder", "nie ma takiego katalogu biblioteki"), 404)
            dev = _dev(roots[root_index]["path"])
            u = shutil.disk_usage(roots[root_index]["path"])
            pct = to_percent if to_percent is not None else (None if amount else 15)
            want = int(amount) if amount else max(int(u.total * float(pct) / 100) - u.free, 0)
            torrents, now = self._torrents(), time.time()
            cands, skipped = [], []
            src = [r for r in self._job_roots() if _dev(r["path"]) == dev]
            for k, r in enumerate(src):
                p["progress"] = int(100 * k / max(len(src), 1))
                p["step"] = r["name"]
                try:
                    names = sorted(os.listdir(r["path"]))
                except OSError:
                    continue
                for name in names:
                    full = os.path.join(r["path"], name)
                    if name.startswith(".") or (os.path.isfile(full) and os.path.splitext(name)[1].lower() not in VIDEO_EXT):
                        continue
                    size, n = tree_size(full)
                    extra = companions(full) if os.path.isfile(full) else []
                    size += sum(tree_size(os.path.join(r["path"], e))[0] for e in extra)
                    last = self._last_played(os.path.realpath(full))
                    item = {"op": "trash", "root_id": r["id"], "root": r["index"], "root_name": r["name"], "path": name,
                            "name": name, "size": size, "files": n, "extra": extra, "played": last}
                    if self._inside(full, torrents):
                        skipped.append(dict(item, reason=_seeding_text()))
                        continue
                    if skip_kept and last and now - last < WATCHING_DAYS * 86400:
                        skipped.append(dict(item, reason=T("being watched", "jest właśnie oglądane")))
                        continue
                    if order == "watched_oldest" and not last:
                        continue  # never streamed from here: nobody watched it (as far as this server knows)
                    cands.append(item)
            cands.sort(key=(lambda i: i["played"]) if order == "watched_oldest" else (lambda i: -i["size"]))
            chosen, freed = [], 0
            for i in cands:
                if freed >= want:
                    break
                chosen.append(i)
                freed += i["size"]
            warnings = []
            if not want:
                warnings.append(T(f"This disk is already {round(100 * u.free / max(u.total, 1))}% free: nothing to do.",
                                  f"Ten dysk ma już {round(100 * u.free / max(u.total, 1))}% wolnego: nie ma nic do zrobienia."))
            elif order == "watched_oldest" and not cands:
                warnings.append(T("Nothing qualifies: only titles streamed from this server count as watched, and none "
                                  "was streamed yet (or all are being watched). Choose \"largest files\" instead.",
                                  "Nic się nie kwalifikuje: za obejrzane liczą się tylko tytuły odtwarzane z tego serwera, "
                                  "a żadnego jeszcze nie odtwarzano (albo wszystkie są właśnie oglądane). Wybierz raczej "
                                  "\"największe pliki\"."))
            elif freed < want:
                warnings.append(T(f"Only {_size_text(freed)} of {_size_text(want)} can be freed this way.",
                                  f"W ten sposób da się zwolnić tylko {_size_text(freed)} z {_size_text(want)}."))
            if chosen and self.trash_days() > 0:
                warnings.append(T(f"Deleted titles stay in the trash for {self.trash_days()} days: the space comes back "
                                  f"when the trash is emptied.",
                                  f"Usunięte tytuły leżą w koszu {self.trash_days()} dni: miejsce wróci po opróżnieniu kosza."))
            name = roots[root_index]["name"]
            return {"kind": "free", **_title(f"Free up {name}", f"Zwolnij {name}"), "need": want, "free_before": u.free,
                    "free_after": u.free + freed, "total": u.total, "order": order, "items": chosen, "bytes": freed,
                    "skipped": skipped[:50], "warnings": warnings, "trash_days": self.trash_days()}
        return self._plan("free", run)

    def plan_subs_get2(self, languages, which, order="recent", scope=None):
        """Download missing subtitles (agent 1.16+): `which` no_subs (videos without any subtitles) or no_subs_in_lang
        (missing any of `languages`), `order` recent (streamed lately, then newest files) or alpha; `new_only` stores
        `subs_auto` instead: new files get them after the background scan finds them."""
        langs = _langs(languages)
        if not langs:
            raise TaskError(T("give languages, e.g. pl,en", "podaj języki, np. pl,en"))
        if which not in ("no_subs", "no_subs_in_lang", "new_only"):
            raise TaskError(T("which must be no_subs, no_subs_in_lang or new_only", "which to no_subs, no_subs_in_lang albo new_only"))
        if order not in ("recent", "alpha"):
            raise TaskError(T("order must be recent or alpha", "order to recent albo alpha"))
        m = self._matcher(scope)
        if which == "new_only":
            self.cfg["subs_auto"] = ",".join(langs)
            self.save_cfg()

        def run(p):
            items, have = [], 0
            if which != "new_only":
                for r, f, full in self._plan_facts(m, p):
                    if mp_ident.is_extra(f["rel"]):
                        continue
                    got, any_subs = _subs_langs(f)
                    if which == "no_subs":
                        if any_subs:
                            have += 1
                            continue
                        missing = langs
                    else:
                        missing = [x for x in langs if x not in got]
                        if not missing:
                            have += 1
                            continue
                    items.append({"op": "subs_get", "root_id": r["id"], "root": r["index"], "root_name": r["name"],
                                  "path": f["rel"], "name": os.path.basename(f["rel"]), "size": 1, "langs": missing,
                                  "_played": self._last_played(f.get("real") or "") or 0, "_mtime": f["mtime"]})
            if order == "recent":
                items.sort(key=lambda i: (-i["_played"], -i["_mtime"]))
            else:
                items.sort(key=lambda i: i["path"].lower())
            for i in items:
                i.pop("_played")
                i.pop("_mtime")
            p["progress"] = 100
            warnings = []
            if which == "new_only":
                warnings.append(T("New films get subtitles by themselves after they're added.",
                                  "Nowe filmy dostaną napisy same, po dodaniu."))
            return {"kind": "subs_get", **_title("Download missing subtitles", "Pobierz brakujące napisy"),
                    "items": items, "skipped": [], "checked": have, "bytes": 0, "languages": langs, "which": which,
                    "order": order, "minutes": len(items), "subs_auto": self.cfg.get("subs_auto") or "",
                    "per_day": SUBS_PER_DAY, "days": -(-len(items) * len(langs) // SUBS_PER_DAY), "warnings": warnings}
        return self._plan("subs_get", run, m)

    def plan_subs2(self, which, sub_to_srt=True, mark_bad=False, queue_bad=False, scope=None):
        """Fit subtitles (agent 1.16+): `which` unchecked / watched_now (unchecked ones next to what was streamed in the
        last WATCHING_DAYS days) / all; `sub_to_srt` false leaves text .sub files out (fitting converts them);
        `mark_bad` moves subtitles that don't fit aside to Subtitles/ (not deleted); `queue_bad` queues a download
        task for their languages afterwards."""
        import mp_subsync
        if which not in ("unchecked", "watched_now", "all"):
            raise TaskError(T("which must be unchecked, watched_now or all", "which to unchecked, watched_now albo all"))
        m = self._matcher(scope)

        def run(p):
            if not mp_subsync.available():
                raise TaskError(T("speech detection is not installed on this computer (numpy, webrtcvad)",
                                  "na tym komputerze nie ma wykrywania mowy (numpy, webrtcvad)"))
            torrents, cache, watched = self._torrents(), {}, self._watched_dirs()
            items, checked = [], 0
            for r, f, full in self._plan_facts(m, p):
                if which == "watched_now" and os.path.dirname(f.get("real") or full) not in watched:
                    continue
                subs = []
                for x in f.get("side") or []:
                    if not x.get("fit") or (not sub_to_srt and x["name"].lower().endswith(".sub")):
                        continue
                    sp = os.path.join(os.path.dirname(full), x["name"])
                    if which != "all" and self.subsync.get(sp):
                        checked += 1
                        continue
                    subs.append({"name": x["name"], "lang": x.get("lang") or None,
                                 "locked": self.torrent_owned(sp, torrents, cache)})
                if not subs:
                    continue
                size = 0
                for x in subs:
                    try:
                        size += os.path.getsize(os.path.join(os.path.dirname(full), x["name"]))
                    except OSError:
                        pass
                items.append({"op": "subs", "root_id": r["id"], "root": r["index"], "root_name": r["name"],
                              "path": f["rel"], "name": os.path.basename(f["rel"]), "size": size, "subs": subs,
                              **({"mark_bad": True} if mark_bad else {}), **({"queue_bad": True} if queue_bad else {})})
            p["progress"] = 100
            return {"kind": "subs", **_title("Fit subtitles to the films", "Dopasuj napisy do filmów"),
                    "items": items, "skipped": [], "checked": checked, "bytes": sum(i["size"] for i in items),
                    "minutes": -(-len(items) // 2), "which": which}
        return self._plan("subs", run, m)

    # ---- folder layout (A2) and balance between disks (A3), agent 1.17+: mp_arrange computes, these plan and count

    def layout_settings(self):
        """The owner's folder layout: levels in the order tapped, age groups → kids/teens/films, and the folders they
        arranged by hand that stay as they are ([(root id, path)], None = never confirmed: all detected ones stay)."""
        keep = self.cfg.get("layout_keep")
        return {"levels": mp_arrange.norm_levels(self.cfg.get("layout_levels")) or list(mp_arrange.DEFAULT_LEVELS),
                "ages": mp_arrange.norm_ages(self.cfg.get("layout_ages")),
                "keep": None if keep is None else [(k["root_id"], k["path"]) for k in keep if k.get("root_id")]}

    def _arrange_ctx(self, m, torrents):
        """(units, roots with `writable`, roots by id, device of each root) for layout and balance."""
        roots = [dict(r, writable=os.access(r["path"], os.W_OK)) for r in self._job_roots()]
        by_id = {r["id"]: r for r in roots}
        devs = {r["id"]: _dev(r["path"]) for r in roots}
        us = mp_arrange.units(m, by_id, self.catalog, mp_i18n.lang())
        # files a waiting or running task still has to do (a paused encode among them) don't move under it
        busy = set()
        with self.lock:
            for t in self.items:
                if t["state"] in ("queued", "running"):
                    for i in t["items"]:
                        if i.get("state") in ("pending", "running") and i.get("root_id") in by_id and i.get("path"):
                            busy.add(os.path.realpath(os.path.join(by_id[i["root_id"]]["path"], i["path"])))
        if busy:
            for u in us:
                rp = os.path.realpath(u["full"])
                if any(b == rp or b.startswith(rp + os.sep) for b in busy):
                    u["busy"] = True
        return mp_arrange.group_torrents(us, by_id, torrents), roots, by_id, devs

    def _layout(self, m, torrents, levels, ages, keep=None, keep_unsure=True, keep_seeding=False):
        """(moves, skipped, stats, hand folders, keep confirmed) of a folder layout in the scope of `m`."""
        us, roots, by_id, devs = self._arrange_ctx(m, torrents)
        hand = mp_arrange.hand_folders(us, by_id)
        st = self.layout_settings()
        confirmed = keep is not None or st["keep"] is not None
        if keep is None:
            keep = st["keep"] if st["keep"] is not None else [(h["root_id"], h["path"]) for h in hand]
        moves, skipped, stats = mp_arrange.layout(us, levels, ages, keep, roots, mp_arrange.names(), keep_unsure,
                                                  keep_seeding, devs)
        kept = {tuple(k) for k in keep}
        for h in hand:
            h["keep"] = (h["root_id"], h["path"]) in kept
        return moves, skipped, stats, hand, confirmed

    @staticmethod
    def _unit_item(u, dr, sub, **extra):
        """A move item of a whole title (a folder or a file with the files that belong to it)."""
        item = {"op": "move", "root_id": u["root_id"], "root": u["root"], "root_name": u["root_name"], "path": u["rel"],
                "name": u.get("title") or os.path.basename(u["rel"]), "size": u["size"],
                "to_root_id": dr["id"], "to_root": dr["index"], "to_name": dr["name"], "to_sub": sub}
        if not u["dir"] and u.get("torrent") is None:
            item["extra"] = companions(u["full"])
        if u.get("torrent") is not None:
            item["torrent"] = u["torrent"]
        if u.get("members"):
            item["titles"] = len(u["members"])
        item.update(extra)
        return item

    def plan_layout(self, o, scope=None):
        """Folder layout (A2): `levels`, `ages` ({"0": "kids", …}), `keep` (the hand-arranged folders to leave alone,
        [{root, path}]; given = confirmed and remembered), `keep_unsure` (titles without a sure recognition stay where
        they are; false = in the preview, unticked), `seeding` ("move": a seeding torrent moves through its client,
        whole, "skip"), `remember` (levels and ages become the layout's settings). Every title stays on its disk."""
        levels = mp_arrange.norm_levels(o.get("levels")) if o.get("levels") is not None else self.layout_settings()["levels"]
        if not levels:
            raise TaskError(T("choose at least one level", "wybierz przynajmniej jeden poziom"))
        ages = mp_arrange.norm_ages(o.get("ages")) if o.get("ages") is not None else self.layout_settings()["ages"]
        keep = None
        if o.get("keep") is not None:
            idx = {r["index"]: r for r in self.library.roots()}
            keep = [(idx[int(k["root"])]["id"], str(k.get("path") or "").strip("/")) for k in o["keep"]
                    if str(k.get("root", "")).lstrip("-").isdigit() and int(k["root"]) in idx]
            self.cfg["layout_keep"] = [{"root_id": a, "path": b} for a, b in keep]
        if o.get("remember"):
            self.cfg["layout_levels"], self.cfg["layout_ages"] = levels, ages
        if keep is not None or o.get("remember"):
            self.save_cfg()
        keep_unsure = o.get("keep_unsure", True) is not False
        keep_seeding = (o.get("seeding") or "move") == "skip"
        m = self._matcher(scope)

        def run(p):
            moves, skipped, stats, hand, confirmed = self._layout(m, self._torrents(), levels, ages, keep, keep_unsure,
                                                                 keep_seeding)
            items = []
            for u, dr, sub in moves:
                items.append(self._unit_item(u, dr, sub, prune=True, same_disk=True, unsure=bool(u.get("unsure")),
                                             why="/".join(x for x in (dr["name"], sub) if x)))
            p["progress"] = 100
            return {"kind": "layout", **_title("Folder layout", "Układ folderów"), "items": items,
                    "bytes": sum(i["size"] for i in items), "levels": levels, "ages": ages, "confirmed": confirmed,
                    "hand_folders": hand, "stats": stats,
                    "skipped": [{"root_id": u["root_id"], "root": u["root"], "root_name": u["root_name"], "path": u["rel"],
                                 "name": u.get("title") or os.path.basename(u["rel"]), "size": u["size"],
                                 "reason": mp_arrange.reason(k)} for u, k in skipped if k != "kept"][:200],
                    "eta": len(items), "trash_days": self.trash_days()}
        return self._plan("layout", run, m)

    def _balance_disks(self, roots, devs):
        out = {}
        for r in roots:
            d = devs.get(r["id"])
            if d is None or d in out or not r.get("writable", True):
                continue
            try:
                u = shutil.disk_usage(r["path"])
            except OSError:
                continue
            out[d] = {"name": os.path.basename(_mount(r["path"]).rstrip("/")) or _mount(r["path"]), "total": u.total,
                      "free": u.free}
        return out

    def _watched_paths(self):
        """Real paths watched now or streamed in the last WATCHING_DAYS (balance leaves them where they are)."""
        cut = time.time() - WATCHING_DAYS * 86400
        with self.flock:
            out = {p for p, t in self.played.items() if t > cut}
        out |= {os.path.realpath(w["path"]) for w in self.watching() if w.get("path")}
        return out

    def _balance(self, goal, rules, torrents):
        us, roots, by_id, devs = self._arrange_ctx(self._matcher(None), torrents)
        disks = self._balance_disks(roots, devs)
        moves, skipped, after = mp_arrange.balance(goal, us, disks, roots, devs, rules, self._watched_paths())
        return moves, skipped, disks, after

    @staticmethod
    def _disk_rows(disks, after):
        return [{"name": d["name"], "total": d["total"], "free": d["free"], "free_after": after.get(k, d["free"]),
                 "pct": round(100 * d["free"] / max(d["total"], 1)), "pct_after": round(100 * after.get(k, d["free"]) / max(d["total"], 1))}
                for k, d in sorted(disks.items(), key=lambda kv: kv[1]["name"])]

    def plan_balance(self, o):
        """Balance between disks (A3), whole computer only: `goal` (even, kind_per_disk, fill_newer) and `rules`
        (skip_watching, seeding: a seeding torrent moves whole through its client, backup: never onto the disk of a
        folder's replica). Whole titles move into the folder with the same tag on the other disk, same subfolder."""
        goal = o.get("goal") or "even"
        if goal not in mp_arrange.GOALS:
            raise TaskError(T("goal must be even, kind_per_disk or fill_newer", "goal to even, kind_per_disk albo fill_newer"))
        rules = {k: bool(v) for k, v in (o.get("rules") or {}).items() if k in ("skip_watching", "seeding", "backup")}

        def run(p):
            moves, skipped, disks, after = self._balance(goal, rules, self._torrents())
            if len(disks) < 2:
                raise TaskError(T("the library is on one disk only", "biblioteka jest tylko na jednym dysku"))
            items = [self._unit_item(u, dr, sub, same_disk=False, why=dr["name"]) for u, dr, sub in moves]
            p["progress"] = 100
            total = sum(i["size"] for i in items)
            return {"kind": "balance", **_title("Balance between disks", "Rozkład na dyski"), "items": items,
                    "bytes": total, "goal": goal, "rules": rules, "disks": self._disk_rows(disks, after),
                    "eta": int(total / mp_arrange.COPY_SPEED),
                    "skipped": [{"root_id": u["root_id"], "root": u["root"], "root_name": u["root_name"], "path": u["rel"],
                                 "name": u.get("title") or os.path.basename(u["rel"]), "size": u["size"],
                                 "reason": mp_arrange.reason(k)} for u, k in skipped][:200]}
        return self._plan("balance", run)

    # ---- ready for Chromecast (B5), agent 1.17+: an AAC track next to the original sound, for good, in the file

    def _cast_need(self, f, o, torrents):
        """What cast_ready would do with one video: None (not for it: no fact yet, a picture Chromecast can't take, or
        nothing to do), {"pending": True} (its subtitle tracks weren't probed with codecs yet) or the item fields."""
        info = f.get("info") or {}
        v = info.get("video")
        if not v or not info.get("duration") or f["size"] < MIN_SIZE:
            return None
        if not ((v.get("codec") == "h264" and v.get("bits", 8) == 8 and (v.get("height") or 0) <= 1088)
                or (v.get("codec") == "hevc" and v.get("bits", 8) == 8)):
            return None  # the picture itself needs converting: "Prepare" does that per showing
        audio = info.get("audio") or []
        a = mp_media.pick_audio({"audio": audio}) if audio else None
        reasons, out = [], {}
        if o.get("add_aac", True) and a and a.get("codec") not in mp_media.AUDIO_OK \
                and not any(x.get("codec") in mp_media.AUDIO_OK and (x.get("lang") or "") == (a.get("lang") or "") for x in audio):
            ch = a.get("channels") or 0
            chs = {1: "mono", 2: "stereo", 6: "5.1", 7: "6.1", 8: "7.1"}.get(ch, f"{ch} ch" if ch else "")
            name = f"{(a.get('codec') or '?').upper()}{' ' + chs if chs else ''}"
            reasons.append(T(f"{name} sound", f"dźwięk {name}"))
            out.update(add_aac=True, aac_from=audio.index(a), aac_lang=a.get("lang") or "", audio_codec=a.get("codec"))
        subs = info.get("subs") or []
        if o.get("subs_to_srt", True) and subs:
            if any(not isinstance(s, dict) for s in subs):
                return {"pending": True}
            have = {x.get("lang") for x in f.get("side") or [] if x.get("lang")}
            want = []
            for n, s in enumerate(subs):
                code = _lang2(s.get("lang")) or ""
                if s.get("codec") in TEXT_SUBS and code and code not in have and code not in {w["lang"] for w in want}:
                    want.append({"n": n, "lang": code})
            if want:
                reasons.append(T("subtitles in the file", "napisy w pliku"))
                out["subs"] = want
        if o.get("avi_to_mkv", True) and "avi" in (info.get("container") or "").lower():
            reasons.append(T("AVI container", "kontener AVI"))
            out["avi"] = True
        if not reasons:
            return None
        seeding = _in_torrent(f.get("real") or "", torrents)
        if seeding:  # a seeding file stays byte for byte: only its subtitles can be written next to it
            if not out.get("subs"):
                return {"skip": _seeding_text()}
            out = {"subs": out["subs"], "seeding": True}
            reasons = [T("subtitles in the file", "napisy w pliku")]
        remux = bool(out.get("add_aac") or out.get("avi"))
        return dict(out, why=" · ".join(reasons), est=int(f["size"] * 1.03) if remux else f["size"],
                    work=(f["size"] / CAST_SPEED if remux else 0) + 5 * len(out.get("subs") or []))

    def plan_cast_ready(self, o, scope=None):
        """Ready for Chromecast (B5): `add_aac` (an AAC stereo track first, the original kept), `subs_to_srt` (text
        subtitle tracks written next to the file as .srt), `avi_to_mkv` (repacked, not re-encoded). The picture is
        never touched; the result is an .mkv and the original goes to the trash."""
        opts = {k: o.get(k, True) is not False for k in ("add_aac", "subs_to_srt", "avi_to_mkv")}
        if not any(opts.values()):
            raise TaskError(T("choose at least one action", "wybierz przynajmniej jedną czynność"))
        m = self._matcher(scope)

        def run(p):
            torrents = self._torrents()
            items, skipped, unchecked = [], [], 0
            for r, f, full in self._plan_facts(m, p, upgrade=True):
                c = self._cast_need(f, opts, torrents)
                if not c:
                    continue
                base = {"op": "cast_ready", "root_id": r["id"], "root": r["index"], "root_name": r["name"],
                        "path": f["rel"], "name": os.path.basename(f["rel"]), "size": f["size"]}
                if c.get("pending"):
                    unchecked += 1
                elif c.get("skip"):
                    skipped.append(dict(base, reason=c["skip"]))
                else:
                    it = m.title(r["id"], f["rel"]) or {}
                    items.append(dict(base, title=it.get("title"), series=it.get("kind") == "series", **c))
            p["progress"] = 100
            total = sum(i["size"] for i in items)
            return {"kind": "cast_ready", **_title("Ready for Chromecast", "Pod Chromecasta"), "items": items,
                    "bytes": total, "est": sum(i["est"] for i in items), "saved": total - sum(i["est"] for i in items),
                    "eta": int(sum(i["work"] for i in items)), "options": opts, "skipped": skipped[:100],
                    "by_title": self._cast_titles(items), "trash_days": self.trash_days(),
                    "warnings": [T(f"{unchecked} files weren't checked yet", f"{unchecked} plików jeszcze nie sprawdzono")]
                    if unchecked else []}
        return self._plan("cast_ready", run, m)

    @staticmethod
    def _cast_titles(entries):
        """Files grouped by title for the screen: a series once, as "Title, N episodes"."""
        out = {}
        for e in entries:
            key = (e.get("title") or e["name"], bool(e.get("series")))
            g = out.setdefault(key, {"title": key[0], "series": key[1], "files": 0, "why": e["why"], "size": 0})
            g["files"] += 1
            g["size"] += e["size"]
        return sorted(out.values(), key=lambda g: -g["size"])

    def _cast_ready(self, i, progress, t):
        root = self._root_path(i["root_id"])
        full = os.path.join(root, i["path"])
        if not os.path.exists(full):
            raise TaskError(T("the file no longer exists", "pliku już nie ma"))
        st = os.stat(full)
        if st.st_size != i["size"]:
            raise TaskError(T("the file changed since the preview", "plik zmienił się od podglądu"))
        stem = os.path.splitext(full)[0]
        info = mp_media.probe(full)
        wrote = []
        for s in i.get("subs") or []:  # subtitles next to the file first: they never replace a file that's there
            out = f"{stem}.{s['lang']}.srt"
            if os.path.exists(out):
                continue
            r = subprocess.run(_nice(["ffmpeg", "-hide_banner", "-nostdin", "-v", "error", "-n", "-i", full,
                                      "-map", f"0:s:{s['n']}", "-c:s", "srt", out]), capture_output=True, text=True)
            if r.returncode != 0 or not os.path.exists(out) or os.path.getsize(out) == 0:
                _rm(out)
                self.log(f"cast_ready: {os.path.basename(full)}: subtitles {s['lang']}: {r.stderr.strip()[-200:]}")
            else:
                wrote.append(os.path.basename(out))
            self._check_cancel()
        if wrote:
            i["subs_written"] = wrote
        if not (i.get("add_aac") or i.get("avi")):
            progress(i["size"])
            return
        if self._inside(full, self._torrents()):
            raise TaskError(T("the file is back in the torrent client", "plik jest z powrotem w kliencie torrentów"))
        need = int(st.st_size * 1.05) + (256 << 20)
        if shutil.disk_usage(os.path.dirname(full)).free < need:
            self._relieve(root, need - shutil.disk_usage(os.path.dirname(full)).free)
        if shutil.disk_usage(os.path.dirname(full)).free < need:
            raise TaskError(T(f"not enough room on the disk: about {need >> 20} MB needed",
                              f"za mało miejsca na dysku: potrzeba ok. {need >> 20} MB"))
        part = os.path.join(os.path.dirname(full), f".{os.path.basename(stem)}.mp-cast.part.mkv")
        out_final = stem + ".mkv"
        n_audio = len(info.get("audio") or [])
        cmd = ["ffmpeg", "-hide_banner", "-nostdin", "-y", "-v", "error", "-progress", "pipe:1"]
        if i.get("avi"):
            cmd += ["-fflags", "+genpts"]
        cmd += ["-i", full, "-map", "0:v:0"]
        if i.get("add_aac"):
            cmd += ["-map", f"0:a:{i['aac_from']}"]
        cmd += ["-map", "0:a?", "-map", "0:s?", "-map", "0:t?", "-map_chapters", "0", "-c", "copy"]
        if any(s.get("codec") == "mov_text" for s in info.get("subs") or []):
            cmd += ["-c:s", "srt"]
        if i.get("add_aac"):
            cmd += ["-c:a:0", "aac", "-b:a:0", "192k", "-ac:a:0", "2", "-metadata:s:a:0", "title=AAC stereo",
                    "-disposition:a", "0", "-disposition:a:0", "default"]
            if i.get("aac_lang"):
                cmd += ["-metadata:s:a:0", f"language={i['aac_lang']}"]
        cmd.append(part)
        _rm(part)
        self.log("cast_ready:", os.path.basename(full), "aac" if i.get("add_aac") else "", "avi→mkv" if i.get("avi") else "")
        p = subprocess.Popen(_nice(cmd), stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        with self.lock:
            self.proc = p
        dur = info.get("duration") or 0
        err = []
        threading.Thread(target=lambda: err.extend(p.stderr.read().splitlines()[-20:]), daemon=True).start()
        try:
            for line in p.stdout:
                if line.startswith("out_time_us=") and dur:
                    us = line.split("=", 1)[1].strip()
                    if us.isdigit():
                        progress(int(i["size"] * min(int(us) / 1e6 / dur, 1)))
                if self.cancel_ev.is_set():
                    p.terminate()
                    break
            p.wait()
        finally:
            with self.lock:
                self.proc = None
        if self.cancel_ev.is_set():
            _rm(part)
            raise _Cancelled()
        if p.returncode != 0:
            _rm(part)
            raise TaskError(_ffmpeg_reason(err))
        try:
            ninfo = mp_media.probe(part)
        except Exception as e:  # noqa: BLE001
            _rm(part)
            raise TaskError(T(f"the result can't be read: {e}", f"wyniku nie da się odczytać: {e}"))
        got = ninfo.get("duration") or 0
        if abs(got - dur) > max(2.0, dur * 0.005) and abs(got - _readable_length(full)) > max(2.0, got * 0.005):
            _rm(part)
            raise TaskError(T("the result has a different length, the original was kept", "wynik ma inną długość, oryginał został"))
        if len(ninfo.get("audio") or []) < n_audio + (1 if i.get("add_aac") else 0) \
                or (i.get("add_aac") and (ninfo["audio"][0].get("codec") != "aac")):
            _rm(part)
            raise TaskError(T("the result lacks a sound track, the original was kept", "w wyniku brakuje ścieżki dźwięku, oryginał został"))
        real = os.path.realpath(full)
        while any(os.path.realpath(w["path"]) == real for w in self.watching()):
            t["hold"] = "in_use"
            if self.cancel_ev.wait(5):
                t["hold"] = None
                _rm(part)
                raise _Cancelled()
        t["hold"] = None
        new = os.path.getsize(part)
        if self.trash_days() > 0:
            self._to_trash(full, root, i)
        else:
            os.remove(full)
        os.rename(part, out_final)
        mp_media.remove_variant(full)
        i.update(new_size=new, new_path=os.path.relpath(out_final, root), grew=new - st.st_size)
        mp_library.forget_names()
        self.log(f"cast_ready done: {os.path.basename(full)} {st.st_size >> 20} → {new >> 20} MB")

    # ---- counters for the task list (GET /api/library/jobs): only what the background scans know

    def jobs(self, scope=None, languages="pl,en", levels=None):
        """`levels` (1.17+): the folder layout counter with these levels (the A2 screen while the owner taps them)."""
        m = self._matcher(scope)
        roots = {r["id"]: r for r in self._job_roots()}
        with self.flock:
            facts = list(self.facts.items())
            listing = list(self.facts_list or [])
            groups = list(self.dupe_groups)
            at = self.facts_at
        inscope = [(k, roots[f["root_id"]], f) for k, f in facts if f["root_id"] in roots and m.match(roots[f["root_id"]], f["rel"])]
        keys = {k for k, _, _ in inscope}
        known = {k for k, _ in facts}
        lst = [(a, b, s) for a, b, s in listing if a in roots and m.match(roots[a], b)]
        pending = sum(1 for a, b, _ in lst if f"{a}/{b}" not in known)
        torrents = self._torrents_soft()
        enc = self.resolve_encoder(None)
        choices = self._encoder_choices()
        ctx = {"torrents": torrents, "enc": enc, "check": None}
        hours = lambda s: None if s is None else int(s)  # noqa: E731
        jobs = {}

        def count(kind, o, examples=None):
            n = size = saving = 0
            work = {e: 0.0 for e in choices}
            waiting = 0
            for k, r, f in inscope:
                full = os.path.join(r["path"], f["rel"])
                c = self._pick(kind, f, full, o, ctx)
                if not c or c.get("ok") or c.get("skip"):
                    continue
                if c.get("pending"):
                    waiting += 1
                    continue
                n += 1
                size += f["size"]
                saving += f["size"] - c["cost"](enc)[0]
                for e in choices:
                    w = c["cost"](e)[1]
                    work[e] = None if w is None or work[e] is None else work[e] + w
                if examples is not None:
                    examples.append((f, c))
            return {"count": n, "size": size, "saving": max(saving, 0), "eta": hours(work.get(enc)),
                    "eta_by": {e: hours(work[e]) for e in choices}, "waiting": waiting}

        def job(applicable=True, count=0, size=0, saving=0, eta=0, why=None, pending=0, options=None):
            return {"applicable": applicable, "count": count, "size": size, "saving": saving, "eta": eta,
                    "why": why, "pending": pending, "options": options or {}}
        whole_only = T("whole disks only: on the list for the whole computer", "tylko całe dyski: na liście dla całego komputera")

        # free up: whole disks
        if not m.whole:
            jobs["free"] = job(False, why=whole_only)
        else:
            low, need, seen = 0, 0, set()
            for r in roots.values():
                d = _dev(r["path"])
                if d in seen:
                    continue
                seen.add(d)
                try:
                    u = shutil.disk_usage(r["path"])
                except OSError:
                    continue
                if u.free < u.total * 0.15:
                    low += 1
                    need += int(u.total * 0.15) - u.free
            jobs["free"] = job(count=low, size=need, saving=need,
                               why=None if low else T("every disk has more than 15% free", "każdy dysk ma ponad 15% wolnego"))

        # duplicates
        sf_groups, sf_saving, seeding = 0, 0, 0
        size_of = {k: f for k, _, f in inscope}
        for g in groups:
            g = [k for k in g if k in keys]
            if len(g) < 2:
                continue
            sf_groups += 1
            sf_saving += size_of[g[0]]["size"] * (len(g) - 1)
            seeding += sum(1 for k in g if _in_torrent(size_of[k].get("real") or "", torrents))
        st = self._same_titles(m, roots)
        st_saving = sum(sum(x["size"] for x in g) - max(x["size"] for x in g) for g in st)
        jobs["dupes"] = job(count=sf_groups, size=sf_saving, saving=sf_saving, pending=pending,
                            why=None if sf_groups else T("no identical files found", "nie znaleziono identycznych plików"),
                            options={"same_files": {"groups": sf_groups, "saving": sf_saving},
                                     "same_title": {"titles": len(st), "saving": st_saving}, "seeding": seeding})

        # fix sound
        ex = []
        c = count("audio_fix", {}, ex)
        suspect = [f for _, _, f in inscope if (f.get("info") or {}).get("video") and (f["info"].get("duration") or 0)
                   and f["size"] >= MIN_SIZE and not is_old(f["info"]["video"])
                   and any(a.get("codec") in AUDIO_SUSPECT for a in f["info"].get("audio") or [])]
        checked = sum(1 for f in suspect if self._acheck_cached(f) is not None)
        jobs["audio_fix"] = job(count=c["count"], size=c["size"], saving=0, eta=c["eta"] if c["count"] else 0,
                                pending=len(suspect) - checked + pending,
                                why=None if c["count"] else (T("still being checked in the background", "jeszcze sprawdzane w tle")
                                                             if len(suspect) > checked else T("no damaged sound found", "nie znaleziono uszkodzonego dźwięku")),
                                options={"checked": checked, "total": len(suspect),
                                         "examples": [{"name": os.path.basename(f["rel"]), "codec": ", ".join(x["item"]["audio_codecs"]),
                                                       "errors": x["item"]["errors"]} for f, x in ex[:3]]})

        # old picture
        ex = []
        c = count("modernize", {"audio": "keep_if_ok"}, ex)
        jobs["modernize"] = job(count=c["count"], size=c["size"], saving=c["saving"], eta=c["eta"] if c["count"] else 0,
                                pending=pending, why=None if c["count"] else T("no old formats found", "nie znaleziono starych formatów"),
                                options={"examples": [{"name": os.path.basename(f["rel"]), "format": _format_name(f["info"]["video"]),
                                                       "size": f["size"]} for f, _ in ex[:3]],
                                         "eta_gpu": c["eta_by"].get("gpu") if c["count"] else (0 if "gpu" in choices else None),
                                         "eta_cpu": c["eta_by"].get("cpu") if c["count"] else 0})

        # downscale: per target, and per folder
        by_target, folders, sizes = {}, {}, {}
        for t in TARGETS:
            ex = []
            c = count("downscale", {"target": t}, ex)
            by_target[str(t)] = {"count": c["count"], "saving": c["saving"], "eta": c["eta"] if c["count"] else 0}
            sizes[t] = c["size"]
            for f, x in ex:
                r = roots[f["root_id"]]
                top = f["rel"].split("/", 1)[0] if "/" in f["rel"] else ""
                d = folders.setdefault((r["index"], top), {"root": r["index"], "path": top, "name": top or r["name"],
                                                          "files": {}, "saving_by_target": {str(x): 0 for x in TARGETS}})
                d["saving_by_target"][str(t)] += max(f["size"] - x["cost"](enc)[0], 0)
        for k, r, f in inscope:  # the resolution mix of the folders that have something to downscale
            top = f["rel"].split("/", 1)[0] if "/" in f["rel"] else ""
            d = folders.get((r["index"], top))
            v = (f.get("info") or {}).get("video")
            if d is not None and v:
                cls = _res_label(_res_class(v))
                d["files"][cls] = d["files"].get(cls, 0) + 1
        flist = []
        for d in sorted(folders.values(), key=lambda d: -max(d["saving_by_target"].values())):
            order = sorted(d.pop("files").items(), key=lambda kv: -_RES_ORDER.get(kv[0], 0))
            flist.append(dict(d, mix=" · ".join(f"{k} {n}" for k, n in order)))
        top = by_target["1080"]
        jobs["downscale"] = job(count=top["count"], size=sizes[1080], saving=top["saving"], eta=top["eta"], pending=pending,
                                why=None if any(x["count"] for x in by_target.values()) else T("nothing bigger than 360p to downscale", "nic większego niż 360p do zmniejszenia"),
                                options={"by_target": by_target, "folders": flist[:50]})

        # compression (default filters)
        by_q = {}
        for q in QUALITIES:
            c = count("compress", {"quality": q})
            by_q[q] = {"count": c["count"], "saving": c["saving"], "eta": c["eta"] if c["count"] else 0, "size": c["size"],
                       "need": None}
        ex = []
        bal = count("compress", {"quality": "balanced"}, ex)
        need_free = sum(x["cost"](enc)[0] for _, x in ex)
        for q in by_q.values():
            q.pop("need")
            q.pop("size")
        jobs["compress"] = job(count=bal["count"], size=bal["size"], saving=bal["saving"], eta=bal["eta"] if bal["count"] else 0,
                               pending=pending, why=None if bal["count"] else T("no big files to compress", "brak dużych plików do kompresji"),
                               options={"by_quality": by_q, "need_free": need_free})

        # download subtitles
        langs = _langs(languages) or ["pl", "en"]
        no_subs = no_lang = have = 0
        for k, r, f in inscope:
            if mp_ident.is_extra(f["rel"]) or "side" not in f:
                continue
            got, any_subs = _subs_langs(f)
            no_subs += not any_subs
            if all(x in got for x in langs):
                have += 1
            else:
                no_lang += 1
        subs_ok = self.subs_ok() if self.subs_ok else True
        jobs["subs_get"] = job(applicable=subs_ok, count=no_lang, eta=no_lang * 60, pending=pending,
                               why=(None if no_lang else T("every film has subtitles in these languages",
                                                           "każdy film ma napisy w tych językach")) if subs_ok else
                               T("subtitle search isn't installed on this computer", "na tym komputerze nie ma wyszukiwania napisów"),
                               options={"no_subs": no_subs, "no_subs_in_lang": no_lang, "have": have, "per_day": SUBS_PER_DAY,
                                        "languages": ",".join(langs)})

        # fit subtitles
        import mp_subsync
        fit_ok = mp_subsync.available()
        watched = self._watched_dirs()
        unchecked = watched_now = every = fitted = 0
        for k, r, f in inscope:
            full = os.path.join(r["path"], f["rel"])
            subs = [x for x in f.get("side") or [] if x.get("fit")]
            if not subs:
                continue
            every += 1
            done = [bool(self.subsync and self.subsync.get(os.path.join(os.path.dirname(full), x["name"]))) for x in subs]
            fitted += sum(done)
            if not all(done):
                unchecked += 1
                if os.path.dirname(f.get("real") or full) in watched:
                    watched_now += 1
        jobs["subs"] = job(applicable=fit_ok, count=unchecked, size=0, eta=unchecked * 30, pending=pending,
                           why=(None if unchecked else T("every subtitle file was checked", "wszystkie napisy już sprawdzono"))
                           if fit_ok else T("speech detection is not installed on this computer (numpy, webrtcvad)",
                                            "na tym komputerze nie ma wykrywania mowy (numpy, webrtcvad)"),
                           options={"unchecked": unchecked, "watched_now": watched_now, "all": every, "fitted_before": fitted})

        # catalog check
        total = recog = 0
        for key, it in m.items.items():
            r = roots.get(it.get("root_id"))
            if not r or not m.match(r, it.get("file") or it["rel"]):
                continue
            total += 1
            recog += bool(it.get("imdb"))
        jobs["tags"] = job(count=total - recog, pending=0,
                           why=None if total - recog else T("every title is recognised", "wszystkie tytuły rozpoznane"),
                           options={"recognised": recog, "total": total})

        # folder layout (1.17+): titles that would move, with the settings or the levels being chosen
        lset = self.layout_settings()
        lv = mp_arrange.norm_levels(levels) if levels is not None else lset["levels"]
        moves, skipped, stats, hand, confirmed = self._layout(m, torrents, lv or lset["levels"], lset["ages"])
        tree = {}
        for u, dr, sub in moves:
            k = "/".join(x for x in ([dr["name"]] + sub.split("/")[:1]) if x)
            tree[k] = tree.get(k, 0) + 1
        jobs["layout"] = job(count=len(moves), size=sum(u["size"] for u, _, _ in moves), eta=len(moves),
                             why=None if moves else T("every title is in its place", "każdy tytuł jest na swoim miejscu"),
                             options={"levels": lset["levels"], "ages": lset["ages"], "confirmed": confirmed,
                                      "hand_folders": hand[:50], "unsure": stats["unsure"], "seeding": stats["seeding"],
                                      "kept": stats["kept"], "tree": [{"path": k, "titles": n} for k, n in sorted(tree.items())][:30]})

        # balance between disks (1.17+): the whole computer, like free up
        if not m.whole:
            jobs["balance"] = job(False, why=whole_only)
        else:
            by_goal, rows = {}, None
            for g in mp_arrange.GOALS:
                bm, _, disks, after = self._balance(g, None, torrents)
                rows = rows or self._disk_rows(disks, {})
                b = sum(u["size"] for u, _, _ in bm)
                by_goal[g] = {"count": len(bm), "size": b, "eta": int(b / mp_arrange.COPY_SPEED),
                              "disks": self._disk_rows(disks, after)}
            ev = by_goal["even"]
            many = rows is not None and len(rows) >= 2
            jobs["balance"] = job(applicable=many, count=ev["count"], size=ev["size"], eta=ev["eta"],
                                  why=(None if ev["count"] else T("the disks are balanced", "dyski są wyrównane")) if many
                                  else T("the library is on one disk only", "biblioteka jest tylko na jednym dysku"),
                                  options={"disks": rows or [], "by_goal": by_goal})

        # ready for Chromecast (1.17+)
        cr, cr_wait = [], 0
        for k, r, f in inscope:
            c = self._cast_need(f, {}, torrents)
            if not c or c.get("skip"):
                continue
            if c.get("pending"):
                cr_wait += 1
                continue
            it = m.title(r["id"], f["rel"]) or {}
            cr.append(dict(c, name=os.path.basename(f["rel"]), title=it.get("title"), series=it.get("kind") == "series",
                           size=f["size"]))
        groups = self._cast_titles(cr)
        jobs["cast_ready"] = job(count=len(cr), size=sum(x["size"] for x in cr),
                                 saving=-sum(x["est"] - x["size"] for x in cr), eta=int(sum(x["work"] for x in cr)),
                                 pending=pending + cr_wait,
                                 why=None if cr else T("every file plays on a Chromecast as it is", "każdy plik gra na Chromecaście tak, jak jest"),
                                 options={"by_title": groups[:3], "more_files": sum(g["files"] for g in groups[3:]),
                                          "by_reason": {"aac": sum(1 for x in cr if x.get("add_aac")),
                                                        "subs": sum(1 for x in cr if x.get("subs")),
                                                        "avi": sum(1 for x in cr if x.get("avi"))},
                                          "growth": sum(x["est"] - x["size"] for x in cr)})

        # trash: whole disks
        if not m.whole:
            jobs["trash"] = job(False, why=whole_only, options={"days": self.trash_days()})
        else:
            tr = self._trash_list()
            tsize, tfiles = sum(x["size"] for x in tr), sum(x["files"] for x in tr)
            jobs["trash"] = job(count=tfiles, size=tsize, saving=tsize, why=None if tfiles else T("the trash is empty", "kosz jest pusty"),
                                options={"days": self.trash_days()})
        return {"scope_label": m.label(mp_i18n.lang()), "files": len(lst) if listing else len(inscope),
                "size": sum(s for _, _, s in lst) if listing else sum(f["size"] for _, _, f in inscope),
                "checked_at": int(at) if at else None, "pending": pending, "jobs": jobs}

    # ---- tasks created without a preview (the owner's "by itself" settings for new files)

    def _auto_new(self, keys):
        roots = {r["id"]: r for r in self._job_roots()}
        with self.flock:
            facts = [(k, self.facts.get(k)) for k in keys]
        facts = [(roots[f["root_id"]], f) for k, f in facts if f and f["root_id"] in roots]
        if self.cfg.get("audio_fix_auto"):
            ctx = {"torrents": self._torrents_soft(), "enc": "cpu", "check": True}
            items = []
            for r, f in facts:
                c = self._pick("audio_fix", f, os.path.join(r["path"], f["rel"]), {"quality": "standard"}, ctx)
                if c and c.get("item"):
                    info = f["info"]
                    items.append({"op": "shrink", "root_id": r["id"], "root": r["index"], "root_name": r["name"],
                                  "path": f["rel"], "name": os.path.basename(f["rel"]), "size": f["size"], "est": f["size"],
                                  "codec": info["video"].get("codec"), "height": info["video"].get("height"),
                                  "duration": info["duration"], "bitrate": int(f["size"] * 8 / info["duration"] / 1000),
                                  **c["item"]})
            if items:
                self.log(f"jobs: fixing the sound of {len(items)} new files by itself")
                self._add_task({"kind": "audio_fix", **_title("Fix sound (new files)", "Napraw dźwięk (nowe pliki)")}, items)
        langs = _langs(self.cfg.get("subs_auto") or "")
        if langs and self.fetch_subs:
            items = []
            for r, f in facts:
                if mp_ident.is_extra(f["rel"]):
                    continue
                got, _ = _subs_langs(f)
                missing = [x for x in langs if x not in got]
                if missing:
                    items.append({"op": "subs_get", "root_id": r["id"], "root": r["index"], "root_name": r["name"],
                                  "path": f["rel"], "name": os.path.basename(f["rel"]), "size": 1, "langs": missing})
            if items:
                self.log(f"jobs: downloading subtitles for {len(items)} new files by itself")
                self._add_task({"kind": "subs_get", **_title("Download subtitles (new files)", "Pobierz napisy (nowe pliki)")}, items)


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
                    os.rename(a, free_name(os.path.join(dest, name)))
                elif os.path.isdir(a):
                    shutil.rmtree(a)
                else:
                    os.remove(a)
        mp_media.remove_variant(full)

    # ---- shrinking

    def _drop_job(self, i):
        """An item's detached encode (one paused or left by a restart) stopped and forgotten."""
        j = mp_detached.Detached.find(self.encodes, i.pop("job", None))
        if j:
            _signal(j, "SIGCONT")
            j.terminate()
            self._drop_part(j)
            j.remove()

    def _shrink(self, i, progress, t):
        root = self._root_path(i["root_id"])
        full = os.path.join(root, i["path"])
        if not os.path.exists(full):
            self._drop_job(i)
            raise TaskError(T("the file no longer exists", "pliku już nie ma"))
        st = os.stat(full)
        if st.st_size != i["size"]:
            self._drop_job(i)
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
            # the reserve: an item that would leave the disk under it starts only when the original can go to another
            # disk's trash afterwards (otherwise a long run creeps the disk towards full, item by item)
            u = shutil.disk_usage(os.path.dirname(full))
            if self.trash_days() > 0 and u.free - need < int(u.total * RESERVE) \
                    and self._spare_disk(_dev(root), st.st_size) is None:
                raise TaskError(T(f"the disk would go under its {int(RESERVE * 100)}% reserve and no other library disk "
                                  f"has room for the original in its trash",
                                  f"dysk zszedłby poniżej rezerwy {int(RESERVE * 100)}%, a żaden inny dysk biblioteki "
                                  f"nie ma miejsca na oryginał w koszu"))
        info = mp_media.probe(full)
        pr = _item_profile(i)
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
            # an audio track copied as is must decode cleanly: damaged MP3 frames in an old AVI ("Header missing")
            # are skipped by ffmpeg but stop a phone's decoder, and the copied track kept the whole film silent there
            if i.get("audio_fix"):  # found by the preview (the file is unchanged since: same size)
                broken = set(i.get("broken") or [])
            else:
                broken = {a["index"] for a in _audio_keep(info, pr["audio"])
                          if _audio_codec(a, pr["audio"]) == ["copy"] and _audio_damaged(full, a)}
            if broken:
                self.log(f"shrink: {os.path.basename(full)}: audio track(s) {sorted(broken)} don't decode cleanly, re-encoded")
            cmd = self._shrink_cmd(full, part, info, pr, enc, broken, to_h=i.get("to_height") if not pr.get("copy") else None,
                                   aac=i.get("aac") or "standard")
            self.log("shrink:", os.path.basename(full), "sound only" if i.get("audio_fix") else i["profile"], enc)
            os.makedirs(self.encodes, exist_ok=True)
            p = mp_detached.Detached.start(self.encodes, cmd, {"part": part, "full": full, "enc": enc}, "shrink")
            i["job"] = p.id
            with self.lock:
                self._save()
        with self.lock:
            self.proc = p
        done = threading.Event()
        work = [0.0]  # seconds the encoder was allowed to run (to learn its speed)
        gov = threading.Thread(target=self._govern, args=(p, t, full, done, work), daemon=True)
        gov.start()
        stop = False
        try:
            while p.poll() is None:
                us = p.progress().get("out_time_us", "")
                if us.isdigit() and dur:
                    progress(int(i["size"] * min(int(us) / 1e6 / dur, 1)))
                if self.cancel_ev.wait(1):
                    raise _Cancelled()
                if t.get("held") and sys.platform != "win32":
                    raise _Held()
        except _Held:
            # paused by the owner: the encode stays where it is, suspended, and is attached to again on resume
            done.set()
            gov.join(5)
            stop = True
            self.log("shrink: paused", os.path.basename(full))
            raise
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
            _signal(p, "SIGSTOP" if stop else "SIGCONT")
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
        if new > st.st_size * (OLD_GROWTH if i.get("old_format") or i.get("audio_fix")
                               else 1 - pr.get("min_saving", MIN_SAVING) / 2):
            _rm(part)
            i["state"] = "skipped"
            i["error"] = T("the result wasn't much smaller, the original was kept", "wynik nie był wyraźnie mniejszy, oryginał został")
            return
        dec = subprocess.run(_nice(["ffmpeg", "-hide_banner", "-nostdin", "-v", "error", "-i", part, "-map", "0:v:0",
                                    "-f", "null", "-"]), capture_output=True, text=True)
        bad = _video_decode_errors(dec.stderr, (ninfo.get("video") or {}).get("codec"))
        if dec.returncode != 0 or bad:
            _rm(part)
            self.log(f"shrink: {os.path.basename(full)}: decode check: {(bad or [dec.stderr.strip()[-200:]])[0]}")
            raise TaskError(T("the result doesn't decode cleanly, the original was kept", "wynik nie dekoduje się czysto, oryginał został"))
        if i.get("audio_fix") and any(_audio_damaged(part, a) for a in ninfo.get("audio") or []):
            _rm(part)
            raise TaskError(T("the new sound doesn't decode cleanly either, the original was kept",
                              "nowy dźwięk też nie dekoduje się czysto, oryginał został"))
        if not pr.get("copy") and pr.get("codec") != "h264":  # the speeds are the HEVC encoders'
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
            if t.get("held") and can:  # paused by the owner: _shrink stops the encode for good at its next check
                _signal(p, "SIGSTOP")
                done.wait(1)
                continue
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

    def _shrink_cmd(self, full, out, info, pr, enc="cpu", broken=(), to_h=None, aac="standard"):
        v = info["video"]
        h = to_h or min(v.get("height") or pr.get("height", 0), pr.get("height", 1 << 16))
        h -= h % 2
        # even dimensions: old AVIs can be 351 px wide, and 4:2:0 HEVC/H.264 needs both even
        vf = f"scale=-2:{h}" if (v.get("height") or 0) > h else "scale=trunc(iw/2)*2:trunc(ih/2)*2"
        cmd = ["ffmpeg", "-hide_banner", "-nostdin", "-y", "-loglevel", "error", "-i", full, "-map", "0:v:0"]
        keep = _audio_keep(info, pr["audio"])
        for a in keep:
            cmd += ["-map", f"0:a:{a['index']}"]
        cmd += ["-map", "0:s?", "-map_chapters", "0"]
        if pr.get("copy"):
            cmd += ["-c:v", "copy"]
        elif pr.get("codec") == "h264":  # old picture → H.264 (modernize, 1.16+)
            hw = mp_media.detect_encoder() if enc == "gpu" else None
            row = next((e for e in mp_media.ENCODERS if hw and e[0] == hw["encoder"] and hw.get("hw")), None)
            if row:
                name, _, pre, hvf, args = row
                cmd[6:6] = pre
                cmd += ["-vf", f"{vf},{hvf[len(mp_media._SCALE) + 1:]}", "-c:v", name, *args]  # its own scale left out
            else:
                cmd += ["-vf", f"{vf},format=yuv420p", "-c:v", "libx264", "-preset", "medium", "-crf", str(pr["crf"]),
                        "-profile:v", "high"]
        elif enc == "gpu":
            pre, vfx, vargs = mp_media.hevc_cmd(mp_media.detect_hevc()["gpu"], pr["cq"])
            cmd[6:6] = pre  # input options (e.g. the VAAPI device) go before -i
            cmd += ["-vf", f"{vf},{vfx}", *vargs]
        else:
            cmd += ["-vf", f"{vf},format=yuv420p", "-c:v", "libx265", "-preset", self.cfg.get("shrink_preset", "fast"),
                    "-crf", str(pr["crf"]), "-x265-params", "log-level=error"]
        if not pr.get("copy") and pr.get("codec") != "h264":
            cmd += ["-tag:v", "hvc1"]
        for n, a in enumerate(keep):
            how = _audio_codec(a, pr["audio"])
            if a["index"] in broken:
                # re-encoded (AAC plays everywhere); the gaps of the skipped frames are filled so the sound stays in sync.
                # 128k stereo: the damaged tracks are old MP3s of ~96-128k, and 192k grew the files by ~12 % for nothing
                multi = (a.get("channels") or 2) > 2
                how = ["aac", "-b:a", ("384k" if multi else "192k") if aac == "high" else ("256k" if multi else "128k")]
                cmd += [f"-filter:a:{n}", "aresample=async=1:first_pts=0"]
            cmd += [f"-c:a:{n}", *how]
        cmd += ["-c:s", "copy", "-max_muxing_queue_size", "4096", "-progress", "pipe:1", "-nostats", "-f", "matroska", out]
        return _nice(cmd)

    # ---- damaged sound

    def audio_check(self, full, info, deadline=None):
        """Indexes of `full`'s audio tracks (AUDIO_SUSPECT codecs) that don't decode cleanly, from the cache or a decode
        now; None when it isn't cached and `deadline` has passed."""
        st = os.stat(full)
        key = os.path.realpath(full)
        with self.alock:
            c = self.achecks.get(key)
        if c and c[0] == st.st_size and c[1] == int(st.st_mtime):
            return c[2]
        if deadline is not None and time.time() > deadline:
            return None
        errs = {}  # track index → damaged frames (1.16+: the counters show them)
        for a in info.get("audio") or []:
            if a.get("codec") in AUDIO_SUSPECT and (n := _audio_errors(full, a)):
                errs[str(a["index"])] = n
        bad = sorted(int(k) for k in errs)
        with self.alock:
            self.achecks[key] = [st.st_size, int(st.st_mtime), bad, errs]
            tmp = self.achecks_path + ".tmp"
            with open(tmp, "w") as f:
                json.dump(self.achecks, f, ensure_ascii=False)
            os.replace(tmp, self.achecks_path)
        if bad:
            self.log(f"audio check: {os.path.basename(full)}: track(s) {bad} don't decode cleanly")
        return bad

    def _audio_scan(self):
        """Once a day: decode the sound of library videos with AUDIO_SUSPECT tracks not checked yet (a modern picture
        only: old ones are re-encoded whole and checked then). MP3/MP2 first and small files first (old rips, where the
        damage was found), big AC3 films last. Waits while somebody watches or a task runs."""
        time.sleep(float(os.environ.get("MP_AUDIO_SCAN_DELAY", 300)))
        while True:
            todo = []
            for r in self.library.roots():
                if not r["exists"] or set(r["tags"]) & HIDDEN_TAGS:
                    continue
                for dp, dirs, fs in os.walk(r["path"]):
                    dirs[:] = sorted(d for d in dirs if not d.startswith("."))
                    for f in fs:
                        full = os.path.join(dp, f)
                        if os.path.splitext(f)[1].lower() not in VIDEO_EXT or f.startswith("."):
                            continue
                        try:
                            size = os.stat(full).st_size
                            if size < MIN_SIZE:
                                continue
                            info = mp_media.probe(full)
                        except Exception:  # noqa: BLE001
                            continue
                        codecs = {a.get("codec") for a in info.get("audio") or []}
                        if is_old(info.get("video") or {}) or not codecs & AUDIO_SUSPECT:
                            continue
                        todo.append((not codecs & {"mp3", "mp2", "mp1"}, size, full, info))
            n = 0
            for _, _, full, info in sorted(todo, key=lambda x: x[:2]):
                st = os.stat(full) if os.path.exists(full) else None
                c = self.achecks.get(os.path.realpath(full))
                if st is None or (c and c[0] == st.st_size and c[1] == int(st.st_mtime)):
                    continue
                for _ in range(720):  # an hour at most, then the next file anyway
                    with self.lock:
                        running = any(t["state"] == "running" for t in self.items)
                    if not running and not self.streaming():
                        break
                    time.sleep(5)
                try:
                    self.audio_check(full, info)
                    n += 1
                except Exception as e:  # noqa: BLE001
                    self.log("audio check:", os.path.basename(full), e)
            if n:
                self.log(f"audio check: {n} files decoded")
            time.sleep(24 * 3600)

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
            os.rename(full, free_name(os.path.join(dest, os.path.basename(full))))
            return
        dest = os.path.join(other["path"], TRASH, day, self._trash_name(root), rel_dir)
        os.makedirs(dest, exist_ok=True)
        shutil.move(full, free_name(os.path.join(dest, os.path.basename(full))))
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

    def _trash_list(self):
        """trash(), at most a minute old (it walks every trash folder)."""
        at_t, tr = self._trash_cache
        if time.time() - at_t > 60 or tr is None:
            tr = self.trash()
            self._trash_cache = (time.time(), tr)
        return tr

    def _trash_size(self):
        return sum(x["size"] for x in self._trash_list())

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


class _Held(Exception):
    """The owner paused the task (F4): the encode is suspended where it is and the task waits in the queue."""


def _space_error(err):
    e = (err or "").lower()
    return any(x in e for x in ("no space left", "brak miejsca", "not enough room", "za mało miejsca"))


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


def _video_decode_errors(stderr, vcodec):
    """Lines of a decode check that are about the result's picture. Only the video decoder's own complaints count:
    the null muxer's (repeated timestamps from AVI sources) and the copied audio's ("[mp3float] Header missing": a
    damaged frame already in the original, copied as is, read while ffmpeg probes the file) are not the encode's
    fault; they rejected every episode of an old series."""
    out = []
    for ln in stderr.splitlines():
        if not ln.strip() or ln.lstrip().startswith("Last message repeated"):
            continue
        m = re.match(r"\[(\w+) @", ln)
        if m and m.group(1) != (vcodec or m.group(1)):
            continue
        out.append(ln)
    return out


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
    if x["verdict"] == "bad" and x.get("aside"):
        return T(f"{who}: doesn't fit this copy of the film, set aside", f"{who}: nie pasuje do tej wersji filmu, odłożone")
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
        except Exception:  # noqa: BLE001 — without babelfish: the common ones
            c = _ISO3.get(c, c)
    return "" if c in ("und", "mul", "zxx") else c


_ISO3 = {"eng": "en", "pol": "pl", "ger": "de", "deu": "de", "fre": "fr", "fra": "fr", "spa": "es", "ita": "it",
         "rus": "ru", "cze": "cs", "ces": "cs", "jpn": "ja", "por": "pt", "dut": "nl", "nld": "nl", "ukr": "uk"}


def _audio_damaged(full, a):
    """The audio track `a` of `full` has frames its decoder rejects (a full decode of that track only)."""
    return _audio_errors(full, a) > 0


def _audio_errors(full, a):
    """How many errors the decoder of audio track `a` reports over the whole file (0 = it decodes cleanly)."""
    try:
        r = subprocess.run(_nice(["ffmpeg", "-hide_banner", "-nostdin", "-v", "error", "-i", full, "-map", f"0:a:{a['index']}",
                                  "-f", "null", "-"]), capture_output=True, text=True, timeout=3600)
    except (OSError, subprocess.TimeoutExpired):
        return 0
    n = 0
    for ln in r.stderr.splitlines():
        if ln.startswith("[") and not ln.startswith("[null @"):
            n += 1
        elif m := re.match(r"\s*Last message repeated (\d+) times", ln):
            n += int(m.group(1))
    return n


def _audio_keep(info, mode):
    """Audio tracks to keep: all for `keep`; for smaller profiles Polish, English, undefined and the default one."""
    audio = info.get("audio") or []
    if mode in ("keep", "aac") or len(audio) <= 1:  # "lean" (tidy) filters languages like the smaller profiles
        return audio
    keep = [a for a in audio if (a.get("lang") or "und") in ("pol", "pl", "eng", "en", "und") or a.get("default")]
    return keep or audio[:1]


def _audio_codec(a, mode):
    c, ch = a.get("codec") or "", a.get("channels") or 2
    if mode == "aac":  # "always AAC" (modernize, 1.16+): every track, 5.1 kept
        return ["copy"] if c == "aac" else ["aac", "-b:a", "384k" if ch > 2 else "192k"]
    if mode in ("keep", "lean"):
        if c in LOSSLESS_AUDIO or c.startswith("pcm_") or c not in PLAYABLE_AUDIO:
            return ["eac3", "-b:a", "640k"] if ch > 2 else ["aac", "-b:a", "192k"]
        return ["copy"]
    if mode == "stereo":
        return ["copy"] if c in ("aac", "opus") and ch <= 2 else ["aac", "-b:a", "160k", "-ac", "2"]
    return ["aac", "-b:a", "96k", "-ac", "2"]


def _item_profile(i):
    """The encode settings of a task item: the sound fix, the old-picture H.264 profile or a storage profile, with the
    item's own overrides (compression quality, sound mode; agent 1.16+)."""
    if i.get("audio_fix"):
        return AUDIO_FIX
    pr = MODERN if i.get("modern") else PROFILES[i["profile"]]
    if i.get("q"):
        pr = dict(pr, crf=i["q"]["crf"], cq=i["q"]["cq"])
    if i.get("audio_mode"):
        pr = dict(pr, audio=i["audio_mode"])
    return pr


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


# ---- jobs v2 helpers (agent 1.16+)

def _size_text(b):
    return f"{b / (1 << 30):.1f} GB" if b >= 1 << 30 else f"{max(b >> 20, 0)} MB"


def _seeding_text():
    return T("still seeding in the torrent client", "jeszcze seeduje w kliencie torrentów")


def _in_torrent(real, torrents):
    """Whether a file (real path) is a torrent's content or inside one."""
    if not torrents or not real:
        return False
    return real in torrents or any(real.startswith(c + os.sep) for c in torrents)


def _langs(text):
    """"pl, EN,pl" → ["pl", "en"] (2-letter codes, order kept)."""
    out = []
    for x in str(text or "").split(","):
        x = x.strip().lower()
        if re.fullmatch(r"[a-z]{2}", x) and x not in out:
            out.append(x)
    return out


def _subs_langs(f):
    """(languages a video has subtitles in, whether it has any): files next to it and tracks inside it."""
    got = {x.get("lang") for x in f.get("side") or [] if x.get("lang")}
    emb = [_lang2(s.get("lang")) for s in ((f.get("info") or {}).get("subs") or [])]
    got |= {x for x in emb if x}
    return got, bool(f.get("side") or emb)


def _dir_sig(d):
    """A folder's mtime and those of its subtitle subfolders: the subtitles of its videos are listed again when it
    changes."""
    try:
        sig = [int(os.stat(d).st_mtime)]
        for n in sorted(os.listdir(d)):
            if n.lower() in ("subs", "sub", "subtitles"):
                try:
                    sig.append(int(os.stat(os.path.join(d, n)).st_mtime))
                except OSError:
                    pass
    except OSError:
        return None
    return sig


def _side(full):
    """Subtitle files of a video: [{"name" (relative to its folder), "lang" (2 letters, from the name or the text,
    "" unknown), "fit" (SRT or text SUB: can be fitted)}]."""
    import mp_subsync
    out = []
    for x in mp_library.sidecar_subs(full):
        sp = os.path.join(os.path.dirname(full), x["name"])
        code = _lang2(x.get("lang"))
        if len(code) != 2:
            code = mp_library.text_lang(sp)
        ext = os.path.splitext(sp)[1].lower()
        try:
            fit = ext == ".srt" or (ext == ".sub" and mp_subsync.is_text_sub(sp))
        except OSError:
            fit = False
        out.append({"name": x["name"], "lang": code or "", "fit": fit})
    return out


def _quick_hash(fp, sz):
    import hashlib
    h = hashlib.sha1()
    with open(fp, "rb") as f:
        h.update(f.read(4 << 20))
        f.seek(max(sz - (4 << 20), 0))
        h.update(f.read(4 << 20))
    return h.hexdigest()


def _mid_hash(fp, sz):
    import hashlib
    h = hashlib.sha1()
    with open(fp, "rb") as f:
        for frac in (1 / 3, 2 / 3):
            f.seek(int(sz * frac))
            h.update(f.read(4 << 20))
    return h.hexdigest()


def _res_class(v):
    """A picture's resolution class: its height, or the height a 16:9 picture of its width would have (a 1920×800
    film is 1080p)."""
    h, w = v.get("height") or 0, v.get("width") or 0
    return max(h, int(round(w * 9 / 16)))


_RES_ORDER = {"4K": 2160, "1440p": 1440, "1080p": 1080, "720p": 720, "576p": 576, "480p": 480, "360p": 360, "SD": 0}


def _res_label(cls):
    for name, h in _RES_ORDER.items():
        if cls >= h * 0.9:
            return name
    return "SD"


_FORMATS = {"msmpeg4v1": "DivX 3", "msmpeg4v2": "DivX 3", "msmpeg4v3": "DivX 3", "wmv1": "WMV", "wmv2": "WMV",
            "wmv3": "WMV 9", "vc1": "VC-1", "rv10": "RealVideo", "rv20": "RealVideo", "rv30": "RealVideo",
            "rv40": "RealVideo", "flv1": "Flash", "vp6": "Flash (VP6)", "vp6f": "Flash (VP6)", "vp6a": "Flash (VP6)",
            "theora": "Theora", "cinepak": "Cinepak", "svq1": "Sorenson", "svq3": "Sorenson", "mpeg1video": "MPEG-1",
            "h261": "H.261", "mpeg4": "XviD/DivX"}


def _format_name(v):
    c = v.get("codec") or ""
    if c == "h264":
        return "H.264 10-bit" if (v.get("bits") or 8) > 8 else "H.264 4:2:2/4:4:4"
    return _FORMATS.get(c, c.upper())


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
