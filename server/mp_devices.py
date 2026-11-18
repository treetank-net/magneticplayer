"""Paired devices, pairing codes and single-file tickets.

- Each device has its own token. The file stores only the SHA-256 hash of the token, so a leaked file grants no access.
- Pairing code: single-use, valid for PAIR_TTL s. It is exchanged for a token (`POST /api/pair`). Codes are created
  by an already paired device or by someone with access to the computer (`magneticplayer_agent.py --pair`).
- Role: "owner" (household member, everything) or "guest" (watching only: no settings, torrents or suspending; the
  list is magneticplayer_agent.GUEST_ALLOWED). A guest may have an expiry (`expires`), after which the token stops working.
- The old shared token from config.json keeps working as the "legacy" device until someone disables it.
- Ticket: an HMAC signature for a single file (MediaRef key) with an expiry time. A screen (mpv on another computer)
  gets a URL with a ticket instead of a token, so it does not have to be paired with the file server.

The devices.json file next to config.json is also read by CLI commands (--pair, --devices, --revoke) in a separate
process, so the agent checks the mtime and reloads it after a change.
"""
import hashlib
import hmac
import json
import os
import secrets
import threading
import time

from mp_i18n import T

PAIR_TTL = 600
GUEST_CODE_TTL = 24 * 3600  # a guest code is sent via a messenger, so it lives longer (still works once)
ROLES = ("owner", "guest")
TICKET_TTL = 12 * 3600
CODE_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"  # without 0/O, 1/I


def _hash(token):
    return hashlib.sha256(token.encode()).hexdigest()


def normalize_code(code):
    return "".join(ch for ch in (code or "").upper() if ch in CODE_ALPHABET)


class Devices:
    def __init__(self, path, legacy_token):
        self.path = path
        self.legacy_token = legacy_token
        self.lock = threading.Lock()
        self.data = None
        self.mtime = None
        self.dirty_seen = 0.0
        self.fails = []  # times of failed pairing attempts (protection against guessing)
        self._load()

    # ---- file

    def _load(self):
        try:
            st = os.stat(self.path)
            if self.data is not None and st.st_mtime == self.mtime:
                return
            with open(self.path) as f:
                self.data = json.load(f)
            self.mtime = st.st_mtime
        except FileNotFoundError:
            if self.data is None:
                self.data = {}
        except (OSError, ValueError):
            if self.data is None:
                self.data = {}
        self.data.setdefault("devices", [])
        self.data.setdefault("codes", {})
        self.data.setdefault("legacy_token", True)
        if not self.data.get("ticket_secret"):
            self.data["ticket_secret"] = secrets.token_hex(32)
            self._save()

    def _save(self):
        os.makedirs(os.path.dirname(self.path), exist_ok=True)
        tmp = self.path + ".tmp"
        with open(tmp, "w") as f:
            json.dump(self.data, f, indent=2, ensure_ascii=False)
        os.chmod(tmp, 0o600)
        os.replace(tmp, self.path)
        self.mtime = os.stat(self.path).st_mtime

    # ---- authentication

    def check(self, token):
        """Device id for a token, or None. Saves "last seen" at most once a minute."""
        if not token:
            return None
        with self.lock:
            self._load()
            if self.data["legacy_token"] and self.legacy_token and hmac.compare_digest(token, self.legacy_token):
                self.data["legacy_seen"] = time.time()
                self._maybe_save()
                return "legacy"
            h = _hash(token)
            for d in self.data["devices"]:
                if hmac.compare_digest(h, d["token_sha256"]):
                    if d.get("expires") and d["expires"] < time.time():
                        return None
                    d["last_seen"] = time.time()
                    self._maybe_save()
                    return d["id"]
        return None

    def role(self, dev_id):
        """Device role (after `check`); "legacy" and old entries without a role are household members."""
        if dev_id in (None, "legacy"):
            return "owner" if dev_id else None
        with self.lock:
            for d in self.data["devices"]:
                if d["id"] == dev_id:
                    return d.get("role", "owner")
        return None

    def _maybe_save(self):
        if time.time() - self.dirty_seen > 60:
            self.dirty_seen = time.time()
            self._save()

    # ---- pairing

    @staticmethod
    def _exp(v):
        return v if isinstance(v, (int, float)) else v.get("exp", 0)  # old format: just the expiry time

    def new_code(self, role="owner", days=None):
        """Single-use code. Guest: the code is valid for a day, and `days` (>0) limits the validity of the access itself."""
        if role not in ROLES:
            raise ValueError(T("unknown role", "nieznana rola"))
        ttl = GUEST_CODE_TTL if role == "guest" else PAIR_TTL
        with self.lock:
            self._load()
            now = time.time()
            self.data["codes"] = {c: v for c, v in self.data["codes"].items() if self._exp(v) > now}
            code = "".join(secrets.choice(CODE_ALPHABET) for _ in range(6))
            entry = {"exp": now + ttl, "role": role}
            if role == "guest" and days:
                entry["days"] = float(days)
            self.data["codes"][code] = entry
            self._save()
            return code, ttl

    def pair(self, code, name):
        """Code → new device token (returned once, afterwards only its hash is known)."""
        now = time.time()
        with self.lock:
            self.fails = [t for t in self.fails if now - t < 60]
            if len(self.fails) >= 10:
                return None, T("too many attempts, wait a minute", "za dużo prób, odczekaj minutę")
            self._load()
            code = normalize_code(code)
            entry = self.data["codes"].pop(code, None)
            exp = self._exp(entry) if entry is not None else None
            if not exp or exp < now:
                self.fails.append(now)
                self._save()
                return None, T("invalid or expired pairing code", "zły albo przeterminowany kod parowania")
            token = secrets.token_urlsafe(24)
            role = entry.get("role", "owner") if isinstance(entry, dict) else "owner"
            dev = {"id": secrets.token_hex(6), "name": (name or T("device", "urządzenie")).strip()[:60],
                   "token_sha256": _hash(token), "created": now, "last_seen": now, "role": role}
            if isinstance(entry, dict) and entry.get("days"):
                dev["expires"] = now + entry["days"] * 86400
            self.data["devices"].append(dev)
            self._save()
            return {"token": token, "device_id": dev["id"], "role": role, "expires": dev.get("expires")}, None

    def list(self, current=None):
        with self.lock:
            self._load()
            out = [{"id": d["id"], "name": d["name"], "created": d.get("created"), "last_seen": d.get("last_seen"),
                    "current": d["id"] == current, "role": d.get("role", "owner"), "expires": d.get("expires")}
                   for d in self.data["devices"]]
            if self.data["legacy_token"]:
                out.append({"id": "legacy", "name": T("Old shared token", "Stary wspólny token"), "legacy": True, "role": "owner",
                            "last_seen": self.data.get("legacy_seen"), "current": current == "legacy"})
            return out

    def revoke(self, dev_id):
        with self.lock:
            self._load()
            if dev_id == "legacy":
                found = self.data["legacy_token"]
                self.data["legacy_token"] = False
            else:
                before = len(self.data["devices"])
                self.data["devices"] = [d for d in self.data["devices"] if d["id"] != dev_id]
                found = len(self.data["devices"]) != before
            self._save()
            return found

    # ---- tickets

    def _sig(self, scope, exp):
        return hmac.new(self.data["ticket_secret"].encode(), f"{scope}|{exp}".encode(), hashlib.sha256).hexdigest()[:32]

    def ticket(self, scope, ttl=TICKET_TTL):
        with self.lock:
            self._load()
            exp = int(time.time() + ttl)
            return f"{exp}.{self._sig(scope, exp)}", exp

    def check_ticket(self, ticket, scope):
        try:
            exp_s, sig = (ticket or "").split(".", 1)
            exp = int(exp_s)
        except ValueError:
            return False
        if exp < time.time():
            return False
        with self.lock:
            self._load()
            return hmac.compare_digest(sig, self._sig(scope, exp))
