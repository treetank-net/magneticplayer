"""The phone app from GitHub releases: the agent downloads a newer app itself and serves it at /app/MagneticPlayer.apk.

Mode in config.json `app_updates`: "auto" (default) or "manual" (only phone/tools/publish.sh). A phone changes it
(`POST /api/config/app_updates`). The check runs a minute after start, then every 6 hours, and right away when "auto"
is switched on. It never downgrades: it only serves an app with a higher version_code than the current one (including
the one from publish.sh).

The APK must carry the treetank certificate (SHA-256 fingerprint from the APK v2/v3 signing block). We do not verify
the signature cryptographically here: Android does that on install and only accepts an update signed with the same key.
"""
import hashlib
import json
import os
import struct
import threading
import time
import urllib.request

BASE = "https://github.com/treetank-net/magneticplayer/releases/latest/download"
CERT_SHA256 = "05f72b11239022e9256185257b8926693e39e445c89c96b926613cd7de6e39ce"  # treetank (same as release-ci.sh)
FIRST_DELAY, INTERVAL = 60, 6 * 3600
MODES = ("auto", "manual")


def apk_cert_sha256(path):
    """SHA-256 fingerprint of the first signer's certificate from the APK signing block (scheme v2 or v3); None if absent."""
    with open(path, "rb") as f:
        f.seek(0, 2)
        size = f.tell()
        tail_len = min(size, 65557)
        f.seek(size - tail_len)
        tail = f.read()
        eocd = tail.rfind(b"PK\x05\x06")
        if eocd < 0:
            return None
        cd_off = struct.unpack_from("<I", tail, eocd + 16)[0]
        if cd_off < 32:
            return None
        f.seek(cd_off - 24)
        footer = f.read(24)
        if footer[8:] != b"APK Sig Block 42":
            return None
        block_size = struct.unpack_from("<Q", footer, 0)[0]
        start = cd_off - block_size - 8
        if start < 0:
            return None
        f.seek(start + 8)
        pairs = f.read(block_size - 24)
    found = {}
    i = 0
    while i + 12 <= len(pairs):
        n, pid = struct.unpack_from("<QI", pairs, i)
        found[pid] = pairs[i + 12:i + 8 + n]
        i += 8 + n

    def lp(buf, o):  # value prefixed with a uint32 length
        n = struct.unpack_from("<I", buf, o)[0]
        return buf[o + 4:o + 4 + n], o + 4 + n

    for pid in (0x7109871A, 0xF05368C0):  # v2, v3
        if pid not in found:
            continue
        signers, _ = lp(found[pid], 0)
        signer, _ = lp(signers, 0)
        signed_data, _ = lp(signer, 0)
        _digests, o = lp(signed_data, 0)
        certs, _ = lp(signed_data, o)
        cert, _ = lp(certs, 0)
        return hashlib.sha256(cert).hexdigest()
    return None


class AppUpdates:
    def __init__(self, cfg, save_cfg, apk_dir, current, log):
        self.cfg, self.save_cfg, self.apk_dir, self.current, self.log = cfg, save_cfg, apk_dir, current, log
        self.last_check, self.last_error = None, None
        self._wake = threading.Event()
        self._lock = threading.Lock()

    def mode(self):
        m = self.cfg.get("app_updates", "auto")
        return m if m in MODES else "auto"

    def status(self):
        return {"mode": self.mode(), "last_check": self.last_check, "last_error": self.last_error,
                "published": self.current()}

    def set_mode(self, mode):
        if mode not in MODES:
            raise ValueError(mode)
        self.cfg["app_updates"] = mode
        self.save_cfg()
        if mode == "auto":
            self._wake.set()
        return self.status()

    def start(self):
        threading.Thread(target=self._loop, daemon=True).start()

    def _loop(self):
        self._wake.wait(FIRST_DELAY)
        while True:
            self._wake.clear()
            if self.mode() == "auto":
                try:
                    self.check()
                    self.last_error = None
                except Exception as e:  # network, GitHub, bad file: we will retry next time
                    self.last_error = str(e)
                    self.log(f"app update from GitHub: {e}")
                self.last_check = time.time()
            # wall-clock deadline in 15 min steps: Event.wait's monotonic clock stops while the computer sleeps
            due = time.time() + INTERVAL
            while time.time() < due and not self._wake.wait(min(900, max(1, due - time.time()))):
                pass

    @staticmethod
    def _get(url, timeout=60):
        req = urllib.request.Request(url, headers={"User-Agent": "magneticplayer-agent"})
        return urllib.request.urlopen(req, timeout=timeout)

    def check(self):
        """Downloads a newer app from the latest release. Returns its version_name or None (nothing new)."""
        with self._lock:
            with self._get(f"{BASE}/version.json", 30) as r:
                v = json.loads(r.read())
            code = int(v.get("version_code") or 0)
            if code <= int(self.current().get("version_code") or 0):
                return None
            os.makedirs(self.apk_dir, exist_ok=True)
            apk = os.path.join(self.apk_dir, "MagneticPlayer.apk")
            part = apk + ".part"
            try:
                with self._get(f"{BASE}/MagneticPlayer.apk", 120) as r, open(part, "wb") as f:
                    while chunk := r.read(1 << 20):
                        f.write(chunk)
                cert = apk_cert_sha256(part)
                if cert != CERT_SHA256:
                    raise ValueError(f"APK with a foreign certificate ({cert})")
                vj = os.path.join(self.apk_dir, "version.json")
                with open(vj + ".new", "w") as f:
                    json.dump({"version_code": code, "version_name": v.get("version_name", "")}, f)
                # app first, version last (like publish.sh): a phone never sees the version before the complete file
                os.replace(part, apk)
                os.replace(vj + ".new", vj)
            finally:
                if os.path.exists(part):
                    os.remove(part)
            self.log(f"app {v.get('version_name')} from GitHub is now served to phones")
            return v.get("version_name")
