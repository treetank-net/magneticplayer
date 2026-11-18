"""The agent updates itself from GitHub releases, like the phone app (mp_appupdate.py).

Mode in config.json `agent_updates`: "auto" (default) or "manual" (a phone or the web page starts it,
`POST /api/agent/update`). The check runs 90 s after start and then every 6 hours; it only looks at the small signed
manifest (`agent.json` + `agent.json.sig`), so "manual" still shows that a newer agent exists.

Trust: the manifest is signed by the release job (ECDSA P-256, key in CI only); the public key is below (the same as
phone/tools/agent-signing.pub). The manifest carries the sha256 of `agent.tgz` and `agent_version`. Only a strictly
higher `agent_version` than the running one is installed, so a hand-deployed development agent is never replaced by an
older release and nobody can slip in an old bundle.

Applying: the bundle is unpacked into `<agent dir>/update/`, and when the computer is quiet (no stream from the
library in the last 5 minutes, no conversion, no file task) `self-update.sh` from the new bundle runs in its own
systemd unit (it survives the agent restart): it backs up the current files to `prev/`, runs `install.sh` in update
mode (files, Python packages, restart) and waits for `/api/ping` with the new version; otherwise it puts `prev/` back
and restarts the old agent. The result lands in `update-result.json`, which the agent reports after it starts.

Linux (systemd --user) only for now; on Windows the status says "unsupported".
"""
import base64
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tarfile
import threading
import time
import urllib.request

BASE = "https://github.com/treetank-net/magneticplayer/releases/latest/download"
PUBKEY = """
MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEny3WIOoUhPssZtMSG4CSGRTOrnTL
FCv3cJCMH7fdfPN3zPo908ZOz8//8XJeUya8akroN+iLaHhT6WFROpW4AA==
"""  # phone/tools/agent-signing.pub
# tests only (a fake release served in the LAN, signed with a throwaway key): environment of the service, never the API
BASE = os.environ.get("MP_UPDATE_BASE") or BASE
PUBKEY = os.environ.get("MP_UPDATE_PUBKEY") or PUBKEY
FIRST_DELAY, INTERVAL, IDLE_POLL = int(os.environ.get("MP_UPDATE_FIRST_DELAY") or 90), 6 * 3600, 60
MODES = ("auto", "manual")
BUNDLE_NAME = re.compile(r"server/(?:[A-Za-z0-9_.-]+\.(?:py|sh|ps1)|web/[A-Za-z0-9_.-]+)")

# --- ECDSA P-256 verification (no dependencies; only public data, so constant time doesn't matter) ---
_P = 0xffffffff00000001000000000000000000000000ffffffffffffffffffffffff
_A = _P - 3
_N = 0xffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551
_G = (0x6b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296,
      0x4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5)
_P256_OID = bytes.fromhex("06082a8648ce3d030107")


def _add(p, q):
    if p is None:
        return q
    if q is None:
        return p
    if p[0] == q[0]:
        if (p[1] + q[1]) % _P == 0:
            return None
        lam = (3 * p[0] * p[0] + _A) * pow(2 * p[1], -1, _P) % _P
    else:
        lam = (q[1] - p[1]) * pow(q[0] - p[0], -1, _P) % _P
    x = (lam * lam - p[0] - q[0]) % _P
    return x, (lam * (p[0] - x) - p[1]) % _P


def _mul(k, p):
    r = None
    while k:
        if k & 1:
            r = _add(r, p)
        p = _add(p, p)
        k >>= 1
    return r


def _der_ints(sig):
    """(r, s) from a DER ECDSA signature (SEQUENCE of two INTEGERs), as openssl dgst -sign writes it."""
    if len(sig) < 8 or sig[0] != 0x30:
        raise ValueError("signature is not DER")
    i, out = 2 if sig[1] < 0x80 else 2 + (sig[1] & 0x7f), []
    for _ in range(2):
        if sig[i] != 0x02:
            raise ValueError("signature is not DER")
        n = sig[i + 1]
        out.append(int.from_bytes(sig[i + 2:i + 2 + n], "big"))
        i += 2 + n
    return out


def public_point(pem_b64=PUBKEY):
    der = base64.b64decode("".join(pem_b64.split()))
    if _P256_OID not in der or der[-65] != 4:
        raise ValueError("not an uncompressed P-256 key")
    return int.from_bytes(der[-64:-32], "big"), int.from_bytes(der[-32:], "big")


def verify(data, sig, point=None):
    """True when `sig` (DER) is a valid ECDSA P-256 / SHA-256 signature of `data`."""
    try:
        r, s = _der_ints(sig)
    except (ValueError, IndexError):
        return False
    if not (0 < r < _N and 0 < s < _N):
        return False
    q = point or public_point()
    z = int.from_bytes(hashlib.sha256(data).digest(), "big")
    w = pow(s, -1, _N)
    x = _add(_mul(z * w % _N, _G), _mul(r * w % _N, q))
    return x is not None and x[0] % _N == r


def vtuple(v):
    return tuple(int(x) for x in re.findall(r"\d+", v or "0"))


class AgentUpdates:
    def __init__(self, cfg, save_cfg, agent_dir, version, port, busy, log):
        self.cfg, self.save_cfg, self.dir, self.version, self.port = cfg, save_cfg, agent_dir, version, port
        self.busy, self.log = busy, log  # busy(): reason (str) or None
        self.last_check, self.last_error, self.available, self.state = None, None, None, "idle"
        self.waiting_for = None
        self.requested = False  # "update now" in manual mode
        self.result = self._read_result()
        self._wake = threading.Event()
        self._lock = threading.Lock()

    @staticmethod
    def supported():
        return sys.platform.startswith("linux") and shutil.which("systemd-run") is not None

    def mode(self):
        m = self.cfg.get("agent_updates", "auto")
        return m if m in MODES else "auto"

    def _read_result(self):
        p = os.path.join(self.dir, "update-result.json")
        try:
            with open(p) as f:
                r = json.load(f)
        except (OSError, ValueError):
            return None
        if r.get("ok") and r.get("version") == self.version:
            self.log(f"agent updated to {self.version}")
        elif not r.get("ok") and not r.get("reported"):
            self.log(f"agent update to {r.get('version')} failed, back on {self.version}: {r.get('error')}")
        if not r.get("reported"):
            r["reported"] = True
            try:
                with open(p, "w") as f:
                    json.dump(r, f)
            except OSError:
                pass
        return r

    def status(self):
        return {"mode": self.mode(), "supported": self.supported(), "version": self.version,
                "available": self.available, "state": self.state, "waiting_for": self.waiting_for,
                "last_check": self.last_check, "last_error": self.last_error, "last_result": self.result}

    def set_mode(self, mode):
        if mode not in MODES:
            raise ValueError(mode)
        self.cfg["agent_updates"] = mode
        self.save_cfg()
        if mode == "auto":
            self._wake.set()
        return self.status()

    def update_now(self):
        """Check right away and install when there is something newer (still waits for a quiet moment)."""
        if not self.supported():
            raise RuntimeError("unsupported")
        self.requested = True
        self._wake.set()
        return self.status()

    def start(self):
        if self.supported():
            threading.Thread(target=self._loop, daemon=True).start()

    def _loop(self):
        self._wake.wait(FIRST_DELAY)
        next_check = 0
        while True:
            self._wake.clear()
            now = time.time()
            if now >= next_check or self.requested:
                try:
                    self.check()
                    self.last_error = None
                except Exception as e:  # network, GitHub, bad signature: retried next time
                    self.last_error = str(e)[:300]
                    self.log(f"agent update check: {e}")
                self.last_check, next_check = time.time(), time.time() + INTERVAL
            if self.available and self.state != "failed" and (self.mode() == "auto" or self.requested):
                try:
                    if self._apply():
                        return  # the updater restarts us
                except Exception as e:
                    self.last_error, self.state, self.requested = str(e)[:300], "error", False
                    self.log(f"agent update: {e}")
            self._wake.wait(IDLE_POLL if self.state == "waiting" else
                            max(60, next_check - time.time()))

    @staticmethod
    def _get(url, timeout=60):
        req = urllib.request.Request(url, headers={"User-Agent": "magneticplayer-agent"})
        return urllib.request.urlopen(req, timeout=timeout)

    def manifest(self):
        with self._get(f"{BASE}/agent.json", 30) as r:
            raw = r.read(1 << 16)
        with self._get(f"{BASE}/agent.json.sig", 30) as r:
            sig = r.read(1 << 10)
        if not verify(raw, sig):
            raise ValueError("agent.json: bad signature")
        return json.loads(raw)

    def check(self):
        with self._lock:
            m = self.manifest()
            newer = vtuple(m.get("agent_version")) > vtuple(self.version)
            self.available = m["agent_version"] if newer else None
            self._manifest = m if newer else None
            if not newer:
                self.requested = False
                self.state = "idle"
            elif self.result and not self.result.get("ok") and self.result.get("version") == m["agent_version"] \
                    and not self.requested:
                self.state = "failed"  # this version was already tried and rolled back: only "update now" retries it
            elif self.state == "failed":
                self.state = "idle"
            return self.available

    def _stage(self, m):
        """Downloads and unpacks the bundle into <dir>/update/<version>; returns that directory."""
        root = os.path.join(self.dir, "update")
        dest = os.path.join(root, m["agent_version"])
        if os.path.exists(os.path.join(dest, ".ok")):
            return dest
        shutil.rmtree(root, ignore_errors=True)
        os.makedirs(dest)
        tgz = os.path.join(root, "agent.tgz")
        h = hashlib.sha256()
        with self._get(f"{BASE}/agent.tgz", 120) as r, open(tgz, "wb") as f:
            while chunk := r.read(1 << 16):
                h.update(chunk)
                f.write(chunk)
        if h.hexdigest() != m["sha256"] or os.path.getsize(tgz) != m["size"]:
            raise ValueError("agent.tgz doesn't match the signed manifest")
        with tarfile.open(tgz) as t:
            for e in t.getmembers():
                if not e.isfile() or not BUNDLE_NAME.fullmatch(e.name):
                    raise ValueError(f"unexpected entry in agent.tgz: {e.name}")
            for e in t.getmembers():
                out = os.path.join(dest, e.name)
                os.makedirs(os.path.dirname(out), exist_ok=True)
                with t.extractfile(e) as src, open(out, "wb") as f:
                    shutil.copyfileobj(src, f)
                os.chmod(out, 0o755 if e.name.endswith(".sh") else 0o644)
        os.remove(tgz)
        open(os.path.join(dest, ".ok"), "w").close()
        return dest

    def _apply(self):
        with self._lock:
            m = getattr(self, "_manifest", None)
            if not m:
                return False
            self.state = "downloading"
            stage = self._stage(m)
            why = self.busy()
            if why:
                self.state, self.waiting_for = "waiting", why
                return False
            self.state, self.waiting_for = "installing", None
            script = os.path.join(stage, "server", "self-update.sh")
            self.log(f"agent update {self.version} -> {m['agent_version']}: starting {script}")
            subprocess.run(["systemd-run", "--user", "--collect", "--quiet",
                            f"--unit=magneticplayer-update-{int(time.time())}",
                            "/bin/bash", script, os.path.join(stage, "server"), self.dir, str(self.port),
                            m["agent_version"]], check=True, timeout=30)
            return True
