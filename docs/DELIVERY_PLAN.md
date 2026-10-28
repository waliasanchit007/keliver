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

### W1 — iOS production host (G1). **Done: released in tools 0.3.7.**

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
`{"installId", "channel", "hostVersion", "sequence", "source", "outcome", "reason", "platform"}`; `reason` is a fixed category, and the free-text `detail` stays with the app (W6 review).
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

#### W8 design (2026-10-11; built 2026-10-12 with the recommended answers)

**U30 is fixed** (`portal-device-android` `syncPortalKey`, on `feat/w8-keys`;
see `KNOWN_BUGS.md`).

**What constrains a rotation** (Zipline 1.22 and today's hosts):
- A manifest's signatures are keyed by **name**. A host's `ManifestVerifier`
  checks the signatures whose names it trusts, and needs at least one to
  verify.
- Every host shipped so far trusts exactly one key, under the name
  `portal-ed25519`.
- The signing block signs with one key, under that name.
- The rollback floor and the Zipline cache are kept per key (the key's first
  16 hex digits).

**Proposed rotation: dual-signing, three phases.**
1. **Hosts trust a set.** New hosts trust `portal-ed25519` (the current key)
   and `portal-ed25519-next` (the next key), each a separate line in the key
   file. The build checks every line. Ship these hosts, and wait until the old
   ones are gone or forced to update.
2. **Dual-sign.** The signing block signs each manifest twice, with the
   current key as `portal-ed25519` and the next key as `portal-ed25519-next`.
   Old hosts verify the first signature, new hosts either one. Nothing breaks
   at any point.
3. **Retire.** The next key becomes the only signer, and later hosts trust
   only it, under its own name.

**The floor across a rotation.** A host reads the highest floor over all the
keys it trusts and writes it under each, so a key change never reopens the
rollback window. Today a new key starts at 0. That reopening is exactly what
W4.2's "one key, one sequence space" warns about.

**Key custody guidance** (docs only):
- The private key lives in a CI secret, written to a file at job start, as
  `KELIVER_SIGNING_KEY_FILE` already supports.
- It is never in the repository, and never on a command line (U31).
- Offline backup of the next key.
- A short "what to do if the key leaks" runbook: rotate, ship hosts trusting
  only the new key, and raise the floor.

**Decisions (2026-10-12).** The owner gave full control overnight. W8 was
built with the recommended answers, which the owner can still change:
1. **Dual-signing**, not a signed key handover.
2. **At most 2 keys** per host: this app's key, then the next one.
3. **A publish guard:** `keliver-publish` refuses a bundle that shares no
   signing key with the newest published bundle, unless `--accept-key-change`.

**One change from the draft above: signature names.** Zipline 1.22 checks only
the FIRST signature in a manifest whose name the host trusts, and fails if
that one does not verify. Positional names (`portal-ed25519`,
`portal-ed25519-next`) would therefore break at the retire step. So:
- Each key signs under its own name, `keliver-ed25519-<first 16 hex>`, first.
- `portal-ed25519`, signed by the first key, comes LAST: it is all a host from
  before W8 trusts.
- A host trusts each of its keys under its own name, and its first key under
  `portal-ed25519` too.

**Built:**
- **Hosts** (Android key file lines; iOS `PORTAL_NEXT_PUBLIC_KEY_HEX`) trust
  one or two keys.
- **The rollback floor** is the highest over the trusted keys, written under
  each, also at start.
- **The signing block** (v3) signs with one or two keys. The 0.3.8 block is
  saved as `templates/publish/legacy/signing-0.3.8.gradle`, so
  `keliver-new-publish-target.sh` upgrades it.
- **`keliver-publish`:**
  - one or two public keys;
  - the guard;
  - `--new-signing-key <dir>`;
  - a republish source needs only a listed key's signature;
  - it refuses a bundle whose `portal-ed25519` is not this app's key when that
    key signed.
- **Docs:** `docs/KEY_ROTATION.md` (the three steps, the guard, the floor,
  custody, a leaked key).

**Known gap (documented):** an install that jumps from a host trusting only the
old key straight to one trusting only the new key starts the new key's floor
at 0, until its first load.

## Order and dependencies

W1 (iOS parity) → W3 (headless publish + static index, which W4 builds on) →
W4 → W2 → W5 → W6. W7 and W8 run alongside whenever hardware or decisions
allow. Releases went 0.3.7 (iOS host), 0.3.8 (CLI + static), and so on, each
with the owner's approval. From 2026-10-27, tools releases are authorized when
their required checks pass (Standing constraints, below).

## Status

| step | state | evidence / where |
|---|---|---|
| plan written | done 2026-10-07 | this file; branch `feat/ios-production-host` |
| W1 I0 iOS feasibility from Maven Central | **done 2026-10-07**: the production host links for the simulator; 54 `dev.keliver` artifacts, all 0.3.3 from Maven Central | `docs/superpowers/evidence/ios-host-i0/` |
| W1 I1 scaffolder | **built, measured locally 2026-10-07.** `keliver-new-ios-host.sh` + `templates/ios-host/`; self-test 38/0, and 43/0 with `--build` (xcodebuild). The scaffolded host loads signed v1, then v2 after an edit (Inventory → Stockroom); the real SQLite `IosSqlHost` passes 5/5. | `docs/superpowers/evidence/ios-host-i1/` |
| W1 I2 iOS CI | **done 2026-10-07.** `ios-host.yml` run `37597390396`, then `37602257459` after the independent review's fixes (macos-15, Xcode 16.4, iOS 26.2): self-test 48/0, P1/P2/P4–P7 30/0. Review: nothing blocking, all points fixed. | `docs/superpowers/evidence/ios-host-ci-37597390396/`; run `37602257459` |
| W1 I3 spike fallback | **deferred.** `portal-device-ios` is built only from Keliver source, and no CI job compiles it. It never reaches adopters, who get the scaffolded host. Fix it when that module is next built. | — |
| W1 I4 docs + 0.3.7 | **RELEASED 2026-10-07: tools 0.3.7** (candidate 4, with the U31 fix). Tag `portal-tools-v0.3.7` → `aaa03478`; zip `75ce0928…`, checked at the draft digest, by download-back and at the public URL. Candidates 1–3 superseded. | `docs/RELEASE_NOTES_TOOLS_0.3.7.md`; PRs #89, #91 (merged through #93, 2026-10-10) |
| W3 design | **done 2026-10-07**: a static `bundles/index.json` (format 1). Each entry is bound by its manifest's own signature plus `manifestSha256`; the index is unsigned. Anti-rollback is deferred to W4. | "W3 design" above |
| W3 `keliver-publish` + index-reading hosts | **built, CI green 2026-10-07** on PR #90 (`feat/w3-static-publish`, stacked on #88). The CLI verifies the signature and every module's sha256, then writes `v<N>/` and an atomic `index.json`. It refuses anything unsigned, foreign or incomplete and writes nothing (exit codes in the W3 design). The relay also serves `/bundles/index.json`. Both host templates read the index, pin the manifest's sha256, and fall back to `/bundles/latest` only on a 404. `KELIVER_SIGNING_KEY_FILE` covers CI signing. Tests before the review: `StaticPublishTest` 12, `BundleIndexTest` 7, `keliver-publish-selftest.sh` 16/0, the iOS host self-test with `xcodebuild` 49/0 (CI). The Android host self-test with a build, 40/0, was local and is in no CI evidence. | PR #90 |
| W3 static HTTPS on CI | **green 2026-10-07**. No relay; a static HTTPS server is fed only by the CLI, with a throwaway CA in the emulator's system store and the simulator's keychain. S2 (v1 through the index), S4 (v2), S5 (CLI refuses a foreign key), S6 (wrong sha256, nothing loads), S7 (offline from the cache). Android `reference-app.yml` run 37629943998: prepare 18/0, device 54/0. iOS `ios-host.yml` run 37629943934: self-test 49/0, `ios.sh` 48/0. | `docs/superpowers/evidence/w3-android-ci-37629943998/`, `w3-ios-ci-37629943934/` |
| W3 independent review | **done 2026-10-07; all should-fix points fixed or recorded** (PR #90). B1: the recipe republished v1 at sequence 1 from an empty checkout. Now the CLI refuses an out dir with no index unless `--init` (checked before building), and the guide's recipe downloads the live `bundles/` first, uses a concurrency group, uploads `v<N>/` before the index with no deletes, and removes `.gradle/` (U31). S1: no fallback to the cache after a failed load is documented (guide, `DEVICE_HOST`); the fallback itself is a W5 item. S2: the relay's index skips a `v<N>` without usable `meta.json`. S4: the lock refusal has its own message; exit codes are documented accurately; the production host's `gradle.properties` no longer says a static server isn't enough. Nits done: one app dir in the wrapper, `--out` inside the output refused, duplicate sequences or versions refused, S5 wording, head runs named. S3 (the key in `.gradle/`) is U31, fixed separately. Tests after the fixes: `StaticPublishTest` 16/0, `BundleIndexTest` 7/0, `PublishSignatureTest` 7/0, `keliver-publish-selftest.sh` 18/0 (local). **CI on the post-review head `5f2807569`:** `reference-app.yml` 37655639736 (prepare 18/0, publish self-test 18/0, device 54/0 including S2/S4–S7) and `ios-host.yml` 37655639526 (self-test 49/0, `ios.sh` 48/0). | PR #90 |
| W3 known, not done | **Mostly done 2026-10-12 (PR #101).**<br>• **Fixed:** one lookup deadline for the index plus `/bundles/latest` (10 s in all); a total timeout on iOS (`withTimeoutOrNull` cancels the task); a 1 MiB cap on the index body (Content-Length and bytes read); strict JSON numbers in `pickFromIndex`.<br>• **Capability filtering on a device and a simulator:** rows S16/S16b.<br>• **Evidence:** `BundleIndexTest` 17/0; reference-app 38203057445, device **142/0**; ios-host 38203057497, `ios.sh` **127/0**; `ci.yml` 38203057477 green. An independent review's blocking point is fixed (`99883d7a`).<br>• **Still open:** the deadline and the size cap have no device row; "same origin" is not a boundary, because Zipline's downloads follow redirects; there is no device check of a new host against a new relay's `/bundles/index.json`. | PR #101 |
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
| tools 0.3.8 candidate 2 (W3 + W4 + W2 + W5) | **verified 2026-10-11; NOT tagged, awaiting the owner's approval** (built from `feat/w5-update-api`, PR #96).<br>• **Identities:** source `ef625050cbb5a1afd7cb2288567d6df3a35da2fa`; zip `32d47c34b46594dc89c2feef43e3657bf24daa74af77bbff3d4535fd29b0ab68` (90,559,881 bytes); APK `106b2bd4…`.<br>• **Build** 38078500722 (artifact `11679304379`, built 2026-10-11, 30-day retention): every self-test green. **Device run** 38079812823: 19/0 and 28/0.<br>• **Local check:** hashes; 57 packaged files byte-identical to the commit; no key files; the packaged `keliver-publish` 40/0.<br>• **`ios.sh` on the zip** (local, Xcode 26.4.1, iOS 26.4): **118/0**, with W2's I-rows, W5's U1, S6/U2, S8 and S9c.<br>• **Step 3:** clean.<br>The record is in `docs/RELEASE_NOTES_TOOLS_0.3.8.md`; candidate 1 is superseded. | PR #96 |
| W6 reports (W6.1 + review) | **done 2026-10-11** (PR #97, stacked on #96).<br>• **Built:** `KeliverReport` on both hosts (`reports` / `onReport`); an opt-in `keliver.reportUrl` / `REPORT_URL` (one best-effort JSON POST per outcome, no redirects); crash-key docs; V1 in `ci/w5/`.<br>• **Independent review:** nothing blocking. Four should-fix, all fixed in `5360ac7e`. (1) The free-text `detail`, which can hold a LAN IP, paths or URLs, was POSTed; now only a fixed `reason` category leaves the device (`reportReason`, `BundleIndexTest` 15/0). (2) Android accepted and logged a report URL with `user@`, a query or a fragment; it is now refused, and never logged. (3) The iOS crash-key example could name a sequence that never ran. (4) Stale install-id comments; the docs now say it is a pseudonymous id (GDPR) and that requests reveal IP and user agent. Nits fixed: no-redirect sessions, guarded iOS callbacks, `no-bundle` once per process, a stricter V1 (exactly one of each, no failure outcome, exactly 8 fields).<br>• **CI at `5360ac7e`:** reference-app 38086013708, device **134/0**; ios-host 38086013593, `ios.sh` **119/0**. **W6 is done.** | PR #97 |
| W6.3 relay collector | **done 2026-10-11:** `POST /report`, `GET /reports`, `GET /reports/view` (`HostReports.kt`). `HostReportsTest` 4/0, on CI in build 38133756571. V2 is in the harnesses: SKIP on CI's published relay, and **passed end to end** in candidate 3's local `ios.sh` on the zip | PR #97 |
| **tools 0.3.8 RELEASED** (candidate 3: W3 + W4 + W2 + W5 + W6) | **released 2026-10-11** on the owner's approval.<br>• **Tag:** `portal-tools-v0.3.8` → `731ebfce`; the push ran only the read-only rebuild 38150887414.<br>• **Zip** `aafed0d8…` (90,584,445 bytes), uploaded from the verified artifact `11700407805`.<br>• **Checked before publishing:** both stored assets' server digests and sizes (download-back blocked by the network, as for 0.3.5). After publishing: the public `.sha256` here, and the public zip verified from GitHub-hosted runners (`sha256sum -c` OK: 38151355318, 38155942970, 38151386539).<br>• **Published-only proof** (runs started by hand, only the zip's `bin/`): reference-app 38155942970, device **135/0**; ios-host 38151386539, `ios.sh` **120/0**. V2 is on CI now, because the published relay collects reports.<br>• **Candidate 3's verification:** build 38133756571; device 38134971077 (19/0, 28/0); local check; `ios.sh` on the zip 120/0, V2 included.<br>The record is in `docs/RELEASE_NOTES_TOOLS_0.3.8.md`. Candidates 1 and 2 were superseded and never tagged. | release |
| PR stack #94–#97 merged | **done 2026-10-11** after the release, by merge commits: #94 `09661866`, #95 `642f4a13`, #96 `6ba680c5`, #97 `2bae5cbb`. Both 0.3.7 and 0.3.8 tags are reachable from `main`; no branch was deleted | `main` |
| W8 | **built 2026-10-12** with the recommended answers (W8 design above). U30 fixed. Evidence:<br>• **Unit:** `KeyRotationTest` 14/0 (Zipline's own signer and verifier over a whole rotation, and the publisher's check never accepting what a W8 or pre-W8 host refuses); portal-relay 192/0.<br>• **Scaffold self-tests:** `keliver-publish` 41/0 locally; publish-target `--build` 44/0 on CI, which signs with two keys in a real Gradle build and refuses three (portal-tools 38214047382).<br>• **Earlier commit `15e52646`:** `ci.yml` 38214024406 green.<br>• **Rows K1–K8, all passing on both platforms at `52be15a4`:** the guard, dual-signed, a pre-W8 host from the published zip, the floor across keys, the next key alone, each side refusing the other's. Runs: reference-app 38215749144 (device **153/0**, prepare 30/0, `keliver-publish` self-test 41/0); ios-host 38215749105 (`ios.sh` **140/0**, self-test 68/0); `ci.yml` 38215749027 green; portal-tools 38215747969 (all self-tests green).<br>• **Independent review:** no blocking point; its 6 should-fix points and the relevant nits are fixed (`52be15a4`). | PR #98 |
| W7 | **prep done 2026-10-12; not executed on hardware** (PR #100). `docs/DEVICE_PROOF.md`: the session's checklist (Android over USB, iPhone signing and `devicectl`, real-certificate HTTPS, release-signed APK and IPA, the embed proof, what to record, what it does not prove). The Android harness has a physical-device route (`KELIVER_DEVICE=physical`: hosts built for `127.0.0.1` behind `adb reverse`, the throwaway CA inside the test APKs, the development host's rows SKIP), **rehearsed on the CI emulator only** (`reference-app.yml` 38202773284, `device_route=physical`: prepare 28/0, device 130/0, E1–E10 and D3 SKIP; not a device result). `ci/w7/ios-device.sh` (build, sign, install, first load on an iPhone) has never run. Found and fixed: **U32**, the iOS host ignored `TEAM_ID`. D14 screenshots: black only on the `aosp_atd` image; `google_apis` gives VALID captures, device 135/0 (38213948442; `REFERENCE_APP.md`). Moving CI to it is a decision left open. CI at `ebdd0b31d`: reference-app 38213936318 (135/0), physical rehearsal 38213940854 (28/0, 130/0), ios-host 38213936321 (self-test 69/0, `ios.sh` 120/0), `ci.yml` 38213936497. Still needs the owner: the devices, an Apple team, a real-certificate server, an adopter app | PR #100 |
| **tools 0.3.9 RELEASED** (W8 + the W3 hardening + U32) | **released 2026-10-13** under the owner's full control of releases (2026-10-12).<br>• **Tag** `portal-tools-v0.3.9` → `6069da99`.<br>• **Zip** `31c4214c…` (90,611,347 bytes), from the verified artifact `11843942421` (build 38454753493).<br>• **Device:** 38463890634 (19/0, 28/0).<br>• **Local check:** 58 files byte-identical; `ios.sh` on the zip **146/0**.<br>• **Stored asset:** the draft download-back worked (`31c4214c`); the public zip checked here (`shasum -c` OK).<br>• **Merged first, all green:** #101 (`993c43d6`), #100 (`f2dafdab`), #98 (`f4de3a34`).<br>• **Published-only proof (PR #102):** reference-app dispatch 38494338455 at `5ca15f16` (scaffolders from the published zip): prepare 30/0, device **160/0**; ios-host dispatch 38468862546 at `4c19479d` (`ios.sh` is unchanged since): **147/0**. These are the first runs where K1–K8 come entirely from a published zip, with the pre-W8 host from the published 0.3.8. The PR runs are also green (38483538737: 160/0; 38483538839: 147/0; CI 38483538738). | `docs/RELEASE_NOTES_TOOLS_0.3.9.md` |
| **libraries 0.3.4 RELEASED** (adoption item 1: the host widget gaps) | **released 2026-10-16** on the owner's go.<br>• **PRs** #103 (keyed container children, `2b89e1c8`), #105 (the widget fixes, `8b547f2b`) and #107 (version, CHANGELOG, docs; merged as `502416e9` after main was merged in and CI went green on the identical tree `b0a2c25e`).<br>• **Tag** `v0.3.4` (annotated) → `502416e9`.<br>• **Maven Central:** `publish-maven-central.yml` run `38814070981`: preflight and upload green, automatic release; all 378 artifacts of the local pre-gate publish and the Gradle plugin markers resolve from `repo1.maven.org` (`maven-metadata.xml` latest = 0.3.4). GitHub Packages: `publish.yml` `38814046945` green.<br>• **Device:** rendered on a Samsung S25 Ultra before the release (BottomSheet open/close/reopen, Theme, scroll column, lazy list after list changes, pager, text align); text-field `align` was not exercised on a device (the proof guest is built on 0.3.3).<br>• **Not in 0.3.4:** U33 (#112, merged after the tag). Tools still scaffold 0.3.3 until the next tools release. | `CHANGELOG.md` [0.3.4], PR #107 |
| adoption item 6, rows 1–2 and 4; item 2, M1 | **merged 2026-10-15, released in tools 0.3.10:** #108 (the full Gradle output of every publish is kept, linked from the reply), #109 (publish reads `keliver.portal.json` each time), #110 (M1: Production bundles by default; Development behind a flag), #111 (a debug host takes its bundle server at launch, on Android and iOS). | #108–#111 |
| adoption item 6, row 3: debug hosts update on resume | **merged 2026-10-17** (#114, `f659f280`). `keliver.updates` / `UPDATES` empty by default: `on-resume` in debug builds, `next-launch` otherwise. A debug "check now": `adb shell am broadcast -a dev.keliver.debug.CHECK_FOR_UPDATE -p <appId>` (a run-time receiver, `DUMP`-guarded) and the Darwin notification `<bundle id>.keliver.check-for-update`. Reference CI pins `next-launch` where it relied on it. CI: reference-app prepare 31/0, device 164/0 (U0, U1 on the default, U2); ios-host `ios.sh` 152/0 (U1 on the default, U2). Samsung S25 Ultra, a debug template host: going home and back applied v2 in the same process, and the adb broadcast applied v3 without leaving the app (logs; the screenshots caught the owner's own screen) | PR #114 |
| adoption item 6, row 1b: the first-publish compiler error | **worked around 2026-10-17** (#115, `7a7eef3f`): a relay publish that fails with "Internal compiler error" is built once more, both logs kept. Six reproduction attempts on the proof app did not reproduce it; KNOWN_BUGS U34 records them and what is still open | PR #115 |
| adoption item 2, M0: timing marks | **#116**: `process`/`open`/`code`/`first-frame`/`content` in debug builds and a new benchmark build type (Android) / `Benchmark` configuration (iOS); none of it in a store build. `first-frame` wraps the guest UI's change sink in the template (no library change). Rows T0–T2 on both platforms. S25 (debug template host, Development bundle over plain HTTP, the FIRST launch after install, not a cold start): process→open 389 ms, →code 1315 ms, →first-frame and content 1437 ms | PR #116 |
| **tools 0.3.10 RELEASED** | **released 2026-10-17** on the owner's go (candidate 3; 1 and 2 superseded by the review's fixes).<br>• **Tag** `portal-tools-v0.3.10` → `78bcfa0ad`; zip `0ba5c07b…` from artifact `12087402781` (build 38996395407).<br>• **Verified:** device job 38997931981 (19/0, 28/0); local acceptance 25/0, identity 6/0, 61 files identical; `ios.sh` on the zip 158/0; device-verify on an S25 18/18 (its acceptance 27/28: the bundled host's dev loop is emulator-only).<br>• New apps get the 0.3.4 libraries; scaffolders follow an app's `appRuntime.keliverVersion`. Record: `RELEASE_NOTES_TOOLS_0.3.10.md` | #117 |
| reference app on the published tools 0.3.10 | **#118**: bootstrap, `ios.sh` and both workflows pin the 0.3.10 zip `0ba5c07b…`; the app stays on libraries 0.3.3 and every scaffolder is told so (`KELIVER_VERSION` from the app's `keliver.portal.json`). Published-only proof from GitHub runners: `reference-app.yml` dispatch 39015039884 (prepare 34/0, device 171/0), `ios-host.yml` dispatch 39030961195 (71/0, 159/0); both checked the public asset | #118 |
| adoption item 2, M2 (Android) | **#119**: `reference/inventory/m2/` on a Samsung S25 Ultra, the published 0.3.10 host's benchmark build and a Production bundle: first launch 1024 ms, cold 243 ms, warm 35 ms (medians of 5); PSS 89–111 MB. iOS M2 waits on the owner (signing team) | #119 |
| **tools 0.3.11 RELEASED** | **released 2026-10-19** on the owner's go: U35/U36 (loopback-only relay and editor server, the relay's request check), refused instead of lossy write-backs, K1–K5 (#120–#126).<br>• **Tag** `portal-tools-v0.3.11` → `ebbb24c96`; zip `ae84cb2c…` (91,311,273 bytes) from artifact `12170000839` (build 39214066995); the tag push ran only the read-only rebuild 39222008390.<br>• **Verified:** device job 39216352757 (19/0, 28/0); the build job's packaged acceptance 25/0, identity 6/0, store identity 11/0, store recovery 144/0; locally store identity 11/0 and store recovery 134/1 (C5: port 8096 held by a running portal); the draft zip downloaded back in full and hashed identical before publishing. Not run: device-verify on a physical phone.<br>• New apps still get the 0.3.4 libraries. Record: `RELEASE_NOTES_TOOLS_0.3.11.md` | #127 |
| reference app on the published tools 0.3.11 | **#128**: bootstrap, `ios.sh` and both workflows pin the 0.3.11 zip `ae84cb2c…` (source `ebbb24c96`); the app stays on libraries 0.3.3 (`KELIVER_VERSION` from its `keliver.portal.json`). Each run's first step downloads the public asset on a GitHub runner and checks it against the release's `.sha256`, the pinned hash and `VERSION.json`'s source commit: the public-download half of the 0.3.11 record. Pull-request runs: reference-app 39234198214 (prepare 34/0, device 171/0), ios-host 39234198413 (71/0, 159/0); by hand, host scaffolders from the published zip: reference-app 39255263700 (34/0, 171/0), ios-host 39255268701 (`ios.sh` 159/0 from the zip; its scaffolder self-test 71/0 always runs this checkout's identical script). Merged `5d75b0bf9` | #128 |
| relay tests use their own store | **#130** (`c35664a64`): this repo's `keliver.portal.json` names `~/.keliver-portal`, so a local `:portal-relay:test` claimed the developer's real store (owner marker) and a run from another checkout then exited 70. Test tasks now set `PORTAL_STORE` under `build/`; `StoreContractTest`'s resolver subprocesses unset it; `HermeticTestStoreTest` guards it (fails with the setting removed). 342/0 locally, configuration-cache clean. A marker an earlier run left in `~/.keliver-portal/owner` is the developer's to remove | #130 |
| compiled component preview (exact canvas for opaque components) | **#132** (`84d0447b0`): in a per-app editor an opaque component is drawn by the app's own compiled composable inside the editor's guest composition, from adapters the relay generates from component signatures (`compiledComponents` in `keliver.portal.json`; `:portal-relay:compiledComponentAdapters [-Pcheck]`, run in CI). A failure probe (inside the canvas's locals, no events, no saved state) falls back to the placeholder. Independent review: three rounds (the last after a one-line follow-up fix). **Browser-verified on the dogfood editor** (main `84d0447b0`): the opaque `NoteCountPill` draws compiled ("3 NOTES", "1 ITEM"), a tap selects it as one unit, editing Count 3→7 redraws "7 NOTES" and writes `count = 7` back surgically (`docs/superpowers/evidence/compiled-component-preview/`). Not done: a device-screenshot comparison (D14). Needs libraries 0.4.0 and a tools release | #132 |
| **K6** equality, compound conditions, derived Boolean locals | **#131** (`d39fa143d`): `x == <literal\|token>` / `!=`, terms joined by `&&` only or `\|\|` only, `val active = a && !b` in a component body (inlined at ingest). One shared rule for a term's declared type (`termTypeMisfit`: refuses only types known not to fit); an equality on a field the file cannot type needs evidence from the same scope; extended conditions travel on the device tree under a new key, so an older reader takes the true/then branch instead of misreading. Independent review: three rounds with fixes, a fourth found nothing. Real-screen sweep, re-run after the round-2 fixes (`1d3478290`): 45 screens, no-op write-back byte-identical (also CRLF), 350 inserts / 350 deletes / 272 moves, 0 refusals. On the product-proof app: every screen-called component transparent (35/35), 33/35 fully clean (two still contain an opaque component), 0 opaque components used by screens (was 4) | #131 |
| **K7** Int arithmetic, Int → Dp | **#134** (`7c9be494f`): `+ - * / %` over Int literals, tokens and Int fields/parameters (`/`, `%` only by a non-zero literal), `x.dp` / `Dp(x.toDouble())`, derived Int locals; compiled-vs-interpreted parity on js and wasm; `.uppercase()` stays refused (the device's QuickJS has its own case tables). The product-proof app: every screen-called component fully clean (35/35) | #134 |
| contract write-back never redeclares an inherited member (U37) | **#135** (`e274eefc4`, KNOWN_BUGS U37): on a screen whose Bindings interface extends a type from another file, an edit wrote `val title: String get() = ""` for an inherited field, and the file stopped compiling; now nothing that could be inherited is declared, and on such a screen an edit reading a field the file neither declares nor reads is refused when made (K4 allowed it). Tests compile the written file with kotlinc | #135 |
| **libraries 0.4.0 RELEASED** | **released 2026-10-21 (IST; Central publish finished 2026-10-20T21:13Z)** on the owner's go (2026-10-20).<br>• **PR** #136 (version, CHANGELOG, docs), merged `b359b3952` after CI 39471554982.<br>• **Tag** `v0.4.0` (annotated, `1f78a1137`) → `b359b3952`.<br>• **Maven Central:** `publish-maven-central.yml` 39484069794 (preflight + publish green, automatic release); all 389 poms of the local pre-gate at the merge commit (378 artifacts + 11 plugin markers, the same set as 0.3.4) resolve from `repo1.maven.org`; `maven-metadata.xml` latest/release = 0.4.0. GitHub Packages: `publish.yml` 39484052616 green.<br>• **Consumer check:** the product-proof app (Kotlin/JS guest) built against the Central artifacts (re-run after the release, 2026-10-21, with the scratch repository removed): Zipline bundles compile, 101/104 tests unchanged; the other 3 are goldens that differ only by `OutlinedTextField.align` (added in 0.3.4) now being sent — the app's goldens were recorded on 0.3.3.<br>• Of the published modules, only `portal-core`, `portal-document`, `portal-render`, `portal-editor` changed source; source break for an exhaustive `when` over `PropValue` (CHANGELOG). Tools still scaffold 0.3.4 until a tools release | #136 |
| **tools 0.3.12 RELEASED** | **released 2026-10-21** on the owner's go: K6 (#131), K7 (#134), the compiled component preview's tools side (#132), U37 (#135); new apps get the 0.4.0 libraries.<br>• **Tag** `portal-tools-v0.3.12` → `8139a55b`; zip `4c9aac74…` (91,616,631 bytes) from artifact `12329584134` (build 39564359560); the tag push ran only the read-only rebuild 39584468151.<br>• **Verified:** device job 39567056811 (28/0, 19/0); the build job's packaged checks (acceptance 25/0, identity 6/0, store recovery 144/0, …); locally on the attached bytes: acceptance 25/0, identity 6/0, and the compiled component preview end to end on a scaffolded app (generic editor: placeholder; per-app editor: the app's compiled component); the draft's assets downloaded back in full and hashed identical, then the public URL.<br>• **Review caught two release blockers in the first candidate** (`964982ef1`, not released): the generic editor drew Keliver's sample components in place of an app's same-named opaque ones, and `keliver-portal` never wrote an app's adapters. Fixed in `2150b4fa6`/`8139a55b`. Follow-up: U38. Not run: device-verify on a physical phone. Record: `RELEASE_NOTES_TOOLS_0.3.12.md` | #138 |
| reference app on the published tools 0.3.12 | **#139**: bootstrap, `ios.sh` and both workflows pin the 0.3.12 zip `4c9aac74…` (source `8139a55be`). The app stays on libraries 0.3.3 (`KELIVER_VERSION` from its `keliver.portal.json`), although 0.3.12 scaffolds 0.4.0 for new apps: moving an existing app is a separate change (0.3.12's release notes). Each run's first step downloads the public asset on a GitHub runner and checks it against the release's `.sha256`, the pinned hash and `VERSION.json`'s source commit: the public-download half of the 0.3.12 record. Runs at `d02ae8f58`, pull request (scaffolders from this checkout): reference-app 39585012146 (prepare 34/0, `keliver-publish` self-test 41/0, device 171/0), ios-host 39585012260 (71/0, `ios.sh` 159/0); by hand, host scaffolders and `keliver-publish` from the published zip: reference-app 39585020070 (34/0, 41/0, 171/0), ios-host 39585026780 (`ios.sh` 159/0 from the zip; its scaffolder self-test 71/0 always runs this checkout's script, byte-identical to the zip's `bin/keliver-new-ios-host.sh`) | #139 |
| editor E2E suite (real input) | **merged into the editor branch 2026-10-22** (`5d37db41e`), **reached `main` through #140 on 2026-10-25**: `editor-e2e/run.sh`, a Node CDP driver with real mouse and keyboard input against a headless Chrome and a public fixture app; CI job "Editor E2E (real input, headless Chrome)" plus a changed-paths job. Unreleased. |
| typed property controls (E8–E13, E27, E28, E48) | **merged into the editor branch 2026-10-23** (`3944c52f7`), **reached `main` through #140**: enum and code props as named dropdowns from the schemas, colours with swatch and alpha, numbers validated and written once on Enter or blur, tokens filtered by meaning, `@` picks a contract field, conditions and events picked from the contract. Unreleased. |
| editor program, PR #140 | **merged 2026-10-25** (`12cacbe08`) after five feedback rounds by a user-role agent and seven independent reviews: selection and hover boxes, components opened and edited in place (zoomed when small), properties and State panel, cross-document undo whose order is the relay's (`/history`), byte-exact undo of write-back, new screen files written compactly. Editor 191/0, ingest 369/0, relay 391/0, E2E 34/34; CI green. The loop (editor edit, write-back, signed bundle, render) was checked on a physical Android phone (Android 12) against a scratch store. **Unreleased:** the generic editor ships in the tools bundle; a per-app editor compiles against the published `portal-editor`, so it also needs a libraries release. |
| editor polish + host resume fix, PR #141 | **merged 2026-10-26** (`a13b2d4ea`): the outline keeps its scroll place; the top bar says what ▶ Live, </> Code and ⬆ Publish do and which bundle devices get; a palette preview drawn by the canvas; a compact publish card; Live says when the editor has nothing to run. Production-host template: a screen opened while an on-resume update is being applied waits for it (up to 5 s), so it is drawn once, not old-then-new (checked on the same phone: one guest start instead of two). All checks green, reference-app included. **Unreleased.** |
| ▶ Live for an adopter's own presenters | **deferred 2026-10-26** by the owner: planned (generated live adapters from Bindings interfaces instead of hand-written mappers), not started; the debug device loop comes first. `docs/ROADMAP.md` adoption item 9. |
| bundled scaffolders write into the app, PR #142 | **merged 2026-10-27** (`13b69b9d6`): `keliver-new-screen.sh` / `keliver-new-component.sh` take the app from the working directory (or `KELIVER_APP_DIR`), refuse without `keliver.portal.json`, and derive the app's package. `keliver-new-scaffolders-selftest.sh` 9/0 (1/7 on the old scripts). | PR #142 |
| U38, PR #143 | **merged 2026-10-27** (`1dda934fe`): `keliver-portal` serves the editor `Cache-Control: no-store` and ignores `If-Modified-Since`. Not re-run in a browser yet. | PR #143 |
| docs audit, PR #144 | **merged 2026-10-27** (`34daa91b6`): status records, guides, ranked priorities, the Live deferral. | PR #144 |
| live device updates (Android), PR #145 | **merged 2026-10-27** (`d3b3ad2cf`) at head `418bc6898`, reviewed twice: the relay's `GET /bundles/events` (SSE, ≤ 32 listeners, a stalled phone never delays a publish), the Android host's `keliver.updates=live` (debug default) listening while a `KeliverScreen` is on show, the editor's devices chip. CI on that head: Build, both editor E2E, reference-app (Android LV1–LV3: static-server fallback, a publish applied in the open app in the same process, the stream closed in the background), ios-host 40409443193 — all green. | PR #145 |
| U28 on both generic hosts, PR #146 | **merged 2026-10-27** (`33fa9fdeb`) at `b3c5b895d`: `portal-device-android` and `portal-device-ios` emit no manifest URL until the lookup has one. Compiled both; no device run of prod mode. | PR #146 |
| MCP `device_screenshot` finds adb, PR #147 | **merged 2026-10-27** (`46cd57649`) at `dd674f727`: `ANDROID_HOME`, `ANDROID_SDK_ROOT`, the macOS and Linux SDK defaults, then `PATH`. mcp tests 27/0. | PR #147 |
| `keliver-portal` ports, PR #148 | **merged 2026-10-27** (`b84b79291`) at `69c5d6358`, reviewed three times: the editor on `editorPort` (default port + 19), `?relay=` in the printed URL and added by the editor's server to a URL without it, ports validated (and `stop` working with a broken config), C5 on the right port, `allowedOrigins` documented. | PR #148 |
| relay `/doc-events` fd leak, PR #149 | **merged 2026-10-27** (`a4e31b6e4`) at `a02d5d874`: a dropped listener's exchange is closed. Relay 393/0. Not done: per-listener write locking (pre-existing interleave risk). | PR #149 |
| **libraries 0.4.1 RELEASED** | **released 2026-10-27** under the owner's 2026-10-27 prompt (publish when warranted): the editor libraries (#140, #141, #145's chip), additive API only.<br>• **PR** #151 (version, CHANGELOG, docs), reviewed; merged `c5a7e8716` at head `5b8f04c01` (CI 40426997869: Build, both E2E — the real-input suite after one rerun of K1, a flake fixed in #153); the merge commit's tree equals the head's.<br>• **Tag** `v0.4.1` (annotated) → `c5a7e8716`; `publish-maven-central.yml` run `40430838622`: preflight then publish, green; all 389 poms resolve from `repo1.maven.org` (03:24Z). | PR #151 |
| **tools 0.3.13 RELEASED** | **released 2026-10-27** (06:12Z) under the owner's 2026-10-27 handover: #140–#149; new apps on libraries 0.4.1.<br>• **Prep** PR #152 (reviewed); candidate build `40444075230` at `a5ab6e2b2` (packaged acceptance 25/0 and every packaged self-test green), retained artifact `12708283293`; device job `40446935093` (emulator: device verify 28/0, refusals 19/0).<br>• **Tag** `portal-tools-v0.3.13` → `a5ab6e2b2`; the push ran only the read-only rebuild `40458612267`.<br>• **Zip** `5d1dd0ef…` (92,036,638 bytes), APK `04e5e58c…` (no key), checked locally from the artifact; the draft's server digests matched; download-back and the public URL were blocked by the network (the 0.3.5 route; public check from GitHub runners in #154). `--latest=false`. | [`RELEASE_NOTES_TOOLS_0.3.13.md`](RELEASE_NOTES_TOOLS_0.3.13.md) |
| reference app on the published tools 0.3.13 | **merged 2026-10-27** (`d4e0e1b46`) at `e9d25be21` (CI green incl. ios-host `40480758847`), **#154**: bootstrap, `ios.sh` and both workflows pin the 0.3.13 zip `5d1dd0ef…` (source `a5ab6e2b2`). The app stays on libraries 0.3.3 (`KELIVER_VERSION` from its `keliver.portal.json`), although 0.3.13 scaffolds 0.4.1. Each run's first step downloads the PUBLIC asset on a GitHub-hosted runner and checks it against the release's `.sha256` and the pinned hash: the public-URL check of 0.3.13, which the releasing machine's network blocked. That step passed at `71d8a20db` in reference-app run `40460117078` (ubuntu) and ios-host run `40460117086` (macOS): `keliver-portal-tools-0.3.13.zip: OK`, then the pinned hash and `sourceCommit`; both runs green. | PR #154 |
| live device updates (iOS), PR #150 | **merged 2026-10-27** (`553398ede`) at `f2f4b20f4`, reviewed twice (one should-fix: count screens by the view controller's lifecycle, not the composition): `UPDATES = "live"` (a DEBUG binary's default; `on-resume` in a release one), `BundleEvents.kt` (NSURLSession stream; 3xx/4xx/non-SSE = not served), the relay's 2 KiB preamble + `nosniff`. CI on that head: Build, both E2E, reference-app, ios-host `40460319680` (`ios.sh` 167/0: LV1 fallback, LV2 v10 applied in the same pid with 'Loft' on screen, LV3 closed in the background). Not on a physical iPhone. Ships in the next tools release. | PR #150 |
| editor E2E K1 flake, PR #153 | **merged 2026-10-27** (`28a67d612`) at `b70170677`, reviewed twice: K1 presses Enter when the double-click only selected, and says so in the CI output; KNOWN_BUGS U39 (open) for the editor behaviour behind it. | PR #153 |
| host kit K1 part 1 (Android), PR #155 | **merged 2026-10-27** (`95a298733`) at `f65ab1a54`, reviewed three times: typed `KeliverHostState`, `onUnavailable` (re-checks the state; a retried start is `Loading`), `showsDeveloperText` (debug variant AND debuggable app). Self-test static checks 63/0; the scaffold's assembleDebug and compileReleaseKotlin. No device run (K4). | PR #155 |
| live device updates on a **physical Android phone** | **checked 2026-10-27** on a Galaxy A12 (Android 12, arm64-v8a, wireless adb), from the **published** tools 0.3.13 zip (`5d1dd0ef…`) and libraries 0.4.1 from Maven Central, in an isolated home and store (relay :8478, editor :8497, `adb reverse tcp:8478`): `keliver-init` → `keliver-new-device-target.sh` → `keliver-new-publish-target.sh` → `keliver-portal` → publish v1 → `keliver-new-production-host.sh --bundle-server http://localhost:8478` → `assembleDebug` → install. The host logged `updates live` and listened to `/bundles/events`; the relay counted 1 device listening. With the screen open, a title edit was published as v2: the host applied it ≈ 1.1 s after the event, in the same process (same pid, no restart), raised its floor 1 → 2, and the screen showed the new title. Home: "stopped listening"; the relay counted 0 listeners 22 s later. Reopened: listening again. ("stopped listening" appeared twice in that session's output: the session's command read `logcat -d` twice without clearing it, so one line was printed twice; one stop, one line.) Screenshots kept off-repository. The iPhone run waits on the owner (Xcode has no Apple ID signed in). | this row |
| live updates and host kit follow-ups, PR #158 | **merged 2026-10-28** (`8810bf387`) at `300aecda1`, reviewed twice: the relay's `/bundles/events` sends a listener nothing before its 2 KiB preamble; `/doc-events` writes one event at a time per listener; `KeliverHostState.Unavailable.attempt`; the iOS template declares `lifecycle-runtime-compose`; LV4 (back in the foreground the host listens again) on Android and iOS; U39 narrowed (every failure was K1, the one test intercepting the component request) and instrumented. Relay 401/0. CI: Build, both E2E, reference-app, ios-host (after one rerun: the first run's P2 lookup of the published 0.3.13 relay timed out on a slow macOS runner; job `122354750369`, rerun `122440015566`). | PR #158 |
| host kit K1: `RequestPolicy` and `ExternalLinkPolicy`, PR #159 | **merged 2026-10-27** (`489f5e8e9`) at `a635372ae`, reviewed four times (security-minded): exact `(method, path)` pairs, one origin that must be the API base's, https unless the build allows cleartext, a switch for state-changing calls, method-override headers dropped; `intent:` links only as a browser opens them, never this app, no launch flags; JVM tests scaffolded into the host (13 + 9). | PR #159 |
| host kit K2 part 1 (iOS), PR #160 | **merged 2026-10-28** (`0f8caaed9`) at `8c5add3f6`, reviewed twice: public `KeliverHostState` (Android's shape), `currentState` / `onStateChange` for Swift, `viewController(safeArea:onUnavailable:)`, the release text policy; the self-test type-checks the embed's Swift against the built framework. | PR #160 |
| host kit K1 rest (Android), PR #161 | **merged 2026-10-28** (`3ed8535c1`) at `4ddc9edb8`, reviewed before and after it opened (one blocker found and fixed: `KeliverScreenHost` must be in one package on both sides, or Zipline never matches `launchRoute`): `screen-failed` (a crash shows the unavailable slot and calls `onUnavailable`, also on screens opened afterwards); the debug-surface audit as self-test checks (a release APK's BuildConfig and manifest); routes and close (one shared pure-Kotlin `KeliverRoute`; an old guest falls back to `launch()`; a default close that finishes the activity). CI: Build, both E2E, reference-app, ios-host (after one rerun: P2's first launch printed nothing on a slow runner; job `122529889585`, rerun `122584972689`). Not run on a device (K4). | PR #161 |

## Next action

**2026-10-28 (later).** #161 is merged: K1 on Android is complete in code.
In review: `RequestPolicy`, routes and close on iOS, with a relaunch in the
iOS reference CI for a first launch that printed nothing. Then tools 0.3.14.
The entry below is kept as written.

**2026-10-28.** #158, #159 and #160 are merged (rows above). In review: the
rest of K1 on Android (`screen-failed`, the debug-surface audit, routes and
close), then `RequestPolicy`, routes and close on iOS. Then tools 0.3.14 (no
libraries release: nothing Maven-published changed since 0.4.1). The device
proof (K4) and the iPhone run wait on devices. The entry below is kept as
written.

**2026-10-27 (evening).** Live updates on a physical Android phone are
checked (row above: a Galaxy A12, from the published tools 0.3.13 and libraries
0.4.1). The iPhone run waits on the owner (Xcode needs an Apple ID). Next: the
small follow-ups from the reviews and the device run (the relay preamble's
order, per-listener locking on `/doc-events`, the doubled "stopped listening"
log line, an LV4 re-listen check, an attempt counter on `Unavailable`, the iOS
template's lifecycle dependency, U39), then the rest of the host
kit's K1 on Android, K2 on iOS and the K4 device proof, then tools 0.3.14. The
entry below is kept as written.

**2026-10-27 (afternoon).** The reference app is on the published 0.3.13
(#154; its runs were the public-download check), iOS live updates (#150), the
K1 fix (#153) and host kit part 1 (#155) are merged. Next: the rest of the host
kit's K1 (`RequestPolicy`, `ExternalLinkPolicy`, routes and close, the
`screen-failed` outcome), then K2 on iOS; a physical-device check of live
updates when the owner has a phone free. The entry below is kept as written.

**2026-10-27 (morning).** Libraries **0.4.1** and tools **0.3.13** are
released (rows above). Next: the reference app on the published 0.3.13
(#154, whose runs are the public-download check), iOS live updates (#150,
green on the simulator, held until this release), the K1 test fix (#153),
then the brownfield host kit (ROADMAP item 7). The entry below is kept as
written.

**2026-10-27 (night).** #145–#149 are merged (rows above), each after an
independent review and with every check green on its exact head. Next: libraries
**0.4.1** (this PR, then tag `v0.4.1` and `publish-maven-central.yml`), so that
tools **0.3.13** can scaffold per-app editors on it; then tools 0.3.13 by
`PORTAL_TOOLS_RELEASE.md`; then iOS live updates (draft PR #150) and the
brownfield host kit (ROADMAP item 7). The entry below is kept as written.

**2026-10-27.** The editor program is merged (#140, #141; rows above) and
**unreleased**: no tools release since 0.3.12. The roadmap's adoption items
now carry status lines, and Live for an adopter's presenters is deferred
(ROADMAP item 9). The owner handed over end-to-end ownership on 2026-10-27,
with merges and tools releases authorized when checks pass (no Maven
publish, no key changes, no destructive cleanup without asking). Next, in
order: the documentation pass, then the prioritized work in
`docs/ROADMAP.md` ("Current priorities"), starting with the debug host that
picks up a publish while open. The 2026-10-21 entries below are kept as
written.

**2026-10-21 (later).** Tools **0.3.12** is released (row above): new apps get
the 0.4.0 libraries, and the editor carries K6/K7 and the compiled component
preview. The reference app is on the published 0.3.12 (#139), which checked the
public download from GitHub runners. Next: U38 (the editor's cache across editors),
and the product-proof app's run of the 0.3.12 editor on the phone. The entry
below is kept as written.

**2026-10-21.** Libraries **0.4.0** are on Maven Central (row above). K7 (#134)
and U37 (#135) are merged. On the S25, the product-proof app's refactored
bundle (sequence 13) renders pixel-identically to the previous one (12) on
home, the calculator and order history (screenshots compared pixel by pixel
below the status bar, 0 differing pixels; which bundle ran is from the host's
`codeLoadSuccess … sequence=N` log line; kept with the app's private notes). Next: a tools release on the owner's
go (K6, K7, the compiled component preview's tools side, U37, scaffolders on
0.4.0). iOS M2 is deferred by the owner. The 2026-10-20 entry below is kept
as written.

**2026-10-20.** K6 (#131) and the compiled component preview (#132) are
merged, and relay tests no longer touch the developer's store (#130). The
product-proof app's screens no longer use any opaque component; two
screen-called components are still not fully clean (each contains a
component with Int arithmetic). Next: **K7** (Int arithmetic over parameters, Int → Dp,
and `.uppercase()` only if the js and wasm targets provably agree), then a
libraries **0.4.0** candidate (local publish + apiCheck) for the owner's go,
then a tools release on the owner's go. Still needs the owner: the S25 run of
the refactored product-proof app (before/after device screenshots), iOS M2
(signing team). The 2026-10-19 entry below is kept as written.

**2026-10-19.** K1–K5 are merged (#120–#126) and tools **0.3.11** is released
(row above), with U35/U36. The reference app is on the published 0.3.11
(#128, its public download checked from GitHub runners, by hand from the zip's
own scaffolders too, except the iOS host scaffolder's self-test, which runs
this checkout's identical script). Next: the product-proof app's components move into the
grammar, checked by before/after device screenshots; the next library release
(0.4.0: portal-document's API changed in K2–K4) on the owner's go. The 2026-10-17 entry below is kept as written.

**2026-10-17, later.** Tools 0.3.10 is released (#117), the reference app is on
it (#118), M2 on Android is done (#119). The owner then found that the editor
draws every component of the product-proof app as an opaque placeholder: the
app followed D9 for screens (0 RawCode) but no gate checked components, and the
grammar lacked layout modifiers, Margin values and design tokens. Owner's
decision: fix both halves, Keliver first. In order:
- **K1** layout modifiers and Margin values in the grammar: **merged (#120)**.
- **K2** design-token references (`C.Ink`, `Assets.Logo`): PR #121, in review.
- **K5** canvas fidelity (images through the relay for declared hosts, Theme,
  component instance modifiers, the opaque label): in progress, stacked on K2.
- **K3/K4** one component per file stays the rule; the editor names why a
  component is opaque, and the D14 bar and a check fail on opaque components.
- Then the product-proof app's components move into the grammar, checked by
  before/after device screenshots; tools release on the owner's go.
M3 is proposed in `reference/inventory/m2/results-2026-10-17/README.md`
("where the time goes"); iOS M2 needs the owner (signing team).

**2026-10-16.** Libraries **0.3.4** are on Maven Central (row above). Next, from
`ADOPTION_PLAN_2026-10.md`: debug hosts defaulting to `on-resume` updates,
the first-publish Kotlin/JS compiler error, timing marks in the host templates, and a
tools release whose scaffolders wire 0.3.4. The 2026-10-14 entry below is kept as written.

**2026-10-14, current state.** Tools **0.3.9** is published (W8, the W3
hardening, U32), and `main` holds #98, #100 and #101. #102 (merging) pins the reference app to the published 0.3.9 (published-only proof: device 160/0, `ios.sh`
147/0, K1–K8 from a published zip). **Next:**
- **W7 (the owner, with hardware):** the session is in `docs/DEVICE_PROOF.md`.
  It uses the published 0.3.9, whose iOS host takes `TEAM_ID` (U32). It covers
  a USB Android phone (`KELIVER_DEVICE=physical`), an iPhone
  (`ci/w7/ios-device.sh`, the owner's Apple team), HTTPS with a real
  certificate, a release-signed APK and IPA, and the W2 embed in a real app.
- **The owner can still change the W8 decisions** (W8 design, "Decisions").
- **Open, recorded:**
  - the W3 deadline and the size cap have no device row;
  - "same origin" is not a boundary;
  - a host that jumps straight from the old key to the new one starts the new
    key's floor at 0;
  - D14 screenshots: `google_apis` captures are VALID and `aosp_atd` captures
    are black, so moving CI's emulator image is the owner's call.

*The blocks from here to "Standing constraints" are the 2026-10-07 to
2026-10-12 next-action history, kept as written; the PRs they call open have
all merged since (Status table).*

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
- **Done 2026-10-11: tools 0.3.8 released** (candidate 3, W3–W6), and #94–#97
  merged into `main` (Status).
- **PR #99** moves the reference app to the published 0.3.8 and makes
  `KELIVER_SCAFFOLD_FROM` follow the trigger: a pull request tests its own
  `scripts/`, a run started by hand only the published zip. Published-only and
  PR runs are green. It also raises `ci.yml`'s job limit to 90 minutes: on
  cloud macOS the full graph took 50–60 minutes, and the last test steps kept
  being cut off as "cancelled". **Merged 2026-10-11** (`7a58eadc`) after its
  full `ci.yml` passed in 63 minutes (38160559466).
- **W8 (PR #98):** the owner decides on the rotation design. Then build it,
  with an independent review, CI, and a tools release (0.3.9) with approval.
- **W7** needs the owner: physical devices and an adopter. The preparation is
  on PR #100: follow `docs/DEVICE_PROOF.md` in order (Android harness on the
  phone first, then the release-signed builds against a real-certificate
  server, then the iPhone, then the embed proof).

## Standing constraints (from the owner; they apply to every step)

- **2026-10-27, the owner's handover:** merging reviewed changes and
  publishing **tools-bundle** releases are authorized when the required checks
  pass, preserving the verified artifact's identity (never rebuild or
  substitute release bytes). Still only after discussing the specific action
  with the owner: a **Maven** (libraries) publication, exposing or rotating
  private keys, deleting user data, destructive cleanup. Never move or
  recreate a released tag. (Before 2026-10-27: no merge, tag, release or Maven
  publication without explicit approval for each.)
- Pushing review branches, opening PRs, and running build-only/device CI are
  allowed.
- Never touch `~/.keliver-portal`, its keys, `~/.gradle/caches`, or the real
  `~/.konan`. Never print private keys. Use disposable stores, user homes,
  Gradle homes and `KONAN_DATA_DIR`, behind `keliver_require_isolated_store`.
- No protection bypass, force-push or branch deletion. Stop only the processes,
  and remove only the temp dirs, that this run created.
- Report completed work and evidence, not hours. Don't call the reference app
  an external adoption, and don't claim a platform that wasn't executed.
- The PR stacks #81–#93 (2026-10-10) and #94–#97 (2026-10-11) are merged;
  this plan's newest copy is on `main`.
