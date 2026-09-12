#!/usr/bin/env bash
#
# keliver-store-recover — rebind an existing document store to an app that has
# moved, been renamed, or been split across two paths.
#
#   keliver-store-recover.sh <app-dir> [--store DIR] [--home DIR] [--dry-run]
#
# This is the supported answer to U23/U24. It exists because the alternative
# was hand-editing <store>/owner, which nothing documented, and because the
# conflict message used to recommend a recovery the relay then refused.
#
# WHAT IT DOES: rewrites <store>/owner to this app's canonical path, and
# rewrites the app's pointer. That is all.
#
# WHAT IT NEVER DOES: read, write, move, copy or generate key material; copy or
# merge documents or bundles; touch a second store. The identity is preserved
# because nothing under keys/ is opened at all — the fingerprints printed
# before and after are proof, not a promise.
#
# It refuses, changing nothing, when:
#   * the store has no owner marker (nothing to recover — just start);
#   * the recorded owner still exists AND still resolves to this same store
#     (two live apps, not a relocation);
#   * this app already resolves to a DIFFERENT store holding its own identity
#     or documents (adopting would abandon one or merge two — it never merges);
#   * another recovery holds the lock.
#
# An absent owner path is NOT what authorizes this. What authorizes it is you
# running it and naming both sides; the checks above catch mistakes, they do
# not establish ownership.
#
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
# The resolver sits next to this script in a bundle (bin/) and one level up in
# the repository (scripts/).
if   [ -x "$HERE/keliver-store-path.sh" ];            then RESOLVE="$HERE/keliver-store-path.sh"
elif [ -x "$HERE/../scripts/keliver-store-path.sh" ]; then RESOLVE="$HERE/../scripts/keliver-store-path.sh"
else echo "keliver-store-path.sh not found next to $HERE" >&2; exit 2; fi

APP="${1:?usage: $0 <app-dir> [--store DIR] [--home DIR] [--dry-run]}"; shift
STORE=""; HOME_DIR=""; DRY=0
while [ $# -gt 0 ]; do
  case "$1" in
    --store)   STORE="${2:?--store needs a value}"; shift 2 ;;
    --home)    HOME_DIR="${2:?--home needs a value}"; shift 2 ;;
    --dry-run) DRY=1; shift ;;
    -h|--help) sed -n '2,32p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done
[ -d "$APP" ] || { echo "no such app dir: $APP" >&2; exit 2; }
APP="$(cd "$APP" && pwd -P)"
# bash 3.2 (the macOS system bash) errors on "${arr[@]}" for an EMPTY array
# under `set -u`, so every expansion below uses the +-guarded form.
HOME_ARGS=()
[ -n "$HOME_DIR" ] && HOME_ARGS=(--home "$HOME_DIR")
resolve() { "$RESOLVE" "$@" ${HOME_ARGS[@]+"${HOME_ARGS[@]}"}; }

die()  { printf '✗ %s\n' "$1" >&2; exit 1; }
say()  { printf '%s\n' "$1"; }

# The identity of a store, printable: SHA-256 of the PUBLIC key. ed25519.priv
# is never opened.
fingerprint() {
  if [ -r "$1/keys/ed25519.pub" ]; then shasum -a 256 "$1/keys/ed25519.pub" | cut -c1-16
  elif [ -d "$1" ]; then echo "none yet"
  else echo "(no store)"; fi
}

# --- 1. which store ----------------------------------------------------------
if [ -z "$STORE" ]; then
  STORE="$(resolve "$APP")" || die \
    "cannot tell which store this app is bound to. Name it: --store <dir>"
fi
[ -d "$STORE" ] || die "no store at $STORE"
STORE="$(cd "$STORE" && pwd -P)"

say "app:   $APP"
say "store: $STORE"
say "identity before: $(fingerprint "$STORE")"

OWNER_FILE="$STORE/owner"
[ -f "$OWNER_FILE" ] || die "$STORE has no owner marker — there is nothing to recover. Just start the portal."
OLD="$(tr -d '\n' < "$OWNER_FILE")"
say "recorded owner:  ${OLD:-(empty)}"

if [ "$OLD" = "$APP" ]; then
  say "✓ already bound to this app; nothing to do"
  exit 0
fi

# --- 2. refuse the ambiguous cases ------------------------------------------
# (a) the recorded owner is still a live app using this same store.
if [ -n "$OLD" ] && [ -d "$OLD" ]; then
  THEIRS="$(resolve "$OLD" 2>/dev/null)" || THEIRS=""
  if [ -n "$THEIRS" ] && [ "$(cd "$THEIRS" 2>/dev/null && pwd -P || echo /nonexistent)" = "$STORE" ]; then
    die "$OLD still exists and still uses this store. That is two apps, not a relocation.
  Nothing was changed. If that directory is a leftover copy, remove it (or its
  .gradle/keliver-store-path) first, then re-run."
  fi
fi

# (b) this app already has a store of its own with something in it.
MINE="$(resolve "$APP" --default 2>/dev/null)" || MINE=""
if [ -n "$MINE" ] && [ -d "$MINE" ] && [ "$(cd "$MINE" && pwd -P)" != "$STORE" ]; then
  HAS_KEYS=0; HAS_DOCS=0
  [ -f "$MINE/keys/ed25519.pub" ] && HAS_KEYS=1
  find "$MINE" -mindepth 2 -maxdepth 2 -name '*.json' -print -quit 2>/dev/null | grep -q . && HAS_DOCS=1
  if [ "$HAS_KEYS" = 1 ] || [ "$HAS_DOCS" = 1 ]; then
    die "this app already has its own store at
    $MINE   (identity $(fingerprint "$MINE"))
  Adopting $STORE would abandon that one, and two stores are never merged.
  Nothing was changed. Move $MINE aside if you are sure which identity you want."
  fi
fi

if [ "$DRY" = 1 ]; then
  say "✓ dry run: this recovery would be accepted"
  exit 0
fi

# --- 3. take the lock and rebind --------------------------------------------
# mkdir is the atomic create. Two concurrent recoveries: one wins, the loser
# exits before touching either store.
LOCK="$STORE/owner.lock"
mkdir "$LOCK" 2>/dev/null || die "another recovery is in progress on $STORE (lock: $LOCK).
  Nothing was changed. If no other recovery is running, remove that directory."
cleanup() { rmdir "$LOCK" 2>/dev/null || true; }
trap cleanup EXIT INT TERM

# Re-read under the lock: the marker may have changed between the checks above
# and now.
NOW="$(tr -d '\n' < "$OWNER_FILE")"
[ "$NOW" = "$OLD" ] || die "the owner marker changed while this ran ($OLD -> $NOW). Nothing was changed."

TMP="$STORE/.owner.recover.$$"
printf '%s\n' "$APP" > "$TMP" || die "cannot write inside $STORE"
mv -f "$TMP" "$OWNER_FILE" || { rm -f "$TMP"; die "could not replace the owner marker"; }

mkdir -p "$APP/.gradle"
printf '%s\n' "$STORE" > "$APP/.gradle/keliver-store-path"

say "identity after:  $(fingerprint "$STORE")"
say "✓ $STORE is now bound to $APP"
say "  keys, documents and bundles were not read, copied or moved."
say "  $OLD was not modified."
