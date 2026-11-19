"""File format vs Chromecast (ffprobe) and the "for Chromecast" version (ffmpeg, hardware-accelerated when the machine can).

The version lives next to the movie in a hidden directory `.mp-cast/<name>.cast.mp4` (same disk as the movie, the
Library does not show it). H.264 8-bit video is left as is, otherwise a hardware encoder (NVENC, VAAPI, VideoToolbox,
V4L2) or x264 is used. AAC/MP3 audio is kept, everything else → AAC stereo. One conversion at a time.
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

import mp_detached
from mp_i18n import T, bind

CAST_DIR = ".mp-cast"

# encoder → (human-readable label, arguments before -i, video filter, encoder arguments)
_SCALE = "scale=-2:'min(1080,ih)'"
ENCODERS = [
    ("h264_nvenc", "NVIDIA NVENC", [], f"{_SCALE},format=yuv420p",
     ["-preset", "slow", "-rc:v", "vbr", "-cq:v", "23", "-b:v", "0", "-maxrate", "20M", "-bufsize", "40M",
      "-profile:v", "high"]),
    ("h264_videotoolbox", "Apple VideoToolbox", [], f"{_SCALE},format=yuv420p",
     ["-b:v", "8M", "-profile:v", "high"]),
    ("h264_vaapi", "VAAPI (Intel/AMD)", ["-vaapi_device", "/dev/dri/renderD128"], f"{_SCALE},format=nv12,hwupload",
     ["-qp", "23", "-profile:v", "high"]),
    ("h264_qsv", "Intel Quick Sync", [], f"{_SCALE},format=nv12", ["-global_quality", "23", "-profile:v", "high"]),
    ("h264_v4l2m2m", "V4L2 (e.g. Raspberry Pi)", [], f"{_SCALE},format=yuv420p", ["-b:v", "8M"]),
    ("libx264", "CPU (x264)", [], f"{_SCALE},format=yuv420p",
     ["-preset", "veryfast", "-crf", "21", "-profile:v", "high"]),
]

LABEL_PL = {"h264_v4l2m2m": "V4L2 (np. Raspberry Pi)", "libx264": "procesor (x264)"}


def encoder_label(enc):
    """Encoder label in the language of the current request."""
    return T(enc["label"], LABEL_PL.get(enc["encoder"], enc["label"])) if enc else None


_hw = None
_hw_lock = threading.Lock()


def _test_encoder(name, pre, vf, args):
    """Whether the encoder really works (the `ffmpeg -encoders` list only says what ffmpeg was built with)."""
    if name == "h264_vaapi" and not os.path.exists("/dev/dri/renderD128"):
        return False
    cmd = ["ffmpeg", "-hide_banner", "-nostdin", "-loglevel", "error", *pre,
           "-f", "lavfi", "-i", "testsrc2=size=1280x720:rate=25:duration=1", "-vf", vf, "-c:v", name, *args,
           "-f", "null", "-"]
    try:
        return subprocess.run(cmd, capture_output=True, timeout=30).returncode == 0
    except (OSError, subprocess.TimeoutExpired):
        return False


def detect_encoder():
    """Best working H.264 encoder: {"encoder", "label", "hw"} or None (no ffmpeg). Computed once."""
    global _hw
    with _hw_lock:
        if _hw is not None:
            return _hw or None
        if not shutil.which("ffmpeg") or not shutil.which("ffprobe"):
            _hw = False
            return None
        try:
            listed = subprocess.run(["ffmpeg", "-hide_banner", "-encoders"], capture_output=True, text=True,
                                    timeout=20).stdout
        except (OSError, subprocess.TimeoutExpired):
            listed = ""
        for name, label, pre, vf, args in ENCODERS:
            if re.search(rf"\s{name}\s", listed) and _test_encoder(name, pre, vf, args):
                _hw = {"encoder": name, "label": label, "hw": name != "libx264"}
                break
        else:
            _hw = False
        return _hw or None


def encoder_info():
    """Without waiting: the detection result, or None while it is still running (starts in the background at agent start)."""
    return (_hw or None) if _hw is not None else None


# ---- HEVC encoders for shrinking (mp_tasks): the processor (x265) and the graphics card next to it.
# Old NVENC (Maxwell, e.g. GTX 970) can't do B-frames in HEVC, so the files come out larger at the same quality;
# Turing (RTX 20xx) and newer can. Each option is tried with a trial encode, from the full set of options down.
HW_HEVC = {
    "h264_nvenc": ("hevc_nvenc", "NVIDIA NVENC (HEVC)", [], "format=yuv420p", [
        (True, ["-bf", "3", "-b_ref_mode", "middle", "-spatial_aq", "1", "-temporal_aq", "1", "-rc-lookahead", "32"]),
        (True, ["-bf", "3", "-spatial_aq", "1", "-rc-lookahead", "32"]),
        (False, ["-bf", "0", "-spatial_aq", "1", "-rc-lookahead", "32"]),
        (False, ["-bf", "0"])]),
    "h264_vaapi": ("hevc_vaapi", "VAAPI (HEVC)", ["-vaapi_device", "/dev/dri/renderD128"], "format=nv12,hwupload", [
        (True, ["-bf", "2"]), (False, ["-bf", "0"])]),
}
_hevc = None
_hevc_lock = threading.Lock()


def _hevc_args(name, q):
    """Quality arguments of a HEVC encoder: `q` is the profile's NVENC CQ (also used as the VAAPI QP)."""
    if name == "hevc_nvenc":
        return ["-preset", "p6", "-tune", "hq", "-rc", "vbr", "-cq", str(q), "-b:v", "0"]
    return ["-qp", str(q)]


def _bench(pre, vf, args):
    """Seconds of work per second of 1080p video, or None when the encoder fails. Two trials (2 s and 8 s of
    video): the difference leaves out ffmpeg's start, which on a graphics card takes longer than the encode."""
    def run(seconds):
        cmd = ["ffmpeg", "-hide_banner", "-nostdin", "-loglevel", "error", *pre, "-f", "lavfi",
               "-i", f"testsrc2=size=1920x1080:rate=25:duration={seconds}", "-vf", vf, *args, "-f", "null", "-"]
        if sys.platform != "win32" and shutil.which("nice"):
            cmd = ["nice", "-n", "19", *cmd]
        t0 = time.time()
        try:
            ok = subprocess.run(cmd, capture_output=True, timeout=180).returncode == 0
        except (OSError, subprocess.TimeoutExpired):
            return None
        return time.time() - t0 if ok else None
    short = run(2)
    if short is None:
        return None
    long = run(8)
    if long is None:
        return None
    return max(long - short, 0.05) / 6


def detect_hevc():
    """HEVC encoders for shrinking: {"cpu": {...}, "gpu": {...}?}; each has "encoder", "label", "hw", "bframes",
    "pre", "vf" (appended to the scale filter), "args" (without quality) and "speed" (pixel·seconds of video per
    second of work, from a trial on a synthetic picture: only a rough first guess, real encodes refine it).
    Computed once, in the background after detect_encoder()."""
    global _hevc
    with _hevc_lock:
        if _hevc is not None:
            return _hevc
        out = {}
        h264 = detect_encoder()
        px = 1920 * 1080
        if h264:
            if (t := _bench([], "format=yuv420p", ["-c:v", "libx265", "-preset", "fast", "-crf", "24",
                                                   "-x265-params", "log-level=error"])):
                out["cpu"] = {"encoder": "libx265", "label": "x265", "hw": False, "bframes": True, "pre": [],
                              "vf": "format=yuv420p", "args": [], "speed": px / t}
            hw = HW_HEVC.get(h264["encoder"])
            if hw and (h264["encoder"] != "h264_vaapi" or os.path.exists("/dev/dri/renderD128")):
                name, label, pre, vf, tries = hw
                for bframes, args in tries:
                    if (t := _bench(pre, vf, ["-c:v", name, *_hevc_args(name, 27), *args])):
                        out["gpu"] = {"encoder": name, "label": label, "hw": True, "bframes": bframes, "pre": pre,
                                      "vf": vf, "args": args, "speed": px / t}
                        break
        _hevc = out
        return _hevc


def hevc_info():
    """Without waiting: the detected HEVC encoders, or None while detection still runs."""
    return _hevc


def hevc_cmd(enc, q):
    """(input options, filter suffix, video codec arguments) of a detected HEVC encoder for quality `q` (CRF for
    x265, CQ/QP for the graphics card)."""
    if enc["encoder"] == "libx265":
        return [], enc["vf"], ["-c:v", "libx265", "-preset", "fast", "-crf", str(q), "-x265-params", "log-level=error"]
    return enc["pre"], enc["vf"], ["-c:v", enc["encoder"], *_hevc_args(enc["encoder"], q), *enc["args"]]


_probes = {}
_probe_lock = threading.Lock()


def probe(path):
    """Streams of the file (ffprobe), cached by (mtime, size)."""
    st = os.stat(path)
    key = (path, st.st_mtime, st.st_size)
    with _probe_lock:
        if key in _probes:
            return _probes[key]
    out = subprocess.run(["ffprobe", "-v", "error", "-print_format", "json", "-show_streams", "-show_format", path],
                         capture_output=True, text=True, timeout=60)
    if out.returncode != 0:
        raise ValueError((out.stderr or T("ffprobe couldn't read the file", "ffprobe nie przeczytał pliku")).strip()[-300:])
    d = json.loads(out.stdout or "{}")
    video, audio, subs = None, [], []
    for s in d.get("streams", []):
        t, tags = s.get("codec_type"), s.get("tags") or {}
        if t == "video" and video is None and not (s.get("disposition") or {}).get("attached_pic"):
            pix = s.get("pix_fmt") or ""
            video = {"codec": s.get("codec_name"), "profile": s.get("profile"), "pix_fmt": pix,
                     "bits": 10 if "10" in pix or "12" in pix else 8,
                     "width": s.get("width"), "height": s.get("height")}
        elif t == "audio":
            audio.append({"index": len(audio), "codec": s.get("codec_name"), "channels": s.get("channels"),
                          "lang": tags.get("language"), "default": bool((s.get("disposition") or {}).get("default"))})
        elif t == "subtitle":
            subs.append({"codec": s.get("codec_name"), "lang": tags.get("language")})
    fmt = d.get("format") or {}
    res = {"container": fmt.get("format_name"), "duration": float(fmt.get("duration") or 0) or None,
           "video": video, "audio": audio, "subs": subs}
    with _probe_lock:
        _probes[key] = res
    return res


def pick_audio(info, langs=("pol", "pl")):
    """Audio track for the Chromecast version: Polish, then the default one, then the first."""
    a = info["audio"]
    return (next((x for x in a if (x["lang"] or "") in langs), None) or next((x for x in a if x["default"]), None)
            or (a[0] if a else None))


AUDIO_OK = {"aac", "mp3"}
AUDIO_MAYBE = {"ac3", "eac3", "opus", "vorbis", "flac"}


def cast_check(info):
    """Chromecast verdict: "ok" | "maybe" | "no", the reasons and what a conversion would do."""
    v, a = info["video"], pick_audio(info)
    problems, verdict = [], "ok"
    v_copy = bool(v and v["codec"] == "h264" and v["bits"] == 8 and (v["height"] or 0) <= 1088)
    if not v:
        problems.append(T("no video", "brak obrazu"))
        verdict = "no"
    elif v_copy:
        pass
    elif v["codec"] == "hevc" and v["bits"] == 8:
        problems.append(T("HEVC video: only a newer Chromecast will play it (with Google TV, Ultra)",
                          "obraz HEVC: zagra tylko nowszy Chromecast (z Google TV, Ultra)"))
        verdict = "maybe"
    else:
        what = f"{v['codec'].upper()}{' 10-bit' if v['bits'] > 8 else ''}{' ' + str(v['height']) + 'p' if v['height'] else ''}"
        problems.append(T(f"{what} video: Chromecast probably won't play it", f"obraz {what}: Chromecast raczej nie zagra"))
        verdict = "no"
    a_copy = bool(a and a["codec"] in AUDIO_OK)
    if a and not a_copy:
        name = (a["codec"] or "?").upper()
        if a["codec"] in AUDIO_MAYBE:
            problems.append(T(f"{name} audio: depends on the Chromecast and TV (may play without sound)",
                              f"dźwięk {name}: zależy od Chromecasta i telewizora (może grać bez dźwięku)"))
            verdict = "maybe" if verdict == "ok" else verdict
        else:
            problems.append(T(f"{name} audio: Chromecast won't play it", f"dźwięk {name}: Chromecast nie zagra"))
            verdict = "no"
    if "matroska" in (info["container"] or "") and verdict == "ok":
        problems.append(T("MKV container: usually works", "kontener MKV: zwykle działa"))
    return {"verdict": verdict, "problems": problems, "video_copy": v_copy, "audio_copy": a_copy,
            "audio": a and {"codec": a["codec"], "lang": a["lang"]}}


def cast_check_for(info, prof):
    """Verdict for a specific Chromecast (profile from mp_renderers.CAST_PROFILES): "ok" | "maybe" | "no" and the reasons."""
    v, a = info["video"], pick_audio(info)
    problems, verdict = [], "ok"

    def worse(x):
        nonlocal verdict
        if x == "no" or (x == "maybe" and verdict == "ok"):
            verdict = x
    if not v:
        problems.append(T("no video", "brak obrazu")); worse("no")
    else:
        lim = prof["video"].get(v["codec"])
        what = f"{(v['codec'] or '?').upper()}{' 10-bit' if v['bits'] > 8 else ''}" \
               f"{' ' + str(v['height']) + 'p' if v['height'] else ''}"
        if not lim:
            problems.append(T(f"{what} video: this Chromecast has no such decoder",
                              f"obraz {what}: ten Chromecast nie ma takiego dekodera")); worse("no")
        elif (v["height"] or 0) > lim[0] + 8 or v["bits"] > lim[1]:
            problems.append(T(f"{what} video: too much for this Chromecast (up to {lim[0]}p, {lim[1]}-bit)",
                              f"obraz {what}: za dużo dla tego Chromecasta (do {lim[0]}p, {lim[1]}-bit)")); worse("no")
    if a:
        c, name = a["codec"], (a["codec"] or "?").upper()
        if c in prof["audio"]:
            pass
        elif c in prof["passthrough"]:
            problems.append(T(f"{name} audio: Chromecast passes it to the TV; without TV support there will be no sound",
                              f"dźwięk {name}: Chromecast przekaże go telewizorowi; bez obsługi w TV będzie cisza")); worse("maybe")
        else:
            problems.append(T(f"{name} audio: this Chromecast won't play it", f"dźwięk {name}: ten Chromecast go nie odtworzy")); worse("no")
    if "matroska" in (info["container"] or "") and not prof.get("mkv") and verdict == "ok":
        problems.append(T("MKV container: usually works", "kontener MKV: zwykle działa"))
    return {"verdict": verdict, "problems": problems}


def variant_path(full):
    d, base = os.path.split(full)
    return os.path.join(d, CAST_DIR, os.path.splitext(base)[0] + ".cast.mp4")


def variant(full):
    p = variant_path(full)
    return p if os.path.isfile(p) else None


def remove_variant(full):
    p = variant_path(full)
    for f in (p, p + ".part"):
        try:
            os.remove(f)
        except OSError:
            pass
    try:
        os.rmdir(os.path.dirname(p))  # only when empty
    except OSError:
        pass


class Converter:
    """Queue of "for Chromecast" conversions, one at a time. The queue is in memory; the running conversion is detached
    (mp_detached), so after an agent restart it goes on and is picked up again, while the queued ones are forgotten."""

    def __init__(self, log, encodes=None):
        self.log = log
        self.lock = threading.Lock()
        self.jobs = {}  # full path → state
        self.queue = []
        self.proc = None
        self.worker = None
        self.encodes = encodes
        if encodes:
            for p in mp_detached.Detached.all(encodes, "convert"):
                m = p.meta
                if not m.get("full"):
                    p.terminate()
                    p.remove()
                    continue
                j = {"state": "running", "title": m.get("title"), "pct": 0, "speed": None, "error": None,
                     "started": m.get("started"), "check": m.get("check"), "encoder": m.get("encoder")}
                self.jobs[m["full"]] = j
                self.log("conversion: attached to the running one of", os.path.basename(m["full"]))
                self.worker = threading.Thread(target=bind(self._resume), args=(m["full"], j, p), daemon=True)
                self.worker.start()

    def _resume(self, full, j, p):
        try:
            self._watch(full, j, p)
        except Exception as e:  # noqa: BLE001
            with self.lock:
                if j["state"] != "cancelled":
                    j.update(state="error", error=str(e)[-300:])
            self.log("conversion failed:", os.path.basename(full), e)
        self._loop()  # whatever was queued meanwhile

    def status(self, full):
        with self.lock:
            j = self.jobs.get(full)
            return dict(j) if j else None

    def all(self):
        with self.lock:
            return [dict(j, path=p) for p, j in self.jobs.items()]

    def start(self, full, title):
        if not detect_encoder():
            raise RuntimeError(T("this server has no ffmpeg (install it: install-root.sh)", "na tym serwerze nie ma ffmpeg (zainstaluj: install-root.sh)"))
        info = probe(full)
        free = shutil.disk_usage(os.path.dirname(full)).free
        if free < os.path.getsize(full) * 1.1 + (1 << 30):
            raise RuntimeError(T("not enough disk space next to the video", "za mało miejsca na dysku obok filmu"))
        with self.lock:
            j = self.jobs.get(full)
            if j and j["state"] in ("queued", "running"):
                return dict(j)
            j = {"state": "queued", "title": title, "pct": 0, "speed": None, "error": None, "started": None,
                 "check": cast_check(info), "encoder": None}
            self.jobs[full] = j
            self.queue.append(full)
            if not self.worker or not self.worker.is_alive():
                self.worker = threading.Thread(target=bind(self._loop), daemon=True)
                self.worker.start()
            return dict(j)

    def cancel(self, full):
        with self.lock:
            j = self.jobs.get(full)
            if not j:
                return
            if full in self.queue:
                self.queue.remove(full)
                del self.jobs[full]
            elif j["state"] == "running" and self.proc:
                j["state"] = "cancelled"
                self.proc.terminate()

    def _loop(self):
        while True:
            with self.lock:
                if not self.queue:
                    return
                full = self.queue.pop(0)
                j = self.jobs[full]
                j.update(state="running", started=time.time())
            try:
                self._run(full, j)
            except Exception as e:  # noqa: BLE001 — a failure of one conversion does not stop the queue
                with self.lock:
                    if j["state"] != "cancelled":
                        j.update(state="error", error=str(e)[-300:])
                self.log("conversion failed:", os.path.basename(full), e)

    def _cmd(self, full, out, info, chk, enc):
        name, label, pre, vf, args = next(e for e in ENCODERS if e[0] == enc["encoder"])
        a = pick_audio(info)
        cmd = ["ffmpeg", "-hide_banner", "-nostdin", "-y", "-loglevel", "error"]
        if not chk["video_copy"]:
            cmd += pre
        cmd += ["-i", full, "-map", "0:v:0"]
        if a:
            cmd += ["-map", f"0:a:{a['index']}"]
        if chk["video_copy"]:
            cmd += ["-c:v", "copy"]
        else:
            cmd += ["-vf", vf, "-c:v", name, *args]
        if a:
            cmd += ["-c:a", "copy"] if chk["audio_copy"] else ["-c:a", "aac", "-b:a", "192k", "-ac", "2"]
        cmd += ["-sn", "-dn", "-movflags", "+faststart", "-f", "mp4", "-progress", "pipe:1", "-nostats", out]
        if sys.platform != "win32" and shutil.which("nice"):
            cmd = ["nice", "-n", "10", *cmd]
        return cmd

    def _run(self, full, j):
        info, enc = probe(full), detect_encoder()
        chk = cast_check(info)
        out = variant_path(full)
        os.makedirs(os.path.dirname(out), exist_ok=True)
        part = out + ".part"
        j["encoder"] = T("no video encoding", "bez kodowania obrazu") if chk["video_copy"] else encoder_label(enc)
        cmd = self._cmd(full, part, info, chk, enc)
        self.log("conversion:", os.path.basename(full), "→", j["encoder"])
        meta = {"full": full, "part": part, "out": out, "dur": info["duration"] or 0, "title": j["title"],
                "started": j["started"], "check": chk, "encoder": j["encoder"]}
        if self.encodes:
            os.makedirs(self.encodes, exist_ok=True)
            p = mp_detached.Detached.start(self.encodes, cmd, meta, "convert")
        else:
            p = _Inline(cmd, meta)
        self._watch(full, j, p)

    def _watch(self, full, j, p):
        m = p.meta
        part, out, dur = m["part"], m["out"], m.get("dur") or 0
        with self.lock:
            self.proc = p
        while p.poll() is None:
            pr = p.progress()
            v = pr.get("out_time_us", "")
            if v.isdigit() and dur:
                j["pct"] = min(99.9, int(v) / 1e6 / dur * 100)
                # at the end +faststart rewrites the whole file (index moved to the front): on a USB disk that can take a minute at 99%
                if j["pct"] >= 99.5:
                    j["finishing"] = True
            if pr.get("progress") == "end":
                j["finishing"] = True
            sp = pr.get("speed", "")
            if sp.endswith("x"):
                try:
                    j["speed"] = float(sp[:-1])
                except ValueError:
                    pass
            time.sleep(1)
        err = "\n".join(p.err_tail(5))
        p.remove()
        with self.lock:
            self.proc = None
        if j["state"] == "cancelled":
            remove_variant(full)
            with self.lock:
                self.jobs.pop(full, None)
            return
        if p.returncode != 0:
            try:
                os.remove(part)
            except OSError:
                pass
            raise RuntimeError((err or T(f"ffmpeg exited with code {p.returncode}", f"ffmpeg zakończył się kodem {p.returncode}")).strip())
        os.replace(part, out)
        j.update(state="done", pct=100)
        self.log("conversion done:", os.path.basename(out), f"{time.time() - (j['started'] or time.time()):.0f}s")


class _Inline:
    """A conversion as a plain child process (no place for detached runs): the same face as mp_detached.Detached."""

    def __init__(self, cmd, meta):
        import tempfile
        self.meta = meta
        self._out = tempfile.NamedTemporaryFile(prefix="mp-conv-", suffix=".progress", delete=False)
        self._err = tempfile.NamedTemporaryFile(prefix="mp-conv-", suffix=".err", delete=False)
        self.p = subprocess.Popen(cmd, stdout=self._out, stderr=self._err)
        self.pid = self.p.pid

    def poll(self):
        return self.p.poll()

    @property
    def returncode(self):
        return self.p.returncode

    def terminate(self):
        self.p.terminate()

    def progress(self):
        out = {}
        with open(self._out.name, "rb") as f:
            f.seek(0, 2)
            f.seek(max(0, f.tell() - 4096))
            for line in f.read().decode("utf-8", "replace").splitlines():
                k, _, v = line.strip().partition("=")
                if k and v:
                    out[k] = v
        return out

    def err_tail(self, n=5):
        with open(self._err.name, encoding="utf-8", errors="replace") as f:
            return f.read().strip().splitlines()[-n:]

    def remove(self):
        for f in (self._out, self._err):
            f.close()
            try:
                os.remove(f.name)
            except OSError:
                pass


# ---- covers (plan-library.md, stage 5): a picture next to the video first, otherwise a frame from ~10% in, cached
COVER_NAMES = ("poster", "folder", "cover", "movie", "show")
COVER_EXT = (".jpg", ".jpeg", ".png", ".webp")
VIDEO_EXT = {".mkv", ".mp4", ".avi", ".m4v", ".mov", ".webm", ".wmv", ".mpg", ".mpeg", ".ts", ".m2ts", ".flv", ".ogv"}  # as mp_library
_cover_sem = threading.Semaphore(2)


def cover_dir():
    base = os.environ.get("XDG_CACHE_HOME") or os.path.expanduser("~/.cache")
    if sys.platform == "win32":
        base = os.path.join(os.environ.get("LOCALAPPDATA") or os.path.expanduser("~"), "magneticplayer", "cache")
        return os.path.join(base, "covers")
    return os.path.join(base, "magneticplayer", "covers")


def _first_video(folder, depth=2):
    try:
        names = sorted(os.listdir(folder), key=str.lower)
    except OSError:
        return None
    vids = [n for n in names if not n.startswith(".") and os.path.splitext(n)[1].lower() in VIDEO_EXT
            and "sample" not in n.lower()]
    if vids:  # the biggest one: the movie, not an extra
        return max((os.path.join(folder, n) for n in vids), key=lambda p: os.stat(p).st_size)
    if depth:
        for n in names:
            p = os.path.join(folder, n)
            if not n.startswith(".") and os.path.isdir(p):
                v = _first_video(p, depth - 1)
                if v:
                    return v
    return None


def find_cover(full):
    """(path of an existing picture or None, video to take a frame from or None)."""
    if os.path.isdir(full):
        for n in COVER_NAMES:
            for e in COVER_EXT:
                p = os.path.join(full, n + e)
                if os.path.isfile(p):
                    return p, None
        return None, _first_video(full)
    stem = os.path.splitext(full)[0]
    for suf in ("", "-poster", ".poster", "-cover"):
        for e in COVER_EXT:
            if os.path.isfile(stem + suf + e):
                return stem + suf + e, None
    return None, full if os.path.splitext(full)[1].lower() in VIDEO_EXT else None


def cover(full, width=360):
    """Path of a JPEG/PNG cover for a folder or a video, or None (nothing to show)."""
    pic, video = find_cover(full)
    if pic:
        return pic
    if not video or not shutil.which("ffmpeg"):
        return None
    st = os.stat(video)
    key = hashlib.sha1(f"{video}|{st.st_size}|{st.st_mtime}|{width}".encode()).hexdigest()
    out = os.path.join(cover_dir(), key[:2], key + ".jpg")
    if os.path.exists(out):
        return out if os.path.getsize(out) else None
    with _cover_sem:
        if os.path.exists(out):
            return out if os.path.getsize(out) else None
        os.makedirs(os.path.dirname(out), exist_ok=True)
        try:
            dur = probe(video).get("duration") or 0
        except Exception:  # noqa: BLE001
            dur = 0
        at = max(dur * 0.1, 0) if dur else 30
        cmd = ["ffmpeg", "-hide_banner", "-nostdin", "-v", "error", "-y", "-ss", f"{at:.1f}", "-i", video, "-frames:v", "1",
               "-vf", f"scale={width}:-2", "-q:v", "4", out + ".part.jpg"]
        if sys.platform != "win32" and shutil.which("nice"):
            cmd = ["nice", "-n", "10", *cmd]
        try:
            subprocess.run(cmd, capture_output=True, timeout=60)
            os.replace(out + ".part.jpg", out)
        except (OSError, subprocess.TimeoutExpired):
            open(out, "w").close()  # remember the failure (an empty file), don't try on every request
            return None
        return out if os.path.getsize(out) else None
