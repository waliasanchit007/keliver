# keliver-portal-tools 0.3.14 — release notes

**Status: CANDIDATE**, by `PORTAL_TOOLS_RELEASE.md`. Identities are filled in
as each step of the procedure passes.

What is in it since 0.3.13 (`a5ab6e2b2`): PRs #150 (live updates on the iOS
host), #153 (an editor test fix), #155, #158, #159, #161 (the host kit on
Android), #160 and #162 (the host kit on iOS), and #158's follow-ups to live
updates. #152, #154, #156, #157 record releases and evidence.

**The libraries stay 0.4.1.** Nothing published to Maven Central changed
since 0.4.1: every change here is in the scaffolders' templates, the relay
(part of this bundle), the reference app's CI and the docs. New apps get
`dev.keliver:*:0.4.1`, as with 0.3.13.

## New: iOS debug hosts pick up a publish while the app is open (#150)

The scaffolded iOS host (`keliver-new-ios-host.sh`, standalone or `--embed`)
gets what the Android host got in 0.3.13. `UPDATES = "live"` is the default
for a DEBUG binary (`on-resume` in a release one): while a Keliver screen is
on show (its view controller started), the host listens to the relay's
`GET /bundles/events` and applies a publish in the open app, after the usual
signature, index and floor checks. A server without the stream is detected
once; the host then updates on resume.

The relay's stream now starts every connection with a 2 KiB comment
(URLSession holds back a short first event), and a listener hears nothing
before it (#158).

## New: the host kit, for an app that embeds Keliver screens

Adoption item 3. What each embed wrote by hand, and where its review
findings were, now ships in both host templates. Complete in code on
Android; on iOS, all but `screen-failed`. Not yet checked on a device (K4,
next). A host scaffolded by an earlier release gets none of it unless it is
scaffolded again.

**Android (`keliver-new-production-host.sh`, standalone or `--embed`):**
- **Status:** `KeliverHostState` is `Loading`, `Unavailable(outcome, reason,
  title, message, attempt)` or `Running`. `KeliverScreen`/`KeliverView` call
  `onUnavailable` when the host cannot show a screen: replace the Keliver
  screen there with what it replaced.
- **Release text:** the host's own text (addresses, paths) only in a debug
  build of a debuggable app; otherwise "Loading…" and a generic line.
- **`screen-failed`:** when the code throws while a screen shows it, the
  host reports it, and that screen (and any opened before code loads again)
  shows the unavailable slot and calls `onUnavailable`.
- **`RequestPolicy`:** HostHttp requests limited to exact `(method, path)`
  pairs on one origin (the API base's), https unless the build allows
  cleartext, state-changing calls behind a per-build switch, method-override
  headers dropped; checked before anything is sent. **Opt-in:** without a
  policy HostHttp behaves as before (every path under the API base); an app
  embedding Keliver screens should set one. The query string and other
  headers (`Authorization`, `Cookie`) are not restricted.
- **`ExternalLinkPolicy.open(context, url)`:** https in a Custom Tab;
  `intent:` only as a browser opens it (never this app, no launch flags);
  everything else refused.
- **Routes and close:** `KeliverScreen(route = KeliverRoute.from(link,
  allowedQueryKeys), onClose = …)`. The guest receives the route in
  `launchRoute` (`keliver-new-device-target.sh` scaffolds it); a guest
  scaffolded before shows its default screen. `closeScreen()` runs your
  `onClose`, or finishes the activity.
- **Debug-only surfaces audited:** each needs a debug build of a debuggable
  app; Keliver's scaffolder self-test checks a release APK's manifest and
  BuildConfig.
- New dependency: `androidx.browser:browser:1.8.0`. The standalone host
  carries the kit's JVM tests (`./gradlew -p host-android testDebugUnitTest`).

**iOS (`keliver-new-ios-host.sh`):**
- **Status:** a public `KeliverHostState` with Android's shape;
  `Keliver.shared.currentState` and `onStateChange` for Swift;
  `viewController(safeArea:onUnavailable:)` and `KeliverScreen(onUnavailable:)`;
  the release text policy (the host's text only in a DEBUG binary).
- **`RequestPolicy`:** Android's rules, opt-in as there, set with
  `Keliver.shared.requestPolicy` before the host starts.
- **Routes and close:** the same `KeliverRoute` (one file for both hosts, so
  the same link gives the guest the same text);
  `viewController(safeArea:onUnavailable:route:onClose:)` and
  `KeliverScreen(route:onClose:)`; by default a close pops the navigation
  stack back below the screen, or dismisses it.
- No `screen-failed` outcome on iOS yet.
- The module gains a `jvm()` target that only runs the kit's pure-Kotlin
  tests (`./gradlew -p host-ios jvmTest`), and declares
  `org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose:2.8.4` (it was
  transitive, #158).

**For an existing guest:** `keliver-new-device-target.sh` now writes
`src/jsMain/kotlin/device/KeliverScreenHost.kt` (package
`dev.keliver.hostkit`), and `launchRoute(route, screen)` on the
`PortalPresenter` interface and on its implementation in
`src/jsMain/kotlin/device/Main.kt`. An app scaffolded earlier keeps working
(no routes: the host shows its `launch()` screen) until it adds both; the
package must be exactly that one, or the host cannot match `launchRoute`.

## Fixed

- **The relay's `/doc-events`:** two events could be written into one
  browser's stream at once (#158).
- **`Unavailable` after a retry (Android):** the same failure again could
  leave `onUnavailable` uncalled (#158: `attempt`; iOS has it from #160).

## Verification

(to be filled in: source commit, zip sha256 and size, `VERSION.json`, APK,
build run and artifact, device job, the packaged checks, the stored asset's
digest, the public download check)

Not verified for this release: the host kit on a device (K4), live updates
on a physical iPhone (waiting on an Xcode account).
