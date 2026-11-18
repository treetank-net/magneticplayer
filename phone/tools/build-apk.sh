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
  echo "generated signing key: phone/magneticplayer.keystore (do not lose it: app updates must be signed with the same one)"
fi
mkdir -p .gradle-home out
# agent w apce: telefon sam serwuje instalator nowemu komputerowi (InstallShare.kt), bez innego serwera
# (dependencies: pip, mpv, Python on Windows; a new computer downloads them from the internet anyway).
# Same content as the bundle served by the agent (mp_install.bundle); join.* templates are separate so the phone can fill in the address.
tools/agent-bundle.sh app/src/main/assets/agent
# public key for verifying agent bundles from releases (AgentBundle.kt); without it the app uses only the bundle built into the APK
[ -f tools/agent-signing.pub ] && cp tools/agent-signing.pub app/src/main/assets/agent/signing.pub
# versionCode grows on its own (minutes since 2025-01-01), so updates are always "newer"
VC=$(( ($(date +%s) - 1735689600) / 60 ))
VN=${VN:-"1.$(date +%y%m%d.%H%M)"}  # a CI release passes the version from the tag
nice -n 19 docker run --rm --cpus="${CPUS:-4}" --memory="${MEM:-6g}" \
  -e GRADLE_USER_HOME=/gh -v "$ROOT/.gradle-home:/gh" -v "$ROOT:/w" -w /w "$IMG" bash -c "
    chmod +x gradlew
    ./gradlew --no-daemon -q --max-workers=3 -Dorg.gradle.jvmargs=-Xmx3g assembleRelease -PvCode=$VC -PvName=$VN; r=\$?
    cp -f app/build/outputs/apk/release/app-release.apk out/MagneticPlayer.apk 2>/dev/null
    chown -R $(id -u):$(id -g) /w /gh
    exit \$r"
printf '{"version_code": %s, "version_name": "%s"}\n' "$VC" "$VN" > out/version.json
echo "OK: $ROOT/out/MagneticPlayer.apk ($VN)"
