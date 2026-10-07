# keliver-portal-tools 0.3.7 — release notes

**Status: CANDIDATE.** Not tagged, not released. Tagging and publishing wait
for the owner's explicit approval; 0.3.6's approval does not carry over.
Tools 0.3.6 and earlier, and the Maven 0.3.3 libraries, are unchanged.

## Candidate verification (recorded after the build; not in the built commit)

### Candidate 2 — the one to tag

| | |
|---|---|
| source commit to tag | **`ce9a966042dbcdefad7b2dc06e3200f3bf418ea9`** (`VERSION.json` `sourceCommit`, `sourceDirtyFiles: 0`; tools 0.3.7, Maven dependency 0.3.3) |
| zip | `keliver-portal-tools-0.3.7.zip`, 90,443,206 bytes; zip sha256 **`0808e14464712a88dff9d66d0cd3a789bd5a4cbf564b9f08f9471cd993d77abe`** |
| bundled dev-host APK sha256 | **`d2b5b0bfab05ec5f36d3382c75bcf1f07091bff9abeb49dc8f1b24363f0a3744`**, no `assets/portal_ed25519.pub`; debug certificate `E9:33:CC:FD…` |
| build run | [`37632305427`](https://github.com/waliasanchit007/keliver/actions/runs/37632305427): every portable check green, including the iOS scaffolder's self-test **42/0 from `scripts/` and 42/0 from the candidate zip**, the Android host scaffolder 39/0 and 34/0, the publish scaffolder 23/0 and 18/0, and U27 key permissions 63/0/0. Retained artifact `11489395242` (90,440,795 bytes, `sha256:8df113a9…`) |
| device run | [`37635257054`](https://github.com/waliasanchit007/keliver/actions/runs/37635257054), attempt 2: the APK pinned by sha256; 19/0 device checks, 28/0 packaged acceptance; API 33 x86_64 emulator. Verifier from `release/portal-tools-0.3.7` at `ce9a9660`. Attempt 1 ran no check: the runner's emulator package download failed (`Error on ZipFile unknown archive`, then no emulator on port 5554) |
| iOS, the candidate zip itself | `reference/inventory/ci/ios.sh` with `KELIVER_CANDIDATE_SHA256=0808e144…`, on this Mac (Xcode 26.4.1, iOS 26.4 simulator, iPhone 17 Pro): the app recreated from the candidate zip; the host scaffolded by the **zip's own `bin/keliver-new-ios-host.sh`** (candidate mode now refuses to fall back to `scripts/`) and built from Maven Central (54 `dev.keliver` artifacts, all 0.3.3); P1, P2, P4–P7 **30/0**. Evidence: `docs/superpowers/evidence/tools-0.3.7-candidate-ios/candidate-2/` |
| iOS, hosted runner | `ios-host.yml` [`37632312323`](https://github.com/waliasanchit007/keliver/actions/runs/37632312323) at `ce9a9660` (macos-15): self-test with `xcodebuild` 48/0; P1, P2, P4–P7 30/0. It ran against the public 0.3.6 zip with the repository's scaffolder, because the workflow can't take a candidate zip before it is on `main`. It shows that `ios.sh`'s default path still works after the candidate-mode change |
| reference app (Android) | `reference-app.yml` [`37632312432`](https://github.com/waliasanchit007/keliver/actions/runs/37632312432) at `ce9a9660`: 37/0 |
| local check | the retained artifact downloaded on macOS. The zip and APK hashes match both runs, and `VERSION.json` is as above. `bin/keliver-new-ios-host.sh` (755) and `templates/ios-host/` (16 files) are in the bundle. The guide inside the MCP jar, `host/README.md` and `README.md` are byte-identical to `docs/PORTAL_ADOPTER_GUIDE.md`, `docs/DEVICE_HOST.md` and `docs/PORTAL_TOOLS_README.md` at the commit. There are no `.priv`, `.pem`, `.jks` or keystore files |
| tag push | at `ce9a9660`, only `portal-tools.yml` (read-only) matches `portal-tools-v*`. `publish.yml` (`packages: write`) is `v*` only; `pages.yml` (`id-token: write`) runs only on a push to `main`; `ci.yml` ignores tags; `ios-host`, `reference-app`, `compat-matrix` and `publish-maven-central` don't trigger on tags. The workflows' triggers are unchanged since candidate 1 (one comment in `reference-app.yml`) |

### Superseded: candidate 1 (`2ff3b269`) — must NOT be tagged

Candidate 1 was verified in full, below. The independent review then found
errors in the documentation it ships:
- "scaffolding needs macOS"; it needs only bash and python3;
- the tools README's stale "Doesn't (yet)" list;
- `DEVICE_HOST.md`'s Android-only introduction and its gaps list.

Candidate 2 fixes them; the artifact's code is unchanged. Candidate 1's zip
`222ba0a1…` must not be published.

`ios-host.yml` [`37627628855`](https://github.com/waliasanchit007/keliver/actions/runs/37627628855)
below ran the public 0.3.6 zip with the repository's scaffolder. The workflow
can't take a candidate zip before it is on `main`.

| | |
|---|---|
| source commit (NOT to be tagged) | **`2ff3b269c0c503c3ff564434c9c7e538d424a3d2`** (`VERSION.json` `sourceCommit`, `sourceDirtyFiles: 0`; tools 0.3.7, Maven dependency 0.3.3) |
| zip | `keliver-portal-tools-0.3.7.zip`, 90,443,031 bytes; zip sha256 **`222ba0a1acce40f125f30d24355431993d8595fea7c2029a808ff919d5976303`** |
| bundled dev-host APK sha256 | **`0ccf76e8c87b841c3f2cec7426b6a522c7c1217efaff114344988ed88878220c`**, no `assets/portal_ed25519.pub` |
| build run | [`37627385486`](https://github.com/waliasanchit007/keliver/actions/runs/37627385486): every portable check green, including the iOS scaffolder's self-test **42/0 from `scripts/` and 42/0 from the candidate zip** (new), the Android host scaffolder 39/0 and 34/0, the publish scaffolder 23/0 and 18/0, and U27 key permissions 63/0/0. Retained artifact `11485528885` (90,440,611 bytes, `sha256:b1f1e526…`) |
| device run | [`37630451394`](https://github.com/waliasanchit007/keliver/actions/runs/37630451394): the APK pinned by sha256; 19/0 device checks, 28/0 packaged acceptance; API 33 x86_64 emulator. Verifier from `release/portal-tools-0.3.7` at `d29da11d` |
| iOS, the candidate zip itself | `reference/inventory/ci/ios.sh` with `KELIVER_CANDIDATE_SHA256`, on this Mac (Xcode 26.4.1, iOS 26.4 simulator, iPhone 17 Pro): the app recreated from the candidate zip, the host scaffolded by the **zip's own `bin/keliver-new-ios-host.sh`**, built from Maven Central (54 `dev.keliver` artifacts, all 0.3.3); P1, P2, P4–P7 **30/0**. Evidence: `docs/superpowers/evidence/tools-0.3.7-candidate-ios/` |
| iOS, hosted runner | `ios-host.yml` [`37627628855`](https://github.com/waliasanchit007/keliver/actions/runs/37627628855) (macos-15): self-test with `xcodebuild` 48/0; P1, P2, P4–P7 30/0, against the public 0.3.6 zip |
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
built from the same sources as 0.3.6. Compared entry by entry with the
published 0.3.6 zip, candidate 1 differed only in:
- the new iOS scaffolder and its templates;
- the documentation that ships in the bundle (`README.md`, `host/README.md`,
  the guide inside the MCP jar);
- `VERSION`/`VERSION.json`;
- the dev-host APK's debug signing certificate (see *Known issues*).

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
needs only bash and python3; building and running need macOS with Xcode.

- **Production only.** Every manifest must verify against the public key in
  `HostConfig.kt`, copied from your store's **public** key and meant to be
  committed. The scaffolder refuses a `.priv` file, a file that is not a key,
  and, when your store resolves, any key that is not its `ed25519.pub`. A
  malformed key fails the build. Without a valid key the host fetches nothing
  and shows a refusal; there is no unverified fallback.
- **Startup**, as on Android: the lookup first (10 s); when it fails and a
  bundle loaded before, Zipline's pinned cache, verified again against the key;
  otherwise "No bundle". One Zipline cache per key. Manifest URLs are followed
  only on the bundle server's origin. Zipline's own downloads follow HTTP
  redirects, as on Android.
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
| the same, before the independent review's fixes | `ios-host.yml` `37597390396` | 43/0, 30/0 |
| the host's SQLite driver: create/insert/select, update and delete counts, a failing batch rolled back, data surviving a reopen, bad SQL an error rather than a crash, empty SQL giving no rows. Run on the template's `IosSqlHost.kt` at the candidate, which includes the review's empty-SQL fix | macOS Kotlin/Native harness (`docs/superpowers/evidence/tools-0.3.7-sqltest/`) | 6/6 |

## Known issues

- **U31, found after candidate 2 was verified: the publish signing block
  exposes the private signing key.** It was first shipped in 0.3.6, and this
  bundle's `keliver-new-publish-target.sh` writes it unchanged. The block gives
  the key to Zipline's compile task, which passes it to a child JVM as
  `--sign …:<private key hex>`. The key is then:
  - visible in the process list while a bundle compiles;
  - printed in full by a `--info` build (measured);
  - stored in `.gradle/<version>/executionHistory/executionHistory.bin`,
    mode 0644 (measured).

  A fix that signs inside Gradle, after the compile, is on branch
  `fix/u31-signing-key-exposure`, stacked on this one. **Candidate 2 does not
  include it.**
- **Not measured on iOS:** a physical iPhone; a release or App Store build;
  HTTPS end to end; taps (P3); `HostHttp` and network images in a running app
  (the reference guest uses neither); the SQLite driver inside the iOS app.
- **Rollback is not protected** on either platform.
- **U30:** Android `syncPortalKey` deletes through symlinks. Recorded, not
  fixed.
- **The dev-host APK's signing certificate changes between releases.** It is
  signed with a debug key the CI runner creates for each build: 0.3.6's
  certificate is `18:31:88:A3…`, candidate 1's `2C:C9:53:49…`, candidate 2's
  `E9:33:CC:FD…`. `keliver-install-device-host.sh`
  runs `adb install -r`, which Android refuses
  (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`) over an install signed with another
  key. Uninstall `dev.keliver.portaldevice` first. This follows from the
  certificates and Android's rules; it was not run.
- **U27 not covered** (unchanged from 0.3.6; the relay is the same): ACLs;
  directories above the store; modes on network filesystems taken as reported.
- **#77 leftovers** (unchanged): `compileIosMainKotlinMetadata` compiles the
  generated-key directory without the task, and iOS has no `devOnlyHost`
  short-circuit. These concern Keliver's own Gradle plugin path, not the
  scaffolded host.
- The in-repo iOS spike (`portal-device-ios`) still falls back to
  `NO_SIGNATURE_CHECKS` in prod mode without a key. It is built only from
  Keliver source and is **not** in the bundle; adopters get the scaffolded host.
- No physical Android device, no arm64, no release-signed APK. Only the
  development bundle variant is wired for publishing.
