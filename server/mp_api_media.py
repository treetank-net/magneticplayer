"""Routes: playback tickets, a file's format and its version for Chromecast, and streaming a torrent's file. Each
function is a route of mp_routes.ROUTES: (req = the Handler, a = the App, method, p = path, q = query lists, qd =
query values, path parameters)."""
import os
import subprocess

import mp_media
import mp_renderers
from mp_core import ApiError
from mp_i18n import T
from mp_mpv import pick_audio


def _variant_info(v):
    """The prepared TV version: size, its cast check, and (1.14.2+) the language and title of its one audio track, so a
    phone sees whether it matches the audio picked since."""
    vi = mp_media.probe(v)
    a = (vi.get("audio") or [{}])[0]
    return {"size": os.path.getsize(v), "cast": mp_media.cast_check(vi),
            "audio": {"lang": a.get("lang") or "", "title": a.get("title") or ""}}


def media_ticket(req, a, **_):
    b = req.body()
    return req.send_json(a.ticket_urls(b, req.base_url(), b.get("variant")))


# ---- file format and the version for Chromecast
def media_probe(req, a, qd, **_):
    full = a.resolve_ref(qd)
    if not mp_media.detect_encoder():
        return req.send_json({"ffmpeg": False})
    try:
        info = mp_media.probe(full)
    except (ValueError, OSError, subprocess.TimeoutExpired) as e:
        raise ApiError(T(f"can't read the file: {e}", f"nie da się odczytać pliku: {e}"), 422)
    v = mp_media.variant(full)
    # assessment for every known Chromecast (renderer registry with the model)
    per = []
    for r in a.renderers.casts():
        prof = mp_renderers.CAST_PROFILES.get(r.get("profile") or "")
        if prof:
            per.append({"id": r["id"], "name": r.get("name"), "label": mp_renderers.label(prof),
                        **mp_media.cast_check_for(info, prof)})
    return req.send_json({
        "ffmpeg": True, "file": info, "cast": mp_media.cast_check(info), "cast_devices": per,
        "complete": a.ref_complete(qd),
        "variant": _variant_info(v) if v else None,
        "job": a.convert.status(full), "encoder": mp_media.encoder_info()})


def media_convert(req, a, **_):
    b = req.body()
    full = a.resolve_ref(b)
    if b.get("cancel"):
        a.convert.cancel(full)
        return req.send_json({"ok": True})
    if b.get("remove"):
        a.convert.cancel(full)
        mp_media.remove_variant(full)
        return req.send_json({"ok": True})
    if not a.ref_complete(b):
        raise ApiError(T("the video is still downloading; it will be prepared once complete",
                         "film jeszcze się pobiera; przygotuję go, gdy będzie cały"), 409)
    # the phone's remembered audio choice ("kind|lang", as for mpv): the version carries that track (agent 1.14+)
    aidx = None
    if b.get("audio"):
        try:
            tr = [{"type": "audio", "id": x["index"] + 1, "title": x.get("title"), "lang": x.get("lang")}
                  for x in mp_media.probe(full)["audio"]]
            hit = pick_audio(tr, b["audio"])
            aidx = hit - 1 if hit else None
        except (ValueError, OSError, subprocess.TimeoutExpired, KeyError):
            aidx = None
    try:
        return req.send_json(a.convert.start(full, os.path.basename(full), aidx))
    except (RuntimeError, ValueError, OSError) as e:
        raise ApiError(str(e), 409)


def torrent_stream(req, a, method, q, qd, tid, idx, **_):
    tid, idx = tid, int(idx)
    full, f = a.resolve_file(tid, idx)
    req.caption_header(q, f"torrent_id={tid}&file_index={idx}")
    if qd.get("v") == "cast" and mp_media.variant(full):
        return req.stream(mp_media.variant(full), method == "HEAD", watch=full)
    # downloaded pieces only: instead of zeros from a not-yet-downloaded file we wait for Deluge (see stream())
    return req.stream(full, method == "HEAD", f.get("_pieces"),
                       lambda: a.resolve_file(tid, idx)[1].get("_pieces"), watch=full)
