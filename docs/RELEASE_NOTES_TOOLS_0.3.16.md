# keliver-portal-tools 0.3.16 — release notes

**Status: RELEASED** 2026-10-31T11:24:06Z, by `PORTAL_TOOLS_RELEASE.md`. Tag
`portal-tools-v0.3.16` → `65346c63dc4f28bf5f20daf4f6da1ea6ce9c0877`; asset
`keliver-portal-tools-0.3.16.zip`, sha256
`e120ea7aed62960e1d65da2a0dc31a8361b6f805b14101cc850c9795bf8c8699`
(92,110,030 bytes), the retained, verified CI artifact.

What is in it since 0.3.15 (`3dc039f5e`): PRs #176 (a failed first load loads
again), #178 (B2: guests' HostHttp on iOS with your app's credentials), #179
(M3: the code load starts at host start) and #180 (TRACE, TRACK and CONNECT
never through your app's own client on Android). #171–#175, #177 and #181
move the reference app, its harness and the records, and #170 records
the 0.3.15 release.

**The libraries stay 0.4.1.** Nothing published to Maven Central changed
since 0.4.1. Every change here is in the scaffolders, their templates and
the docs; the relay is unchanged. New apps get `dev.keliver:*:0.4.1`, as
with 0.3.15. A host scaffolded earlier gets none of it until it is
scaffolded again.

## New: guests' HostHttp on iOS with your app's credentials (B2, #178)

`Keliver.shared.requestHeaders = { method, path, done in … }`, set before
the host starts, adds your app's credentials to guests' HostHttp requests:
- It is called on the main thread for each request the `RequestPolicy`
  allowed, with the method and the path relative to the API base. The
  headers given to `done` replace the guest's of the same name.
- `done(nil)` refuses the request, and so do no answer within 30 s (a busy
  main thread included), a throw and an invalid header. A refused request
  sends nothing.
- It needs a `requestPolicy`; without one every request is refused.
- With it, the guest's own `Authorization`, `Proxy-Authorization` and
  `Cookie` are dropped, a response's `Set-Cookie` is not handed back, the
  session keeps no cookies, cache or credentials, and `TRACE`, `TRACK` and
  `CONNECT` are refused.
- Bundles, the update stream and reports never get these headers.

On every iOS host, the path-override headers (`X-Original-URL` and kin) are
now dropped too, as on Android since 0.3.15. The host's rules run as 20
simulator tests against a local server: `./gradlew -p host-ios
iosSimulatorArm64Test` (`-PkeliverTestSimulator=<name or UDID>` when
Kotlin's default simulator is not installed).

## New: a failed first load loads again without a restart (#176)

When a host's first code load fails (the lookup worked, the download or the
verification did not, and there is no last good bundle), its next update
check now looks the bundle up and loads it again in the same process. An
update check is a live event, a return to the foreground at least 30 s
after the last lookup (on-resume or live updates), or `checkForUpdate()`.
It is not fetched again only when the index still names the same manifest
and sha256 and that load failed the rollback floor or its signature. Before,
only a process restart recovered. A host with next-launch updates and no
`checkForUpdate()` call still needs a restart.

## Changed: the code load starts as soon as the lookup answers (M3, #179)

`start()` now starts the code load as soon as the bundle lookup answers,
not when the first screen asks for it. An app that calls `start()` at
launch therefore does the whole load at launch, whether or not a Keliver
screen opens: the download, the code's memory, the `loaded` report, and
update checks on return to the foreground. `DEVICE_HOST.md` §4 has the
list. An app whose Keliver screens are rarely used can leave `start()` to
the first screen.

Measured on a Galaxy A12 with the bundles over wireless `adb reverse`
(`reference/inventory/m2/results-2026-10-31-a12-m3`): in this setup, no
change for a screen opened at launch, because the lookup answered after the
screen opened in every run. The case M3 is for, a first screen opened
after the lookup answered, is not measured.

## Changed: TRACE, TRACK and CONNECT never through your app's own client (Android, #180)

With `KeliverConfig.httpClient` (0.3.15), these methods are refused even when
the `RequestPolicy` allows them: a `TRACE` would echo your interceptor's
headers back to the guest. The host's plain client is unchanged.

## Verification

All of it against one set of bytes: the retained artifact of build run
`41193510584`, which was then attached to the release.

| | |
|---|---|
| Source commit | `65346c63dc4f28bf5f20daf4f6da1ea6ce9c0877` (PR #182's head at the build; `sourceDirtyFiles` 0) |
| Zip | `keliver-portal-tools-0.3.16.zip`, sha256 `e120ea7aed62960e1d65da2a0dc31a8361b6f805b14101cc850c9795bf8c8699`, 92,110,030 bytes |
| `VERSION.json` | tools 0.3.16, Maven dependency 0.4.1 |
| Bundled host APK | sha256 `57a2cb8fd96322e0c47d73b5ce2bc9b7dda4b5579bfffa82eb4739d835a93e76`, no `assets/portal_ed25519.pub` |
| Dependency floor | `templates/production-host-embed/dependency-floor.json`: keliver 0.4.1, Kotlin 2.2.0, Zipline 1.22.0, Compose 1.8.2, 201 modules resolved at build time |
| Build | `portal-tools.yml` run `41193510584`; retained artifact `13040705437` (92,093,096 bytes, digest `sha256:24f695fa01d1ec6e60e983c5d958061bf32f5a2346978129d1f454383557cba6`) |
| Device job | run `41195531997` (an emulator on GitHub-hosted Ubuntu; the verifier from the candidate's own branch): the APK's sha256 checked against the pin; device verify 28/0, its acceptance 19/0; evidence artifact `13040366518` |

The build run's packaged checks:
- the unit tests (a Gradle run: `BUILD SUCCESSFUL`);
- the disposable-parent refusal 134/0;
- the store resolver 32/0 and 73/0;
- packaging hygiene (U22) 10/0;
- the **packaged adopter acceptance**, with the U21 identity gate, 25/0;
- the foreign-relay refusal 6/0;
- the store identity contract 11/0;
- the production-host scaffolder 80/0 (and 71/0 from the package);
- the iOS host scaffolder 72/0 (and 72/0);
- the publish scaffolder 51/0 (and 33/0);
- the screen and component scaffolders 9/0;
- `keliver-run-android` from the package 22/0;
- the embed dependency floor from the package 11/0;
- `keliver-publish` 41/0;
- key permissions 63/0;
- guest bundle signing 4/0;
- store recovery 144/0.

The artifact was downloaded and checked on the releasing machine before the
tag (through `gh api …/actions/artifacts/13040705437/zip`): the artifact's
sha256 equals GitHub's digest, and the zip, `VERSION.json`, APK and floor are
as above. Step 3 at `65346c63d`: no workflow in the tagged tree can create a
release or attach an asset (`publish.yml` takes `v*` only and is the only one
that can write packages or contents; `pages.yml`, with `pages: write`, runs
on `main` only; `portal-tools.yml`
is read-only). PR #182's CI on the same commit (run `41193419978`): Build,
both editor E2E suites, embed-floor and production-host-build green.

**How the stored asset was checked.** The draft's assets matched the
verified files by GitHub's server-side record (`digest`, `size`): the zip
`sha256:e120ea7a…`, 92,110,030 bytes, and the `.sha256` file
`sha256:c8a047f9…`, 98 bytes, both `uploaded`. That is GitHub's own record
of the stored bytes, not a download: this time the network blocked asset
downloads (`gh api …/releases/assets/695180562` and the public URL both
answered 502, "Bad Gateway", 879 bytes), so the 0.3.5 route was used. The tag
(annotated, object `0c3b9e945`; its message lost the line naming #176 and
#180, because git drops message lines that start with `#`, and a released
tag is never moved) was re-checked at `65346c63d` before publishing, and its push ran only `portal-tools.yml`'s read-only rebuild
(`41196283640`, never attached). Published 2026-10-31T11:24:06Z with
`--latest=false` (the "Latest" badge stays on `v0.3.3`). After publishing,
the public download was checked on GitHub-hosted runners by the reference
app's move to 0.3.16 (PR #183 at `1d860f2de`): the first step of
reference-app run `41196932565` (job `124524886641`, ubuntu-latest) and of
ios-host run `41196932589` (job `124524886541`, macos-15) downloaded the
public zip and checked it against the release's `.sha256`, the pinned hash
`e120ea7a…` and `VERSION.json`'s source commit `65346c63d`; both passed.

Not verified for this release:
- B2 in a running app or on a phone (no guest uses HostHttp yet);
- M3 with a screen opened after the lookup, M3 on iOS at all, and M3
  together with #176's load-again (the measured host was 0.3.15 plus M3's
  one line);
- #176's final revision on a phone (earlier revisions ran on a Galaxy A12
  and an iPhone 13);
- #180 on a phone: JVM tests only (the CI emulator built it but does not
  exercise it).
