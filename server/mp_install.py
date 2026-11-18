"""Instalacja agenta na kolejnym komputerze z już działającego serwera (kod dołączenia).

Przebieg:
1. Sparowany telefon: `POST /api/install/code` → jednorazowy kod i polecenie
   `curl -fsSL <ten serwer>/i/<kod> | bash` (instalacja lekka; `?full=1` = pełna z hasłem administratora).
2. Nowy komputer: `GET /i/<kod>` daje skrypt, a ten pobiera `GET /i/<kod>/bundle.tar.gz` (agent z tego serwera:
   *.py, web/, skrypty instalacyjne; bez venv i APK) i uruchamia `install.sh` z `JOIN=<adres>/i/<kod>`.
3. Nowy agent po instalacji (`magneticplayer_agent.py --join`) zakłada własny kod parowania i zgłasza się:
   `POST /api/install/join {code, id, name, port, pair_code}`. Kod dołączenia jest wtedy zużyty.
4. Telefon pyta `GET /api/install/status?code=` i dostaje adres, id i kod parowania nowego serwera, więc paruje się
   sam, bez przepisywania kodu.

Kody żyją w pamięci (restart agenta je kasuje) przez TTL. Bez tokenu działają tylko `/i/<kod>…` i `join`,
a każdy z nich wymaga ważnego kodu; złe próby są limitowane jak przy parowaniu.
"""
import io
import os
import secrets
import tarfile
import threading
import time

from mp_devices import CODE_ALPHABET, normalize_code
from mp_i18n import T

TTL = 30 * 60
HERE = os.path.dirname(os.path.abspath(__file__))
BUNDLE_FILES = ("install.sh", "install-root.sh", "uninstall.sh", "join.sh")
BUNDLE_OPTIONAL = ("install.ps1", "uninstall.ps1", "join.ps1")  # Windows (instalacja lekka)


def template(name):
    """Skrypt `curl … | bash` (join.sh) albo `irm … | iex` (join.ps1); te same pliki serwuje telefon (InstallShare.kt)."""
    with open(os.path.join(HERE, name), encoding="utf-8-sig") as f:
        return f.read()


class InstallError(Exception):
    def __init__(self, msg, status=400):
        super().__init__(msg)
        self.status = status


class Installs:
    def __init__(self, log):
        self.log = log
        self.lock = threading.Lock()
        self.codes = {}  # kod → {exp, by, state, server, downloads}
        self.fails = []

    def _gc(self):
        now = time.time()
        self.codes = {c: v for c, v in self.codes.items() if v["exp"] > now}

    def available(self):
        """Czy da się stąd instalować: obok agenta muszą leżeć skrypty instalacyjne (install.sh je kopiuje)."""
        return all(os.path.isfile(os.path.join(HERE, f)) for f in BUNDLE_FILES)

    def new_code(self, device):
        if not self.available():
            raise InstallError(T("install scripts are missing next to the agent — run install.sh again",
                                 "brak skryptów instalacyjnych obok agenta — uruchom ponownie install.sh"), 409)
        with self.lock:
            self._gc()
            code = "".join(secrets.choice(CODE_ALPHABET) for _ in range(6))
            self.codes[code] = {"exp": time.time() + TTL, "by": device, "state": "waiting", "server": None,
                                "downloads": 0}
            return code, TTL

    def _get(self, code, *, consume=False):
        now = time.time()
        with self.lock:
            self.fails = [t for t in self.fails if now - t < 60]
            if len(self.fails) >= 10:
                raise InstallError(T("too many attempts, wait a minute", "za dużo prób, odczekaj minutę"), 429)
            self._gc()
            c = self.codes.get(normalize_code(code))
            if not c or c["state"] == "joined":
                self.fails.append(now)
                raise InstallError(T("invalid, used or expired install code", "zły, zużyty albo przeterminowany kod instalacji"), 403)
            return c

    # ---- nowy komputer (bez tokenu)

    def script(self, code, base, full):
        c = self._get(code)
        c["state"] = "downloading" if c["state"] == "waiting" else c["state"]
        url = f"{base}/i/{normalize_code(code)}"
        lite = "0" if full else "${LITE:-1}"
        return template("join.sh").replace("@URL@", url).replace("@LITE@", lite)

    def script_ps(self, code, base):
        """Windows: `irm <url>/ps | iex` (zawsze instalacja lekka)."""
        self.script(code, base, False)  # ten sam stan i kontrola kodu
        if not all(os.path.isfile(os.path.join(HERE, f)) for f in ("install.ps1", "join.ps1")):
            raise InstallError(T("this server has no Windows installer (install.ps1) — update it",
                                     "ten serwer nie ma instalatora dla Windows (install.ps1) — zaktualizuj go"), 409)
        return template("join.ps1").replace("@URL@", f"{base}/i/{normalize_code(code)}")

    def bundle(self, code):
        c = self._get(code)
        c["downloads"] += 1
        buf = io.BytesIO()
        with tarfile.open(fileobj=buf, mode="w:gz") as tar:
            for name in sorted(os.listdir(HERE)):
                full = os.path.join(HERE, name)
                if (name.endswith(".py") and os.path.isfile(full)) or name in BUNDLE_FILES or name in BUNDLE_OPTIONAL:
                    tar.add(full, arcname=f"server/{name}")
            web = os.path.join(HERE, "web")
            if os.path.isdir(web):
                for name in sorted(os.listdir(web)):
                    if os.path.isfile(os.path.join(web, name)):
                        tar.add(os.path.join(web, name), arcname=f"server/web/{name}")
        return buf.getvalue()

    def join(self, body, host):
        c = self._get(body.get("code"))
        pair = normalize_code(body.get("pair_code"))
        if len(pair) != 6 or not body.get("id"):
            raise InstallError(T("missing pairing code or new server id", "brak kodu parowania albo id nowego serwera"))
        with self.lock:
            c["state"] = "joined"
            c["server"] = {"host": host, "port": int(body.get("port") or 8765), "id": str(body["id"])[:40],
                           "name": str(body.get("name") or host)[:60], "pair_code": pair,
                           "version": str(body.get("version") or "")[:20], "at": time.time()}
            c["exp"] = max(c["exp"], time.time() + 600)  # telefon ma 10 min (ważność kodu parowania) na odebranie
        self.log(f"nowy serwer dołączył: {c['server']['name']} ({host})")
        return {"ok": True}

    # ---- telefon (z tokenem)

    def status(self, code, device):
        with self.lock:
            self._gc()
            c = self.codes.get(normalize_code(code))
            if not c or c["by"] != device:
                raise InstallError(T("no such install code", "nie ma takiego kodu instalacji"), 404)
            return {"state": c["state"], "server": c["server"], "downloads": c["downloads"],
                    "expires_in": int(c["exp"] - time.time())}


