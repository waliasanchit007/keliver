# keliver-portal-tools 0.3.15 — release notes

**Status: CANDIDATE**, by `PORTAL_TOOLS_RELEASE.md`. Identities are filled in
as each step of the procedure passes.

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
is your code once written; the scaffolder refuses to write over it. Scaffold
into a new directory and carry your edits over, as for 0.3.14.

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

(to be filled in: source commit, zip sha256 and size, `VERSION.json`, APK,
build run and artifact, device job, the packaged checks, the stored asset's
digest, the public download check)

Not verified for this release: `keliver-run-android` and live updates on a
physical Android phone or iPhone. On 2026-10-29 neither test phone could
reach the releasing machine's network. The
host kit's device proof (K4) has run on the CI emulator and simulator only.
