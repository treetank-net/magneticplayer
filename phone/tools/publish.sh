#!/bin/bash
# Publishes a tested app on the computer (MagneticPlayer agent); other phones will get "Update available".
# Usage: phone/tools/publish.sh [--release vTAG|latest] [ssh-host]   (default jmpc)
# Phones have the app signed with the treetank key (CI releases), so only an APK from a release can serve as an update:
# --release downloads MagneticPlayer.apk and version.json from GitHub into out/. Without it, it publishes out/ (e.g. your own build
# on a computer with an app signed with the same key as out/).
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
# app first, version last: the phone won't see the new version before the file is complete
ssh "$HOST" 'cd ~/.local/share/magneticplayer/apk && mv -f MagneticPlayer.apk.new MagneticPlayer.apk && mv -f version.json.new version.json'
echo "opublikowano na $HOST"
