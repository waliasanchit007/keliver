#!/usr/bin/env bash
#
# Regression: the acceptance entry point must never delete what the caller gave it.
#
#   scripts/keliver-acceptance-safety-check.sh
#
# keliver-adopter-acceptance.sh began with `rm -rf "$1"` — before the isolation
# guard ran. Point it at a reused path and the contents were gone.
#
# The old behaviour is demonstrated with a STUB that reproduces just that line,
# inside a throwaway directory, so nothing real is at risk. The real script is
# then run against a directory holding the same sentinels, which must survive.
#
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
ACC="$ROOT/scripts/keliver-adopter-acceptance.sh"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/acceptance-safety-XXXXXX")"
pass=0; fail=0
ok(){ printf '  PASS  %s\n' "$1"; pass=$((pass+1)); }
bad(){ printf '  FAIL  %s\n' "$1"; fail=$((fail+1)); }

seed() { # dir
  mkdir -p "$1/earlier-evidence"
  printf 'irreplaceable\n' > "$1/SENTINEL.txt"
  printf 'prior run\n'     > "$1/earlier-evidence/report.txt"
}
survives() { [ -f "$1/SENTINEL.txt" ] && [ -f "$1/earlier-evidence/report.txt" ]; }

echo "acceptance safety   workdir: $WORK"

# --- 1. the OLD behaviour, reproduced by a stub, destroys the caller's data ---
OLDDIR="$WORK/old"; mkdir -p "$OLDDIR"; seed "$OLDDIR"
cat > "$WORK/old-style.sh" <<'EOS'
#!/usr/bin/env bash
set -uo pipefail
DISP="$1"
rm -rf "$DISP"; mkdir -p "$DISP/home"    # the line as it was
EOS
chmod +x "$WORK/old-style.sh"
"$WORK/old-style.sh" "$OLDDIR" >/dev/null 2>&1
if survives "$OLDDIR"; then
  bad "the stub did not reproduce the old destruction — this check proves nothing"
else
  ok "the old line destroys a caller-supplied directory (reproduced in a temp dir)"
fi

# --- 2. the CURRENT script preserves everything -------------------------------
NEWDIR="$WORK/new"; mkdir -p "$NEWDIR"; seed "$NEWDIR"
BEFORE="$(cd "$NEWDIR" && find . -type f | sort | xargs shasum)"
# a package path that does not exist: the script must fail on THAT, having
# already had the chance to delete, and it must still not delete.
"$ACC" "$NEWDIR" "$WORK/no-such-package.zip" >"$WORK/missing-pkg.log" 2>&1
rc=$?
[ "$rc" -ne 0 ] && ok "a missing package is refused (exit $rc)" || bad "a missing package was accepted"
if survives "$NEWDIR"; then ok "sentinels survive a refused run"
else bad "the caller's files were destroyed by a refused run"; fi
[ "$BEFORE" = "$(cd "$NEWDIR" && find . -type f | sort | xargs shasum)" ] \
  && ok "every caller file is byte-identical after a refused run" \
  || bad "a caller file changed"

# --- 3. a real (but early-failing) run still preserves them -------------------
: > "$WORK/empty.zip"                     # a package that unzips to nothing
"$ACC" "$NEWDIR" "$WORK/empty.zip" >"$WORK/empty-pkg.log" 2>&1 || true
if survives "$NEWDIR"; then ok "sentinels survive a run that fails after unpacking"
else bad "a failing run destroyed the caller's files"; fi

# --- 4. it works beneath the parent, in its own unique directory --------------
RUNS="$(find "$NEWDIR" -maxdepth 1 -type d -name 'keliver-acceptance-*' | wc -l | tr -d ' ')"
[ "$RUNS" -ge 1 ] && ok "each run gets its own directory under the parent ($RUNS so far)" \
                  || bad "no run directory was created under the parent"
FIRST="$(find "$NEWDIR" -maxdepth 1 -type d -name 'keliver-acceptance-*' | head -1)"
SECOND="$(find "$NEWDIR" -maxdepth 1 -type d -name 'keliver-acceptance-*' | tail -1)"
[ "$RUNS" -lt 2 ] || { [ "$FIRST" != "$SECOND" ] && ok "run directories are unique" || bad "run directories collide"; }

# --- 5. the guard runs BEFORE any relay is started ----------------------------
GUARD_LINE="$(grep -n 'keliver_require_isolated_store' "$ACC" | head -1 | cut -d: -f1)"
RELAY_LINE="$(grep -n 'keliver-portal" \.' "$ACC" | head -1 | cut -d: -f1)"
if [ -n "$GUARD_LINE" ] && [ -n "$RELAY_LINE" ] && [ "$GUARD_LINE" -lt "$RELAY_LINE" ]; then
  ok "the isolation guard runs before the relay starts (line $GUARD_LINE < $RELAY_LINE)"
else
  bad "the guard does not precede relay startup (guard=$GUARD_LINE relay=$RELAY_LINE)"
fi

# --- 6. no blanket port kills remain -----------------------------------------
if grep -q 'lsof -ti :[0-9]* -sTCP:LISTEN | xargs kill' "$ACC"; then
  bad "the script still kills whatever holds a port"
else
  ok "cleanup stops only processes this invocation started"
fi

echo
echo "passed: $pass   failed: $fail"
echo "artifacts: $WORK"
[ "$fail" -eq 0 ]
