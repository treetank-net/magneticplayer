"""Intro and end credits of series episodes, for "Skip intro" and the next-episode countdown in the phone's player.

How (no network, no model; idea of Jellyfin's Intro Skipper):
- intro: the opening theme sounds the same in every episode. The first minutes of the sound → a chromaprint fingerprint
  (ffmpeg's `chromaprint` muxer, one 32-bit point every ~0.124 s); against the neighbouring episodes of the same folder:
  shifts where points are equal, then for the best shifts the run of similar points (≤ 6 differing bits) with gaps
  under 3.5 s; the longest run of 10–180 s is the intro. With episodes on both sides both must agree (a "previously
  on" recap in the next episode shares sound with this one, but not with the previous one; one side finding nothing,
  e.g. a pilot without the intro, leaves the decision to the other);
- credits: the same comparison of the last minutes (the end theme), the run must reach the last minute of the file;
  otherwise black: credits usually roll on black, so `blackdetect` on keyframes only (about a second for the last
  8 minutes); a black stretch of ≥ 15 s that reaches the last half minute;
- nothing found: no intro, credits unknown (the player counts down from the end of the file).

Results per video file (path, size, mtime) in `<cache>/segments.json`; fingerprints in `<cache>/prints/`. A request
for an episode queues it and the next one (so the next episode is ready when it starts). About 5–10 s per episode
on jmpc, nice'd, one at a time.
"""
import collections
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import threading
import time

import mp_subs

STEP = 0.1238  # seconds per chromaprint point
HEAD, TAIL = 600, 480  # seconds of sound compared at the start and at the end (at most 30% of the video each)
VERSION = 1  # bump to recompute everything


def _np():
    mp_subs.add_venv_path()
    try:
        import numpy
        return numpy
    except ImportError:
        return None


def _base():
    if sys.platform == "win32":
        return os.path.join(os.environ.get("LOCALAPPDATA") or os.path.expanduser("~"), "magneticplayer", "cache")
    return os.path.join(os.environ.get("XDG_CACHE_HOME") or os.path.expanduser("~/.cache"), "magneticplayer")


def _run(cmd, **kw):
    if sys.platform == "win32":
        kw["creationflags"] = subprocess.CREATE_NO_WINDOW
    elif shutil.which("nice"):
        cmd = ["nice", "-n", "15", *cmd]
    return subprocess.run(cmd, capture_output=True, timeout=600, **kw)


_chroma = None


def has_chromaprint():
    global _chroma
    if _chroma is None:
        try:
            out = _run(["ffmpeg", "-hide_banner", "-muxers"]).stdout.decode("utf-8", "replace")
            _chroma = bool(re.search(r"\bchromaprint\b", out))
        except (OSError, subprocess.SubprocessError):
            _chroma = False
    return _chroma


def available():
    return shutil.which("ffmpeg") is not None and shutil.which("ffprobe") is not None and _np() is not None


def duration(video):
    r = _run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", video])
    try:
        return float(r.stdout.decode().strip())
    except ValueError:
        return 0.0


def _key(video):
    st = os.stat(video)
    return hashlib.sha1(f"{os.path.realpath(video)}|{st.st_size}|{int(st.st_mtime)}".encode()).hexdigest()[:20]


def fingerprint(video, start, length):
    """Chromaprint points of `length` seconds from `start`, cached."""
    np = _np()
    d = os.path.join(_base(), "prints")
    path = os.path.join(d, f"{_key(video)}-{int(start)}-{int(length)}.npy")
    try:
        return np.load(path)
    except (OSError, ValueError):
        pass
    r = _run(["ffmpeg", "-hide_banner", "-nostdin", "-v", "error", "-ss", f"{start:.2f}", "-t", f"{length:.2f}",
              "-i", video, "-map", "0:a:0", "-vn", "-sn", "-ac", "1", "-f", "chromaprint", "-fp_format", "raw", "-"])
    a = np.frombuffer(r.stdout, dtype="<u4").copy()
    if len(a) > 50:
        os.makedirs(d, exist_ok=True)
        np.save(path, a)
    return a


def common(a, b, max_diff=6, gap=3.5, min_len=10):
    """The longest stretch of similar sound in two fingerprints: (a_start, a_end, b_start, b_end) in seconds, or None."""
    np = _np()
    if len(a) < 50 or len(b) < 50:
        return None
    pop = np.array([bin(i).count("1") for i in range(256)], dtype=np.uint8)
    first = {}
    for i, x in enumerate(a.tolist()):
        first.setdefault(x, i)
    shifts = collections.Counter()
    for j, x in enumerate(b.tolist()):
        i = first.get(x)
        if i is not None:
            shifts[j - i] += 1
    best, tried = None, set()
    for sh, _ in shifts.most_common(40):
        for s in (sh - 1, sh, sh + 1):
            if s in tried:
                continue
            tried.add(s)
            i0, j0 = max(0, -s), max(0, s)
            n = min(len(a) - i0, len(b) - j0)
            if n <= 1:
                continue
            d = pop[(a[i0:i0 + n] ^ b[j0:j0 + n]).view(np.uint8).reshape(-1, 4)].sum(1)
            ok = np.flatnonzero(d <= max_diff)
            if len(ok) < 2:
                continue
            breaks = np.flatnonzero(np.diff(ok) * STEP > gap)
            starts, ends = np.r_[0, breaks + 1], np.r_[breaks, len(ok) - 1]
            k = int(np.argmax(ok[ends] - ok[starts]))
            length = (ok[ends[k]] - ok[starts[k]]) * STEP
            if length >= min_len and (best is None or length > best[0]):
                best = (length, (i0 + ok[starts[k]]) * STEP, (i0 + ok[ends[k]]) * STEP,
                        (j0 + ok[starts[k]]) * STEP, (j0 + ok[ends[k]]) * STEP)
    return tuple(round(float(v), 1) for v in best[1:]) if best else None


def black_credits(video, dur):
    """Start of a long black stretch at the very end (credits on black), or None."""
    start = max(0.0, dur - min(TAIL, dur * 0.3))
    r = _run(["ffmpeg", "-hide_banner", "-nostdin", "-skip_frame", "nokey", "-ss", f"{start:.2f}", "-i", video,
              "-an", "-sn", "-dn", "-vf", "scale=320:-2,blackdetect=d=1:pic_th=0.90:pix_th=0.12", "-f", "null", "-"])
    spans = []
    for a, b in re.findall(r"black_start:([\d.]+) black_end:([\d.]+)", r.stderr.decode("utf-8", "replace")):
        a, b = start + float(a), start + float(b)
        if spans and a - spans[-1][1] <= 4:  # credit lines break the black into pieces
            spans[-1][1] = b
        else:
            spans.append([a, b])
    for a, b in reversed(spans):
        if b - a >= 15 and b >= dur - 30:
            return round(a, 1)
    return None


def _overlap(x, y):
    return max(0.0, min(x[1], y[1]) - max(x[0], y[0])) >= 0.5 * min(x[1] - x[0], y[1] - y[0])


def analyse(video, neighbours, log=print):
    """{"intro": [start, end] | None, "credits": start | None} for `video`, compared with 1–2 neighbouring episodes."""
    dur = duration(video)
    if dur < 300:
        return {"intro": None, "credits": None, "duration": dur}
    intros, credits = [], []
    if has_chromaprint():
        head = min(HEAD, dur * 0.3)
        tail = min(TAIL, dur * 0.3)
        mine_h, mine_t = fingerprint(video, 0, head), fingerprint(video, dur - tail, tail)
        for nb in neighbours:
            nd = duration(nb)
            if nd < 300:
                continue
            m = common(mine_h, fingerprint(nb, 0, min(HEAD, nd * 0.3)))
            intros.append((m[0], m[1]) if m and 10 <= m[1] - m[0] <= 180 else None)
            nt = min(TAIL, nd * 0.3)
            m = common(mine_t, fingerprint(nb, nd - nt, nt))
            if m and dur - tail + m[1] >= dur - 60:
                credits.append(round(dur - tail + m[0], 1))
    intro = None
    found = [i for i in intros if i]
    if len(found) == 1:  # the other neighbour has no intro (a pilot, a special): this one alone decides
        intro = list(found[0])
    elif len(found) == 2 and _overlap(found[0], found[1]):  # two that disagree: a recap, not the intro
        intro = [min(found[0][0], found[1][0]), max(found[0][1], found[1][1])]
    credit = max(credits) if credits else None  # the later one: never cut the episode short
    if credit is None:
        credit = black_credits(video, dur)
    return {"intro": intro, "credits": credit, "duration": round(dur, 1)}


class Segments:
    def __init__(self, log=print):
        self.log = log
        self.path = os.path.join(_base(), "segments.json")
        try:
            with open(self.path) as f:
                self.rec = json.load(f)
        except (OSError, ValueError):
            self.rec = {}
        self.lock = threading.Lock()
        self.queue = collections.deque()
        self.wake = threading.Event()
        self.thread = None

    def _save(self):
        os.makedirs(os.path.dirname(self.path), exist_ok=True)
        tmp = self.path + ".tmp"
        with open(tmp, "w") as f:
            json.dump(self.rec, f)
        os.replace(tmp, self.path)

    def get(self, video):
        try:
            r = self.rec.get(_key(video))
        except OSError:
            return None
        return r if r and r.get("v") == VERSION else None

    def request(self, video, episodes):
        """What is known about `video` ({"state": "done", "intro", "credits"} or {"state": "pending"}); `episodes`:
        the videos of its folder in watching order. Queues it and the next episode when not known yet."""
        if not available():
            return {"state": "unavailable"}
        r = self.get(video)
        i = episodes.index(video) if video in episodes else -1
        todo = [] if r else [video]
        if 0 <= i < len(episodes) - 1 and not self.get(episodes[i + 1]):
            todo.append(episodes[i + 1])
        for v in todo:
            j = episodes.index(v) if v in episodes else -1
            nbs = [episodes[k] for k in (j - 1, j + 1) if j >= 0 and 0 <= k < len(episodes)]
            with self.lock:
                if all(q[0] != v for q in self.queue):
                    self.queue.append((v, nbs))
        if todo:
            self._start()
        if r:
            return {"state": "done", "intro": r.get("intro"), "credits": r.get("credits")}
        return {"state": "pending"}

    def _start(self):
        with self.lock:
            if self.thread and self.thread.is_alive():
                return
            self.thread = threading.Thread(target=self._work, daemon=True, name="segments")
            self.thread.start()

    def _work(self):
        while True:
            with self.lock:
                if not self.queue:
                    self.thread = None
                    return
                video, nbs = self.queue.popleft()
            if self.get(video):
                continue
            t = time.time()
            try:
                res = analyse(video, nbs, self.log)
            except (OSError, subprocess.SubprocessError, ValueError) as e:
                self.log(f"segments: {os.path.basename(video)}: {e}")
                res = {"intro": None, "credits": None}
            try:
                key = _key(video)
            except OSError:
                continue
            with self.lock:
                self.rec[key] = dict(res, v=VERSION, at=int(time.time()))
                self._save()
            self.log(f"segments: {os.path.basename(video)} intro {res.get('intro')} credits {res.get('credits')} "
                     f"({time.time() - t:.0f} s)")
