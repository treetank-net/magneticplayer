#!/bin/bash
# Część instalacji wymagająca roota. Uruchamiana przez install.sh: pkexec bash install-root.sh <user>
# Idempotentna — można puszczać wielokrotnie.
set -euo pipefail
U=${1:?podaj użytkownika}
PORT=${2:-8765}
BACKEND=${3:-deluge}

echo "== pakiety"
need=()
pkgs=(mpv ethtool qrencode python3-venv ffmpeg)  # ffmpeg: wersje „dla Chromecasta”
[ "$BACKEND" = deluge ] && pkgs+=(deluged deluge-web)
for p in "${pkgs[@]}"; do
  dpkg -s "$p" >/dev/null 2>&1 || need+=("$p")
done
if [ ${#need[@]} -gt 0 ]; then
  apt-get update -q
  DEBIAN_FRONTEND=noninteractive apt-get install -y -q "${need[@]}"
fi

echo "== systemowe usługi deluged/deluge-web wyłączone (działają jako usługi użytkownika $U, na jego torrentach)"
for s in deluged deluge-web; do
  systemctl disable --now "$s" 2>/dev/null || true
done

echo "== polkit: $U może blokować usypianie i usypiać komputer z usługi w tle"
mkdir -p /etc/polkit-1/rules.d
cat > /etc/polkit-1/rules.d/49-magneticplayer.rules <<EOF
// MagneticPlayer: agent działa jako usługa systemd --user (poza sesją graficzną)
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
  echo "UWAGA: $DEV to Wi-Fi — Wake-on-LAN po Wi-Fi zwykle nie działa. Podłącz komputer kablem."
else
  CON=$(nmcli -t -f NAME,DEVICE connection show --active | awk -F: -v d="$DEV" '$2==d{print $1; exit}')
  if [ -n "$CON" ]; then
    nmcli connection modify "$CON" 802-3-ethernet.wake-on-lan magic
    echo "NetworkManager: połączenie '$CON' ma wake-on-lan=magic"
  fi
  # karta musi mieć też włączone budzenie po stronie PCI (sysfs); trwale przez regułę udev
  cat > /etc/udev/rules.d/81-magneticplayer-wol.rules <<UDEV
ACTION=="add", SUBSYSTEM=="net", NAME=="$DEV", RUN+="/usr/sbin/ethtool -s $DEV wol g", RUN+="/bin/sh -c 'echo enabled > /sys/class/net/$DEV/device/power/wakeup'"
UDEV
  echo enabled > "/sys/class/net/$DEV/device/power/wakeup" 2>/dev/null || true
  ethtool -s "$DEV" wol g || echo "ethtool: karta $DEV nie przyjęła wol g (sprawdź BIOS/UEFI: Wake on LAN / PME)"
  ethtool "$DEV" | grep -E "Wake-on" || true
fi

echo "== firewall"
if command -v ufw >/dev/null && ufw status | grep -q "Status: active"; then
  ufw allow from 192.168.0.0/16 to any port "$PORT" proto tcp comment "MagneticPlayer"
  ufw allow from 10.0.0.0/8 to any port "$PORT" proto tcp comment "MagneticPlayer"
fi
echo "== root: gotowe"
