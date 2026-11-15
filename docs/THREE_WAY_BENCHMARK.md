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

- **Scrolling (guest, measured 2026-11-16; [Scrolling](#scrolling)).** A normal fling looks native:
  the host's list scrolls by itself and the guest's window keeps ahead (no placeholders, stopped
  0.7 s after the finger lifts). A **fast fling shows a blank list**: from its first frames until
  4.9–6.7 s after the finger lifts (median), every row on screen is an empty placeholder while the
  guest composes, at about 0.1 s per row, the windows the list flew past. Native and web scrolling,
  a heavy screen's first visit and the GC threshold are built into the route but were not measured
  (the phone left adb).

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

Scrolling was measured in a later session: see [Scrolling](#scrolling).

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

## The second session: scrolling, a heavy first visit, the GC threshold

A third session, 2026-11-16 from 03:20 IST, same A12, measured what the first two did not: scrolling
the 1,000-item list, the first visit to a heavy screen, and QuickJS's garbage-collection threshold.
Everything is built into the same route, off unless asked for (`bench=scroll`, `x=heavy`,
`x=gc<MiB>`, `x=mem`), prototype builds only.

**Phone state.** On battery throughout (not on the charger, unlike the first two sessions), 89% at
the start, wireless adb, thermal status 0, no power saving; per series below (baseline: 87%,
unplugged, 27.5 °C). WebView
`com.google.android.webview` 157.0.8103.38 (as before). The APK was compiled as in the experiments
session (the libraries' profiles through profileinstaller, then `speed-profile`). The Mac was busy
with another build session; it served nothing to the phone in this session (below).

**The fixture API ran inside the app.** No Mac-side server could run in this session, so the route
serves the same API itself (`--ez localApi true`, `LocalFixture.kt`): `GET /items` on
127.0.0.1:8491, the same bytes (checked: 4,473 and 224,145 B), the same fixed 100 ms delay, HTTP/1.1
keep-alive. Every form still goes through its own network stack (OkHttp for native and the guest,
Chromium for the page); the adb tunnel and the Mac are left out, so the network part of an API call
is shorter and steadier than in the first sessions (about 125–130 ms for the large response, from
about 180–230). Navigation, scrolling and the heavy screen make no request after the first load.

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
were checked on the phone (one smoke run each, below) but **not measured**; they are one command each
in [How to run it](#how-to-run-it).

### Scrolling

**How.** The route loads the 1,000 items (tap "Load 1,000"), then the flings come from outside the
app, through the input system as a finger's would: `adb shell input swipe`, 10 down the list, then
10 back up, per run, each run in a fresh process. **Normal:** 800 px in 300 ms (about 2,700 px/s at
release, 1,400 dp/s); the list travels about 11 rows per fling. **Fast:** 1,000 px in 80 ms (about
12,500 px/s); about 106 rows per fling, so the tenth reaches the end of the list. 7 s (normal) or
8 s (fast) between flings, longer than the guest needed to settle. Per fling the route logs:

- **Frame times** from the window's FrameMetrics (`TOTAL_DURATION`, from the frame's intended vsync
  to the RenderThread's completion): the same numbers `dumpsys gfxinfo <package> framestats`
  reports, read in the app so each fling's frames are its own (a fling: the DOWN until nothing has
  been drawn for 1 s and, for the guest, no guest frame has run for 1 s). Comparable across the
  three forms because all three draw through this one window (the WebView through its draw
  functor); what it cannot see is Chromium's own renderer falling behind (checkerboarding), so for
  the web form it is a lower bound.
- **Settled:** the last drawn frame at which the list was still moving or still showed a
  placeholder, from the fling's UP. This needs the semantics tree read in every frame (the list's
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
Frame times in ms over all frames of 10 flings; the other columns are medians per fling.

| Guest, 10 flings each | Frames | p50 | p90 | p99 | max | > 16.7 ms | > 33 ms | > 100 ms | List stops (after UP) | Content settled (after UP) | Flings with placeholders | Guest busy after UP |
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
- **Normal flings: no visible cost.** The guest's window (the first row plus 15 preloaded, and the
  loading strategy's look-ahead) covers about 11 rows of travel, so every row the user sees has
  content; the guest keeps composing for 2–3 s after the list stops, off screen.
- **Fast flings: a blank list.** The list outruns the window within the first frames of the fling,
  and from then on **every frame shows only placeholders** (14 of them: the whole viewport) until
  the guest catches up: 4.9–6.7 s after the finger lifts (median), 2.6–4.5 s after the list has
  stopped. The guest composes the windows it passes through (about 50–70 rows per fling, 0.1 s each)
  before the one where the list came to rest.
- **Frame times.** The host draws frames of either about 10 ms or about 20 ms, so the medians land
  on one or the other (14.1–20.2 for the same normal flings in different runs; the instrumented
  pass 18.7 / 12.9); the shares over 16.7 ms swing with it (43–63%). Frames over 33 ms are rare in
  normal flings (0–2.5%) and 3–9% in fast ones, where the host recomposes placeholders into rows;
  the p99 of 85–97 ms and the 150–169 ms maxima are those. Whether the 20 ms frames come from the
  guest's work competing for the CPU or from the host's list itself needs the native series (not
  measured).

**What a user would feel.** A normal flick through the guest's list looks like a native list: it
moves with the finger, stops on rows that are there. A fast flick shows an empty list: the rows
vanish as soon as it gets going and the screen stays blank, the list already still, for two and a
half to four and a half seconds more before the rows appear. On this phone that is the guest's
biggest visible defect so far; a user would think the list broke. The cost is the guest composing
every window it passes (0.1 s per row in QuickJS), not the bridge or the host.

**The first, discarded attempt.** A first pass (03:35–03:45) closed a fling after 1 s without a host
frame, but a guest frame can run longer than that with the host idle: its work spilled into the next
fling's counts. The route now waits for the guest too; only the later runs are reported.

### A heavy first visit

**Built, not measured on the phone** (the phone left adb first). `-e x heavy` makes a row open a
second detail variant in all three forms: a header, the amount and status, a row of four chips, four
sections of a title and six label/value rows, a row of three buttons and a footer, text only:
**102 widgets** in the guest (counted: `n_creates` = 102 on every open), the same layout and strings
in the page (`index.html`). The series is the navigation series with that flag (`-e bench nav
--ei n 14 --ei warmup 2 -e x heavy`, each form), plus the guest with `split` for its breakdown.

One smoke run on the phone (guest, `split` on, 2 iterations, not a measurement): opening took
1,314 and 1,210 ms to drawn (the simple detail: about 430), of which the click reached the guest's
frame after about 35 ms, the guest composed and recorded until 947–973 ms (recording 1,036
properties and 102 modifiers: about 300 ms of it), its batch reached the host 106–120 ms later and
the host drew 128–194 ms after that. Back to the list after it: 1,186–1,213 ms. If the series
confirms it, a 100-widget screen costs the guest about 1.2 s to open on this phone, against an
expected tens of ms native and web (not measured).

### The GC threshold

**Built, not measured on the phone.** `-e x gc4`, `gc16` or `gc64` sets `zipline.quickJs.gcThreshold`
to that many MiB in the route's `ziplineCreated`, before any guest code loads; with it (or `-e x
mem`) the route reads QuickJS's `memoryUsage` (heap in use, object count) and `gcThreshold` before
each tap and after each drawn result, and at the end of each series (off the measured interval, on
the Zipline thread). The series to run: navigation and `api-large`, 14 iterations, the default twice
(for the run-to-run noise), then each threshold.

What the smoke runs showed, which matters for reading the series:
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

- **One phone, one session per series, on the charger** (see Phone state). The cold-start medians
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
- **Scrolling: the guest only.** Native and web scrolling, the heavy screen's series and the GC
  threshold series were built and smoke-checked but not measured: the phone left wireless adb at
  about 03:58 IST on 2026-11-16 and had not come back 45 minutes later. Without the native series,
  the guest's ~20 ms host frames cannot yet be attributed to the guest or to the list itself.
- **The second session ran on battery** (89% → 84%, 27.4–27.7 °C), the first two on the charger,
  and served the fixture API from inside the app (no tunnel); its baseline navigation matched the
  first session's within a few ms. Its API numbers are not comparable with the first sessions'
  network columns (none were reported).
- **Scroll flings came from `input swipe`**: real input events, but fixed straight-line gestures
  (two speeds, 10 each way, one run each). The fast flings' "settled" values (4.9 and 6.7 s) come
  from the instrumented pass, which reads the semantics tree in every frame; the guest's own times
  in that pass were within about 5% of the clean pass's.
- **Guest ms per new row** is the guest's whole frame time divided by the rows it created: it also
  pays for disposing the rows that left the window and for recomposing the list; it is not a
  per-row profile.
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
#   -e x gc4|gc16|gc64       guest: QuickJS's GC threshold (MiB) set when Zipline is created (implies mem)
# scrolling: load 1,000 items, then fling from outside, 10 down and 10 up per run; one line per fling
# and a summary per 10 (frame percentiles, shares over 16.7/33/100 ms; guest frames and widgets)
adb shell 'am start -n dev.keliver.sample/dev.keliver.sample.host.threeway.ThreewayActivity \
  -e form guest -e bench scroll --ez overLockScreen true --ez localApi true -e x scroll; sleep 14; \
  for i in 1 2 3 4 5 6 7 8 9 10; do input swipe 360 1300 360 500 300; sleep 7; done; \
  for i in 1 2 3 4 5 6 7 8 9 10; do input swipe 360 500 360 1300 300; sleep 7; done'
#   fast: input swipe 360 1400 360 400 80 (and back), sleep 8 for the guest
#   -e x scroll,blank        the instrumented pass: placeholders and settle times from the semantics tree
```
