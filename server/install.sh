#!/bin/bash
# Install the MagneticPlayer agent on a computer (Linux Mint). Run as a regular user:
#   bash install.sh            # full install (root part via pkexec)
#   SKIP_ROOT=1 bash install.sh  # user part only (when install-root.sh is run separately)
#   BACKEND=none bash install.sh # server without torrents (only a mpv screen and/or a library), no Deluge
#   PLAYER=0 POWER=0 …           # no screen (no mpv) / no sleep and power-off from the network (e.g. a work laptop)
#   PORT=8766 …                  # another port (when 8765 is taken)
#   LITE=1 bash install.sh       # lite mode (someone else's computer): no root or password, no torrents, no sleep
#                                  from the network and no WoL; files, upload from the phone and the screen (if mpv exists) remain
#   JOIN=http://<serwer>:8765/i/<kod> …  # set by the script from /i/<code>: after install it reports there, the phone pairs by itself
# Uninstall: bash ~/.local/share/magneticplayer/uninstall.sh [--purge]
set -euo pipefail
# messages: Polish when the system locale is Polish (LC_ALL / LC_MESSAGES / LANG), English otherwise
case "${LC_ALL:-${LC_MESSAGES:-${LANG:-}}}" in pl*) MP_LANG=pl ;; *) MP_LANG=en ;; esac
say() { if [ "$MP_LANG" = pl ]; then echo "$2"; else echo "$1"; fi; }
if [ -n "${LITE:-}" ] && [ "$LITE" != 0 ]; then
  SKIP_ROOT=1
  BACKEND=${BACKEND:-none}
  POWER=${POWER:-0}
  say "== lite install: no root, backend=$BACKEND, no sleep over the network" "== tryb lekki: bez roota, backend=$BACKEND, bez usypiania z sieci"
  command -v python3 >/dev/null || { say "python3 is missing: install it or use the full install." "Brak python3: zainstaluj go albo użyj pełnej instalacji."; exit 1; }
  systemctl --user show-environment >/dev/null 2>&1 || { say "No systemd --user (user session): the lite install needs it." "Brak systemd --user (sesji użytkownika): tryb lekki tego wymaga."; exit 1; }
fi
if command -v python3 >/dev/null && ! python3 -c 'import sys; sys.exit(sys.version_info < (3, 8))'; then
  say "Python too old ($(python3 -V 2>&1)): the agent needs 3.8 or newer (Ubuntu 20.04+, Debian 11+)." \
      "Za stary Python ($(python3 -V 2>&1)): agent potrzebuje 3.8 albo nowszego (Ubuntu 20.04+, Debian 11+)."; exit 1
fi
SRC=$(cd "$(dirname "$0")" && pwd)
DEST=~/.local/share/magneticplayer
UNITS=~/.config/systemd/user
PORT_SET=${PORT+x}
PORT=${PORT:-8765}
BACKEND_SET=${BACKEND+x}
BACKEND=${BACKEND:-deluge}

if [ -z "${SKIP_ROOT:-}" ]; then
  # pkexec only in a local graphical session; over SSH polkit has no session ("No session for cookie")
  if [ -z "${SSH_CONNECTION:-}" ] && [ -n "${DISPLAY:-}${WAYLAND_DISPLAY:-}" ] && ! sudo -n true 2>/dev/null; then
    pkexec bash "$SRC/install-root.sh" "$USER" "$PORT" "$BACKEND" "$MP_LANG"  # pkexec drops LANG
  else
    sudo bash "$SRC/install-root.sh" "$USER" "$PORT" "$BACKEND" "$MP_LANG"
  fi
fi

echo "== agent -> $DEST"
mkdir -p "$DEST" "$UNITS"
install -m 755 "$SRC/magneticplayer_agent.py" "$DEST/"
install -m 644 "$SRC"/mp_*.py "$DEST/"
install -d "$DEST/web" && install -m 644 "$SRC"/web/* "$DEST/web/"
# scripts next to the agent: the server uses them to install the agent on further computers (mp_install.py, /i/<code>)
install -m 755 "$SRC/uninstall.sh" "$SRC/install.sh" "$SRC/install-root.sh" "$SRC/join.sh" "$DEST/"
[ -f "$SRC/join.ps1" ] && install -m 644 "$SRC/join.ps1" "$SRC/install.ps1" "$SRC/uninstall.ps1" "$DEST/"

say "== subtitles: subliminal in a venv ($DEST/venv)" "== napisy: subliminal w venv ($DEST/venv)"
# --clear: after a failed attempt (python3-venv missing); without a venv the agent works, only without subtitles and
# Chromecast from the web page.
# Without python3-venv (fresh Ubuntu/Debian): a venv without pip plus pip from bootstrap.pypa.io, so no root needed.
[ -x "$DEST/venv/bin/pip" ] || python3 -m venv --clear "$DEST/venv" >/dev/null 2>&1 || {
  rm -rf "$DEST/venv"
  if python3 -m venv --without-pip "$DEST/venv" >/dev/null 2>&1 && command -v curl >/dev/null &&
     curl -fsSL https://bootstrap.pypa.io/get-pip.py | "$DEST/venv/bin/python" - -q >/dev/null 2>&1; then
    say "venv without python3-venv: pip from bootstrap.pypa.io" "venv bez python3-venv: pip z bootstrap.pypa.io"
  else
    rm -rf "$DEST/venv"
    say "WARNING: can't create a venv (python3-venv package missing). The agent works without subtitles and Chromecast from the web page." \
        "UWAGA: nie da się założyć venv (brak pakietu python3-venv). Agent działa bez napisów i Chromecasta ze strony."
  fi
}
if [ -x "$DEST/venv/bin/pip" ]; then
  "$DEST/venv/bin/pip" install --isolated -q --upgrade subliminal ||
    say "WARNING: subliminal didn't install — subtitles won't work" "UWAGA: subliminal się nie zainstalował — napisy nie zadziałają"
  # Chromecast controlled from the web client (the phone app has its own Cast SDK)
  "$DEST/venv/bin/pip" install --isolated -q --upgrade pychromecast ||
    say "WARNING: pychromecast didn't install — Chromecast from the browser won't work" "UWAGA: pychromecast się nie zainstalował — Chromecast z przeglądarki nie zadziała"
  # pairing QR in the terminal when qrencode is missing (the lite install doesn't install packages)
  "$DEST/venv/bin/pip" install --isolated -q --upgrade qrcode || true
fi

# settings given explicitly (BACKEND, PLAYER, POWER) go to config.json; the rest of the config is kept
LITE_ON=; [ -n "${LITE:-}" ] && [ "$LITE" != 0 ] && LITE_ON=1
if [ -n "$BACKEND_SET$PORT_SET${PLAYER+x}${POWER+x}$LITE_ON" ]; then
  echo "== config: ${BACKEND_SET:+backend=$BACKEND }${PORT_SET:+port=$PORT }${PLAYER+player=$PLAYER }${POWER+power=$POWER}"
  python3 - "${BACKEND_SET:+$BACKEND}" "${PLAYER-}" "${POWER-}" "${PORT_SET:+$PORT}" "$LITE_ON" <<'EOF'
import json, os, sys
p = os.environ.get("MAGNETICPLAYER_CONFIG") or os.path.expanduser("~/.config/magneticplayer/config.json")
cfg = json.load(open(p)) if os.path.exists(p) else {}
backend, player, power, port, lite = sys.argv[1:6]
if lite:
    cfg["wake"] = False
if port:
    cfg["port"] = int(port)
if backend:
    cfg["backend"] = backend
if player:
    cfg["player"] = player not in ("0", "no", "false")
if power:
    cfg["power"] = power not in ("0", "no", "false")
os.makedirs(os.path.dirname(p), exist_ok=True)
json.dump(cfg, open(p, "w"), indent=2, ensure_ascii=False)
os.chmod(p, 0o600)
EOF
fi

# without torrents the library is just a directory of videos: when none exists we create the XDG videos dir, so that
# upload from the phone and the Library have somewhere to write (the agent itself picks ~/Wideo, ~/Videos, ~/Filmy)
if [ "$BACKEND" = none ]; then
  VID=$(xdg-user-dir VIDEOS 2>/dev/null || true)
  [ -z "$VID" ] || [ "$VID" = "$HOME" ] && VID=$HOME/Videos
  if [ ! -d ~/Wideo ] && [ ! -d ~/Videos ] && [ ! -d ~/Filmy ] && [ ! -d "$VID" ]; then
    mkdir -p "$VID" && say "== library: created $VID" "== biblioteka: założono $VID"
  fi
fi

if [ "$BACKEND" = deluge ]; then
say "== Deluge: client-server mode (the GTK app connects to deluged instead of keeping torrents itself)" \
    "== Deluge: tryb klient-serwer (GTK łączy się z deluged zamiast trzymać torrenty w sobie)"
pkill -x deluge-gtk 2>/dev/null && { say "closed the Deluge window" "zamknięto okno Deluge"; sleep 2; } || true
pkill -f "bin/deluge$" 2>/dev/null || true
python3 - <<'EOF'
import json, os, re
p = os.path.expanduser("~/.config/deluge/gtk3ui.conf")
if os.path.exists(p):
    raw = open(p).read()
    # Deluge format: two JSON objects one after another (format version + data)
    dec = json.JSONDecoder()
    head, i = dec.raw_decode(raw)
    data, _ = dec.raw_decode(raw, re.compile(r"\s*").match(raw, i).end())
    if data.get("standalone", True) or not data.get("autoconnect"):
        data["standalone"] = False
        data["autoconnect"] = True
        data["show_connection_manager_on_start"] = False
        open(p, "w").write(json.dumps(head, indent=4) + json.dumps(data, indent=4))
        print("gtk3ui.conf: standalone=false, autoconnect=true")
EOF

cat > "$UNITS/deluged.service" <<EOF
[Unit]
Description=Deluge daemon (MagneticPlayer)
After=network-online.target

[Service]
ExecStart=/usr/bin/deluged -d -l $HOME/.cache/deluged.log -L warning
Restart=on-failure

[Install]
WantedBy=default.target
EOF

cat > "$UNITS/deluge-web.service" <<EOF
[Unit]
Description=Deluge Web UI (MagneticPlayer)
After=deluged.service
Requires=deluged.service

[Service]
ExecStart=/usr/bin/deluge-web -d -i 127.0.0.1 -p 8112
Restart=on-failure

[Install]
WantedBy=default.target
EOF
fi

cat > "$UNITS/magneticplayer.service" <<EOF
[Unit]
Description=MagneticPlayer agent
After=deluge-web.service network-online.target

[Service]
ExecStart=/usr/bin/python3 $DEST/magneticplayer_agent.py
Restart=always
RestartSec=3

[Install]
WantedBy=default.target
EOF

systemctl --user daemon-reload
[ "$BACKEND" = deluge ] && systemctl --user enable --now deluged deluge-web
systemctl --user enable magneticplayer
# linger: the agent starts with the computer and keeps running after logout (someone else's computer may have no owner logged in)
if [ -n "${LITE:-}" ] && [ "$LITE" != 0 ]; then
  if [ "$(loginctl show-user "$USER" -p Linger --value 2>/dev/null)" != yes ]; then
    # marker: uninstall.sh disables linger only if we enabled it
    loginctl enable-linger </dev/null 2>/dev/null && touch "$DEST/.linger" &&
      say "linger: the agent runs even when nobody is logged in" "linger: agent działa także bez zalogowania" ||
      say "WARNING: without linger the agent only runs while $USER is logged in" "UWAGA: bez linger agent działa tylko, gdy $USER jest zalogowany"
  fi
fi
systemctl --user restart magneticplayer
sleep 2

echo
systemctl --user --no-pager --lines=5 status magneticplayer | head -12 || true
echo
echo "================ MagneticPlayer ================"
PY=python3; [ -x "$DEST/venv/bin/python" ] && PY="$DEST/venv/bin/python"
if [ -n "${JOIN:-}" ] && python3 "$DEST/magneticplayer_agent.py" --join "$JOIN"; then
  say " If the phone doesn't pair, get a code manually: python3 $DEST/magneticplayer_agent.py --pair" \
      " Gdyby telefon się nie sparował, kod ręczny: python3 $DEST/magneticplayer_agent.py --pair"
else
  "$PY" "$DEST/magneticplayer_agent.py" --pair
fi
say " another code:    python3 $DEST/magneticplayer_agent.py --pair" \
    " kolejny kod:     python3 $DEST/magneticplayer_agent.py --pair"
say " devices:         python3 $DEST/magneticplayer_agent.py --devices / --revoke <id>" \
    " urządzenia:      python3 $DEST/magneticplayer_agent.py --devices / --revoke <id>"
say " uninstall:       bash $DEST/uninstall.sh   (--purge also removes settings and paired phones)" \
    " odinstalowanie:  bash $DEST/uninstall.sh   (z --purge usuwa też ustawienia i sparowane telefony)"
echo "==============================================="
