# keliver-portal-tools 0.3.6 — release notes

**Status: CANDIDATE — not tagged, not published.** This text is written in the
commit that gets built, so it cannot cite that build's verification. The
candidate's identities (zip and APK sha256, build and device runs) are recorded
after verification, outside this commit. Tools 0.3.5 stays the published
release until a person approves the tag and upload.

**A first build was superseded.** It was built from `e40d20ca` without #82
(U27) and #83 (#77): build `37564871932`, device `37566617059`, zip
`1d610a96…`. It was verified and **must not be tagged**. This candidate merges
both.

---

## What this release is

**Tools 0.3.6 uses Maven libraries 0.3.3.** The library line has not moved.
`VERSION.json` inside the ZIP records both versions and the commit the bundle
was built from.

It is the first bundle with which an adopter can **ship to production without a
Keliver checkout**. Two scaffolders add a production host and signed
publishing to a `keliver-init` app. The relay now **refuses to store a bundle
that a production host would refuse**. It also **no longer writes the private
signing key readable by other local users** (U27).

## Upgrading from 0.3.5 or earlier: your key's permissions

Earlier relays wrote `keys/ed25519.priv` with whatever mode the umask gave:
**0644 under the usual umask**, so any local user could read the key that signs
your production bundles. This relay never changes or rotates an existing key.
It **reports** an identity that is not owner-only at every start, with shell
commands that keep the identity, and **`/publish` refuses** until you run them.
Editing keeps working. If someone else may have read the key, rotating it is
your decision. A host embeds the public key, so rotating means rebuilding your
production host.

## New: `bin/keliver-new-production-host.sh`

```bash
bin/keliver-new-production-host.sh --bundle-server URL [--api-base-url URL] \
    [--application-id ID] [--public-key-file PATH]
./gradlew -p host-android assembleDebug
```

This writes `host-android/`: your app's own production Android host, a
standalone Gradle build that resolves Keliver only from Maven Central. The
template is in the bundle's `templates/production-host/`.

- **Production only.** Every manifest must verify against
  `assets/portal_ed25519.pub`, which is copied from your store's **public** key
  and meant to be committed. A missing or malformed key fails the build. On the
  device, the host refuses before any fetch. The private key is never read.
- **Startup.** The host looks up the newest bundle first (5 s to connect, 10 s
  in all):
  - **The lookup answers:** it loads that bundle from the network.
  - **The lookup fails and a bundle loaded before:** it starts from Zipline's
    pinned cache, which is verified again against the key.
  - **Otherwise:** it shows "No bundle".

  It follows manifest URLs only on the bundle server's origin, and it keeps one
  Zipline cache per key.
- **HostHttp** is OkHttp to your API base. It is bound only when one is
  configured, and it cannot escape that base.
- **Cleartext** is debug-only and only for an `http://` server. Release
  variants refuse `http://` URLs at build time.
- **Not protected: rollback.** Any bundle signed with your key is accepted,
  including an older one.

## New: `bin/keliver-new-publish-target.sh`

```bash
bin/keliver-new-device-target.sh      # first, if you have not: the bundle's entry point
bin/keliver-new-publish-target.sh
```

A `keliver-init` app couldn't use `POST /publish`: `publishTask` defaulted to
Keliver's own module, and nothing signed. This command writes
`publishTask`/`publishOutput` (the development Zipline bundle) into
`keliver.portal.json`. It also appends a signing block to `build.gradle`, below
`kotlin {}`, which signs with your store's `keys/ed25519.priv`. Restart the
portal afterwards: the relay reads `keliver.portal.json` at start.

## Fixed: U27 and U29, the signing key's permissions and integrity (#82)

- **A new private key is owner-only from the moment it exists.** It is created
  `O_EXCL` at mode 0600, empty, in a 0700 staging directory. Its mode is read
  back before any key byte is written, and it is then hard-linked into place.
  It is never written and chmodded afterwards. No key is created on a
  filesystem that does not apply modes, or on a macOS volume mounted
  `noowners`.
- **Existing keys are never modified.** See *Upgrading* above. Owner-only
  means: no group or other bits on the key; `keys/` and the public key not
  writable by others; the key and `keys/` owned by the relay's user; the store
  directory not world-writable.
- **U29:** with one key file missing, the relay used to regenerate **both**
  silently, replacing a private key or the public key that hosts embed. It now
  refuses half a pair.
- **`keliver-adopt-legacy-store.sh`** copies the pair owner-only, as a unit.
- **Not gated:** a Gradle build run directly still signs with whatever key is
  there.

## Fixed: #77, the iOS `generatePortalKey` output directory (#83)

A file planted in that generated source directory survived an UP-TO-DATE build
and was compiled into the framework. The task now runs every time and empties
the directory without following links, failing if it cannot. It writes only
`PortalPublicKey.kt`, checks that exactly that file remains, and refuses a store
key that is not 64 hex digits. This was measured on a debug
`iosSimulatorArm64` framework only.

## Changed: the relay refuses unsigned bundles

A build whose signing block was missing, sat above `kotlin {}` (measured: it
compiles unsigned with no error), or couldn't find the store used to give
`publish OK` for a bundle every production host refuses. `POST /publish` now
keeps a bundle only if its manifest carries a `portal-ed25519` signature that
verifies against the store's public key. Otherwise it answers
`publish REFUSED: …`, says what to fix, and stores nothing.

`keliver-portal` passes `KELIVER_TOOLS_BIN` to the relay, so the signing block
finds the store resolver during `/publish` without `KP` exported.

## Verified before this candidate (on its parent commits, not on this build)

| what | where | result |
|---|---|---|
| production host: P1–P7 on an emulator (signed v1 → v2, foreign key refused, no empty-URL load, offline start from the cache) | reference-app `37505534432` | 37/0 |
| the same, with v1/v2 signed by the **scaffolded** signing block | reference-app `37513078292` | 37/0 |
| relay signature check against Zipline's own signer/verifier and real compile-task manifests | portal-tools `37513071473` | 7/7 |
| publish round-trip through the packaged portal: signed v1 stored; unsigned refused, nothing stored | portal-tools `37513071473` | pass |
| scaffolders' self-tests with real builds; from the packaged zip | portal-tools `37513071473` | 39/0, 34/0 (host); 23/0, 18/0 (publish) |
| U27 key permissions, with a second local account attempting the read | portal-tools `36468669380` (#82's final code) | 63/0/0 |
| #77: a planted file is absent from the klib, header and binary | macOS, debug `iosSimulatorArm64` (#83) | measured; see #83 |

## Known issues

- **U30:** Android `syncPortalKey` deletes through symlinks. Recorded, not
  fixed.
- **#77 leftovers:** `compileIosMainKotlinMetadata` compiles the same
  directory without the task (its output isn't used by the framework), and iOS
  has no `devOnlyHost` short-circuit.
- **U27 not covered:** ACLs; directories above the store; modes on network
  filesystems taken as reported.
- No physical device, no arm64, no HTTPS end to end, no release-signed APK.
  Only the development bundle variant is wired. iOS hosts are not scaffolded.
