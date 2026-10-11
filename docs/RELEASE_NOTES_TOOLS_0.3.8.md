# keliver-portal-tools 0.3.8 — release notes

**Status: RELEASED 2026-10-11** (candidate 3), on the owner's approval
("you have access to push release", 2026-10-11).

| | |
|---|---|
| tag | `portal-tools-v0.3.8` (annotated, object `ebf64054`) → **`731ebfcefca14f3c9766d782cedb6982086729a7`** |
| release | [portal-tools-v0.3.8](https://github.com/waliasanchit007/keliver/releases/tag/portal-tools-v0.3.8), published 2026-10-11T15:24:08Z, `--latest=false` (the "Latest" badge stays `v0.3.3`) |
| zip asset | `keliver-portal-tools-0.3.8.zip`, 90,584,445 bytes; sha256 **`aafed0d810243bf0a44e800f4ad3e958eda443ee138af49bbae432b2b0be912d`**: uploaded from the retained artifact `11700407805` of build `38133756571` (downloaded and hashed first), never rebuilt |
| `.sha256` asset | 97 bytes, sha256 `b475fe60832ed41bdb0f60acbd8678587071a447e7c2fc8c53092428d978df16` |
| tag push | ran only `portal-tools.yml`'s read-only rebuild (`38150887414`), as step 3 said; its artifact is not attached |

**How the stored asset was checked** (procedure step 4). Downloading the draft
asset back stalled at 0 bytes: this machine's network blocks the asset host,
as for 0.3.5. So, as the procedure allows only in this case:
1. Before publishing, both stored assets' server-side `digest` and `size`
   were compared with the verified files, and matched: zip
   `sha256:aafed0d8…`, 90,584,445 bytes; `.sha256` `sha256:b475fe60…`, 97
   bytes. The tag was re-checked at `731ebfce`. This is GitHub's record of the
   stored bytes, not a download.
2. After publishing, the public `.sha256` sidecar downloaded here (97 bytes,
   naming `aafed0d8…`). The public zip is checked from GitHub-hosted runners:
   `reference-app.yml` (38151355318) and `ios-host.yml` (38151386539),
   dispatched on PR #99's branch. Their first step runs `sha256sum -c` against
   the release's own `.sha256`, then checks the pinned hash.

Tools 0.3.7 and earlier, and the Maven 0.3.3 libraries, are unchanged. The
tools version and the library version are separate lines: 0.3.8 still
scaffolds against `dev.keliver:*:0.3.3`.

The released candidate is **candidate 3** (W3, W4, W2, W5 and W6). Its
verification is recorded under "Candidate verification" below; the commit that
records it is not the built commit. **Candidates 1
(`c0e71108`, zip `7ea7f11d…`) and 2 (`ef625050`, zip `32d47c34…`) are
superseded: never tag them.** Candidate 1 carried W3 and W4; candidate 2 added
W2 and W5. The owner held each release until more was done.

## New: publish without the relay — `bin/keliver-publish` (W3)

```bash
keliver-publish [app-dir] --out DIR [--public-key-file PATH] [--channel stable] [--skip-build] [--init]
```

- **What it does.** It builds the app's `publishTask`. It refuses a bundle that
  isn't signed with the app's key or is incomplete, and then writes nothing.
- **What it writes.** `DIR/bundles/v<N>/` (the compile output, unchanged) and
  `DIR/bundles/index.json` (format 1). Serve `DIR` from any static host or CDN
  over HTTPS.
- **The live index is the state.** Download the served `bundles/` first.
  Without an index it refuses, unless `--init` marks the first publish.
- **CI.** `KELIVER_SIGNING_KEY_FILE` names the private key for the signing
  block. Nothing prints it.

The production hosts these scaffolders write read `bundles/index.json`. They
fall back to the relay's `/bundles/latest` only on a 404.

## New: release controls (W4)

- **Signed sequence.** Every publish signs its sequence into the manifest
  (`metadata["keliver.sequence"]`).
- **Rollback floor.** Hosts keep the highest sequence they have run, per key,
  and refuse anything below it: on the network, from the cache, and on the
  cache start's network fallback.
- **Rollback.** `--republish <version>` publishes an older bundle's unchanged
  modules as a new sequence. The app's `keliverResign` task signs them, and
  nothing is compiled.
- **Channels.** Both host scaffolders take `--channel` (default `stable`). A
  host takes its own channel and stable. `--promote <sequence> --channel <name>`
  offers a published bundle on another channel, with no private key.
- **Staged rollouts and host-version gates.** `--rollout <percent>`,
  `--min-host-version` and `--max-host-version` go on a publish, republish or
  promotion. `--set-rollout <sequence> --rollout <percent>` raises or halts a
  rollout without a key. Hosts that already ran a bundle keep it.

Details: `docs/DEVICE_HOST.md` §2–3, the adopter guide's "Publish from CI to a
static server", and the tools README.

## New: embed Keliver in an existing app (W2)

Both host scaffolders take `--embed --into DIR`:
- **Android:** `keliver-new-production-host.sh --embed` writes a library module
  (`DIR/keliver-host/`). It holds `KeliverHost` (one per process, made in your
  `Application`), the `KeliverScreen` Composable and `KeliverView`. Settings
  are in `keliver.properties`. Your build supplies Kotlin 2.2.0, its Compose
  plugin and `app.cash.zipline` 1.22.0.
- **iOS:** `keliver-new-ios-host.sh --embed` writes the `KeliverHost`
  framework's Gradle build (`DIR/keliver-host-ios/`), `KeliverScreen.swift` and
  `EMBED.md`, which lists the Xcode edits.
- **Your files are not edited:** the scaffolders print the lines to add.

## New: updates while the app runs, and the fall-back to the last good bundle (W5)

- **Fall-back.** When the bundle the lookup names fails to load (a sha256
  mismatch, the floor, a signature, a missing `v<N>/`) and one ran before, the
  host restarts from Zipline's verified cache instead of showing nothing. The
  floor still holds. Without one, it shows "Bundle did not load".
- **The update API.** `checkForUpdate()`, `currentBundle` and update events
  (applied, failed, fell back).
- **Updates on resume.** `keliver.updates=on-resume` (Android) or
  `UPDATES = "on-resume"` (iOS `HostConfig.kt`) applies a newer bundle in place
  when the app comes back to the foreground, at most every 30 s. The default is
  `next-launch`.

## New: host reports, and the relay as a collector (W6)

- **Reports.** Every outcome a host meets becomes a `KeliverReport`: loaded,
  fell back, update applied, update failed, not loaded, no bundle, refused.
  The app gets them in code (Android `KeliverHost.reports`, iOS
  `Keliver.onReport`), for its own analytics or crash keys.
- **An opt-in report URL.** With `keliver.reportUrl` (Android) or `REPORT_URL`
  (iOS `HostConfig.kt`), the host also POSTs each one as JSON, best effort.
  - Exactly eight fields, with a fixed `reason` category, never the error
    text.
  - The URL takes no `user@`, query or fragment, and is never logged.
  - Empty by default: nothing leaves the device.
- **The relay as a development collector.** `POST /report` takes those
  records, and refuses anything else; it keeps at most 20,000 under the store.
  `GET /reports` and `GET /reports/view` show counts per platform, channel and
  sequence.

## Changed: the hosts (W2)

The scaffolded hosts are rebuilt around one host per process:
- Android: `KeliverHost` plus a thin `MainActivity`; iOS: a `Keliver` object.
- A rotation or a second screen loads nothing again.
- The trust key is compiled into the build (`BuildConfig` on Android) and
  checked there.
- "No bundle" is retried by the next screen that opens.
- A failed load shows a message instead of a blank view.

## Changed: the signing block (v2, with sequence and `keliverResign`)

`keliver-new-publish-target.sh` writes a block that:
- reads `KELIVER_SIGNING_KEY_FILE`;
- signs the publish sequence;
- registers `keliverResign`.

Apps wired by 0.3.6 or 0.3.7: **run `keliver-new-publish-target.sh` again.**
It replaces exactly either released block, changes nothing else, and refuses
an edited one. The U31 property is unchanged: the private key is never a task
input, a process argument or a log line.

## Changed: the candidate build checks the packaged `keliver-publish`

`portal-tools.yml` runs `scripts/keliver-publish-selftest.sh --package` against
the candidate zip's own `bin/keliver-publish` and `relay/`. The reference app's
harnesses (`ci/prepare.sh`, `ci/w3/ios-static.sh`) use the zip's
`bin/keliver-publish` and `bin/keliver-new-publish-target.sh` when the zip
ships them. In candidate mode a missing one is a failure, not a fallback.

## Upgrading from 0.3.7

- **Hosts.** Hosts scaffolded by 0.3.7 or earlier don't read
  `bundles/index.json` and have no rollback floor, channel or constraints. The
  templates are copied at scaffold time, so re-scaffold (or port the template
  changes) to use static publishing. Hosts from before W4.5 skip any index
  entry that carries `constraints`.
- **Signing block.** Upgrade it as above.
- **Hosts scaffolded by 0.3.7** have none of W2's or W5's changes: no embed,
  no fall-back, no update API. Re-scaffold, or port the template changes.

## Known issues

- **Device data.** A reinstall or "clear data" resets the host's rollback floor
  and install id, and device backups copy both to a new device.
- **The relay.** Its `/publish` has no republish, channels or rollouts; the
  relay is the development route.
- **Proof.** Nothing has run on physical devices yet (plan W7).
- **The next signing-block change** must first add this block to
  `templates/publish/legacy/` as `signing-0.3.8.gradle`, so apps wired by 0.3.8
  can upgrade.
- **Embedding on Android** needs the app to build with Kotlin 2.2.0 (Zipline
  1.22.0's compiler plugin). A prebuilt host library would remove that; it is
  not published. CI builds one combination of AGP, Compose, OkHttp and Coil.
- **Not measured:** native navigation around an embedded iOS screen (no
  simulator tap driver), an iOS app that already embeds a Kotlin framework,
  and an XCFramework build.
- **Updates.** A process that started from the cache takes a newer bundle at
  its next start. An update that failed is not tried again in that process.
- **Reports.** They go out best effort: one try, no queue. A report URL
  receives the install id, a persistent pseudonymous identifier, and like any
  request the sender's IP address.
- **Carried over.** The 0.3.7 notes' known issues that are not fixed above.

## Candidate verification (recorded after the build; not in the built commit)

### Candidate 3 — `731ebfce` (W3, W4, W2, W5, W6): the one to release

It is built from `feat/w6-visibility` (PR #97, on #96, #95 and #94, with
`main` merged in). The verifiers ran from the same branch, at `731ebfce`.

| | |
|---|---|
| source commit to tag | **`731ebfcefca14f3c9766d782cedb6982086729a7`** (`VERSION.json` `sourceCommit`, `sourceDirtyFiles: 0`; tools 0.3.8, Maven dependency 0.3.3) |
| zip | `keliver-portal-tools-0.3.8.zip`, 90,584,445 bytes; sha256 **`aafed0d810243bf0a44e800f4ad3e958eda443ee138af49bbae432b2b0be912d`** |
| bundled dev-host APK | sha256 **`1a0b6d920ed46c8008c6d9a8c61f63369ae42f9f55d12734f787d8a3e47fff43`**, no embedded portal key |
| build run | [`38133756571`](https://github.com/waliasanchit007/keliver/actions/runs/38133756571), retained artifact `11700407805` (90,573,343 bytes, `sha256:64458570…`), built 2026-10-11. Every portable check is green: the packaged `keliver-publish` self-test 40/0; the production-host scaffolder 65/0 (`--build`) and 60/0 (zip); the iOS scaffolder 62/0 twice; the publish target 41/0 (`--build`) and 25/0; U27 63/0/0; the relay's tests, `HostReportsTest` included |
| device run | [`38134971077`](https://github.com/waliasanchit007/keliver/actions/runs/38134971077): the APK pinned by sha256 (`1a0b6d92…`). Device checks 19/0, packaged acceptance 28/0 |
| local check | The downloaded zip and its APK match the build run; `VERSION.json` is as above; there is no `portal_ed25519.pub` in the APK. Every `bin/` script is 755, and `relay/bin/keliver-publish-jvm` is present. There are no key files. 57 packaged files are byte-identical to the commit (templates, scripts, `host/README.md`, `README.md`, the guide inside the MCP jar), and the relay jar carries `HostReports`. The packaged `keliver-publish` self-test, rerun against the downloaded zip: 40/0 |
| iOS, the candidate zip itself | `ios.sh` with `KELIVER_CANDIDATE_SHA256=aafed0d8…` and `KELIVER_SCAFFOLD_FROM=zip`, on this Mac: Xcode 26.4.1, iOS 26.4, iPhone 17 Pro; a disposable simulator (deleted after), Gradle home and Konan dir. Every host and publish came from the zip's own `bin/`. **120/0.** This includes **V2**: the zip's relay counted the development host's reports of v1 and v2 at `GET /reports`, end to end. Also P1–P7; S2–S15 with S6/U2, S8 and S9c; I1, I2, I4, I5; U1; V1. Evidence: `docs/superpowers/evidence/tools-0.3.8-candidate3-ios/` |
| tag push | Unchanged since candidate 2 (no workflow file differs from `ef625050`): only `portal-tools.yml` matches `portal-tools-v*`, and it is read-only |

**Recommendation:** tag `portal-tools-v0.3.8` on `731ebfce` and attach
`keliver-portal-tools-0.3.8.zip` (`aafed0d8…`) from the retained artifact
`11700407805` (it expires about 2026-11-10), per
`docs/PORTAL_TOOLS_RELEASE.md` step 4.

### Candidate 2 — `ef625050` (W3, W4, W2, W5): superseded, never tag it

It is built from `feat/w5-update-api` (PR #96, on #95 on #94, with `main`
merged in). The verifiers ran from the same branch, at `ef625050`.

| | |
|---|---|
| source commit to tag | **`ef625050cbb5a1afd7cb2288567d6df3a35da2fa`** (`VERSION.json` `sourceCommit`, `sourceDirtyFiles: 0`; tools 0.3.8, Maven dependency 0.3.3) |
| zip | `keliver-portal-tools-0.3.8.zip`, 90,559,881 bytes; sha256 **`32d47c34b46594dc89c2feef43e3657bf24daa74af77bbff3d4535fd29b0ab68`** |
| bundled dev-host APK | sha256 **`106b2bd43eb84ad29b7fe4abc56eec74173466d2150f1f0b96af13613702f69b`**, no embedded portal key |
| build run | [`38078500722`](https://github.com/waliasanchit007/keliver/actions/runs/38078500722), retained artifact `11679304379` (90,548,700 bytes, `sha256:5ca56ace…`). Every portable check is green: the packaged `keliver-publish` self-test 40/0; the production-host scaffolder 65/0 (`--build`, the scaffolded host compiled) and 60/0 (from the zip); the iOS scaffolder 62/0 twice; the publish target 41/0 (`--build`) and 25/0; U27 key permissions 63/0/0 |
| device run | [`38079812823`](https://github.com/waliasanchit007/keliver/actions/runs/38079812823): the APK pinned by sha256 (`106b2bd4…`). Device checks 19/0, packaged acceptance 28/0 |
| local check | The downloaded zip (`32d47c34…`) and its APK (`106b2bd4…`) match the build run; `VERSION.json` is as above. The APK has no `portal_ed25519.pub`. Every `bin/` script is 755, and `relay/bin/keliver-publish-jvm` is present. There are no `.priv`, `.pem`, `.jks` or keystore files. 57 packaged files are byte-identical to the commit: every file under `templates/` (the host templates, the embed templates, the publish blocks and `legacy/`), every `bin/` script that has a counterpart in `scripts/`, `host/README.md` (= `DEVICE_HOST.md`), `README.md`, and the guide inside the MCP jar. The packaged `keliver-publish` self-test, rerun here against the downloaded zip: 40/0 |
| iOS, the candidate zip itself | `reference/inventory/ci/ios.sh` with `KELIVER_CANDIDATE_SHA256=32d47c34…` and `KELIVER_SCAFFOLD_FROM=zip`, on this Mac: Xcode 26.4.1, iOS 26.4 simulator, iPhone 17 Pro; a disposable simulator (deleted after), Gradle home and Konan dir. The app was recreated from the zip. Every host was scaffolded by the zip's own `bin/` (`keliver-new-ios-host.sh`, with `--embed` for W2), and every publish used the zip's `bin/keliver-publish`. **118/0:** P1–P7; S2–S15, including W5's S6/U2 and S8 (fall-back to the cached v2), and S9c (a refused fall-back: "Bundle did not load"); W2's I1, I2, I4, I5; W5's U1 (v9 applied on resume, same process, floor 8 -> 9, Cellar on screen). No screenshot needed a re-read. Evidence: `docs/superpowers/evidence/tools-0.3.8-candidate2-ios/` (results, console logs, screen readings) |
| tag push | At `ef625050`, only `portal-tools.yml` matches `portal-tools-v*`, and every job in it is read-only (`contents: read`; the device job also `actions: read`). `publish.yml` fires on `v*` only, `ci.yml` ignores tags, and `pages.yml` runs only on pushes to `main`. Since candidate 1 (`c0e71108`) only the path filters of `ios-host.yml` and `reference-app.yml` changed |
| PR CI at the same code | `7afb8498` (the code of `ef625050`; only docs changed since): reference-app 38073852570, device 133/0; ios-host 38073852556, `ios.sh` 118/0 |

Candidate 2 was verified, then superseded on 2026-10-11 when W6 was done
before the release. It is kept here as a record.

### Candidate 1 — `c0e71108` (superseded: never tag it)

It is built from `release/portal-tools-0.3.8` (PR #94), stacked on W4 (#93).
The verifiers ran from the same branch, at `c0e71108` and then `a60624f0`
(see the iOS row).

| | |
|---|---|
| build run | [`37932744136`](https://github.com/waliasanchit007/keliver/actions/runs/37932744136): every portable check green. **New: the packaged `keliver-publish` self-test 40/0**, run against the candidate zip's own `bin/keliver-publish` and `relay/`: publish, the refusals, `--republish`, `--promote`, constraints and `--set-rollout` with no key. The publish target is 41/0 with `--build` (the real `keliverResign`) and 25/0 from the zip. Also: the production-host scaffolder 45/0 and 40/0, the iOS scaffolder 48/0 twice, and U27 key permissions 63/0/0 |
| device run | [`37935499926`](https://github.com/waliasanchit007/keliver/actions/runs/37935499926): the APK pinned by sha256 (`afa9dadd…`). The device checks are 19/0 and the packaged acceptance 28/0 (a signed v1 stored, an unsigned bundle refused and not stored, the production refusal cold and warm) |
| local check | The zip (`7ea7f11d…`) and APK (`afa9dadd…`) hashes match the build run. `VERSION.json` is as above. The APK has no `assets/portal_ed25519.pub`. `bin/` scripts are 755, and `relay/bin/keliver-publish-jvm` is present. These are byte-identical to the commit's: the bundle's `templates/publish/signing.gradle` and both `legacy/` blocks, both hosts' `BundleIndex.kt`, `bin/keliver-publish`, and the three scaffolders. So is the guide inside the MCP jar (`PORTAL_USAGE.md`). There are no `.priv`, `.pem`, `.jks` or keystore files. The packaged `keliver-publish` self-test was rerun here against the downloaded zip: 40/0 |
| iOS, the candidate zip itself | `reference/inventory/ci/ios.sh` with `KELIVER_CANDIDATE_SHA256=7ea7f11d…`, on this Mac (Xcode 26.4.1, iOS 26.4 simulator, iPhone 17 Pro; disposable simulator, Gradle home and Konan dir). The app was recreated from the candidate zip. The host was scaffolded by the zip's own `bin/keliver-new-ios-host.sh`. W3/W4 used the zip's own `bin/keliver-publish` and `bin/keliver-new-publish-target.sh` (the app already had the current block). **Run 1: 91/1.** The one FAIL was the harness, not the candidate: P2 expected the host to fall back to `/bundles/latest`, which only a 0.3.7-or-earlier relay forces, but the candidate's relay serves `bundles/index.json` and the host correctly looked v1 up through it. The harness was fixed in `a60624f0` (P2 now asks the relay which lookup applies). **Run 2, with that fix: 92/0**, including P1–P7 and S2–S15 (republish, channels and promotion, rollout 0/100/halt, the host-version gate, and the rollback floor on the network, the cache and the fallback). Evidence: `docs/superpowers/evidence/tools-0.3.8-candidate-ios/` (both runs' results; run 2's console logs and screen readings) |
| tag push | At `c0e71108`, only `portal-tools.yml` matches `portal-tools-v*`, and it is read-only (no job with `contents: write`). `publish.yml` (`packages: write`) fires on `v*` only. `pages.yml` (`id-token: write`) runs only on a push to `main`. `ci.yml` ignores tags. `ios-host`, `reference-app`, `compat-matrix` and `publish-maven-central` don't trigger on tags. Since 0.3.7 only `ios-host.yml`, `reference-app.yml` and `portal-tools.yml` changed; `portal-tools.yml` gained one read-only step |

Candidate 1 was verified, then superseded on 2026-10-10 when the owner deferred
the release until W2 and W5 were in. It is kept here as a record.
