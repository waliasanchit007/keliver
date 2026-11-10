#!/usr/bin/env bash
#
# Self-test for keliver-check (PLAN_2026-11 WS5), through the shell wrapper the
# tools bundle ships and the relay install's keliver-check-jvm.
#
#   scripts/keliver-check-selftest.sh <work-dir> [--relay-home DIR | --package DIR]
#
# --relay-home: an existing portal-relay install (build/install/portal-relay, or
# a tools bundle's relay/). Without it, `./gradlew :portal-relay:installDist`
# runs in this checkout.
# --package: an unpacked tools bundle; its own bin/keliver-check and relay/ are tested.
#
# The apps are scratch directories under <work-dir> with a stand-in ./gradlew
# (it "compiles" by writing a bundle file, or fails with a Kotlin e: line), so
# the compile check's plumbing is tested without a real Gradle build. Nothing
# outside <work-dir> is written.
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
REPO="$(cd "$HERE/.." && pwd -P)"
WORK="${1:?usage: $0 <work-dir> [--relay-home DIR | --package DIR]}"
shift
RELAY_HOME=""
CHECK="$HERE/keliver-check"
while [ $# -gt 0 ]; do
  case "$1" in
    --relay-home) RELAY_HOME="${2:?}"; shift ;;
    --package) P="$(cd "${2:?}" && pwd -P)"; RELAY_HOME="$P/relay"; CHECK="$P/bin/keliver-check"; shift ;;
    *) echo "unknown argument $1" >&2; exit 2 ;;
  esac
  shift
done
mkdir -p "$WORK"
WORK="$(cd "$WORK" && pwd -P)"
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 17 2>/dev/null || true)}"

pass=0; fail=0
ok(){ printf '  PASS  %s\n' "$1"; pass=$((pass+1)); }
bad(){ printf '  FAIL  %s\n' "$1"; fail=$((fail+1)); }
expect(){ # expect <name> <want-exit> <got-exit>
  if [ "$2" = "$3" ]; then ok "$1 (exit $3)"; else bad "$1: exit $3, wanted $2"; fi; }
has(){ # has <name> <file> <fixed-string>
  if grep -qF -- "$3" "$2"; then ok "$1"; else bad "$1: no '$3' in $2"; sed 's/^/        /' "$2"; fi; }

if [ -z "$RELAY_HOME" ]; then
  echo "==> :portal-relay:installDist"
  (cd "$REPO" && ./gradlew -q :portal-relay:installDist) || { echo "installDist failed" >&2; exit 2; }
  RELAY_HOME="$REPO/portal-relay/build/install/portal-relay"
fi
export KELIVER_RELAY_HOME="$RELAY_HOME"
[ -x "$RELAY_HOME/bin/keliver-check-jvm" ] && ok "the relay install has bin/keliver-check-jvm" \
  || { bad "no $RELAY_HOME/bin/keliver-check-jvm"; exit 1; }

# --- a scratch app -------------------------------------------------------------
new_app(){ # new_app <dir> <budget-bytes> <gradlew-mode: ok|fail>
  local d="$1"
  rm -rf "$d"; mkdir -p "$d/src/screens" "$d/src/components" "$d/src/logic"
  cat >"$d/keliver.portal.json" <<EOF
{ "screensDir": "src/screens", "publishTask": ":guest:compileDevelopmentZipline",
  "publishOutput": "build/bundle", "check": { "bundleBudgetBytes": $2 } }
EOF
  cat >"$d/src/screens/Home.kt" <<'EOF'
package app.screens
import androidx.compose.runtime.Composable
import dev.keliver.material.compose.Text

interface HomeBindings { val title: String }

@Composable
fun HomeScreen(b: HomeBindings) {
  Text(text = b.title)
}
EOF
  cat >"$d/src/logic/Repo.kt" <<'EOF'
package app.logic
class Repo(val name: String)
EOF
  if [ "$3" = ok ]; then
    cat >"$d/gradlew" <<'EOF'
#!/bin/sh
# stand-in: "compile" by writing a 1000-byte bundle; record the task asked for
dir=""; while [ $# -gt 0 ]; do case "$1" in -p) dir="$2"; shift ;; -q) ;; *) echo "$1" >"$dir/task.txt" ;; esac; shift; done
mkdir -p "$dir/build/bundle" && head -c 1000 /dev/zero >"$dir/build/bundle/app.zipline"
EOF
  else
    cat >"$d/gradlew" <<EOF
#!/bin/sh
echo "e: file://$d/src/screens/Home.kt:9:3 Unresolved reference 'Txt'."
exit 1
EOF
  fi
  chmod +x "$d/gradlew"
}

run(){ # run <name> <args...>: stdout → $WORK/<name>.jsonl, stderr → .txt; sets $rc
  # Runs the script itself (its executable bit and shebang), and checks that
  # every stdout line is JSON in every scenario that prints any.
  local n="$1"; shift
  "$CHECK" "$@" >"$WORK/$n.jsonl" 2>"$WORK/$n.txt"; rc=$?
  if [ -s "$WORK/$n.jsonl" ] && command -v python3 >/dev/null 2>&1; then
    if python3 -I -c 'import json,sys; [json.loads(l) for l in open(sys.argv[1], encoding="utf-8").read().splitlines()]' "$WORK/$n.jsonl" 2>/dev/null; then
      ok "$n: every stdout line is JSON"; else bad "$n: stdout is not JSON lines"; sed 's/^/        /' "$WORK/$n.jsonl"; fi
  fi
}

echo "==> a clean app, with one component"
new_app "$WORK/clean" 5000 ok
cat >"$WORK/clean/src/components/PriceTag.kt" <<'EOF'
package app.components
import androidx.compose.runtime.Composable
import dev.keliver.material.compose.Text

@Composable
fun PriceTag(label: String) {
  Text(text = label)
}
EOF
run clean "$WORK/clean"
expect "clean app" 0 "$rc"
has "compile passes with the publishTask" "$WORK/clean.jsonl" '"check":"compile","severity":"pass"'
has "the compile ran the publishTask" "$WORK/clean/task.txt" ':guest:compileDevelopmentZipline'
has "editor: 0 RawCode, the component read" "$WORK/clean.jsonl" '1 screen(s), 1 component(s): 0 RawCode'
has "api passes" "$WORK/clean.jsonl" '"check":"api","severity":"pass"'
has "budget passes with the measured size" "$WORK/clean.jsonl" '"bytes":1000'
has "goldens is a skipped hook" "$WORK/clean.jsonl" '"check":"goldens","severity":"skip"'
has "a summary line ends the stream" "$WORK/clean.jsonl" '"check":"summary","schema":1,"severity":"pass","errors":0,"warnings":0,"exit":0'
if grep -q jvm-done "$WORK/clean.jsonl"; then bad "the jvm-done marker leaked"; else ok "the jvm-done marker is not in the output"; fi
has "a human summary on stderr" "$WORK/clean.txt" 'editor   ok'

echo "==> an opaque component"
new_app "$WORK/comp" 5000 ok
printf 'package app.components\nval notAComponent = 1\n' >"$WORK/comp/src/components/Nothing.kt"
run comp "$WORK/comp" --only editor
expect "a component file with no @Composable" 1 "$rc"
has "it is an error on its file" "$WORK/comp.jsonl" '"file":"src/components/Nothing.kt","message":"no @Composable'

echo "==> RawCode, an unsupported API, over budget"
new_app "$WORK/dirty" 500 ok
cat >"$WORK/dirty/src/screens/Home.kt" <<'EOF'
package app.screens
import androidx.compose.runtime.Composable
import dev.keliver.material.compose.Text

interface HomeBindings { val title: String }

@Composable
fun HomeScreen(b: HomeBindings) {
  Text(text = b.title)
  val x = listOf(1, 2).map { it * 2 }.joinToString()
  val x = listOf(1, 2).map { it * 2 }.joinToString()
}
EOF
printf 'package app.logic\nimport java.io.File\nclass Repo(val f: File)\n' >"$WORK/dirty/src/logic/Repo.kt"
run dirty "$WORK/dirty"
expect "findings" 1 "$rc"
has "RawCode with file and line" "$WORK/dirty.jsonl" '"check":"editor","severity":"error","file":"src/screens/Home.kt","line":10'
has "a duplicated RawCode gets its own line" "$WORK/dirty.jsonl" '"check":"editor","severity":"error","file":"src/screens/Home.kt","line":11'
has "java.io is a warning with file and line" "$WORK/dirty.jsonl" '"check":"api","severity":"warning","file":"src/logic/Repo.kt","line":2'
has "over budget" "$WORK/dirty.jsonl" 'over the budget of 500 B'

echo "==> warnings alone, and --strict"
new_app "$WORK/warn" 5000 ok
printf 'package app.logic\nimport android.content.Context\n' >"$WORK/warn/src/logic/Repo.kt"
run warn "$WORK/warn"
expect "warnings only" 0 "$rc"
run warn-strict "$WORK/warn" --strict
expect "--strict makes warnings findings" 1 "$rc"

echo "==> a compile error"
new_app "$WORK/broken" 5000 fail
run broken "$WORK/broken" --only compile
expect "compile fails" 1 "$rc"
has "the compiler's error with file and line" "$WORK/broken.jsonl" '"check":"compile","severity":"error","file":"src/screens/Home.kt","line":9'

echo "==> compiler output with control characters and no e: lines"
new_app "$WORK/noisy" 5000 fail
printf '#!/bin/sh\nprintf "\\033[31mFAILURE\\033[0m: Build failed\\r\\n\\tbell\\007 back\\\\slash \\"q\\"\\n"\nexit 1\n' >"$WORK/noisy/gradlew"
run noisy "$WORK/noisy" --only compile
expect "noisy compile fails" 1 "$rc"
has "the log tail is the message, escapes stripped" "$WORK/noisy.jsonl" 'failed: FAILURE: Build failed'

echo "==> a failed compile does not measure yesterday's bundle"
new_app "$WORK/stale" 5000 fail
mkdir -p "$WORK/stale/build/bundle" && head -c 100 /dev/zero >"$WORK/stale/build/bundle/app.zipline"
run stale "$WORK/stale" --only compile,budget
expect "stale bundle" 1 "$rc"
has "budget is skipped after the failed compile" "$WORK/stale.jsonl" '"check":"budget","severity":"skip"'
if grep -q '"check":"budget","severity":"pass"' "$WORK/stale.jsonl"; then bad "the stale bundle passed the budget"; else ok "the stale bundle did not pass"; fi

echo "==> a crash in the file checks is never a pass"
mkdir -p "$WORK/fakejvm/bin"
printf '#!/bin/sh\necho "Exception in thread main" >&2\nexit 1\n' >"$WORK/fakejvm/bin/keliver-check-jvm"
chmod +x "$WORK/fakejvm/bin/keliver-check-jvm"
KELIVER_RELAY_HOME="$WORK/fakejvm" "$CHECK" "$WORK/clean" --skip-compile >"$WORK/crash1.jsonl" 2>"$WORK/crash1.txt"
expect "an exit-1 JVM with no output" 2 "$?"
has "its stderr is shown" "$WORK/crash1.txt" 'Exception in thread main'
new_app "$WORK/crash" 5000 ok
printf 'package app.screens\n' >"$WORK/crash/src/screens/Locked.kt"; chmod 000 "$WORK/crash/src/screens/Locked.kt"
run crash2 "$WORK/crash" --skip-compile --only editor
chmod 644 "$WORK/crash/src/screens/Locked.kt"
expect "an unreadable screen file crashes the editor check" 2 "$rc"
has "the crash is reported" "$WORK/crash2.txt" 'the checks crashed'

echo "==> a compile task that is not a task path"
new_app "$WORK/badtask" 5000 ok
printf '{ "screensDir": "src/screens", "publishTask": "--init-script=x", "publishOutput": "build/bundle" }\n' >"$WORK/badtask/keliver.portal.json"
run badtask "$WORK/badtask"
expect "an option as the compile task" 2 "$rc"
if [ -e "$WORK/badtask/task.txt" ]; then bad "Gradle ran with it"; else ok "Gradle did not run"; fi

echo "==> --skip-compile and --only"
run skip "$WORK/clean" --skip-compile --only compile,editor
expect "--skip-compile" 0 "$rc"
has "compile reported as skipped" "$WORK/skip.jsonl" '"check":"compile","severity":"skip"'
if grep -q '"check":"api"' "$WORK/skip.jsonl"; then bad "--only ran api"; else ok "--only runs only what it names"; fi

echo "==> usage and environment"
run usage1 "$WORK/clean" --bogus;            expect "unknown option" 2 "$rc"
run usage2 "$WORK/clean" --only nonsense;    expect "unknown check" 2 "$rc"
run usage2b "$WORK/clean" --only '*';        expect "--only '*' is not a glob" 2 "$rc"
mkdir -p "$WORK/not-an-app"
run usage3 "$WORK/not-an-app";               expect "no keliver.portal.json" 2 "$rc"
run usage4 "$WORK/missing-dir";              expect "no such directory" 2 "$rc"
KELIVER_RELAY_HOME="$WORK/nowhere" "$CHECK" "$WORK/clean" >/dev/null 2>&1; expect "no relay install" 2 "$?"

echo
echo "keliver-check selftest: $pass passed, $fail failed"
[ "$fail" = 0 ]
