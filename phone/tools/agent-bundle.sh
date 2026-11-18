#!/bin/bash
# Agent bundle (git-tracked files from server/) + join.* templates + version.txt ("<commit time> <commit>").
# Used by build-apk.sh (agent embedded in the APK) and tools/release.sh (agent in the GitHub release, signed).
# Usage: phone/tools/agent-bundle.sh <output directory>
set -euo pipefail
OUT=$(realpath -m "$1")
REPO=$(cd "$(dirname "$0")/../.." && pwd)
rm -rf "$OUT" && mkdir -p "$OUT"
# nie .tar.gz: AAPT rozpakowuje takie assets
( cd "$REPO" && git ls-files -z server | grep -zE '^server/([^/]+\.(py|sh|ps1)|web/[^/]+)$' \
    | xargs -0 tar --owner=0 --group=0 --numeric-owner -czf "$OUT/agent.tgz" )
cp "$REPO/server/join.sh" "$REPO/server/join.ps1" "$OUT/"
# the time of the last commit touching server/ grows with the agent code: the app uses it to pick the newer bundle (built-in / release)
# AGENT_CT: only for testing the download of a newer bundle from a release (artificially old built-in one)
echo "${AGENT_CT:-$(git -C "$REPO" log -1 --format=%ct -- server)} $(git -C "$REPO" rev-parse --short HEAD)" > "$OUT/version.txt"
