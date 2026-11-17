#!/bin/bash
# Buduje APK (release, podpisany kluczem z keystore.properties) w kontenerze Android SDK.
# Wynik: phone/out/MagneticPlayer.apk
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT=$PWD
IMG=${SDK_IMAGE:-ghcr.io/cirruslabs/android-sdk:36}
if [ ! -f keystore.properties ]; then
  PW=$(head -c 18 /dev/urandom | base64 | tr -d '/+=')
  keytool -genkeypair -keystore magneticplayer.keystore -alias magneticplayer -keyalg RSA -keysize 2048 \
    -validity 20000 -storepass "$PW" -keypass "$PW" -dname "CN=MagneticPlayer" >/dev/null
  printf 'storeFile=magneticplayer.keystore\nstorePassword=%s\nkeyAlias=magneticplayer\nkeyPassword=%s\n' "$PW" "$PW" > keystore.properties
  echo "wygenerowano klucz podpisu: phone/magneticplayer.keystore (nie gub — aktualizacje apki muszą mieć ten sam)"
fi
mkdir -p .gradle-home out
# agent w apce: telefon sam serwuje instalator nowemu komputerowi (InstallShare.kt), bez innego serwera
# (zależności: pip, mpv, Python na Windows, nowy komputer i tak pobiera z internetu).
# Ta sama zawartość co paczka z serwera (mp_install.bundle); szablony join.* osobno, żeby telefon podstawił adres.
AG=app/src/main/assets/agent
rm -rf "$AG" && mkdir -p "$AG"
( cd "$ROOT/.." && git ls-files -z server | grep -zE '^server/([^/]+\.(py|sh|ps1)|web/[^/]+)$' \
    | xargs -0 tar --owner=0 --group=0 --numeric-owner -czf "$ROOT/$AG/agent.tgz" )
cp ../server/join.sh ../server/join.ps1 "$AG/"
git -C .. describe --always --dirty > "$AG/version.txt" 2>/dev/null || true
# versionCode rośnie sam (minuty od 2025-01-01), żeby aktualizacje zawsze były „nowsze”
VC=$(( ($(date +%s) - 1735689600) / 60 ))
VN="1.$(date +%y%m%d.%H%M)"
nice -n 19 docker run --rm --cpus="${CPUS:-4}" --memory="${MEM:-6g}" \
  -e GRADLE_USER_HOME=/gh -v "$ROOT/.gradle-home:/gh" -v "$ROOT:/w" -w /w "$IMG" bash -c "
    chmod +x gradlew
    ./gradlew --no-daemon -q --max-workers=3 -Dorg.gradle.jvmargs=-Xmx3g assembleRelease -PvCode=$VC -PvName=$VN; r=\$?
    cp -f app/build/outputs/apk/release/app-release.apk out/MagneticPlayer.apk 2>/dev/null
    chown -R $(id -u):$(id -g) /w /gh
    exit \$r"
printf '{"version_code": %s, "version_name": "%s"}\n' "$VC" "$VN" > out/version.json
echo "OK: $ROOT/out/MagneticPlayer.apk ($VN)"
