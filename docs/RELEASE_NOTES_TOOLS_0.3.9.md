# keliver-portal-tools 0.3.9 — release notes

**Status: CANDIDATE.** Not tagged or published yet. The verification is
recorded below as it happens.

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

## Candidate verification

To be recorded: the build run and its retained artifact, the device run, the
local check of the artifact, `ios.sh` on the zip, and the step-3 workflow
check.
