#!/usr/bin/env bash
#
# Regression coverage for keliver-consumer-smoke.sh.
#
#   scripts/keliver-consumer-smoke-selftest.sh [version] [--smoke PATH]
#
# --smoke points the suite at an alternative implementation. That exists so the
# suite can be validated against DELIBERATELY BROKEN smoke commands: a test
# suite that cannot fail is not coverage. See --meta.
#
#   scripts/keliver-consumer-smoke-selftest.sh --meta
#     Runs the suite against broken implementations and asserts it rejects each
#     one. This is itself regression coverage, not a throwaway demonstration.
#
# The smoke script exists to prevent a silent false pass, so every assertion
# here must be able to fail. Two previously could not:
#   * the pre-existing-cache check only set a note and never incremented `fail`
#   * `grep -q ... 2>/dev/null` on a MISSING evidence file returns nonzero and
#     was read as "mavenLocal absent" — a missing file proved success
#
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SMOKE="$ROOT/scripts/keliver-consumer-smoke.sh"
VERSION="0.3.3"
META=0
while [ $# -gt 0 ]; do
  case "$1" in
    --smoke) [ $# -ge 2 ] || { echo "--smoke needs a value" >&2; exit 2; }; SMOKE="$2"; shift 2 ;;
    --meta)  META=1; shift ;;
    *)       VERSION="$1"; shift ;;
  esac
done

# ---------------------------------------------------------------- meta mode --
if [ "$META" = "1" ]; then
  MW="$(mktemp -d "${TMPDIR:-/tmp}/smoke-meta-XXXXXX")"
  mpass=0; mfail=0
  mcheck() { # name  expected(0=suite passes,1=suite rejects)  actual
    if [ "$2" = "$3" ]; then printf '  PASS  %-52s\n' "$1"; mpass=$((mpass+1))
    else printf '  FAIL  %-52s suite exit %s, wanted %s\n' "$1" "$3" "$2"; mfail=$((mfail+1)); fi
  }
  echo "meta: validating the suite against broken smoke implementations"

  # B1 — always succeeds, produces no evidence at all
  cat > "$MW/always-pass.sh" <<'EOS'
#!/usr/bin/env bash
out=""; while [ $# -gt 0 ]; do [ "$1" = "--out" ] && out="$2"; shift; done
[ -n "$out" ] && mkdir -p "$out"
exit 0
EOS
  # B2 — succeeds and leaves evidence, but REUSES/alters the seeded cache dir
  cat > "$MW/eats-cache.sh" <<'EOS'
#!/usr/bin/env bash
out=""; prev=""; for a in "$@"; do [ "$prev" = "--out" ] && out="$a"; prev="$a"; done
[ -n "$out" ] || exit 0
mkdir -p "$out/gradle-home"
echo "clobbered" > "$out/gradle-home/PRE_EXISTING_MARKER"
touch "$out/gradle-home/used-as-cache"
printf 'repositories { mavenCentral() }\n' > "$out/generated-settings.gradle"
exit 0
EOS
  # B3 — succeeds, but the generated project DOES contain mavenLocal
  cat > "$MW/leaks-mavenlocal.sh" <<'EOS'
#!/usr/bin/env bash
out=""; prev=""; for a in "$@"; do [ "$prev" = "--out" ] && out="$a"; prev="$a"; done
[ -n "$out" ] || exit 0
mkdir -p "$out"
printf 'repositories { mavenLocal(); mavenCentral() }\n' > "$out/generated-settings.gradle"
exit 0
EOS
  # B4 — never fails, not even on a nonexistent version or bad args
  cat > "$MW/never-fails.sh" <<'EOS'
#!/usr/bin/env bash
out=""; prev=""; for a in "$@"; do [ "$prev" = "--out" ] && out="$a"; prev="$a"; done
[ -n "$out" ] && { mkdir -p "$out"; printf 'repositories { mavenCentral() }\n' > "$out/generated-settings.gradle"; }
exit 0
EOS
  chmod +x "$MW"/*.sh
  for b in always-pass eats-cache leaks-mavenlocal never-fails; do
    "$0" "$VERSION" --smoke "$MW/$b.sh" >"$MW/$b.out" 2>&1
    mcheck "suite rejects broken impl: $b" 1 "$?"
  done
  # and it must still accept the real one
  "$0" "$VERSION" >"$MW/real.out" 2>&1
  mcheck "suite accepts the real smoke script" 0 "$?"
  echo
  echo "meta passed: $mpass   failed: $mfail"
  echo "artifacts: $MW"
  [ "$mfail" -eq 0 ]; exit $?
fi

# ---------------------------------------------------------------- main suite --
WORK="$(mktemp -d "${TMPDIR:-/tmp}/smoke-selftest-XXXXXX")"
pass=0; fail=0
ok()  { printf '  PASS  %s\n' "$1"; pass=$((pass+1)); }
bad() { printf '  FAIL  %s\n' "$1"; fail=$((fail+1)); }
chk() { if [ "$2" = "$3" ]; then ok "$1 (exit $3)"; else bad "$1 — expected exit $2, got $3"; fi; }

echo "keliver-consumer-smoke self-test   version=$VERSION"
echo "smoke:   $SMOKE"
echo "workdir: $WORK"
[ -x "$SMOKE" ] || { echo "  FAIL  smoke command not executable"; echo; echo "passed: 0   failed: 1"; exit 1; }

run_bounded() { # seconds, then the command — guards against a hang
  local secs="$1"; shift
  ( "$@" >/dev/null 2>&1 ) & local p=$!
  ( sleep "$secs"; kill -9 $p 2>/dev/null ) & local w=$!
  wait $p 2>/dev/null; local rc=$?
  kill $w 2>/dev/null
  return $rc
}

# 1-2. missing option values must exit, not spin
run_bounded 15 "$SMOKE" "$VERSION" --out;        chk "missing --out value exits, does not hang" 2 "$?"
run_bounded 15 "$SMOKE" "$VERSION" --scaffolder; chk "missing --scaffolder value exits, does not hang" 2 "$?"

# 3. FAILED SCAFFOLDING — a scaffolder that exits nonzero
cat > "$WORK/bad-scaffolder.sh" <<'EOS'
#!/usr/bin/env bash
echo "scaffolder: deliberate failure" >&2
exit 1
EOS
chmod +x "$WORK/bad-scaffolder.sh"
"$SMOKE" "$VERSION" --scaffolder "$WORK/bad-scaffolder.sh" --out "$WORK/failscaffold" >/dev/null 2>&1
chk "failed scaffolding step fails" 1 "$?"

# 4. FAILED COMPILATION — scaffolding SUCCEEDS, the project does not build.
#    Distinct from case 3: this exercises the resolve/compile stage.
cat > "$WORK/uncompilable-scaffolder.sh" <<'EOS'
#!/usr/bin/env bash
name="${1:-Smoke}"; dir="$(pwd)/$(echo "$name" | tr '[:upper:]' '[:lower:]')"
mkdir -p "$dir/src/jsMain/kotlin"
cat > "$dir/settings.gradle" <<'INNER'
dependencyResolutionManagement { repositories { mavenCentral() } }
rootProject.name = 'smoke'
INNER
cat > "$dir/build.gradle" <<INNER
plugins { id 'org.jetbrains.kotlin.multiplatform' version '2.2.0' }
kotlin { js { browser() } }
dependencies { }
// pinned so the smoke script's version assertion is satisfied
// dev.keliver:keliver-material-compose:${KELIVER_VERSION}
INNER
echo 'this is not valid kotlin' > "$dir/src/jsMain/kotlin/Broken.kt"
cp -R "$(dirname "$0")/wrapper/." "$dir/" 2>/dev/null || true
exit 0
EOS
chmod +x "$WORK/uncompilable-scaffolder.sh"
mkdir -p "$WORK/wrapper"
cp -R "$ROOT/gradlew" "$ROOT/gradle" "$WORK/wrapper/" 2>/dev/null || true
"$SMOKE" "$VERSION" --scaffolder "$WORK/uncompilable-scaffolder.sh" --out "$WORK/failcompile" >/dev/null 2>&1
chk "failed compilation step fails (distinct from scaffolding)" 1 "$?"

# 5. artifacts unavailable
"$SMOKE" 0.0.0-does-not-exist --out "$WORK/badversion" >/dev/null 2>&1
chk "version absent from Central fails" 1 "$?"

# 6. snapshot refused
"$SMOKE" 9.9.9-SNAPSHOT --out "$WORK/snap" >/dev/null 2>&1
chk "snapshot version refused" 2 "$?"

# 7. the happy path, from a hostile parent environment
mkdir -p "$WORK/preexisting/gradle-home"
echo marker > "$WORK/preexisting/gradle-home/PRE_EXISTING_MARKER"
SEED_SUM="$(shasum "$WORK/preexisting/gradle-home/PRE_EXISTING_MARKER" | awk '{print $1}')"
KELIVER_USE_MAVEN_LOCAL=1 "$SMOKE" "$VERSION" --out "$WORK/preexisting" >/dev/null 2>&1
rc=$?
chk "hostile env + pre-existing dir still passes" 0 "$rc"

# 7a. the seeded cache must be neither reused nor altered — THIS MUST FAIL LOUDLY
if [ ! -f "$WORK/preexisting/gradle-home/PRE_EXISTING_MARKER" ]; then
  bad "seeded cache: marker was deleted"
elif [ "$(shasum "$WORK/preexisting/gradle-home/PRE_EXISTING_MARKER" | awk '{print $1}')" != "$SEED_SUM" ]; then
  bad "seeded cache: marker was modified"
elif [ "$(ls -A "$WORK/preexisting/gradle-home" | wc -l | tr -d ' ')" != "1" ]; then
  bad "seeded cache: directory was written into (extra entries present)"
else
  ok "seeded cache neither reused nor altered"
fi

# 7b. evidence must EXIST and be readable; a missing file is a failure, never a pass
EV="$WORK/preexisting/generated-settings.gradle"
if [ ! -r "$EV" ]; then
  bad "generated-settings.gradle missing or unreadable (cannot verify mavenLocal)"
else
  if grep -q "mavenLocal" "$EV"; then
    bad "generated project contains mavenLocal despite env stripping"
  else
    ok "hostile env stripped: no mavenLocal in the generated project"
  fi
fi

# 7c. the run must record a cold dependency cache and a real result
RES="$WORK/preexisting/result.txt"
if [ ! -r "$RES" ]; then
  bad "result.txt missing or unreadable"
elif ! grep -q "^result:.*PASS" "$RES"; then
  bad "result.txt does not record a PASS"
else
  ok "result.txt records a PASS"
fi

LOG="$WORK/preexisting/smoke.log"
if [ ! -r "$LOG" ]; then
  bad "smoke.log missing or unreadable"
elif ! grep -q "dependency cache empty" "$LOG"; then
  bad "smoke.log does not show a cold dependency cache"
else
  ok "smoke.log shows the dependency cache started empty"
fi

echo
echo "passed: $pass   failed: $fail"
echo "artifacts: $WORK"
[ "$fail" -eq 0 ]
