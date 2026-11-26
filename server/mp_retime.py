"""Subtitles re-timed to the film by Whisper, piece by piece (agent 1.20+).

Why: the speech-detection fit (mp_subsync) finds one shift (and a frame-rate ratio) for the whole file. A copy cut
differently from the subtitles' release needs a different shift after every cut, and old copies also run at a slightly
different speed. Tested by hand on two old films from archive.org (2026-11-25/26): one with a 6 s cut and a -0.1% speed
difference, one with five cuts of 1-9 s. The fit marks such files "unsure" with `cuts`; this module fixes them.

Model: film time = subtitle time × (1 + tempo) + offset of the stretch, one tempo for the film, a new offset after each
cut. How:
1. listen (Whisper, in a separate process: mp_whisper_worker.py) to 1-minute pieces where the subtitles have many lines
   and the film has speech (Silero, from mp_subsync's cache), spread over the film;
2. match the first words of each line to the words heard (within ±45 s of where the speech fit put the file) →
   (line, offset);
3. fit the tempo (a grid) and the stretches (Viterbi over the matched lines: a cost per change);
4. two neighbouring matched lines in different stretches far apart = a cut not yet located: listen between them
   (bisection), until the two lines around each cut are less than 2 minutes apart, then listen to that gap itself;
   where nothing more can be heard, the cut goes where the lines land on the film's speech best;
5. a line heard clearly that fits neither stretch keeps its own time (a lone line in a stretch without dialogue);
6. every line gets its stretch's offset; a line shows `lead` seconds before the speech (the owner: up to 0.5 s early is
   fine, at the start of the speech ideal, late or long before is wrong); lines that overlapped in the original (two
   speakers split into two cues with the same times) still overlap, others never do.

Results by hand (sampling, jmpc GTX 970): a 92-min film heard 13 min, 82 s, median error 0.29 s against full-film
Whisper; a 95-min film with little dialogue heard 26 min, 155 s.
"""
import json
import os
import re
import subprocess
import sys
import threading
import time
from difflib import SequenceMatcher

import mp_subs
import mp_subsync

PIECE = 60.0      # seconds heard at a time
INIT = 10         # first pieces, spread over the film
FINE = 120.0      # a cut is located when the two matched lines around it are closer than this (film seconds)
MATCH = 0.85      # SequenceMatcher ratio of a line's first words against what was heard
LAM, CAP = 6.0, 3.0  # Viterbi: cost of a new stretch; a line's cost is |error| capped (a wrong match costs at most CAP)
MAX_ROUNDS = 25
WIN = 45.0        # a line is looked for within ±WIN s of where the speech fit put it (cuts of up to ~40 s)
_lock = threading.Lock()  # one Whisper at a time (GPU memory, CPU)


class RetimeError(Exception):
    pass


def settings(cfg):
    """config.json "whisper": {"python", "device": auto|cuda|cpu, "model", "model_en", "auto", "lead", "extend"}."""
    w = dict(cfg.get("whisper") or {})
    venv = os.path.join(os.path.dirname(mp_subs.VENV), "whisper-venv")
    default_py = os.path.join(venv, "Scripts", "python.exe") if sys.platform == "win32" else os.path.join(venv, "bin", "python")
    w.setdefault("python", default_py)
    w.setdefault("device", "auto")
    w.setdefault("model_en", "small.en")
    w.setdefault("model", "small")
    w.setdefault("auto", True)    # re-time "unsure" fits by itself
    w.setdefault("lead", 0.15)    # seconds a line shows before its speech
    w.setdefault("extend", 0.0)   # a line may stay this much longer, up to the next one (0 = as in the original)
    return w


def available(cfg):
    w = settings(cfg)
    return os.path.exists(w["python"]) and mp_subsync.available()


def _env(python):
    """CUDA libraries installed as pip packages (nvidia-cublas-cu11 etc.) are found through LD_LIBRARY_PATH, which the
    loader reads at start: set it for the worker process."""
    env = dict(os.environ)
    if sys.platform != "win32":
        base = os.path.dirname(os.path.dirname(python))
        libs = []
        for root, dirs, _ in os.walk(os.path.join(base, "lib")):
            if root.endswith(os.path.join("site-packages", "nvidia")):
                libs = [os.path.join(root, d, "lib") for d in sorted(dirs) if os.path.isdir(os.path.join(root, d, "lib"))]
                break
        if libs:
            env["LD_LIBRARY_PATH"] = ":".join(libs + [env.get("LD_LIBRARY_PATH", "")]).rstrip(":")
    return env


class Worker:
    def __init__(self, w, video, language):
        model = w["model_en"] if (language or "en") == "en" else w["model"]
        script = os.path.join(os.path.dirname(os.path.abspath(__file__)), "mp_whisper_worker.py")
        cmd = [w["python"], script, video, model, w["device"], language or "", str(mp_subsync.audio_track(video, language))]
        if sys.platform != "win32":
            cmd = ["nice", "-n", "10", *cmd]
        extra = {"creationflags": subprocess.CREATE_NO_WINDOW} if sys.platform == "win32" else {}
        self.p = subprocess.Popen(cmd, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                                  text=True, env=_env(w["python"]), **extra)
        first = json.loads(self.p.stdout.readline() or '{"error": "the Whisper worker exited"}')
        if "error" in first:
            self.close()
            raise RetimeError(first["error"])
        self.device = first["ready"]

    def words(self, a, b):
        self.p.stdin.write(json.dumps({"a": a, "b": b}) + "\n")
        self.p.stdin.flush()
        r = json.loads(self.p.stdout.readline() or '{"error": "the Whisper worker exited"}')
        if "error" in r:
            raise RetimeError(r["error"])
        return r["words"]

    def close(self):
        try:
            self.p.stdin.close()
            self.p.wait(timeout=10)
        except (OSError, subprocess.TimeoutExpired):
            self.p.kill()


_norm = re.compile(r"[^a-z0-9'À-ɏ]")


def norm(w):
    return _norm.sub("", w.lower())


def parse(srt_text):
    """[[start, end, text lines]] in time order."""
    out = []
    for blk in re.split(r"\n\s*\n", srt_text.replace("\r", "").strip()):
        m = mp_subsync._TIME.search(blk)
        if not m:
            continue
        g = m.groups()
        lines = blk.split("\n")
        i = next(k for k, l in enumerate(lines) if "-->" in l)
        out.append([mp_subsync._sec(*g[:4]), mp_subsync._sec(*g[4:]), "\n".join(l for l in lines[i + 1:] if l.strip())])
    out.sort(key=lambda c: c[0])
    return out


def language_of(name):
    m = re.search(r"\.([a-z]{2,3})(?:-[a-z]+)?\.(srt|sub)$", name.lower())
    return m.group(1) if m else None


def retime(video, srt_text, w, language=None, guess=None, progress=None):
    """Re-timed SRT text and a summary dict, or RetimeError. `guess`: the fit's result (offset, cuts) for the first
    pieces."""
    import numpy as np
    progress = progress if progress is not None else {}
    t0 = time.time()
    cues = parse(srt_text)
    if len(cues) < 30:
        raise RetimeError("too few subtitle lines")
    CW = [[norm(x) for x in c[2].split() if norm(x)] for c in cues]
    # the second speaker of a split two-speaker line (same times) starts talking later: not a timing sample
    usable = [i for i in range(len(cues)) if len(CW[i]) >= 3 and not (i and cues[i - 1][0] == cues[i][0])]
    try:
        speech = mp_subsync.speech(video)
    except mp_subsync.SyncError:
        speech = None
    dur = (len(speech) / mp_subsync.SAMPLE) if speech is not None else cues[-1][1] + 60
    sub_starts = np.array([cues[i][0] for i in usable])

    def dense(c):  # lines of subtitles in [c, c + PIECE) (subtitle time)
        return int(np.searchsorted(sub_starts, c + PIECE) - np.searchsorted(sub_starts, c))

    def talk(fa):  # share of speech in the film over [fa, fa + PIECE)
        if speech is None:
            return 1.0
        i, j = int(max(0, fa) * mp_subsync.SAMPLE), int(max(0, fa + PIECE) * mp_subsync.SAMPLE)
        return float(speech[i:j].mean()) if j > i else 0.0

    heard, words = [], []
    worker = Worker(w, video, language)
    progress.update(device=worker.device, heard=0.0)

    def listen(a, b):
        a, b = max(0.0, a), min(dur, b)
        for h0, h1 in heard:
            if h0 <= a < h1:
                a = h1
            if h0 < b <= h1:
                b = h0
        if b - a < 3:
            return False
        for t, x in worker.words(a, b):
            if norm(x):
                words.append((t, norm(x)))
        words.sort()
        heard.append((a, b))
        heard.sort()
        progress["heard"] = round(sum(h1 - h0 for h0, h1 in heard) / 60, 1)
        return True

    def unheard(fa, fb):
        return not any(h0 < fb and fa < h1 for h0, h1 in heard)

    def best_piece(lo, hi, off):
        """the subtitle-time window in [lo, hi) with the most lines × speech, not heard yet, as film time"""
        best = None
        for c in np.arange(lo, max(lo + 1, hi - PIECE + 1), 5.0):
            fa = c + off
            if not unheard(fa, fa + PIECE):
                continue
            score = dense(c) * (0.3 + talk(fa))
            if score > 0 and (best is None or score > best[0]):
                best = (score, fa)
        return best[1] if best else None

    def matches():
        WT = np.array([x[0] for x in words])
        sp = [x[1] for x in words]
        out = {}
        for ci in usable:
            a = cues[ci][0]
            ws = CW[ci]
            k = min(len(ws), 6)
            # around where the fit put it: the subtitles of another release can be minutes off (another intro)
            lo, hi = np.searchsorted(WT, a + center - WIN), np.searchsorted(WT, a + center + WIN)
            best = None
            for i in range(lo, hi):
                if sp[i] != ws[0] and sp[i] not in ws[:k]:
                    continue  # quick skip: the piece must start with one of the line's first words
                s = SequenceMatcher(None, ws[:k], sp[i:i + k + 2]).ratio() + (0.1 if sp[i] == ws[0] else 0)
                if best is None or s > best[0]:
                    best = (s, i)
            if best and best[0] >= MATCH:
                out[ci] = (float(WT[best[1]] - a), best[0])
        return out

    def fit(m):
        idx = sorted(m)
        a = np.array([cues[i][0] for i in idx])
        o = np.array([m[i][0] for i in idx])
        best = None
        for s in np.arange(-0.003, 0.00301, 0.0001):
            y = o - s * a
            G = np.arange(np.floor(y.min()) - 1, np.ceil(y.max()) + 1, 0.05)
            cost = np.minimum(np.abs(y[:, None] - G[None, :]), CAP)
            acc = cost[0].copy()
            back = np.zeros(cost.shape, np.int32)
            for i in range(1, len(y)):
                j = int(np.argmin(acc))
                jump = acc[j] + LAM
                take = jump < acc
                back[i] = np.where(take, j, np.arange(len(G)))
                acc = np.where(take, jump, acc) + cost[i]
            if best is None or acc.min() < best[0]:
                st = int(np.argmin(acc))
                path = [st]
                for i in range(len(y) - 1, 0, -1):
                    st = int(back[i][st])
                    path.append(st)
                best = (acc.min(), s, G[np.array(path[::-1])], idx, y)
        _, s, d, idx, y = best
        seg = np.r_[0, np.cumsum(np.diff(d) != 0)]
        b = np.array([np.median(y[seg == k]) for k in range(seg.max() + 1)])
        return float(s), seg, b, idx

    center = float((guess or {}).get("offset") or 0.0) if (guess or {}).get("ratio", 1.0) == 1.0 else 0.0
    try:
        off0 = center
        targets = np.linspace(0.06, 0.94, INIT) * cues[-1][0]
        first = best_piece(targets[0] - 120, targets[0] + 120, off0)
        if first is not None:
            listen(first, first + PIECE)
            m = matches()
            if m:
                off0 = float(np.median([v[0] for v in m.values()]))
        for t in targets[1:]:
            fa = best_piece(t - 120, t + 120, off0)
            if fa is not None:
                listen(fa, fa + PIECE)
        m = matches()
        if len(m) < 15:
            raise RetimeError(f"Whisper recognised only {len(m)} lines: another language or another film?")
        for _ in range(MAX_ROUNDS):
            m = matches()
            s, seg, bb, idx = fit(m)
            todo = []
            for p in range(1, len(idx)):
                if seg[p] == seg[p - 1]:
                    continue
                i0, i1 = idx[p - 1], idx[p]
                fa, fb = cues[i0][0] + m[i0][0], cues[i1][0] + m[i1][0]
                if fb - fa > FINE:
                    lo, hi = cues[i0][0], cues[i1][0]
                    third = (hi - lo) / 3
                    fa2 = best_piece(lo + third - PIECE / 2, hi - third + PIECE / 2, (m[i0][0] + m[i1][0]) / 2)
                    if fa2 is None:  # nothing dense left: each line in between on its own (a lone line counts)
                        for ci in usable:
                            if i0 < ci < i1:
                                c = cues[ci][0] + (m[i0][0] + m[i1][0]) / 2
                                if unheard(c - 8, c + 8):
                                    todo.append((c - 8, c + 12))
                    else:
                        todo.append((fa2, fa2 + PIECE))
                else:
                    todo.append((fa, fb))
            # the third point (the owner): a heard piece whose lines sit off the fitted line, by more than 0.6 s the
            # same way, holds a cut the cost per stretch hid (few lines in a film with little dialogue): listen
            # between it and its neighbours
            lin = {ci: bb[seg[p]] + s * cues[ci][0] for p, ci in enumerate(idx)}
            hs = sorted(heard)
            for k, (h0, h1) in enumerate(hs):
                inside = [ci for ci in idx if h0 <= cues[ci][0] + m[ci][0] < h1]
                r = [m[ci][0] - lin[ci] for ci in inside]
                if len(r) < 3 or abs(float(np.median(r))) <= 0.6:
                    continue
                o = float(np.median([m[ci][0] for ci in inside]))
                for nb in ((hs[k - 1] if k else None), (hs[k + 1] if k + 1 < len(hs) else None)):
                    if nb is None:
                        continue
                    ga, gb = (nb[1], h0) if nb[1] <= h0 else (h1, nb[0])
                    if gb - ga > FINE:
                        third = (gb - ga) / 3
                        fa2 = best_piece(ga + third - o - PIECE / 2, gb - third - o + PIECE / 2, o)
                        if fa2 is not None:
                            todo.append((fa2, fa2 + PIECE))
                    elif gb - ga > 5:
                        todo.append((ga, gb))
            todo = [t for t in todo if unheard(*t) or sum(max(0.0, min(t[1], h1) - max(t[0], h0)) for h0, h1 in heard) < t[1] - t[0] - 5]
            if not todo or not any([listen(a, b) for a, b in todo[:10]]):
                break
        m = matches()
        s, seg, bb, idx = fit(m)
    finally:
        worker.close()

    # every line: its stretch's offset; a cut between two matched lines goes where the lines in between land on the
    # film's speech best (Silero), else into the largest gap between them
    off = np.empty(len(cues))
    for p in range(len(idx)):
        off[idx[p]] = bb[seg[p]]
    cuts = []
    for p in range(1, len(idx)):
        i0, i1 = idx[p - 1], idx[p]
        if seg[p] == seg[p - 1]:
            off[i0:i1] = bb[seg[p]]
            continue
        o0, o1 = bb[seg[p - 1]], bb[seg[p]]

        def on_speech(j):  # lines i0+1..j shifted by o0, j+1..i1-1 by o1: how much of them falls on speech
            if speech is None:
                return 0.0
            tot = 0.0
            for k in range(i0 + 1, i1):
                o = o0 if k <= j else o1
                a_, b_ = int((cues[k][0] + o) * mp_subsync.SAMPLE), int((cues[k][1] + o) * mp_subsync.SAMPLE)
                if 0 <= a_ < b_ <= len(speech):
                    tot += float(speech[a_:b_].sum())
            return tot
        cand = range(i0, i1)
        jg = max(cand, key=lambda j: (round(on_speech(j)), cues[j + 1][0] - cues[j][1]))
        off[i0:jg + 1] = o0
        off[jg + 1:i1] = o1
        cuts.append([round((cues[jg + 1][0] + o1 + s * cues[jg + 1][0]) / 60, 2), round(float(o1 - o0), 2)])
    off[:idx[0]] = bb[seg[0]]
    off[idx[-1]:] = bb[seg[-1]]
    off = off + s * np.array([c[0] for c in cues])
    # a line heard clearly that fits neither stretch keeps its own time
    alone = 0
    for p, ci in enumerate(idx):
        o, r = m[ci]
        # 4+ words matched exactly: a short common line ("Yes, sir.") can match the same words said elsewhere
        if r >= 1.0 and len(CW[ci]) >= 4 and 1.0 < abs(o - off[ci]) <= 10:
            off[ci] = o
            alone += 1
    res = np.array([m[ci][0] - off[ci] for ci in idx])
    good = float((np.abs(res) <= 1.0).mean())
    if good < 0.8:
        raise RetimeError(f"only {good:.0%} of the lines heard agree with the fit: left as it was")
    lead, extend = float(w["lead"]), float(w["extend"])
    out = [[c[0] + o - lead, c[1] + o - lead, c[2]] for c, o in zip(cues, off)]
    for i in range(len(out) - 1):
        nxt = out[i + 1][0]
        if cues[i][1] <= cues[i + 1][0]:  # didn't overlap in the original: must not now
            if out[i][1] > nxt - 0.05:
                out[i][1] = max(out[i][0] + 0.3, nxt - 0.05)
            elif extend > 0:
                out[i][1] = min(out[i][1] + extend, nxt - 0.05)
    text = "".join(f"{i + 1}\n{mp_subs._ts(max(0.0, a))} --> {mp_subs._ts(max(0.0, b))}\n{t}\n\n" for i, (a, b, t) in enumerate(out))
    summary = {"verdict": "good", "by": "whisper", "tempo": round(s * 100, 3), "offset": round(float(bb[0]), 2),
               "cuts": cuts, "alone": alone, "matched": len(idx), "lines": len(cues),
               "error": round(float(np.median(np.abs(res))), 2), "heard": progress.get("heard"),
               "device": progress.get("device"), "secs": round(time.time() - t0, 1),
               # the original's line starts and offsets (film = start + offset): a translation written for the same
               # release as these subtitles gets the same cuts (mp_rhythm.translate, agent 1.21+)
               "map": {"ratio": 1.0, "tempo": float(s), "starts": [round(float(c[0]), 3) for c in cues],
                       "offsets": [round(float(o), 3) for o in off]}}
    return text, summary
