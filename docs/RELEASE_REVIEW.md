# Release review — keliver-portal-tools 0.3.4 candidate

Prepared 2026-09-11. **Nothing has been pushed, tagged, uploaded or dispatched,
and no published bytes have been replaced.** This is a candidate built locally
from a clean tree, for a release decision.

## Candidate

| | |
|---|---|
| tools version | **0.3.4** (`build-support/portal-tools.version`) |
| source commit | `ca95f1bab8878a597fe0284c0e67626962f22496`, clean tree |
| package | `build/portal-tools/keliver-portal-tools-0.3.4.zip` |
| sha256 | `c023deb98fbc01569c5ba72a69f3b8e28a1bea2abc3860cf869bf738b9469513` |
| size | 90,306,099 bytes |
| Maven dependency version | **0.3.3, unchanged** — what the bundled scaffolders write into new projects |
| recorded in the package | `VERSION.json` and `VERSION` at the bundle root |

The package was built at `ca95f1bab`. The commits after it touch only
`CURRENT_STATE.md`, `KNOWN_BUGS.md` and this file — none of which the bundle
contains — so tagging a later commit would produce a byte-identical package
apart from `sourceCommit` in `VERSION.json`. Tag `ca95f1bab` to match the
artifact exactly, or tag the tip and rebuild; either is defensible, but say
which one the published asset came from.

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
  *Covered by:* a direct check against this candidate's packaged relay (404
  returned, source tree byte-identical, only `home.kt` present). There is **no
  automated test** for this path — see Blockers.
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
- **Scaffolder fixes:** `keliver-init` writes a `.gitignore`; a documented route
  exists for adopting a pre-existing store. *Covered by:* the acceptance's
  scaffold and ownership steps.

Not in these notes, deliberately: **U19 is not fixed.** A preview defect was
observed, two fixes were written, and both were removed once measurement showed
they changed nothing. `portal-editor` is identical to `v0.3.3`. U19 stands as
*previously observed, currently unreproduced, cause unresolved*.

## Evidence

| check | result |
|---|---|
| `:portal-editor:wasmJsTest :portal-relay:test :portal-mcp:test apiCheck` | BUILD SUCCESSFUL, **194 tests, 0 failures** |
| `keliver-adopter-acceptance.sh` vs this zip | **16 passed, 0 failed** |
| `keliver-acceptance-identity-check.sh` vs this zip | **6 passed, 0 failed** |
| unknown-screen `/doc` against the packaged relay | 404, source tree unchanged |
| packaged APK contents | no `assets/portal_ed25519.pub` — see Blockers |
| device / emulator route | **NOT RUN — verification incomplete** |

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

1. **The device route is unverified for a release that ships an APK.** There is
   no AVD, no system image, no `sdkmanager` and no attached device on this
   machine, so the emulator route could not be exercised. The acceptance's
   device steps were *skipped*, not passed. This candidate ships
   `host/keliver-device-host-0.3.4.apk` that nobody has installed or launched.
2. **A locally built bundle can embed the builder's portal public key.**
   `:portal-device-android:assembleDebug` copies
   `<store>/keys/ed25519.pub` into `assets/portal_ed25519.pub` when the build
   machine has a portal store with keys. The first 0.3.4 build did exactly that
   with this machine's key, which would have shipped one developer's portal
   identity inside a public artifact and made prod-mode verification fail for
   every adopter signing with their own key. The candidate above was rebuilt
   with `PORTAL_STORE` pointed at an empty directory and contains **no**
   embedded key, matching what a clean CI machine produces — but nothing
   enforces that, and a future local build will silently re-embed. Recorded as
   U22.

**Other limitations:**

- macOS only; Linux inferred from the script, not executed.
- The acceptance runs a single app: it says nothing about concurrent store
  isolation, which rests on the unit tests named above.
- `/doc` unknown-screen behaviour has no automated test; it was checked by hand
  against this candidate.
- U20 remains open and unassessed: the editor asks the browser for a frame every
  ~16 ms for the life of the page. Pre-existing, not optimised here.
- Prerequisites for an adopter are unchanged and untested off this machine:
  JDK 17, `python3`, and `adb` for the device path. Behind a TLS-inspecting
  proxy, `NODE_EXTRA_CA_CERTS` and a Gradle truststore are needed — and
  isolating `user.home` without keeping `GRADLE_USER_HOME` on the real one loses
  that truststore and every download fails PKIX.

## Exact release actions, awaiting authorization

None of these has been performed.

1. Decide on the two blockers: run the device route (or accept shipping an
   unexercised APK), and decide whether the APK must be built with an empty
   `PORTAL_STORE` by construction rather than by convention.
2. `git tag portal-tools-v0.3.4 ca95f1bab` — the tag must name the commit the
   package was built from.
3. `git push origin portal-tools-v0.3.4` — this fires `portal-tools.yml` only.
   It will *rebuild* the zip on `ubuntu-latest`, which is the unverified path;
   if that is not wanted yet, create the GitHub release manually and upload the
   locally built zip instead, leaving the tag push for when the CI gate passes.
4. Verify the published asset's sha256 against the candidate above if the local
   zip is uploaded; expect a *different* hash if CI rebuilds it, and re-run the
   acceptance against whatever is actually published.
5. Leave `v0.3.3` and its asset alone. No Maven action of any kind.
