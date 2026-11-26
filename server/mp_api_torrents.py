"""Routes: the torrent client (list, add, move, files, actions), the agent's own download list and recognising a
magnet's folder. Each function is a route of mp_routes.ROUTES: (req = the Handler, a = the App, method, p = path, q
= query lists, qd = query values, path parameters)."""
import os
import time

import mp_archive
import mp_media
from mp_core import ApiError, log
from mp_i18n import T
from mp_jobs import AFTER, magnet_name
from mp_torrents import media_duration


def torrents(req, a, **_):
    jobs = {j["id"]: j for j in a.jobs.all()}
    out = a.backend.list()
    for t in out:
        j = jobs.get(t["id"])
        t["mine"] = j is not None
        t["after"] = j.get("after", "none") if j else "none"
    return req.send_json(out)


def torrent_jobs(req, a, **_):
    present = {t["id"]: t for t in a.backend.list()}
    out = []
    for j in a.jobs.all():
        t = present.get(j["id"])
        out.append({"id": j["id"], "name": j.get("name") or (t or {}).get("name") or j["id"][:12],
                    "added": j.get("added"), "after": j.get("after", "none"),
                    "after_done": j.get("after_done", False), "magnet": j.get("magnet"),
                    "torrent_url": j.get("torrent_url"),  # agent 1.19+: added from the Internet Archive search
                    "in_client": t is not None, "progress": t["progress"] if t else None,
                    "state": t["state"] if t else None})
    return req.send_json(out)


def torrent_after(req, a, tid, **_):
    after = req.body().get("after")
    if after not in AFTER:
        raise ApiError("after: " + "/".join(AFTER), 400)
    a.jobs.set_after(tid.lower(), after)
    return req.send_json({"ok": True})


def torrent_add(req, a, **_):
    b = req.body()
    if b.get("torrent_url"):
        return req.send_json(add_archive(a, b))
    uri = (b.get("magnet") or "").strip()
    if not uri.startswith("magnet:?"):
        raise ApiError(T("this isn't a magnet link", "to nie jest magnet link"), 400)
    dest = a.library.dest(int(b["root"]), str(b.get("sub") or "")) if b.get("root") is not None else None
    tid = a.backend.add_magnet(uri, b.get("sequential", True), dest)
    if tid:
        a.jobs.add(tid.lower(), uri, b.get("after", "none"))
        a.subflow.asked_torrent(tid, b.get("sub_langs"))  # the downloader's subtitle languages (agent 1.21.3+)
        if dest:
            a.jobs.set_meta(tid.lower(), dest=dest, guess=b.get("guess"))
        elif not magnet_name(uri) and a.library.roots():
            # no name in the link: recognized and placed once the client has the metadata (place_unnamed)
            a.jobs.set_meta(tid.lower(), place_when_named=time.time())
    log("magnet added:", tid, "→", dest or "(client default folder)")
    return req.send_json({"id": tid, "path": dest})


def add_archive(a, b):
    """An Internet Archive item (agent 1.19+): its .torrent (web seeds are listed only there, a magnet never starts),
    only the best video file wanted, renamed to "Title (Year)/Title (Year).ext" from the title and year the user saw.
    Folder: `root`/`sub` from the app (its own recognition, as for a magnet), else recognised here from that name and
    used when sure (the web page). The guess is kept with the job, so "Change" can teach the folder memory."""
    url = str(b.get("torrent_url") or "").strip()
    ident = mp_archive.check_url(url)
    name = mp_archive.clean_name(b.get("title") or "", b.get("year")) or ident
    data = mp_archive.fetch_torrent(url)
    meta = mp_archive.parse(data)
    pick = mp_archive.pick_video(meta["files"], mp_archive.file_formats(ident))
    if pick is None:
        raise ApiError(T("this item has no video file to download", "ta pozycja nie ma pliku wideo do pobrania"), 422)
    chosen = meta["files"][pick]
    new_path = mp_archive.placed_name(name, chosen["path"])
    guess, dest = b.get("guess"), None
    if b.get("root") is not None:
        dest = a.library.dest(int(b["root"]), str(b.get("sub") or ""))
    elif a.library.roots():
        guess = a.ident.guess(name, [(new_path, chosen["size"])])
        kind = guess.get("kind") if guess.get("tag") in ("seriale", "bajki") else None
        tg = a.library.targets(guess.get("tag"), guess.get("title"), kind)
        if tg["pick"] is not None and guess.get("confidence") == "high":
            t = tg["targets"][tg["pick"]]
            dest = a.library.dest(t["root"], t["sub"])
    tid = a.backend.add_torrent(data, ident + ".torrent", b.get("sequential", True), dest,
                                mp_archive.priorities(meta["files"], pick), [(pick, chosen["path"], new_path)],
                                name, meta["hash"])
    tid = (tid or meta["hash"]).lower()
    a.jobs.add(tid, None, b.get("after", "none"), name=name, torrent_url=url)
    a.subflow.asked_torrent(tid, b.get("sub_langs"))
    a.jobs.set_meta(tid, **{k: v for k, v in (("dest", dest), ("guess", guess)) if v})
    log("archive.org item added:", tid, ident, "→", dest or "(client default folder)", "file:", chosen["path"])
    return {"id": tid, "path": dest, "name": name, "file": new_path, "size": chosen["size"]}


def archive_search(req, a, qd, **_):
    """GET /api/archive/search?q=Title+Year[&any=1][&rows=30] (agent 1.19+)."""
    return req.send_json(mp_archive.search(qd.get("q", ""), qd.get("rows") or 30, qd.get("any") in ("1", "true")))


# changing the folder after identification: the client moves it (keeps seeding)
def torrent_move(req, a, tid, **_):
    b, tid = req.body(), tid.lower()
    dest = a.library.dest(int(b["root"]), str(b.get("sub") or ""))
    a.backend.move(tid, dest)
    j = a.jobs.get(tid) or {}
    if b.get("learn") and j.get("guess"):  # next time this title goes straight here
        tags = a.library.roots()[int(b["root"])]["tags"]
        if tags:
            a.ident.learn(j["guess"], tags[0])
    a.jobs.set_meta(tid, dest=dest)
    log("torrent moved:", tid, "→", dest)
    return req.send_json({"ok": True, "path": dest})


def guess(req, a, **_):
    b = req.body()
    name, files = b.get("name"), None
    if b.get("magnet"):
        name = magnet_name(b["magnet"]) or name
    if b.get("torrent"):  # already in the client (e.g. a magnet without a name, once the metadata is there)
        t = next((t for t in a.backend.list() if t["id"] == b["torrent"]), None)
        if t:
            name = t["name"]
            files = [(f["path"], f["size"]) for f in a.backend.files(b["torrent"])[1]] or None
    g = a.ident.guess(name, files) if name else {"tag": None, "confidence": "low", "why": []}
    kind = g.get("kind") if g.get("tag") in ("seriale", "bajki") else None
    return req.send_json(dict(a.library.targets(g.get("tag"), g.get("title"), kind), guess=g, name=name))


def torrent_files(req, a, tid, **_):
    save_path, files = a.backend.files(tid)
    for f in files:
        if f["media"] == "video" and f["ready"] > 0:
            dur = media_duration(os.path.join(save_path, f["path"]))
            if dur:
                f["duration"] = dur
                f["ready_sec"] = dur * f["ready"] / f["size"]
    return req.send_json([{k: v for k, v in f.items() if not k.startswith("_")} for f in files])


def torrent_action(req, a, tid, **_):
    act = req.body().get("action")
    if act == "remove_data":  # the "for Chromecast" versions live in the torrent's folder: delete them along with it
        try:
            save_path, files = a.backend.files(tid)
            for f in files:
                mp_media.remove_variant(os.path.join(save_path, f["path"]))
        except ApiError:
            pass
    a.backend.action(tid, act)
    return req.send_json({"ok": True})
