# The wrapper for apps Keliver generates

`wrapper.properties` here (installed as an app's
`gradle/wrapper/gradle-wrapper.properties`) names the Gradle that the scaffolder templates'
toolchain runs on (Kotlin 2.2.0, AGP 8.12.0, Compose Multiplatform 1.8.2:
`scripts/keliver-init`, `scripts/keliver-new-production-host.sh`,
`scripts/keliver-new-ios-host.sh`, `scripts/keliver-new-editor.sh`,
`scripts/fixtures/embed-older-compose`). It is pinned to Gradle 9.0.0 with the
distribution's official sha256.

It is deliberately **independent of this repository's own wrapper**
(`gradle/wrapper/gradle-wrapper.properties`), which follows Keliver's build
toolchain. AGP 8.12 uses a Gradle-internal API that Gradle 9.6 removed, so an
app on the templates' pins fails at configuration on the repo's newer Gradle.

Every place that gives a generated or adopter app a wrapper goes through
`scripts/keliver-app-wrapper.sh <dir>`, which takes `gradlew`, `gradlew.bat` and
`gradle-wrapper.jar` from the repo root (the launcher runs older distributions)
and the properties from here:

- `scripts/build-portal-tools.sh` stages the tools bundle's `wrapper/`, which
  the bundle's `keliver-init` copies into a new app;
- `scripts/keliver-init` from a checkout, and `scripts/keliver-new-editor.sh`'s
  fallback when the app has no wrapper;
- the self-tests that build template apps (`keliver-embed-floor.sh`,
  `keliver-embed-floor-check.sh`, `keliver-new-production-host-selftest.sh`,
  `keliver-new-ios-host-selftest.sh`, `keliver-consumer-smoke-selftest.sh`).

Move it together with the templates' pins, never with the repo's wrapper.
