"""Announcing "what is playing" between remotes (phones running the app, agents): UDP multicast 239.255.77.77:47700, TTL 1.

Format as in the app (`Announce.kt`): {"mp":1,"type":"playing"|"stopped"|"who","from":{kind,id,name},
"target":{kind,id,name,ctl},"title","state","pos","dur","ts"}. The agent listens (list for the web page:
GET /api/announce) and announces its own mpv screen itself. No secrets in the packets. Description: docs/plan-remotes.md.
"""
import json
import socket
import struct
import threading
import time

GROUP, PORT, TTL = "239.255.77.77", 47700, 10


class Announcer:
    def __init__(self, log, me, mine):
        """`me()` → the agent's {"id", "name"}; `mine()` → list of "playing" messages (what is playing here right now)."""
        self.log, self.me, self.mine = log, me, mine
        self.others = {}
        self.lock = threading.Lock()
        self.sock = None

    def start(self):
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
            s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            s.bind(("", PORT))
            s.setsockopt(socket.IPPROTO_IP, socket.IP_ADD_MEMBERSHIP, struct.pack("4s4s", socket.inet_aton(GROUP),
                                                                                  socket.inet_aton("0.0.0.0")))
            s.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL, 1)
            s.settimeout(3)
            self.sock = s
        except OSError as e:
            self.log("announce: not listening:", e)
            return
        threading.Thread(target=self._listen, daemon=True).start()
        threading.Thread(target=self._beacon, daemon=True).start()
        self.send({"type": "who"})

    def _from(self):
        return {"kind": "agent", **self.me()}

    def send(self, msg):
        if not self.sock:
            return
        msg = {"mp": 1, "from": self._from(), **msg}
        try:
            self.sock.sendto(json.dumps(msg, ensure_ascii=False).encode(), (GROUP, PORT))
        except OSError:
            pass

    def _beacon(self):
        was = set()
        while True:
            now = set()
            for m in self.mine():
                self.send(m)
                now.add((m["target"]["kind"], m["target"]["id"]))
            for kind, tid in was - now:
                self.send({"type": "stopped", "target": {"kind": kind, "id": tid}})
            was = now
            time.sleep(3)

    def _listen(self):
        my_id = self.me()["id"]
        while True:
            try:
                data, _ = self.sock.recvfrom(8192)
            except socket.timeout:
                continue
            except OSError:
                return
            try:
                j = json.loads(data)
            except ValueError:
                continue
            if j.get("mp") != 1 or (j.get("from") or {}).get("id") == my_id:
                continue
            t, kind = j.get("target") or {}, j.get("type")
            key = f"{t.get('kind')}:{t.get('id')}"
            with self.lock:
                if kind == "who":
                    for m in self.mine():
                        self.send(m)
                elif kind == "stopped":
                    self.others.pop(key, None)
                elif kind == "playing" and t:
                    self.others[key] = {**j, "at": time.time()}

    def list(self):
        """What other remotes are playing (without entries older than 10 s)."""
        now = time.time()
        with self.lock:
            for k in [k for k, v in self.others.items() if now - v["at"] > TTL]:
                del self.others[k]
            return [{"target": {k: v for k, v in e["target"].items() if k != "ctl"}, "from": e["from"],
                     "title": e.get("title"), "state": e.get("state"), "pos": e.get("pos"), "dur": e.get("dur")}
                    for e in self.others.values()]
