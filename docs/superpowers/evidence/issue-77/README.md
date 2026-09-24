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
in its own action, writes `PortalPublicKey.kt`, and checks that the directory
holds exactly that one file; `outputs.upToDateWhen { false }` makes it do so on
every build — the shape `portal-device-android`'s `syncPortalKey` already has,
for the same measured reason (output-directory contents are not part of Gradle's
up-to-date check). After the independent review it also:

* empties the directory with `Files.walk` + `Files.delete`, which never follows a
  symlink and throws on failure. The first version used Groovy's `deleteDir()`,
  which does both wrong (rows 4–5 below);
* refuses a store key that is not 64 hex digits, because the key is spliced into
  Kotlin source (row 6).

`fix-repro.sh` measures the three boundaries on a **warm** build and prints the
generated constant; `fix-hardening.sh` measures the review's three cases, at task
level. Everything at `b96a9e2eb` — the build file is identical on this branch —
on macOS with Xcode:

| | unfixed | first fix (`deleteDir`) | hardened (this commit) |
|---|---|---|---|
| 1 — planted file after a warm `generatePortalKey` | task `UP-TO-DATE`, **survived** | task ran, absent | task ran, absent |
| 2a — files in the module klib mentioning the plant | **3** | 0 | 0 |
| 2b — `plantedMarker` in `PortalDeviceHost.h` / `nm` / UTF-16 literal | **1 / 2 / 1** | 0 / 0 / 0 | 0 / 0 / 0 |
| `PortalPublicKey.kt` | exactly the expected source, key `5ca1ab1e…` | same | same |
| the key's UTF-16 literal in the linked binary | 1 | 1 | 1 |
| 3 — nothing planted, link again | (step added later) | compile and link `UP-TO-DATE` | compile and link `UP-TO-DATE` |
| 4 — a symlink in the directory, to a directory holding a canary | (not run) | **the link's target was emptied** | target left alone; link removed |
| 5 — a plant that cannot be deleted (`chflags uchg`) | (not run) | **survived a green build** | build **fails**, naming the file |
| 6 — a store key that is Kotlin, not hex | (not run) | **became source** (`injected()` in `PortalPublicKey.kt`) | build fails; nothing generated |

Files: `fix-before.txt`, `fix-after.txt` (first fix), `fix-after-hardened.txt`,
`fix-hardening-committed.txt` (first fix), `fix-hardening-hardened.txt`. The
`real`/`user`/`sys`/`exit=` lines come from the wrapper that ran the scripts
(`/usr/bin/time`), not from the scripts. In `fix-after-hardened.txt` the compile
is `UP-TO-DATE` in step 2: the task had already removed the plant, so the
compile's inputs were those of the earlier plant-free compile, and the klib and
framework inspected are what those inputs produce.

Row 3 is why running every time costs nothing: rewriting identical bytes does
not dirty the compile, whose inputs are content hashes.

Isolation: a Gradle home no other build used (`<scratch>/gh77`, beside the run
directory), the JVM's `user.home` and `KONAN_DATA_DIR` inside the run directory,
and a store holding only a dummy PUBLIC key (no private key exists in any of
these runs). Each output ends by checking that nothing under the real `~/.konan`
changed. **Disclosed:** a first attempt at the unfixed run reused a Gradle daemon
that another build had started without the disposable `user.home`, so everything
that daemon resolved through `user.home` used the real home — Kotlin/Native's
toolchain in `~/.konan` (checked afterwards with `find ~/.konan -maxdepth 2 -newer
<the script>`: one `.lock` file changed, nothing added at that depth), and the usual per-user files
builds write there (`~/.kotlin/daemon`, `~/.android`), which were not checked. No
store was involved: `-Pkeliver.portalStore` is resolved before any `user.home`
lookup. That run was discarded, the worktree's build outputs were deleted, and it
was repeated as above; `fix-before.txt` is the repeat.

**Measured only for a debug `iosSimulatorArm64` framework.** Not measured: a
release framework, `iosArm64` (device), and an app embedding the framework. The
change is to the source directory both targets compile, but that is an argument,
not a measurement.

**Not covered by this fix** (the review's finding 2): the task is wired only to
tasks named `compileKotlinIos*`. The shared `iosMain` source set also gets
`compileIosMainKotlinMetadata`, which compiles the same directory without
`generatePortalKey` and so could compile a plant into the *metadata* klib. The
linked framework does not consume that klib. Wiring the directory to the task
itself would make the metadata compile consult the store too, which is the other
half of the iOS asymmetry (no `devOnlyHost` short-circuit; `KNOWN_BUGS.md`,
U25.4 note), and is left with it.
