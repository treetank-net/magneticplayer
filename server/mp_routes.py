"""The agent's HTTP routes: one table instead of a chain of `if`s (Handler._dispatch_inner uses it).

ROUTES rows are (methods, pattern, handler, options), checked in order; the first row whose pattern and method match
handles the request. Methods: one ("GET"), a tuple, or "*" (any). A pattern is the path (its trailing "/" removed)
with `<name:type>` parameters, types in TYPES; the parameters reach the handler by name.

Options: PUBLIC rows are looked up before the token check (pairing, installing, OAuth); every other row needs a device
token or, for GET/HEAD, a ticket for that one file, and a guest gets only what magneticplayer_agent.GUEST_ALLOWED lists
(that list stays the single source of what guests may do). A request no row takes ends as before: 401 without a token,
403 for a guest, otherwise 404.

A handler is a function `(req, a, *, method, p, q, qd, **params)` that sends the response itself (req = the Handler,
a = the App); the topic modules mp_api_*.py hold them. A row that only returns `app.x.y(params)` as JSON says
`call("x.y", "param")`: build() resolves it once at startup, so a misspelled target stops the agent from starting
instead of failing a request.
"""
import re

import mp_api_access as access
import mp_api_catalog as catalog
import mp_api_config as config
import mp_api_library as library
import mp_api_media as media
import mp_api_player as player
import mp_api_subs as subs
import mp_api_system as system
import mp_api_tasks as tasks
import mp_api_torrents as torrents
import mp_api_watch as watch

TYPES = {"hex": "[0-9a-fA-F]+", "int": r"\d+", "id8": "[0-9a-f]{8}", "id16": "[0-9a-f]{16}",
         "code": "[A-Za-z0-9-]{4,12}", "path": ".+", "rest": ".*", "tail": "(?:/.*)?"}
PUBLIC = {"public": True}


class call:
    """A row that returns `app.<target>(*path params[, request body])` as JSON."""

    def __init__(self, target, *params, body=False):
        self.target, self.params, self.body = target, params, body

    def bind(self, app):
        fn = app
        for part in self.target.split("."):
            fn = getattr(fn, part)  # AttributeError at startup: the route points at something that doesn't exist
        if not callable(fn):
            raise TypeError(f"route target app.{self.target} is not callable")
        params, body = self.params, self.body

        def route(req, a, **kw):
            return req.send_json(fn(*[kw[x] for x in params], *([req.body()] if body else [])))
        route.__name__ = route.__qualname__ = f"call({self.target})"
        return route


ROUTES = [
    ("*", "/api/ping", system.ping, PUBLIC),
    (("GET", "HEAD"), "", system.web_page, PUBLIC),
    (("GET", "HEAD"), "/index.html", system.web_page, PUBLIC),
    ("GET", "/i/<code:code>", access.install_script, PUBLIC),
    ("GET", "/i/<code:code>/bundle.tar.gz", access.install_bundle, PUBLIC),
    ("GET", "/i/<code:code>/ps", access.install_ps, PUBLIC),
    ("POST", "/api/install/join", access.install_join, PUBLIC),
    ("POST", "/api/pair", access.pair, PUBLIC),
    ("POST", "/api/access/request", access.access_request, PUBLIC),
    ("GET", "/api/access/request/<rid:id16>", access.access_poll, PUBLIC),
    ("*", "/.well-known/oauth-<rest:rest>", access.mcp, PUBLIC),
    ("*", "/oauth/<rest:rest>", access.mcp, PUBLIC),
    ("*", "/mcp", access.mcp, PUBLIC),
    (("GET", "PUT"), "/api/upload/<rest:rest>", library.upload, {}),
    ("GET", "/api/info", system.info, {}),
    ("GET", "/api/access/requests", access.access_requests, {}),
    ("POST", "/api/access/requests/<rid:id16>", access.access_decide, {}),
    ("POST", "/api/pair/code", access.pair_code, {}),
    ("POST", "/api/install/code", access.install_code, {}),
    ("GET", "/api/install/status", access.install_status, {}),
    ("GET", "/api/devices", access.devices, {}),
    ("POST", "/api/devices/revoke", access.devices_revoke, {}),
    ("POST", "/api/wake/verified", system.wake_verified, {}),
    ("POST", "/api/wake", system.wake, {}),
    ("POST", "/api/media/ticket", media.media_ticket, {}),
    ("GET", "/api/media/probe", media.media_probe, {}),
    ("GET", "/api/media/title", media.media_title, {}),
    ("POST", "/api/media/convert", media.media_convert, {}),
    ("GET", "/api/media/converts", call("convert.all"), {}),
    ("GET", "/api/cast/devices", call("casts.devices"), {}),
    ("POST", "/api/cast/play", player.cast_play, {}),
    ("GET", "/api/announce", call("announce.list"), {}),
    ("GET", "/api/renderers", call("renderers.casts"), {}),
    ("GET", "/api/cast/status", call("casts.status"), {}),
    ("POST", "/api/cast/cmd", player.cast_cmd, {}),
    ("GET", "/api/app/version", system.app_version, {}),
    (("GET", "HEAD"), "/app/MagneticPlayer.apk", system.app_apk, {}),
    ("GET", "/api/torrents", torrents.torrents, {}),
    ("GET", "/api/jobs", torrents.torrent_jobs, {}),
    ("POST", "/api/torrents/<tid:hex>/after", torrents.torrent_after, {}),
    ("POST", "/api/torrents", torrents.torrent_add, {}),
    ("POST", "/api/torrents/<tid:hex>/move", torrents.torrent_move, {}),
    ("POST", "/api/library/guess", torrents.guess, {}),
    ("GET", "/api/torrents/<tid:hex>/files", torrents.torrent_files, {}),
    ("POST", "/api/torrents/<tid:hex>/action", torrents.torrent_action, {}),
    (("GET", "HEAD"), "/media/<tid:hex>/<idx:int><rest:tail>", media.torrent_stream, {}),
    ("POST", "/api/player/open", player.player_open, {}),
    ("GET", "/api/player/status", call("mpv.status"), {}),
    ("POST", "/api/player/cmd", player.player_cmd, {}),
    ("POST", "/api/system/suspend", system.system_suspend, {}),
    ("POST", "/api/system/screen_on", system.system_screen_on, {}),
    ("POST", "/api/system/unlock", system.system_unlock, {}),
    ("GET", "/api/library", library.library, {}),
    ("POST", "/api/library", library.library_edit, {}),
    ("GET", "/api/library/moves", tasks.moves, {}),
    ("GET", "/api/library/disks", tasks.disks, {}),
    ("POST", "/api/library/plan", tasks.plan, {}),
    ("GET", "/api/library/jobs", tasks.job_counters, {}),
    ("GET", "/api/library/plan/<pid:id8>", call("tasks.plan_status", "pid"), {}),
    ("POST", "/api/library/profile", tasks.profile, {}),
    ("POST", "/api/library/trash/empty", tasks.trash_empty, {}),
    ("GET", "/api/tasks", tasks.task_list, {}),
    ("POST", "/api/tasks", tasks.task_create, {}),
    ("GET", "/api/tasks/<tid:id8>", call("tasks.get", "tid"), {}),
    ("POST", "/api/tasks/<tid:id8>/remove", call("tasks.remove", "tid"), {}),
    ("POST", "/api/tasks/<tid:id8>/cancel", call("tasks.cancel", "tid"), {}),
    ("POST", "/api/tasks/<tid:id8>/pause", call("tasks.pause", "tid"), {}),
    ("POST", "/api/tasks/<tid:id8>/resume", call("tasks.resume", "tid"), {}),
    ("POST", "/api/tasks/<tid:id8>/move", tasks.task_move, {}),
    ("POST", "/api/tasks/<tid:id8>/retry", call("tasks.retry", "tid"), {}),
    ("POST", "/api/tasks/order", tasks.task_order, {}),
    (("GET", "HEAD"), "/api/library/cover", library.cover, {}),
    ("GET", "/api/library/search", library.search, {}),
    ("GET", "/api/library/browse", library.browse, {}),
    ("GET", "/api/library/episodes", library.episodes, {}),
    ("GET", "/api/library/segments", library.segments, {}),
    ("GET", "/api/fs", library.fs, {}),
    (("GET", "HEAD"), "/lib/<root:int>/<path:path>", library.library_stream, {}),
    ("GET", "/api/subs/list", subs.subs_list, {}),
    (("GET", "HEAD"), "/sub", subs.sub_file, {}),
    ("POST", "/api/subs/search", subs.subs_search, {}),
    ("GET", "/api/subs/job", subs.subs_job, {}),
    ("POST", "/api/subs/job/stop", subs.subs_job_stop, {}),
    ("POST", "/api/subs/download", subs.subs_download, {}),
    ("POST", "/api/subs/default", subs.subs_default, {}),
    ("POST", "/api/subs/fit", subs.subs_fit, {}),
    ("POST", "/api/subs/shift", subs.subs_shift, {}),
    ("GET", "/api/config/app_updates", call("app_updates.status"), {}),
    ("POST", "/api/config/app_updates", config.app_updates_mode, {}),
    ("GET", "/api/catalog", catalog.catalog, {}),
    ("GET", "/api/catalog/title", catalog.title, {}),
    ("GET", "/api/catalog/search", catalog.search, {}),
    ("GET", "/api/catalog/episodes", catalog.episodes, {}),
    ("GET", "/api/catalog/person", catalog.person, {}),
    ("GET", "/api/catalog/next", catalog.next_titles, {}),
    ("GET", "/api/catalog/suggest", catalog.suggest, {}),
    ("POST", "/api/watch/sync", watch.sync, {}),
    ("GET", "/api/wishlist", catalog.wishlist, {}),
    ("POST", "/api/wishlist", catalog.wishlist_add, {}),
    ("POST", "/api/wishlist/remove", catalog.wishlist_remove, {}),
    ("GET", "/api/config/search", config.search_engines, {}),
    ("POST", "/api/config/search", config.search_engines_set, {}),
    ("GET", "/api/uploads", library.uploads, {}),
    ("POST", "/api/uploads/keep", library.uploads_keep, {}),
    ("POST", "/api/uploads/move", library.uploads_move, {}),
    ("GET", "/api/config/jobs", call("tasks.jobs_config"), {}),
    ("POST", "/api/config/jobs", call("tasks.set_jobs_config", body=True), {}),
    ("GET", "/api/config/shrink", call("tasks.shrink_config"), {}),
    ("POST", "/api/config/shrink", call("tasks.set_shrink_config", body=True), {}),
    ("GET", "/api/config/agent_updates", call("agent_updates.status"), {}),
    ("POST", "/api/config/agent_updates", config.agent_updates_mode, {}),
    ("POST", "/api/agent/update", config.agent_update, {}),
    ("GET", "/api/config/opensubtitles", config.opensubtitles, {}),
    ("POST", "/api/config/opensubtitles", config.opensubtitles_set, {}),
]


def compile_pattern(pattern):
    out, pos = "", 0
    for m in re.finditer(r"<(\w+):(\w+)>", pattern):
        out += re.escape(pattern[pos:m.start()]) + f"(?P<{m.group(1)}>{TYPES[m.group(2)]})"
        pos = m.end()
    return re.compile(out + re.escape(pattern[pos:]))


def build(app, routes=None):
    """The table ready for matching: [(methods or None, regex, handler, public)]; `call` rows bound to `app`."""
    out = []
    for methods, pattern, handler, opts in ROUTES if routes is None else routes:
        if isinstance(handler, call):
            handler = handler.bind(app)
        elif not callable(handler):
            raise TypeError(f"route {pattern}: handler is not callable")
        methods = None if methods == "*" else frozenset((methods,) if isinstance(methods, str) else methods)
        out.append((methods, compile_pattern(pattern), handler, bool(opts.get("public"))))
    return out


def find(table, method, p, public):
    """(handler, path parameters) of the first matching row among the public (or the other) ones, or None."""
    for methods, rx, handler, pub in table:
        if pub != public or (methods is not None and method not in methods):
            continue
        m = rx.fullmatch(p)
        if m:
            return handler, m.groupdict()
    return None
