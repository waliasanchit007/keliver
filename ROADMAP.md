# Konduit roadmap

Forward-looking. Adopter-facing. Distinct from
[`PUBLIC_LAUNCH_ROADMAP.md`](./PUBLIC_LAUNCH_ROADMAP.md), which tracks
the one-time work to take this repo from "private fork shipping to
Caliclan's own apps" to "public OSS framework." That one is launch
prep. **This document is the steady-state plan** — what adopters can
expect from Konduit over the next two release cycles, what's
deliberately out of scope, and what stability commitments come with
each piece.

This is a living document. Items move from "Up Next" → "In Progress" →
"Released" as work lands. The release log itself lives in
[`CHANGELOG.md`](./CHANGELOG.md).

---

## Released — adopter-facing API surface today

`1.0.0-caliclan.4-SNAPSHOT` (in flight)

**Adopter ergonomics**
- `dev.konduit:konduit-host` / `dev.konduit:konduit-guest` — single-import
  facade modules. Replaces the 7-module hand-wired host-side dep
  block.
- `dev.konduit:konduit-vm` — `KonduitViewModel` + `konduitViewModel { }`
  Compose helper. Closes the cosmetic gap with native
  Compose-Android ViewModel ergonomics.
- `dev.konduit:konduit-nav` — typed guest-side navigation
  (`KonduitNavController` + `KonduitNavHost`), per-entry
  `rememberSaveable` state preservation.
- `dev.konduit:konduit-http` — `KonduitHttp` typed wrapper +
  `HostHttpProvider` generic shim. Replaces N per-endpoint
  `HostXxxProvider` services with one host binding.
- `dev.konduit:konduit-storage` — `KonduitStorage` key/value shim.
- `dev.konduit:konduit-console` — standardized `KonduitConsole`
  logging service + reference `DefaultKonduitConsole`.
- `dev.konduit:konduit-image` — `KonduitImage.installSingleton()`
  one-liner for Coil 3 setup. Closes KNOWN_BUGS U5.
- `dev.konduit:konduit-http-annotations` — Retrofit-style annotation
  surface (`@KonduitApi`, `@GET`, `@POST`, `@Path`, `@Query`, `@Body`,
  `@Header`, `@HeaderMap`). Phase 1 of the codegen workstream
  (issue #18); processor in [`docs/HTTP_API_CODEGEN_DESIGN.md`](./docs/HTTP_API_CODEGEN_DESIGN.md).

**Production hardening (silent-failure mitigations)**
- `TreehouseApp.Spec.retain()` — strong-ref helper for inline service
  impls (KNOWN_BUGS U7).
- `Spec.bindWithTimeout { … }` — turns the U1 / U2 silent bind-hang
  into a clear `ZiplineBindTimeoutException` after 30s.
- `Spec.requireSerializerOf<T>()` and bulk
  `requireSerializersOf(vararg KType)` — bind-time serializer
  preflight. Catches U3 / U4 before the silent-callback path can
  swallow the failure.

**Build-time lint surface**
- `dev.konduit.zipline-shapes` Gradle plugin — rejects
  `ZiplineService` methods with function-typed parameters
  (KNOWN_BUGS U11). Auto-wires `check` lifecycle.
- Schema parser rejects `@Composable` lambdas as `@Property` at
  parse time — points at `@Children` as the canonical replacement.
- Schema parser rejects function-typed `@Modifier` properties
  (KNOWN_BUGS U6).

**Distribution**
- POM metadata cleaned up — published artifacts identify as
  Konduit, not the inherited Cash App Redwood values.
- CI runner configurable via `vars.CI_RUNNER` — adopters
  self-hosting on a Mac can bypass GHA macOS minute billing.

---

## Up next — in scope for 1.0.0-caliclan.5

In rough priority order:

### 1. `konduit-http-codegen` — Phase 2 of issue #18 (KSP processor)
KSP `SymbolProcessor` that walks `@KonduitApi`-annotated interfaces
and emits `*Impl(KonduitHttp)` classes. Phase 1 annotations are
already committed; the processor is the meat. Full spec in
[`docs/HTTP_API_CODEGEN_DESIGN.md`](./docs/HTTP_API_CODEGEN_DESIGN.md).

Estimate: 4–6 hours of focused build + tests via
`kotlin-compile-testing-ksp`.

### 2. Konduit-only sample app  ✅ *landed + tested on both platforms*
Minimal end-to-end sample lives at [`sample/`](./sample/) — a
standalone Gradle build with its own custom schema (`Box` + `Text`),
codegen pipeline (widget / modifier / protocol-host / protocol-guest),
Kotlin/JS guest bundle, Compose-MP host renderer, Android app
shell, and iOS Xcode project.

**Validated end-to-end:**
- Android: Pixel 9 emulator (API 37) — renders "Hello, Konduit!"
  ([PR #54](https://github.com/waliasanchit007/konduit/pull/54)).
- iOS: iPhone 17 Pro simulator (iOS 26.3.1, Xcode 26.3) — same
  widget renders, same full Zipline RPC sequence visible via
  `simctl launch --console`
  ([PR #56](https://github.com/waliasanchit007/konduit/pull/56)).

Testing surfaced 8 adopter-facing bugs (5 Android + 3 iOS-specific);
all fixed in tree, all written up in
[`sample/TESTING.md`](./sample/TESTING.md). See
[`sample/README.md`](./sample/README.md) for the runbook +
"Gotchas you'll hit on day one".

### 3. iOS host validation  ✅ *complete*
The sample's `host-compose` module produces a `KonduitSampleHost`
framework for both `iosArm64` and `iosSimulatorArm64`. The
ready-made Xcode project at [`sample/iosApp/`](./sample/iosApp/)
demonstrates the Swift integration:
`UIViewControllerRepresentable` wrapping `MainKt.MainViewController()`
from Kotlin/Native. Adopters can either copy the iosApp template
or follow the integration notes in
[`sample/README.md` § iOS adopter notes](./sample/README.md).

### 4. Performance benchmarks  ✅ *Phase 1 landed; Phases 2-3 queued*
**Phase 1 (measured today):** [`docs/PERFORMANCE.md`](./docs/PERFORMANCE.md)
ships baseline measurements for APK size, Zipline bundle size, iOS
framework size, and build times. Reproducible via
`scripts/measure-baselines.sh`. Key adopter-relevant numbers:
production Zipline bundle is **732 KB total** but Konduit + per-app
own-code is only **12 KB** — the runtime cost is fixed, the
per-feature cost is small. Methodology + target SLAs for cold-start /
warm-mount / update-latency / memory-footprint are committed in
the same doc so adopters can predict + verify.

**Phase 2 (scaffolded):** [`sample/benchmarks/`](./sample/benchmarks/)
ships a working AndroidX Macrobenchmark module with `coldStartup`
+ `warmStartup` fixtures, a `benchmark` build type on
`:host-android` (non-debuggable + profileable + signed with the
debug key), and the matching wiring on `:benchmarks` itself. Runs
via `./gradlew :benchmarks:connectedBenchmarkAndroidTest`. Known
limitation: the activity-launch detection times out on Android
emulators because `dumpsys gfxinfo … framestats` is slow to
populate post-launch on AVD — physical-device runs are
recommended for publishable numbers. See
[`docs/PERFORMANCE.md`](./docs/PERFORMANCE.md) § "Phase 2 known
limitation" for the full writeup. CI workflow that re-runs
benchmarks per release tag is the next sub-task.

**Phase 3 (adopter-demand-gated):** Native-Compose-only and upstream
Cash App Redwood 0.18.0 comparison baselines.

### 5. Bundle-size budget
Adopters apply a Konduit-provided Gradle task that fails the build
if the produced `.zipline` artifact grows beyond a configurable
threshold. Catches accidental size regressions in CI.

### 6. `konduit-treehouse` adapter helper — eliminate the U12 boilerplate
Every adopter writing a new `AppService` subinterface today copies
the same ~95-line `ManualXxxServiceAdapter.kt` workaround for
[Zipline #765](https://github.com/cashapp/zipline/issues/765).
Documented as [`U12`](./docs/KNOWN_BUGS.md#u12-appservice-subinterfaces-need-a-hand-rolled-adapter--zipline-ir-plugin-cant-generate-one).
Two possible Konduit-side fix shapes:

- **(a) Code-gen plugin** — a small KSP processor that scans
  `@KonduitAppService`-annotated interfaces and emits the
  adapter automatically. Adopters add a single annotation.
- **(b) Reflective base adapter** — a `BaseAppServiceAdapter` that
  uses kotlinx-serialization's reflective lookup to discover
  method shapes at runtime. Adopters extend one class.

Each has trade-offs: (a) adds a new processor for every adopter
to wire (similar to `konduit-http-codegen`'s position), (b) costs
a small amount of JS-side runtime. The right pick is the one that
hides the most boilerplate with the least surface-area expansion.

---

## Looking further out — 1.0.0-caliclan.6+

Items here have a clear shape but no committed timeline. Some unlock
once user-action gates clear (Maven Central, public visibility); some
need adopter signal before they're worth building.

### Distribution

- **Maven Central publishing** — gated on Sonatype namespace claim
  + GPG signing setup. Full walkthrough in
  [`docs/MAVEN_CENTRAL_SETUP.md`](./docs/MAVEN_CENTRAL_SETUP.md).
  Once landed, the GitHub-Packages PAT requirement goes away.
- **Public OSS visibility flip** — gated on maintainer review of
  commit history + a security pass on the self-hosted CI runner
  setup. See [`PUBLIC_LAUNCH_ROADMAP.md`](./PUBLIC_LAUNCH_ROADMAP.md)
  Phase 6.
- **Docs site** — MkDocs / Docusaurus / Writerside on a hosted
  domain (TBD). Replaces the long `docs/USAGE.md` with a
  navigable site once the page count justifies it. Phase 7 of the
  launch roadmap.

### Ergonomics (more adopter friction reduction)

- **HostObserver / Flow over Zipline** — pure-docs scope landed in
  caliclan.4 (`docs/USAGE.md` § "Reactive data"); a future small
  helper module could ship `HostStateFlowProvider` that wraps a
  `StateFlow<T>` with correct dispatcher routing for adopters
  who'd rather not roll their own.
- **`konduit-room`-style persistence shim** — `KonduitStorage`
  covers key/value today; a structured-query variant (SQL-like,
  schema-typed) is an open design question. Wait for adopter signal.
- **Retrofit-style codegen Phase 3+ work** —
  multipart bodies, streaming responses, form-urlencoded, `@Url`
  overrides. Each is a small additive PR on top of the Phase 2
  processor.

### Tooling & quality

- **Migration guide from Cash App Redwood `0.18.0+`** —
  [`docs/MIGRATION_FROM_REDWOOD.md`](./docs/MIGRATION_FROM_REDWOOD.md)
  already exists; expand it with a scripted rename helper for
  adopters with large Redwood codebases.
- **Dokka HTML output** — Dokka is configured; the next step is
  auto-publishing on every tag via GHA.
- **Snapshot publishing on merge-to-main** — adopters who want to
  pin `1.0.0-caliclan.N-SNAPSHOT` from `main` HEAD can't today;
  only tagged releases publish. Add the GHA workflow trigger.
- **Module-level READMEs** — `konduit-http/README.md`,
  `konduit-vm/README.md`, etc. Each module's GitHub page shows
  `build.gradle` today; a one-line README per module dramatically
  improves first-impression discoverability.

### Strategic positioning (when going public)

- **`COMPARISON.md`** — Konduit vs upstream Redwood (sunsetted),
  vs Flutter, vs React Native. Each comparison 2–3 paragraphs.
- **`API_STABILITY.md`** — explicit SemVer commitment,
  deprecation policy, LTS window.
- **`ADOPTERS.md`** — placeholder; populates as adopters opt in.
- **Public announcement** — Twitter / r/androiddev / Kotlin Slack
  pitch after the public-visibility flip lands.

---

## What's deliberately out of scope

These come up in adopter conversations enough to warrant calling
out — but Konduit is not the right place for them.

- **View / UIView / DOM widget systems.** Konduit shipped Phase 1.5
  (`caliclan.2`) by dropping 13 upstream modules that aren't
  relevant to Compose Multiplatform. The fork is full CMP. If you
  need to render via Android Views, iOS UIKit, or the browser
  DOM, you want upstream
  [Cash App Redwood](https://github.com/cashapp/redwood) instead.
- **The Zipline runtime itself.** Konduit consumes
  [`app.cash.zipline`](https://github.com/cashapp/zipline) as a
  vendored dependency. Upstream Zipline ships the QuickJS bridge,
  the Kotlin compiler plugin, the bundle loader. Konduit's value
  is the protocol-on-top-of-Zipline + the production-hardening
  helpers, not the Zipline runtime. Bugs in QuickJS or the
  Zipline plugin (e.g. KNOWN_BUGS U8 part 1) get filed against
  Zipline.
- **Application-specific schemas.** Konduit ships the framework
  (runtime, codegen, lint, protocol). Your app's `@Widget`
  definitions, `HostXxxProvider` shapes, and routing belong in
  your own repo (see
  [ServerDrivenUI](https://github.com/waliasanchit007/ServerDrivenUI)
  for the reference integration's schema layout).
- **A.I.-generated UI.** The schema is a static contract between
  guest and host. Runtime-generated UI from a model that's never
  seen the schema is a different problem; Konduit doesn't solve
  it.

---

## How to influence the roadmap

For now, file an issue. Once public visibility lands, the same
channel applies — adopter signal moves items between the "Up Next"
and "Further Out" sections.

For specific bug shapes Konduit's runtime + lint already mitigates,
see [`docs/KNOWN_BUGS.md`](./docs/KNOWN_BUGS.md) and the
"Production hardening" section above.
