# Three-way benchmark: native Compose, a Keliver guest, a web page

The question: on a low-end Android phone, how long do the same screens take as **full native**
(Compose), as a **Keliver guest** (Kotlin/JS in QuickJS, rendered by the host's widgets) and as a
**web page** in a WebView: on cold start, on a later navigation between screens, and when the screen
calls an API and shows the result? And when guest code makes an API call, what is the flow, and where
does the time go?

Phone: a low-end Android phone (Galaxy A12, Android 12), 60 Hz, on wireless adb, 2026-11-15.
Prototype code only (`-PkeliverComposePrototype`), as Gate B's ([GATE_B_RESULTS.md](GATE_B_RESULTS.md)).

**In one paragraph.** On this phone a native screen opens in about 1.2 s from a cold start and every
later step feels instant: a navigation is drawn about 15 ms after the tap, an API call the time of
the network plus about 45 ms (small) to 80 ms (large). The web page starts slower than native (about
2.0 s: the WebView itself costs about 0.7 s before the page's first frame) but is close to native on
later steps: 40–50 ms for a navigation, network plus 50 ms for a small response; the page as
measured slows down with a large list (about 0.45 s to lay out and paint 1,000 rows, because it does
not virtualise them; one CSS line, `content-visibility`, brings the large call to 0.47 s, see
Experiments). The
Keliver guest is the slowest of the three at every step on this phone: about 2.9 s to its first
content (warm code cache), **0.44 s to open a detail and 1.1 s to go back to the list**, network plus
about 0.17 s for a small response and network plus about 1 s for a large one, nearly all of it
parsing 224 KB of JSON with kotlinx.serialization inside QuickJS (0.8 s). The bridge itself is cheap
(2–3 ms out, 10–20 ms back with the body). What costs is guest code running in QuickJS on a slow CPU.

**Measured since (the experiments, same phone, same day; [Experiments, measured](#experiments-measured)):**
part of that is fixable and part is not.
- **The large response is fixable without a library change.** Parsing with QuickJS's own `JSON.parse`
  plus a hand mapping takes 38 ms instead of 806, and the guest's large API call drops from about
  1.2 s to **about 0.4 s**. That is faster than the web page as built (0.71 s) and slower than native
  (0.27 s).
- **Navigation is partly fixable.** Back to the list splits into 3% waiting for the guest's frame,
  about 40% recording the changes for the host, and about 60% Compose itself running in QuickJS.
  Composing a 10-row first window instead of 16 (a one-line library change) takes back from 1.03 s
  to **0.70 s** and open from 0.42 s to **0.32 s**. Not sending default properties saves nothing:
  the defaults were cheap to encode, and the cost is encoding real values (about 1 ms per modifier,
  0.2–0.35 ms per property). Even with every fix measured here, a guest navigation on this phone
  takes about 0.3–0.7 s, against 10–15 ms native and 40–60 ms web. What is left is Compose's own
  work in an interpreter (about 0.2–0.4 s) and the encoding, which only a cheaper encoder or a
  faster CPU would shrink.

- **Scrolling (runs 2 and 3 of 2026-11-16; [Scrolling, run 3](#scrolling-run-3-the-first-speed-with-placeholders-guest),
  [native and web](#scrolling-run-3-native-and-web-against-the-guest), [run 2](#scrolling-run-2)).**
  The guest's list shows **placeholders from 2,133 dp/s** at release (one or two rows at the
  leading edge, filled 35–120 ms after the list stops); **from 2,844 dp/s 8–12 of the 14 visible
  rows are blank** flinging down, and content comes back 2.3–2.9 s after the list stops (4.8 s
after a 6,667 dp/s fling up). At 1,422 dp/s there
  are none. While it moves, the guest misses a median of 1 vsync per fling up to 2,844 dp/s, like
  native (0–1); flinging down at 3,556 dp/s it misses 8 (native 1) and at 6,667 dp/s 15 (native
  4). Web missed none (counted on the app window's frames; Chromium's own renderer is not seen).
  Native has no placeholders by construction; the web form's blank areas are not visible to the
  route. The first run (old route, superseded) found the same blank list at 6,000–7,000 dp/s.
- **A heavy screen (102 widgets; [A heavy screen](#a-heavy-screen-opening-warm)).** Warm open,
  median: native **22 ms**, guest **1,042 ms**, web **48 ms**; about 85% of the guest's open is its
  own frame in QuickJS.
- **GC threshold ([The GC threshold](#the-gc-threshold)).** Raising or disabling QuickJS's
  collection threshold has no measurable effect on the median navigation or API time; each
  collection under the default adds about 100–130 ms to the one iteration it lands in (inferred,
  not timed).

| Median (ms) on the A12 | native | guest | web | guest, experiment: 10-row window + `JSON.parse` | web, experiment: `content-visibility` |
|---|---|---|---|---|---|
| Cold start to the list's data drawn (API call included) | **1,235** | **2,910** | **2,040** | not measured | not measured |
| Open a detail (tap → drawn) | **16** | **438** | **37** | 327 | 36 |
| Back to the list (tap → drawn) | **13** | **1,082** | **50** | 703 | 64 |
| Tap → API (20 items, 4.5 KB) → drawn | **177** | **306** | **214** | 311 | 241 |
| Tap → API (1,000 items, 224 KB) → drawn | **277** | **1,216** | **716** | **389** | **465** |

The first three columns are the as-shipped series of the morning's first sessions. The experiment
columns come from a later session (06:13–06:39 IST), whose own as-shipped series reproduced those
within 5% (below).

The fixed network part (the server's 100 ms delay plus the adb tunnel) is in every API and cold-start
number: about 130 ms for the small response and 180–230 ms for the large one, in all three forms.

## What was built (prototype only)

| Piece | Where |
|---|---|
| The two screens, written once as plain Compose: a **list** of orders from the API (title, subtitle, amount, a coloured status chip; text only) with two reload actions (20 or 1,000 items), and a **detail** opened by tapping a row (from data already loaded: navigation only, no request) | `sample/threeway-guest/src/jsMain/…/ThreewayScreens.kt` |
| The guest form: that file compiled against Keliver's Compose-generated API (`dev.keliver.compose.*`, the Gate A/B group), fetching through the host's `HostHttp` as an adopter's guest does (D13: the guest owns the endpoint, the types and the parsing), parsing with kotlinx.serialization in QuickJS | `sample/threeway-guest` (bundle signed with the sample's demo key, in the APK's assets) |
| The native form: **the same file**, imports rewritten from `dev.keliver.compose.` to `androidx.compose.` by a Gradle task (only imports differ), OkHttp on an IO thread, the same kotlinx.serialization parser on a Default thread | `sample/host-android/build.gradle.kts` (`threewayNativeScreen`) |
| The web form: plain HTML, CSS and JavaScript (no framework), the same layout (header 56, actions 48, status line 24, rows 72: CSS px = dp at `initial-scale=1`), `fetch()` and `JSON.parse`, the list rendered with `innerHTML` (all rows: a plain page has no virtualised list) | `sample/host-android/src/threeway/assets/threeway-web/index.html` |
| One route, `ThreewayActivity`, choosing the form by the `form` extra (`native`, `guest`, `web`), with the timing marks and an in-app tap driver (`bench` extra) | `sample/host-android/src/threeway/kotlin/…/ThreewayActivity.kt` |
| The data model and parser (shared by native and guest), the guest's app service, a benchmark-only host service the guest reports its marks to | `sample/threeway-guest/src/commonMain` |
| Cold start: Macrobenchmark `ThreewayStartupBenchmark` (StartupTimingMetric, COLD, 10 iterations per form) | `sample/benchmarks/src/gateb/…` |
| The fixture API: a scratch Python server on the Mac, `GET /items?size=small\|large&seq=N`, a **fixed 100 ms delay** before every response, HTTP/1.1 keep-alive, the same bytes for every form (small: 20 items, 4,473 B; large: 1,000 items, 224,145 B), reached by the phone through `adb reverse` | not in the repository (benchmark scratch) |

### Why it is fair, and where it is not

- **Same screens, same data, same network.** One JSON, one server, one fixed delay, one tunnel. Native
  and guest are the same Compose source; the web page reproduces the layout by hand.
- **Same app, same build, same compilation.** One APK, the `benchmark` build type (release-shaped,
  not debuggable, signed with the debug key; **not minified**: the sample's `benchmark` type sets
  `isMinifyEnabled = false`, as in Gate B's runs), no app baseline profile of its own; the libraries'
  bundled baseline profiles through profileinstaller, compiled `speed-profile` by Macrobenchmark's
  default (`CompilationMode.Partial`) for the cold start. The in-app series ran afterwards on the same
  install. The WebView (system Chromium) is used as installed.
- **Same client for native and guest.** The guest's `HostHttp` runs on the app's OkHttp client (one
  instance for both forms), a minimal version of the production-host template's `OkHttpHostHttp`
  (same base and thread hop; no request policy). The web form uses Chromium's own network stack.
- **Not equal, by design of each form:**
  - The guest's and native's lists are lazy (only visible rows are composed); the web page builds
    all rows. With 1,000 items this costs the web form layout and paint time that a virtualised web
    list would not.
  - Native parses on a background thread; the guest parses in QuickJS, on the one thread its code
    runs on; the web page parses in the renderer's main thread with `JSON.parse` (native code).
  - The guest's code comes from the APK's assets and Zipline's cache, never the network: it is the
    best case for a guest (see Gate B for the first start after an install or an update).
- **Taps are synthetic.** The driver dispatches a DOWN and, 40 ms later, an UP `MotionEvent` to the
  activity's window at the screens' fixed positions; the time starts at the UP (the click). The
  kernel and InputDispatcher hops of a real finger are not included, for all three forms alike.

## Method

**"Drawn" (the end of every measurement).** Each form reports when its content is in its own tree,
and the route takes the **next draw of the window** after that as the frame showing it (a
`ViewTreeObserver.OnDrawListener`, main thread):
- native: a `DisposableEffect` in the screen (the composition's apply phase) → the same frame's draw;
- guest: the same `DisposableEffect` runs in QuickJS and makes one bridge call (`composed`, carrying
  the guest's marks), then the guest sends that frame's change batch; when the host's `sendChanges`
  returns, the batch is queued on the main thread; a message posted after it hands the applied
  changes to the host's composition (`Snapshot.sendApplyNotifications()`) → the next draw;
- web: the page assigns `innerHTML`, then `requestAnimationFrame` and a task after it (the page has
  painted) call a JavaScript interface → the next draw of the app's window (which draws the WebView's
  new frame).

The host's draw happens on the UI thread before the RenderThread and the display, so "drawn" is one
frame (16.7 ms at 60 Hz) or a little more before light leaves the screen, in all three forms.

**Marks.** Each form records marks in epoch milliseconds on the phone's one wall clock: the host
with `System.currentTimeMillis()`, the guest and the page with `Date.now()`. Results are relative
to the tap.

| Mark | native | guest | web |
|---|---|---|---|
| `click` | the screen's `onClick` (main thread) | the screen's `onClick`, in QuickJS (after the event crossed into the guest) | the page's click handler |
| `request` | the request starts (in the screen's `LaunchedEffect`, before the IO hop) | the same, in QuickJS, before calling `HostHttp` | before `fetch()` |
| `hostIn`, `hostNet`, `hostOut` | — | the host's `HostHttp`: the call arrived from the guest; the body has been read (OkHttp); returning | — |
| `response` | body read (IO thread) | the response is back in QuickJS | `response.text()` resolved |
| `parsed` | kotlinx.serialization done (Default thread) | kotlinx.serialization done (QuickJS) | `JSON.parse` done |
| `composed` | the screen's `DisposableEffect` | the same, in QuickJS | `innerHTML` assigned |
| `batchSent` / `painted` | — | the host's `sendChanges` returned | `rAF` + task |
| `drawn` | the next window draw (above) | the same | the same |

**Series.**
1. **Cold start** (Macrobenchmark, `StartupMode.COLD`, 10 iterations per form): the route opened with
   the small list; `reportFullyDrawn()` is called inside the draw that shows the list's data (above),
   so time to full display is "process start to the list's data on screen", the API call included.
   Time to initial display is the activity's first frame.
2. **Navigation** (in-app driver, `bench=nav`): the route already running on the small list; tap a
   row → the detail drawn ("open"); tap Back → the list drawn again ("back"). 700 ms between taps.
3. **Tap → API → rendered** (`bench=api-small`, `api-large`): tap "Load 20" or "Load 1,000" → the
   request, the response, the parse, the list re-drawn with the new response (each response carries
   its request number, shown on the status line). 0.8 s (small) or 1.5 s (large) between taps.

Raw output (logcat lines, Macrobenchmark output, the phone's battery readings, the fixture's request
log) is kept in the benchmark's scratch directory, not in the repository.

## Results

**Phone state.** At the start of the session the phone read 74%, unplugged, 26.1 °C. It was on its
charger (AC) for every measured series below: 96% → 100%, 26.6–26.9 °C, thermal status 0, no power
saving (`dumpsys battery` before each cold-start run; the route's own reading at the start of each
in-app series). This differs from the plan (on battery) and from Gate B's cold start (on battery).
Wireless adb dropped once, for about 30 minutes, during the first cold-start run; the forms were then
re-run one at a time (below).

### 1. Cold start (Macrobenchmark, 10 iterations per form, ms)

Session 2 (after the reconnect): native, then guest, then web, each run separately, 2026-11-15.
Medians with p90 (nearest rank, the 9th of 10) and min–max.

| Form | Time to full display (list data drawn): median | p90 | min–max | Time to initial display: median | p90 | min–max |
|---|---|---|---|---|---|---|
| native | **1,235** | 1,348 | 1,212–1,357 | 874 | 900 | 852–917 |
| guest | **2,910** | 4,494 | 2,716–8,448 | 710 | 931 | 660–4,076 |
| web | **2,040** | 3,216 | 1,916–4,097 | 1,553 | 2,533 | 1,524–3,523 |
| guest − native | +1,675 | | | −164 | | |
| web − native | +805 | | | +679 | | |

Earlier runs of the same benchmark (same day, same state, per-iteration values not kept): native
1,326 median (1,259–1,808) before the adb drop; guest 2,816 (2,741–8,678) and web 2,110
(1,953–4,641) right after it.

- **The guest's iteration 0 started with an empty code cache.** Macrobenchmark clears the app's
  data before a series, so the first iteration read all 53 modules from the APK's assets (4,494 ms;
  the route's log says `modulesFromAssets=53`); iterations 1–9 loaded them from Zipline's cache
  (`modulesFromAssets=0`). Its median is a warm-cache start; without iteration 0 it is 2,870. Every
  user gets the iteration-0 start once after an install or an update (Gate B: +0.36 to +0.87 s).
- **Iteration 1 was an outlier in the guest's and the web's series** (guest 8,448, initial display
  4,076; web 4,097 and 3,523; the earlier guest and web runs had the same pattern), already before the
  first frame, so in the process or the system rather than the form (not investigated; the native
  series did not show it). p90 and the maximum carry it; the medians do not.
- **What each start includes.** All three wait for the same API call (the fixed 100 ms plus the
  tunnel). Native starts it in its first composition; the guest only after its code has loaded and
  its first composition has run, so its network time adds to its code load; the web page after the
  WebView has started and loaded the page from assets.
- **Initial display.** The guest's first frame is its empty host surface (as in Gate B), so it comes
  before native's, whose first frame already has the screen's header. The web form's first frame
  comes about 0.7 s after native's: creating the first WebView in a process loads Chromium.
- Gate B's native/guest pair on this phone was 919 / 2,581 ms for an animation screen with no API
  call (+1,662); this is +1,675 for a list with one.

### 2. Navigation (in-app driver, 12 opens and 12 backs per form, ms from the tap)

| Form | Open a detail: median | p90 | min–max | Back to the list: median | p90 | min–max |
|---|---|---|---|---|---|---|
| native | **16** | 17 | 14–30 | **13** | 13 | 12–13 |
| guest | **438** | 453 | 417–461 | **1,082** | 1,117 | 1,016–1,123 |
| web | **37** | 48 | 31–86 | **50** | 51 | 47–54 |

Navigation is a state switch in all three forms (the list or the detail replaces the other in the
same window), with no transition or animation.

Where the guest's time goes (medians): the click reaches the guest's `onClick` 8 ms after the tap;
the guest's composition is applied 370 ms (open) or 951 ms (back) after the click; its change batch
reaches the host 31 / 91 ms later; the host draws it 24 / 14 ms after applying it. Going back builds
the list screen again from nothing in the guest: the page has 20 items, about 9–10 of them visible,
and the guest's `LazyColumn` composes a first window of **16 rows** (the lazy-layout loading
strategy's default: the first visible row plus 15 preloaded; `ScrollOptimizedLoadingStrategy`),
where native's `LazyColumn` composes the ~9–10 that fit. Every new widget's every property is then
recorded for the host: each row is 8 widgets (a row, two columns, a box, four texts), each text sends
its 14 serialised properties whether or not they are defaults (encoded with kotlinx.serialization's
`encodeToDynamic` in `FastGuestProtocolAdapter`, treehouse-guest's JS adapter), so the back step
records on the order of a thousand property and modifier changes. Opening composes a dozen texts.
Native does the same recomposition in 9–12 ms on ART.

**The 370 / 951 ms is composition plus protocol encoding, now split** (the `split` experiment,
below). Going back: about 27 ms waiting for the guest's next frame, then about 390 ms recording the
1,111 property changes and 145 modifiers of 145 new widgets for the host, and about 570 ms of Compose
itself in QuickJS. Opening: 23 ms of frame wait, about 70 ms of recording, about 290 ms of Compose
(this includes disposing the list's rows). See [Experiments, measured](#experiments-measured).

### 3. Tap → API → rendered (12 per form and size, ms from the tap)

**Small: 20 items, 4,473 B.**

| Form | Total: median | p90 | min–max | Tap → click | Click → request | Network | Bridge out / back | Parse | Parsed → drawn |
|---|---|---|---|---|---|---|---|---|---|
| native | **177** | 232 | 159–240 | 4 | 10 | 133 (OkHttp, IO thread) | — | 8 (ART, Default thread) | 20 |
| guest | **306** | 323 | 275–325 | 8 | 38 | 130 (host OkHttp) | 3 / 10 | 15 (QuickJS) | 85 |
| web | **214** | 308 | 182–316 | 13 | 0 | 156 (fetch) | — | 1 (`JSON.parse`) | 49 |

**Large: 1,000 items, 224,145 B.**

| Form | Total: median | p90 | min–max | Tap → click | Click → request | Network | Bridge out / back | Parse | Parsed → drawn |
|---|---|---|---|---|---|---|---|---|---|
| native | **277** | 292 | 223–293 | 3 | 8 | 198 | — | 33 | 19 |
| guest | **1,216** | 1,472 | 1,145–2,106 | 8 | 37 | 232 (one 957) | 2 / 21 | **803** | 104 |
| web | **716** | 772 | 647–945 | 43 | 0 | 182 | — | 6 | **463** |

The columns are medians of each step on its own, so they do not add up exactly to the total's
median. "Parsed → drawn", split (medians):
- native: composition 18 ms (small) / 18 ms (large), drawn in the same frame;
- guest: the guest's composition 67 / 70 ms, its batch handed to the host 8 / 13 ms later, the host
  draws 10 / 19 ms after applying it;
- web: building the DOM 4 / 77 ms (`innerHTML` of all rows), then style, layout and paint until the
  next frame 30 / 374 ms, the app draws the WebView's frame 12 / 10 ms later.

Reading them:
- **Small responses: the network dominates every form.** Over the same 130–156 ms of network, native
  adds about 45 ms, web about 60 and the guest about 175 (38 ms before the request starts: the guest's
  `LaunchedEffect` runs on its next frame; 85 ms after the parse: its recomposition and the batch).
- **Large responses: each form pays somewhere different.** Native's parse on a background thread
  costs 33 ms. The guest's costs 803 ms: kotlinx.serialization decoding 224 KB in QuickJS (no JIT) on
  this CPU, after Zipline has already decoded the response once to bring the body across (part of
  the 21 ms back). The web form parses in 6 ms (`JSON.parse` is native code) but spends 0.45 s laying
  out and painting 1,000 rows, because the page builds them all; a virtualised web list would not.
  The page also pays at the tap: tap → click is 13 ms on the first large iteration, then 39–46 ms on
  every later one, once the DOM holds 1,000 rows (the renderer's main thread handling the input
  against the big document), so **the large web number overstates what a competent page costs**.
  Measured since: with `content-visibility: auto` on the rows (`webcv`), the large total falls to
  465 ms (layout and paint to 149 ms). Tap → click stays at 44 ms, because the 1,000 rows are still
  in the DOM; a page that also virtualised its DOM would lose that too (not built).
- **The bridge is not where the guest's time goes**: 2–3 ms out, 10 ms back with 4.5 KB, 21 ms back
  with 224 KB (one 179 ms outlier, in the same iteration as the network outlier below).
- One guest iteration's network took 957 ms (the large set's second, with the 179 ms bridge-back
  outlier); the native and web sets had no such outlier, and the smoke run before had one of 1.7 s
  on the same step. The tunnel over wireless adb is the likely cause (not checked against the
  fixture's log timestamps).

Scrolling was measured in a later session: see [Scrolling, run 2](#scrolling-run-2).

## The guest's API call, step by step (as measured)

1. The user taps. The host's Compose `clickable` (a generated renderer) sees it on the main thread and
   sends the event into the guest: a bridge call into QuickJS (`click`: about 7–10 ms after the tap).
2. The guest's `onClick` changes state (`request += 1`). The guest's `LaunchedEffect(request)` runs on
   its next frame (the host sends frames to the guest; `request` mark).
3. The guest calls `HostHttp.execute(HostHttpRequest("GET", "/items", query))`: Zipline encodes the
   request as JSON and calls the host's `HostHttpProvider` (`hostIn`: 2–3 ms after `request`).
4. The host's provider hops to `Dispatchers.IO` and runs the call on the app's OkHttp client; the body
   is read into a `String` (`hostNet`).
5. The response returns over the bridge: Zipline encodes the body as a JSON string, QuickJS decodes it
   and resumes the guest's coroutine (`response`).
6. The guest parses the body with kotlinx.serialization, in QuickJS (`parsed`).
7. The guest sets the new page; its next frame recomposes the list in QuickJS (`composed`), and the
   frame's changes cross to the host as one batch (`sendChanges`; `batchSent`).
8. The host applies the changes to its widgets on the main thread; its composition and layout run
   and the frame is drawn (`drawn`).

Measured (medians, ms from the tap; small / large response):

| Step | small | large |
|---|---|---|
| 1. tap → the guest's `onClick` | 8 | 8 |
| 2. → the guest's request starts (its next frame) | 46 | 45 |
| 3. → the call arrives on the host (bridge out) | +3 | +2 |
| 4. → OkHttp has the body (network, fixed delay included) | +130 | +232 |
| 5. → the body is back in the guest (bridge back) | +10 | +21 |
| 6. → parsed in QuickJS | +15 | +803 |
| 7. → the guest's composition applied; → its batch handed to the host | +67; +8 | +70; +13 |
| 8. → the host draws | +10 | +19 |
| total (median of totals) | 306 | 1,216 |

The same call from native code has steps 2 (10 ms), 4 (133 / 198), 6 (8 / 33, on a background
thread) and 7–8 (20 / 19) and nothing else; from the web page, steps 4 (156 / 182), 6 (1 / 6) and
7–8 (49 / 463).

## What this means

What a user would feel on a phone like this (100 ms is about where a delay becomes noticeable, a
second where it becomes a wait):

- **Native** feels instant everywhere except the network itself.
- **The web page** feels instant for navigation and small responses, starts about 0.8 s slower than
  native from cold, and makes the user wait about half a second extra for a long list as built
  (about 0.2 s with one CSS line, `content-visibility`, measured).
- **The Keliver guest** starts about 1.7 s slower than native (about 0.9 s slower than the web page)
  even with its code cached, and **every navigation inside the guest is noticeably slow on this
  phone**: as shipped, 0.44 s to open a simple detail and 1.1 s to go back to a list, against 15 ms
  native and 40–50 ms web. With the one-line library change measured here (a 10-row first window),
  that is 0.32 s and 0.70 s: better, still noticeably slow. A small API call costs about 0.13 s more
  than native, which a user would barely notice behind a network that is itself slower than this
  fixture's. A large JSON response costs about 0.9 s more as shipped, nearly all of it in
  kotlinx.serialization's string decoder; parsed with `JSON.parse` instead (adopter code, no library
  change), it costs about 0.1 s more than native and less than the web page.

**Said one way:** on a low-end phone the guest is slow wherever it builds a lot of UI at once, and
that is mostly not a bug that one change removes: after the fixes measured here, about 0.2–0.4 s of
every screen switch is Compose itself running in an interpreter. **Said the other way:** the guest's
network path is as fast as native, its data path can be as fast as the web page's with ordinary
code, a third of its "back" cost came from one fixable default (a 16-row first window), and about a
third of what remains (251 of 698 ms) is the protocol's encoder, which a cheaper encoder could cut (not measured); so the
as-shipped numbers overstate what the approach must cost.

Where Keliver is **equal**: the network path (the host's own OkHttp client; the bridge adds 2–3 ms
out and 10–20 ms back), and the host's own drawing once the changes arrive (10–20 ms, comparable to
the web form's last step). Where it is **slower**, as shipped: anything that runs guest code on a slow
CPU (QuickJS has no JIT): composition plus protocol encoding (70 ms for a list update, 370 ms for
opening a detail, 950 ms for re-entering a list; split below) and parsing with kotlinx.serialization's
string decoder (224 KB: 33 ms on ART, 803 ms in QuickJS, about 24 times slower). As shipped it is
**faster nowhere** on this phone; with `JSON.parse` its large API call (389 ms) beats the web page
as built (714) and with `content-visibility` (465). On the iPhone 13 Gate B measured the guest's
whole start at 180 ms, so a faster CPU should narrow all of this (not measured for these screens).

### Inherent vs fixable (measured, guest)

Inside the "Compose itself" and recording numbers below (a profile, the GC experiment, the suspects measured, engine options): [GUEST_PROFILE.md](GUEST_PROFILE.md).

| Cost (median, ms) | as shipped | after the fix measured | What it is | Fix, and what it needs in the library |
|---|---|---|---|---|
| Parsing 224 KB | 806 | **38** (`jsonparse`); 257 (`dynamic`) | kotlinx.serialization's string decoder in an interpreter; QuickJS's own C `JSON.parse` takes 25 ms of the 38 | **Fixable, no library change**: adopter code (`JSON.parse<dynamic>` plus a hand mapping). A helper in `keliver-http` would make it the default path; `decodeFromDynamic` keeps generated serializers but costs 232 ms |
| The lazy list's first window (back / open) | 16 rows: 1,031 / 421 | 10 rows: **698 / 324** | Composing, recording and (on open) disposing 6 rows the screen does not show | **Fixable, library change**: the prototype's `LazyWindow` default (`LazyDsl.kt`, `keliver-compose-gatea-compose`), better sized from the host's viewport (`sample/threeway-guest/experiments/lazy-window-10.patch` is the one-line version) |
| Recording the changes for the host (back, 10 rows / open) | 392 at 16 rows | 251 / 68 | kotlinx.serialization's `encodeToDynamic` per value: about 1 ms per modifier (≈1 element each), 0.2–0.35 ms per property | **Partly fixable, library change, not measured**: a cheaper encoder (generator-emitted direct encoders instead of `encodeToDynamic`); the most it could save is the whole line. **Not fixed by skipping defaults**: `skipdefaults` dropped 42% of properties and 64% of modifiers and saved nothing (the skipped values were nulls and empty modifiers, already nearly free; the skip's own bookkeeping cost as much) |
| Compose itself in QuickJS (back, 10 rows / open) | about 570 / 290 at 16 rows | about **385 / 185** | Composition, the lazy layout and disposal running in an interpreter on a slow CPU | **Inherent to the approach on this CPU** unless Compose does less (Gate B's guest-side profile, option 1) or the CPU is faster |
| Waiting for the guest's next frame | 23–27 | — | The click's state reaches the recomposer on the guest's next frame | Small; inherent to frame-driven composition |
| The batch to the host, applied and drawn (back / open) | 64+16 / 24+15 | 44+15 / 21+18 | Zipline carrying the batch; the host applying it | Shrinks with fewer widgets; the host's part is native speed |

The small API call's 65–70 ms of guest composition is Compose recomposing the visible rows and
finding nothing new (the 20 items are the same; one property is recorded): Compose's own cost again.

## Experiments


An independent fairness review of this benchmark asked for the measurements below before anyone
decides on the numbers above. They are built into the same route, off unless the `x` extra
(comma-separated) names them, so the as-shipped series above are unchanged. None changes published
library behaviour: the guest-side ones live in the sample (`threeway-guest`), and the one that needs
a library change (`window10`) is a patch applied only to a scratch copy of the prototype publish.

| `x` | Form | What it does | What it answers | What the library would need |
|---|---|---|---|---|
| `webcv` | web | `.row { content-visibility: auto; contain-intrinsic-size: auto 72px; }`: the browser lays out and paints only rows near the viewport | the large-list cost of a competent page, and whether tap → click returns to ~13 ms | — |
| `split` | guest (and native) | marks the start of the frame that handles a click (`frame`: a `withFrameNanos` callback registered in the `onClick`, before the click's state reaches the recomposer); wraps the guest's protocol adapter and times and counts every recording call (`n_props`, `n_propMs`, `n_mods`, `n_modMs`, `n_creates`, `n_struct`, `n_structMs`), click → composed | navigation split into: click → the guest's frame starts; frame → composed (Compose in QuickJS plus recording), of which the recording is `n_*Ms`; composed → batch at the host | — (measurement only) |
| `window10` | guest | the lazy list's first window is 10 rows (preload 9) instead of 16 (preload 15) | what composing 6 rows fewer than the guest now does saves on "back" | a smaller default first window in the prototype's `LazyWindow` (`keliver-compose-gatea-compose`, `LazyDsl.kt`), or one sized from the host's viewport: `sample/threeway-guest/experiments/lazy-window-10.patch` |
| `skipdefaults` | guest | a new widget's properties whose value is the "not set" value the host also falls back to (null, `Color.Unspecified`, `TextUnit.Unspecified`) and its empty modifier are not sent | what sending only non-default properties saves; a lower bound (other defaults, such as `maxLines = Int.MAX_VALUE`, are still sent) | the generator (`keliver-compose-generator`) to emit, per property, the host's initial value, and the guest's adapter to skip a first value equal to it; correct only where the host's initial value is the default (a few host properties, such as an icon's `contentDescription`, start "unset") |
| `jsonparse` | guest | `JSON.parse<dynamic>` (QuickJS's C parser), then the objects mapped to `Item` by hand; marks `jsParsed` between | how much of the 803 ms is kotlinx.serialization's string decoder rather than QuickJS | — (adopter code; or a helper in `keliver-http`) |
| `dynamic` | guest | `JSON.parse<dynamic>`, then kotlinx.serialization's `decodeFromDynamic` | the same with generated serializers instead of hand mapping | — |
| `retain` | guest | NF1's retained back stack: the list stays composed under the detail, hidden by a frame of the generated API's own widgets (`RetainedStack.kt`, keliver-nav's `KeliverRetainedStack`); the list's "composed" mark for a back is made by the app, since the list does not compose | what "back" costs when it composes nothing (Zipline QuickJS on the Mac, `profileQuickJs`, guest only: 81 → 2.4 ms, a 104-byte batch; the host's work is not measured; docs/GUEST_PROFILE.md, `retain=1`) | — (keliver-nav, from 0.5.0; a schema-level frame for retention by default is an open decision) |

The split's timer reads `Date.now()` (1 ms resolution) around every call; each call is well under a
millisecond, so the sum of the differences estimates the total (unbiased while calls are not
phase-locked to the clock), and the wrapper's own calls add a little. Note for readers of the
review: the guest on JS records through `FastGuestProtocolAdapter.encodeToDynamic`
(`keliver-treehouse-guest`, `ProtocolBridgeJs.kt`), not `DefaultGuestProtocolAdapter`'s
`encodeToJsonElement`, which is the JVM path.

```
# run (after the build and adb reverse in How to run it): as shipped, then each experiment
adb shell am start -n dev.keliver.sample/dev.keliver.sample.host.threeway.ThreewayActivity \
  -e form guest -e bench nav,api-small,api-large --ei n 12 --ez overLockScreen true -e x split
#   -e x split,skipdefaults   -e x jsonparse   -e x dynamic   -e form web -e x webcv
#   window10: apply the patch, publish keliver-compose-gatea-compose to a copy of the scratch Maven
#   repository, build the sample against that copy, then -e x split
dumpsys webviewupdate     # (adb shell) record the WebView's version with the web series
```

### Experiments, measured

A later session, 2026-11-15, 06:13–06:39 IST, same A12. **Phone state:** on the charger (AC), 100% for
every series, 23.8 °C at the start, 24.6 °C (native and guest series) to 25.9 °C (web `webcv` and
window-10 series), thermal status 0, no power saving (the route's own reading at the start of each
series; `dumpsys battery` at the start and end). WebView: `com.google.android.webview`
**157.0.8103.38**. Each series: 12 iterations per step after a 2-iteration warm-up; every form and
experiment in a fresh process.

**Compilation.** This session installed the APKs itself (no Macrobenchmark run first), then did what
Macrobenchmark's `CompilationMode.Partial` does: the libraries' baseline profiles through
profileinstaller, then `cmd package compile -f -m speed-profile` for the app's own package. A first
attempt without that step (JIT only) measured native's open/back at 47 / 31 ms instead of 14 / 11;
those series were discarded.

**As shipped, again** (this session; the first session's medians in brackets): native open 14 [16],
back 11 [13], small 178 [177], large 272 [277]; guest open 421 [438], back 1,031 [1,082], small 291
[306], large 1,212 [1,216]; web open 42 [37], back 49 [50], small 221 [214], large 714 [716]. Within
5% throughout, except native's small-network spread (p90 411; tunnel outliers).

**As shipped vs experiments** (medians in ms, tap → drawn, 12 each; `x` labels the experiment; the
`w10` rows ran on the APK built against the patched library):

| Series | Open | Back | Small API | Large API | Parse (large) |
|---|---|---|---|---|---|
| guest, as shipped | 421 | 1,031 | 291 | 1,212 | 806 |
| guest, `split` (timers on; adds ~10–50 ms) | 434 | 1,083 | 311 | 1,163 | 813 |
| guest, `split,skipdefaults` | 424 | 1,102 | 276 | 1,191 | 807 |
| guest, `jsonparse` | — | — | 309 | **432** | **39** (`JSON.parse` 25, mapping 14) |
| guest, `dynamic` | — | — | 299 | 645 | 257 (`JSON.parse` 24, `decodeFromDynamic` 232) |
| guest, `skipdefaults,jsonparse` | 433 | 1,082 | 289 | 407 | 39 |
| guest, `window10` | **324** | **698** | 285 | 1,187 | 804 |
| guest, `window10` + `split` | 323 | 732 | 294 | 1,168 | 808 |
| guest, `window10` + `jsonparse` (**the realistic best case measured**) | **327** | **703** | 311 | **389** | 38 |
| guest, `window10` + `skipdefaults,jsonparse` | 326 | 711 | 296 | 402 | 39 |
| web, as shipped | 42 | 49 | 221 | 714 | 6 |
| web, `webcv` | 36 | 64 | 241 | **465** (render 208, from 462) | 6 |
| native, as shipped | 14 | 11 | 178 | 272 | 33 |

**The navigation split** (`split`, medians, ms from the click; the counts are identical in every
iteration):

| | Open, 16-row window | Back, 16-row window | Open, 10-row window | Back, 10-row window |
|---|---|---|---|---|
| click → the guest's frame starts | 23 | 27 | 23 | 23 |
| frame → composed | 353 | 964 | 253 | 636 |
| of which recording, all calls (of it: properties; modifiers) | 67 (44; 23) | 392 (237; 150) | 68 (44; 23) | 251 (150; 99) |
| of which Compose itself (the rest) | ≈ 286 | ≈ 572 | ≈ 185 | ≈ 385 |
| new widgets / properties / modifiers recorded | 22 / 213 / 22 | 145 / 1,111 / 145 | 22 / 213 / 22 | 91 / 697 / 91 |
| composed → batch handed to the host | 23 | 64 | 22 | 44 |
| total (tap → drawn) | 434 | 1,083 | 323 | 732 |

With `skipdefaults` on back (16 rows), 466 of the 1,111 properties and 93 of the 145 modifiers
were not sent; the 645 properties and 52 modifiers still sent took 224 and 147 ms, as much as all of
them had (237 and 150). The defaults were nulls and empty modifiers, cheap to encode; the cost is in
real values, and a modifier's elements cost about 1 ms each. What the experiments did not answer:
whether a cheaper encoder (not built) would remove most of the recording time, and how much of
"Compose itself" a guest-side profile could find.

The run's raw logcat output, battery readings and fixture logs are kept in the benchmark's scratch
directory, not in the repository.

## The second session: scrolling, a heavy screen, the GC threshold

A third session, 2026-11-16 from 03:20 IST, same A12, set out to measure what the first two did not:
scrolling the 1,000-item list, opening a heavy screen, and QuickJS's garbage-collection threshold.
Everything is built into the same route, off unless asked for (`bench=scroll`, `x=heavy`,
`x=gc<MiB>`, `x=gcoff`, `x=mem`), prototype builds only.

**Phone state.** On battery throughout (not on the charger, unlike the first two sessions): 87% and
27.5 °C at the baseline, 84% at the last series, 27.4–27.6 °C in every state line; wireless adb,
thermal status 0, no power saving. WebView
`com.google.android.webview` 157.0.8103.38 (as before). The APK was compiled as in the experiments
session (the libraries' profiles through profileinstaller, then `speed-profile`). The Mac was busy
with another build session; it served nothing to the phone in this session (below).

**The fixture API ran inside the app.** No Mac-side server could run in this session, so the route
serves the same API itself (`--ez localApi true`, `LocalFixture.kt`): `GET /items` on
127.0.0.1:8491, the same bytes (checked: 4,473 and 224,145 B), the same fixed 100 ms delay, HTTP/1.1
keep-alive. Every form still goes through its own network stack (OkHttp for native and the guest,
Chromium for the page); the adb tunnel and the Mac are left out, so the network part of an API call
is shorter and steadier than in the first sessions (119–125 ms from request received to body read
for the large response across the 7 load-large calls, from about 180–230). Navigation, scrolling and the heavy screen make no request after the first load.

**Same-day baseline (as shipped, navigation).** 14 opens and backs per form, the first 2 dropped
(the experiments session's warm-up), ms from the tap, median / p90 / min–max:

| Form | Open | Back |
|---|---|---|
| native | **16** / 17 / 14–17 | **13** / 14 / 11–21 |
| guest | **436** / 443 / 423–468 | **1,082** / 1,089 / 1,015–1,152 |
| web | **44** / 49 / 31–50 | **49** / 50 / 47–51 |

Within a few ms of the first session's 16 / 13, 438 / 1,082 and 37 / 50 (web open 44 against 37 and
42 in the two earlier sessions), so this session's other numbers compare with the earlier ones.

**The phone left adb at about 03:58 IST** (wireless adb, as in the first session) and had not come
back 45 minutes later, so this session measured the guest's scrolling and the baseline only. The
native and web scrolling series, the heavy screen's series and the GC threshold series are built and
were checked on the phone (one check run each, below; their output was not kept) but **not
measured** in this session (measured in run 3, below); they are one command each in [How to run it](#how-to-run-it).

**Phone run 2 (the rerun with the review-round-1 route), 2026-11-16 from 11:40 IST.** Same phone,
on battery throughout (60% at the start), wireless adb, thermal status 0, no power saving, 60 Hz,
density 1.875 (300 dpi, 720×1600); WebView 157.0.8103.38 as before; the fixture API inside the app
(`--ez localApi true`, `api=app` in every line) for every series. The APK was installed and compiled
as before (profileinstaller, then `cmd package compile -f -m speed-profile`). Each series' battery
level and temperature are given with it. **A smoke run found one harness bug** before any series:
native's first fling group had no frames after the list stopped, the summary's percentile came out
NaN, `org.json` refused it and the process died (the guest's and the web's groups happened to have
such frames). Empty statistics are now logged as `null` (they were rounded to 0 before, which also
made a group with no placeholder report `contentAfterUpMsMedian: 0`), and a failing summary is logged
rather than ending the run. Every new field was then logged and plausible in all three forms.

**Run 2 also lost the phone.** The Mac's adb server stopped at about 12:41 IST, during the guest's
instrumented (`x=scroll,blank`) pass, and the phone was not found again in the next 33 minutes
(a restarted adb server on this Mac may need macOS's Local Network permission to see a wireless
device; that was not checked). Run 2 therefore measured the same-day baseline and the **guest's
clean scrolling pass at five speeds** only. Not measured in run 2: the guest's instrumented pass
(placeholders and content times; run 3 found it had never started), the `x=scroll`
pass, native and web scrolling, the heavy screen and the GC threshold series. The smoke runs (one
gentle and one fast fling per form, one heavy guest open with `x=mem`) are in the scratch raw files
and are quoted below only where marked as smoke.

**Same-day baseline, run 2** (as shipped, navigation, 14 opens and backs, first 2 dropped; 58%,
25.4 °C; median / p90 / min–max, ms from the tap; the process's first open apart):

| Form | Open (warm) | Back | Open (cold, i=1) |
|---|---|---|---|
| native | **16.5** / 17 / 14–18 | **13** / 14 / 12–18 | 27 |
| guest | **440.5** / 450 / 420–452 | **1,054.5** / 1,122 / 1,021–1,167 | 454 |
| web | **44** / 49 / 32–49 | **48** / 52 / 47–57 | 52 |

Within 1–3% of the first run's baseline above (16 / 13, 436 / 1,082, 44 / 49), so run 2's other
numbers compare with every earlier session's.

**Phone run 3 (finishing run 2), 2026-11-16 from 13:42 IST.** Same phone, the same installed APK as
run 2 (installed 11:51 with the null-not-NaN fix; nothing in the app changed since, so run 3's
numbers compare with run 2's directly and run 2's baseline above is run 3's), on battery (50% at
the start), wireless adb, thermal status 0, no power saving, 60 Hz, the fixture inside the app for
every series. Run 3's temperatures are the battery's, from the `dumpsys battery` file saved after
each series; the app's own state line (read at the series' start) can differ by up to about 1 °C
(native 26.7 °C at every speed; web 26.7–32.5). **The lost placeholder pass was not on the phone:** the app's `files/s/` held run 2's
five clean guest files and a results file identical to the fast one, so that pass had not started
when the phone left. **Every series in run 3 ran from one script on the phone itself**
(`nohup sh r3.sh`, launched over adb, the same `am start -S` and `input swipe` commands as before),
so a dropped adb link could not stop a series; the Mac only pulled the files. One thing to know when
re-running it that way: `input` and `am` fail with "Failed transaction" when their output is a file
on shared storage (the system server cannot use that descriptor), so the script gives them
`/dev/null`. Each series' battery level and temperature are given with it.

**Run 3 lost the phone's adb link too**, from about 14:09 to 15:25 IST (wireless adb, as in both
earlier runs). The script on the phone was not affected: every series ran, in order, from 13:45 to
15:17 (battery 50% → 44%, on battery throughout, thermal status 0 in every series), and the files
were pulled when the phone came back. The phone warmed during the scrolling series: 25.7–26.8 °C
for the guest's placeholder pass, 27.0–27.7 °C for native, 27.6–33.1 °C for web, 31.3–33.6 °C for
the guest's `x=scroll` pass, then 29.8–30.4 °C for the heavy screen and 29.3–29.8 °C for the
GC arms. One series is incomplete: the guest's fast `x=scroll` run logged 2 flings and then stopped
logging (no crash in the crash buffer; cause not established).

### Scrolling, run 3: the first speed with placeholders (guest)

**The guest's instrumented pass** (`x=scroll,blank`, five speeds, each a fresh process; 13:45–14:05
IST, 50% → 47%, 25.7–26.8 °C, thermal 0). No fling was closed by a touch or busy at its DOWN; edge
flings dropped as in run 2. A placeholder counts from half a row (the host's 48 dp spacer for a row
the guest has not delivered); "content" is the first frame after the last frame with one, from the
UP. Missed vsyncs in this pass match the clean pass's (8 at 3,556 dp/s down, 15 at 6,667), so the
semantics reads did not change what the list does.

| Guest, release speed | Flings with placeholders | Most on screen (of 14) | First frame with content, from UP: median (min–max) | List stops (median) |
|---|---|---|---|---|
| 1,422 dp/s, down | 0 of 10 | 0 | none | 741 ms |
| 1,422 dp/s, up | 0 of 9 (one sub-row gap) | 0 | none | 743 |
| **2,133 dp/s, down** | **9 of 10** | **1–2** | **1,075 ms (1,027–1,113)** | 992 |
| 2,133 dp/s, up | 1 of 9 (the first up: 7 rows) | 7 | 1,046 | 992 |
| 2,844 dp/s, down | 10 of 10 | 8–12 | **3,516 (1,230–3,641)** | 1,208 |
| 2,844 dp/s, up | 4 of 9 (14 on the first up, then 1 row) | 1–14 | 1,602 (986–3,164) | 1,208 |
| 3,556 dp/s, down | 10 of 10 | 14 (the whole list) | **4,318 (1,508–4,726)** | 1,420 |
| 3,556 dp/s, up | 9 of 9 | 9–14 | 2,183 (1,896–4,291) | 1,425 |
| 6,667 dp/s, down | 6 of 7 (fling 1 started no fling) | 14 | **4,802 (4,719–5,078)** | 2,237 |
| 6,667 dp/s, up | 5 of 5 | 14 | 7,016 (4,819–7,133) | 2,238 |

**The first speed with placeholders is 2,133 dp/s** (800 px in 200 ms), flinging down the list: in 9
of 10 flings one or two rows at the leading edge are blank for 14–44 frames (about 0.25–0.7 s)
near the end of the fling, and are filled 35–120 ms after the list stops (first frame with content
1,027–1,113 ms after the UP, the list stopping at 989–993). At 1,422 dp/s there are none. **From
2,844 dp/s the list goes mostly blank:** 8–12 of the 14 visible rows are placeholders and content
comes back a median 3.5 s after the UP, 2.3 s after the list has stopped; at 3,556 dp/s the whole
screen is blank and content comes back after 4.3 s (2.9 s after the stop); at 6,667 dp/s after
4.8 s down and 7.0 s up (2.6 and 4.8 s after the stop), as the first run's old-route pass found
(4.9 and 6.7 s). Flinging back up costs less at 2,133–3,556 dp/s (the rows above were composed on
the way down and the guest's window still holds some), except the first fling up after the
direction changes. The process's first fast swipe again started no fling (`flingVelocityPxS` 0,
travel the finger's own): now three of five fresh-process fast runs (four of six with the
`x=scroll` run, below).

**What a user would feel (run 3, with run 2's clean pass).** A slow, deliberate scroll (about
1,400 dp/s) looks native: the list follows the finger and stops on rows that are there. A relaxed
ordinary flick (about 2,100 dp/s) mostly looks fine, but the bottom one or two rows flash blank for
a fraction of a second as the list comes to rest. A normal brisk flick (2,800 dp/s and up) moves
smoothly at 2,800 dp/s but stops on a mostly empty screen: 8 of 14 rows or more are blank, and the
content comes back about 2.3 s after the list has stopped. At 3,500 dp/s and faster the list also
stutters while it moves (run 2: 7–11 missed vsyncs per fling down at 3,556 dp/s, 3–20 at 6,667)
and the whole screen stays empty for about 2.5–5 s after it stops (2.9 s at 3,556 dp/s down; 2.6
and 4.8 s at 6,667). A user flicking through a long list at a normal pace would see a list that
keeps going blank; on this phone that is the guest's biggest visible defect. Native has no
placeholders by construction (not measured); the web form's blank areas (Chromium checkerboarding)
are not visible to the route (below).

### Scrolling, run 3: native and web against the guest

**Native and web, clean passes, five speeds** (native 14:05–14:25 IST, 47%, 27.0–27.7 °C; web
14:25–14:44, 47% → 46%, 27.6–33.1 °C; thermal 0). The same swipes and gaps as the guest's; no fling
closed by a touch. The guest's column is run 2's clean pass (same APK, 58% → 55%, 25.0–25.6 °C).
Missed vsyncs per fling while the list moves, median (min–max), down / up:

| Release speed | Native | Web | Guest (run 2) | List stops: native / web / guest |
|---|---|---|---|---|
| 1,422 dp/s | 1 (0–2) / 1 (0–2) | 0 / 0 | 1 (0–1) / 1 (0–2) | 743 / 671 / 743 ms |
| 2,133 dp/s | 0 (0–1) / 0 (0–2) | 0 / 0 | 1 (0–2) / 1 (0–2) | 992 / 921 / 992 |
| 2,844 dp/s | 1 (0–2) / 0 (0–1) | 0 / 0 | 1.5 (0–5) / 1 (0–1) | 1,211 / 1,162 / 1,210 |
| 3,556 dp/s | 1 (0–2) / 0 (0–1) | 0 / 0 | **8 (7–11)** / 1 (0–3) | 1,423 / 1,363 / 1,419 |
| 6,667 dp/s | 4 (2–10) / 1 (0–2) | 0 / 0 | **15 (3–20)** / 3 (2–6) | 2,241 / 2,210 / 2,248 |

- **Web never missed a vsync** in 93 flings (the app window's frames; Chromium's own renderer is
  not seen): Chromium scrolls on its compositor thread, and its
  list is plain DOM rows that are already laid out.
- **Native misses 0–2 per fling up to 3,556 dp/s**, and 2–10 (median 4) flinging fast down the list,
  where each fling passes about 79 of its 72 dp rows (its moving frames' p90 37 ms there, 21–24 ms at
  the other speeds).
- **The guest matches native up to 2,844 dp/s and falls behind from 3,556 dp/s down the list**:
  8 against 1 at 3,556 dp/s, 15 against 4 at 6,667. Flinging up it stays close to native.
- **In these scripted flings, moving frames take about 20 ms in all three forms** (median
  20.0–20.7 ms in every group but the guest's 3,556 dp/s down, 10.4 ms). Flings by hand do not
  show that, so it is not the phone's pipeline alone (next paragraph). Why the scripted flings
  differ is not established.

**By hand (2026-11-16, 16:49–16:56).** The owner flung the same 1,000-item list in each form on
the A12 (60 Hz, on battery, 38–39%, 25.9–27.6 °C), using the same build and the in-app fixture.
Their verdict: "guest scrolling definitely feels laggy". The app's frame lines, all flings of each
session:

| Form | Flings | Frames | Median frame | Over 25 ms | Over 50 ms | Worst |
|---|---|---|---|---|---|---|
| native | 14 | 468 | 11.2 ms | 1 (0%) | 0 | 32 ms |
| web | 31 | 618 | 10.1 ms | 1 (0%) | 0 | 27 ms |
| guest | 44 | 1,782 | 20.2 ms | 297 (17%) | 118 | 231 ms |

- **In 13 of the guest's flings it did no work while the list moved.** Its frames still had a
  13.7 ms median, and 40% were over 16.7 ms: part of the lag is the host drawing the guest's tree.
- **In the other 31 flings** the median was 20.3 ms, with 70% over 16.7 ms.
- **On the five longest flings by distance**, the guest was still composing rows 1.0–7.2 s after
  the finger lifted. Single guest compositions (`gMaxMs`) took up to 3.0 s; the host frames in the
  table peaked at 231 ms.
- One session per form, unscripted, so the flings differ in speed and number. This is a check of
  what a user sees, not a controlled series.
- **Native's first fast swipe started its fling** (velocity 12,500 px/s, 10,591 px travelled); the
  guest's did not in 4 of 6 fresh-process fast runs (run 3's `x=scroll` run is the sixth). That
  points at the guest form's first drag, not at the input route; why is not established.

**The guest's `x=scroll` pass** (its widget counters; 14:44–15:04, 46%, 31.3–33.6 °C). Missed vsyncs
are within 1.5 of the clean pass's (down: 1.5, 1, 3, 8 at the four slower speeds; the clean pass 1, 1, 1.5, 8).
The guest's work per fling, down / up:

| Release speed | Guest time per fling (median) | Rows created per group (10 down / 9 up) | Guest ms per new row | Guest busy after UP (median) |
|---|---|---|---|---|
| 1,422 dp/s | 2,942 / 2,175 ms | 156 / 175 | 181 / 106 | 2,932 / 2,185 ms |
| 2,133 dp/s | 3,392 / 2,457 | 223 / 210 | 152 / 98 | 3,533 / 2,587 |
| 2,844 dp/s | 3,812 / 2,642 | 339 / 267 | 112 / 96 | 4,079 / 2,921 |
| 3,556 dp/s | 4,600 / 3,650 | 472 / 375 | 101 / 87 | 5,019 / 3,986 |
| 6,667 dp/s | not measured (the run stopped logging after 2 flings) | | | |

(Rows are widgets created / 9.) The guest spends about 0.1–0.18 s per row it creates, and creates
more rows the faster the fling: it composes the windows the list passes through.

**What a user would feel, native and web against the guest.** Native and web feel the same at every
speed: the list moves, slows and stops on rows that are there; native drops a few frames on a very
fast flick down a long list, web drops none. The guest feels the same as them only on slow
scrolls. From an ordinary flick it shows blank rows (native has no placeholders; web's
checkerboarding is not measured), and from a
brisk one it also stutters while it moves.

### Where a scrolling frame's time goes (NF0 trace)

2026-11-16, the same A12. Four clean traces (native and guest, gentle and fast) were recorded from
17:25 IST with the run-3 build of `dev.keliver.sample`. Two more guest traces were recorded at
18:22 with the `x=trace` build, which has the same app plus trace sections. All six were read on
the phone that evening. **Phone state:** the four clean traces ran on battery, 35%, 30.5–30.6 °C,
thermal status 0. The two `x=trace` traces ran on the charger, 56–57%, 36.2–36.3 °C, thermal
status 0. Comparisons below are made within one condition: clean against clean, and `x=trace`
only for question 3.

**Method.** Perfetto on the phone (`perfetto --txt -c -`, its text config through stdin, written
into `/data/misc/perfetto-traces/`) recorded:
- ftrace `sched_switch`/`sched_waking`/`sched_wakeup`, `cpu_frequency`, `gpu_frequency`;
- atrace `gfx view input am wm dalvik power` and the app's own sections (`atrace_apps:
  dev.keliver.sample`, which turns on Compose's built-in sections such as `Recomposer:recompose`
  and the lazy list's prefetch);
- SurfaceFlinger's frame timeline, and process names.

Each trace is one series in one fresh process, started by a script on the phone (`nohup sh`, so a
dropped adb link cannot stop it), using the doc's scroll commands:
- gentle: 800 px in 300 ms (1,422 dp/s), flings 8 s apart;
- fast: 1,000 px in 80 ms (6,667 dp/s), flings 14 s apart;
- **10 flings down per trace**, native and guest.

The analysis is Perfetto SQL, run on the phone with a `trace_processor_shell` that was already there
(the Mac's shell could neither download one nor run Python). Each query file adds to a common
prelude, and only small CSV results come back.

The prelude finds the app's process by its main thread, because a process forked after the trace
started keeps the name `zygote64` in the process table. It also finds the flings: the main thread's
`deliverInputEvent` slices, with a new fling after a gap of more than 1 s. **Moving frames** are
the main thread's `Choreographer#doFrame` slices from the DOWN to a **fixed** stop: 743 ms
(gentle) or 2,240 ms (fast) after the last input event, run 2's median stop times, the same for
every fling. The traces do not mark where each fling really stopped. Every trace yielded 10 flings
of 20–21 (gentle) or 6–8 (fast) input events. **The guest's fast fling 10 reached the end of the
list** (its 48 dp placeholders let a fling pass about 1.5 times native's rows; the first run saw
that fling stop 284 ms after the UP): it produced 22 viewport reports and 3 batches against 97–103
and 5, and the Zipline thread's last running moment was 3.9 s after its UP against 6.7–8.3 s. So
the guest's fast "moving" window includes most of 2 s of a list at rest. Native's fast flings, at
72 dp rows, did not reach the end.

**The traces were deleted after these queries.** Only the CSV results are kept, so no number here
can be re-queried or split per fling.

The `x=trace` build (prototype only, off unless the run asks for it) adds trace sections:
- for every call into or out of the guest on the Zipline thread (`zl:sendFrame`, `zl:sendEvent`,
  `zl:sendChanges n=<changes>`, through the app's Treehouse `EventListener.callStart`/`callEnd`).
  These are synchronous sections, so suspending calls are skipped (they can overlap or end on
  another thread). `sendFrame`, `sendEvent` and `sendChanges` are not suspending, so the numbers
  below are unaffected; the skip was added after the traces were recorded;
- for every main-thread message (`msg <callback>`, from `Looper.setMessageLogging`).

The scripts and queries are kept in the session's scratch directory, not in the repository.

**Per moving frame, guest against native** (clean traces. Main-thread times are means per moving
frame. "Outside doFrame" is the lazy list's prefetch and Compose's out-of-frame work, which run as
their own main-thread messages):

| | Native, gentle | Guest, gentle | Native, fast | Guest, fast |
|---|---|---|---|---|
| Moving frames (10 flings) | 628 | 631 | 1,401 | 1,311 |
| Main `doFrame`, mean / median | 4.27 / 3.77 ms | 3.82 / 3.16 | 6.51 / 5.34 | 4.84 / 3.87 |
| — input | 0.54 | 0.44 | 0.07 | 0.07 |
| — animation (the fling, `Recomposer:animation`, the list's measure) | 2.20 | 2.07 | 4.46 | 3.61 |
| — of which `Recomposer:recompose` (**guest only**) | < 0.05 | **0.46** | 0.06 | **0.61** |
| — traversal (draw: `Record View#draw()`) | 1.48 | 1.26 | 1.93 | 1.12 |
| Outside doFrame: prefetch (compose, measure, apply, urgent) | 1.33 | 1.22 | 2.56 | 0.90 |
| Main thread running while moving | 38% | 34% | 57% | 36% |
| RenderThread `DrawFrame`, mean / median | 6.44 / 6.67 | 4.73 / 4.58 | 6.84 / 6.06 | 4.09 / 3.51 |
| Zipline thread running while moving | — | **95%** | — | **98%** |
| Faster CPU cluster while moving: mean (time at its 2.3 GHz max) | 1.71 GHz (4%) | 2.26 (92%) | 1.92 (16%) | 2.29 (97%) |
| Frame timeline: median actual duration; "Buffer Stuffing" | 20.0 ms; 52% | 20.0; 55% | 20.7; 95% | 19.9; 52% |
| Presents 20 ms – 1 s apart, 10 flings (fast guest: includes fling 10's list at rest) | 30 | 16 | 61 | **152** |
| Main thread asleep > 20 ms in the window (fast guest: includes fling 10's list at rest) | not queried | not queried | 0 | **16 times, 1.50 s** |
| After the list stops: main / Zipline running, 10 flings | 0.13 s / — | 0.76 / **20.8 s** | 0.19 / — | 1.57 / **36.4 s** |

**Answers.**

1. **Main-thread and RenderThread time, guest against native (measured).** The host's work per
   moving frame is *not* larger in the guest form. Main-thread `doFrame` time is 3.8 ms against
   4.3 ms (gentle) and 4.8 ms against 6.5 ms (fast). RenderThread time is 4.7 ms against 6.4 ms and
   4.1 ms against 6.8 ms. The guest's rows draw more cheaply than the native rows, and at the fast
   speed most of the guest's rows are blank placeholders (run 3). Native also prefetches and
   composes real rows: 2.6 ms of prefetch per frame against the guest's 0.9.

   Two costs exist only in the guest form:
   - **`Recomposer:recompose` inside the frame, 0.46–0.61 ms per moving frame** (native under
     0.06). This is the host recomposing Redwood's protocol nodes as the guest's changes and the
     lazy list's placeholders update.
   - **The Zipline thread runs through 95–98% of the moving time**, holding the faster CPU cluster
     at its maximum (92–97% of the time, against 4–16% in native).

   In the fast traces the guest has 152 presents 20 ms – 1 s apart against native's 61, and 1,311
   frames drawn against 1,401 in the same 23.4 s of windows. **Much of that difference is probably
   one fling's list at rest, not a moving list.** These are not long frames: main-thread frames over 16.7 ms number 32
   against 25, and RenderThread frames over 16.7 ms 28 against 54. Waiting for a CPU is not the
   cause either: the main thread was runnable but not running for 0.2 s in both forms. Instead,
   **the guest's main thread slept for more than 20 ms 16 times inside the fixed 2,240 ms windows
   (1.5 s in total; native never did)**, and no frame was produced in those intervals. 1.5 s is
   about the 90 missing frames at 60 Hz. The guest's fast fling 10 reached the end of the list and
   stopped early (see the method), so its window holds a list at rest, where frames come only when
   a batch lands; that tail alone can account for the sleeps, the missing frames and many of the
   wide present gaps. The traces cannot be split per fling (they were deleted), so this is likely,
   not shown. Run 2's "first fast swipe starts no fling" is a second candidate for an idle window.
2. **What the host does extra in frames with no guest work: the class hardly exists in scripted
   flings (measured).** The Zipline thread was idle from 20 ms before a moving frame to its end in
   only 24 of 631 (gentle) and 10 of 1,311 (fast) moving frames. All of them came at a fling's
   start, so the by-hand class (13.7 ms median) cannot be rebuilt from these traces. The host's
   only guest-specific per-frame work is the `Recomposer:recompose` above, 0.5–0.6 ms. Everything
   else costs the same or less than native. **In scripted flings the host does not do more per
   moving frame**; this is cleanest at the gentle speed, where the guest's rows are real (at the
   fast speed most are blank placeholders, so its lower host cost there is not like for like).
   **The by-hand 13.7 ms class was not traced; its cause is open.**
3. **The guest's work during and after a fling (measured with `x=trace`; on the charger at
   36 °C).**
   - **The guest's thread.** QuickJS runs on one thread, `Treehouse <app>`. Every `sendFrame` and
     `sendEvent`, and the guest's `sendChanges` back into the host, run there. The host paces the
     guest's frames (from the code): a Choreographer callback queues `sendFrame` onto that thread
     (`AndroidChoreographerFrameClock`), so while one guest frame runs no other can start. The
     threeway guest uses the generated `dev.keliver.compose.foundation.lazy.LazyColumn`. Its host
     half, `ReportLazyViewport` (`keliver-compose-gatea-composeui`, `LazyRuntime.kt`), runs
     `LaunchedEffect(state, viewport)` on the (first, last) visible pair and calls
     `contentViewport`, which reaches the guest's `onViewport` (`LazyDsl.kt`). Each such report
     becomes its own task on the Zipline thread (`EventBridge.sendEvent`).
   - **Every report is delivered; the guest's composition reads only the latest (confirmed).** A
     fast fling produced 97–103 `sendEvent` calls, and every one of them reached the guest
     (fling 10, which hit the end of the list, produced 22). `onViewport` writes state, and the
     guest's next frame composes from whatever was written last, which is why there are about 6
     guest frames per fling rather than about 100. The last report was handled **4.9–8.2 s after
     the UP** (median 5.8 s, flings 1–9), while the list stopped at about 2.2 s. At the gentle
     speed each fling produced 21–22 reports, and in 8 of 10 flings **19–20 of them were handled
     after the list had stopped** (fling 1: 1, fling 10: 12; the last at 0.9–1.3 s after the UP).
   - **The reports themselves are cheap: 0.6–0.7 s per fast fling** (0.12 s gentle). They are
     late because they wait behind the guest's frames.
   - **What the guest composes.** The guest ran 6 frames per fast fling (flings 1–9), the longest
     2.8–3.4 s, 4.7–7.7 s in total. Each frame composes the window of the latest report handled
     before it. Each fling sent 5 batches with about 630–940, **4,100**, 200–3,600, 520 (6–7 in two
     flings) and 6–11 changes; fling 10, at the end of the list, ran 5 frames and sent 3 batches
     (841, 1,277, 521). The
     second, 4,100-change batch comes from a frame that started while the list was still moving,
     so it is most likely a window the list was passing through. Which batch holds the resting
     window was not traced; this is an inference from timing. The guest's last call ended
     **6.4–9.5 s after the UP** (median 7.7 s, flings 1–9; fling 10 4.4 s). Gentle flings: 5 frames, the longest 0.7–1.3 s,
     the last call ending 2.6–4.0 s after the UP. So the code reading's mechanism is confirmed,
     with one correction: the guest does not compose every window the list passed (6 frames and 5
     batches, not one per row: the guest's list passes about 106 rows per fast fling). By timing
     (inferred, not traced) it does compose windows the list has left, and builds the resting
     window last.
   - **Applying a batch on the main thread is short.** It is one message
     (`ViewContentCodeBinding.sendChanges`): 48 over 10 fast flings, 216 ms in total, **at most
     21 ms**, and 10 of them came while the list moved (22 ms). The longest frame message in those
     flings took **101 ms**; whether it was the frame composing a big batch's widgets was not
     matched. The multi-second gaps are the guest's own frames on the Zipline thread, not the main
     thread. (These are `x=trace` numbers: message logging adds a little to every main-thread
     message.)
4. **Why scripted `input swipe` flings give about 20 ms frames for native too (measured, in part).**
   - **The ~20 ms is frame latency, not work.** SurfaceFlinger's frame timeline classes 52–95% of
     the moving frames as **"Buffer Stuffing"**, mean 20.5–22.7 ms: the app queues its next buffer
     while the previous one still waits to be latched, so each frame is presented one vsync later.
     The frame's own work is 4–7 ms on the main thread plus 4–7 ms on the RenderThread, and native
     misses few vsyncs (30 and 61 presents more than 20 ms apart over 10 flings). FrameMetrics'
     `TOTAL_DURATION` and the frame timeline's actual duration both include that queueing.
   - **CPU frequency does not explain it.** Scripted native flings ran the faster cluster at a mean
     of 1.7–1.9 GHz (at its 2.3 GHz maximum 4–16% of the time; 0.95 GHz in the second before each
     DOWN). The guest traces, whose Zipline thread pins that cluster at the maximum 92–97% of the
     time, show the same 20 ms. So a missing touch boost for injected events is not the cause.
   - **Not established:** why hand flings stay out of the stuffed state (11.2 ms native median). No
     hand trace was recorded. The injected events come every 15 ms (20 in 300 ms) and are not
     aligned to vsync.

**What NF1's scroll fix should change (ranked; each item says whether its basis is measured or
estimated).**

1. **Guest: compose only the window the list will show, and drop windows it has left. The cost is
   measured; the saving is estimated.**
   - What it costs today: after a fast fling the guest keeps working 3.6 s per fling once the list
     has stopped (36.4 s over 10, after the fixed 2,240 ms window), and its last call ends 6.4–9.5 s
     after the UP (flings 1–9). At least the 4,100-change batch is, by its timing, a window the
     list was passing through (inferred, not traced).
   - Conflating the viewport reports (`EventBridge`, or `ReportLazyViewport` / `onViewport`) on its
     own saves only their 0.6–0.7 s of handling per fling, plus the queueing, as measured.
   - Most of the saving needs the guest not to start, or to abandon, a frame for a window that is
     no longer current. Estimate: most of the 3.6 s after the stop, and the resting rows one guest
     frame after the stop instead of 4–6 s after it.
2. **Host: report where the fling will stop as soon as it starts (estimated).** The decay
   animation's target is known at the UP. Reporting that window first makes the resting window the
   first one composed. Estimate: content where the list stops arrives 0.1–1 s after the UP, one
   guest frame, instead of 4.9–8.2 s.
3. **The Zipline thread's CPU use while the list moves (measured, effect not separated).** The
   guest runs 95–98% of the moving time and holds the faster cluster at its maximum. The scripted
   traces show no runnable delay for the main or Render threads (0.2–0.3 s in both forms). Fewer
   stale compositions (item 1) reduce it. Lowering the Zipline thread's priority while a fling runs
   is a candidate if hand traces show contention. Estimated: no per-frame saving in scripted flings.
4. **Host: spread the work after a big batch across frames (measured: small).** Applying a batch is
   at most 21 ms on the main thread. The longest frame message in the same flings was 101 ms; it
   was not matched to a batch. At most that is one visible hitch per big batch, and most batches
   are applied after the list has stopped (38 of 48). A per-frame budget, or deferring off-screen
   rows, would remove it. Estimated saving: at most one long frame per batch.
5. **Host: per-frame host cost (measured: small).** The only guest-specific per-frame work is
   `Recomposer:recompose`, 0.5–0.6 ms. Flatter rows or skipping recomposition of unchanged
   protocol nodes save at most that.
6. **First check whether the guest's fast-trace sleeps are fling 10's tail (a check, not a fix).**
   The 16 main-thread sleeps over 20 ms (1.5 s) and the 152 against 61 presents 20 ms – 1 s apart
   fall inside the fixed 2,240 ms windows, and the guest's fling 10 reached the end of the list and
   stopped early, so they most likely include a list at rest. The deleted traces cannot say. The
   check is one fast guest trace with each fling's real stop marked and no fling reaching the end,
   plus run 2's first-swipe-starts-no-fling. No saving is estimated until then.
7. **Guest: placeholders with the row's height and look, never blank** (already NF1). No time
   saving; it changes what a user sees during the work above.

### Cold start (NF0)

2026-11-17, the same A12 (Android 12, 60 Hz, wireless adb), on battery: 82% → 80%, 27.4–28.5 °C,
thermal status 0 before every series. The NF4 target is the guest within 0.4 s of native.

**Method.**
- **Build.** The prototype at `61a3c2a52` (Kotlin 2.4.20, Zipline 1.28.0) plus this section's
  cold-start marks. It was published to a scratch Maven repository; the sample's `benchmark` build
  type was built against it.
- **API.** Every form used the app's own fixture (`localApi=true`; LocalFixture, a fixed 100 ms
  delay), so no `adb reverse` tunnel was involved. `ThreewayStartupBenchmark` passes the
  instrumentation argument `-e localApi true` through to the activity.
- **Start policy.** The guest's code comes from the APK's assets through Zipline's cache, so code
  needs no network. Parallel's cached start (#249, the default) only differs from network-first when
  the manifest needs the network. These numbers are therefore that policy's warm-cache case, with
  no network for code.
- **Series.** Macrobenchmark `StartupMode.COLD`, 10 iterations per form, one form at a time. The
  per-iteration values come from the benchmark's JSON. p90 is the nearest rank (the 9th of 10).
- **Split.** The guest's split comes from the app's marks. Each process logs one `"cold"` line when
  the list's data is first drawn, giving every mark in ms from the app's start
  (`Process.getStartUptimeMillis`, which Android sets at `bindApplication`; in trace 2 that is
  about 56 ms after the fork. Macrobenchmark's clock starts about 270 ms earlier, at the launch).
  The marks are:
  - `activityCreate`, `contentSet` (the form's content set) and `firstDraw` (the activity's first
    frame);
  - from the Treehouse `EventListener`, the code load's steps: `codeLoadStart`, `manifestReady`,
    `ziplineCreated`, the first and the last module load, `mainStart`/`mainEnd` and
    `codeLoadSuccess`;
  - `firstBatchSent`;
  - the request's marks as in the other series.

  The line also carries the 53 module loads' summed time and the slowest module.
- **Traces.** Three Perfetto traces of guest cold starts with `x=trace`, which adds the
  `zl:<call>` sections. They were started with `am start -W`, read on the phone with
  `trace_processor_shell`, and deleted afterwards.
- **First launch.** Three first launches after `pm clear` (empty code cache), and three ordinary
  cold starts as their comparison, by the same method (`am start -W`, no Macrobenchmark).

**Cold start to the list's data drawn (Macrobenchmark, 10 iterations, ms).**

| Form | Full display (data drawn): median | p90 | min–max | Initial display: median | p90 | min–max |
|---|---|---|---|---|---|---|
| native | **1,286** | 1,316 | 1,249–1,325 | 939 | 971 | 906–977 |
| guest | **2,877** | 4,424 | 2,818–8,519 | 760 | 976 | 736–4,361 |
| web | **1,937** | 1,985 | 1,904–2,010 | 1,498 | 1,580 | 1,468–1,600 |
| guest − native | **+1,591** | | | −179 | | |
| web − native | +651 | | | +558 | | |

- **The gap to the target is about 1.2 s:** +1,591 ms against +400 ms. The previous run (#250, the
  Mac's API through the tunnel) measured 1,235 / 2,910 / 2,040, which is within 5% of this one in
  every form (native +4%, guest −1%, web −5%).
- **The guest's iterations 0 and 1 are as before.** Iteration 0 started with an empty code cache
  (4,424 ms; Macrobenchmark clears the app's data). Iteration 1 was the same outlier as in #250
  (8,519 ms, initial display 4,361), which was already slow before the app's code ran (its
  `activityCreate` came 1.7 s after the process start against 0.35 s). Without both, the guest's
  median is 2,857.

**The guest's split** (median of each step over iterations 2–9's `"cold"` lines, ms). The third
column is the running sum of the step medians, not the median of each mark (the request's own
median mark is 1,386, not 1,364); only the last row's 2,608 is a median of the mark itself.
Native's same marks are given for comparison:

| Step (guest) | ms | Running sum of step medians | Native |
|---|---|---|---|
| Process start → `Activity.onCreate` | 359 | 359 | 382 |
| `onCreate` → content set (the factory, the fixture) | 96 | 455 | 106 |
| Content set → first frame (the guest's empty host surface) | 103 | 557 | 210 (header drawn) |
| First frame → code load starts | 18 | 575 | — |
| Manifest read (cache) | 9 | | — |
| Zipline and QuickJS created | 23 | 608 | — |
| **53 modules loaded** (QuickJS's bytecode loads alone, summed: 298; slowest `androidx-compose-runtime`, 82–89) | **469** | 1,077 | — |
| `main()` and the code load's end | 38 | 1,115 | — |
| **First composition: the loading state** (110 changes; trace 2: `zl:start` 252 ms; 246–252 across the three traces per the session's notes) | **244** | 1,358 | — |
| First batch sent → request (the `LaunchedEffect`) | 6 | 1,364 | native requests at 799 |
| Request → parsed (100 ms fixture, bridge, parse) | 154 | 1,518 | 191 |
| **Parsed → list composed** (trace 2: one `zl:sendFrame` of 1,132 ms emitting `sendChanges n=1593`; 1,132–1,168 across the three per the notes) | **995** | 2,512 | 33 |
| Composed → batch sent (encoding; trace 2: `sendChanges` 49 ms; 49–64 across the three per the notes) | 65 | 2,577 | — |
| Batch sent → data drawn | 23 | **2,608** | **1,028** |

- **Before the guest's code, the two forms cost the same.** Process start, `onCreate` and content
  set are within 25 ms of native. The guest's first frame comes about 130 ms earlier (median 565
  against 698), because it is an empty surface.
- **The guest-only critical path is about 1,800 ms:** the code load (about 540 ms), the first
  composition (244), the list's composition (995) and its batch (65). The request goes out at
  1,364 ms against native's 799 (about 100 ms after native's first frame). So the guest is 565 ms
  behind before the network starts (running sums; about 587 with the request mark's own median),
  and 1,580 ms behind when the data is drawn. The gap grows by about 1,015 ms after the request;
  about 960 of that is the list's composition.
- **The Zipline thread is CPU-bound, not waiting.** In trace 2 it ran for 2,565 ms and was runnable but
  not running for only 7.7 ms; its I/O wait was 0.2 ms. The session's notes give 2,565–2,600 ms and
  5–8 ms across all three traces (traces 1 and 3: raw lines not retained).
- **First launch after install or update: +445 ms** (median data drawn 3,110 against 2,665; three
  each, `am start`; raw lines not retained, figures from the session's notes). In these six runs
  the QuickJS loads cost the same either way (271–280 ms summed), but the 53 modules' span is
  740–830 ms on a first launch against about 370 in the three ordinary `am start` runs (461 in the
  Macrobenchmark runs above): reading the assets, checking and writing the cache. The retained
  Macrobenchmark iteration 0 (empty cache, all 53 modules from the assets) agrees: its span is
  981 ms.
  Macrobenchmark's iteration 0 (4,424) also includes the app's own first-run costs after
  Macrobenchmark resets its compilation (its first frame came 200 ms later), so it is not the
  guest-only figure.
- **Not reconciled:** the `x=trace` traces show a 137–143 ms main-thread frame (138 in trace 2; the
  range is from the session's notes) right after the big
  batch. The marks give 23 ms from batch sent to drawn in the clean runs, and 50 ms in the traced
  runs. The frame may follow the drawn one, and `x=trace`'s message logging adds to every message.
  It is not on the guest's ranked list below.

**Where NF4 can take the time (guest-only costs, largest first).**
1. **The list's first composition in a fresh QuickJS: 995 ms (measured).** In a warm process, the
   same list recomposed after a reload costs about 67 ms (section 3, small). So about 0.9 s is
   first-run cost: code paths running for the first time in an interpreter (class and object
   initialisation of the rows' code and the runtime's first slot-table growth), plus creating
   1,593 changes for 20 rows (about 80 per row; their encoding is the separate 65 ms step, and the
   warm 67 ms also creates changes). This breakdown is inferred and was not
   profiled inside QuickJS. Candidates:
   - **Run the row code before the data arrives.** Placeholder rows built from the real row
     composables, during the loading state, would move the first-run cost into the network wait.
     Estimated saving: up to the wait itself (154 ms here, more against a slower API).
   - **Compose only the rows on screen first** (about 10 of 20 at 720×1600; NF3's 10-row window).
     First-run cost is per code path, not per row. Estimated: 100–300 ms.
   - **Fewer changes per row** (flatter rows, defaults not sent). Estimated: part of the encoding.
   - **NF2's engine.** It measured 2.0–2.4× on this phone for warm work. Estimated: about −500 ms
     if first-run work scales the same (not measured).
   - **An initialised heap kept between processes** (a QuickJS snapshot). Zipline has none. This
     is research, with no estimate.
2. **The code load: about 540 ms (measured)**, of which the QuickJS bytecode loads are 298 ms and
   about 170 ms is around and between the 53 module loads (cache reads and checks; inferred, not
   split). Candidates:
   - **Start the code load at process start** (the `Application`) instead of after the host's
     first frame. It starts 575 ms after the process start today, in parallel with nothing but the
     first frame. Estimated: −200 to −300 ms, overlapping `onCreate` and the first frame on another
     core (the main and Zipline threads do not compete: the Zipline thread was runnable but not
     running for under 10 ms).
   - **Fewer, larger modules, or a smaller first bundle** (53 modules; the Compose runtime alone is
     about 85 ms). Estimated: part of the 170 ms per-module overhead and of the 298 ms load.
3. **The loading state's first composition: 244 ms (measured), and the request waits for it.**
   Candidate: **start the screen's request without waiting for the first composition** (from
   `main()`, or a host-side prefetch named by the screen), so the network overlaps the composition.
   Estimated: −150 ms here (the whole network time), up to −244 ms against a slower API. The
   composition itself is first-run cost, and item 1's engine and snapshot candidates apply to it.
4. **First launch after install or update: +445 ms (measured).** Candidate: **unpack the bundle
   into the cache in the background after an install or update**, or load the embedded bundle
   straight from the assets without copying it. Estimated: most of the 445 ms, once per release.
5. **The big batch: 65 ms encoding plus the host's apply (measured, 23 ms to drawn).** Item 1's
   smaller window and fewer changes reduce it. Estimated: tens of ms.

Items 1–3 are estimated at roughly 0.8–1.3 s of the 1.2 s that needs to go including NF2's engine
(about 0.5 s of it, not measured), and about 0.3–0.8 s without it. Item 1's placeholder rows and
item 3 both use the same network wait, so their savings do not add. Only a measured NF4 run can say whether the 0.4 s target is reached without the engine.

### NF1 gate, part 1 (back and scrolling, A12)

2026-11-17 from 01:53 IST, the same A12. A **partial** gate run: NF1's two merged parts are
measured, the retained back stack (#259, the guest's `x=retain`) and the windowed lazy list with
drawn placeholders (#258, in every guest run, since it is in the generated list). The host's
pressed state and screen transitions are not built yet, so the tap target is not measured here,
and nothing was flung by hand.

**Method.** A fresh prototype build from main at `eadb91fd8` (the prototype published to a scratch
Maven repository, then the sample's `host-android` benchmark APK), installed over the old copy and
compiled with `speed-profile`, as before. Every series ran from one script on the phone (`nohup sh`,
the commands of [How to run it](#how-to-run-it), each series a fresh process with `am start -S`,
the fixture inside the app, `input`/`am` output to `/dev/null`); the Mac only pulled the files.
Navigation: 14 opens and backs, the first 2 dropped. Scrolling: the five speeds of runs 2–3, the
same swipes and gaps. Memory: 10 open/back cycles in a fresh process (no warm-up), then
`dumpsys meminfo` with the list shown.

**Phone state.** On battery throughout (79% → 72%), thermal status 0 in every series, 60 Hz,
density 1.875, sdk 31, no power saving. Battery 24.2 °C at the start, 27.3 °C after the memory
series, 27.4–28.5 °C while scrolling (the script's `dumpsys battery` line around every series).
The phone looked busier than in earlier sessions, by ad hoc `adb` reads of `/proc/meminfo` during the
session that were not kept with the record (`MemFree` 290–430 MB, `SwapTotal − SwapFree` 1.6–1.7 GB;
`MemAvailable` was higher, about 2.7 GB, since it counts reclaimable cache). The
native form was slower than in every earlier session, in navigation and in scrolling (below), so
every comparison here is within this session.

**Navigation, same day** (ms from the tap, median / p90 / min–max; open is warm; the process's
first open apart):

| Form | Open (warm) | Back | Open (cold, i=1) |
|---|---|---|---|
| native | **43** / 51 / 36–60 | **32** / 36 / 30–43 | 70 |
| web | **48** / 49 / 30–50 | **48** / 50 / 46–62 | 83 |
| guest, as shipped | **472** / 521 / 455–577 | **1,116** / 1,518 / 1,068–1,613 | 520 |
| guest, `x=retain` | **323.5** / 338 / 302–347 | **104.5** / 110 / 95–114 | 353 |
| heavy detail: native | 61 / 77 / 42–100 | **34** / 47 / 28–52 | 141 |
| heavy detail: guest, as shipped | 1,099 / 1,122 / 1,065–1,173 | **1,123** / 1,142 / 1,085–1,171 | 1,349 |
| heavy detail: guest, `x=retain` | 971.5 / 1,022 / 931–1,067 | **126.5** / 132 / 120–136 | 1,126 |

- **Retention takes back from 1,116 to 104.5 ms** (−91%), and the heavy screen's back from 1,123
  to 126.5. It is not yet under 50 ms. Native's back today is 32 ms and the web's 48.
- **Opening also gets faster with retention** (472 → 323.5 ms; heavy 1,099 → 971.5): the list is
  no longer disposed when the detail opens.
- **Native was slower than in every earlier session** (open 43 against 16–16.5, back 32 against
  13). The backs of the others were within 0–6% of runs 1–3 (web 48 against 48–50, guest as
  shipped 1,116 against 1,054.5–1,082); their opens were 7–9% slower than runs 2–3 (web 48 against
  44, run 1's 37; guest as shipped 472 against 436–440.5). The cause is not established: the phone's memory
  pressure and the stack change since run 3 (none in the prototype's native screens) are candidates.

**Where retained back's 104.5 ms goes** (`x=retain,split`, medians, ms from the tap; the as-shipped
`x=split` for comparison):

| Mark | `retain,split` back | `split` back (as shipped) |
|---|---|---|
| guest's `onClick` | 11 | 10 |
| guest's frame starts | 32.5 | 38 |
| composed (applied) | 63 | 995.5 |
| batch sent | 74.5 | 1,081 |
| in the host's tree | 101 | 1,099.5 |
| drawn | **105** | **1,135** |
| changes in the batch | 1 structural, 1 modifier, 0 properties, 0 created | 423 structural, 145 modifiers, 1,111 properties, 145 created |

- The batch is now one modifier change (the list's entry shown) and one removal (the detail).
- What is left: about 11 ms to reach the guest's `onClick`, about 21 ms waiting for the guest's next
  frame, **about 30 ms of composition in QuickJS** (disposing the detail screen and showing the
  list's entry), about 12 ms to encode and send, about 26 ms until the host's tree has
  it, and 4 ms to draw. The 50 ms target needs most of that gone: a host-side back that shows the
  retained entry itself (NF1's host transitions) and lets the guest's frame follow.

**Memory after 10 open/back cycles** (the list shown; two fresh processes each for the guest, one
for native; KiB from `dumpsys meminfo`; QuickJS from `x=mem`):

| Form | Total PSS | Java heap | Native heap | Graphics | QuickJS used / objects (end) |
|---|---|---|---|---|---|
| native | 55,227 | 8,816 | 8,504 | 13,898 | — |
| guest, as shipped | 68,762 / 68,872 | 6,836 / 6,820 | 19,988 / 20,084 | 13,560 / 13,578 | 10.12 MB / 39,612 (both runs) |
| guest, `x=retain` | 68,386 / 68,231 | 9,036 / 9,064 | 18,116 / 17,928 | 13,545 / 13,543 | 8.53 MB / 30,580 (both runs) |

- **After 10 cycles, with the list shown, retention costs nothing measurable** in total PSS
  (68,231–68,386 against 68,762–68,872 KiB): the Java heap is about 2,200 KiB higher and the native
  heap (QuickJS lives there) 1,870–2,160 KiB lower, and QuickJS ends 1.6 MB smaller (QuickJS
  figures are bytes / 10⁶). That end state depends on the number of cycles: retain starts about
  1 MB higher in QuickJS (next point) and only ends lower because the as-shipped form keeps more
  after each cycle. Measured after each back (the list shown), the as-shipped form passes retain
  at the 3rd cycle; after each open (the detail shown), at the 8th.
- **While the detail is open, the retained list holds about 1.0 MB and 6,800 objects in
  QuickJS**: 8.12 MB / 28,307 objects with retain against 7.13 MB / 21,518 as shipped, at the
  first open, before anything builds up (as shipped, that first open *frees* 0.81 MB and 5,591
  objects: the list is disposed). **Per cycle, QuickJS's used memory grows and is not reclaimed**
  (`gcThreshold` stays at 262,144): as shipped about 218 KB and 1,250 objects per open/back cycle
  (7.13 → 9.09 MB after each open, over 10 cycles), with retain about 55 KB and 330 (8.12 →
  8.62 MB). Whether that growth is garbage a later collection would reclaim or a leak is not
  established on the phone. In the Mac harness it is garbage: a full collection reclaims all of it,
  and QuickJS's own collector does every 38 cycles as shipped and every 116 with retain. By the
  same rule the phone would collect at cycle 11–20 as shipped and after about 70 with retain
  (a prediction), so a 10-cycle run stops before that point
  (GUEST_PROFILE.md, "Memory growth per navigation"). Medians over the 10 cycles mix these two trends, so they are not used here. The
  host's side of a retained screen while
  hidden (its views) was not measured: `dumpsys meminfo` was read only at the end, on the list.
- **Depth.** The prototype has two screens, so the retained stack never holds more than the one
  list under the detail: depth 1 and depth 2 behave the same here, and these numbers are the cost
  of one retained screen. Depth 2's cost on a deeper app is about twice that per hidden screen (by
  inference, not measured).

**Scrolling: not measured, because most scripted swipes started no fling.** The guest's
placeholder pass (`x=scroll,blank`, five speeds, 02:04–02:24, 78% → 75%, 27.4–28.5 °C) ran in
full. Then the native reference at 2,133 and 6,667 dp/s (02:25–02:33, 75% → 73%, 27.8–28.5 °C),
then a clean guest pass at 2,133 dp/s (02:33–02:38, 73% → 72%, 28.4–28.5 °C). The guest's `x=scroll` pass was stopped and not run: its
counters were already in the placeholder pass. In every series, **in native as in the guest, most
swipes started no fling**:

| Series | Swipes | Flings that started (nested-scroll velocity > 0) | Of them, not at an end of the list | Moving frames p50 / p90 (ms) | Android's release velocity (`upVelocityDpS`) |
|---|---|---|---|---|---|
| guest, 5 speeds, `scroll,blank` | 94 | 10 | 3 | 33–129 / 56–221 | the speed asked, or 0 |
| guest, 2,133 dp/s, clean | 20 | 1 | 0 | 62 / 117 (down) | 2,133 or 0 (2,133 down, 0 up, median) |
| native, 2,133 dp/s | 20 | 5 | 2 | 59 / 100 (down) | 2,133 or 0 (1,066.5 median) |
| native, 6,667 dp/s | 14 | 6 | 0 | 82 / 184 (down) | 6,667 |

- Android's `VelocityTracker` velocity at the UP is always either exactly the speed asked or 0,
  and several swipes that started no fling had the full speed there (the guest's 6,667 dp/s
  series: all 14 delivered ±6,667, none flung). So the velocity that was 0 was the one Compose
  hands to its fling, not the touch's.
- A swipe that started no fling moved the list by the finger's own travel, mostly about 480–780 px
  (a few 350–480), and stopped at the UP (`stopAfterUpMs` about −3 to −25 ms; two guest swipes
  −50 and −84). Runs 2–3 flung every swipe but the process's first fast one, and
  the list stopped 743–2,248 ms after the UP. Without a fling there is nothing to predict and no
  window to jump to. A drag only reaches the next few rows, so the placeholder pass found **no
  placeholders in any drag**, and that result does not test #258.
- **The cause is not the guest alone:** native's own list lost most flings too (5 of 20 and 6 of 14
  started, against 1 of 20 and 0 of 14 in the guest at the same speeds), with drag frames of
  59–82 ms median, against about 20 ms in run 3. A phone-wide cause is likely but not established.
  Compose's fling velocity was 0 at the UP, the symptom run 2 saw on a process's first fast swipe
  only. Ad hoc `adb` reads during the series, not kept with the record, showed the app in the
  `top-app` cpuset, the big cores at their top clock (2.3 GHz), `MemAvailable` about 2.7 GB and
  1.6–1.7 GB in swap; thermal status was 0 (recorded). The keyguard was showing under the
  app (`overLockScreen`, as in every run). The slow navigation of native, above, is likely the same
  slowdown. Why the frames were slow is not established.
- **The three guest flings that did fling** (one sample each, so indicative only):
  - 2,844 dp/s, the first fling up: 13 placeholder rows on screen at most. **Content was there
    1,245 ms after the UP, 2 ms after the list stopped.** In run 3 the median was 1,602 ms, and
    986–3,164 ms, after the list had stopped. The guest's last call came 3,673 ms after the UP.
  - 1,422 dp/s, two flings up: no placeholders. The list stopped at 730–738 ms (run 3: 743). The
    guest's last call came at 1,432 and 2,203 ms (run 2: 2,167 median), and each fling missed 12–14
    vsyncs (run 2: 0–2).
- **Guest work after the finger lifts, drags only** (median per group, with 3–5 change batches per
  drag in every group): 1,100–2,610 ms for a drag of about 9 rows. The drags are not the flings of
  runs 2–3, so this is not compared with them.

**Gate verdict (part 1):**

| Target | Verdict | Evidence |
|---|---|---|
| Back under 50 ms | **Not met** (measured) | Retained back 104.5 ms median (p90 110), heavy 126.5. As shipped: 1,116. The guest's own composition after the tap is about 30 ms of it. The rest is frame wait, encoding and the host's apply |
| No blank rows at an ordinary fling | **Not measured** | Most scripted swipes started no fling, in native too (native 11 of 34, 2 away from an end of the list; guest 11 of 114, 3 away from an end). One fling at 2,844 dp/s showed 13 placeholders, with content at the list's stop |
| Frames over 25 ms under 2% (by hand) | **Not measured** | Nothing was flung by hand. Today's scripted frames were slow in native too (above) |
| Tap response within one frame | **Not measured** | The host's pressed state is not built yet |

**What remains for NF1's gate:**
- The host's pressed state and transitions (not built). With them:
  - the tap target;
  - a back that the host shows from the retained entry, which is the route to under 50 ms.
- **The scrolling series again**, on a phone whose native list flings as it did in runs 2–3. Before
  the series, check one native fling (velocity > 0, the list stopping about 1 s after the UP). If
  it does not fling, reboot the phone or unlock it before the run.
- The owner's hand check (frames over 25 ms).
- The host's memory while a retained screen is hidden. A deeper app for depth 2.

### NF1 gate, part 2 (back on the host, A12)

2026-11-17, 18:03–18:49 IST, the same A12, restarted about 25 minutes before (uptime and swap were
ad hoc `adb` reads, not kept with the record), on battery 36% → 29%, 29.3–31.5 °C, thermal status 0 in every series. A fresh
prototype build from main at `229e876cf` (#258, #259, #262 and #265 merged). Every series ran from a
script on the phone, each in a fresh process with the in-app fixture; the run stopped itself when the battery fell below 30%
(29% at 18:49) after the counter pass at 1,422–2,844 dp/s (the two fastest counter speeds did not run).

**Back** (median / p90 / min–max in ms; 14 iterations, the first 2 dropped). `drawn` is from the
tap or the press to the guest's tree drawn; `firstDraw` (system back only, `bench=sysback`) is from
the press to the window's first draw after it, which with `retainhost` is the host showing the
retained screen without waiting for the guest:

| Form | Back, drawn | Back, first draw after the press | Open (warm), drawn |
|---|---|---|---|
| native | 31 / 34 / 28–36 | — | 41 / 53 / 36–54 |
| web | 48.5 / 51 / 45–62 | — | 44.5 / 47 / 33–49 |
| guest, as shipped | 873.5 / 887 / 837–898 | — | 467.5 / 491 / 448–493 |
| guest, `x=retain`, header back | 102.5 / 110 / 94–112 | — | 307 / 313 / 285–322 |
| guest, `x=retain`, system back | 97 / 107 / 85–118 | 96.5 / 107 / 85–118 | 315 / 322 / 304–325 |
| **guest, `x=retainhost`, system back** | 201.5 / 219 / 144–237 | **37.5 / 48 / 27–51** | 313.5 / 330 / 302–441 |
| guest, `x=retainhost`, heavy screen | 231.5 / 259 / 189–263 | 56.5 / 74 / 38–74 | 918 / 979 / 902–1,034 |

- **With back on the host (#262) the retained screen is on screen 37.5 ms after the press**
  (p90 48, max 51): under the 50 ms target at the median and p90, close to native's 31 and below
  the web page's 48.5 (native and web: header back, `drawn`; not the same start event; 6.5 ms behind
  native at the median, 14 ms at p90, 48 against 34). The guest confirms later (`drawn` 201.5 ms; `composed` 172.5 in the split
  run), The confirming batch carries one structural and one property change; whether the screen visibly
  changes when it lands was not recorded.
- The heavy screen (about 100 widgets) shows its retained list at 56.5 ms (max 74): over the
  target. Investigated since (Mac only): see [Back on the heavy screen](#nf1-back-on-the-heavy-screen-where-the-extra-time-went-mac).
- Guest-side retention alone (#259) stays at about 100 ms, as in part 1; as shipped the guest's
  back is 873.5 ms (part 1: 1,116, a phone in worse state).
- Open is unchanged (about 310 ms). #265 is in the build, but its touch-down pre-composition is not
  exercised: the host has no press signal yet, so this bench's tap opens as before. Open is NF2's
  and NF3's.

**Scrolling: still not measurable with scripted swipes.** The phone had been restarted, yet in 18
of 20 groups the list's median stop came 3.9–17.6 ms *before* the finger lifted (`stopAfterUp` −3.9
to −17.6): no fling. The exception was the guest's second 300 ms group (1,422 dp/s, upward), which
flung in both passes (`stopAfterUp` +357.7 and +363.1, 6 flings each, 4 more excluded at the end of
the list). Native never flung (its 80 ms second group: all 7 excluded at the end). No `blank` run
recorded a placeholder (including its 6 flings at 1,422 dp/s), nor content arriving after the stop
(`contentAfterUpMsMedian` null); the counter (`scroll`) pass has no placeholder fields. A restart did
not bring the flings back, so accumulated state (swap, processes) is not the cause; whether the
phone was unlocked was not recorded, and why `input swipe` no longer starts flings is not
established. NF1's scrolling targets are judged by hand (the owner), as the gate says ("by hand").

**Gate verdicts, part 2:** back under 50 ms **met** for the simple screen with back on the host
(37.5 / p90 48; heavy screen 56.5, not met); no blank rows at an ordinary fling and frames over
25 ms **not measured** (scripted flings mostly did not start; 12 guest flings at 1,422 dp/s, none
with placeholders: indicative only; a hand check is next); tap within one frame
**not measured** (no host pressed-state instrumentation). Raw output:
`keliver-scratch/nf1-gate2/out` (not in this repository).

### NF1, back on the heavy screen: where the extra time went (Mac)

2026-11-21, **the Mac only** (JVM tests; no phone): relative numbers, and an A12 run is still to
come. Part 2's question: why does the heavy screen's host back take 56.5 ms to the first draw when
the simple screen's takes 37.5? In both, the host shows the same retained list.

**What `firstDraw` covers: recompose and apply, very likely not measure and layout.** The mark is
a `ViewTreeObserver.OnDrawListener`. In the platform's source (the SDK's `android-36.1` sources),
`ViewRootImpl.draw` calls `mTreeObserver.dispatchOnDraw()` and only then
`ThreadedRenderer.draw`, which opens the `Record View#draw()` trace section and walks the views'
display lists, so `AndroidComposeView.dispatchDraw` runs after the mark. A view tree's own
`requestLayout()` is measured and laid out earlier, in `performTraversals`, before the draw. As
Compose's `AndroidComposeView` is remembered here (its source could not be opened in this
session, so this half is **not checked**), when a layout changes inside the root but the root's
size does not, it calls `invalidate()`, not `requestLayout()`, and measures and lays out at the
start of `dispatchDraw`. A retained back changes which entry the container measures, not the
root's size, so on that reading `firstDraw` covers the wait for the next vsync, the recomposition
in that frame's animation callback and applying its changes, and not measure, layout or drawing.
The A12 recipe below adds a trace that shows where the layout runs. At 60 Hz the extra 19 ms is
about one frame: the heavy frame's work missed a vsync that the simple frame made. Both series
also start high and fall (heavy 132, 149, then 74 … 38; simple 63 … 27): the
host's code warming up over the series.

**The cause, found in the code.** Each entry of the host's retained container (`ExtraRetained`,
`Retained.kt`) is wrapped in a slot that provides `LocalHostBackDispatcher`: null while the entry is
hidden, the dispatcher while it is shown. That local was a *static* composition local. When a
static local's value changes, everything under the provider recomposes without skipping. So a
host back recomposed every widget of both entries: the list shown again, and the heavy detail
(102 widgets) as it was hidden. Each rebuilt widget then updated its modifiers, so their layout
was redone too. `RetainedHostBackTest.goingBackRecomposesNeitherEntrysContent` proves it: before
the fix, the entry shown again composed a second time on the back.

**The fix.** `LocalHostBackDispatcher` is now an ordinary (dynamic) `compositionLocalOf`, so a change
recomposes only the places that read it (a nested stack's container). Its declared type is the
same (`ProvidableCompositionLocal`). This module is a prototype with no API dump, so its API does
not change.

**Measured on the Mac** (`RetainedBackFrameBenchmark`): the prototype's list and both details, ported
to the same generated guest API, run through the real protocol into the host's renderers. Each
back is pressed with the guest held, and the one host frame after the press is timed: recompose,
measure, layout and draw. 40 warm-up backs, then 60 measured (ms). A plain `jvmTest` run (CI's)
presses back 5 times with no warm-up and measures nothing; these numbers are
`-Dkeliver.retainedBack.measured=60` on the Gradle command line, which the module's `jvmTest` task
forwards to the test JVM (as it does every `keliver.retainedBack.*` property):

```
./gradlew :keliver-compose-gatea-composeui:jvmTest --tests '*RetainedBackFrameBenchmark' \
  -Dkeliver.retainedBack.measured=60     # results: that module's build/retained-back-frame.txt
```

| | Before | After |
|---|---|---|
| heavy screen: median / p90 | 1.43 / 1.67 | **0.76 / 0.93** |
| simple screen: median / p90 | 0.91 / 1.15 | **0.48 / 0.58** |

A profiler's run (3,000 backs each, sampling the UI thread during the timed frame) splits the
frame. Counts are samples; recompose and apply is the part the phone's `firstDraw` very likely
sees (above), and measure, layout and draw come after it:

| | Recompose and apply | Measure and layout | Draw |
|---|---|---|---|
| heavy, before | 19,998 | 13,685 | 11,835 |
| heavy, after | 3,582 | 3,760 | 10,218 |
| simple, before | 7,978 | 11,458 | 10,815 |
| simple, after | 1,287 | 2,964 | 10,091 |

- The heavy screen's recompose and apply, the part `firstDraw` sees, falls by 82% (19,998 → 3,582
  samples), and its lead over the simple screen there by 81% (12,020 → 2,295). Measure and layout,
  which come after the mark, fall too (13,685 → 3,760): recompose plus layout falls by 78% (33,683 →
  7,342), but a phone's `firstDraw` will not show that part.
- The simple screen gains as well (recompose and apply 7,978 → 1,287): the list shown again was
  being recomposed too.

**What is left (not fixed here):**
- **Compose scans the whole composition after a state change.** About a third of what remains
  before the draw (2,600 of the heavy screen's 7,342 samples) is the Compose runtime's
  `addPendingInvalidationsLocked`. After a state change it goes through every state read
  recorded in the composition, so its cost grows with everything retained, including the hidden
  heavy detail, whose generated widgets keep one state per property. Lowering it means fewer
  state reads (a generator change) or each entry in its own composition (a larger change to
  `ExtraRetained`). The generator change was made and measured later: see
  [NF1, one Compose state per generated widget](#nf1-one-compose-state-per-generated-widget-mac).
- **Hiding and showing entries re-places their trees.** Hiding the detail un-places it (about 700
  samples); showing the list places it again and recreates its layers. The draw then re-records
  the list. Keeping hidden entries placed (off screen, with their layers kept) would avoid this.
  A trial on the Mac, not kept in this change: every entry measured, the hidden ones placed off
  screen in a layer with alpha 0. Medians fell to 0.40 ms for the heavy screen (from 0.78) and
  0.34 for the simple one (from 0.49). In the heavy screen's profile, the part before the draw fell
  from 7,342 to 4,970 samples; the simple screen's profile run was too noisy to use. The costs:
  the memory the hidden layers hold, layout work on hidden entries while the guest changes them,
  and redrawing a hidden layer on every frame of an animation that runs inside it. That is a
  policy decision, and it should be measured on a phone first, **by the trace in the A12 recipe
  below, not by `firstDraw`**: what it saves is placement, layers and drawing, which come after
  the mark.
- **The guest's confirmation.** About 150 ms after the press, the guest's batch removes the
  detail. The host then disposes of the detail's widgets in a later frame. That frame is not in
  `firstDraw`; whether it drops a frame is not measured.

**A12 before/after (prepared, not run).** Build the APK twice, once from main and once from this
change, as in [How to run it](#how-to-run-it). Both checkouts publish the prototype as
`0.0.0-gateb` into the same `/tmp/m2`, and the second build writes over the first's APK, so build
them one after the other, each straight after its own publish, and copy each APK aside:

```
mkdir -p ~/nf1-back
# 1. in the main checkout (root, then sample/), exactly as in How to run it:
./gradlew publishToMavenLocal -Dmaven.repo.local=/tmp/m2 -PkeliverVersion=0.0.0-gateb \
  -PkeliverComposePrototype -DRELEASE_SIGNING_ENABLED=false
(cd sample && ./gradlew :host-android:assembleBenchmark -Dmaven.repo.local=/tmp/m2 -PkeliverComposePrototype)
cp sample/host-android/build/outputs/apk/benchmark/*.apk ~/nf1-back/main.apk
# 2. in this change's checkout: the same two commands again (republishing over main's /tmp/m2), then
cp sample/host-android/build/outputs/apk/benchmark/*.apk ~/nf1-back/change.apk
```

Install each in turn (`adb install -r ~/nf1-back/<build>.apk`) and run, from the Mac (`A` as in
"The rerun, series by series"):

```
adb shell "$A -e form guest -e bench sysback --ei n 14 --ei warmup 2 -e x retainhost"
adb shell "$A -e form guest -e bench sysback --ei n 14 --ei warmup 2 -e x heavy,retainhost"
# wait for {"done":true} in the results file after each launch, then
adb pull /sdcard/Android/data/dev.keliver.sample/files/threeway-results.txt
```

Alternate the builds (main, change, main, change) so the phone's drift shows. Compare the
`firstDraw` medians in the `"summary":"back"` lines. Target: the heavy screen under 50 ms. That
target is on `firstDraw`, which very likely sees only recompose and apply (above): under 50 ms there
does not mean the pixels arrive in 50 ms.

**A measure that sees layout and the pixels.** One more heavy series per build, under a Perfetto
trace, recorded as in "Method" for the scrolling traces (`perfetto --txt -c -` on the phone, the
same atrace categories with `gfx view` and `atrace_apps: dev.keliver.sample`, and SurfaceFlinger's
frame timeline), with `x=trace` added so each main-thread message is a section:

```
adb shell "$A -e form guest -e bench sysback --ei n 14 --ei warmup 2 -e x heavy,retainhost,trace"
```

For each back after the warm-up, take the main-thread message that presses it (the bench's step,
which calls `onBackPressedDispatcher.onBackPressed()`), then the first `Choreographer#doFrame` after
it, and read:
- **where the layout runs:** whether a Compose measure-and-layout section (`AndroidOwner:...`
  sections from Compose's own tracing; the exact name is to be read from the trace) sits inside
  `Record View#draw()` (after the `firstDraw` mark, as read above) or in `traversal` before it;
- **main-thread work:** the press to the end of that `doFrame` (recompose, apply, measure, layout,
  recording), and `Record View#draw()`'s own duration;
- **the pixels:** the press to the present of that frame in SurfaceFlinger's actual frame timeline
  for the app's layer (or that frame's FrameMetrics `TOTAL_DURATION` added to the press-to-intended-vsync
  gap, which the sysback bench does not log today).

Trace numbers compare only with trace numbers (`x=trace` adds a section per message). **Decision 1
(keep hidden entries placed) is judged on these, not on `firstDraw`**: its saving is placement,
layers and drawing, which come after the mark. If the heavy screen's press-to-present is already
within the target with this change alone, it is not needed.

### NF1, one Compose state per generated widget (Mac)

2026-11-21, **the Mac only** (JVM tests; no phone). This follows up the item above, "Compose scans
the whole composition after a state change". When a snapshot change invalidates a scope, Compose's
`CompositionImpl.addPendingInvalidationsLocked` walks every state read the composition recorded.
Gate A's generated renderers held one `mutableStateOf` per property plus one for the modifier, and
each renderer reads all of them, so each widget recorded about a dozen reads.

**The change.** The generator (`keliver-compose-generator`, `Emit.kt`) now gives each renderer one
`mutableIntStateOf` version. The properties, events and the modifier are plain fields. A setter
compares the new value with `==`, which is what `mutableStateOf`'s structural policy did, and bumps
the version only on a change. The renderer reads the version first, and reads every plain field in
its own scope, never inside a lambda (`GeneratorTest.noRendererReadsAPlainFieldInsideALambda`
checks all 29 renderers). The guest API and the wire are unchanged. Two points needed care:
- **The modifier.** A container reads its children's modifiers (`ScopedChildren`,
  `ComposeWidgetChildren`). It still recomposes on a modifier change through its own tick, which
  Redwood's host bumps with `onModifierUpdated` after every modifier set.
- **A lazy list's counts stay snapshot state.** They are two `mutableIntStateOf` of their own,
  outside the version, read only inside the list's content lambda, as before the change. Compose
  runs that lambda in a derived state, so a scroll window shift, which changes only the counts,
  updates the list's items without rerunning the renderer or recomposing `LazyColumn`
  (`OneStatePerWidgetTest.aLazyListsCounts`). That is two recorded states per lazy list, not per
  widget. A first version of this change put the counts in the version too, read in the
  renderer's scope and captured: every window shift then recomposed the list's renderer and
  `LazyColumn`, and the scroll's record + recompose + apply rose 16% (9,649 → 11,237 samples, with
  1,479 in the renderer's lambda). The independent review caught it; it is fixed, and the numbers
  below are measured after the fix.

**Method (repeatable).** `RetainedBackFrameBenchmark` now has four scenarios. The two backs are
unchanged. The two guest batches are new:
- a text change on the heavy detail, with the list retained below it;
- a window shift on a 1,000-row list: the host scrolls 30 rows, then the guest's batch arrives.

A batch's timed window is only the host's part: applying the batch, the snapshot's apply
notifications, and the frame. The recomposer records the changes, and so runs the scan, when it is
notified. On a phone that happens in a main-thread message before the frame, so a window that
covered only the frame missed it. Each scenario also writes the state reads the host's
compositions record (`StateReads`, read by reflection). The same commands as above:

```
./gradlew :keliver-compose-gatea-composeui:jvmTest --tests '*RetainedBackFrameBenchmark' \
  -Dkeliver.retainedBack.measured=60                      # medians
./gradlew :keliver-compose-gatea-composeui:jvmTest --tests '*RetainedBackFrameBenchmark' \
  -Dkeliver.retainedBack.measured=2000 -Dkeliver.retainedBack.sample=true   # profile
```

**State reads recorded** (all of the host's compositions; the heavy back is counted with the detail
pushed):

| | Before | After |
|---|---|---|
| heavy back (list + heavy detail, 10 compositions) | 2,335 reads of 2,145 states | **423 of 419** (−82%) |
| text change (1 composition) | 1,337 of 1,231 | **196 of 193** (−85%) |
| simple back | 1,294 of 1,184 | **285 of 281** (−78%) |

**Profile** (2,000 timed steps; inclusive samples of the UI thread inside the window; record +
recompose + apply is `recordComposerModifications` + `CompositionImpl.recompose` +
`CompositionImpl.applyChanges`). One session: the change's `Generated.kt` swapped for the one before
it for the "before" run, then the change measured twice, to show the run-to-run noise:

| | Scan (`addPendingInvalidationsLocked`) | Record + recompose + apply | Measure and layout | Draw | All samples |
|---|---|---|---|---|---|
| heavy back, before | 1,592 | 4,311 | 2,778 | 7,019 | 20,512 |
| heavy back, after (runs 1, 2) | 1,219; 1,305 | 4,101; 4,055 | 3,016; 2,777 | 7,356; 7,446 | 21,195; 21,374 |
| text change, before | 1,197 | 2,866 | 2,368 | 8,257 | 22,800 |
| text change, after | **479; 421** | **1,994; 1,776** | 2,337; 2,098 | 8,106; 7,477 | 21,708; 20,150 |
| simple back, before | 808 | 2,016 | 1,660 | 6,379 | 15,764 |
| simple back, after | 527; 848 | 1,870; 2,014 | 1,830; 1,822 | 6,593; 7,009 | 16,345; 16,987 |
| scroll shift, before | 1,000 | 10,945 | 57,876 | 12,171 | 106,404 |
| scroll shift, after | 1,003; 969 | 9,977; 9,903 | 53,567; 52,048 | 12,135; 11,961 | 101,498; 99,630 |

- **Before the change, the scan was a real share of what the phone's `firstDraw` sees.** It was
  37% of record + recompose + apply on the heavy back and 42% on the text change. Measured against
  the whole window, draw included, it was 7.8% and 5.3%.
- **A small batch gains the most.** The text change's scan fell by 60–65% (1,197 → 479 and 421)
  and its record + recompose + apply by 30–38%. **Its whole window did not reliably shrink**: the
  scan is a small part of it, and draw and measure vary more between runs than the scan saved. In
  the first measuring session (before the review) the total rose, 20,016 → 21,142 samples, while
  the scan fell 75%; in this one it fell 5% and 12%. The medians below agree: no visible change.
- **The heavy back's scan fell by only 18–23%**, although its reads fell by 82%, and its whole
  window did not shrink (20,512 → 21,195 and 21,374). What remains of the scan is sampled mostly in
  `Object.hashCode`, the identity hash of the scopes the walk tests. That points to a cost per
  invalidated scope or per composition, not per recorded read. The profiler samples at safepoints
  (`Thread.getStackTrace`) and cannot split the method further: **not resolved**.
- **Scrolling does not regress.** Its window is dominated by measuring and subcomposing the new
  rows (about half the samples). The scan is 1% of the window before and after; the renderer's
  recompose is unchanged (1,477 → 1,498 and 1,467 samples), and neither the list renderer's lambda
  nor `LazyColumn` appears in the window, as before the change. Its record + recompose + apply fell
  9–10%, within this machine's noise.
- **Medians (60 steps) moved within the noise** of this shared machine: heavy back 0.797 → 0.661
  ms, text change 0.461 → 0.411, simple back 0.495 → 0.396, scroll shift 3.610 → 3.112 (one run
  each; the first session's were 0.609 → 0.618, 0.416 → 0.392, 0.390 → 0.355, 3.130 → 2.762).

**Not measured:** a phone (the A12 recipe above applies unchanged). keliver-material-composeui is
unchanged: its 64 widget classes hold 198 per-widget field states (`mutableStateOf` in a field,
not counting imports or a composable's own `remember { mutableStateOf }`), about 3.1 per widget;
62 of them override the modifier, which is already a plain field. The same pattern applies there.
On those numbers, the estimate is a cut of about 68% in recorded reads per widget, a smaller gain
than gate A's twelve reads to one, and it would be a hand port: every state-dependent lambda in 62
renderers checked by hand, with a test per widget that each setter's change recomposes.

### NF4, step 1: the code load at process start, and the first request sent by the host (emulator)

2026-11-18, **the emulator only** (AVD `keliver_a12_api35`, arm64, Android 15, on the Mac), so
these numbers are **relative only**: the emulator's process start → `Activity.onCreate` is about
100 ms against the A12's 359, and its list composition is many times faster than the A12's. The
A12 run is still to come. Items 2 and 3 of "Where NF4 can take the time" (above), as two guest
experiments, so one build measures every arm:

- **`x=prestart`**: the guest's code load starts in `bindApplication`, from a ContentProvider
  (`ThreewayPrestartProvider`), instead of after the activity's first frame. The provider cannot
  see the launching intent, so it acts on what the previous launch asked for (a preference the
  activity writes): the first launch of a series does not prestart. Production hosts already
  start in `Application.onCreate` (M3, `HostApp.kt`); this is the prototype's equivalent.
- **`x=prefetch`**: the list's first request (`GET /items?size=small&seq=1`) is sent by the host as
  it starts the guest (at process start with `prestart`, otherwise in `onCreate`), and the
  guest's identical request takes the response. The guest is unchanged. Production hosts get the
  same thing as `KeliverConfig.prefetch` (`HostPrefetch.kt`; [`DEVICE_HOST.md`](DEVICE_HOST.md)),
  sent at the first activity's creation (only a start toward a screen sends it), so on a device
  it goes out somewhat later than this prototype's process-start send with `prestart`.

**Method.** The prototype at this PR's commit (published to a scratch Maven repository as in "How
to run it"), `ThreewayStartupBenchmark#guest` with `-e localApi true` and the new `-e x <experiments>`
argument, 10 iterations per arm (StartupMode.COLD), and the baseline twice. The emulator shared
the Mac with other builds, so the baseline has outliers. Each process's `"cold"` line was grouped
by what actually ran in it (a prestart mark, and the request answered within 10 ms). Processes
whose prestart was not used are left out: a series' first launch inherits the previous
series' preference. The table gives the medians of those lines (ms from the process start).

- **No process was reclassified by the 10 ms rule** (checked in the raw lines after the review):
  every guest process that sent the prefetch (21, the two left out included) had the guest's
  request answered in 0–1 ms, and every one that did not (29) in 104–128 ms. So "answered within
  10 ms" and "the prefetch was sent" pick the same processes, and no prefetch process whose answer
  was late was left out of its arm.
- **Left out: two processes**, the first launches of the as-shipped series (it inherited
  `prestart,prefetch`) and of the `prestart,prefetch` series (it inherited `prestart`): in both the
  prestarted app was refused for its other experiments. The first launch of the `prestart` series
  inherited nothing and ran as shipped, so it is counted there (hence 20 as shipped, 9 per prestart
  arm). The native series' first process inherited `prestart,prefetch` and started a guest code load
  in the background; it is counted (347 ms; native's median is 324 without it).
- **Since the review**, the benchmark's setup makes one unmeasured launch with the series' own
  form and experiments before the first iteration, so every measured process acts on its own
  series' preference and none of the above recurs. The runs recorded here predate that.

| Arm | Processes | List's data drawn: median (min–max) | Code load starts | First frame | Guest's request → host's answer |
|---|---|---|---|---|---|
| as shipped | 20 | **938** (793–2,887) | 237 | 206 | 108 |
| `prefetch` | 10 | **802** (726–1,126) | 214 | 185 | 1 |
| `prestart` | 9 | **773** (738–905) | 145 | 168 | 107 |
| `prestart,prefetch` | 9 | **666** (642–702) | 128 | 148 | 1 |
| native (reference) | 10 | 325 (305–412) | — | 153 | — |

Macrobenchmark's own `timeToFullDisplayMs` medians agree: as shipped 931.5 and 990.1 (two
series), `prefetch` 805.9, `prestart` 818.8, both 702.1, native 365.2. Its `timeToInitialDisplayMs`
did not grow (as shipped 263.5 / 256.7, `prestart` 237.6, both 210.6).

- **The first frame's median does not grow with the prestart.** The code load now starts before
  the first frame (145 against 168, medians). The first frame's median (min–max) is 206 (129–891)
  as shipped, 185 (149–480) with `prefetch`, 168 (125–193) with `prestart` and 148 (120–172) with
  both; the prestart arms' slowest first frames (193, 172) are below the as-shipped median, so
  none was held back past the baseline's own range. The prestart's
  main-thread work (creating the factory and the app, `start()`; the benchmark's fixture server
  is measured apart) is 2.3 ms median (1.8–6.2; 2.9 with the prefetch, at most 8.2), against
  1.0 ms for the same `create` in `onCreate` as shipped.
- **The prefetch removes the whole network wait from the guest's path**: the fixture's 100 ms
  plus the hop, 108 → 1 ms. The response is there before the loading state's first composition
  ends.
- **Together, −272 ms (−29%) on the emulator** (`prefetch` alone −136, `prestart` alone −165). On the A12 the prestart should gain more: there
  the code load can move earlier by up to the 575 ms between the process start and the first
  frame. Here it moved about 90 ms earlier (237 → 145), and the arm still gained 165 ms, more than
  that, by a margin within the baseline's noise. NF0's estimate for the A12 is −200 to −300 ms. The prefetch saves the
  network time (about 150 ms against NF0's fixture; more against a slower API, up to the time
  from the process start to the guest's request). Neither is measured on the A12 yet.
- **Not built:** item 4 (unpacking the bundle into the cache in the background after an install
  or an update): a production host has no embedded bundle, so its first launch loads from the
  network. That item applies only to the prototype and to apps that embed a bundle.
- **iOS (added after this run):** the production iOS host template has the same prefetch
  (`Keliver.shared.prefetch`, the same `HostPrefetch.kt`; [`DEVICE_HOST.md`](DEVICE_HOST.md) §3).
  It goes out when the process starts toward a screen: the host starts while a connected scene is
  in the foreground, or the first scene enters the foreground, or the first Keliver screen is made
  while a scene is in the foreground. This benchmark measured nothing on iOS; a simulator run since
  (`DEVICE_HOST.md` §3, "When the host starts") compares the arms there, and no iPhone run has
  measured what it saves.

### Scrolling, run 2

**How.** As in [How to run it](#how-to-run-it): `adb shell input swipe` through the input system,
each speed a fresh process, 10 flings down the list then 10 back up (fast: 7 each way), the
review-round-1 route (release speed from the touch events, missed vsyncs, frames split at the list's
stop, flings closed by a touch, busy at DOWN or at an edge dropped from the summaries). Speeds (the
release speed is the touch events' own, the same in every fling of a speed): gentle 800 px in
300 ms = **1,422 dp/s**; 800 px in 200 ms = **2,133 dp/s**; in 150 ms = **2,844 dp/s**; in 120 ms =
**3,556 dp/s**; fast, 1,000 px in 80 ms = **6,667 dp/s**. Gaps 8 s (gentle), 12 s (intermediate),
14 s (fast): **no fling in any series was closed by a touch or busy at its DOWN**. The last fling
up reaches the top in every series (it travels what the flings down did), so the "up" groups
have one edge fling dropped (fast: two).

**Guest, clean pass** (no counters; 2026-11-16 11:56–12:16 IST, 58% → 55%, 25.0–25.6 °C, thermal 0).
Per fling: missed vsyncs while the list moves (median, min–max, total over the group), the moving
frames' `TOTAL_DURATION` p50 / p90 (frame timing, for comparison only), when the list stopped, and
when the guest's last frame ended (both from the UP):

| Guest | Flings | Missed vsyncs (median, range; total) | Moving frames p50 / p90 | List stops | Guest busy until (median, range) |
|---|---|---|---|---|---|
| 1,422 dp/s, down | 10 | 1 (0–1; 7) | 20.3 / 20.8 ms | 743 ms | 2,874 ms (1,508–3,004) |
| 1,422 dp/s, up | 9 | 1 (0–2; 10) | 20.2 / 20.7 | 743 | 2,167 (1,286–2,321) |
| 2,133 dp/s, down | 10 | 1 (0–2; 7) | 20.2 / 20.7 | 992 | 3,662 |
| 2,133 dp/s, up | 9 | 1 (0–2; 8) | 20.2 / 36.9 | 992 | 2,640 |
| 2,844 dp/s, down | 10 | 1.5 (0–5; 18) | 20.2 / 20.8 | 1,210 | 4,152 |
| 2,844 dp/s, up | 9 | 1 (0–1; 8) | 20.2 / 20.6 | 1,209 | 2,867 |
| 3,556 dp/s, down | 10 | **8 (7–11; 87)** | 10.4 / 20.6 | 1,419 | 4,932 (4,771–5,866) |
| 3,556 dp/s, up | 9 | 1 (0–3; 8) | 20.3 / 21.0 | 1,425 | 3,852 (3,421–5,551) |
| 6,667 dp/s, down | 7 | **15 (3–20; 97)** | 20.0 / 36.6 | 2,248 | 6,617 (1,242–7,232) |
| 6,667 dp/s, up | 5 | 3 (2–6; 19) | 20.1 / 36.9 | 2,247 | 7,555 (5,357–7,909) |

**The process's first fast swipe started no fling, again.** In the fast series, fling 1 (the
process's first drag) travelled 848 px, the finger's own distance: the touch events' velocity was
12,500 px/s like every other, but the list's fling started at 0 (`flingVelocityPxS` 0), and the
swipe took 140 ms to deliver against 80 asked. The first run saw the same in one of its three fast
runs (each a fresh process, so also on the first drag); with this one, two of four. The release
velocity now shows where it is lost: Android's tracker saw a fling, Compose's did not, on a drag
whose frames ran late (the process's first). The cause is not established. It is kept in the
"down" group above (its 3 missed vsyncs and 1.2 s are the low ends of the ranges); the other six
moved 10,466–10,766 px.

**Reading them.**
- **Up to 2,844 dp/s the list moves as smoothly as it can on this phone:** a median of 1 missed
  vsync per fling (0–5) over 60–85 moving frames. From **3,556 dp/s down the list it starts to
  stutter**: 7–11 missed vsyncs in every one of the 10 flings, and 3–20 at 6,667 dp/s. Flinging
  back up at the same speeds stays at 0–6. The guest is composing rows for the windows the list
  enters while it moves; why only the "down" direction pays was not analysed (the per-frame
  components are in the raw `frameDetail` lines).
- **The ~20 ms frames are not the guest's.** The moving frames' median `TOTAL_DURATION` is about
  20 ms at most speeds, with the GPU component 11–15 ms of it. The native smoke flings (one gentle,
  one fast; **smoke, not a series**) show the same: median 20.6 / 20.7 ms with an 11–12 ms GPU
  component, 0 and 3 missed vsyncs; the web's 13 ms (gentle, GPU 3.6 ms) and 20.1 ms (fast, GPU
  11 ms), 0 missed. The native list costs about the same per frame as the guest's host list, so
  missed vsyncs, not frame time, are where the guest differs.
- **The guest keeps working long after the list stops.** Its last frame ends 2.2–2.9 s after the
  finger lifts at the gentle speed, 3.9–4.9 s at 3,556 dp/s and 6.6–7.6 s at 6,667 dp/s (the list
  stops at 0.7, 1.4 and 2.2 s). Whether that work shows as placeholders is what the instrumented
  pass measures; run 2 did not get it (the phone left during that pass); **run 3 did** (above):
  the first placeholders come at 2,133 dp/s, and from 2,844 dp/s most of the screen is blank for
  seconds after the list stops.

**What a user would feel (from run 2's guest pass alone; run 3 corrects it, see "Scrolling, run 3").**
Run 2 read "up to about 2,800 dp/s feels like a native list" from missed vsyncs only; run 3's
placeholder pass shows that at 2,844 dp/s the list itself moves smoothly but stops on a mostly blank
screen. Run 2's reading, as written then: gentle and
moderate flicks (up to about 2,800 dp/s, a relaxed thumb flick) feel like a native list. A brisk
flick down the list (3,500 dp/s and faster) likely shows a stutter while it moves: 7–11 skipped
frames per fling at 3,556 dp/s and 3–20 at 6,667 dp/s, where native's one smoke fling at
6,667 dp/s skipped 3 (native's series was not run, so that comparison is one fling). And after a fast
flick the guest is still filling the list for several seconds after it stops; the first run saw
that as a blank list, which run 2 could not re-measure.

### Scrolling, first run (guest only; superseded by run 2)

**Superseded.** The first session's guest-only numbers below came from the old route (last blank
frame as "settled", frame times as the only frame measure, 8 s gaps, two speeds, edge flings
pooled); run 2 above replaces them. They are kept for the record and for what they said about the
method; where they and run 2 differ, run 2's are the numbers.

**How (first run).** The route loads the 1,000 items (tap "Load 1,000"), then the flings come from outside the
app, through the input system as a finger's would: `adb shell input swipe`, 10 down the list, then
10 back up, per run, each run in a fresh process. **"Normal" is a gentle fling:** 800 px in 300 ms
(about 2,700 px/s at release, about 1,400–1,500 dp/s); the guest's list travels about 11 rows per
fling. **Fast:** 1,000 px in 80 ms (about 12,500 px/s, about 6,000–7,000 dp/s); about 106 of the
guest's rows per fling, so the tenth reaches the end of the list (row counts are the guest's
only: its undelivered rows are 48 dp placeholders, its real rows 72 dp, so the same swipe passes
about 1.5 times more items than in native). 7 s (normal) or 8 s (fast) between flings. **8 s was
too short for fast flings:** in several fast-up flings the guest was busy until 7.75–7.96 s after
the UP, so they were closed by the next touch, not by settling, and part of the fast-up vs fast-down
difference may be work carried over. The rerun uses 14 s and drops flings closed by a touch.
Per fling the route logged:

- **Frame times** from the window's FrameMetrics (`TOTAL_DURATION`, from the frame's intended vsync
  to the RenderThread's completion): the same numbers `dumpsys gfxinfo <package> framestats`
  reports, read in the app so each fling's frames are its own (a fling: the DOWN until nothing has
  been drawn for 1 s and, for the guest, no guest frame has run for 1 s). Comparable across the
  three forms because all three draw through this one window (the WebView through its draw
  functor); what it cannot see is Chromium's own renderer falling behind (checkerboarding), so for
  the web form it is a lower bound. **It is not a jank measure:** `TOTAL_DURATION` depends on where
  a frame starts against vsync and on frames queued behind earlier ones, not only on the frame's
  work (fast fling 2 has about 60 frames at 20.0 ± 0.3 ms, then 11–14, then 8–9 ms, with no change
  in workload). The rerun counts missed vsyncs instead (below).
- **Settled:** the last drawn frame at which the list was still moving or still showed a
  placeholder, from the fling's UP. **A lower bound:** while the list sits still waiting for the
  guest nothing is drawn, so the rows appear in the *next* frame, which can come a whole guest frame
  later (0.4–3.3 s). The rerun records that first frame with content. This needs the semantics tree read in every frame (the list's
  scroll position and which rows have content), so it comes from a second, instrumented pass
  (`x=scroll,blank`); its frame times are not the ones reported.
- **Guest only:** its frames (`AppLifecycle.sendFrame` calls, which compose and send the batch
  synchronously), their summed and longest time, and the widgets the guest created (read from the
  guest after the fling settled; a row is 9 widgets). **A row the guest has not delivered yet** is
  drawn by the host as an empty 48 dp placeholder (`LazyPlaceholder` in
  `keliver-compose-gatea-composeui`, `LazyRuntime.kt`): the host's `LazyColumn` knows the item count,
  and every item outside the guest's window is a blank spacer until a batch fills it. The
  instrumented pass counts the viewport height no row with content covers, in placeholders.

**Guest, measured** (2026-11-16 03:47–03:57 IST; on battery, 85% → 84%, 27.5–27.6 °C, thermal 0).
Frame times in ms over all frames of 10 flings (frame timing, not jank: see above); the other
columns are medians per fling. "Content settled" is the last blank frame: **at least** this, and
at most the guest's busy end in the last column. The fast pools include flings that are not normal
flings: fast fling 10 down hit the end of the list (stopped 284 ms after the UP, 48 frames), fast
fling 20 hit the top (31 frames), and in the clean fast pass fling 1 moved only 3,012 position
units (about 6 rows, 8 frames) against about 52,500 for the others. That fling's travel is the
swipe's own 1,000 px: the list followed the finger and **no fling started**. Its first frames took
37–60 ms during an 80 ms swipe (the process's first drag); fling 1 in the other two fast runs
travelled normally, so it is a one-off whose cause is not known. The rerun logs the release
velocity (the touch events' and the velocity the list's fling started with), which will show it.

| Guest, 10 flings each | Frames | p50 | p90 | p99 | max | > 16.7 ms | > 33 ms | > 100 ms | List stops (after UP) | Content settled (after UP; at least) | Flings with placeholders | Guest busy after UP |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| normal, down | 646 | 14.1 | 20.5 | 21.8 | 25 | 43% | 0% | 0% | 711 ms | **711 ms** | 0 of 10 | 2,897 ms |
| normal, up | 646 | 20.2 | 20.8 | 39.7 | 64 | 63% | 2.5% | 0% | 700 ms | **700 ms** | 1 of 10 (under half a row) | 2,129 ms |
| fast, down | 1,175 | 20.0 | 31.3 | 97.2 | 169 | 58% | 9.2% | 0.7% | 2,222 ms | **4,855 ms** (p90 5,011) | 10 of 10 | 6,462 ms |
| fast, up | 1,252 | 20.1 | 22.9 | 84.5 | 150 | 72% | 3.0% | 0.3% | 2,239 ms | **6,723 ms** (p90 7,256) | 9 of 10 | 7,449 ms |

| Guest work per fling (median) | normal, down | normal, up | fast, down | fast, up |
|---|---|---|---|---|
| Guest frames (with a change batch) | 5 (4) | 6 (5) | 6 (5) | 6 (4) |
| Guest time, all its frames | 2,923 ms | 2,140 ms | 5,689 ms | 6,688 ms |
| Its longest frame (max over the 10) | 1,284 ms | 965 ms | 3,270 ms | 3,313 ms |
| Widgets created, 10 flings (rows) | 1,413 (157) | 1,701 (189) | 4,428 (492) | 6,525 (725) |
| Guest ms per new row (its time / rows created) | 176 | 105 | 104 | 80 |
| Placeholders on screen, at most | 0 | 0 | 14 (the whole list) | 14 |
| Frames showing a placeholder, 10 flings (instrumented pass) | 0 of 646 | 13 of 644 | 1,198 of 1,206 | 1,158 of 1,303 |

The instrumented pass reproduced the clean pass's guest numbers within about 5% (guest time per
fling 2,937 / 2,118 / 5,773 / 6,656 ms; the same widget counts within 10%).

**Reading them.**
- **The host's list scrolls by itself; the guest only fills it.** The host's `LazyColumn` runs the
  fling on the main thread with no guest involvement, so the list moves as soon as the finger does
  and stops when Compose's fling ends (0.7 s for a normal fling, 2.2 s for a fast one). The guest
  learns the new viewport, composes the rows the new window needs and sends them; each guest frame
  takes 0.4–3.3 s on this phone.
- **Gentle flings: no visible cost.** The one normal-up fling counted "with placeholders" had a gap
  of 4–24 px that started 857 ms after the touch, after the list had stopped: a sub-row gap, not a
  visible placeholder (the rerun counts placeholders from half a row and gaps apart). The guest's window (the first row plus 15 preloaded, and the
  loading strategy's look-ahead) covers about 11 rows of travel, so every row the user sees has
  content; the guest keeps composing for 2–3 s after the list stops, off screen.
- **Fast flings: a blank list.** The list outruns the window within the first frames of the fling,
  and from then on **every frame shows only placeholders** (14 of them: the whole viewport) until
  the guest catches up: **at least** 4.9–6.7 s after the finger lifts (median), 2.6–4.5 s after the
  list has stopped, and at most about 6.5–7.4 s (4.2–5.2 s after the stop), when the guest's last
  frame ended. The guest composes the windows it passes through (about 50–70 rows per fling, 0.1 s each)
  before the one where the list came to rest.
- **Frame times.** The host draws frames of either about 10 ms or about 20 ms, so the medians land
  on one or the other (14.1–20.2 for the same normal flings in different runs; the instrumented
  pass 18.7 / 12.9); the shares over 16.7 ms swing with it (43–63%). Frames over 33 ms are rare in
  normal flings (0–2.5%) and 3–9% in fast ones, where the host recomposes placeholders into rows;
  the p99 of 85–97 ms and the 150–169 ms maxima are those. The flat runs of 10, 20 or 37 ms frames
  are mostly frame timing (where each frame starts against vsync), not work, so the ~10 / ~20 ms
  flip and the "% over 16.7 ms" are not jank measures; and about 119 of about 125 frames of a fast
  fling come after the UP, many of them the host applying guest batches after the list stopped
  (the native form has none of those). The rerun reports missed vsyncs while the list moves and the
  frames after the stop apart.

**What a user would feel.** A gentle flick through the guest's list looks like a native list: it
moves with the finger, stops on rows that are there. A fast flick shows an empty list: the rows
vanish as soon as it gets going and the screen stays blank, the list already still, for at least
two and a half to four and a half seconds more (perhaps up to five) before the rows appear. Where
between the two speeds that starts is not measured yet. On this phone that is the guest's
biggest visible defect so far; a user would think the list broke. The cost is the guest composing
every window it passes (0.1 s per row in QuickJS), not the bridge or the host.

**The first, discarded attempt.** A first pass (03:35–03:45) closed a fling after 1 s without a host
frame, but a guest frame can run longer than that with the host idle: its work spilled into the next
fling's counts. The route now waits for the guest too; only the later runs are reported. One case
still leaked in the reported runs: a guest frame still running at the next touch is counted in
neither fling. The rerun marks such flings (`guestBusyAtDown`) and drops them.

### A heavy screen: opening (warm)

**Run 3, measured** (15:04–15:08 IST, 45%, 29.8–30.4 °C, thermal 0). `-e x heavy` (the screen is
described below, under "What was built"), 14 opens and
backs per form, the first 2 dropped; ms from the tap to drawn, median / p90 / min–max; cold = the
process's first open (`am start -S`), three processes per form:

| Form | Open (warm) | Back | Open (cold, 3 processes) |
|---|---|---|---|
| native | **22** / 23 / 19–24 | **14** / 16 / 13–19 | 32, 37, 35 |
| guest | **1,042** / 1,068 / 998–1,299 | **1,085.5** / 1,103 / 1,068–1,227 | 1,094, 1,109, 1,113 |
| web | **48** / 51 / 41–54 | **48.5** / 51 / 34–55 | 64, 67, 58 |

Against the simple detail (run 2's baseline: native 16.5, guest 440.5, web 44 ms to open), the
102-widget screen adds about 6 ms in native, 4 ms in web and **600 ms in the guest**. Back to the
list costs the same in native and web and about 30 ms more in the guest (1,085.5 against
1,054.5).

**The guest's split** (`x=heavy,split`, same series; its timers add about 40 ms): open 1,083.5 ms
median. The click reaches the guest's frame at 32.5 ms; the guest composes and records until
963 ms (recording 1,036 properties in 200.5 ms and 102 modifiers in 102 ms: about 300 ms of the
930 ms frame; the rest is Compose itself); its batch reaches the host 71.5 ms later (1,034.5),
is applied 9 ms later and drawn 40 ms after that. **About 85% of the guest's open is its own
frame in QuickJS** (930 of 1,083.5 ms); the other ~150 ms are 32.5 ms before the guest's frame,
then ~120 ms of bridge, apply and draw.

**What a user would feel.** In native and web a 100-widget screen opens as fast as a small one,
within a frame or two of the tap. In the guest it takes a full second on this phone: a visible
pause after every tap into such a screen, and another second back.

**Run 2, smoke only (raw kept; not a series).** One guest launch, `-e x heavy,mem`, 3 iterations
(58–59%, 28.9 °C): the process's first open 1,189 ms to drawn (composed at 997), the next two
1,181 and 1,120; backs 1,191, 1,150, 1,109. This reproduces the first run's unkept check
(1,210–1,314 open, 1,186–1,213 back) and confirms the order of magnitude: about 1.1–1.2 s to open
a 102-widget screen in the guest on this phone, against about 0.44 s for the simple detail. The
series (14 per form, 3 cold opens, native and web) was not run: the phone left first.

**What was built** (written in the first run, when it was not yet measured). `-e x heavy` makes a row open a
second detail variant in all three forms: a header, the amount and status, a row of four chips, four
sections of a title and six label/value rows, a row of three buttons and a footer, text only:
**102 widgets** in the guest (counted: `n_creates` = 102 on every open), the same layout and strings
in the page (`index.html`). The series is the navigation series with that flag (`-e bench nav
--ei n 14 --ei warmup 2 -e x heavy`, each form), plus the guest with `split` for its breakdown.

**What it measures: opening (warm).** With the warm-up dropped, each open is a fresh composition of
all 102 widgets, but in a warm process: host code already compiled, text-layout caches warm, and
the rows opened cycle through only 5 items. The summary line is labelled `open (warm)`. A cold
first open is logged apart, once per process, as `open (cold, i=1)` (the series' first iteration,
when `nav` is the process's first series): launch each form with `am start -S` and repeat the
launch for several cold samples.

One check run on the phone, **output not kept** (guest, `split` on, 2 iterations, not a
measurement; no raw file backs these numbers): opening took
1,314 and 1,210 ms to drawn (the simple detail: about 430), of which the click reached the guest's
frame after about 35 ms, the guest composed and recorded until 947–973 ms (recording 1,036
properties and 102 modifiers: about 300 ms of it), its batch reached the host 106–120 ms later and
the host drew 128–194 ms after that. Back to the list after it: 1,186–1,213 ms. If the series
confirms it, a 100-widget screen costs the guest about 1.2 s to open on this phone, against an
expected tens of ms native and web (not measured).

### The GC threshold

**Run 3, measured** (15:08–15:17 IST, 45% → 44%, 29.3–29.8 °C, thermal 0). Guest only, each arm one
fresh process: navigation (14 opens and backs) then `api-large` (14 calls), the first 2 dropped,
QuickJS's memory read before each tap and after each drawn result (the decision rule below, after "What was built", was
written before any of this). Median / p90 / min–max, ms:

| Arm | Open (warm) | Back | api-large | Heap before open: i=1 → 7 → 14 | Heap before api-large: i=1 → 7 → 14 |
|---|---|---|---|---|---|
| default (`x=mem`), run 1 | 435 / 443 / 424–443 | 1,068 / 1,143 / 1,019–1,169 | 1,114.5 / 1,138 / 1,088–1,242 | 7.94 → 7.85 → 9.38 MB | 9.59 → 12.00 → 10.39 MB |
| default (`x=mem`), run 2 | 439 / 444 / 413–448 | 1,068 / 1,095 / 1,037–1,192 | 1,111.5 / 1,150 / 1,088–1,226 | 7.94 → 7.85 → 9.38 | 9.60 → 12.00 → 10.39 |
| `gc4` | 450 / 455 / 428–458 | 1,088.5 / 1,160 / 1,070–1,193 | 1,129.5 / 1,139 / 1,096–1,230 | 7.94 → 7.85 → 9.38 | 9.59 → 12.00 → 10.39 |
| `gc64` | 441 / 444 / 425–447 | 1,068 / 1,095 / 1,038–1,098 | 1,110.5 / 1,122 / 1,090–1,128 | 8.04 → 9.35 → 10.88 | 11.09 → 13.50 → 15.47 |
| `gcoff` | 433 / 442 / 419–446 | 1,064 / 1,081 / 1,048–1,107 | 1,108.5 / 1,118 / 1,083–1,138 | 8.04 → 9.35 → 10.88 | 11.09 → 13.50 → 15.47 |

**Applying the decision rule** (the rule itself is below, after "What was built").
- *Collections ran under the default: yes, both conditions hold.* Under the default the heap and
  object count fall where no rows were freed: at back i=6 the heap went 8.22 → 7.55 MB and the
  objects 28,159 → 25,018 (every other back adds about 0.73 MB and 4,900 objects), and during
  api-large i=7 the heap went 12.00 → 8.68 MB. Under `gc64` and `gcoff` it never falls beyond what each open frees (about 0.81 MB): it grows
  steadily, 8.04 → 10.88 MB over navigation and to 15.47 MB (51,900 objects) by the last API call.
  `gc4`'s heap follows the default's to within a few hundred bytes, collections at the same
  iterations (its 4 MiB is passed during start-up, as predicted), and `gc64`'s follows `gcoff`'s the
  same way (the heap never reached 64 MB).
- *GC matters for navigation: no, within the rule's own resolution.* The default runs differ by
  4 ms in open, 0 in back and 3 in api-large. `gc64` is faster than both only in api-large, by 1 ms (under the
  3 ms the defaults differ).
  `gcoff` is faster than both by 2 ms in open (under 4) and by 3 ms in api-large (not more than 3),
  and by **4 ms in back**, where the two default runs happen to tie. That last one passes the rule
  as written. It is 0.4% of 1,068 ms, inside every arm's range, and the rule's noise estimate there
  (0 ms) comes from two medians that coincide, not from a measured spread; it is not a real
  difference. **Conclusion: GC does not measurably change the median navigation or API time.**
- *Where GC does show: in the slowest iteration.* Each collection lands in one iteration and adds
  about 100–130 ms to it (inferred from the slow iteration's excess over the median, not timed):
  back i=6 took 1,169, 1,192 and 1,193 ms under the default and `gc4`
  (the other backs 1,019–1,160), and api-large i=7 took 1,242, 1,226 and 1,230 ms (`parsed`
  1,120–1,134 against 1,000–1,010 for the same call under `gc64`/`gcoff`, which took 1,108). That is
  what the default's higher p90 and max are; under `gc64`/`gcoff` those collections did not happen.

**What it means.** On this phone the reads show one QuickJS collection of an 8–12 MB heap in each
14-iteration series (about 28 navigation taps; 14 large API calls); its cost, inferred from the slow
iteration's excess over the median, is about 100–130 ms, and it lands on whatever the user did then. Raising
the threshold removes them only for as long as the heap stays under it: `gcoff` grows about
0.2 MB per open-and-back and 0.3 MB per large API call, so `gc64` would postpone the first
collection by a few hundred interactions, and that collection would walk a heap several times
larger. **Scope:** 14 iterations each of navigation and one API call in a fresh process, one run
per arm (two for the default); not a long session, not scrolling, not memory pressure.

**Run 2: not measured; one smoke read kept.** The heavy smoke run above had `x=mem` (the default
arm's reads): `gcThreshold` read 262,144 before and after every step, the heap in use 7.5–8.7 MB
and 24,500–32,000 objects. Each open dropped the heap by about 0.43 MB and 3,000 objects (the
list's rows freed), each back added about 0.38 MB and 2,550 objects, and the heap grew by about
0.3 MB between a step's "after" and the next step's "before" (the 300 ms pause, the memory read
itself, the next tap): 7.94 MB before the first open, 8.71 MB at the end of 3 iterations. Three
iterations cannot apply the decision rule below; **no GC conclusion from run 2**.

**What was built** (written in the first run, before any measurement). `-e x gc4`, `gc16` or `gc64` sets `zipline.quickJs.gcThreshold`
to that many MiB in the route's `ziplineCreated`, before any guest code loads (on the Zipline
thread, on the only Zipline instance); `-e x gcoff` sets −1 (QuickJS never collects on
allocation; reference counting still frees acyclic objects). With any of them, or `-e x mem`, the
route reads QuickJS's `memoryUsage` (heap in use, object count) and `gcThreshold` before each tap
and after each drawn result, and at the end of each series, on the Zipline thread. Each read walks
the whole heap (about 25,000–30,000 objects), so the tap waits 300 ms after it: the walk is
neither in the measured interval nor queued ahead of the guest's work. **The default arm runs as
`-e x mem`**, so it pays the same reads as the others; without it, the default and a threshold
would differ by the reads too. The series to run: navigation and `api-large`, 14 iterations,
warm-up 2, the default (`x=mem`) twice for the run-to-run noise, then `gc4`, `gc64` and `gcoff`.

**Decision rule, written before any result.** Zipline's getter echoes the value set, so the only
evidence of collections is the per-iteration heap in use and object count.
- *Collections ran under the default* if its object count drops between iterations by more than
  the drops `gc64` shows, and the heap in use grows steadily under `gc64` (and `gcoff`) but not
  under the default. Without both, the reads cannot tell a collection from reference counting.
- *GC matters for navigation* only if, with collections shown to run under the default, `gc64` or
  `gcoff` is faster than **both** default runs by more than the two default runs differ from each
  other, in the median of open or back (12 iterations each). Otherwise: "GC does not measurably
  matter", and that holds only for 14 iterations of navigation and the large API call, not for a
  long session.
- `gc16` and `gc64` should behave alike (the heap peaked near 10 MB in the check run, so neither
  is reached), and `gc4` like the default; `gc16` is therefore not run. Timing one forced
  collection on the ~8 MB heap would be direct evidence; the route does not do it, and whether
  Zipline 1.28's `QuickJs` exposes such a call was not checked in this round.

What the check runs showed (one check run each, **output not kept**: only the `ziplineCreated`
line below is in a raw file), which matters for reading the series:
- **The default threshold is QuickJS's 256 KiB** (`gcThreshold` reads 262,144; `memoryLimit` −1).
  The guest's heap is far above it: 7.4–8.4 MB in use and 24,000–30,000 objects during navigation,
  9.7 MB after two large API calls.
- **The read-back does not change** after the guest has run (262,144 before and after every
  navigation), although QuickJS raises its own threshold to 1.5 times the heap after each
  collection (`js_trigger_gc`). Zipline's getter therefore reports the value it was given, not
  QuickJS's current one, and cannot tell whether a collection ran. The heap in use fell across
  each open (7.85 → 7.43 MB, 26,937 → 24,000 objects): the list's rows being freed, by reference
  counting or a collection; these reads cannot tell which.
- If QuickJS behaves as its source says, a threshold set at creation lasts only until the first
  collection; after that the threshold follows the heap (1.5×) whatever was set. So `gc4` should
  behave like the default once the heap passes 4 MB (during start-up), and `gc16`/`gc64` should
  postpone collections only until the heap first reaches 16 or 64 MB. The series would show
  whether that postponement changes navigation; re-applying the threshold before every tap would
  test a pinned threshold (not built).

### Review round 1: what the rerun records

A review of this session's method found faults that would bias a rerun. The route is changed as
below. **Run 2 ran it on the A12** (one bug found and fixed: see Phone run 2); the first run's
numbers come from the old route.

| Finding | What the route does now (per fling line, `bench=scroll`) |
|---|---|
| Settled was the last blank frame | `bContentAfterUpMs`: the first frame with content after the last blank frame; `bSettledAfterUpMs` = the later of that and the list's stop (falls back to the last blank frame, then null in `bContentAfterUpMs`, if no such frame came before the fling closed) |
| Frame time is not jank | `missedVsyncs`: vsyncs skipped between consecutive moving frames' intended-vsync timestamps; `vsyncPeriodMs` used; per-component medians and p90 (`movingMedianMs`, `movingP90Ms`: late start, unknown delay, input, animation, layout, draw, sync, command issue, swap; GPU and deadline on API 31+); every frame's components in the results file only (`frameDetail` lines); the state line gives `refreshHz` and `density` |
| 8 s gap too short | `closedBy`: `settled` or `touch`; `guestBusyAtDown`; summaries drop both and count them (`excludedTouch`, `excludedGuestBusyAtDown`) |
| Only two speeds | `upVelocityPxS` / `upVelocityDpS` from the touch events (Android's `VelocityTracker`, all forms); `flingVelocityPxS`, the velocity the list's fling started with (Compose forms); the rerun adds 800 px in 200, 150 and 120 ms |
| Edge hits pooled | `edge`: the list left scroll unconsumed (Compose) or cannot scroll further that way; summaries drop it (`excludedEdge`); fast runs use 7 flings per direction (about 740 rows of 1,000) |
| Distance not comparable | `travelPx`: the list's own consumed scroll in px, from a nested-scroll connection around the content (Compose) or the WebView's scroll listener; `from`/`to` stay, labelled as the lazy list's semantics estimate |
| Frames after the stop pooled | `stopAfterUpMs` (the last frame in which the list scrolled); `movingFrames` and `afterStopFrames`; summary percentiles are over moving frames, `afterStopFrames`/`afterStopP90`/`afterStopMax` apart |
| GC design | `x=mem` for the default; 300 ms between a memory read and its tap; `gcoff`; the decision rule above |
| Heavy screen "first visit" | `open (warm)`; `open (cold, i=1)` once per process |
| One normal fling "with placeholders" | a placeholder counts from half a row; smaller gaps are `bFramesWithGap`, `flingsWithSubRowGapsOnly` |
| Fixture delay | the body is built once at start; the 100 ms delay is counted from the request line |
| Mixed API routes | `api` (`app` or `mac`) in every state, summary and fling line |

`x=scroll` (the guest's widget counters) wraps the guest's protocol adapter, a cost only the guest
pays; the clean pass for the guest/native comparison is run once without it, and the counters come
from the `x=scroll` run.

## NF3: the host's press signal (built, not run on a device)

2026-11-21. The "press signal" the NF1 gate rows above name as missing is now built in the gate-A
prototype: gate A's `ExtraPress` modifier (tag 501), which the host answers with a press event on
touch-down (inside a scroll container, once the finger has stayed still for 50 ms; never for a
fling that starts on a row or a touch that stops a moving list) and a cancel event at the end of
every gesture that sent a press (after the click, if any), both on the click's own event path. A
host declares it as the host feature `keliver.press@1`; a guest sends the modifier only to a
host that declares it, so an older host gets the click only (D2). keliver-nav's `prepareOnPress`
turns it into pre-composition (`KeliverNavController.prepare` under `KeliverNavHost`, or
`KeliverPreparedStack`). Tests: `PressSignalTest` (host and guest over the real protocol on the
JVM). Guest-side timings on the Mac: `GUEST_PROFILE.md`, "NF3: the host's press signal".

**Since measured on the A12** ([A12 checkpoint, series F](#series-f-the-press-signal-on-the-phone-nf3)):
the bench now has `x=prepared,press` and `--ei hold`. What follows is this section as written.

**Not measured here.** This bench's Android host does not declare the feature and its guest does
not provide the signal, so no device number is new: open on the A12 stays as in "NF1 gate, part 2"
(about 310 ms). What a device run needs: the threeway host's `hostFeatures` to include
`keliver.press@1`, the guest's `Main.kt` to provide `GateAPress` and a prepared stack, and the bench
to time from the touch-down (not the click) to the detail drawn. Before the host declares the
feature, measure list-scroll jank on the A12 with the press on and off, and tune the scroll delay
(`PressSignalTiming`) against how long taps last there. The tap-within-one-frame target
also needs the host's pressed state (the ripple), which the host draws already; the guest is not in
that path.

## A12 checkpoint, 2026-11-22

The batched A12 checkpoint for the native-feel work (PLAN_2026-11.md, WS7): the engine (NF2), the
heavy screen's host back (#276), one Compose state per generated widget (#280) and the cold start
(NF4), on the same Galaxy A12, restarted by the owner at about 02:12 IST. The first timed series
started at 02:29 (17 minutes of uptime). Builds came first.

**Method, common to every series.**
- **Builds.** Each build is the prototype published to its own scratch Maven repository
  (`-Dmaven.repo.local=<scratch>/m2-<build>`, version `0.0.0-gateb`), then the sample's benchmark
  APK from the same checkout, as in [How to run it](#how-to-run-it). "Main" is `1e083833f`. The
  "before" builds are each merge's first parent, in detached checkouts: `228a229b0` before #276
  and `4f3a5c865` before #280. The engine arms are main's sample built with
  `use-fork.init.gradle` against the fork published from a fresh `apply.sh` (`1.28.0-q20260604`;
  its `libquickjs.so` sizes equal step 2's on all four ABIs), with and without
  `-PziplineForkLongAsBigInt`. Every APK installs as `dev.keliver.sample`.
- **Every series ran from a script on the phone** (`nohup sh`, the APKs pushed to the phone and
  installed there with `pm install -r`, so a dropped adb link cannot stop a series). After each
  install the script started the app once, sent profileinstaller's install-profile broadcast and
  compiled `speed-profile` (`cmd package compile -f`), the same for every build. Each series is a
  fresh process (`am start -S`) with the in-app fixture (`--ez localApi true`), 14 iterations with
  the first 2 dropped, unless said otherwise. Before each series the script woke the screen and
  logged the uptime, `/proc/loadavg`, the battery, the thermal status and the CPU idle share over
  2 s (`/proc/stat`). The load average stays at 20–23 even when idle (kernel threads), so the idle
  share is the check.
- Medians are written median / p90 / min–max, in ms from the tap (or the press), unless said
  otherwise.

### Series A: the engine on the phone (NF2)

**Arms.** Zipline 1.28.0 (main as built), the fork (QuickJS 2026-06-04), and the fork with `Long`
as a JS BigInt. Each arm ran five series in a row: navigation (`bench=nav`, header back), the same
on the heavy detail (`x=heavy`), system back with the host's retained back (`bench=sysback`,
`x=retainhost`), the same on the heavy detail, and `x=split` on the simple screen. Native ran
once, on the first arm's install (its code does not depend on the engine).

**Phone state.** 02:29–02:44, uptime 17.5–32.5 min, on battery 100% → 98%, unplugged, 25.8–26.3 °C,
thermal status 0, CPU idle 84–98% before each series.

**Navigation, header back** (simple screen; heavy detail below it):

| Arm | Open (warm) | Back | Open (cold, i=1) |
|---|---|---|---|
| native | 16 / 17 / 14–18 | 13 / 14 / 10–14 | 28 |
| guest, Zipline 1.28.0 | 436 / 456 / 414–456 | 834 / 847 / 827–855 | 445 |
| guest, fork | 353.5 / 370 / 344–374 | 698.5 / 708 / 678–715 | 368 |
| **guest, fork, `Long` as BigInt** | **208** / 220 / 184–222 | **461.5** / 478 / 441–487 | 208 |
| heavy: native | 22 / 23 / 20–25 | 14 / 15 / 11–33 | 35 |
| heavy: guest, Zipline 1.28.0 | 1,028.5 / 1,072 / 1,008–1,136 | 856.5 / 880 / 819–921 | 1,098 |
| heavy: guest, fork | 828.5 / 859 / 799–879 | 702 / 717 / 689–719 | 884 |
| **heavy: guest, fork, BigInt** | **514** / 559 / 499–585 | **468** / 497 / 452–498 | 565 |

**System back with the host's retained back (`x=retainhost`)**: `firstDraw` is the press to the
window's first draw (the host showing the retained list), `drawn` the guest's confirming frame:

| Arm | Back, first draw | Back, drawn (guest) | Open (warm) |
|---|---|---|---|
| Zipline 1.28.0 | 17 / 22 / 10–23 | 119 / 123 / 92–126 | 289.5 / 305 / 273–306 |
| fork | 17 / 23 / 12–23 | 98.5 / 102 / 92–121 | 240 / 260 / 234–261 |
| fork, BigInt | 16 / 19 / 13–24 | 97 / 99 / 70–101 | 174.5 / 176 / 170–253 |
| heavy: Zipline 1.28.0 | 18 / 22 / 7–23 | 187 / 194 / 165–194 | 908.5 / 951 / 891–979 |
| heavy: fork | 14.5 / 17 / 9–19 | 150 / 155 / 142–157 | 740 / 767 / 713–792 |
| heavy: fork, BigInt | 12 / 19 / 8–24 | 95 / 101 / 87–103 | 478 / 536 / 451–536 |

**The guest's split** (`x=split`, simple screen; the guest's frame is from the frame's start to
`composed`, in QuickJS; encoding is the split's modifier plus property time). The frame and encoding
columns are the **difference (or sum) of the marks' medians**, not the median of each iteration's
difference: per iteration the guest's frame is 352.5 / 738 (1.28.0), 284.5 / 605 (fork) and
127.5 / 353 (BigInt), so 2.76× on open and 2.09× on back, and encoding 67 / 287, 50.5 / 233 and
12.5 / 63 (open / back). The conclusions below hold either way.

| Arm | Open: guest's frame | Open: encoding | Open: drawn | Back: guest's frame | Back: encoding | Back: drawn |
|---|---|---|---|---|---|---|
| Zipline 1.28.0 | 352.5 | 67 | 447 | 742 | 286.5 | 862 |
| fork | 285.5 | 51.5 | 367.5 | 605.5 | 231.5 | 723.5 |
| fork, BigInt | **129** | 13.5 | 213.5 | **351.5** | 61.5 | 469.5 |

Every arm's batches had the same shape (open: 70 structural changes, 22 modifiers, 213
properties; back: 319, 109, 835), so the arms did the same work.

**APK size per arm** (bytes; the `.zipline` files are the three-way guest's 54 modules in the APK's
assets):

| Arm | APK | `libquickjs.so` (arm64-v8a) | Guest `.zipline` files |
|---|---|---|---|
| Zipline 1.28.0 | 25,264,991 | 2,432,744 | 2,077,321 |
| fork | 26,058,183 (+3.1%) | 2,618,752 (+7.6%) | 2,215,853 (+6.7%) |
| fork, BigInt | 26,041,387 (+3.1%) | 2,618,752 (+7.6%) | 2,193,386 (+5.6%) |

- **With `Long` as BigInt the guest's work is more than halved on the phone**: its frame 2.73× faster
  on open (352.5 → 129 ms) and 2.11× on back (742 → 351.5); encoding 4.7× on back (286.5 →
  61.5). The emulator's step 2 had 1.8× and 2.0×, and NF0's command-line engine on this phone 2.4×
  and 2.0×. On the screen, open goes from 436 to 208 ms (2.1×) and the heavy open from 1,028.5 to
  514 (2.0×).
- **The new engine alone gains about 20%** (frame 1.23× on both; open 436 → 353.5): the gain is
  mostly BigInt, as on the Mac.
- **The host's back does not depend on the engine**: the retained list is on screen 12–18 ms after
  the press on every arm, the heavy screen included (see series C for #276). What the engine changes
  is the guest's confirming frame (heavy: 187 → 95 ms).
- **Native was fast again** (open 16, back 13, as in runs 1–3), unlike part 1's slow day (43 / 32).
- NF2's gate, the guest's work about halved: **the A12 part is met, with `Long` as BigInt** (not
  met by the new engine alone). Still open: the iPhone, and "both engines load their own bytecode",
  which this checkpoint did not test.

### Series B: the fork on 32-bit ARM (NaN boxing)

The phone's ABI list is `arm64-v8a,armeabi-v7a,armeabi` (`ro.zygote` `zygote64_32`), so a package
installed with `--abi armeabi-v7a` runs as a 32-bit process on the fork's 32-bit `libquickjs.so`,
where QuickJS NaN-boxes its values (`JS_NAN_BOXING`).

**Method.** Zipline's device tests from the fork's checkout (`:zipline:assembleAndroidDeviceTest`,
plain and with `-PziplineForkLongAsBigInt`), installed on the phone with `pm install -t --abi
armeabi-v7a` and run with `am instrument -w -r`. The test APK as built does not install on this
phone: its `lib/*/wrap.sh` (a one-line `exec "$@"` that lets native `printf` reach the log) is
stored compressed and not page-aligned, while the APK loads native libraries in place, and the
installer refuses it (`INSTALL_FAILED_INVALID_APK: Failed to extract native libraries`; logcat
names `wrap.sh`). So each APK was installed without its `wrap.sh` (removed, zipaligned for 16 KB,
signed with the debug key); its tests, JavaScript and `libquickjs.so` are the build's own (the
BigInt APK's `kotlin-kotlin-stdlib.js` has `BigInt.asIntN(64`). The `asUintN` check:
`apply.sh qjs-android` and `check-bigint-device` (step 2's runner, on the AAR's compile line).
Both test packages were uninstalled after.

**Phone state.** 04:14–04:19 (the check: 02:46), on battery 92% → 91%, thermal status 0.

| Check (armeabi-v7a, `primaryCpuAbi=armeabi-v7a`) | Plain | `Long` as BigInt |
|---|---|---|
| Zipline's device tests: run / passed / skipped / failed | 271 / 269 / 2 / 0 | 271 / 269 / 2 / 0 |
| among them | `QuickJs2026Test`, `QuickJs2026InboundChannelTest`, `BigIntOpsTest` (48 classes) | the same |

| `bigint-ops.js` on the phone (292 lines against Node) | Fork | 2026-06-04 unpatched |
|---|---|---|
| arm64-v8a (`JS_NAN_BOXING no`, 64-bit limbs) | **matches all 292** | differs on 76 |
| armeabi-v7a (`JS_NAN_BOXING yes`, 32-bit limbs, 32-bit pointers) | **matches all 292** | differs on 108 |

- **The fork works on 32-bit ARM**: Zipline's whole device suite passes in a 32-bit process, plain
  and with `Long` as BigInt across the bridge, and the `asUintN` fix holds with NaN boxing (the
  unpatched engine is wrong on 108 lines there, more than on 64-bit). The 2 skipped are upstream's
  (`genericSuspendResultInList`, `veryDeepRecursionFailsGracefully`). Step 2's emulator run had 266
  run in 47 classes; the suite has grown since (#274 moved the JNI tests into it).
- Not covered: x86 (32-bit) and x86_64, which this phone does not run.

### Series C: the heavy screen's host back, before and after #276

**Method.** `bench=sysback`, `x=heavy,retainhost` (and the simple screen's `x=retainhost`), on
three builds installed in turn, twice: main, before #276 (`228a229b0`), before #280 (`4f3a5c865`,
series D's), then the same again. So each build has 2 runs of 12 measured backs (24 pooled), and
the phone's drift shows between rounds. Then one heavy series per build under a Perfetto trace
(below).

**Phone state** (series C and D together): 02:49–03:35, uptime 37–83 min, on battery 98% → 94%,
25.6–25.8 °C, thermal status 0, CPU idle 82–98% before each series.

**Back, first draw after the press** (the host showing the retained list) and the guest's
confirming frame (`drawn`), pooled over both runs; the two runs' own `firstDraw` medians in brackets:

| Build | Heavy: first draw | Heavy: guest's frame | Simple: first draw | Simple: guest's frame |
|---|---|---|---|---|
| before #276 | **35** / 39 / 23–40 (37; 32.5) | 221 / 239 / 194–241 | 27 / 29 / 22–31 (26.5; 27) | 149 / 167 / 129–171 |
| main | **14** / 21 / 7–22 (13.5; 14) | 183.5 / 193 / 165–198 | 20.5 / 23 / 13–24 (20.5; 20) | 119 / 121 / 95–123 |

- **#276 takes the heavy screen's host back from 35 to 14 ms to the first draw** (−60%; p90 39 →
  21), and the simple screen's from 27 to 20.5. The heavy screen is now no slower than the simple
  one on this measure. The guest's confirming frame is faster too (heavy 221 → 183.5 ms), as the
  host no longer recomposes both entries when the confirming batch changes them.
- Part 2 (2026-11-17, `229e876cf`) measured 56.5 ms heavy and 37.5 simple. Today's before-#276
  build gives 35 and 27 on the same measure: the phone is faster today (native's back 13 ms
  against 31 that day), so compare within today only.
- On `firstDraw`, the heavy back is under 50 ms: **met**. Whether the pixels are within 50 ms is
  the trace's question (next).

**The measure that sees the pixels (Perfetto, the #276 recipe).** One heavy series per build
(`x=heavy,retainhost,trace`, 14 backs, the first 2 dropped) under a Perfetto trace started on the
phone (the config of the NF0 traces: sched, atrace `gfx view input am wm dalvik`, the app's own
sections, SurfaceFlinger's frame timeline), read on the phone with `trace_processor_shell` (the
build of the one that androidx.benchmark's macro library ships). For each back: the press is the
bench's step message (the opens' step messages are the ones that sleep 40 ms in the tap); then the
first `Choreographer#doFrame` after it, its sections, and the app's actual frame timeline row for
that frame's vsync id, whose end is the present.

| Build | Press → frame starts | Press → end of the frame's main-thread work | `Record View#draw()` (of it: Compose's measure and layout) | `Recomposer:recompose` | **Press → presented** |
|---|---|---|---|---|---|
| before #276 | 10.8 | 65.4 / 89.5 / 57.2–90.2 | 30.4 (25.2) | 22.9 | **74.2** / 99.4 / 66.1–99.7 |
| before #280 | 13.1 | 29.4 / 32 / 15.6–32.3 | 9.0 (3.4) | 4.5 | **37.3** / 41.7 / 22.7–42.5 |
| main | 14.1 | 26.9 / 34.7 / 19–34.9 | 8.5 (3.2) | 4.4 | **36.1** / 44.4 / 29.4–44.8 |

(Medians, or median / p90 / min–max; ms; 12 backs per build. The traced series' own `firstDraw`
medians: 34.5 before #276, 17.5 before #280, 17.5 on main; tracing adds a section per message.)

- **Where the layout runs: inside `Record View#draw()`**, in every back of every build
  (`AndroidOwner:measureAndLayout` nested in it, after the `firstDraw` mark), as #276's section
  read from the sources. So `firstDraw` sees the recomposition and the apply only, and the pixels
  come about 20 ms after it.
- **With #276 the heavy screen's back is presented 36.1 ms after the press** (p90 44.4, at most
  44.8): **the 50 ms target is met on the measure that sees pixels**, with every one of the 12
  backs under it. Before #276: 74.2 ms (p90 99.4), none under 50. The frame's main-thread work
  falls from 65.4 to 26.9 ms; the recomposition from 22.9 to 4.4 and the layout from 25.2 to 3.2.
- #280 adds nothing measurable here (37.3 against 36.1).
- So decision 1 of #276's section (keep hidden entries placed, to save placement, layers and
  drawing) is **not needed for the 50 ms target** on this phone today; the margin is thin (p90
  44.4, max 44.8) and the phone was fast today, so park it rather than drop it.

### Series D: one Compose state per generated widget, before and after #280

**Method.** Back as in series C (the before-#280 build against main, in the same rounds), and a
scrolling series per build and round with `x=scroll` (the guest's counters on) at two speeds, the
doc's swipes: gentle (800 px in 300 ms, 1,422 dp/s, 10 each way, 8 s apart) and fast (1,000 px in
80 ms, 6,667 dp/s, 7 each way, 14 s apart). Frames over 25 ms are counted over the moving frames of
the clean flings (none closed by a touch, busy at the DOWN or at an end), from each fling's
`movingFrames` (FrameMetrics' total duration, which depends on where a frame starts against the
vsync; missed vsyncs are the jank measure).

**Scripted flings flung today**, unlike both NF1 gate sessions: every clean swipe but one started a
fling, and the list stopped 743 ms (gentle) and 2,245 ms (fast) after the finger lifted, as in runs
2–3.

**Back** (pooled as in series C):

| Build | Heavy: first draw | Heavy: guest's frame | Simple: first draw | Simple: guest's frame |
|---|---|---|---|---|
| before #280 | 17 / 22 / 6–24 (19; 16) | 185.5 / 191 / 164–192 | 19 / 21 / 15–23 (19; 19.5) | 118 / 120 / 103–122 |
| main | 14 / 21 / 7–22 (13.5; 14) | 183.5 / 193 / 165–198 | 20.5 / 23 / 13–24 (20.5; 20) | 119 / 121 / 95–123 |

**Scrolling** (per run; flings clean of 20 or 14 swipes; frames over 25 ms; missed vsyncs median /
total over the clean flings; the guest's last frame after the finger lifted, median):

| Speed | Build, run | Clean flings | Moving frames | Over 25 ms | Missed vsyncs | Guest's last frame after the UP |
|---|---|---|---|---|---|---|
| 1,422 dp/s | before #280, 1 | 19 | 1,149 | 0.5% | 2 / 25 | 2,773 ms |
| | before #280, 2 | 19 | 1,151 | 0.2% | 1 / 17 | 2,305 |
| | main, 1 | 19 | 1,155 | 0.4% | 1 / 19 | 2,791 |
| | main, 2 | 19 | 1,152 | 0.3% | 1 / 22 | 2,399 |
| 6,667 dp/s | before #280, 1 | 14 | 1,921 | 10.6% | 3 / 52 | 4,412 |
| | before #280, 2 | 13 | 1,782 | 12.2% | 4 / 51 | 4,498 |
| | main, 1 | 14 | 1,924 | 12.2% | 3 / 50 | 4,165 |
| | main, 2 | 13 (12 flung) | 1,653 | 6.4% | 3 / 42 | 4,611 |

- **#280 changes nothing measurable on the phone**, on the trace's evidence (series C): the traced
  heavy backs' first draw is 17.5 ms in both builds and the press → presented 37.3 against 36.1.
  The untraced first draw, 17 → 14 ms, is not by itself proof of neutral: both before-#280 runs
  (19 and 16) sit above both of main's (13.5 and 14). The guest's frames are unchanged, and **scrolling does not regress** (gentle 0.2–0.5% of frames over 25 ms
  in both builds, 1–2 missed vsyncs; fast 6.4–12.2%, 3–4 missed, in both). The Mac's −82% in
  recorded state reads does not show as time on the A12 at these sizes, as the Mac's medians
  already suggested.
- **Scrolling against NF1's target** (frames over 25 ms under 2%; the target is judged by hand):
  scripted, with the counters on, the gentle fling is within it (0.2–0.5%) and the fast fling is
  not (6.4–12.2%), although at that speed the share is noisy (series F's same arm: 5.3% and
  32.9%; missed vsyncs, 3–4 per fling, are the stable measure). Placeholders (`x=scroll,blank`)
  were not run.

### Series E: the cold start, shipped against `x=prestart` and `x=prestart,prefetch` (NF4)

**Method.** Main's build (installed last in series C). Each arm: one unmeasured launch (it writes
the preference the next process's prestart acts on), then 4 cold launches (`am force-stop`, 4 s,
`am start`, wait for the process's `"cold"` line), in two rounds (native, shipped, `prestart`,
`prestart,prefetch`, then again): 8 measured launches per arm. The marks are ms from the process
start (`Process.getStartUptimeMillis`), as in [Cold start (NF0)](#cold-start-nf0), not
Macrobenchmark's (which starts about 270 ms earlier, at the launch).

**Phone state.** 03:35–03:42, uptime 83–91 min, on battery 94% → 93%, 25.6–25.7 °C, thermal status
0, CPU idle 85–98%.

| Arm | Launches | Data drawn: median (min–max) | − native | First frame | Code load starts → succeeds | Request → response | List composed |
|---|---|---|---|---|---|---|---|
| native | 8 | **604** (595–630) | — | 375 | — | 143 | 602 |
| guest, shipped | 8 | **2,775** (2,745–2,811) | +2,171 | 374.5 | 485.5 → 1,086 | 144.5 | 2,522 |
| guest, `prestart` | 8 | **2,649.5** (2,617–2,689) | +2,045.5 | 369.5 | 312.5 → 940.5 | 143 | 2,390.5 |
| guest, `prestart,prefetch` | 8 | **2,533** (2,518–2,794) | +1,929 | 371.5 | 314 → 926.5 | 16 | 2,288 |

(Request → response is the median of each launch's difference.)

- **Together the two steps save 242 ms on the A12** (2,775 → 2,533, −8.7%): `prestart` 125.5 and
  the prefetch about 117 more. The emulator's step 1 saved 272 ms (−29%) on a much shorter start.
- **The prestart moves the code load 173 ms earlier, not to the process start.** The provider
  runs at 79 ms (the `prestart` mark), but the code load's first event comes at 312.5 ms, between
  `onCreate` (195) and the first frame (369.5): the load waits about 230 ms after the prestart.
  Where it waits is not established (a main-thread hop in the app's start is the candidate). The
  prestart's own main-thread work is 11.9 ms median (15.3 with the prefetch), and `onCreate` comes
  74 ms later than as shipped (195 against 121); the first frame does not move (369.5–375).
- **The prefetch removes the network wait** (request → response 144.5 → 16 ms), as on the emulator.
- **What is left is the list's composition**: parsed → composed (medians of the marks) is 994.5 ms as
  shipped (1,008.5 with `prestart`, 1,039.5 with both), as NF0 found (995). That is guest work in QuickJS, which series A
  halves with `Long` as BigInt (an inference for the cold start: the BigInt build's cold start was
  not measured).
- Native today is 604 ms to the data drawn (NF0's same mark: 1,028), most of the difference before
  `onCreate` (122.5 against 382). The guest's shipped cold start is close to NF0's (2,775 against
  2,608), so the gap to native is wider today (+2,171 against +1,580).
- NF4's target, within 0.4 s of native: **not met** (+1,929 ms with both steps).

### Series F: the press signal on the phone (NF3)

**What was added for it** (prototype only, in this change): the guest's `x=prepared` (keliver-nav's
`KeliverPreparedStack` at depth 1, each row with `prepareOnPress`; `RetainedStack.kt`), `x=press`
(the host declares `keliver.press@1` in the threeway app's `Spec.hostFeatures`, and the guest
provides `GateAPress`), and the driver's `--ei hold <ms>`: a tap whose finger stays down that long
without blocking the main thread (the existing taps sleep 40 ms on it), with every time measured
from the DOWN. A detail composed ahead of its tap reports the previous navigation's number, so the
driver now counts a screen only with a newer number than the last one.

**Series F's data came from the first driver, not the committed one.** The APK the series
installed (`press.apk`) was built at 02:59, before that rule existed; the build with it finished at
03:46, after the series had started, and never ran on the phone. All four press-on result files log
`i=1 seq=100000`, the prepared detail counted as drawn: the bug the rule fixes. So the committed
"navigation number is newer" rule was built but has not run on a device. Only the dropped `i=1` was
affected: every `i ≥ 2` number matches 100,000 + 2i − 1. The earlier series (A–E) stay comparable,
by reading the code: all three forms number navigations from 100,001 (`ThreewayScreens.kt` is
copied into the native form, and `index.html` uses the same base), every navigation increases the
number, a timeout leaves the last number alone, and `data` screens are exempt, so the rule cannot
reject a screen the old driver accepted on an as-shipped arm.

**Method.** One build (main plus the above). Open and back (`bench=nav`, header back), 100 ms hold,
`x=prepared` (the press off: the rows add nothing, the stack composes what `retain` does) against
`x=prepared,press`, simple and heavy, alternating in two rounds; `x=retain` with the same hold as a
reference; then the scrolling series (as in series D, `x=prepared` and `x=prepared,press`, without
the counters).

**Phone state.** 03:44–04:05, and the second fast scrolling pair from 04:07, uptime 92–115 min, on
battery 93% → 92%, 25.5–25.7 °C, thermal status 0, CPU idle 81–99%.

**Open from the touch-down** (ms; `click` is when the guest's `onClick` ran):

| Arm | Round 1: drawn | Round 2: drawn | `click` (medians) | Back: drawn |
|---|---|---|---|---|
| press off | 381.5 / 395 / 374–397 | 392.5 / 406 / 382–415 | 113; 113 | 187.5; 193 |
| **press on** | **405.5** / 419 / 387–420 | **403.5** / 406 / 386–417 | **313; 308** | 191; 188.5 |
| heavy, press off | 1,019.5 / 1,056 / 984–1,101 | 1,005 / 1,028 / 969–1,041 | 113.5; 112.5 | 208.5; 212.5 |
| **heavy, press on** | **1,089** / 1,116 / 1,051–1,116 | **1,060** / 1,098 / 1,021–1,171 | **932.5; 916.5** | 210; 209 |
| `retain`, same hold (reference) | 394 / 398 / 377–398 | — | 113.5 | 186 |

- **On this phone and this engine the press signal makes open slower.** The solid evidence is the
  click (113 → 313 ms after the DOWN) and the heavy screen (+55 to +70 ms in both rounds). On the
  simple screen round 1 gives +24 ms, but round 2's +11 is within the round-to-round spread: the
  press-off arm itself moved 11 ms between rounds (381.5 → 392.5), and round 2's ranges overlap
  (382–415 against 386–417). The press reaches the guest 50 ms after the DOWN
  (the scroll container's still-finger delay), and composing the detail takes longer than the 50 ms
  left before a 100 ms tap lifts: the guest's `onClick` runs only when that composition ends (313
  ms after the DOWN instead of 113; heavy 932.5 instead of 113.5), since QuickJS runs one task at
  a time. Then showing the prepared entry costs about 70–130 ms more (click → composed, round 1,
  as the difference of the marks' medians: 73 on the simple screen, 129 on the heavy; per
  iteration 68 and 123.5), because the three-way detail reads the navigation
  counter that the click increments (its `composed` mark), so it recomposes at the tap: the
  caveat `KeliverPreparedStack` documents. A screen that reads nothing the tap changes would not
  pay that part.
- What would make it pay: a guest frame short enough to finish within the press (series A's
  BigInt build composes the simple detail's frame in about 129 ms, still longer than 50 ms), a
  longer press (a slower tap), or a host-side transition that hides the wait. Not measured: the
  press on the BigInt build, and other hold times.
- Back is unchanged (187.5–193 ms in every arm): the press only touches opening.

**Scrolling with the press signal** (series D's columns; one run each at 1,422 dp/s, two at
6,667 dp/s, the second pair in the order on, off):

| Speed | Arm, run | Clean flings | Moving frames | Over 25 ms | Missed vsyncs | Guest's last frame after the UP |
|---|---|---|---|---|---|---|
| 1,422 dp/s | press off | 20 | 1,218 | 0.3% | 1 / 17 | 2,376 ms |
| | press on | 19 | 1,148 | 0.3% | 1 / 22 | 2,526 |
| 6,667 dp/s | press off, 1 | 14 | 1,923 | 5.3% | 3 / 50 | 4,621 |
| | press on, 1 | 13 (12 flung) | 1,660 | 21.1% | 3 / 40 | 4,445 |
| | press on, 2 | 13 | 1,788 | 13.4% | 3 / 49 | 4,616 |
| | press off, 2 | 14 | 1,911 | 32.9% | 4 / 60 | 4,569 |

- **The press signal's 50 ms scroll delay costs no jank that this route can see**: at 1,422 dp/s
  both arms have 0.3% of frames over 25 ms and 1 missed vsync per fling; at 6,667 dp/s both miss
  3–4 vsyncs per fling. The swipes move at once, so no press is ever sent; what is exercised is
  the host's pending press timer on every DOWN.
- **At 6,667 dp/s the share of frames over 25 ms is noise**: the same press-off arm gave 5.3% and
  32.9% in two runs, while missed vsyncs stayed at 3–4 (series D: 6.4–12.2%). FrameMetrics' total
  duration depends on where a frame starts against the vsync; use missed vsyncs at that speed.

**Owner's checklist (by hand on the A12; not scriptable here):**
- [ ] The press feel: open a row with `x=prepared,press` against `x=prepared` (both on the build
  installed now): does the delay before the detail feel different?
- [ ] The scroll by hand with `x=scroll,blank` (placeholders), both speeds: any blank rows, and
  does it stutter?
- [ ] The scroll by hand with `x=prepared,press`: does a slow drag that stops on a row ever open
  it, or feel sticky?

The build left on the phone is main (`1e083833f`, Zipline 1.28.0) with this change's bench options,
so the checklist runs on it, for example
`am start -S -n dev.keliver.sample/dev.keliver.sample.host.threeway.ThreewayActivity --ez localApi true -e form guest -e x prepared,press`.

### Verdicts against the owner's targets

| Target | Verdict | Evidence (this checkpoint) |
|---|---|---|
| Back under 50 ms | **Met for the heavy screen on the pixels** (trace, the prototype's `x=retainhost`); simple: first draw 20.5 ms, not traced today | Host back, heavy screen: first draw 14 ms (p90 21), presented 36.1 ms after the press (p90 44.4, max 44.8; trace). Simple: first draw 20.5 only, and the pixels come about 20 ms after the first draw (series C). Before #276 the heavy one was presented at 74.2. Only the prototype's retained host back: the shipped header back is still 834 ms (series A), and the guest's own frame after a retained back is 119–187 ms |
| Tap response within one frame | **Not measured** | The host's pressed state (the ripple) is drawn by the host; no measure of its first frame was taken |
| About 100 ms to the destination (open: simple ~100, heavy ≤ 150) | **Not met** | Retained, Zipline 1.28.0: simple 289.5, heavy 908.5; with `Long` as BigInt 174.5 and 478. The press signal makes it slower on this engine (click 113 → 313 ms; heavy +55 to +70; simple +24 in round 1, round 2's +11 within the noise) |
| 55 fps scrolling (frames over 25 ms under 2%, by hand) | **Partly measured**, scripted only | 1,422 dp/s: 0.2–0.5% over 25 ms, 1–2 missed vsyncs per fling; 6,667 dp/s: 3–4 missed per fling (the share over 25 ms is noisy there). No hand check; no placeholder pass |
| Cold start within +400 ms of native | **Not met** | +2,171 ms as shipped, +1,929 with `prestart,prefetch` (native 604) |
| NF2: the guest's work about halved | **The A12 part is met, with `Long` as BigInt**; the iPhone and "both engines load their own bytecode" still open | Guest frame 2.73× (open), 2.11× (back) as the difference of the marks' medians (2.76× and 2.09× per iteration); the new engine alone 1.23×. 32-bit: all tests pass |

**What it changes in the plan (proposals for the owner):**
- NF1's back meets the target on Android for the heavy screen on the pixels (the prototype's
  retained host back; the simple screen's pixels not traced). Park "keep hidden entries placed"
  rather than drop it: the margin is thin (p90 44.4, max 44.8 against 50) and was measured on a
  fast day (native back 13 ms against 31 on 11-17). #280 is neutral on the phone (keep it for the Mac's state-read savings, but it
  is not a reason to port the pattern to keliver-material by hand).
- NF2's engine with `Long` as BigInt is the largest lever left for open and for the cold start (the
  list's composition, about 1 s of the cold start, is guest work): shipping it (the bundles for both
  engines and the transition) moves up in value. The new engine without BigInt is not worth a
  transition on its own.
- NF3's pre-composition on touch-down does not pay on the A12 with 1.28.0: composing a detail takes
  longer than a tap's press. Keep the press signal off by default until a run on the BigInt engine
  shows a gain, and keep screens that use it from reading state the tap changes.
- NF4: `prestart,prefetch` saves 242 ms; the rest of the gap is the list's composition. The prestart's
  code load waits about 230 ms after the provider runs: worth finding before more cold-start work.

## A12 checkpoint 2, 2026-11-22

The second A12 checkpoint of the night, for what the first left open: the cold start and the press
signal on the BigInt build (series G and H), the tap's first response in frames (I), and where the
prestarted code load waits (J); after this checkpoint's review, the tap's first visible change on
the pixels (K) and a rerun naming J's waker. Same Galaxy A12, still up since about 02:12 IST; the series ran
from 04:43 (2 h 31 min of uptime), on battery. The phone was fast again (native as in the first
checkpoint), but compare within this section only.

**Method, as in the [first checkpoint](#a12-checkpoint-2026-11-22), except:**
- **Two builds, both from this checkpoint's branch** (the first checkpoint's head `338a02f81`, which
  has the committed "navigation number is newer" rule in the driver): the prototype published to a
  scratch Maven repository, then the sample's benchmark APK. **1.28.0** is the sample as built;
  **BigInt** is the same sample built with `use-fork.init.gradle` against the fork, rebuilt for this
  session with `apply.sh` (`clone`, `engine`, `jni`, `android`, `publish`; `1.28.0-q20260604`, its
  `libquickjs.so` sizes equal series A's on all four ABIs), and `-PziplineForkLongAsBigInt` (the init
  script named both guest links it changed). APKs 25,271,283 and 26,047,427 bytes. **The engine
  inside the BigInt APK is inferred, not inspected**: the build log shows the init script switching
  both guest links to `Long` as BigInt but not the host resolving `zipline-android 1.28.0-q20260604`,
  the APK was not unzipped, and the `libquickjs.so` sizes above are the fork's own build output. The
  evidence for it is the APK delta, +776,144 bytes (+3.07%), matching series A's fork-and-BigInt APK
  (+776,396, +3.1%, where the library sizes were read from the APK), and series G's 2.3× faster
  composition.
- **One script on the phone ran all four series** (`nohup sh`), installing the two APKs in turn as
  before (`pm install -r`, profileinstaller's broadcast, `speed-profile`).

### Series G: the cold start on the BigInt build (NF4)

**Method.** Series E's (`cold`: one unmeasured launch per run, then 4 cold launches, `am force-stop`
between, each waiting for the process's `"cold"` line; marks in ms from the process start), in two
rounds. Each round installed the BigInt build and ran native, as shipped, `x=prestart` and
`x=prestart,prefetch`, then installed 1.28.0 and ran as shipped and `x=prestart,prefetch` (the
anchor to series E): 8 measured launches per arm. Native ran on the BigInt install (its code does
not use the engine).

**Phone state.** 04:43–04:57, uptime 2 h 32 min – 2 h 44 min, on battery 90% → 88%, 25.5–25.6 °C,
thermal status 0, CPU idle 84–98% before each run.

| Build, arm | Data drawn: median (min–max) | − native | First frame | Code load starts → succeeds | Request → response | Parsed → composed | List composed |
|---|---|---|---|---|---|---|---|
| native | **618** (612–622) | — | 378.5 | — | 143 | 25.5 | 616 |
| 1.28.0, shipped | **2,834** (2,772–2,985) | +2,216 | 378.5 | 491.5 → 1,145.5 | 144 | 998.5 | 2,580 |
| 1.28.0, `prestart,prefetch` | **2,577** (2,547–2,622) | +1,959 | 373 | 314.5 → 981 | 16 | 1,029.5 | 2,320 |
| BigInt, shipped | **2,143** (2,127–2,159) | +1,525 | 377 | 486.5 → 1,106.5 | 142 | 434.5 | 1,902.5 |
| BigInt, `prestart` | **1,988.5** (1,975–2,022) | +1,370.5 | 368 | 312.5 → 953.5 | 141 | 427 | 1,743.5 |
| **BigInt, `prestart,prefetch`** | **1,866** (1,856–1,875) | **+1,248** | 374 | 314.5 → 945 | 15 | 448.5 | 1,636 |

(Request → response and parsed → composed are medians of each launch's difference; the other
columns are medians of the marks.)

- **The BigInt build's cold-start gain is real: −691 ms as shipped** (2,834 → 2,143, −24%) and
  **−711 ms with `prestart,prefetch`** (2,577 → 1,866, −28%), measured in the same rounds, with no
  overlap between the arms' ranges. Almost all of it is the list's composition (parsed → composed
  998.5 → 434.5 ms, 2.3×, as series A's 2.73× on open). The code load gains little (the summed
  module load time, `n_moduleLoadMs`, 306 → 239 ms as shipped, 245.5 with both steps; the load
  succeeds 39 ms earlier as shipped): it is bytecode loading, not guest work.
- **The 1.28.0 anchor reproduces series E** within 60 ms (2,834 against 2,775 as shipped, 2,577
  against 2,533 with both steps), and native too (618 against 604).
- **With BigInt the two NF4 steps still add up**: `prestart` −154.5 ms, the prefetch −122.5 more,
  −277 together (1.28.0 here: −257).
- NF4's target, within 0.4 s of native: **not met**, +1,248 ms at best. What is left, BigInt with
  both steps (medians of the marks): the code load 314.5 → 945 (630 ms, of it 406 ms from the first
  module's load start to the last one's end, a span, not `n_moduleLoadMs`'s sum); its
  start still waits about 230 ms after the prestart (81 ms; series J); the guest's first frame
  ("Loading…") and the request, 945 → 1,149 (the first batch sent); the list's composition, 448.5;
  and the batch to the screen, composed → drawn 230.5 ms (241.5 as shipped, 2 for native).

### Series H: the press signal on the BigInt build (NF3)

**Method.** Series F's open and back (`bench=nav`, header back, `--ei hold 100`, timed from the
touch-down), on the BigInt build: `x=prepared` (press off) against `x=prepared,press`, simple and
heavy, alternating, in two rounds (14 iterations, the first 2 dropped). **The driver is the
committed one this time**, with the "navigation number is newer" rule: every press-on file's `i=1`
is `seq=100001` (the click's navigation, drawn 301–311 ms simple, 728–732 heavy), not series F's
`seq=100000` (the prepared detail counted as drawn). The rule works on the device; no run timed out.

**Phone state.** 04:57–05:03, uptime 2 h 46 min – 2 h 51 min, on battery 87%, 25.6–25.7 °C, thermal
status 0, CPU idle 86–99%.

**Open from the touch-down** (ms; median / p90 / min–max of `drawn`; `click` is when the guest's
`onClick` ran; the last two columns are medians of each iteration's difference):

| Arm | Round 1: drawn | Round 2: drawn | `click` (medians) | Click → composed | Click → drawn | Back: drawn |
|---|---|---|---|---|---|---|
| press off | 280.5 / 283 / 251–288 | 278 / 285 / 264–298 | 113; 113 | 116.5; 112 | 167; 163.5 | 169.5; 171.5 |
| **press on** | **278** / 287 / 258–288 | **287** / 300 / 267–304 | **198.5; 201** | 57.5; 58 | 80; 85 | 174; 169.5 |
| heavy, press off | 590 / 627 / 569–628 | 583 / 598 / 564–610 | 111; 113 | 369.5; 363 | 479; 470.5 | 183.5; 181 |
| **heavy, press on** | **626** / 671 / 593–683 | **635** / 647 / 596–651 | **488.5; 494** | 114.5; 111 | 140.5; 131.5 | 189; 187.5 |

- **With `Long` as BigInt the press signal is neutral on the simple screen** (round 1 −2.5 ms,
  round 2 +9, within this run's noise of up to 9 ms (the press-off rounds differ by 2.5); series F
  on 1.28.0: +11 to +24) **and still slower
  on the heavy one** (+36 and +52; F: +55 to +70). It never makes open faster here.
- **Why, per iteration.** The press reaches the guest at about 50 ms (series F; not measured in H). On the simple screen it then
  composes the prepared detail, and the click, queued behind that work, runs at about 200 ms
  instead of 113 (F: 313). From the click the prepared detail is drawn in 80–85 ms instead of
  163.5–167, so the two halves cancel: the head start is spent waiting for QuickJS. On the heavy
  screen the click waits until about 490 ms: preparing costs more than it saves (click → drawn
  131.5–140.5 against 470.5–479). The detail still recomposes at the click (click → composed
  57.5–58 simple, 111–114.5 heavy), because the three-way detail reads the counter the click
  increments (series F's caveat).
- What would make it pay is unchanged from F: a guest frame shorter than the press, or a
  preparation that does not hold the engine when the click comes (cancellable, or yielding between
  frames). Back is unchanged (169.5–189 ms), as in F.
- On the BigInt build, open without the press is **278–280.5 ms simple and 583–590 heavy from the
  touch-down** (163.5–167 and 470.5–479 from the click). NF3's gate (about 100 ms simple, 150 heavy)
  is **not met**.

### Series I: the tap's first response, in frames (NF1's "tap within one frame")

**Method.** Real touches through the input system (the shell's `input`, injected into the
InputDispatcher; the driver's synthetic taps cannot be used, they bypass it), under a Perfetto
trace (series C's config). One fresh process per arm (`--ez localApi true`, no `bench`), then 11
rounds, 2.5 s apart, meant to leave the app idle (no frame in flight) before each touch (true for
BigInt and native; see the 1.28.0 caveat below the table): a quick tap on a list
row (`input tap`: it opens the detail), the detail's header **Back** (a button), **Load 20** (a
button: a new request), a **held** press on a row (`input swipe` in place for 300 ms; it opens the
detail), and Back again. The first round is dropped (the process's first touch is slower), leaving
10 of each (9 of the last Back). Arms: the guest on BigInt and native (the BigInt install), then the
guest on 1.28.0. Read on the phone with `trace_processor_shell`:
- **the DOWN**: system_server's `interceptMotionBeforeQueueing` for the injected event (the first of
  its gesture; the app receives it 1.4–1.9 ms later, median, its `processInputEventForCompatibility`);
- **the response frame**: the app's first `Choreographer#doFrame` after the DOWN (the app draws
  nothing while idle), and **its present**: the end of the app's actual frame timeline row for that
  frame's vsync id (SurfaceFlinger's present).

**What that frame draws was not captured here**, so this series measures the first frame
*scheduled* after the touch, not the first *visible* change (series K below captures it: the guest's
first frame is pixel-identical to the screen before, and the first visible change comes one frame
later). In the guest arms a quick tap's first frame is
followed by frames at every vsync through the 450 ms looked at (an animation), and the guest's
destination comes much later (series H: about 280 ms after the touch on BigInt). The three-way host
wraps its content in material3's `MaterialTheme` (`ThreewayActivity`), so the host's tap feedback is
a ripple and a pressed state layer, both fading in from alpha 0; Compose draws an animation's
starting value in its first frame, so that first frame may show little or nothing, and the first
visible change may be one frame later (about 35–45 ms after the DOWN). For a quick tap on a row
inside a scrollable list Compose also delays the press until the UP. In native, the first frame for
a row and for Back is the destination itself (one frame, then nothing until the next vsync-locked
work), with one exception: native row k=21's first frame, at 16.3 ms with main-thread work ending
at 9 ms, started before the UP arrived and is not the destination (it is the "1 of 39 native"
within 16.7 ms below). So for a row and Back the guest and native columns may compare a frame with
no visible change against a fully visible one; Load 20 and the held press compare like with like
(both the host's press feedback). For a held press every arm, native included, starts an animation
8–25 ms after the DOWN (the pressed state). A real touchscreen adds its own sampling and controller delay before
the DOWN, the same for every form (not measured).

**Phone state.** 05:03–05:12, uptime 2 h 52 min – 2 h 58 min, on battery 87% → 86%, 25.6–25.8 °C,
thermal status 0, CPU idle 85–98%.

**DOWN → the response frame presented** (ms; median / p90 / min–max; in brackets the frame's start
after the DOWN, median; the quick taps' UP came 4.8–6.7 ms after the DOWN, median):

| Touch | Guest, BigInt | Guest, 1.28.0 | Native |
|---|---|---|---|
| Row, quick tap | **23.8** / 30.2 / 14.7–31.9 (13.7) | **26.8** / 30.9 / 15.8–31 (17.3) | 55.4 / 58 / 16.3–59.8 (16.8): the detail |
| Back (header), quick tap | **27.8** / 30.8 / 20.2–33 (18.5) | **23.9** / 28.3 / 15.9–29.2 (14.3) | 110.3 / 117.2 / 95.5–117.4 (15.6): the list |
| Back, second (n=9) | **18.6** / 29.7 / 14.5–29.7 (10) | **21.3** / 27 / 15.4–27 (12.2) | 114.9 / 117.8 / 83.6–117.8 (19.9): the list |
| Load 20, quick tap | **24.1** / 26.9 / 16.1–28.8 (15) | **25.3** / 29 / 13.8–29.4 (15.9) | 31.8 / 33.4 / 22.2–33.8 (19.1) |
| Row, held 300 ms | **32.4** / 35.3 / 19.5–36.5 (20) | **26.8** / 33.4 / 17.1–37.3 (15.1) | 30.2 / 35.4 / 22.1–36.7 (17.7) |

- **The 1.28.0 arm was not always idle before the touch**: 10 of its 49 kept touches (rows k=6, 11,
  26, 36, 41, 46; Load 20 k=8, 23, 43, 53) had 1–3 app frames in the second before the DOWN (BigInt
  and native: none), so their first frame after the DOWN may not be the touch's. Without them the
  1.28.0 row median is 23.0 ms (n=4, 15.8–31) instead of 26.8, and Load 20's 21.5 (n=6, 13.8–29.4)
  instead of 25.3; Back and the held press had none. The conclusion does not change.
- **The first frame scheduled after a guest tap is the next frame the pipeline allows**: it starts
  at the first vsync after the touch arrives (10–18.5 ms after the DOWN, median, depending on where
  the touch falls in the 16.7 ms period) and is presented one vsync later. Every quick tap in both
  guest arms (78 of 78) had that frame on screen within **33 ms** of the DOWN, two frame periods,
  medians 18.6–27.8 ms; no frame missed its deadline. Held presses: within 37.3 ms, as native.
  Whether that frame shows a visible change was not captured: with a ripple fading in from alpha 0,
  the first visible change may be one frame later.
- **The engine does not matter for that frame** (BigInt and 1.28.0 are within a few ms both ways):
  the host's Compose `clickable` schedules it, and the click reaches the guest without holding the
  main thread. The guest pays later, for the destination.
- **Native's first frame is no earlier**: for Load 20 and the held press it is the same 30–32 ms
  (like with like: both are press feedback); for a row and Back its first frame is the new screen
  itself, composed in that frame (main-thread work to 46 and 100 ms) and presented at 55.4 and
  110.3 ms. Whether the guest *visibly* acknowledges a row or Back tap sooner than native is not
  established by this series: its first frame may be an invisible start of a fade-in, native's is
  the whole destination. The guest arrives later.
- **Against the target, "the first response to a tap within one frame"**: **not established by
  this series** (settled on the pixels by series K: not met). The first frame scheduled after the
  touch is one frame after it is delivered (no frame waits for the guest or the engine), but what
  it shows was not captured. Within 16.7 ms of the DOWN itself
  no form reaches even the scheduled frame on this phone (7 of 78 guest quick taps, 1 of 39
  native): the app draws at the next vsync and the compositor shows that frame one vsync later.

### Series J: where the prestarted code load waits (NF4)

**Method.** The 1.28.0 build, `x=prestart,prefetch,trace` (`trace` names every main-thread message
and the guest's calls), one unmeasured launch, then two cold launches each under a 20 s Perfetto
trace (series C's config). The trace's times are aligned with the `"cold"` line's marks (ms from
`Process.getStartUptimeMillis`) by `onCreate`: in J-1 the trace's `performCreate` begins 286.8 ms
after the main thread first runs and the `activityCreate` mark is 203, so the trace leads the marks by 84 ms
(the process forks and specialises before its start time is set). Times below are the marks'.

**Phone state.** 05:12–05:14, on battery 86%, 25.8 °C, thermal status 0.

The two launches' marks: prestart 84 and 90, `codeLoadStart` 316 and 343, data drawn 2,591 and
2,652 (series G's median for this arm: 2,577). Launch J-1, from the provider to `codeLoadStart`
(J-2 has the same steps, 253 ms from the prestart to `codeLoadStart` against J-1's 232):

| From → to (ms) | What runs | Where |
|---|---|---|
| 84 → 153 | the bench's own fixture API starting (`n_fixtureMainMs` 68.8): the prestart marks itself before it | main thread, in `bindApplication` |
| 153 → 169 | the prestart's own work: `create`, `start` (`n_prestartMainMs` 16) | main thread |
| 164 → 214 | the Treehouse (Zipline dispatcher) thread starts and runs about 45 ms: opening the code cache's SQLite database (`androidx.sqlite`'s `ProcessLock`), first-use class loading | the Treehouse thread |
| 214 → 263 | **the Treehouse thread sleeps 49.1 ms** (J-2: 50.4) | — |
| 263 → 316 | it runs again (kotlinx.serialization's JSON and Zipline's signature classes loading), then `codeLoadStart` | the Treehouse thread |

- **The wait is about 85 ms on the main thread and about 150 ms on the Treehouse thread, not a
  main-thread hop.** 69 ms of it is the bench's fixture, which a real host does not have, placed
  between the prestart's mark and its work: a production prestart should start its code load about
  70 ms sooner than this bench shows, if nothing else takes the fixture's place (a prediction, not
  measured).
- **The ~50–64 ms sleep is the Treehouse thread waiting on a `Dispatchers.Default` pool thread
  (measured in a rerun).** This trace processor leaves `thread_state.waker_utid` empty and J's
  traces were not kept, so J itself could not name the waker. Two more traced cold launches of the
  same build and arm at 05:50 (marks: prestart 85 and 84, `codeLoadStart` 351 and 340, data drawn
  2,702 and 2,642) show a sleep at the same point (64.1 and 51.6 ms). In both, the raw `sched_waking` event
  for the Treehouse thread at the end of the sleep was emitted by a `DefaultDispatch` thread of the
  app (in the first launch the process still carried the name `zygote64`), which had just loaded
  `ZiplineManifest`, `TopologicalSortKt` and `LoadedManifest` (0.2–1.8 ms before the wake). J's two
  launches show the same pool-thread work in the 2 ms before the wake: the loader's cache work on
  its own dispatcher (reading the cached manifest), while the main thread loads classes too (the
  threads log `Lock contention on ClassLinker classes lock` against each other around it). So J's
  own sleep is attributed to the pool thread by that work pattern, not by a waker event. The wake
  comes 6–7 ms after the main thread's `activityResume` ends in J's two launches; whether the main
  thread's class loading is what delays the pool thread is not established.
- **`codeLoadStart` is not the load's start**: the Treehouse thread has run about 100 ms by then.
  Most of what precedes it is first-use class loading and the cache's database, cost that a
  baseline profile for the host's loader (Gate B's option 5) or opening the cache earlier could
  address. Findings only; no fix was tried.

### Series K: the tap's first visible change, on the pixels (NF1)

Added after the review of this checkpoint, to settle what series I's response frame shows.

**Method.** Series I's touches (`input tap`, the same coordinates; 2.5 s apart) under series I's
Perfetto trace **and** a screen recording of every composited frame: `screenrecord
--output-format=frames --size 180x400` writes each frame the compositor sends to its virtual display
as raw RGB (a 20-byte header with the size and format, no timestamp), so unchanged frames stay
byte-identical and any changed byte is a pixel change. One fresh process per arm, then 9 rounds of a
quick tap on a list row (it opens the detail), the detail's header **Back**, and **Load 20**; the
first round dropped, leaving 8 of each per run. **Two runs** (K at 05:40, K2 at 05:54), each with
the 1.28.0 guest and then native (the as-shipped install; the BigInt build was not rebuilt). The
frames were compared on the phone, each with the one before it: changed bytes below the status bar,
their bounding box, and the largest channel change (0–255).

**Timing.** Raw frame *i* is screenrecord's *i*-th `acquireBuffer` in the trace (697–708 frames per
guest run; the counts match the file exactly). The recorder trails the display: where a frame's
identity is known (native row and Back, whose first frame is the destination, itself series I's
inference; the last frame of each Load 20 ripple), the virtual display's buffer is queued 10–31 ms
after that frame's present. Inferred from the lags: the first raw frame after the DOWN is the app's
first frame after it, trailing that frame's present by 11–23 ms in every guest touch. Matching it to
the app's second frame instead would need a lag of −2 to +6 ms, which no known frame shows. The
recorder drops a few frames (0–5 of each touch's 24–32 app frames), so the frame counts alone cannot
prove the order; the lags support it. The recorder also emits frames that are not the app's: two
status-bar-only frames in native's K2 run (below), and in two guest Load 20 windows (K2 k=18 and
k=27) a last recorder frame 1.1–1.9 s after the app's last frame. Times below are the matched app frame's
present (frame timeline), from the DOWN (system_server's `interceptMotionBeforeQueueing`, as in I).
The recording did not shift the first frame: the guest row's first frame was presented 26.9 ms after
the DOWN in K (median; I: 26.8), and native's row 57.0 (I: 55.4).

**Phone state.** 05:40–05:57, uptime 3 h 28 min – 3 h 45 min, on battery 85% → 83%, 25.7–25.8 °C,
thermal status 0, CPU idle 97–98%.

**What the frames show.**
- **Every guest touch's first frame after the DOWN is pixel-identical to the screen before it**
  (48 of 48 kept, and the 6 of the dropped first rounds; unchanged in the status bar too), and so
  is native **Load 20**'s (16 of 16). Inferred: the host's Compose ripple draws its first frame at
  alpha 0. The evidence is the ramp that follows: the largest channel change in the first changed
  frame and the next three is about 5–6, 10, 15 and 20 of 255, the linear fade-in of a ripple that
  starts from zero.
- **The first changed frame is the app's second frame after the touch** (47 of 48 kept guest
  touches; one row tap's first change came a frame later, in its third), and it shows **the host's
  ripple, faint**: for a row a circle centred on the touch inside the row's band, for Back a circle
  on the back icon, for Load 20 the button. Its largest channel change is 5–6 of 255 (about 2%,
  at this scale); for a row and Load 20 it is about 9–11, 14–16 and 19–22 in the next three frames
  (the ripple fading in; where the recorder dropped a frame, a sequence skips a step), for Back it
  grows more slowly (7, 9–10, 13).
- **Native's row and Back first change is the destination itself** (the whole screen, in one frame),
  except in two rounds, where an unchanged press frame came first (one row: then the destination
  in the next frame; one Back: a ripple first, the destination later). In two more native touches
  (K2: one row, one Back) the recorder had a frame before the app's first frame that changed only
  the status bar (a status-bar-only update, not an app frame); those two were skipped, and the
  table uses the destination for them.

**DOWN → the first visibly changed frame presented** (ms; median / p90 / min–max over both runs,
n=16 each):

| Touch | Guest, 1.28.0 | Native |
|---|---|---|
| Row, quick tap | **46.8** / 48.5 / 39.2–50.4: the ripple | 56.6 / 61.1 / 47.4–64.4: the detail |
| Back (header) | **44.8** / 49.1 / 36.2–50.0: the ripple | 117.8 / 124.3 / 34.3–125.6: the list (34.3: the one ripple-first round) |
| Load 20 | **39.3** / 45.5 / 31.9–50.6: the ripple | 44.2 / 48.9 / 36.6–50.3: the ripple |

- **The guest's first visible response to a tap is one frame later than its first scheduled
  frame**: 39.3–46.8 ms after the DOWN (medians), all ≤ 50.6 ms, about three frame periods, and
  it is faint for one more frame. The cause is the host's ripple starting at alpha 0, not the guest
  or the engine: native Compose's own button (Load 20) is the same, 44.2 ms.
- **Against native**: the guest shows *something* sooner for a row (46.8 against 56.6) and for Back
  (44.8 against 117.8), but only the ripple; native shows the destination. For a button that does
  not navigate they are the same.
- **"Tap within one frame" is not met** on the pixels, by the guest or by native's Compose button:
  the first visible change is presented in the app's second frame after the touch. Within two frame
  periods of the DOWN (33.3 ms, the bar the review proposes) it comes for 1 of 48 guest touches
  (Load 20 at 31.9 ms) and none of native's button taps. What would meet it is on the host: an
  indication that draws a visible pressed state in its first frame (a pressed layer at full alpha at
  once, the ripple after it) instead of fading in from 0. No guest work is involved.

### Verdicts against the owner's targets (checkpoint 2)

| Target | Verdict | Evidence |
|---|---|---|
| Tap response within one frame | **Not met** (on the pixels): the first frame scheduled after the touch, presented one frame after it is delivered, is pixel-identical to the screen before (the host's ripple at alpha 0); the first visible change is the next frame, a faint ripple. Native Compose's own button is the same | First visible change, guest on 1.28.0: 39.3–46.8 ms after the DOWN (median), all ≤ 50.6; native button 44.2, native row / Back the destination at 56.6 / 117.8 (series K). The first scheduled frame: 18.6–27.8 ms on both engines (series I) |
| About 100 ms to the destination (open: simple ~100, heavy ≤ 150) | **Not met** | BigInt, press off, from the touch-down: 278–280.5 simple, 583–590 heavy; the press signal neutral (simple) or worse (heavy, +36 to +52) (series H) |
| Cold start within +400 ms of native | **Not met** | BigInt with `prestart,prefetch`: 1,866 against native 618, +1,248 (1.28.0: +1,959); BigInt alone −691 ms as shipped (series G) |

**What it changes in the plan (proposals for the owner):**
- NF2: the BigInt build's cold-start gain is measured, not inferred (−691 ms as shipped, −711 with
  both NF4 steps). It is the largest single cold-start step measured so far.
- NF3: the press signal does not pay on BigInt either (neutral simple, worse heavy). Keep it off by
  default; the open path's remaining cost is the guest's frame from the click (163.5–167 ms simple,
  470.5–479 heavy on BigInt).
- NF1: "tap within one frame" is not met on the pixels, and the gap is the host's, not the guest's:
  no frame waits for the guest, but the host's ripple (inferred from its 5–6, 10, 15, 20 of 255
  ramp) draws its first frame at alpha 0, so the first visible change (a faint ripple) is presented
  in the app's second frame after the touch, 39.3–46.8 ms after the touch-down (native Compose's
  button: 44.2). The fix to try is a host indication that
  is visible in its first frame (a pressed layer at full alpha at once). Whether the bar is "one
  frame after delivery" or "within two frame periods of the touch-down" is the owner's call.
- NF4: before more cold-start work, the code load's first 150 ms on the Treehouse thread (class
  loading, the cache's database and a 50 ms wait on the cache's thread) and the 230 ms from the
  guest's composition to the screen are the next places to look; the bench's own fixture inflates
  the prestart's apparent wait by about 70 ms.

## NF4: composed to drawn on a cold start (A12, 2026-11-22)

Series G left about 230 ms on a cold start between the guest's `composed` mark for the list with
data and the `drawn` mark (230.5 ms on 1.28.0, 241.5 on BigInt; native 2). This section names what
runs there, slice by slice, and measures one fix.

**Answer.** Most of it was **the host's code running interpreted**: the Keliver libraries, Zipline
and kotlinx.serialization had no entry in any shipped baseline profile, so on a cold start the host
decoded the guest's first big batch (1,593 changes), mapped it and applied it in ART's interpreter
while the JIT compiled it. **A baseline profile shipped in `keliver-treehouse-host`'s AAR** (this
change, narrowed to the library packages on the host's path) takes the as-shipped cold start from
**2,921.5 to 2,566.5 ms** to the data frame's end (**−355 ms**), and the guest's composed → the
data frame's main-thread end from **365 to 223.5 ms** (series Y: the A12, 16 launches per build,
every install in the same profile state; native's composed → frame end is about 80). The PR's first,
broad profile took more off (2,472.5 ms, −449) but also compiled the sample's own code and the
generated gate-A code, at about three times the size (see
[Which profile ships](#which-profile-ships-and-what-it-costs)).
Also, `drawn` is earlier than the pixels: it fires as the frame starts drawing. Compose then measures
and lays out the list inside that draw (about 100–120 ms on this first data frame, native too).
**When it matters**: the first launches after a Play install or update, which Play compiles from
the shipped profile, until Play's cloud profiles cover the host's code. A sideloaded install is
compiled only at the next idle-and-charging dexopt, and by then ART's own profile of the app's
launches exists and gives the same (round 2 of series W and X).

**Method.**
- **Builds**, from this branch (main `003b84c23`), each prototype published to its own scratch Maven
  repository and the sample's benchmark APK built against it, as in
  [How to run it](#how-to-run-it): **base** (as main, no host profile), **broad** (the PR's first
  profile, six wildcard lines: methods and startup classes of all of `dev/keliver`, `app/cash/zipline`
  and `kotlinx/serialization`) and **narrow** (the profile that ships, see
  [Which profile ships](#which-profile-ships-and-what-it-costs)). Series T–X compared base with
  broad (called "fix" in their logs); series Y compares all three. The traced builds (T, U) are the
  sample as on main. The timed builds (series W, X, Y) add one bench mark: **`dataFrameEnd`**, posted
  at the front of the main thread's queue from the `drawn` listener, so it runs as the data frame's
  main-thread work ends. The cold line's log (building its JSON and appending it to a file) moved
  with it: it used to run inside the `reportFullyDrawn` frame and now runs after it. The marks up to
  `dataDrawn` are unchanged, but ThreewayStartupBenchmark's time to full display and the data frame's
  own length are not comparable with earlier series (the traced builds T and U still log inside the
  traced frame). The APKs have byte-identical dex files; only `assets/dexopt/baseline.prof` differs.
- **Phone**: the Galaxy A12, still up since about 02:12 IST, on battery, as in the checkpoints. Every
  run was a script on the phone (`nohup sh`), with the in-app fixture, `am force-stop` between
  launches, and each run's first launch unmeasured.
- **Profile states.** `cmd package dump-profiles` writes two sections: the *current* profile (what
  ART's profile saver and profileinstaller write) and the *reference* profile (what the compile
  used). Series T–X counted lines over both, which this section first misreported as the reference
  profile alone. Their installs launched the app for 12 s, then sent profileinstaller's broadcast and
  compiled `speed-profile`, and ended in one of two states that alternated between rounds:
  **15,273** kotlinx.serialization lines (the profile in one section) or **30,552** (in both), and for
  base 0 or **3,101** lines (ART's own saved profile from that 12 s launch). The 30,552-line installs
  timed like the base with ART's own profile and the 15,273-line ones like series Y's clean installs
  (see series W and X), so the 30,552 state most likely also carried ART's own profile of that
  launch; lines outside the host's packages were not counted, so this is not established. **Series Y
  removes the launch**: each install sends only profileinstaller's broadcast (to the stopped
  package; it answered `result=1`), then compiles `speed-profile` (`cmd package compile -f`), and
  records both sections, `dumpsys package dexopt` and the oat files' sizes. Every Y install ended in
  the same state: the current profile empty, the reference profile holding exactly the build's own
  rules (base 0 host lines; broad 15,273 kotlinx.serialization lines, which is round-1 X's state),
  and the same oat sizes per build; `dumpsys package dexopt` showed `status=speed-profile`,
  `reason=cmdline` in the 6 of 12 installs where it answered right after the compile (blank in the
  others; for every build at least once).
- **Traces** (series C's Perfetto config; `x=prestart,prefetch,trace`, so every guest call is a
  section): 3 cold starts of base (T, 07:55–07:56), 3 of broad (U, 08:05) and 2 of native (N,
  08:06–08:07), read on the phone with `trace_processor_shell`. The window opens at the guest's
  `zl:composed` call (inside the 1.2 s `zl:sendFrame` that composes the list), so no clock alignment
  is needed. It ends at the main thread's frame that runs `reportFullyDrawn`.
- **Not used: a sampling profiler.** `simpleperf record -p` on the app (profileable, not
  debuggable) is refused on this Android 12 build (`Permission denied` opening the event); enabling it
  needs a system property change, which this session does not make. So which share of a Treehouse
  thread slice is QuickJS and which is the host's Kotlin is read from the JIT's compile events, not
  sampled.
- **Timed series W** (`x=prestart,prefetch`, untraced): base and broad installed in turn, **each
  install after `pm uninstall`**, 3 rounds of 4 cold launches, 08:18–08:30. A first attempt (V)
  reinstalled with `pm install -r` and was aborted: with identical dex files the broad build's
  reference profile survived into the base install (its composed → batchSent was 68 ms). Profile
  states (kotlinx.serialization lines over both sections): base 0, **3,101**, 0; broad 30,552,
  **15,273**, 30,552 (rounds 1–3). So round 2's base is reported apart, and the base arm is rounds 1
  and 3 (8 launches); broad is all 12, in two states. **Series X**: the same on the as-shipped arm
  (no experiments), 2 rounds of 4: base 0 then 3,101; broad **15,273** then 30,552.
- **Timed series Y** (as shipped, untraced; the series that the headline numbers come from): base,
  broad and narrow, 4 rounds with the order rotated (base–broad–narrow, broad–narrow–base,
  narrow–base–broad, base–broad–narrow), each install after `pm uninstall` and with series Y's
  install (above), 4 measured cold launches per install: 16 per build, all in one profile state. A
  first attempt (08:59–09:01) was stopped by a label bug in the script after one launch on each of
  two builds; nothing from it is used.

**Phone state.** Series T–X: 07:54–08:40, uptime 5 h 43 min – 6 h 28 min, on battery 82% → 77%,
22.9–25.0 °C, thermal status 0, CPU idle 63–84% before each run (lower than the checkpoints'
84–98%). Series Y: 09:02–09:24, uptime 6 h 50 min – 7 h 12 min, on battery 77% → 74%, 24.0–25.0 °C,
thermal status 0, CPU idle 52–60% before each run.

**The slices, traced** (ms from the guest's `zl:composed`; median of 3 traces, min–max in brackets):

| Slice | Thread | Base | Broad | Native |
|---|---|---|---|---|
| composed → the host's `sendChanges` begins: the rest of the guest's frame, the batch's JSON encoding in QuickJS, the bridge, and the host decoding the batch (kotlinx.serialization) | Treehouse | **157.3** (151.9–157.8) | **61.7** (59.7–62.5) | — |
| the host's `sendChanges`: `UiChange.fromProtocol` for 1,593 changes (property values decoded) | Treehouse | **56.0** (51.8–56.8) | **8.5** (7.4–8.5) | — |
| wait for the main thread | — | 0.3 | 0.6 | — |
| applying the batch: `HostProtocolAdapter`, widgets created (`msg ViewContentCodeBinding$sendChanges`) | main | **27.8** (24.2–27.9) | **8.7** (7.2–8.8) | — |
| wait for the frame | — | 1.7 | 1.8 | — |
| the data frame (`Choreographer#doFrame`) | main | 142.3 (138.8–144.9) | 148.3 (117.5–149.1) | 130.5–135.0 |
| of it: `Recomposer:recompose` | main | 20.9 | 21.7 | 23.8–25.1 |
| of it: `Record View#draw()` (Compose's measure and layout, then recording) | main | 116.8 (105.8–117.1) | 119.5 (90.3–120.6) | 100.4–105.5 |
| **the data frame's main-thread end** | | **382.0** (375.8–386.0) | **225.5** (199.0–230.6) | — |
| frame start → presented (frame timeline) | RenderThread, SurfaceFlinger | 159.8 (157.9–164.6) | 131.9 (one trace matched) | 147.8–153.2 |
| **presented** | | **401.0** (398.1–401.0) | 213.4 (one trace) | — |
| JIT compilations in the window (JIT thread time) | Jit thread pool | **28** (100.3 ms), 24–25 of them kotlinx.serialization | **0** | — |

- **Where the base's time goes.** From 46 ms after `composed` the JIT compiles kotlinx.serialization's
  JSON decoder (`StringJsonLexer`, `StreamingJsonDecoder.decodeSerializableValue`, `JsonTreeReader`,
  `PropertyChange$$serializer.deserialize`, …, all `kind=Baseline`: that is the JIT's first, quick
  compiler tier, not the baseline profile): the host is decoding the batch in the interpreter. Then
  `UiChange.fromProtocol` and, on the main thread, `GenText1ProtocolNode.apply` and the generated
  `applyGenerated` modifiers (its compile alone took 36.9 ms on the JIT thread during the frame).
  The Treehouse thread runs 227 of the window's 382 ms (broad: 81 of 199). The traces used the broad
  profile; the narrow one does not cover the generated gate-A code (see series Y).
- **The bench's `drawn` comes before the pixels**, for every form: it is `ViewTreeObserver.onDraw`,
  which runs as the frame starts drawing, before `Record View#draw()`, where Compose measures and
  lays out. Series G's composed → drawn (230.5) therefore leaves out about 110 ms of the data frame and
  about 20 ms to its present; native's "2 ms" leaves out its own 130 ms frame (native's present
  comes about 150 ms after its frame starts too).
- **What the profile does not touch: the first layout of the list** (`Record View#draw()`, 90–121 ms
  traced). Native pays nearly the same (100–105 ms), so it is a first-use cost of Compose itself (the
  list's first measure, text and fonts), not the guest's, and not part of the gap to native.

**As shipped, three builds (series Y)**: no prestart or prefetch, 09:02–09:24, 4 rounds of 4
launches per build, every install in the same profile state (the reference profile holding the
build's own rules, nothing else). Ms from the process start or between marks; median (min–max),
n=16 per build:

| Measure | Base | **Narrow (ships)** | Broad (the PR's first profile) |
|---|---|---|---|
| the activity's first frame (`firstDraw`, "Loading…") | 403.5 (397–413) | 404 (397–577) | 391.5 (387–399) |
| the code load: starts → succeeds | 488 → 1,111 | **396 → 952** | 375 → 920.5 |
| the guest's request | 1,392.5 | **1,180.5** | 1,128.5 |
| request → parsed | 159.5 | 155 | 153 |
| parsed → composed (the guest's composition) | 994 | 1,007.5 | 998.5 |
| composed → batchSent | 200 (195–216) | **76.5** (75–80) | 68 (67–72) |
| batchSent → inTree (applying the batch) | 26 (24–29) | **20** (18–24) | 7 (6–7) |
| inTree → drawn | 23.5 (19–27) | 19 (17–22) | 21.5 (16–27) |
| drawn → `dataFrameEnd` | 111 (104–141) | **104.5** (92–138) | 84.5 (80–103) |
| **composed → `dataFrameEnd`** | **365** (348–408) | **223.5** (207–254) | 181.5 (172–198) |
| **the data frame's end (`dataFrameEnd`)** | **2,921.5** (2,883–2,988) | **2,566.5** (2,523–3,349) | 2,472.5 (2,436–2,521) |
| data drawn (`dataDrawn`) | 2,804 (2,763–2,885) | 2,463 (2,429–3,233) | 2,389 (2,357–2,424) |

Per round, the data frame's end (rounds 1–4): base 2,924, 2,921.5, 2,922, 2,905; narrow 2,643,
2,562.5, 2,586.5, 2,554; broad 2,473.5, 2,468.5, 2,464.5, 2,489. No overlap between the builds in any
round. Narrow's round 1 holds two slow launches (3,349, its code load succeeding at 1,408, and
2,719); its other 14 launches are 2,523–2,610.

- **The narrow profile takes 355 ms off the as-shipped cold start** (the data frame's end
  2,921.5 → 2,566.5, −12%; data drawn 2,804 → 2,463): 212 ms before the guest's request (the code
  load starts 92 ms sooner and succeeds 159 ms sooner) and 141.5 ms after `composed` (365 → 223.5).
  The guest's composition does not change (994 → 1,007.5).
- **What the broad profile adds, 94 ms**: 42 ms after `composed`, mostly applying the batch (20 → 7)
  and the frame's draw (104.5 → 84.5): the generated gate-A protocol nodes and modifiers
  (`dev.keliver.compose.gatea.*`, `GenText1ProtocolNode.apply`, `applyGenerated` in the traces),
  which the narrow profile leaves to a profile the codegen would emit (a follow-up). And 52 ms before
  the request (the code load starts 21 ms and succeeds 31.5 ms sooner): `dev.keliver` code the narrow
  profile does not list, **including the sample's own** (5,410 profile lines from `dev.keliver.sample`:
  ThreewayActivity, ThreewayPrestart, BenchHostHttp, LocalFixture, the recorder). An app outside
  `dev.keliver` would not get that part. It was not split further.
- **The activity's first frame does not move** with either profile in this state (403.5, 404,
  391.5). The 88 ms series W showed came from ART's own saved profile (below).

### Which profile ships, and what it costs

**The narrow profile ships** (`keliver-treehouse-host/src/androidMain/baseline-prof.txt`, 15 lines):
hot, startup and post-startup methods of `dev/keliver/treehouse`, `dev/keliver/protocol`,
`dev/keliver/widget`, `app/cash/zipline` and the kotlinx.serialization packages the decode uses (the
top-level package, `json`, `internal`, `encoding`, `descriptors`, `modules`), one line each; startup
classes only for the hot packages (treehouse, protocol, Zipline, kotlinx.serialization's
`json/internal` and `internal`). The broad first version (all of `dev/keliver`, `app/cash/zipline` and
`kotlinx/serialization`, methods and startup classes) also compiled an app's own code whenever the
app lives under `dev.keliver`, as the sample does, plus every other Keliver library in the app. The
narrow one keeps 79% of its gain (355 of 449 ms) for about a third of its size:

| Build | Reference profile lines: kotlinx.serialization / Zipline / `dev.keliver.protocol` / `dev.keliver.sample` / gate-A | `base.odex` | `base.art` (app image) | `base.vdex` | `speed-profile` compile |
|---|---|---|---|---|---|
| Base | 0 / 0 / 0 / 0 / 0 | 12.35 MB | 1.87 MB | 52.63 MB | 8.3–9.1 s |
| **Narrow** | 10,590 / 6,828 / 1,895 / 0 / 0 | **14.07 MB (+1.72)** | **2.10 MB (+0.23)** | 52.63 MB | **9.2–9.3 s** |
| Broad | 15,273 / 7,062 / 3,899 / 5,410 / 17,261 | 17.26 MB (+4.92) | 2.70 MB (+0.84) | 52.63 MB | 11.1–11.7 s |

Sizes from `ls -l` of the install's `oat/arm64` files (the same in all four installs of a build);
the compile is the wall time of `cmd package compile -f -m speed-profile` on the phone (four installs
each). The lines are counted in the profile dump: every line that mentions the package anywhere,
including signatures in other classes' methods. The Zipline and `dev.keliver.protocol` columns differ
between narrow and broad for that reason (both profiles cover those packages fully; broad's extra
lines are classes only it covers).
An adopter pays the narrow profile's cost in every app that uses `keliver-treehouse-host`; it is
paid once per install or update, on the phone's storage and at the install's compile.

### Earlier series W and X (the broad profile, mixed profile states)

**Before and after, untraced** (series W, `x=prestart,prefetch`, ms from the process start or
between marks; median (min–max)). The broad arm pools two profile states (see Method):

| Measure | Base (rounds 1, 3; n=8) | **Broad (n=12; rounds 1, 3: 30,552 lines, round 2: 15,273)** | Base, round 2 (ART's own saved profile; n=4) | Native (n=8) |
|---|---|---|---|---|
| composed → batchSent | 205 (203–206) | **63** (60–71) | 62 (61–68) | — |
| batchSent → inTree | 26 (25–28) | **4** (3–7) | 3.5 (2–4) | — |
| inTree → drawn | 21.5 (20–23) | 19 (8–25) | — | — |
| drawn → `dataFrameEnd` | 110 (101–134) | **73.5** (60–87) | 71 (60–78) | 79 (9–91) |
| **composed → `dataFrameEnd`** | **362.5** (353–390) | **155.5** (137–184) | 152.5 (145–173) | 80.5 (68–93) |
| composed → drawn (series G's measure) | 252.5 (252–256) | 88 (74–99) | — | — |
| the code load: starts → succeeds | 326 → 965 | **210 → 710.5** | 197.5 → 703 | — |
| the activity's first frame (`firstDraw`, "Loading…") | 399 | **311** | 292 | 359 |
| the guest's request reaches the host (`hostIn`) | 1,259 | **930.5** | — | 423 (its request) |
| ... and waits for the prefetched response | 52 (27–79) | 374.5 (278–409) | — | — |
| the prefetched response (`prefetchDone`) | 1,306 | 1,306 | — | — |
| parsed → composed (the guest's composition) | 1,013 | 1,012 | — | — |
| **the data frame's end (`dataFrameEnd`)** | **2,711** (2,674–2,769) | **2,503.5** (2,452–2,538) | 2,454 | 665 |
| data drawn (`dataDrawn`, series G's measure) | 2,603 (2,572–2,637) | 2,432 (2,378–2,462) | 2,381 | 615.5 |

Per round, the data frame's end: base 2,710 and 2,711 (rounds 1, 3); broad 2,478.5 (30,552
lines), 2,530 (15,273) and 2,494.5 (30,552): no overlap between the arms.

- **Broad took composed → the data frame's end from 362.5 to 155.5 ms (−207)** in this pooled arm
  (round by round: 150, 180 and 151.5; the 15,273-line round matches series Y's 181.5). The host's
  decode, mapping and apply fell from 231 to 67 ms (composed → inTree), and the frame's own draw by
  about 36 ms (the generated widgets' modifiers compiled). What is left against native (75 ms) is
  composed → batchSent, 63 ms: the guest encoding 1,593 changes in QuickJS, the bridge, and the
  host's decode, now compiled. That part recurs on every screen, in proportion to the batch.
- **The code-load and first-frame gains in this table come mostly from the 30,552-line state**, not
  from the shipped profile: those installs behaved like the base with ART's own saved profile (the
  code load succeeding at 698–710.5, the first frame at 305.5–307; base round 2: 703 and 292), while
  the 15,273-line round matched series Y (796.5 and 396.5). So the "255 ms off the code load" and
  "88 ms off the activity's first frame" this section first reported are withdrawn. In a clean state
  (series Y) the broad profile takes 190.5 ms off the code load, part of it the sample's own code, and
  the first frame does not move.
- **In this session the prefetch absorbs the code load's gain.** The prefetched response came back at
  about 1,306 ms in every launch of both builds, so with broad the guest's request waits 374.5 ms
  for it and the list's data arrives no sooner (parsed 1,336 → 1,327.5). In series G the same
  prefetch was done at about 297 ms. The sample's Android code is unchanged since then, and the
  traced launches show the same ~1.3 s. **Series Q (below) rules out the host name and the
  fixture.** Its 18 launches were done at 292–333 ms, so the extra second belonged to that morning's
  session, and its cause is not established. Series X and Y, without the prefetch, are the measure.
- **Round 2's base shows what ART does on its own**: its install's 12 s launch left ART's own
  profile (3,101 kotlinx.serialization lines), and with it the base build matches broad (152.5 ms).
  On a phone, ART builds that profile from the app's own launches and compiles it at the next
  idle-and-charging dexopt. **The shipped profile matters before that, on a Play install or update**:
  Play compiles the app at install from the shipped profile, and the first launches run that code
  until Play's cloud profiles (gathered from a release's users) cover the host too. A sideloaded
  install is not compiled from the shipped profile until that same idle dexopt, by which time ART's
  own profile exists. The bench's forced `speed-profile` compile stands in for a Play install. A
  guest update (OTA) does not change the host's code, so it does not reset this.

**The as-shipped arm, first try** (series X, no prestart or prefetch, 08:33–08:40; 2 rounds of 4
launches per build), per round with each install's profile state (n=4 each):

| Measure | Base, round 1 (0 lines) | Broad, round 1 (15,273) | Base, round 2 (3,101: ART's own saved profile) | Broad, round 2 (30,552) |
|---|---|---|---|---|
| the activity's first frame | 389 | 378 | 287.5 | 297.5 |
| the code load succeeds | 1,110 (1,103–1,112) | 920 (900–933) | 771 (768–776) | 781 (775–783) |
| composed → `dataFrameEnd` | 373 (351–386) | 188 (184–193) | 149.5 (141–157) | 151 (145–157) |
| **the data frame's end** | **2,920** (2,899–2,944) | **2,491** (2,482–2,497) | 2,264 (2,259–2,285) | 2,290.5 (2,267–2,307) |
| data drawn (`dataDrawn`) | 2,802 (2,797–2,813) | 2,401.5 (2,398–2,406) | 2,197 (2,192–2,217) | 2,222.5 (2,196–2,237) |

- **Round 1 is the only clean pair: 2,920 → 2,491 (−429)**, which series Y repeats with 16
  launches per build (−449 for broad). In round 2 both installs carried a profile of the app's own
  launch and measure the same. This section first reported the two rounds' broad installs pooled
  (2,394.5, −525.5): that median fell between the two states and is withdrawn.
- Against series G's as shipped on 1.28.0 (2,834 to `dataDrawn`), series Y's base is the same build
  without the profile (2,804). With the shipped (narrow) profile, 2,463: still far from NF4's target
  (native 615.5 today, so +1,847.5).

**First use, or every screen.**

| Slice (base, ms) | Kind | Removed by the shipped (narrow) profile |
|---|---|---|
| host decoding the batch (in composed → sendChanges, ~95 of 157) | first use per process (interpreted until the JIT compiles it); every cold start until ART compiles the host | yes (composed → batchSent 200 → 76.5 untraced; broad: 68, traced 157 → 62) |
| `UiChange.fromProtocol` (56) | the same | yes (broad, traced: → 8.5) |
| applying the batch on the main thread (28) | the same | partly (untraced 26 → 20; the generated protocol nodes stay interpreted; broad: → 7) |
| the generated widgets' modifiers in the frame's draw (~36 untraced) | the same | no: generated code, for a profile the codegen would emit (broad covered the sample's) |
| the guest's encoding, the bridge, the compiled decode (~62 with the profile) | every screen, with the batch's size | no |
| Compose's first layout of the list (~100–120) | first use, native too | no (Compose's own profile already covers its code) |
| the frame to the screen (~20) | every frame | no |

### Why the bench's prefetch took a second (series Q)

In series W and the traced launches (07:55–08:30), the `prestart,prefetch` arm's prefetched
response came back about 1.14 s after it was sent: done − start 1,136–1,171 ms in every launch,
against about 143 ms the night before (series G). That held from the provider (sent at about 162
ms) and from `onCreate` (each series' first launch, sent at about 232 ms). Native's first request on
the same client, sent at 462–573 ms, took 140 ms. **The excess is a fixed 1.0 s.** Linux retransmits
an unanswered TCP SYN after 1 s, so a lost first SYN fits it. A DNS retry does not: none was seen
here, and a resolver retry is not a 1 s step.

**Series Q** (the A12, 09:46–09:51, uptime 7 h 34 min – 7 h 40 min, on battery 73% → 72%, 24–25 °C,
thermal status 0, CPU idle 63–83% before each block, `deviceidle` light state IDLE throughout). One install of this
branch's sample, compiled `speed-profile` as in series Y. Three arms, interleaved, 2 rounds of 3
measured cold launches each. Each block's first launch is unmeasured, because the prestart acts on
the previous launch's settings. A new bench option, **`x=netlog`**, writes OkHttp's `EventListener`
times for the process's first call (here the prefetch) and the fixture's listen, first accept and
first request line into the cold line's `netEvents`. **`x=ip4`** sends the bench's requests to
`127.0.0.1` instead of `localhost`. Its first try (series P) was refused by the sample's cleartext
policy, which listed only `localhost`; that policy now lists `127.0.0.1` too. Median (min–max), n=6
per arm, ms from the process start:

| Arm | Prefetch sent | Name lookup | Connect | Prefetch done | The guest's request (`hostIn`) | Data drawn | The data frame's end |
|---|---|---|---|---|---|---|---|
| `prestart,prefetch` (`localhost`) | 153.5 | 3 (3–6), `127.0.0.1` only | 6 (5–9) | **296** (294–308) | 1,065.5 | 2,224.5 | 2,332.5 |
| `prestart,prefetch,ip4` (`127.0.0.1`) | 153.5 | none | 6 (5–8) | **292** (288–294) | 1,059.5 | 2,219 | 2,328 |
| `prefetch` (sent from `onCreate`) | 196.5 | 2.5 (2–3) | 5 (4–6) | 332 (331–333) | 1,201 | 2,354 | 2,463.5 |

- **The host name is not the cause.** `localhost` resolves in 2–6 ms to `127.0.0.1` alone (no `::1`
  is tried), and the 127.0.0.1 arm is 4 ms sooner, within the spread.
- **The fixture is not the cause.** It listens 18–24 ms before the prefetch's call starts, and it
  accepts the connection 2–4 ms after the connect starts (series P).
- **What happened that morning is not established.** The extra second did not come back in 23
  launches (series P's 5 included). The phone was then, as now, locked on battery and in Doze: the
  bench never unlocks it (`wait_for_unlock=true`). `dumpsys netpolicy` shows the sample's uid on the
  Doze firewall chain's deny between launches. Today the rule turns to allow when the process becomes
  the top app. That was 3–8 ms after the process start in the launches checked, and 125 ms in one
  unmeasured launch. Either way it came before the first SYN (150–200 ms). A SYN sent while
  the deny rule still held would be dropped and resent 1 s later, which matches the morning's fixed
  second. But the firewall log of that hour had rolled over, so this is a candidate, not a finding.
  Series Q's phone script recorded the Doze state (`dumpsys deviceidle`) before each launch and the
  uid's lines of `dumpsys netpolicy` after each block. A run of the prefetch arm should do the same
  and add `netlog`; if it happens again, that shows which step takes the second.
- **With the prefetch working, the narrow profile's code-load gain reaches the data.** The
  guest's request finds the response waiting (wait 0–1 ms; series W's broad arm waited 374.5 ms).
  Data drawn is 2,224.5 ms, against series Y's as-shipped narrow 2,463 (−238.5; another session,
  not an interleaved A/B). The guest's composition is still the largest part (parsed → composed
  about 1,000 ms on 1.28.0).
- **The library's prefetch**: the production host sends its prefetch when the first activity is
  being created (`onActivityPreCreated`). That is the third arm's moment, which was not delayed. The
  templates are unchanged.

**Inferred, not measured.** The split of the remaining ~62–70 ms between QuickJS (encoding) and
the host's compiled decode (no sampling profiler; see Method). That production apps get the
profile: the AAR carries `baseline-prof.txt` (checked in the library's build, and now by
`checkBaselineProfilePackaged`, part of the module's `check`), AGP merges it into the app's profile
(checked in the sample's merged profile), and profileinstaller installs it on a sideloaded install.
**The production-host template gets profileinstaller only transitively** (through activity-compose
and Compose UI), which was not checked in a template-built app; Play installs use the profile
without it. **An app that repackages classes with R8** (`-repackageclasses`) moves them out of these
packages, and the package rules then match nothing; the template's R8 settings do not repackage, and
whether R8's profile rewriting keeps wildcard rules was not checked. *Later checked (PR "Baseline
profiles: opt-out for the host profile, opt-in for generated code")*: on AGP 9.3.1 it does. AGP
expands the wildcards against the classes before R8 and R8 rewrites the expanded list, so in one
build with `-repackageclasses ''` the repackaged classes kept their rules under their new names
(docs/DEVICE_HOST.md, "Baseline profiles"); AGP 8.x was not checked. The BigInt build was not
measured: the host's part does not depend on the engine, so the same gain is expected there (series
G's 241.5 against 230.5 ms suggests so). The heavy screen and other screens' first batches were not
measured. Generated code (gate-A's and an adopter's own) is not covered by the shipped rules. The
traced frame-timeline present was matched for 4 of the 6 guest traces only.

## Flutter on the A12, 2026-11-22

> **Kotlin 2.4.20 (noted 2026-11-23).** The Keliver guest was linked with Kotlin 2.4.20, whose production builds were not minified (KT-89449; on the three-way guest 2.4.21 is 4.4% smaller (2.7% gzipped) in the `.zipline` bundle, and with 2.4.20 Mac code load was about 3% slower, #303). These numbers are re-measured at the next phone checkpoint; they are kept as measured.

The owner's question: **is Keliver faster than Flutter?** Flutter can be added to an existing app
(add-to-app); it was set aside earlier for the size it adds. Over the air Flutter needs Shorebird: on
Android a patch replaces the compiled Dart library, so a patched screen runs ahead-of-time compiled,
as fast as the shipped one; on iOS changed functions run in Shorebird's interpreter (an iPhone
question, not run here). So on this phone **Flutter release (AOT) stands for Flutter over the air on
Android too**: that is reasoned from how Shorebird patches on Android, not measured; Shorebird's
engine fork and its updater ran nowhere here, and what they add to a start is unknown. Same Galaxy
A12, same day, same app and process: native Compose, Keliver (Zipline 1.28.0 as shipped, with #286's
host profile; and the **BigInt fork, unreleased**, as series G built it), and Flutter with both
engine policies. No Shorebird account or service was used.

**Answer in one table** (medians; how each was measured is in the series below; corrected after
the review, see [Review fixes](#review-fixes-2026-11-22)):

| Question | Native | Keliver 1.28.0 | Keliver BigInt fork (unreleased) | Flutter | Is Keliver faster than Flutter? |
|---|---|---|---|---|---|
| Cold start to the list's data (frame end, ms) | 728.5 | 2,537.5 | 1,800.5; `prestart,prefetch` 1,543.5 | on demand 633.5; pre-warmed 558 ‡ | **No**: 1.9 s behind on 1.28.0, 0.9 s with BigInt and both NF4 steps. Flutter's 95 ms lead over native is where the bench sends the request (`initState`, 100 ms before native's first composition), not Flutter's rendering (F1) |
| Open, list → detail (frame end, ms from the tap) | 28 | 455.5 | 217.5 | 30.5 (pre-warmed 31) | **No**: 15× (1.28.0) and 7× (BigInt) Flutter's time (F2) |
| Open, heavy detail | 59 | 1,081 | 548 | 44.5 | **No**: 24× and 12× (F2) |
| Back, header button (frame end) | 81 | 921.5 | 516 | 33.5 | **No** (F2) |
| Back on the screen (the list presented, ms from the DOWN; Keliver with `x=retainhost`) | 168.0 | 91.3 (74.5–106.7) | 79.0 (68.5–84.4) | 99.6 (90.3–116.6) | **About the same, only with the retained back**: within about 20 ms, the ranges overlap, one run of 8 touches per arm, and Keliver's is system back against Flutter's header button (F2, pixels). Without it, no (0.67–1.0 s) |
| Cold start on the screen (the data frame presented; F1's frame end + each arm's measured gap) | ≈ 769 | ≈ 2,596 | ≈ 1,580 with both NF4 steps | on demand ≈ 738; pre-warmed ≈ 664 | **No**: about 0.84 s behind on BigInt with both steps (inferred from measured parts) |
| Scrolling: missed vsyncs per fling, gentle / fast (down) | 0.5 / 3 | 1 / 3.5 | 1 / 10 | 0 / 0 | **No**: Flutter missed 2 vsyncs in 33 flings, native 36 in 33, Keliver 62 in 31 (1.28.0) and 133 in 33 (BigInt; its fast flings may be confounded by the charger) (F3) |
| Size added on an arm64 phone (installed) | — | about 3.9 MB (engine 0.83 + bundle 0.91 deflated in the APK + its 2.11 cache copy) + an unmeasured host dex share | about 4.2 MB, the same way | 16.1 MB (floor: 15.8), dex included | **Yes, far smaller**: Flutter adds about 16 MB, Keliver about 3–4 MB plus a host dex share not measured (F4) |
| Memory after the list (PSS, MB; first reading 6 s after the data / second reading, after a Java collection; n = 2 per arm) | 61.5–62.7 / 55.2–56.1 | 87.2–87.3 / 72.5–72.7 | 87.7–88.0 / 72.9–73.3 | 89.3–89.9 / 83.5–84.0 (pre-warmed 90.7–91.5 / 84.9–85.7) | **Somewhat less**: 1–3 MB below Flutter at the first reading, about 11 MB after a Java collection, which does not touch Dart's heap (F5) |

‡ Pre-warmed Flutter's 558 is not a like-for-like frame end: in 5 of its 10 launches the data frame was
built before Flutter's surface showed its first frame (`dataFrameEnd` 540–552 against
`flutterUiDisplayed` 545–556, 4–5 ms later each time), so the window bounds it (F1, on the pixels). Its on-screen figure (≈ 664)
is the one to compare.

**What this does not cover.**
- **What a Flutter screen is.** Flutter draws its own widgets: its screens cannot reuse the app's
  Compose components or design system (this bench rebuilt the screens in Dart), and they use no
  platform views here. Keliver's guest renders through the host's own native widgets (Compose on
  Android). The tables compare speed and size, not that trade.
- **Shorebird itself.** Its engine fork and updater were not run; the Android claim above is reasoned.
- **The BigInt fork is unreleased** (a scratch publish, `1.28.0-q20260604`), and `prestart,prefetch`
  is not Keliver's default. "BigInt" below means that fork, not something an adopter has today.
- **R8.** No arm was minified (see [Why it is fair](#why-it-is-fair-and-where-it-is-not-1)).
- **iOS** (see the iPhone note under [the verdicts](#verdicts-is-keliver-faster-than-flutter)).

### What was built (a recipe, not merged)

| Piece | Where |
|---|---|
| The Flutter module: the same list, detail and heavy detail as `ThreewayScreens.kt` (Material 3, the same colours, sizes and fixed geometry: header 56, actions 48, status line 24, rows 72 dp), navigation as a state switch with no transition (as every form), the list a `ListView.builder` (lazy, per-row height 72, no `itemExtent`, keys by id), rows with `InkWell` (Material's ink, as Compose's ripple) | [`flutter-a12/main.dart`](flutter-a12/main.dart): the module's one source file, with its [`pubspec.yaml`](flutter-a12/pubspec.yaml). The module is `flutter create -t module` (Flutter 3.50.1 stable, Dart 3.14.0, engine `4d35714800`), with only `material_ui` as a dependency (3.50's Material package; the template's `cupertino_icons` removed) |
| Its data: the same in-app fixture (`--ez localApi true`, `GET http://localhost:8491/items?size=small&seq=1`, the same bytes, the same 100 ms delay), `dart:io`'s `HttpClient` (one client, keep-alive), `jsonDecode` and a hand mapping to the same fields, on the UI isolate | the same file |
| The fourth form in the same app and activity: `-e form flutter` in `ThreewayActivity`, a `FlutterView` attached to a `FlutterEngine` (add-to-app, the AAR from `flutter build aar --release`, consumed from a local Maven repository in scratch), the activity forwarding its lifecycle to the engine; **two engine policies**: on demand (the engine created and its Dart entrypoint run in `onCreate`) and `x=prewarm` (created and run in `bindApplication` from the prestart provider, Flutter's documented recommendation; like `prestart`, it acts on the previous launch's settings) | [`flutter-a12/flutter-recipe.patch`](flutter-a12/flutter-recipe.patch) against main `2073a4ace` (`sample/` only; not built by CI). Build: [How to run it](#how-to-run-it) |
| The marks: Dart takes them on the wall clock (`DateTime.now()`, as `System.currentTimeMillis()` on the host) and sends them over a `MethodChannel` after taking them, so **the channel's own latency is in no mark**. What the channel costs: the host receives a mark 1 ms after Dart took it (median; p90 2, max 6; 157 messages). `composed` is taken in `build()`; `frameEnd` (Flutter's `drawn`) in a post-frame callback of the first frame that shows the content: the end of the UI thread's work for that frame, the Compose forms' `frameEnd` (below). The data frame's raster end comes from `FrameTiming` | the same files |

**One mark added for every form** (in the recipe): `frameEnd`, posted to the front of the main thread's
queue from the `drawn` draw listener, so it is the end of that frame's main-thread work. It is
`dataFrameEnd` (NF4's measure) for every step, not only the first data. For the Compose forms `drawn`
fires as the frame **starts** drawing, before Compose measures and lays out inside
`Record View#draw()`; Flutter's post-frame callback comes **after** its build, layout, paint and
compositing. So **`frameEnd` is the like-for-like measure** in every table below; `drawn` is given
for continuity with the earlier series. After the frame end, each form still rasterises (the
RenderThread for Compose, Flutter's raster thread) and the compositor presents: the pixel checks
measure that last part.

### Why it is fair, and where it is not

- **Same app, process, launcher and drivers.** One APK per build holds all four forms; the same
  activity starts each by the `form` extra, the same in-app fixture serves the same bytes, the same
  synthetic taps drive navigation, and every form logs the same cold line and step lines. Every
  install was series Y's (uninstall first, profileinstaller's broadcast, `speed-profile` compile).
- **ART profile state.** That `speed-profile` compile is the best case for the Kotlin arms: the
  app's baseline profile (with #286's host profile) is compiled ahead of time. Flutter's Dart code
  needs no ART profile (it is AOT machine code in `libapp.so`), but its Java embedding is not in the
  app's baseline profile, so that small part ran interpreted or JIT-compiled.
- **No arm went through R8.** The sample's `benchmark` type (release-shaped, debug-signed) sets
  `isMinifyEnabled = false`, and every build's log shows D8 only (`dexBuilderBenchmark`,
  `mergeDexBenchmark`, no `minifyBenchmarkWithR8`). So native Compose, Keliver's Compose host and
  Flutter's Java embedding all ran unshrunk and unoptimised by R8. Flutter's Dart code is optimised
  by its own AOT compiler (`--release`, tree-shaken), so **only the Flutter arm's main code was
  optimised for release**. This disfavours the Kotlin side, most where the host's Compose work
  dominates: native's and Keliver's scrolling (native's 36 missed vsyncs against Flutter's 2),
  native's back (81 ms against Flutter's 33.5), and "Flutter starts as fast as native". It cannot
  close the Keliver-against-Flutter gaps on cold start and open, which are the guest's work in
  QuickJS (seconds and hundreds of ms, not the tens R8 might change). Not re-run with R8 here (no
  phone in the review round); a run of F2 and F3 for native and Keliver on the minified `release`
  type is a follow-up before the scroll and native comparisons are read as final.
- **Native libraries stripped for every arm**: Flutter's engine AAR
  ships `libflutter.so` unstripped (170 MB per ABI), and AGP could not strip without an installed
  NDK, so the recipe names one (`threewayNdkVersion`). This also strips `libquickjs.so`, which every
  earlier series packaged unstripped (2,432,744 bytes on arm64; stripped, 833,272): the loaded code
  is the same.
- **ABIs.** Every APK carries all the sample's ABIs: Keliver's `libquickjs.so` for arm64-v8a,
  armeabi-v7a, x86 and x86_64; Flutter's for arm64-v8a, armeabi-v7a and x86_64 (Flutter builds no
  32-bit x86). The phone runs arm64. Sizes are given per ABI.
- **Renderer.** Flutter used Impeller's **OpenGL ES** backend on this phone (logcat: "Using the
  Impeller rendering backend (OpenGLES)"); the `FlutterView` renders into a `SurfaceView` (the
  default).
- **Differences that could not be removed:**
  - *Where the first request starts.* Native starts it in its first composition (after
    `setContent`); Flutter in `initState`, when `runApp` builds the tree, before Flutter's first
    frame; Keliver after its code has loaded and its first composition ran. That is where the
    bench's code for each form sends the request (the usual place in each framework, but a choice,
    not something the framework forces): Flutter's `initState` sends it at 369 ms, native's first
    composition at 469, and those 100 ms are Flutter's 95 ms lead over native at the frame end (F1).
    No arm was given a prefetch except Keliver's `prestart,prefetch` arm.
  - *Pre-warm also prefetches.* A pre-warmed engine runs `main()` in `bindApplication`, so the
    screen's request goes out before the activity exists. That is what Flutter's recommended policy
    does; it is Keliver's `prestart,prefetch` in effect.
  - *JSON on the UI thread.* Flutter parses on its UI isolate (Flutter's guidance moves only large
    parses to an isolate); native parses on a background thread. The timed requests are the
    4.5 KB list.
  - *Text.* Both use the system's Roboto at the same sizes; line heights follow each framework's
    defaults (Compose's Material 3 `bodyLarge`, Flutter's Material 3 text theme), so rows are the
    same 72 dp but the text inside may sit a pixel or two differently. Text only, no images, in
    every form (the bench's screens have none).
  - *The recipe adds Flutter's code to every arm's APK* (about 1 MB of dex, never loaded by the other
    forms): native and Keliver were measured in the same APK as Flutter. Their numbers agree with the
    earlier series (below).
  - *The press feedback.* Flutter's `InkWell` (an ink splash; Material 3's `InkSparkle` on Android)
    against Compose's ripple: both fade in.
  - *Back.* Flutter's back is the header's button (as native's and Keliver's `bench=nav`); system
    back with a retained screen exists only in Keliver's host (`x=retainhost`), so it has no Flutter
    or native row.

### Builds and phone

- **Builds**, all from this branch (main `2073a4ace`) with the recipe applied: the prototype
  published to a scratch Maven repository (`0.0.0-gateb`), then the sample's benchmark APK.
  **main** is Zipline 1.28.0 as built (with #286's narrow host profile in `keliver-treehouse-host`);
  **bigint** is the same sample built with `use-fork.init.gradle` and `-PziplineForkLongAsBigInt`
  against the fork's scratch publish from NF2 step 3 (`1.28.0-q20260604`; not rebuilt with
  `apply.sh` this session). The init script named both guest links it switched, and **the engine in
  the APK was checked**: its arm64 `libquickjs.so` is the fork's (996,184 bytes stripped, against
  1.28.0's 833,272) and its guest bundle differs. Both APKs carry the same Flutter AAR.
  F1 used the first Flutter AAR; F2, F3 and the pixel checks a second one whose only change is the
  matcher that pairs a frame with its `FrameTiming` (the first one's matcher picked an earlier frame
  for the pre-warmed arm; the second one's never matched, so its runs have no raster end). The
  screens, the marks and the timing code are the same in both.
- **Every series ran from a script on the phone** (`nohup sh`), APKs installed there, each install
  as series Y's. Before each round or run the script woke the screen and logged uptime, load, the
  battery, the thermal status and the CPU idle share over 2 s. The phone was up since about 02:11 IST
  (14 h at the start), unplugged and on battery except where a series' "Phone state" says
  otherwise (F3's BigInt arm and main's pixel checks ran on the charger).
- **Which recipe built which APK.** `main-flutter` and `bigint-flutter` (16:15, F1) and
  `main-flutter2` and its BigInt twin (16:33: F2, F3, the pixel checks) were built from the
  recipe as it stood at those times; the exact patch text was not saved per build. The committed
  [`flutter-recipe.patch`](flutter-a12/flutter-recipe.patch) is the host wiring as it stood after
  every run. A copy saved at 16:21, between the two builds, has the same Flutter wiring with the
  Flutter frame-end branch written inside the draw listener rather than in a shared function
  (`contentDrawn`); a full line-by-line reconciliation was not done. `main.dart` and `pubspec.yaml`
  match the scratch module that built the second AAR.

### F1. Cold start to the list's data drawn

**Method.** Series E/G's cold launch (`am force-stop`, 4 s, `am start` with the in-app fixture,
wait for the process's cold line; marks in ms from the process start, `Process.getStartUptimeMillis`).
Each install ran rounds in which the arms alternate; each arm's block is one unmeasured launch (it
writes the settings the next process's prestart or pre-warm acts on, and absorbs the previous arm's)
and 2 measured launches. After each install, one unmeasured launch per arm. Two installs of each
build (main: 3 rounds, then 2; bigint the same), interleaved main, bigint, main, bigint: **10
measured launches per arm**. Native ran on both installs. The measure is **the data frame's end**
(`dataFrameEnd`: Compose's main-thread end of the frame that shows the list's data, or Flutter's
post-frame callback of it); p90 is the nearest rank (the 9th of 10).

**Phone state.** 16:20–16:41, uptime 14 h 10 min – 14 h 30 min, on battery 53% → 51%, unplugged,
25.2–25.7 °C, thermal status 0, CPU idle 90–98% before each round.

| Arm (install) | The data frame's end: median | p90 | min–max | − native (same install) |
|---|---|---|---|---|
| native (main) | **728.5** | 734 | 722–765 | — |
| native (bigint) | **699.5** | 736 | 677–792 | — |
| Keliver 1.28.0, as shipped (main) | **2,537.5** | 2,591 | 2,519–2,637 | +1,809 |
| Keliver BigInt fork (unreleased), default settings (bigint) | **1,800.5** | 1,827 | 1,784–1,928 | +1,101 |
| Keliver BigInt fork (unreleased), `prestart,prefetch` (bigint) | **1,543.5** | 1,553 | 1,514–1,560 | +844 |
| **Flutter, on demand** (main) | **633.5** | 638 | 627–639 | **−95** |
| **Flutter, pre-warmed** (main) | **558** ‡ | 573 | 540–632 | **−170.5** |

‡ Not a like-for-like frame end: in 5 of these 10 launches the data frame ended 4–5 ms before
Flutter's surface showed its first frame (`flutterUiDisplayed`), so the window, not Flutter, bounds
it. Use the on-screen figure below.

**Where each start's time goes** (medians of the marks, ms from the process start):

| Mark | Native | Keliver 1.28.0 | Keliver BigInt, `prestart,prefetch` | Flutter, on demand | Flutter, pre-warmed |
|---|---|---|---|---|---|
| engine or prestart (in `bindApplication`) | — | — | prestart 86 | — | engine created 210.5 (pre-warm main-thread work 86.75; the bench's fixture before it, about 70) |
| `Activity.onCreate` | 126 | 128.5 | 201 | 127.5 | 282.5 |
| content set | 219 | 227.5 | 224.5 | 330 (engine created in `onCreate`: 86.3 on the main thread) | 329 |
| the window's first frame / Flutter's first frame on its surface | 382 | 379.5 | 386.5 | 446 / 503 | 451 / 527 |
| Dart `main()` / the guest's code load | — | 402.5 → 905 | 236 → 694 | `main()` 359 | `main()` 257.5 |
| the list's request | 469 | 1,138 | 864.5 (prefetched at 298) | 369 | 367.5 |
| response / parsed | 615 / 618.5 | 1,277 / 1,293.5 | 874.5 / 889.5 | 579 / 579 | 493 / 493.5 |
| list composed (`composed`) | 641.5 | 2,312.5 | 1,346 | 592 | 507 |
| **the data frame's end** | **728.5** | **2,537.5** | **1,543.5** | **633.5** | **558** |
| the data frame rasterised (Flutter's `FrameTiming`) | — | — | — | 712 | not valid (see Builds) |

- **Flutter starts as fast as native on this phone, and with pre-warm faster.** On demand its data
  frame ends 95 ms before native's; pre-warmed, 170.5 ms before. Flutter's own start costs about the
  same as Compose's: creating the engine is 86 ms of main-thread work (libflutter.so and libapp.so
  loaded, the Dart VM and isolate started), and Flutter's first frame reaches its surface at 503 ms
  against native's first window frame at 382. What puts it ahead is **where the bench sends the
  request**, not Flutter's rendering: `runApp` builds the screen right away and its `initState`
  sends the request at 369 ms, 100 ms before native's first composition sends its own (469); a
  native screen that sent its request as early would close most of the 95 ms. (Native also ran
  without R8, see above.) Flutter's request then takes longer to come back (210 ms
  against native's 146 with the same 100 ms fixture: Dart's first connection, while the UI isolate
  builds the first frame), and its data frame's UI-thread work is shorter (composed → frame end
  41.5 ms against native's 87, which includes Compose's first layout of the list inside the draw).
- **Keliver as shipped (1.28.0) is 1.8 s behind native and 1.9 s behind Flutter on demand**; with
  BigInt and both NF4 steps, 0.84 s behind native and 0.91 s behind Flutter on demand (0.99 s behind
  pre-warmed Flutter). Keliver's guest-only critical path is the same as series G and NF4 found: the
  code load (about 0.5 s), the first composition and the list's composition in QuickJS (1,019 ms
  parsed → composed on 1.28.0, 456.5 on BigInt with both steps).
- **Anchors.** Today's guest 1.28.0 matches series Y's narrow-profile build (2,537.5 against
  2,566.5 to the data frame's end), so adding Flutter's code to the APK did not slow the other forms.
  The BigInt arm is faster than series G's (1,439 against 1,866 to `dataDrawn` with both steps)
  most likely because it now carries #286's host profile, which series G's build did not (other
  changes on main since series G's build were not excluded); native is within 40 ms of series
  E/G/Y (644 to `dataDrawn` today, 604–618 then).
- **Pre-warm's cost to every other start.** A pre-warmed engine is created in `bindApplication` on
  every launch, whether or not the Flutter screen is shown: 86.75 ms of main-thread work before
  `onCreate` (here `onCreate` moved from 127.5 to 282.5 ms, of which about 70 is the bench's own
  fixture started in the same provider) and about 4 MB more native heap than on demand (F5). An app
  that pre-warms pays that on a launch that never opens a Flutter screen.

**On the pixels** (3 cold launches per arm, after F3). Series K's recorder: each launch under a
Perfetto trace and `screenrecord --output-format=frames --size 180x400`, both started before
`am start`; each raw frame matched to its composition and the display frame that presented it (series
K's rule), and classified on the phone: **the data frame is the first composited frame whose list's
second row has text in it** (at least 100 dark pixels in that row's band, the header's indigo and the
row's white padding in place), the same test for every form. Times are from the process start (the
trace's `performCreate` aligned to each launch's own `activityCreate` mark). Each launch alternated
with an unmeasured one of the same arm, as in F1. The recorder and the trace slow every start (the
frame end below is 85–300 ms later than F1's untraced one), so read **the gap**, not the absolutes.

**Phone state.** 17:25–17:38, uptime 15 h 14 min – 15 h 27 min, thermal status 0, CPU idle 96–98%.
Main's arms **on the charger** (53% → 58%, 30.0–31.7 °C), the BigInt arms unplugged again (58%,
27.3–28.1 °C).

| Arm | The data frame on screen (3 launches; median) | Its frame end, same launches (median) | Frame end → on screen (median, min–max) |
|---|---|---|---|
| native | 895, 831, 866 (**866**) | 825 | **40.7** (35.8–43.4) |
| Keliver 1.28.0 | 2,897, 2,854, 2,859 (**2,859**) | 2,817 | **58.5** (41.5–61.5) |
| Keliver BigInt | 2,226, 2,096, 2,138 (**2,138**) | 2,096 | **37.8** (36.2–41.7) |
| Keliver BigInt, `prestart,prefetch` | 1,776, 1,787, 1,828 (**1,787**) | 1,752 | **36.3** (34.8–47.1) |
| Flutter, on demand | 842, 846, 826 (**842**) | 738 | **104.1** (97.7–106.2) |
| Flutter, pre-warmed | 781, 747, 749 (**749**) | 643 | **105.8** (90.6–156.2) |

- **The data frame passes the same test in every form** (in all 18 launches the frames just before
  it had the header in place and 31 dark pixels in the row's band, the loading state; the data frame
  has 123–155), so "data drawn" means the same thing for all six arms. In every launch the frame that passed was presented after the
  form's frame-end mark, never before.
- **Flutter's data frame takes about 100 ms from its UI thread's end to the screen; Compose's about
  40** (Keliver's guest 36–59, as it is a Compose host). Flutter's raster thread rasterises the frame
  after the post-frame callback (F1's `FrameTiming`: 78.5 ms after the frame end, median) and its
  `SurfaceView` is presented after that. **Inferred, not measured**: most of the 78.5 ms is the
  first use of the screen's text and shapes in Impeller's GL backend.
- **So on the screen Flutter's lead over native is smaller than its frame-end lead**: in these traced
  launches Flutter on demand put the data on screen 24 ms before native (842 against 866), pre-warmed
  117 ms before. Applying each arm's gap to F1's untraced medians (**inferred**): native about 769 ms,
  Flutter on demand about 738, pre-warmed about 664, Keliver BigInt with both NF4 steps about 1,580,
  Keliver 1.28.0 about 2,596. Keliver still trails Flutter on demand by about 0.84 s (BigInt with both
  steps) and 1.86 s (1.28.0) on the screen.
- **Pre-warmed Flutter's data was ready before its window was:** in all three launches the first
  frame with the header in place already had the list's data, and in C1 the frames before it had
  the content without the header at its final place (inferred: the window's opening animation). Its
  number is bounded by the window appearing, not by Flutter.

### F2. Open (list → detail) and back

**Method.** Series A's navigation driver: one fresh process per arm (`am start -S`), `bench=nav`
(synthetic tap on a row, then the header's Back), 14 iterations with the first 2 dropped, on the
simple and the heavy detail; Keliver also with `bench=sysback` and `x=retainhost` (system back, the
host's retained list), the best shipped back. Flutter on demand and pre-warmed (each pre-warmed run
after one launch that records the setting). Times in ms from the tap, written median / p90 / min–max
of the 12 kept iterations; the measure is **`frameEnd`** (above), with Compose's `drawn` (the frame's
start) in brackets.

**Phone state.** 16:46–16:57, uptime 14 h 35 min – 14 h 45 min, on battery 50% → 48%, unplugged,
25.2 °C, thermal status 0, CPU idle 98% before every run but one (84%, before Keliver 1.28.0's
first run).

**Simple detail, header back:**

| Arm (install) | Open | Back | Open, the process's first (frame end) |
|---|---|---|---|
| native (main) | 28 / 32 / 25–35 (16) | 81 / 89 / 64–105 (13) | 55 |
| native (bigint) | 30 / 32 / 27–39 (16) | 83.5 / 89 / 64–97 (13) | 56 |
| Keliver 1.28.0 | 455.5 / 468 / 440–473 (445.5) | 921.5 / 941 / 904–947 (844.5) | 479 |
| Keliver BigInt | 217.5 / 229 / 198–232 (197.5) | 516 / 524 / 497–528 (442.5) | 217 |
| **Flutter, on demand** | **30.5** / 33 / 19–35 | **33.5** / 36 / 26–40 | 34 |
| **Flutter, pre-warmed** | **31** / 35 / 21–35 | **31.5** / 34 / 24–41 | 28 |

**Heavy detail (about 100 widgets), header back:**

| Arm | Open | Back | Open, the process's first |
|---|---|---|---|
| native | 59 / 68 / 51–76 (21.5) | 83.5 / 102 / 71–110 (14.5) | 101 |
| Keliver 1.28.0 | 1,081 / 1,117 / 1,052–1,119 (1,045) | 936 / 983 / 898–1,009 (852) | 1,103 |
| Keliver BigInt | 548 / 575 / 523–590 (510) | 544.5 / 552 / 521–556 (468.5) | 600 |
| **Flutter, on demand** | **44.5** / 54 / 36–63 | **34** / 36 / 28–37 | 77 |
| **Flutter, pre-warmed** | **43.5** / 51 / 35–53 | **34** / 37 / 27–41 | 78 |

**Keliver with system back and the host's retained list (`x=retainhost`)**: back's first draw is
the press to the window's first draw (the host showing the retained list; Compose's `drawn`, the
frame's start), then the guest's confirming frame (its frame end):

| Arm | Open | Back, the retained list's first draw | Back, the guest's confirming frame (frame end) |
|---|---|---|---|
| Keliver 1.28.0 | 290 / 293 / 285–297 (271.5) | **17** / 20 / 7–23 | 111.5 / 120 / 100–123 |
| Keliver 1.28.0, heavy | 951 / 1,001 / 930–1,024 (913.5) | **16.5** / 23 / 8–23 | 192 / 199 / 164–201 |
| Keliver BigInt | 176 / 194 / 169–197 (157.5) | **17** / 21 / 13–22 | 82 / 103 / 67–107 |
| Keliver BigInt, heavy | 503.5 / 513 / 489–522 (464.5) | **13** / 21 / 8–24 | 99 / 102 / 86–103 |

- **Flutter opens as fast as native, and backs faster.** Open: 30.5 against native's 28 to the
  frame end (heavy: 44.5 against 59). Back: 33.5 against 81: native's back recomposes the list
  inside its draw (13 ms to the frame's start, 81 to its end), Flutter's `ListView.builder` builds
  only the visible rows. The engine policy changes nothing once the engine runs (pre-warmed within
  1–2 ms of on demand).
- **Keliver's open is 15× Flutter's on 1.28.0 and 7× with BigInt** (455.5 and 217.5 against 30.5;
  heavy 24× and 12×), and its header back 27× and 15×: the guest's composition in QuickJS, as
  series A measured (today's 1.28.0 and BigInt rows are within 20 ms of series A's).
- **Keliver's retained back reaches the main thread first**: the retained list's frame starts 17 ms
  after the press on every arm, before Flutter's back frame ends (33.5). That compares a frame's
  start with a frame's end; the pixel check below compares what reaches the screen.
- **The first open in a process** (the column on the right) is close to the warm open for every
  form: Flutter's 34 (heavy 77), native's 55 (heavy 101).

**Open and back on the pixels** (one run per arm, after the cold launches; same phone state as the
F1 pixel check). Series K's method: a fresh process, then 8 rounds of a quick tap on a row (`input
tap`), 2.5 s, then back, 2.5 s, under the trace and the frame recorder: the header's Back for native,
Flutter and Keliver's `bench=nav` arms, `KEYCODE_BACK` for Keliver's `x=retainhost`. Per touch, from
the DOWN (system_server's intercept of the injected event) to the **present** of the first changed
frame, and of the first frame that changes most of the screen (the destination). ms, median and
min–max over the 8 touches of each kind. Flutter's recording held one unrelated motion event 8 s
before the first tap (3 events 12 ms apart, no tap was sent then); it was dropped, which pairs its
touches correctly (the analysis had labelled them shifted by one).

| Arm | Open: first visible change | Open: the detail on screen | Back: first visible change | Back: the list on screen |
|---|---|---|---|---|
| native | the detail | **94.1** (79.6–135.0) | the list (one round: a ripple first) | **168.0** (163.7–199.0) |
| Keliver 1.28.0, header back | 65.1 (55.0–87.8), the ripple | 537.2 (508.6–571.1) | 55.4 (52.7–70.6), the ripple | 1,001.7 (968.6–1,021.8) |
| Keliver 1.28.0, `x=retainhost`, system back | 61.2 (55.0–92.8), the ripple | 371.6 (347.4–409.2) | the list | **91.3** (74.5–106.7) |
| Keliver BigInt, header back | 62.8 (56.9–86.9), the ripple | 290.6 (262.0–320.1) | 58.9 (55.2–148.4), the ripple | 666.0 (630.4–681.4) |
| Keliver BigInt, `x=retainhost`, system back | 66.1 (58.1–74.0), the ripple | 245.0 (224.5–273.8) | the list | **79.0** (68.5–84.4) |
| **Flutter, on demand** | the detail | **77.0** (63.1–144.5) | the list | **99.6** (90.3–116.6) |

- **Back: with the retained back, Keliver's list reaches the screen about when Flutter's does**
  (BigInt 79.0, 1.28.0 91.3, against Flutter's 99.6 and native's 168.0). The medians differ by
  8–20 ms, but 1.28.0's range (74.5–106.7) overlaps Flutter's (90.3–116.6), each arm is one run of
  8 touches, and the paths differ: Keliver's is system back (`KEYCODE_BACK`) showing a retained
  snapshot of the list (the guest's confirming frame lands later, 82–111 ms to its frame end above),
  Flutter's is its header button (Flutter with system back was not measured; its state switch would
  be the same path). Read it as **about the same**. Without the retained back, Keliver's list takes
  0.67 s (BigInt) and 1.0 s (1.28.0). Flutter's back is faster than native's on the screen too (99.6
  against 168.0), as at the frame end.
- **Open: Flutter's detail is on screen at 77 ms; Keliver shows its ripple at 61–66 ms** (faint: it
  fades in from alpha 0, series K) **and its detail at 245–537 ms.** Native's detail came at 94.1.
- **These times are higher than series K's for the same touches** (native's row 94.1 against 56.6,
  its back 168.0 against 117.8, the guest's ripple 55–66 against 45–47): this trace records more
  (the app's `view`, `dalvik` and `input` categories) and main's part ran on the charger, 5 °C warmer.
  The arms ran under the same conditions (BigInt's unplugged and cooler), so compare them with
  each other, not with series K.

### F3. Scrolling the 1,000-item list

**Method.** Series D's two scripted speeds (run 3's commands in [How to run it](#how-to-run-it)):
gentle, 800 px in 300 ms (1,422 dp/s), 10 swipes each way 8 s apart; fast, 1,000 px in 80 ms
(6,667 dp/s), 7 each way 14 s apart; one fresh process per arm and speed, `bench=scroll`. Flings
closed by a touch, busy at DOWN or ending at an edge are dropped, as the summary does. Per fling,
from the DOWN to the list's last move: **missed vsyncs** (the gaps between consecutive moving frames,
in vsync periods, minus one) and each moving frame's time. Compose forms: the host's ScrollTracker
on `FrameMetrics` (the intended vsync to the GPU's completion of the frame). Flutter: the module's
probe on `FrameTiming` (the vsync to the raster thread's end, `totalSpan`), the missed vsyncs
counted the same way from its frames' vsync timestamps, and logged through the host in the host's
fling format, so the same summary runs.

**The frame times are not like for like; the missed vsyncs are.** `FrameMetrics`' total includes
the GPU executing the frame (native's gentle flings: 12.3 ms of GPU in a 20.5 ms median frame);
`FrameTiming`'s raster end is when Flutter's raster thread has submitted, before the GPU has run it.
So Flutter's frame times below are lower by construction, and the column to compare is missed
vsyncs (a frame that was not produced in time, whatever the measure). Flutter's fling physics also
differ (its `ClampingScrollPhysics` against Android's `OverScroller`): the same swipe travels about
the same distance but the list stops sooner (gentle 605 against 743 ms after the UP, fast 1,870
against 2,245).

**Phone state.** 16:57–17:25, uptime 14 h 46 min – 15 h 13 min, thermal status 0, CPU idle 85–99%.
Main's arms on battery, 48% → 45%, unplugged, 25.2 °C; **the BigInt arm on the charger** (the owner
plugged the phone in at about 17:17), 47% → 53%, 28.8–30.5 °C.

| Speed, direction | Arm | Flings kept | Missed vsyncs per fling: median (min–max), total | Moving frames | Frame p50 / p90 / max (ms) | Over 25 ms |
|---|---|---|---|---|---|---|
| gentle, down | native | 10 | 0.5 (0–3), 10 | 610 | 17.3 / 21 / 26.4 | 1 (0.2%) |
| | Keliver 1.28.0 | 10 | 1 (0–2), 9 | 612 | 13.8 / 20.4 / 33.7 | 2 (0.3%) |
| | Keliver BigInt | 10 | 1 (0–2), 10 | 610 | 20.2 / 20.7 / 26.1 | 2 (0.3%) |
| | **Flutter** | 10 | **0 (0–0), 0** | 544 | 9.9 / 12.6 / 18.5 † | 0 |
| gentle, up | native | 9 | 1 (0–1), 5 | 549 | 20 / 21 / 23.5 | 0 |
| | Keliver 1.28.0 | 9 § | 1 (0–2), 10 | 546 | 20 / 20.6 / 29.9 | 1 (0.2%) |
| | Keliver BigInt | 10 | 1 (0–2), 11 | 611 | 20.1 / 20.8 / 30 | 3 (0.5%) |
| | **Flutter** | 10 | **0 (0–0), 0** | 546 | 9.7 / 12.4 / 18.5 † | 0 |
| fast, down | native | 7 | 3 (0–5), 18 | 967 | 20.6 / 25.4 / 58 | 102 (10.5%) |
| | Keliver 1.28.0 | 6 | 3.5 (0–6), 22 | 691 | 20.8 / 21.6 / 65.4 | 42 (6.1%) |
| | Keliver BigInt | 7 | **10 (7–16), 71** | 916 | 20.9 / 37.5 / 162.5 | 270 (29.5%) |
| | **Flutter** | 7 | **0 (0–1), 2** | 820 | 12 / 14.8 / 26.4 † | 1 (0.1%) |
| fast, up | native | 7 | 0 (0–2), 3 | 977 | 20.6 / 37.2 / 52.4 | 144 (14.7%) |
| | Keliver 1.28.0 | 6 | 3.5 (2–5), 21 | 822 | 20.9 / 21.8 / 61.3 | 60 (7.3%) |
| | Keliver BigInt | 6 | 7 (5–9), 41 | 808 | 20.9 / 37.5 / 119.5 | 191 (23.6%) |
| | **Flutter** | 6 | **0 (0–0), 0** | 703 | 12.1 / 14.5 / 20.5 † | 0 |

† Flutter's frame times end at the raster thread's submit, without the GPU (above); not comparable
with the Compose rows' times.

§ **Corrected after the review.** This row first read 10 flings, 1 (0–6), 16 missed, 548 frames.
The first analysis script put each fling in a speed by its release velocity and did not drop flings
that began while the guest was busy; the fast run's fling 8 (2 frames, 6 missed vsyncs, travel 0, released
at 107 dp/s, busy at DOWN) landed here. The script now takes the speed from the run, the direction
from the fling's number, and drops what the host's own summary drops (closed by a touch, at an edge,
busy at DOWN). Re-run on the raw results, **every row's flings, median, total, moving frames and
frame p50 / p90 / max now match the host's own summary lines** (to rounding; the min–max and the
over-25 counts exist only in the per-fling lines); this row is the only one that changed (9 flings,
fling 20 dropped at the edge).

- **Missed vsyncs over all kept flings (31–33 per arm): Flutter 2 in 33, native 36 in 33, Keliver
  62 in 31 (1.28.0) and 133 in 33 (BigInt).** Flutter scrolled this list without a missed vsync at the gentle speed and with
  2 at the fast one; its two threads stayed well inside a frame (UI-thread build median 1.5 ms gentle,
  4.2 fast, max 16.9; raster thread 6.8 and 7.1, max 14.7).
- **Keliver matches native at the gentle speed** (1 missed per fling against native's 0.5–1), as in
  series D, and falls behind it on fast flings.
- **The BigInt guest missed more vsyncs on fast flings than 1.28.0** (10 and 7 per fling against
  3.5 and 3.5), the opposite of every other series, where BigInt is faster. **This may be the power
  state, not the engine**: the BigInt arm was the only F3 arm on the charger, 4–5 °C warmer (thermal
  status 0 throughout), and the two fast arms were not run back to back in the same state. Until
  they are, this row is not evidence either way. One possible cause if it holds (**inferred, not
  measured**): its guest finished its work for each fling within the fling (2.0–2.2 s of guest work,
  the list stopping 2.24 s after the UP), while 1.28.0's guest was still composing rows 4.0–4.4 s
  after the DOWN, about 2 s after the list had stopped; so the faster guest would send more of its
  row batches while the list moves, and the host would apply them on the main thread during the
  fling, where the slower one leaves rows blank and applies them after it. The placeholder pass
  (`x=scroll,blank`) that would show the blank rows was not run.
- What a user would see is not measured here: the Keliver rows that are still blank while the list
  moves (series D's "blank rows") are not in these frame counts.

### F4. Size

**APK sizes** (bytes, the benchmark APKs; all ABIs, native libraries stored uncompressed and
page-aligned as AGP packages them; `base` is the same sample without Flutter, also stripped):

| APK | Bytes | − base |
|---|---|---|
| base (no Flutter) | 19,900,619 | — |
| base + a hello-world Flutter module (`flutter create -t module`, unchanged but for `cupertino_icons`) | 63,096,131 | **+43,195,512** |
| base + the bench's Flutter module | 64,029,827 | **+44,129,208** |
| the BigInt build (+ the bench's Flutter module) | 64,730,079 | +44,829,460 |

**What Flutter adds, per ABI** (bytes; uncompressed as installed, and `gzip -9` as a stand-in for
a store's compressed download, which was not measured):

| Part | arm64-v8a | armeabi-v7a | x86_64 |
|---|---|---|---|
| `libflutter.so` (the engine) | 11,346,352 (gzip 5,390,286) | 8,458,404 (4,735,869) | 12,467,088 (5,572,931) |
| `libapp.so`, the bench module (AOT Dart: the framework, Material and the screens) | 3,605,464 (1,416,920) | 3,981,896 (1,584,336) | 3,671,000 (1,437,922) |
| `libapp.so`, hello world | 3,277,784 (1,293,442) | 3,637,832 | 3,408,856 |
| `flutter_assets` (shaders, fonts manifest, licences), all ABIs | 164,464 (114,772 deflated in the APK) | | |
| dex (Flutter's embedding, plus the recipe's own glue), all ABIs | +997,004 (+403,291 deflated) | | |

- **On an arm64 phone from a store that splits by ABI** (a Play bundle), Flutter adds about
  **16.1 MB installed** (engine 11.35 + app 3.61 + assets 0.16 + dex 1.0) and about **7.3 MB to
  download** (gzip estimate). **The floor**, a hello-world module, is 15.8 MB installed and about
  7.2 MB to download: the bench's three screens add 0.33 MB of AOT code over it. In a universal APK
  (all three Flutter ABIs, as here) the delta is 44.1 MB.
- **Keliver's own native and code parts, the same way**: `libquickjs.so` 833,272 bytes on arm64
  (gzip 358,741; the BigInt fork's 996,184, gzip 435,681), 533,144 on armeabi-v7a, 801,740 on x86,
  861,952 on x86_64; the three-way guest's bundle (54 modules, in the APK's assets: what an update
  replaces) 2,106,972 bytes, 906,180 deflated (BigInt: 2,218,711, 946,134). **Installed, the
  bundle exists twice**: deflated in the APK, and uncompressed in Zipline's cache after the first
  launch (F1's log: each install's first launch loaded 54 modules from the assets, every later one
  0). So on arm64 Keliver's engine and bundle take about **3.9 MB installed** (0.83 + 0.91 in the
  APK + 2.11 in the cache; BigInt about 4.2: 1.00 + 0.95 + 2.22).
- **The dex is not counted the same way for both, and could not be here.** Flutter's 1.0 MB of dex
  is the difference between APKs with and without the module. Keliver's host libraries' share of
  the dex (Treehouse, the Zipline JVM side, the protocol, the widget systems; Compose's own runtime,
  which a Compose app has anyway, is not Keliver's) needs an APK without Keliver, which this session
  did not build, and the APKs were not kept for a per-package dex count. Both are unminified (no R8,
  above), so R8 would shrink both. **So: Flutter adds about 16 MB installed, Keliver about 3–4 MB
  plus a host dex share not measured**; the direction is safe (Flutter's floor alone is 15.8 MB),
  the size of the margin is not a precise 12–13 MB.

### F5. Memory after the list is shown

**Method.** In F1's rounds, after each install's measured rounds: per arm one unmeasured launch, then
a cold launch and `dumpsys meminfo dev.keliver.sample` 6 s after its cold line, then a second
`dumpsys` right after it. 2 samples per arm (one per install), both shown; MB (1,000 KB as dumpsys
reports KB).

| Arm | TOTAL PSS, first reading | TOTAL PSS, second reading | Native heap | Java heap (first / second) | Graphics | Code | Private other |
|---|---|---|---|---|---|---|---|
| native (main; bigint) | 62.7, 62.5; 61.5, 61.5 | 56.1, 55.8; 55.2, 55.2 | 7.9, 7.7; 6.7, 6.7 | 16.3 / 9.9 | 12.8 | 18.7 | 2.4 |
| Keliver 1.28.0 | 87.3, 87.2 | 72.7, 72.5 | 18.4, 18.2 | 25.4 / 10.7 | 12.8 | 23.0 | 2.9 |
| Keliver BigInt | 87.7, 88.0 | 72.9, 73.3 | 18.4, 18.7 | 25.6 / 10.7 | 12.8 | 23.1 | 2.9 |
| Keliver BigInt, `prestart,prefetch` | 87.8, 88.2 | 73.0, 73.4 | 18.5, 18.9 | 25.7 / 10.7 | 12.8 | 23.1 | 2.9 |
| Flutter, on demand | 89.3, 89.9 | 83.5, 84.0 | 13.6, 13.5 | 15.0 / 9.1 | 26.1, 26.8 | 19.1 | 11.0 |
| Flutter, pre-warmed | 91.5, 90.7 | 85.7, 84.9 | 17.7, 17.6 | 15.1 / 9.1 | 24.2, 23.4 | 19.1 | 11.0 |

- **The second reading is lower in every arm** (the Java heap falls by 6–15 MB between two dumps 0.3 s
  apart; the native and graphics lines do not move), most likely garbage collected between them.
  The first reading is what was resident 6 s after the data, the second what is left after a Java
  collection. **That collection is not symmetric**: ART's collector frees the Java heap (Keliver's
  host objects for the guest fell most, 25.4 → 10.7 MB) but not Dart's heap, which is in Flutter's
  "Private other", nor QuickJS's, which is in Keliver's native heap. Which reading a phone is at
  depends on when ART collects, so both are given, and neither is "the" figure.
- **Over native, at the first reading Keliver adds about 25 MB and Flutter about 27; at the second,
  Keliver about 17 and Flutter about 28.** So Keliver is 1–3 MB below Flutter on demand at the first
  reading (about 2 at the medians) and about 11 MB below at the second, with 2 samples per arm. Where it goes differs: Keliver's is QuickJS's
  heap (native heap +11 MB) and the host's Java objects for the guest; Flutter's is graphics
  (+11 to 14 MB: Impeller's GL buffers and its own surface, `GL mtrack` 19.4 MB against native's 8.6)
  and private other (+8.6 MB: the Dart VM's heap and code pages). The engine does not change
  Keliver's memory (1.28.0 and BigInt within 0.5 MB).

### Verdicts: is Keliver faster than Flutter?

- **Cold start: no.** Flutter starts as fast as native on this phone (on demand 95 ms ahead of native
  at the frame end, about 30 on the screen; pre-warmed about 105 ahead on the screen). That lead is
  where the bench sends the request (Flutter's `initState` 100 ms before native's first
  composition), not Flutter's rendering, and native ran without R8. Keliver is 0.9 s behind Flutter
  on demand with the unreleased BigInt fork and both NF4 steps, 1.9 s as shipped (measured at the
  frame end; on the screen inferred from each arm's measured gap).
- **Open: no.** Flutter opens a screen in 30.5 ms to the frame end (77 on the screen), like native;
  Keliver takes 7× (BigInt) to 15× (1.28.0) as long, 12× to 24× on the heavy screen. Keliver's ripple
  is on screen before Flutter's detail, but the detail is 3–7× later.
- **Back: about the same as Flutter, only with the retained back.** With `x=retainhost` the list is
  on screen 79 ms (BigInt) or 91 ms (1.28.0) after the press, against Flutter's 99.6 and native's
  168: within about 20 ms, with overlapping ranges, one run of 8 touches per arm, and system back
  against Flutter's header button. Without it, no: with the header back (the guest recomposing the
  list) Keliver's list is on screen after 0.67 s (BigInt) and 1.0 s (1.28.0).
- **Scrolling: no.** Flutter missed 2 vsyncs in 33 flings, native 36 in 33, Keliver 62 in 31
  (1.28.0) and 133 in 33 (BigInt, whose fast flings ran on the charger and may be confounded); at
  the gentle speed Keliver is close to native (1 per fling) and Flutter misses none. Native and
  Keliver ran without R8, Flutter's Dart AOT-optimised.
- **Size: yes, Keliver adds far less.** Flutter adds about 16 MB installed on an arm64 phone (7 MB
  to download, estimated), and its floor is 15.8 MB; Keliver's engine and this bundle take about
  3–4 MB installed (counting the bundle's cache copy), plus a host dex share not measured here.
- **Memory: somewhat less than Flutter, depending on when it is read.** PSS after the list, n = 2
  per arm: at 6 s Keliver 87.2–88.2 MB, Flutter on demand 89.3–89.9 (1–3 MB more; pre-warmed up to 91.5), native 61.5–62.7; after a
  Java collection Keliver 72.5–73.4, Flutter 83.5–85.7 (about 11 more), native 55.2–56.1. The
  collection frees Keliver's host Java objects, not Dart's heap.
- **Measured vs inferred.** Measured: every table above. Inferred: the screen-time cold starts in the
  answer table (F1's untraced frame end plus each arm's gap from the traced pixel launches), why
  Flutter's first data frame takes about 100 ms to reach the screen, why the BigInt guest misses more
  vsyncs on fast flings (and whether it does, given the charger), that a Shorebird-patched screen
  on Android runs as fast as release AOT, and the download sizes (gzip, not a store's delivery).

**What an iPhone run with Shorebird would add (not run).** On iOS a Shorebird patch runs changed
Dart functions in its interpreter while unchanged ones stay AOT, so a patched Flutter screen on
iOS is slower than the shipped one by however much of it changed. The fair iOS comparison is
Keliver's guest (QuickJS, interpreted) against a Flutter screen whose code was patched, plus the
unpatched AOT screen as Flutter's best case; it needs a Shorebird account and release, and the
iPhone signing that section 10 of PLAN_2026-11.md waits on. On Android, as here, a patched Flutter
screen runs AOT, so this section's Flutter numbers stand for Flutter over the air on Android.

**Unverified, and caveats.**
- One phone, one afternoon (uptime 14–15.5 h), one run of each series; the F3 BigInt arm and main's
  pixel checks ran on the charger, warmer (thermal status 0 throughout).
- Flutter's scrolling frame times end at its raster thread's submit, Compose's at the GPU's
  completion; only missed vsyncs are compared across them.
- The Keliver host libraries' share of the APK's dex was not separated (F4); Play's real download
  sizes were not measured (gzip stands in).
- Pre-warm's cost to launches that never open a Flutter screen is measured only as `onCreate`'s
  delay and native heap (F1, F5), not as a start without the Flutter form.
- Flutter ran on Impeller's OpenGL ES backend (this phone's choice); Vulkan devices may differ.
- The recipe's sample wiring is not built by CI; it applies to main `2073a4ace`.
- **No arm went through R8**; Flutter's Dart code was AOT-optimised by its own compiler. This
  disfavours native and Keliver's host most on scrolling and native's back (see Why it is fair).
- The BigInt arm is an unreleased fork; Shorebird itself was not run; Flutter screens cannot reuse
  the app's Compose components (see What this does not cover, under the answer table).

**Follow-ups** (not in this PR):
- Re-run F2 and F3 for native and Keliver on the minified `release` type (R8), before the scroll
  and native comparisons are read as final.
- Re-run the two fast-fling guest arms (1.28.0 and BigInt) back to back in the same power state, to
  settle whether BigInt's fast-fling result is the engine or the charger.
- An APK without Keliver, to count the host's dex share as Flutter's was counted.
- **A possible double code load with `prestart`.** In the pixel run, the unmeasured `P-bi` launches
  (default settings, after a `prestart,prefetch` launch, so the provider prestarted from the
  previous launch's setting) loaded 107–108 modules where one load is 54: the prestarted Zipline
  app looks not to have been adopted, and the activity loaded the code again. These launches are in
  no table here; it needs its own look at how the activity adopts a prestarted app when the launch's
  own settings differ.
- The iPhone run with Shorebird (below the verdicts).

### Learnings for later

Facts only; each comes from this section's numbers.
- **On cold start and open, the gap is the guest interpreter, not rendering.** Keliver renders
  through the host's Compose, and from its frame end to the screen it takes about what native does (36–59 ms against native's 40.7;
  F1 on the pixels). What it adds is guest work in QuickJS: on cold start the code load (about
  0.5 s) and the list's composition (1,019 ms from parsed to composed on 1.28.0, 456.5 on BigInt
  with both NF4 steps, against native's 23 and Flutter's 13); on open 455.5 / 217.5 ms against
  native's 28. Flutter's AOT code is native speed on this phone: open 30.5 against native's 28, heavy
  open 44.5 against 59.
- **Scrolling is different.** Native Compose also missed 36 vsyncs in 33 flings against Flutter's 2
  (no arm used R8), so most of that gap is the host's rendering path; the guest adds about 26 over
  native (62 in 31), mostly on fast flings.
- **Where Keliver wins, and by how much after the review's corrections.** Back: about the same as
  Flutter (within about 20 ms, ranges overlapping), and only with the host's retained back; without
  it 0.67–1.0 s. Size: Flutter adds about 16 MB installed on arm64 (floor 15.8), Keliver about
  3–4 MB plus a host dex share not measured; a large margin, but not a precise 12–13 MB. Memory: 1–3
  MB less than Flutter at the first reading, about 11 MB less after a Java collection, n = 2.
- **Where the first request is sent decides much of a cold start.** Flutter's 95 ms lead over native
  is the 100 ms by which its `initState` sends the request before native's first composition does.
  On Keliver, NF4's `prestart,prefetch` moved the request to 298 ms (prefetched) and took the BigInt
  arm from 1,800.5 to 1,543.5 ms (F1), with prestart's earlier code load: both steps together, not
  split here.
- **One option recorded, not decided: "compiled until patched".** Screens would be compiled into the
  store build (native speed, as Flutter's AOT here), and the interpreter would run only screens
  changed over the air since that release: Shorebird's iOS model applied to Kotlin. The owner's
  stated view: probably not the direction, but not a bad option if implemented correctly. It would
  have to be squared with PLAN_2026-11.md's **PD8** (one over-the-air lane: no "native by default,
  the guest between releases") and **PD3** (the Shorebird-style interpreter parked), and with the
  plan's aim (PD10, WS7) that the one lane itself feels native: a patched screen would fall back to
  today's interpreted speed.

### Review fixes, 2026-11-22

An independent review of this section ("merge after fixes") checked every figure against the raw
files. What changed:
- **The F3 cell for Keliver 1.28.0, gentle up, was wrong** (H1): the analysis script's grouping put
  a fast-run fling there (F3, note §). Corrected to 9 flings, 10 missed, 546 frames; Keliver 1.28.0
  missed 62 vsyncs in 31 flings, not 68 in 32, and every total, verdict and the answer table follow.
  No other F3 cell changed.
- **R8** (H2): "same build shape" was wrong. No arm was minified; Flutter's Dart was AOT-optimised.
  Stated in Why it is fair, the caveats and the verdicts.
- **Back** (M1): "Yes" became "about the same, only with the retained back".
- **Memory** (M2): both readings are given with n = 2, and the asymmetry of the Java collection;
  the "both add 25–30 MB" contradiction is gone.
- **Size** (M3): Keliver's bundle counted twice when installed (APK and cache), about 3.9 MB; the
  dex is not counted the same way for both and why; "smaller by about 13 MB" dropped.
- **Owner context** (M4): What this does not cover (Flutter draws its own widgets; Shorebird not
  run, the Android claim reasoned; the BigInt fork unreleased, `prestart,prefetch` not the default).
- **BigInt fast flings** (M5): the charger confound comes first; the cause is a hypothesis.
- **Lows**: ART profile state per arm (L1); request placement in the cold-start verdict (L2); the
  pre-warmed 558 footnoted (L3); native within 40 ms of the earlier series, and other changes on
  main not excluded from BigInt's gain (L4); which recipe built which APK (L5). The possible prestart
  double load (L6) is a follow-up above.

## NF5 R0: the floor step under Perfetto (A12, 2026-11-26)

NF5's R0 ([`SHOW_BEFORE_COMPUTE.md`, appendix D](SHOW_BEFORE_COMPUTE.md#appendix-d-the-floor-step-under-perfetto-r0-a12)),
a day after its planned date. It reran series H's three arms and one new arm, `simple-on-idle`, each
in a fresh process under a Perfetto trace. It then split the floor step (click → composed, for a
detail already composed with the right item) into its parts, and applied the rule fixed in advance
to the new arm. The session followed its command list (steps 0–7) as written, except the power
state: it ran on the charger, where step 0 says on battery (caveat 3, below). The queries are
[`docs/nf5-r0/`](nf5-r0/), unchanged. Three diagnostic queries written during the session are in
[`docs/nf5-r0/a12-2026-11-26/`](nf5-r0/a12-2026-11-26/); `analyse.sh` does not run them.

**Verdict: reachable in principle**, with a small margin and the three caveats below it. The rule
was applied to `simple-on-idle`'s opens 3–14; all 12 were idle at the UP.
- G = 22.4 ms, under the limit of 25.
- W = 8.1 ms, the median of the per-open values, and every part of it has a named mechanism.
- P = 65.0 ms on q2's printed `total` (59.1). That `total` is too short in 9 of the 12 opens
  (below). Measured to the frame that actually drew the detail, `total` is 82.0 and **P = 87.9 ms**,
  under 100.
- At a ratio of 30 (+36 instead of +14), P is 87.0 printed and 109.9 corrected.
- The margins are 2.6 ms on G and 12.1 ms on P. The pre-run prediction was G ≈ 21.

**The caveats beside the verdict** (added after the review of the record; no measured number
changed):
1. **Which "frame end".** Appendix D wrote T as UP → "presented". But the 100 ms target, and
   native's 28 ms that it is set against, are **frame end**: the end of the main thread's work for
   the frame that shows the content (the bench's `frameEnd`, "Method" above; SHOW_BEFORE_COMPUTE
   section 1, "All are frame end"). Native's 28 ms is not measured to the display, so the
   like-for-like T is a frame end too. The idle arm's trace gives three readings of T (medians over
   opens 3–14, ms):

   | T runs to | T | P (ratio 12) | Verdict |
   |---|---|---|---|
   | the main thread's frame end, as the targets and native's 28 ms (per open, `total − j`: the end of the `doFrame` after the apply) | 71.2 | about 77.1 | reachable |
   | **the frame's buffer queued** (`frame-end.sql`, below): **the one recorded** | **82.0** | **87.9** | reachable |
   | the display, "presented" (appendix D's pre-registered word): SurfaceFlinger's display frame ends about 45 ms after the buffer, in the one open checked | about 127 | about 133 | **not reachable** |

   87.9 is recorded because it is the most conservative of the frame-end readings: the RenderThread
   queues the buffer after the main thread's work ends. On the "presented" reading the verdict is
   the opposite. The target was not set on that reading, so the owner confirms which reading R0 uses.
2. **The margin is less than one frame** (inferred from the per-open columns and the 60 Hz
   vsync, not measured). P's 12.1 ms margin is smaller than one frame (16.7 ms), and the app draws
   only at vsyncs. In all 12 opens the apply came 1.1–3.5 ms (`h`) before the `doFrame` that drew
   it, and `h` + W stays under 16.7 ms (at most 13.8), so removing W moves no open's frame. The
   rebind's 14 ms is more than `h` + W in every open, so every open would slip one vsync. Quantized
   to vsyncs, P ≈ 82.0 + 16.7 ≈ 98.7, a margin of about 1.3 ms; at a ratio of 30, about 115. For
   the same reason **the frame-clock change named for c1 (below) would not, alone, bring the simple
   open's frame forward on the A12**: the guest's chain from the frame tick to the apply (c2 + d + e
   + f + g, about 31 ms) is just under two frames, and starting it W earlier moves the apply earlier
   within the same vsync period, so the same `doFrame` draws it. This matters for R1 and for (B): on
   this phone a change shortens the open only if it moves the apply across a vsync.
3. **The charger.** Appendix D's step 0 says on battery, as series H. The run was on the charger
   (below), so it deviates there. On series H's three arms the guest's step (`composed − click`)
   on the charger is within −4% to +8% of series H's on battery (step 5's table). If the charger
   made the guest up to 4% faster, G on battery would be about 23.4, still under 25. That is
   inferred from series H's arms: the idle arm was not run on battery.

**Build.** The dry run's APKs, kept in the session's scratch directory, matched their sha256 at
04:00 and again before the install:
- `host-android-benchmark.apk`: 26,057,387 bytes, `8bd7ecef…c7ee76243`;
- the trace processor: `ae2f18ff…68a0e7d`, Perfetto v45.

The APK was built from `316abd41a`'s code: the three-way sample as on main, with `Long` as BigInt,
on the fork's engine built locally as `1.28.0-q20260604` through `use-fork.init.gradle`. Main itself
now resolves the published `dev.keliver.zipline:*:1.28.0-keliver.1` (#294, merged after that
commit). The two engines were not compared in this session. Install: `install -r -d`, then
profileinstaller's broadcast (result 1), then `speed-profile`.

**Phone state.** The Galaxy A12, Android 12 (API 31), 04:41–04:46 IST. Up 4 d 2 h 29 min – 2 h 33 min.
**Plugged in and charging (AC), 50% → 52%, unlike series H (on battery, 87%)**: the owner was asleep
and the phone could not be unplugged. The session waited from 25% (04:00) until it read 50% (04:41)
before the first arm. Temperature 34.6 °C (series H: 25.6–25.7), thermal status 0, CPU idle 96–97%
(`top`, before each arm), load average 20.4–20.9.

**The runs (step 5).** Every arm had 14 opens, 14 backs, `done` and no timeout; the traces were
14.0–20.0 MB. Against series H, per-iteration medians over opens 3–14:

| Arm | `click` | `composed − click` |
|---|---|---|
| simple-on | 191 (H: 198.5–201) | 55.5 (H: 57.5–58) |
| simple-off | 112 (H: 113) | 113 (H: 112–116.5) |
| heavy-on | 496 (H: 488.5–494) | 120 (H: 111–114.5) |

All are within 15%; heavy-on's `composed − click` is the farthest, at +5% to +8%. The idle arm:
`click` 412 (the 400 ms hold + 12), click → composed 42, click → drawn 56.

**The checks (step 6).**
- **q0a**: after the start group come 28 groups alternating open and back, with `is_open` on every
  open. The event, frame and `composed` counts are those appendix D expects: press on 3, 2, 2; press
  off 1, 1, 1; a back 1, 1, 1. No override was needed.
- **q0b**: the UP is `ThreewayActivity$$ExternalSyntheticLambda5` in every arm (`upname.txt`). It
  comes 3–10 ms before E on the idle and press-off arms. On the press-on arms the click waits behind
  the preparation.
- **qc**: passes on every arm, 12 of 12 opens. On the idle arm 12 of 12 opens were idle at the UP,
  207–224 ms after the guest's last call.
- **The CPUs**: the cpufreq rows show two maxima, 2.301 GHz on cpu0–3 (`fast`) and 1.8 GHz on
  cpu4–7, so the clusters were separated. `d_running_fast` = `d_running` on every arm: the guest
  composed only on the fast cluster.
- **q3**: empty on every arm, because `d_other` was 0 in every open: the Zipline thread never
  blocked while composing. Perfetto accepted `symbolize_ksyms`, but whether this user build gives
  it kernel symbols was not exercised.

**The table** (q2: medians over opens 3–14, ms):

| Column | simple-on-idle | simple-on | simple-off | heavy-on |
|---|---|---|---|---|
| idle at the UP | 210.9 | −84.1 | 814.2 | −387.9 |
| a UP → E0 | 4.9 | 84.5 | 4.8 | 388.3 |
| b the event(s) | 7.7 | 5.1 | 5.2 | 5.9 |
| c E → F | 15.1 | 28.2 | 16.9 | 74.3 |
| c1 E → frame callback | 14.2 | 27.3 | 15.4 | 73.8 |
| c2 callback → F | 0.8 | 1.0 | 1.2 | 0.8 |
| c_zl_run | 6.6 | 7.6 | 4.7 | 8.4 |
| d F → C | 22.4 | 24.8 | 95.8 | 43.6 |
| **d_running (G)**; of it fast, slow | **22.4**; 22.4, 0 | 24.8; 24.8, 0 | 95.8; 95.8, 0 | 43.6; 43.6, 0 |
| d_runnable | 0.0 | 0.0 | 0.0 | 0.0 |
| d_other | 0.0 | 0.0 | 0.0 | 0.0 |
| **W** | **8.1** | 21.1 | 10.3 | 66.2 |
| e C → sent | 5.9 | 6.8 | 14.9 | 5.6 |
| f, g sent → apply; apply | 1.0, 1.3 | 0.7, 0.9 | 0.8, 5.0 | 0.7, 1.0 |
| h apply → doFrame | 1.7 | 5.1 | 1.4 | 13.4 |
| i the frame (layout) | 8.7 (1.2) | 6.7 (1.1) | 28.2 (15.3) | 9.4 (1.1) |
| j frame → the row's end | −10.5 | 6.2 | −5.4 (n = 8) | 9.5 |
| **total** UP → the row's end (q2) | **59.1** | 169.6 | 164.2 (n = 8) | 546.7 |
| total to the frame that drew the detail (`frame-end.sql`) | **82.0** | 172.1 | not derived | 545.9 |
| P_allW_r12, P_allW_r30 (q2) | 65.0, 87.0 | 162.7, 184.7 | 166.8, 188.8 (n = 8) | 498.6, 520.6 |

**What `total` measures, and where q2's is wrong.** The traces show two things that the emulator's
dry run did not.
1. **The row's end is the frame's buffer queued, not the display's present.** The end of the app's
   actual frame-timeline row comes 0.6–1.8 ms after the RenderThread's `queueBuffer` for that frame
   (idle arm, opens 4 and 8). The SurfaceFlinger display frame the row points to
   (`display_frame_token`) ends later. That was checked on one valid row only, open 4's, while the
   press's ripple was animating: relative to the apply, the row ends at 19.7 ms, and the display
   frame runs from 32.6 to 65.3 ms, about 45 ms after the row. Other opens, and frames without the
   ripple, were not checked. So appendix D's `j` and `total` run to the buffer queued: later than
   the targets' frame end (the main thread's, caveat 1 above), and earlier than light on the screen.
   Series I reads "present" from the same row (its query takes the end of the app's actual
   frame-timeline row), so its "presented" is probably this buffer-queued end too; that was not
   re-checked. Series K's headline numbers, and the C11 reference's K below, do not use that row:
   `q-px.sql` takes the end of SurfaceFlinger's display frame that spans the recorder's buffer. Only
   its `app_first_present` column reads the app's row.
2. **In 9 of the idle arm's 12 opens, `measure.sql` matched a frame that did not draw the detail.**
   It takes the first `doFrame` after the apply, and that vsync's row. Inferred from the trace:
   - **What runs at the UP.** After a 400 ms hold, the press's ripple is still animating on the
     RenderThread: there are `RenderNodeAnimator` and `RippleHostView` messages, and the
     RenderThread queues a buffer at every vsync with no frame on the main thread. In these opens
     the detail's frame collides with that animation.
   - **The matched row is zero-length.** Its end equals its start, so `j` is negative.
   - **The detail is drawn elsewhere.** The RenderThread's draw of the new content (`DrawFrames`,
     6.5–16.5 ms long) ran one vsync later in 3 opens. In 3 more it waited 22 ms behind the
     animation's `queueBuffer` in the same vsync. In the other 3 it ran at the normal time, but its
     row was still zero-length.

   [`frame-end.sql`](nf5-r0/a12-2026-11-26/frame-end.sql) measures to the frame that drew instead:
   - **Which frame.** It takes the first `DrawFrames` of at least 2 ms from the matched `doFrame`
     on, and the end of the `queueBuffer` inside it.
   - **The idle arm.** Per open, UP → that end is 65.5–98.6 ms, median **82.0**, against q2's 59.1.
     The 3 opens with a usable row agree with q2 within 2.6 ms.
   - **The other arms.** In 5 of simple-on's 12 opens the next vsync drew too, adding 12–15 ms to
     each (median 172.1 against 169.6); in 1 of heavy-on's, 17.5 ms. Simple-off has no row at all
     for 4 opens, and the supplement does not work there: in 4 of its opens 3–14 (5, 7, 8 and 10)
     it read a `DrawFrames` 754–758 ms after the frame, after the next tap. Its frame-end columns (h to total) are not reliable; its guest columns
     (a to d, W) are. *Corrected 2026-11-26 (NF5 R1's review, checked on the kept trace):* the cause is
     the rule, not a missing draw. In those 4 opens the `DrawFrames` after the frame took 0.4–0.7 ms
     and queued nothing, and every buffer of the next 60 ms was queued by the RenderThread's own
     animation frames (`dispatchFrameCallbacks`, 5.5–8.4 ms, no `DrawFrames`), which the rule never
     reads. NF5 R1's `q_drew` reads them ([`nf5-r1/measure.sql`](nf5-r1/measure.sql)).

**The rule, applied to `simple-on-idle`.**
- **Count.** 12 of 12 opens were idle at the UP (206.9–223.9 ms after the last guest call), which
  is at least 10, so the rule applies.
- **G** = `d_running` = **22.4 ms**, all of it on the 2.301 GHz cluster.
- **W** = **8.1 ms**. Every part of it has a named mechanism:
  - **c1** (median 14.2, E's end → the main thread's frame callback). The guest's frame tick is a
    Choreographer frame callback (`AndroidChoreographerFrameClock`), so after the click the guest
    waits for the next vsync on the main thread. Meanwhile the Zipline thread runs the click's own
    coroutine for 6.6 ms (`c_zl_run`, not counted in W). The change that would remove it is in the
    runtime: a frame clock that runs the guest's next frame right after an input event instead of at
    the next vsync.
  - **c2** (0.8): the hop from that callback to the Zipline thread. It goes with c1.
  - **d_runnable and d_other** are 0.0: the guest was neither preempted nor blocked while it
    composed. There is nothing to name.
  - So **W_named = 8.1**.
- **P = T − W_named + 14**, at a ratio of 30 (+36) in brackets:

  | T | P | P_allW_r12, per open |
  |---|---|---|
  | q2's, 59.1 | **65.0** (87.0) | 65.0 |
  | the frame that drew the detail (its buffer queued), 82.0 | **87.9** (109.9) | 87.4 |

- **Reachable in principle** (P ≤ 100 and G ≤ 25): **yes, on both T's.** Not reachable
  (`P_allW_r12` > 100, or G ≥ 40): no.
- **With the caveats above:** reachable on both frame-end readings of T (P about 77.1 and 87.9),
  with a margin under one frame once quantized to vsyncs (about 98.7); not reachable on the
  display's "presented" reading (about 133).

**Also reported** (appendix D):
- **The press's preparation costs the click.** On simple-on the click waits 84.5 ms in `a` behind
  the preparation. c1 doubles, to 27.3 against the idle arm's 14.2, and W is 21.1 against 8.1. The
  prediction was W of about 30–36 on the press-on arm and less on the idle arm: the direction was
  right and the size too high.
- **Press off against press on.** W is 10.3 against 21.1. Simple-off's G of 95.8 is the detail
  composed fresh at the click; with the press it is 24.8, the prepared detail recomposing.
- **The heavy arm, for B1's slice budget.** G = 43.6 ms. W = 66.2, nearly all of it c1 (73.8): the
  frame callback comes 74 ms after the click's event ends. Inferred: the main thread is busy with the
  press's prepared heavy detail. Its 8–11 ms `sendChanges` apply message comes just before E (q0b),
  but the window was not split further, and the mechanism is not named.
- **Outside W, on the idle arm.** In 9 of 12 opens the ripple collision (above) puts the detail's
  buffer 15–40 ms after q2's `total`. It is a host mechanism, and the rule does not subtract it. It
  matters to (B): the reveal's shell would be drawn while the press's ripple is running (R1).

**Kept** in the session's scratch directory (`keliver-scratch/nf5/r0/`; not in the repository): the
four traces, the phone's directory (the output of every `analyse.sh` query and of `frame-end.sql`
on each arm, the results and state files) and the session log. The two diagnostic queries'
outputs (`frames-around-apply.sql`, `row-vs-display.sql`) were not kept; they can be regenerated
from the kept idle-arm trace. The traces' sha256:

| Trace | sha256 |
|---|---|
| `nf5-simple-on-idle.pftrace` | `03c555d7…86c75c9f` |
| `nf5-simple-on.pftrace` | `532d322b…0587aa38` |
| `nf5-simple-off.pftrace` | `41ccdc03…3b0a8356` |
| `nf5-heavy-on.pftrace` | `348b1f60…3138237a` |

They were then deleted from the phone, with `/data/local/tmp/nf5floor`.

## A12 reference for 0.5.0, 2026-11-26

The A12 reference for [`MS4_PLAN.md`](MS4_PLAN.md) section 3's C11: "open, back, cold start and
scrolling no worse than the 2026-11-25 re-measure on the same harness". It ran a day late, so it is
dated 2026-11-26. It is the baseline the 0.5.0 candidate's C11 run is compared with. It used
checkpoint 2's harness and method:
- **G**: the cold start, against a same-session native reference;
- **H**: the depth-1 open, simple and heavy, timed from the touch-down, and the back;
- **the retained back**: series A's `sysback` with `x=retainhost`;
- **K**: the tap's first visibly changed frame, against native Compose's own button.

The phone scripts are checkpoint 2's, copied, in
[`docs/a12-reference-2026-11-26/`](a12-reference-2026-11-26/). The run order is in `lib.sh`, and
`an.sh` gives the medians, on the phone. Its output for this run,
[`an.txt`](a12-reference-2026-11-26/an.txt), lists every iteration's value (sorted) for G, H and
the retained back. K's per-touch values are in the tables below. The C11 run should use the same
scripts.

Three script changes came after the run, from its review. `k.sh`'s `arm()` regained the
battery-level check of checkpoint 2's `px.sh` (it stops below 30% off the charger; this run was on
the charger, where it passes). `lib.sh` lost checkpoint 2's unused `taps()`. `an.sh` now prints `dataFrameEnd`, so
`an.txt`, written before that, has no frame-end line; G's "Its frame end" column comes from the
raw results files (each launch's value is listed below the table).

**Build.** The same install as NF5 R0 (above): the three-way sample's benchmark build from
`316abd41a`'s code, on the fork's engine built locally as `1.28.0-q20260604`, with `Long` as BigInt.
It is not main's own build (which resolves the published `1.28.0-keliver.1`), and it holds no MS4
code: nothing of MS4 had merged.

**Phone state.** 05:02–05:22 IST, up 4 d 2 h 50 min – 3 h 10 min. **Plugged in and charging, 62% →
73%, unlike checkpoint 2 (on battery, 83–90%).** Temperature 34.4–34.6 °C (checkpoint 2:
25.5–25.8). Thermal status 0, CPU idle 95–99% before each arm, load average 20.6–22.5.
- **The power state differs from checkpoint 2's.** Native was faster than in checkpoint 2: data
  drawn 508 against 618 on a cold start, first frame 296 against 378.5. Whether the charger caused
  that is not separated (G, below). The C11 run must be on the charger too, or this reference must
  be re-run on battery. Comparisons across power states are not valid.
- **Iterations.** G: two rounds of 1 unmeasured and 4 measured launches, so 8 per arm. H and the
  retained back: two rounds of 14 iterations, the first 2 dropped, so 24 per arm. K: 9 rounds, the
  first dropped, so 8 per touch.
- **Runs.** No run timed out, and every bench run ended `done`.

### G: cold start

Data drawn, ms from the process start; the other columns are medians of the marks or of each
launch's difference:

| Arm | Data drawn: median / p90 / min–max | − native | Its frame end (`dataFrameEnd`) | First frame | Code load starts → succeeds | Request → response | Parsed → composed | Composed → data drawn |
|---|---|---|---|---|---|---|---|---|
| native | **508** / 513 / 500–513 | — | 583 | 296 | — | 138.5 | 23.5 | 2 |
| guest, as shipped | **1,568.5** / 1,593 / 1,552–1,593 | +1,060.5 | 1,655 | 281.5 | 309 → 740 | 136 | 441 | 84.5 |
| guest, `prestart,prefetch` | **1,368.5** / 1,391 / 1,362–1,391 | +860.5 | 1,453.5 | 289 | 211.5 → 651 | 9.5 | 454.5 | 84 |

Data drawn, each launch (sorted): native 500, 501, 504, 507, 509, 510, 512, 513; as shipped 1,552,
1,558, 1,563, 1,566, 1,571, 1,573, 1,583, 1,593; `prestart,prefetch` 1,362, 1,364, 1,366, 1,366,
1,371, 1,372, 1,388, 1,391.

Its frame end (`dataFrameEnd`), each launch (sorted; from the raw results files): native 576, 580,
580, 581, 585, 587, 587, 589; as shipped 1,635, 1,641, 1,649, 1,650, 1,660, 1,665, 1,666, 1,677;
`prestart,prefetch` 1,445, 1,449, 1,449, 1,452, 1,455, 1,457, 1,472, 1,474.

Against series G (checkpoint 2, on battery): native 618, as shipped 2,143 (+1,525), `prestart,
prefetch` 1,866 (+1,248). Native gained 110 ms. This run changed the power state (and the phone's
temperature) and the build together, so the data does not say how much of it is the charger. The
guest gained more (−574.5 as shipped), in the code load (309 → 740, against 486.5 → 1,106.5) and in
composed → drawn (84.5 against 241.5). Those are the steps NF4's host baseline profile acts on
("NF4: composed to drawn", above), merged since. The charger and the code changes since `338a02f81`
are not separated here.

### H: open and back (header back)

From the touch-down, with a 100 ms hold; the last three columns are medians of each iteration's
difference:

| Arm | Open drawn: median / p90 / min–max | `click` | Click → composed | Click → drawn | Back drawn: median / p90 / min–max | Back: click → drawn |
|---|---|---|---|---|---|---|
| guest, press off | **247.5** / 261 / 240–262 | 111 | 103.5 | 136 | **166** / 169 / 155–171 | 56 |
| guest, press on | 260.5 / 269 / 239–271 | 190 | 53.5 | 69.5 | 158 / 170 / 152–173 | 49 |
| guest heavy, press off | **563.5** / 573 / 537–581 | 111 | 366.5 | 452.5 | **181.5** / 187 / 172–189 | 72 |
| guest heavy, press on | 602.5 / 635 / 582–636 | 483.5 | 104.5 | 125 | 175 / 190 / 170–192 | 66.5 |
| native | **127** / 130 / 116–132 | 105 | 20.5 | 21 | **120** / 127 / 114–131 | 15 |
| native heavy | **132** / 136 / 122–141 | 105 | 26 | 27 | **123.5** / 128 / 116–130 | 16.5 |

- Press off is 0.5.0's default (the owner, 2026-11-22). The guest opens 120.5 ms after native
  (simple) and 431.5 ms after it (heavy), from the touch-down.
- Against series H (battery): press off 247.5 against 278–280.5, heavy 563.5 against 583–590.
  Within this section the press is again neutral to worse: simple +13, heavy +39.

### The retained back (`bench=sysback`, `x=retainhost`)

From the press (no hold). First draw is the host showing the retained list; drawn is the guest's
confirming frame:

| Arm | Back, first draw | Back, drawn (guest) | Open (warm), drawn |
|---|---|---|---|
| simple | **21** / 24 / 15–27 | 69 / 81 / 64–97 | 156 / 173 / 138–182 |
| heavy | **13.5** / 22 / 6–24 | 92.5 / 99 / 81–100 | 461.5 / 470 / 440–483 |

Series A (BigInt, battery) had 16 and 12 for the first draw, and 97 and 95 for drawn.

### K: the tap's first visible change, on the pixels

DOWN → the first visibly changed frame presented (ms; median / p90 / min–max; n = 8 per touch,
round 1 dropped):

| Touch | Guest (BigInt) | Native |
|---|---|---|
| Row, quick tap | **63.7** / 69.7 / 57.8–69.7: the ripple | 95.9 / 105.4 / 79.8–105.4: the detail |
| Back (header) | **62.6** / 69.2 / 60.4–69.2: the ripple | 138.8 / 151.0 / 105.9–151.0: the list |
| Load 20 | **58.6** / 64.4 / 53.8–64.4: the ripple | **65.5** / 81.0 / 56.3–81.0: the ripple |

Each touch's value (rounds 2–9, in order):

| Touch | Guest | Native |
|---|---|---|
| Row | 61.4, 62.7, 57.8, 62.1, 67.0, 69.7, 64.6, 64.7 | 105.4, 98.2, 80.0, 79.8, 101.1, 98.6, 93.6, 88.8 |
| Back | 63.3, 61.0, 62.8, 60.4, 62.3, 69.2, 67.2, 61.9 | 122.5, 136.4, 131.7, 144.5, 144.5, 151.0, 141.2, 105.9 |
| Load 20 | 64.4, 55.7, 61.0, 55.8, 56.2, 53.8, 63.5, 62.7 | 56.3, 64.8, 66.2, 64.5, 81.0, 78.7, 56.8, 69.7 |

- **As in series K, every guest touch's first frame after the DOWN is pixel-identical to the one
  before it** (24 of 24), and so is native's Load 20 (8 of 8). The first change is the next frame
  (in 2 guest touches, the one after), the ripple's first step, with a largest channel change of 5–6
  of 255.
- **The tap bar (C11: at parity with native Compose's own button): met in this reference.** The
  guest's first visible change, 58.6–63.7 ms (medians), is at or below native's own button, 65.5,
  and the ranges overlap.
- **The absolute times are later than series K's (2026-11-22), in both forms.** The ripple comes
  17–21 ms later (then: guest 39.3–46.8, native button 44.2), and native's row and back 39 and
  21 ms later. Compare within this section only; the cause is not established. The guest arm was
  1.28.0 then and is BigInt now, but the ripple is the host's.
- **Script fix.** Native's recorder process kept the name `sh` in this trace. `q-px.sql` now finds
  the recorder by its thread's name; it had found no frames for native.

### What is not in this reference

- **Scrolling** (in C11's list) was not run.
- **The iPhone** half of C11 was not run.
- **The 1.28.0 anchor** was not run (no such build).
- **The engine.** The engine is the locally built `1.28.0-q20260604`, not the published
  `1.28.0-keliver.1` that main and the candidate use. Whether the two differ in speed is not
  measured.

## For an iOS run (follow-up)

The guest bundle and the web page are shared as they are: the iOS host would load the same bundle
(the same `ThreewayAppService`, `HostHttp` bound to an `NSURLSession`-based provider, the same
`threewayHost` marks service) and the same `index.html` in a `WKWebView` (with a script message
handler named `Threeway` in place of the Android JavaScript interface). The native form on iOS needs
its own screens: SwiftUI (or Compose Multiplatform for iOS, which would compile the same
ThreewayScreens.kt, as on Android, but is not "native" in the sense iOS developers mean), `URLSession`
and `JSONDecoder`, with the same layout numbers. The driver's synthetic taps and the "next draw" rule
need iOS equivalents (XCUITest taps; `CADisplayLink` after the change is committed).

## Unverified, and caveats

- **One phone, one session per series; the first sessions on the charger** (see Phone state), the
  second session and runs 2 and 3 on battery. The cold-start medians
  are single runs of 10; the earlier runs on the same day were within about 100 ms of them.
- **"Drawn" is the host's UI-thread draw**, not light on the screen; the RenderThread and the
  display add about a frame to every form. For the guest, the rule assumes the host's composition
  picks up the applied batch in the next frame (`Snapshot.sendApplyNotifications()` is called first);
  a frame missed there would make the guest look up to one frame faster than it is. For the web form,
  `rAF` plus a task is the usual "painted" signal, not a guarantee from Chromium.
- **The navigation split's "Compose itself"** is the remainder after the timed recording calls, not
  a profile: it includes the lazy layout, disposal and anything else the guest ran in that frame,
  and any garbage collection that fell outside the timed calls (collections inside them count as
  recording). The split's timers read `Date.now()` around every call (1 ms resolution, summed); with
  them on, back takes ~50 ms longer, so the recording times are a slight overestimate.
- **The experiments are one session each** (12 iterations), on the same day, on the charger, after
  the session's own as-shipped series reproduced the first session's within 5%. The `window10`
  series ran on a second APK built against a patched scratch publish of the prototype; the rest of
  that APK is the same source.
- **Synthetic taps** (dispatched to the window, not through the input system). The web form's
  tap → click is 13 ms with a small DOM and 39–46 ms once the page holds 1,000 rows (every large
  iteration after the first), so it grows with the page's own DOM, not only with Chromium's input
  routing to the renderer; native's (3–4 ms) and the guest's (8 ms) do not have that hop.
- **The network is a tunnel over wireless adb**, not a phone network: its own delay varies (one guest
  iteration 957 ms). Each form's network column shows what it got; the guest and native share
  the same client, the web form uses Chromium's stack.
- **Not minified.** The benchmark build type does not run R8 (`isMinifyEnabled = false`, as in Gate
  B's runs, although GATE_B_RESULTS.md describes that build as "R8"). With R8 native and the host
  side of the guest would likely be a little faster; QuickJS code is unaffected.
- **No app baseline profile** in any form; the libraries' profiles only. Not measured: a profile for
  the host's loader and bridge (Gate B's option 5).
- **The guest's code came from the APK's assets and its cache**, never a network: a production host
  that checks for updates first adds that round trip (Gate B, option 3).
- **The WebView's version** (system Chromium, as installed) was not recorded in the first session;
  in the experiments session it was 157.0.8103.38. Whether the first session had the same version
  is not known; the two sessions' as-shipped web series agree within 5%.
- **The scrolling comparison mixes runs and temperatures.** The guest's clean pass is run 2's
  (25.0–25.6 °C), native's and web's are run 3's (27.0–33.1 °C), the same APK on the same day; the
  phone was warmer for web than for the others. Each form and speed is one run. The guest's
  `x=scroll` pass's missed vsyncs were within 1.5 of the clean pass's at 31–34 °C.
- **The guest's fast `x=scroll` run is incomplete:** it logged 2 flings and then stopped logging,
  with no crash in the crash buffer; the logcat for it was not kept (the phone was off adb), so the
  cause is not established. The guest's work per fling at 6,667 dp/s is therefore not measured in
  run 3 (the first run's old-route numbers stand for it).
- **The process's first fast swipe** started no fling in four of six fresh-process guest fast runs
  (Compose's fling velocity 0 although the touch events' was 12,500 px/s); native's one fast run
  started its fling. Cause not established.
- **The GC result is one run per arm** (two for the default), 14 iterations of navigation and one
  API call in a fresh process. Collections are inferred from heap and object counts falling; no
  collection was timed directly.
- **Run 2's 14 s and 12 s gaps were long enough** (no fling closed by a touch or busy at DOWN);
  the guest's last frame ended up to 7.9 s after a fast fling's lift.
- **The second session ran on battery** (87% → 84%, 27.4–27.6 °C), the first two on the charger,
  and served the fixture API from inside the app (no tunnel); its baseline navigation matched the
  first session's within a few ms. Its API numbers are not comparable with the first sessions'
  network columns (none were reported).
- **Scroll flings came from `input swipe`**: real input events, but fixed straight-line gestures
  (runs 2 and 3: five speeds, 10 down and 10 up, fast 7 each way, one run per form and speed; the
  first run: two speeds). The placeholder timings come from the instrumented pass, which reads the
  semantics tree in every frame (in the first run, the fast flings' "settled" 4.9 and 6.7 s; the
  guest's own times in that pass were within about 5% of the clean pass's).
- **Guest ms per new row** is the guest's whole frame time divided by the rows it created: it also
  pays for disposing the rows that left the window and for recomposing the list; it is not a
  per-row profile.
- **The scrolling frame times are frame timing, not jank** (second session): `TOTAL_DURATION`
  depends on where a frame starts against vsync; its p50 and "% over 16.7 ms" flip between runs
  with no change in work. Missed vsyncs and frame components replace them in the rerun.
- **"Normal" is a gentle fling** (about 1,400–1,500 dp/s at release). Ordinary flicks are faster;
  run 3 places the guest's first placeholders at 2,133 dp/s, between the gentle speed and 2,844,
  with five fixed speeds: the threshold lies somewhere above 1,422 and at or below 2,133 dp/s.
- **"Content settled" is a lower bound** in the first run's table (the last blank frame; the rows
  appear in the next frame, up to one guest frame, 0.4–3.3 s, later). Run 3's placeholder table
  gives the first frame with content instead.
- **Run 3's placeholder pass is one run per speed** (10 flings each way, 7 at the fast speed), at
  five fixed straight-line speeds, from `input swipe`.
- **The heavy screen is one series per form** (12 warm opens and backs after 2 dropped) and three
  cold opens per form; its earlier check-run figures (output not kept) are superseded by run 3.
- **The review-round-1 instrumentation ran on a device in runs 2 and 3**: every path (clean and
  instrumented scrolling in all forms, heavy screen, GC arms).
- **QuickJS's GC**: Zipline's `gcThreshold` getter returns the value set, not QuickJS's current
  threshold, so a run cannot see whether a collection happened from it.
- **The iPhone** was not measured (see above for what a run needs).

## How to run it

```
# from the Keliver root: the prototype, to a scratch Maven repository (as Gate B)
./gradlew publishToMavenLocal -Dmaven.repo.local=/tmp/m2 -PkeliverVersion=0.0.0-gateb \
  -PkeliverComposePrototype -DRELEASE_SIGNING_ENABLED=false
# from sample/ (its own wrapper)
./gradlew :host-android:assembleBenchmark :benchmarks:assembleBenchmark \
  -Dmaven.repo.local=/tmp/m2 -PkeliverComposePrototype
# a fixture API on port 8491 answering GET /items?size=small|large&seq=N (fixed delay), then
adb reverse tcp:8491 tcp:8491
adb shell am instrument -w -e class dev.keliver.sample.benchmarks.ThreewayStartupBenchmark#native \
  dev.keliver.sample.benchmarks/androidx.test.runner.AndroidJUnitRunner      # or #guest, #web
adb shell am start -n dev.keliver.sample/dev.keliver.sample.host.threeway.ThreewayActivity \
  -e form guest -e bench nav,api-small,api-large --ei n 12 --ez overLockScreen true
adb logcat -s Threeway:I        # one JSON line per iteration, marks in ms from the tap
# experiments: add -e x <names> (see Experiments)

# the second session's additions (see "The second session"):
#   --ez localApi true       the app serves the fixture itself on 127.0.0.1:8491 (no Mac, no adb reverse)
#   --ei warmup 2            the series' summary line ("summary") drops the first 2 iterations
#   -e x heavy               the heavy detail variant (about 100 widgets), all three forms
#   -e x mem                 guest: QuickJS's heap and object count before each tap and after each drawn
#                            (300 ms pause between a read and its tap)
#   -e x gc4|gc16|gc64|gcoff guest: QuickJS's GC threshold (MiB; gcoff = -1) set when Zipline is created (implies mem)
#   am instrument ... -e localApi true   the startup benchmark with the app's own fixture (NF0's cold start)
#   am instrument ... -e x prestart,prefetch   the startup benchmark with the activity's experiments
#                            (NF4 step 1; one unmeasured launch first records the series' preference,
#                            so `prestart` acts in every measured launch: see that section)
#   -e x prestart            guest: the code load starts in bindApplication (the NEXT process's start)
#   -e x prefetch            guest: the host sends the list's first request as it starts the guest
#   -e x netlog              OkHttp's EventListener times for the process's first call and the fixture's
#                            listen/accept/request, in the cold line's "netEvents" (series Q)
#   -e x ip4                 the bench's requests go to 127.0.0.1 instead of localhost (series Q)
#   -e x prepared            guest (NF3): keliver-nav's prepared stack, depth 1, rows with prepareOnPress
#   -e x prepared,press      the same, with the host's press signal declared (keliver.press@1) and the
#                            guest's GateAPress provided: a touch-down on a row composes its detail
#   --ei hold 100            taps hold the finger down 100 ms without blocking the main thread, and the
#                            series' times are from the DOWN (the press series; see "A12 checkpoint")
#   -e x r1prep              guest (NF5 R1, docs/nf5-r1/): the list and detail in the host's retained
#                            container; the next open's detail is composed and hidden 300 ms after a back
#   -e x r1prep,reveal       the same; the host shows the hidden detail on the click, before the guest
#                            runs (the reveal; its marks: revealed, revealDrawn, revealRecorded, revealLive)
#   -e x r1prep,reveal,idlemeasure   the same; the hidden detail is measured whenever it changes, while idle
#   -e x trace               also: the driver's own sections, `bench:up <step> <i>` at each UP and
#                            `bench:drawn <screen> <seq>` in the draw the bench takes as `drawn`
# Every process logs one {"cold":"<form>",...} line when the list's data is first drawn: its marks in ms
# from the process start (see "Cold start (NF0)"), logged as the data frame's main-thread work ends
# (its `dataFrameEnd` mark; `drawn`/`dataDrawn` fire as that frame starts drawing: see "NF4: composed to drawn").
# Every state/summary/fling line carries "api":"app"|"mac"; the state line also refreshHz and density.
# The results file also has the per-frame "frameDetail" lines, which logcat does not get:
adb pull /sdcard/Android/data/dev.keliver.sample/files/threeway-results.txt
```

**The rerun, series by series** (review round 1; `A` is the activity launch, run from the Mac):

```
A='am start -S -n dev.keliver.sample/dev.keliver.sample.host.threeway.ThreewayActivity --ez overLockScreen true --ez localApi true'

# 1. Navigation baseline and heavy screen, each form (native, guest, web); -S = a fresh process,
#    so "open (cold, i=1)" is the process's first open. Repeat the heavy launch 3 times for 3 cold opens.
adb shell "$A -e form native -e bench nav --ei n 14 --ei warmup 2"
adb shell "$A -e form native -e bench nav --ei n 14 --ei warmup 2 -e x heavy"
adb shell "$A -e form guest  -e bench nav --ei n 14 --ei warmup 2 -e x heavy,split"   # guest breakdown

# 2. GC (guest only; the default arm with x=mem; wait for {"done":true} between launches)
adb shell "$A -e form guest -e bench nav,api-large --ei n 14 --ei warmup 2 -e x mem"   # default, run 1
adb shell "$A -e form guest -e bench nav,api-large --ei n 14 --ei warmup 2 -e x mem"   # default, run 2
adb shell "$A -e form guest -e bench nav,api-large --ei n 14 --ei warmup 2 -e x gc4"
adb shell "$A -e form guest -e bench nav,api-large --ei n 14 --ei warmup 2 -e x gc64"
adb shell "$A -e form guest -e bench nav,api-large --ei n 14 --ei warmup 2 -e x gcoff"

# 3. Scrolling, per form F (native, guest, web) and per speed; each a fresh process.
#    The 14 s sleep before the first swipe covers the start and the 1,000-item load.
#    Coordinates are for a 720x1600 screen; scale them for another.
#    gentle (800 px in 300 ms), 10 each way, 8 s apart:
adb shell "$A -e form F -e bench scroll; sleep 14; \
  for i in \$(seq 10); do input swipe 360 1300 360 500 300; sleep 8; done; \
  for i in \$(seq 10); do input swipe 360 500 360 1300 300; sleep 8; done"
#    intermediate: the same with 200, then 150, then 120 ms per swipe, and sleep 12
#    fast (1,000 px in 80 ms), 7 each way so the list does not reach an end, 14 s apart:
adb shell "$A -e form F -e bench scroll --ei group 7; sleep 14; \
  for i in \$(seq 7); do input swipe 360 1400 360 400 80; sleep 14; done; \
  for i in \$(seq 7); do input swipe 360 400 360 1400 80; sleep 14; done"
#    guest only, per speed, two more passes: -e x scroll (the guest's widget counters; a cost only
#    the guest pays, so the guest/native comparison uses the pass without it) and -e x scroll,blank
#    (placeholders, first content frame and settle times from the semantics tree; its frame times
#    are not the ones reported). The web form reports when the list stops, but no placeholder or
#    blank measure (Chromium's own renderer is not visible to the route).
# Each summary drops flings closed by a touch, busy at DOWN or at an edge, and counts them: if
# excludedTouch is not 0, lengthen the gap and rerun that speed.
```

**The Flutter form** ("Flutter on the A12"; not built by CI, a recipe):

```
# a Flutter module from the bench's two files (Flutter 3.50.1 stable)
flutter create -t module --org dev.keliver.bench threeway_flutter
cp docs/flutter-a12/main.dart threeway_flutter/lib/main.dart
cp docs/flutter-a12/pubspec.yaml threeway_flutter/pubspec.yaml
(cd threeway_flutter && flutter pub get && \
  flutter build aar --no-debug --no-profile --release --output /tmp/flutter-aar)
# the sample with the fourth form (from the Keliver root, on main 2073a4ace)
git apply docs/flutter-a12/flutter-recipe.patch
./gradlew publishToMavenLocal -Dmaven.repo.local=/tmp/m2 -PkeliverVersion=0.0.0-gateb \
  -PkeliverComposePrototype -DRELEASE_SIGNING_ENABLED=false
(cd sample && ./gradlew :host-android:assembleBenchmark -Dmaven.repo.local=/tmp/m2 \
  -PkeliverComposePrototype -PthreewayFlutterRepo=/tmp/flutter-aar/host/outputs/repo \
  -PthreewayNdkVersion=<an installed NDK, so native libraries are stripped>)
# install as series Y did (uninstall first, profileinstaller's broadcast, speed-profile), then
adb shell "$A -e form flutter"                   # on demand: the engine created in onCreate
adb shell "$A -e form flutter -e x prewarm"      # pre-warmed: created in bindApplication, from the
                                                 # NEXT process on (the setting is saved, as prestart)
adb shell "$A -e form flutter -e bench nav --ei n 14 --ei warmup 2"   # and every other bench
```

## NF5 R1: the reveal-only arm on the A12 (2026-11-28)

R1 ([`docs/nf5-r1/README.md`](nf5-r1/README.md); `SHOW_BEFORE_COMPUTE.md` section 7, R1's row) on the
Galaxy A12, following the kit's steps 0–7 in order: the eight arms of `run.sh`, step 4b's four no-ripple
arms, `analyse.sh all`, and one rerun of the two judged arms. Medians over opens 3–14 (n = 12), ms from
the UP, in one session on one build. Summaries are in
[`docs/nf5-r1/a12-2026-11-28/`](nf5-r1/a12-2026-11-28/); the traces and the full `analyse-all.txt` stay
in the session's scratch (their sha256 are recorded at the end of this section).

**Readings: MET on both sizes by the owner's bar. Pre-registered kit: NO VERDICT (the identity check
failed on every guest arm, rerun included). Owner's ruling pending.** Nothing below says "MET" without
that context.

**The readings, by the bar the owner decided on 2026-11-28 (they are what the arms measured; the kit's
own rule, "When an arm counts", does not let the two idle arms count, see the next block):**

| Size | Reveal first draw (`revealDrawn`) vs native's + 16.7 | Reveal buffer (`reveal.q_drew`) vs native's + 16.7 | Reveal `Record View#draw()` < 10 | Bar |
|---|---|---|---|---|
| **simple** | 13 against 19 + 16.7 = 35.7: in time | 26.5 against 39.2 + 16.7 = 55.9: in time | 4.5 ms | reading met |
| **heavy** | 12 against 24.5 + 16.7 = 41.2: in time | 32.4 against 82.1 + 16.7 = 98.8: in time | 8.2 ms | reading met |

The rerun of the two idle arms gave the same: simple 12.5 / 26.7 / 4.7, heavy 13 / 32.1 / 8.1. In
every reveal arm the reveal's own frame queued the buffer (`drew_own` 12 of 12, `q_rt` 0), and the
reveal's buffer came *before* native's own (26.5 against 39.2 simple, 32.4 against 82.1 heavy).

**The kit's own validity gate prints "R1: NO VERDICT", and that is reported as it is.** On every
guest arm, the identity check (the trace's click → composed within 2 ms of the bench's, on 10 of 12
opens) failed: 0 of 12 on all but `heavy-reveal` (1 of 12), and again on the rerun of both judged arms
(0 of 12). The difference is always positive, **2.0–5.4 ms** over all opens of all guest arms (a
constant of 2.3–4.4 on most; `heavy-reveal` has the 2.0 and the 5.4), **including the arms with the
reveal off** (`simple-off` 2.8–4.2). So it does not come from the reveal, and a rerun cannot change it.
What the bar reads (the reveal's own frame, its buffer, its `Record View#draw()`) is found by markers
inside the open's window and does not use `ec`; but the gate exists so that the trace's open is the
bench's, and by the kit's rule ("When an arm counts", README) the two idle arms do not count.

**The cause is the check's definition, not the arms.** The two spans measure different things. The
trace's `ec` runs from the start of the first `zl:sendEvent` slice to the start of the first
`zl:composed` slice, so it **includes the bridge crossings**; the bench's `composed − click` is taken
inside the guest and does not:
- The trace's start is when the host begins the `sendEvent` call (`ThreewayActivity.kt:1083–1087`,
  `callStart` opens the `zl:` slice). That is before the event is encoded, enters QuickJS, is decoded and
  reaches the click lambda; the guest marks `click` only inside that lambda (`ThreewayScreens.kt:151`).
- The trace's end is when the host receives the guest's `composed` call. The guest marks `composed`
  earlier and then builds the JSON of pending marks and encodes the call (`Main.kt:170–177`).
- The kit assumed the two agree "within about 1 ms" (`measure.sql:112–113`); that came from the emulator.

So the offset is always positive and scales with QuickJS's speed (about 0.5 ms on the emulator, where
`ec` is about 8 ms; about 3.3 ms on the A12, where `ec` is about 45 ms). It fits the data: positive on all
guest opens (8 guest arms and the 2 rerun arms, 14 opens each; the review counted 168, the stored files give 140), the same with the reveal off, on or idle, with or
without the ripple, and within an arm every open sits within about ±1.1 ms of the arm's own median
offset while `ec` itself ranges from 35 to 164 ms (a wrongly matched event would be a frame or more
away). **This is shown from the code and the pattern of the data, not confirmed by a trace query:** the
stored summaries do not hold `zl:sendEvent`'s own duration per open, and the traces (kept in scratch)
have not been queried for it. One query on them would confirm it. An earlier version of this record
blamed the guest's whole-ms clock; that cannot be it, because truncating two whole-ms marks errs by −1
to +1 ms around zero and cannot give an always-positive 3 ms.

**The owner rules** on the check; a re-based check is proposed in the next subsection.

#### Validity check re-based after the run (proposed; owner to accept or reject)

This is a proposal made **after** the run, from the stored files. It changes the check only; no reading,
threshold or arm is changed. The rule, stated before it is applied:

1. Per open, the offset (trace `ec` − bench `composed_click`) minus the arm's median offset is within
   ±2 ms on at least 10 of opens 3–14.
2. The arm's median offset is between 0 and 8.3 ms (half a vsync).
3. The host-side marker agreement: the trace's `bench:drawn` against the bench's `drawn` within about
   0.5 ms, and the trace's `r1:revealDrawn` after the bench's `revealDrawn` by the measure-and-layout time.

Computed from the stored `*.check.txt` (rules 1 and 2) and the stored `*.q3.txt` and `*.bench.txt`
(rule 3), opens 3–14 (the review's figures re-derived, not copied):

| Guest arm | (1) opens within ±2 of the median | median offset (ms) | offset range, opens 3–14 | (2) |
|---|---|---|---|---|
| `simple-off` | 12 / 12 | 3.7 | 2.8–4.2 | pass |
| `simple-reveal` | 12 / 12 | 3.2 | 2.4–4.3 | pass |
| `simple-reveal-idle` | 12 / 12 | 3.55 | 2.7–4.4 | pass |
| `heavy-off` | 12 / 12 | 3.75 | 2.8–4.1 | pass |
| `heavy-reveal` | **11 / 12** (open 4: 5.4, 2.1 from the median 3.3) | 3.3 | 2.0–5.4 | pass |
| `heavy-reveal-idle` | 12 / 12 | 2.95 | 2.3–3.7 | pass |
| `simple-reveal-idle-noripple` | 12 / 12 | 3.15 | 2.4–4.3 | pass |
| `heavy-reveal-idle-noripple` | 12 / 12 | 3.25 | 2.3–4.1 | pass |
| rerun `simple-reveal-idle` | 12 / 12 | 3.6 | 2.5–4.1 | pass |
| rerun `heavy-reveal-idle` | 12 / 12 | 3.15 | 2.5–3.7 | pass |

Every arm passes (1) and (2): all medians are 2.95–3.75 ms, all within 0–8.3. Only `heavy-reveal` (not a
judged arm) is below 12 of 12. (The native arms have no guest step and are not checked.)

Rule (3), on the six arms with the ripple (off, reveal, idle, both sizes; the rerun and the no-ripple
arms were not computed here):
- **`bench:drawn` against the bench's `drawn`:** read by eye on opens 3–14 of the six arms, the
  differences are −0.7 to +1.3 ms; the largest are 1.3 (`simple-reveal` open 14) and 1.2
  (`heavy-reveal` open 10); the rest are under 1 ms, in either sign. The bench's clock is whole-ms, so a per-open "within about 0.5" is too tight to
  hold on every open; the review's "about 0.5 ms (one open 2.0)" was not reproduced as stated. If the
  owner accepts this rule, the tolerance should read "within 1.5 ms" (the whole-ms quantum plus the
  marker's own cost); **that is a second post-run choice and is flagged as one.**
- **`r1:revealDrawn` after the bench's by the measure-and-layout time:** median gap against the
  arm's `ml_ms` median: `simple-reveal-idle` 1.9 against 2.0; `simple-reveal` 10.7 against 10.6;
  `heavy-reveal-idle` 3.25 against 3.3; `heavy-reveal` 24.55 against 24.8. It agrees on all four.

So the trace's open is the bench's open on the markers the bar reads, and the offset is a constant that
the check's definition adds.

**Worst case: could the offset change either reading?** The bar never reads `ec` or the guest's clock.
Even so, add the largest offset seen on any arm, **5.4 ms** (the judged arms' largest is 4.4), to the
reveal (re-derived from the printed medians):

| | Reading | Reveal + 5.4 | Limit (native + 16.7) | Margin (was) |
|---|---|---|---|---|
| simple | (a) first draw | 18.4 | 35.7 | 17.3 (22.7) |
| simple | (b) `q_drew` | 31.9 | 55.9 | 24.0 (29.4) |
| heavy | (a) first draw | 17.4 | 41.2 | 23.8 (29.2) |
| heavy | (b) `q_drew` | 37.8 | 98.8 | 61.0 (66.4) |

Doubled (+5.4 on the reveal **and** −5.4 on native) the margins are still 11.9, 18.6, 18.4 and 55.6 ms;
the rerun also passes both sizes. The offset cannot get into bar 2 (`Record View#draw()` under 10 ms): it
is one main-thread slice's duration. That bar's real exposure is the heavy margin, 8.2 against 10; every
judged open is under 10 (heavy opens 3–14: 6.7–9.6, rerun 6.7–9.2; the review's figures, not re-derived here).

**Under a validity check changed after the run, with no reading, threshold or arm changed, R1 passes.
The owner rules.** The pre-registered kit's outcome stays NO VERDICT. Applying a re-based check is a
change to a pre-registered gate, made after seeing the data; waiving the check would drop the guard the
next arms (R2, the (B) arms) need, and a rerun with the same kit fails the same way.

**What the run shows**

1. **The reveal is on the screen before native.** The reveal's buffer is queued 26.5 ms (simple) and
   32.4 ms (heavy) after the UP, against native's 39.2 and 82.1. The display frame ends (`sf_end`, a
   reading, not a condition) are 45.1 and 52.1 against native's 65.1 and 106.6. That is the prepared
   detail against native's first draw with data; the reveal has no shell and shows the predicted item
   (README, "What it leaves out").
2. **The idle measure is what keeps bar 2.** `Record View#draw()` of the reveal frame, with / without
   it: simple 4.5 / 12.8, heavy 8.2 / 29.8. Without it, bar 2's 10 ms fails on both sizes (bar 2 judges
   the idle arm; the arms without it are reported). Bar 1's two readings hold with or without it
   (simple 34.5, heavy 54.7 for the buffer). Measure and layout inside that draw: 2.0 / 10.6 simple,
   3.3 / 24.8 heavy.
3. **The emulator's lost first frame did not appear on the A12, but its mechanism does show up, after
   the UP, in the arms without the reveal.** q4 over all 14 opens of every arm: no RenderThread-only
   buffer in the 200 ms before the DOWN, none in the tap's hold, and the first frame after the UP queued
   a buffer in every open, on native, `off`, reveal and idle arms alike, with the ripple on. So the loss
   of the *first frame after the UP*, which the dry run found on an Android 15 emulator, is not seen on
   this Android 12 phone. But in `simple-off` and `heavy-off` the guest's live detail frame was queued
   by its own vsync in only **2 of 12** opens (`drew_own`); a RenderThread-only frame queued it in
   **5 of 12** (simple) and **9 of 12** (heavy) (`q_rt`), and q4's `rt_post` (RenderThread-only buffers
   in the 50 ms after the UP) is 1–2 on every open of both off arms. (Re-derived from the stored
   `*.q2.txt` and `*.q4.txt`.) The reveal arms and native are 12 of 12 own-vsync with `q_rt` 0, so the
   readings and the bar are unaffected; the ripple's RenderThread frames after the UP are real on this
   phone, in the arms the bar does not judge. The no-ripple arms are close to their ripple twins (below).
4. **The reveal adds to the guest's own step after the click** (appendix B assumed nothing): click →
   composed, bench (trace `ec`), off / reveal / reveal + idle: simple 39.5 / 46 / 43.5 (43.5 / 49.2 /
   46.7), heavy 52 / 87 / 63.5 (56 / 89.6 / 66.6). With the idle measure that is +4 ms on simple and
   +11.5 ms on heavy; without it, +6.5 and +35. `count.k_reveal` is 0 in every arm: the reveal's frame
   is not the one that ticked the guest, so the cost is in the frame before it or in the main thread's
   queue, not shown here. The rerun: 43 / 45.8 (simple), 63 / 66.2 (heavy). The guest's own detail
   drawn (bench `drawn`), off / reveal / idle: simple 62.5 / 66 / 66.5, heavy 114 / 111.5 / 90.5.

**The tables.** `rv` is the reveal's frame, `nat` native's (`drawn`). Opens 3–14, n = 12 for every cell
shown except `native-heavy`'s `sf_end` (n = 11: one open without a display frame; the same for
`simple-off` and `heavy-off`).

| Arm | first draw | `q_drew` | `rec_ms` (`ml_ms`) | `row_end` | `sf_end` | `q_vs` | main `f_end` |
|---|---|---|---|---|---|---|---|
| `native-simple` | 19 | 39.2 | 12.1 (10.2) | 39.3 | 65.1 | 2.5 | 31.7 |
| `simple-reveal-idle` rv | 13 | 26.5 | 4.5 (2.0) | 26.2 | 45.1 | 1.5 | 18.3 |
| `simple-reveal` rv | 14 | 34.5 | 12.8 (10.6) | 34.3 | 59.8 | 2.0 | 26.9 |
| `native-heavy` | 24.5 | 82.1 | 47.7 (42.7) | 83.8 | 106.6 | 5 | 74.7 |
| `heavy-reveal-idle` rv | 12 | 32.4 | 8.2 (3.3) | 32.8 | 52.1 | 2.0 | 22.0 |
| `heavy-reveal` rv | 14 | 54.7 | 29.8 (24.8) | 55.5 | 80.2 | 3.0 | 44.6 |

The guest's own live detail (`drawn` in the guest arms), `q_drew` / `sf_end`: `simple-off` 96.8 / 86.7,
`simple-reveal` 73.3 / 110.2, `simple-reveal-idle` 75.2 / 95.1; `heavy-off` 127.1 / 116.0,
`heavy-reveal` 129.7 / 163.7, `heavy-reveal-idle` 104.7 / 135.8. Per-open vsync reading, the reveal's
buffer no later than the vsync after native's own (`q_vs` ≤ native's + 1): 12 of 12 in all four reveal
arms. Counts: `count.opens` 14 everywhere, reveals 12 of 12 (trace and bench), lowest `nextHit` 1,
every results file 14 opens, `{"done":true}`, no timeout; `drew_own` 12 of 12 and `q_rt` 0 in native,
every reveal and idle arm (and the no-ripple ones and the rerun), **but not `simple-off` (`drew_own`
2 of 12, `q_rt` 5) and `heavy-off` (2 of 12, `q_rt` 9)**: point 3.

**The phone state per arm** (all 8 + 4 arms: **unplugged**, AC and USB off, thermal status 0; level %
at the arm's start, battery temperature °C, CPU idle of the 800% in `top`, up 6 d 15 h–16 h). The
Galaxy A12, Android 12, 17:57–18:12 IST, 18:29–18:36 for the rerun:

| Arm | level | temp | idle | Arm | level | temp | idle |
|---|---|---|---|---|---|---|---|
| `native-simple` | 57 | 22.4 | 436 | `native-heavy` | 57 | 23.4 | 546 |
| `simple-reveal-idle` | 57 | 22.5 | 511 | `heavy-reveal-idle` | 57 | 23.4 | 538 |
| `simple-reveal` | 57 | 23.1 | 476 | `heavy-reveal` | 56 | 23.5 | 537 |
| `simple-off` | 57 | 23.3 | 531 | `heavy-off` | 56 | 23.6 | 454 |
| `native-simple-noripple` | 56 | 23.4 | 556 | `native-heavy-noripple` | 56 | 23.5 | 551 |
| `simple-reveal-idle-noripple` | 56 | 23.3 | 511 | `heavy-reveal-idle-noripple` | 55 | 23.5 | 457 |

The rerun arms ran at 54%, 22.5–22.7 °C, thermal 0. The battery never fell below 54%. **The phone was
busy:** the state files show 436–565 of 800% idle (about 55–70% idle), against R0's recorded 96–97%. The
bar compares arms within the one session, so the run stays valid, but the absolute numbers are not
comparable with R0's or C11's. The first arm,
`native-simple`, had the lowest idle (436 against 454–556 for the rest); its `q_drew` is within 1.1 ms
of `native-simple-noripple`'s (38.1, idle 556), so that did not visibly move native's reading.

**Step 4b, the no-ripple diagnostic** (it never changes the verdict). The reveal's frame without the
theme ripple, against its native without it: simple first draw 12 against 17, `q_drew` 25.1 against
38.1, `rec_ms` 4.3 (< 10), `q_vs` 1 against 2.5; heavy first draw 12 against 23.5, `q_drew` 29.9 against
84, `rec_ms` 7.1, `q_vs` 2 against 5. Both would meet the bar by the same margins. Without the ripple
the reveal's frame is 1.4–2.5 ms earlier in `q_drew` (26.5 → 25.1, 32.4 → 29.9) and 0.2–1.1 ms shorter
in `rec_ms` (4.5 → 4.3, 8.2 → 7.1). That is a difference between arms, not an isolated effect of the
ripple: the no-ripple arms ran about 10 minutes later and native moved too (39.2 → 38.1, 82.1 → 84).
The first frame after the UP queued its buffer with or without it. The step-4b arms'
identity check fails like the others (0 of 12). The README's other controls (`simple-reveal-noripple`,
`simple-off-noripple`, `simple-listen`) were not in the eight or in step 4b and were not run.

**Build.** From `e9dcfc7d6` (main), the maintainer's build, step 1's record (`BUILD.txt` in
[`a12-2026-11-28/`](nf5-r1/a12-2026-11-28/), copied here):

```
built: 2026-11-28 17:56:23 by the maintainer, sample's own wrapper (Gradle 9.0.0), --no-daemon -PkeliverComposePrototype -Dmaven.repo.local=<scratch>/m2
commit: e9dcfc7d6163bee900f8544478374d6ceb212e7f (origin/main e9dcfc7d6; m2 published from this worktree)
local change for the build only: sample/settings.gradle.kts without the two maven.pkg.jetbrains.space lines (host gone, NXDOMAIN; PR #335). Restored after the build.
host-android-benchmark.apk: 26078199 bytes, sha256 06226f540dc263633d5822a98a1658137fd2f4a6182eec0574d14175b4486a01
  lib/arm64-v8a/libquickjs.so: 2618992 bytes   (armeabi-v7a 1903900, x86 2097092, x86_64 2465256)
tp (trace_processor_shell_aarch64 from the benchmarks APK): sha256 ae2f18ff9cbcad67edc28b5d6ac4364f696afbcc1364680752c2159b668a0e7d
```

The sample was built with its own wrapper (Gradle 9.0.0, because its AGP 8.13.2 needs it, and not the
root's 9.7.1), with the sample's two dead `maven.pkg.jetbrains.space` lines removed for the build only
(PR #335). The installed copy's sha256 on the phone (`06226f54…486a01`) was read back by the run's
`state.txt` in every arm. Install: `install -r -d`, profileinstaller's broadcast (result 1),
`speed-profile`.

**Unverified, and caveats**
- **The identity gate** (above): the two judged arms do not count by the kit's rule, twice. The bar's
  readings are as printed. That the offset is the bridge crossings is shown from the code, not
  confirmed by a trace query of `zl:sendEvent`'s duration.
- **The idle reading is the driver's.** The driver's tap blocks the main thread for 40 ms and lifts in
  one message, so the first frame after the UP starts after it: 6.1–7.3 ms on native, 7.8–10.3 ms
  (simple) and 6.8–9.4 ms (heavy) on the reveal-idle arms (q4's `f1_start`).
  With a finger the main thread is free; this is not measured here.
- **One session, one phone, on battery** (54–57%), with no charger, **and a busy phone** (55–70% CPU
  idle, against R0's 96–97%): R0 and the C11 reference ran on the charger and idle, so the numbers here
  are not comparable across those (native runs in the same session, so the bar does not cross power
  states or load).
- **The prepared detail is the predicted item** (the driver's next row is known); a real tap on any
  other row is not R1. No shell, token, answer or cancel, no back handling.
- **The rerun covers the two judged arms only.** The other arms that fail the gate were not rerun
  (the failure is constant across them).
- The emulator's lost first frame after the UP (point 3) is not reproduced on this phone; whether it
  appears with a finger's tap or on Android 15 hardware is not measured. The RenderThread-only frames
  after the UP do appear, in the off arms.
- `sf_end` has n = 11 (one open without a display frame) on `native-heavy`, `simple-off` and `heavy-off`.

**Trace sha256** (`shasum -a 256`, recorded 2026-11-28 after the review; the traces sit in the session's
scratch directory with their results; `r1/` = the 12 arms of steps 3 and 4b, `rerun/` = the rerun):

```
20be39cc61380f91e3f2894f7b41e7f7055671d85f031952a4f788cd0b213827  r1/nf5r1-heavy-off.pftrace
f28632b20ff508a99f0a5d435ebe179347eeeb3d2a1b47338ec7b25dc4b6a140  r1/nf5r1-heavy-reveal-idle-noripple.pftrace
207a20406f9eac71dc442cea1813b5f081ecaaa2c6957d66c9390a66992c4dee  r1/nf5r1-heavy-reveal-idle.pftrace
1478b6fbe2d906ceda9613b84f68b2cc1d408266491000b8190e037739483e68  r1/nf5r1-heavy-reveal.pftrace
401093c6b7a46302e8022be449acc41bcc632c0908aa6bbd3b55ae594a8538b9  r1/nf5r1-native-heavy-noripple.pftrace
73f025915dadd0869292be68705fb73754b6b9d39978ea81d108f15de9c18e75  r1/nf5r1-native-heavy.pftrace
975b9696394becace80b67a09761e3da2aa9138f62c824e5f6f4b30e85567c20  r1/nf5r1-native-simple-noripple.pftrace
bafd47f8c98a82b8b45c2bab88e64c54e31841e40e5561eddc012cc5b7cd7833  r1/nf5r1-native-simple.pftrace
6ef507cba2611a319b64419ab1d0a8e8a3c2573d3dd8f79e79aa5ed8d91fdf54  r1/nf5r1-simple-off.pftrace
6561b705dac6fd4df4b5afbed86ceb272540583d3a4c9f0af0f2b857b4465184  r1/nf5r1-simple-reveal-idle-noripple.pftrace
e9df4bd4d49e992df66e6b83eb8674867a65c563ea9f04ce3654e07e3e03f552  r1/nf5r1-simple-reveal-idle.pftrace
8f4186e4f35f106a32b6c43a6aa0ef7f06012a1f3b4031add5050e83384c0476  r1/nf5r1-simple-reveal.pftrace
7222c06457b97b83938c72e9d01f7fae2c9a0a23cec1403087858784aaacde8e  rerun/nf5r1-heavy-reveal-idle.pftrace
214b9bbc21927c7dcb27119e4e65f2a3b8faed809356abc911d11ea9661a9d93  rerun/nf5r1-simple-reveal-idle.pftrace
```
