# Adoption retrospective — a brownfield product proof (2026-10)

**What this is.** In October 2026 Keliver was put into two existing production apps (one Android,
one iOS) to replace one WebView feature with native, server-driven screens. It was a **product
proof**: it was never shipped, and the apps' own repositories were not pushed. This document
records what it cost, what broke, and what it measured, in general terms. No code or data from
those apps is in this repository.

It is the first time Keliver met an app that **we did not write**: an existing codebase with its own
Kotlin and Compose versions, its own network stack, auth, analytics, navigation and release
flavours. Every earlier adoption gate in this repo (`docs/REFERENCE_APP.md`, `sample/`) was
dogfooding. Read this alongside
[`ROADMAP.md` — Adoption findings (2026-10)](ROADMAP.md#adoption-findings-2026-10--pick-work-from-here-first),
where each finding below becomes a prioritised item. It runs real devices, but debug builds of a
proof app, so it does not close `DELIVERY_PLAN.md` G11 (release-signed app, external adopter).

## The proof in one paragraph

A transactional web feature (13 routes, with lists, bottom sheets, forms, status pages and
hand-offs to third-party web pages) was rebuilt as one Keliver guest:
13 portal screens, all ingesting with **0 RawCode**, 42 unit tests, published as signed bundles
through the portal relay (v1 → v10 over two days). Each app got a production host scaffolded by
`keliver-new-production-host.sh --embed` (tools 0.3.9), a seam at the point where the app opened
the web page, and a fallback to that web page when no bundle can load. Both hosts were reviewed
independently; every blocking finding was fixed. On devices (Samsung SM-S938B, Android 17; iPhone 13)
the screens ran from test data end to end, and on Android also against the live backend, read-only.
Nothing that commits a transaction or starts a third-party flow was ever run.

## What it cost

| | Android | iOS |
|---|---|---|
| Lines added (all files, before the measurement commits) | 3,974 | 3,811 |
| Host module (scaffolded, then edited) | 2,911 (`KeliverHost` 848, bundle index 300, widget overrides 206, screen activity 148, …) | 2,755 (host 796, bundle index 300, HTTP 257, widget overrides 206, …) |
| App-side glue (bridge delegate, web-result screen, seam, settings, debug switches) | ≈ 900 | ≈ 990 (Swift) |
| Existing app files touched only because of a dependency upgrade | **17** (+45 / −38 lines), plus a new 59-line pull-to-refresh compatibility shim | 0 |

The guest itself was ≈ 7,000 lines (1,700 screens, 3,300 presenter/logic, 2,000 components),
plus ≈ 500 lines of tests. It was scaffolded at 05:11 (its first commit), and its eighth bundle was
running on both phones through both hosts the same morning. **The guest was the cheap part.** The two hosts and their glue cost
more lines than the guest, and that is where every serious defect was:

1. **No fallback.** The first hosts showed the host's own message ("No bundle", a server address)
   when no bundle could load. The fix had to be written twice: open the original web page, with
   the original arguments, whenever the host fails — before or during the screen.
2. **The order block could be bypassed.** The guest's generic `request` was checked by path
   prefix; the reviews required an exact `(method, path)` allow-list in each host, with the one
   state-changing POST allowed only in production release builds.
3. **Token-leak risk.** A host forwarded any URL the guest asked for through the app's
   authenticated client. Fixed by pinning the scheme, host and port to the API base URL and
   refusing everything else before any network call.
4. **Developer text in release builds.** Host messages ("Refusing to load: …") reached users.
   Release builds now show a plain loading line or a friendly failure line.
5. Smaller, all host-side: `intent://` links opened without the browser's rules; an exported
   debug receiver without a permission; route query parameters passed through unfiltered; a
   re-delivered intent's new route ignored; the release bundle origin left on `http://`.

None of these are guest bugs, and none are things a single adopter should have to discover. The
scaffolded host is a **template you fork and edit**; after editing, each copy is its own security
boundary, and both copies needed the same five fixes.

## The Kotlin version gap

The Android app was on Kotlin 2.1. Keliver 0.3.3 needs Kotlin 2.2. The Android host therefore had to be
**its own Gradle build** (Gradle 9, AGP 8.12, Kotlin 2.2) producing an AAR that the app consumes,
so the app kept its compiler. That worked, but the AAR's runtime dependencies still forced
app-wide upgrades of what the app resolved: **Compose 1.7.8 → 1.8.2, material3 1.2.1 → 1.3.2,
OkHttp 4.12 → 5.1**, and a second image library (Coil 3 next to the app's Coil 2). Seventeen
unrelated files had to change (an experimental pull-to-refresh API was removed, Material's sheet
`windowInsets` and text-field colour parameters were renamed), and the whole app needs a
regression pass that has nothing to do with the feature. On iOS the host is a separate
Kotlin/Native framework, so there was no gap to bridge — at the price of a second Gradle build
inside the Xcode build (an incremental Xcode build with a host change took ≈ 2 minutes).

A team evaluating Keliver will see the dependency bump before it sees a screen.

## Widget gaps (each worked around in host-side widget overrides)

Both hosts carry the same 206-line override file that wraps `keliver-material`'s Compose widgets:

| Gap | What happened | Workaround | Library fix |
|---|---|---|---|
| `BottomSheet` opens half-expanded | The purchase sheet was cut off above its stepper and button | `skipPartiallyExpanded = true`, the theme's surface colour, a slide-out on close, one dismiss per show | 0.3.4 |
| `Theme` starts from `lightColorScheme()` | Every colour role the guest does not set came out Material purple (slider tracks, containers) | Start from the host's `MaterialTheme.colorScheme` | 0.3.4 |
| Children kept by index | A new page inherited the previous page's scroll position; a field's text could follow its index | `key(widget)` wrappers on Column, Row, ScrollableColumn and the text fields | **fixed in #103** (merged, unreleased) |
| Lazy lists and pagers keyed by index | Same class of bug as above, not yet hit | none | 0.3.4 |
| Text fields cannot set content alignment | A centred quantity field could not be centred | none (design changed) | 0.3.4 |
| A `ScrollableColumn` with unbounded height crashes at runtime | Two early bundles crashed on device | documented in the guest | 0.3.4: catch it at ingest, or render it bounded |

## The bridge

The guest talks to the app through one `ZiplineService` contract (11 calls: context, request,
web-for-result, HTML-for-result, open URL, track, haptic, toast, share, download, close). It was
**hand-copied, byte-identical, into three repositories**, with a hand-written Kotlin delegate on
Android and a hand-written Swift delegate (through a Kotlin adapter) on iOS. The request
allow-list — the most security-relevant part — exists in two hand-written copies and only exists
at all because review asked for it. One contract should generate both sides, and the allow-list
should be part of the contract.

## Testing without a UI driver on iOS

There is no sanctioned way to tap through an iOS app from a script here, so iOS was checked by:

- **`?demo=<state>` routes** in the guest (test-data mode only) that open a sheet or a state
  directly;
- **launch-environment switches** in the debug build (native on, test data, route to open,
  orders off) passed through `devicectl`;
- **an in-app snapshot hook** writing PNGs of the key window, copied off the device.

It worked, but every piece was improvised. The test data itself was **written by hand** (a fake
API with a fixed 450 ms delay) even though the relay already records and replays HTTP — the
guest had no route to use it from a device.

## Dev-loop friction

- **The first publish after any other Gradle build fails** ("Internal compiler error" in the
  Kotlin/JS link) and the retry succeeds. Reproduced on 2026-10-14 after a `jsNodeTest` run; the
  cause is not diagnosed (the relay keeps only the last 30 lines of Gradle output, so the stack
  trace was lost).
- **The relay reads its config once, at start.** Switching the publish task from the Development
  to the Production bundle needed a relay restart. Keliver's own default `publishTask` is a
  Development bundle, so an adopter who never changes it ships unminified bundles (see below).
- **A new bundle needs an app restart.** Hosts use next-launch updates; there is no way to see a
  change on a running device without killing the app.
- **Code loading starts only when the first screen opens.** Starting the host at app launch did
  the bundle lookup only; Zipline began fetching and evaluating code when the screen was shown.
- **A phone needs a tunnel to reach the relay.** iOS asks for Local Network permission, which an
  unattended run cannot accept, so the iPhone fetched bundles through a quick tunnel to a
  GET-only proxy of `/bundles/*` (never the relay itself: it has write endpoints). Quick tunnels
  expire; each new URL meant an iOS rebuild, because the debug bundle origin is compiled in.

## Measurements

All on 2026-10-14/15, on **debug builds** of both apps (debuggable Compose and Kotlin are slower
than release; the WebView is not affected, so native-vs-web comparisons below lean against
native), with orders off, in test-data mode (a fake API that answers after 450 ms) unless a row
says live. Sizes are in MB of 10^6 bytes unless marked MiB. Times are
from the moment the screen was asked for. "Shell" is the first frame after the guest started;
"content" is the frame after the screen's first data was composed (the guest's
"page viewed" analytics event). Raw logs, scripts and method:
`metrics/` next to the proof (not in this repository).

### Bundle size

| | Development (published v1–v9) | Production (v10) | Web feature (for comparison) |
|---|---|---|---|
| Raw | 4.28 MB, 41 modules | 2.36 MB, 41 modules | 1.03 MB entry + 0.98 MB in 52 lazy chunks |
| Compressed | 1.57 MB (gzip -9) | 0.98 MB (gzip -9) | 0.33 MB entry + 0.34 MB lazy (brotli, as served) |
| Largest module | the guest, 845 KB | the guest, 717 KB | the entry chunk, 679 KB |

Compose runtime (435 KB), Kotlin stdlib (251 KB), coroutines (151 KB) and serialization (239 KB)
make up most of the rest of a Production bundle. A one-line guest change re-downloaded **two**
modules (the guest and stdlib, 1.4 MB raw in Development), not one.

### Time to first content

iPhone 13 (5 cold launches each, median; the bundle came through a quick tunnel, modules from cache):

| | v8 Development | v10 Production |
|---|---|---|
| Code loaded (lookup done at launch; manifest fetched on open; includes tunnel latency, 0.33 s vs 0.17 s) | 0.75 s | 0.34 s |
| … of which module evaluation (41 modules) | 0.31 s | 0.17 s |
| Shell | 0.82 s | 0.40 s |
| Content, **cold** (first open in the process) | 2.41 s | 1.82 s |
| Content, **warm** (second open, same process) | 1.66 s | 1.52 s |

Where a warm open's 1.5 s goes (v10): the screen and guest start in ≈ 0.12 s; then ≈ 0.55 s of
composing the first route in QuickJS before the data call starts; then the 450 ms fake network;
then ≈ 0.4 s until the content's frame. **About one second of a warm open is the guest's own work
between frames** — composition in QuickJS (an interpreter), the protocol, and the host applying
the changes; the trace does not split it further. Minifying (Production) halved module evaluation
and helped the rest only a little.

Samsung SM-S938B (5 cold launches each, median; v10 Production; bundle from the relay over
`adb reverse`; native against the **web page it replaced**, both reading the live backend
read-only, the web page with its assets already in the WebView's HTTP cache):

| | Code loaded | Shell / FCP | Data | Content (web: LCP) |
|---|---|---|---|---|
| Native, test data, cold | 0.42 s | 0.51 s | 1.23 s | 1.49 s |
| Native, test data, warm | – | 0.08 s | 0.74 s | 1.00 s |
| Native, live, **cold** | 0.50 s | 0.61 s | 1.10 s | **1.51 s** |
| Native, live, **warm** | – | 0.11 s | 0.39 s | **0.63 s** |
| Web, live, **cold** | – | 0.58 s | 0.95 s | **1.11 s** |
| Web, live, **warm** | – | 0.31 s | – | **0.61 s** |

The web's content time is the later of its last LCP entry and the data response (in three of
five cold runs LCP came before the data); warm, the page did not refetch, so it is LCP alone.

**The native screen was not faster than the cached web page.** It was ≈ 0.4 s slower on a first
open (half a second of code loading, then the data call starts later than the web's), and the
same on a reopen. These are debug builds; a release build would narrow the cold gap, by an
amount not measured here. The guest's composition on Android costs ≈ 0.2 s before the data call and
≈ 0.25 s after it; on the iPhone ≈ 0.55 s and ≈ 0.4 s. The iPhone web page was not measured
(without a signed-in session it would have called a production token endpoint).

### Over-the-air update

- A one-line change published as v9 reached the iPhone on its **next launch**: code ready 1.75 s
  after the screen opened (0.72 s manifest, two changed modules downloaded in parallel).
- The Production build (v10; about 20 of its 41 modules differed from what the phone had, and an
  interrupted earlier launch had already fetched 6 of them) was ready 2.1 s after the screen
  opened: the manifest and 14 modules, three requests at a time, ≈ 0.3 s each through the
  tunnel. A download is **latency-bound**: one request per module, three in flight.
- Propagation is therefore "publish → the user's next cold launch of the app → the screen
  opened". There is no faster path today (no on-resume check was enabled here, and no push).

### Memory

| | Before the screen | Content (cold) | Content (warm reopen) |
|---|---|---|---|
| iPhone 13, footprint, v10 (median) | 94 MB | 170 MB | 185 MB |
| Samsung, native: app PSS (median) | 401 MB | 373 MB | 411 MB |
| Samsung, web page: app PSS (median) | 400 MB | 491 MB | 494 MB |
| Samsung, web page: WebView renderer processes | 227 MB | 300 MB | 319 MB |

On Android, the native screen costs ≈ 10 MB of app PSS (whole-app PSS moves ±30 MB between readings),
where the web page cost ≈ 91 MB in the app process and ≈ 73–92 MB more in the WebView's renderer:
**memory is where native clearly wins on Android.** On iOS the first open costs **≈ 76 MB** of
footprint and a second open adds ≈ 15 MB more without the first being released. The app had no
Compose Multiplatform before, so this includes starting that runtime (Skia, Metal) as well as
QuickJS and the guest; it is not the per-screen figure `PERFORMANCE.md` proposes a budget for, and
it needs breaking down.

### App size

Android (R8 release APKs of the commit before the feature and the branch, built the same way):
**+3.55 MB** universal APK, **≈ +2.1 MB** for an arm64 device — dex +1.23 MB compressed
(+3.7 MB raw; this includes the forced Compose and OkHttp upgrades, not only Keliver) and QuickJS
+0.84 MB on arm64 (`libquickjs.so`; 0.54 MB armv7, 0.86 MB x86_64).

iOS (Release build with a link map; the bytes the linker kept from the host framework):
**37.4 MiB** of the 92 MiB linked into the app binary, ≈ **14 MB** compressed (an estimate at the
binary's own compression ratio). Almost all of it is Compose Multiplatform's iOS stack (Skia,
HarfBuzz, a 6.3 MB ICU data object) and Kotlin/Native code; Keliver's own share was not separated.
On iOS, Keliver brings a UI toolkit with it; on Android the toolkit is already in the app.

## What we would tell the next adopter

1. Budget for the host, not the screens.
2. Check the Kotlin and Compose versions first; the dependency bump is the first cost you pay.
3. Publish Production bundles; Development is the default.
4. Write the request allow-list on day one.
5. Plan device testing on iOS before you need it.
6. Measure against the page you replace. On Android a cached web page was faster than the native
   screen on a first open and equal on a reopen; native won on memory, not on time.

## What changes in Keliver

Prioritised in [`ROADMAP.md`](ROADMAP.md#adoption-findings-2026-10--pick-work-from-here-first):

1. **Library 0.3.4** — the widget gaps above, each with a test.
2. **Brownfield host kit** — the fallback, a status API, user-safe loading and error states,
   routes and close, and a documented recipe for the Kotlin gap.
3. **Bridge generator** — one contract → the Kotlin and Swift sides plus the request allow-list.
4. **Device state catalogue** — named states, on-device snapshots, record/replay data instead of
   hand-written fakes.
5. **Dev loop** — reliable first publish, config reload, an in-process update, a device route
   that needs no tunnel.
6. **Startup and memory** — start loading code at host start, measure where composition time
   goes in QuickJS, and find the iOS memory.
