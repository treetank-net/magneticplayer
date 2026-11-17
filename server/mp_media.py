"""Format pliku a Chromecast (ffprobe) i wersja „dla Chromecasta” (ffmpeg, sprzętowo, gdy maszyna umie).

Wersja leży obok filmu w ukrytym katalogu `.mp-cast/<nazwa>.cast.mp4` (ten sam dysk co film, Biblioteka jej nie
pokazuje). Obraz H.264 8-bit zostaje bez zmian, inaczej koder sprzętowy (NVENC, VAAPI, VideoToolbox, V4L2) albo x264.
Dźwięk AAC/MP3 zostaje, reszta → AAC stereo. Jedna konwersja naraz.
"""
import json
import os
import re
import shutil
import subprocess
import sys
import threading
import time

CAST_DIR = ".mp-cast"

# koder → (etykieta dla ludzi, argumenty przed -i, filtr obrazu, argumenty kodera)
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
    ("h264_v4l2m2m", "V4L2 (np. Raspberry Pi)", [], f"{_SCALE},format=yuv420p", ["-b:v", "8M"]),
    ("libx264", "procesor (x264)", [], f"{_SCALE},format=yuv420p",
     ["-preset", "veryfast", "-crf", "21", "-profile:v", "high"]),
]

_hw = None
_hw_lock = threading.Lock()


def _test_encoder(name, pre, vf, args):
    """Czy koder naprawdę działa (lista z `ffmpeg -encoders` mówi tylko, z czym ffmpeg zbudowano)."""
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
    """Najlepszy działający koder H.264: {"encoder", "label", "hw"} albo None (brak ffmpeg). Liczone raz."""
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
    """Bez czekania: wynik wykrywania albo None, gdy jeszcze trwa (startuje w tle przy starcie agenta)."""
    return (_hw or None) if _hw is not None else None


_probes = {}
_probe_lock = threading.Lock()


def probe(path):
    """Strumienie pliku (ffprobe), z pamięcią po (mtime, size)."""
    st = os.stat(path)
    key = (path, st.st_mtime, st.st_size)
    with _probe_lock:
        if key in _probes:
            return _probes[key]
    out = subprocess.run(["ffprobe", "-v", "error", "-print_format", "json", "-show_streams", "-show_format", path],
                         capture_output=True, text=True, timeout=60)
    if out.returncode != 0:
        raise ValueError((out.stderr or "ffprobe nie przeczytał pliku").strip()[-300:])
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
    """Ścieżka dźwięku dla wersji Chromecasta: polska, potem domyślna, potem pierwsza."""
    a = info["audio"]
    return (next((x for x in a if (x["lang"] or "") in langs), None) or next((x for x in a if x["default"]), None)
            or (a[0] if a else None))


AUDIO_OK = {"aac", "mp3"}
AUDIO_MAYBE = {"ac3", "eac3", "opus", "vorbis", "flac"}


def cast_check(info):
    """Ocena dla Chromecasta: "ok" | "maybe" | "no", powody i co by robiła konwersja."""
    v, a = info["video"], pick_audio(info)
    problems, verdict = [], "ok"
    v_copy = bool(v and v["codec"] == "h264" and v["bits"] == 8 and (v["height"] or 0) <= 1088)
    if not v:
        problems.append("brak obrazu")
        verdict = "no"
    elif v_copy:
        pass
    elif v["codec"] == "hevc" and v["bits"] == 8:
        problems.append("obraz HEVC: zagra tylko nowszy Chromecast (z Google TV, Ultra)")
        verdict = "maybe"
    else:
        problems.append(f"obraz {v['codec'].upper()}{' 10-bit' if v['bits'] > 8 else ''}"
                        f"{' ' + str(v['height']) + 'p' if v['height'] else ''}: Chromecast raczej nie zagra")
        verdict = "no"
    a_copy = bool(a and a["codec"] in AUDIO_OK)
    if a and not a_copy:
        name = (a["codec"] or "?").upper()
        if a["codec"] in AUDIO_MAYBE:
            problems.append(f"dźwięk {name}: zależy od Chromecasta i telewizora (może grać bez dźwięku)")
            verdict = "maybe" if verdict == "ok" else verdict
        else:
            problems.append(f"dźwięk {name}: Chromecast nie zagra")
            verdict = "no"
    if "matroska" in (info["container"] or "") and verdict == "ok":
        problems.append("kontener MKV: zwykle działa")
    return {"verdict": verdict, "problems": problems, "video_copy": v_copy, "audio_copy": a_copy,
            "audio": a and {"codec": a["codec"], "lang": a["lang"]}}


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
        os.rmdir(os.path.dirname(p))  # tylko gdy pusty
    except OSError:
        pass


class Converter:
    """Kolejka konwersji „dla Chromecasta”, po jednej naraz. Stan w pamięci (po restarcie agenta: od nowa)."""

    def __init__(self, log):
        self.log = log
        self.lock = threading.Lock()
        self.jobs = {}  # pełna ścieżka → stan
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
            raise RuntimeError("na tym serwerze nie ma ffmpeg (zainstaluj: install-root.sh)")
        info = probe(full)
        free = shutil.disk_usage(os.path.dirname(full)).free
        if free < os.path.getsize(full) * 1.1 + (1 << 30):
            raise RuntimeError("za mało miejsca na dysku obok filmu")
        with self.lock:
            j = self.jobs.get(full)
            if j and j["state"] in ("queued", "running"):
                return dict(j)
            j = {"state": "queued", "title": title, "pct": 0, "speed": None, "error": None, "started": None,
                 "check": cast_check(info), "encoder": None}
            self.jobs[full] = j
            self.queue.append(full)
            if not self.worker or not self.worker.is_alive():
                self.worker = threading.Thread(target=self._loop, daemon=True)
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
            except Exception as e:  # noqa: BLE001 — błąd jednej konwersji nie zatrzymuje kolejki
                with self.lock:
                    if j["state"] != "cancelled":
                        j.update(state="error", error=str(e)[-300:])
                self.log("konwersja nieudana:", os.path.basename(full), e)

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
        j["encoder"] = "bez kodowania obrazu" if chk["video_copy"] else enc["label"]
        cmd = self._cmd(full, part, info, chk, enc)
        self.log("konwersja:", os.path.basename(full), "→", j["encoder"])
        dur = info["duration"] or 0
        with self.lock:
            self.proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        p = self.proc
        for line in p.stdout:
            k, _, v = line.strip().partition("=")
            if k == "out_time_us" and v.isdigit() and dur:
                j["pct"] = min(99.9, int(v) / 1e6 / dur * 100)
                # na końcu +faststart przepisuje cały plik (indeks na początek): na dysku USB to i minuta przy 99%
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
            raise RuntimeError((err or f"ffmpeg zakończył się kodem {p.returncode}").strip())
        os.replace(part, out)
        j.update(state="done", pct=100)
        self.log("konwersja gotowa:", os.path.basename(out), f"{time.time() - j['started']:.0f}s")
