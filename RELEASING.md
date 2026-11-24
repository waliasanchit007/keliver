# Releasing

Keliver has **two independent release lines**. Never bump one to name the
other.

## Libraries (`dev.keliver:*` on Maven Central)

1. Bump `KELIVER_VERSION` in
   `build-support/src/main/kotlin/dev/keliver/buildsupport/RedwoodBuildPlugin.kt`.
2. In `CHANGELOG.md`, rename the `## Unreleased (X.Y.Z)` heading to
   `## Keliver X.Y.Z - <date>`, and open a new, empty `## Unreleased (A.B.C)`
   above it that names the **next** version (the next patch, or the next minor
   when the next release will break consumers or change the toolchain). Write
   that heading exactly so: `scripts/keliver-runtime-line-selftest.sh` reads it to
   pick the line in `scripts/keliver-runtime-line.sh` whose toolchain must match
   this checkout's catalog and wrapper. Without it, the check falls back to
   `KELIVER_VERSION` (the last release) and says so in its failure. When the plan
   for the next release changes (a patch becomes a minor), change the heading in
   the same PR. There is one unreleased section: an older bare `## [Unreleased]`
   heading is folded into it, not kept beside it.
3. Pre-gate locally:
   `./gradlew publishToMavenLocal -PkeliverVersion=X.Y.Z -DRELEASE_SIGNING_ENABLED=false`
   and `apiCheck`.
4. Merge, then tag the merge commit `vX.Y.Z` (annotated) and push the tag.
   A `v*` tag also runs `publish.yml` (GitHub Packages).
5. Publish to Maven Central by hand:
   `gh workflow run publish-maven-central.yml -f ref=vX.Y.Z`
   (it refuses a tag that does not match `KELIVER_VERSION`).
6. Check that every published pom resolves from `repo1.maven.org`, and record
   the release in `docs/CURRENT_STATE.md` and `docs/DELIVERY_PLAN.md`.

Pushing a tag does **not** create a GitHub release.

## Tools bundle (`keliver-portal-tools-X.Y.Z.zip`, a GitHub release asset)

Bump `build-support/portal-tools.version` and tag `portal-tools-vX.Y.Z`. That
tag runs `portal-tools.yml`, which only rebuilds and checks: no job in it can
publish. The asset is attached **by hand, from the retained artifact that was
verified**, never from a rebuild at the tag. The full procedure, its gates
(`scripts/build-portal-tools.sh`, `scripts/keliver-adopter-acceptance.sh`,
`scripts/keliver-acceptance-identity-check.sh`, `scripts/keliver-device-verify.sh`)
and how the stored asset is checked are in
[`docs/PORTAL_TOOLS_RELEASE.md`](docs/PORTAL_TOOLS_RELEASE.md). Each release
gets a `docs/RELEASE_NOTES_TOOLS_X.Y.Z.md` record.

Never move or recreate a released tag.
