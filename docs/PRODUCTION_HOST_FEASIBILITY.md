# A standalone Android production host — feasibility

**2026-09-24, measured 2026-09-29. Decision input for ROADMAP "Current
priorities" item 1.** Decided 2026-09-30: **option B, scaffold a host app** —
implemented as `keliver-new-production-host.sh` (#85), which reworks item 1's
gaps (production-only, configurable servers, real `HostHttp`, no U28).

> **Measured (2026-09-29):** a throwaway Android app, outside the checkout,
> with only Maven Central and Google as repositories, **resolves every
> dependency and compiles the three host files byte-for-byte unchanged — once
> one 17-line file declares `HostApi` and `PortalPresenter` by shape.** Without
> that file it fails, and every one of its compile errors traces to those two
> names. See "The measurement" below.

The question: can an adopter build an Android app that renders their
keliver-material screens from **signed** bundles, verified against **their own**
key, using published dependencies only — no Keliver checkout?

Today the answer is no. The only host that does it is `portal-device-android`,
an application module compiled from Keliver source; the reference app reached
production OTA that way (P1–P6, `REFERENCE_APP.md`). The generic host APK in the
tools bundle is development-only and refuses production mode (U22). That
refusal is not in question under any option below.

## What the host is made of

The production host is three Kotlin files, 382 lines
(`portal-device-android/src/main/kotlin/dev/keliver/portaldevice/host/`), plus an
`AndroidManifest.xml` (INTERNET, cleartext traffic, the launcher activity) and a
`build.gradle` that supplies `BuildConfig.DEV_ONLY` and embeds the key
(`syncPortalKey`), on top of libraries. It applies `com.android.application`,
`org.jetbrains.kotlin.android`, `org.jetbrains.kotlin.plugin.compose`,
`org.jetbrains.compose` 1.8.2 and `app.cash.zipline`.

| role | coordinate (as used at `b5615637`) | published? | Android variant |
|---|---|---|---|
| Treehouse host runtime | `dev.keliver:keliver-treehouse-host:0.3.3` | yes | `androidJvm` |
| Compose UI host | `dev.keliver:keliver-treehouse-host-composeui:0.3.3` | yes | `androidJvm` |
| keliver-material widgets | `dev.keliver:keliver-material-composeui:0.3.3` | yes | `androidJvm` |
| keliver-material host protocol | `dev.keliver:keliver-material-protocol-host-web:0.3.3` | yes | no `androidJvm`; `jvm` |
| SQL host wire (`AndroidSqlHost`) | `dev.keliver:portal-sql:0.3.3` | yes | no `androidJvm`; `jvm` |
| HTTP host (`HostHttp`) | `dev.keliver:keliver-http:0.3.3` | yes | no `androidJvm`; `jvm` |
| guest contract (`PortalPresenter`, `HostApi`) | `portal-device-guest` | **no** | — |
| Zipline runtime; `ManifestVerifier` | `app.cash.zipline:zipline:1.22.0`, `app.cash.zipline:zipline-loader:1.22.0`, and the `app.cash.zipline` Gradle plugin (IR rewrite of `take`/`bind`) | yes | — |
| the rest | `com.squareup.okhttp3:okhttp:5.1.0`; `androidx.activity:activity-compose:1.10.1`; `androidx.core:core-ktx:1.16.0`; `io.coil-kt.coil3:coil-compose-core:3.3.0`, `coil-network-okhttp:3.3.0`; `org.jetbrains.compose.{runtime,foundation,material,ui}` 1.8.2; `kotlinx-coroutines-core:1.10.2`; Kotlin 2.2.0, AGP 8.12.0 | yes | — |

The variants come from each coordinate's published Gradle module metadata on
Maven Central, read 2026-09-24. Imported directly by the host files but arriving
transitively, so they resolve without being listed: `keliver-leak-detector`
(via `keliver-protocol-host`), `keliver-capabilities` (via `keliver-http`),
`kotlinx-serialization-json` 1.9.0 and okio 3.16.0 (via `keliver-treehouse-host`),
and androidx.lifecycle (via activity).

## What is missing from what is published

1. **The host itself — and as it stands it is not a production host.** No
   library on Maven Central provides `MainActivity.kt` (development versus
   production mode, the `/bundles/latest` lookup, Zipline loading with
   `ManifestVerifier`), `HostTrustPolicy.kt` (refuses production without a valid
   embedded key) or `AndroidSqlHost.kt`. And at `b5615637` those files are a
   test host with a production *mode*, not a production app:
   * production is opt-in **per launch** (`intent.getStringExtra("mode") ==
     "prod"`); a plain launcher start takes the development path, with
     `ManifestVerifier.NO_SIGNATURE_CHECKS` and a cleartext manifest URL;
   * the endpoints are emulator addresses (`http://10.0.2.2:8077`, `:8080`), and
     the manifest allows cleartext traffic;
   * `HostHttp` is bound to `AndroidReplayHttpHost`, which posts to the relay's
     `/http-replay` with a test fixture set;
   * the `applicationId` is the generic host's, and it logs a false
     `codeLoadFailed` on every start (U28).
   Any option has to make production the default (or only) mode, the endpoints
   configurable, and `HostHttp` the adopter's own. The reference app's
   production runs lived with all of this because the emulator reached the relay
   at `10.0.2.2`.
2. **The key-embedding build step.** `syncPortalKey` copies
   `<store>/keys/ed25519.pub` into the APK's assets, and `-Pkeliver.devOnlyHost`
   selects the build shape. The store lookup (`keliverPortalStore`) is in the
   Keliver repository's root `build.gradle`. The published
   `keliver-gradle-plugin` has nothing for stores or keys. The reference app's
   signing block shows the published workaround: call the tools bundle's
   `keliver-store-path.sh`.
3. **`portal-device-guest` is not published, and does not need to be — but
   the host files import from it.** Zipline binds services by name and shape.
   The scaffolded guest already declares `PortalPresenter` itself
   (`keliver-new-device-target.sh`), and the generic host binds it: the
   reference app's E1–E10 ran that way. `MainActivity.kt` imports
   `dev.keliver.portaldevice.HostApi` and `PortalPresenter` from that module, so
   a standalone host needs one extra file declaring both by shape (measured
   below: that is the only thing missing for compilation).
4. **Resolving the published metadata in an Android build — measured, it
   works.** More
   than the three above have no `androidJvm` variant — `keliver-protocol`,
   `keliver-protocol-host`, `keliver-leak-detector`, `keliver-material-widget`,
   `keliver-material-modifiers` and `keliver-capabilities` too — and the
   `androidJvm` artifacts depend on them, so the `jvm` variants are already how
   Android consumes them: `portal-device-android` does exactly that through
   project dependencies (`portal-sql/build.gradle`: "android host consumes via
   jvm artifact"). An AGP 8.12 build resolves the same graph from Maven
   Central's module metadata: it picked the `-jvm` artifacts for those modules
   and the `-android` ones where they exist, and built an APK (below).

## The smallest supported publish/signing setup

This is measured in the reference app (`reference/inventory/app`). The setup
itself comes from no checkout; the host that verified the result (step 3) was
built by the checkout route (option C below).

1. `keliver.portal.json`:
   `"publishTask": ":compileDevelopmentExecutableKotlinJsZipline", "publishOutput": "build/zipline/Development"`.
   The default is Keliver's own `:portal-published-guest:…`, which fails on a
   scaffolded app.
2. `build.gradle`, **after** the `kotlin {}` block: set `ZiplineCompileTask.signingKeys`
   from `<store>/keys/ed25519.priv`, using the tools bundle's
   `keliver-store-path.sh` to find the store (so the build needs `KP` or
   `-Pkeliver.toolsBin`). Placed before that block — or with no key in the
   store, by design — the bundle compiles **unsigned without an error**. The
   ordering rule is in Keliver's own build files and checks
   (`portal-published-guest/build.gradle`, `keliver-guest-signing-check.sh`) and
   the reference app's, but in no scaffold and no adopter-facing doc.
   *(Superseded, 2026-10-07, by U31 in `KNOWN_BUGS.md`. `signingKeys` leaks the
   private key into `ps`, `--info` logs and `.gradle/`. The scaffolded block now
   signs the manifest in a `doLast`, wherever it sits.)*
3. The relay's `POST /publish` then runs `publishTask`, and the Zipline compile
   task signs. Measured result:
   - the manifest carries a `portal-ed25519` signature;
   - a production host with the matching key loads v1, then v2;
   - a bundle signed with another app's key is refused on its signature.

Known limits of this setup:
- The bundles are Zipline **Development** builds.
- The tools 0.3.5 relay creates the private key `0644` (U27; fixed on a
  branch, not released).
- `keliver-init` could write steps 1 and 2 itself. That is ROADMAP item 2, and
  it is independent of the host decision.

## The options, for the decision

* **A. Publish a host library.** For example, an Android library that contains
  `MainActivity`'s loading logic as an embeddable Activity or composable, plus
  `HostTrustPolicy`, and a Gradle task (or plugin) that embeds the store's
  public key. `devOnlyHost` must stay a compile-time input of the tools APK —
  never a runtime or intent parameter a library caller could flip.
  - The adopter's app depends on it and supplies the key.
  - It goes on the library line (Maven Central, `apiCheck`, versioning), so
    this is a new public API.
* **B. Scaffold a host app.** A tools-bundle command writes an Android module
  into the adopter's repository: the host files — reworked per item 1 — a
  manifest, a `build.gradle` on the published coordinates above, and a
  key-embedding task that calls `keliver-store-path.sh`.
  - The adopter owns the generated code, and updates come by re-scaffolding.
  - It goes on the tools line only, so no library release is needed.
* **C. Document the checkout route.** This is what the reference app does:
  build `portal-device-android` from the release tag with
  `-Pkeliver.devOnlyHost=false -Pkeliver.portalStore=<store>`.
  - It costs nothing, and it keeps the "needs a checkout" gap that item 1
    exists to close.

**Common to all three:**
- The generic development APK keeps refusing production.
- A production host is a separate app, with its own key embedded at build time.
- `build-portal-tools.sh` keeps refusing to package any APK that embeds a key.

## The measurement

`superpowers/evidence/host-feasibility/measure.sh <keliver-checkout> <work>`
generates a throwaway Android project with:
- AGP 8.12.0, Kotlin 2.2.0, Compose Multiplatform 1.8.2 and Zipline 1.22.0;
- `compileSdk` 35, `minSdk` 21, and `DEV_ONLY=false`;
- a new `applicationId`;
- repositories limited to Maven Central, Google and the plugin portal (`FAIL_ON_PROJECT_REPOS`; no `mavenLocal`);
- the coordinates in the table above.

It copies the three host files and the manifest from `b5615637`, checks that the host files are byte-identical, and builds two variants.

It is isolated:
- the Gradle home and the JVM's `user.home` are inside the work directory;
- the local Android SDK is only read;
- no store is read, no key is embedded (the host reads its key from an asset at runtime), and nothing is signed.

Run on macOS, 2026-09-29 (`superpowers/evidence/host-feasibility/`):

| variant | dependencies | `assembleDebug` |
|---|---|---|
| **A** — the three files alone | all resolved from the public repositories | **fails**: 22 errors, all from the unresolved `HostApi` / `PortalPresenter` (`variant-A-errors.txt`) |
| **B** — the same, plus `GuestContract.kt` declaring both by shape (17 lines, the signatures from `portal-device-guest` at `b5615637`) | all resolved | **succeeds**: a 20.7 MB debug APK, embedding no key |

What this settles:
- **Item 4 is settled:** the published graph resolves and compiles for Android.
- **Item 3 is settled:** the one gap for compilation is the guest contract, and a by-shape declaration closes it.
- **For the options:**
  - B (scaffold a host app) needs no new published artifact to *compile*: the three files, the one contract file, a manifest and this build script.
  - A (a host library) would carry the contract itself.

What it does not settle:
- **The APK was not run**, on any device.
- **The production defaults (item 1) are untouched:** production is still opt-in per launch, the endpoints are emulator addresses, and `HostHttp` is still a replay fixture. That is design work under any option.
- **The key-embedding build step (item 2) is not part of it.**
- **Release (R8) builds were not tried.**
