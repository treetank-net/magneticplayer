"""Routes: playback tickets, a file's format and its version for Chromecast, and streaming a torrent's file. Each
function is a route of mp_routes.ROUTES: (req = the Handler, a = the App, method, p = path, q = query lists, qd =
query values, path parameters)."""
import os
import subprocess

import mp_media
import mp_renderers
from mp_core import ApiError
from mp_i18n import T
from mp_mpv import _iso2, audio_kind, pick_audio


def _pref_index(info, pref):
    """The film's audio track (index among its audio tracks) that the remembered choice `pref` ("kind|lang", as for
    mpv) picks, or None when it picks none (the version then gets mp_media.pick_audio's default)."""
    if not pref:
        return None
    tr = [{"type": "audio", "id": x["index"] + 1, "title": x.get("title"), "lang": x.get("lang")} for x in info["audio"]]
    hit = pick_audio(tr, pref)
    return hit - 1 if hit else None


def _kind(a, info):
    """The track's kind from its title; a track that says nothing, beside lector or dubbing ones, is the original (as
    AudioPref.of in the app)."""
    k = audio_kind(a.get("title"))
    if not k and any(audio_kind(x.get("title")) in ("lector", "dubbing") for x in info["audio"]):
        return "original"
    return k


def _audio_desc(a, info):
    """One audio track as the phone shows it: index, language, title, kind (as AudioSheet.kt reads it), channels."""
    return {"index": a.get("index"), "lang": a.get("lang") or "", "title": a.get("title") or "",
            "kind": _kind(a, info), "codec": a.get("codec"), "channels": a.get("channels")}


def _variant_info(full, v, info, want):
    """The prepared TV version: size, its cast check, and its one audio track (1.14.2+ language and title; 1.18+ also
    the film's track index and kind), plus `audio_match` when the phone sent its choice: whether the version carries
    the track that choice picks today, so the phone can offer preparing it again for another audio."""
    vi = mp_media.probe(v)
    a = (vi.get("audio") or [{}])[0]
    out = {"size": os.path.getsize(v), "cast": mp_media.cast_check(vi),
           "audio": {"lang": a.get("lang") or "", "title": a.get("title") or ""}}
    src = mp_media.variant_audio(full)
    if src is None and a:
        # a version made before the agent noted its track: the film's track with the same language and title
        same = [x for x in info["audio"] if _iso2((x.get("lang") or "").lower()) == _iso2((a.get("lang") or "").lower())
                and (x.get("title") or "") == (a.get("title") or "")]
        src = same[0] if len(same) == 1 else None
    if src is not None:
        out["audio"].update(_audio_desc(src, info))
        if want is not None and len(info["audio"]) > 1:
            out["audio_match"] = src.get("index") == want.get("index")
    else:
        out["audio"]["kind"] = audio_kind(a.get("title"))
    return out


def media_title(req, a, qd, **_):
    """The recognised name of a library video ("Series · S2 E3", "Film (1968)", as on mpv and the Remote), or null
    (agent 1.18+, `features.media_title`): the phone's player shows it instead of the file name."""
    full = a.resolve_ref(qd)
    try:
        title = a.catalog.title_for(full)
    except Exception:  # noqa: BLE001 (a title is a nicety)
        title = None
    return req.send_json({"title": title})


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
    # `audio` (agent 1.18+, `features.convert_audio_match`): the phone's remembered choice; the answer says which track
    # a version made now would carry (`audio_want`) and whether the prepared one has it (`variant.audio_match`)
    want = None
    if qd.get("audio") and info["audio"]:
        w = mp_media.pick_audio(info, index=_pref_index(info, qd["audio"]))
        want = _audio_desc(w, info) if w else None
    return req.send_json({
        "ffmpeg": True, "file": info, "cast": mp_media.cast_check(info), "cast_devices": per,
        "complete": a.ref_complete(qd),
        "variant": _variant_info(full, v, info, want) if v else None,
        "audio_want": want,
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
            aidx = _pref_index(mp_media.probe(full), b["audio"])
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
