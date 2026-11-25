"""What the agent and its route modules share: the config file (load, save), the log, the API error, where the web page
and the published APK live, the address on the local network."""
import json
import os
import secrets
import socket
import sys
import threading
import time


CONFIG_PATH = os.environ.get("MAGNETICPLAYER_CONFIG") or os.path.expanduser("~/.config/magneticplayer/config.json")


CONFIG_DIR = os.path.dirname(CONFIG_PATH)


WEB_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "web")  # web client (server/web)


APK_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "apk")  # publish.sh puts the APK here


def apk_version():
    """Version of the app published on the computer (phone/tools/publish.sh); phones update themselves."""
    try:
        with open(os.path.join(APK_DIR, "version.json")) as f:
            v = json.load(f)
        if os.path.isfile(os.path.join(APK_DIR, "MagneticPlayer.apk")):
            return v
    except (OSError, ValueError):
        pass
    return {"version_code": 0}
WINDOWS = os.name == "nt"
DEFAULT_CONFIG = {
    "port": 8765,
    "token": "",
    "id": "",  # stable server identifier (the app recognises a server by it, not by IP)
    "name": "",  # name shown in the app; empty = hostname
    "backend": "deluge",  # deluge | transmission | none (a server without torrents, e.g. screen only)
    "deluge_url": "http://127.0.0.1:8112/json",
    "deluge_password": "deluge",
    "transmission_url": "http://127.0.0.1:9091/transmission/rpc",
    "transmission_user": "",
    "transmission_password": "",
    "keep_awake": True,
    # unlock the lock screen on "On the computer" and "Turn on screen"; anyone with a token can then unlock the desktop
    "auto_unlock": True,
    "mpv_args": ["--fs", "--save-position-on-quit"],
}


def log(*a):
    print(time.strftime("%H:%M:%S"), *a, file=sys.stderr, flush=True)


def json_default(o):
    """`default=` for json.dump(s): a numpy scalar (np.bool_, np.float64, … from the speech and fingerprint maths)
    becomes the plain Python value; anything else is still an error."""
    if type(o).__module__ == "numpy" and hasattr(o, "item"):
        return o.item()
    raise TypeError(f"Object of type {type(o).__name__} is not JSON serializable")


def load_config():
    cfg = dict(DEFAULT_CONFIG)
    if os.path.exists(CONFIG_PATH):
        with open(CONFIG_PATH) as f:
            cfg.update(json.load(f))
    if not cfg["token"] or not cfg["id"]:
        cfg["token"] = cfg["token"] or secrets.token_urlsafe(18)
        cfg["id"] = cfg["id"] or secrets.token_hex(8)
        os.makedirs(CONFIG_DIR, exist_ok=True)
        with open(CONFIG_PATH, "w") as f:
            json.dump(cfg, f, indent=2)
        os.chmod(CONFIG_PATH, 0o600)
    return cfg
_config_lock = threading.Lock()


def save_config(cfg):
    """Several threads save the config (devices, settings, tasks): one at a time, each with its own temp file."""
    with _config_lock:
        os.makedirs(CONFIG_DIR, exist_ok=True)
        tmp = f"{CONFIG_PATH}.{threading.get_ident()}.tmp"
        with open(tmp, "w") as f:
            json.dump(cfg, f, indent=2, ensure_ascii=False)
        os.chmod(tmp, 0o600)
        os.replace(tmp, CONFIG_PATH)


class ApiError(Exception):
    def __init__(self, msg, status=500):
        super().__init__(msg)
        self.status = status


def lan_ip():
    """Address on the local network (nothing is sent: a UDP connect only picks the route)."""
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(("192.168.0.1", 9))
            return s.getsockname()[0]
    except OSError:
        return socket.gethostbyname(socket.gethostname())
