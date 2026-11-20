"""Folder layout and balance between disks (mp_arrange computes, these plan and count) and ready for Chromecast (an AAC
track added in the file), agent 1.17+."""
import os
import shutil
import subprocess
import threading
import time

import mp_arrange
import mp_i18n
import mp_library
import mp_media
from mp_i18n import T
from mp_tasks_common import (
    MIN_SIZE, TaskError, WATCHING_DAYS, _Cancelled, _dev, _ffmpeg_reason, _in_torrent, _lang2, _mount, _nice,
    _readable_length, _rm, _seeding_text, _title, companions
)


TEXT_SUBS = {"subrip", "srt", "ass", "ssa", "mov_text", "webvtt", "text"}  # embedded subtitles that can become .srt
CAST_SPEED = 80 << 20  # bytes per second of a remux (For Chromecast), for the estimates


class LayoutMixin:

    # ---- folder layout (A2) and balance between disks (A3), agent 1.17+: mp_arrange computes, these plan and count

    def layout_settings(self):
        """The owner's folder layout: levels in the order tapped, age groups → kids/teens/films, and the folders they
        arranged by hand that stay as they are ([(root id, path)], None = never confirmed: all detected ones stay)."""
        keep = self.cfg.get("layout_keep")
        return {"levels": mp_arrange.norm_levels(self.cfg.get("layout_levels")) or list(mp_arrange.DEFAULT_LEVELS),
                "ages": mp_arrange.norm_ages(self.cfg.get("layout_ages")),
                "keep": None if keep is None else [(k["root_id"], k["path"]) for k in keep if k.get("root_id")]}

    def _arrange_ctx(self, m, torrents):
        """(units, roots with `writable`, roots by id, device of each root) for layout and balance."""
        roots = [dict(r, writable=os.access(r["path"], os.W_OK)) for r in self._job_roots()]
        by_id = {r["id"]: r for r in roots}
        devs = {r["id"]: _dev(r["path"]) for r in roots}
        us = mp_arrange.units(m, by_id, self.catalog, mp_i18n.lang())
        # files a waiting or running task still has to do (a paused encode among them) don't move under it
        busy = set()
        with self.lock:
            for t in self.items:
                if t["state"] in ("queued", "running"):
                    for i in t["items"]:
                        if i.get("state") in ("pending", "running") and i.get("root_id") in by_id and i.get("path"):
                            busy.add(os.path.realpath(os.path.join(by_id[i["root_id"]]["path"], i["path"])))
        if busy:
            for u in us:
                rp = os.path.realpath(u["full"])
                if any(b == rp or b.startswith(rp + os.sep) for b in busy):
                    u["busy"] = True
        return mp_arrange.group_torrents(us, by_id, torrents), roots, by_id, devs

    def _layout(self, m, torrents, levels, ages, keep=None, keep_unsure=True, keep_seeding=False):
        """(moves, skipped, stats, hand folders, keep confirmed) of a folder layout in the scope of `m`."""
        us, roots, by_id, devs = self._arrange_ctx(m, torrents)
        hand = mp_arrange.hand_folders(us, by_id)
        st = self.layout_settings()
        confirmed = keep is not None or st["keep"] is not None
        if keep is None:
            keep = st["keep"] if st["keep"] is not None else [(h["root_id"], h["path"]) for h in hand]
        moves, skipped, stats = mp_arrange.layout(us, levels, ages, keep, roots, mp_arrange.names(), keep_unsure,
                                                  keep_seeding, devs)
        kept = {tuple(k) for k in keep}
        for h in hand:
            h["keep"] = (h["root_id"], h["path"]) in kept
        return moves, skipped, stats, hand, confirmed

    @staticmethod
    def _unit_item(u, dr, sub, **extra):
        """A move item of a whole title (a folder or a file with the files that belong to it)."""
        item = {"op": "move", "root_id": u["root_id"], "root": u["root"], "root_name": u["root_name"], "path": u["rel"],
                "name": u.get("title") or os.path.basename(u["rel"]), "size": u["size"],
                "to_root_id": dr["id"], "to_root": dr["index"], "to_name": dr["name"], "to_sub": sub}
        if not u["dir"] and u.get("torrent") is None:
            item["extra"] = companions(u["full"])
        if u.get("torrent") is not None:
            item["torrent"] = u["torrent"]
        if u.get("members"):
            item["titles"] = len(u["members"])
        item.update(extra)
        return item

    def plan_layout(self, o, scope=None):
        """Folder layout (A2): `levels`, `ages` ({"0": "kids", …}), `keep` (the hand-arranged folders to leave alone,
        [{root, path}]; given = confirmed and remembered), `keep_unsure` (titles without a sure recognition stay where
        they are; false = in the preview, unticked), `seeding` ("move": a seeding torrent moves through its client,
        whole, "skip"), `remember` (levels and ages become the layout's settings). Every title stays on its disk."""
        levels = mp_arrange.norm_levels(o.get("levels")) if o.get("levels") is not None else self.layout_settings()["levels"]
        if not levels:
            raise TaskError(T("choose at least one level", "wybierz przynajmniej jeden poziom"))
        ages = mp_arrange.norm_ages(o.get("ages")) if o.get("ages") is not None else self.layout_settings()["ages"]
        keep = None
        if o.get("keep") is not None:
            idx = {r["index"]: r for r in self.library.roots()}
            keep = [(idx[int(k["root"])]["id"], str(k.get("path") or "").strip("/")) for k in o["keep"]
                    if str(k.get("root", "")).lstrip("-").isdigit() and int(k["root"]) in idx]
            self.cfg["layout_keep"] = [{"root_id": a, "path": b} for a, b in keep]
        if o.get("remember"):
            self.cfg["layout_levels"], self.cfg["layout_ages"] = levels, ages
        if keep is not None or o.get("remember"):
            self.save_cfg()
        keep_unsure = o.get("keep_unsure", True) is not False
        keep_seeding = (o.get("seeding") or "move") == "skip"
        m = self._matcher(scope)

        def run(p):
            moves, skipped, stats, hand, confirmed = self._layout(m, self._torrents(), levels, ages, keep, keep_unsure,
                                                                 keep_seeding)
            items = []
            for u, dr, sub in moves:
                items.append(self._unit_item(u, dr, sub, prune=True, same_disk=True, unsure=bool(u.get("unsure")),
                                             why="/".join(x for x in (dr["name"], sub) if x)))
            p["progress"] = 100
            return {"kind": "layout", **_title("Folder layout", "Układ folderów"), "items": items,
                    "bytes": sum(i["size"] for i in items), "levels": levels, "ages": ages, "confirmed": confirmed,
                    "hand_folders": hand, "stats": stats,
                    "skipped": [{"root_id": u["root_id"], "root": u["root"], "root_name": u["root_name"], "path": u["rel"],
                                 "name": u.get("title") or os.path.basename(u["rel"]), "size": u["size"],
                                 "reason": mp_arrange.reason(k)} for u, k in skipped if k != "kept"][:200],
                    "eta": len(items), "trash_days": self.trash_days()}
        return self._plan("layout", run, m)

    def _balance_disks(self, roots, devs):
        out = {}
        for r in roots:
            d = devs.get(r["id"])
            if d is None or d in out or not r.get("writable", True):
                continue
            try:
                u = shutil.disk_usage(r["path"])
            except OSError:
                continue
            out[d] = {"name": os.path.basename(_mount(r["path"]).rstrip("/")) or _mount(r["path"]), "total": u.total,
                      "free": u.free}
        return out

    def _watched_paths(self):
        """Real paths watched now or streamed in the last WATCHING_DAYS (balance leaves them where they are)."""
        cut = time.time() - WATCHING_DAYS * 86400
        with self.flock:
            out = {p for p, t in self.played.items() if t > cut}
        out |= {os.path.realpath(w["path"]) for w in self.watching() if w.get("path")}
        return out

    def _balance(self, goal, rules, torrents):
        us, roots, by_id, devs = self._arrange_ctx(self._matcher(None), torrents)
        disks = self._balance_disks(roots, devs)
        moves, skipped, after = mp_arrange.balance(goal, us, disks, roots, devs, rules, self._watched_paths())
        return moves, skipped, disks, after

    @staticmethod
    def _disk_rows(disks, after):
        return [{"name": d["name"], "total": d["total"], "free": d["free"], "free_after": after.get(k, d["free"]),
                 "pct": round(100 * d["free"] / max(d["total"], 1)), "pct_after": round(100 * after.get(k, d["free"]) / max(d["total"], 1))}
                for k, d in sorted(disks.items(), key=lambda kv: kv[1]["name"])]

    def plan_balance(self, o):
        """Balance between disks (A3), whole computer only: `goal` (even, kind_per_disk, fill_newer) and `rules`
        (skip_watching, seeding: a seeding torrent moves whole through its client, backup: never onto the disk of a
        folder's replica). Whole titles move into the folder with the same tag on the other disk, same subfolder."""
        goal = o.get("goal") or "even"
        if goal not in mp_arrange.GOALS:
            raise TaskError(T("goal must be even, kind_per_disk or fill_newer", "goal to even, kind_per_disk albo fill_newer"))
        rules = {k: bool(v) for k, v in (o.get("rules") or {}).items() if k in ("skip_watching", "seeding", "backup")}

        def run(p):
            moves, skipped, disks, after = self._balance(goal, rules, self._torrents())
            if len(disks) < 2:
                raise TaskError(T("the library is on one disk only", "biblioteka jest tylko na jednym dysku"))
            items = [self._unit_item(u, dr, sub, same_disk=False, why=dr["name"]) for u, dr, sub in moves]
            p["progress"] = 100
            total = sum(i["size"] for i in items)
            return {"kind": "balance", **_title("Balance between disks", "Rozkład na dyski"), "items": items,
                    "bytes": total, "goal": goal, "rules": rules, "disks": self._disk_rows(disks, after),
                    "eta": int(total / mp_arrange.COPY_SPEED),
                    "skipped": [{"root_id": u["root_id"], "root": u["root"], "root_name": u["root_name"], "path": u["rel"],
                                 "name": u.get("title") or os.path.basename(u["rel"]), "size": u["size"],
                                 "reason": mp_arrange.reason(k)} for u, k in skipped][:200]}
        return self._plan("balance", run)

    # ---- ready for Chromecast (B5), agent 1.17+: an AAC track next to the original sound, for good, in the file

    def _cast_need(self, f, o, torrents):
        """What cast_ready would do with one video: None (not for it: no fact yet, a picture Chromecast can't take, or
        nothing to do), {"pending": True} (its subtitle tracks weren't probed with codecs yet) or the item fields."""
        info = f.get("info") or {}
        v = info.get("video")
        if not v or not info.get("duration") or f["size"] < MIN_SIZE:
            return None
        if not ((v.get("codec") == "h264" and v.get("bits", 8) == 8 and (v.get("height") or 0) <= 1088)
                or (v.get("codec") == "hevc" and v.get("bits", 8) == 8)):
            return None  # the picture itself needs converting: "Prepare" does that per showing
        audio = info.get("audio") or []
        a = mp_media.pick_audio({"audio": audio}) if audio else None
        reasons, out = [], {}
        if o.get("add_aac", True) and a and a.get("codec") not in mp_media.AUDIO_OK \
                and not any(x.get("codec") in mp_media.AUDIO_OK and (x.get("lang") or "") == (a.get("lang") or "") for x in audio):
            ch = a.get("channels") or 0
            chs = {1: "mono", 2: "stereo", 6: "5.1", 7: "6.1", 8: "7.1"}.get(ch, f"{ch} ch" if ch else "")
            name = f"{(a.get('codec') or '?').upper()}{' ' + chs if chs else ''}"
            reasons.append(T(f"{name} sound", f"dźwięk {name}"))
            out.update(add_aac=True, aac_from=audio.index(a), aac_lang=a.get("lang") or "", audio_codec=a.get("codec"))
        subs = info.get("subs") or []
        if o.get("subs_to_srt", True) and subs:
            if any(not isinstance(s, dict) for s in subs):
                return {"pending": True}
            have = {x.get("lang") for x in f.get("side") or [] if x.get("lang")}
            want = []
            for n, s in enumerate(subs):
                code = _lang2(s.get("lang")) or ""
                if s.get("codec") in TEXT_SUBS and code and code not in have and code not in {w["lang"] for w in want}:
                    want.append({"n": n, "lang": code})
            if want:
                reasons.append(T("subtitles in the file", "napisy w pliku"))
                out["subs"] = want
        if o.get("avi_to_mkv", True) and "avi" in (info.get("container") or "").lower():
            reasons.append(T("AVI container", "kontener AVI"))
            out["avi"] = True
        if not reasons:
            return None
        seeding = _in_torrent(f.get("real") or "", torrents)
        if seeding:  # a seeding file stays byte for byte: only its subtitles can be written next to it
            if not out.get("subs"):
                return {"skip": _seeding_text()}
            out = {"subs": out["subs"], "seeding": True}
            reasons = [T("subtitles in the file", "napisy w pliku")]
        remux = bool(out.get("add_aac") or out.get("avi"))
        return dict(out, why=" · ".join(reasons), est=int(f["size"] * 1.03) if remux else f["size"],
                    work=(f["size"] / CAST_SPEED if remux else 0) + 5 * len(out.get("subs") or []))

    def plan_cast_ready(self, o, scope=None):
        """Ready for Chromecast (B5): `add_aac` (an AAC stereo track first, the original kept), `subs_to_srt` (text
        subtitle tracks written next to the file as .srt), `avi_to_mkv` (repacked, not re-encoded). The picture is
        never touched; the result is an .mkv and the original goes to the trash."""
        opts = {k: o.get(k, True) is not False for k in ("add_aac", "subs_to_srt", "avi_to_mkv")}
        if not any(opts.values()):
            raise TaskError(T("choose at least one action", "wybierz przynajmniej jedną czynność"))
        m = self._matcher(scope)

        def run(p):
            torrents = self._torrents()
            items, skipped, unchecked = [], [], 0
            for r, f, full in self._plan_facts(m, p, upgrade=True):
                c = self._cast_need(f, opts, torrents)
                if not c:
                    continue
                base = {"op": "cast_ready", "root_id": r["id"], "root": r["index"], "root_name": r["name"],
                        "path": f["rel"], "name": os.path.basename(f["rel"]), "size": f["size"]}
                if c.get("pending"):
                    unchecked += 1
                elif c.get("skip"):
                    skipped.append(dict(base, reason=c["skip"]))
                else:
                    it = m.title(r["id"], f["rel"]) or {}
                    items.append(dict(base, title=it.get("title"), series=it.get("kind") == "series", **c))
            p["progress"] = 100
            total = sum(i["size"] for i in items)
            return {"kind": "cast_ready", **_title("Ready for Chromecast", "Pod Chromecasta"), "items": items,
                    "bytes": total, "est": sum(i["est"] for i in items), "saved": total - sum(i["est"] for i in items),
                    "eta": int(sum(i["work"] for i in items)), "options": opts, "skipped": skipped[:100],
                    "by_title": self._cast_titles(items), "trash_days": self.trash_days(),
                    "warnings": [T(f"{unchecked} files weren't checked yet", f"{unchecked} plików jeszcze nie sprawdzono")]
                    if unchecked else []}
        return self._plan("cast_ready", run, m)

    @staticmethod
    def _cast_titles(entries):
        """Files grouped by title for the screen: a series once, as "Title, N episodes"."""
        out = {}
        for e in entries:
            key = (e.get("title") or e["name"], bool(e.get("series")))
            g = out.setdefault(key, {"title": key[0], "series": key[1], "files": 0, "why": e["why"], "size": 0})
            g["files"] += 1
            g["size"] += e["size"]
        return sorted(out.values(), key=lambda g: -g["size"])

    def _cast_ready(self, i, progress, t):
        root = self._root_path(i["root_id"])
        full = os.path.join(root, i["path"])
        if not os.path.exists(full):
            raise TaskError(T("the file no longer exists", "pliku już nie ma"))
        st = os.stat(full)
        if st.st_size != i["size"]:
            raise TaskError(T("the file changed since the preview", "plik zmienił się od podglądu"))
        stem = os.path.splitext(full)[0]
        info = mp_media.probe(full)
        wrote = []
        for s in i.get("subs") or []:  # subtitles next to the file first: they never replace a file that's there
            out = f"{stem}.{s['lang']}.srt"
            if os.path.exists(out):
                continue
            r = subprocess.run(_nice(["ffmpeg", "-hide_banner", "-nostdin", "-v", "error", "-n", "-i", full,
                                      "-map", f"0:s:{s['n']}", "-c:s", "srt", out]), capture_output=True, text=True)
            if r.returncode != 0 or not os.path.exists(out) or os.path.getsize(out) == 0:
                _rm(out)
                self.log(f"cast_ready: {os.path.basename(full)}: subtitles {s['lang']}: {r.stderr.strip()[-200:]}")
            else:
                wrote.append(os.path.basename(out))
            self._check_cancel()
        if wrote:
            i["subs_written"] = wrote
        if not (i.get("add_aac") or i.get("avi")):
            progress(i["size"])
            return
        if self._inside(full, self._torrents()):
            raise TaskError(T("the file is back in the torrent client", "plik jest z powrotem w kliencie torrentów"))
        need = int(st.st_size * 1.05) + (256 << 20)
        if shutil.disk_usage(os.path.dirname(full)).free < need:
            self._relieve(root, need - shutil.disk_usage(os.path.dirname(full)).free)
        if shutil.disk_usage(os.path.dirname(full)).free < need:
            raise TaskError(T(f"not enough room on the disk: about {need >> 20} MB needed",
                              f"za mało miejsca na dysku: potrzeba ok. {need >> 20} MB"))
        part = os.path.join(os.path.dirname(full), f".{os.path.basename(stem)}.mp-cast.part.mkv")
        out_final = stem + ".mkv"
        n_audio = len(info.get("audio") or [])
        cmd = ["ffmpeg", "-hide_banner", "-nostdin", "-y", "-v", "error", "-progress", "pipe:1"]
        if i.get("avi"):
            cmd += ["-fflags", "+genpts"]
        cmd += ["-i", full, "-map", "0:v:0"]
        if i.get("add_aac"):
            cmd += ["-map", f"0:a:{i['aac_from']}"]
        cmd += ["-map", "0:a?", "-map", "0:s?", "-map", "0:t?", "-map_chapters", "0", "-c", "copy"]
        if any(s.get("codec") == "mov_text" for s in info.get("subs") or []):
            cmd += ["-c:s", "srt"]
        if i.get("add_aac"):
            cmd += ["-c:a:0", "aac", "-b:a:0", "192k", "-ac:a:0", "2", "-metadata:s:a:0", "title=AAC stereo",
                    "-disposition:a", "0", "-disposition:a:0", "default"]
            if i.get("aac_lang"):
                cmd += ["-metadata:s:a:0", f"language={i['aac_lang']}"]
        cmd.append(part)
        _rm(part)
        self.log("cast_ready:", os.path.basename(full), "aac" if i.get("add_aac") else "", "avi→mkv" if i.get("avi") else "")
        p = subprocess.Popen(_nice(cmd), stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        with self.lock:
            self.proc = p
        dur = info.get("duration") or 0
        err = []
        threading.Thread(target=lambda: err.extend(p.stderr.read().splitlines()[-20:]), daemon=True).start()
        try:
            for line in p.stdout:
                if line.startswith("out_time_us=") and dur:
                    us = line.split("=", 1)[1].strip()
                    if us.isdigit():
                        progress(int(i["size"] * min(int(us) / 1e6 / dur, 1)))
                if self.cancel_ev.is_set():
                    p.terminate()
                    break
            p.wait()
        finally:
            with self.lock:
                self.proc = None
        if self.cancel_ev.is_set():
            _rm(part)
            raise _Cancelled()
        if p.returncode != 0:
            _rm(part)
            raise TaskError(_ffmpeg_reason(err))
        try:
            ninfo = mp_media.probe(part)
        except Exception as e:  # noqa: BLE001
            _rm(part)
            raise TaskError(T(f"the result can't be read: {e}", f"wyniku nie da się odczytać: {e}"))
        got = ninfo.get("duration") or 0
        if abs(got - dur) > max(2.0, dur * 0.005) and abs(got - _readable_length(full)) > max(2.0, got * 0.005):
            _rm(part)
            raise TaskError(T("the result has a different length, the original was kept", "wynik ma inną długość, oryginał został"))
        if len(ninfo.get("audio") or []) < n_audio + (1 if i.get("add_aac") else 0) \
                or (i.get("add_aac") and (ninfo["audio"][0].get("codec") != "aac")):
            _rm(part)
            raise TaskError(T("the result lacks a sound track, the original was kept", "w wyniku brakuje ścieżki dźwięku, oryginał został"))
        real = os.path.realpath(full)
        while any(os.path.realpath(w["path"]) == real for w in self.watching()):
            t["hold"] = "in_use"
            if self.cancel_ev.wait(5):
                t["hold"] = None
                _rm(part)
                raise _Cancelled()
        t["hold"] = None
        new = os.path.getsize(part)
        if self.trash_days() > 0:
            self._to_trash(full, root, i)
        else:
            os.remove(full)
        os.rename(part, out_final)
        mp_media.remove_variant(full)
        i.update(new_size=new, new_path=os.path.relpath(out_final, root), grew=new - st.st_size)
        mp_library.forget_names()
        self.log(f"cast_ready done: {os.path.basename(full)} {st.st_size >> 20} → {new >> 20} MB")
