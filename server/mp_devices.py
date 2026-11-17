"""Sparowane urządzenia, kody parowania i bilety na pojedynczy plik.

- Każde urządzenie ma własny token. Plik trzyma tylko skrót SHA-256 tokenu, więc wyciek pliku nie daje dostępu.
- Kod parowania: jednorazowy, ważny PAIR_TTL s. Wymienia się go na token (`POST /api/pair`). Kody tworzy już
  sparowane urządzenie albo ktoś z dostępem do komputera (`magneticplayer_agent.py --pair`).
- Rola: „owner” (domownik, wszystko) albo „guest” (gość: oglądanie, bez ustawień, torrentów, usypiania; lista
  w magneticplayer_agent.GUEST_ALLOWED). Gość może mieć termin ważności (`expires`), po nim token przestaje działać.
- Stary wspólny token z config.json działa dalej jako urządzenie „legacy”, dopóki ktoś go nie wyłączy.
- Bilet: podpis HMAC na jeden plik (klucz MediaRef) z czasem ważności. Ekran (mpv na innym komputerze) dostaje URL
  z biletem zamiast tokenu, więc nie musi być sparowany z serwerem plików.

Plik devices.json obok config.json czytają też polecenia CLI (--pair, --devices, --revoke) w osobnym procesie,
więc agent sprawdza mtime i wczytuje go ponownie po zmianie.
"""
import hashlib
import hmac
import json
import os
import secrets
import threading
import time

PAIR_TTL = 600
GUEST_CODE_TTL = 24 * 3600  # kod dla gościa idzie komunikatorem, więc żyje dłużej (dalej działa raz)
ROLES = ("owner", "guest")
TICKET_TTL = 12 * 3600
CODE_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"  # bez 0/O, 1/I


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
        self.fails = []  # czasy nieudanych prób parowania (ochrona przed zgadywaniem)
        self._load()

    # ---- plik

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

    # ---- uwierzytelnianie

    def check(self, token):
        """Id urządzenia dla tokenu albo None. Zapisuje „ostatnio widziane” najwyżej raz na minutę."""
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
        """Rola urządzenia (po `check`); „legacy” i stare wpisy bez roli to domownicy."""
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

    # ---- parowanie

    @staticmethod
    def _exp(v):
        return v if isinstance(v, (int, float)) else v.get("exp", 0)  # stary zapis: sam czas wygaśnięcia

    def new_code(self, role="owner", days=None):
        """Jednorazowy kod. Gość: kod ważny dobę, a `days` (>0) ogranicza ważność samego dostępu."""
        if role not in ROLES:
            raise ValueError("nieznana rola")
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
        """Kod → nowy token urządzenia (zwracany raz, potem znany tylko jego skrót)."""
        now = time.time()
        with self.lock:
            self.fails = [t for t in self.fails if now - t < 60]
            if len(self.fails) >= 10:
                return None, "za dużo prób, odczekaj minutę"
            self._load()
            code = normalize_code(code)
            entry = self.data["codes"].pop(code, None)
            exp = self._exp(entry) if entry is not None else None
            if not exp or exp < now:
                self.fails.append(now)
                self._save()
                return None, "zły albo przeterminowany kod parowania"
            token = secrets.token_urlsafe(24)
            role = entry.get("role", "owner") if isinstance(entry, dict) else "owner"
            dev = {"id": secrets.token_hex(6), "name": (name or "urządzenie").strip()[:60],
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
                out.append({"id": "legacy", "name": "Stary wspólny token", "legacy": True, "role": "owner",
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

    # ---- bilety

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
