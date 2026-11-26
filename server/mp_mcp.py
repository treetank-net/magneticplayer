"""MCP server in the agent (plan-library.md, decision 17): an AI assistant in the home network organises the library.

Transport: Streamable HTTP at `POST /mcp`, plain JSON responses (no server-initiated messages). Authorisation:
the device token as `Authorization: Bearer`. Without one the agent answers 401 with `WWW-Authenticate` pointing to the
OAuth metadata, and an MCP client (e.g. Claude Code) goes through OAuth 2.1 with PKCE:

1. `/.well-known/oauth-protected-resource` and `/.well-known/oauth-authorization-server` describe the endpoints;
2. `POST /oauth/register` (dynamic client registration) remembers the client's name and loopback redirect URIs;
3. `GET /oauth/authorize` opens a page in the user's browser that files an ordinary access request
   (mp_access.py): the household's phones show a notification "<client> wants access";
4. the approval decides the role: "household member" = `owner` (all tools), "guest for a day" = `guest` (read-only
   tools, for a day); the page then pairs and redirects back with a one-time code;
5. `POST /oauth/token` exchanges the code (PKCE S256) for the device token. The device shows up in the device list
   and is revoked there like any phone.

Tools that change something only prepare a plan (a preview, `plan_*`); `start_task` runs it. The assistant's client
asks the user before each tool call, and the tool descriptions tell the model to show the preview first.
"""
import base64
import hashlib
import html
import json
import os
import secrets
import threading
import time
import urllib.parse

import mp_media
import mp_subflow
from mp_i18n import T

PROTOCOLS = ("2025-06-18", "2025-03-26", "2024-11-05")
CODE_TTL = 120
PLAN_WAIT = 50  # seconds a plan_* tool waits for the planner before returning "still running"


def _schema(props=None, required=()):
    return {"type": "object", "properties": props or {}, "required": list(required)}


S = {"root": {"type": "integer", "description": "library folder index (from list_folders)"},
     "path": {"type": "string", "description": "path inside the folder, '' = the folder itself"}}

TOOLS = [
    # read
    ("list_folders", False, "Library folders with tags, visibility, storage profile, disk and size.", _schema()),
    ("browse", False, "List subfolders and media files of a library folder path.", _schema(S, ["root"])),
    ("search", False, "Search file and folder names in the whole library (case and diacritics insensitive).",
     _schema({"query": {"type": "string"}}, ["query"])),
    ("identify", False, "Recognise a release name (movie/series/kids, title, year, IMDb, suggested tag).",
     _schema({"name": {"type": "string"}}, ["name"])),
    ("file_info", False, "Streams of a video (codec, resolution, audio tracks, subtitles, duration).",
     _schema(S, ["root", "path"])),
    ("disks", False, "Disks holding library folders: total, free, size per folder, trash.", _schema()),
    ("list_tasks", False, "Background tasks (moving, shrinking, deleting) with progress and results.", _schema()),
    ("get_plan", False, "A plan prepared by a plan_* tool: state and the proposed items.",
     _schema({"plan": {"type": "string"}}, ["plan"])),
    # organise (owner only)
    ("set_folder", True, "Change a library folder: name, tags (e.g. filmy, seriale, bajki, kopia), visibility "
     "('all', 'owners' or device ids), excluded subfolders. Ask the user before changing visibility.",
     _schema({"root": S["root"], "name": {"type": "string"}, "tags": {"type": "array", "items": {"type": "string"}},
              "visible": {}, "exclude": {"type": "array", "items": {"type": "string"}}}, ["root"])),
    ("set_title_tags", True, "Set tags on a title by its IMDb id, used by the folder layout like the title's own: e.g. "
     "'pl' or 'en' (its sound language when the file's tracks carry none: check with file_info), 'anime', 'bajki'. "
     "A library folder fits a title when the title has all of the folder's tags; the most specific folder wins. "
     "An empty list removes them. Moves nothing.",
     _schema({"imdb": {"type": "string"}, "tags": {"type": "array", "items": {"type": "string"}}}, ["imdb", "tags"])),
    ("set_profile", True, "Set the storage profile of a folder, subfolder or file: original (never touch), good "
     "(HEVC up to 1080p), compact (HEVC up to 720p), tiny (HEVC up to 480p). Nothing is re-encoded until a "
     "plan_shrink preview is confirmed.",
     _schema({**S, "profile": {"type": "string", "enum": ["original", "good", "compact", "tiny"]}},
             ["root", "profile"])),
    ("plan_free_space", True, "Preview: which items to move off the disk of a library folder to folders with the same "
     "tag on other disks. Changes nothing.",
     _schema({"root": S["root"], "need_gb": {"type": "number", "description": "how much to free; default: up to 15% free"}},
             ["root"])),
    ("plan_move", True, "Preview explicit moves, e.g. after recognising misplaced items. Changes nothing.",
     _schema({"items": {"type": "array", "items": _schema({"root": {"type": "integer"}, "path": {"type": "string"},
                                                            "to_root": {"type": "integer"}, "to_sub": {"type": "string"}},
                                                           ["root", "path", "to_root"])}}, ["items"])),
    ("plan_tidy", True, "Preview: items whose recognised kind doesn't match their folder's tag, with a target. "
     "Changes nothing. Items marked unsure need the user's judgement.", _schema()),
    ("plan_shrink", True, "Preview: videos in a folder to re-encode by their storage profiles, with the estimated "
     "saving. Changes nothing.", _schema(S, ["root"])),
    ("plan_duplicates", True, "Find identical videos (size, then content hash). Changes nothing.", _schema()),
    ("plan_delete", True, "Preview deleting files (to the folder's trash, kept for trash_days). Changes nothing.",
     _schema({"items": {"type": "array", "items": _schema({"root": {"type": "integer"}, "path": {"type": "string"}},
                                                           ["root", "path"])}}, ["items"])),
    ("start_task", True, "Run a plan as a background task. Only after the user has seen the preview and agreed. "
     "`pick` = indexes of the plan's items to do (default: all).",
     _schema({"plan": {"type": "string"}, "pick": {"type": "array", "items": {"type": "integer"}}}, ["plan"])),
    ("cancel_task", True, "Stop a running or queued task (the current item is left as it was).",
     _schema({"task": {"type": "string"}}, ["task"])),
    # subtitles (agent 1.21+)
    ("get_subtitle_settings", False, "How subtitles are timed and fetched by themselves after a download. Sections: "
     "subflow (auto: run after a download; original: the film's language, 'auto' or a code; fallback; sources: "
     "file/embedded/search in order; providers: services for automatic downloads, only ones without daily limits by "
     "default; backup: services tried only when nothing from providers fitted (OpenSubtitles: daily limit), at most "
     "backup_max downloads per language and film; whisper: never/unsure/always; target: 'last' = the language the user picked last (last), a code, or '' "
     "for none; translate: baseline/speech; snap: s; min_lift), rhythm (speech-rhythm timing: jump, desc_weight, "
     "onset_lag, max_disagree, min_sharp), whisper (python, device, model, model_en, auto, lead: s a line shows before "
     "its speech, extend: s a line may stay longer).", _schema()),
    ("set_subtitle_settings", True, "Change subtitle settings of one section (see get_subtitle_settings); a value of "
     "null restores the default. Tell the user what you changed.",
     _schema({"section": {"type": "string", "enum": ["subflow", "rhythm", "whisper"]}, "changes": {"type": "object"}},
             ["section", "changes"])),
    ("subtitle_status", False, "For one video: its baseline subtitles (language, how timed, verdict) and the last "
     "automatic result.", _schema(S, ["root", "path"])),
    ("prepare_subtitles", True, "Run the automatic subtitles for one video now (baseline in the film's language, then "
     "the target language fitted to it); force=true makes a new baseline. Can take minutes with Whisper.",
     _schema({**S, "force": {"type": "boolean"}}, ["root", "path"])),
]


class McpError(Exception):
    def __init__(self, code, msg):
        super().__init__(msg)
        self.code = code


class Mcp:
    def __init__(self, app, version, config_dir, log):
        self.app, self.version, self.log = app, version, log
        self.clients_path = os.path.join(config_dir, "oauth-clients.json")
        self.lock = threading.Lock()
        try:
            with open(self.clients_path) as f:
                self.clients = json.load(f)
        except (OSError, ValueError):
            self.clients = {}
        self.pending = {}  # authorize session → {client_id, redirect_uri, challenge, state, req, secret, exp}
        self.codes = {}  # one-time code → {token, client_id, redirect_uri, challenge, exp, role}

    # ---- OAuth

    def metadata_resource(self, base):
        return {"resource": f"{base}/mcp", "authorization_servers": [base], "bearer_methods_supported": ["header"],
                "resource_name": "MagneticPlayer"}

    def metadata_server(self, base):
        return {"issuer": base, "authorization_endpoint": f"{base}/oauth/authorize",
                "token_endpoint": f"{base}/oauth/token", "registration_endpoint": f"{base}/oauth/register",
                "response_types_supported": ["code"], "grant_types_supported": ["authorization_code"],
                "code_challenge_methods_supported": ["S256"], "token_endpoint_auth_methods_supported": ["none"],
                "scopes_supported": ["library"]}

    @staticmethod
    def _redirect_ok(uri):
        u = urllib.parse.urlsplit(uri or "")
        return (u.scheme == "http" and u.hostname in ("localhost", "127.0.0.1", "::1")) or u.scheme == "https"

    def register(self, body):
        uris = [str(u) for u in body.get("redirect_uris") or []][:5]
        if not uris or not all(self._redirect_ok(u) for u in uris):
            raise McpError(400, "redirect_uris must be loopback http or https")
        cid = secrets.token_urlsafe(12)
        c = {"client_id": cid, "client_name": " ".join(str(body.get("client_name") or "AI assistant").split())[:40],
             "redirect_uris": uris, "created": time.time()}
        with self.lock:
            self.clients[cid] = c
            if len(self.clients) > 50:
                for k in sorted(self.clients, key=lambda k: self.clients[k]["created"])[:-50]:
                    del self.clients[k]
            tmp = self.clients_path + ".tmp"
            with open(tmp, "w") as f:
                json.dump(self.clients, f)
            os.replace(tmp, self.clients_path)
        return dict(c, token_endpoint_auth_method="none", grant_types=["authorization_code"], response_types=["code"])

    def authorize_page(self, q, ip):
        """HTML page in the user's browser: files the access request and waits for the phone."""
        cid, uri = q.get("client_id", ""), q.get("redirect_uri", "")
        c = self.clients.get(cid)
        if not c or uri not in c["redirect_uris"] or q.get("response_type") != "code" \
                or q.get("code_challenge_method") != "S256" or not q.get("code_challenge"):
            raise McpError(400, "bad authorization request (client, redirect_uri, PKCE S256)")
        r = self.app.access.request(T(f"{c['client_name']} (AI assistant)", f"{c['client_name']} (asystent AI)"), ip)
        sid = secrets.token_urlsafe(16)
        with self.lock:
            now = time.time()
            self.pending = {k: v for k, v in self.pending.items() if v["exp"] > now}
            self.pending[sid] = {"client_id": cid, "redirect_uri": uri, "challenge": q["code_challenge"],
                                 "state": q.get("state"), "req": r["id"], "secret": r["secret"], "exp": now + r["expires_in"],
                                 "name": c["client_name"]}
        name = html.escape(c["client_name"])
        title = T("Approve on your phone", "Zatwierdź na telefonie")
        text = T(f"<b>{name}</b> wants to manage the MagneticPlayer library on this computer. A notification is on "
                 "the household's phones: <b>Household member</b> = it may organise (move, shrink, delete to the trash, "
                 "always after a preview), <b>Guest for a day</b> = it may only look.",
                 f"<b>{name}</b> chce zarządzać biblioteką MagneticPlayera na tym komputerze. Na telefonach domowników "
                 "jest powiadomienie: <b>Domownik</b> = może porządkować (przenosić, zmniejszać, usuwać do kosza, "
                 "zawsze po podglądzie), <b>Gość na dzień</b> = może tylko oglądać.")
        waiting, denied, expired = (T("Waiting…", "Czekam…"), T("Declined.", "Odrzucono."),
                                    T("The request expired, start again from the assistant.", "Prośba wygasła, zacznij jeszcze raz z asystenta."))
        return f"""<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width">
<title>MagneticPlayer</title><style>body{{font:16px system-ui;max-width:32em;margin:3em auto;padding:0 1em;color:#222}}
@media(prefers-color-scheme:dark){{body{{background:#121212;color:#eee}}}}</style>
<h2>{title}</h2><p>{text}</p><p id="s">{waiting}</p>
<script>
async function poll(){{
  try{{const r=await fetch('/oauth/authorize/wait?session={sid}');const j=await r.json();
    if(j.redirect){{location.href=j.redirect;return}}
    if(j.state==='denied'){{document.getElementById('s').textContent={json.dumps(denied)};return}}
    if(j.state==='gone'){{document.getElementById('s').textContent={json.dumps(expired)};return}}
  }}catch(e){{}}
  setTimeout(poll,2000)}}
poll();
</script>"""

    def authorize_wait(self, sid):
        with self.lock:
            s = self.pending.get(sid)
        if not s:
            return {"state": "gone"}
        try:
            st = self.app.access.poll(s["req"], s["secret"])
        except Exception:  # noqa: BLE001 — expired
            return {"state": "gone"}
        if st["state"] == "denied":
            return {"state": "denied"}
        if not st.get("code"):
            return {"state": st["state"]}
        res, err = self.app.devices.pair(st["code"], T(f"{s['name']} (AI assistant)", f"{s['name']} (asystent AI)"))
        if err:
            return {"state": "gone"}
        code = secrets.token_urlsafe(24)
        with self.lock:
            self.pending.pop(sid, None)
            self.codes[code] = {"token": res["token"], "client_id": s["client_id"], "redirect_uri": s["redirect_uri"],
                                "challenge": s["challenge"], "exp": time.time() + CODE_TTL, "role": res["role"]}
        self.log(f"MCP: {s['name']} approved as {res['role']}")
        q = {"code": code, **({"state": s["state"]} if s["state"] else {})}
        sep = "&" if "?" in s["redirect_uri"] else "?"
        return {"redirect": s["redirect_uri"] + sep + urllib.parse.urlencode(q)}

    def token(self, form):
        if form.get("grant_type") != "authorization_code":
            raise McpError(400, "unsupported_grant_type")
        with self.lock:
            c = self.codes.pop(form.get("code", ""), None)
        if not c or c["exp"] < time.time() or c["client_id"] != form.get("client_id", c["client_id"]) \
                or c["redirect_uri"] != form.get("redirect_uri", c["redirect_uri"]):
            raise McpError(400, "invalid_grant")
        verifier = form.get("code_verifier", "")
        calc = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()
        if not verifier or not secrets.compare_digest(calc, c["challenge"]):
            raise McpError(400, "invalid_grant")
        return {"access_token": c["token"], "token_type": "Bearer", "scope": "library"}

    # ---- JSON-RPC

    def handle(self, msg, role):
        """One JSON-RPC message → response dict, or None for a notification."""
        mid, method, params = msg.get("id"), msg.get("method"), msg.get("params") or {}
        if mid is None:
            return None  # notifications/initialized and friends
        try:
            if method == "initialize":
                want = params.get("protocolVersion")
                result = {"protocolVersion": want if want in PROTOCOLS else PROTOCOLS[0],
                          "capabilities": {"tools": {"listChanged": False}},
                          "serverInfo": {"name": "magneticplayer", "title": "MagneticPlayer library",
                                         "version": self.version},
                          "instructions": "Home media library (movies, series, kids' shows on several disks). "
                                          "Use list_folders and disks first. Changes always go through a plan_* "
                                          "preview that you show to the user; call start_task only after they agree. "
                                          "Folder tags: filmy = movies, seriale = series, bajki = kids, kopia = backup "
                                          "(hidden, never touched)."}
            elif method == "ping":
                result = {}
            elif method == "tools/list":
                result = {"tools": [{"name": n, "description": d, "inputSchema": sch,
                                     "annotations": {"readOnlyHint": not w}}
                                    for n, w, d, sch in TOOLS if not w or role != "guest"]}
            elif method == "tools/call":
                result = self.call(params.get("name"), params.get("arguments") or {}, role)
            else:
                raise McpError(-32601, f"method not found: {method}")
            return {"jsonrpc": "2.0", "id": mid, "result": result}
        except McpError as e:
            return {"jsonrpc": "2.0", "id": mid, "error": {"code": e.code, "message": str(e)}}

    def call(self, name, args, role):
        tool = next((t for t in TOOLS if t[0] == name), None)
        if not tool:
            raise McpError(-32602, f"unknown tool: {name}")
        if tool[1] and role == "guest":
            return _text(T("This assistant was approved as a guest: it may only look.",
                           "Ten asystent został zatwierdzony jako gość: może tylko oglądać."), True)
        try:
            out = getattr(self, "t_" + name)(**args)
        except TypeError as e:
            return _text(f"bad arguments: {e}", True)
        except Exception as e:  # noqa: BLE001 — LibError, TaskError, …: the model sees the message
            return _text(str(e), True)
        return _text(json.dumps(out, ensure_ascii=False, default=str))

    # ---- tools

    def t_list_folders(self):
        roots = self.app.library.visible_roots(True)
        return [{k: r.get(k) for k in ("index", "name", "path", "tags", "visible", "exclude", "exists", "profile",
                                       "profiles")} for r in roots]

    def t_browse(self, root, path=""):
        return self.app.library.browse(int(root), path)

    def t_search(self, query):
        return self.app.library.search(query, 50)

    def t_identify(self, name):
        return self.app.ident.guess(name)

    def t_file_info(self, root, path):
        return mp_media.probe(self.app.library.resolve(int(root), path))

    def t_disks(self):
        return {"disks": self.app.tasks.disks(), "trash": self.app.tasks.trash(), "trash_days": self.app.tasks.trash_days()}

    def t_list_tasks(self):
        return [{k: v for k, v in t.items() if k != "items"} | {"items": len(t["items"])} for t in self.app.tasks.list()[:20]]

    def t_get_plan(self, plan):
        return _compact(self.app.tasks.plan_status(plan))

    def t_set_folder(self, root, **b):
        self.app.library.update_root(int(root), {k: v for k, v in b.items() if k in ("name", "tags", "visible", "exclude")})
        return {"ok": True}

    def t_set_title_tags(self, imdb, tags):
        return self.app.tasks.set_title_tags(imdb, tags)

    def t_set_profile(self, root, profile, path=""):
        self.app.tasks.set_profile(int(root), path, profile)
        return {"ok": True}

    def t_get_subtitle_settings(self):
        return mp_subflow.all_settings(self.app.cfg)

    def t_set_subtitle_settings(self, section, changes):
        return mp_subflow.set_settings(self.app, section, changes)

    def t_subtitle_status(self, root, path):
        return self.app.subflow.status(self.app.library.resolve(int(root), path))

    def t_prepare_subtitles(self, root, path, force=False):
        return self.app.subflow.run(self.app.library.resolve(int(root), path), bool(force))

    def _wait(self, p):
        end = time.time() + PLAN_WAIT
        while p["state"] == "running" and time.time() < end:
            time.sleep(0.5)
            p = self.app.tasks.plan_status(p["id"])
        return _compact(p)

    def t_plan_free_space(self, root, need_gb=None):
        return self._wait(self.app.tasks.plan_free(int(root), int(need_gb * (1 << 30)) if need_gb else None))

    def t_plan_move(self, items):
        return self._wait(self.app.tasks.plan_move(items))

    def t_plan_tidy(self):
        return self._wait(self.app.tasks.plan_tidy(self.app.ident))

    def t_plan_shrink(self, root, path=""):
        return self._wait(self.app.tasks.plan_shrink(int(root), path))

    def t_plan_duplicates(self):
        return self._wait(self.app.tasks.plan_dupes())

    def t_plan_delete(self, items):
        return self._wait(self.app.tasks.plan_trash(items))

    def t_start_task(self, plan, pick=None):
        t = self.app.tasks.create(plan, None if pick is None else {int(x) for x in pick})
        return {k: v for k, v in t.items() if k != "items"}

    def t_cancel_task(self, task):
        t = self.app.tasks.cancel(task)
        return {k: v for k, v in t.items() if k != "items"}


def _text(s, error=False):
    return {"content": [{"type": "text", "text": s}], "isError": error}


def _compact(p):
    """A plan for the model: items with their index and the fields that matter, sizes in MB."""
    p = dict(p)
    r = p.get("result")
    if r:
        keep = ("name", "path", "root_name", "to_name", "to_sub", "tag", "unsure", "guess", "profile", "codec", "height",
                "to_height", "torrent", "reason")
        mb = lambda v: round(v / 1048576) if isinstance(v, (int, float)) else v  # noqa: E731
        r = dict(r)
        r["items"] = [dict({k: i[k] for k in keep if k in i}, index=n, size_mb=mb(i.get("size")),
                           **({"est_mb": mb(i["est"])} if "est" in i else {})) for n, i in enumerate(r.get("items") or [])]
        r["skipped"] = [dict({k: i[k] for k in keep if k in i}, size_mb=mb(i.get("size"))) for i in r.get("skipped") or []]
        for k in ("bytes", "est", "saved", "need", "free_before", "free_after", "total", "wasted"):
            if k in r:
                r[k + "_mb"] = mb(r.pop(k))
        p["result"] = r
    return p
