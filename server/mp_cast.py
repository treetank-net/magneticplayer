"""Chromecast controlled by the agent (pychromecast from the venv): for the web client, so that "computer to TV" does
not need a phone, and for the Remote in the app when something is playing on the Chromecast that the app did not
start through its Cast SDK.

Discovery (mDNS) starts on the first query and keeps running in the background; after that the agent listens to the
state of all Chromecasts.
"""
import glob
import os
import sys
import threading
import time

from mp_i18n import T

from mp_renderers import profile_info

VENV = os.path.join(os.path.dirname(os.path.abspath(__file__)), "venv")


class CastError(Exception):
    def __init__(self, msg, status=500):
        super().__init__(msg)
        self.status = status


_pc = None


def _pychromecast():
    global _pc
    if _pc is None:
        for sp in glob.glob(os.path.join(VENV, "lib", "python3*", "site-packages")):
            if sp not in sys.path:
                sys.path.append(sp)
        try:
            import pychromecast
            import zeroconf
        except ImportError:
            raise CastError(T("pychromecast is missing — run server/install.sh again", "brak pychromecast — uruchom ponownie server/install.sh"), 501)
        _pc = (pychromecast, zeroconf)
    return _pc


PLAYING = ("PLAYING", "PAUSED", "BUFFERING")


class Casts:
    """Connections to every Chromecast on the network (listening to state, without launching anything), so the status
    also sees what someone else started (a phone via Cast SDK, YouTube, Spotify…) and it can be controlled."""

    def __init__(self, log, renderers=None):
        self.log = log
        self.renderers = renderers  # mp_renderers.Renderers: records who each Chromecast is
        self.lock = threading.Lock()
        self.browser = None
        self.zconf = None
        self.started = 0
        self.casts = {}  # uuid → pychromecast.Chromecast (connected in the background)
        self.uuid = None  # the device we last started a video on from the agent

    def _ensure(self):
        if self.browser:
            return
        pc, zc = _pychromecast()
        self.zconf = zc.Zeroconf()
        self.browser = pc.discovery.CastBrowser(pc.discovery.SimpleCastListener(), self.zconf)
        self.browser.start_discovery()
        self.started = time.time()

    def _watch_all(self):
        """Attaches new devices: start() connects in the background and reconnects by itself after a drop."""
        pc, _ = _pychromecast()
        with self.lock:
            self._ensure()
            for u, info in list(self.browser.devices.items()):
                u = str(u)
                if u in self.casts or getattr(info, "cast_type", "cast") == "group":
                    continue
                if self.renderers:
                    self.renderers.seen_cast(u, info.friendly_name, info.model_name, info.host)
                try:
                    c = pc.get_chromecast_from_cast_info(info, self.zconf)
                    c.start()
                    self.casts[u] = c
                except Exception as e:  # noqa: BLE001
                    self.log("chromecast: not connecting to", info.friendly_name, e)

    def devices(self):
        """[{id, name, model}] — the first call waits up to 3 s for answers from the network."""
        with self.lock:
            self._ensure()
        while time.time() - self.started < 3 and not self.browser.devices:
            time.sleep(0.2)
        self._watch_all()
        out = []
        for uuid, info in list(self.browser.devices.items()):
            if getattr(info, "cast_type", "cast") == "group":
                continue
            r = (self.renderers.get(str(uuid)) if self.renderers else None) or {}
            out.append({"id": str(uuid), "name": info.friendly_name, "model": info.model_name,
                        "product": r.get("product"), **profile_info(r.get("profile"))})
        return sorted(out, key=lambda d: d["name"].lower())

    def _connect(self, dev_id):
        self._watch_all()
        c = self.casts.get(dev_id)
        if not c:
            raise CastError(T("can't see this Chromecast on the network", "nie widzę tego Chromecasta w sieci"), 404)
        c.wait(timeout=10)
        return c

    def play(self, dev_id, url, title, content_type, subs=None, start=None):
        """`subs`: [{"url" (WebVTT), "lang"}], the first one is enabled."""
        c = self._connect(dev_id)
        mc = c.media_controller
        kw = {"title": title, "current_time": start or 0, "autoplay": True, "stream_type": "BUFFERED"}
        if subs:
            lang = (subs[0].get("lang") or "pl").split(".")[0] or "pl"
            kw.update(subtitles=subs[0]["url"], subtitles_lang=lang, subtitles_mime="text/vtt", subtitle_id=1)
        mc.play_media(url, content_type, **kw)
        mc.block_until_active(timeout=20)
        self.uuid = dev_id
        self.log("chromecast:", c.cast_info.friendly_name, "←", title)
        return self.status()

    @staticmethod
    def _media(c):
        if not c.socket_client.is_connected:
            return None
        return c.media_controller.status

    def _pick(self, dev_id=None):
        """(uuid, Chromecast): the given one, otherwise one that is playing something (ours first)."""
        if dev_id:
            return dev_id, self.casts.get(dev_id)
        order = sorted(self.casts.items(), key=lambda kv: kv[0] != self.uuid)
        for u, c in order:
            m = self._media(c)
            if m and (m.player_state in PLAYING or (u == self.uuid and m.content_id and m.player_state != "UNKNOWN")):
                return u, c
        return None, None

    def _one(self, u, c):
        m = self._media(c)
        if m is None:
            return None
        st = c.status
        return {
            "active": m.player_state in PLAYING or (u == self.uuid and bool(m.content_id) and m.player_state != "UNKNOWN"),
            "device": c.cast_info.friendly_name, "id": u,
            # the application on the Chromecast ("Default Media Receiver" = us or the app, YouTube, Spotify…)
            "app": st.display_name if st else None, "mine": u == self.uuid,
            "title": m.title, "state": m.player_state, "pos": m.adjusted_current_time or m.current_time or 0,
            "duration": m.duration, "volume": round((st.volume_level if st else 0) * 100),
            "muted": bool(st and st.volume_muted),
            # fixed volume (e.g. controlled by the TV over HDMI-CEC): the volume buttons do nothing
            "volume_fixed": bool(st and st.volume_control_type == "fixed"),
        }

    def status(self):
        """State of the Chromecast that is playing something (any sender); `all`: all playing ones."""
        self._watch_all()
        playing = [x for x in (self._one(u, c) for u, c in self.casts.items()) if x and x["active"]]
        if not playing:
            return {"active": False, "all": []}
        playing.sort(key=lambda x: not x["mine"])
        return {**playing[0], "all": playing}

    def control(self, cmd, value=None, dev_id=None):
        u, c = self._pick(dev_id)
        if not c:
            raise CastError(T("nothing is playing on any Chromecast", "na żadnym Chromecaście nic nie gra"), 409)
        mc = c.media_controller
        if cmd == "toggle":
            mc.pause() if mc.status.player_state == "PLAYING" else mc.play()
        elif cmd == "pause":
            mc.pause()
        elif cmd == "play":
            mc.play()
        elif cmd == "seek":
            mc.seek(float(value))
        elif cmd == "seek_rel":
            mc.seek(max(0.0, (mc.status.adjusted_current_time or 0) + float(value)))
        elif cmd == "volume":
            c.set_volume(max(0.0, min(1.0, float(value) / 100)))
        elif cmd == "mute":
            c.set_volume_muted(bool(value))
        elif cmd == "stop":
            c.quit_app()
            if u == self.uuid:
                self.uuid = None
        else:
            raise CastError(T("unknown command: ", "nieznane polecenie: ") + str(cmd), 400)
        return {"ok": True}
