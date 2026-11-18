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
    """Queue of "for Chromecast" conversions, one at a time. State is in memory (after an agent restart: starts over)."""

    def __init__(self, log):
        self.log = log
        self.lock = threading.Lock()
        self.jobs = {}  # full path → state
        self.queue = []
        self.proc = None
        self.worker = None

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
        dur = info["duration"] or 0
        with self.lock:
            self.proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        p = self.proc
        for line in p.stdout:
            k, _, v = line.strip().partition("=")
            if k == "out_time_us" and v.isdigit() and dur:
                j["pct"] = min(99.9, int(v) / 1e6 / dur * 100)
                # at the end +faststart rewrites the whole file (index moved to the front): on a USB disk that can take a minute at 99%
                if j["pct"] >= 99.5:
                    j["finishing"] = True
            elif k == "progress" and v == "end":
                j["finishing"] = True
            elif k == "speed" and v.endswith("x"):
                try:
                    j["speed"] = float(v[:-1])
                except ValueError:
                    pass
        err = p.stderr.read()
        p.wait()
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
        self.log("conversion done:", os.path.basename(out), f"{time.time() - j['started']:.0f}s")


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
