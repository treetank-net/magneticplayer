"""Power: sleep inhibition while downloading or playing (SleepGuard), suspend and power-off over the network (config
`power`), waking and unlocking the screen (config `auto_unlock`)."""
import threading
import time

import mp_platform as platform
from mp_i18n import T
from mp_core import ApiError, log


AUTO_UNLOCK = True  # overridden from config in App.__init__
POWER = True  # config "power": false: no suspend or power-off over the network (e.g. a work laptop)


def wake_screen(unlock=None):
    return platform.wake_screen(AUTO_UNLOCK if unlock is None else unlock)


class SleepGuard(threading.Thread):
    """Holds systemd-inhibit (sleep) while something is downloading or mpv is playing."""

    def __init__(self, app):
        super().__init__(daemon=True)
        self.app = app
        self.proc = None
        self.reason = ""
        self.paused_until = 0

    def hold(self, why):
        if self.proc and self.proc.poll() is None:
            return
        self.proc = platform.inhibit_sleep(why)
        if self.proc:
            log("sleep inhibited:", why)

    def release(self):
        if self.proc and self.proc.poll() is None:
            self.proc.terminate()
            log("sleep inhibition released")
        self.proc = None

    def run(self):
        while True:
            try:
                why = None
                if time.time() >= self.paused_until:
                    if self.app.mpv.running() and self.app.mpv.prop("pause") is False:
                        why = "playback"
                    else:
                        active = [t for t in self.app.backend.list()
                                  if t["state"] in ("Downloading", "Checking", "Allocating", "Moving")]
                        if active:
                            why = f"downloading ({len(active)})"
                if why:
                    self.hold(why)
                else:
                    self.release()
                self.reason = why or ""
                playing = self.app.mpv.running() and self.app.mpv.prop("pause") is False
                self.app.jobs.tick(self.app.backend.list(), playing, self.app.machine_action)
                self.app.place_unnamed()
                self.app.refit_due()
                self.app.subflow.tick(self.app.backend.list())
            except Exception as e:  # noqa: BLE001 — a background thread must not die
                log("SleepGuard:", e)
            time.sleep(20)


def poweroff(guard):
    if not POWER:
        raise ApiError(T("this server has power-off over the network disabled (config: power)",
                   "ten serwer ma wyłączone wyłączanie z sieci (config: power)"), 403)
    guard.paused_until = time.time() + 120
    guard.release()
    err = platform.power("poweroff")
    if err:
        raise ApiError(T(f"power-off failed: {err}", f"wyłączanie nie wyszło: {err}"), 500)


def suspend(guard):
    if not POWER:
        raise ApiError(T("this server has sleep over the network disabled (config: power)",
                   "ten serwer ma wyłączone usypianie z sieci (config: power)"), 403)
    guard.paused_until = time.time() + 120
    guard.release()
    err = platform.power("suspend")
    if err:
        raise ApiError(T(f"sleep failed: {err}", f"usypianie nie wyszło: {err}"), 500)
