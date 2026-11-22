#!/usr/bin/env bash
#
# keliver-new-production-host-selftest — the production-host scaffolder refuses
# bad input without writing anything, and writes a complete module otherwise.
#
#   scripts/keliver-new-production-host-selftest.sh <disposable-parent> [--build]
#
# --build also compiles the scaffolded host (assembleDebug) against Maven
# Central, in a disposable Gradle home and user.home. That needs an Android SDK
# (ANDROID_HOME, or ~/Library/Android/sdk) and network access.
#
# Uses fixture apps and a fixture STORE (PORTAL_STORE, inside the run
# directory) holding a dummy key pair; the JVM's user.home is inside the run
# directory too, behind keliver_require_isolated_store, so the scaffolder's
# store lookup never reaches a real store. The dummy private key is never
# printed and exists only to prove it is refused.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
# KELIVER_PRODUCTION_HOST_SCAFFOLD runs a PACKAGED copy instead (bin/ of an
# unpacked tools bundle), which finds its templates at bin/../templates.
SCAFFOLD="${KELIVER_PRODUCTION_HOST_SCAFFOLD:-$ROOT/scripts/keliver-new-production-host.sh}"
. "$ROOT/scripts/keliver-test-isolation-guard.sh"
DISP="$(keliver_make_run_dir "${1:?usage: $0 <disposable-parent> [--build]}" prod-host)" || exit $?
BUILD=0; [ "${2:-}" = "--build" ] && BUILD=1
pass=0; fail=0
echo "scaffolder: $SCAFFOLD"
ok()  { printf '  PASS  %s\n' "$1"; pass=$((pass+1)); }
bad() { printf '  FAIL  %s\n' "$1"; fail=$((fail+1)); }
snapshot() { ( cd "$1" && find . -type f ! -path './.gradle/*' -print0 | sort -z | xargs -0 shasum -a 256 ) 2>/dev/null; }

APP="$DISP/demo"
mkdir -p "$APP/src/jsMain/kotlin/screens" "$APP/src/jsMain/kotlin/logic"
printf '{ "screensDir": "src/jsMain/kotlin/screens" }\n' > "$APP/keliver.portal.json"
printf "rootProject.name = 'demo'\n" > "$APP/settings.gradle"
printf 'package com.example.demo.screens\n\nfun HomeScreen() {}\n' > "$APP/src/jsMain/kotlin/screens/home.kt"
# The Gradle the template's AGP runs on, not this repo's (templates/app-wrapper).
bash "$ROOT/scripts/keliver-app-wrapper.sh" "$APP"
STORE="$DISP/store"; mkdir -p "$STORE/keys" "$DISP/home"
KEY="$STORE/keys/ed25519.pub"; printf '%s\n' "$(printf 'ab%.0s' $(seq 1 32))" > "$KEY"
PRIV="$STORE/keys/ed25519.priv"; ( umask 077; printf '%s' "$(printf 'cd%.0s' $(seq 1 32))" > "$PRIV" )
BADKEY="$DISP/bad.pub"; printf 'not-a-key\n' > "$BADKEY"
RENAMED="$DISP/looks-public.key"; cp "$PRIV" "$RENAMED"
LINKED="$DISP/looks.pub"; ln -s "$PRIV" "$LINKED"
OTHER="$DISP/other.pub"; printf '%s\n' "$(printf 'ef%.0s' $(seq 1 32))" > "$OTHER"
SERVER=http://10.0.2.2:8077
export JAVA_TOOL_OPTIONS="-Duser.home=$DISP/home" PORTAL_STORE="$STORE"
[ -z "${JAVA_HOME:-}" ] && [ -x /usr/libexec/java_home ] && export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
keliver_require_isolated_store "$DISP" "$APP" > /dev/null || { echo "isolation guard refused" >&2; exit 2; }

refuses() { # <label> <expected message fragment> <args...>
  local label="$1" want="$2"; shift 2
  local before after out rc
  before="$(snapshot "$APP")"
  out="$( cd "$APP" && "$SCAFFOLD" "$@" 2>&1 )"; rc=$?
  after="$(snapshot "$APP")"
  if [ "$rc" != 0 ] && printf '%s' "$out" | grep -q -- "$want" && [ "$before" = "$after" ]; then
    ok "refuses $label, and the app is byte-identical"
  else
    bad "$label: rc=$rc, changed=$([ "$before" = "$after" ] && echo no || echo YES), output: $(printf '%s' "$out" | head -2 | tr '\n' ' ')"
  fi
}

echo "=== refusals"
refuses "without --bundle-server"          "bundle-server is required" --public-key-file "$KEY"
refuses "a non-URL bundle server"          "must be an http"          --bundle-server "10.0.2.2:8077" --public-key-file "$KEY"
refuses "a bundle server with a quote"     "must be an http"          --bundle-server "http://x\"y" --public-key-file "$KEY"
refuses "a non-URL API base"               "api-base-url must be"     --bundle-server "$SERVER" --api-base-url "ftp://x" --public-key-file "$KEY"
refuses "a malformed channel (W4.4)"       "channel must be"          --bundle-server "$SERVER" --channel "Beta" --public-key-file "$KEY"
refuses "a malformed application id"       "application-id must"      --bundle-server "$SERVER" --application-id "demo" --public-key-file "$KEY"
refuses "a key file that is not a key"     "not a 64-hex-digit"       --bundle-server "$SERVER" --public-key-file "$BADKEY"
refuses "a PRIVATE key file"               "refusing a private key"   --bundle-server "$SERVER" --public-key-file "$PRIV"
refuses "a .pub symlink to the private key" "refusing a private key"  --bundle-server "$SERVER" --public-key-file "$LINKED"
refuses "the private key renamed .key"     "is not this app's public key" --bundle-server "$SERVER" --public-key-file "$RENAMED"
refuses "another app's public key"         "is not this app's public key" --bundle-server "$SERVER" --public-key-file "$OTHER"
refuses "a bundle server with a query"     "without a query"          --bundle-server "$SERVER/?x=1" --public-key-file "$KEY"
refuses "a non-ASCII bundle server"        "plain ASCII"              --bundle-server "http://bündel.example" --public-key-file "$KEY"
refuses "a value holding a placeholder"    "which the templates use as placeholders" --bundle-server "http://h:8077/@@NAME@@" --public-key-file "$KEY"
KELIVER_HOST_KOTLIN_VERSION="1'x" refuses "a hostile version override" "not a version" --bundle-server "$SERVER" --public-key-file "$KEY"
refuses "a missing key file"               "no such file"             --bundle-server "$SERVER" --public-key-file "$DISP/nope.pub"
# An unprefixed KOTLIN_VERSION (common on CI images) must not change the build.
export KOTLIN_VERSION=1.9.0
refuses "an unknown option"                "unknown option"           --bundle-server "$SERVER" --frobnicate
mv "$APP/settings.gradle" "$APP/s.gradle"
out="$( cd "$APP" && "$SCAFFOLD" --bundle-server "$SERVER" --public-key-file "$KEY" 2>&1 )"; rc=$?
[ "$rc" = 0 ] && [ -d "$APP/host-android" ] && ok "no settings.gradle: still scaffolds (named after the directory), no silent exit" \
  || bad "no settings.gradle: rc=$rc $(printf '%s' "$out" | tail -1)"
rm -rf "$APP/host-android"; mv "$APP/s.gradle" "$APP/settings.gradle"
mv "$APP/keliver.portal.json" "$APP/k.json"
refuses "outside an app root"              "no keliver.portal.json"   --bundle-server "$SERVER" --public-key-file "$KEY"
mv "$APP/k.json" "$APP/keliver.portal.json"
printf 'package elsewhere\n\nfun OtherScreen() {}\n' > "$APP/src/jsMain/kotlin/screens/other.kt"
refuses "screens in two packages"          "must all declare one"     --bundle-server "$SERVER" --public-key-file "$KEY"
rm "$APP/src/jsMain/kotlin/screens/other.kt"

echo "=== a successful scaffold (the key from the store, by default)"
out="$( cd "$APP" && "$SCAFFOLD" --bundle-server "$SERVER" --api-base-url "https://api.example.com/v1" 2>&1 )"; rc=$?
[ "$rc" = 0 ] && ok "scaffolded (exit 0)" || { bad "scaffold failed: $out"; }
printf '%s' "$out" | grep -q "matches this app's store" && ok "the key was checked against this app's store" || bad "no store match in: $out"
H="$APP/host-android"
missing=0
for f in settings.gradle build.gradle gradle.properties .gitignore src/main/AndroidManifest.xml \
         src/main/assets/keliver/portal_ed25519.pub \
         src/main/kotlin/com/example/demo/host/MainActivity.kt \
         src/main/kotlin/com/example/demo/host/HostApp.kt \
         src/main/kotlin/com/example/demo/host/KeliverHost.kt \
         src/main/kotlin/com/example/demo/host/KeliverScreen.kt \
         src/main/kotlin/com/example/demo/host/ProductionTrust.kt \
         src/main/kotlin/com/example/demo/host/AndroidSqlHost.kt \
         src/main/kotlin/com/example/demo/host/OkHttpHostHttp.kt \
         src/main/kotlin/com/example/demo/host/GuestContract.kt \
         src/main/kotlin/com/example/demo/host/BundleIndex.kt; do
  [ -f "$H/$f" ] || { bad "missing $f"; missing=1; }
done
[ "$missing" = 0 ] && ok "every expected file is there"
m="$(stat -c %a "$H" 2>/dev/null || stat -f %Lp "$H")"; [ "$m" = 755 ] && ok "host-android/ is 755" || bad "host-android/ is $m"
grep -q "version '2.2.0'" "$H/build.gradle" && ! grep -q "1.9.0" "$H/build.gradle" \
  && ok "an unprefixed KOTLIN_VERSION in the environment does not change the build" || bad "KOTLIN_VERSION leaked into the build"
unset KOTLIN_VERSION
grep -rl '@@' "$H" >/dev/null 2>&1 && bad "an unsubstituted @@placeholder@@ remains" || ok "no placeholder left"
cmp -s <(tr -d '[:space:]' < "$KEY") <(tr -d '[:space:]' < "$H/src/main/assets/keliver/portal_ed25519.pub") \
  && ok "the embedded key is the given public key" || bad "the embedded key differs"
grep -q "applicationId 'com.example.demo.host'" "$H/build.gradle" && grep -q "namespace 'com.example.demo.host'" "$H/build.gradle" \
  && ok "package and application id default to <app package>.host" || bad "package/application id"
grep -q "^keliver.bundleServer=$SERVER$" "$H/gradle.properties" && grep -q "^keliver.apiBaseUrl=https://api.example.com/v1$" "$H/gradle.properties" \
  && ok "the servers are recorded in gradle.properties" || bad "gradle.properties servers"
grep -v '^[[:space:]]*//' "$H/settings.gradle" "$H/build.gradle" | grep -q "mavenLocal\|includeBuild\|project(\|maven {" && bad "the host build reaches beyond Maven Central/Google" \
  || ok "the host build uses only Maven Central, Google and the plugin portal"
grep -q "NO_SIGNATURE_CHECKS\|DevelopmentUnsigned\|10\.0\.2\.2\|http-replay" "$H"/src/main/kotlin/com/example/demo/host/*.kt \
  && bad "the host sources carry a development path, an emulator address or the replay fixture" \
  || ok "no development path, emulator address or replay fixture in the sources"
# W3: the lookup reads bundles/index.json, holds the manifest to its sha256,
# and falls back to the relay's bundles/latest only on a 404.
M="$H/src/main/kotlin/com/example/demo/host/KeliverHost.kt"
# W2: the host logic lives in KeliverHost, owned by the Application; the activity
# only shows KeliverScreen, so a rotation recreates the screen, not the host.
A="$H/src/main/kotlin/com/example/demo/host/MainActivity.kt"
grep -q 'android:name=".HostApp"' "$H/src/main/AndroidManifest.xml" \
  && grep -q 'KeliverHost.create(this)' "$H/src/main/kotlin/com/example/demo/host/HostApp.kt" \
  && grep -q 'KeliverScreen((application as HostApp).keliver' "$A" && ! grep -q 'lifecycleScope\|TreehouseAppFactory\|pickFromIndex' "$A" \
  && grep -q 'appScope = scope' "$M" && grep -q 'compareAndSet(false, true)' "$M" \
  && ok "W2: the Application owns one KeliverHost (its own scope, started once); MainActivity only shows KeliverScreen" \
  || bad "W2: the host is not owned by the Application"
# M3: the code load starts at host start (right after the lookup), not at the first screen.
# (A PARALLEL start's state waits for its lookup; its code load starts there all the same.)
grep -A2 '^    if (!held) makeTheHosts()$' "$M" | grep -q '^    app.start()$' \
  && ok "M3: the code load starts when the host starts, not when the first screen asks" \
  || bad "M3: the host does not start the code load at host start"
grep -q 'addPathSegments("bundles/index.json")' "$M" && grep -q 'response.code == 404' "$M" \
  && grep -q 'ManifestPinningHttpClient(okhttp.asZiplineHttpClient(), latest.manifestUrl, latest.manifestSha256, floor, crash, hostEngine)' "$M" \
  && ok "the lookup reads bundles/index.json, pins the manifest's sha256, and falls back only on a 404" \
  || bad "the host does not look up through bundles/index.json"
# W4.2: the rollback floor is read per key, guards the network load and the cache
# start, and rises only from a verified successful load.
grep -q 'getLong(floorKey(cacheName), 0L)' "$M" && grep -q 'AcceptCachedBundle(floor, crash, servedBridges, hostEngine)' "$M" \
  && grep -q 'ManifestPinningHttpClient(okhttp.asZiplineHttpClient(), lastGood, null, floor, crash, hostEngine)' "$M" \
  && grep -q 'putLong(floorKey(it), sequence)' "$M" && grep -q 'prefs.raiseFloor(floorNames, sequence)' "$M" \
  && grep -q 'edit().apply { lower.forEach { putLong(floorKey(it), sequence) } }.commit()' "$M" \
  && grep -q 'floorAcross(cacheNames.map { floor(it) })' "$M" && grep -q 'val floor = { prefs.floor(floorNames) }' "$M" \
  && grep -q 'prefs.raiseFloor(floorNames, floor())' "$M" \
  && grep -q 'manifestSequence(manifest.metadata)' "$M" && grep -q 'sequence?.let(onSequence)' "$M" \
  && ok "W4.2: the rollback floor guards the network load, the cache start and its network fallback, and rises (committed) after a load" \
  || bad "W4.2: the rollback floor is not wired"
# B4: the bridge check runs where the floor's does: on the cache start (the network load's is
# inside ManifestPinningHttpClient, in BundleIndex.kt), and a refused manifest is unloadable.
grep -A16 '^private class AcceptCachedBundle' "$M" | grep -q 'manifestBridgeProblem(manifest.metadata\[BRIDGES_METADATA_KEY\], bridges)' \
  && grep -q '"bridge refused:" in reason' "$M" \
  && grep -q 'manifestBridgeProblem(manifestBridges(json), bridges())' "$(dirname "$M")/BundleIndex.kt" \
  && ok "B4: the bridge check guards the network load and the cache start, and a refused manifest is unloadable" \
  || bad "B4: the bridge check is not wired"
# B5: the app's bridges are declared in KeliverConfig (checked there); the host gates on its own
# list in every lookup, manifest and cache check (no process-wide one), binds them before its own
# services, and checks what each bound through the code load's listener.
grep -q '  bridges: List<KeliverBridgeBinding> = emptyList(),' "$M" && grep -q '  val bridges: List<KeliverBridgeBinding> = bridges.toList()' "$M" \
  && grep -q 'servedBridgesOf(bridges.map { it.declared })' "$M" \
  && grep -q 'private val servedBridges: Map<String, HostBridge> = servedBridgesOf(config.bridges.map { it.declared })' "$M" \
  && [ "$(grep -c 'HostFacts(' "$M")" = "$(grep -cE 'HostFacts\(.*, servedBridges[,)]' "$M")" ] \
  && [ "$(grep -c 'ManifestPinningHttpClient(' "$M")" = "$(grep -c 'ManifestPinningHttpClient(.*) { servedBridges }' "$M")" ] \
  && [ "$(grep -c 'AcceptCachedBundle(floor' "$M")" = "$(grep -c 'AcceptCachedBundle(floor, crash, servedBridges, hostEngine)' "$M")" ] \
  && ! grep -q 'ServedBridges' "$M" "$(dirname "$M")/BundleIndex.kt" \
  && grep -A10 'override suspend fun bindServices' "$M" | grep -q 'bindAppBridges(config.bridges, bridgeBinds, ::bridgeDebugLog, { load?.isCancelled == true })' \
  && grep -A10 'override suspend fun bindServices' "$M" | grep -q 'KeliverBridgeScope(zipline, main, ::bridgeDebugLog)' \
  && [ "$(grep -A12 'override suspend fun bindServices' "$M" | grep -n 'bindAppBridges' | cut -d: -f1)" -lt "$(grep -A12 'override suspend fun bindServices' "$M" | grep -n 'zipline.bind<HostSqlDriver>' | cut -d: -f1)" ] \
  && grep -A1 'override fun bindService(name: String, service: ZiplineService) {' "$M" | grep -q 'if (fence.live) bridgeBinds.bound(name)' \
  && grep -q '    val bridgeBinds = BridgeBindRecorder()' "$M" \
  && grep -q 'if (BuildConfig.DEBUG) Log.w(TAG, "bridge: $message")' "$M" \
  && [ -f "$(dirname "$M")/Bridges.kt" ] && grep -q 'internal fun bindAppBridges(' "$(dirname "$M")/Bridges.kt" \
  && [ -f "$H/src/test/kotlin/com/example/demo/host/KeliverBridgesTest.kt" ] \
  && ok "B5: the app's bridges are checked in KeliverConfig, gated on per host, bound before the host's services and checked as bound" \
  || bad "B5: the app's bridges are not wired"
# NF4: the app's declared first requests go out from run() (the Application's start), through the
# one HostHttp every app binds.
grep -q 'if (apiBase != null) hostHttp(okhttp, apiBase).startPrefetch(scope)' "$M" \
  && grep -A16 'override suspend fun bindServices' "$M" | grep -q 'val hostHttp = hostHttp(okhttp, apiBase)' \
  && grep -q 'prefetch: List<KeliverPrefetch> = emptyList(),' "$M" \
  && [ -f "$(dirname "$M")/HostPrefetch.kt" ] && [ -f "$H/src/test/kotlin/com/example/demo/host/HostPrefetchTest.kt" ] \
  && [ -f "$H/src/test/kotlin/com/example/demo/host/HostPrefetchRaceTest.kt" ] \
  && ok "NF4: KeliverConfig.prefetch is sent at start and taken by the guests' one HostHttp" \
  || bad "NF4: the first request's prefetch is not wired"
# NF4 review (#269): sent only at a start toward a screen (the first activity's creation), dropped
# by any non-GET and by discardPrefetched(), kept 5 s from the answer, waited for at most 1 s.
grep -q '    watchForUiStart()' "$M" && grep -q 'app.registerActivityLifecycleCallbacks(object' "$M" \
  && grep -q 'override fun onActivityPreCreated(activity: android.app.Activity, savedInstanceState: Bundle?) = uiStarting()' "$M" \
  && grep -q '  fun discardPrefetched() {' "$M" \
  && grep -q 'prefetched.discard("a ${request.method.uppercase()} went through HostHttp")' "${M%/*}/OkHttpHostHttp.kt" \
  && grep -q 'const val DEFAULT_PREFETCH_MAX_AGE_MILLIS = 5_000L' "${M%/*}/HostPrefetch.kt" \
  && grep -q 'withTimeoutOrNull(maxWaitMillis) { pending.await() }' "${M%/*}/HostPrefetch.kt" \
  && grep -q 'restore your session BEFORE keliver.start()' "${M%/*}/HostApp.kt" \
  && ok "NF4: the prefetch goes out only toward a screen, is dropped on a change or sign-out, and is waited for at most 1 s" \
  || bad "NF4: the prefetch's start, discard or wait bound is not wired"
# W4.4: the host takes one channel besides stable, stable by default.
grep -q '^keliver.channel=stable$' "$H/gradle.properties" && grep -q "buildConfigField 'String', 'KELIVER_CHANNEL'" "$H/build.gradle" \
  && grep -q 'channel = BuildConfig.KELIVER_CHANNEL' "$M" && grep -q 'pickFromIndex(body, capabilities, channel = config.channel' "$M" \
  && ok "W4.4: the channel defaults to stable and selects the index entries" || bad "W4.4: the channel is not wired"
# Updates: keliver.updates empty by default, so a debug build listens for
# publishes while a screen shows (live) and every other build type checks at the
# next launch; live outside debug is on-resume; a debug build also checks on adb's word.
grep -q '^keliver.updates=$' "$H/gradle.properties" \
  && grep -q "buildConfigField 'String', 'KELIVER_UPDATES', \"\\\\\"\${updatesOutsideDebug ?: 'next-launch'}" "$H/build.gradle" \
  && grep -q "buildConfigField 'String', 'KELIVER_UPDATES', \"\\\\\"\${updates ?: 'live'}" "$H/build.gradle" \
  && grep -q "def updatesOutsideDebug = updates == 'live' ? 'on-resume' : updates" "$H/build.gradle" \
  && grep -q '"android.permission.DUMP", null, ContextCompat.RECEIVER_EXPORTED' "$M" && grep -q 'if (!BuildConfig.DEBUG) return$' "$M" \
  && grep -q 'addPathSegments("bundles/events")' "$M" && grep -q 'host.screenShown()' "${M%/*}/KeliverScreen.kt" \
  && ok "updates: live in debug builds (listening to /bundles/events while a screen shows), next-launch otherwise; a debug-only check-now receiver that needs DUMP" \
  || bad "updates: the debug default, the live listener or the check-now receiver is not wired"
# Host kit (adoption item 3, K1): a typed status, a fallback hook, and no developer text in a
# release build's screen.
S="${M%/*}/KeliverScreen.kt"
grep -q 'data object Loading : KeliverHostState' "$M" && grep -q 'data class Unavailable(val outcome: String, val reason: String, val title: String, val message: String, val attempt: Int = 0) : KeliverHostState' "$M" \
  && grep -q 'title, message, unavailableAttempts.incrementAndGet())' "$M" \
  && ! grep -q 'KeliverHostState.Message' "$M" "$S" && ! grep -q 'mutableState.value = KeliverHostState.Message' "$M" \
  && grep -q 'onUnavailable: ((KeliverHostState.Unavailable) -> Unit)? = null' "$S" && grep -q 'if (host.state.value == s) latest?.invoke(s)' "$S" \
  && grep -q 'BuildConfig.DEBUG && (this.context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0' "$M" \
  && ! grep -q 'BuildConfig.DEBUG' "$S" && grep -q 'if (host.showsDeveloperText) MessageScreen(state.title, state.message)' "$S" \
  && grep -q 'var onUnavailable: ((KeliverHostState.Unavailable) -> Unit)? by mutableStateOf(null)' "$S" \
  && ok "host kit: state is Loading, Unavailable(outcome, reason, …) or Running; KeliverScreen/KeliverView call onUnavailable; a release build shows no host text" \
  || bad "host kit: the typed state, the onUnavailable hook or the release text policy is not wired"
# Host kit K1, screen-failed: code that throws while a screen shows it is reported, and that
# screen draws its unavailable slot and calls its onUnavailable (Treehouse's crashed widget).
grep -q 'dynamicContentWidgetFactory = failureWidgets' "${M%/*}/KeliverScreen.kt" \
  && grep -q 'if (failed == null) failed = host.screenFailed(uncaughtException)' "${M%/*}/KeliverScreen.kt" \
  && grep -q 'report("screen-failed", running?.sequence' "$M" && grep -q 'outcome == "screen-failed" -> "exception"' "${M%/*}/BundleIndex.kt" \
  && ok "host kit: a screen whose code throws reports screen-failed, calls onUnavailable and draws its unavailable slot" \
  || bad "host kit: screen-failed is not wired"
# Host kit K1, routes and close: a route keeps only the app's allowed query keys, a new route
# relaunches the guest, a guest from before K1 still opens (launch()), and closeScreen reaches
# the app's onClose (by default the activity finishes; a late close closes nothing); the
# activity takes a re-delivered intent's route.
S="${M%/*}/KeliverScreen.kt"
grep -q 'fun launchRoute(route: String, screen: KeliverScreenHost): ZiplineTreehouseUi' "${M%/*}/GuestContract.kt" \
  && grep -q '^import dev.keliver.hostkit.KeliverScreenHost$' "${M%/*}/GuestContract.kt" \
  && grep -q '^package dev.keliver.hostkit$' "${M%/*}/KeliverScreenHost.kt" \
  && grep -q 'catch (e: app.cash.zipline.ZiplineApiMismatchException)' "$M" && grep -q 'noRouteCode = code' "$M" \
  && grep -q 'val contentSource = remember(app, route) {' "$S" && grep -q 'route: KeliverRoute? = null,' "$S" \
  && grep -q 'onClose ?: { activity?.finish() }' "$S" && grep -q 'if (active.get()) latestOnClose()' "$S" \
  && grep -q 'override fun onNewIntent(intent: Intent)' "$H/src/main/kotlin/com/example/demo/host/MainActivity.kt" \
  && [ -f "$H/src/test/kotlin/com/example/demo/host/KeliverRouteTest.kt" ] \
  && ok "host kit: routes (allowed query keys only, a new route relaunches, older guests still open) and close" \
  || bad "host kit: routes and close are not wired"
# Zipline matches launchRoute by its signature, parameter types' packages included: the guest
# that keliver-new-device-target.sh writes must declare KeliverScreenHost exactly as this host does.
DT="$(dirname "$SCAFFOLD")/keliver-new-device-target.sh"
if [ -f "$DT" ]; then
  sed -n "/<<'HOSTKIT'$/,/^HOSTKIT$/p" "$DT" | sed '1d;$d' | cmp -s - "${M%/*}/KeliverScreenHost.kt" \
    && grep -qF 'fun launchRoute(route: String, screen: KeliverScreenHost): ZiplineTreehouseUi' "$DT" \
    && ok "host kit: the guest scaffolder declares KeliverScreenHost and launchRoute exactly as this host does" \
    || bad "host kit: the guest's KeliverScreenHost or launchRoute differs from this host's: routes would never reach it"
else
  bad "host kit: no keliver-new-device-target.sh beside $SCAFFOLD to compare the guest contract with"
fi
# A crash leaves no blank screen: a screen opened after the code threw shows that and calls onUnavailable.
grep -q 'val failed by host.crashed.collectAsState()' "$S" && grep -q '.also { crashed.value = it }' "$M" \
  && grep -q 'crashed.value = null // new code' "$M" \
  && ok "host kit: after the code threw, a screen opened later says so (not a blank screen) until code loads again" \
  || bad "host kit: a screen opened after a crash is not handled"
# Host kit K1, debug-only surfaces: the bundle server set on the device, the check-now receiver,
# the host's developer text and live updates each need this module's DEBUG AND a debuggable app;
# the receiver also needs DUMP (adb's shell has it; apps do not). The manifest declares no receiver.
cat > "$DISP/debug-gates.py" <<'PY'
import re, sys
src = open(sys.argv[1]).read()
need = {
  'debugBundleServer': r'private fun debugBundleServer\(context: Context\): String\? \{(.*?)\n\}',
  'registerDebugCheckForUpdate': r'private fun registerDebugCheckForUpdate\(\) \{(.*?)\n  \}',
  'showsDeveloperText': r'val showsDeveloperText: Boolean(.*?)\n\n',
  'liveUpdatesAllowed': r'private fun liveUpdatesAllowed\(\): Boolean =(.*?)\n\n',
}
missing = []
for name, rx in need.items():
    m = re.search(rx, src, re.S)
    body = m.group(1) if m else ''
    if 'BuildConfig.DEBUG' not in body or 'FLAG_DEBUGGABLE' not in body: missing.append(name)
print(' '.join(missing))
PY
dbg_gates="$(python3 "$DISP/debug-gates.py" "$M")"
[ -z "$dbg_gates" ] && grep -q '"android.permission.DUMP", null, ContextCompat.RECEIVER_EXPORTED' "$M" \
  && ! grep -q '<receiver' "$H/src/main/AndroidManifest.xml" && grep -q "def updatesOutsideDebug = updates == 'live' ? 'on-resume' : updates" "$H/build.gradle" \
  && ok "debug surfaces: each needs DEBUG and a debuggable app; the receiver also needs DUMP; release never runs live" \
  || bad "debug surfaces: not gated on both: ${dbg_gates:-the receiver permission, the manifest or the release updates mode}"
# Host kit K1: RequestPolicy (one origin, exact (method, path) pairs, a switch for state-changing
# calls), checked in OkHttpHostHttp before anything is sent; its JVM tests come with the host.
grep -q 'class RequestPolicy(' "${M%/*}/RequestPolicy.kt" && grep -q '(baseRefusal ?: policy.refusal(method, request.path))' "${M%/*}/OkHttpHostHttp.kt" \
  && grep -q '"x-http-method-override", "x-http-method", "x-method-override",' "${M%/*}/OkHttpHostHttp.kt" \
  && grep -q 'OkHttpHostHttp(okhttp, apiBase, config.requestPolicy, prefetched = prefetched)' "$M" && grep -q 'val requestPolicy: RequestPolicy? = null' "$M" \
  && [ -f "$H/src/test/kotlin/com/example/demo/host/RequestPolicyTest.kt" ] \
  && grep -q "testImplementation 'com.squareup.okhttp3:mockwebserver3:" "$H/build.gradle" \
  && grep -q 'if (appOwnsCredentials && method in ECHOING_METHODS) {' "${M%/*}/OkHttpHostHttp.kt" \
  && grep -q 'val ECHOING_METHODS = setOf("TRACE", "TRACK", "CONNECT")' "${M%/*}/OkHttpHostHttp.kt" \
  && ok "host kit: HostHttp checks the app's RequestPolicy before sending (TRACE, TRACK and CONNECT never through the app's client); its tests are scaffolded" \
  || bad "host kit: RequestPolicy is not wired into HostHttp, or its tests are missing"
L="${M%/*}/ExternalLinkPolicy.kt"
grep -q 'scheme == "https" && httpsHost(trimmed) != null -> Decision.InAppBrowser(trimmed)' "$L" \
  && grep -q 'intent.component = null' "$L" && grep -q 'intent.selector = null' "$L" \
  && grep -q 'intent.addCategory(Intent.CATEGORY_BROWSABLE)' "$L" && grep -q 'intent.flags = 0' "$L" \
  && grep -q 'targetPackage == thisPackage || resolvesToThisApp -> ' "$L" && grep -q '.queryIntentActivities(intent, ' "$L" \
  && [ -f "$H/src/test/kotlin/com/example/demo/host/ExternalLinkPolicyTest.kt" ] \
  && grep -q "implementation 'androidx.browser:browser:" "$H/build.gradle" \
  && ok "host kit: ExternalLinkPolicy opens https in a Custom Tab, intent: only as a browser would, nothing else" \
  || bad "host kit: ExternalLinkPolicy is missing or not as a browser handles intent: links"
# M0: timing marks in the debug and benchmark build types only; a no-op elsewhere.
P="${M%/*}"; P="${P#$H/src/main/kotlin/}"
grep -q 'interface KeliverTrace' "$H/src/timing/kotlin/$P/KeliverTiming.kt" 2>/dev/null \
  && grep -q 'inline fun mark(name: String) = Unit' "$H/src/notiming/kotlin/$P/KeliverTiming.kt" 2>/dev/null \
  && ! grep -q 'KeliverTrace' "$H/src/notiming/kotlin/$P/KeliverTiming.kt" \
  && grep -q "def timingBuildTypes = \['debug', 'benchmark'\]" "$H/build.gradle" && grep -q 'profileable true' "$H/build.gradle" \
  && grep -q 'KeliverTiming.firstFrame(host.launch(app, treehouseApp, route)' "$H/src/main/kotlin/$P/KeliverScreen.kt" && [ -f "$H/proguard-rules.pro" ] \
  && ok "M0: timing marks in debug and benchmark (src/timing), a no-op elsewhere (src/notiming); a benchmark build type" \
  || bad "M0: the timing marks or the benchmark build type are not wired"
# M4: the guest frame profile, beside the marks: the listener's call hooks, the window, a no-op elsewhere.
grep -q 'override fun callStart(call: ZiplineCall): Any? = KeliverTiming.callStart(call)' "$M" \
  && grep -q 'override fun callEnd(call: ZiplineCall, result: CallResult, startValue: Any?) = KeliverTiming.callEnd(call, startValue)' "$M" \
  && grep -q '^internal class FrameWindow' "$H/src/timing/kotlin/$P/KeliverFrames.kt" 2>/dev/null \
  && grep -q 'call.service !is AppLifecycle || !call.function.signature.startsWith("fun sendFrame(")' "$H/src/timing/kotlin/$P/KeliverTiming.kt" \
  && grep -q 'isAsynchronous = true' "$H/src/timing/kotlin/$P/KeliverTiming.kt" \
  && grep -q 'inline fun callStart(call: Call): Any? = null' "$H/src/notiming/kotlin/$P/KeliverTiming.kt" \
  && ! grep -q 'FrameWindow' "$H/src/notiming/kotlin/$P/KeliverTiming.kt" && [ ! -e "$H/src/notiming/kotlin/$P/KeliverFrames.kt" ] \
  && [ -f "$H/src/testDebug/kotlin/$P/FrameWindowTest.kt" ] \
  && grep -q 'name == "memory" || name == "frames" -> Log.w' "$H/src/timing/kotlin/$P/KeliverTiming.kt" \
  && ok "M4: the guest frame profile in debug and benchmark (sendFrame and change-list spans, the main thread's apply), a no-op elsewhere; its window's tests; guest marks cannot use its name" \
  || bad "M4: the guest frame profile is not wired"
# M5: the memory profile at its points, and the memory cycle; neither outside debug and benchmark.
T="$H/src/timing/kotlin/$P"; N="$H/src/notiming/kotlin/$P/KeliverTiming.kt"
grep -q 'Debug.getMemoryInfo(info)' "$T/KeliverMemory.kt" 2>/dev/null \
  && grep -q '@OptIn(EngineApi::class)' "$T/KeliverMemory.kt" \
  && grep -qx -- '-keep class app.cash.zipline.MemoryUsage { \*; }' "$H/proguard-rules.pro" \
  && grep -q 'private const val MEMORY_CYCLE = "dev.keliver.debug.MEMORY_CYCLE"' "$T/KeliverTiming.kt" \
  && grep -q '"android.permission.DUMP", null, ContextCompat.RECEIVER_EXPORTED' "$T/KeliverTiming.kt" \
  && grep -q 'override fun ziplineCreated(zipline: Zipline) = KeliverTiming.memory("runtime", zipline)' "$M" \
  && grep -q 'KeliverTiming.memory("code", zipline)' "$M" \
  && grep -q 'KeliverTiming.watch(this, app)' "$M" \
  && grep -q '^ *KeliverTiming.mark(name)$' "$T/KeliverTiming.kt" && ! grep -q '^ *mark(name)$' "$T/KeliverTiming.kt" \
  && grep -q 'KeliverTiming.memory("open")' "$H/src/main/kotlin/$P/KeliverScreen.kt" \
  && grep -q 'KeliverTiming.memoryAfterFrame("ui")' "$H/src/main/kotlin/$P/KeliverScreen.kt" \
  && grep -q 'KeliverTiming.screenOpened(screenActivity)' "$H/src/main/kotlin/$P/KeliverScreen.kt" \
  && grep -q 'inline fun screenOpened(activity: android.app.Activity?) = Unit' "$N" \
  && grep -q 'inline fun memory(point: String, zipline: Zipline? = null) = Unit' "$N" \
  && grep -q 'inline fun watch(host: KeliverHost, app: TreehouseApp<\*>) = Unit' "$N" \
  && [ ! -e "$H/src/notiming/kotlin/$P/KeliverMemory.kt" ] \
  && ! grep -rq 'MEMORY_CYCLE' "$H/src/notiming" "$H/src/main" \
  && ok "M5: the memory profile and its cycle in debug and benchmark, a no-op elsewhere" \
  || bad "M5: the memory profile is not wired, or reaches a build without the marks"
# ED3: the debug link, in the debug source set only: its activity, its check and its intent filter.
D="$H/src/debug"
grep -q 'android:scheme="keliver-com.example.demo"' "$D/AndroidManifest.xml" 2>/dev/null \
  && grep -q 'android:host="keliver-debug"' "$D/AndroidManifest.xml" && grep -q 'android:path="/bundles"' "$D/AndroidManifest.xml" \
  && grep -q 'android:name="com.example.demo.host.KeliverDebugLinkActivity"' "$D/AndroidManifest.xml" \
  && grep -q '^class KeliverDebugLinkActivity : Activity()' "$D/kotlin/$P/KeliverDebugLinkActivity.kt" \
  && grep -q 'internal const val DEBUG_LINK_SCHEME = "keliver-com.example.demo"' "$D/kotlin/$P/KeliverDebugLinkActivity.kt" \
  && ! grep -q 'DEBUG_LINK_PORT' "$D/kotlin/$P/KeliverDebugLinkActivity.kt" \
  && grep -q 'if (!BuildConfig.DEBUG || (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0)' "$D/kotlin/$P/KeliverDebugLinkActivity.kt" \
  && grep -q '^internal object DebugBundleLink' "$D/kotlin/$P/DebugBundleLink.kt" \
  && [ -f "$H/src/testDebug/kotlin/$P/DebugBundleLinkTest.kt" ] \
  && ! grep -rqE 'keliver-debug|KeliverDebugLinkActivity|DebugBundleLink' "$H/src/main" \
  && [ ! -e "$H/src/release" ] \
  && ok "ED3: the debug link (keliver-com.example.demo://keliver-debug/bundles, any port from 1024) is in src/debug only: none of it in src/main" \
  || bad "ED3: the debug link is not wired, or reaches src/main"
# W4.5: constraints are checked against this install's id, the build's versionCode and the floor.
grep -q 'HostFacts(prefs.installId(), config.hostVersion ?: appVersionCode(), floor(), servedBridges, setAside = crash.setAside(), engine = hostEngine)' "$M" && grep -q 'facts = facts' "$M" \
  && grep -q 'longVersionCode' "$M" \
  && grep -q 'setOf("rollout", "minHostVersion", "maxHostVersion", "bridges", "engine")' "$H/src/main/kotlin/com/example/demo/host/BundleIndex.kt" \
  && ok "W4.5: the lookup checks rollout and host-version constraints (install id, versionCode, floor)" || bad "W4.5: constraints are not wired"
# NF2: the host names its engine from the QuickJS its Zipline links; every lookup takes only that
# engine's entries, and every load (network, cache start, kept copy) refuses another engine's manifest.
B="$H/src/main/kotlin/com/example/demo/host/BundleIndex.kt"
grep -q 'engineOfQuickJsVersion(app.cash.zipline.QuickJs.version)' "$M" \
  && [ "$(grep -c 'HostFacts(' "$M")" = "$(grep -c 'engine = hostEngine)' "$M")" ] \
  && [ "$(grep -c 'ManifestPinningHttpClient(okhttp' "$M")" = "$(grep -c 'crash, hostEngine) { servedBridges }' "$M")" ] \
  && ! grep -q 'AcceptCachedBundle(floor, crash, servedBridges)' "$M" \
  && [ "$(grep -c 'AcceptCachedBundle(floor' "$M")" = "$(grep -c 'AcceptCachedBundle(floor, crash, servedBridges, hostEngine)' "$M")" ] \
  && grep -A16 '^private class AcceptCachedBundle' "$M" | grep -q 'engineProblem(manifest.metadata\[ENGINE_METADATA_KEY\], engine)' \
  && [ "$(grep -c 'engineProblem(manifestEngine(' "$B")" = 3 ] \
  && grep -q '"engine refused:" in reason' "$M" \
  && ok "NF2: the host's engine gates every lookup, and the network load, the kept copy and the cache start refuse another engine's bundle" \
  || bad "NF2: the engine gate is not wired"
# NF2 (M1): a cache another engine left (an app update that switched engine) is nothing cached, so the
# start policy cannot pick a cache-first or parallel start from it; nor does crash rollback go back
# to a kept bundle for another engine. lastGood is recorded with the engine that loaded it.
grep -A4 'val lastGood = lastGoodForEngine(' "$M" | grep -q 'prefs.stringOrNull(lastGoodEngineKey(cacheName))' \
  && [ "$(grep -c 'getString(lastGoodKey(cacheName)' "$M")" = 1 ] \
  && grep -q 'putString(lastGoodKey(cacheName), url).putString(lastGoodEngineKey(cacheName), hostEngine)' "$M" \
  && [ "$(grep -c 'crash.rollbackTo(' "$M")" = "$(grep -c 'keptForEngine(crash.rollbackTo(floor()), hostEngine)' "$M")" ] \
  && ok "NF2: a last good or kept bundle for another engine is nothing cached (network-first), and lastGood records its engine" \
  || bad "NF2: the start still counts another engine's cache"
# WS6: crash rollback. The ledger (CrashRollback.kt) and its JVM tests are scaffolded; the trial
# starts once the bundle's code begins to evaluate (the first moduleLoadStart), a load failure
# counts only when it came from that code (CodeLoadProgress: not a download), the "not yet
# healthy" marker is written before the code runs, only while a screen shows (screens), and
# checked at the next start against the exit record of the pid that set it, set-aside entries
# are skipped on the network and the cache, W4's floor keeps its one exception in one place
# (cachedBundleProblem), the threshold and window are KeliverConfig's, it is reported (the
# app's view once, at the set-aside), and a debug build logs it loudly.
C="${M%/*}/CrashRollback.kt"
[ -f "$C" ] && [ -f "$H/src/test/kotlin/com/example/demo/host/CrashRollbackTest.kt" ] \
  && grep -q 'override fun manifestReady(manifest: ZiplineManifest)' "$M" && grep -q 'onReady = { sequence -> crash.loadStarting(sequence) }' "$M" \
  && grep -A3 'if (progress.moduleLoadStart()) {' "$M" | grep -q 'fence.codeStarts(mark = { onReady(s) }' \
  && grep -A4 'fun codeStarts(mark: () -> Unit, unmark: () -> Unit) {' "${M%/*}/StartPolicy.kt" | grep -q 'Mode.LIVE -> mark()' \
  && grep -q 'override fun downloadFailed(url: String, exception: Exception, startValue: Any?) = progress.downloadFailed()' "$M" \
  && grep -q 'val theBundles = progress.failureIsTheBundles(exception) { it is java.io.IOException }' "$M" \
  && grep -q 'crash.loadFailed(sequence, theBundles)' "$M" \
  && grep -q 'fence.run { onUncaught(count(CrashKind.EXCEPTION)) }' "$M" && grep -q 'val startStep = crash.atStart(::lastExitCrashed)' "$M" \
  && grep -q 'crash?.screens(true)' "$M" && grep -q 'crash?.screens(screensShown > 0)' "$M" \
  && grep -q 'android.app.ApplicationExitInfo.REASON_CRASH_NATIVE,' "$M" \
  && grep -q '.firstOrNull { it.pid == pid && it.processName == me && it.timestamp >= markedAt }' "$M" \
  && grep -q 'report = setAsideNow != null)' "$M" \
  && grep -q 'CrashGuard(PrefsCrashStore(prefs), cacheName, config.crashThreshold, android.os.Process.myPid(), System::currentTimeMillis)' "$M" \
  && grep -q 'if (v == null) remove(k) else putString(k, v) } }.commit()' "$M" \
  && grep -q '  val crashThreshold: Int = DEFAULT_CRASH_THRESHOLD,' "$M" && grep -q '  val crashWindowMillis: Long = DEFAULT_CRASH_WINDOW_MILLIS,' "$M" \
  && grep -q 'cachedBundleProblem(manifestSequence(manifest.metadata), floor(), crash)' "$M" \
  && grep -q 'report(CRASH_ROLLBACK_OUTCOME, step.sequence, null, detail)' "$M" \
  && grep -q 'Request.Builder().url(url).header("Connection", "close")' "$M" \
  && grep -q 'Log.e(TAG, "!!!!!!!! CRASH ROLLBACK !!!!!!!!")' "$M" \
  && [ "$(grep -c '    syncCrashWindow()$' "$M")" -ge 2 ] && grep -q 'delay(config.crashWindowMillis)' "$M" \
  && grep -q 'if (sequence <= facts.setAside) return null' "${M%/*}/BundleIndex.kt" \
  && grep -q 'crash?.setAsideProblem(sequence)?.let { throw IOException(it) }' "${M%/*}/BundleIndex.kt" \
  && ! grep -q 'rollbackProblem(manifestSequence(manifest.metadata), floor())' "$M" \
  && ok "WS6: crash rollback counts verified failures (load, exception, process end), sets aside at the threshold, keeps the floor's one exception, reports it" \
  || bad "WS6: crash rollback is not wired"
# Start policy (docs/DEVICE_HOST.md, "Start policy"): the decision (StartPolicy.kt, the iOS host's
# file too) and its JVM tests are scaffolded; the app sets keliver.startPolicy (parallel by
# default); crash rollback decides as network-first; each start logs its policy and why; every
# index read keeps the override for the next start, unless keliver.startPolicyOverride=none; a
# PARALLEL start's cached load is in an app of its own, closed when dropped, its crash records fenced;
# after a start from the cache an update loads in a new app; screens key their content on the app.
SP="${M%/*}/StartPolicy.kt"
[ -f "$SP" ] && [ -f "$H/src/test/kotlin/com/example/demo/host/StartPolicyTest.kt" ] \
  && grep -q '^val DEFAULT_START_POLICY: KeliverStartPolicy = KeliverStartPolicy.PARALLEL$' "$SP" \
  && grep -q 'mode == Mode.DROPPED && exception is IllegalStateException && exception.message == ZIPLINE_CLOSED' "$SP" \
  && grep -A2 'override fun uncaughtException(exception: Throwable) {' "$M" | grep -q '    if (fence.isLateClose(exception)) {' \
  && grep -q '^keliver.startPolicy=$' "$H/gradle.properties" \
  && grep -q '^keliver.startPolicyOverride=$' "$H/gradle.properties" \
  && grep -q "buildConfigField 'String', 'KELIVER_START_POLICY'" "$H/build.gradle" \
  && grep -q "buildConfigField 'String', 'KELIVER_START_POLICY_OVERRIDE'" "$H/build.gradle" \
  && grep -q 'startPolicy: KeliverStartPolicy? = KeliverStartPolicy.parse(BuildConfig.KELIVER_START_POLICY),' "$M" \
  && grep -q 'config.startPolicyOverrideAllowed)' "$M" \
  && grep -q 'val rollbackWhy = crashRollbackReason(crash, startStep, floor())' "$M" \
  && grep -q 'Log.i(TAG, choice.logLine(policy))' "$M" \
  && [ "$(grep -c 'recordStartOverride(.*, it)' "$M")" -ge 3 ] \
  && grep -q '          held.drop()' "$M" \
  && grep -q 'val http = ManifestPinningHttpClient(okhttp.asZiplineHttpClient(), lastGood, recordedSha256, floor, crash, hostEngine) { servedBridges }' "$M" \
  && grep -q 'afterParallelLookup(latest?.manifestUrl, latest?.manifestSha256, lastGood, recordedSha256)' "$M" \
  && grep -A1 'held.retryFromNetwork(retryAfterShownCache(step, latest?.manifestUrl, latest?.manifestSha256))' "$M" | grep -q '          held.show()' \
  && grep -q '      } else if (retryFromNetwork != null && step !is CrashStep.SetAside) {' "$M" \
  && grep -q 'val fence = LoadFence(held, { block -> scope.launch { block() } }, besideALiveApp = held && besideALiveApp)' "$M" \
  && grep -q '    fence.run { onUncaught(count(CrashKind.EXCEPTION)) }' "$M" \
  && grep -q 'inNewApp(latest.manifestUrl, latest.manifestSha256)' "$M" \
  && grep -q 'for (app in listOfNotNull(back, front)) {' "${M%/*}/KeliverScreen.kt" \
  && grep -q 'val release = host.drawing(app)' "${M%/*}/KeliverScreen.kt" \
  && grep -q '    old?.let { retire(it.app) }' "$M" && ! grep -q 'OLD_APP_CLOSE_DELAY' "$M" \
  && grep -q 'zipline.bind<HostHttpProvider>("HostHttp", if (held) GatedHostHttp(hostHttp, fence.calls) else hostHttp)' "$M" \
  && grep -q 'val main = if (held) GatedDispatcher(Dispatchers.Main, fence.calls) else Dispatchers.Main' "$M" \
  && grep -q 'fence.codeStarts(mark = { onReady(s) }, unmark = { crash.loadFailed(s, theBundles = false) })' "$M" \
  && grep -q 'besideALiveApp = held && besideALiveApp)' "$M" && [ "$(grep -c '          besideALiveApp = true,$' "$M")" = 1 ] \
  && grep -q 'if (inNewApp != null && updateInNewApp != null)' "$M" \
  && [ -f "${M%/*}/CallGates.kt" ] && [ -f "$H/src/test/kotlin/com/example/demo/host/CallGatesTest.kt" ] \
  && grep -q 'fun indexStartPolicy(indexJson: String, channel: String = DEFAULT_CHANNEL): String?' "${M%/*}/BundleIndex.kt" \
  && ok "start policy: keliver.startPolicy (parallel by default), the index's override from the next start (unless refused), logged at each start; parallel's cached app fenced and closed when dropped; updates after a cache start in a new app; a replaced app's late close is not reported as uncaught" \
  || bad "start policy: not wired"
refuses "a second run over an existing host-android" "already exists" --bundle-server "$SERVER" --public-key-file "$KEY"
ls -a "$APP" | grep -q '^\.host-android\.' && bad "a staging directory was left behind" || ok "no staging directory left behind"
# W4.4: --channel beta, over a removed host-android (the build below then builds a beta host).
rm -rf "$H"
out="$( cd "$APP" && "$SCAFFOLD" --bundle-server "$SERVER" --api-base-url "https://api.example.com/v1" --channel beta 2>&1 )"; rc=$?
[ "$rc" = 0 ] && grep -q '^keliver.channel=beta$' "$H/gradle.properties" && printf '%s' "$out" | grep -q 'channel          beta (and stable)' \
  && ok "W4.4: --channel beta is recorded in gradle.properties" || bad "W4.4: --channel beta: rc=$rc"

echo "=== --embed: the host as a library module in an existing app (W2)"
EX="$DISP/existing"; mkdir -p "$EX/app"
printf "pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }\nrootProject.name = 'existing'\ninclude ':app'\n" > "$EX/settings.gradle"
printf "plugins { id 'com.android.application' version '8.12.0' apply false }\n" > "$EX/build.gradle"
embed_refuses() { # <label> <expected> <args...>: refused, and neither app changed
  local label="$1" want="$2"; shift 2
  local b1 b2 out rc
  b1="$(snapshot "$APP")$(snapshot "$EX")"
  out="$( cd "$APP" && "$SCAFFOLD" "$@" 2>&1 )"; rc=$?
  b2="$(snapshot "$APP")$(snapshot "$EX")"
  if [ "$rc" != 0 ] && printf '%s' "$out" | grep -q -- "$want" && [ "$b1" = "$b2" ]; then
    ok "--embed refuses $label; both apps byte-identical"
  else
    bad "--embed $label: rc=$rc, changed=$([ "$b1" = "$b2" ] && echo no || echo YES): $(printf '%s' "$out" | head -2 | tr '\n' ' ')"
  fi
}
embed_refuses "without --into" "needs --into" --embed --bundle-server "$SERVER" --public-key-file "$KEY"
embed_refuses "an --into without settings.gradle" "no settings.gradle" --embed --into "$EX/app" --bundle-server "$SERVER" --public-key-file "$KEY"
embed_refuses "--application-id" "is for the standalone host" --embed --into "$EX" --application-id com.x.y --bundle-server "$SERVER" --public-key-file "$KEY"
embed_refuses "a bad module name" "plain module name" --embed --into "$EX" --module "../x" --bundle-server "$SERVER" --public-key-file "$KEY"
embed_refuses "--into without --embed" "only for --embed" --into "$EX" --bundle-server "$SERVER" --public-key-file "$KEY"
embed_refuses "a malformed key" "not a 64-hex-digit" --embed --into "$EX" --bundle-server "$SERVER" --public-key-file "$BADKEY"
out="$( cd "$APP" && "$SCAFFOLD" --embed --into "$EX" --bundle-server "$SERVER" --channel beta 2>&1 )"; rc=$?
L="$EX/keliver-host"; LK="$L/src/main/kotlin/com/example/demo/host"
[ "$rc" = 0 ] && [ -d "$L" ] && ok "--embed wrote $EX/keliver-host (exit 0)" || bad "--embed failed: rc=$rc $(printf '%s' "$out" | tail -3 | tr '\n' ' ')"
printf '%s' "$out" | grep -q "include ':keliver-host'" && printf '%s' "$out" | grep -q "implementation project(':keliver-host')" \
  && printf '%s' "$out" | grep -q "KeliverHost.create(this)" && ok "--embed prints the include, the dependency and the Application line" || bad "--embed's next steps"
printf '%s' "$out" | grep -q "warning: no app.cash.zipline plugin" && printf '%s' "$out" | grep -q "warning: no Kotlin 2.2.0" \
  && ok "--embed warns that the existing build declares no Zipline plugin and no Kotlin 2.2.0" || bad "--embed did not warn about the missing plugins"
[ "$(cat "$EX/settings.gradle")" = "$(printf "pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }\nrootProject.name = 'existing'\ninclude ':app'")" ] \
  && ok "--embed edited none of the existing app's files" || bad "--embed changed the existing settings.gradle"
grep -q "id 'com.android.library'" "$L/build.gradle" && ! grep -q "applicationId\|version '" "$L/build.gradle" \
  && ! grep -q "<application" "$L/src/main/AndroidManifest.xml" && grep -q "android.permission.INTERNET" "$L/src/main/AndroidManifest.xml" \
  && ok "a library: com.android.library, no application id, no plugin versions, a manifest without <application>" || bad "the embedded module is not a plain library"
grep -q '^keliver.bundleServer=http://10.0.2.2:8077$' "$L/keliver.properties" && grep -q '^keliver.channel=beta$' "$L/keliver.properties" \
  && grep -q '^keliver.updates=$' "$L/keliver.properties" && grep -q "debug { buildConfigField 'String', 'KELIVER_UPDATES'" "$L/build.gradle" \
  && ok "the settings are in keliver.properties (a subproject's gradle.properties is not read); updates by build type" || bad "keliver.properties"
cmp -s <(tr -d '[:space:]' < "$KEY") <(tr -d '[:space:]' < "$L/src/main/assets/keliver/portal_ed25519.pub") \
  && ok "the key is the module's src/main/assets/keliver/portal_ed25519.pub" || bad "the embedded module's key"
grep -q "KELIVER_PUBLIC_KEY_HEX'.*portalKeyLines\[0\]" "$L/build.gradle" && grep -q "KELIVER_PUBLIC_KEY_HEX'.*portalKeyLines\[0\]" "$H/build.gradle" \
  && grep -q "KELIVER_NEXT_PUBLIC_KEY_HEX'.*portalKeyLines\[1\]" "$L/build.gradle" && grep -q "KELIVER_NEXT_PUBLIC_KEY_HEX'.*portalKeyLines\[1\]" "$H/build.gradle" \
  && grep -q 'publicKeyHex = BuildConfig.KELIVER_PUBLIC_KEY_HEX' "$LK/KeliverHost.kt" && grep -q 'nextPublicKeyHex = BuildConfig.KELIVER_NEXT_PUBLIC_KEY_HEX' "$LK/KeliverHost.kt" \
  && ! grep -q 'assets.open' "$LK/KeliverHost.kt" \
  && ok "the host trusts the checked key compiled into BuildConfig, never the merged assets (another asset of that name cannot replace it)" \
  || bad "the host still reads its key from the merged assets"
grep -q 'claim(cacheNameFor(it.publicKeyHex))' "$LK/KeliverHost.kt" && ! grep -q '^    claim(cacheName)' "$LK/KeliverHost.kt" \
  && ok "a second host for the same key fails in create(), not later in start()" || bad "the per-key claim is not in create()"
grep -qx -- '-keep class app.cash.zipline.MemoryUsage { \*; }' "$L/consumer-rules.pro" \
  && ok "M5: the library's consumer rules keep MemoryUsage (QuickJS builds it through JNI)" || bad "M5: the library's consumer rules do not keep MemoryUsage"
[ ! -e "$LK/HostApp.kt" ] && [ ! -e "$LK/MainActivity.kt" ] && ok "no app shell (HostApp, MainActivity) in the library" || bad "the library carries the app shell"
same=1; for f in KeliverHost.kt KeliverScreen.kt BundleIndex.kt Bridges.kt CrashRollback.kt StartPolicy.kt CallGates.kt ProductionTrust.kt AndroidSqlHost.kt OkHttpHostHttp.kt GuestContract.kt; do
  cmp -s "$LK/$f" "$H/src/main/kotlin/com/example/demo/host/$f" || { same=0; bad "$f differs between the library and the standalone host"; }
done
[ "$same" = 1 ] && ok "the host Kotlin is byte-identical in the library and the standalone host (one set of sources)"
diff -r "$H/src/debug" "$L/src/debug" > /dev/null 2>&1 \
  && ok "ED3: the library has the same debug link in its debug source set (an app's debug build merges it, its release build none of it)" \
  || bad "ED3: the library's src/debug differs from the standalone host's"
embed_refuses "a second run over the module" "already exists" --embed --into "$EX" --bundle-server "$SERVER" --public-key-file "$KEY"
ls -a "$EX" | grep -q '^\.keliver-host\.' && bad "an --embed staging directory was left behind" || ok "no --embed staging directory left behind"

if [ "$BUILD" = 1 ]; then
  echo "=== the scaffolded host compiles against Maven Central"
  SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
  if [ ! -d "$SDK/platforms" ]; then
    bad "no Android SDK at $SDK"
  else
    [ -n "${JAVA_HOME:-}" ] || { [ -x /usr/libexec/java_home ] && export JAVA_HOME="$(/usr/libexec/java_home -v 17)"; }
    export GRADLE_USER_HOME="$DISP/gradle-home" JAVA_TOOL_OPTIONS="-Duser.home=$DISP/home"
    mkdir -p "$GRADLE_USER_HOME" "$DISP/home"
    grep -E '^systemProp\.javax\.net\.ssl\.trustStore' "$HOME/.gradle/gradle.properties" > "$GRADLE_USER_HOME/gradle.properties" 2>/dev/null || true
    printf 'sdk.dir=%s\n' "$SDK" > "$H/local.properties"
    ( cd "$APP" && ./gradlew --console=plain -p host-android assembleDebug > "$DISP/assemble.log" 2>&1 ); rc=$?
    if [ "$rc" = 0 ]; then
      apk="$(find "$H/build/outputs/apk" -name '*.apk' | head -1)"
      ok "assembleDebug succeeded: $(basename "$apk"), $(wc -c < "$apk" | tr -d ' ') bytes"
      unzip -p "$apk" assets/keliver/portal_ed25519.pub | tr -d '[:space:]' | cmp -s - <(tr -d '[:space:]' < "$KEY") \
        && ok "the APK embeds exactly the scaffolded public key" || bad "the APK's embedded key differs"
      # The listing first: under pipefail, grep -q stopping early could end unzip with SIGPIPE.
      unzip -l "$apk" > "$DISP/apk-listing.txt" 2>&1
      grep -q ' AndroidManifest.xml$' "$DISP/apk-listing.txt" && ok "the APK has a manifest" || bad "the APK has no manifest"
      find "$H/build/generated/source/buildConfig/debug" -name BuildConfig.java -exec cat {} + | grep -q 'KELIVER_UPDATES = "live"' \
        && ok "the debug build, keliver.updates empty, takes live updates" || bad "the debug build's KELIVER_UPDATES is not live"
    else
      bad "assembleDebug failed (rc=$rc): $(grep -E '^e: |What went wrong' -A2 "$DISP/assemble.log" | head -6 | tr '\n' ' ')"
    fi
    ( cd "$APP" && ./gradlew --console=plain -p host-android testDebugUnitTest > "$DISP/unit-tests.log" 2>&1 ); rc=$?
    [ "$rc" = 0 ] && ok "the host kit's JVM tests pass (testDebugUnitTest)" \
      || bad "the host kit's JVM tests failed (rc=$rc): $(grep -E 'FAILED|What went wrong' -A2 "$DISP/unit-tests.log" | head -6 | tr '\n' ' ')"
    fw="$H/build/test-results/testDebugUnitTest/TEST-com.example.demo.host.FrameWindowTest.xml"
    [ -f "$fw" ] && grep -q 'failures="0" errors="0"' "$fw" && ! grep -q 'tests="0"' "$fw" \
      && ok "M4: the frame window's tests ran: $(grep -o 'tests="[0-9]*"' "$fw" | head -1)" || bad "M4: the frame window's tests did not run, or failed"
    cr="$H/build/test-results/testDebugUnitTest/TEST-com.example.demo.host.CrashRollbackTest.xml"
    [ -f "$cr" ] && grep -q 'failures="0" errors="0"' "$cr" && ! grep -q 'tests="0"' "$cr" \
      && ok "WS6: crash rollback's tests ran: $(grep -o 'tests="[0-9]*"' "$cr" | head -1)" || bad "WS6: crash rollback's tests did not run, or failed"
    spt="$H/build/test-results/testDebugUnitTest/TEST-com.example.demo.host.StartPolicyTest.xml"
    [ -f "$spt" ] && grep -q 'failures="0" errors="0"' "$spt" && ! grep -q 'tests="0"' "$spt" \
      && ok "start policy: its decision's tests ran: $(grep -o 'tests="[0-9]*"' "$spt" | head -1)" || bad "start policy: its tests did not run, or failed"
    rs="$H/build/test-results/testDebugUnitTest/TEST-com.example.demo.host.ReportSendTest.xml"
    [ -f "$rs" ] && grep -q 'failures="0" errors="0"' "$rs" && ! grep -q 'tests="0"' "$rs" \
      && ok "W6: the report sender's tests ran (a collector that closes loses no report): $(grep -o 'tests="[0-9]*"' "$rs" | head -1)" || bad "W6: the report sender's tests did not run, or failed"
    dlt="$H/build/test-results/testDebugUnitTest/TEST-com.example.demo.host.DebugBundleLinkTest.xml"
    [ -f "$dlt" ] && grep -q 'failures="0" errors="0"' "$dlt" && ! grep -q 'tests="0"' "$dlt" \
      && ok "ED3: the debug link's tests ran: $(grep -o 'tests="[0-9]*"' "$dlt" | head -1)" || bad "ED3: the debug link's tests did not run, or failed"
    hpt="$H/build/test-results/testDebugUnitTest/TEST-com.example.demo.host.HostPrefetchTest.xml"
    [ -f "$hpt" ] && grep -q 'failures="0" errors="0"' "$hpt" && ! grep -q 'tests="0"' "$hpt" \
      && ok "NF4: the first request's prefetch tests ran: $(grep -o 'tests="[0-9]*"' "$hpt" | head -1)" || bad "NF4: the prefetch tests did not run, or failed"
    # The race test shared with the iOS host (the same file): 1,000 runs each, never two sends.
    hrt="$H/build/test-results/testDebugUnitTest/TEST-com.example.demo.host.HostPrefetchRaceTest.xml"
    [ -f "$hrt" ] && grep -q 'failures="0" errors="0"' "$hrt" && grep -q 'tests="2"' "$hrt" \
      && ok "NF4: the prefetch's race tests ran (arm, UI start, take and discard racing, 1,000 runs each): $(grep -o 'tests="[0-9]*"' "$hrt" | head -1)" \
      || bad "NF4: the prefetch's race tests did not run, or failed"
    # A release build with an http:// server is refused.
    ( cd "$APP" && ./gradlew --console=plain -p host-android assembleRelease > "$DISP/assemble-release.log" 2>&1 ); rc=$?
    [ "$rc" != 0 ] && grep -q "A release build needs https:// servers" "$DISP/assemble-release.log" \
      && ok "a release build with an http:// server fails" || bad "release with http server: rc=$rc"
    # Debug-only surfaces (K1's audit): a release build with https servers, keliver.updates=live
    # asked for, is not debuggable, has DEBUG false and no live updates, and exports nothing but
    # its launcher activity unless a permission guards it (AndroidX's profile installer: DUMP).
    ( cd "$APP" && ./gradlew --console=plain -p host-android assembleRelease \
        -Pkeliver.bundleServer=https://bundles.example.com -Pkeliver.updates=live > "$DISP/assemble-release-https.log" 2>&1 ); rc=$?
    rel_apk="$(find "$H/build/outputs/apk/release" -name '*.apk' 2>/dev/null | head -1)"
    aapt2="$(ls -d "$SDK"/build-tools/*/aapt2 2>/dev/null | sort -V | tail -1)"
    if [ "$rc" = 0 ] && [ -n "$rel_apk" ] && [ -n "$aapt2" ]; then
      find "$H/build/generated/source/buildConfig/release" -name BuildConfig.java -exec cat {} + > "$DISP/release-buildconfig.txt"
      grep -q 'boolean DEBUG = false' "$DISP/release-buildconfig.txt" && grep -q 'KELIVER_UPDATES = "on-resume"' "$DISP/release-buildconfig.txt" \
        && ok "debug surfaces: a release build has DEBUG false, and keliver.updates=live becomes on-resume there" \
        || bad "debug surfaces: the release BuildConfig: $(grep -E 'DEBUG|KELIVER_UPDATES' "$DISP/release-buildconfig.txt" | tr '\n' ' ')"
      "$aapt2" dump xmltree --file AndroidManifest.xml "$rel_apk" > "$DISP/release-manifest.txt" 2>&1
      cat > "$DISP/manifest-exposed.py" <<'PY'
import re, sys
# The aapt2 tree: an element line "E: name (line=N)", its attributes "A: …" below it, indented deeper.
lines = open(sys.argv[1]).read().splitlines()
problems, i = [], 0
def indent(l): return len(l) - len(l.lstrip())
for i, l in enumerate(lines):
    s = l.strip()
    if 'debuggable' in s and s.endswith('=true'): problems.append('the application is debuggable')
    m = re.match(r'E: (activity|activity-alias|receiver|service|provider) ', s)
    if not m: continue
    body, own, j = [], [], i + 1
    while j < len(lines) and indent(lines[j]) > indent(l):
        t = lines[j].strip()
        body.append(t)
        if t.startswith('A: ') and indent(lines[j]) == indent(l) + 2: own.append(t)
        j += 1
    exported = any(':exported(' in a and a.endswith('=true') for a in own)
    guarded = any(':permission(' in a for a in own)
    launcher = any('android.intent.category.LAUNCHER' in b for b in body)
    if exported and not guarded and not (m.group(1) in ('activity', 'activity-alias') and launcher):
        name = next((a for a in own if ':name(' in a), '?')
        problems.append(f'{m.group(1)} {name} is exported without a permission')
print('; '.join(problems))
PY
      exposed="$(python3 "$DISP/manifest-exposed.py" "$DISP/release-manifest.txt")"
      [ -z "$exposed" ] && ok "debug surfaces: a release build is not debuggable and exports only its launcher, or what a permission guards" \
        || bad "debug surfaces: $exposed"
      # ED3: the debug link. The positive control first: the debug APK has its filter and its
      # class, so the release check below can see them.
      dbg_apk="$(find "$H/build/outputs/apk/debug" -name '*.apk' 2>/dev/null | head -1)"
      "$aapt2" dump xmltree --file AndroidManifest.xml "$dbg_apk" > "$DISP/debug-manifest.txt" 2>&1
      # The dex files to a file first: `grep -q` stops at the first match, and under pipefail the
      # unzip it cut off would turn a match into a failure.
      dex_has(){ unzip -p "$1" 'classes*.dex' > "$DISP/dex.bin" 2>/dev/null && LC_ALL=C grep -aqE "$2" "$DISP/dex.bin"; }
      grep -q '"keliver-com.example.demo"' "$DISP/debug-manifest.txt" && grep -q '"keliver-debug"' "$DISP/debug-manifest.txt" \
        && grep -q 'KeliverDebugLinkActivity' "$DISP/debug-manifest.txt" && dex_has "$dbg_apk" 'KeliverDebugLinkActivity' && dex_has "$dbg_apk" 'DebugBundleLink' \
        && ok "ED3: the debug APK has the debug link (its intent filter for keliver-com.example.demo://keliver-debug, its activity and check in the dex)" \
        || bad "ED3: the debug APK lacks the debug link: the release check would prove nothing"
      ! grep -qE 'keliver-debug|keliver-com\.example\.demo|KeliverDebugLinkActivity' "$DISP/release-manifest.txt" \
        && ! dex_has "$rel_apk" 'KeliverDebugLinkActivity|DebugBundleLink|keliver-debug' \
        && ok "ED3: the release APK has no debug link: no intent filter or activity in its manifest, no handler class in its dex" \
        || bad "ED3: the release APK carries the debug link"
    else
      bad "debug surfaces: no release build to check (rc=$rc, apk '${rel_apk}', aapt2 '${aapt2}')"
    fi
    # A key that is not a key fails the BUILD.
    printf 'garbage\n' > "$H/src/main/assets/keliver/portal_ed25519.pub"
    ( cd "$APP" && ./gradlew --console=plain -p host-android assembleDebug > "$DISP/assemble-badkey.log" 2>&1 ); rc=$?
    [ "$rc" != 0 ] && grep -q "not a 64-hex-digit Ed25519 public key" "$DISP/assemble-badkey.log" \
      && ok "a malformed embedded key fails the build" || bad "a malformed key did not fail the build (rc=$rc)"
    ( cd "$APP" && ./gradlew -p host-android --stop > /dev/null 2>&1 )
  fi
fi

echo "=== NF2: the Zipline line follows the app's Keliver version (keliver-runtime-line.sh)"
# The app above names no version (0.4.1): upstream Zipline 1.22.0 on Kotlin 2.2.0, as before.
grep -q "^  id 'app.cash.zipline' version '1.22.0'$" "$H/build.gradle" && grep -q "implementation 'app.cash.zipline:zipline:1.22.0'" "$H/build.gradle" \
  && grep -q "implementation 'app.cash.zipline:zipline-loader:1.22.0'" "$H/build.gradle" && grep -q "id 'org.jetbrains.kotlin.android' version '2.2.0'" "$H/build.gradle" \
  && ! grep -q 'dev\.keliver\.zipline' "$H/build.gradle" && grep -q "^  id 'app.cash.zipline' // IR rewrite" "$L/build.gradle" \
  && grep -q "implementation 'app.cash.zipline:zipline:1.22.0'" "$L/build.gradle" && ! grep -q 'dev\.keliver\.zipline:' "$L/build.gradle" \
  && ok "NF2: a 0.4.1 app's host and library keep upstream Zipline 1.22.0 on Kotlin 2.2.0" || bad "NF2: the 0.4.1 app's host changed its Zipline"
for v in 0.5.0 0.5.1-SNAPSHOT; do
  APPF="$DISP/fork-${v//./-}"; mkdir -p "$APPF/src/jsMain/kotlin/screens"
  printf '{ "screensDir": "src/jsMain/kotlin/screens", "appRuntime": { "keliverVersion": "%s", "widgetVersion": 1 } }\n' "$v" > "$APPF/keliver.portal.json"
  cp "$APP/settings.gradle" "$APPF/"; cp "$APP/src/jsMain/kotlin/screens/home.kt" "$APPF/src/jsMain/kotlin/screens/"
  keliver_require_isolated_store "$DISP" "$APPF" > /dev/null || { echo "isolation guard refused" >&2; exit 2; }
  out="$( cd "$APPF" && "$SCAFFOLD" --bundle-server "$SERVER" --public-key-file "$KEY" 2>&1 )"; rc=$?
  HF="$APPF/host-android/build.gradle"
  [ "$rc" = 0 ] && grep -q "^  id 'dev.keliver.zipline' version '1.28.0-keliver.1'$" "$HF" \
    && grep -q "implementation 'dev.keliver.zipline:zipline:1.28.0-keliver.1'" "$HF" && grep -q "implementation 'dev.keliver.zipline:zipline-loader:1.28.0-keliver.1'" "$HF" \
    && grep -q "id 'org.jetbrains.kotlin.android' version '2.4.20'" "$HF" && grep -q "id 'org.jetbrains.compose' version '1.12.2'" "$HF" \
    && grep -q "implementation 'dev.keliver:keliver-treehouse-host:$v'" "$HF" && ! grep -qE "['\"]app\.cash\.zipline['\":]" "$HF" \
    && ok "NF2: an app on $v gets Keliver's Zipline fork (plugin dev.keliver.zipline, its libraries) on Kotlin 2.4.20, Compose 1.12.2" \
    || bad "NF2: the host of an app on $v (rc=$rc): $(grep -hE 'zipline|kotlin.android|org.jetbrains.compose' "$HF" 2>/dev/null | tr -s ' ' | tr '\n' '|') $(printf '%s' "$out" | tail -2 | tr '\n' ' ')"
done
KELIVER_HOST_ZIPLINE_VERSION=1.28.0-keliver.9 KELIVER_HOST_KOTLIN_VERSION=2.4.99 bash -c 'cd "$1" && rm -rf host-android && "$2" --bundle-server "$3" --public-key-file "$4"' _ "$DISP/fork-0-5-0" "$SCAFFOLD" "$SERVER" "$KEY" > /dev/null 2>&1; rc=$?
[ "$rc" = 0 ] && grep -q "^  id 'dev.keliver.zipline' version '1.28.0-keliver.9'$" "$DISP/fork-0-5-0/host-android/build.gradle" \
  && grep -q "id 'org.jetbrains.kotlin.android' version '2.4.99'" "$DISP/fork-0-5-0/host-android/build.gradle" \
  && ok "NF2: KELIVER_HOST_*_VERSION still override the fork line's defaults" || bad "NF2: the overrides on the fork line (rc=$rc)"

echo "=== the toolchain follows the line (keliver-runtime-line.sh's table)"
# The 0.4.1 app above: exactly the lines it had before the table (AGP 8.12.0 applying Kotlin's
# Android plugin, compileSdk and targetSdk 35, JDK 17), in the host and in the library.
grep -qx "  id 'com.android.application' version '8.12.0'" "$H/build.gradle" && grep -qx "  id 'org.jetbrains.kotlin.android' version '2.2.0'" "$H/build.gradle" \
  && grep -qx '  compileSdk 35' "$H/build.gradle" && grep -qx '    targetSdk 35' "$H/build.gradle" \
  && grep -qx '  compileOptions { sourceCompatibility JavaVersion.VERSION_17; targetCompatibility JavaVersion.VERSION_17 }' "$H/build.gradle" \
  && grep -qx 'kotlin { jvmToolchain(17) }' "$H/build.gradle" \
  && grep -qx "  id 'org.jetbrains.kotlin.android'" "$L/build.gradle" && grep -qx '  compileSdk 35' "$L/build.gradle" && grep -qx 'kotlin { jvmToolchain(17) }' "$L/build.gradle" \
  && ok "0.4 line: AGP 8.12.0 with Kotlin's Android plugin applied, compileSdk/targetSdk 35, JDK 17 (host and library, as before)" \
  || bad "0.4 line: the host's toolchain changed: $(grep -hE "com.android|kotlin.android|compileSdk|targetSdk" "$H/build.gradle" "$L/build.gradle" | tr -s ' ' | tr '\n' '|')"
for v in 0.5.0 0.5.1-SNAPSHOT; do
  HF="$DISP/fork-${v//./-}/host-android/build.gradle"
  [ "$v" = 0.5.0 ] && HF="$DISP/fork-0-5-0-fresh/host-android/build.gradle"
  if [ "$v" = 0.5.0 ]; then
    # fork-0-5-0's host was rewritten with overrides above: scaffold a clean copy.
    APPF="$DISP/fork-0-5-0-fresh"; mkdir -p "$APPF/src/jsMain/kotlin/screens"
    cp "$DISP/fork-0-5-0/keliver.portal.json" "$APP/settings.gradle" "$APPF/"; cp "$APP/src/jsMain/kotlin/screens/home.kt" "$APPF/src/jsMain/kotlin/screens/"
    keliver_require_isolated_store "$DISP" "$APPF" > /dev/null || { echo "isolation guard refused" >&2; exit 2; }
    ( cd "$APPF" && "$SCAFFOLD" --bundle-server "$SERVER" --public-key-file "$KEY" > /dev/null 2>&1 ) || bad "the fresh 0.5.0 scaffold failed"
  fi
  grep -qx "  id 'com.android.application' version '9.3.1'" "$HF" \
    && grep -q "^  id 'org.jetbrains.kotlin.android' version '2.4.20' apply false //" "$HF" \
    && grep -qx '  compileSdk 37' "$HF" && grep -qx '    targetSdk 36' "$HF" && grep -qx 'kotlin { jvmToolchain(17) }' "$HF" \
    && ok "0.5 line ($v): AGP 9.3.1 with built-in Kotlin (Kotlin's Android plugin only pins its version), compileSdk 37, targetSdk 36, JDK 17" \
    || bad "0.5 line ($v): $(grep -hE "com.android|kotlin.android|compileSdk|targetSdk" "$HF" 2>/dev/null | tr -s ' ' | tr '\n' '|')"
done
KELIVER_HOST_AGP_VERSION=8.12.0 bash -c 'cd "$1" && rm -rf host-android && "$2" --bundle-server "$3" --public-key-file "$4"' _ "$DISP/fork-0-5-0" "$SCAFFOLD" "$SERVER" "$KEY" > /dev/null 2>&1; rc=$?
[ "$rc" = 0 ] && grep -qx "  id 'com.android.application' version '8.12.0'" "$DISP/fork-0-5-0/host-android/build.gradle" \
  && grep -qx "  id 'org.jetbrains.kotlin.android' version '2.4.20'" "$DISP/fork-0-5-0/host-android/build.gradle" \
  && ok "0.5 line with KELIVER_HOST_AGP_VERSION=8.12.0: Kotlin's Android plugin is applied again (what AGP 8 needs)" || bad "the AGP override on the 0.5 line (rc=$rc)"
# The host builds with the app's wrapper: an app on 0.5.0 whose wrapper is older than AGP 9.3.1's
# floor (Gradle 9.5.0) is told what to set; one on the line's Gradle is not; a 0.4.1 app on 9.0.0 is not.
WF="$DISP/fork-0-5-0-fresh"; rm -rf "$WF/host-android"
bash "$ROOT/scripts/keliver-app-wrapper.sh" "$WF"
out="$( cd "$WF" && "$SCAFFOLD" --bundle-server "$SERVER" --public-key-file "$KEY" 2>&1 )"; rc=$?
[ "$rc" = 0 ] && printf '%s' "$out" | grep -q "warning: this app's Gradle wrapper is Gradle 9.0.0; AGP 9.3.1 (Keliver 0.5.0) needs Gradle 9.5.0 or newer" \
  && printf '%s' "$out" | grep -qF 'distributionUrl=https\://services.gradle.org/distributions/gradle-9.7.0-bin.zip' \
  && printf '%s' "$out" | grep -q 'distributionSha256Sum=84fbba45c7f4c64abc77460e1c00f541e9f960e3c7ed2538f1ede19eacd873ae' \
  && ok "0.5 line: an app wrapper on Gradle 9.0.0 is warned, with the lines to set (Gradle 9.7.0 and its sha256)" \
  || bad "0.5 line: no warning about the app's Gradle 9.0.0 (rc=$rc): $(printf '%s' "$out" | grep -i gradle | head -2 | tr '\n' ' ')"
rm -rf "$WF/host-android"; bash "$ROOT/scripts/keliver-app-wrapper.sh" "$WF" 0.5.0
out="$( cd "$WF" && "$SCAFFOLD" --bundle-server "$SERVER" --public-key-file "$KEY" 2>&1 )"; rc=$?
[ "$rc" = 0 ] && ! printf '%s' "$out" | grep -q "Gradle wrapper is Gradle" && ok "0.5 line: an app wrapper on the line's Gradle 9.7.0 is not warned" || bad "0.5 line: warned on Gradle 9.7.0 (rc=$rc)"
A04="$DISP/old-wrapper"; mkdir -p "$A04/src/jsMain/kotlin/screens"
cp "$APP/keliver.portal.json" "$APP/settings.gradle" "$A04/"; cp "$APP/src/jsMain/kotlin/screens/home.kt" "$A04/src/jsMain/kotlin/screens/"
bash "$ROOT/scripts/keliver-app-wrapper.sh" "$A04"
keliver_require_isolated_store "$DISP" "$A04" > /dev/null || { echo "isolation guard refused" >&2; exit 2; }
out="$( cd "$A04" && "$SCAFFOLD" --bundle-server "$SERVER" --public-key-file "$KEY" 2>&1 )"; rc=$?
[ "$rc" = 0 ] && grep -q 'gradle-9.0.0-bin' "$A04/gradle/wrapper/gradle-wrapper.properties" && ! printf '%s' "$out" | grep -qi "warning" \
  && ok "0.4 line: a 0.4.1 app on Gradle 9.0.0 (AGP 8.12.0 needs 8.13+) is not warned" || bad "0.4 line: the 0.4.1 app on Gradle 9.0.0 (rc=$rc): $(printf '%s' "$out" | grep -i warning | head -1)"
# --embed on 0.5.0: the library names the fork's plugin; an existing app still on app.cash.zipline is warned.
EXF="$DISP/existing-fork"; mkdir -p "$EXF/app"
printf "pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }\nrootProject.name = 'existing'\ninclude ':app'\n" > "$EXF/settings.gradle"
printf "plugins {\n  id 'com.android.application' version '8.12.0' apply false\n  id 'app.cash.zipline' version '1.22.0' apply false\n}\n" > "$EXF/build.gradle"
out="$( cd "$DISP/fork-0-5-0" && "$SCAFFOLD" --embed --into "$EXF" --bundle-server "$SERVER" --public-key-file "$KEY" 2>&1 )"; rc=$?
LF="$EXF/keliver-host/build.gradle"
[ "$rc" = 0 ] && grep -q "^  id 'dev.keliver.zipline' // IR rewrite" "$LF" && grep -q "implementation 'dev.keliver.zipline:zipline:1.28.0-keliver.1'" "$LF" \
  && ! grep -qE "['\"]app\.cash\.zipline['\":]" "$LF" \
  && printf '%s' "$out" | grep -q "warning: no dev.keliver.zipline plugin found" \
  && printf '%s' "$out" | grep -q "names app.cash.zipline: Keliver 0.5.0 builds on Keliver's Zipline fork" \
  && printf '%s' "$out" | grep -q "warning: no Kotlin 2.4.20 found" \
  && ok "NF2: --embed on 0.5.0 writes the fork's plugin id and libraries, and warns about the app's app.cash.zipline and its Kotlin" \
  || bad "NF2: --embed on 0.5.0 (rc=$rc): $(printf '%s' "$out" | grep warning | tr '\n' ' ')"
! grep -q "^  id 'org.jetbrains.kotlin.android'" "$LF" && grep -q "^  // Kotlin: AGP 9 compiles this module itself (built-in Kotlin)" "$LF" \
  && grep -qx '  compileSdk 37' "$LF" && grep -qx 'kotlin { jvmToolchain(17) }' "$LF" \
  && printf '%s' "$out" | grep -q "note: Keliver 0.5.0's libraries need .* on AGP 9.1.0 or newer (Keliver builds with 9.3.1) and compileSdk 37" \
  && ok "0.5 line: --embed applies no Kotlin plugin (the app's AGP 9 compiles Kotlin), compileSdk 37, and says the app needs AGP 9.1.0+" \
  || bad "0.5 line: --embed's toolchain: $(grep -hE "kotlin.android|Kotlin:|compileSdk" "$LF" | tr -s ' ' | tr '\n' '|') $(printf '%s' "$out" | grep note: | head -1)"
# The helper must sit beside the scaffolder (the tools bundle's bin/ carries it).
NL="$DISP/noline"; mkdir -p "$NL"; cp "$SCAFFOLD" "$NL/"; cp -R "$(dirname "$SCAFFOLD")/templates" "$NL/" 2>/dev/null || cp -R "$(dirname "$SCAFFOLD")/../templates" "$NL/"
before="$(snapshot "$APP")"
out="$( cd "$APP" && "$NL/keliver-new-production-host.sh" --embed --into "$EXF" --module nl --bundle-server "$SERVER" --public-key-file "$KEY" 2>&1 )"; rc=$?
[ "$rc" != 0 ] && printf '%s' "$out" | grep -q "keliver-runtime-line.sh is missing" && [ ! -e "$EXF/nl" ] && [ "$before" = "$(snapshot "$APP")" ] \
  && ok "NF2: without keliver-runtime-line.sh beside it, the scaffolder refuses and writes nothing" || bad "NF2: no keliver-runtime-line.sh (rc=$rc): $(printf '%s' "$out" | tail -1)"

echo "=== without a store: accepted, and said so"
unset PORTAL_STORE
APP2="$DISP/demo2"; mkdir -p "$APP2/src/jsMain/kotlin/screens"
cp "$APP/keliver.portal.json" "$APP/settings.gradle" "$APP2/"; cp "$APP/src/jsMain/kotlin/screens/home.kt" "$APP2/src/jsMain/kotlin/screens/"
keliver_require_isolated_store "$DISP" "$APP2" > /dev/null || { echo "isolation guard refused" >&2; exit 2; }
out="$( cd "$APP2" && "$SCAFFOLD" --bundle-server "$SERVER" --public-key-file "$OTHER" 2>&1 )"; rc=$?
[ "$rc" = 0 ] && printf '%s' "$out" | grep -q "NOT checked against a store" \
  && ok "with no store, a key file is accepted and marked unchecked" || bad "no-store scaffold: rc=$rc $(printf '%s' "$out" | tail -2 | tr '\n' ' ')"
grep -rqF -f "$PRIV" "$DISP"/demo*/host-android 2>/dev/null && bad "the private key is in a scaffolded module" \
  || ok "the private key is in no scaffolded module"

echo
echo "production-host scaffold: $pass passed, $fail failed   ($DISP)"
[ "$fail" -eq 0 ]
