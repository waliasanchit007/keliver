# keliver-portal-tools 0.3.16 — release notes

**Status: CANDIDATE**, by `PORTAL_TOOLS_RELEASE.md`. Identities are filled in
as each step of the procedure passes.

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

(to be filled in: source commit, zip sha256 and size, `VERSION.json`, APK,
build run and artifact, device job, the packaged checks, the stored asset's
digest, the public download check)

Not verified for this release:
- B2 in a running app or on a phone (no guest uses HostHttp yet);
- M3 with a screen opened after the lookup, M3 on iOS at all, and M3
  together with #176's load-again (the measured host was 0.3.15 plus M3's
  one line);
- #176's final revision on a phone (earlier revisions ran on a Galaxy A12
  and an iPhone 13);
- #180 on a phone (JVM tests and the CI emulator only).
