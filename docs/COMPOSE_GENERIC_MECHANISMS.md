# Spike: generic generator mechanisms

**Status: spike result, 2026-11-24. Decides nothing.** It tests a hypothesis about
[`MS4_PLAN.md`](MS4_PLAN.md) (revision 3): that three generic generator mechanisms replace most of
the per-type hand work in MS4b and MS4c, and that per-group configs and locks let the groups proceed
in parallel. Design references: [`COMPOSE_GENERATOR_DESIGN.md`](COMPOSE_GENERATOR_DESIGN.md) §5, §6,
§9, §10, §20–22.

**Short answer:**
- The mechanisms work. Each is proven on real Compose 1.12.2 metadata, sources and a real Compose
  host (JVM).
- They remove most per-type code: about 124 value types, 19 state classes and the drawing API go
  from hand-written to generated.
- They shrink MS4b plus MS4c by about **10 of 80 agent-days**, not most of it. Most of those 80 days
  go to features and tests that are not per-type plumbing: slots, NF5's frame rules, app components,
  the corpus samples, and M3 containers and inputs.
- The bigger gain is the **lanes**. Once the mechanisms and per-group outputs land, the serial spine
  MS4-16 → 17 → 18 becomes parallel group work, and a fourth agent becomes useful.

## 1. The three mechanisms, and what the spike built

Code:
- In `keliver-compose-generator`: `GenericIndex.kt`, `GenericSurvey.kt` and `GenericEmit.kt`, plus
  `SourceReader.defaultsOf`. The task `generateGenericSpike` writes the generated sources.
- `keliver-compose-generic-spike`: a JVM-only module that holds the generated sources and the
  hand-written runtime they share (`wire/Runtime.kt`, `host/HostRuntime.kt`, about 200 lines).

Not a word of the generated code names a type. The emitter reads metadata and sources, and the
configuration is one list of producers, one class name and one interface name (`GenericSpike`).

**One rule for defaults, everywhere.** A defaulted parameter is nullable in the guest, with `null`
as its default ("not passed"), and the wire leaves it out. The host evaluates Compose's own default
expression, copied from the sources and qualified, at the same call site Compose would. Some
defaults do not compile there, because they name a sibling parameter or a private member (DrawScope's
`size = this.size.offsetSize(topLeft)`). Those take design §5's presence split: the argument is left
out of the call text, with at most two such parameters per call. A default that reads the scope
(`center = this.center`) is evaluated on the host's real scope, so it is correct without a round trip.

### 1.1 Call records (values): **works**

The guest builds a value with a mirror that records the call: `TextStyle(...)`,
`Brush.linearGradient(...)`, `RoundedCornerShape(8.dp)`, `FontWeight.Bold`, `Stroke(width = 3f)`.
The value crosses as `{"call":<id>,"args":{…}}`, and the host's generated `GeneratedReplay` makes
the same call. Records nest: a `FontWeight(600)` inside a `TextStyle`, a `TileMode` inside a `Brush`.
The mirrors keep Compose's type hierarchy, so a `RoundedCornerShape` is a `Shape` in the guest, and
an abstract type such as `Brush` becomes an interface with a private carrier class.

`CallRecordsTest` (5 tests) checks each replayed value against the value Compose builds from the
same source text:
- `TextStyle` with five fields, and with a nested `FontWeight`;
- `Brush.linearGradient`, and its host-evaluated defaults (`Offset.Zero`, `Offset.Infinite`,
  `TileMode.Clamp`);
- `RoundedCornerShape`;
- `TextStyle()`, which crosses with no arguments.

What breaks:
- **Reading a property of a recorded value.** The guest holds the call, not the value, so the spike
  makes every property read an ERROR stub. Two kinds:
  - computed properties (97 on the covered types, such as `Brush.intrinsicSize`) stay stubs, or get
    the host snapshot of §7;
  - constructor-backed properties (365, such as `style.fontSize`) can be made readable by copying
    literal defaults into the guest, which is §5 case 1 and already exists for widgets.
- **Equality.** Two values are equal when their calls are. So `FontWeight.Bold != FontWeight(700)`,
  and `TextStyle() != TextStyle(color = Color.Unspecified)`, though both pairs are equal in Compose.
  Recomposition skipping is unaffected, because equal calls are equal. Guest code that compares
  values differs; a lint check can flag it.
- **A guest subclass** of an open type (a custom `Shape`, `VisualTransformation`,
  `PopupPositionProvider` or `Indication`) cannot be recorded, so it is an error stub. That rule
  exists today.
- **Arithmetic value classes stay hand codecs:** `Dp`, `Color`, `TextUnit`, `Offset`, `Size`, `DpSize`
  and `TransformOrigin`, plus theme references (§7).
- **Not built in the spike:**
  - member calls on recorded values, such as `typography.titleMedium.copy(…)` or
    `fadeIn() + slideIn()`. These are naturally *receiver* call records, the same mechanism;
  - vararg producers;
  - lock tags instead of string ids (today's `DefaultsCall` already uses tags).

### 1.2 Remote objects (host-owned state): **works** for PagerState; partly for the class of states

From `PagerState`'s metadata, the emitter writes the guest proxy and the host's `PagerStateRemote`
dispatcher:
- 10 observed fields (`currentPage`, `settledPage`, `isScrollInProgress`, …);
- `scrollToPage` and `animateScrollToPage` as awaited commands;
- `requestScrollToPage` as a command that is not awaited;
- error stubs for `layoutInfo`, `interactionSource`, `scrollIndicatorState`, `scroll {}`,
  `dispatchRawDelta` and `getOffsetDistanceInPages`.

A field is observed from its first read. The host reports only observed fields, from a
`snapshotFlow`, so on change and at most once a frame. A suspend command returns when the host's
real call returns.

`RemoteObjectTest` drives a real `HorizontalPager` through JSON text:
- `animateScrollToPage(3)` returns after the host's animation, and then `host.currentPage == 3`
  and `guest.currentPage == 3`;
- the acknowledged command leaves the wire;
- `requestScrollToPage(1)` round-trips;
- every report carried only `currentPage`.

Not built in the spike, but needed in the product:
- creating the state through its `remember*` factory with its `Saver`, and the process-death
  restore that MS4-15 tests;
- binding the state to its widget as a property, with an event back. The spike uses an in-memory
  channel;
- the "observed" set marking the property dirty. The spike re-sends the wire by hand;
- command results, such as `showSnackbar`'s `SnackbarResult`. These are a small extension.

### 1.3 Recorded interfaces (draw scopes): **works** for DrawScope's primitives

From DrawScope's metadata (with `Density` and `FontScaling`), the emitter writes a guest
`DrawScope` interface, a `RecordingDrawScope`, and the host's `DrawScope.replay(commands)`:
- 12 recorded members (the `drawLine`/`drawRect`/`drawRoundRect`/`drawCircle`/`drawOval`/`drawArc`
  overloads);
- `size` from the host, as of its last layout;
- error stubs for `drawContext`, `center`, `layoutDirection`, `density` and `fontScale`.

`RecordedScopeTest` records five calls against the guest mirrors: a gradient-filled rect, a
round-capped line, and a stroke circle. One rect has the presence-split `size`, and one circle has
the host-evaluated `center`. The test replays them on a real `Canvas` and compares the result with
Compose drawing the same source text: **0 differing pixels in 100×100**.

Finding: Skia dithers gradients and antialiases by device position. So two identical drawings side
by side differ in 27 pixels, and the comparison must draw both at one place. MS4-33's goldens should
know this.

`SpikeScreenTest` renders one screen that uses all three mechanisms on the JVM host (Skia):
- a call-recorded title style;
- a gradient and a shape;
- a pager the guest scrolled to page 2;
- a recorded canvas.

It writes `keliver-compose-generic-spike/build/generic-spike/screen.png`. That file is not checked
in, because `*.png` is LFS.

## 2. Coverage from the metadata (part a)

`GenericSurveyTest` writes `keliver-compose-generator/build/generic-survey.md`. The numbers below are
from Compose Multiplatform 1.12.2 (material3 at its own pin). The surface is 342 public, non-hidden
composables and `Modifier` extensions in 15 packages: foundation (layout, lazy, grid, staggered,
pager, text, text.input, selection, shape), material3, animation, ui.draw, ui.graphics and ui.text.
They have 2,078 parameters.

| Parameter category | Parameters | Distinct types | Mechanism |
|---|---|---|---|
| Kotlin primitives and containers of covered types | 351 | | as today |
| Arithmetic value classes | 272 | 6 (`Dp`, `Color`, `TextUnit`, `DpSize`, `DpOffset`, `TransformOrigin`) | **hand codecs stay** |
| Produced by a public constructor, factory, constant or enum entry | 590 | 124 | **call records** |
| Host-owned state | 149 | 26 (92 of them `MutableInteractionSource`) | **remote objects** |
| Function types (slots, callbacks, scope blocks) | 484 | | §9 slots and modifier events, not values |
| No public producer | 28 | 11 | stay out (see below) |
| `Modifier` chains | 204 | | as today |

- **Call records.** The covered types have 574 producers. 524 of them are replayable with every
  argument covered, 39 take a lambda (`InlineTextContent`, builder DSLs), and 11 take another
  uncovered type.
  - "Has a producer" is not "works": some covered types are behaviours a guest would implement,
    such as `VisualTransformation` (×8), `Indication` (×6), `PopupPositionProvider`,
    `NestedScrollConnection` and `FocusRequester`. Factory-made values of these replay, and guest
    implementations stay error stubs.
  - Outside the mechanism:
    - `InputTransformation`, `OutputTransformation` and `KeyboardActionHandler`: guest code per
      keystroke;
    - `ColorProducer`: per frame;
    - `BringIntoViewSpec`, `SnapPosition`, `LookaheadScope`, `SnackbarData` and
      `TextContextMenu.TextManager`;
    - a type parameter `T`, and Coil's `Any`.
- **Remote objects.** The 19 state classes MS4-15 and MS4-39 name have 92 observed fields, 24
  awaited commands and 8 commands that are not awaited.
  - **Outside: 44 members.** 10 return a value synchronously (`dispatchRawDelta`, `requireOffset`,
    `getOffsetDistanceInPages`, `tryEmit`). 7 take a lambda (`scroll {}`, `TextFieldState.edit {}`,
    `SliderState.drag {}`). 27 have a type that does not cross (`layoutInfo` trees,
    `interactionSource`, `ScrollIndicatorState`, `Locale`, `SnackbarVisuals`, `MutatePriority`).
  - Of the states:
    - `LazyListState` stays hand-written: its NF1 lazy window is not a field;
    - `TextFieldState` stays largely hand-written: editing is a lambda, and it keeps today's
      `userEditCount` round trip;
    - `TopAppBarState`'s scroll behaviours connect host nested scrolling, which is not a member;
    - `MutableInteractionSource`'s use (`collectIsPressedAsState`) is a `Flow`, not a field.
- **Recorded interfaces.**

  | Interface | Recorded | Other members |
  |---|---|---|
  | `DrawScope` (52 members and extensions) | 16 | 13 nested blocks (`translate`, `rotate`, `scale`, `inset`, `clipRect`, `clipPath`, `withTransform`, `drawIntoCanvas`, `GraphicsLayer.record`); 13 density conversions the guest computes from a density snapshot; 6 host values (`size`, `center`, `layoutDirection`, `density`, `fontScale`, `drawContext`); 3 `drawImage` that need a host image registry; 1 uncovered |
  | `GraphicsLayerScope` | 20 assignable properties, all covered | `size` is a host value |
  | `Path` | 26 of 47 (`moveTo`, `cubicTo`, `addRoundRect`, …) | 15 guest-computed queries (`getBounds`, `op`, `iterator`) |

  The nested blocks are the same mechanism with a nested command list, except two:
  `drawIntoCanvas` (a raw `Canvas`) and `GraphicsLayer.record`. `drawText` needs a `TextMeasurer`
  (host-measured), so it stays a hand piece. `Path` is itself a recorded interface, so `drawPath`
  ships the path's commands. The same technique fits builder classes: `AnnotatedString.Builder` and
  `ImageVector.Builder`.

## 3. Groups in parallel (part c)

**Already per group:**
- the config: `loadConfig`, one `--config` per run;
- the lock: `Lock.group`, its own widget, modifier and factory tag counters;
- the level;
- the schema, with its `dependencies` tag ranges.

MS4-01 already plans one config per group.

**Shared today, and what would have to change:**
1. **`keliver-compose-values` output** (`--out-values`, `GeneratedDefaults.kt` per `*Defaults`
   package). `Main.kt` deletes every `GeneratedDefaults.kt` under the root that *this* run did not
   write. So a second group's generation would delete the first group's files. **Change:** namespace
   by group (`GeneratedDefaults_<group>.kt`), and limit the stale check to the group's own files.
2. **The editor interpreter** (`--out-editor-render`). Every run writes the same file in one
   package. **Change:** one file per group, plus a generated dispatcher.
3. **The call-record table.** Today `*Defaults` factories are tagged in each group's lock, and
   `DefaultsCall` carries no group. Values (`TextStyle`, `Shape`), state classes and draw commands
   are used by every group. **Change:** one shared **`values` lock** (producers, remote members,
   scope members), keyed by Compose declaration. Every widget group depends on it. It stays
   append-only, and §2.2's rule (merge `origin/main`, regenerate, never hand-merge) resolves
   parallel appends. That lock is the one remaining shared serial point. Its appends are small and
   mechanical.
4. **The root generated schema** lists the groups. Each new group touches one line.
5. **Gradle:** `generateComposeApi` and `checkComposeApi` are single tasks, and need one per group.
6. **The generator's code** (Emit.kt, 1,054 lines). After the mechanisms land, a coverage PR should
   be config, classification (MS4-36) and samples only. The spike keeps each mechanism in its own
   emitter class, so the mechanisms can be built in parallel. Only their integration with the
   widget emitter touches Emit.kt.

Cost of 1, 2 and 5: about 2 agent-days (**G0** below). Item 3 is part of G1.

## 4. Re-estimate of MS4b and MS4c

Days as in MS4_PLAN.md: one agent, review included, CI excluded. **New PRs:**
- **G0**, per-group outputs and tasks: 2 days;
- **G1**, call records in the product: lock section, receiver calls, readable constructor-backed
  properties, js/wasm/iOS round trips, editor kinds: 5 days;
- **G2**, remote objects in the product: widget binding, `remember*` factories with savers, restore,
  dirty observation, results: 4 days;
- **G3**, recorded interfaces in the product: `Canvas`, `drawBehind`, `drawWithContent`,
  `graphicsLayer {}` and nested blocks, `Path`, size round trip: 5 days.

| PR | Plan | New | Why |
|---|---|---|---|
| MS4-07 include lists, generic error stubs | 4 | 3 | `GenericIndex` already enumerates every public declaration with HIDDEN detection; the scan test builds on it |
| MS4-08 default case 3, nullable modifiers | 3 | 2 | the presence split exists (`hostCall`); widgets reuse it |
| MS4-09 slots | 6 | 6 | unchanged: slots are not values |
| MS4-10 editor metadata | 2 | 2 | unchanged |
| MS4-11 app components | 6 | 6 | unchanged |
| G0, G1, G2 (new) | – | 11 | above |
| **MS4b** | **21** | **30** | the mechanisms are built here, early |
| MS4-12 foundation without state | 3 | 2 | no codec work: config and samples |
| MS4-13 codecs left | 4 | 1.5 | **replaced** by G1. Left: host-registered `FontFamily` names, `Painter` resource references |
| MS4-14 theme snapshot | 3 | 3 | unchanged |
| MS4-15 foundation states | 6 | 2.5 | **shrunk** by G2: Scroll, Grid, Staggered and Pager are config plus tests. Left: the `TextFieldState` link and the restore tests |
| MS4-38 grids, pagers | 3 | 3 | unchanged (NF1's window for grids is hand-written) |
| MS4-39 M3 states | 5 | 2 | **shrunk** by G2: eight states as config, host fakes and restore tests |
| MS4-16 M3 part 1 | 4 | 3 | colours, elevations and borders need no codec |
| MS4-17 M3 part 2 | 5 | 4 | `TextField` on `TextFieldState` stays |
| MS4-18 M3 part 3 | 6 | 4.5 | states generated; scroll behaviours, insets and dialogs stay |
| MS4-19 icons | 2 | 1.5 | icons are property call records |
| MS4-20 animation | 5 | 4 | spec factories and `+` become call records; the semantics stay |
| MS4-21 guest-clock opt-in | 1 | 1 | unchanged |
| MS4-22 Canvas, layers | 7 | 5 (G3) | **replaced** by G3; `drawText`'s measurer stays a hand piece |
| MS4-36 NF5 frame rules | 5 | 5 | unchanged: classification, not plumbing |
| **MS4c** | **59** | **42** | |
| **MS4b + MS4c** | **80** | **72** | −8 days (about −10%) |

**Why so little:** the per-type plumbing these mechanisms remove is about 25 of the 80 days (MS4-13,
-15, -22, -39 and parts of -12 and -16…-20). Building the mechanisms well costs about 16 of them back.
The rest of MS4b and MS4c does not change: slots, app components, editor metadata, NF5's frame rules,
and the corpus samples every coverage PR writes. **The hypothesis holds for "per-type hand work"**:
124 value types go to 6 hand codecs, 19 states to about 3 hand ones, and one generated drawing API.
**It fails for "most of the 80 days".**

**Lanes (the larger effect):**
- **Today.** L1's spine is MS4-07 → 09 → 16 → 17 → 18, and each step builds on the previous one's
  generator features. That spine is why a fourth agent would not help.
- **With G0 to G3.** G1, G2 and G3 are independent emitters, so they can run on two lanes at once
  (G1 on L1; G2 and G3 on L2) from about day 7.
- **After MS4-09 and G1 to G3 land** (about day 20), MS4-12, -15, -16, -17, -18, -19, -38 and -39
  are config, samples and tests per group. They split by group: foundation, material3, animation and
  icons. Each group has its own config, lock and outputs, and the one shared `values` lock follows
  §2.2's merge rule.
  - Dependencies still between them: MS4-17 needs `TextFieldState` (MS4-15's part), MS4-18 needs
    MS4-39, and MS4-38 needs NF1's window (MS4-02).
  - They no longer depend on each other's generator features.
- **The binding constraint stays total load** (about 199 agent-days with NF5's 57 and the rest of
  MS4 unchanged):
  - with three agents, about 66 days instead of 69;
  - the gain is that **a fourth agent now has parallel work**. With four, about 50 days, if the
    shared machine's CI and review keep up.

**Confidence:**
- **High** that each mechanism works on its representative: the JVM tests run against real Compose.
- **Medium** on coverage: the counts come from metadata, and a "covered" type can still fail in
  practice, as with behaviour interfaces and constructor defaults that do not compile.
- **Low to medium** on the day counts: they are one spike's judgement, and G1 to G3 are not built
  in the product.

## 5. Unverified

- **The guest in Kotlin/JS (QuickJS):** the spike module is JVM-only. The guest half uses only
  common Kotlin, `kotlinx.serialization` and Compose's runtime, but it was not compiled for JS,
  wasm or iOS.
- **Device rendering:** there is no emulator screenshot (time box). The screen above is the JVM host.
- **The real protocol path:**
  - remote objects use an in-memory channel, not a widget property and an event;
  - call records use string ids, not lock tags.
- **Wire size and encode cost** of call records against today's hand codecs. With tags they are
  comparable (`DefaultsCall` is the same shape), but this was not measured.
- **Editor ingest** of call records. A call record is literally the source text's call, so
  `portal-ingest`'s mapping should be direct; it is not built.
