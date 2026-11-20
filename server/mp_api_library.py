"""Routes: library folders, browsing, search, covers, episodes and segments, streaming a library file, uploads from
phones. Each function is a route of mp_routes.ROUTES: (req = the Handler, a = the App, method, p = path, q = query
lists, qd = query values, path parameters)."""
import os
import urllib.parse

import mp_ident
import mp_media
import mp_upload
from mp_core import ApiError
from mp_i18n import T
from mp_library import media_kind


def upload(req, a, method, p, qd, **_):
    return mp_upload.handle(req, method, p[len("/api/upload/"):], qd)


def library(req, a, qd, **_):
    return req.send_json(a.library.visible_roots(qd.get("manage") == "1"))


def library_edit(req, a, **_):
    b = req.body()
    if b.get("op") == "remove":
        a.library.remove_root(int(b["index"]))
    elif b.get("op") == "update":
        a.library.update_root(int(b["index"]), b)
    else:
        a.library.add_root(b["path"], b.get("name"))
    return req.send_json(a.library.visible_roots(True))


def cover(req, a, method, qd, **_):
    # a folder or a video: poster.jpg/folder.jpg/<name>.jpg next to it, otherwise a frame (mp_media.cover)
    pic = mp_media.cover(a.library.resolve(int(qd.get("root", 0)), qd.get("path", "")),
                         720 if qd.get("w") == "720" else 360)
    if not pic:
        raise ApiError(T("no cover", "brak okładki"), 404)
    return req.send_static(pic, "image/png" if pic.endswith(".png") else "image/webp" if pic.endswith(".webp")
                            else "image/jpeg", method == "HEAD", "private, max-age=86400",
                            {"X-Cover-Kind": "frame" if mp_media.is_frame(pic) else "picture"})


def search(req, a, qd, **_):
    return req.send_json(a.library.search(qd.get("q", "")))


def browse(req, a, qd, **_):
    return req.send_json(a.library.browse(int(qd.get("root", 0)), qd.get("path", ""), qd.get("all") == "1"))


def episodes(req, a, qd, **_):
    return req.send_json(a.library.episodes(int(qd.get("root", 0)), qd.get("path", "")))


def segments(req, a, qd, **_):
    full = a.library.resolve(int(qd.get("root", 0)), qd.get("path", ""))
    d = os.path.dirname(full)
    try:
        names = [n for n in os.listdir(d) if not n.startswith(".") and media_kind(n) == "video"
                 and os.path.isfile(os.path.join(d, n))]
    except OSError:
        names = []
    eps = [os.path.join(d, x["path"]) for x in mp_ident.episodes([(n, 0) for n in names])[0]]
    return req.send_json(a.segments.request(full, eps))


def fs(req, a, qd, **_):
    return req.send_json(a.library.fs_dirs(qd.get("path", "")))


def library_stream(req, a, method, q, qd, root, path, **_):
    full = a.library.resolve(int(root), urllib.parse.unquote(path))
    req.caption_header(q, f"root={int(root)}&path={urllib.parse.quote(urllib.parse.unquote(path))}")
    if qd.get("v") == "cast" and mp_media.variant(full):
        return req.stream(mp_media.variant(full), method == "HEAD", watch=full)
    part = a.downloading_part(full)
    if part:  # a torrent still downloading into the library: downloaded pieces only (see /media/)
        tid, idx = part
        return req.stream(full, method == "HEAD", a.resolve_file(tid, idx)[1].get("_pieces"),
                           lambda: a.resolve_file(tid, idx)[1].get("_pieces"), watch=full)
    return req.stream(full, method == "HEAD", watch=full)


# ---- files that phones sent to the server (mp_upload.py history)
def uploads(req, a, **_):
    items = a.uploads.list()
    for h in items:
        if h["state"] == "new":
            g = a.ident.guess(h["name"])
            kind = g.get("kind") if g.get("tag") in ("seriale", "bajki") else None
            h.update(a.library.targets(g.get("tag"), g.get("title"), kind), guess=g)
            here = os.path.realpath(h["dir"])
            for t in h["targets"]:  # where it lies now is not a suggestion
                t["here"] = os.path.realpath(t["path"]) == here
            if h.get("pick") is not None and h["targets"][h["pick"]]["here"]:
                h["pick"] = None
        h.pop("dir", None)
        h.pop("path", None)
    return req.send_json({"items": items, "new": sum(h["state"] == "new" for h in items)})


def uploads_keep(req, a, **_):
    return req.send_json(a.uploads.keep(str(req.body().get("id") or "")))


def uploads_move(req, a, **_):
    b = req.body()
    root = a.library.roots()[int(b["root"])]
    dest = a.library.dest(int(b["root"]), str(b.get("sub") or ""))
    if b.get("learn") and b.get("guess") and root["tags"]:
        a.ident.learn(b["guess"], root["tags"][0])
    return req.send_json(a.uploads.move(str(b.get("id") or ""), dest, root["name"]))
