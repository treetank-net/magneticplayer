"""Routes: subtitles next to a video: list, serve, search, download, set the default, fit and shift. Each function is a
route of mp_routes.ROUTES: (req = the Handler, a = the App, method, p = path, q = query lists, qd = query values,
path parameters)."""
import os
import threading

import mp_subfiles
import mp_subsync
from mp_core import ApiError
from mp_i18n import T, bind
from mp_library import sidecar_subs
from mp_subs import readable_srt


# ---- subtitles
def subs_list(req, a, qd, **_):
    full = a.resolve_ref(qd)
    out = []
    for x in sidecar_subs(full):
        sp = os.path.join(os.path.dirname(full), x["name"])
        if x["ext"] == "sub" and mp_subsync.is_text_sub(sp):
            x["ext"] = "srt"  # served converted (/sub): MicroDVD/MPL2/TMPlayer as SubRip
        r = a.subsync.get(sp)
        if r:  # fitted or assessed before (agent 1.9+): good / unsure / bad for this copy of the film
            x["fit"] = {"verdict": r["verdict"], "z": r["z"]}
        src = a.subsources.get(sp)
        if src:  # downloaded by the agent (1.12+): search results it matches are marked "you have it"
            x["source"] = {"provider": src["provider"], "id": src["id"], "release": src.get("release", "")}
        out.append(x)
    return req.send_json(out)


def sub_file(req, a, method, qd, **_):
    full = a.resolve_ref(qd)
    names = {s["name"]: s for s in sidecar_subs(full)}
    if qd.get("name") not in names:
        raise ApiError(T("no such subtitles", "nie ma takich napisów"), 404)
    if qd.get("vtt") == "1":  # browsers (<track>) understand only WebVTT
        return req.send_vtt(os.path.join(os.path.dirname(full), qd["name"]), full)
    sp = os.path.join(os.path.dirname(full), qd["name"])
    if qd["name"].lower().endswith(".srt") or (qd["name"].lower().endswith(".sub") and mp_subsync.is_text_sub(sp)):
        # MicroDVD/cp1250 named .srt, or a text .sub: the phone gets real UTF-8 SubRip
        return req.send_text(readable_srt(os.path.join(os.path.dirname(full), qd["name"]), full),
                              "application/x-subrip", method == "HEAD")
    return req.stream(os.path.join(os.path.dirname(full), qd["name"]), method == "HEAD")


def subs_search(req, a, **_):
    b = req.body()
    langs = [l.strip().lower() for l in (b.get("languages") or "en,pl").split(",") if l.strip()]
    args = (a.resolve_ref(b), langs, (b.get("title") or "").strip() or None, b.get("year") or None)
    if mp_subsync.available():  # while the user looks at the results: the embedded track's times for fitting
        threading.Thread(target=bind(lambda f: (mp_subsync.embedded(f) if os.path.exists(f) else None)),
                         args=(args[0],), daemon=True).start()
    if b.get("async"):  # the app shows progress by polling /api/subs/job
        return req.send_json({"job": a.subs.start_search(*args)})
    return req.send_json(a.subs.search(*args))


def subs_job(req, a, qd, **_):
    return req.send_json(a.subs.job(qd.get("id", "")))


def subs_job_stop(req, a, **_):
    a.subs.stop(req.body().get("id", ""))
    return req.send_json({"ok": True})


def subs_download(req, a, **_):
    b = req.body()
    full = a.resolve_ref(b)
    if b.get("async"):  # agent 1.12.1+: {"job"} right away; /api/subs/job says "download", then "fit"
        return req.send_json({"job": a.subs.start_job(lambda job: a.download_subs(full, b, job), "download")})
    return req.send_json(a.download_subs(full, b))


# this version becomes its language's default (1.12+)
def subs_default(req, a, **_):
    b = req.body()
    full = a.resolve_ref(b)
    if b.get("name") not in {x["name"] for x in sidecar_subs(full)}:
        raise ApiError(T("no such subtitles", "nie ma takich napisów"), 404)
    try:
        name = mp_subfiles.promote(full, b["name"], a.subsources, a.tasks.torrent_owned, a.subsync.move)
    except mp_subfiles.FileError as e:
        raise ApiError(str(e), e.status)
    except OSError as e:
        raise ApiError(e.strerror or str(e), 500)
    if a.mpv.running() and a.mpv.prop("path") == full:  # the Remote: this video plays in mpv, turn it on
        try:
            a.mpv.cmd("sub-add", os.path.join(os.path.dirname(full), name), "select")
        except ApiError:
            pass
    return req.send_json({"name": name})


# one subtitle file next to the video (agent 1.9+)
def subs_fit(req, a, **_):
    b = req.body()
    full = a.resolve_ref(b)
    if b.get("name") not in {x["name"] for x in sidecar_subs(full)}:
        raise ApiError(T("no such subtitles", "nie ma takich napisów"), 404)
    return req.send_json(a.fit_subs(full, b["name"]))


# by hand: {ref, name, by: seconds} (agent 1.12.1+)
def subs_shift(req, a, **_):
    b = req.body()
    full = a.resolve_ref(b)
    if b.get("name") not in {x["name"] for x in sidecar_subs(full)}:
        raise ApiError(T("no such subtitles", "nie ma takich napisów"), 404)
    try:
        by = float(b.get("by"))
    except (TypeError, ValueError):
        raise ApiError(T("by: seconds", "by: sekundy"), 400)
    if not -600 < by < 600 or not by:
        raise ApiError(T("by: seconds", "by: sekundy"), 400)
    return req.send_json(a.shift_subs(full, b["name"], by))
