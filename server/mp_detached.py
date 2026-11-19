"""Long ffmpeg runs (shrinking, "for Chromecast" versions) that outlive the agent.

An encode is not a child of the agent but of a small keeper (`python mp_detached.py <dir>`) started outside the
agent's process tree: on Linux in its own systemd scope (`systemctl restart` kills the service's whole cgroup), on
Windows with DETACHED_PROCESS + CREATE_BREAKAWAY_FROM_JOB (install.ps1 stops only `magneticplayer_agent.py`). The keeper
holds an exclusive lock on `<dir>/lock` while the command runs, writes the command's stdout (ffmpeg `-progress`) to
`out`, stderr to `err`, its pid to `pid` and, at the end, the exit code to `rc`.

After an agent restart the owner (mp_tasks, mp_media.Converter) finds the run by its id and attaches again: the lock
says whether it still runs, `out` gives the progress, `rc` the result. A run whose keeper died without `rc` (a reboot)
counts as failed and is redone. `Detached` behaves like `subprocess.Popen` where the owners need it (`pid`, `poll()`,
`wait()`, `send_signal()`, `terminate()`), so pausing (SIGSTOP/SIGCONT) and disk-read measurement work as before.
"""
import json
import os
import secrets
import shutil
import signal
import subprocess
import sys
import time

WINDOWS = sys.platform == "win32"
START_GRACE = 20  # seconds a fresh run may take before the keeper holds the lock


def _try_lock(f):
    """Exclusive non-blocking lock on an open file: True if we got it."""
    try:
        if WINDOWS:
            import msvcrt
            f.seek(0)
            msvcrt.locking(f.fileno(), msvcrt.LK_NBLCK, 1)
        else:
            import fcntl
            fcntl.flock(f.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        return True
    except OSError:
        return False


def _unlock(f):
    try:
        if WINDOWS:
            import msvcrt
            f.seek(0)
            msvcrt.locking(f.fileno(), msvcrt.LK_UNLCK, 1)
        else:
            import fcntl
            fcntl.flock(f.fileno(), fcntl.LOCK_UN)
    except OSError:
        pass


def _write(path, text):
    tmp = path + ".tmp"
    with open(tmp, "w") as f:
        f.write(text)
    os.replace(tmp, path)


class Detached:
    def __init__(self, base, rid):
        self.base, self.id = base, rid
        self.dir = os.path.join(base, rid)
        self.returncode = None

    # ---- starting and finding

    @classmethod
    def start(cls, base, cmd, meta=None, kind="job"):
        rid = f"{kind}-{secrets.token_hex(4)}"
        d = os.path.join(base, rid)
        os.makedirs(d)
        _write(os.path.join(d, "cmd.json"), json.dumps({"cmd": cmd, "created": time.time()}))
        _write(os.path.join(d, "meta.json"), json.dumps(meta or {}, ensure_ascii=False))
        keeper = [sys.executable, os.path.abspath(__file__), d]
        if WINDOWS:
            pyw = os.path.join(os.path.dirname(sys.executable), "pythonw.exe")
            keeper[0] = pyw if os.path.exists(pyw) else sys.executable
            flags = subprocess.DETACHED_PROCESS | subprocess.CREATE_NEW_PROCESS_GROUP | subprocess.CREATE_NO_WINDOW
            try:
                p = subprocess.Popen(keeper, creationflags=flags | subprocess.CREATE_BREAKAWAY_FROM_JOB,
                                     stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            except OSError:  # a job that forbids breaking away: still detached from the agent's process
                p = subprocess.Popen(keeper, creationflags=flags,
                                     stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        else:
            if shutil.which("systemd-run") and os.environ.get("XDG_RUNTIME_DIR"):
                keeper = ["systemd-run", "--user", "--scope", "--quiet", "--collect",
                          "--unit", f"magneticplayer-{rid}", *keeper]
            p = subprocess.Popen(keeper, start_new_session=True,
                                 stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        # reap the starter when it ends (no zombie); the run itself is watched through its files
        import threading
        threading.Thread(target=p.wait, daemon=True).start()
        return cls(base, rid)

    @classmethod
    def find(cls, base, rid):
        """An earlier run by id, or None when its directory is gone."""
        if not rid or "/" in rid or "\\" in rid or rid.startswith("."):
            return None
        j = cls(base, rid)
        return j if os.path.isfile(os.path.join(j.dir, "cmd.json")) else None

    @classmethod
    def all(cls, base, kind=None):
        try:
            names = sorted(os.listdir(base))
        except OSError:
            return []
        out = []
        for n in names:
            if kind and not n.startswith(kind + "-"):
                continue
            j = cls.find(base, n)
            if j:
                out.append(j)
        return out

    # ---- state

    def _read(self, name):
        try:
            with open(os.path.join(self.dir, name)) as f:
                return f.read()
        except OSError:
            return None

    @property
    def meta(self):
        try:
            return json.loads(self._read("meta.json") or "{}")
        except ValueError:
            return {}

    @property
    def cmd(self):
        try:
            return json.loads(self._read("cmd.json") or "{}").get("cmd") or []
        except ValueError:
            return []

    @property
    def pid(self):
        v = (self._read("pid") or "").strip()
        return int(v) if v.isdigit() else None

    def running(self):
        """The keeper holds the lock (or has only just been started)."""
        path = os.path.join(self.dir, "lock")
        try:
            with open(path, "a+") as f:
                if not _try_lock(f):
                    return True
                _unlock(f)
        except OSError:
            pass
        if self._read("rc") is not None:
            return False
        try:
            created = json.loads(self._read("cmd.json") or "{}").get("created") or 0
        except ValueError:
            created = 0
        return self.pid is None and time.time() - created < START_GRACE

    def poll(self):
        """Exit code once it ended (-1: the keeper died without one, e.g. a reboot), otherwise None."""
        if self.returncode is not None:
            return self.returncode
        def rc():
            v = (self._read("rc") or "").strip()
            return int(v) if v.lstrip("-").isdigit() else None
        if rc() is not None:
            self.returncode = rc()
        elif not self.running():
            self.returncode = rc() if rc() is not None else -1  # rc may land between the two reads
        return self.returncode

    def wait(self, timeout=None):
        end = None if timeout is None else time.time() + timeout
        while self.poll() is None:
            if end is not None and time.time() > end:
                raise subprocess.TimeoutExpired(self.cmd, timeout)
            time.sleep(0.5)
        return self.returncode

    def progress(self):
        """The last values from ffmpeg `-progress` (out_time_us, speed, progress…)."""
        out = {}
        try:
            with open(os.path.join(self.dir, "out"), "rb") as f:
                f.seek(0, 2)
                f.seek(max(0, f.tell() - 4096))
                tail = f.read().decode("utf-8", "replace")
        except OSError:
            return out
        for line in tail.splitlines():
            k, _, v = line.strip().partition("=")
            if k and v:
                out[k] = v
        return out

    def err_tail(self, n=5):
        return (self._read("err") or "").strip().splitlines()[-n:]

    # ---- control

    def send_signal(self, sig):
        pid = self.pid
        if pid is None or self.poll() is not None:
            return
        try:
            os.kill(pid, sig)
        except (OSError, ProcessLookupError):
            pass

    def terminate(self):
        if WINDOWS:
            self.send_signal(signal.SIGTERM)  # TerminateProcess
        else:
            self.send_signal(signal.SIGCONT)  # a stopped process doesn't act on SIGTERM until it runs again
            self.send_signal(signal.SIGTERM)

    def remove(self):
        shutil.rmtree(self.dir, ignore_errors=True)


def keeper(d):
    """Runs the command, holding the lock until it ends."""
    with open(os.path.join(d, "cmd.json")) as f:
        cmd = json.load(f)["cmd"]
    lock = open(os.path.join(d, "lock"), "a+")
    if not _try_lock(lock):
        return 1  # another keeper already runs this
    rc = -1
    try:
        with open(os.path.join(d, "out"), "ab") as out, open(os.path.join(d, "err"), "ab") as err:
            extra = {"creationflags": subprocess.CREATE_NO_WINDOW} if WINDOWS else {}
            p = subprocess.Popen(cmd, stdin=subprocess.DEVNULL, stdout=out, stderr=err, **extra)
            _write(os.path.join(d, "pid"), str(p.pid))
            if not WINDOWS:  # stopping the agent's service must not stop the encode through the keeper
                for s in (signal.SIGHUP, signal.SIGINT):
                    signal.signal(s, signal.SIG_IGN)
            rc = p.wait()
    except OSError as e:
        with open(os.path.join(d, "err"), "a") as err:
            err.write(f"{e}\n")
        rc = 127
    finally:
        _write(os.path.join(d, "rc"), str(rc))
        _unlock(lock)
        lock.close()
    return 0


if __name__ == "__main__":
    sys.exit(keeper(sys.argv[1]))
