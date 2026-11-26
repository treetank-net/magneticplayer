"""Subtitles prepared by themselves once a film has finished downloading (agent 1.21+).

The owner's rules (2026-11-26):
1. a baseline: subtitles in the film's own language, from the file itself, from next to it, or from a subtitle service
   without download limits, timed to this copy: by the rhythm of speech (mp_rhythm, any language, seconds) and, when
   that isn't sure and this computer has Whisper, by Whisper (mp_retime);
2. the language the user last picked on purpose (downloading subtitles, choosing a version, a phone's choice synced
   later) is fitted to that baseline by the rhythm of the lines (mp_rhythm.translate): written for this copy → one tempo
   and offset; for the baseline's release → the same cuts; for another copy → line by line;
3. every such decision is a setting ("subflow" in config.json, /api/config/subflow, MCP `get_settings`/`set_settings`).

Series: every video of a finished torrent, one at a time. Torrents already complete when the agent first runs this are
not touched (the library isn't reprocessed); `run()` does one video by hand.

State (`subflow.json` in the config directory): torrents seen complete, the queue, the baseline per video (its line
starts in the film, its original's cut map: what a translation needs) and the last result per video.
"""
import json
import os
import threading
import time

import mp_retime
import mp_rhythm
import mp_subs
import mp_subsync
from mp_i18n import T

VIDEO_EXT = {".mkv", ".mp4", ".avi", ".m4v", ".mov", ".webm", ".wmv", ".mpg", ".mpeg", ".ts", ".m2ts", ".flv", ".ogv"}
MIN_SIZE = 50 * 1024 * 1024  # samples and extras are left alone

DEFAULTS = {
    "auto": True,             # run after a torrent has finished downloading
    "original": "auto",       # the film's language: "auto" (the sound track's tag, the title's tags) or a code
    "fallback": "en",         # when "auto" can't tell
    "sources": ["file", "embedded", "search"],  # where the baseline comes from, in this order
    # services for downloads nobody asked for: those without a daily limit; add "opensubtitlescom" to spend its limit
    "providers": ["bsplayer", "napiprojekt", "gestdown", "podnapisi"],
    "whisper": "unsure",      # "never", "unsure" (when the rhythm isn't sure) or "always"
    "target": "last",         # the second language: "last" (picked last in the app), a code, or "" for none
    "translate": "baseline",  # other languages: "baseline" (to the baseline's lines) or "speech" (one shift, as before)
    "snap": 0.35,             # a translated line within this many seconds of a baseline line takes its time
    "min_lift": 0.10,         # a translation is rewritten only when its lines fit the baseline's this much better
                              # than the same lines moved off by chance (the subtitles of another film don't)
}
KEYS = {k: type(v) for k, v in DEFAULTS.items()}


def settings(cfg):
    s = dict(DEFAULTS)
    s.update({k: v for k, v in (cfg.get("subflow") or {}).items() if k in DEFAULTS or k == "last"})
    return s


def all_settings(cfg):
    """Every subtitle-timing setting: "subflow" (this module), "rhythm" (mp_rhythm), "whisper" (mp_retime)."""
    return {"subflow": settings(cfg), "rhythm": mp_rhythm.settings(cfg), "whisper": mp_retime.settings(cfg)}


CHOICES = {("subflow", "whisper"): ("never", "unsure", "always"), ("subflow", "translate"): ("baseline", "speech"),
           ("whisper", "device"): ("auto", "cuda", "cpu")}


def set_settings(app, section, changes):
    """Validated changes to one section (API and MCP); a value of the wrong type or an unknown key is refused.
    → all settings after the change."""
    if section not in ("subflow", "rhythm", "whisper"):
        raise ValueError("section: subflow, rhythm or whisper")
    defaults = {"subflow": dict(DEFAULTS, last=""), "rhythm": mp_rhythm.settings({}),
                "whisper": mp_retime.settings({})}[section]
    cur = dict(app.cfg.get(section) or {})
    for k, v in (changes or {}).items():
        if k not in defaults:
            raise ValueError(f"unknown setting: {section}.{k}")
        want = type(defaults[k])
        if want is float and isinstance(v, int) and not isinstance(v, bool):
            v = float(v)
        if v is None:  # back to the default
            cur.pop(k, None)
            continue
        if not isinstance(v, want) or (want is list and not all(isinstance(x, str) for x in v)):
            raise ValueError(f"{section}.{k}: expected {want.__name__}")
        if (section, k) in CHOICES and v not in CHOICES[(section, k)]:
            raise ValueError(f"{section}.{k}: one of {', '.join(CHOICES[(section, k)])}")
        cur[k] = v
    app.cfg[section] = cur
    app.save_cfg()
    return all_settings(app.cfg)


def lang_of_name(name):
    return mp_retime.language_of(name)


class SubFlow:
    def __init__(self, app, config_dir, log):
        self.app, self.log = app, log
        self.path = os.path.join(config_dir, "subflow.json")
        self.lock = threading.Lock()
        self.run_lock = threading.Lock()
        try:
            with open(self.path) as f:
                self.st = json.load(f)
        except (OSError, ValueError):
            self.st = {}
        self.st.setdefault("seen", None)   # torrent ids seen complete (None: not yet initialised)
        self.st.setdefault("queue", [])
        self.st.setdefault("base", {})     # real video path → baseline
        self.st.setdefault("done", {})     # real video path → last result
        self.busy = False

    def _save(self):
        tmp = self.path + ".tmp"
        with open(tmp, "w") as f:
            json.dump(self.st, f, ensure_ascii=False)
        os.replace(tmp, self.path)

    @property
    def cfg(self):
        return settings(self.app.cfg)

    # ---- settings

    def chosen(self, lang):
        """The user picked subtitles in `lang` on purpose: the next films get that language too ("target": "last")."""
        lang = (lang or "").strip().lower()
        if not lang or len(lang) > 8 or lang == (self.app.cfg.get("subflow") or {}).get("last"):
            return
        self.app.cfg.setdefault("subflow", {})["last"] = lang
        self.app.save_cfg()

    def target(self):
        s = self.cfg
        return s.get("last") if s["target"] == "last" else (s["target"] or None)

    # ---- the baseline of a video

    def baseline(self, full):
        """The baseline of this exact video (same size), or None."""
        b = self.st["base"].get(os.path.realpath(full))
        try:
            if b and b.get("size") == os.path.getsize(full):
                return b
        except OSError:
            pass
        return None

    def status(self, full):
        real = os.path.realpath(full)
        b = self.st["base"].get(real) or {}
        return {"settings": self.cfg, "queued": real in self.st["queue"], "running": self.busy == real,
                "baseline": {k: b.get(k) for k in ("lang", "name", "by", "verdict", "cuts", "at")} if b else None,
                "result": self.st["done"].get(real)}

    # ---- finished torrents (SleepGuard, every ~20 s)

    def tick(self, torrents):
        if not self.cfg["auto"]:
            return
        complete = {t["id"]: t for t in torrents if t["progress"] >= 100}
        with self.lock:
            if self.st["seen"] is None:  # first run: what is complete now is the existing library, not new
                self.st["seen"] = sorted(complete)
                self._save()
                return
            new = [t for tid, t in complete.items() if tid not in self.st["seen"]]
            if not new:
                return
            for t in new:
                self.st["seen"].append(t["id"])
                root = os.path.join(t["save_path"], t["name"])
                vids = [root] if os.path.isfile(root) else sorted(
                    os.path.join(d, n) for d, _, ns in os.walk(root) for n in ns
                    if os.path.splitext(n)[1].lower() in VIDEO_EXT)
                for v in vids:
                    try:
                        if os.path.getsize(v) >= MIN_SIZE and os.path.realpath(v) not in self.st["queue"]:
                            self.st["queue"].append(os.path.realpath(v))
                    except OSError:
                        pass
            del self.st["seen"][:-2000]
            self._save()
        self.kick()

    def kick(self):
        if self.busy or not self.st["queue"]:
            return
        self.busy = True
        threading.Thread(target=self._worker, daemon=True).start()

    def _worker(self):
        try:
            while True:
                with self.lock:
                    if not self.st["queue"]:
                        break
                    full = self.st["queue"][0]
                self.busy = full
                try:
                    self.run(full)
                except Exception as e:  # noqa: BLE001 — one film's trouble doesn't stop the queue
                    self.log("subtitles (auto):", os.path.basename(full), "failed:", e)
                    self._result(full, {"error": str(e)})
                with self.lock:
                    if full in self.st["queue"]:
                        self.st["queue"].remove(full)
                    self._save()
        finally:
            self.busy = False

    def _result(self, full, res):
        with self.lock:
            self.st["done"][os.path.realpath(full)] = dict(res, at=int(time.time()))
            if len(self.st["done"]) > 3000:
                for k in sorted(self.st["done"], key=lambda k: self.st["done"][k].get("at", 0))[:500]:
                    self.st["done"].pop(k)
            self._save()

    # ---- one video

    def film_language(self, full):
        s = self.cfg
        if s["original"] != "auto":
            return s["original"]
        info = self._audio_lang(full)
        if info:
            return info
        try:
            known = self.app.catalog.known_for(full) or {}
            tags = (self.app.cfg.get("title_tags") or {}).get(known.get("imdb") or "", [])
            for t in tags:
                if isinstance(t, str) and len(t) == 2 and t.isalpha():
                    return t.lower()
        except Exception:  # noqa: BLE001
            pass
        return s["fallback"]

    @staticmethod
    def _audio_lang(full):
        import subprocess
        import sys
        extra = {"creationflags": subprocess.CREATE_NO_WINDOW} if sys.platform == "win32" else {}
        try:
            out = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "a", "-show_entries", "stream_tags=language",
                                  "-of", "json", full], capture_output=True, text=True, timeout=30, **extra).stdout
            streams = json.loads(out or "{}").get("streams") or []
            tr = mp_subsync.audio_track(full)
            code = ((streams[tr].get("tags") or {}).get("language") or "").lower() if tr < len(streams) else ""
        except (OSError, ValueError, subprocess.SubprocessError):
            return None
        if not code or code in ("und", "unk", "mis", "zxx", "mul"):
            return None
        try:
            _, Language = mp_subs._subliminal()
            return Language.fromietf(code).alpha2 if len(code) == 2 else Language(code).alpha2
        except Exception:  # noqa: BLE001
            return code[:2]

    def run(self, full, force=False):
        """Baseline in the film's language, then the target language fitted to it. → the result dict."""
        with self.run_lock:
            return self._run(full, force)

    def _run(self, full, force):
        a = self.app
        if a.downloading_part(full):
            return {"error": "the film is still downloading"}
        s = self.cfg
        lang = self.film_language(full)
        res = {"lang": lang}
        base = None if force else self.baseline(full)
        if base is None:
            base = self._make_baseline(full, lang, res)
        if base is None:
            self._result(full, res)
            self.log("subtitles (auto):", os.path.basename(full), "no baseline:", res.get("baseline_error"))
            return res
        res["baseline"] = {k: base.get(k) for k in ("name", "by", "verdict")}
        tgt = self.target()
        if tgt and tgt != base["lang"] and s["translate"] == "baseline":
            res["target"] = self._target(full, tgt, base)
        self._result(full, res)
        self.log("subtitles (auto):", os.path.basename(full), json.dumps(res, ensure_ascii=False, default=str)[:400])
        return res

    def _sidecar(self, full, lang):
        from mp_library import sidecar_subs
        for x in sidecar_subs(full):
            if x.get("default") and x["lang"].lower() == lang and x["ext"] in ("srt", "sub"):
                return os.path.join(os.path.dirname(full), x["name"])
        return None

    def _make_baseline(self, full, lang, res):
        a, s = self.app, self.cfg
        for src in s["sources"]:
            try:
                if src == "embedded":
                    b = self._from_embedded(full, lang)
                elif src == "file":
                    p = self._sidecar(full, lang)
                    b = self._from_file(full, p, lang) if p else None
                elif src == "search":
                    got = self._download(full, lang, prepare=lambda tmp: self._prepare_baseline(full, tmp, lang))
                    b = self.baseline(full) if got else None
                else:
                    b = None
            except (mp_rhythm.RhythmError, mp_subsync.SyncError, mp_retime.RetimeError, OSError, ValueError) as e:
                res["baseline_error"] = f"{src}: {e}"
                b = None
            if b:
                return b
        res.setdefault("baseline_error", "no subtitles in the film's language")
        return None

    def _keep(self, full, rec):
        try:
            rec["size"] = os.path.getsize(full)
        except OSError:
            return None
        rec["at"] = int(time.time())
        with self.lock:
            self.st["base"][os.path.realpath(full)] = rec
            self._save()
        return rec

    def _from_embedded(self, full, lang):
        """A text subtitle track of the file in the film's language: its times are this copy's own, to the frame."""
        import subprocess
        import sys
        extra = {"creationflags": subprocess.CREATE_NO_WINDOW} if sys.platform == "win32" else {}
        out = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "s", "-show_entries",
                              "stream=index,codec_name:stream_tags=language,title:stream_disposition=forced,hearing_impaired",
                              "-of", "json", full], capture_output=True, text=True, timeout=60, **extra).stdout
        streams = json.loads(out or "{}").get("streams") or []
        text_codecs = {"subrip", "srt", "ass", "ssa", "mov_text", "webvtt", "text"}
        for i, st in enumerate(streams):
            tl = ((st.get("tags") or {}).get("language") or "").lower()
            if st.get("codec_name") not in text_codecs or (st.get("disposition") or {}).get("forced"):
                continue
            if not (tl == lang or tl[:2] == lang or (len(tl) == 3 and self._a2(tl) == lang)):
                continue
            srt = subprocess.run(["ffmpeg", "-v", "error", "-nostdin", "-i", full, "-map", f"0:s:{i}", "-f", "srt", "-"],
                                 capture_output=True, text=True, timeout=300, **extra).stdout
            cues = mp_retime.parse(srt)
            if len(cues) < 50:
                continue
            wt = mp_rhythm.weights(cues, 0.3)
            # its own times are exact, but they show the line when it is said: the onset is the start
            return self._keep(full, {"lang": lang, "name": f"embedded:{i}", "by": "embedded", "verdict": "good",
                                     "onsets": [round(c[0], 3) for c, w in zip(cues, wt) if w >= 1], "map": None})
        return None

    @staticmethod
    def _a2(code3):
        try:
            _, Language = mp_subs._subliminal()
            return Language(code3).alpha2
        except Exception:  # noqa: BLE001
            return code3[:2]

    def _from_file(self, full, path, lang):
        """A subtitle file already next to the video: re-timed in place (unless a seeding torrent owns it)."""
        a = self.app
        if a.tasks.torrent_owned(path):
            text = mp_subs.readable_srt(path, full)
            _, res = mp_rhythm.retime(full, text, a.cfg)  # only measured: the file stays as it is
            if res["verdict"] != "good" or res["cuts"] or abs(res["offset"]) > 0.5:
                return None
            return self._keep(full, self._record(lang, os.path.basename(path), res, None))
        self._prepare_baseline(full, path, lang, in_place=True)
        return self.baseline(full)

    def _record(self, lang, name, res, text):
        onsets = res.get("onsets")
        if onsets is None and text is not None:  # Whisper: the new line starts (+ the lead) are the speech onsets
            lead = float(mp_retime.settings(self.app.cfg)["lead"])
            cues = mp_retime.parse(text)
            onsets = [round(c[0] + lead, 3) for c, w in zip(cues, mp_rhythm.weights(cues, 0.3)) if w >= 1]
        return {"lang": lang, "name": name, "by": res.get("by"), "verdict": res.get("verdict"),
                "cuts": len(res.get("cuts") or []), "onsets": onsets, "map": res.get("map")}

    def _prepare_baseline(self, full, path, lang, in_place=False):
        """Fit (one shift) → rhythm → Whisper when needed; writes the file (the original kept), keeps the baseline.
        → {"name", "verdict", "by"} as a download's `prepare` expects."""
        a, s = self.app, self.cfg
        text = mp_subs.readable_srt(path, full)
        new, res = mp_rhythm.retime(full, text, a.cfg)
        use_whisper = s["whisper"] == "always" or (s["whisper"] == "unsure" and res["verdict"] != "good")
        if use_whisper and mp_retime.available(a.cfg):
            try:
                with mp_retime._lock:
                    new2, res2 = mp_retime.retime(full, text, mp_retime.settings(a.cfg), lang,
                                                  {"offset": res["offset"]}, {})
                new, res = new2, dict(res2, onsets=None)
            except (mp_retime.RetimeError, mp_subsync.SyncError) as e:
                res["whisper_error"] = str(e)
        if in_place:
            a.subsync.replace(path, new, res)
        else:
            self._write_tmp(path, new, res)
        name = os.path.basename(path)
        if not name.lower().endswith(".srt"):
            name = os.path.splitext(name)[0] + ".srt"
        self._keep(full, self._record(lang, name, res, new))
        return {"name": name, "verdict": res["verdict"], "by": res["by"], "cuts": res.get("cuts")}

    def _download(self, full, lang, prepare):
        """Search `lang` with the allowed services, the best result first (no machine translations, title matches);
        `prepare` fits it in a temporary folder before it goes next to the video. → the download result or None."""
        a, s = self.app, self.cfg
        try:
            found = a.subs.search(full, [lang], known=a.catalog.known_for(full))
        except Exception as e:  # noqa: BLE001 — providers down, a title not recognised
            self.log("subtitles (auto): search failed", os.path.basename(full), e)
            return None
        cands = [r for r in found.get("results") or [] if r.get("lang") == lang and r["provider"] in s["providers"]
                 and not r.get("machine")]
        cands.sort(key=mp_subs.rank_key)
        for r in cands[:3]:
            try:
                got = a.download_subs(full, {"id": r["id"], "provider": r["provider"], "languages": lang,
                                             "auto": True}, prepare=prepare)
            except Exception as e:  # noqa: BLE001 — a limit, an empty file, another film's subtitles: the next one
                self.log("subtitles (auto): download failed or didn't fit", r["provider"], e)
                continue
            if (got.get("fit") or {}).get("verdict") in ("good", "unsure"):
                return got
        return None

    # ---- the second language

    def _target(self, full, lang, base):
        p = self._sidecar(full, lang)
        if p:
            if self.app.tasks.torrent_owned(p):
                return {"name": os.path.basename(p), "verdict": "locked"}
            r = self.app.subsync.get(p) or {}
            if r.get("by") == "baseline" and r.get("base_at") == base.get("at"):
                return {"name": os.path.basename(p), "verdict": r.get("verdict"), "kept": True}
            return self.translate_file(full, p, in_place=True)
        got = self._download(full, lang, prepare=lambda tmp: self.translate_file(full, tmp))
        return (got or {}).get("fit") or {"verdict": "none", "error": "no subtitles found"}

    def translate_file(self, full, path, in_place=False):
        """Fits a subtitle file of another language to the baseline (mp_rhythm.translate) and rewrites it (in place:
        the original kept). → {"name", "verdict", "by": "baseline", "hypothesis", "score"}; a fit worse than
        `min_lift` leaves the file as it came, "unsure"."""
        a, s = self.app, self.cfg
        base = self.baseline(full)
        if base is None:
            raise mp_rhythm.RhythmError("no baseline for this film")
        text = mp_subs.readable_srt(path, full)
        new, res = mp_rhythm.translate(text, base, a.cfg, snap=s["snap"])
        res["base_at"] = base.get("at")
        name = os.path.basename(path)
        if res["lift"] < s["min_lift"]:
            res["verdict"] = "unsure"
            if in_place:
                a.subsync._remember(path, dict(res, name=name, changed=False))
            return dict(res, name=name)
        if in_place:
            a.subsync.replace(path, new, res)
        else:
            self._write_tmp(path, new, res)
        return dict(res, name=name)

    def _write_tmp(self, path, text, res):
        """A fresh download in its temporary folder: rewritten, the assessment remembered (it follows the file to its
        place, mp_subs.download's `moved`)."""
        with open(path, "w", encoding="utf-8", newline="\n") as f:
            f.write(text)
        self.app.subsync._remember(path, dict({k: v for k, v in res.items() if k not in ("map", "onsets")}, ratio=1.0,
                                              offset=0.0, changed=True, name=os.path.basename(path)))
