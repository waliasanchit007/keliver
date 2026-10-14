# keliver-portal-tools 0.3.9 — release notes

**Status: RELEASED 2026-10-13** (candidate 1). The owner had given full
control over releases and publications ("you have full control on release or
publications", 2026-10-12).

| | |
|---|---|
| tag | `portal-tools-v0.3.9` (annotated, object `bb244761`) → **`6069da99f75390b1effe8934dbb1e607c0a275fc`** |
| release | [portal-tools-v0.3.9](https://github.com/waliasanchit007/keliver/releases/tag/portal-tools-v0.3.9), `--latest=false` (the "Latest" badge stays `v0.3.3`) |
| zip asset | `keliver-portal-tools-0.3.9.zip`, 90,611,347 bytes, sha256 **`31c4214cdd4588316f153f6edc9e96c865b44e1ed6475fa70a007653c75f6fa1`**, uploaded from the retained artifact `11843942421` of build run 38454753493 |
| `.sha256` asset | 97 bytes, sha256 `e1210db34845694221306b7eea013b1088e369d82aab5bcdfbe62234049f0eb7` |
| device host APK | `keliver-device-host-0.3.9.apk`, sha256 `2df637b8dd67fedc6a8b49ec6cbecfef1efd628f547656b3ddec3e6c911835e3` (no embedded portal key) |
| tag push | ran only `portal-tools.yml`'s read-only rebuild (38468214547); its artifact is not attached |

**How the stored asset was checked** (procedure step 4):
1. The draft's server-side `digest` and `size` matched both files.
2. **The draft download-back worked this time.** It returned 90,611,347 bytes,
   sha256 `31c4214c…`, identical to the verified artifact. The tag was
   re-checked at `6069da99`.
3. After publishing, the public zip and its `.sha256` were downloaded here, and
   `shasum -a 256 -c` printed OK.

Tools 0.3.8 and earlier, and the Maven 0.3.3 libraries, are unchanged. The
tools and library versions are separate lines: 0.3.9 still scaffolds against
`dev.keliver:*:0.3.3`.

## New: signing-key rotation (W8)

The whole procedure is in `KEY_ROTATION.md`, at the root of the bundle.

- **Hosts trust one key, or two during a rotation:** this app's key, then the
  next one.
  - Android: one key per line in `src/main/assets/keliver/portal_ed25519.pub`.
    The build refuses more than two, or the same key twice.
  - iOS: `PORTAL_NEXT_PUBLIC_KEY_HEX` in `HostConfig.kt` (empty when no
    rotation is under way).
- **The signing block signs with one or two keys.** The signing key file (or
  `KELIVER_SIGNING_KEY_FILE`) may hold two private keys, one per line.
  - Each key signs under its own name, `keliver-ed25519-<first 16 hex>`.
  - Then the first key signs `portal-ed25519`, **last**. That is the only name
    hosts from before this release trust, so they keep loading every bundle
    signed with your current key.
- **The rollback floor is kept across a rotation.** A host reads the highest
  floor over the keys it trusts, and writes it under each.
- **`keliver-publish`:**
  - It accepts one or two public keys.
  - It refuses a bundle that shares no signing key with the newest published
    bundle, unless `--accept-key-change`. That catches signing with the wrong
    key by mistake.
  - It refuses a bundle whose `portal-ed25519` signature isn't this app's key
    when that key signed, because hosts from before W8 would refuse it.
  - `--new-signing-key <dir>` makes the next key pair: an owner-only private
    key, never replaced and never printed.
- **Upgrading:**
  - Run `keliver-new-publish-target.sh` in each app. It replaces a 0.3.6,
    0.3.7 or 0.3.8 signing block exactly, and refuses one you edited.
  - Hosts scaffolded by 0.3.8 or earlier keep working, but they trust one key.
    To rotate, update them to this release's host sources first
    (`KEY_ROTATION.md`, "Limitations").

## Hardening of the bundle lookup (W3's open items)

- **One deadline:** the index and the relay's `/bundles/latest` fallback share
  10 s in all, not 10 s each.
- **iOS:** the lookup has a total timeout, not only an idle one.
- **A size cap:** `bundles/index.json` and `/bundles/latest` are not read past
  1 MiB.
- **Strict numbers:** `format`, `sequence` and `widgetVersion` must be JSON
  numbers. A quoted number makes that entry unusable.
- **Capability filtering** is now checked on an emulator and a simulator
  (rows S16 and S16b), not only in unit tests.

## Fixed: U32, the iOS host's team setting

The iOS host template's `TEAM_ID` never reached `DEVELOPMENT_TEAM`, and
`Config.local.xcconfig` was never included. A device build needed the team set
by hand in Xcode. Hosts scaffolded by 0.3.7 or 0.3.8 need the workaround in
`KNOWN_BUGS.md` (U32).

## Not in the bundle

The physical-device harness mode and `docs/DEVICE_PROOF.md` (W7 preparation)
live in the repository. **Nothing in this release has run on a physical device
yet.**

## Candidate verification (candidate 1, source `6069da99`)

- **Build:** `portal-tools.yml` 38454753493 (dispatched from
  `release/portal-tools-0.3.9`). Retained artifact `11843942421`, zip
  `31c4214c…`.
  - The packaged self-tests: production-host 65/0 and 60/0; ios-host 63/0 and
    63/0; publish-target 44/0 (with `--build`: two-key signing in a real
    Gradle build) and 26/0; `keliver-publish` 41/0; key-permissions 63/0.
  - The adopter acceptance and the U21 identity gate.
- **Device:** `portal-tools.yml` 38463890634 on the APK `2df637b8…`: 19/0 and
  28/0, with no FAIL lines. The verifier came from the same branch.
- **Local check of the retained artifact** (step 2):
  - The downloaded zip hashes to the build's.
  - `VERSION.json`: toolsVersion 0.3.9, sourceCommit `6069da99`, 0 dirty
    files, Maven dependency 0.3.3.
  - The APK hashes to the device run's and holds no `portal_ed25519.pub`.
  - The bundle's `bin/`, `templates/`, `README.md`, `KEY_ROTATION.md` and
    `host/README.md` (58 files) are byte-identical to the source commit.
- **`ios.sh` on the candidate zip, locally:** **146/0**
  (`KELIVER_CANDIDATE_SHA256`; the zip's own scaffolders and
  `keliver-publish`; a disposable simulator, deleted). That includes the W8
  rows K1–K8. With a zip that already has W8, K3/K6 used the one-key host;
  the pre-W8 host ran in the merged PRs' CI.
  - The first two local attempts failed before testing anything. macOS's
    periodic `/tmp` cleaning had deleted files in the disposable Gradle home
    and Konan directory, which were repaired. Nothing about the candidate was
    involved.
- **Step 3**, the workflows at `6069da99`: only `portal-tools.yml` listens on
  `portal-tools-v*`, with no write permission. `publish.yml` listens on `v*`.
  `ci.yml` ignores tags.
- **Before the candidate:** every change in it was reviewed independently and
  green on CI with the PRs merged together:
  - #98 at `40e14fd7`: device 160/0, `ios.sh` 147/0;
  - #100 at `5e1c3ffe`: device 142/0, the physical-route rehearsal 137/0,
    `ios.sh` 127/0;
  - #101 at `99883d7a`: device 142/0, `ios.sh` 127/0.

## Published-only proof after the release

PR #102 pins the reference app and its harness to this published zip (`31c4214c…`).
All of its runs are green at `5ca15f16`, or at `4c19479d` where only the Android
harness changed in between.

| run | mode | result |
|---|---|---|
| reference-app 38494338455 | **dispatch: scaffolders from the published zip** (`KELIVER_SCAFFOLD_FROM: zip`) | prepare **30/0**, device **160/0**, K1–K8 all PASS (18 checks) |
| reference-app 38483538737 | pull request: this checkout's scaffolders | prepare 30/0, device 160/0, K1–K8 all PASS |
| ios-host 38468862546 | **dispatch: scaffolders from the published zip** (at `4c19479d`; `ios.sh` is unchanged since) | `ios.sh` **147/0**, K1–K8 all PASS |
| ios-host 38483538839 | pull request | `ios.sh` 147/0, K1–K8 all PASS |
| CI 38483538738 | pull request | success |

- **The two dispatches are the first runs where the W8 rows come entirely from a
  published zip.** That covers:
  - the scaffolders and `keliver-publish` from 0.3.9;
  - the pre-W8 host for K3/K6, taken from the **published** tools 0.3.8
    (`ci/w8/legacy-tools.sh`, hash-pinned `aafed0d8…`).
- In those runs:
  - K3: the pre-W8 host loaded the dual-signed v10.
  - K6: it refused v11, signed by the next key only ("manifest signature for key
    portal-ed25519 did not verify").
- **What these runs caught first** (runs 38468857400 and 38468858084). The harness's
  own v1 check still expected `portal-ed25519` as the only signature. 0.3.9 signs
  with this app's key name first, then `portal-ed25519` last.
  - The check now reads the signatures in order and accepts either shape.
  - This was not a defect in the release.
- **Interrupted runs, not failures in the code:**
  - CI 38468857261: the hosted runner lost communication.
  - ios-host 38468857330: it hit its time limit while four heavy jobs shared the
    machine.

  Both reran green.
