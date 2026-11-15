#!/bin/bash
# Instalacja agenta MagneticPlayer na komputerze (Linux Mint). Uruchom jako zwykły użytkownik:
#   bash install.sh            # pełna instalacja (część rootowa przez pkexec)
#   SKIP_ROOT=1 bash install.sh  # tylko część użytkownika (gdy install-root.sh puszczony osobno)
set -euo pipefail
SRC=$(cd "$(dirname "$0")" && pwd)
DEST=~/.local/share/magneticplayer
UNITS=~/.config/systemd/user
PORT=8765

if [ -z "${SKIP_ROOT:-}" ]; then
  # pkexec tylko w lokalnej sesji graficznej; przez SSH polkit nie ma sesji ("No session for cookie")
  if [ -z "${SSH_CONNECTION:-}" ] && [ -n "${DISPLAY:-}${WAYLAND_DISPLAY:-}" ] && ! sudo -n true 2>/dev/null; then
    pkexec bash "$SRC/install-root.sh" "$USER" "$PORT"
  else
    sudo bash "$SRC/install-root.sh" "$USER" "$PORT"
  fi
fi

echo "== agent -> $DEST"
mkdir -p "$DEST" "$UNITS"
install -m 755 "$SRC/magneticplayer_agent.py" "$DEST/"
install -m 644 "$SRC"/mp_*.py "$DEST/"
install -d "$DEST/web" && install -m 644 "$SRC"/web/* "$DEST/web/"

echo "== napisy: subliminal w venv ($DEST/venv)"
[ -x "$DEST/venv/bin/python" ] || python3 -m venv "$DEST/venv"
"$DEST/venv/bin/pip" install --isolated -q --upgrade subliminal || echo "UWAGA: subliminal się nie zainstalował — napisy nie zadziałają"

echo "== Deluge: tryb klient-serwer (GTK łączy się z deluged zamiast trzymać torrenty w sobie)"
pkill -x deluge-gtk 2>/dev/null && { echo "zamknięto okno Deluge"; sleep 2; } || true
pkill -f "bin/deluge$" 2>/dev/null || true
python3 - <<'EOF'
import json, os, re
p = os.path.expanduser("~/.config/deluge/gtk3ui.conf")
if os.path.exists(p):
    raw = open(p).read()
    # format Deluge: dwa obiekty JSON jeden po drugim (wersja formatu + dane)
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
systemctl --user enable --now deluged deluge-web
systemctl --user enable magneticplayer
systemctl --user restart magneticplayer
sleep 2

TOKEN=$(python3 "$DEST/magneticplayer_agent.py" --print-token)
DEV=$(ip -o route get 1.1.1.1 | sed -n 's/.* dev \([^ ]*\).*/\1/p')
IP=$(ip -o route get 1.1.1.1 | sed -n 's/.* src \([^ ]*\).*/\1/p')
MAC=$(cat "/sys/class/net/$DEV/address")
URL="magneticplayer://pair?host=$IP&port=$PORT&token=$TOKEN&mac=$MAC"

echo
systemctl --user --no-pager --lines=5 status magneticplayer | head -12 || true
echo
echo "================ MagneticPlayer ================"
echo " komputer: $IP:$PORT   MAC: $MAC ($DEV)"
echo " token:    $TOKEN"
echo " parowanie: $URL"
command -v qrencode >/dev/null && qrencode -t ansiutf8 "$URL"
echo "==============================================="
