#!/usr/bin/env bash
#
# keliver-store-recover — rebind an existing document store to an app that has
# moved, been renamed, or been split across two paths.
#
#   keliver-store-recover.sh <app-dir> [--store DIR] [--home DIR] [--dry-run]
#
# This is the supported answer to U23/U24/U25.1. It exists because the
# alternative was hand-editing <store>/owner, which nothing documented, and
# because the conflict message used to recommend a recovery the relay refused.
#
# WHAT IT DOES: rewrites <store>/owner to this app's canonical path and writes
# the app's pointer, as ONE two-sided update, and then proves the ordinary
# resolver selects that store. That is all.
#
# WHAT IT NEVER DOES: read, write, move, copy or generate key material; copy or
# merge documents or bundles; touch a second store. The identity is preserved
# because nothing under keys/ is opened at all — the fingerprints printed
# before and after are proof, not a promise.
#
# SUCCESS MEANS THE BINDING WORKS. Both writes have to land and the resolver
# has to agree afterwards, or the previous binding is put back and this exits
# non-zero. It used to exit 0 having rewritten `owner` while the pointer write
# failed, leaving the store owned by an app that did not resolve to it.
#
# It refuses, changing nothing, when:
#   * the store has no owner marker (nothing to recover — just start);
#   * the recorded owner still exists AND still resolves to this same store
#     (two live apps, not a relocation);
#   * keliver.portal.json pins the app to a different store (a committed
#     setting outranks this command; change the file instead);
#   * the app's pointer or default already selects a DIFFERENT store that holds
#     an identity or documents (adopting would abandon one or merge two — it
#     never merges);
#   * the resolver refuses for a reason this recovery does not resolve;
#   * another recovery, or a starting relay, holds the app lock.
#
# PORTAL_STORE is a ONE-RUN override and is IGNORED here, loudly: a permanent
# binding must not be decided by an environment variable that will be gone on
# the next command. Every resolver call below runs with it unset, including the
# verification, so the result reported is the result you get without it.
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
    -h|--help) sed -n '2,46p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done
[ -d "$APP" ] || { echo "no such app dir: $APP" >&2; exit 2; }
APP="$(cd "$APP" && pwd -P)"

# bash 3.2 (the macOS system bash) errors on "${arr[@]}" for an EMPTY array
# under `set -u`, so the expansion below uses the +-guarded form.
HOME_ARGS=()
[ -n "$HOME_DIR" ] && HOME_ARGS=(--home "$HOME_DIR")

say()  { printf '%s\n' "$1"; }
warn() { printf '!  %s\n' "$1" >&2; }
die()  { printf '✗ %s\n' "$1" >&2; exit 1; }

# Every resolver call runs with PORTAL_STORE unset. RESOLVE_OUT / RESOLVE_RC
# carry the answer; stderr is kept so a refusal can be shown rather than
# swallowed. `|| RESOLVE_RC=$?` — never `2>/dev/null || X=""`, which is how a
# refusal came to be read as "this app has no store".
resolve() {
  RESOLVE_ERR="$(mktemp "${TMPDIR:-/tmp}/keliver-resolve.XXXXXX")"
  RESOLVE_OUT="$(env -u PORTAL_STORE "$RESOLVE" "$@" ${HOME_ARGS[@]+"${HOME_ARGS[@]}"} 2>"$RESOLVE_ERR")"
  RESOLVE_RC=$?
  RESOLVE_MSG="$(cat "$RESOLVE_ERR")"; rm -f "$RESOLVE_ERR"
  return $RESOLVE_RC
}

if [ -n "${PORTAL_STORE:-}" ]; then
  warn "PORTAL_STORE is set ($PORTAL_STORE). It is a one-run override and is IGNORED
   here: a permanent binding must not be decided by it. Resolution and the
   verification below run with it unset."
fi

# The identity of a store, printable: SHA-256 of the PUBLIC key. ed25519.priv
# is never opened.
# Resolved to a command, not a function: macOS also has a /sbin/sha256 binary
# whose output format differs, and Linux has none.
if command -v sha256sum >/dev/null 2>&1; then SHA256_CMD="sha256sum"; else SHA256_CMD="shasum -a 256"; fi
fingerprint() {
  if [ -r "$1/keys/ed25519.pub" ]; then $SHA256_CMD "$1/keys/ed25519.pub" | cut -c1-16
  elif [ -d "$1" ]; then echo "none yet"
  else echo "(no store)"; fi
}
has_content() { # a store that would be abandoned rather than an empty directory
  [ -f "$1/keys/ed25519.pub" ] && return 0
  find "$1" -mindepth 2 -maxdepth 2 -name '*.json' -print -quit 2>/dev/null | grep -q .
}

# --- 1. the app-side destination, before anything is mutated -----------------
# The pointer is half the binding. If it cannot be written there is no recovery
# to report, so this is checked first and checked for real — a probe write, not
# just `-w`, because the failure modes that matter here are ".gradle is a
# regular file" and "the pointer path is a directory".
GRADLE_DIR="$APP/.gradle"
POINTER="$GRADLE_DIR/keliver-store-path"
if [ -e "$GRADLE_DIR" ] && [ ! -d "$GRADLE_DIR" ]; then
  die "$GRADLE_DIR exists and is not a directory, so this app's store pointer
  cannot be written. Nothing was changed."
fi
mkdir -p "$GRADLE_DIR" 2>/dev/null || die "cannot create $GRADLE_DIR. Nothing was changed."
PROBE="$GRADLE_DIR/.keliver-recover-probe.$$"
printf 'probe\n' > "$PROBE" 2>/dev/null || {
  rm -f "$PROBE" 2>/dev/null
  die "$GRADLE_DIR is not writable, so this app's store pointer cannot be
  written. Nothing was changed."
}
rm -f "$PROBE"
if [ -e "$POINTER" ] && { [ -d "$POINTER" ] || [ ! -w "$POINTER" ]; }; then
  die "$POINTER exists and cannot be replaced. Nothing was changed."
fi

# --- 2. the app lock ---------------------------------------------------------
# Held across validation, both writes and the verification. It is the APP's
# lock, not the store's: two recoveries aiming at two DIFFERENT stores for one
# app never contend for a per-store lock, and the relay — which claims a store
# and writes this same pointer at startup — is a third writer that has to be
# kept out of the same window. The relay takes this lock too.
APP_LOCK="$GRADLE_DIR/keliver-store.lock"
# The holder records its pid. A lock that outlives its holder would otherwise
# block every future recovery AND every relay start, so it is taken over once —
# but only when the pid is readable and the process is gone. An unreadable pid
# means "wait", because the cost of waiting is a message and the cost of
# stealing is two writers.
lock_holder_alive() {
  local pid; pid="$(cat "$1/pid" 2>/dev/null)" || return 0
  [ -n "$pid" ] || return 0
  kill -0 "$pid" 2>/dev/null
}
if ! mkdir "$APP_LOCK" 2>/dev/null; then
  if lock_holder_alive "$APP_LOCK"; then
    die "this app is locked by another store recovery or a starting portal
  (lock: $APP_LOCK). Nothing was changed. If nothing is running, remove it."
  fi
  say "note: taking over $APP_LOCK — the process that held it is gone"
  rm -f "$APP_LOCK/pid"; rmdir "$APP_LOCK" 2>/dev/null
  mkdir "$APP_LOCK" 2>/dev/null || die "could not take $APP_LOCK. Nothing was changed."
fi
printf '%s\n' "$$" > "$APP_LOCK/pid" 2>/dev/null
STORE_LOCK=""
release_locks() {
  [ -n "$STORE_LOCK" ] && rmdir "$STORE_LOCK" 2>/dev/null
  rm -f "$APP_LOCK/pid" 2>/dev/null
  rmdir "$APP_LOCK" 2>/dev/null
  return 0
}
trap release_locks EXIT INT TERM

# --- 3. which store ----------------------------------------------------------
# What the app resolves to RIGHT NOW, and by which rule. Both matter: a store
# named in keliver.portal.json is a committed decision this command may not
# quietly override, while a pointer is this command's own to rewrite.
# EFF_MSG is kept separately: check (a) below resolves the RECORDED OWNER's
# path, which overwrites RESOLVE_MSG, and the split diagnosis further down has
# to be about this app.
if resolve "$APP" --explain; then
  EFF_STEP="${RESOLVE_OUT%%$'\t'*}"; EFF_PATH="${RESOLVE_OUT#*$'\t'}"
  EFF_RC=0; EFF_MSG=""
else
  EFF_STEP=""; EFF_PATH=""; EFF_RC=$RESOLVE_RC; EFF_MSG="$RESOLVE_MSG"
fi

if [ -z "$STORE" ]; then
  [ "$EFF_RC" = 0 ] || die "cannot tell which store this app is bound to — the resolver refused:
$EFF_MSG
  Name the store explicitly: --store <dir>"
  STORE="$EFF_PATH"
fi
[ -d "$STORE" ] || die "no store at $STORE"
STORE="$(cd "$STORE" && pwd -P)"
[ -n "$EFF_PATH" ] && EFF_PATH="$(cd "$EFF_PATH" 2>/dev/null && pwd -P || printf '%s' "$EFF_PATH")"

say "app:   $APP"
say "store: $STORE"
say "identity before: $(fingerprint "$STORE")"
if [ "$EFF_RC" = 0 ]; then
  say "resolves to now: $EFF_PATH   (by $EFF_STEP)"
else
  say "resolves to now: (refused)"
fi

# --- 4. refuse the ambiguous cases ------------------------------------------
OWNER_FILE="$STORE/owner"
[ -f "$OWNER_FILE" ] || die "$STORE has no owner marker — there is nothing to recover. Just start the portal."
OLD="$(tr -d '\n' < "$OWNER_FILE")"
say "recorded owner:  ${OLD:-(empty)}"

# (a) the recorded owner is still a live app using this same store.
if [ -n "$OLD" ] && [ "$OLD" != "$APP" ] && [ -d "$OLD" ]; then
  if resolve "$OLD"; then
    THEIRS="$(cd "$RESOLVE_OUT" 2>/dev/null && pwd -P || echo /nonexistent)"
    if [ "$THEIRS" = "$STORE" ]; then
      die "$OLD still exists and still uses this store. That is two apps, not a relocation.
  Nothing was changed. If that directory is a leftover copy, remove it (or its
  .gradle/keliver-store-path) first, then re-run."
    fi
  fi
fi

# (b) what this app selects today must be reconcilable with what is being asked
# for. The old check looked only at the DEFAULT, so an app pinned by
# configuration or bound by a pointer to store B could "recover" store A and go
# on resolving B — a success message for a binding that does not exist.
if [ "$EFF_RC" != 0 ]; then
  # The resolver refused. The only refusal this command resolves is a split
  # between stores for THIS app, and only when --store names one of them.
  case "$EFF_MSG" in
    *"store split"*)
      # Matched against the FULL, symlink-resolved paths the resolver lists —
      # $STORE is canonicalised above — so a store elsewhere that happens to
      # share a basename with a split candidate cannot pass for one.
      if printf '%s\n' "$EFF_MSG" | grep -qxF -- "  - $STORE"; then
        say "note: this app is split across several stores; $(basename "$STORE") is the one being kept."
      else
        die "this app is split across stores and $STORE is not one of them:
$EFF_MSG
  Nothing was changed."
      fi
      ;;
    *) die "the resolver refused for a reason this recovery does not address:
$EFF_MSG
  Nothing was changed." ;;
  esac
elif [ "$EFF_PATH" != "$STORE" ]; then
  case "$EFF_STEP" in
    config)
      die "keliver.portal.json pins this app to
    $EFF_PATH   (identity $(fingerprint "$EFF_PATH"))
  A committed setting outranks this command, so recovering $STORE would leave
  the app resolving somewhere else. Change or remove \"store\" in
  $APP/keliver.portal.json first. Nothing was changed." ;;
    *)
      if [ -d "$EFF_PATH" ] && has_content "$EFF_PATH"; then
        die "this app already resolves (by $EFF_STEP) to its own store at
    $EFF_PATH   (identity $(fingerprint "$EFF_PATH"))
  Adopting $STORE would abandon that one, and two stores are never merged.
  Nothing was changed. Move $EFF_PATH aside if you are sure which identity you
  want."
      fi
      ;;
  esac
fi

if [ "$DRY" = 1 ]; then
  say "✓ dry run: this recovery would be accepted"
  exit 0
fi

# --- 5. the two-sided update -------------------------------------------------
STORE_LOCK="$STORE/owner.lock"
mkdir "$STORE_LOCK" 2>/dev/null || {
  STORE_LOCK=""
  die "another recovery is in progress on $STORE. Nothing was changed."
}

# Re-read under the lock: the marker may have changed since it was inspected.
NOW="$(tr -d '\n' < "$OWNER_FILE")"
[ "$NOW" = "$OLD" ] || die "the owner marker changed while this ran ($OLD -> $NOW). Nothing was changed."

# Everything needed to put the previous state back, captured before either
# write. `set -e` would not help here: owner and pointer are two writes, and
# the second failing has to undo the first.
BACKUP="$(mktemp "${TMPDIR:-/tmp}/keliver-owner-backup.XXXXXX")"
printf '%s\n' "$OLD" > "$BACKUP"
POINTER_HAD=0; POINTER_WAS=""
if [ -f "$POINTER" ]; then POINTER_HAD=1; POINTER_WAS="$(cat "$POINTER")"; fi

rollback() {
  cp "$BACKUP" "$OWNER_FILE" 2>/dev/null
  if [ "$POINTER_HAD" = 1 ]; then printf '%s' "$POINTER_WAS" > "$POINTER" 2>/dev/null
  else rm -f "$POINTER" 2>/dev/null; fi
  rm -f "$BACKUP"
  return 0
}
fail() { rollback; die "$1"; }

# owner
if [ "$OLD" != "$APP" ]; then
  TMP="$STORE/.owner.recover.$$"
  printf '%s\n' "$APP" > "$TMP" || fail "cannot write inside $STORE. The previous binding is unchanged."
  mv -f "$TMP" "$OWNER_FILE" || { rm -f "$TMP"; fail "could not replace the owner marker. The previous binding is unchanged."; }
else
  # An owner marker that already names this app is NOT evidence that the
  # app-side binding exists or selects this store — after a symlink split both
  # stores record the same canonical path. The pointer below is the half that
  # was missing.
  say "note: the owner marker already names this app; establishing the app-side binding."
fi

# pointer. KELIVER_RECOVER_FAIL_POINTER is a fault injector for the rollback
# path, which is otherwise unreachable from outside the process. Inert unless
# set to 1.
PTMP="$GRADLE_DIR/.keliver-store-path.$$"
if [ "${KELIVER_RECOVER_FAIL_POINTER:-0}" = 1 ]; then
  rm -f "$PTMP"
  fail "the store pointer could not be written (fault injection). The previous binding is unchanged."
fi
printf '%s\n' "$STORE" > "$PTMP" 2>/dev/null \
  || { rm -f "$PTMP"; fail "could not write $PTMP. The previous binding is unchanged."; }
mv -f "$PTMP" "$POINTER" 2>/dev/null \
  || { rm -f "$PTMP"; fail "could not write $POINTER. The previous binding is unchanged."; }

# --- 6. prove it ------------------------------------------------------------
# Exit status is not the claim; the resolver's answer is.
if ! resolve "$APP"; then
  fail "after rebinding, the resolver still refuses:
$RESOLVE_MSG
  The previous binding has been restored."
fi
VERIFIED="$(cd "$RESOLVE_OUT" 2>/dev/null && pwd -P || printf '%s' "$RESOLVE_OUT")"
[ "$VERIFIED" = "$STORE" ] || fail "after rebinding, this app still resolves to $VERIFIED, not $STORE.
  The previous binding has been restored."
[ "$(tr -d '\n' < "$OWNER_FILE")" = "$APP" ] || fail "the owner marker does not name this app after the update.
  The previous binding has been restored."
rm -f "$BACKUP"

say "identity after:  $(fingerprint "$STORE")"
say "verified:        $VERIFIED   (the ordinary resolver's answer)"
say "✓ $STORE is now bound to $APP"
say "  keys, documents and bundles were not read, copied or moved."
[ "$OLD" != "$APP" ] && say "  $OLD was not modified."
exit 0
