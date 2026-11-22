"""Routes: positions, "watched" marks and subtitle choices synced from phones (mp_watch.py, agent 1.18+). Each function
is a route of mp_routes.ROUTES: (req = the Handler, a = the App, method, p = path, q = query lists, qd = query values,
path parameters)."""
import json
import os

import mp_subfiles
from mp_core import ApiError
from mp_i18n import T
from mp_library import LibError, sidecar_subs


def _journal(a):
    """The tasks' moves journal after the watch state's last applied move (raw folder ids)."""
    try:
        with open(a.tasks.moves_path) as f:
            j = json.load(f)
    except (OSError, ValueError, AttributeError):
        return []
    return [m for m in j if m.get("at", 0) > a.watchstate.moves_at]


def sync(req, a, **_):
    b = req.body()
    if not isinstance(b, dict) or not isinstance(b.get("items", []), list):
        raise ApiError(T("expected {now, since, items}", "oczekiwano {now, since, items}"), 400)
    if not req.device:
        raise ApiError(T("only a paired device can sync", "synchronizować może tylko sparowane urządzenie"), 403)
    a.watchstate.apply_moves(_journal(a))

    def resolve(i, path):
        try:
            full = a.library.resolve(i, path)
        except (LibError, IndexError, ValueError, OSError):
            return None
        return full if os.path.exists(full) else None

    def choose_sub(full, name, at):
        subs = sidecar_subs(full)
        if name not in {x["name"] for x in subs} or at <= a.watchstate.subs_changed_at(full, subs):
            return None  # gone or changed here after the phone's choice: the server's subtitles win
        try:
            return mp_subfiles.promote(full, name, a.subsources, a.tasks.torrent_owned, a.subsync.move)
        except (mp_subfiles.FileError, OSError):
            return None

    return req.send_json(a.watchstate.sync(req.device, req.device_name(), b, a.library.roots(), resolve, choose_sub,
                                      a.tasks.note_played))
