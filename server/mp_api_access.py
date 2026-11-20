"""Routes: pairing, invitations and access requests, devices, installing the agent on another computer (join codes),
and the OAuth/MCP endpoints (handled by Handler.mcp_route). Each function is a route of mp_routes.ROUTES: (req = the
Handler, a = the App, method, p = path, q = query lists, qd = query values, path parameters)."""
from mp_core import ApiError, log
from mp_i18n import T


# installation on a new computer: no token, but with a valid join code (mp_install.py)
def install_script(req, a, q, code, **_):
    full = (q.get("full") or ["0"])[0] not in ("0", "")
    return req.send_bytes(a.installs.script(code, req.base_url(), full).encode(),
                          "text/x-shellscript; charset=utf-8")


def install_bundle(req, a, code, **_):
    return req.send_bytes(a.installs.bundle(code), "application/gzip")


def install_ps(req, a, code, **_):
    return req.send_bytes(a.installs.script_ps(code, req.base_url()).encode(), "text/plain; charset=utf-8")


def install_join(req, a, **_):
    return req.send_json(a.installs.join(req.body(), req.client_address[0]))


def pair(req, a, **_):
    # no token: a one-time code (from another device's app or --pair on the computer) → device token
    b = req.body()
    res, err = a.devices.pair(b.get("code"), b.get("name"))
    if err:
        raise ApiError(err, 403)
    log("device paired:", (b.get("name") or "?")[:60])
    return req.send_json({**res, "id": a.cfg["id"], "name": a.name()})


# access request: no token, home network only; after a household member accepts it returns a pairing code
def access_request(req, a, **_):
    return req.send_json(a.access.request(req.body().get("name"), req.client_address[0]))


def access_poll(req, a, q, rid, **_):
    return req.send_json(a.access.poll(rid, (q.get("secret") or [""])[0]))


# ---- MCP for an AI assistant (mp_mcp.py): OAuth with approval on the phone, then the device token
def mcp(req, a, method, p, q, **_):
    return req.mcp_route(method, p, {k: v[0] for k, v in q.items()}, q)


def access_requests(req, a, **_):
    return req.send_json({"requests": a.access.pending()})


def access_decide(req, a, rid, **_):
    return req.send_json(a.access.decide(rid, req.body(), a.devices, req.device))


def pair_code(req, a, **_):
    b = req.body()
    try:  # {"role": "guest", "days": 7}: a guest invitation (code valid for a day, access for `days`)
        code, ttl = a.devices.new_code(str(b.get("role") or "owner"), float(b.get("days") or 0) or None)
    except ValueError as e:
        raise ApiError(str(e), 400)
    host, _, port = (req.headers.get("Host") or "").rpartition(":")
    return req.send_json({"code": code, "expires_in": ttl, "host": host or None,
                           "port": int(port) if port.isdigit() else a.cfg["port"],
                           "id": a.cfg["id"], "name": a.name()})


def install_code(req, a, **_):
    code, ttl = a.installs.new_code(req.device)
    url = f"{req.base_url()}/i/{code}"
    return req.send_json({"code": code, "expires_in": ttl, "url": url,
                           "command": f"curl -fsSL {url} | bash",
                           "command_full": f"curl -fsSL '{url}?full=1' | bash",
                           "command_windows": f"irm {url}/ps | iex"})


def install_status(req, a, qd, **_):
    return req.send_json(a.installs.status(qd.get("code"), req.device))


def devices(req, a, **_):
    return req.send_json(a.devices.list(req.device))


def devices_revoke(req, a, **_):
    dev = str(req.body().get("id") or "")
    if not a.devices.revoke(dev):
        raise ApiError(T("no such device", "nie ma takiego urządzenia"), 404)
    log("access revoked for device", dev)
    return req.send_json({"ok": True})
