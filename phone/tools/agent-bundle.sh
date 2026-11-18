#!/bin/bash
# Paczka agenta (pliki z server/ śledzone przez git) + szablony join.* + version.txt („<czas commita> <commit>”).
# Używają jej build-apk.sh (agent zaszyty w APK) i tools/release.sh (agent w wydaniu na GitHubie, z podpisem).
# Użycie: phone/tools/agent-bundle.sh <katalog wyjściowy>
set -euo pipefail
OUT=$(realpath -m "$1")
REPO=$(cd "$(dirname "$0")/../.." && pwd)
rm -rf "$OUT" && mkdir -p "$OUT"
# nie .tar.gz: AAPT rozpakowuje takie assets
( cd "$REPO" && git ls-files -z server | grep -zE '^server/([^/]+\.(py|sh|ps1)|web/[^/]+)$' \
    | xargs -0 tar --owner=0 --group=0 --numeric-owner -czf "$OUT/agent.tgz" )
cp "$REPO/server/join.sh" "$REPO/server/join.ps1" "$OUT/"
# czas ostatniego commita z server/ rośnie razem z kodem agenta: po nim apka wybiera nowszą z paczek (wbudowana / wydanie)
# AGENT_CT: tylko do testów pobierania nowszej paczki z wydania (sztucznie stara wbudowana)
echo "${AGENT_CT:-$(git -C "$REPO" log -1 --format=%ct -- server)} $(git -C "$REPO" rev-parse --short HEAD)" > "$OUT/version.txt"
