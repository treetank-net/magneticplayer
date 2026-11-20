"""The background scan behind the counters (`job-facts.json`), duplicates by content, played videos, and the background
check of damaged sound."""
import json
import os
import time

import mp_library
import mp_media
from mp_library import HIDDEN_TAGS, VIDEO_EXT
from mp_tasks_common import AUDIO_SUSPECT, MIN_SIZE, WATCHING_DAYS, _audio_errors, _lang2, is_old


FACTS_RESCAN = 1800  # seconds between the background scans behind the counters (sooner after a library change)
FACTS_V = 2  # the scan's fact format: 2 (1.17) keeps the codec of embedded subtitles (For Chromecast); older facts are probed again


def _dir_sig(d):
    """A folder's mtime and those of its subtitle subfolders: the subtitles of its videos are listed again when it
    changes."""
    try:
        sig = [int(os.stat(d).st_mtime)]
        for n in sorted(os.listdir(d)):
            if n.lower() in ("subs", "sub", "subtitles"):
                try:
                    sig.append(int(os.stat(os.path.join(d, n)).st_mtime))
                except OSError:
                    pass
    except OSError:
        return None
    return sig


def _side(full):
    """Subtitle files of a video: [{"name" (relative to its folder), "lang" (2 letters, from the name or the text,
    "" unknown), "fit" (SRT or text SUB: can be fitted)}]."""
    import mp_subsync
    out = []
    for x in mp_library.sidecar_subs(full):
        sp = os.path.join(os.path.dirname(full), x["name"])
        code = _lang2(x.get("lang"))
        if len(code) != 2:
            code = mp_library.text_lang(sp)
        ext = os.path.splitext(sp)[1].lower()
        try:
            fit = ext == ".srt" or (ext == ".sub" and mp_subsync.is_text_sub(sp))
        except OSError:
            fit = False
        out.append({"name": x["name"], "lang": code or "", "fit": fit})
    return out


def _quick_hash(fp, sz):
    import hashlib
    h = hashlib.sha1()
    with open(fp, "rb") as f:
        h.update(f.read(4 << 20))
        f.seek(max(sz - (4 << 20), 0))
        h.update(f.read(4 << 20))
    return h.hexdigest()


def _mid_hash(fp, sz):
    import hashlib
    h = hashlib.sha1()
    with open(fp, "rb") as f:
        for frac in (1 / 3, 2 / 3):
            f.seek(int(sz * frac))
            h.update(f.read(4 << 20))
    return h.hexdigest()


class FactsMixin:


    # ---- jobs v2 (agent 1.16+, docs/plan-ux-tasks.md F1/F2): scopes, counters, one plan kind per task

    def _load_facts(self):
        try:
            with open(self.facts_path) as f:
                d = json.load(f)
        except (OSError, ValueError):
            d = {}
        self.facts = d.get("files") or {}  # "<root id>/<rel>" → what the background scan knows about the video
        self.facts_list = d.get("list")  # [[root id, rel, size]] of the last walk (also files not probed yet)
        self.facts_at = d.get("at")  # when the last scan finished
        self.dupe_groups = d.get("dupes") or []  # [[key, …]] identical files (size + sampled hashes)

    def _save_facts(self):
        with self.flock:
            data = json.dumps({"files": self.facts, "list": self.facts_list, "at": self.facts_at,
                               "dupes": self.dupe_groups}, ensure_ascii=False)
        tmp = self.facts_path + ".tmp"
        with open(tmp, "w") as f:
            f.write(data)
        os.replace(tmp, self.facts_path)

    def _facts_loop(self):
        """The background scan behind the counters: every half hour (sooner after a library change) the library is
        walked; new or changed videos are probed (ffprobe, once per file), their subtitles listed and identical files
        found. New files then get what the owner asked for (`audio_fix_auto`, `subs_auto`)."""
        time.sleep(float(os.environ.get("MP_FACTS_SCAN_DELAY", 60)))
        while True:
            self.facts_wake.clear()
            try:
                self.scan_facts()
            except Exception as e:  # noqa: BLE001 — a disk that went away mid-walk must not stop the thread
                self.log("jobs scan failed:", e)
            self.facts_wake.wait(FACTS_RESCAN)

    def _job_roots(self):
        return [r for r in self.library.roots() if r["exists"] and not set(r["tags"]) & HIDDEN_TAGS]

    def _make_fact(self, r, rel, full, st, real, low=False):
        try:
            info = mp_media.probe(full, low=low)
            info = {"container": info.get("container"), "duration": info.get("duration"), "video": info.get("video"),
                    "audio": [{k: a.get(k) for k in ("index", "codec", "channels", "lang", "default")}
                              for a in info.get("audio") or []],
                    "subs": [{"lang": s.get("lang"), "codec": s.get("codec")} for s in info.get("subs") or []]}
            err = None
        except Exception as e:  # noqa: BLE001 — an unreadable or half-written file
            info, err = None, str(e)[:200]
        return {"root_id": r["id"], "rel": rel, "size": st.st_size, "mtime": int(st.st_mtime), "real": real,
                "ino": [st.st_dev, st.st_ino], "info": info, "err": err, "seen": time.time(), "v": FACTS_V}

    def _scan_wait(self, dev):
        """The background scan gives way: while somebody watches from the same disk it waits (a USB disk spinning
        through thousands of ffprobes next to a stream stuttered it), and it never probes faster than ~5 files/s."""
        for _ in range(720):  # at most an hour per file, then it goes on slowly anyway
            if self._hold(dev) not in ("same_disk", "watching"):
                break
            time.sleep(5)
        time.sleep(0.2)

    def _fresh_fact(self, r, rel, full, dsigs, store=True, scan=False, upgrade=False):
        """The fact of one video, from the scan when the file is unchanged, else probed now; subtitles listed again
        when its folder changed. None when the file is gone. A fact of an older format is probed again by the
        background scan, or now with `upgrade` (a plan that needs the new fields)."""
        try:
            st = os.stat(full)
        except OSError:
            return None
        key = f"{r['id']}/{rel}"
        with self.flock:
            f = self.facts.get(key)
        old = f is not None and f.get("v", 1) < FACTS_V and (scan or upgrade)
        if not f or f["size"] != st.st_size or f["mtime"] != int(st.st_mtime) or old:
            if scan:
                self._scan_wait(st.st_dev)
            f = self._make_fact(r, rel, full, st, os.path.realpath(full), low=scan)
            if store:
                with self.flock:
                    self.facts[key] = f
        d = os.path.dirname(full)
        if d not in dsigs:
            dsigs[d] = _dir_sig(d)
        if f.get("dsig") != dsigs[d]:
            f["side"], f["dsig"] = _side(full), dsigs[d]
        return f

    def scan_facts(self):
        roots = self._job_roots()
        try:  # a torrent still downloading: its files are looked at once they are complete
            unfinished = {os.path.realpath(os.path.join(t["save_path"], t["name"]))
                          for t in self.backend().list() if t.get("progress", 100) < 100}
        except Exception:  # noqa: BLE001
            unfinished = set()
        first = self.facts_at is None
        listing, new, dsigs, n = [], [], {}, 0
        for r in roots:
            for rel, is_dir, size in self.library._names(r):
                if is_dir or os.path.splitext(rel)[1].lower() not in VIDEO_EXT:
                    continue
                listing.append([r["id"], rel, size])
                full = os.path.join(r["path"], rel)
                real = os.path.realpath(full)
                if real in unfinished or any(real.startswith(u + os.sep) for u in unfinished):
                    continue
                key = f"{r['id']}/{rel}"
                with self.flock:
                    known = key in self.facts
                f = self._fresh_fact(r, rel, full, dsigs, scan=True)
                if f is None:
                    continue
                if not known:
                    n += 1
                    if not first:
                        new.append(key)
                    if n % 200 == 0:
                        self._save_facts()
        keys = {f"{a}/{b}" for a, b, _ in listing}
        with self.flock:
            for k in [k for k in self.facts if k not in keys]:
                del self.facts[k]
            self.facts_list = listing
            facts = dict(self.facts)
        rpath = {r["id"]: r["path"] for r in roots}
        groups = self._find_dupes([(k, os.path.join(rpath[f["root_id"]], f["rel"]), f) for k, f in facts.items()
                                   if f["size"] >= MIN_SIZE and f["root_id"] in rpath])
        with self.flock:
            self.dupe_groups = groups
            self.facts_at = time.time()
        self._save_facts()
        if n:
            self.log(f"jobs scan: {n} new videos looked at, {len(listing)} in the library")
        if new:
            self._auto_new(new)

    def _find_dupes(self, files, p=None):
        """Groups of identical files among [(key, full, fact)]: the same size, then the same first and last 4 MB, then
        two more samples from the middle (as plan_dupes). Hashes are kept in the facts while the file is unchanged."""
        by_size = {}
        for k, full, f in files:
            by_size.setdefault(f["size"], []).append((k, full, f))
        cands = []
        for g in by_size.values():
            seen, uniq = set(), []
            for x in g:  # hard links and symlinks to the same data are not copies
                ino = tuple(x[2].get("ino") or ()) or (x[1],)
                if ino not in seen:
                    seen.add(ino)
                    uniq.append(x)
            if len(uniq) > 1:
                cands.append(uniq)
        out = []
        for n, g in enumerate(cands):
            if p is not None:
                p["progress"] = 30 + int(70 * n / max(len(cands), 1))
                p["step"] = os.path.basename(g[0][1])
            for which in ("hq", "hf"):
                buckets = {}
                for k, full, f in g:
                    if not f.get(which):
                        try:
                            f[which] = _quick_hash(full, f["size"]) if which == "hq" else _mid_hash(full, f["size"])
                        except OSError:
                            continue
                    buckets.setdefault(f[which], []).append((k, full, f))
                g = max(buckets.values(), key=len) if buckets else []
                if len(g) < 2:
                    break
            if len(g) >= 2:
                out.append([k for k, _, _ in g])
        return out

    # ---- played videos (streamed from this server): "watched" for free up, "being watched" for subtitles

    def note_played(self, path):
        now = time.time()
        with self.flock:
            self.played[os.path.realpath(path)] = now
            if now - self._played_saved < 300:
                return
            self._played_saved = now
            cut = now - 400 * 86400
            self.played = {k: v for k, v in self.played.items() if v > cut}
            data = json.dumps(self.played, ensure_ascii=False)
        tmp = self.played_path + ".tmp"
        try:
            with open(tmp, "w") as f:
                f.write(data)
            os.replace(tmp, self.played_path)
        except OSError:
            pass

    def _last_played(self, real):
        """When a file, or anything under a folder, was last streamed (epoch s), or None."""
        with self.flock:
            items = list(self.played.items())
        best = None
        for p, t in items:
            if (p == real or p.startswith(real + os.sep)) and (best is None or t > best):
                best = t
        return best

    def _watched_dirs(self):
        cut = time.time() - WATCHING_DAYS * 86400
        with self.flock:
            return {os.path.dirname(p) for p, t in self.played.items() if t > cut}

    # ---- damaged sound

    def audio_check(self, full, info, deadline=None):
        """Indexes of `full`'s audio tracks (AUDIO_SUSPECT codecs) that don't decode cleanly, from the cache or a decode
        now; None when it isn't cached and `deadline` has passed."""
        st = os.stat(full)
        key = os.path.realpath(full)
        with self.alock:
            c = self.achecks.get(key)
        if c and c[0] == st.st_size and c[1] == int(st.st_mtime):
            return c[2]
        if deadline is not None and time.time() > deadline:
            return None
        errs = {}  # track index → damaged frames (1.16+: the counters show them)
        for a in info.get("audio") or []:
            if a.get("codec") in AUDIO_SUSPECT and (n := _audio_errors(full, a)):
                errs[str(a["index"])] = n
        bad = sorted(int(k) for k in errs)
        with self.alock:
            self.achecks[key] = [st.st_size, int(st.st_mtime), bad, errs]
            tmp = self.achecks_path + ".tmp"
            with open(tmp, "w") as f:
                json.dump(self.achecks, f, ensure_ascii=False)
            os.replace(tmp, self.achecks_path)
        if bad:
            self.log(f"audio check: {os.path.basename(full)}: track(s) {bad} don't decode cleanly")
        return bad

    def _audio_scan(self):
        """Once a day: decode the sound of library videos with AUDIO_SUSPECT tracks not checked yet (a modern picture
        only: old ones are re-encoded whole and checked then). MP3/MP2 first and small files first (old rips, where the
        damage was found), big AC3 films last. Waits while somebody watches or a task runs."""
        time.sleep(float(os.environ.get("MP_AUDIO_SCAN_DELAY", 300)))
        while True:
            todo = []
            for r in self.library.roots():
                if not r["exists"] or set(r["tags"]) & HIDDEN_TAGS:
                    continue
                for dp, dirs, fs in os.walk(r["path"]):
                    dirs[:] = sorted(d for d in dirs if not d.startswith("."))
                    for f in fs:
                        full = os.path.join(dp, f)
                        if os.path.splitext(f)[1].lower() not in VIDEO_EXT or f.startswith("."):
                            continue
                        try:
                            size = os.stat(full).st_size
                            if size < MIN_SIZE:
                                continue
                            info = mp_media.probe(full)
                        except Exception:  # noqa: BLE001
                            continue
                        codecs = {a.get("codec") for a in info.get("audio") or []}
                        if is_old(info.get("video") or {}) or not codecs & AUDIO_SUSPECT:
                            continue
                        todo.append((not codecs & {"mp3", "mp2", "mp1"}, size, full, info))
            n = 0
            for _, _, full, info in sorted(todo, key=lambda x: x[:2]):
                st = os.stat(full) if os.path.exists(full) else None
                c = self.achecks.get(os.path.realpath(full))
                if st is None or (c and c[0] == st.st_size and c[1] == int(st.st_mtime)):
                    continue
                for _ in range(720):  # an hour at most, then the next file anyway
                    with self.lock:
                        running = any(t["state"] == "running" for t in self.items)
                    if not running and not self.streaming():
                        break
                    time.sleep(5)
                try:
                    self.audio_check(full, info)
                    n += 1
                except Exception as e:  # noqa: BLE001
                    self.log("audio check:", os.path.basename(full), e)
            if n:
                self.log(f"audio check: {n} files decoded")
            time.sleep(24 * 3600)
