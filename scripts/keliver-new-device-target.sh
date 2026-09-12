#!/usr/bin/env bash
#
# keliver new-device-target — give an existing keliver-init app a device path.
#
#   keliver-new-device-target.sh [--screen NAME] [--presenter NAME]
#
# Run from the APP repo root (the directory holding keliver.portal.json).
#
# WHAT THIS IS FOR. `keliver-init` scaffolds a guest-only project: `js { browser() }`
# and no Android/iOS target. That is correct — a Keliver guest is not an app
# module — but it leaves a new adopter with no way to see their screens on a
# device without hand-assembling four things. This emits exactly those four:
#
#   1. the Zipline plugin, in settings.gradle pluginManagement
#   2. the plugin applied + `binaries.executable()` (needed for
#      serveDevelopmentZipline)
#   3. the published deps a Treehouse guest needs
#   4. src/jsMain/kotlin/device/Main.kt — binds a PortalPresenter that renders
#      your screen with your presenter
#
# It does NOT generate an Android application module: it wires the app to the
# generic device host (`dev.keliver.portaldevice`), which loads a guest bundle
# over HTTP. For a bespoke host, copy `sample/host-android` instead.
#
# ALL-OR-NOTHING: every planned edit is validated and staged before anything is
# written. A rejected input leaves the app byte-identical.
#
set -euo pipefail

KELIVER_VERSION="${KELIVER_VERSION:-0.3.3}"
ZIPLINE_VERSION="${ZIPLINE_VERSION:-1.22.0}"
SCREEN=""
PRESENTER=""
while [ $# -gt 0 ]; do
  case "$1" in
    --screen)    [ $# -ge 2 ] || { echo "--screen needs a value" >&2; exit 2; }; SCREEN="$2"; shift 2 ;;
    --presenter) [ $# -ge 2 ] || { echo "--presenter needs a value" >&2; exit 2; }; PRESENTER="$2"; shift 2 ;;
    -h|--help)   sed -n '2,26p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *)           echo "unknown option: $1" >&2; exit 2 ;;
  esac
done

APP="$(pwd)"
[ -f "$APP/keliver.portal.json" ] || { echo "error: no keliver.portal.json here — run from the app root" >&2; exit 1; }
[ -f "$APP/build.gradle" ] || { echo "error: no build.gradle here" >&2; exit 1; }
[ -f "$APP/settings.gradle" ] || { echo "error: no settings.gradle here" >&2; exit 1; }
[ -e "$APP/src/jsMain/kotlin/device" ] && { echo "error: $APP/src/jsMain/kotlin/device already exists — refusing to overwrite" >&2; exit 1; }

# ---------------------------------------------------------------------------
# Resolve + validate EVERYTHING before writing. Python because per-file parsing
# with `grep -m1` across several files silently produced filename-prefixed
# garbage (and paired an arbitrary screen with an arbitrary presenter).
# ---------------------------------------------------------------------------
PLAN="$(python3 - "$APP" "$SCREEN" "$PRESENTER" "$KELIVER_VERSION" "$ZIPLINE_VERSION" <<'PY'
import os, re, sys, json, glob

app, want_screen, want_presenter, kv, zv = sys.argv[1:6]

def fail(msg):
    print("ERROR " + msg)
    raise SystemExit(3)

def package_of(path, text):
    m = re.search(r'^\s*package\s+([A-Za-z_][A-Za-z0-9_.]*)\s*$', text, re.M)
    if not m:
        fail(f"{os.path.relpath(path, app)} has no package declaration")
    return m.group(1)

def decls(pattern, files):
    """[(name, package, relpath)] for every top-level `fun <Name><Suffix>(`."""
    out = []
    for f in sorted(files):
        text = open(f, encoding='utf-8').read()
        pkg = package_of(f, text)
        for m in re.finditer(pattern, text, re.M):
            out.append((m.group(1), pkg, os.path.relpath(f, app)))
    return out

screen_files = glob.glob(os.path.join(app, 'src/jsMain/kotlin/screens/*.kt'))
logic_files  = glob.glob(os.path.join(app, 'src/jsMain/kotlin/logic/*.kt'))
if not screen_files:
    fail("no screen sources under src/jsMain/kotlin/screens/")

screens = decls(r'^\s*fun\s+([A-Z][A-Za-z0-9]*Screen)\s*\(', screen_files)
if not screens:
    fail("no 'fun <Name>Screen(' found in src/jsMain/kotlin/screens/")

def pick(kind, found, wanted, flag):
    names = [n for n, _, _ in found]
    if wanted:
        hits = [t for t in found if t[0] == wanted]
        if not hits:
            fail(f"{kind} '{wanted}' not found. Available: {', '.join(sorted(set(names)))}")
        if len(hits) > 1:
            where = ', '.join(t[2] for t in hits)
            fail(f"{kind} '{wanted}' is declared more than once ({where}); names must be unique")
        return hits[0]
    if len(found) > 1:
        listing = ', '.join(f"{n} ({p})" for n, _, p in found)
        fail(f"multiple {kind}s found — pass {flag} to choose one. Available: {listing}")
    return found[0]

screen_name, screen_pkg, screen_file = pick("screen", screens, want_screen, "--screen")

presenters = decls(r'^\s*fun\s+([A-Z][A-Za-z0-9]*Presenter)\s*\(', logic_files)
if not presenters:
    fail("no 'fun <Name>Presenter(' found in src/jsMain/kotlin/logic/")
presenter_name, presenter_pkg, presenter_file = pick("presenter", presenters, want_presenter, "--presenter")

# The screen package is the app package + '.screens'; derive the root from it
# rather than from an arbitrary file, and require the conventional layout.
if not screen_pkg.endswith('.screens'):
    fail(f"screen package '{screen_pkg}' does not end in '.screens' (unsupported layout)")
root_pkg = screen_pkg[: -len('.screens')]

# The presenter must be reachable; warn loudly if it lives elsewhere.
if presenter_pkg != root_pkg + '.logic':
    fail(f"presenter package '{presenter_pkg}' is not '{root_pkg}.logic' (unsupported layout)")

# ---- validate every planned build-file edit is applicable ------------------
settings = open(os.path.join(app, 'settings.gradle'), encoding='utf-8').read()
build    = open(os.path.join(app, 'build.gradle'), encoding='utf-8').read()

if 'app.cash.zipline' not in settings:
    if not re.search(r"\n(\s*)id 'org\.jetbrains\.compose' version '[^']+'", settings):
        fail("settings.gradle has no `id 'org.jetbrains.compose' version '...'` line to anchor the zipline plugin version")

if 'app.cash.zipline' not in build:
    if not re.search(r"\nplugins \{.*?\n\}", build, re.S):
        fail("build.gradle has no `plugins { ... }` block")
if 'binaries.executable' not in build:
    if not re.search(r"\n(\s*)js \{ browser\(\) \}", build):
        fail("build.gradle has no `js { browser() }` line to convert (unsupported build-file structure)")
if 'keliver-treehouse-guest' not in build:
    if not re.search(r"\n\s*implementation 'org\.jetbrains\.compose\.runtime:runtime:[^']+'\n", build):
        fail("build.gradle has no compose runtime dependency line to anchor the guest deps")

print(json.dumps({
    "root_pkg": root_pkg,
    "screen": screen_name, "screen_file": screen_file,
    "presenter": presenter_name, "presenter_file": presenter_file,
}))
PY
)" || { echo "$PLAN" | sed 's/^ERROR /error: /' >&2; exit 3; }

case "$PLAN" in
  ERROR*) echo "$PLAN" | sed 's/^ERROR /error: /' >&2; exit 3 ;;
esac

PKG=$(printf '%s' "$PLAN" | python3 -c 'import json,sys; print(json.load(sys.stdin)["root_pkg"])')
SCREEN=$(printf '%s' "$PLAN" | python3 -c 'import json,sys; print(json.load(sys.stdin)["screen"])')
PRESENTER=$(printf '%s' "$PLAN" | python3 -c 'import json,sys; print(json.load(sys.stdin)["presenter"])')
SCREEN_FILE=$(printf '%s' "$PLAN" | python3 -c 'import json,sys; print(json.load(sys.stdin)["screen_file"])')
PRESENTER_FILE=$(printf '%s' "$PLAN" | python3 -c 'import json,sys; print(json.load(sys.stdin)["presenter_file"])')

echo "==> package:   $PKG"
echo "    screen:    $SCREEN   ($SCREEN_FILE)"
echo "    presenter: $PRESENTER   ($PRESENTER_FILE)"

# ---- apply: build files first (validated above), then the entry point ------
python3 - "$APP" "$KELIVER_VERSION" "$ZIPLINE_VERSION" <<'PY'
import re, sys, os
app, kv, zv = sys.argv[1:4]

sp = os.path.join(app, 'settings.gradle'); s = open(sp, encoding='utf-8').read()
if 'app.cash.zipline' not in s:
    s = re.sub(r"(\n(\s*)id 'org\.jetbrains\.compose' version '[^']+')",
               r"\1\n\2id 'app.cash.zipline' version '" + zv + "'", s, count=1)
    open(sp, 'w', encoding='utf-8').write(s)

bp = os.path.join(app, 'build.gradle'); b = open(bp, encoding='utf-8').read()
if 'app.cash.zipline' not in b:
    # anchor on the block's closing brace, not on any particular plugin id
    m = re.search(r"\nplugins \{.*?\n\}", b, re.S)
    block = m.group(0)
    b = b.replace(block, block[:-1] + "  id 'app.cash.zipline'\n}", 1)
if 'binaries.executable' not in b:
    b = re.sub(r"\n(\s*)js \{ browser\(\) \}",
               "\n\\1js {\n\\1  browser()\n\\1  // required for the Zipline bundle + serveDevelopmentZipline\n\\1  binaries.executable()\n\\1}", b, count=1)
if 'keliver-treehouse-guest' not in b:
    dep = ("        // device path: render this app's screens on the generic keliver host\n"
           f"        api 'dev.keliver:keliver-treehouse-guest:{kv}'\n"
           f"        api 'dev.keliver:keliver-treehouse-guest-compose:{kv}'\n"
           f"        implementation 'dev.keliver:keliver-material-protocol-guest-web:{kv}'\n"
           f"        implementation 'app.cash.zipline:zipline:{zv}'\n")
    b = re.sub(r"(\n\s*implementation 'org\.jetbrains\.compose\.runtime:runtime:[^']+'\n)",
               r"\1" + dep, b, count=1)
open(bp, 'w', encoding='utf-8').write(b)
PY
echo "    settings.gradle / build.gradle updated"

mkdir -p "$APP/src/jsMain/kotlin/device"
cat > "$APP/src/jsMain/kotlin/device/Main.kt" <<EOF
package ${PKG}.device

import androidx.compose.runtime.Composable
import app.cash.zipline.Zipline
import app.cash.zipline.ZiplineService
import dev.keliver.material.protocol.guest.KeliverMaterialProtocolWidgetSystemFactory
import dev.keliver.treehouse.AppService
import dev.keliver.treehouse.StandardAppLifecycle
import dev.keliver.treehouse.TreehouseUi
import dev.keliver.treehouse.ZiplineTreehouseUi
import dev.keliver.treehouse.asZiplineTreehouseUi
import ${PKG}.logic.${PRESENTER}
import ${PKG}.screens.${SCREEN}

/**
 * The service the generic keliver device host take()s.
 *
 * Declared here BY SHAPE on purpose: Zipline binds services by name and
 * signature, so this app does not need the host's own module on its
 * classpath — which matters, because that module is not published.
 */
interface PortalPresenter : AppService, ZiplineService {
  fun launch(): ZiplineTreehouseUi
}

private class AppUi : TreehouseUi {
  @Composable
  override fun Show() {
    ${SCREEN}(${PRESENTER}())
  }
}

private class AppPresenter(json: kotlinx.serialization.json.Json) : PortalPresenter {
  override val appLifecycle = StandardAppLifecycle(
    protocolWidgetSystemFactory = KeliverMaterialProtocolWidgetSystemFactory,
    json = json,
    widgetVersion = 1U,
  )
  override fun launch(): ZiplineTreehouseUi = AppUi().asZiplineTreehouseUi(appLifecycle)
}

fun main() {
  val zipline = Zipline.get()
  // If your presenter needs HTTP, the host binds a HostHttpProvider under
  // "HostHttp": zipline.take<HostHttpProvider>("HostHttp").
  zipline.bind<PortalPresenter>("PortalPresenter", AppPresenter(zipline.json))
}
EOF
echo "    src/jsMain/kotlin/device/Main.kt"

cat <<EOF

scaffolded the device target for ${PKG} (${SCREEN} + ${PRESENTER}).

next:
  1. ./gradlew serveDevelopmentZipline          # serves your bundle on :8080
  2. install the device host (see docs/DEVICE_HOST.md), then:
       adb shell am start -n dev.keliver.portaldevice/dev.keliver.portaldevice.host.MainActivity
  3. after each Kotlin change: rebuild, then force-stop and restart the host.

If ${PRESENTER}() takes arguments (a repository, host services), edit
device/Main.kt to construct them — take host services from Zipline by name.
EOF
