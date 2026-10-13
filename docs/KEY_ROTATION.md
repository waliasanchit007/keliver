# Rotating the signing key (W8)

Every production host verifies every bundle's manifest against the Ed25519
public key built into it. That key is the whole of the host's trust: a bundle
signed by anything else is refused. This page is how to change that key
without stranding a single install, and how to look after it until then.

**Needs:** host templates and a signing block from tools **0.3.9** on (W8).
Hosts and signing blocks from 0.3.8 and earlier trust or sign with exactly one
key; they keep working throughout, as described below.

## What W8 adds

- **A host trusts one key, or two during a rotation:** this app's key, and the
  next key.
  - Android: one key per line in `src/main/assets/keliver/portal_ed25519.pub`
    (the build checks every line, and refuses more than two).
  - iOS: `PORTAL_PUBLIC_KEY_HEX` and `PORTAL_NEXT_PUBLIC_KEY_HEX` in
    `HostConfig.kt` (the next one is empty when no rotation is under way).
- **The signing block signs with one key, or two.** The signing key file
  (`KELIVER_SIGNING_KEY_FILE` in CI) may hold two private keys, one per line:
  this app's key, then the next one.
- **The rollback floor is kept across keys.** A host reads the highest floor
  kept under any key it trusts, and writes it under each. So a host that
  trusts both keys carries the floor over to the next key.
- **`keliver-publish` guards against a key change by mistake.** A bundle that
  shares no signing key with the newest published bundle is refused (exit 4),
  unless you pass `--accept-key-change`.
- **`keliver-publish --new-signing-key <dir>`** makes the next key pair:
  - `<dir>/ed25519.priv`, owner-only, created and never replaced;
  - `<dir>/ed25519.pub`.

  It prints only the public half.

### How signatures are named, and why

Zipline 1.22 checks only the *first* signature in a manifest whose name the
host trusts, and refuses the bundle if that one does not verify. So a name must
mean the same key to every host and every signer:

- Each key signs under a name of its own, made from its public key's first 16
  hex digits: `keliver-ed25519-0123456789abcdef`. These signatures come first.
- The signing block then adds `portal-ed25519`, signed by its **first** key,
  **last**. That name is all a host from before W8 trusts.
- A W8 host trusts each of its keys under that key's own name, and its first
  key under `portal-ed25519` too. So bundles signed before W8 still load.

`keliver-publish` refuses a manifest whose `portal-ed25519` signature is not
the last one, or whose signatures don't verify the way a host that trusts the
public key file's keys would check them.

## The rotation, in three steps

Say the current key is **A**, and the next one will be **B**.

**0. Make B and keep it safe.**

```sh
keliver-publish --new-signing-key ~/keliver-next-key   # prints B's public key
```

Store `ed25519.priv` as a CI secret, next to A's, and make an offline backup
(see *Custody*). Delete the local copy once it is stored.

**1. Ship hosts that trust A and B.** Signing doesn't change yet.

- Android: add B's public key as the second line of
  `src/main/assets/keliver/portal_ed25519.pub`.
- iOS: set `PORTAL_NEXT_PUBLIC_KEY_HEX` to B's public key.
- Release the app through the stores. Wait until the installs still on
  hosts that trust only A are few enough to drop, or force them to update
  (a `minHostVersion` gate, or your own app's forced update).

**2. Sign with A and B.**

- Put both private keys in the signing key file, **A first**, one per line.
  The first key signs `portal-ed25519`, the only signature hosts from before
  W8 read. `keliver-publish` refuses the bundle if B comes first.
- Give `keliver-publish --public-key-file` a file with both public keys, A
  first. Your Android host's asset is exactly that file.
- Publish as usual.
  - Hosts that trust only A verify the `portal-ed25519` signature, which is A's.
  - Hosts that trust A and B verify A's or B's.
  - Nothing breaks, and the guard doesn't trip: the newest bundle was signed
    by A, and this one is too.

**3. Retire A.**

- The signing key file holds only B.
- The publish guard passes, because the newest bundle was signed by A and B.
- Hosts that trust A and B load the new bundles, through B's signature.
- Hosts that trust only A refuse them. Step 1 is what made those rare.
- Then ship hosts that trust only B:
  - Android: B as the only line;
  - iOS: B in `PORTAL_PUBLIC_KEY_HEX`, and an empty `PORTAL_NEXT_PUBLIC_KEY_HEX`.

  They load B's bundles, and they refuse anything signed only by A.
- Keep A's public key in `--public-key-file` while you may still republish
  (`--republish`) a bundle that only A signed.

The next rotation (B to C) is the same three steps.

### What the guard catches

`keliver-publish` refuses a publish when the bundle shares no signing key with
the newest published bundle (the highest sequence in `index.json`). Only keys
listed in `--public-key-file` count.

- **A → A+B → B:** passes at every step.
- **A → B directly:** refused. Hosts that trust only A would refuse B's bundles.
  If that is meant (A leaked, and you are moving on regardless), publish again
  with `--accept-key-change`.
- **A key that is not in the public key file:** refused, whatever the flag.
  This is the same refusal as "signed by another key".

## The rollback floor across a rotation

A host keeps the highest sequence it has run, per key. It refuses anything
below that, from the network and from the cache.

Since W8, a host reads the highest floor over all the keys it trusts, and
writes it under each. It does this at start, as well as after every load. So:

- Upgrading from a host that trusts A to one that trusts A and B copies A's
  floor to B at the first start.
- Upgrading from that host to one that trusts only B keeps the floor.

**Not covered:** an install that jumps straight from a host trusting only A to
one trusting only B, skipping every release that trusted both. That host
starts B's floor at 0. Until it loads a bundle, a server serving an older
bundle signed by B could roll it back. Only bundles signed during step 2 or
later carry B's signature at all, so the window is small, and the floor closes
it at the first load. It closes completely only for installs that run a release
trusting both keys at least once, which is why step 1 ships that release
first.

## Custody

- **The private key lives in a CI secret.** It is written to an owner-only file
  at job start, and named by `KELIVER_SIGNING_KEY_FILE`.
  - It is never in the repository, and never on a command line. The signing
    block reads it in the Gradle process and never makes it a task input,
    argument or log line (U31).
  - During a rotation, the secret holds both keys, one per line.
- **The publish job runs alone:** a concurrency group, and the live `bundles/`
  downloaded first (`docs/PORTAL_ADOPTER_GUIDE.md`).
- **Keep an offline backup of each key** (a password manager entry or a
  hardware-backed vault, not a laptop). A lost key with no backup means
  shipping new hosts before you can publish again.
- **Only CI can publish.** Developers publish to their local relay with the
  store's own key. That key is a development identity unless you made it the
  production key.

## If a key leaks

1. Make a new key now (`--new-signing-key`). Ship hosts that trust **only the
   new key**, without the leaked one (Android: one line; iOS: `PORTAL_PUBLIC_KEY_HEX`).
2. Sign with the new key only. Publish with `--accept-key-change`, because no
   published bundle carries the new key yet.
3. Every host that still trusts the leaked key can be served anything signed
   with it, at any sequence: the rollback floor does not help, because whoever
   holds the key can sign a sequence above it. Only hosts that no longer trust
   the leaked key are safe. Force-update the others (a store forced update), and
   gate new entries to the new hosts with `minHostVersion`.
4. Remove the leaked key from every secret store, and record the rotation.

## Limitations

- A signing block from before W8 signs with one key, as `portal-ed25519`. Run
  `keliver-new-publish-target.sh` to upgrade it: it replaces the 0.3.6, 0.3.7
  and 0.3.8 blocks exactly, and refuses one you edited.
- Hosts scaffolded from 0.3.8 or earlier trust one key under `portal-ed25519`.
  To rotate, they must first be updated to the W8 host sources:
  - `BundleIndex.kt`, `ProductionTrust.kt`, `KeliverHost.kt` / `MainViewController.kt`;
  - Android `build.gradle`;
  - iOS `HostConfig.kt` (add `PORTAL_NEXT_PUBLIC_KEY_HEX`) and `build.gradle`.

  Re-scaffolding into a scratch directory and copying those files over is the
  simplest way.
- **Republishing:** `--republish` checks only that a key in the public key
  file signed the source bundle (it is signed again, and that copy is checked
  like any publish). A bundle signed only by a key you have since dropped from
  the file can no longer be republished: keep that key in the file while you
  may need to, or rebuild the code from git.
- **A `KeliverConfig` built by hand** (Android) trusts only what you pass:
  set `nextPublicKeyHex` too, or the host trusts one key whatever the asset
  says. `KeliverConfig.fromBuild` reads both lines.
- **Order matters in both key files.** This app's (current) key comes first,
  the next one second. `keliver-publish` refuses a bundle whose
  `portal-ed25519` signature isn't this app's key, when that key signed: hosts
  from before W8 would refuse it.
- **The Zipline cache follows the first key.** A host that trusts A and B uses
  A's cache (no download after the upgrade). A host that trusts only B starts
  a new cache, so its first start after the upgrade needs the network.

## Evidence

- `KeyRotationTest` (portal-relay) runs a whole rotation through Zipline's own
  `ManifestSigner` and `ManifestVerifier`, built as the templates build them,
  and holds the publisher's check to them.
- The emulator and simulator rows K1–K8 run it on CI:
  - Android: `reference/inventory/ci/w8/android-rotation.sh`;
  - iOS: `ios-rotation.sh`.
- None of this has run on a physical device (W7).
