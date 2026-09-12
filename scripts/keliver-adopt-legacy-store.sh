#!/usr/bin/env bash
#
# keliver-adopt-legacy-store — give ONE app the legacy shared store's contents.
#
#   scripts/keliver-adopt-legacy-store.sh <app-dir> [--legacy DIR] [--force]
#
# Before per-app stores everything lived in ~/.keliver-portal. That directory
# cannot be attributed to a repo on its own — several apps may have written to
# it — so nothing is adopted automatically. You name the app; this COPIES the
# signing identity, published bundles and documents into that app's store.
#
# Non-destructive by contract:
#   * the legacy directory is only ever READ; nothing is moved or deleted
#   * an existing file in the target is never overwritten without --force
#   * key material is copied, never printed
#
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
# The resolver sits next to this script in a bundle (bin/) and one level up in
# the repository (scripts/). Find it either way.
keliver_store_path_script() {
  local here; here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
  if [ -x "$here/keliver-store-path.sh" ]; then echo "$here/keliver-store-path.sh"
  elif [ -x "$here/../scripts/keliver-store-path.sh" ]; then echo "$here/../scripts/keliver-store-path.sh"
  else echo "keliver-store-path.sh not found next to $here" >&2; return 1; fi
}

APP="${1:?usage: $0 <app-dir> [--legacy DIR] [--force]}"; shift
LEGACY=""; FORCE=0
while [ $# -gt 0 ]; do
  case "$1" in
    --legacy) LEGACY="${2:?--legacy needs a value}"; shift 2 ;;
    --force)  FORCE=1; shift ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done
[ -d "$APP" ] || { echo "no such app dir: $APP" >&2; exit 2; }
APP="$(cd "$APP" && pwd -P)"
if [ -z "$LEGACY" ]; then
  JVM_HOME="$(java -XshowSettings:properties -version 2>&1 | awk -F'= ' '/^ *user\.home/ {print $2; exit}')"
  LEGACY="$JVM_HOME/.keliver-portal"
fi
[ -d "$LEGACY" ] || { echo "no legacy store at $LEGACY — nothing to adopt" >&2; exit 1; }

TARGET="$("$(keliver_store_path_script)" "$APP")"
[ "$(cd "$LEGACY" && pwd -P)" != "$(cd "$TARGET" 2>/dev/null && pwd -P || echo x)" ] || {
  echo "this app already uses the legacy store directly; nothing to do"; exit 0; }

echo "adopting into: $TARGET"
echo "          from: $LEGACY  (read-only)"
mkdir -p "$TARGET"

copied=0; skipped=0
copy_one() { # relative path
  local rel="$1" src="$LEGACY/$1" dst="$TARGET/$1"
  [ -e "$src" ] || return 0
  if [ -e "$dst" ] && [ "$FORCE" != 1 ]; then
    echo "  skip (exists): $rel"; skipped=$((skipped+1)); return 0
  fi
  mkdir -p "$(dirname "$dst")"
  cp -R "$src" "$dst"
  echo "  copied: $rel"; copied=$((copied+1))
}

copy_one "keys/ed25519.priv"
copy_one "keys/ed25519.pub"
for d in "$LEGACY"/*/; do
  [ -d "$d" ] || continue
  name="$(basename "$d")"
  # `apps` is where per-app stores live now, not legacy content.
  case "$name" in apps|keys|kotlin) continue ;; esac
  if [ "$name" = "bundles" ]; then
    for b in "$d"*/; do [ -d "$b" ] && copy_one "bundles/$(basename "$b")"; done
    continue
  fi
  # Documents are adopted PER FILE. Copying the project directory as a unit
  # skipped everything the moment the target had one document of its own —
  # which it does as soon as the relay has booted once.
  for j in "$d"*.json; do [ -f "$j" ] && copy_one "$name/$(basename "$j")"; done
done
copy_one "active"

echo "copied $copied item(s), skipped $skipped; $LEGACY is unchanged"
[ "$skipped" -gt 0 ] && echo "re-run with --force to overwrite the skipped items"
exit 0
