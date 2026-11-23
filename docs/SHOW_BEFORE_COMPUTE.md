# NF5: show before compute (design)

Status: **design, revision 3, for review. No production code.** Written 2026-11-24 for WS7 of
[`PLAN_2026-11.md`](PLAN_2026-11.md). The owner chose this step on 2026-11-24, on one condition:
"it has to be executed perfectly". Revision 2 answered the first independent review (verdict: needs
rework). Revision 3 answers the second (verdict: needs rework, scoped to (B); H0 and (A) approved
after fixes): (B)'s reveal is now a protocol (sections 3.2 and 3.5, invariants I4b, I13 and I14),
and the (A) fixes are in. [Appendix A](#appendix-a-review-response) answers every finding of both
rounds, one line each. **(B) needs one more review of sections 3.2 and 3.5 before B0 or B1
starts** (section 9).

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
- [Appendix D: the floor step under Perfetto (R0, A12)](#appendix-d-the-floor-step-under-perfetto-r0-a12)

## 0. Summary

**(A) Cached first frame: approved by the second review, after the fixes in this revision; go to
H0 and then the A12 gate.** On the Mac, drawing the stored list costs the host about 24 ms the first
time (4 ms when warm). The guest's first composition of the same list costs 45–88 ms before
anything is sent. The stored list is 7.9 KB gzipped and draws pixel-identical to the live host.
Only the A12 can say whether this lands within native + 0.4 s (section 7).

**(B) Pre-composed destinations: three steps before any (B) PR.** Changing a hidden detail's
arguments is 2.6–7.5× cheaper than composing it fresh on the Mac. But on the A12 every guest step
pays a large fixed cost. Even a detail that is already composed with the right item took 80–85 ms
(simple) and 131.5–140.5 ms (heavy) from the click to its first draw (series H).

**What (B) reaches on the A12 (inferred, appendix B), tap to the data's frame end:**

| | Simple | Heavy |
|---|---|---|
| Today's best | 217.5 | 548 |
| (B), the guest binding after the tap | about 145–170 | about 225–275 |
| (B) with the host reveal | about 120–150 | about 225–275 (heavy's floor step alone is 111–114.5) |
| Target | about 100 | 150 or less |

**(B) does not reach the targets with data.** The heavy one is out of reach for any design in which
the guest computes after the tap, at today's per-step cost. The simple one might be reachable: of
the floor step's 57.5 ms from click to composed (1.78 ms on the Mac, a ratio of 32 against about 12
for long guest work), much may be waiting rather than composing.

So (B) is a shell + data design with a host reveal. A prepared entry is a shell with no item's data,
which is also the destination's loading UI (3.4). On the tap the host shows that shell itself, in
the reveal's frame, as the retained back already shows the list (its first draw 13–17 ms after the
press on the A12). The guest then answers the tap explicitly: it binds the shell to the item, or
declines it (3.2).

**Before any (B) PR** (section 9):
1. **Decision 7, what "open" means for the bar** (section 11). It is needed now, not after B5: the
   arithmetic above already says the (B) gates fail with data.
2. **R0, the A12 trace of one floor step** (appendix D; tomorrow's phone session, 2026-11-25). It
   says whether about 30 ms of the 57.5 is fixed latency that can be removed. If not, the simple
   target is out of reach too.
3. **R1, a reveal-only arm on the A12**: the shell's first frame on the pixels, with and without
   measuring the shell while idle (3.5).

**Items that differ in structure** cost (B) more. On the Mac, rebinding the heavy detail to an item
with other sections cost 16.0 ms, against 8.6 for the same structure and 46.3 fresh (appendix C,
table B2). On the A12 that would be about 305–315 ms for a heavy open. That figure rests on one kept
run with a wide p90 and leaves out the host's work for 28 new widgets: it is reported, not planned
on (appendix B).

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
    paneTitle = "Orders",      // announced at the swap (I3); stored, so not data
  ) {
    OrdersScreen(page)
  }
  ```

  `CachedFrame` is new, in `keliver-compose`, next to `LocalHostFeatures` (`HostFeatures.kt`). It
  emits only when the host declares `keliver.cachedFrame@1` (I8). It adds one marker modifier to
  the screen's root node, carrying the policy, `ready` and `paneTitle`.
- **Two content levels.** The author must choose; there is no default.
  - `FrameContent.Shell`: an allowlist **for strings**. Every text and URL value in the subtree is
    replaced by a placeholder, unless its node is marked `Modifier.cached()` (a title, a label, a
    section header). **`Shell` removes strings; the structure and every non-text value remain**:
    which nodes exist (an "overdue" card present only when the user is overdue, the number of
    rows), numbers (a `Slider` value, a progress value), booleans (a `Switch`), colours (a status
    colour) and style tokens (an icon's name). A screen where any of those is sensitive must not be
    cached, or must keep that part under `Modifier.notCached()`, which drops the whole subtree. The
    docs say so beside the example. Codegen lists every non-String property for review (2.4).
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
4. Since the content was bound, no input has reached the content: no touch, scroll, focus, key or
   text input.
5. Every scrolling widget in the subtree is at its origin: lazy lists and grids at their first item
   at offset 0, scrolling columns and rows at offset 0, pagers at their first page with no offset.
   This is read from the host widgets' own state, so a position the guest restored also counts.
6. Every widget in the subtree is replayable (2.4). A dialog, sheet, snackbar or skeleton in the
   subtree blocks the write.
7. No property that the schema marks `@BlocksFrameWhenTrue` is true (2.4): a pull-to-refresh
   (`RefreshableLazyList.refreshing`) in progress blocks the write.

Otherwise nothing is written, the previous file stays, and the recorder records
`cachedFrame.writeSkipped(reason)`, the reason being the first condition that failed (`notVisible`,
`navigatedInside`, `input`, `notAtOrigin`, `notReplayable:<widget>`, `refreshing`, `sizeCap`,
`noSession`). **There is no write at `ON_STOP`** or at any other time. The stored frame is therefore
always the screen as first drawn with its data, before the user touched it.

**Where conditions 4 and 5 come from.** The recorder lives in `keliver-treehouse-host`, which cannot
see Compose's scroll or pager state. So both are fed from the Compose UI side, through two small
interfaces in `keliver-widget` (common, host only):
- **Input (condition 4): one observer on the content's root.** `TreehouseContent`
  (`keliver-treehouse-host-composeui`) puts a pointer observer on the content's root in the
  initial pass, which consumes nothing, and an `onFocusChanged` and a key observer beside it. The
  first touch, focus or key event calls `FrameRecorder.inputSeen()`. Text input needs focus first,
  so the focus observer covers it. PR A4a; host test: a touch, a key and a focus each block the
  write; the lazy list's own viewport reports, which are events but not input, do not.
- **Origin (condition 5): `ScrollOrigin`.** `interface ScrollOrigin { val atOrigin: Boolean }`, which
  every scrolling host widget implements from its own state: `LazyListState.firstVisibleItemIndex
  == 0 && firstVisibleItemScrollOffset == 0`, `ScrollState.value == 0`, `PagerState.currentPage ==
  0 && currentPageOffsetFraction == 0f`. Codegen knows which widgets scroll: the schema marks them
  `@Scrolls` (2.4), and `FrameRules` lists their tags. At the `ready` edge the recorder asks every
  such node in the marked subtree. **A node whose tag is in the list but whose widget does not
  implement `ScrollOrigin` blocks the write** (fail closed), so an adopter's own scroller cannot be
  recorded scrolled by mistake. To reach the widgets, `HostProtocolAdapter` gains an internal
  `widgetsUnder(id)` for the recorder. The interface and the recorder's use of it: PR A3c. The
  implementations, one per scroller of the three schemas the production host draws: PR A4a.

  | Schema | Widgets that implement `ScrollOrigin` |
  |---|---|
  | keliver-material | `ScrollableColumn`, `LazyVerticalGrid`, `LazyHorizontalGrid`, `HorizontalPager`, `VerticalPager` |
  | RedwoodLayout | `Row` and `Column` (at origin by definition with `overflow = Clip`; their `ScrollState` with `Scroll`) |
  | RedwoodLazyLayout | `LazyList`, `RefreshableLazyList` |

  Host tests in A4a: each widget, scrolled by one pixel, blocks the write; a guest-set
  `scrollItemIndex` that the host applied blocks it too.

### 2.4 Which widgets can be replayed

The fold works from a list generated by codegen from the schema. Nothing is replayable by default.

- **Schema annotations** (in `keliver-schema`, beside `@Widget` and `@Property`):
  - `@Replayable` on a widget or modifier: it may be replayed;
  - `@Scrolls` on a widget: it scrolls, so it must implement `ScrollOrigin` (2.3, condition 5);
  - `@NeverCached` on a property: its value is never stored, in either level;
  - `@BlocksFrameWhenTrue` on a `Boolean` property: while it is true, nothing is recorded (2.3,
    condition 7);
  - `@StyleToken` on a `String` property: the string names a style or an asset from a fixed set
    (an icon's name), so `Shell` keeps it. Like every non-text value it can still say something
    about the data (a status icon); I2 says so;
  - `@NotData` on a non-String property: it is layout or style, not data (a size, a padding, a
    colour constant), so the review list below leaves it out.
- **Codegen** (`keliver-tooling-codegen`, the step that already writes the protocol code) writes
  `<Schema>FrameRules`: the replayable widget and modifier tags, the scrolling widget tags, the
  `@BlocksFrameWhenTrue` properties, and for each widget and modifier the tags of its text- and
  URL-bearing properties (any property whose type is or contains a `String`, `@StyleToken` aside).
  The fold uses the last list for `Shell`. **For structured values** (a `RichText`'s
  `List<TextSpan>`), `Shell` replaces each string inside the value and keeps the rest (each span's
  size and weight); it never drops the value, so the widget keeps its height (5.2).
- **The review list (M3).** Codegen also writes `<Schema>FrameRules.review.txt`: every non-String
  property of a `@Replayable` widget or modifier that is neither `@NotData` nor `@NeverCached`, with
  its type. These are the values `Shell` keeps. The build prints the list as a warning; with
  `keliver { frameRules { strict = true } }` it fails until each one is classified. A1 classifies
  every keliver-material, RedwoodLayout and RedwoodLazyLayout property and runs strict; the
  scaffolder turns strict on for an app's own schema.
- **The three schemas the production host draws** (`ComposeUiKeliverMaterialWidgetFactory.kt:200–204`:
  keliver-material, RedwoodLayout and RedwoodLazyLayout) are all annotated in A1. Without the other
  two, no screen with a `Column` or a `LazyColumn` could be recorded at all.
  - **RedwoodLayout**: `Row`, `Column`, `Box`, `Spacer` and its nine modifiers are replayable;
    `Row` and `Column` are `@Scrolls` (their `overflow` can be `Scroll`).
  - **RedwoodLazyLayout**: `LazyList` and `RefreshableLazyList` are replayable and `@Scrolls`;
    `RefreshableLazyList.refreshing` is `@BlocksFrameWhenTrue`. Only the rows the guest had
    composed are stored, at the first position (5.2); the guest's `onViewportChanged` reports from
    the replayed list are dropped with the other events (2.6).
  - **keliver-material**: every widget and modifier is replayable except ten
    ([`keliver-material-schema.api`](../keliver-material-schema/api/keliver-material-schema.api)):
    - window or popup: `AlertDialog`, `Dialog`, `BottomSheet`, `DropdownMenu`, `Snackbar`,
      `Tooltip`. A dialog opens its own window, which the frame's container cannot make inert, so
      it must never be replayed;
    - live input: `TextField`, `OutlinedTextField`, `TextInput` (decision 14 proposes replaying
      them empty);
    - a skeleton: `Shimmer` (below).

    `ScrollableColumn`, `LazyVerticalGrid`, `LazyHorizontalGrid`, `HorizontalPager` and
    `VerticalPager` are `@Scrolls`. `Icon.name`, `ListItem.leadingIcon` and `ListItem.trailingIcon`
    are `@StyleToken`: without it `Shell` would replace an icon's name and draw the neutral
    placeholder icon.
- **Animated widgets: each is snapped or excluded.** A replay must not play an animation the live
  host would not play at that moment.

  | Widget | Rule | Why, and the test (A4a) |
  |---|---|---|
  | `AnimatedVisibility` | replayable; **snaps** | Its host widget composes Material's `AnimatedVisibility` with the stored `visible` already set, and a first composition takes that state with no enter transition. Test: replayed with `visible` true and false, the first frame already shows the end state at full alpha and size |
  | `AnimateContentSize` (modifier) | replayable; **snaps** | `animateContentSize` animates only changes after its first measure, so the replayed node lays out at its final size. Test: first-frame size equals the live host's settled size |
  | `AnimatedBorder` | replayable, **after A4a makes its loop clock-phased** | Today its loop starts when it is composed (`ComposeUiAnimatedBorder.kt:73`), so the frame and the live tree beneath it would be at different phases and the border would jump at the swap. A4a derives the phase from the frame time (`frameTimeMillis % durationMs`) instead; both trees then draw the same phase in every frame. Test: the frame and a live host draw the same pixels at the same clock |
  | `Shimmer` | **excluded** | A skeleton on screen at `ready` means part of the screen is still loading. Storing it would store a loading state, and its loop could not be matched at the swap either. Blocks the write (`notReplayable:Shimmer`) |
  | `CircularProgressIndicator`, `LinearProgressIndicator` | replayable | Determinate (`progress ≥ 0`): static. Indeterminate (`progress < 0`, a "loading more" row): Material's own loop, which restarts at the swap. This is the one movement besides text reflow that I4 allows |
  | `HorizontalPager` with `autoScrollMs > 0` | replayable at its first page | The replay never auto-advances (the frame is inert). The live tree beneath the frame is composed hidden (2.7), and its auto-advance timer starts only when shown, so it is still on the first page at the swap |

- **Text fields.** As shipped in this design, a screen with a text field (a search box) is not
  recorded: the three input widgets block the write. Decision 14 asks whether to replay them with
  their value `@NeverCached`, drawn empty.
- **Images.** A replayed image is drawn from the image loader's disk cache only, never the network.
  A miss draws the widget's placeholder. In `Shell`, image URLs are dropped unless `cached()`. A
  replayable image needs a fixed size, or the placeholder's size differs and I4's height test fails.
- **Keys and descriptions.** In `Shell`, the strings inside modifiers are replaced as well:
  content descriptions, semantics labels, test tags. Lazy-list item keys, where the protocol carries
  them, are replaced by their position in both levels.

### 2.5 Host (shared by Android and iOS)

Both production hosts draw with Compose UI (`KeliverScreen.kt` on Android, `MainViewController.kt`
on iOS), so most of the host side is common code.

| Piece | Where | What |
|---|---|---|
| `FrameRules` | generated per schema (2.4) | the replayable, scrolling and blocking tags, the text-bearing properties, the review list |
| `ScrollOrigin`, `TextLines` | new interfaces, `keliver-widget` (A3c); implemented by the host widgets of the three schemas (A4a) | a scroller reports whether it is at its origin (2.3); a text-bearing widget reports how many lines each of its text properties laid out to (5.2) |
| `ChangeFolder` | new, `keliver-treehouse-host` (host only) | folds batches (Create, Property, Modifier, Add, Move, Remove) into the current tree of the marked subtree; prunes hidden entries; applies `Shell`, `notCached`, `@NeverCached` and position keys; emits a `SnapshotChangeList` (`keliver-protocol/.../SnapshotChangeList.kt`) |
| `FrameRecorder` | new, `keliver-treehouse-host` | wraps the content's change sink (as `KeliverTiming.firstFrame` does), feeds `ChangeFolder`, takes `inputSeen()` from the root observer, asks the scrollers and text widgets of the marked subtree at the `ready` edge, writes by the rule in 2.3, and records `writeSkipped(reason)` |
| `FrameStore` | new, `keliver-treehouse-host`; a file implementation per platform | `read(key)`, `write(key, frame)`, `clear(reason)`, `clear(route)`. Atomic writes, size caps, checksum (I5). The file format lives here, on the host, not in `keliver-protocol` |
| `CachedFrameContent` | new, `keliver-treehouse-host-composeui` | decodes a stored frame and applies it through a fresh `HostProtocolAdapter` with the app's widget system and a cache-only image loader. Its event sink drops events. Its mismatch handler does not throw: any unknown tag discards the frame. Provides `LocalFrameReplay = true` to the widgets it draws |
| `FrameHandOff` | new, `keliver-treehouse-host-composeui` | the swap state: `ready`, `HANDOFF_LIMIT`, `FRAME_LIMIT`, a deferred update (2.7); provides `LocalContentShown = false` to the live tree beneath the frame until the swap |
| `TreehouseContent` | `keliver-treehouse-host-composeui`, one new parameter and the root observer | `backEnabled`: while false, the content's back callbacks are registered disabled (2.6); the input observer of 2.3 |

`TreehouseAppContent`'s first batch still clears `view.children`. The cached frame never lives in
`view.children`, so that clear does not touch it.

### 2.6 The frame on screen: inert, with back and an indicator

- **Inert.** The frame's container consumes taps and scrolls. It is unfocusable, so no keyboard
  opens. Its semantics are one node, "Loading", polite live region, no actions. (I3)
- **Accessibility at the swap.** When the guest's tree replaces the frame, the screen reader's
  focus is on the "Loading" node, which disappears. So the swap announces the new pane. On Android
  the guest's content root carries `paneTitle` (the `CachedFrame`'s `paneTitle`) from the swap's
  frame on; a new pane title makes the platform send a window-state change, which TalkBack
  announces, and its focus moves into the new pane. On iOS the host posts
  `UIAccessibility.post(notification: .screenChanged, argument: nil)` in the swap's frame, and
  VoiceOver moves to the first element. Host test (I3).
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

The guest's content is composed beneath, hidden, from its first batch. It is composed with
`LocalContentShown = false` until the swap, so host timers that belong to a shown screen (a pager's
auto-advance) start only at the swap; A4a makes those host widgets wait for it. The frame stays on
top until one of these happens, whichever is first:

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
Changed data appears in place. In `Shell`, a placeholder has as many lines as the text it replaced
laid out to, and keeps its `maxLines` (5.2), so it has the same height and nothing below it moves;
within its lines the real text can be wider or narrower than the placeholder. I4 allows that and
nothing else, with an indeterminate progress indicator's loop restarting (2.4).

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

**`parallel` is the shipped default** (`DEFAULT_START_POLICY = PARALLEL`, `StartPolicy.kt:53`), so
the row for `parallel` is the one most users get. Under it the frame waits for the lookup; on a slow
network the lookup can take about as long as the guest's own data, and (A) then gains little on the
default path. Gate A-2b measures that, with a pass bar (section 7), and decision 8 says what to do if
it misses. Its options, priced:
- **(a) Strict, as above** (recommended for the first ship): the frame never shows a release that
  might be replaced. Cost: on a slow lookup the frame comes late.
- **(b) The existing per-channel `cache-first` override.** Cost: it also gives up `parallel`'s
  promise that a newer release loads at this start, for every user of that channel; it is not a
  lever for frames alone.
- **(c) Under `parallel`, draw at once; on `LOAD_NAMED`, drop the frame and show `loading()`.** The
  frame belongs to the cached release; if the lookup names another, the frame goes before any of
  the new release's content shows, so I1 holds. Cost: when a new release is published, each
  device's first start of it shows content → loading → content, once; the user may start reading
  the old screen first. A host test: on `LOAD_NAMED` the frame goes in the frame the step arrives,
  and nothing of the other release is drawn while the frame is up.

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
- **From the guest.** `FrameScope.sessionChanged(id)` (2.2) runs the same code on the host. A guest
  that holds the login reports its session **at every start**, not only on a change, so a change
  the host missed (the guest's storage cleared, say) is caught at the next start: if the reported
  session differs from the record, the frame on screen goes at once (as on a release mismatch,
  2.7) and every frame is deleted. A frame written while a session change is in flight is checked
  after its rename: if the session record changed, the file is deleted.
- **What is stored.** A hash of the session id with a per-install key from private storage, never
  the id.

### 2.10 Navigation

- Only the **first screen of a launch** is drawn from the cache: the route the activity or scene
  opens with (`KeliverHost.launch`, `KeliverRoute.kt`).
- The route and its launch arguments are part of the key. A deep link to another order never shows
  the last order's frame.
- Apps that change data on native screens (D12) call `KeliverHost.clearCachedFrames(route)`.
- **No frame when the guest's state is restored.** Today no guest state is restored across process
  death: `TreehouseContent` binds with `StateSnapshot.Id(null)` (`TreehouseContent.kt:96`). If a
  host ever binds with a non-null snapshot id, the guest may restore a screen other than the one
  first drawn (another tab, a scrolled list), so `CachedFrameContent` draws nothing when the
  binding's `stateSnapshotId` is not null. Host test (I1).

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
for long compositions. Adding the tap before the click and the frame after the draw, (B) without the
reveal lands at about 145–170 ms (simple) and 225–275 ms (heavy) with the data; with the reveal,
which moves the detail's first layout before the tap, the simple one is about 120–150. Appendix B
shows the arithmetic, and R0 (appendix D) whether the floor step's waits can be cut.

So (B) has two parts, both part of the design:
1. **Shell.** A prepared or parked entry holds a shell: the destination's loading UI, with no item's
   data (3.4). Binding it to an item fills the data in place.
2. **Host reveal.** On the tap, the host shows the shell itself, before the guest runs, and the
   guest answers the tap explicitly (3.2). The shell's first frame should cost what the retained
   back costs: its first draw came 13–17 ms after the press on the A12 (R1 measures it). The
   guest's bind then fills the data.

### 3.2 End to end, and the reveal protocol

```
list shown with its data (`ready`), guest idle: no input, scroll or guest frame for 300 ms
  guest (keliver-nav): composes one entry per declared kind, in slices, as a SHELL (route = the kind's
                       `unbound` value, epoch e). Entry state Prepared. Its effects are held.
  host: the entry becomes Prepared only in the batch that carries its content at epoch e (I14);
        the container MEASURES it while idle, never places, draws or makes it a back target
tap on row N (the row carries Modifier.revealsPrepared(DetailKind))
  host, in the click handler: if that kind's entry is Prepared, shows it as REVEALED (inert) in this
        frame, takes token t, registers its own back callback, and sends reveal(t) and the click
        in ONE Zipline task, reveal(t) first
  guest: runs the click: navigate(Detail(N)) binds the entry (route = Detail(N), epoch e+1,
         effects released); in its next frame keliver-nav ANSWERS t: bind(t)
  host: applies the batch; the entry goes from Revealed to Shown, live, only in the batch where its
        content at epoch e+1 has been applied (I14). Shared nodes have not moved (I4b)
back
  host: retained back as today (NF1); guest: pops the entry and PARKS it (route = unbound, epoch
        e+2), or disposes it if something from the visit still runs (3.3)
```

A kind that is not revealable (3.4), or a tap that finds no Prepared entry of the kind (it is still
being prepared, or it is being parked), skips the reveal. Then the click goes to the guest, which
binds the entry, and the host shows it in the batch that carries its content at the bound epoch
(I14): the list stays until the detail is there, with no frame of the shell or of the last item.

**The reveal protocol.** One host feature, **`keliver.nav.reveal@1`** (I8), separate from the
container's `keliver.nav.retainedContainer@2`. A guest that marks a row `revealsPrepared` against a
host without it gets no reveal and no mismatch: keliver-nav emits the modifier only when the host
declares the feature.

| Message | Direction | Meaning |
|---|---|---|
| `reveal(token, entryKey)` | host → guest, an event on the container | the host has revealed this entry for the click that follows. Sent in the same Zipline task as the row's click event, before it (a new `UiEventSink.sendEvents(first, second)` that launches once), so nothing on the Zipline thread can run between them |
| `cancelReveal(token)` | host → guest, an event on the container | the user left the reveal (back), or `REVEAL_LIMIT` passed. Sent through the same event sink, so the guest handles it after the click |
| `revealAnswer(token, outcome)` | guest → host, a property of the container | keliver-nav's answer to the newest token it has handled: `Bind(entryKey)`, or `Decline(reason)` with `reason` one of `noNavigation`, `otherKind`, `cancelled` |
| `cancelAcked(token)` | guest → host, a property of the container | the newest `cancelReveal` the guest has handled, after undoing what the click did |

**The guest answers every token, explicitly, in its first frame after the click.** keliver-nav
records the token in snapshot state when `reveal(t)` arrives, which schedules a composition even if
the click changes nothing. In that composition (the click has run by then: both came in one task,
and no frame tick can be queued between them) it answers:
- the click navigated to a route of the revealed kind, which bound the revealed entry → `Bind`. The
  bound content, the entry's new state and the answer are in the same composition;
- the click navigated to another kind (a login, a paywall) → `Decline(otherKind)`. The other
  destination is composed in the same composition, so the host goes from the shell to it with no
  list frame between (decision 15). A debug build fails loudly here: a row marked
  `revealsPrepared(DetailKind)` whose click opened another kind is a bug in the app;
- the click did not navigate → `Decline(noNavigation)`. A click handler that navigates only after a
  `suspend` (a check, a load) declines, and its navigation comes later as an ordinary one. Such rows
  must not be marked `revealsPrepared`; the debug build warns when a declined token is followed
  within `REVEAL_LIMIT` by a navigation to the revealed kind (`lateBind`);
- a `cancelReveal(t)` was handled before that composition (a back right after the tap, with the
  guest busy) → `Decline(cancelled)`, with `cancelAcked = t`, and the click's navigation already
  undone in the same composition.

The host counts every decline by reason in release telemetry (`nav.revealDeclined(kind, reason)`).
An unrelated guest batch (a ticker, an analytics recomposition) carries no answer, so it changes
nothing: the reveal no longer depends on "the guest's next batch".

**The host's states for one token.**

| Host state | What the user sees | Ends when |
|---|---|---|
| `Revealed(t)` | the shell, **inert** (below) | `Bind(t)` → `Binding(t)`; `Decline(t)` → hidden, when what the decline shows instead is effective (below); back, the shell's back button or `REVEAL_LIMIT` → `Cancelled(t)` |
| `Binding(t)` | the shell, still inert | the entry's state `Shown` and its content at the same epoch are both applied (I14) → **live**, in that frame; back → `Cancelled(t)` |
| `Cancelled(t)` | the list (the entry below), as after a host back | `cancelAcked ≥ t`. Until then the host ignores every answer for `t` and keeps the entry hidden, even if a guest batch says `Shown`: the same rule as the retained back's `backHandled` counter (`Retained.kt:120–139`) |

- **Inert while `Revealed` or `Binding` (H4).** As the cached frame (2.6): every tap, long press
  and scroll on the shell is consumed, never sent to the guest, and shows the indicator at once
  (the `revealIndicator` slot on the container, a thin bar by default). The shell is unfocusable. A
  "Pay" button tapped at 50 ms therefore never reaches a handler composed with `id == null`. The one
  exception is the shell's back button, if the screen marks it `Modifier.revealBack()`
  (`KeliverNavBackButton` does it itself): a tap on it acts as back (below). The entry becomes live,
  with its taps and focus, only in the frame in which its bound content is applied.
- **Back while `Revealed` or `Binding` (H1).** The host consumes it: on Android with its own
  callback, registered on the host back dispatcher **synchronously in the click handler**, so a
  back in the same frame as the tap is taken too, not left to the guest's back state, which reaches
  the host asynchronously (`TreehouseContent.android.kt:33–34`). On iOS, where no system back
  reaches a guest (`TreehouseContent.ios.kt:25`), the shell's `revealBack()` button is the back. The
  host then shows the list in that frame, enters `Cancelled(t)` and sends `cancelReveal(t)`. The
  guest handles it after the click: if the click bound the entry, keliver-nav pops it (and parks it)
  without a result; then it sets `cancelAcked = t`. The guest's stack ends `[List]`, and the detail
  is never drawn after the user backed out of it. When the entry goes live, the reveal's callback
  is removed in the same frame and the retained back (NF1) takes back over.
- **`REVEAL_LIMIT` (H3; proposed 1 s, decision 2).** If no answer for `t` has arrived 1 s after the
  reveal, the guest is hung or in a long pause. The host shows the indicator on the shell for at
  least 300 ms (so it is seen, not flashed), then shows the list, enters `Cancelled(t)` and sends
  `cancelReveal(t)`; telemetry `nav.revealTimeout(kind)`. When the guest catches up it runs the
  click, then the cancel, and ends on `[List]`; its late answers are ignored. So a shell is never on
  screen for longer than `REVEAL_LIMIT` + 300 ms without the guest's answer (I12).
- **After a decline** the shell goes in the same frame as what replaces it appears: for
  `noNavigation` and `cancelled`, the list, in the frame the decline is applied; for `otherKind`,
  the other destination, in the frame it becomes effective (I14: if it is a rebindable kind, its
  content can come a batch after its state), so no list frame shows between the two. The shell
  stays inert meanwhile, within `REVEAL_LIMIT`. The entry returns to `Prepared` (decision 15).

**Accessibility (M8).** While `Revealed` or `Binding` the entry's semantics are one node,
"Loading", marked busy, a polite live region, with no actions and no focusable child. At the bind
(the frame it goes live) the entry's root carries the destination's `paneTitle` (from the bound
route, `KeliverNavKind.paneTitle(route)`), so Android sends a window-state change, TalkBack
announces the pane and its focus moves into it, to the heading first (`Modifier.semantics {
heading() }` on the destination's title). On iOS the host posts `.screenChanged` in the bind's
frame, not at the reveal. Host test (I13).

**What the shell looks like.** A revealable kind's shell is its loading UI (M7, 3.4): the same
layout the destination shows for an item whose data has not arrived. The reveal therefore shows the
same empty state a fresh open would, earlier; the bind then shows it for the item (with what the
arguments carry, a title, say), and the data fills it in place when it arrives. Nodes in both the
shell and the bound tree do not move (I4b).

**Layout (M5).** Today the container measures only the shown entry (`Retained.kt:205–207`), so a
Prepared shell would be measured for the first time in the reveal's frame. Instead the container
measures every Prepared entry with the constraints it gives the shown one, without placing it, in
the layout pass after
the shell's batch arrives, which comes while idle (the preparation runs only then, 3.4). Compose
caches the measurement, so the reveal's frame only places it. A Prepared entry is never placed, so
it is not drawn, not hit-tested and not in the semantics tree. Whether that keeps the reveal's frame
within one frame on the A12 is R1's question (section 7).

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

**Parking is counted in release, not only logged (M6).** A real screen may never park: a Style B
presenter that collects a flow keyed on `Unit` fails the park check, so its entry is disposed at
every pop and (B) gives that kind nothing. The host sees both outcomes, because a parked entry
changes state in the container and a disposed one is removed. It reports them through `KeliverHost`'s
listener as `nav.parked(kind)` and `nav.disposed(kind, leaked)`, where `leaked` comes from the
entry's last batch before removal (`disposeReason = leak`). A kind whose pops are mostly `leaked`
disposals is a kind (B) does not help, and the app's dashboard shows it. B5 measures the ratio on
the reference app's Style B screen, not only on the three-way app.

**Rules for a rebindable destination's author:**
- Work that needs the item runs only while the item is there: `if (id != null) LaunchedEffect(id)
  { … }` (example in 3.4). It leaves the composition at the park, which cancels it. An effect that
  outlives the visit (keyed on `Unit`, collecting a flow) makes the kind fall back to disposal at
  every pop: it still works, without the gain.
- **A Style B presenter is keyed on the item.** The docs show it this way
  ([`SCREEN_ARCHITECTURE.md`](SCREEN_ARCHITECTURE.md), rewritten in B2):

  ```kotlin
  @Composable fun OrderPresenter(id: Long?): OrderModel {
    if (id == null) return OrderModel.Loading            // the shell: the loading UI (3.4)
    val order by produceState<Order?>(null, id) { value = orders.load(id) }   // keyed on id
    return order?.let(OrderModel::Ready) ?: OrderModel.Loading
  }
  ```

  A presenter that collects `orders.updates()` in a `LaunchedEffect(Unit)` instead fails the park
  check.
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
| A route's **kind**, separate from its arguments: `KeliverNavKind(id, unbound, revealable, paneTitle: (R) -> String?)`, given to the controller by `KeliverNavKinds<R>(kindOf: (R) -> KeliverNavKind?)`. Null: not rebindable | `KeliverNavController.kt` | full route equality (`prepared?.route == route`) |
| `NavEntry.route` backed by snapshot state; `navigate(route)` binds the parked or prepared entry of the same kind | `KeliverNavController.kt` | the route is a `val` |
| **An epoch per entry**, incremented in the same snapshot as every route change (prepare, bind, park). The container entry carries `(state, epoch)`; the entry's content root carries `ContentEpoch(epoch)`, emitted by whichever composition composes that content (I14) | `KeliverNavController.kt`, the host frame (`KeliverNavHostBack.kt`) | none |
| **The reveal's answers**: `reveal(t)` and `cancelReveal(t)` handled; `revealAnswer` and `cancelAcked` emitted (3.2); the cancel pops a bound entry without a result | new, `KeliverNavReveal.kt` | none |
| At most one prepared entry per kind, at most `maxPreparedKinds` (default 2) | `KeliverNavController.kt` | one prepared entry, dropped at any stack change |
| Pop parks a rebindable entry (3.3), or disposes it | `KeliverNavController.kt` (`pop`, `popWithResult`) | popped entries are disposed |
| Entry lifecycle `Prepared`, beside `Visible` and `Hidden` | `KeliverNavRetention.kt:122` | a prepared entry is `Hidden`, and its effects run |
| Prepared and parked entries composed in a sub-composition whose effect dispatcher holds coroutines; preparation in slices that yield between frames; a tap cancels a slice | new, `KeliverNavPreparedComposition.kt` | composed in one frame, effects run |
| Per-visit show counts; per-visit helpers `rememberPerVisit {}`, `rememberSaveablePerVisit {}`, `rememberPerVisitLazyListState()` | `KeliverNavRetention.kt`, new `KeliverNavPerVisit.kt` | counts per entry |
| Idle policy: prepare only after the first screen is `ready` (or, without (A), after its first data batch and 300 ms quiet), with no input, scroll or guest frame for 300 ms (`KeliverNavIdle(quietMillis = 300)`, decision 16); drop prepared and parked entries on memory pressure and in the background | new, `KeliverNavIdle.kt`, fed by host signals | NF3 prepares on touch-down |
| `Modifier.revealsPrepared(kind)` for rows whose click always opens that kind, synchronously; `Modifier.revealBack()` for the shell's back button (`KeliverNavBackButton` sets it). Both emitted only when the host declares `keliver.nav.reveal@1` | new | none |

What a screen author writes:

```kotlin
sealed interface Route {
  data object Orders : Route
  data class Detail(val id: Long) : Route
  data object DetailShell : Route            // the kind's `unbound` value
}
val DetailKind = KeliverNavKind(
  id = "detail", unbound = Route.DetailShell, revealable = true,
  paneTitle = { "Order" },                   // announced at the bind (3.2)
  samples = listOf(Route.Detail(1), Route.Detail(2)),   // for the generated tests below
)

KeliverNavHost(controller, kinds = KeliverNavKinds { r -> if (r is Route.Detail || r == Route.DetailShell) DetailKind else null }) { route ->
  when (route) {
    Route.Orders -> OrdersScreen(onOpen = { controller.navigate(Route.Detail(it)) })  // synchronous
    // ONE call site for both, so binding keeps the composition (two branches would compose anew)
    is Route.Detail, Route.DetailShell -> DetailScreen(id = (route as? Route.Detail)?.id)
  }
}

@Composable fun DetailScreen(id: Long?) {
  // Work that needs the item runs only while it is there: it starts at the bind, stops at the park.
  val order = if (id != null) produceState<Order?>(null, id) { value = load(id) }.value else null
  DetailLayout(order)   // ONE layout: with order == null it is the loading UI, and so the shell;
}                       // the data fills it in place (a separate DetailLoading() would be replaced)
// On the list, once its data is there:
KeliverNavPrepare(controller, DetailKind)
```

**A revealable kind's shell is its loading UI (M7).** In a real app the bind does not bring the
data; it starts the load. If the shell and the loading state differed, the user would see two empty
states in a row: the shell, then the destination's own loading UI, then the data. So for a
revealable kind, the shell (`id == null`) and the bound destination before its data arrives must
draw the same tree, except for what the arguments themselves carry (a title passed in the route).
The generated `assertShellIsLoadingUi(kind)` enforces it.

**A kind whose structure above the fold depends on the item is not revealable** unless its shell
reserves that space (a banner slot drawn empty in the shell and the loading state). Otherwise the
content below jumps down after the user has seen the shell, possibly under their finger. The
generated `assertShellLayoutStable(kind)` (I4b) fails such a kind. The `varied` experiment's detail,
with its banner on even ids, would fail it as written.

**Generated tests per declared kind** (the scaffolder writes them; CI runs them):
- `assertInertWhenPrepared(kind)`: the shell, composed Prepared against capabilities that fail on
  any call (HTTP, bridges, storage, analytics), runs frames; nothing may be called.
- `assertInertAfterVisit(kind)`: bind to an item, run its effects, pop; after the park no coroutine
  from the visit is alive and no call is made in the following frames.
- `assertRebindEquivalent(kind)`: a fresh `Detail(N)` and `Detail(M)` rebound to `N` fold (A's
  `ChangeFolder`) to the same tree, ids aside.
- `assertShellHasNoData(kind)` (revealable kinds only): the shell after a visit to item 7 and after
  a visit to item 8 fold to the same tree, and it contains none of the items' strings. This checks
  what the shell holds at rest; that no frame ever shows item 7 under item 8's tap is I14's host
  test, not this one.
- `assertShellIsLoadingUi(kind)` (revealable kinds only): the shell, and each sample route bound
  against capabilities whose loads never complete, fold to the same tree, texts at nodes the
  arguments feed aside.
- `assertShellLayoutStable(kind)` (revealable kinds only; I4b): on the JVM Compose UI harness with
  the app's own widget system, at 360 × 640 dp and 411 × 891 dp, font scale 1.0 and 1.3: lay out
  the shell, then each sample route with its data (fake capabilities answer at once), and compare
  the bounds of every node present in both, by id (binding keeps the composition, so a node that
  stays keeps its id). Fails if any such node whose shell bounds start within the first viewport
  moves by more than 1 dp. Fails too if a shell node in the first viewport is gone from the data
  tree, unless it is marked `Modifier.shellOnly()` (a spinner): a revealable destination fills its
  shell in place. Without that second check, a destination that swaps a separate loading layout
  for its content would share no ids and pass while everything moved.

The sample routes, and the fixtures their loads answer with, come from the app: the scaffolder
writes the tests with the app's fixtures to fill in. A revealable kind with fewer than two samples
fails its generated tests.

### 3.5 Host

| Change | Where | PR |
|---|---|---|
| The retained container in the production widget system (NF1's production step) | keliver-material's host widgets, templates | B0 |
| **An explicit entry state** on the container's entries, guest-declared: `Shown`, `Retained`, `Prepared`, each with its `epoch`. The host never places or draws a `Prepared` entry, and never makes it a back target; `goBack` skips it. Today the container protects hidden entries by their order alone (`shownIndex` is `lastIndex`, `goBack` takes `index - 1`, `Retained.kt:130–165`). Host feature `keliver.nav.retainedContainer@2` | the container's schema (a protocol change, covered by the schema lock) and its renderer | B0 |
| **State applied only with its content (I14).** The host's effective state of an entry is the newest declared `(state, epoch)` whose epoch equals the `ContentEpoch` of the entry's applied content root. Until both have arrived, in whatever order the two recomposers' changes come, the previous effective state stands | the container's renderer | B0 |
| **Prepared entries measured while idle** (3.2, M5): measured with the container's constraints, not placed | the container's renderer | B0 |
| **The reveal**, host feature `keliver.nav.reveal@1`: the host-only states `Revealed`, `Binding`, `Cancelled` (3.2); the one-task `sendEvents(reveal, click)`; the reveal's own back callback, registered in the click handler; `revealBack()`; inert shell with the `revealIndicator` slot; `REVEAL_LIMIT`; the accessibility of 3.2; the declined and timed-out counts | the container's renderer, `keliver-treehouse-host` (`UiEventSink.sendEvents`) | B4 (prototyped in B1) |
| **Park and dispose counts** (3.3, M6): `nav.parked(kind)`, `nav.disposed(kind, leaked)` | the container's renderer, `KeliverHost`'s listener (templates) | B2 |
| Two host signals with events to the guest: "user input active" (a touch is down or a fling runs) and "memory pressure" (Android `onTrimMemory` at `RUNNING_LOW` or above; iOS `didReceiveMemoryWarning`) | templates, host features | B3 |

Android and iOS share all of it (both draw with Compose UI). They differ in back only: on Android
the reveal takes the system back (3.2); on iOS, where the guest never receives back, the shell's
`revealBack()` button is the back, and the scene's own edge swipe behaves as on any Keliver screen.

### 3.6 What (B) changes and does not change

It changes:
- the retained container's schema (the entry state and epoch, the reveal's events and answers) and
  its renderer;
- three host features (`retainedContainer@2`, `reveal@1`, and the two signals'), and three
  modifiers (`revealsPrepared`, `revealBack`, `shellOnly`) and the `ContentEpoch` marker;
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
  that safe, and in which order are their changes sent? Correctness no longer depends on the order
  (the epoch rule, I14), but B1 must show the rule holds with both orders, by forcing each.
- **The reveal protocol, prototyped on gate A's container** (it is (B)'s whole value, so it is
  proven in the spike, not in B4): the one-task `reveal` + click, the answers, the cancel after the
  click, the epoch rule, and the host tests of I13 and I14 against the guest profile's stack.
- **A slice budget in A12 milliseconds**, stated before the spike: at most 8 ms of guest work per
  slice on the A12 (about 0.3–0.7 ms on the Mac at ratios of 12–30).
- **A hidden entry recomposes when what it reads changes.** The three-way detail reads the app's
  navigation counter, so its hidden copy recomposes at every back (+0.5 to +2 ms on the Mac). Shells
  should read only their arguments; a debug counter reports recompositions of `Prepared` entries.
- **Structure that differs per item** costs more (table B2). The shell should hold the common
  structure; item-specific parts appear at the bind, below the fold or in space the shell reserves
  (I4b).

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
the frame goes in that frame; a host test binding with a non-null `StateSnapshot.Id` draws no frame
(2.10).

### I2. Data freshness is the guest's choice

- **Off by default.** A screen without `CachedFrame` is never stored.
- **Two levels, no default.** `Shell` (an allowlist: only `cached()` texts survive) and `Whole`.
  For money and personal data: `Shell`. The docs show it.
- **What `Shell` does not hide, stated plainly: `Shell` removes strings; the structure and every
  non-text value remain** (2.2): which nodes exist and how many, numbers, booleans, colours, style
  tokens. Where those are sensitive, the subtree is `notCached()` or the screen is not cached.
  Codegen's review list (2.4) names every such property of the app's schema, and strict mode makes
  each one a reviewed choice.
- **Maximum age.** `maxAge` is required; the host caps it (proposed 24 h, decision 2). Age is
  checked by the wall clock and by boot-relative time within one boot.
- **Relative dates.** A text such as "today" goes stale at midnight. `FrameCachePolicy(dayBound =
  true)` adds the local date to the key.

**Enforced:** the guest API; the fold; the review list. **Tested:** unit tests on `ChangeFolder`: in
`Shell`, a text not marked `cached()` never appears in the stored bytes (a search of the file
fails), nor does a content description, an image URL, a lazy key or a string inside a structured
value (a `TextSpan`); a `@StyleToken` value does appear; in `Whole`, a `notCached` value and a
`@NeverCached` value never appear; age expiry by both clocks; `dayBound` at midnight. Codegen
tests: the review list for a test schema names exactly its unclassified non-String properties, and
strict mode fails the build on one.

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
children hidden). At the swap the guest's tree appears with its own semantics, and the new pane is
announced (2.6): `paneTitle` on Android, `.screenChanged` on iOS.

**Tested:** host test: a click, a scroll and an IME request on the frame reach no guest and show the
indicator; one semantics node with no actions; a back press while the frame is up reaches no guest
callback and reaches the activity (Android); the same on the iOS simulator; the next frame after the
swap exposes the guest's nodes, and its root carries the `paneTitle`; on the iOS simulator, a test
hook sees `.screenChanged` posted in the swap's frame and not before.

### I4. Hand-off without a regression

The frame stays on top until the guest says `ready`, bounded by `HANDOFF_LIMIT` and `FRAME_LIMIT`
(2.7). The swap is one frame, with both trees laid out. **Nothing changes height at the swap:** in
`Shell` a placeholder has the line count its text laid out to and keeps its `maxLines` (5.2), and
structured text keeps its spans' styles. Two movements are allowed: the real text's width within
its lines, and an indeterminate progress indicator's loop restarting (2.4). Animated widgets are
snapped or excluded (2.4).

**Tested:** host test with a guest whose first batch is its loading state: frames across the
hand-off never show the loading state and never an empty frame; both timeouts with a fake clock;
the deferred update (2.7); a pixel check that the frame before the swap equals the stored frame and
the one after equals the guest's. **Height equality (A4a):** for every text-bearing widget and
property of the three schemas, the original text and its `Shell` placeholder, laid out at widths of
120, 240 and 360 dp, font scales 1.0 and 1.3, with `maxLines` 0, 1 and 2, over a seeded corpus of
1 to 400 characters (long words, digits, mixed scripts), have equal heights to the pixel. It fails
on any difference. The animated widgets' tests of 2.4.

### I4b. The shell does not move under the data (B)

Between a revealed shell and its destination with the data, a node present in both must not move
by more than 1 dp if it starts within the first viewport, and the shell's first-viewport nodes stay
(a revealable destination fills its shell in place; `shellOnly()` nodes aside). A kind whose
structure above the fold depends on the item is not revealable unless its shell reserves the space
(3.4).

**Enforced:** the rule that a revealable kind's shell is its loading UI (M7, 3.4); the generated
`assertShellLayoutStable(kind)` per revealable kind. **Tested:** that test, which compares bounds
after laying out the shell and each sample route with its data, at two screen sizes and two font
scales (3.4); a test of the test: the `varied` heavy detail declared revealable fails it (its banner
on even ids moves every section down by 32 dp). **Measured:** B5's layout-shift measure, on the
device, for each open: the largest displacement of a shared first-viewport node between the
reveal's frame and the data's frame (gate B-8, section 7).

### I5. Privacy and security

- **Where.** Android: `noBackupFilesDir` (app-private, excluded from Auto Backup and device
  transfer). iOS: `Library/Caches` with `isExcludedFromBackup`, default file protection. Never
  shared storage.
- **Never transmitted.** A frame, or any part of it, is never sent off the device: not in
  telemetry (the `cachedFrame.*` records carry reasons, sizes and times, never content), not in
  crash reports (the frame's bytes are never attached; a decode failure reports its reason only),
  not in bug reports or logs. The format code has no path to the network, and the D1 clarification
  (section 10) says so.
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
fake loader fails on any fetch); every `cachedFrame.*` record, for a frame holding a known
sentinel string, is searched and does not contain it; a decode failure's report does not contain
the frame's bytes.

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
- **Saved state across process death.** Moot today: no guest state is restored across process
  death (`TreehouseContent.kt:96` binds with `StateSnapshot.Id(null)`). If that changes: a shown
  entry is saved with its route; a prepared or parked entry is not saved, and is rebuilt when idle
  after a restore; and (A) draws no frame for a restored binding (2.10).
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
  Without it keliver-nav does what `retentionFor(hostFeatures)` does today: no preparation. The
  reveal needs its own feature, `keliver.nav.reveal@1`, because it lands after `@2` (B4 after B0):
  a guest that emitted `revealsPrepared` or the reveal's answers to a B0-only host would hit the
  mismatch handler. Without `reveal@1`, keliver-nav emits neither, and a tap takes the
  non-revealing path (3.2).
- Host feature names become constants in one place, with a test that each template declares
  exactly what it implements (H0).

**Tested:** `HostFeaturesGuestTest`-style tests: without the feature the guest's batches are
byte-identical to today's; with it, only the marker elements are added; the switch deletes and
stops frames; with `retainedContainer@2` and without `reveal@1`, no reveal modifier or answer is
emitted and a tap on a row opens the detail by the non-revealing path.

### I9. Resource bounds

| Resource | (A) | (B) |
|---|---|---|
| Memory | one decoded frame while drawn (the three-way list: 148 widgets), freed at the swap; two trees during the hand-off, as the update swap already holds | one shell per prepared kind, guest and host; at most `maxPreparedKinds` (2); dropped on memory pressure and in the background. Budget: decision 6 |
| CPU and battery | a read and a decode per cold start; one write per content binding, at most | one composition per kind when idle, and the host's first measure of its shell (3.2); recompositions when what a shell reads changes. Never during input, scroll, fling or animation; never before the first screen is ready. Budget: decision 6 |
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
revealed shell (B) is on screen without the guest's answer for at most `REVEAL_LIMIT` plus the
indicator's 300 ms (3.2), whatever the guest does; a declined shell goes in the frame its
replacement appears, and within the same bound.

**Tested:** host tests with a guest that never loads, one that never sends `ready`; for (B), one
that never answers (the list is back at 1.3 s with a fake clock, and `cancelReveal` was sent), one
whose click does not navigate (`Decline(noNavigation)`, the list in that frame), and one whose
answer comes after `REVEAL_LIMIT` (ignored; the guest's stack ends `[List]`).

### I13. The reveal (B)

Every reveal token gets exactly one answer from the guest (`Bind` or `Decline`), plus a
`cancelAcked` if the host cancelled it. While `Revealed` or `Binding` the shell is inert: no tap,
scroll or focus reaches the guest, the shell's `revealBack()` aside. Back during the reveal is the
host's, never the activity's and never the guest's, and the guest undoes the click after running
it. The reveal is announced as busy, and the destination at the bind (3.2).

**Enforced:** the protocol of 3.2 (the one-task send, the host's own back callback registered in
the click handler, the `Cancelled` state held until `cancelAcked`). **Tested** (host tests, JVM
Compose UI with a fake clock and a scripted guest on keliver-nav, whose bind batch comes 150 ms
after the click; each can fail):
- **back at 0, 20, 80 and 200 ms after the tap** (0: in the tap's own frame, before the reveal has
  drawn; 200: after the bind made the entry live, so the retained back takes it): each ends on the
  list, the guest's stack is `[List]`, the activity received no back, and no frame after the back
  contains a node of the detail;
- **the same through the shell's `revealBack()` button** at 20 and 80 ms, and on the iOS simulator;
- **taps on the shell:** a tap on the shell's "Pay" button at 50 ms reaches no guest handler and
  shows the indicator; after the bind, the same tap reaches the handler with the bound id;
- **decline:** a click that opens a login: the frame after the decline shows the login, and no
  frame between shows the list; a click that does not navigate: the list in the decline's frame;
  `nav.revealDeclined` counted with its reason;
- **an unrelated batch** (a ticker in the list) between the click and the answer changes nothing;
- **accessibility:** while revealed, one node "Loading", busy, with no actions and no focusable
  node; in the bind's frame the entry's root carries the `paneTitle` and the heading is the first
  traversal node; on the iOS simulator, `.screenChanged` posted in the bind's frame, not before.

### I14. An entry's state is applied only with its content (B)

The host shows an entry as `Prepared`, `Revealed` or `Shown` only when the content it shows was
composed for that state's route: never the last item's content under a new item's tap, never a
`Shown` entry whose bound content has not arrived. The entry's state comes from keliver-nav's main
composition and its content from the sub-composition (3.4); they reach the host in either order.

**Enforced:** the epoch rule (3.4, 3.5): an entry's declared `(state, epoch)` takes effect only
when its content root's `ContentEpoch` equals the epoch; until then the previous effective state
stands. A tap finds a revealable entry only in an effective `Prepared` state. **Tested:**
- **back, then tap 20 ms apart:** open item 7, wait for its data, back, tap item 8 20 ms later. With
  the park's content delayed one batch after its state, and again with it one batch before: every
  frame until item 8's data contains no string of item 7, on the revealing path (the shell is not
  yet `Prepared` at 20 ms, so it takes the non-revealing one) and on the revealing path at 200 ms;
- **the non-revealing path:** the `Shown` state one batch before its content: the list stays until
  the content's batch, and then the detail shows with it in one frame;
- **B1's pass criteria include this rule**, shown on the guest profile's stack with both orders of
  the two recomposers' changes forced.

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
- **Placeholders** (`Shell`), built so that the height cannot change at the swap:
  - **The line count is recorded.** At the `ready` edge the recorder asks each text-bearing widget
    in the marked subtree how many lines each text property laid out to (`TextLines`, 2.5; host
    widgets read it from `onTextLayout`). A widget that does not implement it blocks the write
    (fail closed).
  - **The placeholder has exactly that many lines.** A text of `L` characters that laid out to `n`
    lines becomes `n` lines joined by `\n`, each of groups of four figure spaces (U+2007) joined by
    spaces (U+0020), so the line can still break, about `0.85 × L / n` characters long. The 0.85
    keeps a line from wrapping where a figure space is wider than the text's average character;
    the explicit newlines give the line count. There is no cap on the length; the frame's size cap
    bounds it. The widget's `maxLines` and every style property are kept, so the line height is
    the original's.
  - **Structured text** (`RichText`'s spans, `StyledText`): each span's string is replaced the same
    way, and the span's style is kept; the value is never dropped, so it never draws at zero height.
  - A dropped URL is omitted, so the widget uses its default (an image's placeholder, at its fixed
    size, 2.4).
  - The placeholders draw blank, or as skeleton bars if decision 13 chooses bars.
  - I4's height-equality test covers every text-bearing widget and property.
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
| A recording condition fails at the `ready` edge (2.3) | the recorder | nothing visible; the previous frame stays | `cachedFrame.writeSkipped(reason)` |
| (B) The guest does not answer a reveal | `REVEAL_LIMIT` | the shell with the indicator, then the list | `nav.revealTimeout(kind)`; `cancelReveal` sent |
| (B) The guest declines a reveal | its answer | the list, or the other destination, in that frame | `nav.revealDeclined(kind, reason)` |
| (B) A popped entry fails the park check | keliver-nav | nothing visible; the next open composes fresh | `nav.disposed(kind, leaked = true)` |
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
| A-2b | the same under `parallel` (the shipped default) with a **throttled remote lookup** (a server behind a 3G profile: 300 ms RTT, 1.6 Mbit/s, cold DNS and TLS) | **the frame's first draw at least 300 ms before A-3 (guest took over), median of 10.** If it misses, decision 8 chooses between its options (b) and (c) (2.8) |
| A-3 | guest took over | not later than today's data frame for the same build |
| A-4 | cold start without a frame (first launch, after a session change) | not slower than today by more than 10 ms |
| A-5 | the swap | no frame without content between the frame and the guest's (frame trace); pixel diff of the swap frames |
| A-6 | the frame's read, check and decode | reported, on the main thread's critical path |
| A-7 | **correctness on the device**, by log line and screenshot: font scale 1.3, another locale, dark mode, sign-out, a deep link to another route, a new release | each: no frame drawn, the log line names the key field |
| A-8 | **iOS**: A-1, A-3, A-4 and A-5 on the iOS simulator (cold launch by `simctl launch`, after `simctl terminate`; 10 launches), then once on the iPhone when it is signed (PLAN section 10, item 5) | the same ordering as the A12: first content drawn before the guest took over, and A-4 not slower than today by more than 10 ms; A-5's frame check by the simulator's screen recording |

What "data" means: with `Whole`, first content drawn already shows the list's data from storage, so
A-1 is the native comparison and A-3 says when it is live. With `Shell`, the data regions are
placeholders, so only A-3 counts as data. The report gives both, per screen. Decision 1 is whether
A-1 counts for the bar.

### (B) Before any (B) PR: R0 and R1

| Step | What | Pass, or what it decides |
|---|---|---|
| **R0** | **The A12 trace of one floor step** (appendix D): series H's press-on arm (the detail already composed with the right item; click → composed 57.5 ms simple, 111–114.5 heavy), under Perfetto with `x=trace`, decomposed into the hop to the Zipline thread, the guest's event, the wait for its next frame, the guest running, preempted or asleep, the batch to the main thread, the frame and the present. On 2026-11-25 | **The simple target is reachable only if** the floor step's waits (everything in click → composed that is not the Zipline thread running) are at least 30 ms, median, each with a named mechanism that a host or runtime change could remove, **and** the Zipline thread runs for at most 25 ms of it. Then the simple open with the reveal, about 120–150 ms, could come down to about 90–120 (appendix B). If it runs for 40 ms or more, or the waits are under 15 ms, the simple target is out of reach for (B) too, and decision 7's answer governs. Between the two: reported to the owner with decision 7. Heavy is out of reach either way (its floor step alone is 111–114.5 ms) |
| **R1** | **A reveal-only arm** on the prototype (gate A's container in the three-way bench, a small bench PR): the host shows a hidden, already composed detail on the click without asking the guest, with and without measuring it while idle (3.2). Perfetto on the phone, as series C: click → the reveal's frame presented | the shell's frame presented within 50 ms of the click (the retained back's frame is presented 36.1 ms after the press, series C), simple and heavy; with the idle measure, the reveal's `Record View#draw()` under 10 ms. If it misses, the shell's first layout is the cost to cut before B4 |

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
- `native,detailfetch`: native's detail loading its item after the tap, from the same fixture: the
  comparison decision 7's answer (b) needs for destinations that fetch.
- All arms on keliver-nav itself, not the profile's stand-in stack, and on the reference app's
  Style B screen for B-9.

How each open is judged depends on decision 7 (section 11), which is made before B0.

| Gate | Measure | Pass |
|---|---|---|
| B-1 | open, simple, tap to data drawn (frame end) | target about 100 ms; expected 120–150 with the reveal (appendix B), less if R0 found removable waits |
| B-2 | open, heavy, tap to data drawn | target 150 ms or less; expected 225–275 |
| B-1r, B-2r | with the reveal: tap to the shell's first draw and its frame presented | within 50 ms of the click (R1's bar); data as B-1, B-2 |
| B-1f | `detailfetch`, guest with the reveal against `native,detailfetch`: tap to the first draw of the destination, and tap to its data drawn | under decision 7's answer (b): first draw within native's + 16.7 ms (one frame), data within native's + 100 ms |
| B-3 | a tap while a preparation slice runs | no slower than open without preparation |
| B-4 | back | no regression against `x=retainhost` |
| B-5 | the generated inertness tests on the three-way app and the reference app's declared kinds | zero calls out |
| B-6 | `varied` and `detailfetch` | reported against `x=prepkind` |
| B-7 | iOS simulator, the same arms | the same ordering as the A12 |
| B-8 | layout shift (I4b): per open, the largest move of a first-viewport node present in both the reveal's frame and the data's frame | at most 1 dp on every open |
| B-9 | parks against disposals (3.3): `nav.parked` and `nav.disposed` per kind over a browsing script, three-way app and the reference app's Style B screen | every declared kind parks on at least 95% of its pops; a kind below that is reported with its leaked effect |
| B-10 | reveal outcomes over the same script | no `revealTimeout`; declines only where the script opens another kind |

### Regressions to watch

| What | How | Limit |
|---|---|---|
| Scroll jank from idle preparation (B) | series F3's flings, preparation on and off; frames over 25 ms | no increase beyond run-to-run spread |
| Memory (A and B) | PSS after the list (F5's method), with and without, on the three-way app and the reference app | decision 6 |
| Battery (B) | guest CPU time spent preparing, per minute of browsing, on both apps | decision 6 |
| APK size | F4's method | under 50 KB dex for each half (decision 17) |
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
| Showing the frame under `parallel` before the lookup confirms, and handing it off to whatever release loads | breaks I1 when the lookup names another release. Decision 8's option (c) draws at once but drops the frame on `LOAD_NAMED` instead, which keeps I1 |
| The reveal hidden again "if the guest's next batch does not bind it" (revision 2) | flickers on any unrelated batch (a ticker), has no time bound, and leaves back during the reveal to the guest's asynchronous back state: the app closes, or the detail appears after the user left it (second review, H1 and H3). Replaced by the token protocol (3.2) |
| The guest pushing the entry's state and content in "one batch" by convention (revision 2) | the state comes from the main composition and the content from the sub-composition; nothing orders them (second review, H2). Replaced by the epoch rule (I14) |
| A reveal token carried in every widget's click event | needs a new argument on every clickable property of every schema. The container's `reveal(t)` event, sent in the same Zipline task as the click, gives the same ordering with one event |
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
order matters: H0, then (A); for (B), first the steps that say whether it is worth building
(decision 7, R0, R1) and a review of sections 3.2 and 3.5, then B1, then the production PRs once
NF1's production container exists (B0).

**H0 and (A)**, approved by the second review after this revision's fixes:

| PR | Module(s) | What | Tests | Gate |
|---|---|---|---|---|
| **H0** | templates, `keliver-treehouse-host-composeui` | host feature names as constants in one place; templates declare what they implement; `TreehouseContent(backEnabled)` and its use for hidden content; the lastGood record with sequence and module digest, written with `commit()` | a test that each template declares exactly its features; back with hidden content reaches no guest | apiCheck; reference-app and ios-host workflows |
| **A1** | `keliver-schema` annotations, `keliver-tooling-codegen`, the keliver-material, RedwoodLayout and RedwoodLazyLayout schemas | `@Replayable`, `@Scrolls`, `@NeverCached`, `@BlocksFrameWhenTrue`, `@StyleToken`, `@NotData`; generated `FrameRules` and the review list, strict mode; **all three schemas annotated** (2.4), every non-String property classified | codegen tests (the lists for a test schema; strict mode fails on an unclassified property); the staleness guard | apiCheck |
| **A2** | `keliver-protocol`, `keliver-compose`, `keliver-protocol-host` | `FrameCacheTags` (lock test); `CachedFrame` (with `paneTitle`), `cached()`, `notCached()`, `FrameScope`; `HostProtocolAdapter` consumes the reserved tags | batches byte-identical without the feature; with it, only the markers; reserved tags never reach the mismatch handler | apiCheck |
| **A3a** | `keliver-treehouse-host` | `ChangeFolder`: the fold, pruning by entry state, `Shell` (placeholders by line count, strings inside structured values, `@StyleToken` kept), `notCached`, `@NeverCached`, position keys | the round-trip tests of I11 (raw batches on one host, the folded snapshot on another, trees compared); random batch sequences; I2's byte searches | apiCheck; unit tests on JVM, Android and the iOS simulator |
| **A3b** | `keliver-treehouse-host` | `FrameStore`, the format, the key and invalidation, the session record and fail-closed rule, the off switch, clearing, size caps | I1's key tests, I5, I10 | the same |
| **A3c** | `keliver-treehouse-host`, `keliver-widget`, `keliver-protocol-host` | `FrameRecorder`: the rule of 2.3 with all seven conditions, `inputSeen()`, `ScrollOrigin` and `TextLines` (the interfaces) and `widgetsUnder(id)`, `writeSkipped(reason)` | I11's navigation test; one blocked write per condition, each recording its reason; a scroller tag without `ScrollOrigin` blocks the write | the same |
| **A4a** | the composeui modules of keliver-material, RedwoodLayout and RedwoodLazyLayout; `keliver-treehouse-host-composeui` | the hooks: `ScrollOrigin` on every scroller of 2.3, `TextLines` on every text-bearing widget, the root input observer in `TreehouseContent`; `AnimatedBorder`'s clock-phased loop; the pager's auto-advance waiting for `LocalContentShown`; replay snapping (2.4) | per widget: scrolled by a pixel blocks the write; I4's height-equality test; the animated widgets' tests (2.4); input observer tests | apiCheck; the height test |
| **A4b** | `keliver-treehouse-host-composeui` | `CachedFrameContent` (inert, cache-only images, indicator slot, no frame for a restored binding), `FrameHandOff` (both limits, deferred update, `LocalContentShown`), the swap's accessibility | I3, I4, I12 host tests; **0 differing pixels on all three schemas**, replayed against live, on real multi-batch sequences from the sample app's screens | the host tests; the pixel gate |
| **A5** | templates (Android, iOS), docs | the start table on `StartChoice.mode` (and option (c) if decision 8 takes it), the `codeLoadSuccess` check, `session()` and `accounts = NONE`, storage dirs, the off switch from the index, adopter docs (what `Shell` does not hide, I2) | template tests (start table; backup exclusion; fail closed) | reference-app and ios-host workflows |
| **A6** | sample, benchmarks | the bench's `x=cachedframe`, both marks, the throttled lookup; the iOS simulator arm | — | **A-1 to A-8**; the owner reads them before (A) is on by default |

**(B)**, in this order. Its PRs wait for the first four steps:

| Step or PR | Module(s) | What | Tests | Gate |
|---|---|---|---|---|
| **D7** | — | the owner decides what "open" means for the bar (decision 7) | — | before B0 and B1 |
| **R0** | none (a phone session) | the floor step under Perfetto (appendix D), 2026-11-25 | — | appendix D's rule: is the simple target reachable? |
| **R1** | sample (bench only, prototype) | the reveal-only arm (section 7) | — | R1's bar (section 7) |
| **Review** | this document | an independent review of sections 3.2 and 3.5 (the reveal), I4b, I13 and I14 | — | before B0 or B1 starts |
| **B1** | `keliver-nav` (spike, guest profile, gate A's container) | held-effect sub-composition, slices, a tap during a slice; **the reveal protocol** (tokens, answers, cancel, the one-task send) and **the epoch rule**; the checklist in 3.7 | the guest profile; I13's and I14's host tests against the prototype | "feasible with public Compose API, within the slice budget, **and I13 and I14 hold with both orders of the two recomposers' changes**"; else (B) stops and goes back to the owner |
| **B0** | precondition (NF1's production step), the container's renderer | the retained container in the production widget system, with the entry state, the epoch rule and the idle measure (3.5); `keliver.nav.retainedContainer@2` | NF1's; back never reaches a `Prepared` entry; I14's host tests | NF1's |
| **B2** | `keliver-nav`, docs | kinds, shells, epochs, bind and park, the park check, `Prepared`, per-visit counts, the generated tests (all six), park and dispose counts, the retention docs rewrite with the Style B presenter | I6 and I7 unit tests, "inert after a visit" | apiCheck |
| **B3** | `keliver-nav`, templates | the idle policy and the two host signals | idle policy with a fake clock; host signal tests | apiCheck |
| **B4** | the container's renderer, `keliver-treehouse-host`, `keliver-nav` | the production reveal: `keliver.nav.reveal@1`, `revealsPrepared`, `revealBack`, the inert states, the reveal's back, `REVEAL_LIMIT`, accessibility, telemetry (3.2, 3.5) | I12's and I13's host tests, Android and the iOS simulator | apiCheck |
| **B5** | sample, benchmarks, the reference app | the arms in section 7 | — | **B-1 to B-10 on the A12** and the simulator |

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
>    it, and it is never transmitted from the device (not in telemetry, crash reports, bug reports
>    or logs);
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

Each: the question; my recommendation, with the second review's answer where it gave one; the
cost. **Decision 7 is needed first**, before B0 or B1.

1. **Does a cached `Whole` frame count as "the list's data" for the cold-start bar?**
   *Recommendation:* yes, within the screen's `maxAge`, with A-3 (guest took over) reported beside
   it; the bar reads "data (cached or live)". `Shell` counts only as first content. Review: agrees.
   *Cost:* the bar can be met with data up to `maxAge` old, as native apps with an offline cache do.
2. **The limits.** *Recommendation:* `maxAge` cap 24 h; `HANDOFF_LIMIT` 3 s; `FRAME_LIMIT` 5 s;
   512 KB per frame, 2 MB per `cacheName`; `REVEAL_LIMIT` 1 s plus 300 ms of indicator. Add an A6
   arm with a throttled list API, and revisit `HANDOFF_LIMIT` if `handoffTimeout` fires on more than
   1% of starts. Review: agrees. *Cost:* longer shows older data or a stuck shell longer; shorter
   falls back to loading more often on a slow phone (content → loading → content).
3. **Replace `discardPrefetched()` with `session()`.** *Recommendation:* `session(id)` and
   `CachedFrames(accounts = NONE)` in 0.5.0, `discardPrefetched()` deprecated for one release; a
   guest-held login reports its session at every start (2.9). Review: agrees. *Cost:* a migration
   note; an app that calls neither gets no frames.
4. **Indicator: host-drawn or an app slot?** *Recommendation:* a slot (`frameIndicator`, and
   `revealIndicator` for (B)) with a thin bar by default. Review: agrees. *Cost:* two parameters.
5. **keliver-nav behaviour changes** (held effects for every prepared entry; parked pops for
   declared kinds; per-visit show counts, which differ from today only for parked entries; loads at
   the show). *Recommendation:* accept, with the park and dispose counts required (3.3). Review:
   agrees. *Cost:* a documented 0.5.0 change and B2's docs rewrite.
6. **(B)'s memory and battery budget.** *Recommendation:* at most 2 prepared kinds; PSS at most
   8 MB above `x=retainhost`; preparation under 2% of guest CPU per minute of browsing; measured on
   the reference app as well as the three-way app. Review: agrees. *Cost:* the A12 may allow one
   kind.
7. **What does "open" mean for the bar? Decide before B0.** The arithmetic already says (B) misses
   the targets with data (appendix B); waiting for B5 would spend five PRs to confirm it. My
   recommendation is answer (b), the review's split:

   | Answer | "Open" means | It commits us to |
   |---|---|---|
   | (a) Data, always | the destination drawn with its data (B-1, B-2) | (B) can only pass simple, and only if R0 finds removable waits; heavy needs the parked fallbacks (PLAN section 12, a D1 amendment each), so they come to the owner now, not after B5 |
   | **(b) Split (recommended)** | where native has the data at the tap (in the arguments or a cache): the data drawn, as (a). Where native must fetch after the tap: the destination's first draw, because native shows a skeleton there too, and the reveal's shell is its equal (B-1f: within one frame of native's first draw; the data within 100 ms of native's data, same API) | the reveal is worth building for fetched destinations, which most app screens are; destinations with data in hand (the three-way detail) are judged as in (a), so the parked fallbacks still come to the owner for them if R0 says simple is out of reach |
   | (c) The shell, always | the shell's first draw | not recommended: it calls a screen without its data "open" where native shows the data |

   *Cost of (b):* two bars instead of one, and a native fetch-on-open arm in B5.
8. **`parallel` (the shipped default) and the frame.** *Recommendation:* the strict rule for the
   first ship (2.8, option (a)), with A-2b's bar (frame at least 300 ms before A-3); if A-2b misses,
   option (c) (draw at once, drop on `LOAD_NAMED`), not the per-channel `cache-first` override.
   Review: agrees for the first ship. *Cost of (c):* content → loading → content once per device per
   release.
9. **Integrity.** *Recommendation:* a checksum, no Keystore or Keychain key. Review: agrees.
   *Cost:* none beyond I5's stated attacker.
10. **Taps and back on the frame and on a revealed shell.** *Recommendation:* drop taps with the
    indicator; gate back (2.6); the same for (B)'s `Revealed` and `Binding` states, with back taken
    by the host (3.2). Review: agrees, extended to (B). *Cost:* a tap during either needs repeating.
11. **The D1 clarification** (section 10, now with "never transmitted"). *Recommendation:* add it to
    `DECISIONS.md` before A3b merges. Review: agrees. *Cost:* none.
12. **Order of work.** *Recommendation:* H0 → A1, A2, A3a–c, A4a–b, A5, A6; for (B): decision 7 → R0
    (2026-11-25) → R1 → a review of 3.2 and 3.5 → B1 (with the reveal protocol and I14 in its pass
    criteria) → B0 → B2 → B3 → B4 → B5 (section 9). Review: agrees. *Cost:* (B)'s code starts after
    a phone session, a bench PR and a review.
13. **`Shell`'s placeholders: blank or skeleton bars?** *Recommendation:* bars (one rounded bar per
    placeholder line, in the text's colour at low alpha) for `Text`, `StyledText`, `RichText` and
    `ListItem`; blank elsewhere. *Cost:* a replay branch in four host renderers (A4a); an app's own
    text widgets draw blank unless they use the shared helper.
14. **Screens with a search field.** *Recommendation:* replay `TextField`, `OutlinedTextField` and
    `TextInput` inert with their value `@NeverCached`, drawn empty, from A1. *Cost:* three more
    widgets in A1's and A4a's tests; a value the guest restores appears at the swap.
15. **After a declined reveal.** *Recommendation:* the shell goes in the frame its replacement
    appears (the list, or the other destination once effective), never with a list frame between,
    and the entry goes back to `Prepared` (3.2).
    *Cost:* a mis-marked row shows the wrong shell for one guest round trip (about 150–270 ms);
    declines are counted, and debug builds fail on `otherKind`.
16. **The 300 ms idle constant** (no input, scroll or guest frame before preparing). *Recommendation:*
    300 ms as the default of an app setting (`KeliverNavIdle(quietMillis)`), revisited with B5's
    numbers. *Cost:* shorter collides with input and scrolling; longer means a quick user taps
    before the shell exists and gets the non-revealing path.
17. **The size limit** (50 KB of dex for each half, F4's method). *Recommendation:* keep it; a PR
    over it explains why in its description. *Cost:* the fold, store and replay may come close; the
    limit then forces trimming or your waiver.

## Appendix A: review response

### Round 1: the review of revision 1, answered in revision 2

Kept as written then; where round 2 changed an answer, round 2's row says so.

37 rows. **36 fixed** in revision 2, 2 of them with a stated partial disagreement (H1's
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

### Round 2: the review of revision 2, answered in revision 3

Verdict: needs rework, scoped to (B); H0 and (A) approved after the (A) fixes. 51 rows: **48 fixed**
(two with a stated variation on the suggested fix: H3's limit behaviour, M2's line count),
**2 agreed with no further change** (the arithmetic check; round 1's H6 figure), **1 not fixable in
this PR** (the `Main.kt` compile, which CI cannot catch; it is
compiled in R0's build, before this PR merges). None rejected.

| Finding | Response |
|---|---|
| H1 back during the reveal closes the app or brings the detail back | Fixed: 3.2. A token per reveal; the host consumes back with its own callback, registered synchronously in the click handler, shows the list, and sends `cancelReveal(t)`, which the guest handles after the click (same event sink) and acknowledges; until then the host ignores the guest's answers for `t`, as the retained back's `backHandled` counter does. iOS: the shell's `revealBack()` button. I13 tests back at 0, 20, 80 and 200 ms |
| H2 wrong-item flash: state and content from two recomposers | Fixed: I14, the epoch rule (3.4, 3.5). An entry's `(state, epoch)` takes effect only when its content root's `ContentEpoch` matches, so the order of the two recomposers' changes no longer matters; a tap reveals only an effective `Prepared` entry. Test: back then tap 20 ms apart, with both orders forced; no frame contains item 7's strings. In B1's pass criteria |
| H3 "hide if the next batch does not bind" flickers, has no limit, wrong destination | Fixed: 3.2. The guest answers each token explicitly (`Bind` or `Decline(reason)`) in its first frame after the click; unrelated batches carry no answer. `REVEAL_LIMIT` 1 s. Debug builds fail on `otherKind` and warn on `lateBind`; release counts declines by reason. Variation: at the limit the host shows the indicator for 300 ms, then the list, and also sends `cancelReveal(t)`, so a guest that catches up ends on `[List]` instead of opening the detail later |
| H4 taps on the shell reach handlers with no item | Fixed: 3.2, I13. `Revealed` and `Binding` are inert (taps and scrolls consumed with the indicator, unfocusable), live only in the frame the bound content is applied; `revealBack()` the one exception. Host test: "Pay" at 50 ms reaches nothing |
| H5 nothing limits layout movement shell → data | Fixed: I4b. Shared first-viewport nodes move at most 1 dp, and shell nodes stay (filled in place); generated `assertShellLayoutStable(kind)` per revealable kind at two sizes and two font scales; a kind whose structure above the fold varies is not revealable unless the shell reserves the space; B-8 measures it on the device. The `varied` detail fails the test, as a test of the test |
| H6 A-2b has no pass bar; `parallel` is the default | Fixed: A-2b passes if the frame's first draw is at least 300 ms before A-3, median of 10 (section 7). 2.8 prices three options, including (c) draw at once and drop on `LOAD_NAMED`; decision 8 recommends (c) over the per-channel override if A-2b misses |
| M1 allowlist covers one of three schemas; animations; `refreshing` | Fixed: 2.4. A1 annotates keliver-material, RedwoodLayout and RedwoodLazyLayout. Per animated widget: `AnimatedVisibility` and `AnimateContentSize` snap (and why), `AnimatedBorder` replayable once its loop is clock-phased (A4a), `Shimmer` excluded, indeterminate indicators allowed with the restart named in I4, pager auto-advance held until shown. `RefreshableLazyList.refreshing` is `@BlocksFrameWhenTrue` (condition 7) |
| M2 the placeholder breaks wrapped text | Fixed: 5.2. Breakable groups (U+2007 × 4 joined by U+0020), `maxLines` kept, no cap, and the recorded line count: `n` lines joined by `\n`. Structured text keeps its spans. I4's height-equality test over widths, font scales, `maxLines` and a corpus. Variation: recording the line count (through `TextLines`) rather than only dropping the cap, because breakable groups alone still change the line count at some widths |
| M3 `Shell` hides text, not data | Fixed: 2.2 and I2 say "`Shell` removes strings; the structure and every non-text value remain", with examples; codegen's review list of non-String properties, with a strict mode (2.4). Also found: without `@StyleToken`, `Shell` would have replaced `Icon.name` and drawn the placeholder icon |
| M4 conditions 4 and 5 have no mechanism | Fixed: 2.3. Condition 4: a root observer in `TreehouseContent` (A4a). Condition 5: `ScrollOrigin`, implemented by every `@Scrolls` widget of the three schemas, `ScrollableColumn` and RedwoodLayout's `Row`/`Column` included (A4a); the recorder's use and fail-closed rule in A3c |
| M5 the reveal's estimate comes from a laid-out list | Fixed: the container measures `Prepared` entries while idle without placing them (3.2, 3.5, B0); R1, a reveal-only arm on the A12, before any (B) PR (section 7) |
| M6 real screens may never park | Fixed: 3.3. Release counts `nav.parked(kind)` and `nav.disposed(kind, leaked)` from the host; B-9 on the reference app's Style B screen (95% of pops park); the docs show the presenter keyed on the id |
| M7 the bind does not bring data | Fixed: 3.4. A revealable kind's shell is its loading UI (one layout filled in place), enforced by the generated `assertShellIsLoadingUi(kind)` |
| M8 accessibility of the shell | Fixed: 3.2, I13. Busy "Loading" node, unfocusable; at the bind `paneTitle` from `KeliverNavKind.paneTitle`, the heading first; iOS `.screenChanged` at the bind, not the reveal. Host tests on Android and the iOS simulator |
| Low: TalkBack focus at (A)'s swap | Fixed: 2.6, I3. `CachedFrame(paneTitle)`; the content root carries it from the swap's frame; iOS posts `.screenChanged`. Tested on both |
| Low: I5 "never transmitted", and in the D1 clarification | Fixed: I5 (telemetry, crash and bug reports, logs; a sentinel-string test) and point 2 of section 10 |
| Low: no iOS arm for (A) | Fixed: gate A-8 (simulator, then the iPhone when signed) |
| Low: the reveal needs its own host feature | Fixed: `keliver.nav.reveal@1` (3.2, 3.5, I8), with a test that a B0-only host gets no reveal output |
| Low: I7's process death is moot; guard it | Fixed: I7 says it is moot (`TreehouseContent.kt:96`); 2.10 and I1: no frame for a binding with a non-null `StateSnapshot.Id`, tested |
| Low: A3 too big | Fixed: A3a (fold), A3b (store, format, key, session), A3c (recorder rules); A4 split the same way (A4a hooks, A4b replay and hand-off) |
| Low: `variedDetail` in the sample's `Main.kt` never compiled; does CI build `sample/`? | **Not fixable here.** No workflow builds `sample/` (`.github/workflows` has no reference to it), so CI cannot catch it. The line has the same shape as the `heavyDetail` override above it, against the interface member the guest profile compiled. R0's build (appendix D, step 1) compiles it on 2026-11-25; this PR should not merge before that build passes |
| Unasked: blank or skeleton placeholders | Now decision 13 (recommend bars for four text widgets) |
| Unasked: search-field screens | Now decision 14 (recommend replaying text fields empty) |
| Unasked: the hide rule after a decline | Now decision 15, defined in 3.2 |
| Unasked: the 300 ms idle constant | Now decision 16 (an app setting, default 300 ms) |
| Unasked: the 50 KB size limit | Now decision 17 |
| Round 1 C1: conditions 4 and 5 had no mechanism | Fixed with M4 above |
| Round 1 H1: "skip the write" agreed; add `writeSkipped(reason)` | Fixed: 2.3 (eight reasons) and section 6 |
| Round 1 H2: non-text values and structure leak | Fixed with M3 above |
| Round 1 H3: back reappears in (B) | Fixed with H1 above |
| Round 1 H4: the reveal had no limit | Fixed with H3 above |
| Round 1 H6: 225–275 ms accepted | Agreed; no change |
| Round 1 H7: the fallback degrades silently | Fixed with M6 above |
| Round 1 H8: A-2b has no pass bar | Fixed with H6 above |
| Round 1 M3: state and content not atomic | Fixed with H2 above |
| Round 1 M4: the figure-space fix breaks wrapped text | Fixed with M2 above |
| Round 1 M6: no iOS gate for (A) | Fixed with the Low above |
| Round 1 D1, D2: add "never transmitted" | Fixed with the Low above |
| Estimates: the arithmetic checks out | Agreed; no change |
| Estimates: the varied figure is weak | Fixed: section 0 and appendix B report it and say it is not planned on (one kept run, wide p90, no host cost for 28 new widgets) |
| Estimates: the simple claim is not established; the floor is undiagnosed | Fixed: section 0 and appendix B carry the review's arithmetic with the reveal (about 120–150 ms); R0, the Perfetto decomposition of one floor step, is required before any (B) PR, with a pre-registered rule for what makes the simple target reachable (section 7, appendix D) |
| Decision 1 (`Whole` as data) | Folded in: "data (cached or live)", A-3 beside it |
| Decision 2 (limits) | Folded in: the throttled list-API arm in A6; revisit `HANDOFF_LIMIT` above 1% |
| Decision 3 (`session()`) | Folded in: the guest reports its session at every start (2.9) |
| Decision 5 (keliver-nav changes) | Folded in: per-visit counts differ only for parked entries; park and dispose counts required |
| Decision 6 (budget) | Folded in: measured on the reference app too |
| Decision 7 (shell as open) | Changed: decided before B0, with three answers and what each commits us to; the review's split recommended (section 11) |
| Decision 8 (`parallel`) | Changed: option (c) priced; A-2b's bar decides between it and the override |
| Decision 10 (taps, back) | Extended to (B)'s `Revealed` and `Binding` |
| Decision 12 (order) | Changed: decision 7, R0, R1 and a review before B1; the reveal protocol and I14 in B1 (section 9) |
| PR plan: reveal's own feature; A1 three schemas; A3 split; A-2b and iOS | Fixed with the rows above |

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
every bind. **Reported, not planned on:** it rests on one kept run whose p90 is wide (22.92 ms
against a 15.98 median), and the guest ratio leaves out the host's own work to create, apply and
lay out the 28 widgets the rebind adds. I4b also makes such a kind non-revealable unless its shell
reserves the space, which changes the work again.

**With the host reveal:** the shell's first draw needs no guest step. The nearest measured case is
the retained back: its first draw came 13–17 ms after the press on every arm (checkpoint 2,
`x=retainhost`), and its pixels reached the screen 79–91 ms after the touch-down (the pixel table,
including the input pipeline). The shell should do about the same; R1 measures it. The data then
arrives later, and the reveal shortens that too, for the simple screen (the second review's
arithmetic):

| Component, simple, with the reveal | ms | Why it differs from the table above |
|---|---|---|
| tap → click | 30 | same |
| click → composed (the floor step) | 57.5 | series H; R0 decomposes it |
| composed → first draw | 10–15 | the floor's composed → drawn (22.5–27.5) includes the first layout of a never-shown detail, which the reveal (and the idle measure, 3.2) moves before the tap |
| first draw → frame end | 10 | the same layout moved out of the data's frame |
| the rebind's extra guest work | 14–36 | as above |
| **total** | **about 120–150** | |

Heavy stays at about 225–275: its floor step alone (click → composed, 111–114.5 ms) is about twice
the simple one, so no change to the frame's layout brings it near 150.

**What could reach 100 / 150 with data:**
- **Heavy (150 or less with data):** not any design in which the guest computes after the tap, at
  today's per-step cost. Only (i) counting the host-drawn shell as "open" for destinations that
  fetch (decision 7's answer (b)), (ii) taking the destination's composition out of QuickJS after
  the tap (the parked fallbacks, PLAN section 12), or (iii) a cut of 2× or more in the per-step
  cost, which nothing on the roadmap offers.
- **Simple (about 100 with data):** possibly. The floor step is 57.5 ms on the A12 against 1.78 on
  the Mac, a ratio of 32 where long guest work runs at about 12. At 12, the composition itself would
  be about 21 ms, so roughly 35 ms of the step may be waiting: delivering the event to the Zipline
  thread, waiting for the guest's next frame tick, preemption, a slow core, the batch's trip back.
  If about 30 ms of that can be removed, the 120–150 above becomes about 90–120. Nobody has
  measured it, so **R0 decomposes one floor step under Perfetto before any (B) PR** (appendix D),
  with the rule for "reachable" fixed in advance (section 7).

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
| Replayed vs live host, pixels differing (one 360×640 dp frame) | **0** (gate A's widgets, not keliver-material: A4b's gate) |
| **The first replay of the run** (JIT barely warm) | **24.0 ms** (decode 6.0, apply 1.9, frame 16.1) |
| Replay, warm (n = 40 after 40): read + decode / apply / frame / total | 1.007 / 0.227 / 2.959 / 4.244 ms median (p90 4.717) |
| Applying the guest's raw batch to a fresh host, once, cold JIT | 19.8 ms |
| The guest's first composition of the list, before its batch is sent | 87.34 ms (2021), 45.51 ms (2026 BigInt) |
| Events the replayed widgets sent, dropped (lazy list viewport reports) | 162 in 80 replays |
| Smoke run (two batches with a Remove, 33 widgets) | 0 differing pixels |

The comparison is across runtimes (QuickJS against the JVM); it gives the order of magnitude. On
the phone, the host's side of the list's first data frame is NF4's "composed to drawn", 223.5 ms; a
cached frame pays that plus its decode. The fold over real multi-batch sequences was only
smoke-tested here (33 widgets); A4b's gate covers it.

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

## Appendix D: the floor step under Perfetto (R0, A12)

For the phone session of 2026-11-25. Required before any (B) PR (section 9). It reruns series H's
arms ([`THREE_WAY_BENCHMARK.md`](THREE_WAY_BENCHMARK.md), "Series H") under a Perfetto trace, and
splits the floor step (click → composed for a detail already composed with the right item: 57.5 ms
simple, 111–114.5 heavy) into its parts. Nothing in the bench changes: every option it uses is on
main (`x=prepared`, `press`, `heavy`, `trace` and `--ei hold`; `ThreewayActivity.kt:192, 413`,
`Main.kt:64–69`). The files are in [`docs/nf5-r0/`](nf5-r0/).

**What is measured.** `x=trace` adds a trace section for every call into or out of the guest
(`zl:sendEvent`, `zl:sendFrame`, `zl:composed`, `zl:sendChanges n=<changes>`) and for every
main-thread message (`msg <callback>`). Per open, the queries find the click's `zl:sendEvent` (E),
the `zl:sendFrame` that composed the detail (F) and the guest's `composed` report inside it (C),
then the main thread's apply and the frame that drew it, and report, in ms:

| Column | From → to | What it is |
|---|---|---|
| a | the driver's UP message → E starts | the hop to the Zipline thread (part of tap → click) |
| b | E | the guest's `onClick` and the navigation |
| c | E ends → F starts | the wait for the guest's next frame tick; `c_zl_run`: the Zipline thread busy with other work meanwhile |
| d | F starts → C | the recomposition, split by the Zipline thread's state: running (and of it, on the fast cluster), runnable (preempted, waiting for a CPU), other (asleep or blocked) |
| e | C → the batch sent | encoding and sending the batch |
| f, g | batch sent → the main thread's apply message; the apply | the hop back and applying the changes |
| h, i | apply → the next `doFrame`; that frame (`i_layout`: its measure and layout) | the frame that draws the detail |
| j | frame end → presented | the app's actual frame timeline row |
| check_lo, check_hi | C − E's end, C − E's start | the guest's own click → composed (the results file: `composed − click`) must lie between them, ±1 ms, or the open was misidentified |

**The rule, fixed in advance** (section 7, R0), on the simple press-on arm's medians: with
W = c + d_runnable + d_other (the waits inside click → composed) and G = d_running:
- **reachable in principle:** W ≥ 30 ms and G ≤ 25 ms, and each part of W has a named mechanism (c:
  the guest waits for the host's next frame callback after an event; d_runnable: the Zipline thread's
  priority; d_other: what it blocked on). The simple open with the reveal (about 120–150 ms) could
  then come down to about 90–120;
- **not reachable by (B):** G ≥ 40 ms, or W < 15 ms;
- **in between:** reported to the owner with decision 7.

Also reported: the share of G on the fast cluster (on the A12 the 2.3 GHz cluster; time on the
1.8 GHz cluster is a scheduling question, not guest work); the press-off arm's W against the
press-on arm's (if W is fixed latency, it is about the same while G grows); the heavy arm's G, which
calibrates B1's slice budget.

### Steps

**0. The phone.** The Galaxy A12, over adb. Record uptime, battery and charger, temperature,
thermal status and CPU idle before the run (the script records them per arm too). On battery, as
series H, unless the session says otherwise.

**1. The APK: the three-way sample's benchmark build on the BigInt engine, built as checkpoint 2
was** (THREE_WAY_BENCHMARK.md, "A12 checkpoint 2", Method), **from this PR's head**, so the build
also compiles the sample's `Main.kt` change (appendix A, round 2). From the repository root, JDK 17:

```
W=<scratch>/fork; FORK=<scratch>/fork-repo; M2=<scratch>/m2
# the fork's engine as 1.28.0-q20260604, to a scratch Maven repository (never ~/.m2)
for c in clone engine jni android; do bash scripts/zipline-fork.sh $c $W; done
bash scripts/zipline-fork.sh publish $W $FORK
# the prototype, to a scratch Maven repository (as Gate B)
./gradlew publishToMavenLocal -Dmaven.repo.local=$M2 -PkeliverVersion=0.0.0-gateb \
  -PkeliverComposePrototype -DRELEASE_SIGNING_ENABLED=false
# the sample on the fork, Long as BigInt (its own wrapper)
(cd sample && ./gradlew --no-daemon \
  -I ../keliver-compose-gatea-profile/engine/zipline-fork/use-fork.init.gradle \
  -PziplineForkRepo=$FORK -PziplineForkLongAsBigInt \
  :host-android:assembleBenchmark :benchmarks:assembleBenchmark \
  -Dmaven.repo.local=$M2 -PkeliverComposePrototype)
```

Check, and record: the build log names the guest links the init script switched to BigInt; the APK's
`lib/*/libquickjs.so` sizes (`unzip -l`) equal the fork's AAR's (checkpoint 2 inferred this and
did not inspect it); the APK's size and sha256.

**2. Install** as series G and H did:

```
adb install -r -d sample/host-android/build/outputs/apk/benchmark/<the benchmark APK>
adb shell am broadcast -a androidx.profileinstaller.action.INSTALL_PROFILE \
  -n dev.keliver.sample/androidx.profileinstaller.ProfileInstallReceiver
adb shell cmd package compile -f -m speed-profile dev.keliver.sample
```

**3. The trace processor and the files.** The benchmarks APK carries
`assets/trace_processor_shell_aarch64` (androidx benchmark-macro ships it):

```
D=/data/local/tmp/nf5floor
adb shell mkdir -p $D
unzip -p <the benchmarks APK> assets/trace_processor_shell_aarch64 > <scratch>/tp
adb push <scratch>/tp $D/trace_processor_shell && adb shell chmod 755 $D/trace_processor_shell
adb push docs/nf5-r0/cfg.txt docs/nf5-r0/run.sh docs/nf5-r0/prelude.sql docs/nf5-r0/q0a.sql \
  docs/nf5-r0/q0b.sql docs/nf5-r0/measure.sql docs/nf5-r0/q1.sql docs/nf5-r0/q2.sql $D/
```

**4. The screen and the arms.** The three-way app's guest form (`-e form guest`), with its own
fixture (`--ez localApi true`): its list, and the detail opened from a row, simple and heavy.
`bench=nav`, 14 opens and backs, the first 2 dropped, a 100 ms hold, times from the DOWN.
[`run.sh`](nf5-r0/run.sh) runs, each in a fresh process under its own trace
([`cfg.txt`](nf5-r0/cfg.txt): the NF0 config, sched, frequency, atrace `gfx view input am wm dalvik
power`, the app's sections, the frame timeline):

| Arm | `-e x` | Trace | Series H's numbers to compare (untraced) |
|---|---|---|---|
| `simple-on` | `prepared,press,trace` | 60 s | click 198.5–201; click → composed 57.5–58; click → drawn 80–85 |
| `simple-off` | `prepared,trace` | 60 s | click 113; click → composed 112–116.5 |
| `heavy-on` | `heavy,prepared,press,trace` | 75 s | click 488.5–494; click → composed 111–114.5 |

```
adb shell '(nohup sh /data/local/tmp/nf5floor/run.sh < /dev/null > /data/local/tmp/nf5floor/log.txt 2>&1 &)'
# about 4 minutes; done when /data/local/tmp/nf5floor/done.txt exists
```

**5. Check the runs.** Each `<arm>.results.txt` has 14 opens, 14 backs and `{"done":true}`, no
timeout. Medians of `click` and `composed − click` within 15% of series H's (tracing adds a section
per message); if not, say so beside every number.

**6. Query, on the phone** (in `adb shell`; about a minute per query; only a file's last
statement prints, so each run is the prelude plus one query), for each arm `L`:

```
cd /data/local/tmp/nf5floor; T=/data/misc/perfetto-traces/nf5-$L.pftrace
cat prelude.sql q0a.sql > r.sql && ./trace_processor_shell -q r.sql $T > $L.q0a.txt
cat prelude.sql q0b.sql > r.sql && ./trace_processor_shell -q r.sql $T > $L.q0b.txt
# put the UP message's name, exactly as q0b prints it, into upname.txt
sed "s|UPNAME|$(cat upname.txt)|" measure.sql > m.sql
cat prelude.sql m.sql q1.sql > r.sql && ./trace_processor_shell -q r.sql $T > $L.q1.txt
cat prelude.sql m.sql q2.sql > r.sql && ./trace_processor_shell -q r.sql $T > $L.q2.txt
```

- [`q0a.sql`](nf5-r0/q0a.sql): the tap groups. After the start, 28 groups alternating open and
  back; with the press on, an open has 2 events and 2 `composed` reports (the press's preparation,
  then the click); with it off, 1 and 1. If not, the grouping (300 ms gaps) is wrong for this run:
  stop and look before reading q1.
- [`q0b.sql`](nf5-r0/q0b.sql): the main thread's messages in the 200 ms before the click, for opens
  3–5, to find the driver's UP message (the same name in each, about 100 ms after the DOWN's).
- [`measure.sql`](nf5-r0/measure.sql) and [`q1.sql`](nf5-r0/q1.sql): one row per open, the columns
  above. Every kept open's `composed − click` from the results file must lie within
  `check_lo`–`check_hi` (±1 ms); at least 10 of the 12 must, or the identification is wrong.
- [`q2.sql`](nf5-r0/q2.sql): the medians over opens 3–14, with `W_waits`. If `d_running_fast` is 0
  everywhere, the `cpufreq` track was not found: report `d_running` alone.

**7. Keep everything.** Pull `/data/local/tmp/nf5floor` and the three traces
(`/data/misc/perfetto-traces/nf5-*.pftrace`, about 45 MB each) to the session's scratch directory,
so any number can be queried again (NF0's traces were deleted, and its numbers cannot be split
again). Then delete them from the phone.

**8. Record** the result as a new series in THREE_WAY_BENCHMARK.md (phone state, the three arms'
q2 medians, the rule's verdict) in its own PR, and the verdict in this document's section 0.
