"""What each phone watched: positions, "watched" marks and subtitle choices synced from phones (agent 1.18+,
`features.watch_sync`, POST /api/watch/sync).

A phone keeps all of this itself and works without the computer; at a sync point (the server reachable again, the
library refreshed) it sends what changed since its last sync and gets back what this agent knows newer about it. Per
device: a household's phones keep their own places in a film. Per title the newer change wins, by time: the phone
sends its clock with the request, so its times are moved onto this machine's clock before comparing.

A subtitle choice made on the phone without the computer (another version of a downloaded copy's subtitles) is made
the default here too, unless the subtitles of that video changed here after it (a newer download, a shift, another
version picked): then the phone gets the server's subtitles at the same sync.

Keys are "<library folder id>/<path in it>" (ids survive folder renumbering); moves made by tasks are applied from the
moves journal before each sync. State: watch.json in the config directory.
"""
import json
import os
import threading
import time

KEEP_DAYS = 400       # entries untouched for longer are dropped
MAX_ITEMS = 300       # per request


def _ms(x):
    try:
        return max(0, int(x or 0))
    except (TypeError, ValueError):
        return 0


class WatchState:
    def __init__(self, path, log):
        self.path, self.log = path, log
        self.lock = threading.Lock()
        try:
            with open(path) as f:
                d = json.load(f)
        except (OSError, ValueError):
            d = {}
        self.seq = d.get("seq") or 0
        self.moves_at = d.get("moves_at") or time.time()  # moves before this state existed have nothing to re-key
        self.devices = d.get("devices") or {}
        self.subs_at = d.get("subs_at") or {}   # real path of a video → when its subtitles were last chosen here (ms)

    def _save(self):
        with self.lock:
            cut = (time.time() - KEEP_DAYS * 86400) * 1000
            for dev in self.devices.values():
                old = [k for k, v in dev["items"].items() if max(v.get("pos_at", 0), v.get("wd", 0), v.get("sub_at", 0)) <= cut]
                for k in old:  # in place: a sync running now holds this dict
                    del dev["items"][k]
            self.subs_at = {k: v for k, v in self.subs_at.items() if v > cut}
            data = json.dumps({"seq": self.seq, "moves_at": self.moves_at, "devices": self.devices, "subs_at": self.subs_at},
                              ensure_ascii=False)
        tmp = self.path + ".tmp"
        try:
            with open(tmp, "w") as f:
                f.write(data)
            os.replace(tmp, self.path)
        except OSError as e:
            self.log("watch state: not saved:", e)

    def note_subs(self, full):
        """The subtitles of a video were chosen here (POST /api/subs/default): a phone's older offline choice loses."""
        with self.lock:
            self.subs_at[os.path.realpath(full)] = time.time() * 1000
        self._save()

    def subs_changed_at(self, full, subs):
        """When the subtitles of `full` last changed here (ms): a choice made here, or a file written or renamed."""
        t = self.subs_at.get(os.path.realpath(full), 0)
        d = os.path.dirname(full)
        for s in subs:
            try:
                st = os.stat(os.path.join(d, s["name"]))
                t = max(t, st.st_mtime * 1000, st.st_ctime * 1000)
            except OSError:
                pass
        return t

    def apply_moves(self, moves):
        """`moves`: the tasks' journal entries after `moves_at` ({at, from_root_id, from, to_root_id, to})."""
        if not moves:
            return
        with self.lock:
            for m in moves:
                old, new = f"{m['from_root_id']}/{m['from']}", f"{m['to_root_id']}/{m['to']}"
                for dev in self.devices.values():
                    items = dev["items"]
                    for k in [k for k in items if k == old or k.startswith(old + "/")]:
                        nk = new + k[len(old):]
                        if nk not in items:
                            items[nk] = items[k]
                        del items[k]
                self.moves_at = max(self.moves_at, m["at"])
        self._save()

    def sync(self, dev, name, body, roots, resolve, choose_sub, played):
        """One phone's sync. `roots`: library folders (index → id); `resolve(index, path)` → full path or None;
        `choose_sub(full, name, at ms)` → the version's new name when it became the default, else None; `played(full, at s)`: "watched" for the
        disk tasks. Returns the answer of POST /api/watch/sync."""
        now = int(time.time() * 1000)
        skew = now - _ms(body.get("now")) if body.get("now") else 0  # add to a phone time to get this machine's time
        ids = {r["index"]: r["id"] for r in roots}
        idx = {r["id"]: r["index"] for r in roots}
        since = _ms(body.get("since"))
        subs_applied, subs_kept, mine = [], [], set()
        with self.lock:
            d = self.devices.setdefault(dev, {"items": {}})
            if name:
                d["name"] = name
            items = d["items"]
        for x in (body.get("items") or [])[:MAX_ITEMS]:
            try:
                rid = ids[int(x.get("root"))]
            except (KeyError, TypeError, ValueError):
                continue
            path = str(x.get("path") or "").strip("/")
            if not path:
                continue
            key = f"{rid}/{path}"
            pos_at = _ms(x.get("pos_at")) and _ms(x.get("pos_at")) + skew
            wd = _ms(x.get("watched_at")) and _ms(x.get("watched_at")) + skew
            sub_at = _ms(x.get("sub_at")) and _ms(x.get("sub_at")) + skew
            changed = False
            with self.lock:
                e = items.setdefault(key, {})
                if pos_at and pos_at > e.get("pos_at", 0):
                    e["pos"], e["pos_at"], changed = _ms(x.get("pos")), pos_at, True
                if wd and wd > e.get("wd", 0):
                    e["wd"], changed = wd, True
            full = resolve(int(x.get("root")), path) if (pos_at or wd or sub_at) else None
            if sub_at and x.get("sub"):
                ok = choose_sub(full, str(x["sub"]), sub_at) if full else None  # a video gone here: settled as kept
                (subs_applied if ok else subs_kept).append({"root": int(x.get("root")), "path": path, "sub": x["sub"],
                                                            **({"name": ok} if ok else {})})
                if ok:
                    with self.lock:
                        e["sub"], e["sub_at"], changed = ok, sub_at, True
                        self.subs_at[os.path.realpath(full)] = sub_at
            if full and (changed and (pos_at or wd)):
                played(full, max(pos_at, wd) / 1000)
            with self.lock:
                if changed:
                    self.seq += 1
                    e["seq"] = self.seq
                    mine.add(key)
                elif not e:
                    items.pop(key, None)
        with self.lock:
            out = []
            for key, e in items.items():
                if e.get("seq", 0) <= since or key in mine:
                    continue  # what the phone just sent it has already
                rid, _, path = key.partition("/")
                if rid not in idx:
                    continue
                out.append({"root": idx[rid], "path": path, "pos": e.get("pos", 0),
                            "pos_at": max(0, e.get("pos_at", 0) - skew) if e.get("pos_at") else 0,
                            "watched_at": max(0, e["wd"] - skew) if e.get("wd") else 0})
            seq = self.seq
        if mine:
            self._save()
        return {"seq": seq, "items": out, "subs_applied": subs_applied, "subs_kept": subs_kept}
