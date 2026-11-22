"""Re-encode (advanced), docs/plan-ux-tasks.md F3 + B6 (agent 1.18.1+): several changes to a file in one ffmpeg run.

The owner turns on some of six steps (board order: resolution, video codec, old picture, sound, Chromecast,
container) and each file gets one encode built from the steps it needs, always in the same order:
sound fix and deinterlace first, then denoise, crop, scale and the video codec; audio additions (the AAC track for a
Chromecast, subtitles written next to the file); the container last. A step a file doesn't need is skipped for that
file (a file already at 480p isn't scaled, H.265 isn't encoded again unless the picture changes, a progressive picture
isn't deinterlaced) and the preview lists the steps that apply to each file. A file nothing applies to isn't in the
plan.

Steps (`steps` in the plan body and in saved sets), every key optional, `on` false = skipped:

    {"resolution": {"on": true, "height": 480},                      720 / 480 / 360, never enlarged
     "codec": {"on": true, "mode": "hevc_saving"},                   hevc_saving / hevc_quality / h264
     "picture": {"on": true, "deinterlace": true, "denoise": false, "crop": true},
     "sound": {"on": true, "mode": "fix"},                           fix (damaged tracks to AAC) / loudness / leave
     "cast": {"on": true, "add_aac": true, "subs_to_srt": true},
     "container": {"on": true, "format": "mkv"}}                     mkv / mp4

Interlacing and black bars are measured (ffmpeg idet + cropdetect on three 3-second samples) while the preview is made,
within a time budget; files past it are measured when their encode starts. The counters never measure: they use what
earlier previews found and count the rest as "to check".

Items are ordinary shrink items (`op: shrink`) with a `reencode` dict, so the encode runs detached, survives restarts,
is paused and governed while somebody watches, verified and swapped exactly like the other encodes (mp_tasks_shrink).

Saved step sets: `reencode_presets` in the config, next to the built-in "Series archive", "For Chromecast" and
"Old VHS" (GET/POST /api/config/jobs).
"""
import os
import re
import secrets
import subprocess
import time

import mp_media
from mp_i18n import T
from mp_tasks_common import (
    AUDIO_SUSPECT, GPU_SIZE, MIN_SIZE, OLD_GROWTH, PLAYABLE_AUDIO, TaskError, _audio_src_kbps, _in_torrent, _lang2, _nice,
    _rm, _seeding_text, _title, is_old
)
from mp_tasks_layout import CAST_SPEED, TEXT_SUBS

STEPS = ("resolution", "codec", "picture", "sound", "cast", "container")  # the board's order (not the encode's)
HEIGHTS = (720, 480, 360)
# video codec modes: x265 CRF / graphics card CQ, estimated video kb/s at 1080p (scaled by height^1.5 like the others)
CODECS = {"hevc_saving": {"crf": 27, "cq": 30, "kbps": 1250},
          "hevc_quality": {"crf": 22, "cq": 25, "kbps": 2600},
          "h264": {"codec": "h264", "crf": 20, "cq": 23, "kbps": 3500}}
SOUND_MODES = ("fix", "loudness", "leave")
FORMATS = ("mkv", "mp4")
DEFAULTS = {"resolution": {"on": False, "height": 720},
            "codec": {"on": False, "mode": "hevc_saving"},
            "picture": {"on": False, "deinterlace": True, "denoise": False, "crop": True},
            "sound": {"on": False, "mode": "fix"},
            "cast": {"on": False, "add_aac": True, "subs_to_srt": True},
            "container": {"on": False, "format": "mkv"}}
MP4_AUDIO = {"aac", "mp3", "ac3", "eac3"}  # copied into MP4 as they are; anything else becomes AAC
MP4_VIDEO = {"h264", "hevc", "av1", "mpeg4"}  # pictures MP4 takes without re-encoding
DENOISE = "hqdn3d=3:2:4:3"  # moderate: tape noise goes, detail mostly stays
LOUDNORM = "aresample=async=1:first_pts=0,loudnorm=I=-16:TP=-1.5:LRA=11,aresample=48000"
DETECT_BUDGET = 240  # seconds a preview spends measuring interlacing and black bars
DETECT_V = 1
MAX_PRESETS = 20


def _builtins():
    """The built-in step sets, named in the request's language."""
    def s(**on):
        out = {k: dict(v, on=False) for k, v in DEFAULTS.items()}
        for k, v in on.items():
            out[k] = dict(out[k], on=True, **v)
        return out
    return [
        {"id": "series_archive", "name": T("Series archive", "Archiwum seriali"), "builtin": True,
         "steps": s(resolution={"height": 480}, codec={"mode": "hevc_saving"}, sound={"mode": "fix"},
                    container={"format": "mkv"})},
        {"id": "chromecast", "name": T("For Chromecast", "Pod Chromecasta"), "builtin": True,
         "steps": s(codec={"mode": "h264"}, sound={"mode": "fix"}, cast={"add_aac": True, "subs_to_srt": True},
                    container={"format": "mp4"})},
        {"id": "old_vhs", "name": T("Old VHS", "Stare VHS"), "builtin": True,
         "steps": s(picture={"deinterlace": True, "denoise": True, "crop": True}, codec={"mode": "hevc_quality"},
                    sound={"mode": "loudness"}, container={"format": "mkv"})},
    ]


def norm_steps(d):
    """Steps with every key and option filled in (defaults), checked; TaskError for a wrong value."""
    if not isinstance(d, dict):
        raise TaskError(T("steps must be an object", "steps to obiekt"))
    out = {}
    for k, dv in DEFAULTS.items():
        x = d.get(k) or {}
        if not isinstance(x, dict):
            raise TaskError(T(f"step {k} must be an object", f"krok {k} to obiekt"))
        s = dict(dv)
        s["on"] = bool(x.get("on", False))
        for opt in dv:
            if opt != "on" and opt in x:
                s[opt] = x[opt]
        out[k] = s
    try:
        out["resolution"]["height"] = int(out["resolution"]["height"])
    except (TypeError, ValueError):
        out["resolution"]["height"] = None
    if out["resolution"]["height"] not in HEIGHTS:
        raise TaskError(T("resolution height must be 720, 480 or 360", "wysokość to 720, 480 albo 360"))
    if out["codec"]["mode"] not in CODECS:
        raise TaskError(T("codec mode must be hevc_saving, hevc_quality or h264", "tryb kodeka to hevc_saving, hevc_quality albo h264"))
    if out["sound"]["mode"] not in SOUND_MODES:
        raise TaskError(T("sound mode must be fix, loudness or leave", "tryb dźwięku to fix, loudness albo leave"))
    if out["container"]["format"] not in FORMATS:
        raise TaskError(T("container format must be mkv or mp4", "kontener to mkv albo mp4"))
    for k in ("picture", "cast"):
        for opt in out[k]:
            out[k][opt] = bool(out[k][opt])
    return out


def active(steps):
    """The steps that can change anything (on, with something chosen inside)."""
    a = set()
    for k in STEPS:
        s = steps[k]
        if not s["on"]:
            continue
        if k == "picture" and not (s["deinterlace"] or s["denoise"] or s["crop"]):
            continue
        if k == "cast" and not (s["add_aac"] or s["subs_to_srt"]):
            continue
        if k == "sound" and s["mode"] == "leave":
            continue
        a.add(k)
    return a


def warnings(steps):
    """Pointless or surprising combinations of the chosen steps, in the request's language."""
    a, out = active(steps), []
    low = "resolution" in a and steps["resolution"]["height"] == 360
    pic = steps["picture"]
    if low and "picture" in a and pic["denoise"]:
        out.append(T("Denoise at 360p: downscaling already hides the noise, it only adds time",
                     "Odszumianie przy 360p: zmniejszenie i tak ukrywa szum, tylko wydłuża pracę"))
    if low and "picture" in a and pic["crop"]:
        out.append(T("Cropping black bars at 360p saves almost nothing",
                     "Przycinanie czarnych pasów przy 360p prawie nic nie oszczędza"))
    if low and "codec" in a and steps["codec"]["mode"] == "hevc_quality":
        out.append(T("H.265 quality at 360p: the saving option looks the same at this size",
                     "H.265 jakość przy 360p: przy tym rozmiarze oszczędny wygląda tak samo"))
    if ("picture" in a or "resolution" in a) and "codec" not in a:
        out.append(T("Picture changes need a new encode: without the video codec step it is H.265 (saving)",
                     "Zmiany obrazu wymagają kodowania: bez kroku kodeka wideo będzie to H.265 (oszczędnie)"))
    if "sound" in a and steps["sound"]["mode"] == "loudness" and "cast" in a and steps["cast"]["add_aac"]:
        out.append(T("Even loudness makes every track AAC already: the extra AAC track is skipped",
                     "Wyrównana głośność i tak robi z każdej ścieżki AAC: dodatkowa ścieżka AAC zostanie pominięta"))
    if "container" in a and steps["container"]["format"] == "mp4":
        out.append(T("MP4 can't keep image subtitles (PGS, DVD) or fonts: they are dropped; text subtitles stay",
                     "MP4 nie zmieści napisów obrazkowych (PGS, DVD) ani czcionek: wypadną; napisy tekstowe zostają"))
    return out


def _res_class(v):
    h, w = v.get("height") or 0, v.get("width") or 0
    return max(h, int(round(w * 9 / 16)))


def _cur_format(container):
    c = (container or "").lower()
    return "mp4" if "mp4" in c else "mkv" if "matroska" in c else c.split(",")[0] or "?"


def detect(full, info):
    """Interlacing and black bars of a video: {"interlaced": bool, "crop": "w:h:x:y" or None}. Three 3-second
    samples (20, 50, 80 %), decoded at the lowest priority; `cropdetect` accumulates (reset=0), so a sample's last
    line is its union, and the samples are united too (a dark scene alone would crop too much)."""
    v = info.get("video") or {}
    W, H, dur = v.get("width") or 0, v.get("height") or 0, info.get("duration") or 0
    pos = [dur * x for x in (0.2, 0.5, 0.8)] if dur > 60 else [0.0]
    box, fields, prog = None, 0, 0
    for p in pos:
        cmd = _nice(["ffmpeg", "-hide_banner", "-nostdin", "-v", "info", "-nostats", "-ss", f"{p:.1f}", "-i", full,
                     "-t", "3", "-map", "0:v:0", "-vf", "idet,cropdetect=limit=0.1:round=2:reset=0", "-an", "-sn",
                     "-f", "null", "-"])
        try:
            r = subprocess.run(cmd, capture_output=True, text=True, timeout=180)
        except (OSError, subprocess.TimeoutExpired):
            continue
        m = re.findall(r"crop=(-?\d+):(-?\d+):(-?\d+):(-?\d+)", r.stderr)
        if m:
            w, h, x, y = map(int, m[-1])
            if W and H and w >= W * 0.5 and h >= H * 0.3 and x >= 0 and y >= 0:
                b = (x, y, x + w, y + h)
                box = b if box is None else (min(box[0], b[0]), min(box[1], b[1]), max(box[2], b[2]), max(box[3], b[3]))
        # every filter graph instance prints its own summary (ffmpeg may build the graph twice): add them up
        for d in re.findall(r"Multi frame detection:\s*TFF:\s*(\d+)\s*BFF:\s*(\d+)\s*Progressive:\s*(\d+)", r.stderr):
            fields += int(d[0]) + int(d[1])
            prog += int(d[2])
    crop = None
    if box and W and H:
        x, y, w, h = box[0], box[1], box[2] - box[0], box[3] - box[1]
        w, h = w - w % 2, h - h % 2
        if w * h < W * H * 0.96:  # bars worth cropping: over 4 % of the picture
            crop = f"{w}:{h}:{x}:{y}"
    return {"interlaced": fields >= 20 and fields > prog, "crop": crop}


def _write_srts(full, subs, log):
    """Text subtitle tracks written next to the video as `<name>.<lang>.srt` (never over a file that's there)."""
    stem, wrote = os.path.splitext(full)[0], []
    for s in subs or []:
        out = f"{stem}.{s['lang']}.srt"
        if os.path.exists(out):
            continue
        r = subprocess.run(_nice(["ffmpeg", "-hide_banner", "-nostdin", "-v", "error", "-n", "-i", full,
                                  "-map", f"0:s:{s['n']}", "-c:s", "srt", out]), capture_output=True, text=True)
        if r.returncode != 0 or not os.path.exists(out) or os.path.getsize(out) == 0:
            _rm(out)
            log(f"reencode: {os.path.basename(full)}: subtitles {s['lang']}: {r.stderr.strip()[-200:]}")
        else:
            wrote.append(os.path.basename(out))
    return wrote


class ReencodeMixin:

    # ---- saved step sets

    def reencode_presets(self):
        user = [dict(p, builtin=False) for p in self.cfg.get("reencode_presets") or [] if isinstance(p, dict)]
        return _builtins() + user

    def save_reencode_preset(self, b):
        """{"name", "steps"}: a set saved (one with the same name replaced)."""
        if not isinstance(b, dict):
            raise TaskError(T("reencode_preset must be an object", "reencode_preset to obiekt"))
        name = str(b.get("name") or "").strip()[:40]
        if not name:
            raise TaskError(T("the set needs a name", "zestaw potrzebuje nazwy"))
        steps = norm_steps(b.get("steps") or {})
        if not active(steps):
            raise TaskError(T("turn on at least one step", "włącz przynajmniej jeden krok"))
        lst = [p for p in self.cfg.get("reencode_presets") or [] if isinstance(p, dict)
               and str(p.get("name", "")).lower() != name.lower()]
        if len(lst) >= MAX_PRESETS:
            raise TaskError(T(f"at most {MAX_PRESETS} sets", f"najwyżej {MAX_PRESETS} zestawów"))
        lst.append({"id": "u" + secrets.token_hex(3), "name": name, "steps": steps})
        self.cfg["reencode_presets"] = lst

    def delete_reencode_preset(self, pid):
        self.cfg["reencode_presets"] = [p for p in self.cfg.get("reencode_presets") or []
                                        if isinstance(p, dict) and p.get("id") != pid]

    # ---- measuring (interlacing, black bars), cached by file

    def _rdetect_cached(self, f):
        c = getattr(self, "_rdetect", {}).get(f.get("real") or "")
        if c and c[0] == f["size"] and c[1] == f["mtime"] and c[2] == DETECT_V:
            return c[3]
        return None

    def _rdetect_now(self, f, full):
        res = detect(full, f.get("info") or {})
        if not hasattr(self, "_rdetect"):
            self._rdetect = {}
        self._rdetect[f.get("real") or os.path.realpath(full)] = (f["size"], f["mtime"], DETECT_V, res)
        return res

    # ---- what the steps do with one file (shared by the counters and the plan)

    def _reenc_need(self, f, full, steps, ctx):
        """None (nothing to do or not a video for this), {"pending": True} (only unmeasured steps could apply),
        {"skip": reason} or {"item": the item's fields, "cost": enc → (bytes, seconds or None)}."""
        info = f.get("info") or {}
        v, dur, size = info.get("video") or {}, info.get("duration") or 0, f["size"]
        if not v or not dur or size < MIN_SIZE:
            return None
        a = ctx["active"]
        W, H = v.get("width") or 0, v.get("height") or 0
        ids, labels, unknown = [], [], False
        p = {"deinterlace": False, "denoise": False, "crop": None, "detect": False}
        det = None
        pic = steps["picture"]
        if "picture" in a and (pic["deinterlace"] or pic["crop"]):
            det = ctx["detect"](f, full)
            if det is None:
                unknown = True
        # 1. old picture: deinterlace, denoise, crop (deinterlacing works on the full fields, before anything else)
        if "picture" in a:
            if pic["deinterlace"]:
                if det is None:
                    p["deinterlace"] = None
                elif det["interlaced"]:
                    p["deinterlace"] = True
                    ids.append("deinterlace")
                    labels.append(T("Deinterlace", "Usuń przeplot"))
            if pic["denoise"]:
                p["denoise"] = True
                ids.append("denoise")
                labels.append(T("Denoise", "Odszum"))
            if pic["crop"]:
                if det is None:
                    p["crop"] = "detect"
                elif det["crop"]:
                    p["crop"] = det["crop"]
                    cw, ch = det["crop"].split(":")[:2]
                    ids.append("crop")
                    labels.append(T(f"Crop to {cw}×{ch}", f"Przytnij do {cw}×{ch}"))
            p["detect"] = det is None and (pic["deinterlace"] or pic["crop"])
        # 2. scale (never up)
        cls = _res_class(v)
        out_h = H
        if "resolution" in a:
            target = steps["resolution"]["height"]
            if cls > target * 1.05:
                out_h = int(round(H * target / cls))
                out_h -= out_h % 2
                p["target"], p["cls"] = target, cls
                ids.append("resolution")
                labels.append(f"{target}p")
        filters = any(x in ids for x in ("deinterlace", "denoise", "crop", "resolution"))
        # 3. video codec: only when the picture changes or the file isn't in the chosen codec yet
        video = None
        if "codec" in a:
            mode = steps["codec"]["mode"]
            c = v.get("codec")
            if filters or (mode != "h264" and c not in ("hevc", "av1")) or (mode == "h264" and (c != "h264" or is_old(v))):
                video = mode
                ids.append("codec")
                labels.append({"hevc_saving": T("H.265 saving", "H.265 oszczędnie"),
                               "hevc_quality": T("H.265 quality", "H.265 jakość"),
                               "h264": T("H.264 compatible", "H.264 zgodny")}[mode])
        elif filters:
            video = "hevc_saving"  # a changed picture has to be encoded (warnings() says so)
            labels.append(T("H.265 saving (needed)", "H.265 oszczędnie (konieczne)"))
        # 4. sound
        audio = info.get("audio") or []
        p["sound"] = steps["sound"]["mode"] if "sound" in a else "leave"
        broken = []
        if p["sound"] == "fix":
            if any(x.get("codec") in AUDIO_SUSPECT for x in audio):
                chk = ctx["audio"](f, full, info)
                if chk is None:
                    p["check_audio"] = True  # measured when the encode starts (as every encode does)
                else:
                    broken = list(chk)
            if broken:
                ids.append("sound_fix")
                labels.append(T("Fix sound", "Napraw dźwięk"))
        elif p["sound"] == "loudness" and audio:
            ids.append("loudness")
            labels.append(T("Even loudness", "Wyrównaj głośność"))
        p["broken"] = broken
        # 5. audio additions: the AAC track for a Chromecast, text subtitles next to the file
        cast = steps["cast"]
        if "cast" in a and cast["add_aac"] and audio and p["sound"] != "loudness":
            first = mp_media.pick_audio({"audio": audio})
            if first and first.get("codec") not in mp_media.AUDIO_OK and not any(
                    x.get("codec") in mp_media.AUDIO_OK and (x.get("lang") or "") == (first.get("lang") or "") for x in audio):
                p["add_aac"] = {"from": audio.index(first), "lang": first.get("lang") or ""}
                ids.append("add_aac")
                labels.append(T("AAC stereo added", "dodana ścieżka AAC stereo"))
        subs = info.get("subs") or []
        if "cast" in a and cast["subs_to_srt"] and subs:
            if any(not isinstance(s, dict) for s in subs):
                return {"pending": True}
            have = {x.get("lang") for x in f.get("side") or [] if x.get("lang")}
            want = []
            for n, s in enumerate(subs):
                code = _lang2(s.get("lang")) or ""
                if s.get("codec") in TEXT_SUBS and code and code not in have and code not in {w["lang"] for w in want}:
                    want.append({"n": n, "lang": code})
            if want:
                p["subs"] = want
                ids.append("subs_srt")
                labels.append(T("subtitles to .srt", "napisy do .srt"))
        # 6. container
        cur = _cur_format(info.get("container"))
        fmt = steps["container"]["format"] if "container" in a else ("mp4" if cur == "mp4" else "mkv")
        if "container" in a and fmt != cur:
            ids.append("container")
            labels.append(fmt.upper())
        p["format"] = fmt
        p["video"] = video
        remux = bool(video or broken or (p["sound"] == "loudness" and audio) or p.get("add_aac") or "container" in ids)
        if remux and not video and fmt == "mp4" and v.get("codec") not in MP4_VIDEO:
            return {"skip": T("MP4 can't hold this picture format without re-encoding (turn on the video codec step)",
                              "MP4 nie zmieści tego formatu obrazu bez kodowania (włącz krok kodeka wideo)")}
        p["remux"] = remux
        if not ids:
            return {"pending": True} if unknown else None
        seeding = _in_torrent(f.get("real") or full, ctx["torrents"])
        if seeding and remux:
            return {"skip": _seeding_text()}
        # the board's six steps this file needs (separate tasks would encode it this many times)
        groups = {"deinterlace": "picture", "denoise": "picture", "crop": "picture", "resolution": "resolution",
                  "codec": "codec", "sound_fix": "sound", "loudness": "sound", "add_aac": "cast", "subs_srt": "cast",
                  "container": "container"}
        passes = len({groups[x] for x in ids})
        hevc_saving = video and video != "h264" and (video == "hevc_saving" or "resolution" in ids)
        p["max_growth"] = 1.0 if hevc_saving else OLD_GROWTH + (0.05 if p.get("add_aac") else 0)
        if unknown:
            labels.append(T("bars and interlacing measured when encoding", "pasy i przeplot zmierzone przy kodowaniu"))

        def cost(enc, p=p, out_h=out_h):
            ak = 0
            for x in audio:
                if p["sound"] == "loudness" or x["index"] in p["broken"]:
                    ak += (384 if (x.get("channels") or 2) > 2 else 192) if p["sound"] == "loudness" else (
                        256 if (x.get("channels") or 2) > 2 else 128)
                elif fmt == "mp4" and x.get("codec") not in MP4_AUDIO:
                    ak += 384 if (x.get("channels") or 2) > 2 else 192
                else:
                    ak += _audio_src_kbps(x)
            if p.get("add_aac"):
                ak += 192
            if p["video"]:
                pr = CODECS[p["video"]]
                vk = pr["kbps"] * (out_h / 1080) ** 1.5
                if enc == "gpu":
                    vk *= 1.2 if pr.get("codec") == "h264" else GPU_SIZE[((mp_media.hevc_info() or {}).get("gpu") or {}).get("bframes", False)]
                est = int(dur * (vk + ak) * 1000 / 8 * 1.02)
                sp = self._speed(enc)
                work = dur * out_h * out_h * 16 / 9 / sp if sp else None
                if work is not None:
                    work *= (1.6 if p["denoise"] else 1.0) * (1.15 if p["deinterlace"] else 1.0)
                    if pr.get("codec") == "h264" and enc == "cpu":
                        work *= 0.6
            elif p["remux"]:
                src_audio = sum(_audio_src_kbps(x) for x in audio) * dur * 1000 / 8
                est = int(max(size - src_audio, size * 0.3) + dur * ak * 1000 / 8)
                work = size / CAST_SPEED + (dur / 150) * sum(1 for x in audio if p["sound"] == "loudness" or x["index"] in p["broken"])
            else:
                est, work = size, 5.0 * len(p.get("subs") or [])
            return est, work
        return {"item": {"reencode": p, "steps": ids, "labels": labels, "passes": passes, "to_height": out_h,
                         "to_codec": {"hevc_saving": "HEVC", "hevc_quality": "HEVC", "h264": "H.264"}.get(video, ""),
                         "profile": "reencode"},
                "cost": cost, "remux": remux}

    def _reenc_ctx(self, steps, torrents, enc, plan_deadline=None):
        a = active(steps)

        def det(f, full):
            c = self._rdetect_cached(f)
            if c is not None or plan_deadline is None or time.time() > plan_deadline:
                return c
            try:
                return self._rdetect_now(f, full)
            except OSError:
                return None

        def aud(f, full, info):
            c = self._acheck_cached(f)
            if c is not None or plan_deadline is None:
                return c
            try:
                return self.audio_check(full, info, plan_deadline)
            except OSError:
                return None
        return {"active": a, "torrents": torrents, "enc": enc, "detect": det, "audio": aud}

    # ---- counters (GET /api/library/jobs?reencode=<steps>)

    def reencode_counter(self, inscope, steps, torrents, enc, choices):
        """The B6 summary card for `steps` over the scope's facts (no measuring here)."""
        ctx = self._reenc_ctx(steps, torrents, enc)
        n = size = saving = waiting = seeding = passes = 0
        work = 0.0
        by_step = {}
        for _, r, f in inscope:
            c = self._reenc_need(f, os.path.join(r["path"], f["rel"]), steps, ctx)
            if not c:
                continue
            if c.get("pending"):
                waiting += 1
                continue
            if c.get("skip"):
                seeding += 1
                continue
            n += 1
            size += f["size"]
            est, w = c["cost"](enc)
            saving += f["size"] - est
            work = None if w is None or work is None else work + w
            passes = max(passes, c["item"]["passes"])
            for s in c["item"]["steps"]:
                by_step[s] = by_step.get(s, 0) + 1
            if c["item"]["reencode"].get("detect"):
                waiting += 1
        return {"count": n, "size": size, "saving": saving, "eta": None if work is None else int(work), "waiting": waiting,
                "skipped": seeding, "passes": passes, "by_step": by_step}

    # ---- the plan (POST /api/library/plan {"kind": "reencode", "steps": …})

    def plan_reencode(self, o, scope=None):
        steps = norm_steps(o.get("steps") or {})
        if not active(steps):
            raise TaskError(T("turn on at least one step", "włącz przynajmniej jeden krok"))
        if o.get("encoder") is not None and o["encoder"] not in ("auto", "cpu", "gpu"):
            raise TaskError(T("encoder must be auto, cpu or gpu", "koder to auto, cpu albo gpu"))
        name = str(o.get("name") or "").strip()[:40]
        m = self._matcher(scope)

        def run(p):
            mp_media.detect_hevc()
            enc = self.resolve_encoder(o.get("encoder"))
            choices = self._encoder_choices()
            ctx = self._reenc_ctx(steps, self._torrents(), enc, time.time() + DETECT_BUDGET)
            items, skipped, later = [], [], 0
            est_by, work_by = {e: 0 for e in choices}, {e: 0.0 for e in choices}
            any_video = False
            for r, f, full in self._plan_facts(m, p, upgrade=True):
                c = self._reenc_need(f, full, steps, ctx)
                if not c or c.get("pending"):
                    continue
                info = f["info"]
                v, dur = info["video"], info["duration"]
                base = {"op": "shrink", "root_id": r["id"], "root": r["index"], "root_name": r["name"], "path": f["rel"],
                        "name": os.path.basename(f["rel"]), "size": f["size"]}
                if c.get("skip"):
                    skipped.append(dict(base, reason=c["skip"]))
                    continue
                est, work = c["cost"](enc)
                rp = c["item"]["reencode"]
                if rp["video"] and est > f["size"] * rp["max_growth"]:
                    skipped.append(dict(base, est=est, reason=T("the result would be bigger than the original",
                                                                 "wynik byłby większy niż oryginał")))
                    continue
                later += bool(rp.get("detect"))
                any_video = any_video or bool(rp["video"])
                items.append(dict(base, est=est, work=work, codec=v.get("codec"), height=v.get("height"), duration=dur,
                                  bitrate=int(f["size"] * 8 / dur / 1000), **c["item"]))
                for e in choices:
                    b, w = c["cost"](e)
                    est_by[e] += b
                    work_by[e] = None if w is None or work_by[e] is None else work_by[e] + w
            p["progress"] = 100
            warns = warnings(steps)
            if later:
                warns.append(T(f"{later} files weren't measured for black bars and interlacing in time: that happens "
                               f"when their encode starts",
                               f"{later} plików nie zdążono zmierzyć (czarne pasy, przeplot): stanie się to przy ich kodowaniu"))
            if any_video:
                warns += self._codec_warnings()
            total = sum(i["size"] for i in items)
            title = _title(f"Re-encode: {name}", f"Przekoduj: {name}") if name else _title("Re-encode", "Przekoduj")
            return {"kind": "reencode", **title, "items": items, "bytes": total, "est": sum(i["est"] for i in items),
                    "saved": total - sum(i["est"] for i in items), "hours": round(sum(i["duration"] for i in items) / 3600, 1),
                    "eta": int(sum(i["work"] or 0 for i in items)),
                    "passes": max((i["passes"] for i in items), default=0),
                    "encoder": (T("Processor (x264)", "Procesor (x264)") if enc == "cpu" and all(
                        i["reencode"]["video"] in (None, "h264") for i in items) else self.encoder_label(enc)) if any_video else None,
                    "encoder_id": enc if any_video else None,
                    "encoders": [dict(e, est=est_by[e["id"]], saved=total - est_by[e["id"]],
                                      work_hours=None if work_by[e["id"]] is None else round(work_by[e["id"]] / 3600, 1))
                                 for e in self.encoders()] if any_video else [],
                    "warnings": warns, "skipped": skipped[:100], "trash_days": self.trash_days(),
                    "steps": steps, "options": {"steps": steps, "name": name}}
        return self._plan("reencode", run, m)

    # ---- the encode (called from ShrinkMixin._shrink)

    def _reencode_srts(self, full, rp):
        return _write_srts(full, rp.get("subs"), self.log)

    def _reencode_broken(self, full, info, rp):
        """Audio tracks re-encoded by "fix damaged": the ones the preview found, or measured now when it couldn't."""
        if rp.get("sound") != "fix":
            return set()
        if not rp.get("check_audio"):
            return set(rp.get("broken") or [])
        try:
            return set(self.audio_check(full, info) or [])
        except OSError:
            return set()

    def _reencode_cmd(self, full, out, info, rp, enc, broken):
        """One ffmpeg run for a file's steps, in the fixed order (see the module's docstring)."""
        v = info["video"]
        W, H = v.get("width") or 0, v.get("height") or 0
        if rp.get("detect"):
            det = detect(full, info)
            if rp.get("deinterlace") is None:
                rp["deinterlace"] = det["interlaced"]
            if rp.get("crop") == "detect":
                rp["crop"] = det["crop"]
            rp["detect"] = False
        mp4 = rp.get("format") == "mp4"
        cmd = ["ffmpeg", "-hide_banner", "-nostdin", "-y", "-loglevel", "error"]
        if not rp.get("video") and "avi" in (info.get("container") or "").lower():
            cmd += ["-fflags", "+genpts"]
        at = len(cmd)  # input options of a graphics card go here, before -i
        cmd += ["-i", full, "-map", "0:v:0"]
        add = rp.get("add_aac")
        if add:
            cmd += ["-map", f"0:a:{add['from']}"]
        audio = info.get("audio") or []
        for a in audio:
            cmd += ["-map", f"0:a:{a['index']}"]
        if mp4:
            for n, s in enumerate(info.get("subs") or []):
                if (s.get("codec") if isinstance(s, dict) else None) in TEXT_SUBS:
                    cmd += ["-map", f"0:s:{n}"]
        else:
            cmd += ["-map", "0:s?", "-map", "0:t?"]
        cmd += ["-map_chapters", "0"]
        if rp.get("video"):
            vf = []
            if rp.get("deinterlace"):
                vf.append("yadif=0:-1:0")
            if rp.get("denoise"):
                vf.append(DENOISE)
            ch = H
            if rp.get("crop") and rp["crop"] != "detect":
                vf.append(f"crop={rp['crop']}")
                ch = int(rp["crop"].split(":")[1])
            if rp.get("target") and rp.get("cls"):
                nh = int(round(ch * rp["target"] / rp["cls"]))
                nh -= nh % 2
                if nh < ch:
                    vf.append(f"scale=-2:{nh}")
            if not any(x.startswith("scale=") for x in vf):
                vf.append("scale=trunc(iw/2)*2:trunc(ih/2)*2")  # 4:2:0 needs even sizes
            pr = CODECS[rp["video"]]
            chain = ",".join(vf)
            if pr.get("codec") == "h264":
                hw = mp_media.detect_encoder() if enc == "gpu" else None
                row = next((e for e in mp_media.ENCODERS if hw and e[0] == hw["encoder"] and hw.get("hw")), None)
                if row:
                    name, _, pre, hvf, args = row
                    cmd[at:at] = pre
                    cmd += ["-vf", f"{chain},{hvf[len(mp_media._SCALE) + 1:]}", "-c:v", name, *args]
                else:
                    cmd += ["-vf", f"{chain},format=yuv420p", "-c:v", "libx264", "-preset", "medium", "-crf", str(pr["crf"]),
                            "-profile:v", "high"]
            elif enc == "gpu" and (mp_media.detect_hevc() or {}).get("gpu"):
                pre, vfx, vargs = mp_media.hevc_cmd(mp_media.detect_hevc()["gpu"], pr["cq"])
                cmd[at:at] = pre
                cmd += ["-vf", f"{chain},{vfx}", *vargs, "-tag:v", "hvc1"]
            else:
                cmd += ["-vf", f"{chain},format=yuv420p", "-c:v", "libx265", "-preset", self.cfg.get("shrink_preset", "fast"),
                        "-crf", str(pr["crf"]), "-x265-params", "log-level=error", "-tag:v", "hvc1"]
        else:
            cmd += ["-c:v", "copy"]
        n = 0
        if add:
            cmd += ["-c:a:0", "aac", "-b:a:0", "192k", "-ac:a:0", "2", "-metadata:s:a:0", "title=AAC stereo",
                    "-disposition:a", "0", "-disposition:a:0", "default"]
            if add.get("lang"):
                cmd += ["-metadata:s:a:0", f"language={add['lang']}"]
            n = 1
        for a in audio:
            multi = (a.get("channels") or 2) > 2
            c = a.get("codec") or ""
            if rp.get("sound") == "loudness":
                cmd += [f"-filter:a:{n}", LOUDNORM, f"-c:a:{n}", "aac", f"-b:a:{n}", "384k" if multi else "192k"]
            elif a["index"] in broken:
                cmd += [f"-filter:a:{n}", "aresample=async=1:first_pts=0", f"-c:a:{n}", "aac", f"-b:a:{n}", "256k" if multi else "128k"]
            elif (mp4 and c not in MP4_AUDIO) or (not mp4 and c not in PLAYABLE_AUDIO and not c.startswith("pcm_") and c not in ("truehd", "flac")):
                cmd += [f"-c:a:{n}", "aac", f"-b:a:{n}", "384k" if multi else "192k"]
            else:
                cmd += [f"-c:a:{n}", "copy"]
            n += 1
        # MP4 subtitles (mov_text) can't be copied into Matroska: they become SRT there
        movtext = any(isinstance(s, dict) and s.get("codec") == "mov_text" for s in info.get("subs") or [])
        cmd += ["-c:s", "mov_text" if mp4 else "srt" if movtext else "copy",
                "-max_muxing_queue_size", "4096", "-progress", "pipe:1", "-nostats"]
        cmd += ["-movflags", "+faststart", "-f", "mp4", out] if mp4 else ["-f", "matroska", out]
        return _nice(cmd)

