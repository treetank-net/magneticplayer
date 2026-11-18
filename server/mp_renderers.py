"""Receivers (Chromecasts): who they are and what they can play.

Over mDNS a Chromecast reports only `md` ("Chromecast" for everything from the 1st generation to Google TV), so we
work out the model from `product_name` at http://<ip>:8008/setup/eureka_info (no login; newer ones also https :8443).
The profiles below come from Google's documentation (https://developers.google.com/cast/docs/media) and from what
actually played for us ("witnesses"). Registry of devices seen on the network: ~/.config/magneticplayer/renderers.json.
How to query all devices (Chromecast, DLNA, Samsung): docs/devices.md.
"""
import json
import os
import ssl
import threading
import time
import urllib.request

from mp_i18n import T

BASE_AUDIO = {"aac", "mp3", "opus", "vorbis", "flac"}
DOLBY = {"ac3", "eac3"}

# video: codec → (max height, max bits per colour); audio: decodes itself; passthrough: only via the TV/amplifier
CAST_PROFILES = {
    "sabrina": {
        "label": "Chromecast with Google TV 4K", "label_pl": "Chromecast z Google TV 4K",
        "video": {"h264": (2160, 8), "hevc": (2160, 10), "vp9": (2160, 10), "vp8": (1080, 8)},
        "audio": BASE_AUDIO | DOLBY, "passthrough": set(), "mkv": True,
        "source": "Google Cast docs (H.264 5.1, HEVC Main10 5.1, VP9 P2 4K60); Android TV decodes Dolby Digital/Plus; "
                  "tested on hardware: MKV with H.264 + AC3 5.1 played (2026-11-17)",
    },
    "boreal": {
        "label": "Chromecast with Google TV HD", "label_pl": "Chromecast z Google TV HD",
        "video": {"h264": (1080, 8), "hevc": (1080, 10), "vp9": (1080, 10), "av1": (1080, 10), "vp8": (1080, 8)},
        "audio": BASE_AUDIO | DOLBY, "passthrough": set(), "mkv": True,
        "source": "Google Store spec (1080p HDR, AV1/HEVC/VP9/H.264, Dolby Digital/Plus); like the 4K one, but 1080p and AV1",
    },
    "kirkwood": {
        "label": "Google TV Streamer",
        "video": {"h264": (2160, 8), "hevc": (2160, 10), "vp9": (2160, 10), "av1": (2160, 10), "vp8": (1080, 8)},
        "audio": BASE_AUDIO | DOLBY, "passthrough": set(), "mkv": True,
        "source": "Google Cast docs (H.264 5.2, HEVC Main10, VP9 P2, AV1 Main 5.1 4K60, Dolby Atmos without passthrough)",
    },
    "ultra": {
        "label": "Chromecast Ultra",
        "video": {"h264": (1080, 8), "hevc": (2160, 10), "vp9": (2160, 10), "vp8": (2160, 8)},
        "audio": BASE_AUDIO, "passthrough": DOLBY, "mkv": False,
        "source": "Google Cast docs (H.264 4.2 1080p60, HEVC Main10 5.1, VP9 P0/P2 4K60; AC3/E-AC3 passthrough only)",
    },
    "classic": {
        "label": "Chromecast (1st–3rd gen)", "label_pl": "Chromecast (1.–3. generacja)",
        "video": {"h264": (1080, 8), "vp8": (1080, 8)},
        "audio": BASE_AUDIO, "passthrough": DOLBY, "mkv": False,
        "source": "Google Cast docs (H.264 High 4.1/4.2, VP8; AC3/E-AC3 passthrough only, over HDMI)",
    },
}


def profile_key(product, model):
    """Profile key from product_name and model_name; None = unknown (e.g. a TV with a built-in Chromecast)."""
    p, m = (product or "").lower(), (model or "").lower()
    if p in CAST_PROFILES:
        return p
    if m == "chromecast ultra":
        return "ultra"
    if m in ("google tv streamer",):
        return "kirkwood"
    if m == "chromecast hd":
        return "boreal"
    if m == "chromecast" and p:
        # unknown product name with "Chromecast": assume the oldest (the safest)
        return "classic"
    return None


def eureka_info(host, timeout=4):
    """Device description from /setup/eureka_info. New firmware: https :8443 (self-signed certificate) with `device_info`;
    http :8008 now returns only the old, flat format without `product_name`, so it is a fallback (e.g. old Chromecasts)."""
    q = "/setup/eureka_info?params=name,build_info,device_info"
    best = None
    for url, ctx in ((f"https://{host}:8443{q}", ssl._create_unverified_context()), (f"http://{host}:8008{q}", None)):
        try:
            with urllib.request.urlopen(url, timeout=timeout, context=ctx) as r:
                d = json.load(r)
        except Exception:  # noqa: BLE001
            continue
        if (d.get("device_info") or {}).get("product_name"):
            return d
        best = best or d
    return best


class Renderers:
    """Registry of receivers seen on the network: id → {kind, name, model, product, profile, build, host, first_seen, last_seen}."""

    REFRESH = 7 * 86400  # ask about the model again once a week (firmware updates)

    def __init__(self, path, log):
        self.path, self.log = path, log
        self.lock = threading.Lock()
        try:
            with open(path) as f:
                self.items = json.load(f)
        except (OSError, ValueError):
            self.items = {}

    def _save(self):
        tmp = self.path + ".tmp"
        with open(tmp, "w") as f:
            json.dump(self.items, f, ensure_ascii=False, indent=1)
        os.replace(tmp, self.path)

    def seen_cast(self, uid, name, model, host):
        """Called when a Chromecast is discovered; asks about the model in the background when we do not know it or asked long ago."""
        now = time.time()
        with self.lock:
            e = self.items.setdefault(uid, {"kind": "cast", "first_seen": now})
            ask = not e.get("identified_at") or now - e["identified_at"] > self.REFRESH or e.get("host") != host
            e.update(name=name, model=model, host=host, last_seen=now)
            if not ask:
                return
            e["identified_at"] = now  # do not ask a second time before it answers
        threading.Thread(target=self._identify, args=(uid, host, model), daemon=True).start()

    def _identify(self, uid, host, model):
        info = eureka_info(host) or {}
        di = info.get("device_info") or {}
        product = di.get("product_name")
        key = profile_key(product, di.get("model_name") or model)
        with self.lock:
            e = self.items.get(uid, {})
            e.update(product=product, profile=key, build=(info.get("build_info") or {}).get("cast_build_revision"))
            if not info:
                e["identified_at"] = time.time() - self.REFRESH + 600  # no answer: try again in 10 min
            self._save()
        self.log("receiver:", e.get("name"), "→", product or "?", "/", key or "unknown profile")

    def casts(self):
        with self.lock:
            return [dict(v, id=k) for k, v in self.items.items() if v.get("kind") == "cast"]

    def get(self, uid):
        with self.lock:
            v = self.items.get(uid)
            return dict(v, id=uid) if v else None


def label(prof):
    """Profile name for the user (in the current language)."""
    return T(prof["label"], prof.get("label_pl") or prof["label"])


def profile_info(key):
    p = CAST_PROFILES.get(key or "")
    return {"profile": key, "label": label(p) if p else None}
