# Release review — keliver-portal-tools 0.3.4 candidate

Prepared 2026-09-11. **Nothing has been pushed, tagged, uploaded or dispatched,
and no published bytes have been replaced.** This is a candidate built locally
from a clean tree, for a release decision.

## Candidate

| | |
|---|---|
| tools version | **0.3.4** (`build-support/portal-tools.version`) |
| source commit | `ec10e191aa0fc04ce6663342b23b2ccf2ce76891`, clean tree |
| package | `build/portal-tools/keliver-portal-tools-0.3.4.zip` |
| zip sha256 | `aecb3737c1d49591311fde2d9729bc5918b849b318a2dbaf76ab1d360a94788c` |
| size | 90,314,622 bytes |
| device host APK sha256 | `5ca96302df46fc4815a76d4487b0913ba5d160589f3f166f4defbb656b2c5a44` (development-only, **no embedded key**) |
| Maven dependency version | **0.3.3, unchanged** — what the bundled scaffolders write into new projects |
| recorded in the package | `VERSION.json` and `VERSION` at the bundle root |

The package was built at `ec10e191a`. Only this file has changed since — it is
not in the bundle — so tagging the tip and rebuilding yields the same package
apart from `sourceCommit` in `VERSION.json`. Tag `ec10e191a` to match the
artifact byte-for-byte.

The two version lines are now independent. `portal-tools-v*` does not match the
`v*` pattern `publish.yml` listens on, so a tools release cannot publish a
library; no `portal-tools-*` tag exists yet, so the convention is free. The
existing `v0.3.3` release and its `keliver-portal-tools-0.3.3.zip` asset are
untouched and stay that way.

**No Maven release is needed or implied.** Every Central-published module's
production sources and klib dump are unchanged since `v0.3.3`
(`git diff v0.3.3 HEAD -- <module>/src/*Main <module>/api` is empty for each);
the only build change to one is a `wasmJsTest` dependency, which is not part of
a published artifact. `portal-relay`, `portal-mcp` and `portal-published-guest`
are not on Central at all.

## Release notes

Adopter-visible changes since the published 0.3.3 tools bundle. Each line names
the check that actually covers it.

- **A document request for an unknown screen returns 404 and creates nothing.**
  Reading a document used to *mint* it: the engine materialised `<screen>.kt`
  and `Compiled_<screen>.kt` in the app's source tree, in a package that was not
  theirs, so merely opening the editor — or a typo, or a stale link — left junk
  in the working tree. `GET /doc?screen=<unknown>` now answers
  `404 {"error":"no screen 'nope' in project 'default'; known screens: home"}`.
  *Covered by:* the adopter acceptance, which now asserts all three parts
  automatically — the 404, that no source file appeared, and that no store
  document was created.
- **`get_document` accepts project-qualified screen ids.** It previously
  returned an empty document for ids like `default/home`; the id is now
  normalised before lookup. Note this is about what it *accepts*, not about the
  form it returns. *Covered by:* `ScreenIdTest` (7 tests — bare names pass
  through, an own-project prefix is stripped, a foreign project prefix is not,
  normalisation is stable, stray slashes tolerated, an empty suffix is not a
  screen).
- **Each app owns its own document store.** One store per machine used to be
  shared by every app, so two projects overwrote each other's documents. The
  store is now keyed to the app path and claimed atomically.
  *Covered by:* `PortalStoreOwnershipTest` (5), `StoreClaimRaceTest` (3, the
  concurrent claim), `StoreContractTest` (6, the shell resolver and
  `PortalConfig.storeDir()` agree), `PortalConfigTest` (9). The adopter
  acceptance additionally confirms the store lands outside the app tree for a
  single app — **it runs one app and therefore proves nothing about
  concurrency**; the race is the unit tests' claim, not the acceptance's.
- **`get_guide` works outside a Keliver checkout.** It read a repo-only path and
  answered "guide not found" for every real adopter; the guide now ships as a
  classpath resource in the MCP binary, and an app keeping its own
  `docs/PORTAL_USAGE.md` still wins. *Covered by:* `GuideTest` (7) and the
  acceptance (14,185 bytes returned, no Keliver-repo-only commands named).
- **`keliver-portal` refuses an occupied port** instead of appearing to start.
  *Covered by:* the new refusal regression below, which observes the refusal.
- **The bundled device host is development-only, and cannot be talked into
  production mode.** It previously embedded whatever portal key the *build
  machine* had — a locally built bundle shipped its builder's portal identity,
  which would make signature verification fail for every adopter signing with
  their own key — and, asked for production without a key, it logged a warning
  and loaded the bundle **unverified**. The shipped APK now carries no key
  (verified: no `assets/portal_ed25519.pub`), `build-portal-tools.sh` refuses to
  package one that does, and a production request is refused on screen before
  anything is fetched. An adopter's own production host is unchanged: its key is
  embedded and verification stays on. *Covered by:* `HostTrustPolicyTest` (6),
  `keliver-device-host-hygiene-check.sh` (5), and APK inspection — **not** by
  any on-device run.
- **Scaffolder fixes:** `keliver-init` writes a `.gitignore`; a documented route
  exists for adopting a pre-existing store. *Covered by:* the acceptance's
  scaffold and ownership steps.

Not in these notes, deliberately: **U19 is not fixed.** A preview defect was
observed, two fixes were written, and both were removed once measurement showed
they changed nothing. `portal-editor` is identical to `v0.3.3`. U19 stands as
*previously observed, currently unreproduced, cause unresolved*.

## Two artifacts, not one

The same source commit was built twice. **They are not byte-identical** — do not
assume they would be.

| | built on | zip sha256 | APK sha256 | size |
|---|---|---|---|---|
| local candidate | macOS 15, arm64 | `aecb3737c1d49591311fde2d9729bc5918b849b318a2dbaf76ab1d360a94788c` | `5ca96302df46fc4815a76d4487b0913ba5d160589f3f166f4defbb656b2c5a44` | 90,314,622 |
| **CI candidate** | `ubuntu-24.04` (runner image `ubuntu24/20260907.300`) | `2b6536a0252c33c7bfd59add41b346bb6805af8198d6642346b08e0310d6dd98` | `d4fb1605137342f6744628f1129ddaee746cb9c8a3288639f70bf7669ac76ebf` | 90,315,306 |

Both carry `VERSION.json` naming `ec10e191aa0fc04ce6663342b23b2ccf2ce76891`,
tools `0.3.4`, Maven dependency `0.3.3`; both APKs carry no
`assets/portal_ed25519.pub`, and both contain `lib/{arm64-v8a, armeabi-v7a,
x86_64, x86}`.

The CI artifact is **retained** (30 days) on run
[`34587245714`](https://github.com/waliasanchit007/keliver/actions/runs/34587245714),
so whichever is chosen can be published without a silent rebuild. Whichever is
chosen must be the one the remaining device check runs against.

## Linux CI — build and portable checks, no publication

Run `34587245714`, `workflow_dispatch` on branch `review/portal-tools-0.3.4`
(workflow commit `caca47383`), checking out the candidate commit.

Before dispatch the workflow was made least-privilege: the default is
`contents: read`, the `bundle` job is `contents: read`, and a separate `release`
job — `contents: write` — is gated on `github.event_name == 'push' &&
startsWith(github.ref, 'refs/tags/portal-tools-v')`. In this run `release` shows
**skipped**. A branch build or a manual dispatch cannot create a release, attach
an asset, publish a package or move a tag.

Executed on Linux, all passing:

| check | result |
|---|---|
| checkout identity | `building commit: ec10e191aa0fc04ce6663342b23b2ccf2ce76891` |
| `VERSION.json` vs checkout | `tools=0.3.4  maven=0.3.3  sourceCommit=ec10e191a…  checkout=ec10e191a…` |
| packaged APK key check | `no assets/portal_ed25519.pub - ok` |
| `:portal-relay:test :portal-mcp:test :portal-device-android:testDebugUnitTest` | BUILD SUCCESSFUL |
| `keliver-device-host-hygiene-check.sh` | passed: 5 failed: 0 |
| `keliver-adopter-acceptance.sh` (against the CI-built zip) | passed: 19 failed: 0 |
| `keliver-acceptance-identity-check.sh` | passed: 6 failed: 0 |

**A green Linux build is not runtime acceptance.** Nothing in that run installs
or launches the APK; `:portal-editor:wasmJsTest` was not part of it either.

## Evidence

| check | result |
|---|---|
| `:portal-editor:wasmJsTest :portal-relay:test :portal-mcp:test :portal-device-android:testDebugUnitTest apiCheck` | BUILD SUCCESSFUL, **200 tests, 0 failures** |
| `keliver-adopter-acceptance.sh` vs this zip | **19 passed, 0 failed** |
| `keliver-acceptance-identity-check.sh` vs this zip | **6 passed, 0 failed** |
| `keliver-device-host-hygiene-check.sh` | **5 passed, 0 failed** |
| `:portal-device-android:testDebugUnitTest` (`HostTrustPolicyTest`) | **6 tests, 0 failures** |
| unknown-screen `/doc` | now **automated inside the acceptance**: 404, no source created, no store document created |
| packaged APK contents | no `assets/portal_ed25519.pub` |
| device / emulator route | **NOT RUN — verification incomplete, see Blockers** |

The acceptance gate itself was repaired first (U21). It used to launch
`keliver-portal` in the background, ignore its result, and accept any server
answering the port; during the previous review a leftover relay answered and the
whole run — including the mutation — went to a different app while reporting
PASS. It now requires that `keliver-portal` itself started, and that the process
listening on the port is a descendant of a pid recorded in that app's
`keliver-portal` run directory, before any document request, and again after the
restart. A matching screen title is explicitly *not* accepted as identity: both
apps scaffold the same tree, so the title would have matched.

`keliver-acceptance-identity-check.sh` is the regression: a foreign relay on the
expected port, pointed at its own disposable app. It requires the acceptance to
exit nonzero at the startup/identity gate, issue no mutation, leave the foreign
app's **source and store byte-identical**, and leave the foreign relay
**running**.

## Build path

`portal-tools.yml` runs on `ubuntu-latest`. The job's real prerequisites, from
the script rather than the YAML: JDK 17; the Android SDK, because the bundle
ships a device-host APK built by `:portal-device-android:assembleDebug`;
Node/Yarn for the Kotlin/Wasm editor distribution; `python3` and `zip`. Nothing
in the bundle requires macOS — no iOS or native target is built.

**This candidate was built on macOS 15 (Darwin 25.5.0) with JDK 17. The workflow
has not been executed on Linux, in this block or any previous one.** The
workflow file has been corrected for the new tag convention, but a corrected
YAML is not evidence that it runs.

Supported path, by the evidence available: **build locally on macOS** as this
candidate was, and treat the Linux workflow as unverified.

**Required CI gate before trusting the workflow** (not authorized here, no
remote execution in this block): dispatch `portal-tools.yml` on a branch, and
require that it (a) builds the zip, (b) prints a `VERSION.json` whose
`sourceCommit` matches the dispatched ref, and (c) produces an APK — the Android
SDK on the runner is the piece most likely to be missing. Then re-run the two
acceptance scripts against the Linux-built zip on a machine that can run them.
Until that has passed, releases should be cut from the macOS build.

## Blockers and limitations

**Two findings that need a decision before publishing:**

1. **The device route is still unverified, and remains the one blocker.** The
   APK has never been installed or launched. An isolated SDK, an `aosp_atd`
   arm64 system image and an AVD were installed and configured here — see
   `docs/superpowers/evidence/tools-0.3.4/emulator-attempt.md` — but the boot
   was stopped rather than run: the machine's single APFS container was at 100%
   with ~3.3 GB free, the emulator wants ~7.4 GB at the default partition size
   (~3 GB at `disk.dataPartition.size = 800M`), and the `--serial` acceptance
   additionally runs Gradle inside a scaffolded app. The isolated SDK was then
   removed rather than left holding 5.5 GB of a full disk (free space went
   7.6 GB → 13 GB); nothing outside that isolated tree was touched.

   **A CI route is prepared and NOT dispatched**:
   `.github/workflows/portal-tools-device.yml`, `workflow_dispatch` only,
   `contents: read` + `actions: read`, no release step. It downloads the
   *retained* artifact from a nominated run, checks the APK's sha256 against an
   expected value, refuses an APK carrying a portal key, boots an x86_64
   emulator (the APK ships `lib/x86_64`, so this is possible), runs the packaged
   acceptance with `--serial`, then asserts that `--es mode prod` is refused on
   screen with no manifest or bundle request, and that the development route
   still starts afterwards. Screenshots and logcat are uploaded as evidence.

   That workflow has **never been run**: KVM availability and emulator disk
   headroom on a hosted runner are expectations, not measurements. Its first run
   is the experiment, and it needs your authorization.
2. ~~A locally built bundle can embed the builder's portal public key.~~
   **Fixed** (U22, `8751ad333`) — see the release note above. The property is
   now enforced by the build (`-Pkeliver.devOnlyHost=true`, plus a packaging
   refusal if the asset is present) rather than by remembering to set
   `PORTAL_STORE`, and `keliver-device-host-hygiene-check.sh` holds it across a
   warm build directory.

**Other limitations:**

- macOS only; Linux inferred from the script, not executed.
- The acceptance runs a single app: it says nothing about concurrent store
  isolation, which rests on the unit tests named above.
- U20 remains open and unassessed: the editor asks the browser for a frame every
  ~16 ms for the life of the page. Pre-existing, not optimised here.
- Prerequisites for an adopter are unchanged and untested off this machine:
  JDK 17, `python3`, and `adb` for the device path. Behind a TLS-inspecting
  proxy, `NODE_EXTRA_CA_CERTS` and a Gradle truststore are needed — and
  isolating `user.home` without keeping `GRADLE_USER_HOME` on the real one loses
  that truststore and every download fails PKIX.

## Exact release actions, awaiting authorization

None of these has been performed.

1. Decide the remaining blocker: run the device route, or accept shipping an
   APK nobody has launched.
2. `git tag portal-tools-v0.3.4 ec10e191a` — the tag must name the commit the
   package was built from.
3. `git push origin portal-tools-v0.3.4` — this fires `portal-tools.yml` only.
   It will *rebuild* the zip on `ubuntu-latest`, which is the unverified path;
   if that is not wanted yet, create the GitHub release manually and upload the
   locally built zip instead, leaving the tag push for when the CI gate passes.
4. Verify the published asset's sha256 against the candidate above if the local
   zip is uploaded; expect a *different* hash if CI rebuilds it, and re-run the
   acceptance against whatever is actually published.
5. Leave `v0.3.3` and its asset alone. No Maven action of any kind.
