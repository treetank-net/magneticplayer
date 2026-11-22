"""Routes: disks and background file tasks (mp_tasks.py): plans (previews), counters, the task queue. Each function is
a route of mp_routes.ROUTES: (req = the Handler, a = the App, method, p = path, q = query lists, qd = query values,
path parameters)."""
import json

import mp_scope
from mp_core import ApiError
from mp_i18n import T


# ---- disks and background file tasks (mp_tasks.py)
def moves(req, a, q, **_):
    try:
        since = float((q.get("since") or ["0"])[0])
    except ValueError:
        since = 0.0
    return req.send_json(a.tasks.moves(since))


def disks(req, a, **_):
    return req.send_json({"disks": a.tasks.disks(), "trash": a.tasks.trash(), "trash_days": a.tasks.trash_days()})


def plan(req, a, **_):
    b = req.body()
    kind = b.get("kind")
    try:
        scope = mp_scope.parse(b.get("scope"))  # agent 1.16+: every kind takes a scope
    except mp_scope.ScopeError as e:
        raise ApiError(str(e), 400) from None
    m = a.tasks._matcher(scope) if scope else None
    if kind == "free" and scope:
        raise ApiError(T("this task works on whole disks only", "to zadanie działa tylko na całych dyskach"), 400)
    if kind == "free":
        if any(k in b for k in ("amount", "to_percent", "order", "skip_kept")):  # delete to the trash (1.16+)
            return req.send_json(a.tasks.plan_free_space(
                int(b["root"]), b.get("amount"), b.get("to_percent"), b.get("order") or "watched_oldest",
                bool(b.get("skip_kept", True))))
        return req.send_json(a.tasks.plan_free(int(b["root"]), b.get("need")))
    if kind in ("audio_fix", "downscale", "compress") or (kind == "modernize" and "audio" in b):
        opts = {k: b[k] for k in ("encoder", "quality", "audio", "target", "remember", "only_high_bitrate",
                                  "skip_hevc_av1", "skip_seeding") if k in b}
        return req.send_json(a.tasks.plan_job(kind, opts, scope))
    if kind == "shrink":
        if b.get("profile"):  # choosing a profile for this folder/file is remembered (new files follow it)
            a.tasks.set_profile(int(b["root"]), b.get("path", ""), b["profile"])
        return req.send_json(a.tasks.plan_shrink(int(b["root"]), b.get("path", ""), b.get("encoder"), m=m))
    if kind == "modernize":  # old picture formats in the whole library (agent 1.8.2+)
        return req.send_json(a.tasks.plan_shrink(-1, "", b.get("encoder"), modernize=True, m=m))
    if kind == "move":
        return req.send_json(a.tasks.plan_move(b.get("items") or [], m=m))
    if kind == "tidy":
        return req.send_json(a.tasks.plan_tidy(a.ident, m=m))
    if kind == "dupes":
        if "what" in b or "keep" in b:  # agent 1.16+
            return req.send_json(a.tasks.plan_dupes2(b.get("what") or "same_files", b.get("keep") or "manual", scope))
        return req.send_json(a.tasks.plan_dupes(m=m))
    if kind == "subs_get":  # missing subtitles downloaded (agent 1.12.1+): library, a folder or a film
        if "which" in b or scope:  # agent 1.16+
            return req.send_json(a.tasks.plan_subs_get2(b.get("languages") or "", b.get("which") or "no_subs_in_lang",
                                                         b.get("order") or "recent", scope))
        langs = str(b.get("languages") or "").split(",")
        return req.send_json(a.tasks.plan_subs_get(langs, b.get("root"), b.get("path")))
    if kind == "subs":  # subtitles fitted to the films (agent 1.9+)
        if "which" in b or scope:  # agent 1.16+
            return req.send_json(a.tasks.plan_subs2(b.get("which") or "unchecked", bool(b.get("sub_to_srt", True)),
                                                     bool(b.get("mark_bad")), bool(b.get("queue_bad")), scope))
        return req.send_json(a.tasks.plan_subs(bool(b.get("every"))))
    if kind == "trash":
        return req.send_json(a.tasks.plan_trash(b.get("items") or [], m=m))
    if kind == "layout":  # agent 1.17+
        return req.send_json(a.tasks.plan_layout(b, scope))
    if kind == "balance":  # agent 1.17+, whole disks
        if scope:
            raise ApiError(T("this task works on whole disks only", "to zadanie działa tylko na całych dyskach"), 400)
        return req.send_json(a.tasks.plan_balance(b))
    if kind == "cast_ready":  # agent 1.17+
        return req.send_json(a.tasks.plan_cast_ready(b, scope))
    if kind == "reencode":  # agent 1.18+: steps, encoder, name
        return req.send_json(a.tasks.plan_reencode(b, scope))
    raise ApiError(T("kind must be free, shrink, modernize, audio_fix, downscale, compress, move, tidy, dupes, "
                     "subs, subs_get, trash, layout, balance, cast_ready or reencode",
                     "kind to free, shrink, modernize, audio_fix, downscale, compress, move, tidy, dupes, "
                     "subs, subs_get, trash, layout, balance, cast_ready albo reencode"), 400)


# counters of the task list for a scope (agent 1.16+)
def job_counters(req, a, q, **_):
    try:
        scope = mp_scope.parse((q.get("scope") or [""])[0])
    except mp_scope.ScopeError as e:
        raise ApiError(str(e), 400) from None
    lv = q.get("levels")  # agent 1.17+: the folder layout counter for these levels
    rs = None
    if q.get("reencode"):  # agent 1.18+: the re-encode counter for these steps (JSON)
        try:
            rs = json.loads(q["reencode"][0])
        except ValueError:
            raise ApiError(T("reencode must be JSON", "reencode to JSON"), 400) from None
    return req.send_json(a.tasks.jobs(scope, (q.get("languages") or ["pl,en"])[0], None if lv is None else lv[0],
                                      reencode=rs, only=(q.get("only") or [None])[0]))


def profile(req, a, **_):
    b = req.body()
    a.tasks.set_profile(int(b["root"]), b.get("path", ""), b.get("profile"))
    return req.send_json({"ok": True})


def trash_empty(req, a, **_):
    return req.send_json({"freed": a.tasks.purge_trash(everything=True)})


def task_list(req, a, q, **_):
    return req.send_json(a.tasks.list(brief=q.get("brief", [""])[0] == "1"))


def task_create(req, a, **_):
    b = req.body()
    pick = b.get("pick")
    return req.send_json(a.tasks.create(str(b.get("plan")), None if pick is None else {int(x) for x in pick}))


def task_move(req, a, tid, **_):
    return req.send_json({"queue": a.tasks.move(tid, str(req.body().get("to") or "up"))})


def task_order(req, a, **_):
    ids = req.body().get("ids")
    if not isinstance(ids, list):
        raise ApiError(T("ids must be a list", "ids to lista"), 400)
    return req.send_json({"queue": a.tasks.order([str(x) for x in ids])})
