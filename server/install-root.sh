#!/bin/bash
# The part of the installation that needs root. Run by install.sh: pkexec bash install-root.sh <user>
# Idempotent: safe to run repeatedly.
set -euo pipefail
U=${1:?user name required / podaj użytkownika}
PORT=${2:-8765}
BACKEND=${3:-deluge}
# message language: 4th argument from install.sh (pkexec drops LANG), otherwise from the locale
MP_LANG=${4:-${LC_ALL:-${LC_MESSAGES:-${LANG:-}}}}
case "$MP_LANG" in pl*) MP_LANG=pl ;; *) MP_LANG=en ;; esac
say() { if [ "$MP_LANG" = pl ]; then echo "$2"; else echo "$1"; fi; }

say "== packages" "== pakiety"
need=()
pkgs=(mpv ethtool qrencode python3-venv ffmpeg)  # ffmpeg: the "for Chromecast" versions
[ "$BACKEND" = deluge ] && pkgs+=(deluged deluge-web)
for p in "${pkgs[@]}"; do
  dpkg -s "$p" >/dev/null 2>&1 || need+=("$p")
done
if [ ${#need[@]} -gt 0 ]; then
  apt-get update -q
  DEBIAN_FRONTEND=noninteractive apt-get install -y -q "${need[@]}"
fi

say "== system deluged/deluge-web services disabled (they run as user services of $U, on their torrents)" \
    "== systemowe usługi deluged/deluge-web wyłączone (działają jako usługi użytkownika $U, na jego torrentach)"
for s in deluged deluge-web; do
  systemctl disable --now "$s" 2>/dev/null || true
done

say "== polkit: $U may block sleep and put the computer to sleep from a background service" \
    "== polkit: $U może blokować usypianie i usypiać komputer z usługi w tle"
mkdir -p /etc/polkit-1/rules.d
cat > /etc/polkit-1/rules.d/49-magneticplayer.rules <<EOF
// MagneticPlayer: the agent runs as a systemd --user service (outside the graphical session)
polkit.addRule(function(action, subject) {
  if (subject.user == "$U" && (
      action.id == "org.freedesktop.login1.inhibit-block-sleep" ||
      action.id == "org.freedesktop.login1.inhibit-delay-sleep" ||
      action.id == "org.freedesktop.login1.suspend" ||
      action.id == "org.freedesktop.login1.suspend-multiple-sessions" ||
      action.id == "org.freedesktop.login1.power-off" ||
      action.id == "org.freedesktop.login1.power-off-multiple-sessions")) {
    return polkit.Result.YES;
  }
});
EOF

echo "== Wake-on-LAN"
DEV=$(ip -o route get 1.1.1.1 | sed -n 's/.* dev \([^ ]*\).*/\1/p')
if [ -d "/sys/class/net/$DEV/wireless" ]; then
  say "WARNING: $DEV is Wi-Fi — Wake-on-LAN over Wi-Fi usually doesn't work. Connect the computer with a cable." \
      "UWAGA: $DEV to Wi-Fi — Wake-on-LAN po Wi-Fi zwykle nie działa. Podłącz komputer kablem."
else
  CON=$(nmcli -t -f NAME,DEVICE connection show --active | awk -F: -v d="$DEV" '$2==d{print $1; exit}')
  if [ -n "$CON" ]; then
    nmcli connection modify "$CON" 802-3-ethernet.wake-on-lan magic
    say "NetworkManager: connection '$CON' has wake-on-lan=magic" "NetworkManager: połączenie '$CON' ma wake-on-lan=magic"
  fi
  # the card must also have wakeup enabled on the PCI side (sysfs); made permanent by a udev rule
  cat > /etc/udev/rules.d/81-magneticplayer-wol.rules <<UDEV
ACTION=="add", SUBSYSTEM=="net", NAME=="$DEV", RUN+="/usr/sbin/ethtool -s $DEV wol g", RUN+="/bin/sh -c 'echo enabled > /sys/class/net/$DEV/device/power/wakeup'"
UDEV
  echo enabled > "/sys/class/net/$DEV/device/power/wakeup" 2>/dev/null || true
  ethtool -s "$DEV" wol g || say "ethtool: card $DEV didn't accept wol g (check BIOS/UEFI: Wake on LAN / PME)" \
                                "ethtool: karta $DEV nie przyjęła wol g (sprawdź BIOS/UEFI: Wake on LAN / PME)"
  ethtool "$DEV" | grep -E "Wake-on" || true
fi

echo "== firewall"
if command -v ufw >/dev/null && ufw status | grep -q "Status: active"; then
  ufw allow from 192.168.0.0/16 to any port "$PORT" proto tcp comment "MagneticPlayer"
  ufw allow from 10.0.0.0/8 to any port "$PORT" proto tcp comment "MagneticPlayer"
  # "now playing" announcements between remotes (multicast on the LAN)
  ufw allow from 192.168.0.0/16 to any port 47700 proto udp comment "MagneticPlayer"
  ufw allow from 10.0.0.0/8 to any port 47700 proto udp comment "MagneticPlayer"
fi
say "== root: done" "== root: gotowe"
