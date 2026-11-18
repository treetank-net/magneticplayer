#!/bin/bash
# MagneticPlayer: instalacja z innego serwera albo z telefonu w tej sieci (kod jednorazowy). Szablon: adres i tryb
# podstawia serwer (mp_install.py) albo telefon (InstallShare.kt).
mp_main() {
  set -euo pipefail
  local url="@URL@"
  # komunikaty: po polsku, gdy system ma polski (LC_ALL / LC_MESSAGES / LANG), inaczej po angielsku
  local pl=
  case "${LC_ALL:-${LC_MESSAGES:-${LANG:-}}}" in pl*) pl=1 ;; esac
  say() { if [ -n "$pl" ]; then echo "$2"; else echo "$1"; fi; }
  command -v python3 >/dev/null || { say "python3 is missing — install it (sudo apt install python3) and try again." \
    "Brak python3 — zainstaluj go (sudo apt install python3) i spróbuj ponownie."; exit 1; }
  python3 -c 'import sys; sys.exit(sys.version_info < (3, 8))' ||
    { say "Python too old ($(python3 -V 2>&1)): the agent needs 3.8 or newer (Ubuntu 20.04+, Debian 11+)." \
        "Za stary Python ($(python3 -V 2>&1)): agent potrzebuje 3.8 albo nowszego (Ubuntu 20.04+, Debian 11+)."; exit 1; }
  tmp=$(mktemp -d)
  trap 'rm -rf "${tmp:-}"' EXIT
  say "== downloading the agent from $url" "== pobieram agenta z $url"
  if command -v curl >/dev/null; then curl -fsSL "$url/bundle.tar.gz" -o "$tmp/mp.tgz"
  else wget -qO "$tmp/mp.tgz" "$url/bundle.tar.gz"; fi
  tar xzf "$tmp/mp.tgz" -C "$tmp"
  LITE=@LITE@ JOIN="$url" bash "$tmp/server/install.sh" </dev/null
}
mp_main "$@"
