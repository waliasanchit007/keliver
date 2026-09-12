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
# INTERRUPTION. SIGINT and SIGTERM are handled explicitly. Before the
# transaction commits they restore the previous owner marker and pointer WHILE
# STILL HOLDING THE LOCKS, verify the restoration, and exit non-zero; after it
# commits they leave the completed rebinding alone. A trap that only released
# the locks was worse than none — bash resumes at the interrupted statement, so
# the update went on to finish and report success.
#
# SIGKILL AND POWER LOSS CANNOT BE ROLLED BACK. There is no handler for them.
# What survives instead is the backup directory under <app>/.gradle, which is
# only deleted once the transaction is verified; a later run reports any it
# finds and never overwrites one. The lock's pid then belongs to a dead
# process, so the next contender takes the lock over.
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
    -h|--help) sed -n '2,60p' "${BASH_SOURCE[0]}"; exit 0 ;;
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

# TAKING OVER A LOCK WHOSE HOLDER IS GONE.
#
# The holder records its pid inside the lock directory. A lock that outlives
# its holder would otherwise block every future recovery AND every relay start.
# The naive form — see the pid is dead, then `rmdir` — can delete a lock that a
# DIFFERENT contender acquired in between, and then there are two writers.
#
# So the takeover is CLAIMED, not just performed: the `pid` file is renamed
# aside, which exactly one contender can do, and the claim is then checked to
# still hold the dead pid we inspected. A live holder always has a `pid` file it
# wrote before doing anything, and a new holder can only exist after this same
# rename removed the old one — so a successful, content-checked claim proves we
# are not looking at somebody else's live lock. An UNREADABLE pid means wait:
# the cost of waiting is a message, the cost of stealing is two writers.
#
# The same protocol is implemented in PortalConfig.withStoreLock, so the shell
# and the JVM contend correctly with each other.
lock_take_over() { # lock-dir -> 0 when the directory was cleared for a retry
  local lock="$1" pid claim
  pid="$(cat "$lock/pid" 2>/dev/null)" || return 1   # unknown -> wait
  [ -n "$pid" ] || return 1
  kill -0 "$pid" 2>/dev/null && return 1             # alive -> wait
  claim="$lock/pid.stale.$$"
  mv "$lock/pid" "$claim" 2>/dev/null || return 1    # someone else claimed it
  if [ "$(cat "$claim" 2>/dev/null)" != "$pid" ]; then
    # Not the marker we inspected: put it back and wait rather than delete.
    mv "$claim" "$lock/pid" 2>/dev/null
    return 1
  fi
  rm -f "$lock"/pid.stale.* 2>/dev/null
  rmdir "$lock" 2>/dev/null
  return 0
}

APP_LOCK_HELD=0
if ! mkdir "$APP_LOCK" 2>/dev/null; then
  if lock_take_over "$APP_LOCK"; then
    say "note: taking over $APP_LOCK — the process that held it is gone"
    mkdir "$APP_LOCK" 2>/dev/null || die "could not take $APP_LOCK. Nothing was changed."
  else
    die "this app is locked by another store recovery or a starting portal
  (lock: $APP_LOCK). Nothing was changed. If nothing is running, remove it."
  fi
fi
# The marker identifies the holder. Without it nobody — including this script's
# own cleanup, which releases only what the marker says is ours — can tell whose
# lock this is, so failing to write it is failing to acquire.
if ! printf '%s\n' "$$" > "$APP_LOCK/pid" 2>/dev/null \
   || [ "$(cat "$APP_LOCK/pid" 2>/dev/null)" != "$$" ]; then
  rm -f "$APP_LOCK/pid" 2>/dev/null; rmdir "$APP_LOCK" 2>/dev/null
  die "could not record this process as the holder of $APP_LOCK. Nothing was changed."
fi
APP_LOCK_HELD=1
STORE_LOCK=""

# Release ONLY locks this process still holds, identified by the pid marker it
# wrote. Without that check an interrupted run could remove a lock a LATER
# process had already taken over, which is the same two-writers failure the
# lock exists to prevent. Idempotent: safe to call from the signal handler and
# again from the EXIT trap.
release_locks() {
  if [ -n "$STORE_LOCK" ] && [ "$(cat "$STORE_LOCK/pid" 2>/dev/null)" = "$$" ]; then
    rm -f "$STORE_LOCK/pid" 2>/dev/null
    rmdir "$STORE_LOCK" 2>/dev/null
    STORE_LOCK=""
  fi
  if [ "$APP_LOCK_HELD" = 1 ] && [ "$(cat "$APP_LOCK/pid" 2>/dev/null)" = "$$" ]; then
    rm -f "$APP_LOCK/pid" 2>/dev/null
    rmdir "$APP_LOCK" 2>/dev/null
    APP_LOCK_HELD=0
  fi
  return 0
}

# --- the transaction's lifecycle --------------------------------------------
#
# STATE is the only thing the signal handler needs:
#   none      nothing has been written; there is nothing to undo
#   mutating  at least one of the two writes has landed
#   committed both writes landed AND the resolver agreed
#
# A trap that only released the locks was worse than none: bash RESUMES at the
# interrupted statement after the handler returns, so a TERM between the owner
# write and the pointer write released both locks and then went on to finish
# the update and report success.
STATE=none
ABORTING=0

on_signal() {
  trap '' INT TERM          # no re-entry while unwinding
  ABORTING=1
  printf '\n✗ interrupted (SIG%s)\n' "$1" >&2
  case "$STATE" in
    mutating)
      if restore_binding; then
        printf '  the previous binding was restored; nothing was changed.\n' >&2
      else
        report_partial_state
      fi
      ;;
    committed)
      printf '  the rebinding had already completed and been verified; it stands.\n' >&2
      ;;
    *)
      printf '  nothing had been written.\n' >&2
      [ -n "${BACKUP:-}" ] && [ -d "${BACKUP:-}" ] && rm -rf "$BACKUP"
      ;;
  esac
  release_locks
  exit 1
}
trap 'on_signal INT' INT
trap 'on_signal TERM' TERM
trap 'release_locks' EXIT

# Every mutation checks this first, so nothing can run after cleanup has begun
# even if a handler were ever to return instead of exiting.
not_aborting() { [ "$ABORTING" = 0 ] || { release_locks; exit 1; }; }

# Deliberate pauses at transaction boundaries. Inert unless set; they exist so
# an interruption can be delivered at a KNOWN point rather than by racing.
pause_at() {
  [ "${KELIVER_RECOVER_PAUSE_AT:-}" = "$1" ] || return 0
  printf 'paused at %s\n' "$1"
  sleep "${KELIVER_RECOVER_PAUSE_S:-5}"
  return 0
}

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
if ! mkdir "$STORE_LOCK" 2>/dev/null; then
  if lock_take_over "$STORE_LOCK"; then
    say "note: taking over $STORE_LOCK — the process that held it is gone"
    mkdir "$STORE_LOCK" 2>/dev/null || { STORE_LOCK=""; die "could not take the store lock. Nothing was changed."; }
  else
    STORE_LOCK=""
    die "another recovery is in progress on $STORE. Nothing was changed."
  fi
fi
if ! printf '%s\n' "$$" > "$STORE_LOCK/pid" 2>/dev/null \
   || [ "$(cat "$STORE_LOCK/pid" 2>/dev/null)" != "$$" ]; then
  rmdir "$STORE_LOCK" 2>/dev/null; STORE_LOCK=""
  die "could not record this process as the holder of the store lock. Nothing was changed."
fi

# Re-read under the lock: the marker may have changed since it was inspected.
NOW="$(tr -d '\n' < "$OWNER_FILE")"
[ "$NOW" = "$OLD" ] || die "the owner marker changed while this ran ($OLD -> $NOW). Nothing was changed."

# --- the backup -------------------------------------------------------------
# Both sides are copied as FILES. `$(cat f)` strips trailing newlines, so a
# marker restored from a shell variable is not necessarily the marker that was
# there — and whether the pointer EXISTED is state in its own right.
#
# The directory is named per run, so a backup left behind by a killed process
# is never silently overwritten.
BACKUP="$GRADLE_DIR/keliver-store-recover.backup.$$"
LEFTOVER="$(ls -d "$GRADLE_DIR"/keliver-store-recover.backup.* 2>/dev/null | head -3)"
if [ -n "$LEFTOVER" ]; then
  warn "a previous recovery left material behind; it is being kept:"
  printf '%s\n' "$LEFTOVER" | sed 's/^/     /' >&2
fi
mkdir -p "$BACKUP" || die "cannot create $BACKUP. Nothing was changed."
cp "$OWNER_FILE" "$BACKUP/owner" || { rmdir "$BACKUP" 2>/dev/null; die "cannot back up the owner marker. Nothing was changed."; }
cmp -s "$OWNER_FILE" "$BACKUP/owner" || { rm -rf "$BACKUP"; die "the owner-marker backup does not match the original. Nothing was changed."; }
if [ -f "$POINTER" ]; then
  printf '1\n' > "$BACKUP/pointer.existed"
  cp "$POINTER" "$BACKUP/pointer" || { rm -rf "$BACKUP"; die "cannot back up the store pointer. Nothing was changed."; }
  cmp -s "$POINTER" "$BACKUP/pointer" || { rm -rf "$BACKUP"; die "the pointer backup does not match the original. Nothing was changed."; }
else
  printf '0\n' > "$BACKUP/pointer.existed"
fi
{ printf 'app=%s\nstore=%s\nowner_file=%s\npointer=%s\npid=%s\n' \
    "$APP" "$STORE" "$OWNER_FILE" "$POINTER" "$$"; } > "$BACKUP/meta" \
  || { rm -rf "$BACKUP"; die "cannot record the backup metadata. Nothing was changed."; }

# Put both sides back exactly as they were, and PROVE it before saying so.
# Returns non-zero without deleting anything when it cannot.
RESTORE_REPORT=""
restore_binding() {
  local ok=1 had
  if [ "${KELIVER_RECOVER_FAIL_RESTORE:-0}" = 1 ]; then
    RESTORE_REPORT="restoration was refused by fault injection"
    return 1
  fi
  if ! cp "$BACKUP/owner" "$OWNER_FILE" 2>/dev/null || ! cmp -s "$BACKUP/owner" "$OWNER_FILE"; then
    RESTORE_REPORT="the owner marker at $OWNER_FILE could NOT be restored"
    ok=0
  fi
  had="$(tr -d '\n' < "$BACKUP/pointer.existed" 2>/dev/null)"
  if [ "$had" = 1 ]; then
    if ! cp "$BACKUP/pointer" "$POINTER" 2>/dev/null || ! cmp -s "$BACKUP/pointer" "$POINTER"; then
      RESTORE_REPORT="${RESTORE_REPORT:+$RESTORE_REPORT; }the store pointer at $POINTER could NOT be restored"
      ok=0
    fi
  else
    rm -f "$POINTER" 2>/dev/null
    if [ -e "$POINTER" ]; then
      RESTORE_REPORT="${RESTORE_REPORT:+$RESTORE_REPORT; }the store pointer at $POINTER could NOT be removed"
      ok=0
    fi
  fi
  [ "$ok" = 1 ] || return 1
  rm -rf "$BACKUP"
  return 0
}

# What to say when restoration itself failed. The backup is KEPT; the state is
# described as it actually is, never as "unchanged".
report_partial_state() {
  {
    printf '✗ THE PREVIOUS BINDING COULD NOT BE RESTORED.\n'
    printf '  %s\n' "${RESTORE_REPORT:-restoration failed}"
    printf '  This app is now in a PARTIAL state:\n'
    printf '    owner marker  %s -> %s\n' "$OWNER_FILE" "$(tr -d '\n' < "$OWNER_FILE" 2>/dev/null || echo '(unreadable)')"
    if [ -f "$POINTER" ]; then
      printf '    store pointer %s -> %s\n' "$POINTER" "$(tr -d '\n' < "$POINTER" 2>/dev/null)"
    else
      printf '    store pointer %s -> (absent)\n' "$POINTER"
    fi
    printf '  The material needed to put it back by hand has been KEPT:\n'
    printf '    %s/owner            the owner marker as it was\n' "$BACKUP"
    printf '    %s/pointer          the store pointer as it was (if it existed)\n' "$BACKUP"
    printf '    %s/pointer.existed  1 if there was a pointer, 0 if there was none\n' "$BACKUP"
    printf '    %s/meta             the paths involved\n' "$BACKUP"
    printf '  To restore by hand:\n'
    printf '    cp %s/owner %s\n' "$BACKUP" "$OWNER_FILE"
    printf '    cp %s/pointer %s      # or: rm -f %s, if pointer.existed is 0\n' "$BACKUP" "$POINTER" "$POINTER"
    printf '  Then check: keliver-store-path.sh %s\n' "$APP"
  } >&2
}

fail() {
  if restore_binding; then
    die "$1"
  fi
  report_partial_state
  printf '✗ %s\n' "$1" >&2
  exit 1
}

# The backup exists but nothing has been written yet, so an interruption here
# is still "nothing had been written".
pause_at before-owner

# owner
not_aborting
STATE=mutating
if [ "$OLD" != "$APP" ]; then
  TMP="$STORE/.owner.recover.$$"
  printf '%s\n' "$APP" > "$TMP" || fail "cannot write inside $STORE."
  mv -f "$TMP" "$OWNER_FILE" || { rm -f "$TMP"; fail "could not replace the owner marker."; }
else
  # An owner marker that already names this app is NOT evidence that the
  # app-side binding exists, or that it selects this store — after a symlink
  # split both stores record the same canonical path. The pointer below is the
  # half that was missing.
  say "note: the owner marker already names this app; establishing the app-side binding."
fi

pause_at after-owner

# pointer. KELIVER_RECOVER_FAIL_POINTER and KELIVER_RECOVER_FAIL_RESTORE are
# fault injectors for the rollback and the failed-rollback paths, which are
# otherwise unreachable from outside the process. Inert unless set to 1.
not_aborting
PTMP="$GRADLE_DIR/.keliver-store-path.$$"
if [ "${KELIVER_RECOVER_FAIL_POINTER:-0}" = 1 ]; then
  rm -f "$PTMP"
  fail "the store pointer could not be written (fault injection)."
fi
printf '%s\n' "$STORE" > "$PTMP" 2>/dev/null \
  || { rm -f "$PTMP"; fail "could not write $PTMP."; }
mv -f "$PTMP" "$POINTER" 2>/dev/null \
  || { rm -f "$PTMP"; fail "could not write $POINTER."; }

pause_at after-pointer

# --- 6. prove it ------------------------------------------------------------
# Exit status is not the claim; the resolver's answer is. Until this passes the
# transaction is still `mutating`, so an interruption here rolls it back.
not_aborting
if ! resolve "$APP"; then
  fail "after rebinding, the resolver still refuses:
$RESOLVE_MSG"
fi
VERIFIED="$(cd "$RESOLVE_OUT" 2>/dev/null && pwd -P || printf '%s' "$RESOLVE_OUT")"
[ "$VERIFIED" = "$STORE" ] || fail "after rebinding, this app still resolves to $VERIFIED, not $STORE."
[ "$(tr -d '\n' < "$OWNER_FILE")" = "$APP" ] || fail "the owner marker does not name this app after the update."

# Both sides landed and the resolver agrees: the transaction is done. From here
# an interruption leaves it in place rather than undoing verified work.
STATE=committed
pause_at after-commit
rm -rf "$BACKUP"

say "identity after:  $(fingerprint "$STORE")"
say "verified:        $VERIFIED   (the ordinary resolver's answer)"
say "✓ $STORE is now bound to $APP"
say "  keys, documents and bundles were not read, copied or moved."
[ "$OLD" != "$APP" ] && say "  $OLD was not modified."
exit 0
