"""Subtitles fitted to the owner's copy of a film: speech detection in the video's sound against the subtitle cues.

Subtitles found online are often made for another release of the same film: shifted by a few seconds (another intro)
or timed for another frame rate (23.976 vs 25 fps: a few seconds off at the start, minutes by the end). Matching by
file hash doesn't help, uploads with a hash match can be that too.

How (no network, no model):
- the video's sound → 16 kHz mono (ffmpeg) → webrtcvad, 10 ms frames → 100 values a second, speech 1 / other 0;
  cached per video (size + mtime) in the cache directory, ~70 kB for a film; about half a minute for a film on jmpc;
- the cues of a subtitle file → the same kind of signal, for each usual frame-rate ratio (1, 25/23.976, 24/23.976,
  25/24 and back);
- cross-correlation of the ±1 signals (FFT) for lags up to ±5 minutes; the best lag and ratio win;
- `z` = how far the best peak stands above all the other lags (in standard deviations). Subtitles of this film come out
  at 7–9, subtitles of another film at 2–3.5 (measured on a library), so: `z` ≥ 6 fits (it is safe to shift it),
  < 4.5 doesn't fit this copy, in between unsure.

Embedded subtitles (agent 1.12.1+): when the video carries its own subtitle tracks (text, PGS or VobSub), their display
times are the reference instead of the speech: they are exact to the frame, and the speech of an action film is buried
under music and effects (a film measured: z 5.7 against speech, 9.8 against its embedded subtitles, the same correction).
Only the times are read (ffprobe packets, ~7 s for an episode), cached like the speech.

`shift()` moves a file by hand (the "too early / too late" buttons), the same way as a fit.

`fit()` shifts/rescales a fitting file in place when that changes anything (≥ 0.25 s or another ratio), turns a text
`.sub` (MicroDVD/MPL2/TMPlayer) into `.srt`, and keeps the original in `<config>/subs-originals/<date>/`. Assessments
are remembered per subtitle file (path, size, mtime) in `subsync.json` for the subtitle list (`fit`).
"""
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
from mp_core import json_default

SAMPLE = 100  # values a second
RATIOS = (1.0, 25 / 23.976, 23.976 / 25, 24 / 23.976, 23.976 / 24, 25 / 24, 24 / 25)
MAX_OFFSET = 300  # seconds
GOOD_Z, BAD_Z = 6.0, 4.5
# Subtitles that line up where they already are (no shift, one of RATIOS) fit even with a weak peak: an action film's
# speech is buried under music. Measured 2026-11-22 on 101 NapiProjekt subtitles found by the file's hash (made for that
# very file): z alone called 26 of them bad; with this rule 89 of 101 fit. A peak that lands within ±1.5 s of "as is"
# by chance is a few per cent at most (±300 s searched).
ASIS_Z, ASIS_S = 3.0, 1.5
MIN_SHIFT = 0.4  # seconds: smaller corrections are within the jitter of speech detection, not worth a rewrite
_vad_lock = threading.Semaphore(1)  # one sound analysis at a time (CPU)
_rec_lock = threading.Lock()
_emb_lock = threading.Lock()


class SyncError(Exception):
    pass


def _libs():
    mp_subs.add_venv_path()
    try:
        import numpy
        import webrtcvad
        return numpy, webrtcvad
    except ImportError:
        return None, None


def available():
    """numpy + webrtcvad in the venv and ffmpeg on the computer (Windows installs don't bring ffmpeg)."""
    return _libs()[0] is not None and shutil.which("ffmpeg") is not None


def cache_dir():
    if sys.platform == "win32":
        base = os.path.join(os.environ.get("LOCALAPPDATA") or os.path.expanduser("~"), "magneticplayer", "cache")
    else:
        base = os.path.join(os.environ.get("XDG_CACHE_HOME") or os.path.expanduser("~/.cache"), "magneticplayer")
    return os.path.join(base, "speech")


def _key(video):
    st = os.stat(video)
    return hashlib.sha1(f"{os.path.realpath(video)}|{st.st_size}|{int(st.st_mtime)}".encode()).hexdigest()[:20]


def speech(video, progress=None):
    """Speech (1) / no speech (0) every 10 ms of the video's sound, as a numpy uint8 array; cached."""
    np, webrtcvad = _libs()
    if np is None:
        raise SyncError("numpy/webrtcvad missing")
    path = os.path.join(cache_dir(), _key(video) + ".npz")
    try:
        with np.load(path) as z:
            return np.unpackbits(z["bits"])[: int(z["n"])]
    except (OSError, ValueError, KeyError):
        pass
    with _vad_lock:
        cmd = ["ffmpeg", "-hide_banner", "-nostdin", "-v", "error", "-i", video, "-map", "0:a:0", "-vn", "-sn",
               "-ac", "1", "-ar", "16000", "-f", "s16le", "-"]
        if sys.platform != "win32" and shutil.which("nice"):
            cmd = ["nice", "-n", "15", *cmd]
        extra = {"creationflags": subprocess.CREATE_NO_WINDOW} if sys.platform == "win32" else {}
        p = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, **extra)
        vad = webrtcvad.Vad(3)
        frame = 320  # 10 ms of 16 kHz 16-bit mono
        out = bytearray()
        buf = b""
        while True:
            chunk = p.stdout.read(frame * 500)
            if not chunk:
                break
            buf += chunk
            n = len(buf) // frame
            for i in range(n):
                out.append(1 if vad.is_speech(buf[i * frame:(i + 1) * frame], 16000) else 0)
            buf = buf[n * frame:]
            if progress is not None:
                progress["seconds"] = len(out) / SAMPLE
        p.wait()
    arr = np.frombuffer(bytes(out), dtype=np.uint8)
    if len(arr) < 60 * SAMPLE or arr.mean() < 0.02:
        raise SyncError("no speech found in the sound")
    os.makedirs(cache_dir(), exist_ok=True)
    tmp = path + ".tmp.npz"
    np.savez_compressed(tmp, bits=np.packbits(arr), n=len(arr))
    os.replace(tmp, path)
    return arr


def embedded(video):
    """[(start, end)] display times of the video's own subtitle track with the most lines (a forced track has only a
    few), or [] when there is none with enough lines. Cached per video."""
    path = os.path.join(cache_dir(), _key(video) + ".subs.json")
    with _emb_lock:  # the warm-up at a search and a fit right after it: one ffprobe, the other waits for its cache
        try:
            with open(path) as f:
                return [tuple(x) for x in json.load(f)]
        except (OSError, ValueError):
            pass
        return _embedded(video, path)


def _embedded(video, path):
    extra = {"creationflags": subprocess.CREATE_NO_WINDOW} if sys.platform == "win32" else {}
    try:
        out = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "s", "-show_entries",
                              "packet=stream_index,pts_time,duration_time,size", "-of", "json", video],
                             capture_output=True, text=True, timeout=600, **extra).stdout
        packets = json.loads(out or "{}").get("packets") or []
    except (OSError, ValueError, subprocess.TimeoutExpired):
        packets = []
    by = {}
    for p in packets:
        try:
            by.setdefault(p["stream_index"], []).append(
                (float(p["pts_time"]), float(p.get("duration_time") or 0), int(p.get("size") or 0)))
        except (KeyError, ValueError):
            continue
    best = []
    for ps in by.values():
        cs, start = [], None
        if sum(1 for _, d, _ in ps if d > 0) > len(ps) / 2:  # text tracks: start + duration
            cs = [(t, t + d) for t, d, _ in ps if d > 0]
        else:  # PGS/VobSub: a picture starts a line, a small "clear" packet ends it
            for t, _, size in ps:
                if size > 100:
                    if start is not None and t - start < 10:
                        cs.append((start, t))
                    start = t
                elif start is not None:
                    cs.append((start, t))
                    start = None
        if len(cs) > len(best):
            best = cs
    if len(best) < 100:
        best = []
    os.makedirs(cache_dir(), exist_ok=True)
    with open(path + ".tmp", "w") as f:
        json.dump(best, f)
    os.replace(path + ".tmp", path)
    return best


def _signal(cs):
    np, _ = _libs()
    n = int(max(b for _, b in cs) * SAMPLE) + 120 * SAMPLE
    ref = np.zeros(n, dtype=np.uint8)
    for a, b in cs:
        ref[int(a * SAMPLE):int(b * SAMPLE)] = 1
    return ref


_TIME = re.compile(r"(\d+):(\d\d):(\d\d)[,.](\d+)\s*-->\s*(\d+):(\d\d):(\d\d)[,.](\d+)")


def _sec(h, m, s, f):
    return int(h) * 3600 + int(m) * 60 + int(s) + int(f[:3].ljust(3, "0")) / 1000


def _upgrade(r):
    """An assessment remembered before the "weak" rule (agent 1.18.4): one that lines up where it is at ratio 1 fits
    unchanged, so it reads "good" now; one that needs another ratio waits for the next assessment."""
    if "weak" in r or r.get("verdict") == "good" or not isinstance(r.get("z"), (int, float)):
        return r
    if ASIS_Z <= r["z"] < GOOD_Z and abs(r.get("offset") or 0) <= ASIS_S and abs((r.get("ratio") or 1) - 1) <= 1e-4:
        return dict(r, verdict="good", weak=True)
    return r


def cues(srt_text):
    return [(_sec(*g[:4]), _sec(*g[4:])) for g in _TIME.findall(srt_text)]


def align(ref, cs):
    """Best (ratio, offset s, z) of cues `cs` against the speech signal `ref`."""
    np, _ = _libs()
    n = len(ref)
    if len(cs) < 20:
        raise SyncError("too few subtitle lines")
    r = 2.0 * ref.astype(np.float32) - 1
    size = 1 << int(np.ceil(np.log2(2 * n)))
    rf = np.fft.rfft(r, size)
    mx = min(MAX_OFFSET * SAMPLE, n - 1)
    lagv = np.r_[np.arange(mx + 1), np.arange(-mx, 0)]
    best = None
    for ratio in RATIOS:
        s = np.zeros(n, dtype=np.float32)
        for a, b in cs:
            i, j = int(a * ratio * SAMPLE), int(b * ratio * SAMPLE)
            if 0 <= i < n:
                s[i:min(j, n)] = 1
        s = 2 * s - 1
        conv = np.fft.irfft(rf * np.conj(np.fft.rfft(s, size)), size)  # conv[k] = Σ r[i+k]·s[i]
        lags = np.r_[conv[:mx + 1], conv[-mx:]]
        k = int(np.argmax(lags))
        z = float((lags[k] - np.median(lags)) / (lags.std() or 1))
        if best is None or lags[k] > best[0]:
            best = (float(lags[k]), ratio, float(lagv[k]) / SAMPLE, z)
    _, ratio, offset, z = best
    # lines up where it is (at most a frame-rate change): it fits, but the weak peak doesn't say exactly how much to
    # shift, so `fit()` applies only the ratio ("weak")
    # plain Python values only: this dict goes into JSON (API, subsync.json), and a numpy scalar there (np.bool_ from
    # comparing a numpy offset) failed every download whose subtitles came out "weak"
    weak = bool(ASIS_Z <= z < GOOD_Z and abs(offset) <= ASIS_S)
    return {"ratio": round(ratio, 5), "offset": round(offset, 2), "z": round(z, 1), "weak": weak,
            "verdict": "good" if z >= GOOD_Z or weak else "bad" if z < BAD_Z else "unsure"}


def _rewrite(srt_text, ratio, offset):
    def conv(m):
        g = m.groups()
        a, b = max(0.0, _sec(*g[:4]) * ratio + offset), max(0.0, _sec(*g[4:]) * ratio + offset)
        return f"{mp_subs._ts(a)} --> {mp_subs._ts(b)}"
    return _TIME.sub(conv, srt_text)


def is_text_sub(path):
    """A `.sub` that is text (MicroDVD/MPL2/TMPlayer), not VobSub (binary, with an `.idx`)."""
    if os.path.exists(os.path.splitext(path)[0] + ".idx"):
        return False
    try:
        with open(path, "rb") as f:
            head = f.read(2048)
    except OSError:
        return False
    if b"\0" in head:
        return False
    text = head.decode("latin-1")
    return bool(re.search(r"^(\{\d+\}\{\d*\}|\[\d+\]\[\d*\]|\d{1,2}:\d\d:\d\d[:=])", text, re.M))


class Store:
    """Assessments per subtitle file, and the place for originals."""

    def __init__(self, config_dir, log=print):
        self.path = os.path.join(config_dir, "subsync.json")
        self.originals = os.path.join(config_dir, "subs-originals")
        self.log = log
        try:
            with open(self.path) as f:
                self.rec = json.load(f)
        except (OSError, ValueError):
            self.rec = {}

    def _save(self):
        tmp = self.path + ".tmp"
        with open(tmp, "w") as f:
            json.dump(self.rec, f, ensure_ascii=False, default=json_default)
        os.replace(tmp, self.path)

    def get(self, sub_path):
        """The remembered assessment of this exact file (same size and mtime), or None."""
        try:
            st = os.stat(sub_path)
        except OSError:
            return None
        r = self.rec.get(os.path.realpath(sub_path))
        if r and r.get("size") == st.st_size and int(r.get("mtime", 0)) == int(st.st_mtime):
            return _upgrade(r)
        return None

    def move(self, old, new, copy=False):
        """The assessment follows a file moved (or copied) to another name: same content, same size and mtime."""
        with _rec_lock:
            r = self.rec.get(os.path.realpath(old))
            if r is None:
                return
            if not copy:
                self.rec.pop(os.path.realpath(old), None)
            self.rec[os.path.realpath(new)] = r
            self._save()

    def _remember(self, sub_path, res):
        st = os.stat(sub_path)
        with _rec_lock:
            self.rec[os.path.realpath(sub_path)] = dict(res, size=st.st_size, mtime=int(st.st_mtime), at=int(time.time()))
            self._save()

    def _keep_original(self, path):
        d = os.path.join(self.originals, time.strftime("%Y-%m-%d"))
        os.makedirs(d, exist_ok=True)
        dest = os.path.join(d, os.path.basename(path))
        n = 1
        while os.path.exists(dest):
            stem, ext = os.path.splitext(os.path.basename(path))
            dest = os.path.join(d, f"{stem}.{n}{ext}")
            n += 1
        shutil.copy2(path, dest)
        return dest

    def shift(self, sub_path, by, video=None):
        """Moves all lines of a subtitle file by `by` seconds (+ later, - earlier), keeping the original the first
        time. Returns the new total manual shift of this file."""
        text = mp_subs.readable_srt(sub_path, video)
        prev = (self.get(sub_path) or {}).get("manual", 0.0)
        if not prev:
            self._keep_original(sub_path)
        tmp = sub_path + ".mp-tmp"
        with open(tmp, "w", encoding="utf-8", newline="\n") as f:
            f.write(_rewrite(text, 1.0, by))
        os.replace(tmp, sub_path)
        r = dict(self.get_any(sub_path) or {}, manual=round(prev + by, 2))
        r.pop("size", None), r.pop("mtime", None), r.pop("at", None)
        self._remember(sub_path, r)
        self.log(f"subtitles shifted by hand: {os.path.basename(sub_path)} {by:+.2f} s (total {r['manual']:+.2f} s)")
        return r["manual"]

    def get_any(self, sub_path):
        """The last assessment of this file even when it changed since."""
        r = self.rec.get(os.path.realpath(sub_path))
        return _upgrade(r) if r else r

    def fit(self, video, sub_path, change=True, progress=None):
        """Assess `sub_path` against `video` and, when it fits and `change`, correct it in place (a text `.sub` becomes
        `.srt`). Returns {verdict, z, ratio, offset, changed, name}."""
        ext = os.path.splitext(sub_path)[1].lower()
        if ext not in (".srt", ".sub"):
            raise SyncError("only SRT and text SUB subtitles can be fitted")
        if ext == ".sub" and not is_text_sub(sub_path):
            raise SyncError("picture subtitles (VobSub) can't be fitted")
        text = mp_subs.readable_srt(sub_path, video)
        res = None
        emb = embedded(video)
        if emb:
            res = align(_signal(emb), cues(text))
            res["by"] = "embedded"
            if res["verdict"] != "good":
                res = None  # another release's embedded track, or these subtitles don't fit: the speech decides
        if res is None:
            res = align(speech(video, progress), cues(text))
            res["by"] = "speech"
        res.update(changed=False, name=os.path.basename(sub_path))
        if res.get("weak"):
            res["offset"] = 0.0  # the ratio only (see align)
        need = res["verdict"] == "good" and (abs(res["ratio"] - 1) > 1e-4 or abs(res["offset"]) >= MIN_SHIFT)
        convert = ext == ".sub"
        if change and (need or convert):  # a .sub becomes .srt even when it doesn't fit (it then at least shows)
            new_text = _rewrite(text, res["ratio"], res["offset"]) if need else text
            target = os.path.splitext(sub_path)[0] + ".srt"
            if convert and os.path.exists(target):
                raise SyncError(f"{os.path.basename(target)} already exists")
            self._keep_original(sub_path)
            tmp = target + ".mp-tmp"
            with open(tmp, "w", encoding="utf-8", newline="\n") as f:
                f.write(new_text)
            os.replace(tmp, target)
            if target != sub_path:
                os.remove(sub_path)
            self.log(f"subtitles fitted: {os.path.basename(target)} ratio {res['ratio']} offset {res['offset']} s z {res['z']}")
            res.update(changed=True, name=os.path.basename(target))
            sub_path = target
            if need:  # what is on disk now is the corrected file: no shift any more
                res.update(ratio=1.0, offset=0.0, fixed_ratio=res["ratio"], fixed_offset=res["offset"])
        self._remember(sub_path, res)
        return res
