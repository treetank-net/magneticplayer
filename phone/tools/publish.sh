#!/bin/bash
# Publikuje przetestowaną apkę na komputerze (agent MagneticPlayer) — inne telefony dostaną „Jest aktualizacja”.
# Użycie: phone/tools/publish.sh [--release vTAG|latest] [host-ssh]   (domyślnie jmpc)
# Telefony mają apkę z kluczem treetank (wydania z CI), więc aktualizacją może być tylko APK z wydania:
# --release pobiera MagneticPlayer.apk i version.json z GitHuba do out/. Bez niego publikuje out/ (np. własny build
# na komputerze z apką podpisaną tym samym kluczem co out/).
set -euo pipefail
cd "$(dirname "$0")/.."
if [ "${1:-}" = --release ]; then
  TAG=${2:?podaj tag wydania albo latest}; shift 2
  [ "$TAG" = latest ] && BASE=https://github.com/treetank-net/magneticplayer/releases/latest/download \
    || BASE=https://github.com/treetank-net/magneticplayer/releases/download/$TAG
  mkdir -p out
  curl -fsSL -o out/MagneticPlayer.apk.dl "$BASE/MagneticPlayer.apk"
  curl -fsSL -o out/version.json.dl "$BASE/version.json"
  mv -f out/MagneticPlayer.apk.dl out/MagneticPlayer.apk && mv -f out/version.json.dl out/version.json
fi
HOST=${1:-jmpc}
[ -f out/MagneticPlayer.apk ] && [ -f out/version.json ] || { echo "najpierw tools/build-apk.sh"; exit 1; }
cat out/version.json; echo
ssh "$HOST" 'mkdir -p ~/.local/share/magneticplayer/apk'
scp -q out/MagneticPlayer.apk "$HOST":.local/share/magneticplayer/apk/MagneticPlayer.apk.new
scp -q out/version.json "$HOST":.local/share/magneticplayer/apk/version.json.new
# apka najpierw, wersja na końcu — telefon nie zobaczy nowej wersji, zanim plik będzie kompletny
ssh "$HOST" 'cd ~/.local/share/magneticplayer/apk && mv -f MagneticPlayer.apk.new MagneticPlayer.apk && mv -f version.json.new version.json'
echo "opublikowano na $HOST"
