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
| Distribution | bundles served by the local relay (`/bundles/latest?widgetVersion&caps`, `/bundles/vN/…`). No static/CDN layout, no HTTPS run. |
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

### W5 — Update API (G8)

The host exposes:
- `checkForUpdate()` and `currentBundle()`;
- an update policy (at launch, on resume, periodic) and when to apply (next
  launch or immediately);
- events: downloaded, applied, failed, refused (with the reason).

*Done when:* unit tests plus one CI scenario (an update applied on resume)
pass.

### W6 — Visibility (G9)

- **A reporter interface** on the host. Events: install id, bundle sequence,
  channel, outcome. No default backend; a documented example posts to a URL.
- **The current bundle sequence set as a crash key** (Crashlytics/Sentry
  examples).
- Optionally, **a relay `/report` endpoint** that shows counts in the portal.

*Done when:* events arrive in CI from both platforms.

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
| W1 I1 scaffolder | in progress. **The production host already runs on a simulator against a real signed bundle from the published 0.3.6 tools: v1 loads and renders, the offline start comes from the cache, and a foreign key is refused** (local). Scaffolder and templates not written yet. | `docs/superpowers/evidence/ios-host-i0/signed-run/` |
| W1 I2 iOS CI | not started | — |
| W1 I3 spike fallback | not started | — |
| W1 I4 docs + 0.3.7 | not started (needs approval to release) | — |
| W2–W8 | not started | — |

## Next action

W1 I1, continued. The host code and Xcode shell are proven locally
(`docs/superpowers/evidence/ios-host-i0/`: `proj/`, `app/`, `signed-run/`).
Now:
1. Turn `proj/` + `app/` into `scripts/templates/ios-host/`:
   - `@@PACKAGE@@`, the key, the bundle server and the API base are
     substituted at scaffold time;
   - the app's `PRODUCT_NAME` must differ from the framework's `baseName`;
   - there is no `DEVELOPMENT_TEAM`;
   - the ATS `http` exception applies to localhost and Debug only.
2. Write `scripts/keliver-new-ios-host.sh`, with the same refusals as the
   Android scaffolder (key checks, all-or-nothing writes), and its self-test
   with `--build`.
3. Replace the in-memory `IosSqlHost` with real SQLite (`platform.sqlite3`),
   and add network images.
4. I2: a CI workflow on a hosted macOS runner, triggered on `pull_request`
   paths, running iOS P1–P7 on the reference app. That covers the cases
   measured locally plus v1 → v2 and no empty-URL load.

To reproduce the local run, see `signed-run/README.md`. Run the isolation
guard under **bash**, not zsh. Stop the relay with `keliver-portal stop`,
and delete the disposable simulator.

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
- The open PR stack (none merged): #81 ← #82 ← #83; #81 ← #84 ← #85 ← #86 ← #87
  (#87 = the 0.3.6 release branch, with #82 and #83 merged in). This plan's
  branch stacks on #87.
