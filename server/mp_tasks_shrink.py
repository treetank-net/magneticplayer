"""Shrinking videos: encoder choice and speed, the encode itself (detached ffmpeg), pausing it while somebody watches,
the ffmpeg command line."""
import os
import re
import shutil
import subprocess
import sys
import threading
import time

import mp_detached
import mp_library
import mp_media
from mp_i18n import T
from mp_tasks_common import (
    ENCODERS, MIN_SAVING, MODERN, OLD_GROWTH, PROFILES, RESERVE, TaskError, _Cancelled, _Held, _audio_codec,
    _audio_errors, _audio_keep, _dev, _ffmpeg_reason, _nice, _readable_length, _rm, _signal
)


AUDIO_FIX = {"copy": True, "audio": "keep"}  # the picture copied, the damaged tracks re-encoded, the rest copied
WATCH_MODES = ("continue", "pause")
SAME_DISK_DUTY = (1.0, 2.0)  # an encode on the disk somebody watches from: seconds running, seconds stopped
SAME_DISK_FREE = 8 << 20  # ... unless it reads less than this per second (x265 does), then it runs at full speed


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


def _audio_damaged(full, a):
    """The audio track `a` of `full` has frames its decoder rejects (a full decode of that track only)."""
    return _audio_errors(full, a) > 0


def _item_profile(i):
    """The encode settings of a task item: the sound fix, the old-picture H.264 profile or a storage profile, with the
    item's own overrides (compression quality, sound mode; agent 1.16+)."""
    if i.get("audio_fix"):
        return AUDIO_FIX
    if i.get("reencode"):  # re-encode (agent 1.18+): the command comes from mp_tasks_reencode, this only steers checks
        v = i["reencode"].get("video")
        return {"copy": not v, "codec": "h264" if v == "h264" else "hevc", "audio": "keep", "reencode": True}
    pr = MODERN if i.get("modern") else PROFILES[i["profile"]]
    if i.get("q"):
        pr = dict(pr, crf=i["q"]["crf"], cq=i["q"]["cq"])
    if i.get("audio_mode"):
        pr = dict(pr, audio=i["audio_mode"])
    return pr


class ShrinkMixin:

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
        if i.get("reencode") and not i["reencode"].get("remux"):  # re-encode with only subtitles to write: file untouched
            wrote = self._reencode_srts(full, i["reencode"])
            if wrote:
                i["subs_written"] = wrote
            progress(i["size"])
            return
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
        rp = i.get("reencode")
        stem, ext = os.path.splitext(full)
        oext = ".mp4" if rp and rp.get("format") == "mp4" else ".mkv"
        out_final = stem + oext
        part = os.path.join(os.path.dirname(full), f".{os.path.basename(stem)}.mp-shrink.part{oext}")
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
            if rp:  # re-encode (1.18+): subtitles next to the file first, then one encode for every other step
                wrote = self._reencode_srts(full, rp)
                if wrote:
                    i["subs_written"] = wrote
                broken = self._reencode_broken(full, info, rp)
            elif i.get("audio_fix"):  # found by the preview (the file is unchanged since: same size)
                broken = set(i.get("broken") or [])
            else:
                broken = {a["index"] for a in _audio_keep(info, pr["audio"])
                          if _audio_codec(a, pr["audio"]) == ["copy"] and _audio_damaged(full, a)}
            if broken:
                self.log(f"shrink: {os.path.basename(full)}: audio track(s) {sorted(broken)} don't decode cleanly, re-encoded")
            if rp:
                cmd = self._reencode_cmd(full, part, info, rp, enc, broken)
            else:
                cmd = self._shrink_cmd(full, part, info, pr, enc, broken, to_h=i.get("to_height") if not pr.get("copy") else None,
                                       aac=i.get("aac") or "standard")
            self.log("shrink:", os.path.basename(full), "sound only" if i.get("audio_fix") else
                     "re-encode: " + ",".join(i.get("steps") or []) if rp else i["profile"], enc)
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
        if rp and new > st.st_size * rp.get("max_growth", OLD_GROWTH):
            _rm(part)
            i["state"] = "skipped"
            i["error"] = T("the result was bigger than the original, the original was kept",
                           "wynik był większy niż oryginał, oryginał został")
            return
        if not rp and new > st.st_size * (OLD_GROWTH if i.get("old_format") or i.get("audio_fix")
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
        if not pr.get("copy") and pr.get("codec") != "h264" and not rp:  # the speeds are the plain HEVC encoders'
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
