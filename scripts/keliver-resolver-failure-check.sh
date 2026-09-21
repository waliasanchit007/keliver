#!/usr/bin/env bash
#
# keliver-resolver-failure-check — what every caller does when the store
# resolver REFUSES.
#
#   scripts/keliver-resolver-failure-check.sh <disposable-root>
#
# WHY (#78). keliver-store-path.sh used to answer whatever happened — when JVM
# discovery failed it fell back to $HOME. Callers were written against that, so
# most took its stdout and never looked at its exit code. When it started
# refusing, those callers received "" and carried on. The damage is not one bug,
# it is one SHAPE: an unchecked status turns MISSING EVIDENCE into a PASS.
#
#   * keliver-adopt-legacy-store.sh built "/<rel>" destinations, tried to copy a
#     legacy PRIVATE SIGNING KEY to /keys/ed25519.priv, printed "copied", exited 0
#   * keliver-legacy-compat-check.sh would have written LOCAL-EDIT to
#     "/keys/ed25519.pub"
#   * fingerprint helpers ran `find "" -type f` and compared empty to empty, so
#     "the store is unchanged" passed having observed nothing
#   * `case "" in "$APP"/*)` does not match, so "the store is outside the app"
#     passed for an app whose store was never found
#   * keliver-record-http.sh built "/http-record.token", sent an unauthenticated
#     request, and reported the relay's rejection — a different problem entirely
#
# THREE INJECTED SHAPES, because they fail differently:
#   1. exit 4, empty stdout          — the real refusal
#   2. non-zero exit, MISLEADING stdout — a caller that reads stdout and ignores
#      the status gets a plausible path
#   3. exit 0, EMPTY stdout          — the status says fine and there is nothing
#
# Nothing real is touched: every fixture is under <disposable-root>, the resolver
# is a stub, and the empty-path expansion is never exercised by letting a script
# write to /keys or any real store — the assertions are that execution STOPS.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
DISP_PARENT="${1:?usage: $0 <disposable-root>}"

# shellcheck source=/dev/null
. "$ROOT/scripts/keliver-test-isolation-guard.sh"
DISP="$(keliver_make_run_dir "$DISP_PARENT" resolverfail)" || exit $?
# shellcheck source=/dev/null
. "$ROOT/scripts/keliver-resolve-store.sh"

PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); printf '  PASS  %s\n' "$1"; }
bad() { FAIL=$((FAIL+1)); printf '  FAIL  %s\n' "$1"; }

STUBDIR="$DISP/stub"; mkdir -p "$STUBDIR"
GOOD_STORE="$DISP/good-store"; mkdir -p "$GOOD_STORE/keys"
# stub_resolver <shape>
stub_resolver() {
  case "$1" in
    refuse)     printf '#!/bin/sh\necho "stub: refusing" >&2\nexit 4\n' ;;
    misleading) printf '#!/bin/sh\necho "%s"\necho "stub: refusing" >&2\nexit 1\n' "$DISP/MISLEADING-PATH" ;;
    emptyok)    printf '#!/bin/sh\nexit 0\n' ;;
    valid)      printf '#!/bin/sh\necho "%s"\n' "$GOOD_STORE" ;;
    relative)   printf '#!/bin/sh\necho "relative/store"\n' ;;
  esac > "$STUBDIR/keliver-store-path.sh"
  chmod +x "$STUBDIR/keliver-store-path.sh"
}

echo "=== the shared boundary: keliver_require_store"
# Six repo-side callers route through this, so its contract is their contract.
for shape in refuse misleading emptyok relative; do
  stub_resolver "$shape"
  out="$(keliver_require_store "unit" "$STUBDIR/keliver-store-path.sh" "$DISP" 2>"$DISP/u.err")"; rc=$?
  [ "$rc" -ne 0 ] && ok "$shape: refuses (rc=$rc)" || bad "$shape: returned 0"
  [ -z "$out" ]   && ok "$shape: and prints nothing on stdout" \
                  || bad "$shape: printed '$out' — a caller assigning it gets a usable-looking path"
  # The resolver's own message must survive: it is the actionable half.
  if [ "$shape" = "misleading" ] || [ "$shape" = "refuse" ]; then
    grep -q "stub: refusing" "$DISP/u.err" \
      && ok "$shape: and the resolver's own diagnostic reaches the operator" \
      || bad "$shape: the resolver's diagnostic was swallowed"
  fi
done
# THE CONTROL. Without it a helper that refused everything would pass every row
# above, and so would every caller built on it.
stub_resolver valid
out="$(keliver_require_store "unit" "$STUBDIR/keliver-store-path.sh" "$DISP" 2>/dev/null)"; rc=$?
[ "$rc" = 0 ] && [ "$out" = "$GOOD_STORE" ] \
  && ok "valid: resolves normally and returns the path" \
  || bad "valid: a good resolution was refused (rc=$rc, out=$out)"

echo "--- no caller may take the resolver's stdout without checking its status"
# A STATIC GATE over every caller, present and future. The behavioural rows below
# cover the two callers that can be driven to their boundary cheaply; this covers
# the rest, and catches a new caller written in the old shape.
# The pattern. `=\$\(` was WRONG and the self-check below caught it: every real
# call site is `STORE="$(...)"`, with a quote between the `=` and the `$(`, so it
# matched nothing and the gate passed by finding no violations anywhere. A gate
# that cannot see its own subject is worse than no gate.
GATE_RE='=[^=]*\$\((.*keliver-store-path\.sh|"\$\(keliver_store_path_script\)")'
UNCHECKED=0
while IFS= read -r hit; do
  # An assignment from the resolver with no `||` on the same line and no
  # keliver_require_store wrapper.
  case "$hit" in
    *keliver_require_store*) continue ;;
    *'||'*) continue ;;
  esac
  echo "        $hit"
  UNCHECKED=$((UNCHECKED+1))
done < <(grep -rnE "$GATE_RE" --include='*.sh' "$ROOT/scripts" 2>/dev/null \
         | grep -v 'keliver-resolver-failure-check.sh' \
         | grep -v 'keliver-store-home-check.sh' \
         | grep -v 'keliver-resolve-store.sh')
[ "$UNCHECKED" -eq 0 ] \
  && ok "every resolver invocation in scripts/ checks its status" \
  || bad "$UNCHECKED resolver invocation(s) take stdout without checking the status"
# THE GATE MUST SEE A VIOLATION, and must not flag a compliant line. Both
# directions: a pattern that matches nothing passes silently, and one that
# matches everything makes the gate unusable.
VIOLATION='STORE="$("$ROOT/scripts/keliver-store-path.sh" "$APP")"'
COMPLIANT='STORE="$(keliver_require_store "x" "$ROOT/scripts/keliver-store-path.sh" "$APP")" || exit $?'
v_hits="$(printf '%s\n' "$VIOLATION"  | grep -cE "$GATE_RE")"
c_hits="$(printf '%s\n' "$COMPLIANT" | grep -cE "$GATE_RE")"
[ "$v_hits" = 1 ] \
  && ok "and the gate's pattern does match the unchecked shape" \
  || bad "the gate's pattern does not match an unchecked call, so it proves nothing"
# The compliant line still matches the pattern; it is the `||` / wrapper filter
# in the loop above that clears it. Assert that filter, not just the regex.
if [ "$c_hits" = 1 ]; then
  case "$COMPLIANT" in
    *keliver_require_store*|*'||'*) ok "and a compliant call is cleared by the filter, not by the regex" ;;
    *) bad "a compliant call would be reported as a violation" ;;
  esac
else
  bad "the compliant fixture does not even reach the filter"
fi

echo "--- keliver-record-http.sh: resolver failure is not an auth failure"
# Interception: a stub curl records every request. The assertion is that NONE is
# made — a caller that cannot find its token must not send an unauthenticated
# request and then report the relay's rejection.
APPDIR="$DISP/recapp"; mkdir -p "$APPDIR"
printf '{"port": 8131}\n' > "$APPDIR/keliver.portal.json"
BIN="$DISP/recbin"; mkdir -p "$BIN"
cp "$ROOT/scripts/keliver-record-http.sh" "$BIN/"
printf '#!/bin/sh\necho "REQUEST-MADE $*" >> "%s"\nexit 0\n' "$DISP/curl.calls" > "$BIN/curl"
chmod +x "$BIN/curl" "$BIN/keliver-record-http.sh"
rec_case() { # rec_case <shape> <want-nonzero>
  local shape="$1"
  : > "$DISP/curl.calls"
  cp "$STUBDIR/keliver-store-path.sh" "$BIN/keliver-store-path.sh"
  local rc=0
  ( export PATH="$BIN:$PATH" KELIVER_APP_DIR="$APPDIR"
    unset PORTAL_HTTP_RECORD_TOKEN_FILE
    "$BIN/keliver-record-http.sh" close session-x ) > "$DISP/rec.log" 2>&1 || rc=$?
  [ "$rc" -ne 0 ] && ok "record-http/$shape: exits non-zero (rc=$rc)" \
                  || bad "record-http/$shape: exited 0 with no resolvable store"
  [ ! -s "$DISP/curl.calls" ] && ok "record-http/$shape: and made no request" \
                              || bad "record-http/$shape: sent a request anyway: $(head -1 "$DISP/curl.calls")"
  if grep -qiE 'unauthor|forbidden|401|403|relay (said|rejected)' "$DISP/rec.log"; then
    bad "record-http/$shape: blamed authentication for a resolver failure"
  else
    ok "record-http/$shape: and did not blame authentication"
  fi
}
for shape in refuse misleading emptyok; do stub_resolver "$shape"; rec_case "$shape"; done
# CONTROL: with a resolvable store it must get past the resolver and actually try.
stub_resolver valid
printf 'tok\n' > "$GOOD_STORE/http-record.token"
: > "$DISP/curl.calls"
cp "$STUBDIR/keliver-store-path.sh" "$BIN/keliver-store-path.sh"
( export PATH="$BIN:$PATH" KELIVER_APP_DIR="$APPDIR"
  unset PORTAL_HTTP_RECORD_TOKEN_FILE
  "$BIN/keliver-record-http.sh" close session-x ) > "$DISP/rec-ok.log" 2>&1
[ -s "$DISP/curl.calls" ] \
  && ok "record-http/valid: with a resolvable store it does make the request" \
  || { bad "record-http/valid: made no request even with a good store — the rows above prove nothing"
       tail -3 "$DISP/rec-ok.log" | sed 's/^/        /'; }

echo "--- keliver-adopt-legacy-store.sh: all three shapes, no copy, no claim"
LEG="$DISP/legacy"; mkdir -p "$LEG/keys"
printf 'MARKER-NOT-A-REAL-PRIVATE-KEY\n' > "$LEG/keys/ed25519.priv"
ADOPTBIN="$DISP/adoptbin"; mkdir -p "$ADOPTBIN"
cp "$ROOT/scripts/keliver-adopt-legacy-store.sh" "$ADOPTBIN/"
chmod +x "$ADOPTBIN/keliver-adopt-legacy-store.sh"
ADOPT_APP="$DISP/adoptapp"; mkdir -p "$ADOPT_APP"
for shape in refuse misleading emptyok; do
  stub_resolver "$shape"
  cp "$STUBDIR/keliver-store-path.sh" "$ADOPTBIN/keliver-store-path.sh"
  rc=0
  "$ADOPTBIN/keliver-adopt-legacy-store.sh" "$ADOPT_APP" --legacy "$LEG" > "$DISP/ad.log" 2>&1 || rc=$?
  [ "$rc" -ne 0 ] && ok "adopt/$shape: exits non-zero (rc=$rc)" \
                  || bad "adopt/$shape: exited 0 — this is the shape that targeted /keys"
  grep -q 'copied:' "$DISP/ad.log" \
    && bad "adopt/$shape: claimed to copy something" \
    || ok "adopt/$shape: and claimed no copy"
done
# The misleading shape deserves its own check: the path it prints EXISTS nowhere,
# so a caller that ignored the status would have created it.
[ ! -e "$DISP/MISLEADING-PATH" ] \
  && ok "adopt: the misleading path the stub printed was never created" \
  || bad "adopt: something acted on the misleading stdout"
# CONTROL, again: a good resolver must still adopt.
stub_resolver valid
cp "$STUBDIR/keliver-store-path.sh" "$ADOPTBIN/keliver-store-path.sh"
rc=0
"$ADOPTBIN/keliver-adopt-legacy-store.sh" "$ADOPT_APP" --legacy "$LEG" > "$DISP/ad-ok.log" 2>&1 || rc=$?
[ "$rc" = 0 ] && [ -f "$GOOD_STORE/keys/ed25519.priv" ] \
  && ok "adopt/valid: a resolvable store still adopts" \
  || { bad "adopt/valid: refused a good resolution (rc=$rc)"; tail -3 "$DISP/ad-ok.log" | sed 's/^/        /'; }

echo "--- the hazardous write comes AFTER the check that guards it"
# keliver-legacy-compat-check.sh boots a relay, so it is not driven here. What IS
# checkable without one: the resolver check precedes the LOCAL-EDIT write, which
# is the specific line that would have written to /keys/ed25519.pub.
LC="$ROOT/scripts/keliver-legacy-compat-check.sh"
chk_line="$(grep -n 'keliver_require_store' "$LC" | head -1 | cut -d: -f1)"
wr_line="$(grep -n "printf 'LOCAL-EDIT" "$LC" | head -1 | cut -d: -f1)"
if [ -n "$chk_line" ] && [ -n "$wr_line" ] && [ "$chk_line" -lt "$wr_line" ]; then
  ok "legacy-compat: the store is required (line $chk_line) before the write (line $wr_line)"
else
  bad "legacy-compat: check=$chk_line write=$wr_line — the write is not guarded"
fi

echo
printf 'passed: %d   failed: %d\n' "$PASS" "$FAIL"
echo "evidence: $DISP"
[ "$FAIL" -eq 0 ]
