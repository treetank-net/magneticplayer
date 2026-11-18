#!/bin/bash
# Odinstalowanie agenta MagneticPlayer (jako zwykły użytkownik):
#   bash ~/.local/share/magneticplayer/uninstall.sh          # agent i usługi; ustawienia i sparowane telefony zostają
#   bash ~/.local/share/magneticplayer/uninstall.sh --purge  # także ~/.config/magneticplayer (token, urządzenia)
# Filmów nie rusza (także „Z telefonu”). Pakietów systemowych (mpv, ffmpeg, deluge) nie odinstalowuje, bo mogą być
# używane przez inne programy. Rootowe dodatki pełnej instalacji (polkit, WoL, zapora) usuwa przez sudo/pkexec.
set -uo pipefail
DEST=~/.local/share/magneticplayer
UNITS=~/.config/systemd/user
CONF=~/.config/magneticplayer
# komunikaty: po polsku, gdy system ma polski (LC_ALL / LC_MESSAGES / LANG), inaczej po angielsku
case "${LC_ALL:-${LC_MESSAGES:-${LANG:-}}}" in pl*) MP_LANG=pl ;; *) MP_LANG=en ;; esac
say() { if [ "$MP_LANG" = pl ]; then echo "$2"; else echo "$1"; fi; }

say "== services" "== usługi"
for u in magneticplayer deluge-web deluged; do
  f="$UNITS/$u.service"
  # usługi Deluge tylko wtedy, gdy założył je nasz install.sh
  if [ -f "$f" ] && { [ "$u" = magneticplayer ] || grep -q "MagneticPlayer" "$f"; }; then
    systemctl --user disable --now "$u" 2>/dev/null
    rm -f "$f"
    say "removed $u" "usunięto $u"
  fi
done
systemctl --user daemon-reload

ROOT_FILES=(/etc/polkit-1/rules.d/49-magneticplayer.rules /etc/udev/rules.d/81-magneticplayer-wol.rules)
have_root=""
for f in "${ROOT_FILES[@]}"; do [ -e "$f" ] && have_root=1; done
if [ -n "$have_root" ]; then
  say "== system extras (polkit, WoL, firewall): administrator password needed" \
      "== dodatki systemowe (polkit, WoL, zapora): potrzebne hasło administratora"
  cmd='rm -f '"${ROOT_FILES[*]}"'
if command -v ufw >/dev/null; then
  ufw status numbered 2>/dev/null | grep MagneticPlayer | sed -n "s/^\[ *\([0-9]*\)\].*/\1/p" | sort -rn | while read -r n; do yes | ufw delete "$n" >/dev/null; done
fi
echo "'"$(say "system extras removed" "dodatki systemowe usunięte")"'"'
  if [ -z "${SSH_CONNECTION:-}" ] && [ -n "${DISPLAY:-}${WAYLAND_DISPLAY:-}" ] && ! sudo -n true 2>/dev/null; then
    pkexec bash -c "$cmd" ||
      say "WARNING: system extras were left (no administrator password): ${ROOT_FILES[*]}" \
          "UWAGA: dodatki systemowe zostały (bez hasła administratora): ${ROOT_FILES[*]}"
  else
    sudo bash -c "$cmd" || say "WARNING: system extras were left: ${ROOT_FILES[*]}" "UWAGA: dodatki systemowe zostały: ${ROOT_FILES[*]}"
  fi
fi

# linger włączony przez instalację lekką (znacznik w $DEST)
[ -f "$DEST/.linger" ] && loginctl disable-linger </dev/null 2>/dev/null && say "linger disabled" "wyłączono linger"

say "== agent files" "== pliki agenta"
rm -rf "$DEST"
say "removed $DEST" "usunięto $DEST"
if [ "${1:-}" = --purge ]; then
  rm -rf "$CONF"
  say "removed $CONF (settings, paired phones)" "usunięto $CONF (ustawienia, sparowane telefony)"
else
  say "settings were kept in $CONF (remove them with --purge or by hand)" "ustawienia zostały w $CONF (usuń je: --purge albo ręcznie)"
fi
say "== MagneticPlayer uninstalled. Your videos were left in place." "== MagneticPlayer odinstalowany. Filmy zostały na miejscu."
