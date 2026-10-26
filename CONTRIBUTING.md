# Contributing to Keliver

Thanks for considering contributing. Keliver is currently a private fork
moving toward public OSS launch (see [PUBLIC_LAUNCH_ROADMAP.md](./PUBLIC_LAUNCH_ROADMAP.md));
external contribution flows will firm up as we get closer to the launch.
Until then, this doc is the source of truth for the local dev loop and
PR expectations.

## File an issue first

For anything beyond a trivial typo fix, open an issue before opening a PR.
Most "I have a problem with X" reports fall into one of these categories:

- **Bug.** Use the bug-report template. Include the failing build / runtime
  command, the full error, and the platform (Android API level, iOS sim
  version, Kotlin/AGP versions).
- **Adoption help.** Use the integration-help template. Most adoption
  questions are silent-failure-shape variants documented in
  [`docs/KNOWN_BUGS.md`](./docs/KNOWN_BUGS.md) — check there first.
- **Feature request.** Use the feature-request template. Explain the use
  case and why the current schema / API doesn't support it.

## Prerequisites

- **JDK 17** for every Gradle command: `export JAVA_HOME=$(/usr/libexec/java_home -v 17)`
  on macOS.
- **git-lfs, installed before you clone.** `*.png` is LFS-tracked; a clone
  without it fails checkout partway.
- **Android SDK** (`ANDROID_HOME` or `local.properties`) for the Android
  modules; Xcode for the iOS targets.

## Local dev loop

```bash
# Build everything
./gradlew assemble

# Publish to mavenLocal so downstream consumers can use your local changes
./gradlew publishToMavenLocal

# Run the codegen test suite
./gradlew :keliver-tooling-codegen:test
```

For changes that touch a widget schema: a new widget or modifier must also be
listed in the `@Schema` members annotation (codegen silently ignores it
otherwise), and the portal's generated catalog/exporter/RenderNode must be
regenerated with `./gradlew :portal-schema-codegen:generatePortalCode` (CI has
a staleness guard).

## PR expectations

- **Tests for codegen changes.** `keliver-tooling-codegen/src/test/` has
  fixture-based tests. Add a fixture that demonstrates the new behavior;
  golden-file the expected output.
- **CHANGELOG entry.** Every PR adds a line under `[Unreleased]` in
  `CHANGELOG.md`. Format: short summary + reference to the integration
  bug it closes (if any).
- **Wire-format additivity.** New properties on existing `@Widget` /
  `@Modifier` classes must be additive (default values, new tags). Never
  reorder or remove existing tags. The wire format is committed for the
  `1.0.x` major.
- **Multiplatform compile.** Verify both `compileKotlinJvm` and
  `compileKotlinIosSimulatorArm64` pass. CI runs both.

### Gates before "done"

- `./gradlew apiCheck` plus the tests of every module you touched.
- Portal changes: an ingest round-trip on a real screen.
- Widget changes: a render on a device.
- A screen meets the bar in [`docs/DECISIONS.md`](./docs/DECISIONS.md) (D14):
  compile → 0 RawCode on ingest → device screenshot → surgical write-back
  round-trip.

## Release flow

Keliver has **two independent release lines**; never bump one to name the
other. [RELEASING.md](./RELEASING.md) has the canonical steps.

**Libraries** (`dev.keliver:*` on Maven Central):

1. Bump `KELIVER_VERSION` in
   `build-support/src/main/kotlin/dev/keliver/buildsupport/RedwoodBuildPlugin.kt`.
2. Pre-gate locally:
   `./gradlew publishToMavenLocal -PkeliverVersion=X.Y.Z -DRELEASE_SIGNING_ENABLED=false`
   and `apiCheck`.
3. Tag `vX.Y.Z` and push the tag.
4. `GH_REPO=waliasanchit007/keliver gh workflow run publish-maven-central.yml -f ref=vX.Y.Z`
   (it refuses a tag that does not match `KELIVER_VERSION`). A `v*` tag also
   fires `publish.yml`.

**Tools bundle** (`keliver-portal-tools-X.Y.Z.zip`, a GitHub release asset):

1. Bump `build-support/portal-tools.version`.
2. Tag `portal-tools-vX.Y.Z`. That fires `portal-tools.yml` only, which
   rebuilds and checks but cannot publish; it never touches a library.
3. Attach the asset **by hand, from the retained artifact that was
   verified**, never from a rebuild at the tag — the full procedure and its
   gates are in [`docs/PORTAL_TOOLS_RELEASE.md`](./docs/PORTAL_TOOLS_RELEASE.md).

## What lives where

Keliver's schema parser + codegen live in this repo (`keliver-tooling-schema`,
`keliver-tooling-codegen`), and so does the shared widget library,
`keliver-material*` (its schema, guest composables and host renderers). The
portal (`portal-*`), the editor shell (`portal-editor`) and the tools-bundle
scaffolders (`scripts/`) are here too. An app with widgets of its own defines
them in its own schema module — [`sample/`](./sample) shows that shape.
