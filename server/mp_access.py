"""Prośba o dostęp: niesparowane urządzenie w sieci domowej prosi, domownik akceptuje na telefonie.

Przebieg:
1. Nowe urządzenie (strona albo apka bez tokenu): `POST /api/access/request {name}` → `{id, secret, expires_in}`.
   Tylko z adresu prywatnego (LAN), najwyżej jedna oczekująca prośba na adres i kilka naraz.
2. Domownik (apka sprawdza przy połączeniu z serwerem): `GET /api/access/requests` → lista oczekujących,
   `POST /api/access/requests/<id> {action: approve|deny, role, days}`.
3. Nowe urządzenie pyta `GET /api/access/request/<id>?secret=` i po akceptacji dostaje zwykły kod parowania
   (z rolą i terminem), który wymienia na token przez `POST /api/pair`. Kod oddajemy raz.

Prośby żyją w pamięci (restart agenta je kasuje). Bez akceptacji domownika nic nie daje dostępu.
"""
import ipaddress
import secrets
import threading
import time

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
        self.recent = []  # czasy próśb (limit na minutę)

    def _gc(self):
        now = time.time()
        self.reqs = {k: v for k, v in self.reqs.items() if v["exp"] > now}

    # ---- nowe urządzenie (bez tokenu)

    def request(self, name, ip):
        if not is_lan(ip):
            raise AccessError("prośba o dostęp działa tylko w sieci domowej", 403)
        name = " ".join(str(name or "").split())[:NAME_MAX]
        if not name:
            raise AccessError("podaj nazwę urządzenia (żeby domownik wiedział, kto prosi)")
        now = time.time()
        with self.lock:
            self._gc()
            self.recent = [t for t in self.recent if now - t < 60]
            if len(self.recent) >= 10:
                raise AccessError("za dużo próśb, odczekaj minutę", 429)
            for k, v in list(self.reqs.items()):  # ponowna prośba z tego samego adresu zastępuje poprzednią
                if v["ip"] == ip and v["state"] == "pending":
                    del self.reqs[k]
            if sum(v["state"] == "pending" for v in self.reqs.values()) >= MAX_PENDING:
                raise AccessError("za dużo oczekujących próśb, spróbuj za kilka minut", 429)
            self.recent.append(now)
            rid, secret = secrets.token_hex(8), secrets.token_urlsafe(16)
            self.reqs[rid] = {"secret": secret, "name": name, "ip": ip, "at": now, "exp": now + TTL,
                              "state": "pending", "code": None, "role": None}
        self.log(f"prośba o dostęp: {name} ({ip})")
        return {"id": rid, "secret": secret, "expires_in": TTL}

    def poll(self, rid, secret):
        with self.lock:
            self._gc()
            r = self.reqs.get(rid)
            if not r or not secrets.compare_digest(str(secret or ""), r["secret"]):
                raise AccessError("nie ma takiej prośby (wygasła?)", 404)
            out = {"state": r["state"], "expires_in": int(r["exp"] - time.time())}
            if r["state"] == "approved" and r["code"]:
                out.update(code=r["code"], role=r["role"])
                r["code"] = None  # kod oddajemy raz
                r["state"] = "delivered"
            return out

    # ---- domownik (z tokenem)

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
            raise AccessError("action: approve albo deny")
        with self.lock:
            self._gc()
            r = self.reqs.get(rid)
            if not r or r["state"] != "pending":
                raise AccessError("prośba wygasła albo ktoś już ją rozpatrzył", 409)
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
                r["exp"] = time.time() + 120  # urządzenie pyta co kilka sekund, więc odbierze szybko
        self.log(f"prośba o dostęp: {r['name']} → {action}" + (f" ({r['role']})" if action == "approve" else ""))
        return {"ok": True}
