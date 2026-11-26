"""Internet Archive torrents that never finish (agent 1.21.1+).

An archive.org item's torrent lists every file of the item, and we want only the video. The pieces where the video
touches a neighbouring file (thumbnails, automatic subtitles, derived .ogv/.mp3 copies) also hold bytes of that file,
and archive.org regenerates such derived files: the piece no longer matches the torrent's hash, so it never completes
and the torrent stays at 99.9% for good. The video's own bytes in those pieces are not in question; they just can't be
proved by the torrent. The agent serves only proved pieces (see `downloading_part`), so a film whose index (MP4
`moov`) sits in such a piece doesn't open at all, and nothing that waits for a finished download (automatic
subtitles, "after download" actions) ever runs.

Here: once a wanted file of an archive.org torrent lacks only its first and/or last piece for 3 minutes, those
byte ranges are fetched over HTTPS from the item itself (Range requests to archive.org/download/<item>/<file>),
written into the file, and the whole file is checked against the MD5 archive.org lists for it. Only a matching file is
marked complete: `Wrap` then reports it as fully downloaded to the rest of the agent (`files()`: ready = size, no
piece list; `list()`: progress 100 once every wanted file is complete), and the torrent is paused (it would keep
fetching and rejecting the same piece). State: `iafill.json` in the config directory."""
import hashlib
import json
import os
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

import mp_archive

STALL = 180  # seconds a file must sit with only its edge pieces missing before they are fetched over HTTP
TRIES = 3


def _edge_ranges(f):
    """Byte ranges [a, b) of the file that lie in missing pieces, when only its first and/or last piece is missing;
    None when anything else is missing (still downloading) or nothing is."""
    offset, plen, have = f.get("_pieces") or (0, 0, None)
    if have is None or not plen or f["size"] <= 0:
        return None
    first, last = offset // plen, (offset + f["size"] - 1) // plen
    miss = [k for k in range(first, last + 1) if k >= len(have) or not have[k]]
    if not miss or any(k not in (first, last) for k in miss):
        return None
    return [(max(0, k * plen - offset), min(f["size"], (k + 1) * plen - offset)) for k in miss]


def _wanted(raw_backend, tid, files):
    """Indexes of the files the user wants from this torrent (the neighbours are partly downloaded too, inside the
    shared edge pieces). Deluge: file priorities; other clients: the files that are nearly or fully there."""
    try:
        prio = raw_backend.call("core.get_torrent_status", tid, ["file_priorities"])["file_priorities"]
        return {i for i, p in enumerate(prio) if p}
    except Exception:  # noqa: BLE001 — not Deluge, or it didn't answer
        return {f["index"] for f in files if f["progress"] >= 99}


def _opener():
    return urllib.request.build_opener(mp_archive._ArchiveOnly)


def _md5s(ident, timeout=20):
    with urllib.request.urlopen(urllib.request.Request(f"https://archive.org/metadata/{ident}/files",
                                                       headers=mp_archive.UA), timeout=timeout) as r:
        res = json.loads(r.read(8 * 1024 * 1024)).get("result") or []
    return {str(x.get("name")): str(x.get("md5") or "") for x in res if isinstance(x, dict)}


def _md5_file(path):
    h = hashlib.md5()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(8 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


class IAFill:
    def __init__(self, app, config_dir, log):
        self.app, self.log = app, log
        self.path = os.path.join(config_dir, "iafill.json")
        self.lock = threading.Lock()
        self.busy = False
        self.seen = {}  # (tid, index) → when it was first seen with only edge pieces missing
        try:
            with open(self.path) as f:
                self.st = json.load(f)
        except (OSError, ValueError):
            self.st = {}

    def _save(self):
        tmp = self.path + ".tmp"
        with open(tmp, "w") as f:
            json.dump(self.st, f, indent=1)
        os.replace(tmp, self.path)

    # -- what the rest of the agent sees

    def done(self, tid):
        """{file index: True} of files completed over HTTP for this torrent."""
        rec = self.st.get(tid)
        return {int(k) for k, v in (rec or {}).get("files", {}).items() if v.get("ok")}

    def complete(self, tid):
        rec = self.st.get(tid)
        return bool(rec and rec.get("complete"))

    # -- background

    def tick(self, raw_backend):
        """Every ~20 s from the SleepGuard loop: find archive.org torrents stuck on their edge pieces."""
        if self.busy:
            return
        jobs = {j["id"]: j for j in self.app.jobs.all() if j.get("torrent_url")}
        if not jobs:
            return
        todo = []
        now = time.time()
        for t in raw_backend.list():
            tid = t["id"]
            if tid not in jobs or t["progress"] >= 100 or self.complete(tid):
                continue
            rec = self.st.get(tid, {})
            try:
                save_path, files = raw_backend.files(tid)
            except Exception:  # noqa: BLE001 — the client may be restarting
                continue
            wanted = _wanted(raw_backend, tid, files)
            for f in files:
                key = (tid, f["index"])
                fr = rec.get("files", {}).get(str(f["index"]), {})
                if fr.get("ok") or fr.get("tries", 0) >= TRIES or f["index"] not in wanted:
                    continue
                ranges = _edge_ranges(f)
                if not ranges:
                    self.seen.pop(key, None)
                    continue
                since = self.seen.setdefault(key, now)
                if now - since >= STALL:
                    todo.append((tid, jobs[tid]["torrent_url"], save_path, f, ranges))
        if todo:
            self.busy = True
            threading.Thread(target=self._run, args=(raw_backend, todo), daemon=True).start()

    def _run(self, raw_backend, todo):
        try:
            for tid, url, save_path, f, ranges in todo:
                self._fill(raw_backend, tid, url, save_path, f, ranges)
        except Exception as e:  # noqa: BLE001
            self.log("archive.org fill:", e)
        finally:
            self.busy = False

    def _fill(self, raw_backend, tid, url, save_path, f, ranges):
        ident = mp_archive.check_url(url)
        meta = mp_archive.parse(mp_archive.fetch_torrent(url))
        orig = next((x for x in meta["files"] if x["index"] == f["index"]), None)
        rec = self.st.setdefault(tid, {"ident": ident, "files": {}})
        fr = rec["files"].setdefault(str(f["index"]), {"tries": 0})
        fr["tries"] += 1
        fr["at"] = int(time.time())
        full = os.path.realpath(os.path.join(save_path, f["path"]))
        try:
            if not orig or orig["size"] != f["size"]:
                raise ValueError("the file isn't in the item's torrent any more")
            name = orig["path"].split("/", 1)[-1]  # the torrent's top folder is the item
            want = _md5s(ident).get(name)
            if not want:
                raise ValueError("archive.org lists no MD5 for this file")
            src = f"https://archive.org/download/{ident}/{urllib.parse.quote(name)}"
            got = 0
            with open(full, "r+b") as out:
                for a, b in ranges:
                    req = urllib.request.Request(src, headers={**mp_archive.UA, "Range": f"bytes={a}-{b - 1}"})
                    with _opener().open(req, timeout=60) as r:
                        data = r.read(b - a + 1)
                        if r.status != 206 or len(data) != b - a:
                            raise ValueError(f"archive.org sent {len(data)} bytes (HTTP {r.status}) for {b - a}")
                    out.seek(a)
                    out.write(data)
                    got += len(data)
            md5 = _md5_file(full)
            if md5 != want:
                raise ValueError(f"MD5 differs after the fill ({md5} ≠ {want})")
            fr.update(ok=True, bytes=got, error=None)
            self.log(f"archive.org fill: {f['path']}: {got} bytes of its edge pieces fetched over HTTP, MD5 matches")
        except (OSError, ValueError, urllib.error.URLError) as e:
            fr["error"] = str(e)[:300]
            self.log(f"archive.org fill: {f['path']}: try {fr['tries']} of {TRIES} failed: {e}")
        with self.lock:
            try:
                _, files = raw_backend.files(tid)
                wanted = _wanted(raw_backend, tid, files)
                left = [x for x in files if x["index"] in wanted and x["progress"] < 100
                        and not rec["files"].get(str(x["index"]), {}).get("ok")]
                if not left and any(v.get("ok") for v in rec["files"].values()):
                    rec["complete"] = True
                    try:
                        raw_backend.action(tid, "pause")  # it would keep fetching and rejecting the same pieces
                    except Exception as e:  # noqa: BLE001
                        self.log("archive.org fill: pause:", e)
                    self.log(f"archive.org fill: torrent {tid} complete (paused)")
            except Exception as e:  # noqa: BLE001
                self.log("archive.org fill: files:", e)
            self._save()


class Wrap:
    """The torrent client as the rest of the agent sees it: files completed over HTTP count as downloaded."""

    def __init__(self, backend, fill):
        self._b, self._fill = backend, fill

    def __getattr__(self, name):
        return getattr(self._b, name)

    @property
    def raw(self):
        return self._b

    def list(self):
        out = self._b.list()
        for t in out:
            if t["progress"] < 100 and self._fill.complete(t["id"]):
                t["progress"] = 100.0
                t["eta"] = 0
        return out

    def files(self, tid):
        save_path, files = self._b.files(tid)
        done = self._fill.done(tid)
        if done:
            for f in files:
                if f["index"] in done:
                    f["progress"], f["ready"] = 100.0, f["size"]
                    if f.get("_pieces"):
                        f["_pieces"] = (f["_pieces"][0], f["_pieces"][1], None)
        return save_path, files
