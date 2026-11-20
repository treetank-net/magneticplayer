"""Routes: ping, the web page, /api/info, the published APK, waking other computers and this one's power and screen.
Each function is a route of mp_routes.ROUTES: (req = the Handler, a = the App, method, p = path, q = query lists, qd
= query values, path parameters)."""
import os
import threading
import time

import mp_platform as platform
import mp_power
from mp_core import APK_DIR, ApiError, WEB_DIR, apk_version, log, save_config
from mp_i18n import T
from mp_power import suspend, wake_screen


def ping(req, a, **_):
    # id: the app checks that the same server is at a known address before sending it the token
    # tasks: when the latest library task finished, so a phone asks for the list only when it changes
    return req.send_json({"ok": True, "app": "magneticplayer", "version": req.version, "id": req.app.cfg["id"],
                           "tasks": req.app.tasks.last_finished()})


def web_page(req, a, method, **_):
    # web client (laptop/tablet without the app); a static file, API data requires a token anyway
    return req.send_static(os.path.join(WEB_DIR, "index.html"), "text/html; charset=utf-8", method == "HEAD")


def info(req, a, **_):
    return req.send_json(a.info(req.client_address[0], req.device, req.role))


def wake_verified(req, a, **_):
    # the app woke this computer over the network (sent WoL, then it answered): remembered for everyone
    net = platform.net_info(req.client_address[0])
    a.cfg["wake_verified"] = {"at": time.time(), "mac": net.get("mac"), "by": req.device}
    save_config(a.cfg)
    log(f"wake over the network confirmed (device {req.device})")
    return req.send_json({"ok": True})


def wake(req, a, **_):
    b = req.body()
    try:
        platform.send_wol(str(b.get("mac") or ""), b.get("ip"))
    except ValueError as e:
        raise ApiError(str(e), 400)
    return req.send_json({"ok": True})


def app_version(req, a, **_):
    return req.send_json(apk_version())


def app_apk(req, a, method, **_):
    return req.stream(os.path.join(APK_DIR, "MagneticPlayer.apk"), method == "HEAD")


def system_suspend(req, a, **_):
    if not mp_power.POWER:
        raise ApiError(T(f"{a.name()} has sleep over the network disabled", f"{a.name()} ma wyłączone usypianie z sieci"), 403)
    if a.mpv.running():
        a.mpv.cmd("quit")
    req.send_json({"ok": True})
    threading.Timer(1.0, lambda: suspend(a.guard)).start()
    return


def system_screen_on(req, a, **_):
    return req.send_json({"ok": True, "unlocked": wake_screen()})


def system_unlock(req, a, **_):
    return req.send_json({"ok": True, "unlocked": wake_screen(unlock=True)})
