#!/bin/bash
# Publikuje przetestowaną apkę na komputerze (agent MagneticPlayer) — inne telefony dostaną „Jest aktualizacja”.
# Użycie: phone/tools/publish.sh [host-ssh]   (domyślnie jmpc)
set -euo pipefail
cd "$(dirname "$0")/.."
HOST=${1:-jmpc}
[ -f out/MagneticPlayer.apk ] && [ -f out/version.json ] || { echo "najpierw tools/build-apk.sh"; exit 1; }
cat out/version.json; echo
ssh "$HOST" 'mkdir -p ~/.local/share/magneticplayer/apk'
scp -q out/MagneticPlayer.apk "$HOST":.local/share/magneticplayer/apk/MagneticPlayer.apk.new
scp -q out/version.json "$HOST":.local/share/magneticplayer/apk/version.json.new
# apka najpierw, wersja na końcu — telefon nie zobaczy nowej wersji, zanim plik będzie kompletny
ssh "$HOST" 'cd ~/.local/share/magneticplayer/apk && mv -f MagneticPlayer.apk.new MagneticPlayer.apk && mv -f version.json.new version.json'
echo "opublikowano na $HOST"
