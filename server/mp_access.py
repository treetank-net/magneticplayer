"""Access requests: an unpaired device on the home network asks, a household member approves on the phone.

Flow:
1. New device (web page or app without a token): `POST /api/access/request {name}` → `{id, secret, expires_in}`.
   Only from a private (LAN) address, at most one pending request per address and a few at a time.
2. Household member (the app checks when it connects to the server): `GET /api/access/requests` → pending list,
   `POST /api/access/requests/<id> {action: approve|deny, role, days}`.
3. The new device polls `GET /api/access/request/<id>?secret=` and, once approved, receives an ordinary pairing code
   (with role and expiry) that it exchanges for a token via `POST /api/pair`. The code is handed out once.

Requests live in memory (an agent restart clears them). Nothing grants access without a household member's approval.
"""
import ipaddress
import secrets
import threading
import time

from mp_i18n import T

TTL = 5 * 60
MAX_PENDING = 5
NAME_MAX = 60


class AccessError(Exception):
    def __init__(self, msg, status=400):
        super().__init__(msg)
        self.status = status


def is_lan(ip):
    try:
        a = ipaddress.ip_address(ip)
    except ValueError:
        return False
    if a.version == 6 and a.ipv4_mapped:
        a = a.ipv4_mapped
    return a.is_private or a.is_loopback or a.is_link_local


class AccessRequests:
    def __init__(self, log):
        self.log = log
        self.lock = threading.Lock()
        self.reqs = {}  # id → {secret, name, ip, at, exp, state, code, role, by}
        self.recent = []  # request times (per-minute limit)

    def _gc(self):
        now = time.time()
        self.reqs = {k: v for k, v in self.reqs.items() if v["exp"] > now}

    # ---- new device (no token)

    def request(self, name, ip):
        if not is_lan(ip):
            raise AccessError(T("access requests only work on the home network", "prośba o dostęp działa tylko w sieci domowej"), 403)
        name = " ".join(str(name or "").split())[:NAME_MAX]
        if not name:
            raise AccessError(T("enter a device name (so a household member knows who is asking)",
                                "podaj nazwę urządzenia (żeby domownik wiedział, kto prosi)"))
        now = time.time()
        with self.lock:
            self._gc()
            self.recent = [t for t in self.recent if now - t < 60]
            if len(self.recent) >= 10:
                raise AccessError(T("too many requests, wait a minute", "za dużo próśb, odczekaj minutę"), 429)
            for k, v in list(self.reqs.items()):  # a repeated request from the same address replaces the previous one
                if v["ip"] == ip and v["state"] == "pending":
                    del self.reqs[k]
            if sum(v["state"] == "pending" for v in self.reqs.values()) >= MAX_PENDING:
                raise AccessError(T("too many pending requests, try again in a few minutes",
                                        "za dużo oczekujących próśb, spróbuj za kilka minut"), 429)
            self.recent.append(now)
            rid, secret = secrets.token_hex(8), secrets.token_urlsafe(16)
            self.reqs[rid] = {"secret": secret, "name": name, "ip": ip, "at": now, "exp": now + TTL,
                              "state": "pending", "code": None, "role": None}
        self.log(f"access request: {name} ({ip})")
        return {"id": rid, "secret": secret, "expires_in": TTL}

    def poll(self, rid, secret):
        with self.lock:
            self._gc()
            r = self.reqs.get(rid)
            if not r or not secrets.compare_digest(str(secret or ""), r["secret"]):
                raise AccessError(T("no such request (expired?)", "nie ma takiej prośby (wygasła?)"), 404)
            out = {"state": r["state"], "expires_in": int(r["exp"] - time.time())}
            if r["state"] == "approved" and r["code"]:
                out.update(code=r["code"], role=r["role"])
                r["code"] = None  # the code is handed out once
                r["state"] = "delivered"
            return out

    # ---- household member (with token)

    def pending(self):
        with self.lock:
            self._gc()
            now = time.time()
            return [{"id": k, "name": v["name"], "ip": v["ip"], "age": int(now - v["at"]),
                     "expires_in": int(v["exp"] - now)}
                    for k, v in sorted(self.reqs.items(), key=lambda kv: kv[1]["at"]) if v["state"] == "pending"]

    def decide(self, rid, body, devices, by):
        action = body.get("action")
        if action not in ("approve", "deny"):
            raise AccessError(T("action: approve or deny", "action: approve albo deny"))
        with self.lock:
            self._gc()
            r = self.reqs.get(rid)
            if not r or r["state"] != "pending":
                raise AccessError(T("the request has expired or someone already handled it", "prośba wygasła albo ktoś już ją rozpatrzył"), 409)
            if action == "deny":
                r["state"] = "denied"
                r["exp"] = min(r["exp"], time.time() + 60)
            else:
                role = str(body.get("role") or "guest")
                try:
                    days = float(body.get("days") or 0) or None
                    code, _ = devices.new_code(role, days if role == "guest" else None)
                except (TypeError, ValueError) as e:
                    raise AccessError(str(e))
                r.update(state="approved", code=code, role=role, by=by)
                r["exp"] = time.time() + 120  # the device polls every few seconds, so it will pick it up quickly
        self.log(f"access request: {r['name']} → {action}" + (f" ({r['role']})" if action == "approve" else ""))
        return {"ok": True}
