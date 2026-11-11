# Debug HTTP replay on a device (item 5, S2)

[`ADOPTION_PLAN_2026-10.md`](ADOPTION_PLAN_2026-10.md) item 5 asks for recorded data a device
can use with nothing recorded on the device: a debug host embeds the reviewed fixture sets and
replays them in-process, matching exactly as the relay's preview replay does.

**Status (2026-11-11).** The library half is `keliver-http-replay-debug`, unreleased (0.5.0).
The host templates build against Maven Central's 0.4.1, so they cannot use it until 0.5.0 is
published; the two sections below on wiring and on the release-build self-test are the design
the templates follow then. Nothing in `scripts/templates` uses it yet.

## The library

`keliver-http-replay-debug` (JVM, so Android, plus iOS and JS) depends on `keliver-http` (the
`HostHttpProvider` service) and `keliver-http-fixtures` (the matcher the relay uses since #224).
It is a module of its own because `HostHttpProvider` is a Zipline service and Zipline has no
wasm target, while `keliver-http-fixtures` stays Zipline-free for the wasm editor.

```kotlin
val replay = DebugHttpReplay(mapOf("review" to reviewJson, "empty" to emptyJson))
val http: DebugHttpReplayProvider = replay.provider("review") { miss -> log(miss) }
zipline.bind<HostHttpProvider>("HostHttp", http)
// Later, for the next named state through the same binding:
http.startSession("empty")
```

- `DebugHttpReplay(fixtureFiles)` takes each set's file text by id (the file name in the app's
  `httpFixturesDir`, without `.json`) and validates it once, by the relay's rules.
  `catalog()` lists each set: valid or not (and why), entries, expiry.
- `provider(fixtureSet)` is one session with its own sequence cursors. `startSession(set)` begins
  a new session (every sequence from its first entry), optionally on another set.
- It never makes an outbound call. It has no HTTP client, no base URL and no fallback, and no
  networking library is among its dependencies. Its JVM tests check the runtime classpath and
  the module's own bytecode for networking APIs.

### The same answers as the relay

Matching is `HttpFixtureMatcher`'s (normalised method, path, sorted query, the set's
`matchHeaders`, redacted body), and loading follows the relay's `HttpReplayService`. A recorded
response is returned unchanged, including a recorded 4xx or 5xx. Every other answer has the
relay's status and the relay's error JSON (`{"error":"replay_miss","fixtureSet",...,"reason"}`),
and is also passed to `onMiss`:

| Status | When |
|---|---|
| 424 | no matching request, or its sequence is exhausted |
| 400 | an invalid request (blank method, a path that is not relative) or set name |
| 413 | a request body over 1 MiB |
| 404 | no set with that id |
| 422 | the set is invalid: a bad name, over 4 MiB, bad JSON or dates, or any matcher rule |
| 410 | the set has expired (`expiresAt` reached) |

Sequences are the relay's: requests with the same key replay in file order, a final `reuse`
entry repeats, and anything else past the end misses. A miss moves no cursor. Where it differs
from the relay, on purpose:

- A session is a provider object, not a `session` query parameter. The relay's cursor cap and
  30-minute expiry are not needed, because a session holds at most one cursor per distinct
  request in its set.
- A provider answers one request at a time, so two identical requests in flight never get the
  same position. The relay reads a cursor and writes it back in two separate steps.
- The guest gets the miss as a response: a 424 with the error JSON, which `KeliverHttp`'s typed
  calls raise as `KeliverHttpException(424, …)`. The editor's preview client and the portal's
  device hosts turn the relay's non-2xx answer into an exception instead, so code that reads
  the raw status sees 424 here and an exception there.
- The relay's route reads the whole encoded request with a 1 MiB cap first
  (`{"error":"request_too_large"}`) and answers one it cannot decode with
  `{"error":"invalid_request"}`. Here there is no encoding: the 1 MiB cap is on the body, as the
  relay's replay service checks it, and an invalid request gets the `replay_miss` shape with
  status 400.
- Dates parse with `kotlin.time.Instant`, not `java.time.Instant`, so for an invalid
  `recordedAt` or `expiresAt` the 422 reason is worded differently.

## Wiring it into a debug host (design)

The rule from item 5: **release builds contain neither the fixtures nor the provider.** Each
template gets it from the build's structure, not from a runtime flag.

**Android** (`production-host`, `production-host-embed`):

1. `debugImplementation 'dev.keliver:keliver-http-replay-debug:<version>'`. Only the debug
   classpath has it, so code in `src/main` that names it does not compile in release.
2. M0's pattern for debug-only code (`src/timing` and `src/notiming`): a `src/replay/kotlin`
   `KeliverReplay` for the `debug` build type, and a `src/noreplay/kotlin` one that returns
   `null` for every other build type, `benchmark` included (it measures the network path).
3. The data: a Gradle task copies the reviewed sets (the `*.json` files directly in
   `httpFixturesDir` from `keliver.portal.json`, default `portal-fixtures/http`, and never
   `.candidates/`) into a generated assets directory that only the debug variant gets
   (`androidComponents.onVariants(selector().withBuildType('debug'))`, as
   `assets/keliver/http-fixtures/`). `KeliverReplay` reads them with the `AssetManager`.
4. Off unless asked: a launch extra `--es keliver.fixtures <set>` (S3's runner passes one per
   state). Without it, a debug build talks to its API as it does today.
5. In `bindServices`, when `KeliverReplay` returns a provider, bind it as `HostHttp` in place
   of `OkHttpHostHttp`, even with no `keliver.apiBaseUrl`. It sits behind the same
   `RequestPolicy` check (`refusal(method, path)` throwing `HostHttpRefused`), so a call the
   policy refuses is refused in replay too, and a state that passes on replay cannot then fail
   on the policy in production. The origin rule has nothing to check here.
6. `onMiss` logs one `KeliverReplay` logcat line per miss (`424 GET /v1/x: no matching
   request`). S3's runner records misses with the state's marks, and a state with a miss fails.

**iOS** (`ios-host`, `ios-host-embed`):

1. An Xcode build setting, `KELIVER_REPLAY = YES`, set only in the Debug configuration, the way
   `KELIVER_TIMING` is set today.
2. In `build.gradle`, when `KELIVER_REPLAY` is `YES`, `iosMain` gets the dependency and
   `src/replay/kotlin`; otherwise it gets `src/noreplay/kotlin`. A `linkRelease*` task fails
   if `KELIVER_REPLAY` is `YES`, the way `checkReleaseUrls` guards release links.
3. The data: a Run Script phase copies the same `*.json` files into the app bundle's
   `keliver-http-fixtures/`, only when `KELIVER_REPLAY` is `YES`. `KeliverReplay` reads them
   through `NSBundle.mainBundle`.
4. Off unless asked: a launch environment variable `KELIVER_FIXTURES=<set>`
   (`SIMCTL_CHILD_KELIVER_FIXTURES` with `simctl launch`, or `devicectl`'s launch environment;
   S4).
5. Bound where `MainViewController.kt` binds `NSURLSessionHostHttp`, behind the same
   `RequestPolicy` check.

## The release-build self-test (design)

Each template's self-test (`keliver-new-production-host-selftest.sh --build`, which already
builds debug and release, and the iOS host's) scaffolds a host with one extra fixture set
holding a canary string, builds it, and checks each item below. It checks the debug build as a **positive control**, so a check that cannot see the
provider cannot pass by seeing nothing.

| Check | Release (and Android's `benchmark`) | Debug |
|---|---|---|
| Android: `dev.keliver:keliver-http-replay-debug` in the resolved runtime classpath (`releaseRuntimeClasspath` against `debugRuntimeClasspath`) | absent | present |
| Android: `assets/keliver/http-fixtures/` in the APK | absent | present |
| Android: a class under `dev/keliver/http/replay/debug/` in the APK's dex | absent | present |
| iOS: the artifact among the framework link task's libraries | absent | present |
| iOS: `keliver-http-fixtures/` in the `.app` | absent | present |
| iOS: `dev.keliver.http.replay.debug` in the app binary (`strings`) | absent | present |
| Both: the canary anywhere in the APK (every entry, decompressed) or the `.app` | absent | present |

The dex check holds under R8: the artifact's jar carries
`META-INF/proguard/keliver-http-replay-debug.pro` with a `-keepnames` rule for its package.
That rule keeps the names of classes that stay in the build, and keeps no class that nothing
uses. Android's `benchmark` build type is minified with release settings, so it covers that
case. On iOS, finding the package's name in the binary relies on Kotlin/Native keeping class
names for reflection. That is not verified yet: if the Debug control does not find it, the
check falls back to the link libraries row.
