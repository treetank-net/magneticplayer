"""Zlecenia z telefonu: historia magnetów wysłanych z apki + akcja po pobraniu."""
import json
import os
import threading
import time
import urllib.parse

AFTER = ("none", "stop", "suspend")
ACTIVE_STATES = ("Downloading", "Checking", "Allocating", "Moving", "Queued")


def magnet_name(uri):
    q = urllib.parse.parse_qs(uri.partition("?")[2])
    return (q.get("dn") or [None])[0]


class Jobs:
    def __init__(self, path, log):
        self.path = path
        self.log = log
        self.lock = threading.Lock()
        self.items = []
        if os.path.exists(path):
            try:
                with open(path) as f:
                    self.items = json.load(f).get("jobs", [])
            except (OSError, ValueError):
                pass

    def _save(self):
        tmp = self.path + ".tmp"
        with open(tmp, "w") as f:
            json.dump({"jobs": self.items}, f, indent=1, ensure_ascii=False)
        os.replace(tmp, self.path)

    def _find(self, tid):
        return next((j for j in self.items if j["id"] == tid), None)

    def add(self, tid, magnet, after):
        if after not in AFTER:
            after = "none"
        with self.lock:
            j = self._find(tid)
            if j is None:
                j = {"id": tid, "added": time.time()}
                self.items.insert(0, j)
            j.update({"magnet": magnet, "name": j.get("name") or magnet_name(magnet), "after": after,
                      "after_done": False, "finished": None})
            del self.items[300:]
            self._save()

    def set_meta(self, tid, **kw):
        """Dodatkowe dane zlecenia (rozpoznanie, katalog docelowy), np. do zapamiętania zmiany katalogu."""
        with self.lock:
            j = self._find(tid)
            if j is not None:
                j.update(kw)
                self._save()

    def set_after(self, tid, after):
        if after not in AFTER:
            raise ValueError(after)
        with self.lock:
            j = self._find(tid)
            if j is None:
                j = {"id": tid, "added": time.time(), "magnet": None, "name": None}
                self.items.insert(0, j)
            j.update({"after": after, "after_done": False, "finished": None})
            self._save()

    def get(self, tid):
        with self.lock:
            j = self._find(tid)
            return dict(j) if j else None

    def all(self):
        with self.lock:
            return [dict(j) for j in self.items]

    def tick(self, torrents, playing, do_action):
        """Wołane co ~20 s. torrents: lista z backendu; playing: czy mpv gra; do_action(tid|None, akcja)."""
        by_id = {t["id"]: t for t in torrents}
        machine = []
        with self.lock:
            changed = False
            for j in self.items:
                t = by_id.get(j["id"])
                if t and not j.get("name"):
                    j["name"] = t["name"]
                    changed = True
                if j.get("after", "none") == "none" or j.get("after_done") or not t:
                    continue
                if t["progress"] < 100:
                    continue
                if j["after"] == "stop":
                    do_action(j["id"], "pause")
                    j["after_done"] = True
                    changed = True
                    self.log("po pobraniu zatrzymano:", j["name"])
                else:
                    if not j.get("finished"):
                        j["finished"] = time.time()
                        changed = True
                    machine.append(j)
            busy = playing or any(t["state"] in ACTIVE_STATES and t["progress"] < 100 for t in torrents)
            ripe = [j for j in machine if time.time() - j["finished"] > 60]
            act = None
            if ripe and not busy:
                act = "poweroff" if any(j["after"] == "poweroff" for j in ripe) else "suspend"
                for j in machine:
                    j["after_done"] = True
                changed = True
            if changed:
                self._save()
        if act:
            self.log("wszystko pobrane, akcja:", act)
            do_action(None, act)
