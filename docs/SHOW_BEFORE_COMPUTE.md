# NF5: show before compute (design)

Status: **design, revision 6; section 11 decided by the owner 2026-11-24. No production code.** Written 2026-11-24 for WS7 of
[`PLAN_2026-11.md`](PLAN_2026-11.md). The owner chose this step on 2026-11-24, on one condition:
"it has to be executed perfectly". Revision 2 answered the first independent review (verdict: needs
rework). Revision 3 answered the second (needs rework, scoped to (B)): (B)'s reveal became a
protocol (sections 3.2 and 3.5, invariants I4b, I13 and I14). Revision 4 answers the third: **(A)
approved; (B) approved after fixes**, with the reviewer's word that no fourth design round is needed
if the fixes go in as written. They are in: the `Pending` state and the end of `Cancelled` (I14),
the reveal's back callback with a lifetime and a host-owned back while cancelled, `REVEAL_LIMIT`
from tap to live, one outstanding reveal at a time, and the medium and low findings (3.2, 3.5, I12
to I14). R0's files were changed as the review asked and run end to end on an emulator
(appendix D). Revision 5 answers the fourth, a check of revision 4 (**(B) approved after fixes, no
new design round; R0 ready**): a reveal overrides `Pending`, `Cancelled` ends on the ack's epoch,
`CANCEL_LIMIT` releases only the back, the reveal's back callback is owned by a container-level
holder from the click on (3.2, I12 to I14), and R0's rule counts all of the guest's running time in
G, on any cluster, and only the opens whose guest was idle at the UP (section 7, appendix D).
Revision 6 answers the fifth, a final check of revision 5 (**fix first**, no new design round, R0
not affected): the back-callback holder re-adds the current callback when it enters a new
composition (activity recreation), the cancel's ack carries an `e_ack` for every entry it left in
the container, defined once as the epoch right after the cancel, and an entry under a reveal draws
its current content, inert, with the content-first order's own frames in I14 (3.2, 3.5, I12 to I14).
[Appendix A](#appendix-a-review-response) answers every finding of the five rounds, one line each.
**Amended by A1's PR (2026-11-28)** for Q17's retarget ([`MS4_PLAN.md`](MS4_PLAN.md) 2.2): section
2.4's schemas (the generated groups, not legacy's), section 9's A1, A4a and A4b rows, and the
scroller table in 2.3; and with what A1 settled in 2.4 (scrolling modifiers, structured style
tokens, the checked-in review list and where strict mode is switched on). No other change of design.

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
1. **Decision 7, what "open" means for the bar** (section 11): **decided by the owner on
   2026-11-24, (b), the split**, with round 3's three conditions. It was needed before B5 because
   the arithmetic above already says the (B) gates fail with data. (B) now waits only on R1 (R0 ran
   on 2026-11-26, below).
2. **R0, the A12 trace of one floor step** (appendix D; planned for 2026-11-25, run on
   2026-11-26), on a new arm whose click reaches an idle guest, as (B)'s will. It says how much of
   the step is waiting that a host or runtime change could remove, and projects the simple open from
   the finger lifting: reachable only if the projection is at most 100 ms with the guest composing
   for at most 25. If not, the simple target is out of reach too. Its scripts and queries were run end
   to end on an emulator on 2026-11-24, which also compiled the sample's `Main.kt` change.
   **Run on the A12 on 2026-11-26. Verdict: reachable in principle, with a small margin and three
   caveats** ([`THREE_WAY_BENCHMARK.md`, "NF5 R0"](THREE_WAY_BENCHMARK.md#nf5-r0-the-floor-step-under-perfetto-a12-2026-11-26)).
   All 12 opens were idle at the UP.
   - **G = 22.4 ms**: the guest composing, all of it on the fast cluster.
   - **W = 8.1 ms**, all of it named. It is the median of the per-open values; the column medians
     do not add up to it (c 15.1 − c_zl_run 6.6 = 8.5). c1, median 14.2 ms, of which the Zipline
     thread's own work (`c_zl_run`, median 6.6) is not a wait: the guest's frame tick waits for the
     next vsync on the main thread. c2, 0.8 ms: the hop back to the Zipline thread. d_runnable and
     d_other are 0.
   - **P = 87.9 ms** with T measured to the frame that actually drew the detail, to its buffer
     queued (82.0 ms). On q2's printed T (59.1), P = 65.0. q2's T is too short in 9 of 12 opens:
     the press's ripple, still animating on the RenderThread after the 400 ms hold, moves the
     detail's frame, and the matched frame-timeline row is zero-length. At a ratio of 30, P = 109.9
     (87.0 printed).
   - **The margins**: 2.6 ms on G, 12.1 ms on P.
   - **Caveat 1, which "frame end".** The targets and native's 28 ms are frame end: the end of the
     main thread's work for the frame (section 1). That is the like-for-like reading, because
     native's 28 ms is not measured to the display. T has three readings: the main thread's frame
     end (71.2, P about 77.1); the frame's buffer queued (82.0, **P = 87.9, the one recorded**, the
     most conservative of the frame-end readings); and on the display, appendix D's pre-registered
     "presented" (about 45 ms later in the one open checked, P about 133, which would make the
     verdict **not reachable**). The owner confirms the reading.
   - **Caveat 2, the margin is less than one frame.** The app draws only at vsyncs, 16.7 ms apart.
     Quantized to them, P ≈ 98.7, a margin of about 1.3 ms (about 115 at a ratio of 30). Removing
     W moves no open's frame, so the frame-clock change named for c1 would not, alone, bring the
     simple open's frame forward on the A12. This matters for R1 and (B).
   - **Caveat 3, the charger.** The run was on the charger; appendix D says on battery. Series H's
     guest step on the charger is within −4% to +8% of battery, so G on battery is about 23.4 at
     most, still under 25 (inferred; the idle arm was not run on battery).
   - **The heavy arm's G** (for B1's slice budget): 43.6 ms.
3. **R1, a reveal-only arm on the A12**: the shell's first frame on the pixels, with and without
   measuring the shell while idle (3.5). Its arm (`-e x r1prep`, with `reveal` and `idlemeasure`), the
   phone session's scripts and queries, and the bar fixed before the run are in
   [`docs/nf5-r1/`](nf5-r1/README.md). They were run end to end on an emulator on 2026-11-26.

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
  so the focus observer covers it. **Accessibility actions are input too** (round 3): a TalkBack
  double-tap or a VoiceOver activate is a semantics action, not a pointer event, and calls the
  widget's handler directly. So the recorder also wraps the content's event sink, and any event the
  content sends before the `ready` edge counts as input, except the events codegen lists as
  reports rather than input (`FrameRules`: the lazy layouts' viewport reports); and on Android the
  root's accessibility delegate reports an `ACTION_SCROLL_*` or focus action that sends no event.
  PR A4a; host test: a touch, a key, a focus, a semantics click (`performSemanticsAction(OnClick)`)
  and a semantics scroll each block the write; the lazy list's own viewport reports, which are
  events but not input, do not.
- **Origin (condition 5): `ScrollOrigin`.** `interface ScrollOrigin { val atOrigin: Boolean }`, which
  every scrolling host widget implements from its own state: `LazyListState.firstVisibleItemIndex
  == 0 && firstVisibleItemScrollOffset == 0`, `ScrollState.value == 0`, `PagerState.currentPage ==
  0 && currentPageOffsetFraction == 0f`. Codegen knows which widgets scroll: the schema marks them
  `@Scrolls` (2.4), and `FrameRules` lists their tags. At the `ready` edge the recorder asks every
  such node in the marked subtree. **A node whose tag is in the list but whose widget does not
  implement `ScrollOrigin` blocks the write** (fail closed), so an adopter's own scroller cannot be
  recorded scrolled by mistake. To reach the widgets, `HostProtocolAdapter` gains an internal
  `widgetsUnder(id)` for the recorder. The interface and the recorder's use of it: PR A3c. The
  implementations, one per scroller of the generated groups (retargeted under Q17, 2.4): PR A4a.

  | Schema | Widgets that implement `ScrollOrigin` |
  |---|---|
  | the generated groups (`gatea`; the product's in MS4-36) | `LazyColumn`, `LazyRow` (the hand `LazyRuntime`'s two copies), and a node carrying `verticalScroll` or `horizontalScroll` |
  | keliver-material (Q17 (i) only) | `ScrollableColumn`, `LazyVerticalGrid`, `LazyHorizontalGrid`, `HorizontalPager`, `VerticalPager` |
  | RedwoodLayout (Q17 (i) only) | `Row` and `Column` (at origin by definition with `overflow = Clip`; their `ScrollState` with `Scroll`) |
  | RedwoodLazyLayout (Q17 (i) only) | `LazyList`, `RefreshableLazyList` |

  Host tests in A4a: each widget, scrolled by one pixel, blocks the write; a guest-set
  `scrollItemIndex` that the host applied blocks it too.

### 2.4 Which widgets can be replayed

The fold works from a list generated by codegen from the schema. Nothing is replayable by default.

- **Schema annotations** (in `keliver-schema`, beside `@Widget` and `@Property`; built in A1):
  - `@Replayable` on a widget or modifier: it may be replayed;
  - `@Scrolls` on a widget, or on a modifier that makes its node scroll (the generated groups'
    `verticalScroll` and `horizontalScroll`): it scrolls, so its host widget must implement
    `ScrollOrigin` (2.3, condition 5);
  - `@NeverCached` on a property: its value is never stored, in either level;
  - `@BlocksFrameWhenTrue` on a non-null `Boolean` property: while it is true, nothing is recorded
    (2.3, condition 7). A recorded value is therefore always false, so it needs no other
    classification;
  - `@StyleToken` on a property whose type is or contains a `String`: its strings name a style or
    an asset from a fixed set (an icon's name; or a structured value whose strings are all codes,
    such as a generated group's `Arrangement` or `TextStyle`), so `Shell` keeps it. Like every
    non-text value it can still say something about the data (a status icon); I2 says so;
  - `@NotData` on a property whose type contains no `String`: it is layout or style, not data (a
    size, a padding, a colour constant), so the review list below leaves it out.

  A property carries at most one of the last four. The schema parser (`keliver-tooling-schema`)
  checks each fits its type and fails the schema's build otherwise.
- **Codegen** (`keliver-tooling-codegen`, the step that already writes the protocol code) writes
  `<Schema>FrameRules` beside the host protocol: the replayable widget and modifier tags, the
  scrolling widget and modifier tags, the `@BlocksFrameWhenTrue` properties, for each replayable
  widget and modifier its `@NeverCached` properties, and the tags of its text- and URL-bearing
  properties (any property whose type is or contains a `String`, `@StyleToken` and `@NeverCached`
  aside). Tags are as on the wire, with each dependency's offset; a modifier's properties are named
  as in its serialized value (they have no tags). The fold uses the text list for `Shell`. **For
  structured values** (a `RichText`'s `List<TextSpan>`), `Shell` replaces each string inside the
  value and keeps the rest (each span's size and weight); it never drops the value, so the widget
  keeps its height (5.2). **Whether a type contains a `String`** is the schema parser's, which sees
  the compiler's view of the type and fails closed: a type argument, a final class's properties, a
  superclass's, a sealed type's subclasses are looked into; an abstract class or an interface it
  cannot see into counts as carrying one; an enum does not. A schema set with no frame-rule
  annotation at all gets no `FrameRules`, and the host replays nothing of it.
- **The review list (M3).** Codegen also writes `<Schema>FrameRules.review.txt`: every non-String
  property of a `@Replayable` widget or modifier that is neither `@NotData` nor `@NeverCached` (nor
  `@BlocksFrameWhenTrue`), with its type. These are the values `Shell` keeps. **It is checked in, as
  the reviewed list**, in the module that applies `dev.keliver.generator.protocol.host` (A1 found no
  `keliver { }` extension; the existing codegen surface is `redwoodSchema { }`). The build prints
  the list as a warning; with `redwoodSchema { frameRules { strict = true } }` the check (in
  `check`) fails while the checked-in list differs from the schema's, naming each value not yet
  reviewed, until it is classified in the schema (`@NotData`, `@NeverCached`, or off `@Replayable`)
  or accepted into the list with `redwoodFrameRulesGenerate`, which a reviewer sees in the diff. So
  each value `Shell` keeps is a reviewed choice (I2), including data a frame should keep (a
  `Switch`'s state). A1 runs strict on the prototype's group (its list has 13 entries: the
  `enabled` of six controls, two clickables and two scroll modifiers, and the three
  `AnimatedVisibility`s' `visible`); MS4-36 carries it to the product's groups,
  and the scaffolder turns it on for an app's own schema.
- **Which schemas are annotated: the generated groups (retargeted under Q17,
  [`MS4_PLAN.md`](MS4_PLAN.md) 2.2).** Under Q17's (iii) no legacy schema ships in 0.5.0 as a widget
  system, and A6 measures the prototype's generated group, so A1 annotates that group:
  - **`gatea`** (the prototype; 29 widgets, 36 modifiers, 15 factories): a frame-rule emitter in
    `keliver-compose-generator` (its own class, `FrameRules.kt`) writes the annotations onto the
    generated schema from each parameter's category: text and URLs none (so `Shell` replaces them);
    mirrors whose wire form is a code or that the parser cannot see into (`Shape`, `Alignment`,
    `Arrangement`, `TextStyle`, `ImageVector`, `Painter`, `*Defaults` values, transitions)
    `@StyleToken`; number-like layout and style mirrors (`Dp`, `Color`, `TextAlign`) and the host's
    scroll state `@NotData`; wire bookkeeping (a case-2 `…IsDefault`, a modifier's event handle, a
    lazy list's window counts) `@NotData`; primitives none, so they go to the review list unless
    `gatea.json`'s `frameRules` exceptions classify them (a line limit, a fill fraction, an alpha:
    `@NotData`; `AnimatedVisibility.label`, a debug label: `@StyleToken`). Every generated member is
    `@Replayable` unless the config takes it off or a parameter's type has no category (fail
    closed); the `scrollContainers` are `@Scrolls`. Its hand-written extras are annotated by hand:
    all but `ExtraShimmer` (a skeleton) are replayable.
  - **`core` and `coil`** (the product): not annotated until MS4-36, so no frame (fail closed).
  - **Legacy** (keliver-material, RedwoodLayout, RedwoodLazyLayout): not annotated. No `gatea`
    screen uses their widgets (the generated `Column`, `Row` and `LazyColumn` have `gatea`'s own
    tags), and an unannotated schema fails closed: its screens get no frame. Until MS4-32 ports the
    reference app, its legacy screens record no frame. **Only if the owner answers Q17 with (i)**
    are legacy's hand annotations added, as below:
  - (Q17 (i) only) **RedwoodLayout**: `Row`, `Column`, `Box`, `Spacer` and its nine modifiers are
    replayable; `Row` and `Column` are `@Scrolls` (their `overflow` can be `Scroll`).
  - (Q17 (i) only) **RedwoodLazyLayout**: `LazyList` and `RefreshableLazyList` are replayable and
    `@Scrolls`; `RefreshableLazyList.refreshing` is `@BlocksFrameWhenTrue`. Only the rows the guest
    had composed are stored, at the first position (5.2); the guest's `onViewportChanged` reports
    from the replayed list are dropped with the other events (2.6).
  - (Q17 (i) only) **keliver-material**: every widget and modifier is replayable except ten
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
  host would not play at that moment. On the generated groups the rows that apply are
  `AnimatedVisibility`, `AnimateContentSize` and `Shimmer` (`ExtraShimmer`), and two more that A4a
  tests the same way: `animateItem` (a first composition's items do not animate in) and
  `ExtraAnimated` (a first composition's `animate*AsState` starts at its target). The
  `AnimatedBorder` and pager rows are legacy's (Q17 (i) only).

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
| `FrameRules` | generated per schema (2.4) | the replayable, scrolling and blocking tags, the text-bearing and never-cached properties, the review list |
| `ScrollOrigin`, `TextLines` | new interfaces, `keliver-widget` (A3c); implemented by the generated groups' host widgets (A4a; retargeted under Q17, 2.4) | a scroller reports whether it is at its origin (2.3); a text-bearing widget reports how many lines each of its text properties laid out to (5.2) |
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
  announces. That does not promise to move TalkBack's focus, so the screen's heading is made the
  first node in the pane's traversal order, and the test checks that order, not focus movement
  (round 3). On iOS the host posts
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
  host, in the generated dispatch of the row's own onClick (M3): if that kind's entry is effectively
        Prepared and no other token is outstanding (H4), shows it as REVEALED (inert) in this
        frame, takes token t, enables the reveal's back callback, and sends reveal(t) and the click
        in ONE Zipline task, reveal(t) first; from here on, pointer events aimed at entries below
        the revealed one are consumed
  guest: records the stack, then runs the click: navigate(Detail(N)) binds the entry (route =
         Detail(N), epoch e+1, effects released); in its next frame keliver-nav ANSWERS t: bind(t)
  host: the entry goes from Revealed to Shown, live, only in the frame in which its state Shown and
        its content at epoch e+1 have both been applied (I14). Shared nodes have not moved (I4b)
back
  host: retained back as today (NF1); guest: pops the entry and PARKS it (route = unbound, epoch
        e+2), or disposes it if something from the visit still runs (3.3)
```

A kind that is not revealable (3.4), or a tap that finds no Prepared entry of the kind (it is still
being prepared, or it is being parked), skips the reveal. Then the click goes to the guest, which
binds the entry, and the host shows it in the batch that carries its content at the bound epoch
(I14): the list stays until the detail is there, with no frame of the shell or of the last item.

**The reveal protocol.** One host feature, **`keliver.nav.reveal@1`** (I8), separate from the
container's `keliver.nav.retainedContainer@2`, **and requiring it**: the reveal acts on the entry
states and epochs `@2` adds. A guest that marks a row `revealsPrepared` against a host without
`reveal@1` gets no reveal and no mismatch: keliver-nav emits the modifier only when the host
declares the feature. A host that declares `reveal@1` without `retainedContainer@2` is treated as
declaring neither (no preparation, no reveal; a debug build logs it), and the host-feature test of
H0 fails a template that declares it so.

| Message | Direction | Meaning |
|---|---|---|
| `reveal(token, entryKey)` | host → guest, an event on the container | the host has revealed this entry for the click that follows. Sent in the same Zipline task as the row's click event, before it (a new `UiEventSink.sendEvents(first, second)` that launches once), so nothing on the Zipline thread can run between them |
| `cancelReveal(token)` | host → guest, an event on the container | the user left the reveal (back), or `REVEAL_LIMIT` passed. Sent through the same event sink, so the guest handles it after the click |
| `revealLive(token)` | host → guest, an event on the container | the revealed entry went live (the host's frame with its bound content). keliver-nav counts the visit's first show here, not at the bind (Low: "screen viewed" only for a screen the user saw live) |
| `revealAnswers` | guest → host, a property of the container | keliver-nav's answers, **one per token** (H4): a short list of `(token, outcome)` for the last 4 tokens it handled, each `Bind(entryKey)` or `Decline(reason)` with `reason` one of `noNavigation`, `otherKind`, `cancelled`. The host reads its own token's entry; an answer can no longer be overwritten by a later token's |
| `cancelAcked(token)` | guest → host, a property of the container | the newest `cancelReveal` the guest has handled, after undoing what the click did (or finding nothing to undo) |

**How the host knows the click happened (M3).** Every event launches separately
(`TreehouseAppContent.kt:615–625`), and the click belongs to the row widget's own event property,
not to the container. So the reveal is not taken from pointer input on the row: a pointer
observer sees the finger lift, not the click, and would reveal on a scroll-cancel, a long press, a
disabled row or a tap on the row's trailing button. Instead the host side of
`revealsPrepared(kind)` provides a `LocalRevealTarget(widgetId, kind)` to the row's widget, and the
**generated host dispatch of a widget's click event** (`onClick`, the event `revealsPrepared`
names) checks it: only when that widget's own click is actually emitted, and the target's widget id
is this widget's, does it call `sendEvents(reveal(t), click)` instead of `sendEvent(click)`. A child
(the trailing button) sees the local but not its own id, so its click is ordinary. This is codegen
in the host dispatch only; no schema changes. Host tests: a scroll that cancels the press, a long
press on a row with a long-press handler, a disabled row and a trailing button's tap each send no
`reveal` and leave the shell hidden.

**One reveal at a time (H4).** The host issues no reveal while any token is `Revealed`, `Binding`
or `Cancelled`: a tap in that time takes the non-revealing path (its click goes to the guest after
the earlier token's events, in order). From the click handler on, the container consumes pointer
events aimed at entries below the revealed one, so a second finger on another row in the same frame
(when hit-testing still uses the previous layout) reaches nothing. Host tests: a two-finger tap on
rows of two revealable kinds reveals one, and the other row's click never reaches the guest; back
then a re-tap within 50 ms reveals nothing until the first token's `Cancelled` has ended, and ends on
the detail opened by the non-revealing path, in the frame its content is applied, without reaching
`CANCEL_LIMIT` (timed with the fake clock: round 4, N2, below).

**The guest answers every token, explicitly, in its first frame after the click.** keliver-nav
records the token and **the stack as it is at `reveal(t)`** (before the click runs) in snapshot
state when `reveal(t)` arrives, which schedules a composition even if the click changes nothing. In
that composition (the click has run by then: both came in one task, and no frame tick can be queued
between them) it answers:
- the click navigated to a route of the revealed kind, which bound the revealed entry → `Bind`. The
  entry's new state and the answer come from this (main) composition; the bound content comes from
  the sub-composition (3.4), and I14 joins the two;
- the click navigated to another kind (a login, a paywall) → `Decline(otherKind)`. The other
  destination is composed in the same composition, so the host goes from the shell to it with no
  list frame between (decision 15). A debug build fails loudly here: a row marked
  `revealsPrepared(DetailKind)` whose click opened another kind is a bug in the app. **A bind of
  another entry of the same kind** (the click bound a fresh entry, or a parked one other than the
  revealed one, M2) answers `Bind(entryKey)` with that entry's key; the host checks the key against
  the revealed one and treats a mismatch exactly as `otherKind`;
- the click did not navigate → `Decline(noNavigation)`. A click handler that navigates only after a
  `suspend` (a check, a load) declines, and its navigation comes later as an ordinary one. Such rows
  must not be marked `revealsPrepared`; the debug build warns when a declined token is followed
  within `REVEAL_LIMIT` by a navigation to the revealed kind (`lateBind`);
- a `cancelReveal(t)` was handled before that composition (a back right after the tap, with the
  guest busy) → `Decline(cancelled)`, with `cancelAcked = t`, and the click's navigation already
  undone in the same composition.

**What the guest's cancel undoes (H2, M1): token t's navigation, and only while it is in effect.**
On `cancelReveal(t)`, keliver-nav compares its stack with the one it recorded at `reveal(t)`. If
the stack still holds what t's click pushed on top of the recorded one (a bound detail, or a login
pushed by an `otherKind` click), it restores the recorded stack: it pops what the click pushed,
without a result, parking or disposing each entry as a pop does (3.3). Otherwise (nothing was
pushed; a back already popped it; or t is a token this guest never saw, a guest started after a
reload or a code swap) it changes nothing. Either way it then sets `cancelAcked = t`, with an
`e_ack` for **every entry the cancel left in the container**: the revealed entry, and any entry t's
click pushed that the cancel parked rather than disposed (round 4, N2; round 5, M2: the host's
`Cancelled` ends on them, below). **One definition (round 5, L1): an entry's `e_ack` is its epoch as
keliver-nav declared it right after the cancel**, whatever travels in the same batch. So a cancel
can never pop an entry that t did not push, and a second back that reached the guest first does
not make the cancel pop the list. Unit tests in keliver-nav: cancel after a bind; after an
`otherKind` push; after a guest-side back already popped the detail; for an unknown token; each
ends on the recorded stack and acks once.

The host counts every decline by reason in release telemetry (`nav.revealDeclined(kind, reason)`),
and every tap on a `revealsPrepared` row that could not reveal (`nav.revealSkipped(kind, reason)`,
`reason` one of `notPrepared`, the kind's entry not yet effectively `Prepared`, and `outstanding`,
another token in flight): without the first, decision 16's 300 ms could not be revisited with data.
An unrelated guest batch (a ticker, an analytics recomposition) carries no answer, so it changes
nothing: the reveal no longer depends on "the guest's next batch".

**The host's states for one token.**

| Host state | What the user sees | Ends when |
|---|---|---|
| `Revealed(t)` | the shell, **inert** (below) | `Bind(t)` for the revealed entry → `Binding(t)`; `Decline(t)`, or `Bind(t)` for another entry (M2) → hidden, when what replaces it is effective (below); back, the shell's back button or `REVEAL_LIMIT` → `Cancelled(t)` |
| `Binding(t)` | the shell, still inert, **whatever `Pending` says** (round 4, N1: below) | the entry's state `Shown` and its content at the same epoch are both applied (I14) → **live**, in that frame, and `revealLive(t)` is sent; back or `REVEAL_LIMIT` → `Cancelled(t)` |
| `Cancelled(t)` | **the entry that was shown at the tap** (the list), whatever the guest declares `Shown` meanwhile (M1) | `cancelAcked ≥ t` **and**, for **every** entry the ack lists, that entry's effective epoch is at least its `e_ack`, **or the entry is no longer in the container** (round 4, N2; round 5, M2; round 6, L-c). The ack carries one `e_ack` per entry the cancel left in the container: the revealed entry, and each entry t's click pushed that the cancel parked; an entry the cancel disposed is not listed and needs nothing more. An entry's `e_ack` is its epoch as declared right after the cancel (round 5, L1): the pop's epoch, or the current one when the cancel found nothing to undo. It stays the right bound if the ack reaches the host a batch before that state, and if a later click in the ack's batch declares a newer epoch (the condition is `≥`). Until then the host ignores every answer for `t`: the same rule as the retained back's `backHandled` counter (`Retained.kt:120–139`). **Any `Shown` after that is a new navigation**, drawn by the ordinary rules (I14). `CANCEL_LIMIT` releases the held back but does not end it (below) |

**A reveal overrides `Pending` (round 4, N1).** While the host holds `Revealed(t)` or `Binding(t)`
for an entry, it draws that entry, inert, whatever `Pending` says: **its current content, inert**
(round 5, M3: the shell until the bind's content is applied, then that content). `Pending` (I14)
governs only entries not under a reveal. On the reveal's main path the bind's
`(Shown, e+1)` comes from the main composition and its content a batch later from the
sub-composition, so the entry is `Pending` for that batch; without this rule it would go shell →
list → detail. In the other order (the bound content first, its state a batch later) the content
root is one widget tree, so applying e+1's changes replaces the shell: the host draws the entry
inert, now with the content t's bind composed, until the state makes it live. In both orders the
entry goes live in the frame in which state and content agree. Host test (I14), the two orders with
their own frames: **state first**: the shell (inert) until the content lands, then the live detail
in that frame; **content first**: the shell, then the bound content inert from the frame the content
is applied, then live in the frame its state is applied. In neither does any frame show the list in
the shell's place.

**Why `Cancelled` ends on the ack's epoch, not on "not `Shown`" (round 4, N2).** Back, then a
re-tap on item 8 within 50 ms: while `Cancelled`, the re-tap takes the non-revealing path, and the
guest runs click(t), cancel(t), then click(item 8), which binds the same rebindable entry and
declares it `Shown`. Revision 4's end condition ("the revealed entry is not effectively `Shown`")
then never held, and `Cancelled` lasted until `CANCEL_LIMIT`: the list drawn and back held for 1 s,
then item 8, with a normal gesture counted as `nav.cancelTimeout`. With `e_ack`, `Cancelled` ends
once the host has applied what the guest declared up to the cancel; item 8's `(Shown, e+3)` is a new
navigation, `Pending` until its content lands, and then drawn in its content's frame. If item 8's
click is in the ack's own batch, `e_ack` is still e+2 (the epoch right after the cancel, L1), the
host applies e+3 with it, so `Cancelled` ends, and item 8 is drawn, in the frame item 8's content is
applied (a batch later if its content lags: `Pending` until then): no extra frame either way.

**If the revealed entry disappears** while `Revealed` or `Binding` (M2: the guest dropped it on
memory pressure or in the background, reset its navigation on a logout, or the click bound a fresh
entry and the old one was removed): the host shows the entry below it (the list) in that frame. The
token stays outstanding, with its back callback and `REVEAL_LIMIT`, until it is answered: a back
meanwhile enters `Cancelled(t)` as above, and an answer ends it (a `Bind` of the missing entry
cannot arrive; any other answer resolves as in the table). Host test: the entry removed in the batch
before the answer, then back; and then the answer: each ends on the list or on what the answer
shows, with no frame of the shell after its removal.

- **Inert while `Revealed` or `Binding` (H4).** As the cached frame (2.6): every tap, long press
  and scroll on the shell is consumed, never sent to the guest, and shows the indicator at once
  (the `revealIndicator` slot on the container, a thin bar by default). The shell is unfocusable. A
  "Pay" button tapped at 50 ms therefore never reaches a handler composed with `id == null`. The one
  exception is the shell's back button, if the screen marks it `Modifier.revealBack()`
  (`KeliverNavBackButton` does it itself): a tap on it acts as back (below). The entry becomes live,
  with its taps and focus, only in the frame in which its bound content is applied.
- **Back while `Revealed` or `Binding` (H1 of round 2).** The host consumes it, not the guest's
  back state, which reaches the host asynchronously (`TreehouseContent.android.kt:33–34`). On iOS,
  where no system back reaches a guest (`TreehouseContent.ios.kt:25`), the shell's `revealBack()`
  button is the back (and VoiceOver's escape gesture, below). The host then shows the list in that
  frame, enters `Cancelled(t)` and sends `cancelReveal(t)`. The guest handles it after the click
  and undoes t's navigation (above); then it sets `cancelAcked = t`. The guest's stack ends `[List]`,
  and the detail is never drawn after the user backed out of it.
- **The reveal's back callback has a lifetime (H2).** On Android it is added to the host's back
  dispatcher **synchronously in the click handler**, as the last-added callback, so it has priority
  over the guest's and takes a back in the tap's own frame. **Its owner is a container-level holder
  (round 4, N4)**, kept with `TreehouseAppContent` beside the reveal state (M6) and entered by the
  container's composition when the container is first composed (one `DisposableEffect` for the
  container, not keyed on any token), so it exists before any tap. The click handler registers the
  callback in that holder synchronously, in the same call that adds it to the dispatcher, and the
  holder's `onDispose`, like its reset on a code swap, removes whatever reveal callback is current.
  So the callback has an owner from its first moment, and can never outlive the container or the
  code. (Revision 4 held it in a `DisposableEffect` keyed on the token: between the click and the
  composition that ran that effect nothing owned it, and a code swap in that window leaked it.)
  **On entering a composition, the holder adds the current reveal callback, if any, to that
  composition's back dispatcher (round 5, M1).** The holder outlives the view (M6), but its
  `onDispose` runs with the old activity's composition and removes the callback there; so when an
  activity is recreated during `Revealed`, `Binding` or `Cancelled` (a held back, or not yet past
  `CANCEL_LIMIT`), the new composition's entry re-adds it, as the last-added callback (the re-add runs in a
  post-composition effect, after the composition's other back callbacks are registered), and back stays
  the host's (I13).
  It is removed: when the entry goes live (in that frame; the retained back, NF1,
  takes over), when a decline takes effect, when `Cancelled` ends or reaches `CANCEL_LIMIT`, when the container leaves the
  composition (the content is unbound or crashes, or the view is detached and its composition
  disposed), and when the code swaps (the reveal state is reset then, M6). A guest that crashes or
  is replaced at 50 ms therefore cannot leave back swallowed.
- **While `Cancelled`, back is the host's, and acts on the list as shown (H2).** A back in that
  time is held by the host (at most one; further presses are dropped) and never reaches the guest,
  whose stack may still hold the bound detail. When `Cancelled` ends the host re-dispatches it as a
  back on the list: to the guest's own callback if the guest's last reported back state is enabled,
  else to the activity, which it can reach when the list has nothing to pop.
- **At `CANCEL_LIMIT` (round 4, N3) the host releases the back, and only the back.**
  `CANCEL_LIMIT` (proposed 1 s after the cancel, decision 2) bounds how long back is held. At the
  limit the host removes the reveal callback, re-dispatches a held back as above, stops holding
  later ones, and records `nav.cancelTimeout(kind)`. **It keeps hiding what t pushed until the ack
  arrives:** `Cancelled(t)` itself stays, with its end condition (the table above), so the list
  stays drawn, and the detail the user backed out of is never drawn, however late the guest's
  content lands. A released back that reaches a guest whose stack still holds the detail pops that
  hidden detail, and the later cancel finds nothing to undo. The user's back is then used up
  without a visible change. That is the bounded cost of a guest stalled for more than 1 s, and it
  is counted. The guest's token-scoped cancel makes any back that reaches it first safe in the same
  way. **What bounds `Cancelled` itself (round 5, L2): nothing but a crash or a code swap.** A guest
  that stays alive and never acks leaves the container on the list for good, and every later
  navigation in it is ignored, with back released to the guest; the case is counted by
  `nav.cancelTimeout(kind)` at the limit, so it is visible in release telemetry. That was chosen
  over drawing what t pushed (the review's first option), because the latter shows a detail the
  user backed out of.
- **`REVEAL_LIMIT` covers tap → live (H3; proposed 1 s, decision 2),** over `Revealed` and
  `Binding` together: a `Bind` whose content never arrives (a stalled sub-composition, a root
  without `ContentEpoch`, a long GC in a varied rebind) is bounded too. The indicator is cosmetic:
  it appears on the shell at `REVEAL_LIMIT` − 300 ms if the entry is not live by then, so it is seen
  rather than flashed. **At `REVEAL_LIMIT` the host enters `Cancelled(t)` and sends
  `cancelReveal(t)` at once**; telemetry `nav.revealTimeout(kind, state)`. An answer that arrives
  before the limit, including inside the indicator's 300 ms, is handled as usual; any answer after
  it is ignored. When the guest catches up it runs the click, then the cancel, and ends on `[List]`.
  So a shell is never on screen for longer than `REVEAL_LIMIT` (I12).
- **In the background (Low).** Choreographer stops while the activity is stopped, so the guest
  cannot answer. Both limits count only while the host is started: they pause at `ON_STOP` and
  resume at `ON_START`, so a user who comes back finds the reveal where they left it, not the list.
  Host test: `ON_STOP` at 200 ms, 10 s away, `ON_START`, then the answer: the entry goes live, no
  timeout recorded.
- **Activity recreation (M6).** The host-only states (`Revealed`, `Binding`, `Cancelled`, the
  token, the held back) live with `TreehouseAppContent`, beside the content's binding, not in the
  renderer's view, so they survive the view being rebound to a new activity; they are reset on a
  code swap. In `Cancelled`, the new view therefore draws the list, not the guest's still-`Shown`
  detail. Host test: recreate the activity in each state; the new view draws what the old one did,
  and a back after the recreation is the host's (round 5, M1): in `Revealed` and `Binding` it
  enters `Cancelled(t)`, in `Cancelled` before `CANCEL_LIMIT` it is held, and in none of these does
  it reach the guest or the activity.
- **After a decline** the shell goes in the same frame as what replaces it appears: for
  `noNavigation` and `cancelled`, the list, in the frame the decline is applied; for `otherKind`
  (and a `Bind` of another entry, M2), the other destination, in the frame it becomes effective
  (I14: if it is a rebindable kind, its content can come a batch after its state), so no list frame
  shows between the two. The shell stays inert meanwhile, within `REVEAL_LIMIT`. A back meanwhile
  enters `Cancelled(t)`, the host keeps drawing the list, and the guest's cancel pops the other
  destination too (M1). The entry returns to `Prepared` (decision 15).

**Accessibility (M8, and round 3's lows).** While `Revealed` or `Binding` the entry's semantics are
one node, "Loading", marked busy, with no actions and no focusable child. It becomes a polite live
region only 500 ms after the reveal, so a reveal that goes live at 150 ms never queues a "Loading"
announcement to be spoken after the pane's title. On iOS the "Loading" element implements
`accessibilityPerformEscape`, mapped to the reveal's back (VoiceOver's two-finger scrub), because
`revealBack()` is unfocusable while revealed; on Android TalkBack's back gesture is a system back,
which the host already takes. At the bind (the frame it goes live) the entry's root carries the
destination's `paneTitle` (from the bound route, `KeliverNavKind.paneTitle(route)`), so Android
sends a window-state change and TalkBack announces the pane. That does not promise to move
TalkBack's focus, so the design relies on the order instead: the destination's heading
(`Modifier.semantics { heading() }` on its title) is the first node in the pane's traversal order.
On iOS the host posts `.screenChanged` in the bind's frame, not at the reveal. Host test (I13):
the traversal order, not focus movement.

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
  analytics stay right. **For a revealed bind, the visit's first show is counted when the host
  reports the entry live (`revealLive(t)`, 3.2), not at the bind**: a back at 80 ms, after the guest
  composed the bind but before the entry went live, leaves the user having seen only the inert
  shell, so `LaunchedOnceShown` does not run for that visit (the load, keyed on the item, has
  started and is cancelled at the park). Unit test: a cancel before `revealLive` runs no
  `LaunchedOnceShown`; `revealLive` runs it once.
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
| A route's **kind**, separate from its arguments: `KeliverNavKind(id, unbound, revealable, paneTitle: (R) -> String?, dataArrives)` (`dataArrives`: `Arguments`, `Cache` or `Fetch`, decision 7), given to the controller by `KeliverNavKinds<R>(kindOf: (R) -> KeliverNavKind?)`. Null: not rebindable | `KeliverNavController.kt` | full route equality (`prepared?.route == route`) |
| `NavEntry.route` backed by snapshot state; `navigate(route)` binds the parked or prepared entry of the same kind | `KeliverNavController.kt` | the route is a `val` |
| **An epoch per entry**, incremented in the same snapshot as every route change (prepare, bind, park). The container entry carries `(state, epoch)`; the entry's content root carries `ContentEpoch(epoch)`, emitted by whichever composition composes that content (I14) | `KeliverNavController.kt`, the host frame (`KeliverNavHostBack.kt`) | none |
| **The reveal's answers**: `reveal(t)` (recording the stack), `cancelReveal(t)` and `revealLive(t)` handled; `revealAnswers` (one per token) and `cancelAcked` emitted (3.2); the cancel restores the stack recorded at `reveal(t)` only while t's navigation is in effect, else only acks; the visit's first show counted at `revealLive` for a revealed bind | new, `KeliverNavReveal.kt` | none |
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
  dataArrives = DataArrives.Fetch,           // checked by assertDataArrival (decision 7)
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
- `assertDataArrival(kind)` (decision 7): the kind's declared `dataArrives` is what it does. Each
  sample route bound against capabilities that never answer: a `Fetch` kind makes at least one
  network call after the bind and draws none of the sample's data until it is answered; an
  `Arguments` kind draws its data with every capability failing; a `Cache` kind calls only
  storage.
- `assertShellLayoutStable(kind)` (revealable kinds only; I4b): on the JVM Compose UI harness with
  the app's own widget system, at 360 × 640 dp and 411 × 891 dp, font scale 1.0 and 1.3: lay out
  the shell, then each sample route with its data (fake capabilities answer at once), and compare
  the bounds of every node present in both, by id (binding keeps the composition, so a node that
  stays keeps its id). Fails if any such node whose shell bounds start within the first viewport
  moves by more than 1 dp. Fails too if a shell node in the first viewport is gone from the data
  tree, unless it is marked `Modifier.shellOnly()` (a spinner): a revealable destination fills its
  shell in place. Without that second check, a destination that swaps a separate loading layout
  for its content would share no ids and pass while everything moved. **Three escapes closed
  (round 3, M4):**
  - **Lazy rows.** Skeleton rows must be `shellOnly()`, which exempts them, so real rows could land
    anywhere. So the space `shellOnly` nodes reserved must be filled: in each region (a lazy list,
    a column) whose first-viewport `shellOnly` nodes disappear, the first data node starts at the
    top of the space they took, ±1 dp.
  - **Fixture text.** Short fixture strings pass where long real titles push content down. So the
    test substitutes A4a's text corpus at its maximum lengths (and its narrow-letter and wide-letter
    texts) into every fixture string, and lays out each.
  - **Images.** The JVM harness loads no image, so a wrap-content image passes. So in a revealable
    kind's shell and data trees, an image widget without a fixed size or a fixed aspect ratio
    fails the test, whatever its bounds.

The sample routes, and the fixtures their loads answer with, come from the app: the scaffolder
writes the tests with the app's fixtures to fill in. A revealable kind with fewer than two samples
fails its generated tests.

### 3.5 Host

| Change | Where | PR |
|---|---|---|
| The retained container in the production widget system (NF1's production step) | keliver-material's host widgets, templates | B0 |
| **An explicit entry state** on the container's entries, guest-declared: `Shown`, `Retained`, `Prepared`, each with its `epoch`. The host never places or draws a `Prepared` entry, and never makes it a back target; `goBack` skips it. Today the container protects hidden entries by their order alone (`shownIndex` is `lastIndex`, `goBack` takes `index - 1`, `Retained.kt:130–165`). Host feature `keliver.nav.retainedContainer@2` | the container's schema (a protocol change, covered by the schema lock) and its renderer | B0 |
| **State applied only with its content (I14).** The host's effective state of an entry is its newest declared `(state, epoch)`, and it takes effect only when that epoch equals the `ContentEpoch` of the entry's applied content root. **While the two differ, the entry is `Pending`** (H1): never drawn, hit-tested, focused, revealed or used as a back target, and the container shows the nearest drawable entry below it. It never falls back to an earlier `Shown`: a guest that coalesced epoch e+2 into e+3 leaves the entry `Pending` until e+3's content arrives, not showing e+1's. **Except under a reveal** (round 4, N1): while the host holds `Revealed(t)` or `Binding(t)` for the entry, it draws it with its current content, inert (the shell until the bind's content is applied, then that content; round 5, M3), whatever `Pending` says; `Pending` governs only entries not under a reveal | the container's renderer | B0 |
| **Prepared entries measured while idle** (3.2, M5): measured with the container's constraints, not placed | the container's renderer | B0 |
| **The reveal**, host feature `keliver.nav.reveal@1` (requires `@2`): the host-only states `Revealed`, `Binding`, `Cancelled` (3.2), kept with `TreehouseAppContent` and reset on a code swap (M6); one outstanding token at a time, and pointer events below the revealed entry consumed (H4); the one-task `sendEvents(reveal, click)` from the generated click dispatch and `LocalRevealTarget` (M3); the reveal's own back callback, added in the click handler and registered in the same call with a container-level holder kept with `TreehouseAppContent`, whose `onDispose` removes it (round 4, N4) and whose entry into a new composition re-adds the current one (round 5, M1); the ack's `e_ack` per entry left in the container (round 5, M2); the held back while `Cancelled`, released at `CANCEL_LIMIT` while what t pushed stays hidden until the ack (H2, N3); `revealBack()`; inert shell with the `revealIndicator` slot; `REVEAL_LIMIT` from tap to live and `CANCEL_LIMIT`, both paused while stopped; the accessibility of 3.2; the declined, timed-out and skipped counts | the container's renderer, `keliver-treehouse-host` (`UiEventSink.sendEvents`, `TreehouseAppContent`), the host codegen of click events | B4 (prototyped in B1) |
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
  proven in the spike, not in B4): the one-task `reveal` + click from the generated click dispatch,
  the per-token answers, the token-scoped cancel after the click, the back callback's lifetime and
  the held back while `Cancelled` and its release at `CANCEL_LIMIT`, `REVEAL_LIMIT` from tap to
  live, one token at a time, the epoch rule with `Pending` (and the reveal's override of it, N1),
  `Cancelled`'s end on each listed entry's `e_ack` (N2, M2), the holder across recreation (M1), and the host tests of I12, I13 and I14 against the guest profile's stack.
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
swap exposes the guest's nodes, its root carries the `paneTitle`, and its heading is the first
node in traversal order (the order, not TalkBack's focus, is what is tested); on the iOS simulator, a test
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
1 to 400 characters (long words, digits, mixed scripts, **texts made mostly of narrow letters such
as "i", "l" and punctuation, and of wide ones such as "W", "M" and CJK**), have equal heights to the
pixel. It fails on any difference. The animated widgets' tests of 2.4.

### I4b. The shell does not move under the data (B)

Between a revealed shell and its destination with the data, a node present in both must not move
by more than 1 dp if it starts within the first viewport, and the shell's first-viewport nodes stay
(a revealable destination fills its shell in place; `shellOnly()` nodes aside). A kind whose
structure above the fold depends on the item is not revealable unless its shell reserves the space
(3.4).

**Enforced:** the rule that a revealable kind's shell is its loading UI (M7, 3.4); the generated
`assertShellLayoutStable(kind)` per revealable kind. **Tested:** that test, which compares bounds
after laying out the shell and each sample route with its data, at two screen sizes and two font
scales (3.4); tests of the test: the `varied` heavy detail declared revealable fails it (its banner
on even ids moves every section down by 32 dp); a lazy list whose `shellOnly` skeleton rows are 72
dp and whose data rows start 16 dp lower fails it; a title that fits as a fixture and wraps at the
corpus's maximum length, pushing a section down, fails it; a wrap-content image fails it. **Measured:** B5's layout-shift measure, on the
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
revealed shell (B) is on screen for at most `REVEAL_LIMIT` from the tap until it is live (3.2),
over `Revealed` and `Binding` together, whatever the guest does, counting only while the host is
started; a declined shell goes in the frame its replacement appears, and within the same bound. A
`Cancelled` reveal holds back for at most `CANCEL_LIMIT`.

**Tested:** host tests with a guest that never loads, one that never sends `ready`; for (B), with a
fake clock: one that never answers (the indicator at 0.7 s, the list at 1.0 s, and `cancelReveal`
sent at 1.0 s); **one that answers `Bind` but whose content at the bound epoch never arrives** (the
same: `Binding` ends at 1.0 s in `Cancelled`, H3); one whose answer arrives at 0.85 s, inside the
indicator's window (handled: the entry goes live); one whose click does not navigate
(`Decline(noNavigation)`, the list in that frame); one whose answer comes after `REVEAL_LIMIT`
(ignored; the guest's stack ends `[List]`); one that never acks a cancel (the held back is
re-dispatched and the callback removed at `CANCEL_LIMIT`, and `nav.cancelTimeout` counted; after
the limit, 10 s on the fake clock, every frame still draws the list, no frame contains a node of
what t pushed, a later back reaches the guest's callback, and a later navigation declared by the
guest is not drawn: round 5, L2, the bound being only a crash or a code swap, each tested to end
`Cancelled` in its frame); **one stalled for 2 s after the click,
with back at 20 ms and again at 300 ms** (round 4, N3): at 1.0 s the callback is removed, the
held back is re-dispatched as a back on the list (the guest's last reported back state is
disabled, so the activity's fallback receives it) and `nav.cancelTimeout` is counted; the guest's
bound content then lands at 2 s, and no frame from the first back on, before or after the ack,
contains a node of the detail; and one stopped at 200 ms and started
10 s later (no timeout; the limits paused).

### I13. The reveal (B)

Every reveal token gets exactly one answer from the guest (`Bind` or `Decline`), kept per token so
no later token's answer replaces it, plus a `cancelAcked` if the host cancelled it. At most one
token is outstanding. A reveal is issued only for a click the row's widget actually emitted. While
`Revealed` or `Binding` the shell is inert: no tap, scroll or focus reaches the guest, the shell's
`revealBack()` aside, and nothing below the shell is hit. Back during the reveal is the host's,
never the activity's and never the guest's, and the guest undoes only token t's own navigation,
after running the click. A back while `Cancelled` is held by the host (at most one) and
re-dispatched as a back on the list when `Cancelled` ends or at `CANCEL_LIMIT`: it reaches the
guest's back callback, or, when the list has nothing to pop, the activity, as a back on the list
would. What t pushed is never drawn after the back, even past `CANCEL_LIMIT` (N3). The reveal's
back callback has an owner from the click on and never outlives the container or the code (N4). The reveal is announced as busy (after 500 ms), and the destination at the
bind (3.2).

**Enforced:** the protocol of 3.2 (the generated click dispatch, one token at a time, the one-task
send, the back callback registered in the click handler with the container-level holder, the held
back while `Cancelled` and its release at `CANCEL_LIMIT`, the token-scoped cancel, `Cancelled` held
until `cancelAcked ≥ t` and every entry the ack lists has reached its `e_ack`; the holder re-adding
the current callback when it enters a new composition, round 5, M1).
**Tested** (host tests, JVM Compose UI with a fake clock and a scripted guest on keliver-nav, whose
bind batch comes 150 ms after the click; each can fail):
- **the guest killed at 50 ms after the tap, and the code swapped at 50 ms** (H2): the reveal's
  callback is removed in that frame; the next back reaches the activity (killed) or the new code's
  content (swapped); no later frame shows the shell; a `cancelReveal` to the new guest, if one was
  queued, only acks;
- **a code swap between the click and the next composition** (round 4, N4): the click handler has
  added and registered the callback, and the code swaps before any frame composes: no reveal
  callback remains on the dispatcher, and the next back reaches the new code's content;
- **back, then a re-tap on item 8 within 50 ms** (round 4, N2; timed, with the fake clock): the
  re-tap takes the non-revealing path; the guest runs click(t), cancel(t), then item 8's click,
  binding the same entry. Item 8 shows in the frame its content is applied; until then the list,
  and no frame contains a node of t's item; `CANCEL_LIMIT` is not reached and `nav.cancelTimeout`
  is not counted. Run with item 8's click in the ack's batch and in the batch after it;
- **back twice within 100 ms with the guest busy** (H2): the second press is held, never reaches
  the guest while `Cancelled`, and is re-dispatched after the ack as a back on the list; the guest's
  stack pops exactly once per press it was given, and never the list by mistake;
- **two-finger tap and back-then-re-tap** (H4), as in 3.2;
- **non-clicks** (M3): a scroll that cancels the press, a long press, a disabled row, a trailing
  button: no reveal;
- **`Decline(otherKind)` then back before the login is effective** (M1): the list stays drawn, the
  login never appears, the guest's stack ends `[List]`. Run with the login non-rebindable (the cancel
  disposes it) and with it a rebindable entry of another kind (the cancel parks it, and the ack
  lists it with its `e_ack`, round 5, M2), each also **with the ack forced a batch before the park's
  `(Prepared, e+1)`**: `Cancelled` holds until the parked entry's state and content are applied, and
  no frame after the back contains a node of the login;
- **the revealed entry removed, and a bind of another entry of the same kind** (M2), as in 3.2;
- **activity recreation in each state** (M6), as in 3.2, with a back after each recreation: the
  host's, never the guest's or the activity's (round 5, M1);
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
  node, a live region only from 500 ms (a reveal live at 150 ms queues no announcement); in the
  bind's frame the entry's root carries the `paneTitle` and the heading is the first node in
  traversal order (the order is tested, not TalkBack's focus); on the iOS simulator,
  `.screenChanged` posted in the bind's frame, not before, and `accessibilityPerformEscape` on the
  "Loading" element cancels the reveal as back does.

### I14. An entry's state is applied only with its content (B)

The host shows an entry as `Prepared`, `Revealed` or `Shown` only when the content it shows was
composed for that state's route: never the last item's content under a new item's tap, never a
`Shown` entry whose bound content has not arrived. The entry's state comes from keliver-nav's main
composition and its content from the sub-composition (3.4); they reach the host in either order.

**Enforced:** the epoch rule (3.4, 3.5): an entry's newest declared `(state, epoch)` takes effect
only when its content root's `ContentEpoch` equals the epoch; **until then the entry is `Pending`**:
not drawn, hit-tested, focused, revealed or a back target, with the nearest drawable entry below it
shown. It never falls back to an earlier `Shown` (round 3, H1: falling back re-showed item 7's
detail, live, under item 8's tap). **Under a reveal, `Pending` does not apply** (round 4, N1):
while the host holds `Revealed(t)` or `Binding(t)` for an entry, it draws that entry with its
current content, inert (round 5, M3: the shell until the bind's content is applied, then that
content), whatever `Pending` says, and the entry goes live only when state and content agree.
`Cancelled(t)` ends only when `cancelAcked ≥ t` and every entry the ack lists (the revealed entry
and each entry t's click pushed that the cancel parked) has an effective epoch of at least its
`e_ack`, its epoch as declared right after the cancel, which the ack carries (round 4, N2; round 5,
M2 and L1),
so the parked content arriving a batch after the ack cannot flash the detail, and a `Shown` after
that, a new navigation, does not keep `Cancelled` alive. A tap finds a revealable entry only in an
effective `Prepared` state. **Tested:**
- **the bind's state one batch before its content** (round 4, N1): a reveal; `Bind(t)` with
  `(Shown, e+1)` in one batch, the content at e+1 in the next. Every frame from the tap shows the
  shell, inert, until the content's frame, which shows the live detail; no frame shows the list in
  the shell's place. **And the reverse order (the content first), with its own frames** (round 5,
  M3): the shell, inert, until the content's frame; from that frame the bound content, inert (taps
  consumed, the indicator's rules unchanged); live in the frame its `(Shown, e+1)` is applied; no
  frame shows the list;
- **the state first, with the content coalesced** (H1, scenario A): item 7 shown at e+1; back (the
  retained back shows the list); the guest's `(Prepared, e+2)` and `backHandled` arrive; the
  parked content skips e+2 and comes as e+3 a batch later. Between those batches the entry is
  `Pending` and the list is drawn: no frame contains a node of item 7, and a tap on item 8 in that
  window takes the non-revealing path;
- **the ack first, the parked content a batch later** (H1, scenario B): a reveal, back,
  `Cancelled(t)`; `(Shown, e+1)` and its content arrive, then `cancelAcked = t` with the pop's
  `(Prepared, e+2)`, then the parked content a batch later: the entry is effectively `Shown` until
  the ack's batch, so `Cancelled` holds; from the ack it is `Pending` (not drawn) until the parked
  content lands, which makes its effective epoch e+2 = `e_ack` and ends `Cancelled`; no frame
  after the back contains a node of the detail. The same with the ack forced into an earlier batch
  than the pop's state: the ack carries `e_ack` = e+2, so `Cancelled` holds until that state and its
  content are both applied;
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
  - **The lines are recorded.** At the `ready` edge the recorder asks each text-bearing widget in
    the marked subtree how each text property laid out (`TextLines`, 2.5; host widgets read it from
    `onTextLayout`): its line count `n` and, per line, the line's laid-out width
    (`getLineRight(i) − getLineLeft(i)`) and character count (`getLineEnd(i) − getLineStart(i)`).
    A widget that does not implement it blocks the write (fail closed).
  - **The placeholder has exactly those lines, each sized from its own line** (round 3: sizing
    every line as `0.85 × L / n` breaks on a text made mostly of narrow letters, such as "i" and
    "l", where 0.85 of the character count in figure spaces is wider than the line). Line `i`
    becomes groups of four figure spaces (U+2007) joined by spaces (U+0020), so it can still break,
    with as many characters as fit in 0.95 of that line's laid-out width, measured in the text's
    own style with the host's text measurer at record time (the display, font scale and fonts are in
    the key, 5.1, so the replay lays out with the same metrics), and never more than the line's own
    character count. The lines are joined by `\n`, which gives the line count. There is no cap on
    the length; the frame's size cap bounds it. The widget's `maxLines` and every style property
    are kept, so the line height is the original's.
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
| (B) The guest does not answer a reveal, or answers `Bind` but its content never arrives | `REVEAL_LIMIT`, from the tap to live | the shell, with the indicator from `REVEAL_LIMIT` − 300 ms, then the list | `nav.revealTimeout(kind, state)`; `cancelReveal` sent |
| (B) The guest declines a reveal | its answer | the list, or the other destination, in that frame | `nav.revealDeclined(kind, reason)` |
| (B) The guest never acknowledges a cancel | `CANCEL_LIMIT` | the list; a held back is delivered at the limit, and what t pushed stays hidden until the ack (N3). Nothing but a crash or a code swap bounds `Cancelled` after that: a guest alive that never acks leaves the container on the list (round 5, L2) | `nav.cancelTimeout(kind)` |
| (B) The guest crashes, or the code swaps, during a reveal | the content's crash or code-swap path | as today for a crash or a swap; the reveal's back callback is removed with the container | the reveal state is reset |
| (B) The revealed entry disappears before the answer | the container's renderer | the list, in that frame; back still the host's until the token is answered | none |
| (B) A tap on a `revealsPrepared` row cannot reveal | the click dispatch | the non-revealing path | `nav.revealSkipped(kind, reason)` |
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
| **R0** | **The A12 trace of one floor step** (appendix D): series H's arms (the detail already composed with the right item; click → composed 57.5 ms simple, 111–114.5 heavy) and a new **idle arm** (the same detail, the finger held 400 ms so that the press's preparation has finished and the click reaches an idle guest, as (B)'s will), under Perfetto with `x=trace`, decomposed into the hop to the Zipline thread, the guest's event, the wait for its next frame tick (to the main thread's frame callback, then back), the guest running, preempted or blocked (and on what), the batch to the main thread, the frame and the present. Dry-run end to end on an emulator on 2026-11-24 (appendix D); on the A12 on 2026-11-25 | **The rule is a projection, on the idle arm, from the finger lifting** (the reference of native's 28 ms; the bench's own times start at the DOWN): P = the arm's median UP → present − the waits with a named mechanism that a host or runtime change could remove + the rebind's extra guest work (14 ms at a ratio of 12). **Reachable in principle** if P ≤ 100 ms **and** the Zipline thread runs for at most 25 ms of click → composed (on any CPU, at any frequency: running on the slow cluster or down-clocked counts in G, never in W; round 4, R-M1). Only opens whose guest was idle at the UP count (round 4, R-L1). **Not reachable by (B)** if P > 100 even with every wait removed, or the Zipline thread runs 40 ms or more. Between: reported to the owner with decision 7. The press-on arm is reported beside it. Heavy is out of reach either way (its floor step alone is 111–114.5 ms) |
| **R1** | **A reveal-only arm** on the prototype (gate A's container in the three-way bench, a small bench PR): the host shows a hidden, already composed detail on the click without asking the guest, with and without measuring it while idle (3.2). Perfetto on the phone, as series C: tap → the reveal's first draw and its frame presented; and click → composed with the reveal on and off | **B-1f's bar, on the same arm** (round 3, M5): the shell's first draw within native's first draw + 16.7 ms (about 45 ms from the tap), simple and heavy. **Native's first draw is that of the native arm of the same detail** (round 4, M5's nit): `-e form native -e bench nav`, and with `-e x heavy` for heavy (the commands of THREE_WAY_BENCHMARK.md's "How to run it"), run in the same session and build, whose first draw is the detail with its data in hand. R1 runs on simple and heavy, so `native,detailfetch` (B-1f's comparison, whose first draw is native's skeleton) does not exist yet; B-1f compares with it later, on `detailfetch`. **Read twice** (maintainer's ruling, 2026-11-26, after the emulator dry run found a reveal frame that queued no buffer; the owner may change it before the A12 run): met only if the first draw is within native's + 16.7 ms **and** the buffer the reveal's draw queued (`q_drew`) is within native's + 16.7 ms; a first draw within and a buffer beyond is "not met (no buffer in the reveal's frame)" ([`docs/nf5-r1/`](nf5-r1/README.md), "The bar"); with the idle measure, the reveal's `Record View#draw()` under 10 ms. **Also reported: click → composed with the reveal on and off**: the reveal's frame takes the main thread when the guest's frame tick needs it (the tick is a Choreographer callback, `AndroidChoreographerFrameClock.kt:31–36`), so it may add to the floor step, which appendix B assumes it does not. If either misses, the shell's first layout is the cost to cut before B4 |

R0's G is appendix D's, which governs: G = `d_running`, the Zipline thread's running time inside
F → C (the recomposition). The click's event and `c_zl_run` run inside click → composed but are not
in G. R0 ran on the A12 on 2026-11-26 (section 0).

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

How each open is judged depends on decision 7 (section 11), decided by the owner on 2026-11-24:
(b), the split.

| Gate | Measure | Pass |
|---|---|---|
| B-1 | open, simple, tap to data drawn (frame end) | target about 100 ms; expected 120–150 with the reveal (appendix B), less if R0 found removable waits |
| B-2 | open, heavy, tap to data drawn | target 150 ms or less; expected 225–275 |
| B-1r, B-2r | with the reveal: tap to the shell's first draw and its frame presented | within native's first draw + 16.7 ms (R1's bar, which is B-1f's); data as B-1, B-2 |
| B-1f | `detailfetch`, guest with the reveal against `native,detailfetch`: tap to the first draw of the destination, and tap to its data drawn | under decision 7's answer (b): first draw within native's + 16.7 ms (one frame), data within native's + 100 ms (the 100 ms is the owner's choice, recorded as such, not a derived number). Applies only to kinds that declare their data arrives by `fetch` and pass the generated check of it (decision 7) |
| B-3 | a tap while a preparation slice runs | no slower than open without preparation |
| B-4 | back | no regression against `x=retainhost` |
| B-5 | the generated inertness tests on the three-way app and the reference app's declared kinds | zero calls out |
| B-6 | `varied` and `detailfetch` | reported against `x=prepkind` |
| B-7 | iOS simulator, the same arms | the same ordering as the A12 |
| B-8 | layout shift (I4b): per open, the largest move of a first-viewport node present in both the reveal's frame and the data's frame | at most 1 dp on every open |
| B-9 | parks against disposals (3.3): `nav.parked` and `nav.disposed` per kind over a browsing script, three-way app and the reference app's Style B screen | every declared kind parks on at least 95% of its pops; a kind below that is reported with its leaked effect |
| B-10 | reveal outcomes over the same script | no `revealTimeout` and no `cancelTimeout`; declines only where the script opens another kind; `revealSkipped(notPrepared)` reported per kind (decision 16) |

### Regressions to watch

| What | How | Limit |
|---|---|---|
| Scroll jank from idle preparation (B) | series F3's flings, preparation on and off; frames over 25 ms | no increase beyond run-to-run spread |
| Memory (A and B) | PSS after the list (F5's method), with and without, on the three-way app and the reference app | decision 6 |
| Battery (B) | guest CPU time spent preparing, per minute of browsing, on both apps | decision 6 |
| APK size | F4's method, on an R8 release build of the reference app, per half | under 50 KB dex for each half (decision 17) |
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
(decision 7, decided on 2026-11-24; R0, R1), then B1, then the production PRs once NF1's
production container exists (B0). The review of sections 3.2 and 3.5 that revision 3 asked for was
round 3: (B) approved after fixes, made in revision 4 and checked in rounds 4 and 5 (revisions 5
and 6), with no new design round needed.

**H0 and (A)**, approved by the second review after this revision's fixes:

| PR | Module(s) | What | Tests | Gate |
|---|---|---|---|---|
| **H0** | templates, `keliver-treehouse-host-composeui` | host feature names as constants in one place; templates declare what they implement; `TreehouseContent(backEnabled)` and its use for hidden content; the lastGood record with sequence and module digest, written with `commit()` | a test that each template declares exactly its features; back with hidden content reaches no guest | apiCheck; reference-app and ios-host workflows |
| **A1** (retargeted under Q17, [`MS4_PLAN.md`](MS4_PLAN.md) 2.2) | `keliver-schema` annotations, `keliver-tooling-schema` (parsing), `keliver-tooling-codegen`, `keliver-gradle-plugin` (strict), `keliver-compose-generator` (the frame-rule emitter), the prototype's `gatea` group | `@Replayable`, `@Scrolls`, `@NeverCached`, `@BlocksFrameWhenTrue`, `@StyleToken`, `@NotData`; generated `FrameRules` and the checked-in review list, strict mode (`redwoodSchema { frameRules { strict = true } }`); **the prototype's group classified** by the generator from each parameter's category, with `gatea.json`'s exceptions, and strict (2.4). Legacy's schemas are not annotated (Q17 (iii); (i) alone adds them); the product's groups are MS4-36's | codegen tests (the lists for a test schema; strict mode fails on an unclassified property; the review list's content); parser tests (each annotation's check; the `String` walk); the emitter's tests; `checkComposeApi` (the generated schema's staleness guard) and the review list's strict check | apiCheck |
| **A2** | `keliver-protocol`, `keliver-compose`, `keliver-protocol-host` | `FrameCacheTags` (lock test); `CachedFrame` (with `paneTitle`), `cached()`, `notCached()`, `FrameScope`; `HostProtocolAdapter` consumes the reserved tags | batches byte-identical without the feature; with it, only the markers; reserved tags never reach the mismatch handler | apiCheck |
| **A3a** | `keliver-treehouse-host` | `ChangeFolder`: the fold, pruning by entry state, `Shell` (placeholders by line count, strings inside structured values, `@StyleToken` kept), `notCached`, `@NeverCached`, position keys | the round-trip tests of I11 (raw batches on one host, the folded snapshot on another, trees compared); random batch sequences; I2's byte searches | apiCheck; unit tests on JVM, Android and the iOS simulator |
| **A3b** | `keliver-treehouse-host` | `FrameStore`, the format, the key and invalidation, the session record and fail-closed rule, the off switch, clearing, size caps | I1's key tests, I5, I10 | the same |
| **A3c** | `keliver-treehouse-host`, `keliver-widget`, `keliver-protocol-host` | `FrameRecorder`: the rule of 2.3 with all seven conditions, `inputSeen()`, `ScrollOrigin` and `TextLines` (the interfaces) and `widgetsUnder(id)`, `writeSkipped(reason)` | I11's navigation test; one blocked write per condition, each recording its reason; a scroller tag without `ScrollOrigin` blocks the write | the same |
| **A4a** (retargeted under Q17) | `keliver-treehouse-host-composeui`; the prototype's composeui module and the product's `LazyRuntime` copy; the generator's host emitter | the root input observer in `TreehouseContent`; `ScrollOrigin` on `LazyColumn`/`LazyRow` (the hand `LazyRuntime`'s two copies) and on `verticalScroll`/`horizontalScroll`; `TextLines` on `Text`; replay snapping for `AnimatedVisibility` and `animateContentSize` (2.4). Hooks in generated renderers come from the generator's host emitter, so they reach both copies by regeneration. Legacy's `AnimatedBorder` clock phase and its pager's auto-advance are not built (MS4-36 builds the generated pagers') | per scroller: scrolled by a pixel blocks the write; I4's height-equality test; the snapping tests (2.4); input observer tests | apiCheck; the height test |
| **A4b** | `keliver-treehouse-host-composeui` | `CachedFrameContent` (inert, cache-only images, indicator slot, no frame for a restored binding), `FrameHandOff` (both limits, deferred update, `LocalContentShown`), the swap's accessibility | I3, I4, I12 host tests; **0 differing pixels on the prototype's generated widgets** (retargeted under Q17, as appendix C's table A), replayed against live, on real multi-batch sequences from the sample app's screens | the host tests; the pixel gate |
| **A5** | templates (Android, iOS), docs | the start table on `StartChoice.mode` (and option (c) if decision 8 takes it), the `codeLoadSuccess` check, `session()` and `accounts = NONE`, storage dirs, the off switch from the index, adopter docs (what `Shell` does not hide, I2) | template tests (start table; backup exclusion; fail closed) | reference-app and ios-host workflows |
| **A6** | sample, benchmarks | the bench's `x=cachedframe`, both marks, the throttled lookup; the iOS simulator arm | — | **A-1 to A-8**; the owner reads them before (A) is on by default |

**(B)**, in this order. Its PRs wait for R0 and R1 (decision 7 was decided on 2026-11-24, and the
review is done):

| Step or PR | Module(s) | What | Tests | Gate |
|---|---|---|---|---|
| **D7** | — | done: the owner decided what "open" means for the bar on 2026-11-24, (b), the split (decision 7) | — | — |
| **R0** | none (a phone session) | the floor step under Perfetto (appendix D), 2026-11-25; its files dry-run end to end on an emulator on 2026-11-24 (decision 12) | — | appendix D's rule: is the simple target reachable? |
| **R1** | sample (bench only, prototype) | the reveal-only arm (section 7) | — | R1's bar (section 7), B-1f's |
| **Review** | this document | done: round 3 reviewed 3.2, 3.5, I4b, I13 and I14; (B) approved after fixes, made in revision 4 | — | — |
| **B1** | `keliver-nav` (spike, guest profile, gate A's container) | held-effect sub-composition, slices, a tap during a slice; **the reveal protocol** (the generated click dispatch, tokens one at a time, per-token answers, the token-scoped cancel, the back callback's lifetime, the held back while cancelled, `REVEAL_LIMIT` from tap to live, the one-task send) and **the epoch rule with `Pending`**; the checklist in 3.7 | the guest profile; I12's, I13's and I14's host tests against the prototype, **including round 3's**: the state first with the content coalesced to e+3; the ack first with the parked content a batch later; the guest killed and the code swapped at 50 ms; two backs within 100 ms with the guest busy; a `Bind` whose content never arrives; an answer inside the indicator's window; the two-finger tap; back then a re-tap within 50 ms; `otherKind` then back; the entry removed mid-reveal; the non-clicks | "feasible with public Compose API, within the slice budget, **and I12, I13 and I14 hold with both orders of the two recomposers' changes**"; else (B) stops and goes back to the owner |
| **B0** | precondition (NF1's production step), the container's renderer | the retained container in the production widget system, with the entry state, the epoch rule and the idle measure (3.5); `keliver.nav.retainedContainer@2` | NF1's; back never reaches a `Prepared` entry; I14's host tests | NF1's |
| **B2** | `keliver-nav`, docs | kinds, shells, epochs, bind and park, the park check, `Prepared`, per-visit counts, `dataArrives`, the generated tests (all seven), the token-scoped cancel and `revealLive`, park and dispose counts, the retention docs rewrite with the Style B presenter | I6 and I7 unit tests, "inert after a visit" | apiCheck |
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

**Decided by the owner on 2026-11-24** ("go with b, ok, yes"): decision 7 is **(b), the split**, with
round 3's three conditions; decision 8 is **as recommended**; decisions 1–6 and 9–17 are **yes, as
recommended**. Every recommendation below is therefore the decision. Revision 5's R0 queries ran
cleanly on the emulator the same day (qc 12/12 on all four arms), so (B) now waits only on R0
(A12, 2026-11-25) and R1.

Each: the question; my recommendation, with the second review's answer where it gave one; the
cost.

1. **Does a cached `Whole` frame count as "the list's data" for the cold-start bar?**
   *Recommendation:* yes, within the screen's `maxAge`, with A-3 (guest took over) reported beside
   it; the bar reads "data (cached or live)". `Shell` counts only as first content. Review: agrees.
   *Cost:* the bar can be met with data up to `maxAge` old, as native apps with an offline cache do.
2. **The limits.** *Recommendation:* `maxAge` cap 24 h; `HANDOFF_LIMIT` 3 s; `FRAME_LIMIT` 5 s;
   512 KB per frame, 2 MB per `cacheName`; `REVEAL_LIMIT` 1 s from the tap to live, the indicator
   from 0.7 s (revision 4: the 300 ms no longer extend it); `CANCEL_LIMIT` 1 s after a cancel (new
   in revision 4); both paused while the host is stopped. Add an A6
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

   **Round 3's answer: agree with (b), decided before B0, with three conditions, folded in:**
   - **Each kind declares how its data arrives**: `KeliverNavKind(dataArrives = Arguments | Cache
     | Fetch)`. Only `Fetch` kinds are judged by B-1f's first-draw bar, and a generated check
     confirms the declaration, so that a screen cannot call itself "fetch": `assertDataArrival(kind)`
     binds each sample route against capabilities that never answer; a `Fetch` kind must make at
     least one network capability call after the bind and draw none of the sample's data until it is
     answered; an `Arguments` kind must draw its data with every capability failing; a `Cache` kind
     only reads storage. A wrong declaration fails the kind's generated tests.
   - **R1's bar is B-1f's** (M5): the shell's first draw within native's first draw + 16.7 ms, on
     the same arm (section 7).
   - **"+100 ms for data" is the owner's own choice**, recorded as such, not a derived number.

   Stated plainly: under (b), a destination whose data is in hand (the three-way detail, an
   `Arguments` kind) is still judged on its data, by B-1 and B-2. If R0 says the simple target is
   unreachable, (b) does not spare such destinations the parked fallbacks: they come to the owner
   for them.
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
    a phone session, a bench PR and a review. **Round 3:** agrees, and adds an emulator dry run of
    R0's scripts before the phone session: done on 2026-11-24 (appendix D, "Dry run"); the review
    of 3.2 and 3.5 was round 3 itself.
13. **`Shell`'s placeholders: blank or skeleton bars?** *Recommendation:* bars (one rounded bar per
    placeholder line, in the text's colour at low alpha) for `Text`, `StyledText`, `RichText` and
    `ListItem`; blank elsewhere. *Cost:* a replay branch in four host renderers (A4a); an app's own
    text widgets draw blank unless they use the shared helper. **Round 3: agrees, bars.** They are
    static (no shimmer, which would also be a loop to match at the swap) and match each line's
    height exactly (I4's height test covers the bars); an app's own text widgets use the helper.
14. **Screens with a search field.** *Recommendation:* replay `TextField`, `OutlinedTextField` and
    `TextInput` inert with their value `@NeverCached`, drawn empty, from A1. *Cost:* three more
    widgets in A1's and A4a's tests; a value the guest restores appears at the swap. **Round 3:
    agrees, replay them empty.** A replayed field never takes focus (the frame is unfocusable); a
    field that had focus before `ready` already blocks the write (condition 4); its label and hint
    follow `Shell` like any text.
15. **After a declined reveal.** *Recommendation:* the shell goes in the frame its replacement
    appears (the list, or the other destination once effective), never with a list frame between,
    and the entry goes back to `Prepared` (3.2).
    *Cost:* a mis-marked row shows the wrong shell for one guest round trip (about 150–270 ms);
    declines are counted, and debug builds fail on `otherKind`. **Round 3: agrees for
    `noNavigation` and `cancelled`; for `otherKind`, once back while waiting (M1) and a removed or
    replaced entry (M2) are defined.** Both are now (3.2): a back while the other destination is not
    yet effective keeps the list drawn and the guest's cancel pops it; a `Bind` of another entry is
    treated as `otherKind`; a removed entry shows the list and keeps back until the answer.
16. **The 300 ms idle constant** (no input, scroll or guest frame before preparing). *Recommendation:*
    300 ms as the default of an app setting (`KeliverNavIdle(quietMillis)`), revisited with B5's
    numbers. *Cost:* shorter collides with input and scrolling; longer means a quick user taps
    before the shell exists and gets the non-revealing path. **Round 3: agrees, with
    `nav.revealSkipped(kind, notPrepared)`** (3.2) in release telemetry, so the 300 ms can be revisited
    with data (B-10).
17. **The size limit** (50 KB of dex for each half, F4's method). *Recommendation:* keep it; a PR
    over it explains why in its description. *Cost:* the fold, store and replay may come close; the
    limit then forces trimming or your waiver. **Round 3: agrees, keep 50 KB, measured on an R8
    release build of the reference app, per half** (section 7, "APK size").

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

### Round 3: the review of revision 3, answered in revision 4

Verdicts: **(A) approved**; **(B) approved after fixes** (H1–H4, M1–M6, Lows), with no fourth
design round if they go in as written; R0 to change before the phone session (R0-1 to R0-6). 34
rows: **32 fixed** as the reviewer wrote them (two with a stated choice between the reviewer's
options: the placeholder width, the backgrounded reveal), **2 agreed with no further change** (the
review-2 (A) fixes; decisions 1–6 and 8–11). None rejected. Round 2's open row, the `Main.kt` compile, is closed: R0's build
compiled it on 2026-11-24 (appendix D, "Dry run").

| Finding | Response |
|---|---|
| (A) review-2 fixes (H6, M1–M4, Lows) | Agreed: approved; no change beyond the three notes below |
| (A) M2 note: size each placeholder line from its own line; narrow-letter text | Fixed: 5.2. `TextLines` reports each line's width and character count (`getLineStart`/`getLineEnd`); each placeholder line fills 0.95 of its own line's width, measured in the text's style, never more characters than the line had. Choice: width-based rather than `0.85 × the line's count`, because a line of narrow letters breaks a count-based length too. I4's corpus adds narrow- and wide-letter texts |
| (A) M4 note: accessibility actions bypass `inputSeen()` | Fixed: 2.3, condition 4. The recorder wraps the event sink: any event before `ready` other than codegen's listed reports counts as input; Android's root accessibility delegate reports scroll and focus actions. Host tests: a semantics click and a semantics scroll block the write |
| (A) Low: `paneTitle` does not move TalkBack's focus | Fixed: 2.6, 3.2, I3, I13 say TalkBack announces the pane; the heading is first in traversal order, and the tests check the order, not focus movement |
| H1 the epoch rule's fallback re-shows the old item | Fixed: 3.5, I14. A declared epoch that differs from the applied content's makes the entry `Pending`: never drawn, hit-tested, focused, revealed or a back target; the nearest drawable entry below is shown; never an earlier `Shown`. `Cancelled(t)` ends only at `cancelAcked ≥ t` and with the entry not effectively `Shown`. Tests: the state first with the content coalesced to e+3; the ack first with the parked content a batch later |
| H2 the reveal's back callback has no lifetime; the cancel is not token-scoped | Fixed: 3.2. Added in the click handler, owned by the container's composition (a `DisposableEffect` keyed on the token), removed on live, decline, end of `Cancelled`, container disposal, view detach and code swap. While `Cancelled` back is the host's: held and re-dispatched after the ack, bounded by `CANCEL_LIMIT` (1 s, decision 2). The guest's cancel undoes t's navigation only while it is in effect; otherwise, and for an unknown token, it only acks. Tests: guest killed at 50 ms; code swapped at 50 ms; two backs within 100 ms with the guest busy (I13) |
| H3 `Binding` has no time bound | Fixed: 3.2, I12. `REVEAL_LIMIT` covers tap → live over `Revealed` and `Binding`; at the limit `Cancelled` and `cancelReveal` at once; the indicator is cosmetic, from `REVEAL_LIMIT` − 300 ms; later answers ignored. Tests: a `Bind` whose content never arrives; an answer inside the indicator's window |
| H4 more than one outstanding reveal | Fixed: 3.2, I13. No reveal while any token is `Revealed`, `Binding` or `Cancelled` (`nav.revealSkipped(kind, outstanding)`); pointer events aimed below the revealed entry consumed from the click handler on; `revealAnswers` keeps one answer per token. Tests: the two-finger tap; back then a re-tap within 50 ms |
| M1 `Decline(otherKind)` then back loses the back | Fixed: 3.2. The guest records the stack at `reveal(t)` and its cancel restores it (popping the login too) while t's navigation is in effect; while `Cancelled` the host draws the entry shown at the tap, whatever the guest declares. Test in I13 |
| M2 the revealed entry removed or replaced mid-batch | Fixed: 3.2. `Bind(entryKey)` checked against the revealed key, a mismatch treated as `otherKind`; on removal the host shows the list and keeps the back callback until the token is answered. Tests in 3.2 and I13 |
| M3 "in the click handler" has no mechanism | Fixed: 3.2. The generated host dispatch of a widget's click event calls `sendEvents(reveal, click)` only when that widget's own click is emitted and `LocalRevealTarget`'s widget id is its own; no schema change. Tests: scroll-cancel, long press, disabled row, trailing button |
| M4 I4b's generated test has three escapes | Fixed: 3.4, I4b. Space reserved by `shellOnly` nodes must be filled (first data node at the region's top ±1 dp); fixture strings replaced by A4a's corpus at maximum lengths; an image without a fixed size or aspect ratio fails the test. A test of the test for each |
| M5 R1's bar looser than B-1f's; the reveal may add to the floor | Fixed: section 7. R1's bar is B-1f's (native's first draw + 16.7 ms), on the same arm; R1 also reports click → composed with the reveal on and off |
| M6 activity recreation during the reveal | Fixed: 3.2, 3.5. The reveal state lives with `TreehouseAppContent`, survives the view's rebinding, resets on a code swap. Host test: recreation in each state |
| Low: 3.2 said content, state and answer come from one composition | Fixed: 3.2 says the state and the answer come from the main composition, the content from the sub-composition, joined by I14 |
| Low: `LaunchedOnceShown` runs for a reveal cancelled at 80 ms | Fixed: 3.2, 3.3. A revealed bind counts its first show at the host's `revealLive(t)`; a cancelled reveal runs no `LaunchedOnceShown`. Unit test |
| Low: backgrounded during the reveal | Fixed: 3.2, I12. Choice: pause both limits while the host is stopped (rather than state the outcome), so the user returns to the reveal. Host test |
| Low: a "Loading" announcement queued after the pane title | Fixed: 3.2. The node is a live region only from 500 ms |
| Low: VoiceOver cannot reach `revealBack()` | Fixed: 3.2. `accessibilityPerformEscape` on the "Loading" element, mapped to the reveal's back; simulator test (I13) |
| Low: `reveal@1` must say it requires `retainedContainer@2` | Fixed: 3.2, I8, 3.5. A host declaring only `reveal@1` gets neither; H0's feature test fails such a template |
| R0-1 the press-on arm does not measure (B)'s click | Fixed: appendix D, `run.sh`. New arm `simple-on-idle` (`--ei hold 400`); the row has no long-press handler (`clickable` only, `ThreewayScreens.kt:249`); the rule applies to it, press-on reported beside. The emulator dry run confirms the click comes at the UP (click 402–407 ms from the DOWN) |
| R0-2 the rule needs mechanisms the trace cannot give | Fixed: `cfg.txt` adds `sched/sched_blocked_reason`; `measure.sql` splits `d_other` into S and D, q3 lists the blocked functions, and `c` into `c1` (E's end → the main thread's frame callback) and `c2` (callback → F) |
| R0-3 W counted `c_zl_running` | Fixed: W = (c − c_zl_running) + d_runnable + d_other, in `measure.sql` and q2 |
| R0-4 the reachable rule still lands above the target | Fixed: section 7 and appendix D. The rule is a projection from the UP (native's reference): the arm's median UP → present, minus the waits with a named mechanism, plus the rebind's 14 ms, at or under 100; q2 prints the bound with all of W removed (`P_allW_r12`, `P_allW_r30`) |
| R0-5 none of it has ever run | Fixed: run end to end on the emulator (appendix D, "Dry run"): the build, install, push, all four arms, every query, by the session's literal command list. It found and fixed six errors (the grouping gap, a LIKE that returns the wrong row in trace_processor v45, the two events at the UP, the check's start point, a check that compared two runs, now `qc.sql` with a `pass` flag, and q3's empty `blocked_function`: `symbolize_ksyms`, plus the waking thread). All four arms pass qc, 12 of 12. Minimum Android version: 12 |
| R0-6 one stray call shifts every open | Fixed: `<arm>.opengroups.txt`, the open groups' numbers from q0a, replaces the default in the prelude (`analyse.sh`) |
| Decision 7 | Folded in with its three conditions: `dataArrives` with `assertDataArrival`; R1's bar = B-1f's; +100 ms recorded as the owner's choice; data-in-hand destinations still judged on data |
| Decision 12 | Folded in: the emulator dry run before R0, done |
| Decision 13 | Folded in: static bars, exact line heights, the helper for an app's widgets |
| Decision 14 | Folded in: replayed empty, never focused; focused field blocks the write; labels follow `Shell` |
| Decision 15 | Folded in: agreed for all three reasons now that M1 and M2 are fixed |
| Decision 16 | Folded in: `nav.revealSkipped(kind, notPrepared)` |
| Decision 17 | Folded in: 50 KB, R8 release build of the reference app, per half |
| Decisions 1–6, 8–11 | Agreed as folded in; no change |

### Round 4: the check of revision 4, answered in revision 5

Verdict: **(B) approved after fixes** (N1, N2; N3 and N4 as one-line rules), rule changes with no
new design round; **R0 ready** once R-M1 is written into the rule. Every round-3 row was found done.
10 rows here: **8 fixed** as the maintainer scoped them (corrected in revision 6, round 5's L4: the
R-L2 to R-L4 row is not in the revision and the verdict row is agreed). R-L2 to R-L4 (the session's
command list and scratch copies, not this document) are left to the session: see the PR's
"Decisions needed".

| Finding | Response |
|---|---|
| N1 `Pending` contradicts `Binding` on the reveal's main path | Fixed: 3.2 (new paragraph and the `Binding` row), 3.5, I14. While the host holds `Revealed(t)` or `Binding(t)` for an entry it draws that entry, inert, with its last effective content (the shell), whatever `Pending` says; `Pending` governs only entries not under a reveal. Test (I14): the bind's state one batch before its content, and the reverse: shell, then the live detail, no list frame |
| N2 `Cancelled` ends on the entry, not on token t | Fixed: 3.2 (the `Cancelled` row, the guest's ack, a new paragraph), I14. `Cancelled` ends at `cancelAcked ≥ t` with the revealed entry's effective epoch at least `e_ack`, the epoch declared in the ack's batch, which the ack carries (so the I14 test with the ack forced a batch early still holds); any `Shown` after that is a new navigation. Timed test (I13): back then a re-tap within 50 ms: item 8 in its content's frame, no `CANCEL_LIMIT`, no `nav.cancelTimeout`. Supersedes round 3's H1 end condition |
| N3 what happens at `CANCEL_LIMIT` | Fixed: 3.2, I12, I13, section 6. Choice (the review's second option): at the limit the host releases only the back (the callback removed, a held back re-dispatched as a back on the list, later backs not held, `nav.cancelTimeout` counted); `Cancelled` stays, so what t pushed is hidden until the ack. Test (I12): a guest stalled 2 s, backs at 20 and 300 ms |
| N4 the callback's first moments have no owner | Fixed: 3.2, 3.5, I13. The owner is a container-level holder kept with `TreehouseAppContent`, entered when the container is first composed; the click handler registers the callback there in the same call that adds it; the holder's `onDispose` (and its reset on a code swap) removes whatever reveal callback is current. Replaces round 3's `DisposableEffect` keyed on the token. Test (I13): a code swap between the click and the next composition leaves no callback |
| Nit: I13 said a back while `Cancelled` is never the activity's | Fixed: I13 and 3.2. The held back is re-dispatched as a back on the list; it reaches the guest's callback, or the activity when the list has nothing to pop |
| Nit (M5): which native arm R1's first draw is compared with | Fixed: section 7. The native arm of the same detail (`-e form native -e bench nav`, and `-e x heavy`), same session and build, data in hand; `native,detailfetch` is B-1f's, later |
| R-M1 the clusters are not in the pre-registered rule | Fixed: section 7 and appendix D, the maintainer's decision. G is all of the guest's running time in d, on any CPU at any frequency; slow-cluster or down-clocked time may not be named as part of W. "A scheduling question, not guest work" removed. `d_running_slow` added as information only. q2 prints one row per CPU with its recorded maximum `cpufreq` (kHz) and `fast`/`slow`/`unknown`; step 6 says how to read `d_running_fast` = `d_running` from those rows |
| R-L1 nothing checks the guest was idle at the UP | Fixed: appendix D, `measure.sql`, q1, q2, qc, `analyse.sh`. Column `idle` = the UP − the end of the last `zl:*` slice that began before it; on `simple-on-idle` an open counts only if `idle` > 0 (q2's medians use only those; qc prints `idle` and the count); fewer than 10 of 12: the rule is not applied |
| R-L2, R-L3, R-L4 (the session's command list, a stale scratch copy, the install's fallback) | Not in this revision: they concern the session's scratch files, not this document; listed for the session |
| (B) verdict, R0 verdict | Agreed |

### Round 5: the final check of revision 5, answered in revision 6

Verdict: **fix first** (M1 to M3 are one rule or test each, no new design round; R0 not affected).
N3, N4 (apart from M1), the two nits, R-M1, R-L1, the idle arm's 10 of 12, the SQL and section 11's
note were checked and found sound. 9 rows here: **8 fixed**, the verdict agreed.

| Finding | Response |
|---|---|
| M1 the holder's `onDispose` removes the callback on activity recreation, and nothing re-adds it | Fixed: 3.2 (the callback's lifetime, M6), 3.5, I13. On entering a composition the holder adds the current reveal callback, if any, to that composition's back dispatcher. M6's test also checks that a back after the recreation is the host's in every state |
| M2 `e_ack` covers only the revealed entry, but `Cancelled` also waits on entries t's click pushed | Fixed: 3.2 (the guest's cancel, the `Cancelled` row), 3.5, I13, I14. The ack carries an `e_ack` for every entry the cancel left in the container (the revealed entry and each parked entry t pushed); `Cancelled` ends when each listed entry reaches its own. Test (I13): `otherKind` then back, with the other destination non-rebindable and rebindable, each also with the ack forced a batch early |
| M3 "its last effective content (the shell)" contradicts the content-first order | Fixed: 3.2, the 3.5 row, I14: "its current content, inert" (the shell until the bind's content is applied, then that content). Test (I14): the content-first order has its own frames: the shell, then the bound content inert, then live in the frame its state is applied; never the list |
| L1 two definitions of `e_ack` | Fixed: 3.2, I14. One definition: an entry's epoch as declared right after the cancel; the re-tap paragraph rewritten to use it (item 8 in the ack's batch: `e_ack` stays e+2, the condition is `≥`) |
| L2 nothing bounds `Cancelled` once `CANCEL_LIMIT` only releases the back | Fixed: 3.2, I12, section 6. Named: nothing but a crash or a code swap, counted by `nav.cancelTimeout`. I12's "never acks" test says what is drawn after the limit (the list, nothing t pushed, later navigation not drawn, back to the guest) |
| L3 decision 7 still described as open | Fixed: section 0's steps, section 7, section 9 (the intro, the (B) table's lead and the D7 row): decided by the owner on 2026-11-24, (b), the split; "revision 4 makes" reworded |
| L4 round 4's count | Fixed: 8 fixed, not 10 |
| L5 q2's CPU rows put kHz in `median_ms` | Fixed: appendix D says which column and that `n` is empty on those rows |
| Verdict (fix first; R0 may go ahead on the SQL and appendix D as they stand) | Agreed |

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

Written for the phone session of 2026-11-25; run on 2026-11-26 ("The A12 run", below). Required
before any (B) PR (section 9). It reruns series H's arms
([`THREE_WAY_BENCHMARK.md`](THREE_WAY_BENCHMARK.md), "Series H"), and one new arm, under a
Perfetto trace, and splits the floor step (click → composed for a detail already composed with the
right item: 57.5 ms simple, 111–114.5 heavy) into its parts. Nothing in the bench changes: every
option it uses is on main (`x=prepared`, `press`, `heavy`, `trace` and `--ei hold`;
`ThreewayActivity.kt:192, 413`, `Main.kt:64–69`). The files are in [`docs/nf5-r0/`](nf5-r0/). They
were run end to end on an emulator on 2026-11-24, which found and fixed six errors ("Dry run",
below).

**What is measured.** `x=trace` adds a trace section for every call into or out of the guest
(`zl:sendEvent`, `zl:sendFrame`, `zl:runJob`, `zl:composed`, `zl:sendChanges n=<changes>`) and for
every main-thread message (`msg <callback>`). Per open, the queries find the guest events after the
driver's UP message (with the press on there are two, back to back: the press's release and the
click, the click first; E is the last, E0 the first), the `zl:sendFrame` that composed the detail
(F) and the guest's `composed` report inside it (C), then the main thread's apply and the frame
that drew it, and report, in ms:

| Column | From → to | What it is |
|---|---|---|
| a | the driver's UP message → E0 starts | the hop to the Zipline thread |
| b | E0 starts → E ends (`n_ev` events) | the guest's `onClick` and the navigation (and, press on, the release) |
| c | E ends → F starts | the wait for the guest's next frame tick, split: **c1** E's end → the main thread's frame callback (the `animation` section of the last `doFrame` before F: the tick is a Choreographer callback, `AndroidChoreographerFrameClock.kt:31–36`), **c2** that callback → F (the hop back); `c_zl_run`: the Zipline thread running other work meanwhile, which is not a wait |
| idle | the end of the last `zl:*` slice that began before the UP → the UP | the guest idle when the finger lifted: > 0 for an open that counts in the rule (R-L1); negative when a guest call spans the UP |
| d | F starts → C | the recomposition, split by the Zipline thread's state: running (and of it, on the fast cluster, `d_running_fast`, and off it, `d_running_slow`, information only), runnable (preempted, waiting for a CPU), other (of it S, asleep, and D, uninterruptible); q3 names the kernel function each blocked in (`sched_blocked_reason`) |
| W | | the waits inside click → composed: (c − c_zl_run) + d_runnable + d_other |
| e | C → the batch sent | encoding and sending the batch |
| f, g | batch sent → the main thread's apply message; the apply | the hop back and applying the changes |
| h, i | apply → the next `doFrame`; that frame (`i_layout`: its measure and layout) | the frame that draws the detail |
| j | frame end → presented | the app's actual frame timeline row (Android 12 or later) |
| total | the UP message → presented | the open, from the finger lifting |
| check_lo, check_hi | C − E's end, C − E0's start | the guest's own click → composed (the results file: `composed − click`, which `analyse.sh` prints) must lie between them, ±1 ms, for at least 10 of opens 3–14, or the opens were misidentified |

**The rule, fixed in advance** (section 7, R0; revised by round 3's R0-1, R0-3 and R0-4), on the
**`simple-on-idle`** arm's medians over opens 3–14. That arm is the one whose click reaches an idle
guest, as (B)'s will: in series H's press-on arm the click waits behind the press's preparation, so
its `c` and `d` would measure NF3's press, not (B).
- **The reference is the finger lifting.** The bench's own times start at the DOWN, with a hold;
  native's 28 ms is in effect from the UP (its click, too, fires on the UP). So R0 projects from
  the driver's UP message: T = `total` (UP → presented).
- **Only opens whose guest was idle at the UP count** (round 4, R-L1). Per open, `idle` = the UP
  − the end of the last `zl:*` slice on the Zipline thread that began before it (ms); the open
  counts only if `idle` > 0, i.e. no guest call was still running when the finger lifted. q2
  computes the idle arm's medians over the opens 3–14 that count (its `n` says how many), and qc
  prints `idle` per open with the count. If fewer than 10 of the 12 count, the 400 ms hold did not
  leave the guest idle on this phone: the rule is not applied, and the arm is reported to the owner
  as it is.
- **G** = d_running, the guest composing: **all of the Zipline thread's running time in d, on
  whatever CPU and at whatever frequency** (round 4, R-M1, decided by the maintainer before the
  run). Time the guest spends running on the slow cluster, or on a down-clocked fast core, counts
  in G, because it is what the user's phone does; it may **not** be named as part of W, which holds
  only time the thread was not running (c − c_zl_run, runnable, other). The slow cluster's share is
  reported beside G as an informational column (`d_running_slow`) and changes no verdict.
  **W** = (c − c_zl_run) + d_runnable + d_other. Each part
  of W that the session can name a mechanism for, with a host or runtime change that would remove
  it, is written down beside its number (c1: the frame tick waits for the next vsync on the main
  thread; c2: the hop from the frame callback to the Zipline thread; d_runnable: the Zipline
  thread's priority or the CPUs it may use; d_other: what q3 says it blocked on). **W_named** is
  their sum.
- **The projection:** P = T − W_named + 14, where 14 ms is the rebind's extra guest work at a ratio
  of 12 (appendix B; P at a ratio of 30, + 36, is reported beside it). q2 prints `P_allW_r12` and
  `P_allW_r30`, the bound with all of W removed.
- **Reachable in principle:** P ≤ 100 ms and G ≤ 25 ms.
- **Not reachable by (B):** `P_allW_r12` > 100 (even with every wait removed), or G ≥ 40 ms.
- **In between:** reported to the owner (decision 7 is decided, (b); the fallbacks for data-in-hand kinds come to the owner then).

Also reported: the press-on arm beside the idle arm (the difference is what the press's preparation
costs the click); `d_running_fast` and `d_running_slow`, the parts of G on the fast cluster (on the
A12 the 2.3 GHz cluster) and off it, as information only: both are in G, and neither is subtracted
from P or G (R-M1); the press-off arm's W against the press-on arm's; the heavy arm's G, which
calibrates B1's slice budget.

Prediction (written before the run, to be checked): G ≈ 12 × 1.78 ≈ 21 ms on the idle arm; W
≈ 30–36 ms on the press-on arm and less on the idle arm.

### Steps

The session's exact command list, with this machine's notes, is kept beside the session's scratch
(`keliver-scratch/nf5/a12-floor-trace.md`); these are the steps it follows.

**0. The phone.** The Galaxy A12, over adb. Record its Android version first
(`adb shell getprop ro.build.version.release`): **the frame timeline (`j`, and so `total` and the
projection) needs Android 12 or later** (`android.surfaceflinger.frametimeline` and
`actual_frame_timeline_slice` came with Android 12; every record of this A12, e.g.
THREE_WAY_BENCHMARK.md's, says Android 12). The rest is older (`perfetto --txt -c -`, sched, atrace,
the app's sections: Android 10; `sched_blocked_reason` is an Android kernel tracepoint, and if the
phone's kernel lacks it q3's `blocked_function` is empty, not an error); the trace processor is the
benchmarks APK's and does not depend on the phone's version. On Android 11 or earlier, `j` and `total` are
empty: report `total` to the frame's end instead (a + … + i) and say so beside the rule. Record
uptime, battery and charger, temperature, thermal status and CPU idle before the run (the script
records them per arm, with the Android version). On battery, as series H, unless the session says
otherwise.

**1. The APK: the three-way sample's benchmark build on the BigInt engine, built as checkpoint 2
was** (THREE_WAY_BENCHMARK.md, "A12 checkpoint 2", Method), **from this PR's head**. The dry run
built it from `316abd41a` (this PR's code is unchanged since; only documents and `docs/nf5-r0/`
changed) in 7 minutes, and kept the two APKs; the session may install those if their sha256
matches the record ("Dry run"), or rebuild. From the repository root, JDK 17:

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

Check, and record: the build log names the guest links the init script switched to BigInt
(`:gateb-guest` and `:threeway-guest`'s production links); the APK's `lib/*/libquickjs.so` sizes
(`unzip -l`) equal the fork's AAR's (the dry run inspected both: 2,618,992 bytes on arm64-v8a); the
APK's size and sha256.

**2. Install** as series G and H did:

```
adb install -r -d sample/host-android/build/outputs/apk/benchmark/host-android-benchmark.apk
adb shell am broadcast -a androidx.profileinstaller.action.INSTALL_PROFILE \
  -n dev.keliver.sample/androidx.profileinstaller.ProfileInstallReceiver
adb shell cmd package compile -f -m speed-profile dev.keliver.sample
```

**3. The trace processor and the files.** The benchmarks APK carries
`assets/trace_processor_shell_aarch64` (androidx benchmark-macro ships it; Perfetto v45.0):

```
D=/data/local/tmp/nf5floor
adb shell mkdir -p $D
unzip -p sample/benchmarks/build/outputs/apk/benchmark/benchmarks-benchmark.apk \
  assets/trace_processor_shell_aarch64 > <scratch>/tp
adb push <scratch>/tp $D/trace_processor_shell && adb shell chmod 755 $D/trace_processor_shell
adb push docs/nf5-r0/. $D/        # the 12 files of docs/nf5-r0
```

**4. The screen and the arms.** The three-way app's guest form (`-e form guest`), with its own
fixture (`--ez localApi true`): its list, and the detail opened from a row, simple and heavy.
`bench=nav`, 14 opens and backs, the first 2 dropped, times from the DOWN.
[`run.sh`](nf5-r0/run.sh) runs, each in a fresh process under its own trace
([`cfg.txt`](nf5-r0/cfg.txt): the NF0 config, sched with `sched_blocked_reason` and kernel
symbols, frequency, atrace
`gfx view input am wm dalvik power`, the app's sections, the frame timeline):

| Arm | `-e x` | Hold | Trace | Series H's numbers to compare (untraced) |
|---|---|---|---|---|
| **`simple-on-idle`** (the rule's arm) | `prepared,press,trace` | 400 ms | 75 s | none: new. Its click comes at the UP, about 400 ms after the DOWN |
| `simple-on` | `prepared,press,trace` | 100 ms | 60 s | click 198.5–201; click → composed 57.5–58; click → drawn 80–85 |
| `simple-off` | `prepared,trace` | 100 ms | 60 s | click 113; click → composed 112–116.5 |
| `heavy-on` | `heavy,prepared,press,trace` | 100 ms | 75 s | click 488.5–494; click → composed 111–114.5 |

The idle arm holds the finger 400 ms, so that the press's preparation (about 200 ms on the A12)
has finished and the guest is idle when the finger lifts. The row has no long-press handler
(`Modifier.clickable` only, `ThreewayScreens.kt:249`), so a 400 ms hold, at or past the platform's
long-press timeout (400 ms from Android 12, 500 before), still clicks on the UP; the dry run shows
the click 402–407 ms after the DOWN.

```
adb shell '(nohup sh /data/local/tmp/nf5floor/run.sh < /dev/null > /data/local/tmp/nf5floor/log.txt 2>&1 &)'
# about 5 minutes; done when /data/local/tmp/nf5floor/done.txt exists. run.sh <arm> runs one arm.
```

If the phone's Perfetto rejects `symbolize_ksyms` (no trace, or a tiny one, and the error in
`log.txt`), delete that line from the phone's `cfg.txt` and run again: only q3's kernel function is
lost.

**5. Check the runs.** Each `<arm>.results.txt` has 14 opens, 14 backs and `{"done":true}`, no
timeout. For the three series H arms, medians of `click` and `composed − click` within 15% of
series H's (tracing adds a section per message); if not, say so beside every number. For the idle
arm, `click` is the hold plus a few ms.

**6. Query, on the phone** (`analyse.sh`; each query is the prelude plus that one query, in its
own `trace_processor_shell`: only a file's last statement prints). All four arms, detached (about 20
minutes on the A12), into `analyse-all.txt`, then `analyse-done.txt`:

```
adb shell '(nohup sh /data/local/tmp/nf5floor/analyse.sh all < /dev/null > /dev/null 2>&1 &)'
```

One arm `L` again, after a correction:

```
adb shell sh /data/local/tmp/nf5floor/analyse.sh $L groups     # q0a, q0b
# if q0b's UP is not upname.txt's name, put its exact name (with "msg ") into $D/$L.upname.txt; if q0a's
# open groups are not the default (below), put their numbers, comma-separated, in $D/$L.opengroups.txt
adb shell sh /data/local/tmp/nf5floor/analyse.sh $L measure    # q1, q2, q3, qc
```

- [`q0a.sql`](nf5-r0/q0a.sql): the tap groups (a new group after 550 ms without a guest call: the
  driver pauses 700 ms before each tap, and a hold is at most 400). After the start, 28 groups
  alternating open and back, `is_open` on every open. Press on: an open has 3 events (the press;
  then, at the UP, its release and the click), 2 frames and 2 `composed` reports; press off: 1 and
  1 (to be confirmed by the session; the dry run ran it, "Dry run"). A back has 1 event and 1
  report. If `is_open` is on the wrong groups (a stray guest call after the last back shifts every
  open onto a back, R0-6), list the open groups in `$L.opengroups.txt` and run `groups` again.
- [`q0b.sql`](nf5-r0/q0b.sql): the main thread's messages in the 200 ms before E, for opens 3–5.
  The driver's UP is the same `ThreewayActivity$$ExternalSyntheticLambda…` message in each, a few
  ms before E (the dry run's build: `msg dev.keliver.sample.host.threeway.ThreewayActivity$$ExternalSyntheticLambda5`,
  1–5 ms before E, 21–44 on the heavy arm; R8 numbers the lambda, so a rebuilt APK may change it).
- [`measure.sql`](nf5-r0/measure.sql) and [`q1.sql`](nf5-r0/q1.sql): one row per open, the columns
  above. `measure` uses `<arm>.upname.txt` if present, else [`upname.txt`](nf5-r0/upname.txt) (the
  dry run's name), and says which.
- [`qc.sql`](nf5-r0/qc.sql): the identity check. `analyse.sh` turns the results file's
  `composed − click` per open into a table; `pass` = at least 10 of opens 3–14 within
  `check_lo` − 1 … `check_hi` + 1. If 0, the opens or the UP are misidentified: nothing else of that
  arm is read until it passes. It also prints `idle` per open (the UP − the end of the last `zl:*`
  slice before it, ms; R-L1) and `idle_of_3_to_14`, how many opens count. On `simple-on-idle` an
  open counts only if `idle` > 0, and the rule needs 10 of 12 (above); on the press-on arms a
  negative `idle` is expected (the click waits behind the press's preparation) and is only
  reported.
- [`q2.sql`](nf5-r0/q2.sql): the medians over opens 3–14 (on `simple-on-idle`, over those with
  `idle` > 0), with `W_waits` and the projection's bounds, then one row per CPU (round 4, R-M1):
  `cpuN fast`, `cpuN slow` or `cpuN unknown`, with the highest frequency the trace's `cpufreq`
  track recorded for it, in kHz (empty for `unknown`: no `cpufreq` track for that CPU). **On these
  rows the kHz print in the `median_ms` column and `n` is empty** (one result set, so the columns
  are shared; round 5, L5): read `cpu4 fast | 2304000 |` as 2.3 GHz, not as milliseconds. `fast` marks
  `cpu_fast`'s CPUs, those whose recorded maximum is the trace's highest. **Reading
  `d_running_fast`, by those rows, not by the numbers alone:**
  - the rows show two or more different maxima (the A12: 2.3 and 1.8 GHz), so the clusters were
    separated: `d_running_fast` and `d_running_slow` are as printed, and `d_running_fast` =
    `d_running` means the guest did run only on the fast cluster;
  - every row shows the same maximum (the emulator), or every row is `unknown`: the clusters were
    not separated.
    Report `d_running` alone and write "not separated" for the split (`d_running_slow` prints 0 in
    the first case and is empty in the second).

  Either way G is `d_running`, whole (the rule above).
- [`q3.sql`](nf5-r0/q3.sql): what the Zipline thread blocked in during d, by state, the kernel
  function (D states, with kernel symbols), I/O or not, and the thread that woke it (for a plain
  sleep, the mechanism: a lock or a message from that thread). Empty when `d_other` is 0.

**7. Keep everything, then clean the phone.** Pull `/data/local/tmp/nf5floor` and the four traces
(`/data/misc/perfetto-traces/nf5-*.pftrace`, 20–45 MB each) to the session's scratch directory, so
any number can be queried again (NF0's traces were deleted, and its numbers cannot be split again).
Then delete `/data/local/tmp/nf5floor` and `/data/misc/perfetto-traces/nf5-*` from the phone.

**8. Record** the result as a new series in THREE_WAY_BENCHMARK.md (phone state, the four arms' q2
medians, the rule's verdict with each named mechanism) in its own PR, and the verdict in this
document's section 0.

### Dry run, 2026-11-24 (emulator)

**Where.** The AVD `keliver_a12_api35`: arm64, Android 15 (API 35), 4 cores, 60 Hz, on the Mac. Its
numbers are **not results**: an emulator on a fast host, with no cluster split and almost no
scheduling noise. The run shows only that the pipeline works, from the build to the table.

**Build** (step 1's commands, unchanged, from `316abd41a`'s code; 05:32–05:39, 7 minutes): the
sample, including revision 2's `Main.kt` line, compiled. `host-android-benchmark.apk` 26,057,387
bytes, sha256 `8bd7ecef…c7ee76243`; `benchmarks-benchmark.apk` 42,678,434 bytes, `c501a560…ed8415`;
`lib/arm64-v8a/libquickjs.so` 2,618,992 bytes in both the APK and the fork's AAR. The trace processor
from the benchmarks APK's assets, `ae2f18ff…68a0e7d`, is Perfetto v45.

**Run.** The session's command list (`keliver-scratch/nf5/a12-floor-trace.md`, steps 0 and 2–7,
with the emulator's serial) was run literally, in order: install and compile, push, the four arms
(`run.sh`, about 5 minutes), the check, `analyse.sh all`, pull, clean. Every arm: 14 opens, 14 backs,
`done`, no timeout; a trace of 18–31 MB. Every query ran on every arm. q0a: after the start group, 28
groups alternating open and back with `is_open` on each open, and the counts appendix D expects (press
on: 3 events, 2 frames, 2 `composed`; press off: 1, 1, 1; a back: 1, 1, 1), so the default open
groups were right and no override was needed. The UP was
`ThreewayActivity$$ExternalSyntheticLambda5` in all four arms, 1–5 ms before E (heavy-on: 21–44
ms, the click waiting behind the press's preparation). **qc: pass, 12 of
12 opens on every arm.** The emulator has a `cpufreq` track per CPU, all at one frequency, so
`d_running_fast` = `d_running`: the clusters were not separated, and `d_running` is reported alone
(step 6; revision 5's CPU rows in q2, which did not exist at the dry run, would show the four CPUs
at one maximum).

**The table it produced** (q2, medians over opens 3–14, ms; emulator, not results):

| Column | simple-on-idle | simple-on | simple-off | heavy-on |
|---|---|---|---|---|
| a UP → E0 | 1.0 | 2.6 | 1.7 | 32.1 |
| b the event(s) | 2.2 | 1.4 | 3.8 | 0.6 |
| c E → F | 7.9 | 10.1 | 6.2 | 12.9 |
| c1 E → frame callback | 7.6 | 9.8 | 5.3 | 11.3 |
| c2 callback → F | 0.2 | 0.3 | 0.4 | 1.2 |
| c_zl_run | 1.1 | 0.9 | 3.3 | 0.7 |
| d F → C | 6.3 | 4.4 | 28.3 | 5.6 |
| d_running (G) | 6.1 | 4.3 | 27.7 | 5.5 |
| d_runnable | 0.1 | 0.0 | 1.3 | 0.0 |
| d_other | 0.0 | 0.0 | 0.0 | 0.0 |
| **W** | 7.0 | 9.5 | 6.1 | 12.2 |
| e C → sent | 1.1 | 0.9 | 2.4 | 0.6 |
| f, g sent → apply; apply | 0.1, 0.1 | 0.1, 0.1 | 0.1, 0.2 | 0.1, 0.1 |
| h apply → doFrame | 8.6 | 10.0 | 5.3 | 9.2 |
| i the frame (layout) | 1.6 (0.2) | 1.3 (0.1) | 2.6 (1.1) | 1.1 (0.1) |
| j frame → presented | 2.7 | 2.4 | 2.0 | 1.4 |
| **total** UP → presented | 31.5 | 34.4 | 60.8 | 60.9 |
| P_allW_r12, P_allW_r30 | 38.2, 60.2 | 38.7, 60.7 | 61.5, 83.5 | 64.5, 86.5 |

What the run already shows about the method, not about the A12: `c1` is most of W on every arm (the
click's work ends, then the guest waits for the next vsync's frame callback); between E and F the
Zipline thread runs the click's own coroutine (`zl:runJob`, which requests the frame), counted in
`c_zl_run`; with the press on and a 100 ms hold, the heavy arm's click waits 32 ms in `a` behind the
press's preparation on the Zipline thread, as series H's 488 ms click did on the A12. That is the
reason the rule's arm is the idle one.

**What broke, and the fixes** (six; all in `docs/nf5-r0/` now):
1. **The tap groups.** A 300 ms gap split every idle-arm open in two (the press's preparation ends
   long before the UP of a 400 ms hold). Now 550 ms: the driver waits 700 ms before each tap, a hold
   is at most 400.
2. **LIKE in trace_processor v45.** On a perfetto table with `ORDER BY … LIMIT`, a LIKE returned a
   row that does not match (`Choreographer#onVsync` for `'Choreographer#doFrame%'`). Every name
   pattern is a GLOB now.
3. **Two events at the UP** with the press on: the click, then the press's release. E (the last
   before F) and E0 (the first after the UP) are both kept; `b` covers both, `n_ev` counts them.
4. **The check's start point.** Measured from E's start, 3 of 12 opens missed by 1.1–2 ms, the
   length of the event before E; `check_hi` runs from E0.
5. **The check compared two runs.** A first pass analysed one run's trace and then ran the arms again,
   so the results file and the trace came from different runs, and the check failed for reasons
   that looked like the SQL. The check is now a query, `qc.sql`, with a `pass` flag, built from the
   same arm's results file in the same `analyse.sh` call; `analyse.sh all` runs after the arms.
6. **q3 could not name a mechanism.** The trace had 11,448 `sched_blocked_reason` events but no
   `blocked_function`: naming the caller needs kernel symbols. `cfg.txt` adds `symbolize_ksyms`, and
   q3 adds the waking thread (`sched_waking`), the mechanism of a plain sleep (S), which
   `sched_blocked_reason` (D states only) never covers. trace_processor v45 keeps the waker on the
   runnable state after the sleep, not on the sleep, and q3 reads it there. The heavy arm, run again
   with both: 11,060 thread states named, the Zipline thread's sleeps attributed to their wakers
   (mostly the main thread), qc 12 of 12 again; the table above is the first run's. The emulator is a
   userdebug build. On the A12's user build Perfetto may not be able to read the kernel's symbols;
   then `blocked_function` stays empty, and q3 still gives the state, I/O and the waker.

Also changed for the session: [`upname.txt`](nf5-r0/upname.txt) holds the UP's name (each arm's
`measure` says which name it used; an arm's own file overrides it), and `analyse.sh all` runs every
query of every arm detached.

**Minimum Android version: 12** (API 31), for the frame timeline (step 0). The A12's records say
Android 12; the session checks it first.

**Cleaned afterwards:** `/data/local/tmp/nf5floor` and `/data/misc/perfetto-traces/nf5-*` on the
emulator, after pulling them to the scratch directory. The app stays installed.

### The A12 run, 2026-11-26

Run from the command list as written, except the power state, with the dry run's APKs (sha256
matched): plugged in and charging, where step 0 says on battery as series H. The verdict and its
three caveats are in section 0; the tables, the phone state and the checks are in
[`THREE_WAY_BENCHMARK.md`, "NF5 R0"](THREE_WAY_BENCHMARK.md#nf5-r0-the-floor-step-under-perfetto-a12-2026-11-26).

The run passed every check (q0a, q0b, qc 12 of 12 on every arm; the clusters separated). It also
showed two things about the frame columns that the emulator did not, for whoever reuses these
queries (R1, A6):
- **On the A12, the end of the app's actual frame-timeline row is the frame's buffer queued**
  (about its `queueBuffer`). It is neither the targets' frame end (the end of the main thread's
  work, earlier) nor the display's present (later). `j` and `total` measure to it, though the
  table above calls them "presented". In the one open checked (open 4, while the press's ripple
  was animating), SurfaceFlinger's display frame ended about 45 ms after the row; other opens were
  not checked. R0 records T to the buffer queued: frame end is the like-for-like reading for the
  targets and native's 28 ms, and the buffer queued is the most conservative frame-end reading
  (section 0, caveat 1).
- **`measure.sql`'s frame (the first `doFrame` after the apply) is not always the one that drew the
  detail.** When the press's ripple is animating on the RenderThread, the detail's draw moves one
  vsync later, or waits behind the animation's buffer, and the matched row is zero-length (`j` < 0).
  This happened in 9 of the idle arm's 12 opens. [`a12-2026-11-26/frame-end.sql`](nf5-r0/a12-2026-11-26/frame-end.sql)
  measures to the first `DrawFrames` of at least 2 ms instead. It fails on the press-off arm, where
  4 opens have no row. A later revision of `measure.sql` should take the drawing frame from the
  RenderThread.
