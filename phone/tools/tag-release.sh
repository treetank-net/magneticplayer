#!/bin/bash
# Release: tag v1.YYMMDD.HHMM on a pushed main; GitLab CI does the rest (build, signatures, GitHub) via tools/release-ci.sh.
set -euo pipefail
cd "$(dirname "$0")/../.."
[ -z "$(git status --porcelain --untracked-files=no)" ] || { echo "uncommitted changes" >&2; exit 1; }
git fetch -q origin main
[ "$(git rev-parse HEAD)" = "$(git rev-parse origin/main)" ] || { echo "HEAD differs from origin/main: push first" >&2; exit 1; }
TAG="v1.$(date +%y%m%d.%H%M)"
git tag -a "$TAG" -m "MagneticPlayer ${TAG#v}${1:+: $1}"
git push -q origin "$TAG"
git push -q ovh "$TAG" 2>/dev/null || true
echo "$TAG pushed; CI: https://gitlab.com/treetank/magneticplayer/-/pipelines"
