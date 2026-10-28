# keliver-portal-tools 0.3.14 — release notes

**Status: RELEASED 2026-10-28**, by `PORTAL_TOOLS_RELEASE.md`.

| | |
|---|---|
| tag | `portal-tools-v0.3.14` → `58851db260446445ae47ec09e7ac428695bf5a4f` |
| zip asset | `keliver-portal-tools-0.3.14.zip`, sha256 `7284aec9d45ea11a0b8c36863955a4f47c6df79c996be488922d3f092a24dc08` (92,077,423 bytes): the retained, verified CI artifact |
| `.sha256` asset | the zip's sha256 and file name |

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

All of it against one set of bytes: the retained artifact of build run
`40672818679`, which was then attached to the release.

| | |
|---|---|
| Source commit | `58851db260446445ae47ec09e7ac428695bf5a4f` (PR #163's head; `sourceDirtyFiles` 0) |
| Zip | `keliver-portal-tools-0.3.14.zip`, sha256 `7284aec9d45ea11a0b8c36863955a4f47c6df79c996be488922d3f092a24dc08`, 92,077,423 bytes |
| `VERSION.json` | tools 0.3.14, Maven dependency 0.4.1 |
| Bundled host APK | sha256 `0e435144096d75b133321e14310e9f7eb3d1a16d1f0eff2847115279b3b73e83`, no `assets/portal_ed25519.pub` |
| Build | `portal-tools.yml` run `40672818679`; retained artifact `12811030869` (92,061,968 bytes, digest `sha256:c04d62b3c6aac394317950cdc0f20f584afc9f9f5179d9bbde8baa7fbc901575`) |
| Device job | run `40676584557` (an emulator on GitHub-hosted Ubuntu; the verifier from the candidate's own branch): the APK's sha256 checked against the pin (`apk-under-test.sha256`); device verify 28/0; evidence artifact `12810318887` |

The build run's packaged checks: the unit tests (a Gradle run: `BUILD SUCCESSFUL`); the disposable-parent refusal 134/0 (earlier notes called this the unit tests); the store resolver 32/0
and 73/0; packaging hygiene (U22) 10/0; the **packaged adopter acceptance**,
with the U21 identity gate, 25/0; the foreign-relay refusal 6/0; the store
identity contract 11/0; the production-host scaffolder 79/0 (and 70/0 from
the package); the iOS host scaffolder 69/0 (and 69/0); the publish
scaffolder 51/0 (and 33/0); the screen and component scaffolders 9/0;
`keliver-publish` 41/0; key permissions 63/0; guest bundle signing 4/0;
store recovery 144/0 (0.3.13's run had 145: its C9 race check took the
"no winner" branch, three checks, where this run's took the "claimed"
branch, two).

The artifact was downloaded and checked on the releasing machine before the
tag (through `gh api …/actions/artifacts/12811030869/zip`): the artifact's
sha256 equals GitHub's digest, and the zip, `VERSION.json` and APK are as
above. Step 3 at `58851db26`: no workflow in the tagged tree can create a
release or attach an asset. PR #163's CI on the same commit (run `40672758352`):
Build and both editor E2E suites green; reference-app and ios-host do not run
on its paths, and ran on the template changes in #158–#162.

**How the stored asset was checked.** The draft's assets were compared with
the verified files by GitHub's server-side record (`digest`, `size`): the zip
`sha256:7284aec9…`, 92,077,423 bytes, and the `.sha256` file
`sha256:a2fcae54…`, 98 bytes, both `uploaded`; the tag (annotated, object
`2252a65cb`) was re-checked at `58851db26` and its push ran only
`portal-tools.yml`'s read-only rebuild (`40677547950`, never attached).
Downloading the draft asset back, and the public URL after publishing, were
blocked by the releasing machine's network (the 879-byte "Bad Gateway" page;
the public URL timed out), so — as for 0.3.5 and 0.3.13 — the public
download is checked from GitHub-hosted runners instead, by the reference
app's move to 0.3.14 (#164: the first step of `reference-app.yml` and
`ios-host.yml` checks the public zip against the release's `.sha256` and the
pinned hash). It passed in reference-app run `40679106090` (ubuntu) and
ios-host run `40679105755` (macOS; its job passed on the one rerun allowed,
`122872828527`, after the first attempt's P2 lookup of the relay timed out:
a harness flake, mitigated in #165 by asking the relay once before P2): `keliver-portal-tools-0.3.14.zip: OK`, then the
pinned hash and `sourceCommit`. Published 2026-10-28T11:13:40Z with
`--latest=false`.

Not verified for this release: the host kit on a device (K4), live updates
on a physical iPhone (waiting on an Xcode account).
