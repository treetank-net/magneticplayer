#!/bin/bash
# Wydanie w GitLab CI (job release w .gitlab-ci.yml) na chroniony tag v1.RRMMDD.GGMM, wzorem timeprotect:
# build APK podpisanego kluczem treetank (ten sam co timeprotect, alias „treetank”; zmiana klucza = nowa instalacja),
# kontrola odcisku certyfikatu, paczka agenta z manifestem podpisanym ECDSA P-256, wydanie na GitHubie.
# Klucze tylko w chronionych zmiennych CI projektu ze środowiskiem „release” (nigdy w gicie ani w zwykłych jobach):
#   MP_KEYSTORE_B64, MP_KEYSTORE_PASS (kopie TP_KEYSTORE_* z treetank/timeprotect-android), MP_KEY_PASS (opcjonalnie),
#   MP_AGENT_KEY_B64 (PEM klucza paczki agenta, base64).
# Buildy lokalne (emulator, testy) podpisuje klucz deweloperski phone/magneticplayer.keystore: inny certyfikat.
# Lokalnie wydanie to tylko: phone/tools/tag-release.sh
set -euo pipefail
cd "$(dirname "$0")/.."
REPO=treetank-net/magneticplayer
EXPECTED_CERT=05f72b11239022e9256185257b8926693e39e445c89c96b926613cd7de6e39ce  # treetank
IMG=ghcr.io/cirruslabs/android-sdk:36
TAG=${CI_COMMIT_TAG:?tylko w CI na tagu}
VN=${TAG#v}
[[ $VN =~ ^1\.[0-9]{6}\.[0-9]{4}$ ]] || { echo "tag $TAG: oczekiwany v1.RRMMDD.GGMM" >&2; exit 1; }
[ "${CI_COMMIT_REF_PROTECTED:-}" = true ] || { echo "tag $TAG nie jest chroniony" >&2; exit 1; }
for v in MP_KEYSTORE_B64 MP_KEYSTORE_PASS MP_AGENT_KEY_B64 GITHUB_TREETANK_TOKEN; do
  [ -n "${!v:-}" ] || { echo "brak zmiennej CI $v" >&2; exit 1; }
done

# 1. APK: klucz na dysku tylko na czas buildu
umask 077
K=$(mktemp -d)
trap 'rm -rf "$K" magneticplayer-ci.keystore keystore.properties' EXIT
base64 -d <<<"$MP_KEYSTORE_B64" > magneticplayer-ci.keystore
printf 'storeFile=magneticplayer-ci.keystore\nstorePassword=%s\nkeyAlias=treetank\nkeyPassword=%s\n' \
  "$MP_KEYSTORE_PASS" "${MP_KEY_PASS:-$MP_KEYSTORE_PASS}" > keystore.properties
umask 022
CPUS=2 MEM=5g VN=$VN tools/build-apk.sh
rm -f magneticplayer-ci.keystore keystore.properties
CERT=$(docker run --rm -v "$PWD/out:/o:ro" "$IMG" bash -c '/opt/android-sdk-linux/build-tools/*/apksigner verify --print-certs /o/MagneticPlayer.apk' \
  | sed -n '0,/certificate SHA-256 digest: /s/.*certificate SHA-256 digest: //p')
[ "$CERT" = "$EXPECTED_CERT" ] || { echo "certyfikat APK $CERT != $EXPECTED_CERT" >&2; exit 1; }

# 2. paczka agenta i podpisany manifest (wersja w manifeście: apka nie przyjmie starszej paczki jako nowszej)
REL=out/release
tools/agent-bundle.sh "$REL"
read -r CT _ < "$REL/version.txt"
AGENT=$(sed -n 's/^VERSION = "\(.*\)"/\1/p' ../server/magneticplayer_agent.py)
python3 - "$REL" "$CT" "$CI_COMMIT_SHA" "$AGENT" "$VN" <<'P'
import hashlib, json, sys
rel, ct, commit, agent, apk = sys.argv[1:]
tgz = open(f"{rel}/agent.tgz", "rb").read()
json.dump({"format": 1, "ct": int(ct), "commit": commit, "agent_version": agent, "apk_version": apk,
           "sha256": hashlib.sha256(tgz).hexdigest(), "size": len(tgz)}, open(f"{rel}/agent.json", "w"))
P
(umask 077; base64 -d <<<"$MP_AGENT_KEY_B64" > "$K/agent.pem")
openssl dgst -sha256 -sign "$K/agent.pem" -out "$REL/agent.json.sig" "$REL/agent.json"
rm -f "$K/agent.pem"
openssl dgst -sha256 -verify tools/agent-signing.pub -signature "$REL/agent.json.sig" "$REL/agent.json"
cp out/MagneticPlayer.apk out/version.json "$REL/"

# 3. GitHub: tag przepycha lustro (job mirror-github), wydanie dopiero na nim
for _ in $(seq 1 60); do
  [ -n "$(git ls-remote "https://github.com/$REPO.git" "refs/tags/$TAG")" ] && break
  sleep 10
done
PREV=$(git describe --tags --abbrev=0 "$CI_COMMIT_SHA^" 2>/dev/null || true)
{
  echo "Agent $AGENT, apka $VN, commit ${CI_COMMIT_SHA:0:7}."
  echo
  echo "Instalacja: README. Paczkę agenta (agent.tgz) apka pobiera sama i sprawdza podpis manifestu (agent.json.sig)."
  echo
  echo "Zmiany:"
  git log --no-merges --format='- %s' ${PREV:+"$PREV..$CI_COMMIT_SHA"} | head -40
} > "$REL/notes.md"
GITHUB_TOKEN=$GITHUB_TREETANK_TOKEN python3 -I tools/github-release.py "$REPO" "$TAG" "MagneticPlayer $VN" "$REL/notes.md" \
  "$REL/MagneticPlayer.apk" "$REL/version.json" "$REL/agent.tgz" "$REL/agent.json" "$REL/agent.json.sig"
