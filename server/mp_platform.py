"""Wszystko, co zależy od systemu: sesja graficzna, ekran blokady, usypianie, sieć, Wake-on-LAN.

Pełnie Linux (systemd, logind, X11/Wayland). Windows (instalacja lekka, install.ps1): ekran przez mpv, adres IP;
bez odblokowania, usypiania i Wake-on-LAN. Czego system nie umie, funkcje zwracają „nie umiem” (False/None),
a agent nie zgłasza tego w /api/info, więc apka chowa odpowiednie przyciski.
"""
import os
import re
import shutil
import socket
import subprocess
import sys
import time

LINUX = sys.platform.startswith("linux")
WINDOWS = os.name == "nt"
HERE = os.path.dirname(os.path.abspath(__file__))
SESSIONS = ("cinnamon-session", "cinnamon", "xfce4-session", "mate-session", "gnome-shell", "plasmashell",
            "lxsession", "lxqt-session", "budgie-wm", "kwin_wayland", "kwin_x11", "labwc", "wayfire")


def _log(*a):
    print(time.strftime("%H:%M:%S"), *a, file=sys.stderr, flush=True)


def _session_env():
    """Środowisko procesu sesji graficznej tego użytkownika albo None, gdy nikt nie jest zalogowany graficznie."""
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
    """Zmienne środowiskowe sesji graficznej użytkownika (usługa systemd ich nie ma)."""
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
    """mpv z PATH albo obok agenta (Windows: install.ps1 kładzie go w <agent>/mpv/mpv.exe)."""
    local = os.path.join(HERE, "mpv", "mpv.exe" if WINDOWS else "mpv")
    return shutil.which("mpv") or (local if os.path.isfile(local) else None)


def has_display():
    """Czy da się tu wyświetlić film: mpv + zalogowana sesja graficzna (albo jawnie ustawiony DISPLAY)."""
    if not mpv_path():
        return False
    if WINDOWS:  # agent startuje z logowaniem użytkownika (HKCU\...\Run), więc działa w jego sesji
        return True
    return _session_env() is not None or bool(os.environ.get("DISPLAY") or os.environ.get("WAYLAND_DISPLAY"))


# ---------------------------------------------------------------- ekran

def can_unlock():
    return LINUX and bool(shutil.which("loginctl"))


def unlock_screen():
    """Zdejmuje ekran blokady przez logind (sygnał Unlock); cinnamon-screensaver, GNOME i KDE go słuchają.
    polkit pozwala na to właścicielowi sesji bez hasła. Zwraca True, gdy się udało."""
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
    """Budzi monitor i (gdy `unlock`) zdejmuje ekran blokady."""
    if not LINUX:
        return False
    env = graphical_env()
    # bez "cinnamon-screensaver-command --deactivate": w Cinnamon 5.4 wywala wygaszacz (brak grab_helper)
    # i wisi; ekran blokady zdejmuje unlock_screen() przez logind
    for cmd in (["xset", "dpms", "force", "on"], ["xdg-screensaver", "reset"]):
        if shutil.which(cmd[0]):
            try:
                subprocess.run(cmd, env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=3)
            except (OSError, subprocess.SubprocessError) as e:
                _log("ekran:", cmd[0], e)
    return unlock_screen() if unlock else False


# ---------------------------------------------------------------- usypianie i zasilanie

def can_power():
    return LINUX and bool(shutil.which("systemctl"))


def inhibit_sleep(why):
    """Proces trzymający blokadę usypiania (zabicie go zdejmuje blokadę) albo None."""
    if not (LINUX and shutil.which("systemd-inhibit")):
        return None
    proc = subprocess.Popen(
        ["systemd-inhibit", "--what=sleep", "--who=MagneticPlayer", f"--why={why}", "--mode=block",
         "sleep", "infinity"], stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
    time.sleep(0.5)
    if proc.poll() is not None:
        _log("systemd-inhibit odmówił:", proc.stderr.read().decode().strip(), "(brak reguły polkit? uruchom install.sh)")
        return None
    return proc


def power(action):
    """`suspend` albo `poweroff`. Zwraca komunikat błędu albo None."""
    if not can_power():
        return "ten system nie umie tego z agenta"
    r = subprocess.run(["systemctl", action], capture_output=True, text=True, timeout=15)
    if r.returncode != 0:
        return f"{r.stderr.strip()} (reguła polkit z install.sh?)"
    return None


# ---------------------------------------------------------------- sieć i Wake-on-LAN

def net_info(client_ip):
    """Karta, przez którą widać klienta: nazwa, IP, MAC, ustawienie Wake-on (ethtool/nmcli), czy Wi-Fi."""
    dev, src = None, None
    if not LINUX:  # bez nazwy karty i MAC: adres z trasy do klienta (connect na UDP nic nie wysyła)
        try:
            with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
                s.connect((client_ip if client_ip and not client_ip.startswith("127.") else "192.168.0.1", 9))
                src = s.getsockname()[0]
        except OSError:
            pass
        return {"iface": None, "ip": src, "mac": None, "wake_on": None, "wireless": False}
    # klient na tym samym komputerze (lo): karta z trasy domyślnej
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
    if dev and not wol and shutil.which("nmcli"):  # ethtool bez roota nie czyta Wake-on
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
    """Czy da się obudzić ten komputer przez sieć: confidence `maybe` albo `no` (z powodem). `yes` wie tylko apka,
    gdy raz się udało; BIOS może mieć WoL wyłączony, czego stąd nie widać."""
    mac = net.get("mac")
    if not mac or mac == "00:00:00:00:00:00":
        return {"method": None, "mac": None, "confidence": "no", "reason": "nie znam karty sieciowej"}
    base = {"method": "wol", "mac": mac}
    if net.get("wireless"):
        return {**base, "confidence": "no", "reason": "komputer jest na Wi-Fi"}
    wol = net.get("wake_on")
    if wol and "g" in wol:
        return {**base, "confidence": "maybe", "reason": "karta ma Wake-on-LAN, nie wiadomo, czy BIOS go nie blokuje"}
    if wol:
        return {**base, "confidence": "no", "reason": f"Wake-on-LAN wyłączony na karcie ({wol}), uruchom install.sh"}
    return {**base, "confidence": "maybe", "reason": "nie wiadomo, czy karta ma Wake-on-LAN"}


def send_wol(mac, ip=None):
    """Magic packet na broadcast ogólny i /24 adresu `ip`, porty 9 i 7."""
    b = bytes(int(x, 16) for x in re.split(r"[:-]", mac))
    if len(b) != 6:
        raise ValueError(f"zły MAC: {mac}")
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
