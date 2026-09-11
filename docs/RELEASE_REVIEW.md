# Release review — keliver-portal-tools 0.3.3 candidate

Prepared 2026-09-11. **Nothing has been pushed, tagged, published or sent.** This
is a candidate built locally from a clean tree, for review.

## Candidate

| | |
|---|---|
| commit | `14b1b00c16eedff765a20b1065e65c3f219d9cbf` (`main`, working tree clean at build time) |
| package | `build/portal-tools/keliver-portal-tools-0.3.3.zip` |
| sha256 | `0232d86f3fa39589ec23b81adef92ffec102ebb0656d654141984e66c3561bb0` |
| size | 90,305,446 bytes (published 0.3.3 asset: 70,002,032 bytes) |
| contents | `bin/` `relay/` `mcp/` `editor/` `host/` `wrapper/` |
| built by | `scripts/build-portal-tools.sh` |

## No Maven release is needed

Established from the diff, not assumed. Modules published on Central
(`portal-editor`, `portal-render`, `portal-core`, `portal-document`, and the
`keliver-*` libraries — each verified HTTP 200 at `0.3.3`):

```
git diff v0.3.3 HEAD -- <module>/src/*Main <module>/api     →  empty for every one
```

The only change touching a published module is a `wasmJsTest` dependency in
`portal-editor/build.gradle` (`kotlinx.coroutines.test`), which is a test source
set and is not part of the published artifact.

`portal-relay`, `portal-mcp` and `portal-published-guest` return **404** on
Central — they ship only in the tools bundle.

**So `dev.keliver:*:0.3.3` on Maven Central does not need replacing.** Adopters
keep resolving exactly what they resolve today.

## What is in this candidate

Everything unreleased since the `v0.3.3` tag, grouped by what it actually is.

### Tools bundle — the whole of the change

Three production files, both binaries that ship in the bundle:

- `portal-relay/src/main/kotlin/PortalConfig.kt` (+143) and `Relay.kt` (+64)
- `portal-mcp/src/main/kotlin/dev/keliver/portal/mcp/Tools.kt` (+84)

plus the build-script store contract (root `build.gradle`) and the device host
build files, which affect the bundled APK rather than any library.

Adopter-visible fixes, all verified by the packaged acceptance below:

1. **One document store per app** (U17). A single store on the machine used to
   be shared by every app, so two projects overwrote each other's documents.
   Each app now owns a store keyed to its path (`myapp-8b8e4fa1`), claimed
   atomically, and the acceptance asserts the store lives outside the app tree.
2. **`get_guide` works outside the Keliver checkout** (U18). It used to read a
   repo-only path and answer "guide not found" for every real adopter; the guide
   now ships as a classpath resource in the MCP binary (14,185 bytes returned in
   the acceptance), and an app that keeps its own `docs/PORTAL_USAGE.md` still
   wins.
3. **`get_document` returns qualified screen ids** (U16), which previously came
   back as an empty document.
4. **`keliver-portal` refuses an occupied port** instead of appearing to start.
   Observed directly during this review — see Blockers.
5. Scaffolder fixes: `keliver-init` writes a `.gitignore`; `/doc` no longer
   404s; a documented route exists for adopting a pre-existing store.

### Documentation

`PORTAL_ADOPTER_GUIDE.md` (which is what `get_guide` serves), `KNOWN_BUGS.md`,
`CURRENT_STATE.md`, and the evidence set under
`docs/superpowers/evidence/adopter-preview-route/`.

### Tests and evidence

`PreviewTestEditor.kt` and five preview tests; the U19 investigation record.
No adopter-facing effect.

## U19 — do not describe this as a fix

The release notes must not say the preview was fixed, and must not say Maven
`0.3.3` needs replacing because of it.

A preview defect was observed (`0 → 1 → 1 → 1` where the device showed
`0 → 1 → 2 → 3`) and two fixes were written for it. Both were then **removed**
(`b4102945f`) because measurement showed they changed nothing: the editor's host
requests a browser frame every ~16 ms regardless, so the frame they asked for was
already being asked for. `portal-editor`'s production sources and klib dump are
identical to `v0.3.3`.

**Status: previously observed, currently unreproduced, cause unresolved.** Not
fixed, and not "never happened". Detail:
`docs/superpowers/evidence/adopter-preview-route/U19-RECONCILIATION.md`.

## Evidence

### Test gate, at the candidate commit

`./gradlew :portal-editor:wasmJsTest :portal-relay:test :portal-mcp:test apiCheck`
→ **BUILD SUCCESSFUL**, 194 tests, 0 failures across the suite, including:

| suite | tests |
|---|---|
| `LivePreviewDispatchTest` / `LivePreviewAsyncTest` | 2 / 3 |
| `PortalConfigTest`, `PortalStoreOwnershipTest`, `StoreClaimRaceTest`, `StoreContractTest` | 9 / 5 / 3 / 6 |
| `GuideTest`, `ScreenIdTest` | 7 / 7 |
| `SignedBundleVerificationTest`, `PreviewDistributionRunnerTest` | 2 / 3 |
| `apiCheck` | clean |

### Packaged adopter acceptance, against this exact zip

`scripts/keliver-adopter-acceptance.sh <parent> build/portal-tools/keliver-portal-tools-0.3.3.zip`
— the guide executed literally from the package, nothing from a checkout:

```
package: 0232d86f3fa39589ec23b81adef92ffec102ebb0656d654141984e66c3561bb0
passed: 16   failed: 0
```

covering: scaffold → portal starts → `get_guide` (14,185 bytes, no repo-only
commands) → `get_document` (version 1, title `MyApp`) → `apply_ops` dry-run then
commit (version 2) → the source now reads the edited title → exactly one tracked
file changed → hand-owned logic byte-identical → `compileKotlinJs` succeeds →
stop/start → the edit persists in both document and source → the store is
outside the app.

Device steps were **skipped** (no `--serial`).

### Browser check, final artifact

App editor rebuilt with `--refresh-dependencies` resolving `portal-editor:0.3.3`
from Central; the page loaded `0a6d7167e222132265b6.wasm`
(sha256 `33710124a18f5f34…`), `serviceWorkers: 0`, `caches.keys(): []`, no
diagnostic probe present. Three synchronous canvas taps `0 → 1 → 2 → 3`; an
asynchronous completion reaching canvas and State Inspector with no interaction;
Stop clears the live values and a restart is fresh with no stale value.

## Distribution

**Destination**: a GitHub release asset on `waliasanchit007/keliver`, which is
where `keliver-portal-tools-0.3.1/0.3.2/0.3.3.zip` were published. No Maven
Central step.

**Steps** (none performed):

1. Decide the version — see the open decision below.
2. Tag if the version changes; `KELIVER_VERSION` lives in
   `build-support/.../RedwoodBuildPlugin.kt` and the release workflow guards
   `tag == const`.
3. `scripts/build-portal-tools.sh` on the release commit.
4. Attach the zip to the release and publish it.

### Open decision — the only one blocking a release

**This candidate is version `0.3.3`, and a `keliver-portal-tools-0.3.3.zip` is
already attached to the `v0.3.3` release** (uploaded 2026-09-05, 70,002,032
bytes). This one is a different, larger artifact with the same name and version.

Someone has to choose:

- **replace** the existing 0.3.3 asset — same coordinate, different bytes, and
  anyone who already downloaded 0.3.3 has something else; or
- **cut 0.3.4** for the tools bundle — which means bumping `KELIVER_VERSION` and
  so also implies Maven artifacts at `0.3.4` that are byte-for-byte equivalent to
  `0.3.3`, unless the tools bundle is versioned separately.

The second is the honest one, but it forces a question this repo has not
answered: whether the tools bundle and the Maven libraries share a version line.
That is a maintainer decision, not a technical blocker.

## Blockers and limitations

**No product blockers were found within the checks performed.**

One **verification-harness** defect was found, and it is worth fixing before the
acceptance is trusted again:

- `scripts/keliver-adopter-acceptance.sh` reports "keliver-portal started and
  answers" when *anything* answers on the port. During this review a relay left
  running from an earlier step occupied `:8077`; `keliver-portal` correctly
  refused to start ("a portal server is already answering on :8077"), but the
  acceptance treated the foreign server's reply as success and drove every
  subsequent MCP call against a **different app**, editing that app's source.
  It reported 12 passed / 3 failed with `title 'Ledger'` in a run that had
  scaffolded `MyApp`. Re-run with the port free: 16/0. The harness needs to fail
  when `keliver-portal` reports it did not start; it has not been changed here.

Limitations of what was verified:

- **macOS only** (Darwin 25.5.0, JDK 17). Linux and the self-hosted CI runner are
  inferred from code, not executed.
- **No device run** this round — the acceptance's device steps were skipped, and
  the bundled host APK was built but not installed or exercised.
- The browser check is Chrome 152 headless, one app, one presenter.
- U20 is open and unassessed: the editor asks the browser for a frame every
  ~16 ms for the whole life of the page, in every state including before Live is
  pressed and after it is stopped. Pre-existing, not introduced here, and
  deliberately not optimised.
- Bundle prerequisites are unchanged and untested outside this machine: JDK 17,
  `python3` (the editor is served by `http.server`), and for the device path
  `adb`. Behind a TLS-inspecting proxy, `NODE_EXTRA_CA_CERTS` and a Gradle
  truststore are required — and note that isolating `user.home` without keeping
  `GRADLE_USER_HOME` pointed at the real one loses that truststore and every
  download fails PKIX.
