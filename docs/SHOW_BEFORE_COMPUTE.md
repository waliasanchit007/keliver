# NF5: show before compute (design)

Status: **design, for review. No production code.** Written 2026-11-24 for WS7 of
[`PLAN_2026-11.md`](PLAN_2026-11.md). The owner chose this step on 2026-11-24, on one condition:
"it has to be executed perfectly". So this document states the mechanism, the invariants that make
it safe, and the measurements that decide go or no-go for each half. Implementation waits for a
separate review.

NF5 has two independent halves:
- **(A) Cached first frame.** On a cold start the host draws the tree the guest last produced for
  the first screen, from storage, while the guest's code loads. Then the guest takes over.
- **(B) Pre-composed destinations.** Likely destinations are composed ahead of the tap, hidden and
  inert. On the tap only their arguments change.

**Verdicts from the Mac measurements (appendix):** see [section 0](#0-verdicts).

## Contents

0. [Verdicts](#0-verdicts)
1. [The problem and the targets](#1-the-problem-and-the-targets)
2. [(A) Cached first frame: the mechanism](#2-a-cached-first-frame-the-mechanism)
3. [(B) Pre-composed destinations: the mechanism](#3-b-pre-composed-destinations-the-mechanism)
4. [Correctness invariants](#4-correctness-invariants)
5. [Cache key, invalidation and storage format](#5-cache-key-invalidation-and-storage-format)
6. [Failure handling](#6-failure-handling)
7. [Measurement plan and gates](#7-measurement-plan-and-gates)
8. [Alternatives considered and rejected](#8-alternatives-considered-and-rejected)
9. [Implementation plan](#9-implementation-plan)
10. [Open questions for the owner](#10-open-questions-for-the-owner)
- [Appendix: the Mac measurements](#appendix-the-mac-measurements)

## 0. Verdicts

Mac only: Zipline's QuickJS on the JVM for the guest, the JVM Compose UI host for the replay.
**Relative numbers, not phone numbers.** The appendix has the method and the raw output.

| Half | The question | Mac answer | Verdict |
|---|---|---|---|
| (A) | Is drawing a stored tree much cheaper for the host than waiting for the guest's first composition? | The three-way list (148 widgets): replaying the stored tree takes **4.2 ms** warm (p90 4.7; the first replay 24 ms), decode and frame included. The guest's first composition of the same screen takes **88 ms** (QuickJS 2021) or **45 ms** (2026, BigInt) before its batch is even sent. The stored tree is 87 KB of JSON, **7.9 KB gzipped**, and it draws **pixel-identical** to the live host's tree (0 differing pixels). | **Go** to the A12 gate. |
| (B) | Is changing a hidden, already-composed destination's arguments clearly cheaper than composing it fresh? | **Yes.** Simple detail: 15.45 → **4.91 ms** (3.1×) on QuickJS 2021, 7.68 → **2.97 ms** (2.6×) on 2026 with BigInt. Heavy detail (102 widgets): 62.94 → **8.42 ms** (7.5×) and 30.77 → **6.51 ms** (4.7×). Batches 20–40× smaller (17,900 → 902 bytes; 82,446 → 2,063). The floor (the destination already has the right arguments) is 2.95 / 1.78 ms simple, 5.46 / 4.27 heavy. | **Go** to the A12 gate, with the risks in section 3.5. |

What the Mac numbers suggest for the A12 (an inference, not a measurement):
- (B): series H on the A12 already measured the nearest case, a detail composed before the click that
  only recomposes a counter at the click: **80–85 ms simple and 131.5–140.5 heavy from the click to
  drawn** (against 163.5–167 and 470.5–479 composed fresh). NF3 lost that gain because the
  preparation ran at touch-down and the click waited behind it. (B) prepares while idle, so the
  click does not wait. Rebinding adds about 1.2 ms (simple) and 2.2 ms (heavy) of guest work over that
  case on the Mac, roughly 15 and 30 ms on the A12 at the Mac-to-A12 ratio of about 12
  ([`GUEST_PROFILE.md`](GUEST_PROFILE.md)). That would put the simple screen near 100 ms and the
  heavy one near 160–170: close to the targets, not certainly within them.
- (A): the host already pays the same apply and draw for the guest's first batch (NF4's "composed
  to drawn": 223.5 ms on the A12 for the list). A cached frame pays that plus a decode, with no
  code load and no guest composition before it. Whether that lands within native + 0.4 s depends on
  how early the host can draw and on CPU contention with the code load. Only the A12 can say
  (section 7).

## 1. The problem and the targets

The bar (PLAN_2026-11.md, section 2, point 3) asks the one over-the-air lane to feel native on a
Galaxy A12. Two targets are still missed by a wide margin. The best numbers so far come from the
BigInt engine fork (NF2), on the same A12, on 2026-11-22
([`THREE_WAY_BENCHMARK.md`](THREE_WAY_BENCHMARK.md), "Flutter on the A12, 2026-11-22", the answer
table; and "A12 checkpoint 2", series G and H):

| Target (PLAN section 2) | Native | Flutter | Keliver, best | Gap |
|---|---|---|---|---|
| Open a screen, simple: about 100 ms from the tap | 28 | 30.5 | 217.5 (BigInt) | about 2× the target |
| Open a heavy screen (about 100 widgets): 150 ms or less | 59 | 44.5 | 548 (BigInt) | about 3.7× the target |
| Cold start to the list's data: within 0.4 s of native | 728.5 (frame end) | 633.5 | 1,543.5 (BigInt, `prestart,prefetch`) | native + 0.84 s (on the screen, inferred) to + 1.25 s (series G) |

Why the steps so far do not close it:
- **The faster engine is spent.** NF2 roughly halved the guest's work (2.73× on open, 2.11× on back
  on the A12, series A). What is left is still composition in QuickJS.
- **Composing on touch-down did not help** (NF3, series F and H). Composing the detail takes longer
  than a press lasts, so the click waits for it. On the BigInt engine it is neutral on the simple
  screen and 36–52 ms slower on the heavy one.
- **Cold start is the list's first composition.** About 1 s of the cold start on 1.28.0 is the
  list's first composition in QuickJS ("Cold start (NF0)"; PLAN WS7, NF4). Prestart and prefetch
  took off 242 ms; the BigInt build about 700 more. The rest is guest work that has to happen
  before the first content can be drawn.

NF5's idea: do not make the guest faster. Show UI that is already composed, and let the guest's
work happen behind it.

## 2. (A) Cached first frame: the mechanism

### 2.1 End to end

```
cold start
  host: KeliverHost.run()            -> knows the cached release's manifest sha (lastGoodSha)
  host: KeliverScreen composes        -> FrameStore.read(key)   (key: section 5)
          hit, valid                  -> draws CachedFrame (inert), guest content composed beneath, hidden
          miss / invalid              -> today's Loading (nothing changes)
  guest: code loads, first composition, batches   -> applied beneath, unseen
  guest: marks the screen "ready"     -> host swaps in one frame: guest content shown, cached frame gone
  host: while the guest runs, folds its batches for that screen; on "ready" and on ON_STOP,
        writes the folded tree for the next cold start
```

### 2.2 Guest

- **Opt-in, per screen.** A screen that may be shown from storage says so, at its root:

  ```kotlin
  CachedFrame(
    policy = FrameCachePolicy(content = FrameContent.Shell, maxAge = 12.hours),
    ready = page != null,      // the guest's "first meaningful" signal (I4)
  ) {
    OrdersScreen(page)
  }
  ```

  `CachedFrame` is new, in `keliver-compose` (next to `LocalHostFeatures`,
  `keliver-compose/src/commonMain/kotlin/dev/keliver/compose/HostFeatures.kt`). It emits only when
  the host declares the feature `keliver.cachedFrame@1` (I8). It adds one modifier element to the
  screen's root node, carrying the policy and `ready`.
- **Volatile regions.** `Modifier.notCached()` marks a node whose subtree must never be stored
  (a balance, a name). With `FrameContent.Shell` the host stores the node's own modifiers (its
  size) but not its subtree, and draws a neutral placeholder in its place. With
  `FrameContent.Whole` nothing is replaced, but `notCached()` still drops a subtree.
- **No other guest change.** The guest's composition, its ids and its batches are as today.
- **Protocol.** Both markers are modifier elements with tags in a reserved framework range, defined
  in `keliver-protocol` (new file `FrameCacheTags.kt`) and covered by a lock test, as schema tags
  are. Not in gate A's extras (501–503), which sit outside the schema lock (survey of 2026-11-24,
  section 5).

### 2.3 Host (shared by Android and iOS)

Both production hosts draw with Compose UI (`KeliverScreen.kt` on Android, `MainViewController.kt`
on iOS through `ComposeUIViewController`), so the host side is common code:

| Piece | Where | What |
|---|---|---|
| `ChangeFolder` | new, `keliver-protocol` (common) | folds batches (Create, Property, Modifier, Add, Move, Remove) into the current tree; emits a `SnapshotChangeList` (`keliver-protocol/.../SnapshotChangeList.kt:29`, which already rejects Move and Remove). Applies the `notCached` rule. |
| `FrameRecorder` | new, `keliver-treehouse-host` | wraps the content's change sink (as `KeliverTiming.firstFrame` wraps `ZiplineTreehouseUi.Host.sendChanges` today, `scripts/templates/production-host/src/timing/kotlin/KeliverTiming.kt:436–455`), feeds `ChangeFolder`, and asks the store to write when the root marker says `ready` and when the screen stops. Only for a content whose root carries the opt-in marker. |
| `FrameStore` | new interface, `keliver-treehouse-host`; file implementation per platform | `read(key)`, `write(key, frame)`, `clear(reason)`. Atomic writes, size cap, integrity tag (I5). |
| `CachedFrameContent` | new, `keliver-treehouse-host-composeui` | decodes a stored frame and applies it through a fresh `HostProtocolAdapter` (`keliver-protocol-host/.../HostProtocolAdapter.kt:47`) into its own `ScopedChildren`-like container, with the app's widget system. Its event sink drops events (I3). Its mismatch handler is non-throwing: any unknown tag discards the frame (I1). |
| `TreehouseAppContent` | unchanged | Its first batch still clears `view.children` (`TreehouseAppContent.kt:451–455`). The cached frame never lives in `view.children`, so that clear does not touch it (I4). |

### 2.4 Host, Android (`scripts/templates/production-host/src/main/kotlin/`)

- `KeliverScreen.kt`: when the state is `Loading` or `Running` and the store has a valid frame for
  this screen's key, draw `CachedFrameContent` on top. The guest's `AppContent` is composed beneath
  it with `hidden = true`. That is the mechanism the update swap already uses
  (`KeliverScreen.kt:130–159`, `206–227`: alpha 0, `clearAndSetSemantics {}`, unfocusable, touches
  blocked). The swap trigger differs: the root marker's `ready`, not the first changes (I4).
- `KeliverHost.kt`: computes the key's release fields from `lastGoodShaKey` (written in
  `onLoaded`, `KeliverHost.kt:1567–1574`) and the start policy's choice (I1). Adds `session(id)`,
  which generalises `discardPrefetched()` (`KeliverHost.kt:1251`, I5).
- Storage: `context.noBackupFilesDir/keliver-frames/<cacheName>/` (I5).
- `HostApp.kt` (template): calls `keliver.session(...)` where it calls `discardPrefetched()` today.

### 2.5 Host, iOS (`scripts/templates/ios-host/src/iosMain/kotlin/MainViewController.kt`)

- The same screen logic in its screen composable (about lines 1284–1404) and its `Loading()` (1450).
- `KeliverHost.session(id)` beside `discardPrefetched()` (line 506), called from `iOSApp.swift`.
- Storage: `Library/Caches/keliver-frames/<cacheName>/`, with `NSURLIsExcludedFromBackupKey` set
  on the directory, and the default file protection (complete until first unlock).
- `UiStart.kt` holds `start()` until a scene is foreground (#281). The frame read is not held:
  it is a local file read, and drawing it needs no guest.

### 2.6 Navigation

- Only the **first screen of a launch** (the route the activity or scene opens with, as
  `KeliverHost.launch` encodes it, `KeliverHost.kt:601–622`, `KeliverRoute.kt`) is drawn from
  the cache. Screens reached inside the guest's navigation are the guest's business.
- The route and its launch arguments are part of the key (I1). A deep link to another order's
  detail never shows the last order's frame.

### 2.7 What (A) does not change

- The guest's code, its load, its start policy, its checks (signature, floor, compatibility).
- How the guest's tree is drawn once it has taken over.
- Screens that do not opt in, hosts without the feature, bundles without the opt-in (I8).
- D1: production UI is still compiled guest code. The cached frame is a recording of that code's
  own output, keyed to the very bundle that produced it, drawn only until that bundle takes over.
  No document format is authored, and no logic runs from storage (see question 1 in section 10).

## 3. (B) Pre-composed destinations: the mechanism

### 3.1 End to end

```
list shown, data drawn, guest idle (no input, no scroll, no frame for 300 ms)
  guest (keliver-nav): composes one entry per declared destination KIND, hidden, Prepared,
                       with the kind's initial arguments (e.g. the first row's item); in slices
  host: the entry sits in the retained container, not laid out, not drawn, no input or semantics
tap on row N
  guest: navigate(Detail(N)) adopts the prepared entry of kind Detail: its arguments become N,
         it recomposes with them, and it is shown (a visibility change)
  host: applies a small batch (property changes, two modifiers) and shows the entry
back
  guest: the entry is popped but KEPT as the prepared entry of its kind (args unchanged until the
         next tap); its per-visit state is reset (I7)
```

### 3.2 Guest (keliver-nav, `keliver-nav/src/commonMain/kotlin/dev/keliver/nav/`)

| Change | Where | Today |
|---|---|---|
| A route's **kind** is separate from its arguments: `KeliverNavKinds<R>(kindOf: (R) -> Any?)`, given to the controller. `null` = not rebindable (today's behaviour) | `KeliverNavController.kt` | full route equality (`prepared?.route == route`, `KeliverNavController.kt:94–100, 110–119`) |
| `NavEntry.route` is backed by a snapshot state; `navigate(route)` adopts a prepared entry of the same kind and sets its route | `KeliverNavController.kt` (`NavEntry`, 212) | the route is a `val`; another route means a fresh composition |
| More than one prepared entry: at most one per kind, at most `maxPreparedKinds` (default 2) | `KeliverNavController.kt` (`prepared`, 86) | one prepared entry, dropped at any stack change |
| A popped entry of a rebindable kind becomes that kind's prepared entry | `KeliverNavController.kt` (`pop`, `popWithResult`) | popped entries are disposed (or retained, as back entries) |
| A new lifecycle state, `Prepared`, beside `Visible` and `Hidden` | `KeliverNavRetention.kt:122` | a prepared entry is `Hidden`, and its `LaunchedEffect`s run at prepare time |
| Prepared entries composed in a sub-composition whose effect coroutine context is **held** until the entry is first shown (I6), and composed in slices that yield between frames | new, `KeliverNavPreparedComposition.kt` | composed in the host's composition, in one frame |
| Per-visit state helpers: `rememberPerVisit {}`, `rememberSaveablePerVisit {}`, `rememberPerVisitLazyListState()` (scrolls to 0 on a rebind) | new, `KeliverNavPerVisit.kt` | none |
| An idle policy: prepare only after the first screen is `ready`, with no input, no scroll and no guest frame for 300 ms; drop all prepared entries on the host's memory-pressure signal and when the app goes to the background | new, `KeliverNavIdle.kt`, fed by host signals | NF3 prepares on touch-down |

The screen author declares which kinds are worth preparing and with what initial arguments:

```kotlin
KeliverNavHost(controller, kinds = KeliverNavKinds { route -> (route as? Detail)?.let { DetailKind } }) { route -> … }
// on the list, once its data is there:
KeliverNavPrepare(controller, Detail(page.items.first().id))
```

### 3.3 Host

- **No new host code for the mechanism.** A prepared entry is a hidden entry of the host's retained
  container (gate A's `ExtraRetained`, `keliver-compose-gatea-composeui/.../Retained.kt`: only the
  shown entry is measured and placed, 193–211; `RetainedSlot` clears semantics and focus, 221).
- **Precondition:** that container in the production widget system, with
  `keliver.nav.retainedContainer@1` declared by the production templates (today it is gate A's
  prototype, and the templates declare no host features: survey of 2026-11-24, section 5). NF1's
  production work delivers it; (B) waits for it.
- **Two host signals** the idle policy needs, as host features with events to the guest: "user
  input active" (a touch is down or a fling runs) and "memory pressure" (Android `onTrimMemory` at
  `RUNNING_LOW` or above; iOS `didReceiveMemoryWarning`).
- Android and iOS share all of it (both draw with Compose UI).

### 3.4 What (B) does not change

- Destinations that are not rebindable, and every route equality today's code relies on.
- The retained back stack (NF1), the press signal (NF3; it can stay off).
- The protocol and the bundle format.

### 3.5 Risks the Mac numbers do not settle

- **A hidden entry recomposes when what it reads changes.** The three-way detail reads the app's
  navigation counter, so the hidden detail recomposes on every back: back costs 0.5 ms (simple) and
  2 ms (heavy) more on the Mac (appendix, table B). Destinations should read only their arguments;
  the docs say so, and a debug counter reports recompositions of Prepared entries.
- **Preparing still costs one composition.** The first composition of the hidden heavy detail added
  60 ms (QuickJS 2021) or 30 ms (2026) to the list's first composition on the Mac, several hundred
  ms on the A12. In one slice, a tap during it would wait, as in NF3. So preparation is idle-only
  and sliced (pausable composition), and a tap cancels a slice in progress. Spike first (PR B1).
- **Per-visit state** (scroll position, an expanded section, a half-typed field) must not leak from
  one item to the next (I7). It needs the screen author's cooperation; keliver-nav makes the safe
  way the easy one, and a destination that does not opt in is composed fresh.
- **The heavy screen may land just over 150 ms** on the A12 (section 0). If it does, the lever is a
  shell + data split: prepare the layout with placeholders and fill the data (the batch is then
  mostly text properties, the cheapest kind).

## 4. Correctness invariants

Each invariant has an enforcement and a test. "Unit" means a JVM or common test in the module
named; "host test" a JVM Compose UI test like `RetainedHostBackTest`; "device" the A12 or the
iPhone.

### I1. Never wrong content

A cached frame is drawn only if every field of its key equals the current one (section 5): the
release (manifest sha256), the host build, the widget and schema versions, the engine, the route
and its launch arguments, the session, and the configuration (locale, layout direction, font
scale, dark mode, size class). Otherwise it is not drawn, and it is deleted (except after a
configuration change, which keeps it for that configuration: section 5).

**How the release is known before the lookup**, per start policy (`StartPolicy.kt:40`):

| Policy | Release the host will run | Sha known before the lookup? | When the cached frame may show |
|---|---|---|---|
| `cache-first` | the cached release (`lastGood`), always at this start | yes: `lastGoodShaKey`, written in `onLoaded` with the sha of the manifest bytes Zipline verified | at once |
| `parallel` (default) | the cached release **if** the lookup confirms it (`afterParallelLookup`, `StartPolicy.kt:177`) | yes, the same record; but the host has not yet committed to it | **after the lookup confirms** the same sha (`SHOW_CACHED`). If the lookup names another release (`LOAD_NAMED`), the frame is never shown. If the lookup fails or times out, the cached release shows, and so may its frame |
| `network-first` | whatever the lookup names | no | never before the lookup; after it, only if the named sha equals the frame's (rare: a network-first start already waits for the network) |

No sha recorded (`lastGoodShaKey` absent, a relay's legacy lookup) means no frame.

Why not show it under `parallel` before the lookup returns: a different release would then replace
a frame the host never runs. That is a visible switch of content (old release, then the loading
state, then the new one) and it breaks I1. The cost is the lookup's time. On the A12 bench the
code comes from the APK's assets and the lookup is local, so this is small there; on a real network
it is one round trip. Section 7 measures it. If it is too slow, the owner can allow the frame
before confirmation per channel (question 2).

When a newer release arrives later in the same process (an update loading in a new app), the
cached frame has already handed off. The update swap is unchanged.

**Enforced:** the key check in `FrameStore.read`, before any decoding; the policy table in one
function next to `afterParallelLookup`; a non-throwing protocol mismatch handler that discards the
frame on any unknown widget, property, modifier or children tag.
**Tested:** unit tests for each key field (a frame differing in exactly that field is not read);
the policy table as a table test (each `ParallelStep` × frame present); a stored frame with an
unknown tag is discarded, not drawn; a host test where the lookup names another release and no
frame is ever composed.

### I2. Data freshness is the guest's choice

- **Off by default.** A screen without `CachedFrame` is never stored. Safe for money and personal
  data, because nothing is written unless the screen's author asked for it.
- **Two levels.** `FrameContent.Whole` (the whole tree, for screens whose content is not
  sensitive: a catalogue, a menu) and `FrameContent.Shell` (the tree with every `notCached()`
  subtree replaced by a placeholder: a balance, a name, a list of transactions).
  The API has no default for `content`: the author must choose.
- **Maximum age.** `maxAge`, required; the host caps it at 7 days. A frame older than its
  `maxAge` (by the wall clock, and also by `elapsedRealtime` within one boot) is deleted, not drawn.
- **Recommendation for money/PII screens:** `Shell`, with every amount and identity marked
  `notCached()`. The docs say so with an example.

**Enforced:** the guest API (no default for `content`, `maxAge` required); the fold drops
`notCached` subtrees before anything reaches the disk. **Tested:** unit tests on `ChangeFolder`
(a `notCached` text never appears in the stored bytes; a search of the file for the value fails);
age expiry by both clocks.

### I3. Input before take-over

The cached frame is drawn, not run. Its widgets' event sink drops everything.

| Input on the cached frame | What happens | Why |
|---|---|---|
| Tap, long press | Consumed by the frame's container. The host shows its own progress indication at once (a thin indeterminate bar at the top, in the first frame after the touch). The tap is **not** queued for the guest. | Widget ids are not stable across runs, so a queued tap could reach the wrong widget or the wrong data. Feedback means it is not lost silently. |
| Scroll, fling | Consumed: the frame does not move; the same indication shows. | The stored lazy list holds only its window; scrolling would show blank rows. |
| Back | Not consumed: it goes to the activity or scene as on the loading screen today (it leaves the app from the first screen). | Same as today's Loading. The guest's back handlers are not registered yet. |
| Text input | Impossible: the container is unfocusable (as the hidden app is today, `KeliverScreen.kt:230–235`), so no field takes focus and no keyboard opens. | No input can be typed into a frame that will be replaced. |

Accessibility: the frame's container is `clearAndSetSemantics { contentDescription = "Loading";
liveRegion = Polite }` (iOS: the container is one accessibility element labelled "Loading", its
children hidden). A screen reader announces a loading screen, never stale content as interactive.
At the swap the guest's tree appears with its normal semantics, as a screen does when it loads.

**Enforced:** the container in `CachedFrameContent`; the dropping event sink. **Tested:** host test:
a click, a scroll and an IME request on the cached frame reach no guest (the guest's event tap
records nothing) and show the indication; the semantics tree under the frame has one node with no
actions; the swap's next frame exposes the guest's nodes.

### I4. Hand-off without a regression

Today the guest's first batch replaces the Loading view. Many screens' first batch is their own
loading state. Drawing the cached frame and then that batch would go content → loading → content.

Rule: **the guest's content is composed beneath, hidden, from its first batch; the cached frame
stays on top until the guest says `ready`.** Then, in one frame, the hidden content becomes visible
and the cached frame is removed. This is the update swap that ships today (`KeliverScreen.kt:130–159`),
with `ready` as the trigger instead of the first changes.

| Event | Result |
|---|---|
| The root marker's `ready` becomes true in an applied batch | swap in that frame |
| No `ready` within `HANDOFF_LIMIT` (proposed 3 s) after the guest's first batch | swap anyway (the guest's tree as it is); reported as `cachedFrame.handoffTimeout` |
| The guest crashes or the code fails to load | the cached frame goes; the screen shows what it shows today on that failure (`unavailable`) |
| The guest's first batch carries no opt-in marker (the new release dropped it) | swap at once; the store deletes the frame |

How the swap looks: no blank frame (both trees are laid out before the switch; the hidden one has
been measured beneath). No crossfade, as native shows new data in place. No jump: the stored frame
draws the list at its top, and the guest's list starts at its top. If the data changed since the
frame was stored, the new values appear in place, as a refresh does in a native app.

**Enforced:** `KeliverScreen` (Android) and the iOS screen hold the swap state; `ready` is read
from the applied marker, not from a guest call. **Tested:** host test with a guest whose first
batch is its loading state: frames recorded across the hand-off never show the loading state and
never an empty frame; the timeout path; a pixel check that the frame before the swap equals the
stored frame and the frame after equals the guest's.

### I5. Privacy and security

- **Where.** Android: `noBackupFilesDir` (app-private, excluded from Auto Backup and device
  transfer). iOS: `Library/Caches` with `isExcludedFromBackup`. Never shared storage.
- **Cleared:**
  - on a session change: `KeliverHost.session(id: String?)`, which replaces the documented
    `discardPrefetched()` call on sign-in, sign-out and user change (`HostApp.kt:16–17`,
    `iOSApp.swift:18`) and does both. The key stores an HMAC of the session id, never the id;
  - on a release, key or signing-key change: frames whose key differs are deleted when read, and
    all of a `cacheName`'s frames when the bundle key changes (the key is in `cacheName`,
    `KeliverHost.kt:191`);
  - on an app update: the host build is in the key; the first start of a new build deletes the
    directory;
  - on the app's own request: `KeliverHost.clearCachedFrames()`.
- **Size cap.** 512 KB per frame, 2 MB per `cacheName`, oldest first. Section 7 records the real
  size (the three-way list: appendix, table A).
- **What an attacker who can write the app's private storage can make the host draw:** only
  widgets of the app's own schema, with values of their declared types (the protocol decodes into
  the generated widget system; nothing is evaluated). The frame is inert (I3), so it cannot take
  input or send anything. It could show a misleading screen until the guest takes over (at most
  `HANDOFF_LIMIT` after the guest's first batch). Such an attacker already controls the app's
  preferences and databases; the code itself stays protected by the bundle's signature (D2).
- **Integrity tag: yes, cheap.** Each frame carries an HMAC-SHA256 over its header and body, with a
  per-install key kept by the platform (Android Keystore, non-exportable; iOS Keychain,
  `AfterFirstUnlockThisDeviceOnly`). A frame whose tag does not verify is deleted, not drawn. Its
  cost on the A12 is measured before it ships (section 7); if a Keystore operation costs more than
  20 ms at start, the fallback is a key in the app's private storage (protects against nothing an
  attacker with that storage cannot also read, so the owner decides: question 3).

**Tested:** unit tests for every clearing path; a backup-rules check in the template's tests
(the directory is under `noBackupFilesDir`); a frame with one byte changed is refused; a session
change deletes every frame before the next read.

### I6. Inert pre-composition (B)

A prepared destination sends no request, writes nothing, logs no analytics and runs no effect
visible outside its own composition until it is first shown. Today it does: NF3's prepared entry
runs its `LaunchedEffect`s and `DisposableEffect`s at prepare time (`KeliverNavPreparation.kt:90–102`
says so, and tells authors to move them into `LaunchedOnceShown`).

**Enforced, in three layers:**
1. **Held effects.** keliver-nav composes a Prepared entry in its own sub-composition whose effect
   coroutine context holds every coroutine started in it (`LaunchedEffect`, `produceState`,
   `rememberCoroutineScope().launch`) until the entry is first shown, and cancels them if the entry
   is dropped. The same pattern as the start policy's held calls (`CallGate`, `StartPolicy.kt:329`),
   inside the guest. This needs public Compose runtime API only (a child `Recomposer` with its own
   effect context, `Composition(applier, recomposer)`, the parent's frame clock), with the
   composition locals passed on explicitly. Spike: PR B1.
2. **A lifecycle the screen sees.** `LocalKeliverNavEntryLifecycle` is `Prepared`.
   `LaunchedOnceShown` and `LaunchedWhileVisible` (`KeliverNavRetention.kt:162, 194`) do not run
   while Prepared. Synchronous effects (`DisposableEffect`, `SideEffect`) do run at composition; a
   preparable destination must not call a bridge, `HostHttp` or analytics from them.
3. **A test every preparable destination gets.** `assertInertWhenPrepared { Destination(args) }`
   composes it Prepared against capabilities that fail on any call (HTTP, bridges, storage,
   analytics), runs frames, and fails if anything was called. The scaffolder writes it for each
   kind an app declares; CI runs it.

**What screen authors must do:** start work only in effects (held) or in `LaunchedOnceShown`;
create presenters with `rememberPerVisit { }` (not at composition time with side effects in their
constructor); keep `DisposableEffect`/`SideEffect` free of calls out.

**Tested:** keliver-nav unit tests: a Prepared entry's `LaunchedEffect` has not started after N
frames, starts at the show, is cancelled at a drop; `LaunchedOnceShown` likewise; a held HTTP call
from a prepared entry reaches a fake host only after the show.

### I7. Arguments (B)

Today a `NavEntry`'s route is a `val` (`KeliverNavController.kt:212`), its content is
`content(entry.route)` (`KeliverNavHost.kt:165, 176`), and its `SaveableStateHolder` key is the
entry's id. A prepared entry is used only when the new route equals it.

- **Taking the real arguments.** `navigate(route)` with a prepared entry of the same kind sets the
  entry's route state to `route` and pushes the entry. Its content recomposes with the new route
  in the same frame as it becomes visible. The entry keeps its id, so its composition (and its
  `SaveableStateHolder` key) stays.
- **Remembered state.** Everything a rebindable destination remembers must be either independent
  of its arguments or keyed by them. keliver-nav gives a visit counter
  (`LocalKeliverNavVisit`) that increases at each adoption, and helpers keyed by it:
  `rememberPerVisit {}`, `rememberSaveablePerVisit {}`. Plain `remember(route.id) {}` is fine too.
- **Scroll position.** `rememberPerVisitLazyListState()` returns to the top at each visit (a scroll
  of a live list, not a new composition). A destination's lists must use it.
- **Saved state across process death.** The entry is saved with the route it shows; a prepared,
  never-shown entry is not saved (it is rebuilt after a restore, when idle).
- **What cannot be rebound is composed fresh.** A kind is rebindable only if the app's
  `KeliverNavKinds` says so. A destination with a text field, a web view, a media player, or
  anything it cannot reset should not be declared rebindable; then it behaves exactly as today.
- **Results and back.** `popWithResult` and `LaunchedOnResult` (`KeliverNavResults.kt`) are
  unchanged: they see the entry's current route.

**Tested:** keliver-nav unit tests: adopting a prepared `Detail(7)` for `Detail(8)` shows item 8's
content and no remembered value of item 7 (a per-visit counter, a per-visit saveable, a list
scrolled to row 10 is at row 0); a non-rebindable kind composes fresh; the guest profile's
`nf5=rebind` checks each open's batch carries the opened item's title (appendix).

### I8. Old hosts and bundles

- (A) The guest emits its markers only when `LocalHostFeatures` contains `keliver.cachedFrame@1`
  (the host features path: `TreehouseApp.Spec.hostFeatures`, `TreehouseApp.kt:273–275`, to
  `LocalHostFeatures`, `HostFeatures.kt:32`). An old host does not declare it, so it never sees the
  markers and behaves as today. A new host with a bundle that never opts in stores nothing and
  draws nothing new.
- (B) Pre-composition by kind is guest-side (keliver-nav). It needs the host's retained container
  (`keliver.nav.retainedContainer@1`) for hidden entries that are not laid out or drawn; without it
  keliver-nav falls back as `retentionFor(hostFeatures)` does today (`KeliverNavDefaults.kt:66`):
  no preparation, today's behaviour.
- Feature names become constants in one place (today they are typed by hand in several,
  survey section 5), with a test that the templates declare exactly what they implement.

**Tested:** `HostFeaturesGuestTest`-style tests (`keliver-treehouse-host/src/appsJvmTest/.../HostFeaturesGuestTest.kt`):
without the feature the guest's batches are byte-identical to today's; with it, only the marker
elements are added.

### I9. Resource bounds

| Resource | (A) | (B) |
|---|---|---|
| Memory | One decoded frame while it is drawn (the three-way list: 148 widgets), freed at the swap. The guest's own tree is composed beneath during the hand-off, so for that window the host holds two trees, as the update swap already does. | One composed destination per prepared kind, guest and host side: the heavy detail is 104 widgets. At most `maxPreparedKinds` (default 2). Dropped on memory pressure and in the background. Measured on the A12 (PSS, section 7). |
| CPU and battery | A read and a decode per cold start (Mac: about 1 ms for 87 KB of JSON); writes only at `ready` and at `ON_STOP`. | One composition per kind when idle (Mac: +7 to +60 ms, table B's `first`); recompositions when what the hidden entry reads changes (+0.4 to +2 ms per back on the Mac). Never during input, a scroll, a fling or an animation; never before the first screen is ready. |
| Disk | 512 KB per frame, 2 MB per `cacheName` (the list: 87 KB, 7.9 KB gzipped). | none |

**Enforced:** caps in `FrameStore`; the idle policy and `maxPreparedKinds` in keliver-nav.
**Tested:** the cap's eviction order; the idle policy with a fake clock and input events; the A12
series in section 7 (memory, scroll jank, battery).

### I10. One writer, one reader

The store is written from one place (the recorder of the content that showed the screen) and read
once per cold start, before the guest's first batch. Two screens with the same key (two activities
on one route) write the same file; the last complete write wins (atomic rename). A frame is never
read while it is being written (the reader opens only the renamed file).

**Tested:** unit test with two writers and a reader on one key.

### I11. The stored tree is the tree that was drawn

The fold must reproduce exactly what the host had applied. **Enforced:** `ChangeFolder` is tested
against the host itself, not against a model: every test applies the raw batches to one host and
the folded snapshot to another, and compares the widget trees (and, on the JVM Compose UI harness,
the pixels). The appendix's measurement does this for the three-way list (0 differing pixels).
Random batch sequences (Add, Move, Remove, detach, re-add) are generated in a property test.

## 5. Cache key, invalidation and storage format

### 5.1 Key and invalidation

| Field | Source | Changes when | On mismatch |
|---|---|---|---|
| release | manifest sha256 (`lastGoodShaKey`; under `parallel`, the confirmed one) | a new release loads | not drawn; deleted |
| engine | `hostEngine` (NF2: the engine that loaded it, `lastGoodEngineKey`) | the app moves engines | not drawn; deleted |
| host build | the app's version code / bundle version | an app update | directory deleted at the first start |
| widget and schema versions | the widget system's version and the schema's tag-lock hash | a host with other renderers | not drawn; deleted |
| format version | the file's header | the store's code changes | not drawn; deleted |
| route + launch arguments | the encoded `KeliverRoute` | another deep link | another file (no frame for that route) |
| session | HMAC(install key, session id) or "signed out" | sign-in, sign-out, user change | all frames deleted at `session()` |
| configuration | locale, layout direction, font scale (rounded to 0.05), dark mode, width size class | the user changes a setting, rotates into another size class | not drawn (kept for the other configuration, within the cap) |
| age | `createdAt` (wall clock and boot-relative) vs the screen's `maxAge`, capped at 7 days | time | not drawn; deleted |

The file name is a hash of route + configuration + session. The other fields are in the header
and are checked after reading it.

### 5.2 Storage format

```
magic "KLVF" | format version (1) | header length | header (JSON) | body length | body | HMAC-SHA256 (32 bytes)
header: { release, engine, hostBuild, widgetVersion, schemaHash, route, configuration,
          session, createdAtMillis, createdAtBoot, maxAgeMillis, content: "whole"|"shell" }
body:   a SnapshotChangeList as the bridge's JSON (array polymorphism), gzip-compressed
```

- The body is the fold of every batch the screen's content received, up to the moment of writing.
  Only Create, PropertyChange, ModifierChange and ChildrenChange.Add, in an order a fresh host can
  apply (each widget's Create, its last property values, its last modifier, then its children and
  their Adds). `notCached` subtrees are already dropped (I2).
- Lazy lists: only the rows the guest had composed (its window) are stored. The frame shows them;
  no scrolling (I3).
- Writes: to `<name>.tmp`, `fsync`, then rename. A reader opens only `<name>`.
- Version 1 is JSON for reviewability and because the decoder exists on both platforms. A binary
  body is a later, measured choice (the appendix's sizes and decode times).

## 6. Failure handling

| Failure | Detected by | What the user sees | Record |
|---|---|---|---|
| Unreadable, truncated or corrupt file | magic, lengths, HMAC, JSON decode | today's Loading (no frame) | file deleted; `cachedFrame.discarded(reason)` |
| Unknown tag in the frame (renderers changed without a version change) | the non-throwing mismatch handler | today's Loading | file deleted; reported, because the key should have caught it |
| Guest crashes after the frame is shown | `KeliverHost.crashed` / the content's Crashed widget | the cached frame goes; today's `unavailable` | frame deleted (the release may be bad); crash rollback as today |
| Code fails to load (signature, floor, network) | `KeliverHostState.Unavailable` | the cached frame goes; today's failure screen | frame kept only if the failure is the network's and the next start will load the same release |
| The guest never says `ready` | `HANDOFF_LIMIT` after its first batch | the guest's tree as it is | `cachedFrame.handoffTimeout` |
| No guest batch at all within the code-load limit | the existing load timeout | today's behaviour for a stuck load | frame kept |
| Process death mid-write | the rename never happened | next start: the previous complete file, or none | the `.tmp` is deleted at the next start |
| Disk full on write | the write throws | nothing visible | `cachedFrame.writeFailed`; no retry until the next `ready` |
| The clock jumped (manual time change) | boot-relative age check disagrees with the wall clock | the frame is not drawn | deleted |

## 7. Measurement plan and gates

All on the A12 with the three-way benchmark's method ([`THREE_WAY_BENCHMARK.md`](THREE_WAY_BENCHMARK.md),
"Method": marks, "drawn" as the next window draw; cold start with Macrobenchmark `StartupMode.COLD`,
10 iterations, `reportFullyDrawn` at the list-data frame; navigation with `bench=nav`, 700 ms
between taps). iOS: the simulator for relative numbers, the iPhone once it is signed.

### (A) Cold start

Two new marks, both reported:
- **first content drawn**: the first window draw that shows the cached frame (or the guest's
  content, without a frame);
- **guest took over**: the draw after the swap (I4).

| Gate | Measure | Pass |
|---|---|---|
| A-1 | first content drawn, with a cached frame, `cache-first` | within native + 0.4 s of native's list-data frame (native about 729 ms frame end, so about 1.13 s) |
| A-2 | the same under `parallel` (lookup confirming) | within native + 0.4 s; else question 2 |
| A-3 | guest took over | not later than today's data frame for the same build (no regression) |
| A-4 | cold start without a frame (first launch, after a session change) | not slower than today by more than 10 ms (the store read and key check) |
| A-5 | the swap | no frame without content between the cached frame and the guest's (frame trace); pixel diff of the swap frames |
| A-6 | the integrity tag | Keystore HMAC at start ≤ 20 ms (I5) |

What "data" means here: with `Whole`, the first content drawn already shows the list's data, from
storage; A-1 is then the native comparison, and A-3 says when it is live. With `Shell`, the data
regions are placeholders until the swap, so for the bar only A-3 counts as "data", and A-1 as
"first content". The report gives both, per screen.

### (B) Open

The three-way bench gets an arm `x=prepkind` (the detail prepared by kind while idle, press off),
against today's `x=retainhost` and as shipped; `bench=nav`, simple and `heavy`, both engines.

| Gate | Measure | Pass |
|---|---|---|
| B-1 | open, simple, tap to drawn (frame end), BigInt | about 100 ms (median), p90 under 130 |
| B-2 | open, heavy, tap to drawn, BigInt | 150 ms or less (median) |
| B-3 | a tap while a preparation slice runs | no slower than open without preparation (the NF3 failure must not come back) |
| B-4 | back | no regression against `x=retainhost` (series C, checkpoint 2) |
| B-5 | the inertness test (I6) on the three-way app's detail and on the reference app's preparable screens | zero calls out before the show |
| B-6 | iOS simulator, the same arms | the same ordering as the A12 (relative) |

### Regressions to watch

| What | How | Limit |
|---|---|---|
| Scroll jank from idle pre-composition (B) | series F3's flings with pre-composition on vs off; frames over 25 ms | no increase beyond the run-to-run spread |
| Memory (A and B) | PSS after the list (F5's method), with and without | (B) reported per prepared kind; (A) the decoded frame is freed at the swap |
| Battery (B) | the composition work done while idle, per minute of browsing (guest CPU time) | reported; the owner sets the limit |
| APK size | F4's method | (A) and (B) add code only; under 50 KB dex |
| Disk (A) | the store's directory size after a session | under the cap (I5) |
| First launch (A) | cold start after `pm clear` | unchanged (no frame exists) |

## 8. Alternatives considered and rejected

| Alternative | Why not |
|---|---|
| A screenshot (bitmap) of the first screen instead of a tree | Wrong on any configuration change (font scale, dark mode, size); not accessible; much larger; text not crisp on another density. The tree is drawn by the real renderers at the current size. |
| Pre-composing every row's destination | Memory and idle CPU grow with the list; most are never opened; still pays a composition per row. (B) prepares one per kind. |
| Host-composed documents (the parked fallback, PLAN section 12) | Needs a D1 amendment and a grammar that cannot yet express a keyed lazy list. (A) draws the guest's own recorded output instead, with no new authoring surface. |
| Keeping the process warm (a foreground service, a persistent process) | Not allowed for this purpose on modern Android, costs battery, and iOS does not allow it at all. |
| Queuing taps on the cached frame for the guest | Ids differ across runs; mapping a tap to "the same" widget in a new tree is guesswork, and a wrong delivery is worse than a dropped tap with feedback (I3). |
| Showing the frame under `parallel` before the lookup confirms | Breaks I1 when the lookup names another release (section 4, I1). Kept as an owner option per channel (question 2). |
| A snapshot of the guest's heap (QuickJS state) instead of the UI tree | QuickJS has no supported heap snapshot; the guest's state includes live coroutines and host handles. |
| Making the guest faster again | NF2 is spent; NF3 showed composition on touch-down is too slow to hide (section 1). |

## 9. Implementation plan

(A) and (B) are independent. Each PR has its tests and its gate; none merges without the review
this document asks for.

### (A) Cached first frame

| PR | Module(s) | What | Tests | Gate |
|---|---|---|---|---|
| A1 | `keliver-protocol` | `ChangeFolder` (batches → `SnapshotChangeList`), `FrameCacheTags` (reserved tags, lock test), the frame file format (header, body, HMAC field) | fold equality against a real host (widget tree and, through gate A's JVM harness, pixels) on random Add/Move/Remove/detach sequences; the three-way list (appendix); format round-trip and version refusal | apiCheck; 0 differing pixels |
| A2 | `keliver-compose` | `CachedFrame(policy, ready)`, `Modifier.notCached()`, behind `keliver.cachedFrame@1` | without the feature, batches byte-identical to today's; with it, only the marker elements added | apiCheck |
| A3 | `keliver-treehouse-host` | `FrameRecorder`, `FrameStore` (interface + Android/iOS files), the key, `session()`, clearing, caps, HMAC with a platform key | every key field (I1), every clearing path (I5), atomic write and process death (I10), corrupt files (section 6), `notCached` values absent from the bytes (I2) | apiCheck; unit tests on JVM, Android and the iOS simulator |
| A4 | `keliver-treehouse-host-composeui` | `CachedFrameContent` (inert, semantics, indication) and the hand-off controller | I3 and I4 host tests (gate A's JVM harness): no event reaches a guest; one semantics node; no empty or loading frame across the swap; timeout; crash | the host tests |
| A5 | templates (Android, iOS), docs | `KeliverScreen` / iOS screen wiring, the policy table, `session()` in `HostApp.kt` and `iOSApp.swift`, adopter docs | template tests (policy × frame table; backup exclusion), the reference app scaffolded from the repo | reference-app and ios-host workflows |
| A6 | sample, benchmarks | the three-way bench's `x=cachedframe`, the two marks | — | **A-1 to A-6 on the A12** (section 7); the owner reads them before (A) is on by default |

### (B) Pre-composed destinations

| PR | Module(s) | What | Tests | Gate |
|---|---|---|---|---|
| B0 | (precondition) | the retained container in the production widget system; host features declared by the templates (NF1's production step) | NF1's | NF1's |
| B1 | `keliver-nav` (spike, guest profile) | held-effect sub-composition and sliced (pausable) preparation; a tap during a slice | the guest profile: a tap during preparation; effects held | the spike answers "feasible with public Compose API"; else (B) stops here and goes back to the owner |
| B2 | `keliver-nav` | kinds, adoption, `Prepared` lifecycle, per-visit helpers, `assertInertWhenPrepared` | I6 and I7 unit tests; the profile's `nf5=rebind` | apiCheck |
| B3 | `keliver-nav`, templates | the idle policy and the two host signals (input active, memory pressure) | idle policy with a fake clock; host signal tests | apiCheck |
| B4 | sample, benchmarks | the three-way bench's `x=prepkind` | — | **B-1 to B-6 on the A12** and the simulator |

Each PR is reviewed on its own. (A) can ship without (B), and (B) without (A).

## 10. Open questions for the owner

1. **D1.** This design reads a recording of the guest's own output, keyed to the bundle that
   produced it and drawn only until that bundle takes over, as within D1 (no document format, no
   logic from storage), so it proposes no amendment. Do you agree, or do you want D1 amended to
   name it?
2. **`parallel` and the lookup.** Under the default start policy the frame shows only after the
   lookup confirms the cached release (I1). Keep that strict rule, or allow the frame before
   confirmation per channel, accepting a visible content switch when a new release is found?
3. **The integrity key.** Require the platform-kept HMAC key (Keystore / Keychain) even if it costs
   more than 20 ms at start on a low-end phone, or accept a key in private storage then?
4. **Taps on the cached frame.** The design drops them and shows a progress indication (I3). The
   alternative, holding them and delivering them to whatever is at that position after the swap,
   is rejected here as unsafe. Agree?

## Appendix: the Mac measurements

2026-11-24, on the project's Mac (shared with other jobs; each run held the machine's heavy-build
slot), from `2aa0e297f` plus this PR's measurement code. Kotlin 2.4.21, JDK 17. **Relative numbers
only:** the guest runs in Zipline's QuickJS on the JVM (the guest profile, [`GUEST_PROFILE.md`](GUEST_PROFILE.md)),
the host in a JIT-warmed JVM with Skia on the CPU. One run per configuration.

Engines:
- **QuickJS 2021-03-27**: Zipline 1.28.0 as shipped.
- **QuickJS 2026-06-04, `Long` as BigInt**: NF2's fork, from a scratch Maven publish
  (`1.28.0-q20260604`, as the A12 checkpoints used), through
  `keliver-compose-gatea-profile/engine/zipline-fork/use-fork.init.gradle`, with `-PprofileBigInt`.
  Checked: the run printed `QJS engine: QuickJS 2026-06-04`, and the linked standard library uses
  `BigInt`.

### The measurement code (prototype modules only, not production)

- `keliver-compose-gatea-profile/src/jsMain/.../Nf5Stack.kt`: the three-way app's screens (copied
  unchanged from `sample/threeway-guest`, as the profile always does) on a stand-in stack with three
  variants. All keep the list composed under the detail (as `retain=1`), so only the detail's path
  differs:
  - `fresh`: the detail is composed at the tap and disposed at back (today's open);
  - `rebind`: one detail slot keyed by its kind stays composed and hidden; the tap changes its item
    and shows it (the slot recomposes with new arguments);
  - `shown`: the same slot, but a timed `nf5prepare` step gives it the item before the tap; the tap
    only shows it (the floor).
- `GuestProfileMain.kt`: options `nf5=`, `heavy=1` (the 102-widget detail), the `nf5prepare` and
  `wire` commands; the back click skips the heavy detail's three action buttons.
- `QuickJsProfile.kt`: `nf5Runs`: per variant and weight, a fresh Zipline, the list, then 10 warm-up
  and 40 measured open/back pairs on rows 0–4 (`shown`: prepare, open, back). Wall time per step on
  the JVM's clock (the harness's `evaluate` included, about 0.05 ms) and the guest's own
  (`Date.now()`, 1 ms resolution, without the batch's `JSON.stringify`). Every step that gives the
  detail its item is checked to send that item's title, and every open and back to send something,
  so a wrong click cannot be timed as an open. Writes the list's first batch as a file.
- `keliver-compose-gatea-composeui/src/jvmTest/.../CachedFrameReplayBenchmark.kt`: folds the list's
  batches into a `SnapshotChangeList`, stores it as the bridge's JSON, and replays it into a fresh
  `HostProtocolAdapter` with gate A's Compose UI widgets; compares the pixels with a host that
  applied the batches as they came. Without `-Dkeliver.nf5.wire` (CI's run) it is a smoke test on a
  small list composed on the JVM (two batches, with a Remove).

### Commands

```
W=<this worktree>; OUT=<scratch dir>
# (B), QuickJS 2021 (also writes $OUT/nf5-stock-list-wire.json)
./gradlew -Pkeliver.guestProfile :keliver-compose-gatea-profile:profileQuickJs \
  "-PprofileArgs=nf5=fresh+rebind+shown,heavy=0+1,warmup=10,iterations=40,label=nf5-stock" -PprofileOut=$OUT
# (B), QuickJS 2026 with Long as BigInt
./gradlew --no-daemon -I keliver-compose-gatea-profile/engine/zipline-fork/use-fork.init.gradle \
  -PziplineForkRepo=<scratch fork repo, 1.28.0-q20260604> -PprofileBigInt -Pkeliver.guestProfile \
  :keliver-compose-gatea-profile:profileQuickJs \
  "-PprofileArgs=nf5=fresh+rebind+shown,heavy=0+1,warmup=10,iterations=40,label=nf5-bigint" -PprofileOut=$OUT
# cross-check of the "fresh" baseline: today's retained stack
./gradlew -Pkeliver.guestProfile :keliver-compose-gatea-profile:profileQuickJs \
  "-PprofileArgs=modes=kotlinx,retain=1,warmup=10,iterations=40,label=retain-stock" -PprofileOut=$OUT
# (A), the host's replay of the list
./gradlew :keliver-compose-gatea-composeui:jvmTest --tests '*CachedFrameReplayBenchmark' \
  -Dkeliver.nf5.wire=$OUT/nf5-stock-list-wire.json -Dkeliver.nf5.measured=40
```

### Table B: open and back with a hidden destination (wall ms, median / p90, n = 40)

| Engine | Detail | Variant | Open | Back | Prepare (`shown` only) | List's first composition (`first`, once) |
|---|---|---|---|---|---|---|
| 2021 | simple | fresh | **15.45** / 15.96 | 2.45 / 2.63 | — | 87.34 |
| 2021 | simple | rebind | **4.91** / 5.19 | 2.96 / 3.32 | — | 105.61 |
| 2021 | simple | shown | **2.95** / 3.23 | 3.05 / 3.29 | 3.51 / 3.83 | 101.87 |
| 2021 | heavy | fresh | **62.94** / 64.64 | 3.53 / 3.86 | — | 90.84 |
| 2021 | heavy | rebind | **8.42** / 8.65 | 5.52 / 5.67 | — | 150.61 |
| 2021 | heavy | shown | **5.46** / 5.59 | 5.53 / 5.69 | 6.70 / 6.93 | 151.24 |
| 2026 BigInt | simple | fresh | **7.68** / 8.00 | 1.44 / 1.61 | — | 45.51 |
| 2026 BigInt | simple | rebind | **2.97** / 3.02 | 1.84 / 1.88 | — | 52.42 |
| 2026 BigInt | simple | shown | **1.78** / 1.80 | 1.84 / 1.86 | 2.09 / 2.10 | 52.06 |
| 2026 BigInt | heavy | fresh | **30.77** / 31.38 | 2.46 / 2.61 | — | 44.87 |
| 2026 BigInt | heavy | rebind | **6.51** / 6.85 | 4.41 / 4.60 | — | 74.88 |
| 2026 BigInt | heavy | shown | **4.27** / 4.44 | 4.31 / 4.41 | 5.20 / 5.33 | 75.53 |

The guest's own time (1 ms resolution) agrees within 1 ms in every row (raw output below).
`first` includes, for `rebind` and `shown`, the hidden slot's first composition (the cost of
preparing it). Cross-check: today's retained stack (`retain=1`, QuickJS 2021) opened in 16.56 ms
and went back in 3.33, against `fresh`'s 15.45 and 2.45 (the stand-in stack is slightly lighter
than keliver-nav's).

Ratios, open: rebind against fresh **3.1×** (2021) and **2.6×** (2026) on the simple detail,
**7.5×** and **4.7×** on the heavy one. Rebind against the floor: +1.96 / +1.19 ms simple,
+2.96 / +2.24 ms heavy.

**The batches** (the same on both engines; counts of the last measured step):

| Step | Creates | Properties | Modifiers | Structural | Bytes |
|---|---|---|---|---|---|
| open, simple, fresh | 24 | 241 | 25 | 24 | 17,900 |
| open, simple, rebind | 0 | 8 | 3 | 0 | 902 |
| open, simple or heavy, shown | 0 | 0 | 2 | 0 | 154 |
| prepare, simple (shown) | 0 | 8 | 1 | 0 | 749 |
| open, heavy, fresh | 104 | 1,144 | 105 | 104 | 82,446 |
| open, heavy, rebind | 0 | 27 | 3 | 0 | 2,063 |
| prepare, heavy (shown) | 0 | 27 | 1 | 0 | 1,910 |
| back, fresh | 0 | 0 | 1 | 1 | 104 |
| back, rebind or shown | 0 | 1 | 2 | 0 | 234 |

The rebind's 8 (simple) and 27 (heavy) property changes are the texts that depend on the item, and
the "opened N×" line, which reads the app's navigation counter (so the hidden detail also
recomposes on back: the 1 property in back's batch).

### Table A: the cached first frame of the list

| Measure | Value |
|---|---|
| The guest's first batch for the list (one batch: the loading text, then the data, folded by the profile's settle) | 1,715 changes, 93,734 bytes |
| The folded snapshot | 1,695 changes, 148 widgets; 86,957 bytes of JSON; **7,919 bytes gzipped** |
| Replayed vs live host, pixels differing (one 360×640 dp frame, same position) | **0** |
| Replay, warm (n = 40 after 40): read + decode | 1.007 ms median, p90 1.135 |
| … apply through `HostProtocolAdapter` | 0.227, p90 0.251 |
| … the frame (recomposition, measure, layout, draw) | 2.959, p90 3.385 |
| … total | **4.244**, p90 4.717 |
| The first replay of the run (JIT barely warm) | 24.0 ms (decode 6.0, apply 1.9, frame 16.1) |
| Applying the guest's raw batch to a fresh host, once, cold JIT (for scale) | 19.8 ms |
| The guest's first composition of the list, before its batch is sent (QuickJS, wall, table B `fresh`) | 87.34 ms (2021), 45.51 ms (2026 BigInt) |
| Events the replayed widgets sent, dropped (the lazy list's viewport reports) | 162 in 80 replays (about 2 per replay) |
| Smoke run (two batches with a Remove, 33 widgets) | 0 differing pixels |

The comparison is across runtimes (QuickJS against the JVM). It says the order of magnitude: the
host's replay costs what the host's apply and frame cost anyway (and needs no guest), while the
guest's first composition is an order of magnitude more. On the phone, the host's side of the
list's first data frame is NF4's "composed to drawn", 223.5 ms; a cached frame pays that plus its
decode.

**Not measured here:** anything on a phone; the HMAC's cost; a frame written by a real host's
recorder (the fold here ran on the profile's batch, not on `ViewContentCodeBinding`'s); the
hand-off; memory.

### Raw output

```
QuickJS 2021-03-27 (nf5-stock)
QJS NF5 variant=fresh heavy=0 back=2.45/p90 2.63 (guest 2.00/p90 3.00; creates 0 bytes 104) open=15.45/p90 15.96 (guest 15.00/p90 16.00; creates 24 bytes 17900) first=87.34
QJS NF5 variant=rebind heavy=0 back=2.96/p90 3.32 (guest 3.00/p90 3.00; creates 0 bytes 234) open=4.91/p90 5.19 (guest 5.00/p90 5.00; creates 0 bytes 902) first=105.61
QJS NF5 variant=shown heavy=0 back=3.05/p90 3.29 (guest 3.00/p90 3.00; creates 0 bytes 234) open=2.95/p90 3.23 (guest 3.00/p90 3.00; creates 0 bytes 154) prepare=3.51/p90 3.83 (guest 3.00/p90 4.00; creates 0 bytes 749) first=101.87
QJS NF5 variant=fresh heavy=1 back=3.53/p90 3.86 (guest 3.00/p90 4.00; creates 0 bytes 104) open=62.94/p90 64.64 (guest 61.50/p90 63.00; creates 104 bytes 82446) first=90.84
QJS NF5 variant=rebind heavy=1 back=5.52/p90 5.67 (guest 5.50/p90 6.00; creates 0 bytes 234) open=8.42/p90 8.65 (guest 8.00/p90 9.00; creates 0 bytes 2063) first=150.61
QJS NF5 variant=shown heavy=1 back=5.53/p90 5.69 (guest 6.00/p90 6.00; creates 0 bytes 234) open=5.46/p90 5.59 (guest 5.00/p90 6.00; creates 0 bytes 154) prepare=6.70/p90 6.93 (guest 7.00/p90 7.00; creates 0 bytes 1910) first=151.24

QuickJS 2026-06-04, Long as BigInt (nf5-bigint)
QJS NF5 variant=fresh heavy=0 back=1.44/p90 1.61 (guest 1.00/p90 2.00; creates 0 bytes 104) open=7.68/p90 8.00 (guest 8.00/p90 8.00; creates 24 bytes 17900) first=45.51
QJS NF5 variant=rebind heavy=0 back=1.84/p90 1.88 (guest 2.00/p90 2.00; creates 0 bytes 234) open=2.97/p90 3.02 (guest 3.00/p90 3.00; creates 0 bytes 902) first=52.42
QJS NF5 variant=shown heavy=0 back=1.84/p90 1.86 (guest 2.00/p90 2.00; creates 0 bytes 234) open=1.78/p90 1.80 (guest 2.00/p90 2.00; creates 0 bytes 154) prepare=2.09/p90 2.10 (guest 2.00/p90 2.00; creates 0 bytes 749) first=52.06
QJS NF5 variant=fresh heavy=1 back=2.46/p90 2.61 (guest 3.00/p90 3.00; creates 0 bytes 104) open=30.77/p90 31.38 (guest 30.00/p90 30.00; creates 104 bytes 82446) first=44.87
QJS NF5 variant=rebind heavy=1 back=4.41/p90 4.60 (guest 4.00/p90 5.00; creates 0 bytes 234) open=6.51/p90 6.85 (guest 7.00/p90 7.00; creates 0 bytes 2063) first=74.88
QJS NF5 variant=shown heavy=1 back=4.31/p90 4.41 (guest 4.00/p90 5.00; creates 0 bytes 234) open=4.27/p90 4.44 (guest 4.00/p90 5.00; creates 0 bytes 154) prepare=5.20/p90 5.33 (guest 5.00/p90 6.00; creates 0 bytes 1910) first=75.53

retain=1 cross-check (QuickJS 2021)
QJS mode=kotlinx gc=default open=16.56 back=3.33 backMin=3.21 reload=- first=88.06 load=24.53 heapBack=7679K->7535K threshold=256K explicitGc=1.98 hash=-384791821

CachedFrameReplayBenchmark (build/nf5-cached-frame.txt)
input: nf5-stock-list-wire.json; 1 batch(es), 1715 changes, 93734 bytes
folded snapshot: 1695 changes (148 widgets), 86957 bytes JSON, 7919 bytes gzipped
live apply of the guest's batches (once, cold JIT): 19.839 ms
first replay (JIT barely warm): read+decode 6.047, apply 1.869, frame 16.089, total 24.004 ms
replay, n=40 after 40 warm-up: read+decode median 1.007 p90 1.135; apply median 0.227 p90 0.251; frame median 2.959 p90 3.385; total median 4.244 p90 4.717
events from the replayed widgets, dropped: 162 (over 80 replays)
pixels differing, replayed vs live: 0
```

A first replay comparison placed the live and replayed trees side by side (360 dp apart) and found
8 pixels differing by 1–3 of 255 on the status chips' rounded edges (anti-aliasing at another x
offset); drawn at the same position, they are identical. A first (B) run was discarded: the hidden
slot's buttons were recorded before the list's rows, so "open" clicked the wrong widget. The driver
now composes the slot after the rows and checks every open's batch for the opened item's title.
