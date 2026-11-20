"""Routes: the torrent client (list, add, move, files, actions), the agent's own download list and recognising a
magnet's folder. Each function is a route of mp_routes.ROUTES: (req = the Handler, a = the App, method, p = path, q
= query lists, qd = query values, path parameters)."""
import os
import time

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
    uri = (b.get("magnet") or "").strip()
    if not uri.startswith("magnet:?"):
        raise ApiError(T("this isn't a magnet link", "to nie jest magnet link"), 400)
    dest = a.library.dest(int(b["root"]), str(b.get("sub") or "")) if b.get("root") is not None else None
    tid = a.backend.add_magnet(uri, b.get("sequential", True), dest)
    if tid:
        a.jobs.add(tid.lower(), uri, b.get("after", "none"))
        if dest:
            a.jobs.set_meta(tid.lower(), dest=dest, guess=b.get("guess"))
        elif not magnet_name(uri) and a.library.roots():
            # no name in the link: recognized and placed once the client has the metadata (place_unnamed)
            a.jobs.set_meta(tid.lower(), place_when_named=time.time())
    log("magnet added:", tid, "→", dest or "(client default folder)")
    return req.send_json({"id": tid, "path": dest})


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
