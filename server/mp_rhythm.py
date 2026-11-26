"""Subtitles re-timed by the rhythm of speech alone, line by line (agent 1.21+): no Whisper, any language.

Why: Whisper (mp_retime) needs a large model, a GPU for speed, and a language it knows. Speech detection (Silero, from
mp_subsync's cache, ~10 s for a film on a processor) only says where people talk, in any language. The owner: what
matters is that a line appears when its speech starts; how long it stays is the subtitles' own quality.

Model (as mp_retime): film time = subtitle time × (1 + tempo) + offset of the stretch, a new offset after each cut.
How:
1. stretches: 2-minute pieces of the subtitles (every minute) cross-correlated with the speech within ±45 s of the
   whole-film fit; a tempo grid and a Viterbi over the pieces' offsets (a cost per change) give the tempo and the offsets
   the film has;
2. lines: every line may take any offset within ±3 s of those (0.05 s steps); its cost is how far its start lands from
   a speech onset (silence → speech; a start inside ongoing speech costs at most half: a second line of one breath),
   capped; the offset changes only between lines and a change costs JUMP lines' worth (Viterbi over all lines);
3. lines that are not speech: sound descriptions ("[door slams]", "(laughs)", "♪ …") and lines that begin with one
   weigh `desc_weight` (not zero: a description mark can be wrong, both ways); the second speaker of a two-speaker line
   with the same times weighs nothing (starts later than its cue);
4. Silero notices the start of speech a little late: measured against Whisper words on three films, +0.1 to +0.3 s;
   `onset_lag` takes that back, `lead` shows the line a little early (as in mp_retime).

Measured 2026-11-26 against full-film Whisper on three old films: a fast comedy median error 0.14 s (no line over 1 s),
a noir 0.23 s (2% over 1 s); a horror with noisy sound and screams 0.45 s with 18% over 1 s. There, the speech detector
itself prefers the wrong fit (more line starts on speech onsets than the true times), so no rhythm method gets it right:
`confidence` says so (the pieces and the lines disagree, the cost minimum is flat) and the caller uses Whisper or leaves
the subtitles "unsure".

`translate()`: other subtitles (another language) fitted to a timed baseline (subtitles in the film's language, timed by
this module or by Whisper) by the rhythm of the lines, not the speech: the line starts of the baseline are exact speech
onsets. Three hypotheses, the simplest that fits wins: written for this copy (one tempo + offset), written for the same
release as the baseline's original (the baseline's cuts after one tempo + offset), or another copy (line by line).
"""
import re

import mp_subs
import mp_subsync

S = mp_subsync.SAMPLE
W = 120.0          # piece length (s) for the stretches
WIN = 45.0         # a piece is looked for within ±WIN s of the whole-film fit
TEMPOS = [t / 10000 for t in range(-30, 31)]
MIN_LIFT = 0.10   # a translation fits when its lines land on the baseline's this much more often than by chance  # ±0.3 % in 0.01 % steps (on top of the fit's frame-rate ratio)


class RhythmError(Exception):
    pass


def settings(cfg):
    """config.json "rhythm": {"jump", "desc_weight", "onset_lag", "max_disagree", "min_sharp"}; the lead and extend
    come from "whisper" (mp_retime.settings) so both methods show lines the same way."""
    r = dict(cfg.get("rhythm") or {})
    r.setdefault("jump", 6.0)          # a cut costs this many lines' worth of evidence
    r.setdefault("desc_weight", 0.3)   # weight of a line marked as a sound description
    r.setdefault("onset_lag", 0.15)    # Silero notices speech this late (s)
    r.setdefault("max_disagree", 0.06)  # confidence: share of lines where pieces and lines disagree by > 1 s
    r.setdefault("min_sharp", 0.02)    # confidence: the flattest stretch's cost rise 0.3-0.6 s away
    return r


_DESC = re.compile(r"\[[^\]]*\]|\([^)]*\)|<[^>]*>|\{[^}]*\}|♪[^♪]*♪?|[♪#]")
_SPEAKER = re.compile(r"(?m)^\s*[-–]?\s*[A-ZÀ-Ý][A-ZÀ-Ý .'-]{1,20}:")
_LEADS = re.compile(r"\s*[-–]?\s*[\[(♪#]")


def weights(cues, desc_weight):
    """Per line: 1 speech, `desc_weight` a sound description (or a line that begins with one), 0 the second speaker of
    a two-speaker line with the same start."""
    out = []
    for i, c in enumerate(cues):
        t = re.sub(r"<[^>]*>|\{[^}]*\}", "", c[2])
        if i and abs(cues[i - 1][0] - c[0]) < 0.05:
            out.append(0.0)
        elif not re.search(r"\w", _SPEAKER.sub(" ", _DESC.sub(" ", t))) or _LEADS.match(t):
            out.append(desc_weight)
        else:
            out.append(1.0)
    return out


def onsets(np, sp):
    """Seconds where speech starts (silence → speech)."""
    return (np.flatnonzero(np.diff(sp.astype(np.int8)) == 1) + 1) / S


def _viterbi(np, cost, jump):
    """Path over rows (items) choosing a column per row; changing the column costs `jump`. → column per row, total."""
    acc = cost[0].copy()
    back = np.zeros(cost.shape, np.int32)
    ar = np.arange(cost.shape[1])
    for i in range(1, len(cost)):
        j = int(np.argmin(acc))
        take = acc[j] + jump < acc
        back[i] = np.where(take, j, ar)
        acc = np.where(take, acc[j] + jump, acc) + cost[i]
    k = int(np.argmin(acc))
    total = float(acc[k])
    path = [k]
    for i in range(len(cost) - 1, 0, -1):
        k = int(back[i][k])
        path.append(k)
    return np.array(path[::-1]), total


def stretches(np, sp, cues, wt, center, ratio=1.0):
    """[(subtitle second of the piece's middle, offset)] of 2-minute pieces, then the tempo and the offset of each
    piece's stretch. → (tempo, piece times, piece stretch offsets)."""
    r = 2.0 * sp.astype(np.float32) - 1
    pad = int((abs(center) + WIN + W + 60) * S)
    r = np.concatenate([-np.ones(pad, np.float32), r, -np.ones(pad, np.float32)])
    L = int(WIN * S)
    spoken = [(a * ratio, b * ratio) for (a, b, _), w in zip(cues, wt) if w >= 1]
    pts = []
    end = cues[-1][0] * ratio
    w0 = 0.0
    while w0 < end:
        sel = [(a, b) for a, b in spoken if w0 <= a < w0 + W]
        if len(sel) >= 6:
            sig = -np.ones(int(W * S), np.float32)
            for a, b in sel:
                sig[int((a - w0) * S):max(0, min(len(sig), int((b - w0) * S)))] = 1
            s0 = int((w0 + center) * S) - L + pad
            seg = r[s0:s0 + len(sig) + 2 * L]
            if s0 >= 0 and len(seg) == len(sig) + 2 * L:
                size = 1 << int(np.ceil(np.log2(len(seg) + len(sig))))
                conv = np.fft.irfft(np.fft.rfft(seg, size) * np.conj(np.fft.rfft(sig, size)), size)[:2 * L + 1]
                k = int(np.argmax(conv))
                z = float((conv[k] - np.median(conv)) / (conv.std() or 1))
                if z >= 3.0 and S <= k <= 2 * L - S:  # a real peak, not the edge of the search
                    pts.append((w0 + W / 2, center + (k - L) / S))
        w0 += W / 2
    if len(pts) < 3:
        raise RhythmError("the speech doesn't follow these subtitles (too few pieces fit)")
    a = np.array([p[0] for p in pts])
    o = np.array([p[1] for p in pts])
    best = None
    for s in TEMPOS:
        y = o - s * a
        G = np.arange(np.floor(y.min()) - 1, np.ceil(y.max()) + 1, 0.05)
        path, total = _viterbi(np, np.minimum(np.abs(y[:, None] - G[None, :]), 3.0), 2.5)
        if best is None or total < best[0]:
            best = (total, s, G[path], y)
    _, s, d, y = best
    seg = np.r_[0, np.cumsum(np.diff(d) != 0)]
    bb = np.array([float(np.median(y[seg == k])) for k in range(seg.max() + 1)])
    return s, a, bb[seg]


def lines(np, sp, cues, wt, tempo, offs, jump, ratio=1.0):
    """Per line: offset (film = start × ratio × (1 + tempo) + offset), by speech onsets. → (offsets, grid costs, grid,
    path)."""
    ons = onsets(np, sp)
    if len(ons) < 20:
        raise RhythmError("too little speech in the film")
    G = np.unique(np.concatenate([np.arange(b - 3, b + 3.001, 0.05) for b in np.unique(np.round(offs, 2))]).round(2))
    st = np.array([c[0] for c in cues]) * ratio * (1 + tempo)
    C = np.zeros((len(cues), len(G)), np.float32)
    n = len(sp)
    for gi, g in enumerate(G):
        t = st + g
        k = np.clip(np.searchsorted(ons, t), 1, len(ons) - 1)
        d = np.minimum(np.abs(ons[k] - t), np.abs(ons[k - 1] - t))
        c = np.minimum(d, 1.5) / 1.5
        inside = sp[np.clip((t * S).astype(int), 0, n - 1)] > 0
        C[:, gi] = np.where(inside, np.minimum(c, 0.5), c)
    C *= np.array(wt, np.float32)[:, None]
    path, _ = _viterbi(np, C, jump)
    return G[path], C, G, path


def confidence(np, cues, wt, tempo, piece_t, piece_off, off, C, G, path, ratio=1.0):
    """{"disagree": share of speech lines whose line offset is > 1 s from the pieces' stretch at that time (away from
    the pieces' changes), "sharp": the flattest stretch's cost rise 0.3-0.6 s away from its offset}."""
    st = np.array([c[0] for c in cues]) * ratio
    sp_lines = np.array(wt) >= 1
    near = np.clip(np.searchsorted(piece_t, st), 0, len(piece_t) - 1)
    prev = np.clip(near - 1, 0, len(piece_t) - 1)
    pick = np.where(np.abs(piece_t[prev] - st) < np.abs(piece_t[near] - st), prev, near)
    # lines between two pieces with different offsets: the cut can be anywhere in between, don't count them
    steady = piece_off[prev] == piece_off[near]
    po = piece_off[pick] + tempo * st
    dis = np.abs(off - po)[sp_lines & steady]
    disagree = float((dis > 1.0).mean()) if len(dis) else 1.0
    ch = np.r_[0, np.cumsum(np.diff(path) != 0)]
    sharp = []
    for k in range(ch.max() + 1):
        sel = np.flatnonzero((ch == k) & sp_lines)
        if len(sel) < 10:
            continue
        gi = path[sel[0]]
        base = C[sel, gi].mean()
        nb = [j for j in range(len(G)) if 0.3 <= abs(G[j] - G[gi]) <= 0.6]
        if nb:
            sharp.append(float(min(C[sel, j].mean() for j in nb) - base))
    return {"disagree": round(disagree, 3), "sharp": round(min(sharp), 3) if sharp else 0.0}


def render(cues, off, lead, extend, keep_overlaps=True):
    """SRT text with every line moved by its offset and shown `lead` s early; lines that didn't overlap in the original
    never do now (two speakers split into two cues with the same times still overlap)."""
    out = [[c[0] + o - lead, c[1] + o - lead, c[2]] for c, o in zip(cues, off)]
    for i in range(len(out) - 1):
        nxt = out[i + 1][0]
        if cues[i][1] <= cues[i + 1][0] or not keep_overlaps:
            if out[i][1] > nxt - 0.05:
                out[i][1] = max(out[i][0] + 0.3, nxt - 0.05)
            elif extend > 0:
                out[i][1] = min(out[i][1] + extend, nxt - 0.05)
    return "".join(f"{i + 1}\n{mp_subs._ts(max(0.0, a))} --> {mp_subs._ts(max(0.0, b))}\n{t}\n\n"
                   for i, (a, b, t) in enumerate(out))


def _cuts(np, cues, off, ratio, base):
    """[[film minute, change s]] where the stretch offset `base` (without the tempo's slow drift) changes."""
    st = np.array([c[0] for c in cues]) * ratio
    return [[round(float((st[i] + off[i]) / 60), 2), round(float(base[i] - base[i - 1]), 2)]
            for i in range(1, len(cues)) if abs(base[i] - base[i - 1]) > 0.01]


def retime(video, srt_text, cfg, guess=None, track=None):
    """Re-timed SRT text and a summary, or RhythmError. `guess`: the fit (mp_subsync.align: ratio, offset)."""
    import numpy as np
    import mp_retime
    rs, ws = settings(cfg), mp_retime.settings(cfg)
    cues = mp_retime.parse(srt_text)
    if len(cues) < 30:
        raise RhythmError("too few subtitle lines")
    try:
        sp = mp_subsync.speech(video, track=track)
    except mp_subsync.SyncError as e:
        raise RhythmError(str(e))
    if guess is None or "offset" not in guess:
        guess = mp_subsync.align(sp, [(c[0], c[1]) for c in cues])
    if guess.get("verdict") == "bad":  # another film's subtitles (or a wildly different cut): nothing to time
        raise RhythmError("these subtitles don't fit this film")
    ratio = float(guess.get("fixed_ratio") or guess.get("ratio") or 1.0)
    center = float(guess.get("fixed_offset") if guess.get("fixed_offset") is not None else guess.get("offset") or 0.0)
    if guess.get("fixed_ratio") is not None:
        ratio, center = 1.0, 0.0  # the file on disk was already corrected by the fit
    wt = weights(cues, float(rs["desc_weight"]))
    tempo, piece_t, piece_off = stretches(np, sp, cues, wt, center, ratio)
    base, C, G, path = lines(np, sp, cues, wt, tempo, piece_off, float(rs["jump"]), ratio)
    off = base + tempo * np.array([c[0] for c in cues]) * ratio
    conf = confidence(np, cues, wt, tempo, piece_t, piece_off, off, C, G, path, ratio)
    sure = conf["disagree"] <= rs["max_disagree"] and conf["sharp"] >= rs["min_sharp"]
    # film time of each line's start (the speech onset), before the lead: a later translation fits to these
    film = [c[0] * ratio + o for c, o in zip(cues, off)]
    scaled = [[c[0] * ratio, c[1] * ratio, c[2]] for c in cues]
    text = render(scaled, off, float(ws["lead"]) + float(rs["onset_lag"]), float(ws["extend"]))
    summary = {"verdict": "good" if sure else "unsure", "by": "rhythm", "tempo": round(tempo * 100, 3),
               "ratio": round(ratio, 5), "offset": round(float(off[0]), 2), "cuts": _cuts(np, cues, off, ratio, base),
               "lines": len(cues), **conf,
               "map": {"ratio": ratio, "tempo": tempo,
                       "starts": [round(float(c[0]), 3) for c in cues],
                       "offsets": [round(float(o), 3) for o in off]},
               "onsets": [round(float(t - rs["onset_lag"]), 3) for t, w in zip(film, wt) if w >= 1]}
    return text, summary


# ---- translations fitted to a baseline

def _starts_fit(np, src, dst, wsrc, lo=-300.0, hi=300.0, ratios=None):
    """Best (ratio, tempo, offset, score) mapping line starts `src` onto `dst` (both seconds): score = weighted share
    of `src` within 0.4 s of a `dst` start."""
    dst = np.sort(np.asarray(dst))
    src = np.asarray(src)
    wsrc = np.asarray(wsrc, dtype=float)
    best = (1.0, 0.0, 0.0, -1.0)

    def score(x):
        k = np.clip(np.searchsorted(dst, x), 1, len(dst) - 1)
        d = np.minimum(np.abs(dst[k] - x), np.abs(dst[k - 1] - x))
        return float((wsrc * (d <= 0.4)).sum() / max(wsrc.sum(), 1e-9)), d

    bins = np.arange(lo, hi, 0.1)
    for ratio in ratios or mp_subsync.RATIOS:
        for t in TEMPOS[::5]:
            x = src * ratio * (1 + t)
            # offset candidates: a histogram of all pairwise differences within the range (coarse 0.1 s)
            diffs = (dst[None, :] - x[:, None]).ravel() if len(src) * len(dst) < 4e6 else \
                (dst[None, ::2] - x[::2, None]).ravel()
            diffs = diffs[(diffs >= lo) & (diffs < hi)]
            if not len(diffs):
                continue
            h, _ = np.histogram(diffs, bins)
            h = np.convolve(h, np.ones(5), "same")
            for k in np.argsort(h)[-3:]:
                o = bins[k] + 0.05
                sc, _ = score(x + o)
                if sc > best[3]:
                    best = (ratio, t, float(o), sc)
    # refine: tempo in fine steps and offset in 0.02 s around the best
    ratio, t0, o0, _ = best
    for t in [t0 + d / 10000 for d in range(-5, 6)]:
        for o in np.arange(o0 - 0.5, o0 + 0.501, 0.02):
            sc, _ = score(src * ratio * (1 + t) + o)
            if sc > best[3] + 1e-9:
                best = (ratio, t, float(o), sc)
    return best


def translate(srt_text, base, cfg, snap=None):
    """Other subtitles fitted to a baseline. `base`: {"onsets": film seconds of the baseline's speech lines (speech
    onsets, no lead), "map": {"ratio", "tempo", "starts", "offsets"} of the baseline's original file or None}.
    → (SRT text, summary with "hypothesis": "copy" | "release" | "lines" and "score")."""
    import numpy as np
    import mp_retime
    rs, ws = settings(cfg), mp_retime.settings(cfg)
    snap = float((cfg.get("subflow") or {}).get("snap", 0.35) if snap is None else snap)
    cues = mp_retime.parse(srt_text)
    if len(cues) < 30:
        raise RhythmError("too few subtitle lines")
    B = np.sort(np.array(base["onsets"], float))
    if len(B) < 30:
        raise RhythmError("the baseline has too few lines")
    wt = np.array(weights(cues, float(rs["desc_weight"])))
    st = np.array([c[0] for c in cues])
    hyp = {}
    # 1. written for this copy: one ratio/tempo/offset straight onto the baseline's onsets
    r1, t1, o1, s1 = _starts_fit(np, st, B, wt)
    hyp["copy"] = (s1, st * r1 * (1 + t1) + o1, {"ratio": r1, "tempo": t1, "offset": o1})
    # 2. written for the baseline's original release: onto the baseline's original starts, then its cuts
    m = base.get("map")
    if m and m.get("starts"):
        ostarts = np.array(m["starts"], float)
        ooffs = np.array(m["offsets"], float)
        r2, t2, o2, _ = _starts_fit(np, st, ostarts, wt)
        x = st * r2 * (1 + t2) + o2  # in the baseline's original time
        k = np.clip(np.searchsorted(ostarts, x), 0, len(ostarts) - 1)
        kp = np.clip(k - 1, 0, len(ostarts) - 1)
        nearest = np.where(np.abs(ostarts[kp] - x) < np.abs(ostarts[k] - x), kp, k)
        film2 = x * float(m["ratio"]) + ooffs[nearest]
        kk = np.clip(np.searchsorted(B, film2), 1, len(B) - 1)
        d = np.minimum(np.abs(B[kk] - film2), np.abs(B[kk - 1] - film2))
        s2 = float((wt * (d <= 0.4)).sum() / max(wt.sum(), 1e-9))
        hyp["release"] = (s2, film2, {"ratio": r2, "tempo": t2, "offset": o2})
    # 3. another copy: line by line onto the baseline's onsets (as `lines`, the baseline's starts are the onsets)
    best0 = max(hyp.values(), key=lambda h: h[0])
    offs0 = best0[1] - st
    G = np.unique(np.concatenate([np.arange(b - 3, b + 3.001, 0.05) for b in np.unique(np.round(offs0, 1))]).round(2))
    C = np.zeros((len(cues), len(G)), np.float32)
    for gi, g in enumerate(G):
        x = st + g
        k = np.clip(np.searchsorted(B, x), 1, len(B) - 1)
        d = np.minimum(np.abs(B[k] - x), np.abs(B[k - 1] - x))
        C[:, gi] = np.minimum(d, 1.5) / 1.5
    C *= wt[:, None].astype(np.float32)
    path, _ = _viterbi(np, C, float(rs["jump"]))
    film3 = st + G[path]
    kk = np.clip(np.searchsorted(B, film3), 1, len(B) - 1)
    d = np.minimum(np.abs(B[kk] - film3), np.abs(B[kk - 1] - film3))
    s3 = float((wt * (d <= 0.4)).sum() / max(wt.sum(), 1e-9))
    hyp["lines"] = (s3, film3, {"changes": int((np.diff(path) != 0).sum())})
    # the simplest that fits: line by line only when clearly better
    simple = max((h for k, h in hyp.items() if k != "lines"), key=lambda h: h[0])
    name = max((k for k in hyp if k != "lines"), key=lambda k: hyp[k][0])
    if s3 > simple[0] + 0.05:
        name = "lines"
    score, film, info = hyp[name]
    film = np.array(film, float)
    # cuts of the chosen hypothesis, before snapping (a jump of the per-line offset; the tempo's drift is far smaller)
    d0 = film - st
    cuts = [[round(float(film[i] / 60), 2), round(float(d0[i] - d0[i - 1]), 2)]
            for i in range(1, len(st)) if abs(d0[i] - d0[i - 1]) > 0.3]

    def at(x):
        k = np.clip(np.searchsorted(B, x), 1, len(B) - 1)
        d = np.minimum(np.abs(B[k] - x), np.abs(B[k - 1] - x))
        return float((wt * (d <= 0.4)).sum() / max(wt.sum(), 1e-9))
    # chance: the same lines moved off by 7-41 s land on some baseline line this often (dense dialogue: often). Only
    # the simple hypotheses (one tempo + offset, the baseline's cuts) say whether these are this film's subtitles: line
    # by line fits noise too (another film's subtitles came out 0.13-0.16 above chance that way, this film's 0.24+;
    # by the simple ones 0.05 against 0.15+)
    def lift_of(x):
        return at(x) - float(np.mean([at(x + d) for d in (-41, -23, -13, -7, 7, 13, 23, 41)]))
    lifts = {k: lift_of(np.array(h[1], float)) for k, h in hyp.items()}
    lift = max(v for k, v in lifts.items() if k != "lines")
    chance = at(film) - lifts[name]
    # snap: a start within `snap` s of a baseline onset takes it (the same speech, timed exactly)
    if snap > 0:
        k = np.clip(np.searchsorted(B, film), 1, len(B) - 1)
        nb = np.where(np.abs(B[k] - film) < np.abs(B[k - 1] - film), B[k], B[k - 1])
        film = np.where((np.abs(nb - film) <= snap) & (wt >= 1), nb, film)
    off = film - st
    text = render([list(c) for c in cues], off, float(ws["lead"]), float(ws["extend"]))
    summary = {"verdict": "good" if lift >= MIN_LIFT else "unsure", "by": "baseline", "hypothesis": name,
               "score": round(score, 3), "chance": round(chance, 3), "lift": round(lift, 3),
               "lifts": {k: round(v, 3) for k, v in lifts.items()},
               "scores": {k: round(h[0], 3) for k, h in hyp.items()},
               **{k: (round(v, 5) if isinstance(v, float) else v) for k, v in info.items()},
               "cuts": cuts, "lines": len(cues)}
    return text, summary
