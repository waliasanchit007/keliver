# keliver-portal-tools 0.3.7 — release notes

**Status: CANDIDATE.** Not tagged, not released. Tagging and publishing wait
for the owner's explicit approval; 0.3.6's approval does not carry over.
Tools 0.3.6 and earlier, and the Maven 0.3.3 libraries, are unchanged.

## Candidate verification (recorded after the build; not in the built commit)

| | |
|---|---|
| source commit to tag | **`2ff3b269c0c503c3ff564434c9c7e538d424a3d2`** (`VERSION.json` `sourceCommit`, `sourceDirtyFiles: 0`; tools 0.3.7, Maven dependency 0.3.3) |
| zip | `keliver-portal-tools-0.3.7.zip`, 90,443,031 bytes; zip sha256 **`222ba0a1acce40f125f30d24355431993d8595fea7c2029a808ff919d5976303`** |
| bundled dev-host APK sha256 | **`0ccf76e8c87b841c3f2cec7426b6a522c7c1217efaff114344988ed88878220c`**, no `assets/portal_ed25519.pub` |
| build run | [`37627385486`](https://github.com/waliasanchit007/keliver/actions/runs/37627385486): every portable check green, including the iOS scaffolder's self-test **42/0 from `scripts/` and 42/0 from the candidate zip** (new), the Android host scaffolder 39/0 and 34/0, the publish scaffolder 23/0 and 18/0, and U27 key permissions 63/0/0. Retained artifact `11485528885` (90,440,611 bytes, `sha256:b1f1e526…`) |
| device run | [`37630451394`](https://github.com/waliasanchit007/keliver/actions/runs/37630451394): the APK pinned by sha256; 19/0 device checks, 28/0 packaged acceptance; API 33 x86_64 emulator. Verifier from `release/portal-tools-0.3.7` |
| iOS, the candidate zip itself | `reference/inventory/ci/ios.sh` with `KELIVER_CANDIDATE_SHA256`, on this Mac (Xcode 26.4.1, iOS 26.4 simulator, iPhone 17 Pro): the app recreated from the candidate zip, the host scaffolded by the **zip's own `bin/keliver-new-ios-host.sh`**, built from Maven Central (54 `dev.keliver` artifacts, all 0.3.3); P1, P2, P4–P7 **30/0**. Evidence: `docs/superpowers/evidence/tools-0.3.7-candidate-ios/` |
| iOS, hosted runner | `ios-host.yml` [`37627628855`](https://github.com/waliasanchit007/keliver/actions/runs/37627628855) on this PR (macos-15): self-test with `xcodebuild` 48/0; P1, P2, P4–P7 30/0, against the **public 0.3.6** zip with the repository's scaffolder at this branch (not the candidate zip: the workflow can't take one before it is on `main`) |
| reference app (Android) | `reference-app.yml` [`37627628756`](https://github.com/waliasanchit007/keliver/actions/runs/37627628756) on this PR: green, 37/0 |
| local check | the retained artifact downloaded on macOS. The zip and APK hashes match both runs, `VERSION.json` is as above, `bin/keliver-new-ios-host.sh` (executable) and `templates/ios-host/` (16 files) are in the bundle, and the guide inside the MCP jar is byte-identical to `docs/PORTAL_ADOPTER_GUIDE.md` at the commit, with the 0.3.7 download block |
| tag push | at `2ff3b269`, only `portal-tools.yml` (read-only) matches `portal-tools-v*`. `publish.yml` (`packages: write`) is `v*` only; `ci.yml` ignores tags; `ios-host`, `reference-app`, `pages`, `compat-matrix` and `publish-maven-central` don't trigger on tags. No workflow at the commit can create a release or attach an asset |

**Not done: tag, release, upload.** Those wait for the owner's approval.

---

## What this release is

**Tools 0.3.7 uses Maven libraries 0.3.3.** The library line has not moved.
`VERSION.json` inside the ZIP records both versions and the commit the bundle
was built from.

It adds the **iOS production host**. With 0.3.6 an adopter could ship an
Android production host without a Keliver checkout; with 0.3.7 the same goes for
iOS. Nothing else in the bundle changes behaviour: the relay, editor, MCP
server, the other scaffolders and the generic Android development host are
built from the same sources as 0.3.6. Only the documentation that ships in the
bundle changed with them.

## New: `bin/keliver-new-ios-host.sh`

```bash
bin/keliver-new-ios-host.sh --bundle-server URL [--api-base-url URL] \
    [--bundle-id ID] [--public-key-file PATH]
xcodebuild -project host-ios/iosApp.xcodeproj -scheme iosApp -sdk iphonesimulator \
  -configuration Debug CODE_SIGNING_ALLOWED=NO build
```

This writes `host-ios/`: your app's own production iOS host. It is a Kotlin
framework (`KeliverHost`), a standalone Gradle build that resolves Keliver only
from Maven Central, plus an Xcode app whose build phase builds and embeds the
framework. The template is in the bundle's `templates/ios-host/`. Scaffolding
and building need macOS with Xcode.

- **Production only.** Every manifest must verify against the public key in
  `HostConfig.kt`, copied from your store's **public** key and meant to be
  committed. The scaffolder refuses a `.priv` file, a file that is not a key,
  and, when your store resolves, any key that is not its `ed25519.pub`. A
  malformed key fails the build. Without a valid key the host fetches nothing
  and shows a refusal; there is no unverified fallback.
- **Startup**, as on Android: the lookup first (10 s); when it fails and a
  bundle loaded before, Zipline's pinned cache, verified again against the key;
  otherwise "No bundle". One Zipline cache per key. Manifest URLs are followed
  only on the bundle server's origin.
- **`HostHttp`** over `NSURLSession` to your API base only, when one is set.
  Paths can't climb out of it; a method that isn't a plain token or a header
  holding CR or LF is refused; redirects aren't followed.
- **Guest SQL** is real SQLite in Application Support (a `sqlite3` cinterop).
  **Images** load over the network (Coil with Ktor's Darwin engine).
- **App Transport Security:** a development build gets an exception only for
  the `http://` hosts you scaffolded with. A release framework link refuses
  `http://` servers and any ATS exception left in `Info.plist`.
- **Not protected: rollback.** Any bundle signed with your key is accepted,
  including an older one. Same as Android.

`DEVICE_HOST.md` §3 (`host/README.md` in the bundle) has the details.

## Changed: the candidate build checks the packaged iOS scaffolder

`portal-tools.yml` now runs the iOS scaffolder's self-test from `scripts/` and
from the candidate zip's `bin/`, so a packaging error (templates not at
`bin/../templates`) fails the candidate. On Linux this covers the refusals and
the completeness of `host-ios/`; the `xcodebuild` half runs on macOS in
`ios-host.yml`.

## Verified before this candidate (on its parent commits, not on this build)

| what | where | result |
|---|---|---|
| iOS scaffolder self-test, with `xcodebuild` for the simulator from Maven Central; a malformed key fails the build; a release refuses `http://` | `ios-host.yml` `37602257459` (macos-15, Xcode 16.4) | 48/0 |
| iOS production OTA on a simulator, host scaffolded by this script, app recreated from the public 0.3.6 release: signed v1, v2 after an edit, a foreign key refused, recovery, offline start from the cache, no empty-URL load (P1, P2, P4–P7; screens read by macOS Vision) | `ios-host.yml` `37602257459` (iOS 26.2 simulator) | 30/0 |
| the same, before the independent review's fixes | `ios-host.yml` `37597390396` | 48/0, 30/0 |
| the host's SQLite driver: create/insert/select, update and delete counts, a failing batch rolled back, data surviving a reopen, bad SQL an error rather than a crash | macOS Kotlin/Native harness (`docs/superpowers/evidence/ios-host-i1/sqltest/`) | 5/5 |

## Known issues

- **Not measured on iOS:** a physical iPhone; a release or App Store build;
  HTTPS end to end; taps (P3); `HostHttp` and network images in a running app
  (the reference guest uses neither); the SQLite driver inside the iOS app.
- **Rollback is not protected** on either platform.
- **U30:** Android `syncPortalKey` deletes through symlinks. Recorded, not
  fixed.
- The in-repo iOS spike (`portal-device-ios`) still falls back to
  `NO_SIGNATURE_CHECKS` in prod mode without a key. It is built only from
  Keliver source and is **not** in the bundle; adopters get the scaffolded host.
- No physical Android device, no arm64, no release-signed APK. Only the
  development bundle variant is wired for publishing.
