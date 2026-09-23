# #77 — `generatePortalKey`'s output directory keeps foreign source

Measured 2026-09-22 on macOS (Xcode present), in an isolated worktree of `main`
(`b96a9e2eb`), with `GRADLE_USER_HOME` and the JVM's `user.home` inside the run
directory and a disposable store holding a dummy public key (`ab`×32) passed as
`-Pkeliver.portalStore`. No real store, key or Gradle cache was used.

| boundary | script | result |
|---|---|---|
| 1 — a planted file survives an UP-TO-DATE `generatePortalKey` | `boundary1.sh` | **reproduced**: second run `UP-TO-DATE`, `Planted.kt` still present (`boundary1.out`) |
| 2a — the planted source is compiled | `boundary2-klib.sh` | **yes**: `compileKotlinIosSimulatorArm64` ran with `generatePortalKey` UP-TO-DATE; `PLANTED` is in the module klib's `linkdata/package_dev.keliver.portaldevice.ios/0_ios.knm` and `ir/strings.knt` |
| 2b — it reaches the linked framework | `boundary2-framework.sh` | **yes, for a debug simulator-arm64 framework**: a planted *public* function is exported in `PortalDeviceHost.h` and its compiled symbol and UTF-16 string literal are in the binary (`boundary2-framework-inspection.txt`) |

2b plants a public function rather than the issue's `internal const`, because
an unused internal constant can be removed at link time and its absence would
prove nothing. Not measured: a release framework, `iosArm64`, and an app that
embeds the framework. Nothing here fixes the defect.
