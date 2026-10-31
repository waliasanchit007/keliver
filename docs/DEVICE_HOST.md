# Running your screens on a device

Keliver guests are not Android or iOS application modules. To see your screens
on a device you need a **host**: a small native app that loads your compiled
guest bundle over HTTP and renders it with native widgets.

On Android you have two options; most adopters want the first while they
develop. §3 is the iOS production host.

## 1. The generic device host (zero Android code) — **development only**

`keliver-portal-tools` ships a prebuilt host APK in `host/`, plus an installer.

```bash
# once per device/emulator
bin/keliver-install-device-host.sh              # picks the only attached device
bin/keliver-install-device-host.sh --serial emulator-5554
```

**This APK is a locally built debug artifact that ships inside the tools
bundle.** It is not published to an app store, a Maven repository or a release
page, and the installer never downloads anything. Its SHA-256 sits beside it in
`host/keliver-device-host-<version>.apk.sha256`, and the installer verifies it.

**It is development-only, and it is built that way deliberately.** It carries
**no portal public key**, so it has no identity to verify a signed bundle
against, and it **refuses production mode**:

```
$ adb shell am start -n dev.keliver.portaldevice/dev.keliver.portaldevice.host.MainActivity --es mode prod
```

shows, on the device, "Production mode refused" and tells you to build your own
host. It does not fall back to loading the bundle unverified — an earlier build
did exactly that, logging a warning and continuing with no signature checks.

The key-free property is enforced by the build, not left to the build machine:
the bundle is compiled with `-Pkeliver.devOnlyHost=true`, and
`scripts/build-portal-tools.sh` refuses to package an APK that contains
`assets/portal_ed25519.pub` (KNOWN_BUGS U22).

Then, from your app:

```bash
bin/keliver-new-device-target.sh                # once, adds the device entry point
./gradlew serveDevelopmentZipline               # serves your bundle on :8080
adb shell am start -n dev.keliver.portaldevice/dev.keliver.portaldevice.host.MainActivity
```

After each Kotlin change: rebuild, then force-stop and restart the host.

```bash
adb shell am force-stop dev.keliver.portaldevice
adb shell am start -n dev.keliver.portaldevice/dev.keliver.portaldevice.host.MainActivity
```

### What the host gives your guest

It binds services your presenters can take by name:

| name | type | use |
|---|---|---|
| `HostHttp` | `HostHttpProvider` | your `KeliverHttp` calls; routed through the relay's `/http-replay` (never to a real API) |
| `HostSqlDriver` | `HostSqlDriver` | SQLite for `portal-sql` |

The replay fixture set is **hardcoded** in this host, not chosen by you: every
`HostHttp` request asks `http://10.0.2.2:8077/http-replay` for project
`default`, fixture set **`field-researcher`**. So the relay answers from
`field-researcher.json` in your `httpFixturesDir` (default
`portal-fixtures/http/`); with no file of that name, every request fails. Name
your fixture file `field-researcher.json`, or scaffold your own host
(`bin/keliver-new-production-host.sh`) when you need another set or a real API.

### Limits

- **Emulator only.** The host reaches your machine at `10.0.2.2`, the emulator's
  alias for the host loopback. A physical device on your LAN cannot use this
  loop without rebuilding the host against your machine's address.
- **Debug artifact.** Unsigned for release, and it loads UNSIGNED development
  bundles from `serveDevelopmentZipline`. It is a development tool, not a
  shipping container for your app.
- **No production mode.** See above: it refuses, by design.

## 2. Your own production Android host

Shipping to real users needs your own host: your application id, your
portal's public key, signature verification always on. Scaffold one into your
app from this bundle — no Keliver checkout:

```bash
bin/keliver-new-production-host.sh --bundle-server https://bundles.example.com
./gradlew -p host-android assembleDebug        # release: https:// servers + your signing config
```

Run it from your app's root. It writes `host-android/`, a standalone Gradle
build that resolves Keliver from Maven Central only, and that is yours to edit
from then on:

- **production only** — every bundle's manifest must verify against
  `src/main/assets/keliver/portal_ed25519.pub`; there is no development path (that is
  what the generic host above is for);
- **your key, committed** — the scaffolder copies the PUBLIC key from your
  portal store (`keys/ed25519.pub`; `bin/keliver-store-path.sh <app>` prints
  where that is), or from `--public-key-file`. It never reads the private key.
  A missing or malformed key fails the build;
- **your servers** — `--bundle-server`: the host reads
  `<server>/bundles/index.json` and picks the newest bundle it can run. That is
  the file `keliver-publish` writes, so any static server or CDN works; the
  relay serves it too. Only when the index is a 404 — a relay from tools 0.3.7
  or earlier — does the host ask the relay's
  `GET /bundles/latest?widgetVersion=&caps=` instead. (Hosts scaffolded before
  this change know only `/bundles/latest`, so they need the relay.) Optionally, `--api-base-url`, which gives guests the
  `HostHttp` capability over real HTTP, confined to that base (no `..`, no
  redirects, no guest `Host` header). Both live in
  `host-android/gradle.properties`. An `http://` server turns on cleartext
  **app-wide, in debug builds only** — fine for an emulator reaching
  `http://10.0.2.2:8077`; a **release** build with an `http://` server fails;
- `--application-id` defaults to `<your package>.host`;
- **the key check** — the scaffolder refuses a file whose real path ends in
  `.priv`, and a key that is not this app's store's `ed25519.pub` whenever the
  store resolves. A private key has the same format as a public one, so without
  a store it cannot tell them apart: pass only `ed25519.pub`. It never opens
  `ed25519.priv`.

At runtime:

| embedded key | what happens |
|---|---|
| present and valid | the latest compatible bundle's manifest is verified against it; a bundle signed by any other key is refused |
| missing or unusable | **refused** with a message; nothing is fetched (and the build would already have failed) |

A production session is never downgraded to "load it anyway". When it starts
(once per process; see "Lifecycle" below) the host asks the bundle server for the newest compatible bundle first (5 s to
connect, 10 s in all) and follows a manifest URL only on that server's own
origin:

| lookup | what loads |
|---|---|
| answers | the newest compatible bundle, from the network; once it loads, Zipline pins it in its cache |
| fails, and a bundle loaded before | the bundle pinned in Zipline's cache — the last one that loaded from the network — with its manifest verified again against the key; it stays in use until the process restarts |
| fails, nothing loaded before | a "No bundle" screen; the next screen to open looks again |

The cache is used only on that second row because of how Zipline 1.22 loads:
it reads its cache *before* the network and only if the `FreshnessChecker`
accepts the cached manifest, and it never falls back to the cache after a
network failure. Treehouse's default checker accepts nothing, so the host passes
one that accepts the cached bundle for that start alone (and for the fall-back
below). A cached manifest that
no longer verifies is never run: Zipline re-verifies it while reading it, with
either checker, and throws. What the app then shows is not measured. Because
that check runs before the network, the host keeps one cache per key (named by
the key's first 16 hex digits): after an update that changes the embedded key,
the new key starts with an empty cache instead of one it cannot verify. That
key-change path is reasoned from Zipline's source, not run on a device.

When the lookup answers but the bundle itself then fails to download or verify,
the host **falls back to the last good bundle**: the one pinned in Zipline's
cache, which it restarts from exactly as in the second row above (the floor
holds, and the manifest is verified against the key again). Only when no
bundle has loaded before for this key and server, or the cached one fails too,
does it show "Bundle did not load". The next update check then looks up and
loads again in the same process (both hosts, from tools 0.3.16; before
it, only a process restart did):
- a live-update event (a debug build);
- a return to the foreground with `on-resume` or `live` updates, at least
  30 s after the last lookup, the failed start's included (on Android, an
  app that embeds the host calls `resumed()` for this; see "Updates while
  the app runs");
- the app's own `checkForUpdate()`, whatever its `apply` says.

That load is not an update: no `Applied` or `Failed` event comes for it.
The state (`Running` while it loads) and the reports (`loaded`, or
`not-loaded` again) say how it went. When the index still names the very
bundle that failed (the same manifest and sha256), and that bundle cannot
load whatever the link does (it is below the floor, or its signature did not
verify), it is not fetched again. A sha256 mismatch is fetched again: it can
be a stale CDN copy that refreshes.
With `next-launch` updates and no call of its own, the app still needs a
restart. With a static server, the failures include:
- an index entry whose `manifestSha256` isn't the manifest served (a `v<N>/`
  overwritten, or a stale CDN copy);
- a `v<N>/` that isn't there yet because the index was uploaded first;
- a newest entry whose manifest is signed for a sequence below the host's
  rollback floor (below).

Upload `v<N>/` before `index.json`, and never overwrite a `v<N>/` (the adopter
guide's "Publish from CI to a static server"): the fall-back keeps hosts that
ran a bundle before on it, but a new install has nothing to fall back to.

**Lifecycle.** The host looks up and loads once per process, and every screen
shares that load: a rotation, a second screen or a screen opened again doesn't
load anything. So, by default, a bundle published while the app runs is picked
up on the next process start, not when a screen reopens. Only a start that
created nothing (the "No bundle" row above) is retried, by the next screen
that opens.

**Updates while the app runs** (both hosts):
- `keliver.updates=live` (Android) or `UPDATES = "live"` (iOS, `HostConfig.kt`)
  (**a DEBUG build's default: Android from tools 0.3.13, iOS from tools 0.3.14**): `on-resume`, and also, while
  a Keliver screen is on show (Android: a `KeliverScreen` whose lifecycle is
  STARTED; iOS: a `Keliver.viewController()` screen whose lifecycle is STARTED,
  from `viewWillAppear` until it disappears or the app goes to the background),
  the host holds the bundle server's `GET /bundles/events` open and
  runs its ordinary update check on each event, so a bundle you publish
  reaches the open app in a second or two, with no trip to the background.
  The event is only a nudge: what is applied is what `bundles/index.json`
  names and the manifest's signature allows, as on any update, and the screen's
  state may restart with the new code. The portal relay serves the stream (and
  `/devstate` counts the devices listening, which the editor's devices chip
  shows); a static server has none, so the host logs that once and stays
  `on-resume` (so does a 4xx, or a 200 that is not `text/event-stream`). A
  dropped stream is retried after 2 s, doubling to 30 s; with no screen on
  show the host closes the connection. Before the first bundle has loaded
  ("No bundle"), an event starts the host again, so the first publish reaches
  the open app too. The relay accepts at most 32 listeners. Outside a debuggable DEBUG build
  `live` is `on-resume` (the Android template's other build types get
  `on-resume` when you set `live`; on iOS a release binary reads `live` as
  `on-resume`), so it never reaches a store build. The iOS host follows no
  redirect on the stream (a 3xx counts as not served).
- `keliver.updates=on-resume` (Android, in `gradle.properties` or
  `keliver.properties`) or `UPDATES = "on-resume"` (iOS, `HostConfig.kt`),
  which is also what a DEBUG build did when the setting is empty (the
  templates' default from tools 0.3.10 to 0.3.12, and iOS's in 0.3.13; then a
  debug build takes `live` on both platforms; a release build stays `next-launch`; this is a build setting, so a `KeliverConfig` built by hand
  takes its own `updates`, `NEXT_LAUNCH` unless set):
  each time the app comes back to the foreground, at most every 30 s, the host
  looks up again and applies a newer bundle in place. Zipline loads it while
  the running code goes on, swaps it in on success (the floor rises as for any
  load), and keeps the running one on failure. `next-launch` (a release
  build's default) never looks up while the app runs.
- A DEBUG build also checks at once, applying a newer bundle and not held to
  the 30 s, when told to from the computer: on Android
  `adb shell am broadcast -a dev.keliver.debug.CHECK_FOR_UPDATE -p <applicationId>`
  (a receiver registered at run time by a debug build of a debuggable app,
  reachable only by a sender holding `android.permission.DUMP`, which adb's
  shell has and an installed app does not, short of an `adb shell pm grant`);
  on iOS the Darwin notification
  `<bundle id>.keliver.check-for-update` (`xcrun simctl spawn <udid> notifyutil
  -p …`, `xcrun devicectl device notification post --device <id> --name …`).
  A release build has neither.
- In code: `checkForUpdate(apply)` does one lookup now (`UpToDate`,
  `Available`, `Failed`); `currentBundle` is the running sequence and whether
  it came from the cache; `events` (Android) or `onUpdateEvent` (iOS) report
  updates applied or failed and fall-backs. Android's standalone host calls
  `resumed()` in its activity's `onResume`; an embedding app calls it on the
  main thread, after `start()` (the iOS host watches the foreground itself).
- An update that fails to load is not tried again in that process: a check
  reports it as available for the next start. A newer one is applied as usual.
- A process that started from the cache (offline, or after a fall-back) takes
  no further manifest from Zipline: a newer bundle is reported as available
  and applies at the next start.
- The same rules as a start hold: the index, the channel, the constraints, the
  floor, the manifest's sha256 and signature. The fall-back to the last good
  bundle loads from the cache; only if the cache misses does it fetch that
  manifest again, held to the floor and to the index's sha256 when the lookup
  gave one for that URL.

**Reports** (both hosts, W6). Every outcome becomes a small record:
- **Fields:** `installId`, `channel`, `hostVersion`, `sequence`, `source`
  (`network` or `cache`), `outcome`, `reason` and `platform`.
- **Outcomes:** `loaded`, `fell-back`, `update-applied`, `update-failed`,
  `not-loaded`, `no-bundle`, `refused`, or `screen-failed`: the code threw
  while a screen showed it, one record per screen on show (Android from tools
  0.3.14, iOS from 0.3.15). From tools 0.3.16, a host
  whose first load failed loads again at the next update check (§2), so one
  process can send `not-loaded` more than once, then `loaded`.
- **Reasons**, a fixed set: `sha256-mismatch`, `below-floor`, `signature`,
  `lookup-failed`, `network`, `config`, `exception` (for `screen-failed`) or
  `other`.

Where the records go:
- **In code**, always: Android `KeliverHost.reports`, a `SharedFlow` that
  replays the newest to a new collector, and iOS `Keliver.shared.onReport`.
  - Here the record also carries `detail`, the full error message. It can hold
    addresses, file paths or URLs, so it is never sent anywhere by the host.
  - Forward what you need to your own analytics.
- **To a URL**, only if you set one: `keliver.reportUrl` (Android) or
  `REPORT_URL` (iOS `HostConfig.kt`).
  - The host POSTs each record there as JSON with exactly the eight fields
    above.
  - It is best effort: one try, no redirect followed, never blocking or failing
    a load. Android allows 10 s in all; iOS gives up after 10 s without data.
  - The URL may not carry `user@`, a query or a fragment, and it is never
    logged. A release build refuses `http://`.
  - On iOS, use `https://`: the scaffolded App Transport Security exceptions
    cover only the bundle and API hosts.
  - `no-bundle` is sent once per process.
  - Empty, the default, sends nothing anywhere.

**The relay as a collector, for development.** Point the report URL at your
portal relay's `/report`:
- Android: `http://10.0.2.2:8077/report` from an emulator. Debug builds allow
  it.
- iOS: `http://localhost:8077/report` from a simulator, the same host as an
  `http://` bundle server.

The relay keeps the reports in its store (`reports/`, at most 20,000), and
refuses anything that is not exactly the eight fields. It shows the counts:
- `GET /reports`: JSON, per platform, channel and sequence, with distinct
  installs and outcomes, plus the newest records;
- `GET /reports/view`: the same as a page.

For real users, use your own collector over `https://`.

**What a report URL receives about a user:**
- `installId`, the random id made on the device for rollouts. It is a
  persistent pseudonymous identifier, which privacy law such as the GDPR may
  treat as personal data.
- Like any request, the sender's IP address and the HTTP user agent.

Nothing else identifies the user or the device. Say so in your privacy notice
if you set a report URL.

The running sequence makes a useful crash key. Use the bundle that is running,
not every report (an `update-failed` report names the bundle that did not
run):

```kotlin
// Android, in your Application, after creating the host:
appScope.launch {
  keliver.currentBundle.collect { b ->
    Firebase.crashlytics.setCustomKey("keliver_sequence", b?.sequence ?: -1)  // or Sentry.setTag(...)
  }
}
```

```swift
// iOS: after each report, the bundle that is running now
Keliver.shared.onReport = { _ in
  let seq = Keliver.shared.currentBundle()?.sequence ?? -1
  Crashlytics.crashlytics().setCustomValue(seq, forKey: "keliver_sequence")  // or SentrySDK.configureScope
}
```

**Measured:** both platforms' CI checks that the reports reach a URL (V1:
exactly one `loaded`, then one `update-applied`, from one install, with no
failure reported and only the eight fields).

**Rollback protection.** Every bundle `keliver-publish` or the relay publishes
carries its sequence inside the signed manifest (`metadata.keliver.sequence`).
The host remembers the highest sequence it has run for its key: its floor.
Below the floor, a manifest is refused:
- on the network;
- from the cache;
- on the cache start's own network fallback.

Once the floor is above 0, a manifest with no sequence is refused too. The
floor rises only after a bundle has loaded, so after Zipline has verified its
signature. Once a host has run a sequenced bundle, whoever controls the bundle
server can't make it run an older one.

**Not protected:**
- A reinstall, or "clear data", resets the floor.
- A host whose floor is still 0 accepts any bundle your key signed: a new
  install, or one that has only ever run unsequenced (pre-W4) bundles. A
  server that only ever serves old unsequenced bundles keeps it there.

**One key, one sequence space.** The floor is per key, across every server and
route the host has used. During a key rotation (W8) a host reads the highest
floor over the keys it trusts and writes it under each, so the sequence space
carries over to the next key ([`KEY_ROTATION.md`](https://github.com/waliasanchit007/keliver/blob/main/docs/KEY_ROTATION.md); in the tools bundle, `KEY_ROTATION.md` at its root). A newest entry below a host's floor shows **no
bundle** to a new install, and leaves the others on their cached last good
bundle (the fall-back above), never on the newer one. So never let sequences go
backwards. Ways a publisher can do that by mistake:
- an `--init` into an empty directory (it restarts at 1);
- alternating relay publishing (sequence = relay version) and `keliver-publish`
  (the index sequence) with the same key;
- a host once pointed at a development relay that published a high sequence
  with the production key.

**Rolling back** is therefore publishing again, never serving an older index:
`keliver-publish --republish <v>` publishes v`<v>`'s unchanged modules as a new
`v<N>/` at the next sequence, signed by the app's `keliverResign` task. Hosts
take it because it is newer.

**Channels** (both hosts). A host is built for one channel, `--channel` on
either scaffolder (default `stable`; Android `keliver.channel` in
`gradle.properties`, iOS `CHANNEL` in `HostConfig.kt`). It takes that channel's
index entries and stable's, the newest sequence of them. `keliver-publish
--channel beta` reaches only beta hosts; `--promote <sequence> --channel stable`
then offers the same bundle to everyone. Channels are selection, in the
unsigned index: the floor is per key, not per channel, so a host moved to
another channel (a rebuild) keeps its floor.

**Staged rollouts and host-version gates** (both hosts). An index entry may
carry `constraints`:
- **`rollout` (0–100):** an install takes the entry when its bucket is below
  it. The bucket is the first four bytes of `sha256("<install id>:<sequence>")`,
  mod 100. The install id is random, made once on the device, and never sent
  anywhere. A rollout gates only sequences above the host's floor, so lowering
  or halting it never takes a bundle away from a host that ran it.
- **`minHostVersion` / `maxHostVersion`:** compared with the host build's
  version (Android `versionCode`, iOS `CFBundleVersion`). A host whose version
  is not an integer skips gated entries. Gates hold below the floor too, because
  they are about what the code needs.

A host skipping an entry takes the newest one it may run. An entry with a
constraint key the host doesn't know is skipped, so hosts from before W4.5 skip
every constrained entry.


**The iOS twin** is §3. **Publishing the bundles it loads.** `keliver-new-publish-target.sh` (run once,
after `keliver-new-device-target.sh`) gives `keliver.portal.json` a
`publishTask`/`publishOutput` for your app and appends the signing block to your
`build.gradle`. The block signs the compiled manifest inside Gradle; the key is
never on a command line, in a build log or in `.gradle/` (U31; to upgrade a
0.3.6 block, run the command again). `POST /publish` then stores a bundle only if
its manifest verifies against your store's public key: an unsigned or
foreign-signed bundle is refused and nothing is stored. **What you still set up
yourself:** your own signing config for a release APK.

The generic host above deliberately does not grow into that. It exists so that
a new adopter can see their screens on a device without writing Android code.

**Timing marks** (both hosts, from tools 0.3.10; M0 of `ADOPTION_PLAN_2026-10.md`).
For measuring startup, a host records these moments:

| Mark | When |
|---|---|
| `process` | Android only: when the OS started the process (`Process.getStartElapsedRealtime`) |
| `open` | a Keliver screen was asked for (`KeliverScreen` composed; iOS `viewController()`) |
| `code` | the bundle's code loaded (Zipline's `codeLoadSuccess`), each load |
| `first-frame` | one frame after the host applied a screen's first change list |
| `content` | one frame after the guest said its data is shown |

- **Clocks:** monotonic, in nanoseconds: Android `SystemClock.elapsedRealtimeNanos()`,
  iOS `CACurrentMediaTime()`. `first-frame` and the guest's marks are taken one frame
  later (Android: two `Choreographer` callbacks; iOS: two `CADisplayLink` ticks), so
  they count a frame the user can see.
- **Output:** Android logs `KeliverTiming: <name> <nanos>` (tag `KeliverTiming`) and
  appends the same `<name> <nanos>` line to `files/keliver/timing.log`
  (`adb shell run-as <applicationId> cat files/keliver/timing.log`). iOS calls
  `Keliver.shared.onTrace(name, nanos)` on the main thread, prints the line to the
  console, and appends it to `Library/Caches/keliver/timing.log` in the app's container.
  A file over 256 KB starts again when the host starts.
- **The guest's mark:** `keliver-new-device-target.sh` writes
  `src/jsMain/kotlin/device/KeliverTiming.kt`. For an older guest, copy the tools bundle's
  `templates/guest/KeliverTiming.kt` there and replace `@@PACKAGE@@` with the package of
  your `device/Main.kt` minus `.device`. Call `KeliverTiming.mark("content")` once the screen's data is shown;
  pass it to a presenter as a lambda, since presenters are common code. Names are `a-z`,
  `0-9` and `-`, at most 32 characters. It calls the `KeliverTrace` service, which only a
  build with the marks binds; anywhere else it does nothing.
- **Which builds have them:** debug builds, and a **benchmark** build that is never
  shipped: release settings (not debuggable, `https://` only), minified with R8 as a
  store build should be (the standalone template's `release` leaves minifying to you),
  plus `profileable`, signed with the debug key so it installs. Android:
  `./gradlew -p host-android assembleBenchmark` (the `benchmark` build type; an embedded
  host takes your app's own `benchmark` build type). iOS: the `Benchmark` configuration
  (Release settings, `KOTLIN_FRAMEWORK_BUILD_TYPE = release`), e.g.
  `xcodebuild -configuration Benchmark`.
- **The store build compiles none of it.** Android adds `src/timing/` to the debug and
  benchmark build types only, and `src/notiming/` (the same calls, doing nothing) to
  every other one. iOS compiles `src/timing/` only when Xcode's build setting
  `KELIVER_TIMING` is `YES`, which Debug and Benchmark set; Release, and a build outside
  Xcode, compile `src/notiming/`. An embedded iOS host needs that setting in your own
  project (its `EMBED.md`).

**The guest frame profile** (both hosts, in the same builds as the marks; M4 of
`ADOPTION_PLAN_2026-10.md`; not in a released tools bundle yet). Where the time of each
guest frame goes, as one line per window of up to 60 frames, or 1 s after the window's first
sample:

```
frames <nanos> n=12 empty=3 lists=10 changes=84 guest=850/2100/2400 lead=700/1900/2200 sink=40/90/120 apply=300/900/1100 queue=60/400/700
```

Each span is `p50/p95/max` in microseconds, or `-` when the window has none.

| Field | What it counts |
|---|---|
| `n`, `empty` | the guest's frames (the host's `sendFrame` calls), and those that sent no change list |
| `lists`, `changes` | change lists the guest sent, and the changes in them |
| `guest` | a frame's `sendFrame` call, start to end: the guest's composition, applying it to the guest's tree, encoding, and the host taking the change list |
| `lead` | from a frame's start to its change list reaching the host: the guest's work and encoding, and the host decoding the list |
| `sink` | the host's `sendChanges`: converting the list and queueing it for the main thread |
| `apply` | the host applying the list on the main thread |
| `queue` | the list waiting for the main thread |

- **Where it comes from:** the host's `EventListener` times `sendFrame`
  (`callStart`/`callEnd`), and the timing wrapper around each screen's
  `ZiplineTreehouseUi.Host` times `sendChanges` and posts a message before and after the
  apply (Android: asynchronous messages, as `Dispatchers.Main` posts the apply).
- **What it cannot split:** composition from encoding inside `guest` and `lead`. That needs
  a timestamp inside the guest's runtime, a library change. `apply` is the host's own apply
  of the list. The host UI's own recomposition, layout and draw come at its next frame and
  are not in it, except now and then: main-thread work that gets in between the two posts
  (Android: another asynchronous message due in that moment; iOS: a display-link render while
  the main queue was idle between them) is counted, which shows in p95 and max.
- **Output:** the same as the marks' (logcat tag `KeliverTiming`, the iOS console, and
  `timing.log`), but not `Keliver.shared.onTrace`, whose marks are moments. A screen's first
  change list, sent from its start rather than in a frame, has no `lead`. Frames are sent
  when the guest asks for one, and a screen left alone after it opens may ask for none
  (`n=0`, as on the Android emulator) or only for frames that send nothing (`empty`, as on
  the iOS simulator); the reference app's Android T3 taps a button first. A frame's `lead`
  and `sink` are always in the same line as its `guest`; their `apply` and `queue` come later
  and can fall in the next line.
- **Overhead:** per frame, a few small objects and one task on the timing thread; per change
  list, two more main-thread messages and one more timing-thread task; the samples in fixed
  arrays. A store build compiles none of it (`src/notiming/`); only the listener's two
  overrides, returning nothing, remain.

**The memory profile** (the iOS host, in the same builds as the marks; M5 of
`ADOPTION_PLAN_2026-10.md`; not in a released tools bundle yet). What the process, QuickJS
and the Kotlin heap hold at points of a screen's life, as one line per point:

```
memory <nanos> at=content cycle=0 footprint=183204 peak=190112 qjs=6120/7012 objects=41230 knheap=-
```

Sizes are in KB; `-` is "not read here".

| Field | What it is |
|---|---|
| `at` | `open` (a Keliver screen is being made, before its Compose view controller), `ui` (two display-link ticks after its first composition: the first screen's Loading screen; a later screen's is the guest's at once), `runtime` (a QuickJS runtime was made, before any code), `code` (the bundle's code loaded), `content` (with the guest's `content` mark), `closed` (2 s after a memory cycle closed its screen, after collecting both heaps) |
| `cycle` | 0 for the screen the app shows; then each memory cycle's number |
| `footprint`, `peak` | the process's physical footprint (`task_info` `TASK_VM_INFO` `phys_footprint`: what Xcode's memory gauge shows and iOS's memory limit counts), and its highest so far |
| `qjs`, `objects` | QuickJS's heap in use / allocated, and its objects (`Zipline.quickJs.memoryUsage`, read on the Zipline thread); `-` before a runtime exists |
| `knheap` | the Kotlin/Native heap's live objects after a full collection; at `closed` only |

- **A memory cycle** opens a second screen over the shown one, waits for its content (at
  most 15 s), closes it and samples `closed`. One runs at a time, and only once the shown
  screen has its content. A debug or Benchmark build runs one when the
  Darwin notification `<bundle id>.keliver.memory-cycle` is posted (`xcrun simctl spawn
  <udid> notifyutil -p <name>`; `xcrun devicectl device notification post --device <id>
  --name <name>` on a phone). Growth from one cycle's `closed` to the next is what a screen
  did not give back; the first cycle's own growth includes one-time costs (images, caches).
- **Reading it:** `open` to `ui` is mostly the Compose Multiplatform runtime and its first
  draw; `ui` to `runtime` and `runtime` to `code` add QuickJS, the loader and the guest's
  code (`qjs` at `runtime` is the engine before any code; `code` minus it is the guest's code
  and start-up); `code` to `content` is the screen (`qjs` for its guest side).
- **What it cannot split:** the steps overlap (the lookup and the code load run while
  Compose draws), so footprint differences between points are approximate; only `qjs` is
  exact. Skia and Metal memory is not apart from Compose's, nor the host's widgets from
  Compose. The simulator is a macOS process with no memory limit and other graphics memory:
  numbers that matter are a phone's. `closed` reads QuickJS after the screen's view
  controller is dismissed; if its composition is let go only later, at a Kotlin collection,
  its guest objects still count there (compare `objects` at `closed` with `content`). A host
  that starts before its first screen logs `runtime` and `code` before `open`.
- **Output:** the same as the frame profile's (the console and `timing.log`, not `onTrace`).
- **Overhead:** one `task_info` call per point, and one QuickJS read on the Zipline thread;
  collections only at `closed`. A store build compiles none of it (`src/notiming/`); only
  the listener's `ziplineCreated` override, doing nothing, remains. The Android host has no
  memory profile yet.

## 3. Your own production iOS host

`keliver-new-ios-host.sh` (run from the app root; scaffolding needs bash and
python3, building needs macOS with Xcode) writes `host-ios/`:
- **a Kotlin framework** (`KeliverHost`), a standalone Gradle build on Maven
  Central only;
- **an Xcode app** whose build phase runs
  `./gradlew -p host-ios embedAndSignAppleFrameworkForXcode`. Its
  `PRODUCT_NAME` must differ from `KeliverHost`; the scaffolder sees to that.

```bash
keliver-new-ios-host.sh --bundle-server URL [--api-base-url URL] [--bundle-id ID] [--public-key-file PATH] [--channel NAME]
```

It behaves like the Android host in §2:
- **Production-only.** Every manifest must verify against the public key in
  `HostConfig.kt`. Without a valid key the host fetches nothing and shows a
  refusal. There is no unverified fallback.
- **Startup.** The lookup runs first (10 s), then Zipline's verified cache when
  the lookup fails, then "No bundle". As on Android, when a bundle that the
  lookup names then fails to load (a download error, a `manifestSha256`
  mismatch, the floor), the host falls back to the cached last good bundle,
  and shows "Bundle did not load" only without one; see §2. There is one
  Zipline cache per key, and manifest URLs are followed only on the bundle
  server's origin. (Zipline's downloads follow HTTP redirects, as on Android.)
- **`HostHttp`** goes over `NSURLSession` to your API base only, when one is
  set. Paths can't climb out of it; a method that isn't a plain token, or a
  header holding CR or LF, is refused; hop-by-hop headers are dropped; and
  redirects aren't followed. (That last one is the delegate's job. From tools
  0.3.16 it is measured by the host's simulator tests,
  `./gradlew -p host-ios iosSimulatorArm64Test`, against a local server; no
  running guest has used HostHttp yet.)
- **Guest SQL** (`HostSqlDriver`) is real SQLite in Application Support, bound
  through `src/nativeInterop/cinterop/sqlite3.def`.
- **Images** load over the network (Coil with Ktor's Darwin engine).

Build-time checks (`host-ios/build.gradle`):
- `checkHostConfig` fails the build on a malformed key or server;
- `checkReleaseUrls`, on every release framework link, refuses `http://`
  servers, and refuses an `Info.plist` that still has any App Transport
  Security exception.

URLs follow one grammar in the scaffolder, the build and the host: plain
ASCII, a host name or `[IPv6]` with no `user@`, an optional port and path,
and no query, fragment or `$`. They become Kotlin string literals, where `$`
would be a template.

For development, `Info.plist` gets an App Transport Security exception only
for the `http://` hosts you scaffolded with. There is none for `https://`, and
none in a release build.

**Measured** on iOS simulators (the evidence is in the Keliver repository,
`docs/superpowers/evidence/ios-host-*`, and its `ios-host.yml` CI runs):
- signed v1 loads;
- an edit published as v2 is followed (Inventory → Stockroom);
- a bundle signed with another app's key is refused;
- the host recovers when the right relay is back;
- it starts offline from the cache;
- no empty-URL load.

**Not measured:**
- a physical iPhone;
- a release or App Store build;
- HTTPS end to end;
- taps (the CI has no iOS tap driver);
- HostHttp, network images and guest SQL in a running app (the reference
  guest uses none of them; the SQLite driver was tested on a macOS harness).

**Rollback protection** is as on Android (§2): a per-key floor in
`NSUserDefaults`, checked on the network and on the cache start. A reinstall
resets it.


## 4. Embedding the host in an existing app

`--embed` on either scaffolder writes the host of §2 or §3 as a part of an app
you already have, instead of as an app of its own. Nothing of your app is
edited; the scaffolder prints what to add. In the tools bundle from 0.3.8.

```bash
keliver-new-production-host.sh --embed --into DIR [--module keliver-host] --bundle-server URL \
    [--api-base-url URL] [--public-key-file PATH] [--channel NAME]
keliver-new-ios-host.sh --embed --into DIR [--module keliver-host-ios] --bundle-server URL \
    [--api-base-url URL] [--public-key-file PATH] [--channel NAME]
```

Run them from your Keliver app's root (the one with `keliver.portal.json`), as
for §2 and §3: they read its package and its store's public key, and the iOS
one copies its Gradle wrapper. The key checks, URL rules and refusals are the
same, and so is the host's behaviour: production only, the lookup first, the
verified cache, the rollback floor, channels, rollouts and host-version gates.

**Android: a library module.** `DIR/keliver-host/` is a `com.android.library`
with the same host Kotlin as `host-android/` (the self-test compares them byte
for byte):
- **`KeliverHost`**: the host. It owns its coroutine scope, the one Treehouse
  app and Zipline, the SQL and HTTP hosts and the image loader, for the life
  of the process. Create it once, in your `Application`:
  `KeliverHost.create(this)`, from the module's build settings and checked
  key. (`KeliverHost.create(this, KeliverConfig(...))` takes a configuration
  built in code, which skips the build's checks of the key and of `https://`.)
  A second `create()` for the same key throws, because two loaders must never
  share a Zipline cache. `start()` is idempotent: the first screen calls it, or
  call it at launch to warm up. It looks the bundle up and, from tools
  0.3.16 (M3), starts loading its code as soon as the lookup
  answers, before any screen asks (earlier hosts loaded the code only when the
  first screen was shown). So a start at launch does the whole load then,
  whether or not a Keliver screen is ever opened:
  - the download (on a first launch, every module) and the code's memory;
  - the `loaded` report, which no longer means a screen was shown;
  - with on-resume or live updates, update checks on each return to the
    foreground (at most one every 30 s), which a host with nothing loaded
    skipped;
  - a first load that fails, fails at launch. The screen opened later does
    not load again: the next update check does (§2, both hosts),
    and with next-launch updates there is none in the process;
  - guest code that crashes with no screen showing is only logged, and the
    first screen loads it again.

  An app whose Keliver screens are rarely used, or opened long after launch,
  can leave `start()` to the first screen. `state` is a `StateFlow` the screens
  observe:
  `Loading`, `Running`, or `Unavailable(outcome, reason, title, message, attempt)` when
  the host cannot show a screen (`outcome` is the report's: `no-bundle`,
  `refused` or `not-loaded`; `reason` its fixed category; `attempt` numbers
  failures in the process from 1, `screen-failed` ones (given to a screen's
  `onUnavailable` only) included, so the same failure on a retry is a new state;
  equality includes it, so compare `outcome` and `reason`, not whole states). Observe it to hide
  your own chrome while `Running`.
- **`KeliverScreen(host, modifier, onUnavailable, loading, unavailable)`**, a
  Composable, and **`KeliverView`**, an `AbstractComposeView` for View layouts
  (set its `host`, and its `onUnavailable`). They only observe the host, so any
  number of them, recreated on every configuration change, share the one lookup
  and the one load. **`onUnavailable`** is where you replace the Keliver
  screen with what it replaced (your web page or native screen, with the route
  you were showing): it is called on the main thread each time a screen is
  composed in an `Unavailable` state, so finish or pop the Keliver screen
  there rather than stacking the fallback over it. It is also called with
  outcome `screen-failed` when the code throws while it shows: Treehouse stops
  that code, so every screen showing it hears it (one report each), and so
  does every screen opened afterwards, until code loads again (an update);
  `state` stays `Running`. After "no bundle", a
  screen composed later looks up again (`Loading` meanwhile). **`loading`**
  and **`unavailable`** draw those states; the defaults show the host's own
  text only in a debug build of a debuggable app (`showsDeveloperText`) and
  otherwise only "Loading…" and a generic line, because the host's text can
  name addresses and paths. `KeliverView` needs a lifecycle owner
  in its window, like any Compose view: use it in a `ComponentActivity`
  (`AppCompatActivity`) or a `Fragment`, not a plain `android.app.Activity`.
- **Routes and close** (K1): `KeliverScreen(host, route = KeliverRoute.from(intent.dataString, allowedQueryKeys))`
  shows the guest's screen for that route. `KeliverRoute` keeps a deep
  link's path and only the query keys you allow, so a link's other
  parameters never reach the guest. Set a new route (a re-delivered intent's,
  from `onNewIntent`; `KeliverView.route` for Views) and the screen launches
  it. The guest receives it as text (`orders/42?tab=items`) in
  `PortalPresenter.launchRoute(route, screen)`, which
  `keliver-new-device-target.sh` scaffolds; a guest scaffolded before it has
  no `launchRoute`, and the host shows its `launch()` screen instead (logged).
  The route is form-encoded text (UTF-8, `+` for a space) and the same for
  the same link on Android and iOS (one shared `KeliverRoute`). A screen
  closes itself with `screen.closeScreen()`: `KeliverScreen`'s `onClose`, on
  the main thread; without one its activity finishes. In a navigation stack,
  pass `onClose` (pop your entry). A close that arrives after the screen went
  (a late reply) closes nothing.
- **`RequestPolicy`**: what a guest's HostHttp requests may reach, checked in
  Keliver code before anything is sent:
  `KeliverHost.create(this, KeliverConfig.fromBuild(this, RequestPolicy(origin, allowed, allowStateChanging, allowCleartext)))`.
  - `origin` (scheme, host, port) pins the API base: if `keliver.apiBaseUrl`
    is anywhere else, every request is refused. It must be `https` unless
    `allowCleartext`, which you set from `BuildConfig.DEBUG` (a release
    build's settings refuse an http API base anyway);
  - `allowed` holds exact `RequestPolicy.Rule(method, path)` pairs, paths relative
    to `keliver.apiBaseUrl` and compared segment by segment (no patterns: a
    path with an id in it is a different path);
  - a state-changing method (anything but GET, HEAD, OPTIONS) also needs
    `allowStateChanging`, a per-build switch.

  A refused request fails in the guest with "HostHttp refused: …" and is
  logged (the path only in a debug build); nothing reaches the network.
  Without a policy every path under the API base is allowed, as before: an
  app embedding Keliver screens should set one. Redirects are never followed
  and the method-override and path-override headers (`X-HTTP-Method-Override`,
  `X-Original-URL` and kin) are dropped either way (the path-override ones from
  0.3.15). From 0.3.15 the URL is also checked again after your client's
  interceptors and on each network attempt: an interceptor must change a
  request with `newBuilder()`, and one that sends it to another path is
  refused. (An `Authenticator` retry to another path that your client's cache
  answers is not caught.) **Not
  restricted:** the query string, and other headers. With the host's plain
  client a guest can set `Authorization` or `Cookie`. With your own client
  (`KeliverConfig.fromBuild(this, requestPolicy, httpClient = yourClient)`,
  from 0.3.15) they are dropped, and so is a response's `Set-Cookie`. From
  tools 0.3.16, `TRACE`, `TRACK` and `CONNECT` are refused
  through your client even when the policy allows them: a `TRACE` would echo
  your interceptor's headers back to the guest. Your client is used only for
  HostHttp.
- **`ExternalLinkPolicy.open(context, url)`**: call it from whatever your app
  exposes to screens for opening a link. `https` opens in a Custom Tab, on any
  host. `intent:` is handled as a browser handles it, and stricter: a VIEW
  only, no component or selector, BROWSABLE activities only, none of the
  link's flags (no URI grants, no task games), never this app (named, or one
  of its own deep links), and no `file:`, `content:`, `http:`, `javascript:`,
  `data:` or `intent:` data; an https `browser_fallback_url` when nothing
  handles it. Otherwise it reaches any app with a BROWSABLE activity for it,
  custom schemes included. Everything else, `http` included, is refused and
  logged.
- **Tests**: the standalone host carries the kit's JVM tests
  (`./gradlew -p host-android testDebugUnitTest`): your own client behind the
  policy (allowed with your token, refused before your client, wrong origin,
  a redirect, an interceptor or authenticator sending the request elsewhere,
  the guest's credential headers, `Set-Cookie`, no policy), the routes
  (`KeliverRoute`), the policy against a local server (allowed, not listed, wrong method, state-changing without the
  switch, a policy for another origin, cleartext, a redirect, encoded `/` and
  `?`, dot segments, method case, method-override headers) and the link rules
  (schemes, and a parsed `intent:` link's action, target and data). Applying
  those rules to a real `Intent` needs a device.
- **Settings**: `keliver.properties` in the module (a subproject's
  `gradle.properties` is not read), each overridable with `-Pkeliver.<name>`.
  The host version that `minHostVersion`/`maxHostVersion` gates compare with is
  your app's `versionCode`, from `PackageManager`.
- **The trust root**: `src/main/assets/keliver/portal_ed25519.pub`, commit it.
  The build checks it and compiles it into `BuildConfig`, and the host trusts
  only that copy. It never reads the key from the APK's merged assets, where
  your app's asset or another library's of the same name would win.
- **The manifest** asks only for `INTERNET`. Everything application-level is
  yours: cleartext for an `http://` development server (debug only; a release
  build of the module refuses `http://`), the theme, and backups. The
  standalone host sets `allowBackup="false"`; a library can't, so if your app
  allows backups, exclude the host's shared preferences (`keliver-host.xml`:
  the floor and the install id) with `dataExtractionRules` and
  `fullBackupContent`, or a restored device starts with another device's floor
  and rollout bucket.
- **Debug-only surfaces** (audited for K1): the bundle server set on the
  device (`files/keliver/bundle-server`), the check-now broadcast, the
  host's own text on screen, and live updates. Each needs both this module's
  debug variant (`BuildConfig.DEBUG`) and a debuggable app, so a release build
  of your app, or a debuggable build that uses the module's release variant,
  has none of them. The check-now receiver is registered at run time, never in
  the manifest, and only a sender holding `android.permission.DUMP` (adb's
  shell, not an app) reaches it. A release build never runs live updates
  (`keliver.updates=live` becomes `on-resume`). The self-test's `--build`
  checks a release APK: `DEBUG` false, not debuggable, and nothing exported
  but the launcher activity unless a permission guards it.
- **R8**: the module's consumer rules keep Zipline's bridged services, which
  are called by name. A minified release of the reference app loads and
  renders (X7 below).
- **Your build supplies the plugins**: `com.android.library`,
  `org.jetbrains.kotlin.plugin.compose` and `app.cash.zipline` `1.22.0`, with
  Kotlin 2.2.0. The scaffolder warns when it can't find them.

**iOS: a framework build.** `DIR/keliver-host-ios/` is the `KeliverHost`
framework's Gradle build (the same Kotlin as `host-ios/`) with its own wrapper,
plus `KeliverScreen.swift` and `EMBED.md`:
- `public object Keliver { start(); viewController(safeArea:) }`, one per
  process; `KeliverScreen` is the SwiftUI wrapper around `viewController`.
  `MainViewController()` stays, as an alias, for the standalone app.
- **State** (K2, the same shape as Android's): `Keliver.shared.currentState`
  is `KeliverHostStateLoading`, `KeliverHostStateRunning`, or
  `KeliverHostStateUnavailable(outcome, reason, title, message, attempt)`
  (`outcome` the report's: `no-bundle`, `refused` or `not-loaded`; `attempt`
  numbers failures from 1, `screen-failed` ones included, so a repeated
  failure is a new state and the numbers seen here can skip). Read it, then
  set `Keliver.shared.onStateChange`, called on the main thread with each later
  change: hide your own chrome while `Running`. (Kotlin code can collect
  `Keliver.state`, a `StateFlow`.)
- **`viewController(safeArea:onUnavailable:)`** and `KeliverScreen(onUnavailable:)`:
  called on the main thread each time that screen shows an `Unavailable`
  state. Replace the Keliver screen there with what it replaced (your web page
  or native screen, with the route you were showing): dismiss or pop it,
  rather than stacking the fallback over it. The screen holds the closure:
  capture your controller weakly (`[weak self]`). It is also called with
  outcome `screen-failed` when the code throws while it shows, as on Android:
  Treehouse stops that code, so every screen showing it hears it (one report
  each), and so does every screen opened afterwards, until code loads again
  (an update); `currentState` stays `Running`.
- **Routes and close** (K2): `KeliverScreen(route: KeliverRoute.companion.from(uri: url.absoluteString, allowedQueryKeys: ["tab"]), onClose: …)`
  or `Keliver.shared.viewController(safeArea:onUnavailable:route:onClose:)`
  shows the guest's screen for that route: a deep link's path and only the
  query keys you allow, as the same text the Android host sends
  (`orders/42?tab=items`; one shared `KeliverRoute`) to the guest's
  `launchRoute`. A guest scaffolded before it shows its default screen
  (logged). A new route needs a new screen (in SwiftUI, a new `.id`). The
  screen closes itself with `screen.closeScreen()`: your `onClose`, on the
  main thread, or by default the navigation stack pops back to the screen
  below it, or what presents it dismisses it. A SwiftUI app that shows it in
  a sheet or a NavigationStack passes `onClose` (`dismiss()`, or drop the
  path element), so its own state agrees.
- **`RequestPolicy`** (K2, Android's rules): set `Keliver.shared.requestPolicy
  = RequestPolicy(origin: "https://api.example.com", allowed: [RequestPolicy.Rule(method: "GET", path: "orders")], allowStateChanging: false, allowCleartext: false)`
  before the host starts. The origin must be `API_BASE_URL`'s (otherwise every
  request is refused) and `https` unless `allowCleartext` (pass your DEBUG flag, never `true`); paths
  are exact pairs relative to the API base; a state-changing method also needs
  `allowStateChanging`. A refused request fails in the guest ("HostHttp
  refused: …") and nothing is sent; the method-override headers are always
  dropped, and from tools 0.3.16 the path-override ones
  (`X-Original-URL` and kin) too. Not restricted: the query string and other
  headers. Without a policy every path under the API base is allowed, as
  before. It is pure Kotlin, so its tests run on the JVM:
  `./gradlew -p host-ios jvmTest`.
- **`requestHeaders`** (B2, from tools 0.3.16): your app's
  credentials for guests' HostHttp requests, in place of Android's own
  client: `Keliver.shared.requestHeaders = { method, path, done in
  done(["Authorization": "Bearer \(token)"]) }`, set before the host starts.
  It is called on the main thread for each request the policy allowed, with
  its method and its path relative to the API base. Call `done` once, with
  the headers to add (they replace the guest's of the same name), or with
  `nil` to refuse the request, as when nobody is signed in; no answer within
  30 s refuses it too (a busy main thread included: don't block it in the
  callback, answer `done` later), and a refused request sends nothing. It
  needs a `requestPolicy`: without one every request is refused. With it, a
  guest's own `Authorization`, `Proxy-Authorization` and `Cookie` headers are
  dropped, a response's `Set-Cookie` is not handed back, HostHttp keeps no
  cookies, cache or credentials of its own, and `TRACE`, `TRACK` and
  `CONNECT` are refused (`TRACE` would echo your headers back to the guest;
  Android refuses them through your own client too). Only HostHttp gets
  these headers: never
  bundles, the update stream or reports. The rules are measured on a
  simulator against a local server: `./gradlew -p host-ios
  iosSimulatorArm64Test` (`-PkeliverTestSimulator=<name or UDID>` when
  Kotlin's default simulator is not installed).
- **Release text**: only a DEBUG binary (`Keliver.shared.showsDeveloperText`)
  shows the host's own text, which can name addresses and paths; a release
  binary shows "Loading…" and a generic line.
- The settings and key are in `src/iosMain/kotlin/<package>/HostConfig.kt`,
  checked by the build as in §3. `checkReleaseUrls` reads the Info.plist Xcode
  is building (`$SRCROOT/$INFOPLIST_FILE`), so a release build refuses an
  `http://` server or any App Transport Security exception in your app's
  Info.plist.
- `EMBED.md` has the Xcode edits: the Run Script phase (`embedAndSignAppleFrameworkForXcode`)
  before Compile Sources, `ENABLE_USER_SCRIPT_SANDBOXING = NO`, `-lsqlite3`,
  `CADisableMinimumFrameDurationOnPhone`, and adding `KeliverScreen.swift`.
- The scaffolder refuses an Xcode project that already names a product or
  module `KeliverHost`: Swift would then ignore `import KeliverHost`.

**When a load fails**, the Keliver view's state is `Unavailable`: it calls
`onUnavailable` and shows a message instead of the guest's screen until your
app navigates (in a debug build of a debuggable app, or a DEBUG binary on iOS,
the text below; otherwise a generic line), and nothing else in
your app is affected:
- a missing or invalid key: "Refusing to load";
- the lookup failed and nothing is cached: "No bundle", retried by the next
  Keliver screen that opens;
- a bundle that the lookup named but that then fails (another key's signature,
  a sha256 mismatch, a sequence below the floor): the cached last good bundle,
  if one ran before (§2); otherwise "Bundle did not load", until the next
  update check loads it (§2) or the process restarts.

A running process keeps the bundle it loaded; a newer one is picked up on the
next process start (§2, "Lifecycle").

**Measured** in Keliver's CI, on two plain apps standing in for an existing one
(`reference/embed/android`, a View-based app, and `reference/embed/ios`, a
SwiftUI app), each with only the documented edits, loading from a static HTTPS
server fed by `keliver-publish`:
- Android emulator: the native views and the guest's screen on one screen, the
  guest inside the `KeliverView` (X1); a signed load (X2); taps inside the
  embedded screen (X3); native navigation and a rotation with one lookup and
  one load (X4); the floor stored in the app's own data (X5); another key's
  bundle refused, the app's `onUnavailable` putting its own view there, while
  the native views and the process stay (X6); an
  R8-minified release loading and rendering (X7); and the host kit (K4), from
  the app's deep links: a route reaching the guest with only the allowed query
  keys (X8), the guest closing its screen through the app's `onClose` (X9),
  and the guest throwing: `screen-failed`, the app's `onUnavailable`, the
  process alive (X10);
- iOS simulator: the native views and the guest's screen in one screenshot
  (I1); a signed load (I2); the floor stored in the app's defaults (I4);
  another key's bundle looked up and refused, the app's `onUnavailable`
  putting its fallback there, while the native views stay (I5); the host kit
  from a link passed at launch: the route (I6), the close (I7) and
  `screen-failed` (I8).

**Not measured:**
- native navigation on iOS (no tap driver on the simulator CI);
- an app on another Kotlin or AGP than the one combination CI builds (CI also
  BUILDS an app on older Compose, activity, core, lifecycle, coroutines and
  OkHttp 4, raised to the floor, but does not run it);
- an iOS app that already embeds a Kotlin framework (it would carry two Kotlin
  runtimes; put the host sources in that framework instead);
- an XCFramework instead of the build phase (documented in `EMBED.md`, not run);
- physical devices.

**Limits:**
- **Kotlin, on Android.** The module is compiled by your app's Kotlin, and
  Zipline 1.22.0's compiler plugin needs Kotlin 2.2.0. An app on another Kotlin
  can't embed it as source. A prebuilt, published host library would remove
  that; it isn't published.
- **The theme** is Material 1: your Material 3 theme doesn't reach the Keliver
  screen.
- **Dependency versions.** Compose, OkHttp, Coil and the rest resolve together
  with your app's. From 0.3.15, `--embed` prints the module's dependency floor
  beside your app's declared versions before it writes, and says which of
  yours Gradle will RAISE, which strict or forced pins are below the floor,
  and whether your Kotlin is too old (`templates/production-host-embed/dependency-floor.json`
  holds the full resolved list). A raised app is built in CI, not run.
