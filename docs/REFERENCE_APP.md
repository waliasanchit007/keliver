# The reference app — what the published route costs an adopter

**2026-09-22/24.** An inventory app built outside this checkout from the
**published** `keliver-portal-tools` 0.3.5 release and `dev.keliver:*:0.3.3` on
Maven Central, following [`PORTAL_ADOPTER_GUIDE.md`](PORTAL_ADOPTER_GUIDE.md),
then checked against
[`reference/inventory/EXPECTATIONS.md`](../reference/inventory/EXPECTATIONS.md) —
committed in `1ea4356d9`, the head the first device run (35800642819) tested,
and unchanged since. The macOS compile, ingest, edit and Live-preview runs of
2026-09-22 came before that commit; the Live-preview checks are defined in
`live-scenario.mjs`, not in EXPECTATIONS.

**This is dogfooding.** We wrote the app. It is not evidence that anyone outside
the project has adopted Keliver. Nobody has.

**Two routes, and only one of them is published-artifacts-only.**

* **The development route** uses nothing but the public tools zip and Maven
  Central: the guest app, `keliver-init` and the other scaffolders, the relay,
  ingest, the `/ops` write-back, `/publish` and guest signing, and the bundle's
  generic development host APK. E1–E10 and D1–D3 are this route.
* **The production route** needs a Keliver checkout: the production host is
  `portal-device-android` compiled from Keliver **source** at the tools
  release's commit `b5615637` — its Keliver libraries are that source, not the
  Maven Central 0.3.3 artifacts. Every one of P1–P6 depends on that host.
* **The CI harness** is this repository's: `ci/*.sh`, `ci/drive.py`, and the
  isolation guard (`scripts/keliver-test-isolation-guard.sh` and the
  `keliver-store-path.sh` it calls), which are not in the tools bundle.

Source and reproduction: [`reference/inventory/`](../reference/inventory) —
`bootstrap.sh` recreates the app from the public release; CI runs it end to end
(`.github/workflows/reference-app.yml`).

## What was verified, and where

| check | where it ran | result |
|---|---|---|
| public release download: `.sha256` and pinned hash `4e1c3040…` | GitHub-hosted runner | pass (runs 1 and 3) |
| `keliver-init` scaffold compiles against Maven Central 0.3.3, fresh Gradle home | macOS (local, output not committed) and CI | pass |
| two screens ingest with **0 RawCode** (10 and 13 nodes; `Condition` and `Repeat` recognized; full contracts) | macOS (local, not committed) and CI | pass |
| D14's write-back leg: an edit through the relay's `/ops` — 2-line surgical diff, `Compiled_inventory.kt` stamp, `logic/` byte-identical, recompiles | macOS (local, not committed) and CI | pass |
| signed publish: manifest carries a `portal-ed25519` signature from this app's store key | macOS (local, not committed) and CI | pass |
| **Live preview with the real presenters**: 21 checks, real mouse events on the canvas | macOS, headless Chrome 153 | 21/0 |
| development route on the bundle's generic host: E1–E10 | CI emulator, API 33 x86_64 | **30/0** (run 3) |
| D3: the edited title on the device after a rebuild (view hierarchy) | CI emulator | pass |
| **D14's device-render-screenshot leg** | CI emulator | **not met**: runs 1–4 took none; run 5 took six, all blank (see "Device screenshots") |
| production OTA: P1–P6 | CI emulator, production host built from `b5615637` | **all pass** (runs 1 and 3) |
| physical Android device | — | **not run** (none attached) |
| arm64 Android | — | **not run** |
| iOS | — | **not run** for this app |

### The CI runs

| run | head | result |
|---|---|---|
| [`35800642819`](https://github.com/waliasanchit007/keliver/actions/runs/35800642819) | `1ea4356d9` | **failed, 1 check**: E3. The *driver* cleared "bean" with four DELs and one was lost, so it typed `bzzz`; the app answered that query correctly (`0 of 8 items match "bzzz"`, the empty state, no rows), but E3 as written was not executed. Every other check, P1–P6 included, passed. The run also overwrote per-step view dumps (one label for five invocations). |
| [`35801890406`](https://github.com/waliasanchit007/keliver/actions/runs/35801890406) | `6fe677f03` | cancelled by me, to add per-invocation evidence labels before it finished |
| [`35802020305`](https://github.com/waliasanchit007/keliver/actions/runs/35802020305) | `ac7de5f75` | **pass**: prepare 13/0, device 30/0; E1–E10 30/0 including the input checks E2.in/E3.in; P1–P6 all pass |
| [`35803336957`](https://github.com/waliasanchit007/keliver/actions/runs/35803336957) | `5fadabaa3` | **pass** (the pull-request run at #81's first reviewed head; only the workflow trigger and README text differ from run 3): prepare 13/0, device 30/0, E 30/0 |
| [`35967437120`](https://github.com/waliasanchit007/keliver/actions/runs/35967437120) | `25249e8ff` | **pass** after the review's corrections: prepare 13/0, device 32/0 — adding P1's `devOnlyHost=false` and D3's E1–E10 re-run after the edit (30/0); E 30/0. Device screenshots: 6 of 6 **BLANK**, outside the count (below). Manifests and key modes kept. |

E3's assertion did not change between runs 1 and 3; the driver now clears with
spare DELs, reads the field back, and asserts what it typed as its own check.
`P5.results.json` reports two failures **by design**: they are the "is
`Foreign build` on screen?" probes, and `device.sh` records their failure as
the pass ("Foreign build never appeared").

### Live preview (macOS, headless Chrome, CDP)

`keliver-new-editor.sh` + `InventoryPreview.kt` (both screens, real
presenters). Local only; the editor's dependency resolution was not recorded, and
`bootstrap.sh` then unset `KELIVER_USE_MAVEN_LOCAL` only for `keliver-init` —
`keliver-new-editor.sh` writes `mavenLocal()` first when it is 1. It now unsets
it for every scaffolder. `keliver-portal` built `editor/` and reported
`Preview → …/editor/build/dist/wasmJs/productionExecutable (your real
presenters)`. Driven over CDP with real mouse events on the Compose canvas;
values read from the State Inspector. Script and captures:
[`superpowers/evidence/reference-app/live-preview/`](superpowers/evidence/reference-app/live-preview).

| id | check | result |
|---|---|---|
| L0 | mock mode before ▶ Live | pass |
| L1 | fidelity panel: `real presenter — inventory (real presenters)` | pass |
| L2 | real seed value `On hand: 12` | pass |
| L3 | three canvas taps on Add 1: `13`, `14`, `15`, each observed; history `3 adjustments: +1, +1, +1 (net +3)` | pass |
| L4 | ten taps on Remove 1: `14` … `5`, each observed; `isLowStock` false through 6, **true at 5** | pass |
| L5 | Receive 10 → `On hand: 15`; the action console logged `⚡ receive(10) → real presenter` — the literal action argument is delivered | pass |
| L6 | ■ Stop clears the live values | pass |
| L7 | a restarted session starts from the seed again (`On hand: 12`) | pass |
| L8 | the list screen, live: `8 items · 2 low on stock` (the summary is asserted; the eight rows are visible in `L-list-live.png`, not asserted) | pass |

**Not exercised in the preview:** typing into the search field on the canvas,
and navigation (the preview entry logs `open`/`back` rather than switching
screens — navigation lives in `logic/InventoryApp.kt`, which the preview does not
run). A passing preview is a wiring check; the device is where behaviour counts.

## What an adopter has to discover

In the order they were hit. **Severity** is for an adopter trying to ship, not
for us.

### Blocking production

1. **There is no published production host for keliver-material screens.** The
   bundle's `host/README.md` §2 says: copy `sample/host-android` from the
   Keliver repository. That host is built against the *sample's* schema
   (`SampleSchemaHostProtocol`: Box, Text, Column, …), so it cannot render a
   scaffolded app's `StyledText`/`ListItem`/`Button`. The host that can is
   `portal-device-android` — the same source as the bundled development host —
   and it is not published. Getting a production host therefore means a
   Keliver checkout and
   `./gradlew -Pkeliver.devOnlyHost=false -Pkeliver.portalStore=<the app's store> :portal-device-android:assembleDebug`.
   The store override is needed because inside the Keliver checkout the build
   would otherwise resolve **Keliver's** store, not the app's; it is a build-only
   override that warns rather than checks. None of this is in the adopter guide.
   Its production host also keeps the generic host's `applicationId`, reaches the
   relay at `10.0.2.2:8077` (an emulator address), and is a debug build.
2. **Publish is not scaffolded, and fails on a scaffolded app.** `POST /publish`
   runs `publishTask` from `keliver.portal.json`, default
   `:portal-published-guest:compileDevelopmentZipline` — Keliver's own module.
   On a scaffolded app that is, measured:
   `Cannot locate tasks that match ':portal-published-guest:compileDevelopmentZipline' as project 'portal-published-guest' not found in root project 'inventory'`.
   The fix is two keys (`publishTask`, `publishOutput`) **and** a signing block
   in `build.gradle` — the relay does not sign; the Zipline compile task does,
   with the store's private key. The block has to come **after** the `kotlin {}`
   block or the bundle compiles unsigned without an error; that rule is written
   down only in `portal-published-guest/build.gradle`'s comments.
   The reference app's version: `reference/inventory/app/build.gradle` (bottom).
   The adopter guide mentions published bundles and the publisher but does not
   describe how to publish — no `/publish`, no `publishTask`.

### Security

3. **The relay writes the private signing key world-readable.** On macOS with
   the default umask, `keys/ed25519.priv` is created `-rw-r--r--`
   (`Relay.kt#ensureKeys`, `File.writeText`). Any local user can read the key
   that signs production bundles. Not fixed in tools 0.3.5 (KNOWN_BUGS U27).
   The CI log prints the modes; from run 5 on they are also kept as
   `key-modes.txt`.

### Friction

4. **The device scaffolder wires exactly one screen and one presenter.** With
   two screens it stops and asks for `--screen`/`--presenter` (clear), then
   generates `Show() { InventoryScreen(InventoryPresenter()) }`. A presenter that
   takes its state as an argument does not compile there, so a navigating app
   hand-edits `device/Main.kt`. The scaffolder's own output does say to edit it.
5. **The first `keliver-portal` start with an `editor/` builds it silently.**
   Five minutes on a fresh Gradle home, with no progress output; afterwards an
   up-to-date check. `--no-editor-build` exists.
6. **`keliver-new-editor.sh`'s next-steps text** tells you to serve
   `build/dist/wasmJs/productionExecutable` yourself; `keliver-portal` already
   builds and serves it. Harmless, but two instructions for one step.
7. **Editor-canvas ListItems render at content width** — rows of different
   widths (`live-preview/L-list-live.png`). ROADMAP item 7 recorded a
   `fillMaxWidth()` fix with the "canvas visual check pending"; in the published
   0.3.3 editor the rows are still content-width. Which renderer path draws
   them was not traced.
8. **The production host logs a false `codeLoadFailed` on every start** — an
   empty manifest URL, before the bundle lookup answers (see *Production OTA*).
9. **The Bindings panel lags by one update — in every Live capture.** The
   Live-preview checks read the canvas and the State Inspector, which agree;
   the editor's *Bindings* panel shows the value before:

   | capture | canvas and State Inspector | Bindings panel |
   |---|---|---|
   | `L-item-live-12.png` | On hand: 12 | "mock value" |
   | `L-item-live-15.png` | 15, 3 adjustments | On hand: 14, 2 adjustments |
   | `L-item-live-5-low.png` | 5, 13 adjustments | 6, 12 adjustments |
   | `L-list-live.png` | 8 rows | "mock value", row count 3 |

   First written here as "observed once"; the independent review of #81 found
   it in all four, and that is confirmed. Not investigated. It may be related to
   U19 (live-preview re-render); nothing here shows that.

### This machine, not Keliver

10. **TLS interception.** Behind a corporate proxy that re-signs TLS, a fresh
   Gradle home fails the wrapper download (`PKIX path building failed`) until
   the proxy's trust store is configured (`systemProp.javax.net.ssl.trustStore`
   in the Gradle home's `gradle.properties`); Node needs `NODE_EXTRA_CA_CERTS`
   for the editor build. The same proxy blocks
   `release-assets.githubusercontent.com`, so the adopter guide's `curl -LO` of
   the release zip stalls at 0 bytes here. The zip used locally is the retained
   artifact whose hash equals the published asset's; the public download itself
   was checked from a GitHub-hosted runner.

### What worked without friction

* `keliver-init` → a project that compiles against Maven Central on a fresh
  Gradle home (1m16s locally, not recorded; 49s in CI).
* The grammar covered the whole app: search, an empty state, a list, a detail
  screen, conditional warnings, single-argument and literal-argument actions —
  **0 RawCode**, no escape hatches.
* The edit loop: one `SetProp` batch through `/ops` produced exactly the two
  changed lines and left every hand-owned file byte-identical.
* The per-app Live preview ran the real presenters on the first build.

## Production OTA

Every step on an `aosp_atd` API 33 x86_64 emulator on a GitHub-hosted runner,
with app-owned disposable keys generated by the relay inside the run directory.
Verification was never disabled: the development host from the bundle was used
for the development route only, and was not rebuilt, patched or reconfigured.

**The production host.** `portal-device-android` built from the tools
release's source commit `b5615637` with `-Pkeliver.devOnlyHost=false
-Pkeliver.portalStore=<this app's store>`. Its `assets/portal_ed25519.pub`
equals the store's `keys/ed25519.pub` (P1). Each run builds its own host and
its own keys, so the identities differ per run. The installed APK is kept in
each run's evidence artifact; for runs 1–4 only the manifests' sha256 (and v1's
signer and module count) were kept, and from run 5 on the manifests themselves
(`manifest-v1/-v2/-foreign.zipline.json`).

| | run 3 (`35802020305`) | run 1 (`35800642819`) | run 4 (`35803336957`) | run 5 (`35967437120`) |
|---|---|---|---|---|
| production host APK sha256 | `92af8ceebfec4cc8e47928ed20ff90b89dc50437bc1e5f23979b05c92ce1f2bf` | `58b7b5a640d0644b008f05b5ce1e8424606f13b6dba524b18fd9da8db1924f80` | `ccc7d915aa710aed7e42cf6473e8be8265e26f1fa4beb31fa92d6ca60f93d7f9` | `887774e95068aa869a1854de2e3c83e2337a70cc1e08a8d17da0879632237d32` |
| app key (store `keys/ed25519.pub`) | `4ebb499a…` | `e91529de…` | `e3f98d90…` | `df8437e6…` |
| foreign copy's key | `970839c6…` | `6a825f3b…` | `81734293…` | `249349a9…` |
| v1 manifest sha256 (title Inventory) | `c4e6cd91…` | `243a7d74…` | `99a92715…` | `2e6464af…` |
| v2 manifest sha256 (title Stockroom) | `4a8f6202…` | `883fd1cd…` | `cc6c4dd3…` | `4f4eb2ea…` |

The table below quotes run 3. Evidence names ending `.PortalDevice.txt` are
committed (`superpowers/evidence/reference-app/ci-run-35802020305/`, the
`PortalDevice` lines of each logcat); full logcats and the `*.xml` view dumps
are in the run's artifact only, which expires 30 days after the run.

| id | what happened on the device | evidence |
|---|---|---|
| P1 | host embeds this app's public key; `onCreate — mode=prod, devOnlyHost=false` | `prepare.results`, `logcat-prod-v1.PortalDevice.txt`; from run 5 on `device.sh` also asserts the `devOnlyHost=false` line |
| P2 | `prod mode: verifying manifests with portal-ed25519 4ebb499a…`, then `loading …/bundles/v1/manifest.zipline.json`, `codeLoadSuccess modules=40`; the screen reads **Inventory** — while the *source* already said Stockroom, so this is the signed bundle, not the dev server | `logcat-prod-v1.PortalDevice.txt`, `P2.results.json`; artifact: `P2-*.xml` |
| P3 | three taps on Add 1 in production: `13`, `14`, `15`, each observed | `P3.results.json`; artifact: `P3-*.xml` |
| P4 | after the D2 edit, publish v2 (manifest `4a8f6202…`), relaunch: `loading …/bundles/v2/…`, `codeLoadSuccess`; the screen reads **Stockroom** | `logcat-prod-v2.PortalDevice.txt`, `P4.results.json`; artifact: `P4-*.xml` |
| P5 | a copy of the app, its inherited pointer removed as the guide says, got its own store and key (`970839c6…`) and published `Foreign build`. Host data cleared, relaunched: still verifying with `4ebb499a…`, then **`codeLoadFailed: manifest signature for key portal-ed25519 did not verify!`**, no `codeLoadSuccess`, `Foreign build` never on screen | `logcat-prod-foreign.PortalDevice.txt`, `foreign-screen.txt`, `P5.results.json` |
| P6 | the app's own relay back, host relaunched: verified with `4ebb499a…`, v2 loads, **Stockroom** again | `logcat-prod-recover.PortalDevice.txt`, `P6.results.json`; artifact: `P6-*.xml` |

**Found on the way.** Every production launch first logs
`codeLoadFailed: Expected URL scheme 'http' or 'https' but no scheme was found for`
— an empty URL. `MainActivity.kt:112` starts the manifest flow at `""` in
production mode and only sets the real URL once `/bundles/latest` answers, and
Treehouse tries to load the empty one. Harmless to what loads, but an adopter's
crash reporting on `codeLoadFailed` gets a false failure on every start.

**What this does not show.** A production host an adopter could get without
this repository (there is none — finding 1); a production host built from the
**published** Maven Central 0.3.3 libraries (this one compiles Keliver's source
at `b5615637`); production *builds* of the bundles (every bundle here is
`compileDevelopmentExecutableKotlinJsZipline` output); a physical device, where
`10.0.2.2` does not reach the relay; HTTPS; a release-signed APK; arm64; iOS; and,
for runs 1–4, any device screenshot.


## Device screenshots — D14's third leg

D14 asks for a *device render screenshot*. **Runs 1–4 took none.** Their device
evidence is the view hierarchy (`uiautomator dump`) and logcat: D3, P2, P4 and P6
assert on-screen text and its bounds, which shows what the device laid out, not
what it drew. So for runs 1–4 the D14 bar is **not met** for this app: compile,
0 RawCode and the surgical write-back are shown; the device screenshot is not.
`EXPECTATIONS.md` said screencap "returns black frames" on the headless
emulator; that was inherited from run 34669956183 (`RELEASE_REVIEW.md`, same
emulator options), not measured in this workflow.

From run 5 on, `device.sh` takes two captures at three points — after E1–E10,
after the D3 edit (`Stockroom`, development host) and after P4 (`Stockroom`,
production host): the device's own `screencap`, and the emulator's host-side
`screenrecord screenshot`. `ci/shot.py` decodes each PNG and records VALID (a
rendered picture: more than a handful of colours, some non-black pixels) or
BLANK in `shots.results`. VALID says a frame was drawn, not that it is right;
what it shows is for a person to look at. A BLANK capture is kept and reported
and never counts toward D14.

**Run 5 ([`35967437120`](https://github.com/waliasanchit007/keliver/actions/runs/35967437120), head `25249e8ff`): all six captures BLANK.**

| point | `screencap` | emulator `screenrecord screenshot` |
|---|---|---|
| after E1–E10 | 1080×2340, 1 colour, 0.0% non-black — BLANK | same — BLANK |
| after D3 (`Stockroom`, development host) | BLANK | BLANK |
| after P4 (`Stockroom`, production host) | BLANK | BLANK |

Both methods return an all-black frame on this emulator (`aosp_atd` API 33
x86_64, `-no-window -gpu swiftshader_indirect`), while the view hierarchy at the
same moments shows the expected screen — so this is the capture, not the app.
**Visual verification of the device render is therefore incomplete, and D14's
screenshot leg is not met for this app.** The behavioural evidence is unaffected.
Not tried: another system image (`google_apis`), other `-gpu` modes, a physical
device. The PNGs are in the run's artifact (`shot-*.png`, `shots.results`).

## Reproducing

```bash
reference/inventory/bootstrap.sh /tmp/ref            # public release; or pass a zip with the same hash
```

The CI route is `reference/inventory/ci/prepare.sh` then `ci/device.sh` on an
emulator; both run with the JVM's `user.home` inside the work directory and the
repository's isolation guard before every relay start.
