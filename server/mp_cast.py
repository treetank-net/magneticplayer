"""Chromecast sterowany przez agenta (pychromecast z venv): dla klienta webowego, żeby „z kompa na telewizor” nie
wymagało telefonu. Apka na Androidzie ma własny Cast SDK i tego nie używa.

Wykrywanie (mDNS) rusza przy pierwszym zapytaniu i trwa w tle. Jedno aktywne połączenie naraz.
"""
import glob
import os
import sys
import threading
import time

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
            raise CastError("brak pychromecast — uruchom ponownie server/install.sh", 501)
        _pc = (pychromecast, zeroconf)
    return _pc


class Casts:
    def __init__(self, log):
        self.log = log
        self.lock = threading.Lock()
        self.browser = None
        self.zconf = None
        self.started = 0
        self.cast = None  # pychromecast.Chromecast aktywnego urządzenia
        self.uuid = None

    def _ensure(self):
        if self.browser:
            return
        pc, zc = _pychromecast()
        self.zconf = zc.Zeroconf()
        self.browser = pc.discovery.CastBrowser(pc.discovery.SimpleCastListener(), self.zconf)
        self.browser.start_discovery()
        self.started = time.time()

    def devices(self):
        """[{id, name, model}] — pierwsze wywołanie czeka do 3 s na odpowiedzi z sieci."""
        with self.lock:
            self._ensure()
        while time.time() - self.started < 3 and not self.browser.devices:
            time.sleep(0.2)
        out = []
        for uuid, info in list(self.browser.devices.items()):
            if getattr(info, "cast_type", "cast") == "group":
                continue
            out.append({"id": str(uuid), "name": info.friendly_name, "model": info.model_name})
        return sorted(out, key=lambda d: d["name"].lower())

    def _connect(self, dev_id):
        pc, _ = _pychromecast()
        with self.lock:
            self._ensure()
            if self.cast and self.uuid == dev_id:
                return self.cast
            info = next((i for u, i in self.browser.devices.items() if str(u) == dev_id), None)
            if not info:
                raise CastError("nie widzę tego Chromecasta w sieci", 404)
            if self.cast:
                try:
                    self.cast.disconnect(timeout=2)
                except Exception:  # noqa: BLE001
                    pass
            c = pc.get_chromecast_from_cast_info(info, self.zconf)
            c.wait(timeout=10)
            self.cast, self.uuid = c, dev_id
            return c

    def play(self, dev_id, url, title, content_type, subs=None, start=None):
        """`subs`: [{"url" (WebVTT), "lang"}], pierwsze włączone."""
        c = self._connect(dev_id)
        mc = c.media_controller
        kw = {"title": title, "current_time": start or 0, "autoplay": True, "stream_type": "BUFFERED"}
        if subs:
            lang = (subs[0].get("lang") or "pl").split(".")[0] or "pl"
            kw.update(subtitles=subs[0]["url"], subtitles_lang=lang, subtitles_mime="text/vtt", subtitle_id=1)
        mc.play_media(url, content_type, **kw)
        mc.block_until_active(timeout=20)
        self.log("chromecast:", c.cast_info.friendly_name, "←", title)
        return self.status()

    def status(self):
        c = self.cast
        if not c:
            return {"active": False}
        mc = c.media_controller
        s = mc.status
        if s is None or not c.socket_client.is_connected:
            return {"active": False}
        return {
            "active": s.player_state not in ("IDLE", "UNKNOWN") or bool(s.content_id),
            "device": c.cast_info.friendly_name, "id": self.uuid,
            "title": s.title, "state": s.player_state, "pos": s.adjusted_current_time or s.current_time or 0,
            "duration": s.duration, "volume": round((c.status.volume_level if c.status else 0) * 100),
            # stała głośność (np. sterowana przez telewizor po HDMI-CEC): przyciski głośności nic nie dają
            "volume_fixed": bool(c.status and c.status.volume_control_type == "fixed"),
        }

    def control(self, cmd, value=None):
        c = self.cast
        if not c:
            raise CastError("nic nie jest połączone", 409)
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
        elif cmd == "stop":
            c.quit_app()
            with self.lock:
                self.cast = self.uuid = None
        else:
            raise CastError("nieznane polecenie: " + str(cmd), 400)
        return {"ok": True}
