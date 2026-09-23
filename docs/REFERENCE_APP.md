# The reference app — what the published route costs an adopter

**2026-09-22/23.** An inventory app built outside this checkout from the
**published** `keliver-portal-tools` 0.3.5 release and `dev.keliver:*:0.3.3` on
Maven Central, following [`PORTAL_ADOPTER_GUIDE.md`](PORTAL_ADOPTER_GUIDE.md),
then checked against behaviour written down beforehand
([`reference/inventory/EXPECTATIONS.md`](../reference/inventory/EXPECTATIONS.md)).

**This is dogfooding.** We wrote the app. It shows that the adopter route works
and where it is rough; it is not evidence that anyone outside the project has
adopted Keliver. Nobody has.

Source and reproduction: [`reference/inventory/`](../reference/inventory) —
`bootstrap.sh` recreates the app from the public release; CI runs it end to end
(`.github/workflows/reference-app.yml`).

## What was verified, and where

| check | where it ran | result |
|---|---|---|
| public release download: `.sha256` and pinned hash `4e1c3040…` | GitHub-hosted runner | pass (runs 1 and 3) |
| `keliver-init` scaffold compiles against Maven Central 0.3.3, fresh Gradle home | macOS (this machine) and CI | pass |
| two screens ingest with **0 RawCode** (10 and 13 nodes; `Condition` and `Repeat` recognized; full contracts) | macOS and CI | pass |
| D14 edit through the relay's `/ops`: 2-line surgical diff, `Compiled_inventory.kt` stamp, `logic/` byte-identical, recompiles | macOS and CI | pass |
| signed publish: manifest carries a `portal-ed25519` signature from this app's store key | macOS and CI | pass |
| **Live preview with the real presenters**: 21 checks, real mouse events on the canvas | macOS, headless Chrome 153 | 21/0 |
| development route on the bundle's generic host: E1–E10 | CI emulator, API 33 x86_64 | **30/0** (run 3) |
| D3: the edited title on the device after a rebuild | CI emulator | pass |
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

E3's assertion did not change between runs 1 and 3; the driver now clears with
spare DELs, reads the field back, and asserts what it typed as its own check.
`P5.results.json` reports two failures **by design**: they are the "is
`Foreign build` on screen?" probes, and `device.sh` records their failure as
the pass ("Foreign build never appeared").

### Live preview (macOS, headless Chrome, CDP)

`keliver-new-editor.sh` + `InventoryPreview.kt` (both screens, real
presenters). `keliver-portal` built `editor/` and reported
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
| L8 | the list screen, live: `8 items · 2 low on stock`, all eight real rows | pass |

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
   The adopter guide does not mention publishing at all.

### Security

3. **The relay writes the private signing key world-readable.** On macOS with
   the default umask, `keys/ed25519.priv` is created `-rw-r--r--`
   (`Relay.kt#ensureKeys`, `File.writeText`). Any local user can read the key
   that signs production bundles. Not fixed.

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
9. **Observed once, not investigated:** during rapid Live taps the editor's
   *Bindings* panel showed `On hand: 6` while the canvas and the State Inspector
   showed `5` (`live-preview/L-item-live-5-low.png`). One frame of one run;
   recorded, not claimed as a defect.

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

* `keliver-init` → a project that compiles against Maven Central in 1m16s on a
  fresh Gradle home.
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
its own keys, so the identities differ per run; the APK installed and the
manifests served are kept in each run's evidence artifact.

| | run 3 (`35802020305`) | run 1 (`35800642819`) |
|---|---|---|
| production host APK sha256 | `92af8ceebfec4cc8e47928ed20ff90b89dc50437bc1e5f23979b05c92ce1f2bf` | `58b7b5a640d0644b008f05b5ce1e8424606f13b6dba524b18fd9da8db1924f80` |
| app key (store `keys/ed25519.pub`) | `4ebb499a…` | `e91529de…` |
| foreign copy's key | `970839c6…` | `6a825f3b…` |
| v1 manifest sha256 (title Inventory) | `c4e6cd91…` | `243a7d74…` |
| v2 manifest sha256 (title Stockroom) | `4a8f6202…` | `883fd1cd…` |

The table below quotes run 3.

| id | what happened on the device | evidence |
|---|---|---|
| P1 | host embeds this app's public key; `onCreate — mode=prod, devOnlyHost=false` | `prepare.results`, `logcat-prod-v1.txt` |
| P2 | `prod mode: verifying manifests with portal-ed25519 4ebb499a…`, then `loading …/bundles/v1/manifest.zipline.json`, `codeLoadSuccess modules=40`; the screen reads **Inventory** — while the *source* already said Stockroom, so this is the signed bundle, not the dev server | `logcat-prod-v1.txt`, `P2-*.xml` |
| P3 | three taps on Add 1 in production: `13`, `14`, `15`, each observed | `P3.results.json`, `P3-*.xml` |
| P4 | after the D2 edit, publish v2 (manifest `4a8f6202…`), relaunch: `loading …/bundles/v2/…`, `codeLoadSuccess`; the screen reads **Stockroom** | `logcat-prod-v2.txt`, `P4-*.xml` |
| P5 | a copy of the app, its inherited pointer removed as the guide says, got its own store and key (`970839c6…`) and published `Foreign build`. Host data cleared, relaunched: still verifying with `4ebb499a…`, then **`codeLoadFailed: manifest signature for key portal-ed25519 did not verify!`**, no `codeLoadSuccess`, `Foreign build` never on screen | `logcat-prod-foreign.txt`, `foreign-screen.txt`, `P5-*.xml` |
| P6 | the app's own relay back, host relaunched: verified with `4ebb499a…`, v2 loads, **Stockroom** again | `logcat-prod-recover.txt`, `P6-*.xml` |

**Found on the way.** Every production launch first logs
`codeLoadFailed: Expected URL scheme 'http' or 'https' but no scheme was found for`
— an empty URL. `MainActivity.kt:112` starts the manifest flow at `""` in
production mode and only sets the real URL once `/bundles/latest` answers, and
Treehouse tries to load the empty one. Harmless to what loads, but an adopter's
crash reporting on `codeLoadFailed` gets a false failure on every start.

**What this does not show.** A production host an adopter could get without
this repository (there is none — finding 1); a physical device, where
`10.0.2.2` does not reach the relay; HTTPS; a release-signed APK; arm64; iOS.


## Reproducing

```bash
reference/inventory/bootstrap.sh /tmp/ref            # public release; or pass a zip with the same hash
```

The CI route is `reference/inventory/ci/prepare.sh` then `ci/device.sh` on an
emulator; both run with the JVM's `user.home` inside the work directory and the
repository's isolation guard before every relay start.
