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

**Not measured here.** This bench's Android host does not declare the feature and its guest does
not provide the signal, so no device number is new: open on the A12 stays as in "NF1 gate, part 2"
(about 310 ms). What a device run needs: the threeway host's `hostFeatures` to include
`keliver.press@1`, the guest's `Main.kt` to provide `GateAPress` and a prepared stack, and the bench
to time from the touch-down (not the click) to the detail drawn. Before the host declares the
feature, measure list-scroll jank on the A12 with the press on and off, and tune the scroll delay
(`PressSignalTiming`) against how long taps last there. The tap-within-one-frame target
also needs the host's pressed state (the ripple), which the host draws already; the guest is not in
that path.

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
# Every process logs one {"cold":"<form>",...} line when the list's data is first drawn: its marks in ms
# from the process start (see "Cold start (NF0)").
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
