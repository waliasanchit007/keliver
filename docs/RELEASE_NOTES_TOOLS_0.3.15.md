# keliver-portal-tools 0.3.15 — release notes

**Status: RELEASED 2026-10-29**, by `PORTAL_TOOLS_RELEASE.md`.

| | |
|---|---|
| tag | `portal-tools-v0.3.15` → `3dc039f5efebfdac912df34e5cbdc8a49311b80b` |
| zip asset | `keliver-portal-tools-0.3.15.zip`, sha256 `68958efa4977dce413c44b87bf8dcbdefc053677cd1afc20ff8ee6638585a287` (92,097,006 bytes): the retained, verified CI artifact |
| `.sha256` asset | the zip's sha256 and file name |

What is in it since 0.3.14 (`58851db26`): PRs #165 (`screen-failed` on the
iOS host), #166 (`keliver-run-android`), #168 (the embed's dependency floor)
and #169 (HostHttp through the app's own client). #163, #164, #165's
reference-app half, #167 and #170 move the reference app and record releases
and evidence.

**The libraries stay 0.4.1.** Nothing published to Maven Central changed
since 0.4.1. Every change here is in the packaged scripts (the scaffolders,
their templates, the new `keliver-run-android.sh`), the packaging (the tools
build now resolves the embed's dependency floor, so it needs network access
and an Android SDK), CI and the docs; the relay is unchanged. New apps get
`dev.keliver:*:0.4.1`, as with 0.3.14.

**An existing host gets none of this until it is scaffolded again.** The host
is your code once written; the scaffolder refuses to write over it. For an
`--embed` module, scaffold into another `--module` (or `--into`); for the
standalone `host-android/`, move it aside first. Then carry your edits over,
as for 0.3.14.

## New: your screens on a phone or emulator in one command (#166)

`bin/keliver-run-android.sh [--serial S] [app-dir]`, run from a
`keliver-init` app with its portal running and a bundle published:
1. it checks the portal and that a bundle is published;
2. it picks the device;
3. it scaffolds `host-android/` if it is missing;
4. it builds a debug host for `http://localhost:<port>` (a `-P` property; no
   file changes);
5. it runs `adb reverse`, installs the host and starts it;
6. it waits for the bundle to load from the network.

A failure says which step failed: no device, a build error, a load failure,
or a load from the cache. On the CI emulator it went from no host installed
to the newest bundle in 32 s, with Gradle already warm from earlier builds in
the same job.

## New: the dependency floor, before `--embed` writes (#168)

`keliver-new-production-host.sh --embed` first prints the module's
dependency floor. That is the versions of Kotlin, coroutines,
kotlinx-serialization, Compose, activity-compose, AndroidX core, lifecycle
and browser, OkHttp, Okio, Coil and Zipline that the module resolves to.
Each one is printed beside the version the existing app declares (direct
coordinates, version catalogs, simple version variables, the Kotlin plugin
and BOMs). Each row gets a verdict:
- **RAISED:** the app declares an older version, so Gradle will use the
  floor instead. A new major version is called out.
- **PINNED below the floor:** a strict or forced version, so Gradle fails or
  keeps a version the module was not built with.
- **will not compile:** the app's Kotlin plugin is older than the module's
  Kotlin.
- **check this BOM:** the app declares a Compose BOM.

The floor is resolved from the template when the tools are built and ships
as `templates/production-host-embed/dependency-floor.json`, with the full
resolved list. A build file the report cannot read is skipped and named.
If the report itself breaks, it warns and the module is still written. A
floor file it cannot read refuses the scaffold, and nothing is written.

## New: guests' HostHttp through your app's own client (Android, #169)

`KeliverConfig.fromBuild(context, requestPolicy, httpClient)` passes your
app's authenticated `OkHttpClient`, with its auth interceptor and pinning,
to guests' HostHttp requests.
- It needs a `RequestPolicy`; a config without one is refused.
- It is used only for HostHttp: bundles, the update stream and reports keep
  the host's own client.
- The policy is checked before your interceptors see a request.
- A guest's `Authorization`, `Proxy-Authorization` and `Cookie` headers are
  dropped, and a response's `Set-Cookie` is not handed back.
- Known gap: an `Authenticator` retry to another path that your client's
  cache answers is not caught (the check below runs on the network attempt).

iOS (the header callback, B2) is not in this release.

## New: `screen-failed` on the iOS host (#165)

When the code throws while a screen shows it, the iOS host reports
`screen-failed` and calls that screen's `onUnavailable`, as the Android host
has done since 0.3.14.

## Changed, for every Android host (with or without your client)

- The URL that goes out is checked again: as the last of the client's own
  interceptors (before its cache) and on each network attempt. A request an
  interceptor sends to another scheme, host, port or path is refused. Your
  interceptors must change a request with `newBuilder()`; one built from
  scratch, or a deliberate path rewrite (a `/v2` prefix), is refused.
- The path-override headers (`X-Original-URL`, `X-Rewrite-URL`,
  `X-Forwarded-Host`, `X-Forwarded-Prefix`, `X-Forwarded-Proto`) are dropped
  from a guest's request, as the method-override headers already were.

## Changed, in CI

- The scaffolded production host's build and its JVM tests now run on every
  pull request that changes the host (`production-host-build`), and the
  dependency-floor check on every pull request (`embed-floor`).

## Verification

All of it against one set of bytes: the retained artifact of build run
`40871650582`, which was then attached to the release.

| | |
|---|---|
| Source commit | `3dc039f5efebfdac912df34e5cbdc8a49311b80b` (PR #170's head at the build; `sourceDirtyFiles` 0) |
| Zip | `keliver-portal-tools-0.3.15.zip`, sha256 `68958efa4977dce413c44b87bf8dcbdefc053677cd1afc20ff8ee6638585a287`, 92,097,006 bytes |
| `VERSION.json` | tools 0.3.15, Maven dependency 0.4.1 |
| Bundled host APK | sha256 `603ed1a1ba9656f695a25ec265f438086f4c09b06ae616849afee761af3e2173`, no `assets/portal_ed25519.pub` |
| Dependency floor | `templates/production-host-embed/dependency-floor.json`: keliver 0.4.1, Kotlin 2.2.0, Zipline 1.22.0, Compose 1.8.2, 201 modules resolved at build time |
| Build | `portal-tools.yml` run `40871650582`; retained artifact `12897894342` (92,080,667 bytes, digest `sha256:64d25e9de4a1de122a4988e1b8ee0f7f5861e64ba394d741ab6626c610146226`) |
| Device job | run `40904089864` (an emulator on GitHub-hosted Ubuntu; the verifier from the candidate's own branch): the APK's sha256 checked against the pin; device verify 19/0, its acceptance 28/0; evidence artifact `12910832280` |

The build run's packaged checks:
- the unit tests (a Gradle run: `BUILD SUCCESSFUL`);
- the disposable-parent refusal 134/0;
- the store resolver 32/0 and 73/0;
- packaging hygiene (U22) 10/0;
- the **packaged adopter acceptance**, with the U21 identity gate, 25/0;
- the foreign-relay refusal 6/0;
- the store identity contract 11/0;
- the production-host scaffolder 79/0 (and 70/0 from the package);
- the iOS host scaffolder 70/0 (and 70/0);
- the publish scaffolder 51/0 (and 33/0);
- the screen and component scaffolders 9/0;
- `keliver-run-android` from the package 22/0;
- the **embed dependency floor from the package 11/0**: the zip's own floor and scaffolder, and an app on older libraries built and resolved against it;
- `keliver-publish` 41/0;
- key permissions 63/0;
- guest bundle signing 4/0;
- store recovery 145/0.

The artifact was downloaded and checked on the releasing machine before the
tag (through `gh api …/actions/artifacts/12897894342/zip`): the artifact's
sha256 equals GitHub's digest, and the zip, `VERSION.json`, APK and floor are
as above. Step 3 at `3dc039f5e`: no workflow in the tagged tree can create a
release or attach an asset (`publish.yml` takes `v*` only; `portal-tools.yml`
is read-only). PR #170's CI on the same commit (run `40871660786`): Build,
both editor E2E suites, embed-floor and production-host-build green.

**How the stored asset was checked.** The draft's assets matched the
verified files by GitHub's server-side record (`digest`, `size`): the zip
`sha256:68958efa…`, 92,097,006 bytes, and the `.sha256` file
`sha256:96755f2a…`, 98 bytes, both `uploaded`. The tag (annotated, object
`5660281945`) was re-checked at `3dc039f5e`, and its push ran only
`portal-tools.yml`'s read-only rebuild (`40905050619`, never attached). This
time the network let both downloads through. The draft asset downloaded back
(`gh api …/releases/assets/689049110`) hashed `68958efa…`, and after
publishing, the public URL (`curl -L …/releases/download/portal-tools-v0.3.15/…`)
gave the same 92,097,006 bytes and `68958efa…`. Published
2026-10-29T15:49:45Z with `--latest=false` (the "Latest" badge stays on
`v0.3.3`).

Not verified for this release: `keliver-run-android` and live updates on a
physical Android phone or iPhone. On 2026-10-29 neither test phone could
reach the releasing machine's network. The host kit's device proof (K4) has
run on the CI emulator and simulator only.

**After the release (2026-10-29, 20:54–21:00Z), on physical phones, from this
release's zip** (`68958efa…`, hashed again first):
- **Galaxy A12** (Android, wireless adb). Starting from nothing: `keliver-init`,
  the device and publish targets, `keliver-portal`, a publish (19 s), then
  `keliver-run-android.sh --serial <A12>`. That scaffolded, built, installed and
  started the debug host, which loaded sequence 1 from the network, in 38 s with a
  warm Gradle home. A publish then reached the open app about 1 s later.
- **iPhone 13** (on the same Wi-Fi). A debug host from `keliver-new-ios-host.sh`,
  built for the device with the owner's team given only on the build command
  line. The first lookups failed with "The Internet connection appears to be
  offline", probably while iOS asked for local-network access. The
  live-update stream retried and the host loaded the bundle without a restart.
  The next publish was applied as soon as it returned, and on the A12 as well.

K4 on a phone is still not run (the reference harness expects its relay on
port 8077).
