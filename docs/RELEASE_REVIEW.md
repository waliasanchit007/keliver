# Release review — keliver-portal-tools 0.3.4 candidate

Prepared 2026-09-11, device verification completed 2026-09-12. **Nothing has
been tagged, released, uploaded to a release, or published to Maven, and no
published bytes have been replaced.** Commits went to the review branch
`review/portal-tools-0.3.4` only; `main` was not pushed. This is a candidate for
a release decision.

**The device blocker is cleared.** The CI-built candidate APK has been installed
and driven on an emulator — CI run
[`34670604791`](https://github.com/waliasanchit007/keliver/actions/runs/34670604791),
19 device checks and 23 acceptance checks, 0 failures. See *Device verification*
below. The decision is now purely which artifact to publish and whether to
accept the stated limits.

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
  `keliver-device-host-hygiene-check.sh` (5), APK inspection, **and an on-device
  run** — CI run `34670604791` shows the refusal on screen, cold and warm, with
  no manifest or bundle requested and no guest code loaded.
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
| device / emulator route | **PASSED** — CI run `34670604791`, 19 device checks + 23 acceptance checks, 0 failed |

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

The candidate was also built on macOS 15 (Darwin 25.5.0) with JDK 17, which is
where the local artifact in the table above came from.

**The Linux workflow is no longer unverified.** Run `34587245714` executed it
end to end on `ubuntu-24.04`: it built the zip, printed a `VERSION.json` whose
`sourceCommit` matches the dispatched ref, produced an APK, and passed the
portable checks. The Android SDK — the prerequisite most likely to be missing —
was present. The device job then ran the resulting APK on an emulator.

Supported path: **the CI build is the one to publish.** It is reproducible from
a tag by anyone, its artifact is retained, and it is the artifact the device
verification actually ran against.

## Device verification

CI run
[`34670604791`](https://github.com/waliasanchit007/keliver/actions/runs/34670604791),
`workflow_dispatch` on `review/portal-tools-0.3.4`, running
`scripts/keliver-device-verify.sh` as committed at `dca19196d`. It downloads the
**retained** artifact from run `34587245714`, checks the APK's sha256 against
`d4fb1605…`, refuses an APK carrying a portal key, and installs it with the
bundle's own installer — nothing is rebuilt.

| what | result |
|---|---|
| emulator | `aosp_atd` x86_64, API 33, `pixel_5`, headless, swiftshader, KVM on |
| install | `installed dev.keliver.portaldevice (versionName 1.0)`, sidecar checksum matched |
| device checks | **19 passed, 0 failed** |
| packaged acceptance with `--serial` | **23 passed, 0 failed** (19 portable + 4 device) |
| `--es mode prod`, cold host | refused, on screen, no manifest/bundle requested, no guest code loaded |
| `--es mode prod`, warm host (after a dev session) | refused identically |
| development route | guest screen rendered: `My Inbox`, `First item`, `Second item`, `Refresh` |
| development route after a refusal | `mode=dev`, `codeLoadSuccess modules=40`, guest screen in the dump |

The on-screen evidence is the uiautomator view hierarchy, not a screenshot:
`adb exec-out screencap` on a `-no-window` emulator returns an all-black frame,
which an earlier run (`34669956183`) proved by producing three byte-identical
blank PNGs. Details, including two assertions that were tightened after that
discovery, are in
`docs/superpowers/evidence/tools-0.3.4/device-verification-ci.md`; the evidence
files are in `docs/superpowers/evidence/tools-0.3.4/device-run-34670604791/`.

## Blockers and limitations

**Both earlier findings are now closed.**

1. ~~The device route is unverified.~~ **Done** — see *Device verification*
   below. The local attempt was abandoned for disk (this machine's single APFS
   container was at 100%; `docs/superpowers/evidence/tools-0.3.4/emulator-attempt.md`),
   and the isolated SDK was removed. The hosted-runner route replaced it and
   works: 80 GB free, KVM available, boot to `sys.boot_completed=1` in 36-38 s.
2. ~~A locally built bundle can embed the builder's portal public key.~~
   **Fixed** (U22, `8751ad333`) — see the release note above. The property is
   now enforced by the build (`-Pkeliver.devOnlyHost=true`, plus a packaging
   refusal if the asset is present) rather than by remembering to set
   `PORTAL_STORE`, `keliver-device-host-hygiene-check.sh` holds it across a warm
   build directory, and the refusal is now observed on a device.

**Other limitations:**

- The device route was exercised on **one** emulator only: `aosp_atd` x86_64,
  API 33. No physical device, no other API level, and **no arm64 runtime** — the
  APK ships `lib/arm64-v8a` and `lib/armeabi-v7a`, neither of which was
  executed.
- The production *load* path remains unexercised end to end. The bundled host
  refuses production by design, so nothing here verifies a signed bundle
  actually loading in an adopter's own production host.
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

None of these has been performed. **No tag exists, no release exists, nothing
has been uploaded, and no Maven coordinate has moved.**

There is one open decision and then four mechanical steps.

### The decision

`portal-tools-v0.3.4` fires `portal-tools.yml`, whose `release` job attaches
**the zip that tag build produces** — a *fourth* build of `ec10e191a`, not the
artifact the device verification ran against. Two ways to close that gap:

- **(a) Tag and let CI build the asset.** Simplest, and the path is now
  verified: the same workflow already built, checked and produced a working APK
  on Linux twice. The published zip's sha256 will **not** equal
  `2b6536a0…` — a rebuild is not byte-reproducible here, and the two builds
  already recorded above differ. Accept that, then re-run
  `keliver-device-verify.sh` against the published asset to make the
  verification apply to the bytes adopters download.
- **(b) Publish the exact verified bytes.** Create the release manually and
  upload the retained artifact from run `34587245714` (zip `2b6536a0…`, APK
  `d4fb1605…`) — the bytes run `34670604791` actually installed and drove. The
  tag then names the commit but not the build.

**(b) is what the evidence supports**; (a) is defensible and cheaper, but ships
bytes nobody has run.

### Then

1. `git tag portal-tools-v0.3.4 ec10e191a` — the tag must name the commit the
   package was built from. Note `ec10e191a` is an ancestor of the review branch;
   the later commits are the device job, the verification script and these docs,
   none of which is in the bundle.
2. Under (a): `git push origin portal-tools-v0.3.4`. Under (b): push the tag and
   upload the retained zip to the release yourself, and be aware the tag push
   will still trigger a CI build whose `release` job would attach its own zip —
   so under (b) create the release from the retained artifact **first**, or hold
   the tag push.
3. Verify the published asset's sha256 and re-run
   `scripts/keliver-adopter-acceptance.sh`, `keliver-acceptance-identity-check.sh`
   and a device dispatch against **whatever is actually published**.
4. Leave `v0.3.3` and its asset alone. No Maven action of any kind — the library
   line does not move, and `portal-tools-v*` does not match the `v*` pattern
   `publish.yml` listens on.

### What this block changed

Commits on `review/portal-tools-0.3.4` only (`d6b7bb432` → `dca19196d`, plus
this doc): the device job in `.github/workflows/portal-tools.yml`,
`scripts/keliver-device-verify.sh`, the evidence, and these docs. **No
production source changed** — `portal-device-android`, `portal-relay`,
`portal-mcp`, `portal-editor` and every published module are untouched, so the
candidate commit `ec10e191a` and both candidate artifacts remain exactly what
they were.
