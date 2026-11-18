#!/bin/bash
# Instalacja agenta MagneticPlayer na komputerze (Linux Mint). Uruchom jako zwykły użytkownik:
#   bash install.sh            # pełna instalacja (część rootowa przez pkexec)
#   SKIP_ROOT=1 bash install.sh  # tylko część użytkownika (gdy install-root.sh puszczony osobno)
#   BACKEND=none bash install.sh # serwer bez torrentów (tylko ekran z mpv i/lub biblioteka), bez Deluge
#   PLAYER=0 POWER=0 …           # nie ekran (bez mpv) / bez usypiania i wyłączania z sieci (np. laptop do pracy)
#   PORT=8766 …                  # inny port (gdy 8765 jest zajęty)
#   LITE=1 bash install.sh       # tryb lekki (cudzy komputer): bez roota i hasła, bez torrentów, bez usypiania
#                                  z sieci i WoL; zostają pliki, przesyłanie z telefonu i ekran, jeśli jest mpv
#   JOIN=http://<serwer>:8765/i/<kod> …  # ustawia skrypt z /i/<kod>: po instalacji zgłasza się tam, telefon paruje sam
# Odinstalowanie: bash ~/.local/share/magneticplayer/uninstall.sh [--purge]
set -euo pipefail
# komunikaty: po polsku, gdy system ma polski (LC_ALL / LC_MESSAGES / LANG), inaczej po angielsku
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
  # pkexec tylko w lokalnej sesji graficznej; przez SSH polkit nie ma sesji ("No session for cookie")
  if [ -z "${SSH_CONNECTION:-}" ] && [ -n "${DISPLAY:-}${WAYLAND_DISPLAY:-}" ] && ! sudo -n true 2>/dev/null; then
    pkexec bash "$SRC/install-root.sh" "$USER" "$PORT" "$BACKEND" "$MP_LANG"  # pkexec gubi LANG
  else
    sudo bash "$SRC/install-root.sh" "$USER" "$PORT" "$BACKEND" "$MP_LANG"
  fi
fi

echo "== agent -> $DEST"
mkdir -p "$DEST" "$UNITS"
install -m 755 "$SRC/magneticplayer_agent.py" "$DEST/"
install -m 644 "$SRC"/mp_*.py "$DEST/"
install -d "$DEST/web" && install -m 644 "$SRC"/web/* "$DEST/web/"
# skrypty obok agenta: z nich serwer instaluje agenta na kolejnych komputerach (mp_install.py, /i/<kod>)
install -m 755 "$SRC/uninstall.sh" "$SRC/install.sh" "$SRC/install-root.sh" "$SRC/join.sh" "$DEST/"
[ -f "$SRC/join.ps1" ] && install -m 644 "$SRC/join.ps1" "$SRC/install.ps1" "$SRC/uninstall.ps1" "$DEST/"

say "== subtitles: subliminal in a venv ($DEST/venv)" "== napisy: subliminal w venv ($DEST/venv)"
# --clear: po nieudanej próbie (brak python3-venv); bez venv agent działa, tylko bez napisów i Chromecasta ze strony
# Bez python3-venv (świeże Ubuntu/Debian): venv bez pip i pip z bootstrap.pypa.io, więc bez roota.
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
  # Chromecast sterowany z klienta webowego (apka na telefonie ma własny Cast SDK)
  "$DEST/venv/bin/pip" install --isolated -q --upgrade pychromecast ||
    say "WARNING: pychromecast didn't install — Chromecast from the browser won't work" "UWAGA: pychromecast się nie zainstalował — Chromecast z przeglądarki nie zadziała"
  # QR parowania w terminalu, gdy nie ma qrencode (tryb lekki nie instaluje pakietów)
  "$DEST/venv/bin/pip" install --isolated -q --upgrade qrcode || true
fi

# ustawienia podane jawnie (BACKEND, PLAYER, POWER) trafiają do config.json; reszta configu zostaje
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

# bez torrentów biblioteka to tylko katalog z filmami: gdy go nie ma, zakładamy ~/Wideo (XDG), żeby przesyłanie
# z telefonu i Biblioteka miały dokąd zapisywać (agent sam bierze ~/Wideo, ~/Videos, ~/Filmy)
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
# linger: agent startuje z komputerem i działa po wylogowaniu (cudzy komputer bywa bez zalogowanego właściciela)
if [ -n "${LITE:-}" ] && [ "$LITE" != 0 ]; then
  if [ "$(loginctl show-user "$USER" -p Linger --value 2>/dev/null)" != yes ]; then
    # znacznik: uninstall.sh wyłącza linger tylko wtedy, gdy włączyliśmy go my
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
