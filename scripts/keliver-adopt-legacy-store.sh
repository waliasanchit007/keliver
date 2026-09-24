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
  # The same lost-exit-status pipeline the resolver had (#78): this carried AWK's
  # status, and awk succeeds when it matches nothing, so "java is absent" produced
  # an empty JVM_HOME and a LEGACY of "/.keliver-portal" — which then failed the
  # -d test below and reported "nothing to adopt", blaming the wrong thing.
  JVM_HOME="$(java -XshowSettings:properties -version 2>&1)" || {
    echo "keliver-adopt-legacy-store: java could not be run, so the legacy store's" >&2
    echo "  location is unknown. Pass --legacy <dir> if you know it." >&2
    exit 4
  }
  JVM_HOME="$(printf '%s\n' "$JVM_HOME" | awk '
    /^[ \t]*user\.home[ \t]*=/ {
      v = substr($0, index($0, "=") + 1)
      gsub(/^[ \t]+/, "", v); gsub(/[ \t\r]+$/, "", v)
      if (v != "") seen[v] = 1
    }
    END { n = 0; for (k in seen) { n++; last = k }
          if (n == 1) print last; else exit 1 }
  ')" || {
    echo "keliver-adopt-legacy-store: java did not report exactly one user.home, so the" >&2
    echo "  legacy store's location is unknown. Pass --legacy <dir> if you know it." >&2
    exit 4
  }
  case "$JVM_HOME" in
    /*) ;;
    *)  echo "keliver-adopt-legacy-store: java reported a non-absolute user.home." >&2; exit 4;;
  esac
  LEGACY="$JVM_HOME/.keliver-portal"
fi
[ -d "$LEGACY" ] || { echo "no legacy store at $LEGACY — nothing to adopt" >&2; exit 1; }

# STATUS CHECKED, AND NON-EMPTY. This took the resolver's stdout and ignored its
# exit code. Once the resolver started REFUSING instead of falling back to $HOME
# (#78), a refusal arrived here as TARGET="" — and `cd ""` SUCCEEDS in bash,
# returning the cwd, so the "already uses the legacy store" guard below could not
# fire either. Every destination then became "/<rel>": MEASURED, with no java on
# PATH and --legacy given, this script tried to copy the legacy PRIVATE SIGNING
# KEY to /keys/ed25519.priv, printed "copied: keys/ed25519.priv", and exited 0.
# On macOS / is read-only so nothing landed; as root on Linux it would have.
TARGET="$("$(keliver_store_path_script)" "$APP")" || {
  echo "keliver-adopt-legacy-store: this app's store could not be resolved (see above)," >&2
  echo "  so there is nowhere to adopt into. Refusing rather than guessing: this copies a" >&2
  echo "  signing key, and the wrong destination is worse than no copy at all." >&2
  exit 4
}
[ -n "$TARGET" ] || {
  echo "keliver-adopt-legacy-store: the resolver exited 0 but named no store. Refusing." >&2
  exit 4
}
# An absolute target, for the same reason: "$TARGET/$rel" with a relative or
# empty TARGET writes somewhere nobody asked for.
case "$TARGET" in
  /*) ;;
  *)  echo "keliver-adopt-legacy-store: the resolver named a non-absolute store" >&2
      echo "  ('$TARGET'). Refusing." >&2
      exit 4;;
esac
[ "$(cd "$LEGACY" && pwd -P)" != "$(cd "$TARGET" 2>/dev/null && pwd -P || echo x)" ] || {
  echo "this app already uses the legacy store directly; nothing to do"; exit 0; }

echo "adopting into: $TARGET"
echo "          from: $LEGACY  (read-only)"
# CHECKED. Unchecked, a target that cannot be created still reached the copy
# loop — and if the legacy store happened to hold none of the copied names, every
# copy_one returned early on [ -e "$src" ], `failed` stayed 0, and this printed
# "copied 0 item(s) ... unchanged" and exited 0. A success claim with no
# destination: the same family as the bug this script was fixed for.
mkdir -p "$TARGET" || {
  echo "keliver-adopt-legacy-store: could not create $TARGET — nothing was adopted." >&2
  exit 1
}

copied=0; skipped=0; failed=0
copy_one() { # relative path
  local rel="$1" src="$LEGACY/$1" dst="$TARGET/$1"
  [ -e "$src" ] || return 0
  if [ -e "$dst" ] && [ "$FORCE" != 1 ]; then
    echo "  skip (exists): $rel"; skipped=$((skipped+1)); return 0
  fi
  # CHECKED. Both of these were unchecked, so a failed mkdir and a failed cp still
  # printed "copied" and still counted — which is how the empty-TARGET bug above
  # reported "copied 3 item(s)" and exited 0 having written nothing.
  mkdir -p "$(dirname "$dst")" || {
    echo "  FAILED (mkdir): $rel" >&2; failed=$((failed+1)); return 1
  }
  cp -R "$src" "$dst" || {
    echo "  FAILED (copy): $rel" >&2; failed=$((failed+1)); return 1
  }
  echo "  copied: $rel"; copied=$((copied+1))
}

# THE PRIVATE KEY IS COPIED OWNER-ONLY FROM THE MOMENT IT EXISTS (U27). It went
# through copy_one's `cp -R`, which gives a new file the SOURCE's mode minus the
# umask — so a legacy key made before U27 (0644) arrived 0644, readable by every
# local user — and a chmod afterwards would leave a window in which it is
# readable. mktemp creates its file 0600 in the destination directory; that mode
# is read back BEFORE any key byte is written, the key is written into that
# file, and only then is it put in place: `ln`, which never replaces a name,
# or with --force `mv -f`, which replaces it atomically. A filesystem that does
# not enforce the mode fails the copy rather than receiving the key.
file_mode() { stat -c %a "$1" 2>/dev/null || stat -f %Lp "$1" 2>/dev/null; }
# macOS mounts external and disk-image volumes `noowners`: every local user then
# counts as the owner of every file, so no mode protects anything. Prints the
# mount point when the one holding <dir> is such a volume (portal-relay's
# SigningKeys.kt makes the same check).
noowners_mount() { # <dir>
  [ "$(uname)" = Darwin ] || return 1
  local real; real="$(cd "$1" 2>/dev/null && pwd -P)" || return 1
  mount | awk -v p="$real" '
    { i = index($0, " on "); if (!i) next
      rest = substr($0, i + 4)
      if (!match(rest, / \([^()]*\)$/)) next
      point = substr(rest, 1, RSTART - 1); flags = substr(rest, RSTART + 2, RLENGTH - 3)
      pre = (point == "/") ? "/" : point "/"
      if ((p == point || index(p, pre) == 1) && length(point) > best) { best = length(point); bp = point; bf = flags } }
    END { if (best && bf ~ /(^|, )noowners(,|$)/) { print bp; exit 0 }; exit 1 }'
}
copy_private_key() {
  local rel="keys/ed25519.priv" src="$LEGACY/keys/ed25519.priv" dst="$TARGET/keys/ed25519.priv" tmp m
  [ -e "$src" ] || return 0
  if [ -e "$dst" ] && [ "$FORCE" != 1 ]; then
    echo "  skip (exists): $rel"; skipped=$((skipped+1)); return 0
  fi
  # keys/ is made 0700 when this creates it; an existing one is not changed.
  if [ ! -d "$TARGET/keys" ]; then
    ( umask 077 && mkdir -p "$TARGET/keys" ) || {
      echo "  FAILED (mkdir): $rel" >&2; failed=$((failed+1)); return 1; }
  fi
  tmp="$(mktemp "$TARGET/keys/.adopt.XXXXXX")" || {
    echo "  FAILED (temporary file): $rel" >&2; failed=$((failed+1)); return 1; }
  # mktemp creates 0600. Anything else read back — an execute bit included — is
  # a filesystem not applying the mode it was given (macOS FAT reads back 700).
  m="$(file_mode "$tmp")"
  case "$m" in
    600|400) ;;
    *) rm -f "$tmp"
       echo "  FAILED (not owner-only): $rel — a new file in $TARGET/keys was created 600 and reads" >&2
       echo "    back as ${m:-unknown}, so that filesystem does not enforce permissions. The key was NOT copied." >&2
       failed=$((failed+1)); return 1 ;;
  esac
  if m="$(noowners_mount "$TARGET/keys")"; then
    rm -f "$tmp"
    echo "  FAILED (ownership ignored): $rel — the volume at $m is mounted noowners, so every local" >&2
    echo "    user counts as the owner of every file, and this filesystem does not enforce permissions." >&2
    echo "    The key was NOT copied." >&2
    failed=$((failed+1)); return 1
  fi
  if ! cat "$src" > "$tmp"; then
    rm -f "$tmp"; echo "  FAILED (copy): $rel" >&2; failed=$((failed+1)); return 1
  fi
  if [ "$FORCE" = 1 ]; then
    mv -f "$tmp" "$dst" || { rm -f "$tmp"; echo "  FAILED (rename): $rel" >&2; failed=$((failed+1)); return 1; }
  elif ! ln "$tmp" "$dst" 2>/dev/null; then
    rm -f "$tmp"
    # A key that appeared since the check above is kept, not replaced.
    if [ -e "$dst" ]; then echo "  skip (exists): $rel"; skipped=$((skipped+1)); return 0; fi
    echo "  FAILED (link): $rel" >&2; failed=$((failed+1)); return 1
  else
    rm -f "$tmp"
  fi
  echo "  copied: $rel (mode $(file_mode "$dst"))"; copied=$((copied+1))
  # The legacy store is only READ, so its copy keeps whatever mode it has. Say
  # so when that mode lets other users read it; do not change it.
  m="$(file_mode "$src")"
  case "$m" in
    *[4567]?|*[4567]) echo "  note: the LEGACY copy $src is mode $m: readable by other users of this machine." >&2
             echo "    This script does not change the legacy store. To make that copy owner-only:" >&2
             echo "      chmod 600 '$src'" >&2 ;;
  esac
}

copy_private_key
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
# A non-zero exit when anything failed. Reporting a count and exiting 0 while
# copies failed is what let the empty-TARGET bug look like success.
if [ "$failed" -ne 0 ]; then
  echo "$failed item(s) FAILED to copy — this adoption is incomplete." >&2
  exit 1
fi
[ "$skipped" -gt 0 ] && echo "re-run with --force to overwrite the skipped items"
exit 0
