#!/bin/bash
# Agent self-update (started by mp_selfupdate.py in its own systemd --user unit, so it survives the agent restart).
# Usage: self-update.sh <new bundle's server/ dir> <agent dir> <port> <expected version>
# Backs up the current agent to <agent dir>/prev, installs the new one (install.sh with UPDATE=1: files, Python
# packages, restart) and waits up to 90 s for /api/ping with the new version; otherwise restores prev and restarts.
# The outcome goes to <agent dir>/update-result.json, the log to <agent dir>/update.log.
set -u
SRC=$1 DEST=$2 PORT=$3 WANT=$4
exec >>"$DEST/update.log" 2>&1
echo "== $(date -Is) update to $WANT from $SRC"

result() {  # ok error
  python3 - "$DEST/update-result.json" "$WANT" "$1" "$2" <<'EOF'
import json, sys, time
p, v, ok, err = sys.argv[1:]
json.dump({"version": v, "ok": ok == "1", "error": err or None, "time": time.time()}, open(p, "w"))
EOF
}

healthy() {
  for _ in $(seq 1 45); do
    sleep 2
    python3 - "$PORT" "$WANT" <<'EOF' && return 0
import json, sys, urllib.request
try:
    r = json.load(urllib.request.urlopen(f"http://127.0.0.1:{sys.argv[1]}/api/ping", timeout=3))
except Exception:
    sys.exit(1)
sys.exit(0 if r.get("version") == sys.argv[2] else 1)
EOF
  done
  return 1
}

PREV=$DEST/prev
rm -rf "$PREV" && mkdir -p "$PREV/web" || { result 0 "can't create $PREV"; exit 1; }
cp -p "$DEST"/*.py "$DEST"/*.sh "$PREV/" && cp -p "$DEST"/web/* "$PREV/web/" && { cp -p "$DEST"/*.ps1 "$PREV/" 2>/dev/null; true; } ||
  { result 0 "backup failed"; exit 1; }

if UPDATE=1 SKIP_ROOT=1 bash "$SRC/install.sh" && healthy; then
  echo "== ok: $WANT is running"
  result 1 ""
  rm -rf "$DEST/update"
  exit 0
fi

echo "== failed, restoring the previous agent"
journalctl --user -u magneticplayer -n 30 --no-pager 2>/dev/null | tail -30
cp -p "$PREV"/*.py "$PREV"/*.sh "$DEST/" && cp -p "$PREV"/web/* "$DEST/web/" && { cp -p "$PREV"/*.ps1 "$DEST/" 2>/dev/null; true; }
systemctl --user restart magneticplayer
result 0 "the new agent didn't start (log: $DEST/update.log)"
rm -rf "$DEST/update"
exit 1
