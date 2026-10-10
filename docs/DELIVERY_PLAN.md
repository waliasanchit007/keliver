# Delivery plan: from "OTA for Keliver screens" to a Shorebird-grade delivery path

**Read this at the start of every session that works on delivery, iOS, hosts,
publishing or releases.** Update the *Status* table and *Next action* before
you stop. This file is the plan of record; chat context is not.

Written 2026-10-07, after tools 0.3.6 was published. Owner: the repository
owner. Executed by agents under the standing constraints at the bottom.

## Scope

**Accepted limitation:** only code written as Keliver guests (screens and
presenters in Keliver's Kotlin/Compose model, compiled to signed Zipline
bundles) is updated over the air. Native host code ships through the stores.
Everything below is about the **delivery** of those bundles. The aim is that
a native Android or iOS developer gets what Shorebird gives Flutter developers:
- integrate into an existing app;
- release and patch from a CLI and from CI;
- hosted or static distribution;
- signing;
- rollback;
- channels and staged rollouts;
- an update API;
- visibility;
- proof on real devices.

## Where we are (2026-10-07)

| area | state |
|---|---|
| Libraries | `dev.keliver:*:0.3.3` on Maven Central, including the `iosArm64` and `iosSimulatorArm64` variants of the host, Compose UI, protocol and SQL artifacts |
| Tools | **0.3.6**: portal (relay, editor, MCP), scaffolders, generic Android dev host. `keliver-new-production-host.sh` writes an **Android** production host from Maven Central. `keliver-new-publish-target.sh` writes the signing. The relay refuses unsigned bundles. Keys are owner-only. |
| Android production host | scaffolded. On a CI emulator: P1–P7 pass (signed v1 → v2, foreign key refused, no empty-URL load, offline start from the verified cache, one cache per key) |
| iOS production host | **none for adopters.** `portal-device-ios` (+ `portal-device-ios-app`) is an in-repo dev/prod spike, built from Keliver source. Problems: <ul><li>prod without a key falls back to **`NO_SIGNATURE_CHECKS`**</li><li>hard-coded `localhost`</li><li>replay HTTP</li><li>empty-URL first load (U28)</li><li>no offline start</li><li>no cache per key</li></ul> Nothing about iOS is in the adopter guide or CI. |
| Publishing | `POST /publish` on the **local** relay only; signed, verified before storing |
| Distribution | bundles served by the local relay (`/bundles/latest?widgetVersion&caps`, `/bundles/vN/…`). **W3 (PR #90, unreleased):** a static `bundles/index.json` layout written by `keliver-publish`, read by both host templates, checked over HTTPS on CI. |
| Release controls | none. No channels, no rollback, no rollback protection: any older signed bundle is accepted. |
| Update API / visibility | host checks once at launch; no in-app API; no reporting |
| Proof | emulator only (API 33 x86_64). No physical device, no arm64, no iOS run of current artifacts, no external adopter. |

## Gap list (vs Shorebird's delivery features)

| # | gap | workstream |
|---|---|---|
| G1 | No iOS production host for adopters; the iOS spike can run unverified code | **W1** |
| G2 | The only scaffolded path is a standalone host app; there's no way to put Keliver screens into an *existing* Android/iOS app | W2 |
| G3 | Publishing needs the interactive local relay; there's no headless CLI for CI | W3 |
| G4 | No static/CDN distribution; the lookup is a dynamic endpoint; no HTTPS evidence | W3 |
| G5 | No rollback, and no protection against serving an older signed bundle | W4 |
| G6 | No channels (stable/beta/staging), no percentage rollouts | W4 |
| G7 | Bundles aren't tied to host binary versions (only `widgetVersion` + capabilities) | W4 |
| G8 | No in-app update API (check/apply/when), no update events | W5 |
| G9 | No visibility: what each install runs, update success/failure, crash attribution | W6 |
| G10 | No key rotation story (a host trusts one key) | W8 |
| G11 | No physical device, arm64, iOS device, release-signed app, or external adopter | W7 |

## Workstreams, in order

Each step lists its **done when**: the evidence that closes it. A step isn't
done without its evidence recorded (run ids, results files) in
`docs/REFERENCE_APP.md` or the PR.

### W1 — iOS production host (G1). **Current.**

Parity with the Android production host, for iOS.

1. **I0 Feasibility measurement.** Can the iOS host compile and link from
   **published** artifacts alone, as `#84` asked for Android?
   *Done when:* a throwaway KMP module depends only on Maven Central
   `dev.keliver:*:0.3.3` + Zipline 1.22, compiles the host Kotlin and links a
   simulator framework, with the dependency list recorded. Measured locally,
   with a disposable `GRADLE_USER_HOME` and `KONAN_DATA_DIR`.
2. **I1 Template + scaffolder `keliver-new-ios-host.sh`.** Writes
   `host-ios/`:
   - a Gradle KMP framework module (Maven Central only);
   - an Xcode app shell (SwiftUI wrapper, an `embedAndSign` build phase);
   - the public key embedded at scaffold time, as a committed file, not read
     from the store at build time;
   - production-only: no key means no fetch and a refusal screen; never
     `NO_SIGNATURE_CHECKS`;
   - the bundle server and API base as build settings;
   - lookup-first with short timeouts;
   - an offline start from Zipline's pinned cache (the `AcceptCachedBundle`
     pattern);
   - one cache per key;
   - same-origin manifest URLs only;
   - `HostHttp` via `NSURLSession`, bound only when an API base is set, with
     the same path rules as `OkHttpHostHttp`;
   - **a real SQLite `HostSqlDriver`**. The spike's `IosSqlHost` is in memory,
     so guest data doesn't survive a restart; the Android host uses real
     SQLite;
   - **network images**. The spike's coil `ImageLoader` has no network fetcher
     on iOS;
   - all-or-nothing writes.

   *Done when:* a self-test (refusals byte-identical, output complete, no
   placeholders) passes, and `--build` links the framework and builds the app
   for the simulator with `xcodebuild`.
3. **I2 CI on a hosted macOS runner.** A new workflow, triggered on
   `pull_request` paths, since a new workflow can't be dispatched before it is
   on `main`. It:
   - bootstraps the reference app;
   - publishes signed v1 and v2;
   - scaffolds and builds `host-ios`;
   - boots a simulator and runs iOS P1–P7 (install, launch, `log stream` for
     `KeliverHost:` lines, view state through accessibility or a screenshot);
   - checks a foreign key is refused, the offline start, and no empty-URL
     load.

   *Done when:* the run is green and its evidence is kept in the repository.
4. **I3 Retire the spike's unsafe fallback.** `portal-device-ios` must never
   load with `NO_SIGNATURE_CHECKS` in prod mode: refuse, as U22 does on
   Android. *Done when:* a test or measured run shows the refusal.
5. **I4 Docs + release.** Adopter guide "Ship to production" for iOS, and
   `DEVICE_HOST.md` §iOS. Ship in tools 0.3.7, which **needs the owner's
   approval**.

### W2 — Embed in an existing app (G2)

A small, documented host API on both platforms:
- Android: a `KeliverScreen` view/Composable plus a host object built once per
  app (trust, bundle server, capabilities, cache), owned by the app's
  `Application`;
- iOS: a `UIViewController` factory.

The scaffolders gain `--embed`, writing a library module instead of an app.
*Done when:* a plain "existing" Android app and a plain SwiftUI app each show a
signed Keliver screen next to native screens, on CI.

#### W2 design (draft 2026-10-09; from a read-only survey of the templates)

**One set of host sources for both modes.** Today all host logic is inside the
app shell: Android `MainActivity.kt` (trust, lookup, floor, Zipline load,
rendering, via `setContent` and `lifecycleScope`), and iOS
`MainViewController.kt` (`startHost()` runs in each view controller).
- **Android.** Split into:
  - `KeliverHost.kt`: the host object. It owns one scope, one
    `TreehouseAppFactory`/`TreehouseApp`, one SQL host, one HTTP host and one
    `ImageLoader`. `start()` is idempotent; `state: StateFlow` is
    Loading / Message / Running.
  - `KeliverScreen.kt`: a `@Composable KeliverScreen(host, modifier)`.
  - `KeliverView.kt`: an `AbstractComposeView`, for apps built on Views.

  The scaffolded app gets a `HostApp : Application` holding the host, and a
  ~5-line `MainActivity`. These files are byte-identical between the app and
  `--embed`; the self-tests check that with `cmp`.
- **iOS.** A `public object Keliver { start(); viewController() }` (not named
  `KeliverHost`, which is the framework's module name), plus a Swift
  `KeliverScreen: UIViewControllerRepresentable`. `MainViewController()`
  becomes an alias.
- **Process-wide state.** One host per process per key. A second `create()`
  for the same cache fails loudly; it never opens a second loader on the same
  Zipline cache. A configuration change no longer reloads anything.
- **W4 state keeps its keys.** The floor, install id and last-good URL keep
  their store and names in both modes (the harnesses write them directly).
  The host version comes from `PackageManager` (`longVersionCode`), because a
  library's `BuildConfig` has no `VERSION_CODE`.
- **Trust root.** The key asset moves to `assets/keliver/portal_ed25519.pub`
  in both modes. An app asset with the same name would silently override a
  library's, and that file is the trust root.

**`--embed`.**
- **Android:** `keliver-new-production-host.sh --embed --into <existing-root>
  [--module keliver-host]` writes a `com.android.library` module.
  - Plugins are requested without versions, so the adopter's apply.
  - Configuration goes in `keliver.properties` (a subproject's
    `gradle.properties` is not read), with `-Pkeliver.*` overrides. App mode
    uses the same file.
  - The manifest has INTERNET only; there are consumer R8 rules.
  - It prints the `include` / `implementation` / `Application` lines and edits
    no adopter file.
  - It refuses: no settings file, the module dir exists, `--application-id`.
  - It warns: no Zipline or compose plugin, or no Kotlin 2.2.x, found in the
    adopter's build.
- **iOS:** `keliver-new-ios-host.sh --embed --into <dir>` writes
  `keliver-host-ios/`: the Gradle build, `src/iosMain`, a wrapper copy,
  `KeliverScreen.swift` and `EMBED.md`.
  - `EMBED.md` documents the Xcode edits: the run-script phase,
    `ENABLE_USER_SCRIPT_SANDBOXING=NO`, `-lsqlite3`, and
    `CADisableMinimumFrameDurationOnPhone`.
  - `checkReleaseUrls` reads the Info.plist from Xcode's environment.
  - It refuses a project that already names a product or module KeliverHost.
  - An XCFramework route is documented, not proven.

**CI proof.**
- **Fixtures:** `reference/embed/{android,ios}`. Each is a plain app plus an
  `embed.diff` with exactly the documented edits, like the inventory app's
  `hand-edits.diff`.
- **Android** (reusing W3's static HTTPS server):
  - X1: one UI dump has a native view and the guest screen, within the
    `KeliverView`'s bounds;
  - X2: a signed load;
  - X3: interaction inside the embedded view;
  - X4: native navigation, rotation and a second screen leave exactly one
    lookup and one load;
  - X5: the floor is stored;
  - X6: with a foreign key the Keliver slot refuses, while native UI and the
    process survive.
- **iOS** (OCR):
  - I1: a native `Text` and the guest screen in one screenshot;
  - I2: a signed load;
  - I3: a native push and pop, still one load;
  - I4: the floor is stored;
  - I5: with a foreign key, native UI survives.

**Steps:**
1. **W2.1** The Android refactor, with no behaviour change. The device run is
   unchanged (S2–S15), plus a rotation row showing a single lookup.
2. **W2.2** The iOS refactor. `ios.sh` is unchanged.
3. **W2.3** Android `--embed`, its self-test and the fixture build.
4. **W2.4** Android device proof X1–X6.
5. **W2.5** iOS `--embed`, its self-test and the SwiftUI fixture build.
6. **W2.6** iOS simulator proof I1–I5.
7. **W2.7** R8: the fixture `assembleRelease` with minify, then X1–X2.
8. **W2.8** Docs, an independent review, and Status. It ships in a tools
   release only with the owner's approval.

**Risks:**
- **Biggest: the compiler plugin.** The embedded module is compiled by the
  adopter's Kotlin, and the `app.cash.zipline` compiler plugin pins it
  (Zipline 1.22.0 ↔ Kotlin 2.2.0). An app on another Kotlin can't embed the
  source module. The way out is a prebuilt, published host artifact, which is
  a Maven publication and needs the owner's approval. That is recorded as the
  follow-up, not done in W2.
- **Version coupling.** AGP, Compose (androidx 1.8.x) and OkHttp/Coil can be
  coupled to or force-upgraded by the adopter's own versions. CI proves one
  combination.
- **The theme.** It is Material 1, so the adopter's Material 3 theme does not
  reach Keliver screens.
- **iOS.** An app that already embeds a Kotlin framework gets two Kotlin
  runtimes.
- **Unverified.** Several `TreehouseContent`s on one `TreehouseApp` (X4 tests
  it).
- **Backups.** The library can't force `allowBackup=false`: document
  `dataExtractionRules`.

### W3 — Headless publishing and static distribution (G3, G4)

1. **A `keliver publish` CLI** that runs without the relay: compile, verify the
   signature (the relay's `publishedSignatureProblem`), write a **static
   layout**:
   ```
   bundles/v<N>/…
   bundles/index.json   ← signed entries: version, widgetVersion, caps, channel, minHost/maxHost, sequence
   ```
   For CI, the key comes from an env var or a file, never printed.
2. **The hosts read `index.json`** and filter it themselves, so any static host
   or CDN works (S3, GCS, GitHub Pages, nginx). Keep
   `/bundles/latest?…` working on the relay.
3. **A GitHub Actions recipe** in the docs, plus a CI check that publishes to a
   local static server over **HTTPS** (self-signed CA installed in the
   emulator/simulator).

*Done when:* the reference app's P1–P7 pass against a static HTTPS server fed
only by the CLI.

#### W3 design (2026-10-07, branch `feat/w3-static-publish`)

**Layout.** One directory, served as-is by any static host or CDN:

```
<out>/bundles/index.json
<out>/bundles/v1/manifest.zipline.json, *.zipline   (the compile task's output, copied unchanged)
<out>/bundles/v2/…
```

Every `v<N>/` is immutable once written. Only `index.json` changes.

**`index.json`, format 1:**

```json
{
  "format": 1,
  "entries": [
    {
      "sequence": 2,
      "version": 2,
      "channel": "stable",
      "widgetVersion": 1,
      "capabilities": ["host-sql@1"],
      "manifest": "v2/manifest.zipline.json",
      "manifestSha256": "<64 hex>",
      "createdAt": "2026-10-07T12:00:00Z"
    }
  ]
}
```

- `sequence` is strictly increasing across the index. It is the only order a
  host uses. `version` names the directory. The CLI sets both to
  `max(existing) + 1`. They are separate fields because W4's rollback and
  channel promotion add a new sequence without new code.
- `manifest` is a relative path under `bundles/`:
  - no leading `/`, no scheme, no `..`;
  - a host resolves it against `<server>/bundles/` and still checks that the
    result is on the server's origin.
- `channel` is optional; a missing channel means `stable`. W3 hosts take
  `stable` entries only, so a W4 `beta` entry can be published without
  reaching hosts built before channels existed.
- `constraints` (an object) is reserved for W4 (`minHostVersion` and
  `maxHostVersion`, rollout).
  - A host skips any entry whose `constraints` holds a key it doesn't know.
    That is fail-closed per entry, so a constraint added later can't be
    ignored by an older host.
  - Unknown top-level entry fields are ignored.
- A host refuses an index whose `format` isn't 1.

**Trust: per-entry binding by the manifest's own signature, not a signed
index.**
- What makes code run is the manifest. Zipline verifies its Ed25519
  signature, and through it every module's sha256, against the key built
  into the host. The host does that verification today, unchanged. A forged or
  edited index can only point at manifests this key signed.
- `manifestSha256` ties an entry's selection fields (`widgetVersion`,
  `capabilities`, `channel`) to the exact manifest the publisher meant:
  - the host's Zipline HTTP client checks the downloaded manifest bytes
    against it, in the same fetch Zipline loads from;
  - a mismatch fails the load. Zipline has no fallback to the cache after a
    network load fails, so the host then shows no bundle even with a cached
    one. Publishers must upload `v<N>/` before the index and never overwrite
    a `v<N>/`; the fallback is a W5 item.
  - This catches a CDN serving a manifest that doesn't belong to the entry
    (for example, a mixed or stale cache).
  - It is not a security boundary: the index is unsigned, so whoever can
    rewrite the index can rewrite the hash too.
- What a signed index would add is only **freshness and anti-rollback**: a
  server, or a path to it, replaying an older index or an older signed
  bundle.
  - Signing the index doesn't solve that by itself either. The host also
    needs a floor it remembers.
  - That is W4: the sequence goes into the **signed** manifest (Zipline's
    manifest `metadata`), and the host refuses a sequence below the highest
    it has run.
  - So W3 ships an unsigned index, and the rollback gap stays documented, as
    it is today.

**Host selection.**
- Fetch `<server>/bundles/index.json`, with a short timeout and no cache.
- Keep the entries where:
  - `format` is 1;
  - `channel` is `stable`;
  - there are no unknown `constraints`;
  - `widgetVersion` is at most the host's own (1);
  - every capability in `capabilities` is one the host provides;
  - the manifest path is valid.
- Take the highest `sequence`.
- Load that manifest with the sha256 check.
- Lookup-first and the offline cache start are unchanged:
  - a failed lookup, or no usable entry with the server unreachable, starts
    from Zipline's pinned cache as before;
  - with the server reachable and no compatible entry, it shows "No bundle"
    unless a bundle loaded before. The existing rule is kept.

**The relay keeps `/bundles/latest`** and also serves
`GET /bundles/index.json`, generated from its store's `v<N>/meta.json` and
manifests. A `v<N>` without a readable `meta.json` holding an integer
`widgetVersion` is left out, as `/bundles/latest` never picks one: the relay
writes meta.json last, so such a directory is an unfinished publish.
- A host built from the new templates fetches `index.json`.
- Only on **HTTP 404** for it (a relay from tools 0.3.7 or earlier) does it
  fall back to `/bundles/latest`.
- A network failure is not a 404, so it never adds a second timeout before
  the offline start.

**`keliver-publish`**, in the tools bundle's `bin/`:

```
keliver-publish [app-dir] --out <dir> [--public-key-file F] [--channel stable] [--skip-build] [--init]
```

`<dir>` must hold the **live** `bundles/index.json` and `bundles/v<N>/`: the
next version and sequence come from them. Without an index the CLI refuses
before building, unless `--init` marks the very first publish. CI must never
add `--init` itself.

1. It runs the app's `publishTask` from `keliver.portal.json` (as the relay
   does) with `KELIVER_TOOLS_BIN` set, so the existing signing block signs.
2. It verifies the manifest with the relay's `publishedSignatureProblem`
   against the **public** key. The key comes from:
   - `--public-key-file`;
   - else `KELIVER_PUBLIC_KEY_HEX`;
   - else the app's store, through `keliver-store-path.sh`.
3. It checks that every module named in the manifest is present with that
   sha256.
4. It copies to `bundles/.staging-*`, renames it to `v<N>`, then writes
   `index.json` through a temp file and an atomic rename. All of this happens
   under a lock file.
5. Anything unsigned, signed by another key, or incomplete is refused, and
   nothing is written. So are a malformed existing index (never overwritten;
   duplicate sequences or versions count as malformed) and an `--out` inside
   the compile output.
6. Exit status:
   - 0: published;
   - 2: usage;
   - 3: the build failed;
   - 4: refused, with nothing written (losing the lock race may create
     `bundles/.publish.lock`; that message carries no signing hint);
   - 5: an I/O error while writing. The index is unchanged or complete, but a
     `v<N>/` that no entry names may be left.
7. Uploading: `v<N>/` first, then `index.json`; never delete or overwrite a
   `v<N>/`; skip dotfiles; one publish at a time (a CI concurrency group). The
   adopter guide's GitHub Actions recipe does this.

**CI signing without a store.** The signing block gains
`KELIVER_SIGNING_KEY_FILE` (or `-Pkeliver.signingKeyFile`), a file holding
the private key's hex. It takes precedence over the store; in Actions it is a
secret written to a 0600 file. Neither the CLI nor the block prints it.

**Evidence.**
- Unit tests:
  - the CLI staging;
  - the relay's generated index;
  - the host selector and the manifest-pinning client. These are compiled
    from the template source itself, and the Android and iOS copies must be
    byte-identical.
- `keliver-publish-selftest.sh`.
- The reference app on CI, after the relay P-steps:
  - the CLI publishes into a static directory served over **HTTPS** by a
    self-signed CA. On the emulator the CA is put in the system store by
    root; on the simulator, with `simctl keychain add-root-cert`;
  - S2: v1 is loaded through the index;
  - S4: v2 after an edit;
  - S7: server down, so the host starts from the cache.

### W4 — Release controls (G5, G6, G7)

- **A monotonic sequence number** in a signed field. The host refuses any
  bundle whose sequence is below the highest it has run (rollback
  protection). **Rollback** is then republishing the older code as a *new*
  sequence (`keliver rollback --to vN`), the way Shorebird does it.
- **Channels:** set at host build time (default `stable`), stored in each index
  entry; `keliver publish --channel beta`; promote between channels.
- **Percentage rollout:** the index entry carries `rollout: 0–100`. The host
  buckets by a random install id it keeps, which is stable and not personal
  data.
- **Host compatibility:** the index entry carries `minHostVersion` and
  `maxHostVersion`, checked against the host build's own version.

*Done when:* CI shows each of these on the reference app: refusal of an older
sequence, rollback as a new sequence, a beta-only bundle not reaching stable, a
0% rollout not delivered, and a 100% rollout delivered.


#### W4 design (draft 2026-10-08; builds on the W3 index, format 1)

**What is signed, and what is only selection.** One field becomes signed: the
**sequence**. It goes into Zipline's manifest `metadata` as
`keliver.sequence`, a decimal string. `metadata` is inside the signed part of
the manifest, so Zipline's Ed25519 check covers it.

Channel, rollout and host-version constraints stay in the unsigned
`index.json`, as selection policy (as on Shorebird's server).
- **Why sign the sequence:** anti-rollback has to hold against whoever serves
  the bundles, so it needs a signed number and a floor the host remembers.
- **Why not sign the rest:** whoever controls the index can only choose among
  bundles this key signed, and the sequence floor stops them going backwards.
  The worst they can do is send newer, publisher-signed code to the wrong
  channel or rollout bucket. That is recorded, not prevented.
- **Signing the channel was considered.** It makes every promotion a re-sign
  (the private key in CI again) and doesn't stop a replay. Deferred unless an
  adopter needs it.

**How the sequence gets signed.** U31's doLast signing step makes this cheap.
The block reads `-Pkeliver.sequence` (else `KELIVER_PUBLISH_SEQUENCE`) and
writes `metadata["keliver.sequence"]` into the manifest **before** it signs.
The value is also a task input, so a new sequence re-signs.
- `keliver-publish` passes `max(index sequences) + 1`. After the build, it
  checks that the signed value equals the sequence it is about to write, and
  refuses otherwise. That includes a manifest with no sequence, once the index
  has sequenced entries.
- The relay's `/publish` passes its next version number (relay sequence =
  version) and checks the same way.
- Without the property, nothing changes. A development build, or a W3-era
  bundle, has no `keliver.sequence`.

**Host: anti-rollback floor.** Per key, on each platform.
- The host keeps `highestSequence` (SharedPreferences on Android,
  NSUserDefaults on iOS) under the same key id the cache name uses.
- **Network load.** The manifest-pinning HTTP client already sees the manifest
  bytes Zipline loads. It reads `metadata.keliver.sequence` and fails the
  download if the sequence is **below** the floor, or absent while the floor
  is above 0. That happens before Zipline's signature check, which is fine:
  this check only ever refuses.
- **Raising the floor.** Only after `codeLoadSuccess`, to the sequence of the
  bytes that load came from. Zipline has verified them by then, so an
  attacker can't raise the floor with an unsigned high number.
- **Cache start.** `AcceptCachedBundle` receives Zipline's verified pinned
  manifest. It refuses one whose sequence is below the floor.
- **Compatibility.** A host with floor 0 accepts unsequenced bundles, so an app
  can move to W4 hosts before W4 publishing.
- **Not covered:** a reinstall or "clear data" resets the floor.
  Replay-protection across reinstalls needs a server; recorded as a gap.

**Rollback = republish older code as a new sequence.**
`keliver-publish --republish <version>`:
- copies `v<version>/`'s modules into a new `v<M>/`;
- runs the signing block's new `keliverResign` task (inputs: that directory
  and the new sequence) to rewrite and sign the manifest. The private key
  stays inside Gradle, as for any publish;
- adds an entry with the new sequence.

Hosts above the old sequence take it, because it is newer.

*As built (W4.3):* the copy is made in a scratch directory outside the site,
and `keliverResign` refuses a directory beside an `index.json`, so a published
`v<N>/` is never rewritten. After the re-sign, anything but a changed sequence
and signature is refused. The entry keeps the original's capabilities, widget
version, constraints and channel (or `--channel`), plus `republishOf`. An
original entry that hosts would read differently once its fields are written
out explicitly is refused, because a republish must never loosen what the
original allowed.

**Channels.**
- **Host side:** a host is built with one channel. The scaffolders take
  `--channel` (default `stable`); the value goes into
  `HostConfig`/`BuildConfig`.
- **Publishing:** `keliver-publish --channel beta` publishes a new bundle to
  that channel only.
- **Promotion:** `keliver-publish --promote <sequence> --channel stable` adds a
  second index entry for the same manifest and sequence on another channel.
  The index's uniqueness rule becomes the pair (sequence, channel); W3
  currently refuses duplicate sequences, and that is relaxed.
- **The floor is per key, not per channel.** Moving a host to another channel
  is a host rebuild. Its floor can then block a lower sequence there, which is
  documented.

*As built (W4.4):*
- **A host takes its own channel and stable.** It picks the newest of both, so
  a beta host is never behind stable. A stable host takes stable only.
- **Index rule.** Uniqueness is (sequence, channel), and a sequence always
  names the same `v<N>/`.
- **Promotion** needs only the public key: nothing is built or signed. It
  copies the entry verbatim, constraints included (a gate never falls away),
  and adds `promotedFrom`.
- **Refusals.** It refuses when the sequence is already on that channel, or
  when that channel's hosts (its entries and stable's) already take a higher
  sequence that asks no more of them: no constraints, no extra capability, no
  newer widget protocol. None of them would pick the promoted one; use
  `--republish`. A higher entry that some of them skip does not block it.
- **Republish after promotion.** Republishing a version that is on several
  channels needs `--channel`: rolling back only the channel it was first
  published on would leave the others on the bad sequence.
- **Host log.** The lookup line names the picked entry's channel and the
  host's own.

**Percentage rollout.**
- **In the index:** an entry may carry `constraints.rollout` (0–100).
- **Install id:** the host keeps a random install id (UUID, generated once,
  local only, never sent anywhere).
- **Bucket:** the first 4 bytes of `sha256(installId + ":" + sequence)` as an
  unsigned int, mod 100.
- **Eligibility:** an entry is eligible when bucket < rollout. A host outside
  the bucket takes the highest eligible older entry.
- **Raising and halting:**
  - Raising the rollout is an index edit (`keliver-publish --rollout <seq> <pct>`).
  - Halting it (setting 0) stops new hosts. Hosts that already ran the bundle
    keep it, because of the floor, as with Shorebird.

**Host-version gates.**
- **In the index:** `constraints.minHostVersion` and
  `constraints.maxHostVersion` are integers.
- **What they compare against:** the host build's own version
  (`versionCode` on Android, `CFBundleVersion` on iOS, which must be an
  integer). The scaffolders add `--host-version`, or read the app's version.
- **Unknown constraints:** a W3 host already skips an entry whose constraint
  key it doesn't know (fail-closed). So W4 entries never reach W3 hosts
  wrongly.

*As built (W4.5):*
- **Who is gated.** A rollout gates only sequences above the host's floor, so
  lowering or halting it never takes a bundle away from a host that ran it.
  Host-version gates hold below the floor too, because they are about what the
  code needs. A host whose version isn't an integer skips gated entries.
- **Publishing.** `--rollout`, `--min-host-version` and `--max-host-version`
  set constraints on a publish, republish or promotion. They are set on top of
  copied constraints, never dropped.
- **Changing a rollout.** `--set-rollout <sequence> --rollout <percent>
  [--channel]` edits the index only and needs no key. It refuses an entry
  with no constraints, because pre-W4.5 hosts skip constrained entries. On a
  non-stable channel, it warns when stable's entry at that sequence still
  admits more.
- **Republish and promotion.** Host-version gates are copied, and may be
  narrowed but never widened (refused). A republish drops the rollout (a new
  sequence draws new buckets) unless `--rollout` is given.
- **Host log.** The lookup line names the host version.

**Steps, each with its own evidence:**

1. **W4.1** Signed sequence.
   - Signing-block metadata.
   - CLI and relay check that the signed sequence is the one written.
   - Unit tests.
   - Publish-target self-test (`--build`): the sequence is signed, and an
     edited sequence fails verification.
2. **W4.2** Host floor on both templates. CI:
   - an older signed sequence is refused, over the network and from the cache;
   - the floor is raised only after success.
3. **W4.3** `--republish` (rollback). CI: a rollback as a new sequence reaches
   a host that ran the newer one.
4. **W4.4** Channels and promotion. CI:
   - a beta-only bundle does not reach a stable host;
   - after promotion, it does.
5. **W4.5** Rollout and host-version gates. CI:
   - a 0% rollout is not delivered;
   - a 100% rollout is delivered;
   - an entry with `minHostVersion` above the host is skipped.

*Done when:* the reference app's CI shows all of the above on Android and iOS.
That is the plan's W4 "done when", unchanged.

### W5 — Update API (G8)

The host exposes:
- `checkForUpdate()` and `currentBundle()`;
- an update policy (at launch, on resume, periodic) and when to apply (next
  launch or immediately);
- events: downloaded, applied, failed, refused (with the reason).

- **Fall back to the last good bundle.** When the lookup names a bundle that
  then fails to load (a download error, a `manifestSha256` mismatch, a
  missing `v<N>/`) and a bundle loaded before, restart from Zipline's verified
  cache (`AcceptCachedBundle`) instead of showing nothing. Zipline 1.22 has no
  such fallback itself. Found by W3's review, from S6.

*Done when:* unit tests plus one CI scenario (an update applied on resume)
pass.

#### W5 design (draft 2026-10-10; from Treehouse's and Zipline 1.22's source)

**What the libraries allow.**
- `TreehouseApp.restart()` builds a new `ZiplineLoader` on the SAME factory,
  cache and HTTP client, and reads the spec's `manifestUrl` flow and
  `freshnessChecker` again. So both an update and a fallback reuse the one app
  and its one cache: no second loader on a cache (the W2 claim stays true).
- `ZiplineLoader.load(flow)`: first `loadFromLocal` (the pinned cache, used
  only if the freshness checker accepts it); if that loads, the flow ENDS and
  later URLs are ignored. Otherwise each URL the flow emits is loaded from the
  network: an unchanged manifest is skipped; a failure sends `Failure`, unpins
  that manifest and leaves the running code alone; a success pins it, and
  Treehouse swaps the code session in.

**Host API (both platforms; Android `KeliverHost`, iOS `Keliver`).**
- `currentBundle: StateFlow<KeliverBundle?>`: sequence, manifest URL, channel,
  and whether it came from the network or the cache.
- `checkForUpdate(): KeliverUpdateCheck`: runs the lookup now (same index,
  channel, constraints and floor rules) and says `UpToDate`,
  `Available(sequence)` or `Failed(reason)`. With the `IMMEDIATELY` apply mode
  it also applies it.
- `events: SharedFlow<KeliverUpdateEvent>`: `Downloaded`/`Applied(sequence)`,
  `Failed(sequence, reason)`, `Refused(sequence, reason)` (the floor, a sha256
  mismatch, a signature), `FellBack(toSequence)`.
- `KeliverUpdatePolicy(checkOnResume, periodicMinutes, apply)`: `apply` is
  `NEXT_LAUNCH` (the default: the next process start's lookup takes it, as
  today) or `IMMEDIATELY`. On resume: Android `ProcessLifecycleOwner` is NOT
  added (a new dependency); the standalone host's activity calls
  `checkForUpdate()` in `onResume`, and an embedding app calls it where it
  likes. iOS observes `UIApplicationWillEnterForegroundNotification`.

**Applying.** The pinning HTTP client and the freshness checker become
switchable (one object each, owned by the host). After a network start:
point the pinning client at the new manifest and sha256, emit its URL; Zipline
loads it while the old code runs; success swaps it in (floor raised as
today), failure leaves the old one and emits `Failed`. After a cache start
(the flow has ended): set the not-fresh checker, emit, `restart()`.

**Fall back to the last good bundle.** When the start's network load fails
before any code ran and a last-good URL exists for this key: switch to
`AcceptCachedBundle` (the floor still holds), point the pinning client at the
last-good URL with no sha256, `restart()`. Zipline verifies the cached
manifest against the key again. Only if that also fails: "Bundle did not
load". Open question for CI: whether Zipline's unpin of the failed manifest
can remove files the last-good one shares (it pins per file).

**CI scenarios.** U1 (both platforms): v_n running; publish v_{n+1}; the app
goes to the background and back; the screen shows the new title with the same
process, `Applied` logged, floor raised. U2: the lookup names a bundle whose
`manifestSha256` is wrong (S6) after a good load: the host falls back to the
cached last good, which renders. Unit tests: the policy and event logic, the
switchable pinning client.

**Steps.** W5.1 the switchable client and checker plus the fallback (U2);
W5.2 `checkForUpdate`/`currentBundle`/events/policy (U1); W5.3 docs, an
independent review, Status.

### W6 — Visibility (G9)

- **A reporter interface** on the host. Events: install id, bundle sequence,
  channel, outcome. No default backend; a documented example posts to a URL.
- **The current bundle sequence set as a crash key** (Crashlytics/Sentry
  examples).
- Optionally, **a relay `/report` endpoint** that shows counts in the portal.

*Done when:* events arrive in CI from both platforms.

#### W6 design (draft 2026-10-11)

**What a host reports.** One small record per outcome, JSON:
`{"installId", "channel", "hostVersion", "sequence", "source", "outcome", "detail", "platform"}`.
- **Outcomes:** `loaded` (the start's bundle ran), `fell-back` (W5),
  `update-applied`, `update-failed`, `not-loaded` ("Bundle did not load"),
  `no-bundle`, `refused` (no valid key or server).
- **`source`:** `network` or `cache`.
- **`detail`:** a short reason, at most 300 characters (an exception message,
  never a body).

The install id is the random one W4.5 made for rollouts. Nothing else
identifies a user or device.

**Where it goes.** Nowhere by default.
- **In code:** `KeliverHost.reports` (Android, a `SharedFlow`) and
  `Keliver.onReport` (iOS) hand every record to the app, which can forward it
  to its own analytics. That is also the place to set the crash key: the
  sequence goes in as `keliver_sequence` (Crashlytics `setCustomKey`, Sentry
  `setTag`), and the docs show both.
- **The documented example**, built in and off unless set:
  `keliver.reportUrl` (Android) or `REPORT_URL` (iOS `HostConfig.kt`). The host
  POSTs each record there, best effort: no retries, no queue, never blocking or
  failing a load. It follows the same URL rules and release `https://` check
  as the bundle server.
- **No relay endpoint in W6.** The static route has no relay, and a portal
  view of the counts is a later, optional step.

**CI proof (V1, both platforms).** The W5 build (U1) also sets the report URL
to the static test server, whose `POST /report` writes each body to a log.
After U1, the log holds `loaded` (sequence 8, network) and `update-applied`
(sequence 9) from that install, with `platform` `android` or `ios`.

**Steps.** W6.1 reports and the example sender on both hosts, with V1. W6.2
docs (including the crash-key examples), an independent review, Status.

### W7 — Proof (G11)

Physical Android (arm64), a physical iPhone, a release-signed APK and IPA, and
HTTPS. Then one external adopter. These need the owner's hardware or accounts:
**ask, don't assume.**

### W8 — Key custody and rotation (G10)

- Hosts trust a **set** of keys (current and next). Publishing signs with the
  current key.
- A documented rotation: ship a host trusting both keys, switch the signing
  key, then drop the old one in a later host.
- CI key storage guidance.
- Fix U30.

## Order and dependencies

W1 (iOS parity) → W3 (headless publish + static index, which W4 builds on) →
W4 → W2 → W5 → W6. W7 and W8 run alongside whenever hardware or decisions
allow. Releases go 0.3.7 (iOS host), 0.3.8 (CLI + static), and so on, each
**only with the owner's approval**.

## Status

| step | state | evidence / where |
|---|---|---|
| plan written | done 2026-10-07 | this file; branch `feat/ios-production-host` |
| W1 I0 iOS feasibility from Maven Central | **done 2026-10-07**: the production host links for the simulator; 54 `dev.keliver` artifacts, all 0.3.3 from Maven Central | `docs/superpowers/evidence/ios-host-i0/` |
| W1 I1 scaffolder | **built, measured locally 2026-10-07.** `keliver-new-ios-host.sh` + `templates/ios-host/`; self-test 38/0, and 43/0 with `--build` (xcodebuild). The scaffolded host loads signed v1, then v2 after an edit (Inventory → Stockroom); the real SQLite `IosSqlHost` passes 5/5. | `docs/superpowers/evidence/ios-host-i1/` |
| W1 I2 iOS CI | **done 2026-10-07.** `ios-host.yml` run `37597390396`, then `37602257459` after the independent review's fixes (macos-15, Xcode 16.4, iOS 26.2): self-test 48/0, P1/P2/P4–P7 30/0. Review: nothing blocking, all points fixed. | `docs/superpowers/evidence/ios-host-ci-37597390396/`; run `37602257459` |
| W1 I3 spike fallback | **deferred.** `portal-device-ios` is built only from Keliver source, and no CI job compiles it. It never reaches adopters, who get the scaffolded host. Fix it when that module is next built. | — |
| W1 I4 docs + 0.3.7 | **RELEASED 2026-10-07: tools 0.3.7** (candidate 4, with the U31 fix). Tag `portal-tools-v0.3.7` → `aaa03478`; zip `75ce0928…`, checked at the draft digest, by download-back and at the public URL. Candidates 1–3 superseded. | `docs/RELEASE_NOTES_TOOLS_0.3.7.md`; PRs #89, #91 (not merged) |
| W3 design | **done 2026-10-07**: a static `bundles/index.json` (format 1). Each entry is bound by its manifest's own signature plus `manifestSha256`; the index is unsigned. Anti-rollback is deferred to W4. | "W3 design" above |
| W3 `keliver-publish` + index-reading hosts | **built, CI green 2026-10-07** on PR #90 (`feat/w3-static-publish`, stacked on #88). The CLI verifies the signature and every module's sha256, then writes `v<N>/` and an atomic `index.json`. It refuses anything unsigned, foreign or incomplete and writes nothing (exit codes in the W3 design). The relay also serves `/bundles/index.json`. Both host templates read the index, pin the manifest's sha256, and fall back to `/bundles/latest` only on a 404. `KELIVER_SIGNING_KEY_FILE` covers CI signing. Tests before the review: `StaticPublishTest` 12, `BundleIndexTest` 7, `keliver-publish-selftest.sh` 16/0, the iOS host self-test with `xcodebuild` 49/0 (CI). The Android host self-test with a build, 40/0, was local and is in no CI evidence. | PR #90 |
| W3 static HTTPS on CI | **green 2026-10-07**. No relay; a static HTTPS server is fed only by the CLI, with a throwaway CA in the emulator's system store and the simulator's keychain. S2 (v1 through the index), S4 (v2), S5 (CLI refuses a foreign key), S6 (wrong sha256, nothing loads), S7 (offline from the cache). Android `reference-app.yml` run 37629943998: prepare 18/0, device 54/0. iOS `ios-host.yml` run 37629943934: self-test 49/0, `ios.sh` 48/0. | `docs/superpowers/evidence/w3-android-ci-37629943998/`, `w3-ios-ci-37629943934/` |
| W3 independent review | **done 2026-10-07; all should-fix points fixed or recorded** (PR #90). B1: the recipe republished v1 at sequence 1 from an empty checkout. Now the CLI refuses an out dir with no index unless `--init` (checked before building), and the guide's recipe downloads the live `bundles/` first, uses a concurrency group, uploads `v<N>/` before the index with no deletes, and removes `.gradle/` (U31). S1: no fallback to the cache after a failed load is documented (guide, `DEVICE_HOST`); the fallback itself is a W5 item. S2: the relay's index skips a `v<N>` without usable `meta.json`. S4: the lock refusal has its own message; exit codes are documented accurately; the production host's `gradle.properties` no longer says a static server isn't enough. Nits done: one app dir in the wrapper, `--out` inside the output refused, duplicate sequences or versions refused, S5 wording, head runs named. S3 (the key in `.gradle/`) is U31, fixed separately. Tests after the fixes: `StaticPublishTest` 16/0, `BundleIndexTest` 7/0, `PublishSignatureTest` 7/0, `keliver-publish-selftest.sh` 18/0 (local). **CI on the post-review head `5f2807569`:** `reference-app.yml` 37655639736 (prepare 18/0, publish self-test 18/0, device 54/0 including S2/S4–S7) and `ios-host.yml` 37655639526 (self-test 49/0, `ios.sh` 48/0). | PR #90 |
| W3 known, not done | Two 10 s timeouts when the index is a 404 and `/bundles/latest` is slow. iOS `timeoutInterval` is an idle timeout. No size limit on the index body. The docs' "same origin" is not a boundary, because Zipline's downloads follow redirects (integrity rests on the signatures). `pickFromIndex` accepts quoted numbers. Capability filtering is unit-tested only; the device checks publish with no capabilities. There is no device check of a new host against a new relay's `/bundles/index.json`. Not in a released tools bundle: that needs 0.3.8, with approval. | — |
| W3 on tools 0.3.7 | **merged onto the released 0.3.7 line 2026-10-08**, through `chore/reference-app-0.3.7` (#92: the reference app built from the published 0.3.7 zip and its own `bin/` scaffolders). The signing block keeps U31's design (signs in a `doLast`; the key is never a task input, an argument or a log line) and adds W3's CI key file (`-Pkeliver.signingKeyFile` / `KELIVER_SIGNING_KEY_FILE`). `keliver-new-publish-target.sh` upgrades a 0.3.7 block (plain swap) as well as a 0.3.6 one (U31 advice). **Ready for a 0.3.8 candidate, which ships only with the owner's approval.** CI after the merge, at `c777828c`: `reference-app.yml` 37697129379 (prepare 18/0, publish self-test 18/0, device 55/0, S-rows included) and `ios-host.yml` 37697129399 (`ios.sh` 48/0), with hosts from `scripts/` (`KELIVER_SCAFFOLD_FROM=repo`, labelled). `portal-tools.yml` 37675743198 at `5f3ab137` (nothing under `scripts/` or `portal-relay` changed after it): publish-target self-test 35/0 with `--build` (U31 and the CI key file) and 25/0 from the zip. **Review of the merge:** two independent review agents stalled before reporting, so the parent session reviewed it instead. That covered the signing block's U31 properties, the byte-exact legacy blocks, the upgrade loop, the scaffold-from switch and its labels, and conflict leftovers. One gap was found and fixed: the "published-only since #92" lines needed the W3 exception. | PR #90 (base #92) |
| W4 design | **drafted 2026-10-08**, "W4 design" above: the signed `metadata.keliver.sequence`; a host floor, checked on the network and on the cache start; `--republish` for rollback; channels and promotion; rollout by install-id bucket; host-version gates. Zipline 1.22 confirmed: `metadata` is in the signed part, and `FreshnessChecker.isFresh` receives the verified manifest. | this file |
| W4.1 signed sequence | **built 2026-10-08; independently reviewed, findings fixed** (PR #93, stacked on #90). The signing block writes `metadata["keliver.sequence"]` from `-Pkeliver.sequence` / `KELIVER_PUBLISH_SEQUENCE` into the manifest (Zipline's own `copy`), then signs, so the signature covers it; a malformed sequence fails the build, signed or not. `keliver-publish` passes the next sequence to the build: past every index entry AND every signed sequence already in `v<N>/` (so an `--init` over downloaded bundles can't restart at 1). It refuses a manifest signed for another sequence, or for none (the message names the scaffolder upgrade), and checks again under the lock. The relay's `/publish` passes its next version and refuses a mismatch; an unsequenced bundle is stored with a warning only while no bundle in the store is sequenced, and refused after. Tests: `StaticPublishTest` 21/0 (wrong, missing, edited sequences; `--init` over signed bundles; a race caught under the lock), `keliver-publish-selftest.sh` 20/0 (a replayed older sequence; a manifest without metadata). The device harnesses assert each published manifest's signed sequence, and S5 now requires the key refusal itself ("does not verify"). Review notes kept: the unreleased W3 block is not in `legacy/` (fine while unreleased). | PR #93 |
| W4.2 host rollback floor | **built 2026-10-08; independently reviewed, findings fixed** (PR #93). Shared `BundleIndex.kt`: `manifestSequence`, `rollbackProblem`, and the manifest-guard client refusing a network manifest below the floor (or unsequenced once the floor is above 0), for index and relay lookups alike; the floor is read when the manifest arrives (a restart sees a raised floor). Each host keeps the floor per key (Android SharedPreferences, written with `commit()`; iOS NSUserDefaults), raises it in `codeLoadSuccess` from Zipline's verified manifest, and checks it on the cache start AND on the cache start's network fallback. **The review found that fallback unguarded on Android** (a server failing the lookup on purpose could then serve an older signed manifest); fixed, and S9b now covers it. Unit tests: `BundleIndexTest` 10/0. CI before the review fixes: Android S8/S9 passed (reference-app 37740967962, 37742935691); iOS S8 passed but **iOS S9 failed** (ios-host 37742935620): the harness wrote the floor into the simulator user's defaults, not the app container's; fixed. Device checks: S8 (v1 re-served after v2: refused), S9 (stored floor above the cached v2, offline: cache refused), S9b (same, server up but failing the lookup: the network fallback refused). Docs: `DEVICE_HOST.md` §2/§3 (what a floor of 0 does not protect; one key, one sequence space; a refused newest entry shows no bundle), guide, tools README. | PR #93 |
| W4.1 + W4.2 CI (after both reviews) | **green 2026-10-08 at `0fae3739`.** `reference-app.yml` 37749229561: prepare 18/0, publish self-test 20/0, device 66/0, with W4.1 (v1 and v2 signed for sequences 1 and 2) and S8, S9 and S9b on Android. `ios-host.yml` 37749229400: self-test 50/0, `ios.sh` 60/0, with S8, S9 and S9b on iOS. `portal-tools.yml` 37749222637: the production-host scaffolder 41/0 (`--build`) and 36/0 (zip), the iOS host 44/0 twice, the publish target 38/0 (`--build`: the sequence signed, an edit breaking the signature, a malformed one failing) and 25/0. | PR #93 |
| W4.3 republish (rollback) | **built 2026-10-09; independently reviewed, findings fixed** (PR #93, `42dfbcd3` + `f54f5ca5`). `keliver-publish --republish <v>` copies `v<v>/`'s modules to a scratch directory; the signing block's new `keliverResign` task signs the copy for the next sequence (the key found exactly as for a build: never an input, argument or log line; it refuses a directory beside an `index.json`, so a published `v<N>/` is never rewritten); anything but a changed sequence and signature is refused; the copy becomes the next `v<M>/` with `republishOf`. The review found the entry dropping the original's constraints (fail-open), fields filled in that hosts would have rejected, a gradlew that cannot start reported as exit 5, and a vacuous usage check; all fixed. Tests: `StaticPublishTest`, `keliver-publish-selftest.sh` (a no-gradlew exit 3), publish-target `--build` (the real `keliverResign`: verifies, only the sequence changed, nothing compiled, no key in the log, no task inputs, a live `v<N>/` refused). Device S10 on both platforms: after v2 ran (floor 2), v1 republished as v3 loads, the screen reads "Depot" again, floor 2 -> 3. | PR #93 |
| W4.4 channels and promotion | **built 2026-10-09; independently reviewed, findings fixed** (`0256c768` + `2d94da76`). Hosts are built for one channel (`--channel` on both scaffolders, default `stable`; Android `BuildConfig.KELIVER_CHANNEL`, iOS `HostConfig.CHANNEL`, both checked at build time) and take their own channel's entries and stable's. The index allows one sequence on several channels, always naming the same `v<N>/` and manifest; a malformed channel is refused. `--promote <sequence> --channel <name>` adds a second entry for the same bundle with no build and no private key, copying constraints. The review found a republish of a promoted version rolling back only its first channel (now needs `--channel`), a "higher sequence" refusal that blocked a fix for hosts the higher entry gates out (now only an entry asking no more of the hosts blocks it), and a log naming the host's channel instead of the picked entry's; all fixed. Device S11 (beta-only v4: the stable host keeps v3) and S12 (promoted: the stable entry loads, floor 3 -> 4). | PR #93 |
| W4.5 rollout and host-version gates | **built 2026-10-09; independently reviewed, findings fixed** (`dfd3e2a7` + `e8c26c57`). Hosts understand `rollout` (bucket = sha256("<installId>:<sequence>")[0..4] mod 100; a random install id kept on the device, never sent; known-answer vectors checked against python) and `minHostVersion`/`maxHostVersion` (Android `versionCode`, iOS `CFBundleVersion`; unknown version skips gated entries). A rollout gates only sequences above the floor, so a halt keeps hosts that ran it. Publisher: `--rollout`, `--min-host-version`, `--max-host-version` on publish, republish and promote; `--set-rollout <sequence> --rollout <percent>` edits the index only, with no key. **The review found one blocking bug**: a republish or promotion could widen a copied host-version gate; now refused. Also fixed: `--set-rollout` on an unconstrained live entry (pre-W4.5 hosts would lose it; refused), a halt on beta that stable's twin entry undoes (warned), a republish copying a rollout whose buckets no longer apply (dropped). Device S13 (0%: not delivered), S14 (100%: delivered, floor 4 -> 5), S14b (halted: the host that ran it keeps it), S15 (`minHostVersion` 999 above host version 1: skipped). | PR #93 |
| W4.3 CI, first push | **green 2026-10-09 at `42dfbcd3`** (before the reviews' fixes). `reference-app.yml` 37894671231: publish self-test 30/0, device 73/0 with S10 on Android. `ios-host.yml` 37894671308: self-test 50/0, `ios.sh` 67/0 with S10 on iOS. `portal-tools.yml` 37894675395: publish target 40/0 (`--build`, the real `keliverResign`), hosts 41/0, 36/0, 44/0 twice. | PR #93 |
| W4.3 to W4.5 CI (after all three reviews) | **green 2026-10-09 at `e8c26c57`.** `reference-app.yml` 37902855439: prepare 18/0, publish self-test 40/0, device 98/0, with S8 to S15 on Android. `ios-host.yml` 37902855353: self-test 54/0, `ios.sh` 92/0, with S8 to S15 on iOS. `portal-tools.yml` 37902876551: publish target 41/0 (`--build`, the real `keliverResign`) and 25/0, the production-host scaffolder 45/0 and 40/0, the iOS host 48/0 twice, key permissions 63/0. (37902859854 was a dispatch with a mistyped commit, cancelled.) | PR #93 |
| tools 0.3.8 candidate 1 (W3 + W4) | **verified 2026-10-09; NOT tagged, awaiting the owner's approval** (PR #94, `release/portal-tools-0.3.8`).<br>• **Identities:** source `c0e71108`; zip `7ea7f11d9fdebce84861cf9224d324c15d00451ac175d84f3cd51b683c43bc14` (90,532,260 bytes); APK `afa9dadd…`.<br>• **Build** 37932744136 (artifact `11617444772`), with the packaged `keliver-publish` self-test 40/0. **Device run** 37935499926: 19/0 and 28/0.<br>• **Local check:** hashes, `VERSION.json`, no key in the APK, the templates and scripts byte-identical, the packaged self-test 40/0 again.<br>• **`ios.sh` on the candidate zip:** run 1 91/1, with the one FAIL in the harness's P2 expectation (a 0.3.8 relay serves the index), fixed in `a60624f0`; run 2 **92/0** (P1–P7, S2–S15 with the zip's own `keliver-publish`).<br>• **Step 3:** clean.<br>The record is in `docs/RELEASE_NOTES_TOOLS_0.3.8.md`. | PR #94 |
| W2.1–W2.7 (embed) | **built 2026-10-09; green on CI at `f7ad8415`; the independent review is not done yet** (PR #95, stacked on #94).<br>• **Android:** `KeliverHost` (one per process, owned by an `Application`), `KeliverScreen`/`KeliverView`; `--embed` writes a library module with the same Kotlin, byte-identical (self-test 58/0). The key asset moves under `assets/keliver/`.<br>• **iOS:** a `Keliver` object; `--embed` writes the framework's Gradle build, `KeliverScreen.swift` and `EMBED.md` (self-test 67/0 on CI).<br>• **Fixtures:** `reference/embed/{android,ios}`, plain apps with only the documented edits.<br>• **CI** reference-app 37936654024: device **118/0**, with R1 (a rotation: one lookup, one load) and X1–X7 (native views and the guest on one screen; a signed load; interaction; native navigation and a rotation with one load; the floor stored; another key refused while the native app survives; the R8-minified release loading). ios-host 37936654047: `ios.sh` **102/0**, with I1, I2, I4 and I5.<br>• **Not covered:** no iOS tap driver, so native navigation is checked on Android only.<br>• **Observed once:** at `077c5818` iOS P2's screenshot was blank after a verified load (a late first render); `ios.sh` now re-reads a status-bar-only screen once (`0c47eedd`). | PR #95 |
| W2.8 docs, review, fixes | **done 2026-10-10** (PR #95).<br>• **Docs** (`fd60bfd5`): the guide's "Embed in an existing app", `DEVICE_HOST.md` §4 (API, settings, trust root, backups, R8, failure behaviour, measured and not measured, limits), the tools README.<br>• **Independent review** (read-only): nothing blocking; five should-fix points, all fixed in `da5bc7e0`: (1) the trust key was read from merged assets, where an app's or another library's asset of that name wins; the build now compiles the checked key into `BuildConfig.KELIVER_PUBLIC_KEY_HEX` and the host reads only that; (2) `start()` ran once per process, so "No bundle" stuck; a start that created nothing is now retried by the next screen, and a failed load shows "Bundle did not load" instead of a blank view (both platforms); (3) a second host for one key failed later inside `start()` as an uncaught exception; the claim is now in `create()`; (4) iOS I5 proved only "nothing loaded"; it now requires the other key's verification line, the v7 lookup, `codeLoadFailed` and the message; (5) the last-good URL is kept per key. Nits fixed: iOS `start()` hops to the main thread; `checkReleaseUrls` fails on a missing Info.plist under Xcode; CDPATH-safe `--into`; the Kotlin warning wants 2.2.0 exactly; the clash check catches a target named KeliverHost; `KeliverView` needs a lifecycle owner (documented). Not changed: the runtime `KeliverConfig` stays public (documented: it skips the build's checks).<br>• **CI at `da5bc7e0`:** reference-app 38031453424: prepare 24/0, device **119/0** (R1, X1–X7, X6 now also sees "Bundle did not load"). ios-host 38031453435: self-test 68/0, `ios.sh` **104/0** (I1, I2, I4, I5 strengthened, P5 sees the message). **W2 is done**, apart from shipping it in a tools release (with approval). | PR #95 |
| PR stack merged | **done 2026-10-10** (the owner's decision). `main` now holds #81–#93: #81 (`97604c7a`), #82 (`4085b7c7`), #84 (`db91f570`) by their own merge commits, then #93 (`f4ea804e`), which brought #83, #85–#92 and #90 with it (GitHub marked each merged). Why #93 at once: #81's last four commits (run 5–7 records, its review fixes) landed after the rest of the stack branched, so every PR above it conflicted with `main` (5 hunks, 4 docs files and one workflow comment). The owner chose to resolve once at the top: `50a61891` keeps the stack's text plus #81's facts, and changes no code (its code is byte-identical to `35fc28af`). Checks: full `ci.yml` green at `35fc28af` (38030171016), and also at #82, #85, #87 and #91. At `50a61891`, reference-app 38063277345 and ios-host 38063277336 (`ios.sh` 92/0) are green. `ci.yml` on cloud macOS often hits its 60-minute timeout on the last test steps: #83 and #84 timed out twice, and #83 also failed once on a Maven Central download, all infra. The released tags 0.3.5–0.3.7 are reachable from `main`. No branch was deleted. #94 (the 0.3.8 candidate, untagged) now targets `main` and has `main` merged in, as do #95 and #96; none of them is merged. | `main` |
| W5.1 fall back to the last good bundle | **built 2026-10-10** (PR #96, stacked on #95). When the lookup names a bundle that then fails to load and a bundle ran before for this key and server, both hosts restart their one Treehouse app from Zipline's verified cache. That is the switchable pinning client and freshness checker, then `stop()`/`start()`. Unit: `BundleIndexTest` 14/0 (pins). **CI Android 38065789428:** S6/U2 (wrong sha256: fell back to cached v2, screen Warehouse) and S8 (replayed v1: fell back to v2) pass. | PR #96 |
| W5.2 update API | **built 2026-10-10** (PR #96): `checkForUpdate`, `currentBundle`, events, and `keliver.updates` / `UPDATES` = `next-launch` (default) or `on-resume`. U1 in `ci/w5/` on both platforms. First CI (38065789428): U1 failed because the throttle skipped the check silently (v9 was published within 30 s of the start). Fixed in `59ad3f23` (the host logs the mode and skipped checks; the harness waits 40 s). **CI green at `59ad3f23`:** reference-app 38067622371, prepare 25/0, device **130/0**; ios-host 38067622328, self-test 68/0, `ios.sh` **115/0**. Both include S6/U2, S8, and U1: the same process looked up v9, loaded it in place, raised the floor 8 -> 9 and showed Cellar. iOS U1 also passed at `4c19f9b6` (38065789393, 114/0), where its publish happened to take more than 30 s. | PR #96 |
| W5.3 docs, review, fixes | **done 2026-10-11** (PR #96).<br>• **Docs:** DEVICE_HOST §2 ("Updates while the app runs", the fall-back), §3, §4, the guide.<br>• **Independent review** (read-only): one blocking issue, a W5.2 correctness bug and not a security one. A failed update stopped all updates for the rest of the process: the same URL was set on a `StateFlow`, which doesn't re-emit it. Also: `pin(url, null)` erased an index sha256; iOS `checkForUpdate` continued off the main thread; S6/S8 did not prove the cache; no row covered a fall-back that fails. All fixed in `7afb8498`: the pending update is matched by URL and cleared on success, failure or skip, and given up after 2 min; failed URLs are not retried in the process; a null pin keeps a hash; iOS checks on main; `codeLoadSuccess ... source=cache\|network`, with S6/S8 requiring the cache; new **S9c** (the fall-back refused below the floor: "Bundle did not load"); iOS U1 compares pids.<br>• **CI at `7afb8498`:** reference-app 38073852570, device **133/0**; ios-host 38073852556, `ios.sh` **118/0**. **W5 is done.** | PR #96 |
| W6–W8 | not started | — |

## Next action

**On 2026-10-07 the owner said: start both in parallel — the tools 0.3.7
release and W3.**

**Track A: tools 0.3.7 — RELEASED 2026-10-07** (candidate 4, `aaa03478`, zip
`75ce0928…`). The owner said "ok, let's go" to the recommended candidate. The
record is in `docs/RELEASE_NOTES_TOOLS_0.3.7.md`. Follow-ups:
- the reference app moves to the published 0.3.7 zip and its own `bin/`
  scaffolders: PR #92;
- PRs #87, #88, #89 and #91 stay open until the owner merges.

What was done for the candidate:
- Branch `release/portal-tools-0.3.7` from `feat/ios-production-host` (PR #88).
- Bump `build-support/portal-tools.version`, and the adopter guide's download
  block (step 0).
- In the adopter guide, `DEVICE_HOST.md` §3 and the tools README, change
  "not yet in a released bundle" for the iOS scaffolder to "from 0.3.7".
- `docs/RELEASE_NOTES_TOOLS_0.3.7.md`, status CANDIDATE.
- Build: `portal-tools.yml` dispatch. Then the device run with the APK
  sha256, local verification of the retained artifact, and the step-3 workflow
  check at the candidate commit.
- **Tag and publish only after the owner says so explicitly.** "Start" is
  not approval to publish. 0.3.6's approval doesn't carry over.
- Consider adding `ios-host.yml`'s checks to the candidate's verification:
  run `ios.sh` against the candidate zip.

**Track B: W3 — built, reviewed, review fixed, and merged onto the released
0.3.7 line (PR #90, base #92; see Status).** Remaining:
- shipping it in tools 0.3.8 (a candidate, then the owner's approval);
- the "W3 known, not done" items.

**W4 is built** (W4.1 to W4.5, PR #93), reviewed and green on both platforms.

**Next:**
- **Owner's decision, 2026-10-10: the release is deferred** until more
  development is done; the changes will then go out together. Candidate 1
  (`c0e71108`, zip `7ea7f11d…`) stays untagged and will be superseded. The next
  release is a new candidate with full verification (build, device run, local
  check, `ios.sh` on the zip, step 3), still only with the owner's explicit
  approval. Recommended scope: W3 + W4 + W2, cut once W2 is reviewed, not
  waiting for W5 and W6. The version 0.3.8 is still free (no tag).
- **Owner's decision, 2026-10-10: merge the PR stack next.** Done: `main` holds
  #81–#93 (see Status, "PR stack merged"). #94 (the candidate, untagged), #95
  (W2) and #96 (W5) stay open; merging them needs the owner's word (#94
  switches the guide's download block to a 0.3.8 release that does not exist
  yet).
- **W2 (PR #95): done 2026-10-10** (W2.8 docs, the independent review and its
  fixes, CI green on both platforms at `da5bc7e0`; see Status).
  - Recorded follow-up, not in W2: a prebuilt, published host artifact for
    adopters on another Kotlin version. That is a Maven publication and needs
    the owner's approval.
- **Next release candidate, candidate 2 (W3 + W4 + W2 + W5):** built from the
  top branch `feat/w5-update-api` (it already carries #94's release changes),
  so nothing is merged into the release branch. Verify it like candidate 1:
  the build, a device run pinned by the APK sha256, a local check, `ios.sh` on
  the zip, and step 3. Tag only on the owner's explicit approval.
- **Then W6** (visibility).
- **Then W5** (update API, including falling back to the last good bundle),
  **then W6.**

## Standing constraints (from the owner; they apply to every step)

- **No merges** without explicit authorization. **No tag, release or Maven
  publication** without explicit approval for that release (0.3.6 was approved
  on 2026-10-07; that approval does not extend to anything else). Never move or
  recreate a released tools tag.
- Pushing review branches, opening PRs, and running build-only/device CI are
  allowed.
- Never touch `~/.keliver-portal`, its keys, `~/.gradle/caches`, or the real
  `~/.konan`. Never print private keys. Use disposable stores, user homes,
  Gradle homes and `KONAN_DATA_DIR`, behind `keliver_require_isolated_store`.
- No protection bypass, force-push or branch deletion. Stop only the processes,
  and remove only the temp dirs, that this run created.
- Report completed work and evidence, not hours. Don't call the reference app
  an external adoption, and don't claim a platform that wasn't executed.
- The PR stack #81–#93 is merged into `main` (2026-10-10). Open: #94 → `main`
  (the 0.3.8 candidate branch), #95 → #94 (W2), #96 → #95 (W5). This plan's
  newest copy is on the top branch, `feat/w5-update-api`.
