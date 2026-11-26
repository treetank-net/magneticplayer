"""Routes: settings the app changes: app and agent updates, search engines, OpenSubtitles. Each function is a route of
mp_routes.ROUTES: (req = the Handler, a = the App, method, p = path, q = query lists, qd = query values, path
parameters)."""
import re

import mp_appupdate
import mp_selfupdate
import mp_subflow
from mp_core import ApiError, save_config
from mp_i18n import T


def app_updates_mode(req, a, **_):
    mode = req.body().get("mode")
    if mode not in mp_appupdate.MODES:
        raise ApiError(T("mode must be auto or manual", "tryb to auto albo manual"), 400)
    return req.send_json(a.app_updates.set_mode(mode))


def search_engines(req, a, **_):
    return req.send_json({"engines": a.cfg.get("search_engines") or []})


def search_engines_set(req, a, **_):
    eng = []
    for e in (req.body().get("engines") or [])[:10]:
        name, url = str(e.get("name") or "").strip()[:40], str(e.get("url") or "").strip()[:500]
        if not name or not re.match(r"https?://[^\s/]+", url) or not re.search(r"\{(title|imdb|q)\}", url):
            raise ApiError(T("each search needs a name and an http(s) address with {q}, {title} or {imdb}",
                             "każda wyszukiwarka potrzebuje nazwy i adresu http(s) z {q}, {title} albo {imdb}"), 400)
        # 1.16+: what the site finds (torrents / video / subtitles) and where the app opens it (app / browser)
        kind = e.get("kind") if e.get("kind") in ("torrents", "video", "subtitles") else "torrents"
        opens = e.get("open") if e.get("open") in ("app", "browser") else "browser"
        eng.append({"name": name, "url": url, "kind": kind, "open": opens})
    a.cfg["search_engines"] = eng
    save_config(a.cfg)
    return req.send_json({"engines": eng})


def agent_updates_mode(req, a, **_):
    mode = req.body().get("mode")
    if mode not in mp_selfupdate.MODES:
        raise ApiError(T("mode must be auto or manual", "tryb to auto albo manual"), 400)
    return req.send_json(a.agent_updates.set_mode(mode))


def agent_update(req, a, **_):
    if not a.agent_updates.supported():
        raise ApiError(T("this system can't update the agent by itself yet", "ten system nie umie jeszcze sam zaktualizować agenta"), 409)
    return req.send_json(a.agent_updates.update_now())


def opensubtitles(req, a, **_):
    return req.send_json({"api_key": bool(a.cfg.get("opensubtitles_api_key")),
                           "username": a.cfg.get("opensubtitles_username", ""),
                           "password": bool(a.cfg.get("opensubtitles_password"))})


def opensubtitles_set(req, a, **_):
    b = req.body()
    a.subs.set_credentials(b.get("api_key"), b.get("username"), b.get("password"))
    return req.send_json({"ok": True})


# subtitle timing (agent 1.21+): {"subflow": {...}, "rhythm": {...}, "whisper": {...}}; POST one or more sections
def subtitles(req, a, **_):
    return req.send_json(mp_subflow.all_settings(a.cfg))


def subtitles_set(req, a, **_):
    b = req.body()
    out = None
    try:
        for section, changes in b.items():
            out = mp_subflow.set_settings(a, section, changes)
    except ValueError as e:
        raise ApiError(str(e), 400)
    return req.send_json(out or mp_subflow.all_settings(a.cfg))
