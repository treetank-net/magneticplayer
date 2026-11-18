"""Everything that depends on the operating system: graphical session, lock screen, suspend, network, Wake-on-LAN.

Full support on Linux (systemd, logind, X11/Wayland). Windows (light install, install.ps1): screen via mpv, IP address;
no unlocking, suspending or Wake-on-LAN. Whatever the system cannot do, the functions answer "can't" (False/None),
and the agent does not advertise it in /api/info, so the app hides the corresponding buttons.
"""
import os
import re
import shutil
import socket
import subprocess
import sys
import time

from mp_i18n import T

LINUX = sys.platform.startswith("linux")
WINDOWS = os.name == "nt"
HERE = os.path.dirname(os.path.abspath(__file__))
SESSIONS = ("cinnamon-session", "cinnamon", "xfce4-session", "mate-session", "gnome-shell", "plasmashell",
            "lxsession", "lxqt-session", "budgie-wm", "kwin_wayland", "kwin_x11", "labwc", "wayfire")


def _log(*a):
    print(time.strftime("%H:%M:%S"), *a, file=sys.stderr, flush=True)


def _session_env():
    """Environment of this user's graphical session process, or None when nobody is logged in graphically."""
    if not LINUX:
        return None
    uid = os.getuid()
    for pid in filter(str.isdigit, os.listdir("/proc")):
        try:
            if os.stat(f"/proc/{pid}").st_uid != uid:
                continue
            with open(f"/proc/{pid}/comm") as f:
                if f.read().strip() not in SESSIONS:
                    continue
            with open(f"/proc/{pid}/environ", "rb") as f:
                return dict(kv.split("=", 1) for kv in f.read().decode(errors="ignore").split("\0") if "=" in kv)
        except OSError:
            continue
    return None


def graphical_env():
    """Environment variables of the user's graphical session (a systemd service does not have them)."""
    env = dict(os.environ)
    penv = _session_env() or {}
    for k in ("DISPLAY", "WAYLAND_DISPLAY", "XAUTHORITY", "DBUS_SESSION_BUS_ADDRESS", "XDG_SESSION_TYPE"):
        if k in penv:
            env[k] = penv[k]
    env.setdefault("DISPLAY", ":0")
    if "XAUTHORITY" not in env and os.path.exists(os.path.expanduser("~/.Xauthority")):
        env["XAUTHORITY"] = os.path.expanduser("~/.Xauthority")
    return env


def mpv_path():
    """mpv from PATH or next to the agent (Windows: install.ps1 puts it in <agent>/mpv/mpv.exe)."""
    local = os.path.join(HERE, "mpv", "mpv.exe" if WINDOWS else "mpv")
    return shutil.which("mpv") or (local if os.path.isfile(local) else None)


def has_display():
    """Whether a video can be shown here: mpv + a logged-in graphical session (or an explicitly set DISPLAY)."""
    if not mpv_path():
        return False
    if WINDOWS:  # the agent starts at user login (HKCU\...\Run), so it runs in the user's session
        return True
    return _session_env() is not None or bool(os.environ.get("DISPLAY") or os.environ.get("WAYLAND_DISPLAY"))


# ---------------------------------------------------------------- screen

def can_unlock():
    return LINUX and bool(shutil.which("loginctl"))


def unlock_screen():
    """Removes the lock screen via logind (Unlock signal); cinnamon-screensaver, GNOME and KDE listen to it.
    polkit lets the session owner do this without a password. Returns True on success."""
    if not can_unlock():
        return False
    try:
        sid = subprocess.run(["loginctl", "show-user", str(os.getuid()), "-p", "Display", "--value"],
                             capture_output=True, text=True, timeout=5).stdout.strip()
        if not sid:
            return False
        r = subprocess.run(["loginctl", "unlock-session", sid], capture_output=True, text=True, timeout=5)
        if r.returncode:
            _log("unlock-session:", r.stderr.strip())
        return r.returncode == 0
    except (OSError, subprocess.SubprocessError) as e:
        _log("unlock-session:", e)
        return False


def wake_screen(unlock):
    """Wakes the monitor and (when `unlock`) removes the lock screen."""
    if not LINUX:
        return False
    env = graphical_env()
    # no "cinnamon-screensaver-command --deactivate": in Cinnamon 5.4 it crashes the screensaver (no grab_helper)
    # and hangs; the lock screen is removed by unlock_screen() via logind
    for cmd in (["xset", "dpms", "force", "on"], ["xdg-screensaver", "reset"]):
        if shutil.which(cmd[0]):
            try:
                subprocess.run(cmd, env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=3)
            except (OSError, subprocess.SubprocessError) as e:
                _log("screen:", cmd[0], e)
    return unlock_screen() if unlock else False


# ---------------------------------------------------------------- suspend and power

def can_power():
    return LINUX and bool(shutil.which("systemctl"))


def inhibit_sleep(why):
    """A process holding the sleep inhibitor (killing it releases the lock), or None."""
    if not (LINUX and shutil.which("systemd-inhibit")):
        return None
    proc = subprocess.Popen(
        ["systemd-inhibit", "--what=sleep", "--who=MagneticPlayer", f"--why={why}", "--mode=block",
         "sleep", "infinity"], stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
    time.sleep(0.5)
    if proc.poll() is not None:
        _log("systemd-inhibit refused:", proc.stderr.read().decode().strip(), "(missing polkit rule? run install.sh)")
        return None
    return proc


def power(action):
    """`suspend` or `poweroff`. Returns an error message or None."""
    if not can_power():
        return T("this system can't do that from the agent", "ten system nie umie tego z agenta")
    r = subprocess.run(["systemctl", action], capture_output=True, text=True, timeout=15)
    if r.returncode != 0:
        return f"{r.stderr.strip()} " + T("(polkit rule from install.sh?)", "(reguła polkit z install.sh?)")
    return None


# ---------------------------------------------------------------- network and Wake-on-LAN

def net_info(client_ip):
    """The interface through which the client is reachable: name, IP, MAC, Wake-on setting (ethtool/nmcli), whether Wi-Fi."""
    dev, src = None, None
    if not LINUX:  # no interface name or MAC: the address from the route to the client (a UDP connect sends nothing)
        try:
            with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
                s.connect((client_ip if client_ip and not client_ip.startswith("127.") else "192.168.0.1", 9))
                src = s.getsockname()[0]
        except OSError:
            pass
        return {"iface": None, "ip": src, "mac": None, "wake_on": None, "wireless": False}
    # client on the same computer (lo): the interface of the default route
    for target in (client_ip, "1.1.1.1"):
        try:
            out = subprocess.run(["ip", "-o", "route", "get", target], capture_output=True, text=True).stdout
        except OSError:
            break
        m = re.search(r"\bdev (\S+)", out)
        dev = m.group(1) if m else None
        m = re.search(r"\bsrc (\S+)", out)
        src = m.group(1) if m else None
        if dev != "lo":
            break
    mac = None
    if dev:
        try:
            with open(f"/sys/class/net/{dev}/address") as f:
                mac = f.read().strip()
        except OSError:
            pass
    wol = None
    if dev and shutil.which("ethtool"):
        out = subprocess.run(["ethtool", dev], capture_output=True, text=True).stdout
        m = re.search(r"^\s*Wake-on:\s*(\S+)", out, re.M)
        wol = m.group(1) if m else None
    if dev and not wol and shutil.which("nmcli"):  # ethtool without root cannot read Wake-on
        out = subprocess.run(["nmcli", "-t", "-f", "GENERAL.CONNECTION", "device", "show", dev],
                             capture_output=True, text=True).stdout
        con = out.strip().partition(":")[2]
        if con:
            v = subprocess.run(["nmcli", "-g", "802-3-ethernet.wake-on-lan", "connection", "show", con],
                               capture_output=True, text=True).stdout.strip()
            wol = "g" if "magic" in v else (v or None)
    return {"iface": dev, "ip": src, "mac": mac, "wake_on": wol,
            "wireless": bool(dev and os.path.isdir(f"/sys/class/net/{dev}/wireless"))}


def wake_info(net):
    """Whether this computer can be woken over the network: confidence `maybe` or `no` (with a reason). Only the app
    knows `yes`, once a wake has worked; the BIOS may have WoL disabled, which is not visible from here."""
    mac = net.get("mac")
    if not mac or mac == "00:00:00:00:00:00":
        return {"method": None, "mac": None, "confidence": "no", "reason": T("network card unknown", "nie znam karty sieciowej")}
    base = {"method": "wol", "mac": mac}
    if net.get("wireless"):
        return {**base, "confidence": "no", "reason": T("the computer is on Wi-Fi", "komputer jest na Wi-Fi")}
    wol = net.get("wake_on")
    if wol and "g" in wol:
        return {**base, "confidence": "maybe", "reason": T("the card has Wake-on-LAN, but the BIOS may block it",
                                                          "karta ma Wake-on-LAN, nie wiadomo, czy BIOS go nie blokuje")}
    if wol:
        return {**base, "confidence": "no", "reason": T(f"Wake-on-LAN is off on the card ({wol}), run install.sh",
                                                       f"Wake-on-LAN wyłączony na karcie ({wol}), uruchom install.sh")}
    return {**base, "confidence": "maybe", "reason": T("unknown whether the card has Wake-on-LAN", "nie wiadomo, czy karta ma Wake-on-LAN")}


def send_wol(mac, ip=None):
    """Magic packet to the general broadcast and the /24 of address `ip`, ports 9 and 7."""
    b = bytes(int(x, 16) for x in re.split(r"[:-]", mac))
    if len(b) != 6:
        raise ValueError(T(f"invalid MAC: {mac}", f"zły MAC: {mac}"))
    data = b"\xff" * 6 + b * 16
    targets = ["255.255.255.255"]
    m = re.fullmatch(r"(\d+\.\d+\.\d+)\.\d+", ip or "")
    if m:
        targets.append(m.group(1) + ".255")
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
        s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        for t in targets:
            for port in (9, 7):
                try:
                    s.sendto(data, (t, port))
                except OSError:
                    pass
