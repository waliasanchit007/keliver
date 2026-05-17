# Contributing to Konduit

Thanks for considering contributing. Konduit is currently a private fork
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

## Local dev loop

```bash
# Build everything
./gradlew assemble

# Publish to mavenLocal so downstream consumers can use your local changes
./gradlew publishToMavenLocal

# Run the codegen test suite
./gradlew :konduit-tooling-codegen:test
```

For changes that touch schema / codegen:

```bash
# Regenerate downstream artifacts to make sure you didn't break them
# (in the ServerDrivenUI reference repo)
cd ../ServerDrivenUI
./gradlew :presenter:compileDevelopmentExecutableKotlinJsZipline --no-build-cache
```

## PR expectations

- **Tests for codegen changes.** `konduit-tooling-codegen/src/test/` has
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

## Release flow

See [RELEASING.md](./RELEASING.md) for the canonical steps. Roughly:

1. On a release branch, bump `KONDUIT_VERSION` in
   `build-support/.../RedwoodBuildPlugin.kt` from `-SNAPSHOT` to the
   release version.
2. Update `CHANGELOG.md`: move `[Unreleased]` content into a new
   `[1.0.0-caliclan.N] - YYYY-MM-DD` section.
3. Commit `Prepare version 1.0.0-caliclan.N`.
4. Tag `v1.0.0-caliclan.N`. Push (with tags).
5. The `publish.yml` GHA workflow publishes to GitHub Packages on tag
   push. Verify the run lands green.
6. Bump version to `1.0.0-caliclan.{N+1}-SNAPSHOT` for next dev cycle.
   Commit + push.
7. Open PR → main, merge.

## What lives where

Konduit's schema parser + codegen live in this repo (`konduit-tooling-schema`,
`konduit-tooling-codegen`). The downstream **schema definitions** for
Caliclan-specific widgets and the **reference integration** live in
[`waliasanchit007/ServerDrivenUI`](https://github.com/waliasanchit007/ServerDrivenUI).
The **production app** consuming both is [DevoStatus](https://github.com/waliasanchit007/DevoStatus).

If you find yourself adding a new widget to ServerDrivenUI, that's the
right place — Konduit's codegen / runtime is the framework; widget
definitions are per-application.
