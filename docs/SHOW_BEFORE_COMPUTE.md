# NF5: show before compute (design)

Status: **design, revision 2, for review. No production code.** Written 2026-11-24 for WS7 of
[`PLAN_2026-11.md`](PLAN_2026-11.md). The owner chose this step on 2026-11-24, on one condition:
"it has to be executed perfectly". Revision 2 answers the independent design review of revision 1
(verdict: needs rework). [Appendix A](#appendix-a-review-response) answers every finding, one line
each.

NF5 has two halves:
- **(A) Cached first frame.** On a cold start the host draws, from storage, the first screen as the
  guest last drew it, while the guest's code loads. Then the guest takes over.
- **(B) Pre-composed destinations.** A likely destination is composed ahead of the tap as a shell
  with no item's data, hidden and inert. On the tap the host shows the shell at once, and the guest
  fills in the item.

## Contents

0. [Summary](#0-summary)
1. [The problem and the targets](#1-the-problem-and-the-targets)
2. [(A) Cached first frame](#2-a-cached-first-frame)
3. [(B) Pre-composed destinations](#3-b-pre-composed-destinations)
4. [Correctness invariants](#4-correctness-invariants)
5. [Cache key, invalidation and storage format](#5-cache-key-invalidation-and-storage-format)
6. [Failure handling](#6-failure-handling)
7. [Measurement plan and gates](#7-measurement-plan-and-gates)
8. [Alternatives considered and rejected](#8-alternatives-considered-and-rejected)
9. [Implementation plan](#9-implementation-plan)
10. [Proposed D1 clarification](#10-proposed-d1-clarification)
11. [Decisions for the owner](#11-decisions-for-the-owner)
- [Appendix A: review response](#appendix-a-review-response)
- [Appendix B: the A12 estimate for (B)](#appendix-b-the-a12-estimate-for-b)
- [Appendix C: the Mac measurements](#appendix-c-the-mac-measurements)

## 0. Summary

**(A) Cached first frame: go to the A12 gate.** On the Mac, drawing the stored list costs the host
about 24 ms the first time (4 ms when warm). The guest's first composition of the same list costs
45–88 ms before anything is sent. The stored list is 7.9 KB gzipped and draws pixel-identical to
the live host. Only the A12 can say whether this lands within native + 0.4 s (section 7).

**(B) Pre-composed destinations: go to the B1 spike, with a lower expectation.** Changing a hidden
detail's arguments is 2.6–7.5× cheaper than composing it fresh on the Mac. But on the A12 every guest
step pays a large fixed cost. Even a detail that is already composed with the right item took
80–85 ms (simple) and 131.5–140.5 ms (heavy) from the click to its first draw (series H).

**What (B) alone reaches on the A12 (inferred, appendix B):** about **145–170 ms** for the simple
screen and **225–275 ms** for the heavy one, tap to frame end. Today's best is 217.5 and 548.
The targets are about 100 and 150. **(B) alone does not reach them.** It roughly halves the heavy
open and takes a quarter to a third off the simple one.

That is why (B) is now a shell + data design. The prepared entry is a shell with no item's data.
On the tap the host shows that shell itself, without a guest round trip, the way the retained back
already shows the list. On the A12 the retained back's first draw comes 13–17 ms after the press.
The item's data then arrives at the times above. Whether a shell first, with the data about
150–300 ms later, counts as "open" for the bar is decision 7 in section 11.

**Items that differ in structure** cost (B) more. On the Mac, rebinding the heavy detail to an item
with other sections cost 16.0 ms, against 8.6 for the same structure and 46.3 fresh (appendix C,
table B2). On the A12 that is about 305–315 ms for a heavy open (appendix B).

The owner's decisions are in [section 11](#11-decisions-for-the-owner).

## 1. The problem and the targets

The bar (PLAN_2026-11.md, section 2, point 3) asks the one over-the-air lane to feel native on a
Galaxy A12. Two targets are still missed by a wide margin. The best numbers come from the BigInt
engine fork (NF2), on the A12, on 2026-11-22 ([`THREE_WAY_BENCHMARK.md`](THREE_WAY_BENCHMARK.md),
"Flutter on the A12, 2026-11-22"; "A12 checkpoint 2", series G and H). All are frame end, from the
tap.

| Target (PLAN section 2) | Native | Flutter | Keliver, best | Gap |
|---|---|---|---|---|
| Open a screen, simple: about 100 ms | 28 | 30.5 | 217.5 | about 2× |
| Open a heavy screen (about 100 widgets): 150 ms or less | 59 | 44.5 | 548 | about 3.7× |
| Cold start to the list's data: within 0.4 s of native | 728.5 | 633.5 | 1,543.5 | native + 0.81 s (on the screen) to + 1.25 s (series G) |

Why the steps so far do not close it:
- **The faster engine is spent.** NF2 roughly halved the guest's work. What is left is still
  composition in QuickJS.
- **Composing on touch-down did not help** (NF3, series F and H). Composing takes longer than a
  press lasts, so the click waits for it.
- **Cold start is the list's first composition**, about 1 s of it on 1.28.0.

NF5's idea: do not make the guest faster. Show UI that is already composed, and let the guest's
work happen behind it.

## 2. (A) Cached first frame

### 2.1 End to end

```
cold start
  host: chooses the start (StartChoice.mode)       -> decides whether a frame may show, and when (2.8)
  host: KeliverScreen composes; FrameStore.read(key) -> hit and valid: draw the frame (inert, 2.6)
                                                    -> otherwise: today's loading()
  guest: code loads -> codeLoadSuccess              -> the loaded release must equal the frame's (2.8)
  guest: first composition; batches applied beneath, unseen
  guest: the marked screen's `ready` goes false -> true
        host: swaps in one frame (guest shown, frame gone)
        host: writes the marked subtree for the next start, if the recording rule allows (2.3)
  the frame is gone at the latest FRAME_LIMIT after its first draw (2.7)
```

### 2.2 Guest

- **Opt-in, per screen.** A screen that may be shown from storage says so at its root:

  ```kotlin
  CachedFrame(
    policy = FrameCachePolicy(content = FrameContent.Shell, maxAge = 12.hours),
    ready = page != null,      // the guest's "first meaningful" signal (I4)
  ) {
    OrdersScreen(page)
  }
  ```

  `CachedFrame` is new, in `keliver-compose`, next to `LocalHostFeatures` (`HostFeatures.kt`). It
  emits only when the host declares `keliver.cachedFrame@1` (I8). It adds one marker modifier to
  the screen's root node, carrying the policy and `ready`.
- **Two content levels.** The author must choose; there is no default.
  - `FrameContent.Shell`: an allowlist. Every text and URL value in the subtree is replaced by a
    placeholder, unless its node is marked `Modifier.cached()` (a title, a label, a section header).
  - `FrameContent.Whole`: every value is kept, except subtrees marked `Modifier.notCached()`.
- **Values that are never stored**, in either level: text-field values, and any property the schema
  marks `@NeverCached` (2.4).
- **Session changes from the guest.** An app whose login runs in the guest (D13) tells the host
  through a host feature, `keliver.frameScope@1`: `FrameScope.sessionChanged(id)` and
  `FrameScope.variantChanged(id)` (an app's own theme or language, held in the guest). The host
  handles both as `KeliverHost.session()` does (2.9).
- **Protocol.** The marker modifiers use tags in a reserved framework range, defined in
  `keliver-protocol` (`FrameCacheTags.kt`) and covered by a lock test, as schema tags are.
  `HostProtocolAdapter` consumes these tags before widget dispatch, so they never reach a widget or
  the mismatch handler. That is the one change on the content path.
- **No other guest change.** The guest's composition, ids and batches are as today.

### 2.3 Which tree is recorded, and when

This is the rule that makes I1 and I11 hold. The recorder is the only writer.

**What is recorded:**
- Only the subtree under the node that carries the `CachedFrame` marker. Not the whole content.
- Inside it, only what is shown. Every hidden child of a retained container (NF1's retained entries
  below the top) and every prepared entry of (B) is pruned. The pruning uses the host's explicit
  entry state (section 3.6), not the children's order.
- Placeholders and dropped values as section 2.2 says, applied by the fold before anything reaches
  the disk.

**When it is recorded: once, at the `ready` edge, and only in a clean state.** The recorder writes
when all of these hold:
1. The batch it just applied turned the marker's `ready` from false to true. Only the first such
   edge for this content counts. Later edges (a refresh) do not write.
2. The marked node is visible: the content is not hidden, and the node is not inside a hidden,
   retained or prepared entry.
3. Every retained container in the marked subtree shows its first entry (the user has not
   navigated inside it).
4. Since the content was bound, no input has reached the marked subtree: no touch, scroll, focus or
   text input. The host's event sink tracks this.
5. Every lazy list in the subtree is at its first item, at offset 0, and every pager at its first
   page (the host's own state, so a position the guest restored also counts).
6. Every widget in the subtree is replayable (2.4). A dialog, sheet or snackbar in the subtree
   blocks the write.

Otherwise nothing is written, and the previous file stays. **There is no write at `ON_STOP`** or at
any other time. The stored frame is therefore always the screen as first drawn with its data,
before the user touched it.

### 2.4 Which widgets can be replayed

The fold works from a list generated by codegen from the schema. Nothing is replayable by default.

- **A schema annotation** marks a widget as replayable: `@Replayable`. `@NeverCached` on a property
  means its value is never stored.
- **Codegen** (`keliver-tooling-codegen`, the step that already writes the protocol code) writes
  `<Schema>FrameRules`: the replayable widget tags, and for each widget and modifier the tags of its
  text- and URL-bearing properties (any property whose type is or contains a `String`). The fold
  uses the second list for `Shell`.
- **keliver-material** marks every widget replayable except nine
  ([`keliver-material-schema.api`](../keliver-material-schema/api/keliver-material-schema.api)):
  - window or popup: `AlertDialog`, `Dialog`, `BottomSheet`, `DropdownMenu`, `Snackbar`, `Tooltip`.
    A dialog opens its own window, which the frame's container cannot make inert, so it must never
    be replayed;
  - live input: `TextField`, `OutlinedTextField`, `TextInput`.

  The review named six; a check of the schema found `DropdownMenu`, `Tooltip` and
  `OutlinedTextField` as well. `HorizontalPager` and `VerticalPager` are replayable only at their
  first page, as lazy lists are only at their first item (2.3, condition 5).
- **Text fields.** A screen with a text field (a search box) cannot be cached today. If owners want
  it, `TextField` becomes replayable with its value `@NeverCached`, drawn empty. That is a later,
  separate change.
- **Images.** A replayed image is drawn from the image loader's disk cache only, never the network.
  A miss draws the widget's placeholder. In `Shell`, image URLs are dropped unless `cached()`.
- **Keys and descriptions.** In `Shell`, the strings inside modifiers are replaced as well:
  content descriptions, semantics labels, test tags. Lazy-list item keys, where the protocol carries
  them, are replaced by their position in both levels.

### 2.5 Host (shared by Android and iOS)

Both production hosts draw with Compose UI (`KeliverScreen.kt` on Android, `MainViewController.kt`
on iOS), so most of the host side is common code.

| Piece | Where | What |
|---|---|---|
| `FrameRules` | generated per schema (2.4) | the replayable widgets and the text-bearing properties |
| `ChangeFolder` | new, `keliver-treehouse-host` (host only) | folds batches (Create, Property, Modifier, Add, Move, Remove) into the current tree of the marked subtree; prunes hidden entries; applies `Shell`, `notCached`, `@NeverCached` and position keys; emits a `SnapshotChangeList` (`keliver-protocol/.../SnapshotChangeList.kt`) |
| `FrameRecorder` | new, `keliver-treehouse-host` | wraps the content's change sink (as `KeliverTiming.firstFrame` does), feeds `ChangeFolder`, tracks input and list position, and writes by the rule in 2.3 |
| `FrameStore` | new, `keliver-treehouse-host`; a file implementation per platform | `read(key)`, `write(key, frame)`, `clear(reason)`, `clear(route)`. Atomic writes, size caps, checksum (I5). The file format lives here, on the host, not in `keliver-protocol` |
| `CachedFrameContent` | new, `keliver-treehouse-host-composeui` | decodes a stored frame and applies it through a fresh `HostProtocolAdapter` with the app's widget system and a cache-only image loader. Its event sink drops events. Its mismatch handler does not throw: any unknown tag discards the frame |
| `FrameHandOff` | new, `keliver-treehouse-host-composeui` | the swap state: `ready`, `HANDOFF_LIMIT`, `FRAME_LIMIT`, a deferred update (2.7) |
| `TreehouseContent` | `keliver-treehouse-host-composeui`, one new parameter | `backEnabled`: while false, the content's back callbacks are registered disabled (2.6) |

`TreehouseAppContent`'s first batch still clears `view.children`. The cached frame never lives in
`view.children`, so that clear does not touch it.

### 2.6 The frame on screen: inert, with back and an indicator

- **Inert.** The frame's container consumes taps and scrolls. It is unfocusable, so no keyboard
  opens. Its semantics are one node, "Loading", polite live region, no actions. (I3)
- **Indicator.** A tap or scroll shows a progress indicator at once. It is a slot on `KeliverScreen`
  (`frameIndicator`), with a thin indeterminate bar as the default (decision 4).
- **Back, Android.** Today the hidden content's back callbacks register with the activity at once
  (`KeliverScreen.kt:214–216` says so). While the frame is up, `KeliverScreen` passes
  `backEnabled = false` to the hidden content, so its callbacks are registered disabled. A back
  press then does what it does on today's loading screen: it goes to the activity. The same gate
  also closes today's short update-swap window.
- **Back, iOS.** The iOS Treehouse back dispatcher is a no-op (`TreehouseContent.ios.kt:25`): a
  guest never receives back on iOS. Back is the scene's own (an edge swipe in the app's navigation
  controller), as on the loading screen. The I3 test checks this on the simulator.

### 2.7 Hand-off and time limits

The guest's content is composed beneath, hidden, from its first batch. The frame stays on top until
one of these happens, whichever is first:

| Event | Result |
|---|---|
| The marker's `ready` becomes true in an applied batch | swap in that frame: guest shown, frame gone |
| `HANDOFF_LIMIT` (proposed 3 s) after the guest's first batch | swap to the guest's tree as it is; `cachedFrame.handoffTimeout` |
| `FRAME_LIMIT` (proposed 5 s) after the frame's first draw | the frame goes: the guest's tree if it has one, else today's `loading()`; `cachedFrame.frameTimeout` |
| The guest crashes, or the code fails to load | the frame goes; today's `unavailable` |
| The guest's first batch has no marker (the release dropped the opt-in) | swap at once; the frame is deleted |
| `codeLoadSuccess` reports another release than the frame's (2.8) | the frame goes at once; `loading()` until the guest's first batch; the frame is deleted |

So the frame is never on screen for longer than `FRAME_LIMIT`. A stuck download or a hung engine
ends in today's loading screen, not in dead content.

**An update that arrives before the hand-off.** Under `cache-first` or `parallel`, a newer release
can load in a new app while the frame is still up. Then there are three layers: the frame, the old
app and the new one. Rule: the update swap is deferred until the frame has handed off to the old
app. Then today's update swap runs (`KeliverScreen.kt:130–159`). The frame never hands off to an
app whose release it was not recorded from. `FRAME_LIMIT` still bounds the whole.

**How the swap looks.** No blank frame: both trees are laid out before the switch. No crossfade, as
a native refresh shows new values in place. Both draw the list from its top (the recording rule).
Changed data appears in place. In `Shell`, a placeholder has the width of the text it replaced,
rounded up (2.2, 5.2), so the real text may reflow by a few characters. That small reflow is
accepted in I4.

### 2.8 Which release the frame belongs to

A frame records the **release identity of the app that produced it**: its sequence, its manifest
sha256, and a digest of its modules' sha256s. The writer takes them from its own app's
`codeLoadSuccess` (`KeliverHost.kt:2081`), not from preferences.

**Before the code loads**, the host decides on **this start's mode** (`StartChoice.mode`,
`StartPolicy.kt:125`), not on the configured policy. `chooseStart` turns `cache-first` into
network-first after a crash rollback, when a newer release was seen, or before the channel's first
lookup (`StartPolicy.kt:132–149`).

| This start's mode | When the frame may show |
|---|---|
| `cache-first` | at once, if the frame's identity equals the cached release's recorded identity |
| `parallel` | after the lookup: on `SHOW_CACHED` with the cached release confirmed, or after a failed lookup (the cached release then shows). Never on `LOAD_NAMED` |
| `network-first` (configured, or chosen for one of the reasons above) | only after the lookup names a manifest whose sha256 equals the frame's; otherwise never |

No identity recorded means no frame.

**When the code has loaded**, `codeLoadSuccess` compares the loaded sequence and module digest with
the frame's. On a mismatch the frame goes at once (2.7). This catches the one case preferences
cannot: Zipline runs its pinned manifest, and the preference write after the previous load was lost.

**The record itself** is written with `commit()`, not `apply()` (today `KeliverHost.kt:1571–1573`
uses `apply()`, while the crash ledger uses `commit()`). This change is part of (A)'s first host PR
(H0).

### 2.9 Session: fail closed

- **Nothing is stored or drawn until the host knows the session.** The app either calls
  `KeliverHost.session(id: String?)` at least once (null: signed out), or declares in its host
  configuration that it has no accounts (`cachedFrames = CachedFrames(accounts = NONE)`). Until one
  of the two, the store refuses reads and writes.
- **`session()` deletes synchronously.** When the id differs from the recorded one, every frame of
  every `cacheName` is deleted before the call returns, and the new record is written with
  `commit()`. It also drops prefetched code as `discardPrefetched()` does (decision 3).
- **From the guest.** `FrameScope.sessionChanged(id)` (2.2) runs the same code on the host. A frame
  written while a session change is in flight is checked after its rename: if the session record
  changed, the file is deleted.
- **What is stored.** A hash of the session id with a per-install key from private storage, never
  the id.

### 2.10 Navigation

- Only the **first screen of a launch** is drawn from the cache: the route the activity or scene
  opens with (`KeliverHost.launch`, `KeliverRoute.kt`).
- The route and its launch arguments are part of the key. A deep link to another order never shows
  the last order's frame.
- Apps that change data on native screens (D12) call `KeliverHost.clearCachedFrames(route)`.

### 2.11 Remote off switch

The bundle index can turn cached frames off per channel: `cachedFrames: "off"`. The host records it
as it records `startOverride` (`KeliverHost.kt:817`). With it, the host deletes the channel's frames
and draws none. Under `parallel` and `network-first` it applies at this start, after the lookup.
Under `cache-first` it applies from the next start.

### 2.12 What (A) does not change

- The guest's code, its load, its start policy, its checks (signature, floor, compatibility).
- How the guest's tree is drawn once it has taken over.
- Screens that do not opt in, hosts without the feature, and bundles without the opt-in.
- D1 and D2: see section 10.

## 3. (B) Pre-composed destinations

### 3.1 What the numbers say

On the Mac, changing a hidden detail's arguments is clearly cheaper than composing it fresh: 3.1×
(simple) and 7.5× (heavy) on QuickJS 2021; 2.6× and 4.7× with BigInt. The batches are 20–40×
smaller.

On the A12 that is not enough. A guest step has a large fixed cost there. In series H, a detail
already composed with the right item still took 57.5 ms from the click to composed, and 80–85 ms to
drawn. On the Mac the same step takes 1.78 ms: a ratio of about 30 for tiny steps, against about 12
for long compositions. Adding the tap before the click and the frame after the draw, (B) alone lands
at about 145–170 ms (simple) and 225–275 ms (heavy). Appendix B shows the arithmetic.

So (B) has two parts, both part of the design:
1. **Shell.** A prepared or parked entry holds a shell: the destination's layout with no item's
   data. Binding it to an item fills the data.
2. **Host reveal.** On the tap, the host shows the shell itself, before the guest runs. That costs
   what the retained back costs: its first draw came 13–17 ms after the press on the A12. The guest's
   bind then fills the data.

### 3.2 End to end

```
list shown with its data (`ready`), guest idle: no input, scroll or guest frame for 300 ms
  guest (keliver-nav): composes one entry per declared kind, in slices, as a SHELL (route = the kind's
                       `unbound` value). Entry state Prepared. Its effects are held.
  host: the entry sits in the retained container: Prepared, never laid out, drawn or a back target
tap on row N (the row carries Modifier.revealsPrepared(DetailKind))
  host: in the click handler, shows the Prepared shell of that kind (state Revealed), then sends the
        click to the guest
  guest: navigate(Detail(N)) binds the entry: route = Detail(N); its effects are released;
         the entry state becomes Shown
  host: applies the bind batch: the item's data appears in the shell
back
  guest: pops the entry and PARKS it: route = unbound, which cancels every effect keyed on the
         arguments. If anything started during the visit is still running, the entry is disposed
         instead (3.3).
```

A kind that is not revealable (3.4) skips the host reveal. Then the click goes to the guest, which
binds the entry and shows it, with the item's data, in one batch.

The host lays the shell out in the reveal's frame, as it lays the retained list out on a retained
back (only the shown entry is measured, `Retained.kt:193–211`).

### 3.3 The effect lifecycle

The goal: an entry does nothing outside its composition except while it is shown for a visit. A
visit runs from a bind to the next park.

| Moment | What runs |
|---|---|
| Prepared (shell, never shown) | composition only. Effects are held: their coroutines do not start |
| Bind (the tap) | held effects are released; effects keyed on the arguments start for the new item |
| Shown, then hidden by a screen pushed over it | as today with retention (`LaunchedWhileVisible` stops, others keep running) |
| Pop: park | route = `unbound`. Effects keyed on the arguments are cancelled by the key change. New coroutines are held again |
| Park check | the entry's effect scope must have no child job left from the visit. If one is left (an effect keyed on `Unit` that collects a flow, say), the entry is **disposed**, not parked, and a debug build logs which kind leaked |

**Rules for a rebindable destination's author:**
- Work that needs the item runs only while the item is there: `if (id != null) LaunchedEffect(id)
  { … }` (example in 3.4). It leaves the composition at the park, which cancels it. An effect that
  outlives the visit (keyed on `Unit`, collecting a flow) makes the kind fall back to disposal at
  every pop: it still works, without the gain.
- Loads go there too. They start at the bind, not when the entry is prepared. That is the same time
  as a fresh open's load, so no load starts later than with a fresh open.
- Remembered state that depends on the item uses `remember(route)` or the per-visit helpers.

**Show counts are per visit.** `LocalKeliverNavEntryShows` resets to 0 at each bind. So:
- `LaunchedOnceShown` runs once per visit: once for item 7, once for item 8. "Screen viewed"
  analytics stay right.
- `LaunchedOnReturn` runs on a show after the first **within the same visit** (back to item 8 from
  a screen above it). It never runs on a new visit's first show.

**Docs rewrite.** Today's retention docs tell authors to load in `LaunchedEffect` so that a prepared
screen loads on touch-down (`KeliverNavRetention.kt:156–158, 189–190`). With held effects that
advice changes: loads start at the show. The KDoc of `LaunchedWhileVisible`, `LaunchedOnceShown`,
`LaunchedOnReturn` and `KeliverNavPrepare`, and `docs/SCREEN_ARCHITECTURE.md`, are rewritten in the
same PR (B2). This also changes NF3's prepared entries, which are off by default (press off,
2026-11-22); see decision 5.

### 3.4 Guest (keliver-nav)

| Change | Where | Today |
|---|---|---|
| A route's **kind**, separate from its arguments: `KeliverNavKind(id, unbound, revealable)`, given to the controller by `KeliverNavKinds<R>(kindOf: (R) -> KeliverNavKind?)`. Null: not rebindable | `KeliverNavController.kt` | full route equality (`prepared?.route == route`) |
| `NavEntry.route` backed by snapshot state; `navigate(route)` binds the parked or prepared entry of the same kind | `KeliverNavController.kt` | the route is a `val` |
| At most one prepared entry per kind, at most `maxPreparedKinds` (default 2) | `KeliverNavController.kt` | one prepared entry, dropped at any stack change |
| Pop parks a rebindable entry (3.3), or disposes it | `KeliverNavController.kt` (`pop`, `popWithResult`) | popped entries are disposed |
| Entry lifecycle `Prepared`, beside `Visible` and `Hidden` | `KeliverNavRetention.kt:122` | a prepared entry is `Hidden`, and its effects run |
| Prepared and parked entries composed in a sub-composition whose effect dispatcher holds coroutines; preparation in slices that yield between frames; a tap cancels a slice | new, `KeliverNavPreparedComposition.kt` | composed in one frame, effects run |
| Per-visit show counts; per-visit helpers `rememberPerVisit {}`, `rememberSaveablePerVisit {}`, `rememberPerVisitLazyListState()` | `KeliverNavRetention.kt`, new `KeliverNavPerVisit.kt` | counts per entry |
| Idle policy: prepare only after the first screen is `ready` (or, without (A), after its first data batch and 300 ms quiet), with no input, scroll or guest frame for 300 ms; drop prepared and parked entries on memory pressure and in the background | new, `KeliverNavIdle.kt`, fed by host signals | NF3 prepares on touch-down |
| `Modifier.revealsPrepared(kind)` for rows whose click always opens that kind | new | none |

What a screen author writes:

```kotlin
sealed interface Route {
  data object Orders : Route
  data class Detail(val id: Long) : Route
  data object DetailShell : Route            // the kind's `unbound` value
}
val DetailKind = KeliverNavKind(id = "detail", unbound = Route.DetailShell, revealable = true)

KeliverNavHost(controller, kinds = KeliverNavKinds { r -> if (r is Route.Detail || r == Route.DetailShell) DetailKind else null }) { route ->
  when (route) {
    Route.Orders -> OrdersScreen(onOpen = { controller.navigate(Route.Detail(it)) })
    // ONE call site for both, so binding keeps the composition (two branches would compose anew)
    is Route.Detail, Route.DetailShell -> DetailScreen(id = (route as? Route.Detail)?.id)
  }
}

@Composable fun DetailScreen(id: Long?) {
  // Work that needs the item runs only while it is there: it starts at the bind, stops at the park.
  if (id != null) LaunchedEffect(id) { load(id) }
  …                                          // id == null: the layout with placeholders
}
// On the list, once its data is there:
KeliverNavPrepare(controller, DetailKind)
```

**Generated tests per declared kind** (the scaffolder writes them; CI runs them):
- `assertInertWhenPrepared(kind)`: the shell, composed Prepared against capabilities that fail on
  any call (HTTP, bridges, storage, analytics), runs frames; nothing may be called.
- `assertInertAfterVisit(kind)`: bind to an item, run its effects, pop; after the park no coroutine
  from the visit is alive and no call is made in the following frames.
- `assertRebindEquivalent(kind)`: a fresh `Detail(N)` and `Detail(M)` rebound to `N` fold (A's
  `ChangeFolder`) to the same tree, ids aside.
- `assertShellHasNoData(kind)` (revealable kinds only): the shell after a visit to item 7 and after
  a visit to item 8 fold to the same tree, and it contains none of the items' strings.

### 3.5 Host

| Change | Where |
|---|---|
| The retained container in the production widget system (NF1's production step, B0) | keliver-material's host widgets, templates |
| **An explicit entry state** on the container's entries: `Shown`, `Retained`, `Prepared`, `Revealed`. The host never lays out or draws a `Prepared` entry, and never makes it a back target. `goBack` skips it. Today the container protects hidden entries by their order alone (`shownIndex` is `lastIndex`, `goBack` takes `index - 1`, `Retained.kt:130–165`) | the container's schema (a protocol change, covered by the schema lock) and its renderer |
| **The host reveal.** On a click on a node with `revealsPrepared(kind)`, the container shows that kind's `Prepared` entry (state `Revealed`) in the same frame, then sends the click. If the guest's next batch does not bind it, or back is pressed first, the host hides it again | the container's renderer |
| Two host signals with events to the guest: "user input active" (a touch is down or a fling runs) and "memory pressure" (Android `onTrimMemory` at `RUNNING_LOW` or above; iOS `didReceiveMemoryWarning`) | templates, host features |

Android and iOS share all of it (both draw with Compose UI).

### 3.6 What (B) changes and does not change

It changes:
- the retained container's schema (one entry-state property) and its renderer;
- two host features with events, and one modifier (`revealsPrepared`);
- keliver-nav's behaviour for prepared entries (held effects) and, for apps that declare kinds,
  popped entries (parked).

It does not change:
- destinations that are not declared rebindable, and every route equality today's code relies on;
- the retained back stack (NF1), the press signal (NF3, off);
- the bundle format.

### 3.7 Risks, and what the B1 spike must settle

- **Pausable composition in the guest's runtime.** Is it public API in the Compose runtime the
  guest uses? If not, slices are not possible, and a long preparation can delay a tap.
- **Held effects and framework effects.** A held dispatcher must not hold the framework's own
  effects that a hidden layout needs (lazy layouts' prefetch and measurement). Which effects go
  through it?
- **Composition locals** must pass into the sub-composition (`currentCompositionLocalContext`).
- **One Redwood applier fed by two recomposers** (the main one and the sub-composition's). Is
  that safe, and in which order are their changes sent?
- **A slice budget in A12 milliseconds**, stated before the spike: at most 8 ms of guest work per
  slice on the A12 (about 0.3–0.7 ms on the Mac at ratios of 12–30).
- **A hidden entry recomposes when what it reads changes.** The three-way detail reads the app's
  navigation counter, so its hidden copy recomposes at every back (+0.5 to +2 ms on the Mac). Shells
  should read only their arguments; a debug counter reports recompositions of `Prepared` entries.
- **Structure that differs per item** costs more (table B2). The shell should hold the common
  structure; item-specific parts appear at the bind.

## 4. Correctness invariants

Each invariant has an enforcement and a test. "Unit" is a JVM or common test in the module named;
"host test" a JVM Compose UI test like `RetainedHostBackTest`; "device" the A12, the iPhone or the
iOS simulator.

### I1. Never wrong content

A cached frame is drawn only if every field of its key equals the current one (section 5), and only
under the start rule in 2.8. The release it hands off to must be the release it was recorded from,
checked again at `codeLoadSuccess`. Only the shown, marked subtree is ever recorded (2.3), so a
frame cannot hold another screen.

**Enforced:** the key check in `FrameStore.read`, before decoding; the start table in one function
beside `afterParallelLookup`; the `codeLoadSuccess` check; the recording rule; a mismatch handler
that discards the frame on any unknown tag.
**Tested:** one unit test per key field (a frame differing in that field only is not read); the
start table as a table test over every `StartChoice.mode` reason × `ParallelStep` × frame present;
a stored frame with an unknown tag is discarded; a host test where the lookup names another release
and no frame is ever composed; a host test where `codeLoadSuccess` reports another module digest and
the frame goes in that frame.

### I2. Data freshness is the guest's choice

- **Off by default.** A screen without `CachedFrame` is never stored.
- **Two levels, no default.** `Shell` (an allowlist: only `cached()` texts survive) and `Whole`.
  For money and personal data: `Shell`. The docs show it.
- **Maximum age.** `maxAge` is required; the host caps it (proposed 24 h, decision 2). Age is
  checked by the wall clock and by boot-relative time within one boot.
- **Relative dates.** A text such as "today" goes stale at midnight. `FrameCachePolicy(dayBound =
  true)` adds the local date to the key.

**Enforced:** the guest API; the fold. **Tested:** unit tests on `ChangeFolder`: in `Shell`, a text
not marked `cached()` never appears in the stored bytes (a search of the file fails), nor does a
content description, an image URL or a lazy key; in `Whole`, a `notCached` value and a
`@NeverCached` value never appear; age expiry by both clocks; `dayBound` at midnight.

### I3. Input before take-over

The frame is drawn, not run.

| Input on the frame | What happens |
|---|---|
| Tap, long press | consumed; the indicator shows at once. Not queued for the guest: ids differ across runs |
| Scroll, fling | consumed; the frame does not move; the indicator shows |
| Back (Android) | the hidden guest's callbacks are disabled (`backEnabled = false`), so back goes to the activity, as on today's loading screen |
| Back (iOS) | the scene's own back, as on the loading screen (guests never receive back on iOS) |
| Text input | impossible: the container is unfocusable, and text fields are not replayed |

Accessibility: one node, "Loading", polite live region, no actions (iOS: one accessibility element,
children hidden). At the swap the guest's tree appears with its own semantics.

**Tested:** host test: a click, a scroll and an IME request on the frame reach no guest and show the
indicator; one semantics node with no actions; a back press while the frame is up reaches no guest
callback and reaches the activity (Android); the same on the iOS simulator; the next frame after the
swap exposes the guest's nodes.

### I4. Hand-off without a regression

The frame stays on top until the guest says `ready`, bounded by `HANDOFF_LIMIT` and `FRAME_LIMIT`
(2.7). The swap is one frame, with both trees laid out. In `Shell` the swapped-in text may reflow
by the difference between its width and its placeholder's (rounded up to 4 characters); that is the
only movement allowed.

**Tested:** host test with a guest whose first batch is its loading state: frames across the
hand-off never show the loading state and never an empty frame; both timeouts with a fake clock;
the deferred update (2.7); a pixel check that the frame before the swap equals the stored frame and
the one after equals the guest's.

### I5. Privacy and security

- **Where.** Android: `noBackupFilesDir` (app-private, excluded from Auto Backup and device
  transfer). iOS: `Library/Caches` with `isExcludedFromBackup`, default file protection. Never
  shared storage.
- **Cleared:** on a session change (2.9, synchronously); on a release, key or signing-key change
  (frames whose key differs are deleted when read; a bundle key change deletes the `cacheName`); on
  an app update (the host build is in the key); by the remote off switch (2.11); on the app's
  request (`clearCachedFrames()`, `clearCachedFrames(route)`).
- **Size caps.** Proposed 512 KB per frame, 2 MB per `cacheName`, oldest first (decision 2).
- **What a frame can do.** A replayed frame takes no input, runs no guest code and makes no network
  request: images come from the disk cache only. It draws only replayable widgets of the app's own
  schema, with values of their declared types.
- **What an attacker who can write the app's private storage can do.** Make the host draw a
  misleading but inert screen, from the app's own widgets, for at most `FRAME_LIMIT`. Such an
  attacker already controls the app's preferences and databases. The code stays protected by the
  bundle's signature (D2).
- **Integrity.** A checksum (CRC32C over header and body) catches corruption. No Keystore or
  Keychain key: it would protect against nobody who can already write private storage, and it costs
  time on the slowest phone's cold path (decision 9).

**Tested:** unit tests for every clearing path; `session()` has deleted every frame before it
returns (a read right after it finds nothing, the directory is empty); fail closed: no read or
write before `session()` or `accounts = NONE`; a backup-rules check in the template's tests; a frame
with one byte changed is refused; a replay with a cache-only loader makes no network request (a
fake loader fails on any fetch).

### I6. Inert pre-composition (B)

A prepared or parked entry sends no request, writes nothing, logs no analytics and runs no effect
visible outside its composition. Today NF3's prepared entry runs its effects at prepare time
(`KeliverNavPreparation.kt:90–102`).

**Enforced:** held effects (3.3, 3.4); the `Prepared` lifecycle (`LaunchedOnceShown` and
`LaunchedWhileVisible` do not run); the park check, which disposes an entry that leaked; the
generated `assertInertWhenPrepared` and `assertInertAfterVisit`. Synchronous effects
(`DisposableEffect`, `SideEffect`) still run at composition, so a rebindable destination must not
call out from them; the generated tests catch it.

**Tested:** keliver-nav unit tests: a `Prepared` entry's `LaunchedEffect` has not started after N
frames, starts at the bind, is cancelled at a drop; after a visit and a pop, an effect keyed on the
route is cancelled and one keyed on `Unit` makes the entry dispose ("inert after a visit");
`LaunchedOnceShown` runs once per visit; `LaunchedOnReturn` does not run on a new visit's first
show; a held HTTP call from a prepared entry reaches a fake host only after the bind.

### I7. Arguments (B)

- **Binding.** `navigate(route)` with a parked or prepared entry of the same kind sets the entry's
  route state and shows it. The entry keeps its id, so its composition and `SaveableStateHolder` key
  stay.
- **Remembered state** must be independent of the arguments or keyed by them. Per-visit helpers
  make the safe way the easy one.
- **Scroll position.** `rememberPerVisitLazyListState()` returns to the top at each visit.
- **Saved state across process death.** A shown entry is saved with its route. A prepared or parked
  entry is not saved; it is rebuilt when idle after a restore.
- **What cannot be rebound is composed fresh.** A kind is rebindable only if the app declares it.
  Destinations with a text field, a web view or a media player should not be declared.
- **Results and back.** `popWithResult` and `LaunchedOnResult` are unchanged.

**Enforced:** by the generated `assertRebindEquivalent(kind)` for every declared kind, not by
convention. **Tested:** that test; keliver-nav unit tests (a per-visit counter, a per-visit
saveable, a list scrolled to row 10 is at row 0 on the next visit; a non-rebindable kind composes
fresh); the guest profile's `nf5=rebind` checks each open's batch carries the opened item's title.

### I8. Old hosts, old bundles, and turning it off

- (A) The guest emits its markers only when the host declares `keliver.cachedFrame@1`. A new host
  with a bundle that never opts in stores and draws nothing. The remote switch (2.11) turns it off
  per channel.
- (B) Preparation needs the host's container with entry states (`keliver.nav.retainedContainer@2`).
  Without it keliver-nav does what `retentionFor(hostFeatures)` does today: no preparation.
- Host feature names become constants in one place, with a test that each template declares
  exactly what it implements (H0).

**Tested:** `HostFeaturesGuestTest`-style tests: without the feature the guest's batches are
byte-identical to today's; with it, only the marker elements are added; the switch deletes and
stops frames.

### I9. Resource bounds

| Resource | (A) | (B) |
|---|---|---|
| Memory | one decoded frame while drawn (the three-way list: 148 widgets), freed at the swap; two trees during the hand-off, as the update swap already holds | one shell per prepared kind, guest and host; at most `maxPreparedKinds` (2); dropped on memory pressure and in the background. Budget: decision 6 |
| CPU and battery | a read and a decode per cold start; one write per content binding, at most | one composition per kind when idle; recompositions when what a shell reads changes. Never during input, scroll, fling or animation; never before the first screen is ready. Budget: decision 6 |
| Disk | caps per frame and per `cacheName` | none |

**Tested:** the caps' eviction order; the idle policy with a fake clock and input events; the A12
series in section 7.

### I10. One writer, one reader

The store is written only by the recorder of the content that showed the marked screen, and read
once per cold start before the guest's first batch. Two screens with the same key write the same
file; the last complete write wins (atomic rename). A reader opens only renamed files.

**Tested:** unit test with two writers and a reader on one key.

### I11. The stored tree is the tree that was drawn, and only that

- The fold reproduces what the host applied for the marked subtree, nothing else.
- **Enforced:** the recording rule (2.3); `ChangeFolder` tested against the host itself, not a model.
- **Tested:**
  - every fold test applies the raw batches to one host and the folded snapshot to another, and
    compares widget trees and, on the JVM Compose UI harness with **keliver-material**, pixels;
  - a property test over random batch sequences (Add, Move, Remove, detach, re-add);
  - **the navigation test**: compose a `KeliverNavHost` whose list is marked; let `ready` go true
    (write 1); navigate to a detail; send the app to the background (`ON_STOP`). Assert the file is
    write 1, byte for byte, holds the list's tree and no node of the detail, and that no write
    happened after the navigation;
  - a write blocked by each condition of 2.3 (scrolled, touched, a dialog, a hidden marked entry);
  - a frame recorded with a prepared and a retained entry present holds neither.

### I12. Bounded on screen

The frame is on screen for at most `FRAME_LIMIT` after its first draw, whatever the guest does. A
revealed shell (B) is hidden again if the guest does not bind it in its next batch.

**Tested:** host tests with a guest that never loads, one that never sends `ready`, and one whose
click handler does not navigate.

## 5. Cache key, invalidation and storage format

### 5.1 Key and invalidation

| Field | Source | On mismatch |
|---|---|---|
| release identity | sequence, manifest sha256 and module digest of the app that recorded it (2.8) | not drawn; deleted |
| engine | `hostEngine` (NF2) | not drawn; deleted |
| host build | Android: version code and `lastUpdateTime` (a same-version reinstall or a dev build changes it). iOS: `CFBundleVersion` and the executable's Mach-O UUID | the directory is deleted at the first start |
| widget and schema versions | the widget system's version and the schema's tag-lock hash | not drawn; deleted |
| format version | the file's header | not drawn; deleted |
| route and launch arguments | the encoded `KeliverRoute` | another file |
| session | a keyed hash of the session id, or "signed out"; nothing without a session record (2.9) | all frames deleted at the change |
| variant | the app's guest-held theme or language (`FrameScope.variantChanged`) | all frames of the old variant deleted |
| display | width and height in dp (rounded to 8), density, orientation | not drawn; kept |
| insets | system bar and cutout insets (rounded to 4 dp), navigation mode (gestures or buttons) | not drawn; kept |
| text and contrast | locale, layout direction, font scale (rounded to 0.05; iOS the Dynamic Type category), bold text, high-contrast text | not drawn; kept |
| appearance | dark mode | not drawn; kept |
| time | time zone id, 24-hour setting; the local date with `dayBound` | not drawn; kept |
| age | `createdAt` (wall clock and boot-relative) against `maxAge`, capped | not drawn; deleted |

"Kept" fields describe the device's configuration: a frame for the other one stays, within the cap.
The file name is a hash of route, configuration, variant and session. The other fields are in the
header, checked after reading it.

### 5.2 Storage format

```
magic "KLVF" | format version (1) | header length | header (JSON) | body length | body | CRC32C
header: { release: {sequence, manifestSha256, moduleDigest}, engine, hostBuild, widgetVersion,
          schemaHash, route, configuration, variant, session, createdAtMillis, createdAtBoot,
          maxAgeMillis, content: "whole"|"shell" }
body:   a SnapshotChangeList as the bridge's JSON, gzip-compressed
```

- The body is the folded marked subtree at the `ready` edge (2.3): only Create, PropertyChange,
  ModifierChange and ChildrenChange.Add, in an order a fresh host can apply.
- **Placeholders** (`Shell`): a replaced text becomes figure spaces (U+2007), as many as the
  original's length rounded up to a multiple of 4, at most 64. It lays out at about the original
  width and draws blank. A dropped URL or other text-bearing value is omitted, so the widget uses
  its default.
- Lazy lists: only the rows the guest had composed, at the first position.
- Writes go to `<name>.tmp`, `fsync`, then a rename.
- Version 1 is JSON, for reviewability. A binary body is a later, measured choice.
- The format code lives in `keliver-treehouse-host`, which only hosts use.

## 6. Failure handling

| Failure | Detected by | What the user sees | Record |
|---|---|---|---|
| Unreadable, truncated or corrupt file | magic, lengths, CRC, JSON decode | today's loading screen | file deleted; `cachedFrame.discarded(reason)` |
| Unknown tag in the frame | the non-throwing mismatch handler | today's loading screen | file deleted; reported (the key should have caught it) |
| The loaded release differs from the frame's | `codeLoadSuccess` (2.8) | the frame goes; loading until the guest's first batch | file deleted; reported |
| Guest crashes after the frame is shown | `KeliverHost.crashed` / the content's Crashed widget | the frame goes; today's `unavailable` | frame deleted; crash rollback as today |
| Code fails to load | `KeliverHostState.Unavailable` | the frame goes; today's failure screen | frame kept only for a network failure with the same release next time |
| The guest never says `ready` | `HANDOFF_LIMIT` after its first batch | the guest's tree as it is | `cachedFrame.handoffTimeout` |
| Nothing from the guest | `FRAME_LIMIT` after the frame's first draw | today's `loading()` | `cachedFrame.frameTimeout`; frame kept |
| An update loads before the hand-off | the update swap | the frame hands off to the old app first, then the update swaps (2.7) | none |
| Process death mid-write | the rename never happened | next start: the previous complete file, or none | `.tmp` deleted at the next start |
| Disk full on write | the write throws | nothing visible | `cachedFrame.writeFailed` |
| Clock jumped | boot-relative age disagrees with the wall clock | not drawn | deleted |
| Session changed during a write | the post-rename check (2.9) | nothing visible | file deleted |

## 7. Measurement plan and gates

On the A12, with the three-way benchmark's method ([`THREE_WAY_BENCHMARK.md`](THREE_WAY_BENCHMARK.md),
"Method"); cold start with Macrobenchmark `StartupMode.COLD`, 10 iterations; navigation with
`bench=nav`, 700 ms between taps. iOS: the simulator for relative numbers, the iPhone once signed.
All open times are frame end, from the tap, with first draw reported too.

### (A) Cold start

Two marks, both reported: **first content drawn** (the first draw of the frame, or of the guest's
content without one) and **guest took over** (the draw after the swap).

| Gate | Measure | Pass |
|---|---|---|
| A-1 | first content drawn, with a frame, `cache-first` | within native + 0.4 s of native's list-data frame (about 1.13 s) |
| A-2 | the same under `parallel` with the bench's local lookup | within native + 0.4 s |
| A-2b | the same under `parallel` with a **throttled remote lookup** (a server behind a 3G profile: 300 ms RTT, 1.6 Mbit/s, cold DNS and TLS) | reported; if it misses, the existing per-channel `cache-first` override is the lever (decision 8) |
| A-3 | guest took over | not later than today's data frame for the same build |
| A-4 | cold start without a frame (first launch, after a session change) | not slower than today by more than 10 ms |
| A-5 | the swap | no frame without content between the frame and the guest's (frame trace); pixel diff of the swap frames |
| A-6 | the frame's read, check and decode | reported, on the main thread's critical path |
| A-7 | **correctness on the device**, by log line and screenshot: font scale 1.3, another locale, dark mode, sign-out, a deep link to another route, a new release | each: no frame drawn, the log line names the key field |

What "data" means: with `Whole`, first content drawn already shows the list's data from storage, so
A-1 is the native comparison and A-3 says when it is live. With `Shell`, the data regions are
placeholders, so only A-3 counts as data. The report gives both, per screen. Decision 1 is whether
A-1 counts for the bar.

### (B) Open

Arms of the three-way bench (`bench=nav`, simple and `heavy`, BigInt, press off), against
`x=retainhost` and as shipped:
- `x=prepkind`: the detail prepared by kind while idle, bound on the tap (guest-driven).
- `x=prepkind,reveal`: the same with the host reveal.
- `x=prepkind,varied`: the heavy detail whose structure depends on the item (the `varied`
  experiment added in this PR; guest only).
- `x=prepkind,detailfetch`: the detail loads its item by id through `HostHttp` from the bench's
  fixture server, asynchronously, as `bench=api-small` loads the list. Measures open to data drawn
  when the data is not in the arguments.
- `bench=api-small` with and without idle preparation: preparation must not slow the list's load.
- All arms on keliver-nav itself, not the profile's stand-in stack.

| Gate | Measure | Pass |
|---|---|---|
| B-1 | open, simple, tap to data drawn (frame end) | target about 100 ms; expected 145–170 (appendix B) |
| B-2 | open, heavy, tap to data drawn | target 150 ms or less; expected 225–275 |
| B-1r, B-2r | with the reveal: tap to the shell's first draw and frame end | under 100 ms; data as B-1, B-2 |
| B-3 | a tap while a preparation slice runs | no slower than open without preparation |
| B-4 | back | no regression against `x=retainhost` |
| B-5 | the generated inertness tests on the three-way app and the reference app's declared kinds | zero calls out |
| B-6 | `varied` and `detailfetch` | reported against `x=prepkind` |
| B-7 | iOS simulator, the same arms | the same ordering as the A12 |

### Regressions to watch

| What | How | Limit |
|---|---|---|
| Scroll jank from idle preparation (B) | series F3's flings, preparation on and off; frames over 25 ms | no increase beyond run-to-run spread |
| Memory (A and B) | PSS after the list (F5's method), with and without | decision 6 |
| Battery (B) | guest CPU time spent preparing, per minute of browsing | decision 6 |
| APK size | F4's method | under 50 KB dex for each half |
| Disk (A) | the store's size after a session | under the caps |
| First launch (A) | cold start after `pm clear` | unchanged |

## 8. Alternatives considered and rejected

| Alternative | Why not |
|---|---|
| A screenshot (bitmap) instead of a tree | wrong on any configuration change; not accessible; larger; blurry on another density |
| Recording at `ON_STOP` as well (revision 1) | records whatever the user left on screen: another screen, a scrolled list, an open dialog (review C1). Recording only at the `ready` edge in a clean state is simpler and always right |
| Hiding values by a denylist (`notCached()` only, revision 1) | one missed mark leaks a value. `Shell` is an allowlist |
| Pre-composing every row's destination | memory and idle CPU grow with the list; most are never opened |
| Host-composed documents (the parked fallback, PLAN section 12) | needs a D1 amendment and a grammar that cannot yet express a keyed lazy list. (A) draws the guest's own recorded output instead (section 10) |
| Keeping the process warm | not allowed for this on modern Android; iOS does not allow it |
| Queuing taps on the frame for the guest | ids differ across runs; a wrong delivery is worse than a dropped tap with feedback |
| Showing the frame under `parallel` before the lookup confirms | breaks I1 when the lookup names another release. The per-channel `cache-first` override already gives "frame at once, new release at the next start" |
| A platform-kept integrity key (revision 1) | protects against nobody who can already write private storage, and costs time at start |
| Keeping a popped entry with its effects running (revision 1) | a started coroutine cannot be held again: from the second visit on, a prepared entry would poll and load before the tap (review H7). Parking or disposal instead |
| Rebinding by arguments without a shell (revision 1's (B)) | on the A12 the guest round trip alone misses the targets (appendix B). The shell lets the host show something at the tap |
| A heap snapshot of the guest | QuickJS has none supported; the guest's state includes live coroutines and host handles |
| Making the guest faster again | NF2 is spent; NF3 showed composition on touch-down is too slow to hide |

**Considered for later:** (A) for an activity re-created in a warm process (rotation without
configuration handling, a process kept but an activity destroyed). There the release is known
exactly. It is not in the first ship.

## 9. Implementation plan

The halves share a first PR (H0) and some pieces: (A)'s fold prunes (B)'s and NF1's hidden entries;
(B)'s idle policy uses (A)'s `ready` when present; (B)'s equivalence test uses (A)'s fold. So the
order matters: H0, then (A), then (B) once NF1's production container exists.

| PR | Module(s) | What | Tests | Gate |
|---|---|---|---|---|
| **H0** | templates, `keliver-treehouse-host-composeui` | host feature names as constants in one place; templates declare what they implement; `TreehouseContent(backEnabled)` and its use for hidden content; the lastGood record with sequence and module digest, written with `commit()` | a test that each template declares exactly its features; back with hidden content reaches no guest | apiCheck; reference-app and ios-host workflows |
| **A1** | schema annotations, `keliver-tooling-codegen`, keliver-material schema | `@Replayable`, `@NeverCached`; generated `FrameRules`; keliver-material annotated | codegen tests; the staleness guard | apiCheck |
| **A2** | `keliver-protocol`, `keliver-compose`, `keliver-protocol-host` | `FrameCacheTags` (lock test); `CachedFrame`, `cached()`, `notCached()`, `FrameScope`; `HostProtocolAdapter` consumes the reserved tags | batches byte-identical without the feature; with it, only the markers; reserved tags never reach the mismatch handler | apiCheck |
| **A3** | `keliver-treehouse-host` | `ChangeFolder`, `FrameRecorder` (the rule in 2.3), `FrameStore`, the format, the key, the session record and fail-closed rule, the off switch | I1, I2, I5, I10, I11 unit tests, including the navigation test; random-sequence fold tests | apiCheck; unit tests on JVM, Android and the iOS simulator |
| **A4** | `keliver-treehouse-host-composeui` | `CachedFrameContent` (inert, cache-only images, indicator slot), `FrameHandOff` (both limits, deferred update) | I3, I4, I12 host tests; **0 differing pixels on keliver-material**, replayed against live, on real multi-batch sequences from the sample app's screens | the host tests; the pixel gate |
| **A5** | templates (Android, iOS), docs | the start table on `StartChoice.mode`, the `codeLoadSuccess` check, `session()` and `accounts = NONE`, storage dirs, the off switch from the index, adopter docs | template tests (start table; backup exclusion; fail closed) | reference-app and ios-host workflows |
| **A6** | sample, benchmarks | the bench's `x=cachedframe`, both marks, the throttled lookup | — | **A-1 to A-7 on the A12**; the owner reads them before (A) is on by default |
| **B0** | precondition (NF1's production step) | the retained container in the production widget system, with the entry state (3.5) | NF1's; back never reaches a `Prepared` entry | NF1's |
| **B1** | `keliver-nav` (spike, guest profile) | held-effect sub-composition, slices, a tap during a slice; the checklist in 3.7 | the guest profile | "feasible with public Compose API, within the slice budget"; else (B) stops and goes back to the owner |
| **B2** | `keliver-nav`, docs | kinds, shells, bind and park, the park check, `Prepared`, per-visit counts, the generated tests, the retention docs rewrite | I6 and I7 unit tests, "inert after a visit" | apiCheck |
| **B3** | `keliver-nav`, templates | the idle policy and the two host signals | idle policy with a fake clock; host signal tests | apiCheck |
| **B4** | container renderer, `keliver-nav` | the host reveal and `revealsPrepared` | reveal, decline, back during a reveal (host tests) | apiCheck |
| **B5** | sample, benchmarks | the arms in section 7 | — | **B-1 to B-7 on the A12** and the simulator |

## 10. Proposed D1 clarification

Revision 1 asked whether (A) needs a D1 amendment. The review's answer: no change in substance,
but a written clarification, because D1 says "not interpreted JSON" and "production is always
compiled code", and a stored JSON tree drawn in production touches both. Proposed text, for the
owner to add to `DECISIONS.md` under D1 in a separate PR (this PR does not edit it):

> **D1 clarification (NF5, cached first frame).** A production host may draw, on a cold start, a
> recording of a signed bundle's own UI output while that bundle's code loads. This stays within D1
> because:
> 1. it is only the bundle's own recorded output: the folded change batches its compiled code
>    sent to this host;
> 2. it is produced on this device and never distributed: no server, build step or editor writes
>    it;
> 3. it is drawn only when keyed to that exact signed release (sequence, manifest sha256, module
>    digest), checked before drawing and again when the code has loaded;
> 4. it is inert (no input, no guest code, no network) and drawn through the same
>    `HostProtocolAdapter` and the app's own widget renderers, never through the editor's
>    `RenderNode`;
> 5. it is time-bounded: replaced when the code says it is ready, and gone after `FRAME_LIMIT`;
> 6. it is not the parked host-composed-document fallback (PLAN_2026-11 section 12): no document
>    is authored, and no logic or layout comes from anywhere but the compiled bundle.
>
> **D2:** the recording is not covered by the bundle's signature. Its integrity is a checksum
> against corruption, the generated widget allowlist and the release key above. An attacker who
> can write the app's private storage can make it draw a misleading, inert screen for at most
> `FRAME_LIMIT`; such an attacker already controls the app's data.

D3 (frames are per bundle; the session hook is a host capability) and D12 (native screens clear
frames by route, 2.10) need no change.

## 11. Decisions for the owner

Each with my recommendation and its cost.

1. **Does a cached `Whole` frame count as "the list's data" for the cold-start bar?** A-1 measures
   it. *Recommendation:* yes for `Whole`, within the screen's `maxAge`, because it is the data the
   user last saw, from the same release, and it is refreshed in place at the swap. `Shell` counts
   only as first content; its data time is A-3. *Cost:* the bar can be met with data up to `maxAge`
   old.
2. **The limits.** *Recommendation:* `maxAge` cap 24 h (revision 1 proposed 7 days);
   `HANDOFF_LIMIT` 3 s; `FRAME_LIMIT` 5 s; 512 KB per frame and 2 MB per `cacheName`. *Cost:* a
   longer cap shows older data; shorter limits fall back to the loading screen more often on a slow
   phone, which content → loading → content makes visible. Revisit after A6.
3. **Replace `discardPrefetched()` with `session()`.** An adopter API change. *Recommendation:* add
   `session(id)` and `CachedFrames(accounts = NONE)` in 0.5.0; keep `discardPrefetched()` working,
   deprecated, for one minor release. Frames stay off for an app that calls neither (fail closed).
   *Cost:* a migration note; apps that ignore it simply get no frames.
4. **Host-drawn indicator or an app slot.** *Recommendation:* a slot on `KeliverScreen`
   (`frameIndicator`) with a thin bar as the default. *Cost:* one parameter.
5. **keliver-nav behaviour changes.** Held effects for every prepared entry (NF3's too); for apps
   that declare kinds, popped entries parked; show counts per visit; loads start at the show.
   *Recommendation:* accept. Apps that declare no kinds see only the NF3 change, and NF3 is off by
   default. *Cost:* a documented behaviour change in 0.5.0 and the docs rewrite (B2).
6. **The memory and battery budget for (B).** *Recommendation:* at most 2 prepared kinds; PSS on
   the A12 at most 8 MB above `x=retainhost` with both prepared; preparation's guest CPU under 2%
   of a minute of browsing; one preparation per kind per list visit. *Cost:* a tighter budget may
   allow only one kind on the A12.
7. **Does a shell at the tap, with the data about 150–300 ms later, count as "open"?** The design
   ships the reveal either way, because it removes dead time. *Recommendation:* no, not for the
   bar: keep "open" as data drawn (B-1, B-2), report the shell's time beside it, and decide after
   B5's numbers. *Cost:* the open targets stay unmet by (B). Under PLAN_2026-11 (section 11), if
   NF5 misses its gates the parked fallbacks come to the owner with the evidence.
8. **`parallel` before confirmation.** *Recommendation:* keep the strict rule (the frame waits for
   the lookup) and add no option; a channel that wants the frame at once uses the existing
   per-channel `cache-first` override. *Cost:* under `parallel` the frame waits one lookup (A-2b).
9. **Integrity.** *Recommendation:* a checksum, no Keystore or Keychain key; a key in private
   storage only to hash the session id. *Cost:* none beyond I5's stated attacker.
10. **Taps on the frame.** *Recommendation:* drop them with the indicator, and gate back (2.6).
    *Cost:* a tap during the frame needs repeating.
11. **The D1 clarification** (section 10). *Recommendation:* add it to `DECISIONS.md` before A3
    merges. *Cost:* none.
12. **Sequencing.** *Recommendation:* H0, then (A) (A1 to A6); (B) after NF1's production container
    (B0), starting with the B1 spike. *Cost:* (B) waits for NF1.

## Appendix A: review response

37 rows. **36 fixed** in this revision, 2 of them with a stated partial disagreement (H1's
"refuses or omits"; H6's heavy figure). **1 agreed with no change needed** (D3). None rejected.

| Finding | Response |
|---|---|
| C1 which tree, when | Fixed: 2.3 (marked subtree only, hidden entries pruned by explicit state, one write at the first `ready` edge in a clean state, no `ON_STOP` write); I11's navigation test as the reviewer describes |
| H1 window-level and stateful widgets | Fixed: 2.4 (codegen `FrameRules`, `@Replayable` allowlist; 9 keliver-material widgets not replayable, the review's 6 plus `DropdownMenu`, `Tooltip`, `OutlinedTextField`; pagers only at their first page; text-field values never stored). Partly disagreed on "refuses or omits": the fold refuses the write; omitting a subtree would record a tree that was not drawn (I11) |
| H2 `Shell` by denylist; I5 wording | Fixed: `Shell` is an allowlist (`cached()`), covering modifier strings and image URLs; lazy keys by position; replayed images from the disk cache only; I5 reworded ("no input, no guest code, no network request") |
| H3 back during the hand-off | Fixed: 2.6 (`backEnabled = false` for hidden content on Android; iOS guests never get back); I3 test on both |
| H4 no cap on the frame | Fixed: `FRAME_LIMIT` from the frame's first draw, then today's `loading()` (2.7, I12, tested) |
| H5 session fails open | Fixed: 2.9 (nothing until `session()` or `accounts = NONE`; `FrameScope.sessionChanged` from the guest; synchronous delete, tested) |
| H6 (B)'s A12 estimate | Fixed: appendix B (frame end from the tap; tap → click and draw → frame end added; ratios 12 and 30); section 0 says (B) alone misses both targets; the shell + reveal is part of (B); arms `varied`, `detailfetch` and `api-small` added; the varied arm run on the Mac (table B2). Partly disagreed with the figure: the heavy screen comes out at 225–275 ms, worse than the review's 210–230, because the rebind increment at ratio 30 adds up to 67 ms |
| H7 effect lifecycle | Fixed: 3.3 (park with `unbound` cancels effects keyed on arguments; the park check disposes a leaking entry; per-visit show counts; `LaunchedOnReturn` within a visit only; docs rewrite in B2); "inert after a visit" test (I6) |
| H8 `parallel` and the wrong input | Fixed: 2.8 keys on `StartChoice.mode`; gate A-2b with a throttled remote lookup |
| M1 identity before load | Fixed: 2.8 (the writer's own `codeLoadSuccess` identity: sequence, manifest sha256, module digest; compared again at `codeLoadSuccess`; `commit()` in H0) |
| M2 evidence on gate A's widgets | Fixed: section 0 headlines the cold 24 ms; A4's gate is 0 differing pixels on keliver-material over real multi-batch sequences |
| M3 prepared entries protected by order | Fixed: 3.5 explicit entry state (`Prepared` never drawn, never a back target); 3.6 now lists the protocol change and the host code |
| M4 placeholder sizing | Fixed: 5.2 (figure spaces of the original length, rounded up to 4); I4 accepts a small reflow and says so |
| M5 key gaps | Fixed: 5.1 adds display, density, insets, navigation mode, time zone, 24-hour, bold and contrast, the guest-held variant; host build uses `lastUpdateTime`, and `CFBundleVersion` with the Mach-O UUID |
| M6 not independent; device gates; kill switch | Fixed: H0 shared PR and the dependencies in section 9; gate A-7 on the device; per-channel `cachedFrames: "off"` in the index (2.11) |
| M7 I7 by convention | Fixed: generated `assertRebindEquivalent(kind)` per declared kind (3.4, I7) |
| M8 update before hand-off | Fixed: defined in 2.7 (the update waits for the hand-off; `FRAME_LIMIT` bounds it) |
| Low: reserved tags reach the mismatch handler | Fixed: `HostProtocolAdapter` consumes them (2.2); stated as the one content-path change |
| Low: B1's spike checklist | Fixed: 3.7 lists all five, with a slice budget of 8 ms on the A12 |
| Low: format and HMAC in `keliver-protocol` | Fixed: the format, checksum and fold move to `keliver-treehouse-host`; only the marker tags stay in `keliver-protocol` |
| Low: D12 native data changes | Fixed: `clearCachedFrames(route)` (2.10) |
| Low: warm-process activity re-creation | Fixed: listed as a later option (section 8) |
| D1 | Fixed: proposed clarification text in section 10, bounded as the review lists; `DECISIONS.md` not edited |
| D2 | Fixed: stated in the same clarification |
| D3 | Agreed, no change |
| D12 | Fixed with the Low item above |
| Author's decision 1 (D1) | Adopted: no amendment, the clarification instead (decision 11) |
| Author's decision 2 (`parallel` early) | Adopted: strict rule, no new option (decision 8) |
| Author's decision 3 (integrity key) | Adopted: checksum, no Keystore; private key only to hash the session id (decision 9) |
| Author's decision 4 (taps) | Adopted, with the back gate (decision 10) |
| Unasked: `Whole` frame as data | Now decision 1 |
| Unasked: the limits | Now decision 2 (`maxAge` cap lowered to 24 h) |
| Unasked: `discardPrefetched()` → `session()` | Now decision 3 |
| Unasked: host-drawn indicator | Now a slot, decision 4 |
| Unasked: keliver-nav behaviour changes | Now decision 5 |
| Unasked: memory and battery budget | Now decision 6, with numbers |
| Unasked: sequencing on NF1 | Now decision 12 |

## Appendix B: the A12 estimate for (B)

Inferred, not measured. All on the BigInt build, A12, frame end from the tap, the same measure as
the targets (217.5 and 548 today).

**The components:**

| Component | Simple | Heavy | Source |
|---|---|---|---|
| tap → the guest's `onClick` | about 30 | about 30 | open's first draw from the tap, 197.5 ("Flutter on the A12", BigInt), minus series H's click → drawn without the press, 163.5–167 |
| click → first draw, the detail already composed with the right item | 80–85 | 131.5–140.5 | series H, press on (the detail only recomposes a counter) |
| rebinding instead: the extra guest work | 14–36 | 27–67 | Mac: rebind minus shown, +1.19 ms simple and +2.24 heavy (table B1, BigInt), × 12 to × 30 |
| first draw → frame end | 20 | 38 | 217.5 − 197.5 and 548 − 510 ("Flutter on the A12") |
| **(B) alone, total** | **144–171** | **226.5–275.5** | |

**The two ratios.** About 12 is the Mac-to-A12 ratio for long guest work
([`GUEST_PROFILE.md`](GUEST_PROFILE.md)). For tiny steps it is about 30: series H's click →
composed for a detail that only recomposes a counter is 57.5 ms (simple) and 111–114.5 ms (heavy) on
the A12, against 1.78 and 4.27 ms for the same step on the Mac (`shown`): 32× and 26×. The
increment is extra work inside a step whose fixed cost is already counted, so the truth is likely
nearer 12, but the range keeps both.

**Items that differ in structure** (table B2, QuickJS 2021 only): the rebind's increment over the
floor was 11.5 ms on the Mac, 4.0× the same-structure 2.9 ms. Scaled to BigInt as the
same-structure increment scales (2.24 / 2.96), it is about 8.7 ms. A step that size is long guest
work, so the ratio of 12 applies: about 105 ms on the A12. A varied heavy open is then about
30 + 131.5–140.5 + 105 + 38 = **305–315 ms** (up to about 470 at a ratio of 30). Still well under
today's 548, but twice the target. A shell that holds only the common structure pays this cost at
every bind.

**With the host reveal:** the shell's first draw needs no guest step. The nearest measured case is
the retained back: its first draw came 13–17 ms after the press on every arm (checkpoint 2,
`x=retainhost`), and its pixels reached the screen 79–91 ms after the touch-down (the pixel table,
including the input pipeline). The shell should do about the same. The data then arrives at the
totals above.

**What would reach 100 / 150 with data:** nothing in this design. The fixed cost of one guest round
trip on the A12 (click → drawn for a counter: 80–85 ms) plus the tap and the frame end already
exceeds 100 ms. Closing that needs the guest step itself to get cheaper on the A12, which NF2 and
NF3 did not achieve. That is why decision 7 exists.

## Appendix C: the Mac measurements

### Environment

2026-11-24, on the project's Mac (shared with other jobs; each run held the machine's heavy-build
slot). Kotlin 2.4.21, JDK 17. **Relative numbers only:** the guest runs in Zipline's QuickJS on the
JVM ([`GUEST_PROFILE.md`](GUEST_PROFILE.md)), the host in a JIT-warmed JVM with Skia on the CPU.
One run per configuration, except table B2 (two runs).

Engines:
- **QuickJS 2021-03-27**: Zipline 1.28.0 as shipped.
- **QuickJS 2026-06-04, `Long` as BigInt**: NF2's fork, from a scratch Maven publish
  (`1.28.0-q20260604`), through `keliver-compose-gatea-profile/engine/zipline-fork/use-fork.init.gradle`,
  with `-PprofileBigInt`. The run printed `QJS engine: QuickJS 2026-06-04`.

### The measurement code (prototype modules only)

- `keliver-compose-gatea-profile/src/jsMain/.../Nf5Stack.kt`: the three-way app's screens on a
  stand-in stack with three variants. All keep the list composed under the detail, so only the
  detail's path differs:
  - `fresh`: the detail is composed at the tap and disposed at back (today's open);
  - `rebind`: one detail slot keyed by its kind stays composed and hidden; the tap changes its item
    and shows it;
  - `shown`: the same slot, but a timed `nf5prepare` step gives it the item before the tap; the tap
    only shows it (the floor).
- `GuestProfileMain.kt`: options `nf5=`, `heavy=1` (the 102-widget detail), `heavy=2` (the heavy
  detail with the `varied` structure), the `nf5prepare` and `wire` commands.
- `sample/threeway-guest` (`ThreewayMarks.variedDetail`, the `varied` experiment, guest only, off as
  shipped): a banner on even ids, and 2–4 sections of 3–6 rows chosen from the item's id. Rows 0–4
  (ids 1000–1004) all differ from their neighbour.
- `QuickJsProfile.kt`: `nf5Runs`: per variant and weight, a fresh Zipline, the list, then 10 warm-up
  and 40 measured open/back pairs on rows 0–4. Every step that gives the detail its item is checked
  to send that item's title.
- `keliver-compose-gatea-composeui/src/jvmTest/.../CachedFrameReplayBenchmark.kt`: folds the list's
  batches into a `SnapshotChangeList`, stores it as the bridge's JSON, replays it into a fresh
  `HostProtocolAdapter` with gate A's Compose UI widgets, and compares pixels with a host that
  applied the batches live.

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
# (B), items that differ in structure (table B2), QuickJS 2021, run twice
./gradlew -Pkeliver.guestProfile :keliver-compose-gatea-profile:profileQuickJs \
  "-PprofileArgs=nf5=fresh+rebind+shown,heavy=1+2,warmup=10,iterations=40,label=nf5-varied-stock" -PprofileOut=$OUT
# cross-check of the "fresh" baseline: today's retained stack
./gradlew -Pkeliver.guestProfile :keliver-compose-gatea-profile:profileQuickJs \
  "-PprofileArgs=modes=kotlinx,retain=1,warmup=10,iterations=40,label=retain-stock" -PprofileOut=$OUT
# (A), the host's replay of the list
./gradlew :keliver-compose-gatea-composeui:jvmTest --tests '*CachedFrameReplayBenchmark' \
  -Dkeliver.nf5.wire=$OUT/nf5-stock-list-wire.json -Dkeliver.nf5.measured=40
```

### Table B1: open and back with a hidden destination (wall ms, median / p90, n = 40)

| Engine | Detail | Variant | Open | Back | Prepare (`shown`) | List's first composition (`first`) |
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

The guest's own time (1 ms resolution) agrees within 1 ms in every row. `first` includes, for
`rebind` and `shown`, the hidden slot's first composition (the cost of preparing it). Cross-check:
today's retained stack (`retain=1`, 2021) opened in 16.56 ms, against `fresh`'s 15.45.

Ratios, open: rebind against fresh **3.1×** (2021) and **2.6×** (2026) simple, **7.5×** and
**4.7×** heavy. Rebind against the floor: +1.96 / +1.19 ms simple, +2.96 / +2.24 heavy.

**The batches** (the same on both engines; the last measured step):

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

### Table B2: items that differ in structure (QuickJS 2021, heavy detail)

Wall ms, median / p90, n = 40. The open batch is the last measured step's: item 1004 after 1003,
which adds the banner and a fourth section.

| Detail | Variant | Open | Back | Prepare (`shown`) | Open batch: creates / properties / structural / bytes |
|---|---|---|---|---|---|
| heavy, same structure | fresh | **64.50** / 65.77 | 3.71 | — | 104 / 1,144 / 104 / 82,446 |
| heavy, same structure | rebind | **8.59** / 8.79 | 5.63 | — | 0 / 27 / 0 / 2,063 |
| heavy, same structure | shown | **5.70** / 5.94 | 5.80 | 7.01 / 7.27 | 0 / 0 / 0 / 154 |
| heavy, varied | fresh | **46.31** / 56.68 | 3.23 | — | 89 / 969 / 89 / 70,230 |
| heavy, varied | rebind | **15.98** / 22.92 | 5.03 | — | 28 / 346 / 31 / 24,332 |
| heavy, varied | shown | **4.47** / 5.06 | 4.54 | 13.43 / 20.09 | 0 / 0 / 0 / 154 |

- The same-structure rows reproduce table B1's within 2.5% (64.50 against 62.94 fresh, 8.59
  against 8.42 rebind).
- **Varied:** rebind is 2.9× cheaper than fresh (7.5× with the same structure). It costs 1.9× a
  same-structure rebind, and its increment over the floor is 11.5 ms against 2.9 (4.0×).
- The varied p90s are wide because each pair of neighbouring items differs by a different number of
  widgets. Varied items are smaller on average (fresh 46.31 against 64.50).
- A first run of the same command under heavy machine load (load average 33) gave the same ordering
  with 2–5× the times; it was discarded. Not run on the BigInt engine (its scratch Maven repo was
  not available to this session).

### Table A: the cached first frame of the list

| Measure | Value |
|---|---|
| The guest's first batch for the list | 1,715 changes, 93,734 bytes |
| The folded snapshot | 1,695 changes, 148 widgets; 86,957 bytes of JSON; **7,919 bytes gzipped** |
| Replayed vs live host, pixels differing (one 360×640 dp frame) | **0** (gate A's widgets, not keliver-material: A4's gate) |
| **The first replay of the run** (JIT barely warm) | **24.0 ms** (decode 6.0, apply 1.9, frame 16.1) |
| Replay, warm (n = 40 after 40): read + decode / apply / frame / total | 1.007 / 0.227 / 2.959 / 4.244 ms median (p90 4.717) |
| Applying the guest's raw batch to a fresh host, once, cold JIT | 19.8 ms |
| The guest's first composition of the list, before its batch is sent | 87.34 ms (2021), 45.51 ms (2026 BigInt) |
| Events the replayed widgets sent, dropped (lazy list viewport reports) | 162 in 80 replays |
| Smoke run (two batches with a Remove, 33 widgets) | 0 differing pixels |

The comparison is across runtimes (QuickJS against the JVM); it gives the order of magnitude. On
the phone, the host's side of the list's first data frame is NF4's "composed to drawn", 223.5 ms; a
cached frame pays that plus its decode. The fold over real multi-batch sequences was only
smoke-tested here (33 widgets); A4's gate covers it.

**Not measured here:** anything on a phone; a frame written by a real host's recorder; the
hand-off; memory; keliver-material's pixels.

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

Items that differ in structure, QuickJS 2021 (nf5-varied-stock-2; load average 5.8 at the end)
QJS NF5 variant=fresh heavy=1 back=3.71/p90 3.81 (guest 4.00/p90 4.00; creates 0 bytes 104) open=64.50/p90 65.77 (guest 62.00/p90 64.00; creates 104 bytes 82446) first=92.24
QJS NF5 variant=rebind heavy=1 back=5.63/p90 5.80 (guest 6.00/p90 6.00; creates 0 bytes 234) open=8.59/p90 8.79 (guest 9.00/p90 9.00; creates 0 bytes 2063) first=154.03
QJS NF5 variant=shown heavy=1 back=5.80/p90 6.03 (guest 6.00/p90 6.00; creates 0 bytes 234) open=5.70/p90 5.94 (guest 6.00/p90 6.00; creates 0 bytes 154) prepare=7.01/p90 7.27 (guest 7.00/p90 7.00; creates 0 bytes 1910) first=152.44
QJS NF5 variant=fresh heavy=2 back=3.23/p90 3.53 (guest 3.00/p90 4.00; creates 0 bytes 104) open=46.31/p90 56.68 (guest 45.00/p90 55.00; creates 89 bytes 70230) first=91.73
QJS NF5 variant=rebind heavy=2 back=5.03/p90 5.62 (guest 5.00/p90 6.00; creates 0 bytes 234) open=15.98/p90 22.92 (guest 16.00/p90 23.00; creates 28 bytes 24332) first=152.14
QJS NF5 variant=shown heavy=2 back=4.54/p90 5.03 (guest 5.00/p90 5.00; creates 0 bytes 234) open=4.47/p90 5.06 (guest 4.00/p90 5.00; creates 0 bytes 154) prepare=13.43/p90 20.09 (guest 13.00/p90 20.00; creates 28 bytes 24179) first=142.39

CachedFrameReplayBenchmark (build/nf5-cached-frame.txt)
input: nf5-stock-list-wire.json; 1 batch(es), 1715 changes, 93734 bytes
folded snapshot: 1695 changes (148 widgets), 86957 bytes JSON, 7919 bytes gzipped
live apply of the guest's batches (once, cold JIT): 19.839 ms
first replay (JIT barely warm): read+decode 6.047, apply 1.869, frame 16.089, total 24.004 ms
replay, n=40 after 40 warm-up: read+decode median 1.007 p90 1.135; apply median 0.227 p90 0.251; frame median 2.959 p90 3.385; total median 4.244 p90 4.717
events from the replayed widgets, dropped: 162 (over 80 replays)
pixels differing, replayed vs live: 0
```

A first replay comparison placed the live and replayed trees side by side and found 8 pixels
differing by 1–3 of 255 on rounded edges (anti-aliasing at another x offset); drawn at the same
position, they are identical. A first (B) run was discarded: the hidden slot's buttons were recorded
before the list's rows, so "open" clicked the wrong widget. The driver now composes the slot after
the rows and checks every open's batch for the opened item's title.
