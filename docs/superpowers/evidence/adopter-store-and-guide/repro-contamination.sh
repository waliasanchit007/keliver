#!/usr/bin/env bash
# Regression: two adopter apps must not see each other's screens, and the
# portal must not write one app's screens into the other app's source tree.
#
#   repro-contamination.sh <relay-bin>
#
# Runs entirely inside a disposable HOME, so the developer's real
# ~/.keliver-portal is never read or written.
set -uo pipefail
RELAY="${1:?usage: $0 <relay-bin>}"
R="$(cd "$(dirname "$0")" && pwd -P)"
# Setting HOME is NOT enough: storeDir() resolves "~/" through the JVM's
# user.home system property, which macOS derives from the passwd entry and not
# from $HOME. An earlier version of this script set only HOME and wrote into
# the developer's real ~/.keliver-portal. Override the property too.
export HOME="$R/home"
mkdir -p "$HOME"
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 17)}"
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Duser.home=$HOME"
pass=0; fail=0
ok()  { printf '  PASS  %s\n' "$1"; pass=$((pass+1)); }
bad() { printf '  FAIL  %s\n' "$1"; fail=$((fail+1)); }

start() { # app, port
  ( cd "$R/apps/$1" && PORTAL_REPO="$R/apps/$1" "$RELAY" > "$R/relay-$1.log" 2>&1 & )
  for i in $(seq 1 40); do curl -sf -m 2 -o /dev/null "http://localhost:$2/screens" && return 0; sleep 3; done
  return 1
}
stop() { lsof -ti :"$1" -sTCP:LISTEN 2>/dev/null | xargs kill 2>/dev/null; sleep 3; }

srcfiles() { ( cd "$R/apps/$1" && find src -type f | sort ); }
BEFORE_X="$(srcfiles appx)"; BEFORE_Y="$(srcfiles appy)"

echo "contamination regression   HOME=$HOME"
echo "store on disk before: $(ls "$HOME/.keliver-portal" 2>/dev/null | tr '\n' ' ')"

# --- app X boots and its editor opens a document (the ordinary first run) ---
start appx 8101 || { bad "appx relay did not start"; echo; echo "passed: $pass   failed: $fail"; exit 1; }
X_SCREENS="$(curl -s http://localhost:8101/screens)"
curl -s -o /dev/null "http://localhost:8101/doc"
stop 8101
echo "  appx sees: $X_SCREENS"
[ "$X_SCREENS" = '["orders"]' ] && ok "appx sees only its own screen" || bad "appx sees $X_SCREENS"

# --- app Y boots on the same machine ---
start appy 8102 || { bad "appy relay did not start"; echo; echo "passed: $pass   failed: $fail"; exit 1; }
Y_SCREENS="$(curl -s http://localhost:8102/screens)"
Y_PROJECTS="$(curl -s http://localhost:8102/projects)"
curl -s -o /dev/null "http://localhost:8102/doc"
sleep 3
stop 8102
echo "  appy sees screens: $Y_SCREENS   projects: $Y_PROJECTS"

# 1. isolation of the document store
case "$Y_SCREENS" in
  *orders*) bad "ISOLATION: appy sees appx's screen 'orders' ($Y_SCREENS)" ;;
  *profile*) ok "appy sees only its own screen" ;;
  *) bad "appy sees unexpected $Y_SCREENS" ;;
esac

# 2. no foreign source written into either app
AFTER_X="$(srcfiles appx)"; AFTER_Y="$(srcfiles appy)"
if [ "$AFTER_Y" = "$BEFORE_Y" ]; then ok "appy source tree unchanged"
else bad "appy source tree gained files:"; diff <(echo "$BEFORE_Y") <(echo "$AFTER_Y") | sed 's/^/        /'; fi
if [ "$AFTER_X" = "$BEFORE_X" ]; then ok "appx source tree unchanged"
else bad "appx source tree gained files:"; diff <(echo "$BEFORE_X") <(echo "$AFTER_X") | sed 's/^/        /'; fi

# 3. persistence across restart: appy must still see its own document
start appy 8102 || { bad "appy relay did not restart"; echo; echo "passed: $pass   failed: $fail"; exit 1; }
Y2="$(curl -s http://localhost:8102/screens)"
stop 8102
case "$Y2" in
  *profile*) case "$Y2" in *orders*) bad "after restart appy still sees 'orders'" ;;
                           *) ok "after restart appy still sees its own screen and only that" ;; esac ;;
  *) bad "after restart appy lost its screen ($Y2)" ;;
esac

# --- 4. BOTH RUNNING AT ONCE: the ordinary two-project situation -------------
start appy 8102 || bad "appy did not start for the concurrent check"
start appx 8101 || bad "appx did not start for the concurrent check"
sleep 3
CY="$(curl -s http://localhost:8102/screens)"; CX="$(curl -s http://localhost:8101/screens)"
echo "  concurrent — appy: $CY   appx: $CX"
case "$CY" in *orders*) bad "CONCURRENT: appy's screen list shows appx's screen ($CY)" ;;
              *profile*) ok "concurrent: appy still lists only its own screen" ;;
              *) bad "concurrent: appy lists $CY" ;; esac
case "$CX" in *profile*) bad "CONCURRENT: appx's screen list shows appy's screen ($CX)" ;;
              *orders*) ok "concurrent: appx still lists only its own screen" ;;
              *) bad "concurrent: appx lists $CX" ;; esac

# opening a foreign screen must not materialise it into this app's sources
curl -s -o /dev/null "http://localhost:8102/doc?project=default&screen=orders"
sleep 2
AFTER2_Y="$(srcfiles appy)"
if [ "$AFTER2_Y" = "$BEFORE_Y" ]; then ok "appy source tree still unchanged after requesting a foreign screen"
else bad "appy source tree gained foreign files:"; diff <(echo "$BEFORE_Y") <(echo "$AFTER2_Y") | sed 's/^/        /'; fi
stop 8101; stop 8102

# --- 5. appx's documents survived appy's boot --------------------------------
start appx 8101 || bad "appx did not restart"
X2="$(curl -s http://localhost:8101/screens)"
stop 8101
case "$X2" in *orders*) ok "appx's own screen survived the other app's lifecycle" ;;
              *) bad "appx lost its screen after appy ran ($X2)" ;; esac

echo
echo "passed: $pass   failed: $fail"
[ "$fail" -eq 0 ]
