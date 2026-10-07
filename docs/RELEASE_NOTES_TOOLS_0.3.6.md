# keliver-portal-tools 0.3.6 — release notes

**Status: CANDIDATE — not tagged, not published.** This text is written in the
commit that gets built, so it cannot cite that build's verification. The
candidate's identities (zip and APK sha256, build and device runs) are recorded
after verification, outside this commit. Tools 0.3.5 stays the published
release until a person approves the tag and upload.

## Candidate verification (recorded after the build; not in the built commit)

| | |
|---|---|
| source commit to tag | **`e40d20ca27cc8d2a435ad223dba4920a5d843375`** (`VERSION.json` `sourceCommit`, `sourceDirtyFiles: 0`) |
| zip | `keliver-portal-tools-0.3.6.zip`, 90,386,556-byte artifact; zip sha256 **`1d610a962373a1525d7b74c5da68fd175c94a8ece99528565a73bc69db5af8b3`** |
| bundled dev-host APK sha256 | **`b8c2e95736be07dfbc5cab6dce27f5c029234a1b3869044bd6442857ea94b6a3`**, no `assets/portal_ed25519.pub` |
| build run | [`37564871932`](https://github.com/waliasanchit007/keliver/actions/runs/37564871932): every portable check green, including the relay's tests, the acceptance with the publish round-trip, and both scaffolders' self-tests (39/0 and 34/0; 23/0 and 18/0). Retained artifact `11458134325` (`sha256:61bae692…`) |
| device run | [`37566617059`](https://github.com/waliasanchit007/keliver/actions/runs/37566617059): the APK pinned by sha256; 19/0 device checks, 28/0 packaged acceptance; API 33 x86_64 emulator |
| local check | the retained artifact was downloaded on macOS. The zip and APK hashes match both runs, `VERSION.json` is as above, and the four new files are in the bundle |
| tag push | only `portal-tools.yml` (read-only) matches `portal-tools-v*` at that commit. `publish.yml` is `v*`, `ci.yml` ignores tags, and `pages`/`compat-matrix` don't trigger on tags |

**Not done: tag, release, upload.** Those wait for approval.

---

## What this release is

**Tools 0.3.6 uses Maven libraries 0.3.3.** The library line has not moved.
`VERSION.json` inside the ZIP records both versions and the commit the bundle
was built from.

It is the first bundle with which an adopter can **ship to production without a
Keliver checkout**. Two scaffolders add a production host and signed
publishing to a `keliver-init` app. The relay now **refuses to store a bundle
that a production host would refuse**.

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

## Known issues — not fixed in this candidate

- **U27: the relay writes `keys/ed25519.priv` world-readable** (`0644` under a
  default umask). The fix is in PR #82, which is not on this candidate's
  branch. Until it is, keep your store's directory private
  (`chmod 700 ~/.keliver-portal`).
- **#77: iOS `generatePortalKey`** keeps a file planted in its output. The fix
  is in PR #83, also not on this branch.
- **U30:** the Android `syncPortalKey` deletes through symlinks. Recorded, not
  fixed.
- No physical device, no arm64, no HTTPS end to end, no release-signed APK.
  Only the development bundle variant is wired. iOS hosts are not scaffolded.
