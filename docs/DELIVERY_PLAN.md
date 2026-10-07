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
  - a mismatch fails the load.
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
manifests.
- A host built from the new templates fetches `index.json`.
- Only on **HTTP 404** for it (a relay from tools 0.3.7 or earlier) does it
  fall back to `/bundles/latest`.
- A network failure is not a 404, so it never adds a second timeout before
  the offline start.

**`keliver-publish`**, in the tools bundle's `bin/`:

```
keliver-publish [app-dir] --out <dir> [--public-key-file F] [--channel stable] [--skip-build]
```

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
   nothing is written. A malformed existing index is refused, never
   overwritten.

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
| W1 I1 scaffolder | **built, measured locally 2026-10-07.** `keliver-new-ios-host.sh` + `templates/ios-host/`; self-test 38/0, and 43/0 with `--build` (xcodebuild). The scaffolded host loads signed v1, then v2 after an edit (Inventory → Stockroom); the real SQLite `IosSqlHost` passes 5/5. | `docs/superpowers/evidence/ios-host-i1/` |
| W1 I2 iOS CI | **done 2026-10-07.** `ios-host.yml` run `37597390396`, then `37602257459` after the independent review's fixes (macos-15, Xcode 16.4, iOS 26.2): self-test 48/0, P1/P2/P4–P7 30/0. Review: nothing blocking, all points fixed. | `docs/superpowers/evidence/ios-host-ci-37597390396/`; run `37602257459` |
| W1 I3 spike fallback | **deferred.** `portal-device-ios` is built only from Keliver source, and no CI job compiles it. It never reaches adopters, who get the scaffolded host. Fix it when that module is next built. | — |
| W1 I4 docs + 0.3.7 | docs written (adopter guide "Ship to production" iOS; `DEVICE_HOST.md` §3; tools README). **0.3.7 needs the owner's approval.** | this branch |
| W3 design | **done 2026-10-07**: a static `bundles/index.json` (format 1). Each entry is bound by its manifest's own signature plus `manifestSha256`; the index is unsigned. Anti-rollback is deferred to W4. | "W3 design" above |
| W3 `keliver-publish` + index-reading hosts | **built, CI green 2026-10-07** on PR #90 (`feat/w3-static-publish`, stacked on #88). The CLI verifies the signature and every module's sha256, then writes `v<N>/` and an atomic `index.json`; it refuses and writes nothing otherwise. The relay also serves `/bundles/index.json`. Both host templates read the index, pin the manifest's sha256, and fall back to `/bundles/latest` only on a 404. `KELIVER_SIGNING_KEY_FILE` covers CI signing. Tests: `StaticPublishTest` 12, `BundleIndexTest` 7, `keliver-publish-selftest.sh` 16/0, Android and iOS host self-tests with builds 40/0 and 49/0. | PR #90 |
| W3 static HTTPS on CI | **green 2026-10-07**. No relay; a static HTTPS server is fed only by the CLI, with a throwaway CA in the emulator's system store and the simulator's keychain. S2 (v1 through the index), S4 (v2), S5 (CLI refuses a foreign key), S6 (wrong sha256, nothing loads), S7 (offline from the cache). Android `reference-app.yml` run 37629943998: prepare 18/0, device 54/0. iOS `ios-host.yml` run 37629943934: self-test 49/0, `ios.sh` 48/0. | `docs/superpowers/evidence/w3-android-ci-37629943998/`, `w3-ios-ci-37629943934/` |
| W3 remaining | not yet in a released tools bundle (it needs a release after 0.3.7, with approval). A GitHub Actions recipe is in the adopter guide. The independent review is pending. | — |
| W2, W4–W8 | not started | — |

## Next action

**On 2026-10-07 the owner said: start both in parallel — the tools 0.3.7
release and W3.**

**Track A: tools 0.3.7.** Ship `keliver-new-ios-host.sh`, following
`docs/PORTAL_TOOLS_RELEASE.md`.
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

**Track B: W3 — built and green on CI (PR #90, see Status).** Remaining:
- an independent review of PR #90;
- shipping it in a tools release after 0.3.7, only with the owner's approval.

Then W4 (rollback protection through a signed sequence in the manifest's
`metadata`, channels, rollout, host-version `constraints`), which builds on the
index format. The original Track B steps were:
1. Design the static index, `bundles/index.json`:
   - entries carry version, widgetVersion, caps, manifest URL, manifest
     sha256, createdAt, and a monotonically increasing `sequence` (for W4);
   - decide whether the index is signed (Ed25519, same key) or each entry is
     bound by its manifest's own signature plus the manifest hash.
2. A `keliver-publish` CLI (in the tools bundle). Without the relay, it
   compiles, verifies the signature (reuse `publishedSignatureProblem`), and
   writes `bundles/vN/…` plus the index into an output directory.
3. Both hosts (the Android template and the iOS template) look up through
   the index when the server has no `/bundles/latest`, or always.
4. CI: publish with the CLI into a static server over HTTPS (a self-signed CA
   installed in the emulator and simulator), then run P2/P4/P7 against it.

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
