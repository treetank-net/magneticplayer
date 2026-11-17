#!/bin/bash
# Wydanie: tag v1.RRMMDD.GGMM na wypchniętym main; resztę (build, podpisy, GitHub) robi GitLab CI (tools/release-ci.sh).
set -euo pipefail
cd "$(dirname "$0")/../.."
[ -z "$(git status --porcelain --untracked-files=no)" ] || { echo "niezacommitowane zmiany" >&2; exit 1; }
git fetch -q origin main
[ "$(git rev-parse HEAD)" = "$(git rev-parse origin/main)" ] || { echo "HEAD różni się od origin/main: najpierw push" >&2; exit 1; }
TAG="v1.$(date +%y%m%d.%H%M)"
git tag -a "$TAG" -m "MagneticPlayer ${TAG#v}${1:+: $1}"
git push -q origin "$TAG"
git push -q ovh "$TAG" 2>/dev/null || true
echo "$TAG wypchnięty; CI: https://gitlab.com/treetank/magneticplayer/-/pipelines"
