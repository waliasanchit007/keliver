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
# device without hand-assembling four things. This emits exactly those four,
# and nothing else. It does NOT generate an Android application module: it
# wires the app up to the ALREADY-PUBLISHED generic device host
# (`dev.keliver.portaldevice`), which loads a guest bundle over HTTP.
#
#   1. the Zipline plugin, in settings.gradle pluginManagement
#   2. the plugin applied + `binaries.executable()` (needed for
#      serveDevelopmentZipline)
#   3. the four published deps a Treehouse guest needs
#   4. src/jsMain/kotlin/device/Main.kt — binds a PortalPresenter that renders
#      your screen with your presenter
#
# Deliberately NOT a project generator. If you need a bespoke host (custom
# branding, your own Activity, embedded bundles), copy `sample/host-android`
# instead; this is the zero-host path.
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
    -h|--help)   sed -n '2,28p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *)           echo "unknown option: $1" >&2; exit 2 ;;
  esac
done

APP="$(pwd)"
[ -f "$APP/keliver.portal.json" ] || { echo "no keliver.portal.json here — run from the app root" >&2; exit 1; }
[ -f "$APP/build.gradle" ] || { echo "no build.gradle here" >&2; exit 1; }
[ -e "$APP/src/jsMain/kotlin/device" ] && { echo "refusing to overwrite $APP/src/jsMain/kotlin/device" >&2; exit 1; }

PKG="$(grep -m1 '^package ' "$APP"/src/jsMain/kotlin/screens/*.kt 2>/dev/null | sed 's/^package //; s/\.screens$//' | tr -d '\r')"
[ -n "$PKG" ] || { echo "could not infer the app package from src/jsMain/kotlin/screens/*.kt" >&2; exit 1; }

# Infer the screen composable and its presenter unless told.
if [ -z "$SCREEN" ]; then
  SCREEN="$(grep -hoE '^fun [A-Z][A-Za-z0-9]*Screen\(' "$APP"/src/jsMain/kotlin/screens/*.kt 2>/dev/null | head -1 | sed 's/^fun //; s/($//; s/(//')"
fi
[ -n "$SCREEN" ] || { echo "could not find a 'fun <Name>Screen(' in src/jsMain/kotlin/screens/ — pass --screen" >&2; exit 1; }
if [ -z "$PRESENTER" ]; then
  PRESENTER="$(grep -hoE 'fun [A-Z][A-Za-z0-9]*Presenter\(' "$APP"/src/jsMain/kotlin/logic/*.kt 2>/dev/null | head -1 | sed 's/^fun //; s/(//')"
fi
[ -n "$PRESENTER" ] || { echo "could not find a 'fun <Name>Presenter(' in src/jsMain/kotlin/logic/ — pass --presenter" >&2; exit 1; }

echo "==> app package: $PKG   screen: $SCREEN   presenter: $PRESENTER"

# ---- 1. zipline plugin version in pluginManagement --------------------------
if ! grep -q "app.cash.zipline" "$APP/settings.gradle"; then
  python3 - "$APP/settings.gradle" "$ZIPLINE_VERSION" <<'PY'
import re,sys
p,v=sys.argv[1],sys.argv[2]; s=open(p).read()
s=re.sub(r"(\n(\s*)id 'org\.jetbrains\.compose' version '[^']+')",
         r"\1\n\2id 'app.cash.zipline' version '"+v+"'", s, count=1)
open(p,'w').write(s)
PY
  echo "    settings.gradle: zipline $ZIPLINE_VERSION pinned"
fi

# ---- 2 + 3. apply plugin, executable binary, guest deps ---------------------
python3 - "$APP/build.gradle" "$KELIVER_VERSION" "$ZIPLINE_VERSION" <<'PY'
import re,sys
p,kv,zv=sys.argv[1],sys.argv[2],sys.argv[3]; s=open(p).read()
if 'app.cash.zipline' not in s:
    # Append to the plugins block by its CLOSING brace, not by the last plugin
    # id: keliver-init's plugin list changes over time (the serialization
    # plugin was added after this script was first written), and anchoring on a
    # specific id silently no-ops when the list moves.
    m = re.search(r"\nplugins \{.*?\n\}", s, re.S)
    if not m:
        raise SystemExit("could not find the plugins { } block in build.gradle")
    block = m.group(0)
    s = s.replace(block, block[:-1] + "  id 'app.cash.zipline'\n}", 1)
if 'binaries.executable' not in s:
    s=re.sub(r"\n(\s*)js \{ browser\(\) \}",
             "\n\\1js {\n\\1  browser()\n\\1  // required for the Zipline bundle + serveDevelopmentZipline\n\\1  binaries.executable()\n\\1}", s, count=1)
if 'keliver-treehouse-guest' not in s:
    dep = ("        // device path: render this app's screens on the generic keliver host\n"
           f"        api 'dev.keliver:keliver-treehouse-guest:{kv}'\n"
           f"        api 'dev.keliver:keliver-treehouse-guest-compose:{kv}'\n"
           f"        implementation 'dev.keliver:keliver-material-protocol-guest-web:{kv}'\n"
           f"        implementation 'app.cash.zipline:zipline:{zv}'\n")
    s=re.sub(r"(\n\s*implementation 'org\.jetbrains\.compose\.runtime:runtime:[^']+'\n)",
             r"\1"+dep, s, count=1)
open(p,'w').write(s)
PY
echo "    build.gradle: plugin applied, executable binary, guest deps"

# ---- 4. the device entry point ---------------------------------------------
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

scaffolded the device target for ${PKG}.

next:
  1. ./gradlew serveDevelopmentZipline          # serves your bundle on :8080
  2. install the generic host once, then:
       adb shell am start -n dev.keliver.portaldevice/dev.keliver.portaldevice.host.MainActivity
  3. after each Kotlin change: rebuild, then force-stop and restart the host.

If ${PRESENTER}() takes arguments (a repository, host services), edit
device/Main.kt to construct them — take host services from Zipline by name.
EOF
