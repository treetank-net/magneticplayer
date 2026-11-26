"""Routes: subtitles next to a video: list, serve, search, download, set the default, fit and shift. Each function is a
route of mp_routes.ROUTES: (req = the Handler, a = the App, method, p = path, q = query lists, qd = query values,
path parameters)."""
import os
import threading

import mp_subfiles
import mp_retime
import mp_subsync
from mp_core import ApiError
from mp_i18n import T, bind
from mp_library import sidecar_subs
from mp_subs import readable_srt


# ---- subtitles
def subs_list(req, a, qd, **_):
    full = a.resolve_ref(qd)
    out = []
    waiting = a.refit_waiting(full)
    for x in sidecar_subs(full):
        sp = os.path.join(os.path.dirname(full), x["name"])
        if x["ext"] == "sub" and mp_subsync.is_text_sub(sp):
            x["ext"] = "srt"  # served converted (/sub): MicroDVD/MPL2/TMPlayer as SubRip
        r = a.subsync.get(sp)
        # fitted or assessed before (agent 1.9+): good / unsure / bad for this copy of the film; `manual`: the total
        # shift by hand (1.12.1+). A file only shifted by hand (its fit failed, e.g. the film was still downloading)
        # has no verdict: that broke the whole list (500)
        # 1.18.8+: `weak` (lines up as it was, only the frame rate applied) and "pending" + `reason: "incomplete"` while
        # the film is still downloading (fitted when it is complete)
        fit = {k: r[k] for k in ("verdict", "z", "manual", "weak") if k in r} if r else {}
        if x["name"] in waiting:
            fit.update(verdict="pending", reason="incomplete")
        if fit:
            x["fit"] = fit
        src = a.subsources.get(sp)
        if src:  # downloaded by the agent (1.12+): search results it matches are marked "you have it"
            x["source"] = {"provider": src["provider"], "id": src["id"], "release": src.get("release", "")}
        try:  # agent 1.18+: when the file was last written (a shift, a fit), so a phone's copy knows it is stale
            x["mtime"] = int(os.stat(sp).st_mtime)
        except OSError:
            pass
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
    args += (None if args[2] else a.catalog.known_for(args[0]),)  # the catalog's titles and IMDb id: a last round
    if mp_subsync.available():  # while the user looks at the results: the embedded track's times for fitting
        threading.Thread(target=bind(lambda f: (mp_subsync.embedded(f) if os.path.exists(f) else None)),
                         args=(args[0],), daemon=True).start()
    if b.get("async"):  # the app shows progress by polling /api/subs/job
        return req.send_json({"job": a.subs.start_search(*args)})
    return req.send_json(a.subs.search(*args[:4], known=args[4]))


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
    a.watchstate.note_subs(full)  # a phone's older choice made offline doesn't undo this one (agent 1.18+)
    a.subflow.chosen(mp_retime.language_of(name))  # the language picked last (agent 1.21+, mp_subflow)
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


# re-timed by Whisper, cut by cut (agent 1.20+): {ref, name, async?}; async → {"job"}, /api/subs/job shows "retime"
def subs_retime(req, a, **_):
    b = req.body()
    full = a.resolve_ref(b)
    if b.get("name") not in {x["name"] for x in sidecar_subs(full)}:
        raise ApiError(T("no such subtitles", "nie ma takich napisów"), 404)
    if b.get("async"):
        return req.send_json({"job": a.subs.start_job(lambda job: a.retime_subs(full, b["name"], job), "retime")})
    return req.send_json(a.retime_subs(full, b["name"]))


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


# prepared by themselves after a download (agent 1.21+, mp_subflow): the state for one video, or run it now
def subs_auto(req, a, qd, **_):
    return req.send_json(a.subflow.status(a.resolve_ref(qd)))


def subs_auto_run(req, a, **_):
    b = req.body()
    full = a.resolve_ref(b)
    if b.get("async"):
        return req.send_json({"job": a.subs.start_job(lambda job: a.subflow.run(full, bool(b.get("force"))), "auto")})
    return req.send_json(a.subflow.run(full, bool(b.get("force"))))
