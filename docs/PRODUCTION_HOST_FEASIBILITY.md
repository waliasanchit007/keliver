# A standalone Android production host — feasibility

**2026-09-24. Decision input for ROADMAP "Current priorities" item 1. Nothing
here is implemented.**

The question: can an adopter build an Android app that renders their
keliver-material screens from **signed** bundles, verified against **their own**
key, using published dependencies only — no Keliver checkout?

Today the answer is no. The only host that does it is `portal-device-android`,
an application module compiled from Keliver source; the reference app reached
production OTA that way (P1–P6, `REFERENCE_APP.md`). The generic host APK in the
tools bundle is development-only and refuses production mode (U22). That
refusal is not in question under any option below.

## What the host is made of

The production host is three files, about 380 lines
(`portal-device-android/src/main/kotlin/dev/keliver/portaldevice/host/`), on top
of libraries:

| role | coordinate (as used at `b5615637`) | published? | Android variant |
|---|---|---|---|
| Treehouse host runtime | `dev.keliver:keliver-treehouse-host:0.3.3` | yes | `androidJvm` |
| Compose UI host | `dev.keliver:keliver-treehouse-host-composeui:0.3.3` | yes | `androidJvm` |
| keliver-material widgets | `dev.keliver:keliver-material-composeui:0.3.3` | yes | `androidJvm` |
| keliver-material host protocol | `dev.keliver:keliver-material-protocol-host-web:0.3.3` | yes | **`jvm` only** |
| SQL host wire (`AndroidSqlHost`) | `dev.keliver:portal-sql:0.3.3` | yes | **`jvm` only** |
| HTTP host (`HostHttp`) | `dev.keliver:keliver-http:0.3.3` | yes | **`jvm` only** |
| guest contract (`PortalPresenter`, `HostApi`) | `portal-device-guest` | **no** | — |
| Zipline runtime; `ManifestVerifier` | `app.cash.zipline:zipline:1.22.0`, `app.cash.zipline:zipline-loader:1.22.0`, and the `app.cash.zipline` Gradle plugin (IR rewrite of `take`/`bind`) | yes | — |
| the rest | `com.squareup.okhttp3:okhttp:5.1.0`; `androidx.activity:activity-compose:1.10.1`; `androidx.core:core-ktx:1.16.0`; `io.coil-kt.coil3:coil-compose-core:3.3.0`, `coil-network-okhttp:3.3.0`; `org.jetbrains.compose.{runtime,foundation,material,ui}` 1.8.2; `kotlinx-coroutines-core:1.10.2`; Kotlin 2.2.0, AGP 8.12.0 | yes | — |

The variants come from each coordinate's published Gradle module metadata on
Maven Central, read 2026-09-24.

## What is missing from what is published

1. **The host itself.** `MainActivity.kt` handles development versus production
   mode, the `/bundles/latest` lookup, and Zipline loading with
   `ManifestVerifier`. `HostTrustPolicy.kt` refuses production without a valid
   embedded key. `AndroidSqlHost.kt` is the SQL host. These live in an
   application module, and no library on Maven Central provides them.
2. **The key-embedding build step.** `syncPortalKey` copies
   `<store>/keys/ed25519.pub` into the APK's assets, and `-Pkeliver.devOnlyHost`
   selects the build shape. The store lookup (`keliverPortalStore`) is in the
   Keliver repository's root `build.gradle`. The published
   `keliver-gradle-plugin` has nothing for stores or keys. The reference app's
   signing block shows the published workaround: call the tools bundle's
   `keliver-store-path.sh`.
3. **`portal-device-guest` is not published, and does not need to be.** Zipline
   binds services by name and shape. The scaffolded guest already declares
   `PortalPresenter` itself (`keliver-new-device-target.sh`), and the generic
   host binds it: the reference app's E1–E10 ran that way. A standalone host can
   declare its side of the contract the same way.
4. **Unverified: three `jvm`-only libraries in an Android app.** Android builds
   normally accept a Kotlin Multiplatform `jvm` variant. However, no Android
   build has consumed these three from Maven Central: `portal-device-android`
   uses project dependencies. This is the first thing any option has to
   measure.

## The smallest supported publish/signing setup

This is measured in the reference app (`reference/inventory/app`), and nothing
in it comes from a checkout:

1. `keliver.portal.json`:
   `"publishTask": ":compileDevelopmentExecutableKotlinJsZipline", "publishOutput": "build/zipline/Development"`.
   The default is Keliver's own `:portal-published-guest:…`, which fails on a
   scaffolded app.
2. `build.gradle`, **after** the `kotlin {}` block: set `ZiplineCompileTask.signingKeys`
   from `<store>/keys/ed25519.priv`, using the bundle's `keliver-store-path.sh`
   to find the store. Placed before that block, the bundle compiles **unsigned
   without an error**. That rule is recorded only in
   `portal-published-guest/build.gradle`.
3. The relay's `POST /publish` then compiles and signs. Measured result:
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
  public key.
  - The adopter's app depends on it and supplies the key.
  - It goes on the library line (Maven Central, `apiCheck`, versioning), so
    this is a new public API.
* **B. Scaffold a host app.** A tools-bundle command writes an Android module
  into the adopter's repository: the three host files, a `build.gradle` on the
  published coordinates above, and a key-embedding task that calls
  `keliver-store-path.sh`.
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

**The measurement that should come before choosing.** Build a throwaway
Android app that resolves the coordinates above from Maven Central and compiles
the three host files against them, with no host code changed. This settles
item 4 for every option, and it tells A and B apart on cost. It reads no store
and signs nothing.
