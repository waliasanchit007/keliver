#!/usr/bin/env bash
#
# keliver-resolver-failure-check — what every caller does when the store
# resolver REFUSES.
#
#   scripts/keliver-resolver-failure-check.sh <disposable-root> [candidate.zip]
#
# With a ZIP, the bundled callers are exercised FROM THE PACKAGE as well as from
# the repository. Those are not the same artefact: the package is what an adopter
# runs, and a staging list that forgets a file, or ships a stale copy, is exactly
# the kind of gap that only shows up in someone else's hands. A caller missing
# from the package is a FAILURE here — never a reason to fall back to the repo
# copy, which would report a pass for a file the adopter does not have.
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
#   * keliver-record-http.sh built "/http-record.token" and then MISDIAGNOSED:
#     its token guard stopped it before sending anything (so no unauthenticated
#     request — an earlier version of this comment claimed one, wrongly), but it
#     said "recording token not found; start the relay with PORTAL_HTTP_RECORD=1"
#     and sent the operator to restart a healthy relay
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
DISP_PARENT="${1:?usage: $0 <disposable-root> [candidate.zip]}"
CANDIDATE_ZIP="${2:-}"

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
# Literal spellings AND the variable ones. The first version matched only call
# sites that name the script path, which misses `"$RESOLVE"`, `$RESOLVE` and
# `"$STORE_PATH_SH"` — the spellings four scripts actually use. Each of those was
# checked by hand and none is vulnerable (they all pass --home and compare the
# result as a string), but a gate that cannot see a spelling cannot police it.
GATE_RE='=[^=]*\$\((.*keliver-store-path\.sh|"?\$\{?(RESOLVE|STORE_PATH_SH)\}?"?[ )]|"\$\(keliver_store_path_script\)")'
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
# The claim is scoped to what the scan covers: *.sh under scripts/. build.gradle,
# the workflows and the frozen evidence copy under docs/ are outside it and were
# audited by hand.
[ "$UNCHECKED" -eq 0 ] \
  && ok "every resolver invocation in scripts/*.sh checks its status" \
  || bad "$UNCHECKED resolver invocation(s) take stdout without checking the status"
# THE GATE MUST SEE A VIOLATION, and must not flag a compliant line. Both
# directions: a pattern that matches nothing passes silently, and one that
# matches everything makes the gate unusable.
VIOLATION='STORE="$("$ROOT/scripts/keliver-store-path.sh" "$APP")"'
VIOLATION_VAR='STORE="$("$RESOLVE" "$APP")"'
COMPLIANT='STORE="$(keliver_require_store "x" "$ROOT/scripts/keliver-store-path.sh" "$APP")" || exit $?'
v_hits="$(printf '%s\n' "$VIOLATION"  | grep -cE "$GATE_RE")"
c_hits="$(printf '%s\n' "$COMPLIANT" | grep -cE "$GATE_RE")"
vv_hits="$(printf '%s\n' "$VIOLATION_VAR" | grep -cE "$GATE_RE")"
[ "$v_hits" = 1 ] \
  && ok "and the gate's pattern does match the unchecked shape" \
  || bad "the gate's pattern does not match an unchecked call, so it proves nothing"
[ "$vv_hits" = 1 ] \
  && ok "and the variable spelling too, which the first version of it missed" \
  || bad "the gate cannot see \"\$RESOLVE\"-style call sites"
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
printf '#!/bin/sh\necho "REQUEST-MADE $*" >> "%s"\nexit 0\n' "$DISP/curl.calls" > "$BIN/curl"
chmod +x "$BIN/curl"
# ORIGIN is repo or package; it is printed in every label so a failure names which
# artefact was wrong.
ORIGIN="repo"
use_record_http() { cp "$1" "$BIN/keliver-record-http.sh"; chmod +x "$BIN/keliver-record-http.sh"; }
use_record_http "$ROOT/scripts/keliver-record-http.sh"
rec_case() { # rec_case <shape>
  local shape="$ORIGIN/$1"
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
  # THE ROW THAT DISCRIMINATES. Exiting non-zero and sending nothing were ALREADY
  # true before #78 — the token guard did that — so those two rows pass against
  # the old script and prove only that nothing regressed. What was wrong was the
  # message, and that is what this asserts: it must name the store/resolver, and
  # must NOT repeat the old advice to go restart a relay that is fine.
  if grep -q 'start the relay with PORTAL_HTTP_RECORD=1' "$DISP/rec.log"; then
    bad "record-http/$shape: sent the operator to restart the relay for a resolver failure"
  else
    ok "record-http/$shape: and did not misdirect them at the relay"
  fi
  if grep -qiE 'store could not be resolved|named no store|non-absolute store' "$DISP/rec.log"; then
    ok "record-http/$shape: and named the store resolution as the problem"
  else
    bad "record-http/$shape: refused without saying the store could not be resolved"
    head -3 "$DISP/rec.log" | sed 's/^/        /'
  fi
}
run_record_http_rows() {
  local shape
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
    && ok "record-http/$ORIGIN/valid: with a resolvable store it does make the request" \
    || { bad "record-http/$ORIGIN/valid: made no request even with a good store — the rows above prove nothing"
         tail -3 "$DISP/rec-ok.log" | sed 's/^/        /'; }
}
run_record_http_rows
echo "--- keliver-adopt-legacy-store.sh: all three shapes, no copy, no claim"
LEG="$DISP/legacy"; mkdir -p "$LEG/keys"
printf 'MARKER-NOT-A-REAL-PRIVATE-KEY\n' > "$LEG/keys/ed25519.priv"
ADOPTBIN="$DISP/adoptbin"; mkdir -p "$ADOPTBIN"
use_adopt() { cp "$1" "$ADOPTBIN/keliver-adopt-legacy-store.sh"; chmod +x "$ADOPTBIN/keliver-adopt-legacy-store.sh"; }
use_adopt "$ROOT/scripts/keliver-adopt-legacy-store.sh"
ADOPT_APP="$DISP/adoptapp"; mkdir -p "$ADOPT_APP"
run_adopt_rows() {
  local shape rc
  for shape in refuse misleading emptyok; do
    stub_resolver "$shape"
    cp "$STUBDIR/keliver-store-path.sh" "$ADOPTBIN/keliver-store-path.sh"
    rc=0
    "$ADOPTBIN/keliver-adopt-legacy-store.sh" "$ADOPT_APP" --legacy "$LEG" > "$DISP/ad.log" 2>&1 || rc=$?
    [ "$rc" -ne 0 ] && ok "adopt/$ORIGIN/$shape: exits non-zero (rc=$rc)" \
                    || bad "adopt/$ORIGIN/$shape: exited 0 — this is the shape that targeted /keys"
    grep -q 'copied:' "$DISP/ad.log" \
      && bad "adopt/$ORIGIN/$shape: claimed to copy something" \
      || ok "adopt/$ORIGIN/$shape: and claimed no copy"
  done
  # The misleading shape deserves its own check: the path it prints EXISTS nowhere,
  # so a caller that ignored the status would have created it.
  [ ! -e "$DISP/MISLEADING-PATH" ] \
    && ok "adopt/$ORIGIN: the misleading path the stub printed was never created" \
    || bad "adopt/$ORIGIN: something acted on the misleading stdout"
  # CONTROL, again: a good resolver must still adopt.
  rm -rf "$GOOD_STORE/keys"
  stub_resolver valid
  cp "$STUBDIR/keliver-store-path.sh" "$ADOPTBIN/keliver-store-path.sh"
  rc=0
  "$ADOPTBIN/keliver-adopt-legacy-store.sh" "$ADOPT_APP" --legacy "$LEG" > "$DISP/ad-ok.log" 2>&1 || rc=$?
  [ "$rc" = 0 ] && [ -f "$GOOD_STORE/keys/ed25519.priv" ] \
    && ok "adopt/$ORIGIN/valid: a resolvable store still adopts" \
    || { bad "adopt/$ORIGIN/valid: refused a good resolution (rc=$rc)"; tail -3 "$DISP/ad-ok.log" | sed 's/^/        /'; }
}
run_adopt_rows

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

# --- the same callers, FROM THE CANDIDATE PACKAGE -----------------------------
# An adopter runs bin/ out of the ZIP, not scripts/ out of the repo. They are
# built from the same sources today, but the staging list in
# scripts/build-portal-tools.sh is a separate thing that can forget a file or
# ship a stale one, and that gap only shows up in someone else's hands.
if [ -n "$CANDIDATE_ZIP" ]; then
  echo "--- the bundled callers, out of the candidate ZIP"
  if [ ! -f "$CANDIDATE_ZIP" ]; then
    bad "no ZIP at $CANDIDATE_ZIP"
  else
    PKG="$DISP/pkg"; mkdir -p "$PKG"
    if unzip -q "$CANDIDATE_ZIP" -d "$PKG" 2>"$DISP/unzip.err"; then
      PKGBIN="$(dirname "$(find "$PKG" -type f -name 'keliver-store-path.sh' | head -1)")"
      if [ -z "$PKGBIN" ] || [ ! -d "$PKGBIN" ]; then
        bad "the package contains no keliver-store-path.sh"
      else
        ok "the package ships the resolver: ${PKGBIN#$PKG/}"
        # MISSING IS A FAILURE, NOT A FALLBACK. Substituting the repo copy here
        # would report a pass for a file the adopter does not have.
        for want in keliver-record-http.sh keliver-adopt-legacy-store.sh; do
          if [ -f "$PKGBIN/$want" ]; then
            ok "the package ships $want"
          else
            bad "the package does NOT ship $want — not substituting the repo copy"
          fi
        done
        # A stale packaged copy is the other half: assert the fix is actually in
        # the shipped bytes, not just in the repo.
        if [ -f "$PKGBIN/keliver-adopt-legacy-store.sh" ]; then
          grep -q 'could not be resolved' "$PKGBIN/keliver-adopt-legacy-store.sh" \
            && ok "and the packaged adopt-legacy carries the resolver check" \
            || bad "the packaged adopt-legacy is STALE — it has no resolver check"
        fi
        if [ -f "$PKGBIN/keliver-record-http.sh" ]; then
          grep -q 'could not be resolved' "$PKGBIN/keliver-record-http.sh" \
            && ok "and the packaged record-http carries the resolver check" \
            || bad "the packaged record-http is STALE — it has no resolver check"
        fi
        ORIGIN="package"
        if [ -f "$PKGBIN/keliver-record-http.sh" ]; then
          use_record_http "$PKGBIN/keliver-record-http.sh"
          run_record_http_rows
        fi
        if [ -f "$PKGBIN/keliver-adopt-legacy-store.sh" ]; then
          use_adopt "$PKGBIN/keliver-adopt-legacy-store.sh"
          run_adopt_rows
        fi
        ORIGIN="repo"
      fi
    else
      bad "could not unpack $CANDIDATE_ZIP"
      head -3 "$DISP/unzip.err" | sed 's/^/        /'
    fi
  fi
else
  echo "    (no ZIP given, so the packaged callers were NOT exercised)"
fi

echo
printf 'passed: %d   failed: %d\n' "$PASS" "$FAIL"
echo "evidence: $DISP"
[ "$FAIL" -eq 0 ]
