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
embeds the framework. The fix, and its measurement, are below.

## The fix, measured (2026-09-24)

`portal-device-ios/build.gradle`: `generatePortalKey` now empties its directory
in its own action and writes `PortalPublicKey.kt` into it, and
`outputs.upToDateWhen { false }` makes it do so on every build — the shape
`portal-device-android`'s `syncPortalKey` already has, for the same measured
reason (output-directory contents are not part of Gradle's up-to-date check).

`fix-repro.sh` measures all three boundaries on a **warm** build and prints the
generated constant. Run once on the unfixed tree and once with the fix, both at
`b96a9e2eb` (the build file is identical on this branch), on macOS with Xcode:

| | unfixed (`fix-before.txt`) | fixed (`fix-after.txt`) |
|---|---|---|
| 1 — planted file after a warm `generatePortalKey` | task `UP-TO-DATE`, **survived** | task ran, **absent** |
| 2a — files in the module klib mentioning the plant | **3** | 0 |
| 2b — `plantedMarker` in `PortalDeviceHost.h` / `nm` / UTF-16 literal | **1 / 2 / 1** | 0 / 0 / 0 |
| `PortalPublicKey.kt` | exactly the expected source, store key `5ca1ab1e…` | same |
| the key's UTF-16 literal in the linked binary | 1 | 1 |
| 3 — nothing planted, link again | (not run) | `generatePortalKey` runs; compile and link `UP-TO-DATE` |

Row 3 is why running every time costs nothing: rewriting identical bytes does
not dirty the compile, whose inputs are content hashes.

Isolation: a disposable Gradle home, the JVM's `user.home` and
`KONAN_DATA_DIR` inside the run directory, and a store holding only a dummy
PUBLIC key (no private key exists in this run). Both outputs end with the check:
nothing under the real `~/.konan` changed. **Disclosed:** a first attempt at the
unfixed run reused a Gradle daemon that had been started without the disposable
`user.home`, so Kotlin/Native resolved the real `~/.konan` toolchain (it touched
one `.lock` file there; nothing was downloaded into it). That run was discarded,
the worktree's build outputs deleted, and it was repeated with an explicit
`KONAN_DATA_DIR` and a Gradle home no other build had used; `fix-before.txt` is
the repeat.

**Measured only for a debug `iosSimulatorArm64` framework.** Not measured: a
release framework, `iosArm64` (device), and an app embedding the framework. The
change is to the source directory both targets compile, but that is an argument,
not a measurement. Still open and out of this fix: the iOS host has no
`devOnlyHost` short-circuit, so every `compileKotlinIos*` consults the store
(`KNOWN_BUGS.md`, U25.4 note).
