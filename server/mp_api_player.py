"""Routes: mpv on this computer's screen and Chromecasts controlled by the agent. Each function is a route of
mp_routes.ROUTES: (req = the Handler, a = the App, method, p = path, q = query lists, qd = query values, path
parameters)."""
import os

from mp_core import ApiError, lan_ip
from mp_i18n import T
from mp_library import sidecar_subs


def cast_play(req, a, **_):
    b = req.body()
    port = req.server.server_address[1]
    t = a.ticket_urls(b, f"http://{lan_ip()}:{port}", "cast")
    subs = [{"url": x["vtt"], "lang": x["lang"]} for x in t["subs"]]
    return req.send_json(a.casts.play(str(b.get("device") or ""), t["url"], t["title"],
                                       t["content_type"] or "video/mp4", subs, b.get("start")))


def cast_cmd(req, a, **_):
    b = req.body()
    return req.send_json(a.casts.control(b.get("cmd"), b.get("value"), b.get("device") or None))


def player_open(req, a, **_):
    if not a.is_screen():
        raise ApiError(T(f"{a.name()} isn't a screen (no mpv/session or config: player)",
                         f"{a.name()} nie jest ekranem (brak mpv/sesji albo config: player)"), 409)
    b = req.body()
    if b.get("url"):  # a video from another server (URL with a ticket from /api/media/ticket)
        url = str(b["url"])
        subs = [str(u) for u in b.get("subs") or [] if str(u).startswith(("http://", "https://"))]
        if not url.startswith(("http://", "https://")):
            raise ApiError(T("url must be http(s)", "url musi być http(s)"), 400)
        a.mpv.open(url, b.get("title"), subs, b.get("start"), b.get("sub_lang"), b.get("audio"))
    else:
        full = a.resolve_ref(b)
        # mpv finds subtitles next to the video by itself, but not those in a subfolder (Subs/English.srt from torrents)
        extra = [os.path.join(os.path.dirname(full), x["name"]) for x in sidecar_subs(full) if os.sep in x["name"]]
        # the recognised name ("Series · S2 E3") on the Remote and the screen, not the release's file name
        try:
            title = a.catalog.title_for(full)
        except Exception:  # noqa: BLE001 (a title is a nicety, the film plays anyway)
            title = None
        a.mpv.open(full, title, start=b.get("start"), subs=extra, sub_lang=b.get("sub_lang"), audio=b.get("audio"))
    return req.send_json({"ok": True})


def player_cmd(req, a, **_):
    b = req.body()
    a.mpv.control(b.get("cmd"), b.get("value"))
    return req.send_json({"ok": True})
