#!/bin/bash
# Release: tag v1.YYMMDD.HHMM on a pushed main; GitLab CI does the rest (build, signatures, GitHub) via tools/release-ci.sh.
set -euo pipefail
cd "$(dirname "$0")/../.."
[ -z "$(git status --porcelain --untracked-files=no)" ] || { echo "uncommitted changes" >&2; exit 1; }
git fetch -q origin main
[ "$(git rev-parse HEAD)" = "$(git rev-parse origin/main)" ] || { echo "HEAD differs from origin/main: push first" >&2; exit 1; }
# agents update themselves only to a higher VERSION: agent code changed since the last release without a bump = refused
PREV=$(git describe --tags --abbrev=0 --match 'v1.*' 2>/dev/null || true)
if [ -n "$PREV" ] && ! git diff --quiet "$PREV" HEAD -- server; then
  v() { git show "$1:server/magneticplayer_agent.py" | sed -n 's/^VERSION = "\(.*\)"/\1/p'; }
  [ "$(v "$PREV")" != "$(v HEAD)" ] || { echo "server/ changed since $PREV but VERSION is still $(v HEAD): bump it" >&2; exit 1; }
fi
TAG="v1.$(date +%y%m%d.%H%M)"
git tag -a "$TAG" -m "MagneticPlayer ${TAG#v}${1:+: $1}"
git push -q origin "$TAG"
git push -q ovh "$TAG" 2>/dev/null || true
echo "$TAG pushed; CI: https://gitlab.com/treetank/magneticplayer/-/pipelines"
