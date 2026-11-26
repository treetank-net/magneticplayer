"""Task screens v2 (agent 1.16+, docs/plan-ux-tasks.md): settings, what a kind would do with one file, the v2 planners,
the counters of the task list and tasks created by themselves."""
import os
import re
import shutil
import time

import mp_arrange
import mp_i18n
import mp_ident
import mp_media
import mp_scope
from mp_i18n import T
from mp_library import VIDEO_EXT
from mp_tasks_common import (
    AUDIO_CHECK_BUDGET, AUDIO_SUSPECT, ENCODERS, GPU_SIZE, MIN_SAVING, MIN_SIZE, MODERN, OLD_GROWTH, PROFILES,
    SUBS_PER_DAY, TaskError, WATCHING_DAYS, _audio_kbps, _audio_keep, _dev, _in_torrent, _lang2, _seeding_text,
    _title, companions, is_old, tree_size
)


# downscale targets (agent 1.16+) and the profile each one uses
TARGETS = {1080: "good", 720: "compact", 480: "tiny", 360: "mini"}
# compression qualities (agent 1.16+): x265 CRF, graphics card CQ and the bitrate factor of the estimate
QUALITIES = {"transparent": {"crf": 20, "cq": 23, "k": 1.6}, "balanced": {"crf": 24, "cq": 27, "k": 1.0},
             "saving": {"crf": 27, "cq": 30, "k": 0.7}}
HIGH_BITRATE = 3 << 30  # bytes per hour of video: "only high bitrate" in compression
AUDIO_MODES = {"keep_if_ok": "keep", "always_aac": "aac"}
AAC_QUALITIES = ("standard", "high")


# ---- jobs v2 helpers (agent 1.16+)

def _size_text(b):
    return f"{b / (1 << 30):.1f} GB" if b >= 1 << 30 else f"{max(b >> 20, 0)} MB"


def _langs(text):
    """"pl, EN,pl" → ["pl", "en"] (2-letter codes, order kept)."""
    out = []
    for x in str(text or "").split(","):
        x = x.strip().lower()
        if re.fullmatch(r"[a-z]{2}", x) and x not in out:
            out.append(x)
    return out


def _subs_langs(f):
    """(languages a video has subtitles in, whether it has any): files next to it and tracks inside it."""
    got = {x.get("lang") for x in f.get("side") or [] if x.get("lang")}
    emb = [_lang2(s.get("lang")) for s in ((f.get("info") or {}).get("subs") or [])]
    got |= {x for x in emb if x}
    return got, bool(f.get("side") or emb)


def _res_class(v):
    """A picture's resolution class: its height, or the height a 16:9 picture of its width would have (a 1920×800
    film is 1080p)."""
    h, w = v.get("height") or 0, v.get("width") or 0
    return max(h, int(round(w * 9 / 16)))


_RES_ORDER = {"4K": 2160, "1440p": 1440, "1080p": 1080, "720p": 720, "576p": 576, "480p": 480, "360p": 360, "SD": 0}


def _res_label(cls):
    for name, h in _RES_ORDER.items():
        if cls >= h * 0.9:
            return name
    return "SD"


_FORMATS = {"msmpeg4v1": "DivX 3", "msmpeg4v2": "DivX 3", "msmpeg4v3": "DivX 3", "wmv1": "WMV", "wmv2": "WMV",
            "wmv3": "WMV 9", "vc1": "VC-1", "rv10": "RealVideo", "rv20": "RealVideo", "rv30": "RealVideo",
            "rv40": "RealVideo", "flv1": "Flash", "vp6": "Flash (VP6)", "vp6f": "Flash (VP6)", "vp6a": "Flash (VP6)",
            "theora": "Theora", "cinepak": "Cinepak", "svq1": "Sorenson", "svq3": "Sorenson", "mpeg1video": "MPEG-1",
            "h261": "H.261", "mpeg4": "XviD/DivX"}


def _format_name(v):
    c = v.get("codec") or ""
    if c == "h264":
        return "H.264 10-bit" if (v.get("bits") or 8) > 8 else "H.264 4:2:2/4:4:4"
    return _FORMATS.get(c, c.upper())


class JobsMixin:

    # ---- settings of the task screens

    def jobs_config(self):
        st = self.layout_settings()
        idx = {r["id"]: r["index"] for r in self.library.roots()}
        return {"audio_fix_auto": bool(self.cfg.get("audio_fix_auto")), "subs_auto": self.cfg.get("subs_auto") or "",
                "layout_levels": st["levels"], "layout_ages": st["ages"],  # 1.17+
                "layout_keep": None if st["keep"] is None else [{"root": idx[a], "path": b} for a, b in st["keep"] if a in idx],
                "reencode_presets": self.reencode_presets()}  # 1.18.1+: built-in and saved step sets (B6)

    def set_jobs_config(self, b):
        if "audio_fix_auto" in b:
            self.cfg["audio_fix_auto"] = bool(b["audio_fix_auto"])
        if "subs_auto" in b:
            self.cfg["subs_auto"] = ",".join(_langs(b["subs_auto"] or ""))
        if "layout_levels" in b:
            self.cfg["layout_levels"] = mp_arrange.norm_levels(b["layout_levels"]) or list(mp_arrange.DEFAULT_LEVELS)
        if "layout_ages" in b:
            self.cfg["layout_ages"] = mp_arrange.norm_ages(b["layout_ages"])
        if b.get("reencode_preset") is not None:  # 1.18.1+: {"name", "steps"} saved (same name replaced)
            self.save_reencode_preset(b["reencode_preset"])
        if b.get("reencode_preset_delete"):
            self.delete_reencode_preset(str(b["reencode_preset_delete"]))
        if "layout_keep" in b:
            if b["layout_keep"] is None:
                self.cfg.pop("layout_keep", None)
            else:
                ids = {r["index"]: r["id"] for r in self.library.roots()}
                self.cfg["layout_keep"] = [{"root_id": ids[int(k["root"])], "path": str(k.get("path") or "").strip("/")}
                                           for k in b["layout_keep"] if str(k.get("root", "")).isdigit() and int(k["root"]) in ids]
        self.save_cfg()
        return self.jobs_config()

    # ---- what a kind would do with one file (shared by the counters and the plans, so they agree)

    def _cost(self, info, pr, to_h, enc, k=1.0):
        """(estimated bytes, seconds of work or None) of encoding a video with `pr` at height `to_h` on `enc`."""
        dur = info.get("duration") or 0
        vk = pr["kbps"] * k * (to_h / (pr.get("height") or 1080)) ** 1.5
        if enc == "gpu":
            vk *= 1.2 if pr.get("codec") == "h264" else GPU_SIZE[((mp_media.hevc_info() or {}).get("gpu") or {}).get("bframes", False)]
        ak = sum(_audio_kbps(a, pr["audio"]) for a in _audio_keep(info, pr["audio"]))
        est = int(dur * (vk + ak) * 1000 / 8 * 1.02)
        sp = self._speed(enc)
        work = dur * to_h * to_h * 16 / 9 / sp if sp else None
        if work is not None and pr.get("codec") == "h264" and enc == "cpu":
            work *= 0.6  # x264 is quicker than x265
        return est, work

    def _pick(self, kind, f, full, o, ctx):
        """One video for a kind: None (not for this task), {"pending"} (the background check hasn't reached it),
        {"ok"} (checked, nothing to do), {"skip": reason, …} (would qualify, but can't or isn't worth it) or
        {"item": extra item fields, "cost": e → (bytes, seconds), "pr", "to_h"}."""
        info = f.get("info") or {}
        v, dur, size = info.get("video") or {}, info.get("duration") or 0, f["size"]
        if not v or not dur or size < MIN_SIZE:
            return None
        seeding = _in_torrent(f.get("real") or full, ctx["torrents"])
        if kind == "audio_fix":
            if is_old(v) or not any(a.get("codec") in AUDIO_SUSPECT for a in info.get("audio") or []):
                return None
            chk = ctx.get("check")
            if chk is None:
                bad = self._acheck_cached(f)
            else:
                try:
                    bad = self.audio_check(full, info, None if chk is True else chk)
                except OSError:
                    return None
            if bad is None:
                return {"pending": True}
            if not bad:
                return {"ok": True}
            if seeding:
                return {"skip": _seeding_text()}
            names = sorted({(a.get("codec") or "").upper() for a in info["audio"] if a["index"] in bad})
            errs = self._acheck_errors(f)
            return {"item": {"audio_fix": True, "broken": bad, "audio_codecs": names, "to_height": 0, "profile": "original",
                             "aac": o.get("quality") or "standard", "errors": errs},
                    "cost": lambda e: (size, size / (40 << 20))}
        if kind == "modernize":
            if not is_old(v):
                return None
            if seeding:
                return {"skip": _seeding_text()}
            pr = dict(MODERN, audio=AUDIO_MODES[o.get("audio") or "keep_if_ok"])
            h = v.get("height") or 480
            cost = lambda e: self._cost(info, pr, h, e)  # noqa: E731
            if cost(ctx["enc"])[0] > size * OLD_GROWTH:
                return {"skip": T("the result would be much bigger", "wynik byłby dużo większy"), "est": cost(ctx["enc"])[0]}
            return {"item": {"modern": True, "old_format": True, "profile": "modern", "audio_mode": pr["audio"],
                             "to_height": h - h % 2}, "cost": cost}
        if kind == "downscale":
            target = int(o["target"])
            cls = _res_class(v)
            if cls <= target * 1.05:
                return None
            if seeding:
                return {"skip": _seeding_text()}
            prof = TARGETS[target]
            pr = PROFILES[prof]
            h = int(round((v.get("height") or cls) * target / cls))
            h -= h % 2
            cost = lambda e: self._cost(info, pr, h, e)  # noqa: E731
            if cost(ctx["enc"])[0] > size * (1 - MIN_SAVING):
                return {"skip": T("already small, little to gain", "już mały, niewiele do zyskania"), "est": cost(ctx["enc"])[0]}
            return {"item": {"profile": prof, "to_height": h}, "cost": cost}
        if kind == "compress":
            if is_old(v):
                return None  # old pictures: "modernise old picture"
            if o.get("skip_hevc_av1", True) and v.get("codec") in ("hevc", "av1"):
                return None
            if o.get("only_high_bitrate", True) and size / dur * 3600 < HIGH_BITRATE:
                return None
            if seeding:
                return None if o.get("skip_seeding", True) else {"skip": _seeding_text()}
            q = QUALITIES[o.get("quality") or "balanced"]
            pr = dict(PROFILES["same"], crf=q["crf"], cq=q["cq"])
            h = v.get("height") or 1080
            h -= h % 2
            cost = lambda e: self._cost(info, pr, h, e, q["k"])  # noqa: E731
            if cost(ctx["enc"])[0] > size * (1 - MIN_SAVING):
                return {"skip": T("already small, little to gain", "już mały, niewiele do zyskania"), "est": cost(ctx["enc"])[0]}
            return {"item": {"profile": "same", "q": {"crf": q["crf"], "cq": q["cq"]}, "to_height": h}, "cost": cost}
        raise TaskError(f"unknown kind {kind}")

    def _acheck_cached(self, f):
        with self.alock:
            c = self.achecks.get(f.get("real") or "")
        if c and c[0] == f["size"] and c[1] == f["mtime"]:
            return c[2]
        return None

    def _acheck_errors(self, f):
        """Damaged frames found in the sound (all tracks), or None for a check from before 1.16."""
        with self.alock:
            c = self.achecks.get(f.get("real") or "")
        return sum(c[3].values()) if c and len(c) > 3 and isinstance(c[3], dict) else None

    def _torrents_soft(self):
        """The torrent list for counters: cached a minute; {} when the client doesn't answer."""
        at, v = self._tcache
        if time.time() - at < 60 and v is not None:
            return v
        try:
            v = self._torrents()
        except TaskError:
            v = {}
        self._tcache = (time.time(), v)
        return v

    def _matcher(self, scope):
        return mp_scope.Matcher(scope, self.library.roots(), self.catalog)

    def _plan_facts(self, m, p, probe=True, upgrade=False):
        """[(root, fact, full)] of the scope's videos, fresh, for a plan (the walk is the search's, cached minutes)."""
        listing = []
        for r in self._job_roots():
            for rel, is_dir, size in self.library._names(r):
                if not is_dir and os.path.splitext(rel)[1].lower() in VIDEO_EXT and m.match(r, rel):
                    listing.append((r, rel))
        out, dsigs = [], {}
        for k, (r, rel) in enumerate(listing):
            p["progress"] = int((100 if probe else 30) * k / max(len(listing), 1))
            p["step"] = os.path.basename(rel)
            full = os.path.join(r["path"], rel)
            if probe:
                f = self._fresh_fact(r, rel, full, dsigs, upgrade=upgrade)
            else:
                try:
                    st = os.stat(full)
                except OSError:
                    continue
                with self.flock:
                    f = self.facts.get(f"{r['id']}/{rel}")
                if not f or f["size"] != st.st_size or f["mtime"] != int(st.st_mtime):
                    f = {"root_id": r["id"], "rel": rel, "size": st.st_size, "mtime": int(st.st_mtime),
                         "real": os.path.realpath(full), "ino": [st.st_dev, st.st_ino]}
            if f:
                out.append((r, f, full))
        return out

    def _encoder_choices(self):
        return [e["id"] for e in self.encoders()]

    def plan_job(self, kind, o, scope=None):
        """audio_fix, modernize (with `audio`), downscale and compress (agent 1.16+): re-encodes in the scope, shown and
        run like `shrink` (items with op "shrink"), but each kind on its own."""
        if o.get("encoder") is not None and o["encoder"] not in ENCODERS:
            raise TaskError(T("encoder must be auto, cpu or gpu", "koder to auto, cpu albo gpu"))
        if kind == "audio_fix" and (o.get("quality") or "standard") not in AAC_QUALITIES:
            raise TaskError(T("quality must be standard or high", "quality to standard albo high"))
        if kind == "modernize" and o.get("audio") not in AUDIO_MODES:
            raise TaskError(T("audio must be keep_if_ok or always_aac", "audio to keep_if_ok albo always_aac"))
        if kind == "downscale":
            try:
                o["target"] = int(o.get("target"))
            except (TypeError, ValueError):
                o["target"] = None
            if o["target"] not in TARGETS:
                raise TaskError(T("target must be 1080, 720, 480 or 360", "target to 1080, 720, 480 albo 360"))
            if o.get("remember"):
                for x in (scope or {}).get("roots") or []:
                    self.set_profile(x["root"], x["path"], TARGETS[o["target"]])
        if kind == "compress" and (o.get("quality") or "balanced") not in QUALITIES:
            raise TaskError(T("quality must be transparent, balanced or saving",
                              "quality to transparent, balanced albo saving"))
        m = self._matcher(scope)

        def run(p):
            mp_media.detect_hevc()
            enc = self.resolve_encoder(o.get("encoder"))
            choices = self._encoder_choices()
            ctx = {"torrents": self._torrents(), "enc": enc,
                   "check": time.time() + AUDIO_CHECK_BUDGET if kind == "audio_fix" else None}
            items, skipped, unchecked = [], [], 0
            est_by, work_by = {e: 0 for e in choices}, {e: 0.0 for e in choices}
            for r, f, full in self._plan_facts(m, p):
                c = self._pick(kind, f, full, o, ctx)
                if not c or c.get("ok"):
                    continue
                if c.get("pending"):
                    unchecked += 1
                    continue
                info = f["info"]
                v, dur = info["video"], info["duration"]
                base = {"op": "shrink", "root_id": r["id"], "root": r["index"], "root_name": r["name"], "path": f["rel"],
                        "name": os.path.basename(f["rel"]), "size": f["size"]}
                if c.get("skip"):
                    skipped.append(dict(base, reason=c["skip"], **({"est": c["est"]} if c.get("est") else {})))
                    continue
                est, work = c["cost"](enc)
                items.append(dict(base, est=est, work=work, codec=v.get("codec"), height=v.get("height"), duration=dur,
                                  bitrate=int(f["size"] * 8 / dur / 1000), **c["item"]))
                for e in choices:
                    b, w = c["cost"](e)
                    est_by[e] += b
                    work_by[e] = None if w is None or work_by[e] is None else work_by[e] + w
            p["progress"] = 100
            warnings = self._codec_warnings() if kind in ("downscale", "compress") else []
            if unchecked:
                warnings.insert(0, T(f"{unchecked} files haven't been checked for damaged sound yet: the server checks "
                                     f"them in the background, a later preview will include them",
                                     f"{unchecked} plików nie sprawdzono jeszcze pod kątem uszkodzonego dźwięku: serwer "
                                     f"sprawdza je w tle, późniejszy podgląd je uwzględni"))
            total = sum(i["size"] for i in items)
            title = {"audio_fix": _title("Fix sound", "Napraw dźwięk"),
                     "modernize": _title("Modernise old picture", "Unowocześnij stary obraz"),
                     "downscale": _title(f"Downscale to {o.get('target')}p", f"Zmniejsz do {o.get('target')}p"),
                     "compress": _title("Compression", "Kompresja")}[kind]
            return {"kind": kind, **title, "items": items, "bytes": total, "est": sum(i["est"] for i in items),
                    "saved": total - sum(i["est"] for i in items), "hours": round(sum(i["duration"] for i in items) / 3600, 1),
                    "encoder": self.encoder_label(enc), "encoder_id": None if kind == "audio_fix" else enc,
                    "encoders": [dict(e, est=est_by[e["id"]], saved=total - est_by[e["id"]],
                                      work_hours=None if work_by[e["id"]] is None else round(work_by[e["id"]] / 3600, 1))
                                 for e in self.encoders()],
                    "warnings": warnings, "skipped": skipped[:100], "audio_unchecked": unchecked,
                    "trash_days": self.trash_days(), "options": {k: o.get(k) for k in o}}
        return self._plan(kind, run, m)

    def _keep_order(self, keep, files, roots_by_id):
        """The copy of a duplicate group that stays first: a seeding torrent's copy (it can't be deleted here), then by
        `keep`: folder (a folder whose tag fits the title, any tagged folder), quality (height, size) or space (the disk
        with the most free room)."""
        def fits(x):
            tags = set((roots_by_id.get(x["root_id"]) or {}).get("tags") or [])
            it = x.get("title") or {}
            want = "seriale" if it.get("kind") == "series" else "bajki" if it.get("kids") else "filmy"
            return 2 if want in tags else 1 if tags else 0
        free = {}

        def room(x):
            rid = x["root_id"]
            if rid not in free:
                try:
                    free[rid] = shutil.disk_usage(roots_by_id[rid]["path"]).free
                except (OSError, KeyError):
                    free[rid] = 0
            return free[rid]
        if keep == "quality":
            key = lambda x: (not x["torrent"], -(x.get("height") or 0), -x["size"], -fits(x))  # noqa: E731
        elif keep == "space":
            key = lambda x: (not x["torrent"], -room(x), -fits(x))  # noqa: E731
        else:
            key = lambda x: (not x["torrent"], -fits(x), x["path"].count("/"), x["path"].lower())  # noqa: E731
        return sorted(files, key=key)

    def _same_titles(self, m, roots_by_id):
        """Catalog titles present more than once (the same IMDb id) in the scope: [[file dicts]]."""
        by = {}
        for key, it in m.items.items():
            r = roots_by_id.get(it.get("root_id"))
            if not r or not it.get("imdb") or not m.match(r, it["rel"]):
                continue
            by.setdefault(it["imdb"], []).append({
                "root": r["index"], "root_id": r["id"], "root_name": r["name"], "path": it["rel"], "size": it["size"],
                "height": (it.get("probe") or {}).get("height"), "title": it, "dir": bool(it.get("dir"))})
        out = []
        for g in by.values():
            # one copy inside another (a film folder and its file) is not a second copy
            g = [x for x in g if not any(y is not x and y["root_id"] == x["root_id"] and x["path"].startswith(y["path"] + "/")
                                         for y in g)]
            if len(g) > 1:
                out.append(g)
        return out

    def plan_dupes2(self, what, keep, scope=None):
        """Duplicates (agent 1.16+): `what` same_files (identical files) or same_title (the same catalog title in other
        versions); `keep` folder / quality / space chooses the copy that stays and the others become trash items;
        `manual` leaves the choice to the owner (groups only, as plan_dupes)."""
        if what not in ("same_files", "same_title"):
            raise TaskError(T("what must be same_files or same_title", "what to same_files albo same_title"))
        if keep not in ("folder", "quality", "space", "manual"):
            raise TaskError(T("keep must be folder, quality, space or manual", "keep to folder, quality, space albo manual"))
        m = self._matcher(scope)

        def run(p):
            roots_by_id = {r["id"]: r for r in self._job_roots()}
            torrents = self._torrents_soft()
            if what == "same_files":
                files = [(f"{r['id']}/{f['rel']}", full, f) for r, f, full in self._plan_facts(m, p, probe=False)
                         if f["size"] >= MIN_SIZE]
                info = {k: (f, full) for k, full, f in files}
                groups = []
                for g in self._find_dupes(files, p):
                    gs = []
                    for k in g:
                        f, full = info[k]
                        r = roots_by_id[f["root_id"]]
                        gs.append({"root": r["index"], "root_id": r["id"], "root_name": r["name"], "path": f["rel"],
                                   "size": f["size"], "height": ((f.get("info") or {}).get("video") or {}).get("height"),
                                   "title": m.title(r["id"], f["rel"]), "dir": False})
                    groups.append(gs)
            else:
                p["progress"] = 50
                groups = self._same_titles(m, roots_by_id)
            out, items, skipped = [], [], []
            for g in groups:
                for x in g:
                    x["torrent"] = _in_torrent(os.path.realpath(os.path.join(roots_by_id[x["root_id"]]["path"], x["path"])),
                                               torrents)
                g = self._keep_order(keep, g, roots_by_id)
                size = g[0]["size"]
                pub = [{k: v for k, v in dict(x, keep=(n == 0 and keep != "manual")).items() if k not in ("title",)}
                       for n, x in enumerate(g)]
                out.append({"size": size, "files": pub, "wasted": sum(x["size"] for x in g[1:])})
                if keep == "manual":
                    continue
                for x in g[1:]:
                    full = os.path.join(roots_by_id[x["root_id"]]["path"], x["path"])
                    item = {"op": "trash", "root_id": x["root_id"], "root": x["root"], "root_name": x["root_name"],
                            "path": x["path"], "name": os.path.basename(x["path"]), "size": x["size"],
                            "extra": companions(full) if not x["dir"] and os.path.isfile(full) else [],
                            "kept": g[0]["path"]}
                    if x["torrent"]:
                        skipped.append(dict(item, reason=T("still in the torrent client: remove it there",
                                                             "jest jeszcze w kliencie torrentów: usuń go tam")))
                    else:
                        items.append(item)
            out.sort(key=lambda g: -g["wasted"])
            p["progress"] = 100
            return {"kind": "dupes", **_title("Duplicates", "Duplikaty"), "what": what, "keep": keep, "groups": out,
                    "items": items, "bytes": sum(i["size"] for i in items), "wasted": sum(g["wasted"] for g in out),
                    "skipped": skipped[:100], "trash_days": self.trash_days()}
        return self._plan("dupes", run, m)

    def plan_free_space(self, root_index, amount=None, to_percent=None, order="watched_oldest", skip_kept=True):
        """Free up (agent 1.16+): deletes whole titles (top-level items) from the disk of library folder `root_index`
        to the trash, `amount` bytes or until `to_percent` % of the disk is free (default 15). `order`: watched_oldest
        (only what was streamed from this server, the longest ago first) or largest. `skip_kept`: what somebody
        streamed in the last WATCHING_DAYS days stays. Moving and re-encoding are other tasks."""
        if order not in ("watched_oldest", "largest"):
            raise TaskError(T("order must be watched_oldest or largest", "order to watched_oldest albo largest"))

        def run(p):
            roots = self.library.roots()
            if not 0 <= root_index < len(roots) or not roots[root_index]["exists"]:
                raise TaskError(T("no such library folder", "nie ma takiego katalogu biblioteki"), 404)
            dev = _dev(roots[root_index]["path"])
            u = shutil.disk_usage(roots[root_index]["path"])
            pct = to_percent if to_percent is not None else (None if amount else 15)
            want = int(amount) if amount else max(int(u.total * float(pct) / 100) - u.free, 0)
            torrents, now = self._torrents(), time.time()
            cands, skipped = [], []
            src = [r for r in self._job_roots() if _dev(r["path"]) == dev]
            for k, r in enumerate(src):
                p["progress"] = int(100 * k / max(len(src), 1))
                p["step"] = r["name"]
                try:
                    names = sorted(os.listdir(r["path"]))
                except OSError:
                    continue
                for name in names:
                    full = os.path.join(r["path"], name)
                    if name.startswith(".") or (os.path.isfile(full) and os.path.splitext(name)[1].lower() not in VIDEO_EXT):
                        continue
                    size, n = tree_size(full)
                    extra = companions(full) if os.path.isfile(full) else []
                    size += sum(tree_size(os.path.join(r["path"], e))[0] for e in extra)
                    last = self._last_played(os.path.realpath(full))
                    item = {"op": "trash", "root_id": r["id"], "root": r["index"], "root_name": r["name"], "path": name,
                            "name": name, "size": size, "files": n, "extra": extra, "played": last}
                    if self._inside(full, torrents):
                        skipped.append(dict(item, reason=_seeding_text()))
                        continue
                    if skip_kept and last and now - last < WATCHING_DAYS * 86400:
                        skipped.append(dict(item, reason=T("being watched", "jest właśnie oglądane")))
                        continue
                    if order == "watched_oldest" and not last:
                        continue  # never streamed from here: nobody watched it (as far as this server knows)
                    cands.append(item)
            cands.sort(key=(lambda i: i["played"]) if order == "watched_oldest" else (lambda i: -i["size"]))
            chosen, freed = [], 0
            for i in cands:
                if freed >= want:
                    break
                chosen.append(i)
                freed += i["size"]
            warnings = []
            if not want:
                warnings.append(T(f"This disk is already {round(100 * u.free / max(u.total, 1))}% free: nothing to do.",
                                  f"Ten dysk ma już {round(100 * u.free / max(u.total, 1))}% wolnego: nie ma nic do zrobienia."))
            elif order == "watched_oldest" and not cands:
                warnings.append(T("Nothing qualifies: only titles streamed from this server count as watched, and none "
                                  "was streamed yet (or all are being watched). Choose \"largest files\" instead.",
                                  "Nic się nie kwalifikuje: za obejrzane liczą się tylko tytuły odtwarzane z tego serwera, "
                                  "a żadnego jeszcze nie odtwarzano (albo wszystkie są właśnie oglądane). Wybierz raczej "
                                  "\"największe pliki\"."))
            elif freed < want:
                warnings.append(T(f"Only {_size_text(freed)} of {_size_text(want)} can be freed this way.",
                                  f"W ten sposób da się zwolnić tylko {_size_text(freed)} z {_size_text(want)}."))
            if chosen and self.trash_days() > 0:
                warnings.append(T(f"Deleted titles stay in the trash for {self.trash_days()} days: the space comes back "
                                  f"when the trash is emptied.",
                                  f"Usunięte tytuły leżą w koszu {self.trash_days()} dni: miejsce wróci po opróżnieniu kosza."))
            name = roots[root_index]["name"]
            return {"kind": "free", **_title(f"Free up {name}", f"Zwolnij {name}"), "need": want, "free_before": u.free,
                    "free_after": u.free + freed, "total": u.total, "order": order, "items": chosen, "bytes": freed,
                    "skipped": skipped[:50], "warnings": warnings, "trash_days": self.trash_days()}
        return self._plan("free", run)

    def plan_subs_get2(self, languages, which, order="recent", scope=None):
        """Download missing subtitles (agent 1.16+): `which` no_subs (videos without any subtitles) or no_subs_in_lang
        (missing any of `languages`), `order` recent (streamed lately, then newest files) or alpha; `new_only` stores
        `subs_auto` instead: new files get them after the background scan finds them."""
        langs = _langs(languages)
        if not langs:
            raise TaskError(T("give languages, e.g. pl,en", "podaj języki, np. pl,en"))
        if which not in ("no_subs", "no_subs_in_lang", "new_only"):
            raise TaskError(T("which must be no_subs, no_subs_in_lang or new_only", "which to no_subs, no_subs_in_lang albo new_only"))
        if order not in ("recent", "alpha"):
            raise TaskError(T("order must be recent or alpha", "order to recent albo alpha"))
        m = self._matcher(scope)
        if which == "new_only":
            self.cfg["subs_auto"] = ",".join(langs)
            self.save_cfg()

        def run(p):
            items, have = [], 0
            if which != "new_only":
                for r, f, full in self._plan_facts(m, p):
                    if mp_ident.is_extra(f["rel"]):
                        continue
                    got, any_subs = _subs_langs(f)
                    if which == "no_subs":
                        if any_subs:
                            have += 1
                            continue
                        missing = langs
                    else:
                        missing = [x for x in langs if x not in got]
                        if not missing:
                            have += 1
                            continue
                    items.append({"op": "subs_get", "root_id": r["id"], "root": r["index"], "root_name": r["name"],
                                  "path": f["rel"], "name": os.path.basename(f["rel"]), "size": 1, "langs": missing,
                                  "_played": self._last_played(f.get("real") or "") or 0, "_mtime": f["mtime"]})
            if order == "recent":
                items.sort(key=lambda i: (-i["_played"], -i["_mtime"]))
            else:
                items.sort(key=lambda i: i["path"].lower())
            for i in items:
                i.pop("_played")
                i.pop("_mtime")
            p["progress"] = 100
            warnings = []
            if which == "new_only":
                warnings.append(T("New films get subtitles by themselves after they're added.",
                                  "Nowe filmy dostaną napisy same, po dodaniu."))
            return {"kind": "subs_get", **_title("Download missing subtitles", "Pobierz brakujące napisy"),
                    "items": items, "skipped": [], "checked": have, "bytes": 0, "languages": langs, "which": which,
                    "order": order, "minutes": len(items), "subs_auto": self.cfg.get("subs_auto") or "",
                    "per_day": SUBS_PER_DAY, "days": -(-len(items) * len(langs) // SUBS_PER_DAY), "warnings": warnings}
        return self._plan("subs_get", run, m)

    def plan_subs2(self, which, sub_to_srt=True, mark_bad=False, queue_bad=False, scope=None):
        """Fit subtitles (agent 1.16+): `which` unchecked / watched_now (unchecked ones next to what was streamed in the
        last WATCHING_DAYS days) / all; `sub_to_srt` false leaves text .sub files out (fitting converts them);
        `mark_bad` moves subtitles that don't fit aside to Subtitles/ (not deleted); `queue_bad` queues a download
        task for their languages afterwards."""
        import mp_subsync
        if which not in ("unchecked", "watched_now", "all"):
            raise TaskError(T("which must be unchecked, watched_now or all", "which to unchecked, watched_now albo all"))
        m = self._matcher(scope)

        def run(p):
            if not mp_subsync.available():
                raise TaskError(T("speech detection is not installed on this computer (numpy, webrtcvad)",
                                  "na tym komputerze nie ma wykrywania mowy (numpy, webrtcvad)"))
            torrents, cache, watched = self._torrents(), {}, self._watched_dirs()
            items, checked = [], 0
            for r, f, full in self._plan_facts(m, p):
                if which == "watched_now" and os.path.dirname(f.get("real") or full) not in watched:
                    continue
                subs = []
                for x in f.get("side") or []:
                    if not x.get("fit") or (not sub_to_srt and x["name"].lower().endswith(".sub")):
                        continue
                    sp = os.path.join(os.path.dirname(full), x["name"])
                    if which != "all" and self.subsync.get(sp):
                        checked += 1
                        continue
                    subs.append({"name": x["name"], "lang": x.get("lang") or None,
                                 "locked": self.torrent_owned(sp, torrents, cache)})
                if not subs:
                    continue
                size = 0
                for x in subs:
                    try:
                        size += os.path.getsize(os.path.join(os.path.dirname(full), x["name"]))
                    except OSError:
                        pass
                items.append({"op": "subs", "root_id": r["id"], "root": r["index"], "root_name": r["name"],
                              "path": f["rel"], "name": os.path.basename(f["rel"]), "size": size, "subs": subs,
                              **({"mark_bad": True} if mark_bad else {}), **({"queue_bad": True} if queue_bad else {})})
            p["progress"] = 100
            return {"kind": "subs", **_title("Fit subtitles to the films", "Dopasuj napisy do filmów"),
                    "items": items, "skipped": [], "checked": checked, "bytes": sum(i["size"] for i in items),
                    "minutes": -(-len(items) // 2), "which": which}
        return self._plan("subs", run, m)

    # ---- counters for the task list (GET /api/library/jobs): only what the background scans know

    def jobs(self, scope=None, languages="pl,en", levels=None, reencode=None, only=None):
        """`levels` (1.17+): the folder layout counter with these levels (the A2 screen while the owner taps them).
        `reencode` (1.18.1+): the re-encode counter for these steps (B6); `only` = "reencode": just that counter."""
        m = self._matcher(scope)
        roots = {r["id"]: r for r in self._job_roots()}
        with self.flock:
            facts = list(self.facts.items())
            listing = list(self.facts_list or [])
            groups = list(self.dupe_groups)
            at = self.facts_at
        inscope = [(k, roots[f["root_id"]], f) for k, f in facts if f["root_id"] in roots and m.match(roots[f["root_id"]], f["rel"])]
        keys = {k for k, _, _ in inscope}
        known = {k for k, _ in facts}
        lst = [(a, b, s) for a, b, s in listing if a in roots and m.match(roots[a], b)]
        pending = sum(1 for a, b, _ in lst if f"{a}/{b}" not in known)
        torrents = self._torrents_soft()
        enc = self.resolve_encoder(None)
        choices = self._encoder_choices()
        ctx = {"torrents": torrents, "enc": enc, "check": None}
        hours = lambda s: None if s is None else int(s)  # noqa: E731
        jobs = {}

        def envelope():
            return {"scope_label": m.label(mp_i18n.lang()), "files": len(lst) if listing else len(inscope),
                    "size": sum(s for _, _, s in lst) if listing else sum(f["size"] for _, _, f in inscope),
                    "checked_at": int(at) if at else None, "pending": pending, "jobs": jobs}

        # re-encode (1.18.1+, B6): its summary card for the steps being chosen; without steps only the step sets
        import mp_tasks_reencode
        rsteps = mp_tasks_reencode.norm_steps(reencode) if reencode is not None else None
        ropts = {"presets": self.reencode_presets()}
        if rsteps is not None and mp_tasks_reencode.active(rsteps):
            rc = self.reencode_counter(inscope, rsteps, torrents, enc, choices)
            ropts.update(steps=rsteps, passes=rc["passes"], by_step=rc["by_step"], unmeasured=rc["waiting"],
                         skipped=rc["skipped"], warnings=mp_tasks_reencode.warnings(rsteps))
            jobs["reencode"] = {"applicable": True, "count": rc["count"], "size": rc["size"], "saving": max(rc["saving"], 0),
                                "eta": rc["eta"] or 0, "pending": pending, "options": ropts,
                                "why": None if rc["count"] else T("no file needs these steps", "żaden plik nie potrzebuje tych kroków")}
        else:
            jobs["reencode"] = {"applicable": True, "count": 0, "size": 0, "saving": 0, "eta": 0, "pending": 0, "why": None,
                                "options": ropts}
        if only == "reencode":
            return envelope()

        def count(kind, o, examples=None):
            n = size = saving = 0
            work = {e: 0.0 for e in choices}
            waiting = 0
            for k, r, f in inscope:
                full = os.path.join(r["path"], f["rel"])
                c = self._pick(kind, f, full, o, ctx)
                if not c or c.get("ok") or c.get("skip"):
                    continue
                if c.get("pending"):
                    waiting += 1
                    continue
                n += 1
                size += f["size"]
                saving += f["size"] - c["cost"](enc)[0]
                for e in choices:
                    w = c["cost"](e)[1]
                    work[e] = None if w is None or work[e] is None else work[e] + w
                if examples is not None:
                    examples.append((f, c))
            return {"count": n, "size": size, "saving": max(saving, 0), "eta": hours(work.get(enc)),
                    "eta_by": {e: hours(work[e]) for e in choices}, "waiting": waiting}

        def job(applicable=True, count=0, size=0, saving=0, eta=0, why=None, pending=0, options=None):
            return {"applicable": applicable, "count": count, "size": size, "saving": saving, "eta": eta,
                    "why": why, "pending": pending, "options": options or {}}
        whole_only = T("whole disks only: on the list for the whole computer", "tylko całe dyski: na liście dla całego komputera")

        # free up: whole disks
        if not m.whole:
            jobs["free"] = job(False, why=whole_only)
        else:
            low, need, seen = 0, 0, set()
            for r in roots.values():
                d = _dev(r["path"])
                if d in seen:
                    continue
                seen.add(d)
                try:
                    u = shutil.disk_usage(r["path"])
                except OSError:
                    continue
                if u.free < u.total * 0.15:
                    low += 1
                    need += int(u.total * 0.15) - u.free
            jobs["free"] = job(count=low, size=need, saving=need,
                               why=None if low else T("every disk has more than 15% free", "każdy dysk ma ponad 15% wolnego"))

        # duplicates
        sf_groups, sf_saving, seeding = 0, 0, 0
        size_of = {k: f for k, _, f in inscope}
        for g in groups:
            g = [k for k in g if k in keys]
            if len(g) < 2:
                continue
            sf_groups += 1
            sf_saving += size_of[g[0]]["size"] * (len(g) - 1)
            seeding += sum(1 for k in g if _in_torrent(size_of[k].get("real") or "", torrents))
        st = self._same_titles(m, roots)
        st_saving = sum(sum(x["size"] for x in g) - max(x["size"] for x in g) for g in st)
        jobs["dupes"] = job(count=sf_groups, size=sf_saving, saving=sf_saving, pending=pending,
                            why=None if sf_groups else T("no identical files found", "nie znaleziono identycznych plików"),
                            options={"same_files": {"groups": sf_groups, "saving": sf_saving},
                                     "same_title": {"titles": len(st), "saving": st_saving}, "seeding": seeding})

        # fix sound
        ex = []
        c = count("audio_fix", {}, ex)
        suspect = [f for _, _, f in inscope if (f.get("info") or {}).get("video") and (f["info"].get("duration") or 0)
                   and f["size"] >= MIN_SIZE and not is_old(f["info"]["video"])
                   and any(a.get("codec") in AUDIO_SUSPECT for a in f["info"].get("audio") or [])]
        checked = sum(1 for f in suspect if self._acheck_cached(f) is not None)
        jobs["audio_fix"] = job(count=c["count"], size=c["size"], saving=0, eta=c["eta"] if c["count"] else 0,
                                pending=len(suspect) - checked + pending,
                                why=None if c["count"] else (T("still being checked in the background", "jeszcze sprawdzane w tle")
                                                             if len(suspect) > checked else T("no damaged sound found", "nie znaleziono uszkodzonego dźwięku")),
                                options={"checked": checked, "total": len(suspect),
                                         "examples": [{"name": os.path.basename(f["rel"]), "codec": ", ".join(x["item"]["audio_codecs"]),
                                                       "errors": x["item"]["errors"]} for f, x in ex[:3]]})

        # old picture
        ex = []
        c = count("modernize", {"audio": "keep_if_ok"}, ex)
        jobs["modernize"] = job(count=c["count"], size=c["size"], saving=c["saving"], eta=c["eta"] if c["count"] else 0,
                                pending=pending, why=None if c["count"] else T("no old formats found", "nie znaleziono starych formatów"),
                                options={"examples": [{"name": os.path.basename(f["rel"]), "format": _format_name(f["info"]["video"]),
                                                       "size": f["size"]} for f, _ in ex[:3]],
                                         "eta_gpu": c["eta_by"].get("gpu") if c["count"] else (0 if "gpu" in choices else None),
                                         "eta_cpu": c["eta_by"].get("cpu") if c["count"] else 0})

        # downscale: per target, and per folder
        by_target, folders, sizes = {}, {}, {}
        for t in TARGETS:
            ex = []
            c = count("downscale", {"target": t}, ex)
            by_target[str(t)] = {"count": c["count"], "saving": c["saving"], "eta": c["eta"] if c["count"] else 0}
            sizes[t] = c["size"]
            for f, x in ex:
                r = roots[f["root_id"]]
                top = f["rel"].split("/", 1)[0] if "/" in f["rel"] else ""
                d = folders.setdefault((r["index"], top), {"root": r["index"], "path": top, "name": top or r["name"],
                                                          "files": {}, "saving_by_target": {str(x): 0 for x in TARGETS}})
                d["saving_by_target"][str(t)] += max(f["size"] - x["cost"](enc)[0], 0)
        for k, r, f in inscope:  # the resolution mix of the folders that have something to downscale
            top = f["rel"].split("/", 1)[0] if "/" in f["rel"] else ""
            d = folders.get((r["index"], top))
            v = (f.get("info") or {}).get("video")
            if d is not None and v:
                cls = _res_label(_res_class(v))
                d["files"][cls] = d["files"].get(cls, 0) + 1
        flist = []
        for d in sorted(folders.values(), key=lambda d: -max(d["saving_by_target"].values())):
            order = sorted(d.pop("files").items(), key=lambda kv: -_RES_ORDER.get(kv[0], 0))
            flist.append(dict(d, mix=" · ".join(f"{k} {n}" for k, n in order)))
        top = by_target["1080"]
        jobs["downscale"] = job(count=top["count"], size=sizes[1080], saving=top["saving"], eta=top["eta"], pending=pending,
                                why=None if any(x["count"] for x in by_target.values()) else T("nothing bigger than 360p to downscale", "nic większego niż 360p do zmniejszenia"),
                                options={"by_target": by_target, "folders": flist[:50]})

        # compression (default filters)
        by_q = {}
        for q in QUALITIES:
            c = count("compress", {"quality": q})
            by_q[q] = {"count": c["count"], "saving": c["saving"], "eta": c["eta"] if c["count"] else 0, "size": c["size"],
                       "need": None}
        ex = []
        bal = count("compress", {"quality": "balanced"}, ex)
        need_free = sum(x["cost"](enc)[0] for _, x in ex)
        for q in by_q.values():
            q.pop("need")
            q.pop("size")
        jobs["compress"] = job(count=bal["count"], size=bal["size"], saving=bal["saving"], eta=bal["eta"] if bal["count"] else 0,
                               pending=pending, why=None if bal["count"] else T("no big files to compress", "brak dużych plików do kompresji"),
                               options={"by_quality": by_q, "need_free": need_free})

        # download subtitles
        langs = _langs(languages) or ["pl", "en"]
        no_subs = no_lang = have = 0
        for k, r, f in inscope:
            if mp_ident.is_extra(f["rel"]) or "side" not in f:
                continue
            got, any_subs = _subs_langs(f)
            no_subs += not any_subs
            if all(x in got for x in langs):
                have += 1
            else:
                no_lang += 1
        subs_ok = self.subs_ok() if self.subs_ok else True
        jobs["subs_get"] = job(applicable=subs_ok, count=no_lang, eta=no_lang * 60, pending=pending,
                               why=(None if no_lang else T("every film has subtitles in these languages",
                                                           "każdy film ma napisy w tych językach")) if subs_ok else
                               T("subtitle search isn't installed on this computer", "na tym komputerze nie ma wyszukiwania napisów"),
                               options={"no_subs": no_subs, "no_subs_in_lang": no_lang, "have": have, "per_day": SUBS_PER_DAY,
                                        "languages": ",".join(langs)})

        # fit subtitles
        import mp_subsync
        fit_ok = mp_subsync.available()
        watched = self._watched_dirs()
        unchecked = watched_now = every = fitted = 0
        for k, r, f in inscope:
            full = os.path.join(r["path"], f["rel"])
            subs = [x for x in f.get("side") or [] if x.get("fit")]
            if not subs:
                continue
            every += 1
            done = [bool(self.subsync and self.subsync.get(os.path.join(os.path.dirname(full), x["name"]))) for x in subs]
            fitted += sum(done)
            if not all(done):
                unchecked += 1
                if os.path.dirname(f.get("real") or full) in watched:
                    watched_now += 1
        jobs["subs"] = job(applicable=fit_ok, count=unchecked, size=0, eta=unchecked * 30, pending=pending,
                           why=(None if unchecked else T("every subtitle file was checked", "wszystkie napisy już sprawdzono"))
                           if fit_ok else T("speech detection is not installed on this computer (numpy, webrtcvad)",
                                            "na tym komputerze nie ma wykrywania mowy (numpy, webrtcvad)"),
                           options={"unchecked": unchecked, "watched_now": watched_now, "all": every, "fitted_before": fitted})

        # catalog check
        total = recog = 0
        for key, it in m.items.items():
            r = roots.get(it.get("root_id"))
            if not r or not m.match(r, it.get("file") or it["rel"]):
                continue
            total += 1
            recog += bool(it.get("imdb"))
        jobs["tags"] = job(count=total - recog, pending=0,
                           why=None if total - recog else T("every title is recognised", "wszystkie tytuły rozpoznane"),
                           options={"recognised": recog, "total": total})

        # folder layout (1.17+): titles that would move, with the settings or the levels being chosen
        lset = self.layout_settings()
        lv = mp_arrange.norm_levels(levels) if levels is not None else lset["levels"]
        moves, skipped, stats, hand, confirmed = self._layout(m, torrents, lv or lset["levels"], lset["ages"])
        tree = {}
        for u, dr, sub in moves:
            k = "/".join(x for x in ([dr["name"]] + sub.split("/")[:1]) if x)
            tree[k] = tree.get(k, 0) + 1
        jobs["layout"] = job(count=len(moves), size=sum(u["size"] for u, _, _ in moves), eta=len(moves),
                             why=None if moves else T("every title is in its place", "każdy tytuł jest na swoim miejscu"),
                             options={"levels": lset["levels"], "ages": lset["ages"], "confirmed": confirmed,
                                      "hand_folders": hand[:50], "unsure": stats["unsure"], "seeding": stats["seeding"],
                                      "kept": stats["kept"], "tree": [{"path": k, "titles": n} for k, n in sorted(tree.items())][:30]})

        # balance between disks (1.17+): the whole computer, like free up
        if not m.whole:
            jobs["balance"] = job(False, why=whole_only)
        else:
            by_goal, rows = {}, None
            for g in mp_arrange.GOALS:
                bm, _, disks, after = self._balance(g, None, torrents)
                rows = rows or self._disk_rows(disks, {})
                b = sum(u["size"] for u, _, _ in bm)
                by_goal[g] = {"count": len(bm), "size": b, "eta": int(b / mp_arrange.COPY_SPEED),
                              "disks": self._disk_rows(disks, after)}
            ev = by_goal["even"]
            many = rows is not None and len(rows) >= 2
            jobs["balance"] = job(applicable=many, count=ev["count"], size=ev["size"], eta=ev["eta"],
                                  why=(None if ev["count"] else T("the disks are balanced", "dyski są wyrównane")) if many
                                  else T("the library is on one disk only", "biblioteka jest tylko na jednym dysku"),
                                  options={"disks": rows or [], "by_goal": by_goal})

        # ready for Chromecast (1.17+)
        cr, cr_wait = [], 0
        for k, r, f in inscope:
            c = self._cast_need(f, {}, torrents)
            if not c or c.get("skip"):
                continue
            if c.get("pending"):
                cr_wait += 1
                continue
            it = m.title(r["id"], f["rel"]) or {}
            cr.append(dict(c, name=os.path.basename(f["rel"]), title=it.get("title"), series=it.get("kind") == "series",
                           size=f["size"]))
        groups = self._cast_titles(cr)
        jobs["cast_ready"] = job(count=len(cr), size=sum(x["size"] for x in cr),
                                 saving=-sum(x["est"] - x["size"] for x in cr), eta=int(sum(x["work"] for x in cr)),
                                 pending=pending + cr_wait,
                                 why=None if cr else T("every file plays on a Chromecast as it is", "każdy plik gra na Chromecaście tak, jak jest"),
                                 options={"by_title": groups[:3], "more_files": sum(g["files"] for g in groups[3:]),
                                          "by_reason": {"aac": sum(1 for x in cr if x.get("add_aac")),
                                                        "subs": sum(1 for x in cr if x.get("subs")),
                                                        "avi": sum(1 for x in cr if x.get("avi"))},
                                          "growth": sum(x["est"] - x["size"] for x in cr)})

        # trash: whole disks
        if not m.whole:
            jobs["trash"] = job(False, why=whole_only, options={"days": self.trash_days()})
        else:
            tr = self._trash_list()
            tsize, tfiles = sum(x["size"] for x in tr), sum(x["files"] for x in tr)
            jobs["trash"] = job(count=tfiles, size=tsize, saving=tsize, why=None if tfiles else T("the trash is empty", "kosz jest pusty"),
                                options={"days": self.trash_days()})
        return envelope()

    # ---- tasks created without a preview (the owner's "by itself" settings for new files)

    def _auto_new(self, keys):
        roots = {r["id"]: r for r in self._job_roots()}
        with self.flock:
            facts = [(k, self.facts.get(k)) for k in keys]
        facts = [(roots[f["root_id"]], f) for k, f in facts if f and f["root_id"] in roots]
        if self.cfg.get("audio_fix_auto"):
            ctx = {"torrents": self._torrents_soft(), "enc": "cpu", "check": True}
            items = []
            for r, f in facts:
                c = self._pick("audio_fix", f, os.path.join(r["path"], f["rel"]), {"quality": "standard"}, ctx)
                if c and c.get("item"):
                    info = f["info"]
                    items.append({"op": "shrink", "root_id": r["id"], "root": r["index"], "root_name": r["name"],
                                  "path": f["rel"], "name": os.path.basename(f["rel"]), "size": f["size"], "est": f["size"],
                                  "codec": info["video"].get("codec"), "height": info["video"].get("height"),
                                  "duration": info["duration"], "bitrate": int(f["size"] * 8 / info["duration"] / 1000),
                                  **c["item"]})
            if items:
                self.log(f"jobs: fixing the sound of {len(items)} new files by itself")
                self._add_task({"kind": "audio_fix", **_title("Fix sound (new files)", "Napraw dźwięk (nowe pliki)")}, items)
        langs = _langs(self.cfg.get("subs_auto") or "")
        if langs and self.fetch_subs:
            items = []
            for r, f in facts:
                if mp_ident.is_extra(f["rel"]):
                    continue
                got, _ = _subs_langs(f)
                missing = [x for x in langs if x not in got]
                if missing:
                    items.append({"op": "subs_get", "root_id": r["id"], "root": r["index"], "root_name": r["name"],
                                  "path": f["rel"], "name": os.path.basename(f["rel"]), "size": 1, "langs": missing})
            if items:
                self.log(f"jobs: downloading subtitles for {len(items)} new files by itself")
                self._add_task({"kind": "subs_get", **_title("Download subtitles (new files)", "Pobierz napisy (nowe pliki)")}, items)

    # ---- one file for another module (after download, mp_afterdl, agent 1.23+)

    def fact_of(self, full):
        """(library folder, fact) of one video by its path, probed now when the scan doesn't know it yet; None when the
        file is gone or outside the library folders."""
        real = os.path.realpath(full)
        for r in self._job_roots():
            base = os.path.realpath(r["path"])
            if real.startswith(base + os.sep):
                rel = os.path.relpath(real, base)
                f = self._fresh_fact(r, rel, os.path.join(r["path"], rel), {}, upgrade=True)
                return (r, f) if f else None
        return None

    def one_file_task(self, kind, full, o):
        """A task of one item for `full`, queued like any other (it waits while somebody watches, survives a restart):
        kind "compress" (o: quality, encoder, skip_hevc_av1, original "delete" skips the trash) or "cast_ready" (o: add_aac, subs_to_srt, avi_to_mkv).
        → {"task": id}, or {"none": reason} when there is nothing to do or it can't be done."""
        rf = self.fact_of(full)
        if not rf:
            return {"none": T("not in a library folder", "poza folderami biblioteki")}
        r, f = rf
        base = {"root_id": r["id"], "root": r["index"], "root_name": r["name"], "path": f["rel"],
                "name": os.path.basename(f["rel"]), "size": f["size"]}
        torrents = self._torrents()
        if kind == "cast_ready":
            opts = {k: o.get(k, True) is not False for k in ("add_aac", "subs_to_srt", "avi_to_mkv")}
            c = self._cast_need(f, opts, torrents)
            if not c or c.get("pending"):
                return {"none": T("nothing to prepare", "nie ma czego przygotować")}
            if c.get("skip"):
                return {"none": c["skip"]}
            t = self._add_task({"kind": "cast_ready", **_title("Ready for Chromecast (after download)",
                                                                "Pod Chromecasta (po pobraniu)")},
                               [dict(base, op="cast_ready", **c)])
            return {"task": t["id"]}
        if kind != "compress":
            raise TaskError(f"unknown kind {kind}")
        mp_media.detect_hevc()
        enc = self.resolve_encoder(o.get("encoder"))
        po = {"quality": o.get("quality") or "balanced", "only_high_bitrate": False, "skip_seeding": False,
              "skip_hevc_av1": o.get("skip_hevc_av1", True)}
        c = self._pick("compress", f, full, po, {"torrents": torrents, "enc": enc, "check": None})
        if not c or c.get("ok") or c.get("pending"):
            return {"none": T("nothing to gain", "nic do zyskania")}
        if c.get("skip"):
            return {"none": c["skip"]}
        info = f["info"]
        est, work = c["cost"](enc)
        item = dict(base, op="shrink", est=est, work=work, codec=info["video"].get("codec"),
                    height=info["video"].get("height"), duration=info["duration"],
                    bitrate=int(f["size"] * 8 / info["duration"] / 1000), **c["item"])
        if o.get("original") == "delete":
            item["trash"] = False
        t = self._add_task({"kind": "compress", "encoder_id": enc,
                            **_title("Compression (after download)", "Kompresja (po pobraniu)")}, [item])
        return {"task": t["id"]}

    def task_item(self, tid):
        """The state of a one-item task: {"task", "item", "error", "saved", "est"}, None when it's gone."""
        with self.lock:
            t = next((t for t in self.items if t["id"] == tid), None)
            if not t:
                return None
            i = t["items"][0]
            return {"task": t["state"], "item": i.get("state"), "error": i.get("error"), "saved": i.get("saved", 0),
                    "est": i.get("est")}

    def one_move_task(self, top, to_root_id, to_sub="", torrent=None):
        """A one-item move of `top` (a film's folder or a single video, with the files next to it) into a library
        folder: by the torrent client when `torrent` is still in it (seeding goes on), else renamed or copied, and
        journalled for the phones like every move. → {"task": id} or {"none": reason}."""
        real = os.path.realpath(top)
        r = next((r for r in self.library.roots() if r["exists"] and real.startswith(os.path.realpath(r["path"]) + os.sep)),
                 None)  # any library folder, hidden ones too: the client's download folder may be one
        if not r:
            return {"none": T("not in a library folder", "poza folderami biblioteki")}
        rel = os.path.relpath(real, os.path.realpath(r["path"]))
        item = {"op": "move", "root_id": r["id"], "root": r["index"], "root_name": r["name"], "path": rel,
                "name": os.path.basename(rel), "size": max(tree_size(real)[0], 1), "to_root_id": to_root_id,
                "to_sub": to_sub, "extra": companions(real) if os.path.isfile(real) else []}
        if torrent:
            item["torrent"] = torrent
        t = self._add_task({"kind": "layout", **_title("Move (after download)", "Przenieś (po pobraniu)")}, [item])
        return {"task": t["id"]}
