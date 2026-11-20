"""Constants and helpers shared by the parts of the file task system (mp_tasks.py)."""
import os
import re
import shutil
import signal
import stat
import subprocess
import sys

from mp_i18n import T
from mp_library import VIDEO_EXT


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
# old picture → H.264 at the same resolution (modernize with `audio`, agent 1.16+): plays on every phone, TV and
# Chromecast; `kbps` is for 1080p
MODERN = {"same": True, "codec": "h264", "crf": 20, "cq": 23, "kbps": 3500, "audio": "keep"}
SUBS_PER_DAY = 20  # OpenSubtitles downloads a day without an account (what the counters plan with)
WATCHING_DAYS = 14  # a folder something was streamed from this recently is "being watched"
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
AUDIO_CHECK_BUDGET = 90  # seconds a preview spends checking files the background scan hasn't reached yet
OLD_GROWTH = 1.25  # an old-format file may come out up to this much bigger (it then plays, which is the point)


MIN_SIZE = int(os.environ.get("MP_TASKS_MIN_SIZE", 50 << 20))  # smaller videos (samples, extras) aren't worth shrinking
RESERVE = 0.05  # of a disk kept free; under it a shrink's originals go to the trash of another disk
ENCODERS = ("auto", "cpu", "gpu")
GPU_SIZE = {True: 1.1, False: 1.3}  # video size from a graphics card vs x265 at the same quality (rough guesses)


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


def _dev(path):
    try:
        return os.stat(path).st_dev
    except OSError:
        return None


class _Cancelled(Exception):
    pass


class _Held(Exception):
    """The owner paused the task (F4): the encode is suspended where it is and the task waits in the queue."""


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


def _mount(path):
    p = os.path.realpath(path)
    while not os.path.ismount(p):
        p = os.path.dirname(p)
    return p


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


def _seeding_text():
    return T("still seeding in the torrent client", "jeszcze seeduje w kliencie torrentów")


def _in_torrent(real, torrents):
    """Whether a file (real path) is a torrent's content or inside one."""
    if not torrents or not real:
        return False
    return real in torrents or any(real.startswith(c + os.sep) for c in torrents)
