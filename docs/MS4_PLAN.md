# MS4 plan: the 0.5.0 build

**Status: draft, revision 2, 2026-11-24.** The delivery plan for MS4 of
[`PLAN_2026-11.md`](PLAN_2026-11.md) (section 8's MS4 row, section 9's Track B). It orders the work. It
decides nothing that [`DECISIONS.md`](DECISIONS.md) or the plan of record decide. Section 6 lists what
the owner still has to decide, and what the maintainer has decided. Records stay in
[`DELIVERY_PLAN.md`](DELIVERY_PLAN.md). Revision 2 answers the first review (H1–H3, M1–M7, Lows 1–6):
NF5 is in 0.5.0, the lanes are re-planned with NF5 starting now, and the order where NF5 and MS4 meet is
fixed (section 2.2).

**Binding inputs.**
- **PD4 (the owner, 2026-11-24):** **one** 0.5.0 libraries release carries:
  - Kotlin 2.4.21 and the Zipline fork;
  - the Compose-generated API (WS2 in full; Gate B confirmed 2026-11-24; D6 and D9 amended);
  - the bridge modules (WS3);
  - item 2's M4 composition/encoding split;
  - NF1's and NF4's library changes;
  - the library pieces MS6 needs.
- **NF5 is in 0.5.0, both halves** (the owner, 2026-11-24: one 0.5.0 carries everything, and yes to
  NF5's section 11, with decision 7 answered (b), the split). So the candidate waits for NF5's (A) and
  (B), and its checks include NF5's gates A-1…A-8 and B-1…B-10 (section 3, C14 and C15). This is a
  recorded decision, not an open question.
- **The toolchain on `main`:** the 0.5 line's host toolchain is merged (#299, `ae6147350`: AGP 9.3.3,
  Gradle 9.7.1).
- **Decisions:** D2 is extended, not replaced (WS2's gate). D1, D3, D4 and D14 are unchanged. NF5's D1
  clarification is NF5's (its section 10).

**IDs in this note:**
- milestones **MS4a–MS4f**;
- pull requests **MS4-01…MS4-40**;
- candidate checks **C1–C16**;
- owner questions **Q1–Q15**: Q4, Q6 and Q12 are now maintainer decisions, and Q13 is a "confirm";
- lanes **L1**, **L2** and **A**. Revision 1 called L1 and L2 "B1" and "B2". They are renamed because
  NF5 has its own steps B0–B5.

None of these is item 2's M0–M5 or item 4's B1–B5. NF5's steps keep NF5's names: H0, A1–A6, R0, R1 and
B0–B5. This note always writes them as "NF5's B1", and so on.

## 0. What 0.5.0 carries, and what is left

| Item | State on `main` (`dc22ce504`) | Left for 0.5.0 |
|---|---|---|
| Kotlin 2.4.21 | Merged (#303) | Nothing; re-proven by the candidate's checks (section 3) |
| Zipline fork `dev.keliver.zipline` 1.28.0-keliver.1, one engine `quickjs-2026` | Published 2026-11-24. Keliver is on it (#294, merged). The 0.5 line's host toolchain is merged too (#299 at `ae6147350`: AGP 9.3.3, Gradle 9.7.1; the hosts take Coil and Ktor from `keliver-image`) | Nothing; re-proven by the candidate |
| Compose-generated API (WS2) | A prototype behind `-PkeliverComposePrototype`, unpublished: one group `gatea`, with 29 widget, 36 modifier and 15 factory tags at lock level 5 (`keliver-compose-generator/gatea.lock.json`) | Most of MS4: section 1 |
| ED0 (the editor on the generated catalog) | A spike behind a flag (#239, [`ED0_SPIKE.md`](ED0_SPIKE.md)); the canvas is not wired into `portal-editor` | MS4e |
| Bridge modules (WS3) | `keliver-bridge`, `-annotations` and `-codegen` merged, never published; B5b on the JVM (#211) | B5c's library half (`keliver-bridge-annotations` gains wasmJs), B5b on the iOS simulator, `keliver.bridges` from generated constants (MS4d) |
| Item 2's M4 split | **Library half merged:** `KeliverTrace.composed()` (`keliver-treehouse-guest/src/commonMain/kotlin/dev/keliver/treehouse/KeliverTrace.kt:38`, called at `StandardAppLifecycle.kt:133`; #237) | The host templates' reading of it ships in the tools release after 0.5.0. One emulator run in the candidate (C16) |
| NF1's library changes | Merged as experimental in `keliver-nav`, `keliver-compose` and `keliver-treehouse*` (#259, #262, #265, #266, #271, #278). The host-side parts (lazy window prediction, placeholders, retained host back, one state per widget) live **only in the prototype** `keliver-compose-gatea-composeui` (#258, #265, #276, #280) | Copied into the real generated host module (MS4-02), which is also NF5's B0 prerequisite. The press signal stays off by default (owner, 2026-11-22) |
| NF4's library changes | `keliver-treehouse-host`'s baseline profile (#286); the opt-in plugin for generated host code (#290) | The generated groups' host code covered by #290's plugin (MS4-14) |
| MS6's library pieces | `@ScreenState`/`NamedState` in `keliver-nav` (#226), `keliver-http-fixtures` (#224), `keliver-http-replay-debug` (#232) | `keliver-nav` on wasmJs (ED2; its targets are js and jvm only, `TreehouseGuest` in `RedwoodBuildPlugin.kt:484`); a generated `@ScreenState` registry (#233 left it unbuilt) |
| **NF5 (A), the cached first frame: library changes** | Design approved (`docs/SHOW_BEFORE_COMPUTE.md` on #308, revision 5, not yet on `main`; owner's decisions 2026-11-24). No production code | The library side, by NF5's PRs: <br>- `session()` and `CachedFrames(accounts = NONE)`, with `discardPrefetched()` deprecated (NF5's decision 3; A5); <br>- `FrameCacheTags` in `keliver-protocol`, with a lock test (A2); <br>- the frame-rule annotations in `keliver-schema` and `FrameRules` from `keliver-tooling-codegen` (A1); <br>- the fold, store and recorder in `keliver-treehouse-host` (A3a–c); <br>- the hooks in the legacy renderer modules (A4a); <br>- `CachedFrameContent` and `FrameHandOff` in `keliver-treehouse-host-composeui` (A4b). <br>The generated groups get the same rules and hooks in MS4-36 |
| **NF5 (B), pre-composed destinations: library changes** | Approved after fixes, with decision 7 = (b). Waits for R0 (A12, 2026-11-25) and R1 | The library side, by NF5's PRs: <br>- `keliver-nav`'s behaviour changes (NF5's decision 5: held effects for every prepared entry, parked pops for declared kinds, per-visit show counts, loads at the show) and `KeliverNavKind(dataArrives)` (B2, B3); <br>- the container's `keliver.nav.retainedContainer@2` (B0, on MS4-02's production container); <br>- `keliver.nav.reveal@1` (B4) |

NF5's PRs are Track A's and are not counted in MS4's agent-days. Section 2 counts them as Track A's load
(this plan's estimates; NF5's design gives no sizes). MS4-36 (NF5's rules for the generated groups) and
the shared-area rules of section 2.2 are MS4's.

## 1. WS2: what exists against what it needs

Evidence is `path:line` on `main` at 7014fb08e. It was re-checked at `dc22ce504`: #299, #309 and #311
changed none of the cited files except `ci.yml`, whose lines are updated below.

"Prototype" means the modules are always built (`settings.gradle:66-74`) but publish only with
`-PkeliverComposePrototype` (`keliver-compose-values/build.gradle:13`), which the Central workflow never
passes. None of them has an `api/` dump.

Paths below are shortened:
- `keliver-compose-` to `kc-`;
- the generator's `src/main/kotlin/dev/keliver/compose/generator/` to `gen/`;
- `keliver-compose-values/src/commonMain/kotlin/dev/keliver/compose/` to `values/`;
- the guest stubs' `src/commonMain/kotlin/dev/keliver/compose/` to `stubs/`.

| WS2 needs | Exists today | Evidence | Gap for 0.5.0 (PR) |
|---|---|---|---|
| Generator inputs: Compose's published API and sources | **Yes**, on the pinned catalog: Kotlin metadata plus ASM over the desktop jars, PSI over the `-sources.jar`s; CMP 1.12.2, material3 1.9.0, coil 3.6.3 | `gen/ApiReader.kt:42`, `gen/SourceReader.kt:40`, `gen/Main.kt:69` (`generate`); pins `gradle/libs.versions.toml:14,98,27` | **What** is generated is a hand list: 70 declarations in `kc-generator/gatea.json` (`"declarations"`, from line 10), plus 3 curated errors (line 95). Include by package (MS4-07) |
| Outputs: guest stubs, wire schema, host renderers, editor metadata | **Yes**, all four, checked in and staleness-guarded (`checkComposeApi`) | `gen/Emit.kt:98` (schema), `:172` (guest), `:429` (host), `:719` (`*Defaults` mirrors), `:874` (editor JSON), `:933` (canvas interpreter) | One group only (`gatea.json:2`). The product groups are generated beside it (MS4-01) |
| Lock file of tags, append-only | **Yes**: canonical-signature keys, per-parameter `wire` levels, `since`, refusal of any non-append | `kc-generator/gatea.lock.json` (level 5; 29 widgets, 36 modifiers, 15 factories); `gen/Lock.kt:29` (`Lock`), `:42` (`LockEntry`), `:177` (`checkAppendOnly`) | Fresh product locks, frozen at `v0.5.0` (MS4-06, Q2) |
| Value codecs | **Most**: `Dp`, `TextUnit`, `Color`, `Shape`s, `TextStyle`, font types, alignments, arrangements, `PaddingValues`, `BorderStroke`, `ColorFilter`, `Painter`, `ContentScale`, `Role`, animation specs, `*Defaults` call records | e.g. `values/ui/graphics/Color.kt:115` (references), `values/material3/GeneratedDefaults.kt`; host converters in `kc-gatea-composeui/…/Converters.kt` | **`Brush` is a placeholder** (`kc-gatea-compose/stubs/ui/graphics/Generated.kt:9`; `background(brush)` is an ERROR stub, `…/foundation/Generated.kt:30`). Also missing: `AnnotatedString`, path-built `ImageVector`, host-registered fonts (MS4-13) |
| Theme references | **Yes**: `colorScheme`, `typography`, `shapes`, `LocalContentColor`, `LocalTextStyle` resolve on the host | `values/material3/MaterialTheme.kt` (guest), `values/ui/graphics/Color.kt:154` (`ColorRefs`), `Converters.kt:119` (`resolveColor`) | The theme snapshot for guest reads (design §7) is not built (MS4-14) |
| Host-owned state objects | **`LazyListState` only** (commands, viewport fields, `Saver`); `ScrollState` carries its initial offset only | `gen/Classify.kt:72` (`hostState`); `values/foundation/lazy/LazyListState.kt:59`; `values/foundation/ScrollState.kt:32` ("commands … are Gate B") | Foundation's (MS4-15): `ScrollState` commands, `LazyGridState`, `LazyStaggeredGridState`, `PagerState`, `TextFieldState`, interaction sources (now ERROR stubs, `…/material3/Generated.kt:192`). Material 3's (MS4-39): `SheetState`, `DrawerState`, `SnackbarHostState`, `DatePickerState`, `TimePickerState`, `TopAppBarState`, `TooltipState`, `SwipeToDismissBoxState` |
| Per-frame APIs | **Partly**: `AnimatedVisibility` (3 overloads), `animateContentSize`; `animate*AsState` as tagged host references; guest `Animatable` | `kc-gatea-compose/stubs/animation/Generated.kt`; `values/animation/core/AnimatedReferences.kt:267`; `values/animation/core/Animatable.kt:110` | `AnimatedContent`, `Crossfade`, transitions, `keyframes` (MS4-20). **`Canvas` recording: none** (`values/ui/graphics/drawscope/DrawScope.kt:22`, constants only) (MS4-22) |
| Compile-error stubs | **The mechanism, yes**; the list is curated: `Layout`, `pointerInput`, `Scaffold`, and every dropped parameter becomes an ERROR overload | `…/stubs/ui/layout/Generated.kt:10`, `…/stubs/ui/input/pointer/Generated.kt:8`, `…/material3/Generated.kt:507`; type messages in `gatea.json` (`"typeMessages"`) | A generic stub for every non-generated public name; Android locals, resources, `AndroidView` (MS4-07) |
| Slots: optional, several, with parameters | **Several named slots with defaults: yes** (`TopAppBar`'s `navigationIcon` and `actions`: `kc-gatea-schema/src/main/kotlin/dev/keliver/compose/gatea/Generated.kt:430`). **Nullable optional slots (design §9's "present" flag): no. Slots with parameters: no** (`Scaffold` is an ERROR stub; `TopAppBar`'s `scrollBehavior` and `windowInsets` are dropped, `…/material3/Generated.kt:494`) | as left | MS4-09 |
| App components, opt-in | **None** (design §14 only) | `docs/COMPOSE_GENERATOR_DESIGN.md` §14, "app components (100+)" | MS4-11 (Q8, Q15) |
| Compatibility gate (extends D2) | **Not for the generated API.** Lock levels exist and are tested against an old host. B4's `bridges` and NF2's `engine` gates are the pattern | `kc-gatea-composeui/src/jvmTest/…/HostCompatibilityTest.kt`; relay `portal-relay/src/main/kotlin/StaticIndex.kt:98` (`signedBridges`); hosts `scripts/templates/production-host/src/main/kotlin/BundleIndex.kt:84` (`KNOWN_CONSTRAINTS`), `:387` (`admits`) | `keliver.compose` (group levels and the app components' level) in the signed manifest, `constraints.compose`, host skip and refusal (MS4-23, Q5) |
| Cached first frame (NF5 (A)) for generated screens | **None.** NF5's A1 and A4 cover the three legacy schemas only, and nothing is replayable by default (NF5 2.4), so until MS4-36 a generated screen never gets a frame | NF5 section 9, A1, A4a and A4b | Frame rules and host hooks for the generated groups (MS4-36) |
| Legacy schema and migration tool | Legacy `keliver-material` **stays**, as a required dependency of the prototype root. **No migration tool** | `kc-gatea-composeui/…/WidgetSystem.kt:27-34` (needs material, layout, lazy layout) | Legacy optional (MS4-03); migration tool (MS4-31, Q13) |
| Widget groups | **None**: one schema, dependency tags 1–3 | `gatea.json:5-9` | Q1, Q14, MS4-01, MS4-03 |
| Same-source corpus | **Started**: 4 guest/native pairs (orders, card, item detail, animated panel), plus a guest-only long list for the lazy tests; JVM and iOS simulator, 0 differing pixels | `kc-gatea-composeui/src/sameSourceTest/…/SameSourceTest.kt`, `…/samples/{guest,native}/` | The harness at scale plus coverage (MS4-05), and samples with every coverage PR |
| Editor (ED0) | **A spike behind a flag**: ingest with 0 RawCode, byte-stable write-back, a canvas library (not in `portal-editor`) | [`ED0_SPIKE.md`](ED0_SPIKE.md) §1, §4 | MS4-29, MS4-30 |
| Tests today | 149 `@Test` annotations (counted in source) across the prototype modules: generator 24, values 28, guest 16, host 72 (including same-source 8 and lazy scroll 12, on JVM and iOS), profile 9 | CI runs them at `.github/workflows/ci.yml:153-160` and `:304` | Copied to the product modules by MS4-01 and MS4-02. The prototype's own tests are retired by MS4-40 |

## 2. The pull requests

Sizes are working days for one agent, review included, CI excluded.

**Three lanes, three agents:**
- **L1**: the generator and codegen. Track B's agent.
- **L2**: runtime, values, host, editor, harness and bridges. A **dedicated second MS4 agent**.
- **A**: Track A's agent. It runs NF5 from day 0 and takes three MS4 PRs after NF5's B5.

Revision 1 assumed that Track A would be free during NF5's design review. That review is done, and NF5's
H0 and (A) start now, so Track A has no slack for MS4 until NF5 ends (section 2.3).

### 2.1 Milestones and their PRs

**MS4a. Productise (no new API; the wire may still change).** Exit: the product modules carry the
prototype's tests under the real names, with no prototype flag. The prototype itself is retired later,
by MS4-40, after NF5's B1 closes (section 2.2).

| PR | Scope | Days | Gate / test | Needs | Lane |
|---|---|---|---|---|---|
| MS4-35 | **Reference-app harness against a local Maven repository.** On the PR route, this checkout's libraries are published to a disposable local Maven repository (never `~/.m2`), and the reference app's hosts and guest build against it. This is a third, labelled route beside published-only and repo-scaffold. Today the PR route takes the libraries from Maven Central, so no library change can be checked there | 3 | `reference-app.yml` (emulator) and `ios-host.yml` (simulator) green on the new route, on a PR that changes a library: a marker in `keliver-treehouse-host` shows in the device log. The route is named in the results | none | L2 |
| MS4-01 | **The product groups generated beside the prototype** (Q1, Q14): the group configs, the generated root schema and the product modules under their real names. The generator and `keliver-compose-values` keep their names (Q15). **Every `keliver-compose-gatea-*` module and `sample/` stay where they are**, built and regenerated but otherwise frozen, because NF5 measures with them (section 2.2) | 3 | `checkComposeApi` for both the prototype and the product groups. The product modules' copies of `SameSourceTest` and `HostCompatibilityTest` pass with the prototype's counts. `redwoodApiCheck` | Q1, Q14, #308 merged | L1 |
| MS4-02 | **Copy** the NF1 host pieces (`LazyRuntime`, placeholders, retained host back, one state per widget, press timing) verbatim into the product host module. The prototype keeps its copy for NF5's measurements until MS4-40. Fixes go to the product copy. **This is the production container NF5's B0 needs** | 2 | `LazyScrollTest`, `RetainedHostBackTest`, `OneStatePerWidgetTest` and `PressHostTest` pass unchanged in the product module, on JVM and the iOS simulator | MS4-01 | L2 |
| MS4-03 | Absent groups, and legacy optional: `keliver-tooling-codegen`'s host factory accepts a missing dependency group, and its widgets report as unknown (#244 decision 3). The generated root no longer requires the legacy `keliver-material` | 3 | A host built without legacy and Coil renders a generated-only tree, and unknown tags are reported once. The release APK and the iOS app without the legacy renderers are measured (Q11's baseline) | MS4-01 | L1 |
| MS4-04 | `schema-mismatch` reports instead of silent drops (design §12, "inside the gate"): a library `EventListener` hook, plus template wiring. The wiring builds on NF5's H0, which is the one place a template declares what it implements. It adds no second list | 2 | An unknown widget, modifier or property is reported once per tag, and the relay's `/report` accepts the reason. On MS4-35's route, an emulator run of a test bundle with one unknown tag gives one report | MS4-03, NF5's H0, MS4-35 | L2 |
| MS4-05 | The same-source corpus harness at scale: one sample file per widget family, and a coverage report (every lock entry is used by at least one sample), shared by jvmTest and iosTest | 3 | The report lists 0 uncovered entries for the current product lock; 0 differing pixels | MS4-01 | L2 |
| MS4-06 | Fresh product locks at level 1 (Q2). The prototype's lock and its fixture tests stay until MS4-40 | 1 | `checkAppendOnly` against the new locks; the generator's golden tests | Q2, MS4-01 | L1 |
| MS4-40 | **Retire the prototype, after NF5's B1 closes.** Move NF5's measurement code (`CachedFrameReplayBenchmark`, `Nf5Stack.kt`, the guest profile, the three-way bench's prototype arm) onto the product modules. Delete `keliver-compose-gatea-*`, `gatea.json` and `gatea.lock.json`, and the prototype's CI steps. Timed with Track A: after NF5's A6, before NF5's B5 | 3 | The guest profile and the three-way bench's arms (R1's, A6's) build and run on the emulator with the same outputs. No `gatea` remains in `settings.gradle`, CI or any dump. The next A12 session re-baselines `x=retainhost`, and NF5's B5 compares within that session | NF5's B1, MS4-02, MS4-05, MS4-06 | L2 |

**MS4b. The generator complete.** Exit: every WS2 generator output exists for the groups of Q1.

| PR | Scope | Days | Gate / test | Needs | Lane |
|---|---|---|---|---|---|
| MS4-07 | Include lists by package, plus a **generic error stub** for every public declaration of an included package that is not generated (design §11, §19). Curated messages for `Layout`, `SubcomposeLayout`, `Modifier.layout`, `pointerInput`, `detect*Gestures`, Android locals, resources, `AndroidView`. Keliver-only API is generated behind the Q10 opt-ins | 4 | A compile-fail test per curated stub, with the message text asserted. No "unresolved reference" for any public name in an included package (scan test) | MS4-06 | L1 |
| MS4-08 | Default case 3 (presence split, design §5) and nullable-modifier parameters (§19) | 2 | The generator's golden tests; a same-source sample per case | MS4-06 | L1 |
| MS4-09 | Nullable optional slots (the "present" flag) and slots with parameters (§9): host-measured values as events, guest-data slots and placeholder children. **It generates what its own tests use:** <br>- `Scaffold` (top bar, FAB slot, content with `PaddingValues`); <br>- `FloatingActionButton`; <br>- `BoxWithConstraints`; <br>- `AnimatedContent` (one overload, default transition); <br>- `ListItem`; <br>- `BasicTextField`'s value overload with `decorationBox`. <br>MS4-16, MS4-18 and MS4-20 complete those families | 5 | Same-source samples, 0 differing pixels: `Scaffold` with a top bar and a FAB; `BoxWithConstraints`; `ListItem` with and without its optional slots; a decorated field | MS4-07 | L1 |
| MS4-10 | Editor metadata for every group (kinds, defaults as text, KDoc, slots, opt-ins), and `portal-schema-codegen`'s `MappedKind` additions | 2 | `checkPortalCode` fresh; a catalog snapshot test | MS4-07 | L1 |
| MS4-11 | App components, opt-in (Q8): an annotation on an app's multiplatform composable. The generator runs as a `keliver-gradle-plugin` task in the app's build, with its lock in the app's repository and group tags from 100 | 6 | A fixture app exports two components (one with a slot); same-source pixels; the append-only check in the app's build; a non-multiplatform component refused with a reason | MS4-10, Q8, Q15 | L1 |

**MS4c. Coverage, values and per-frame APIs.** Exit: the groups of Q1 are generated at the scope of
section 1, and the corpus covers 100% of lock entries.

| PR | Scope | Days | Gate / test | Needs | Lane |
|---|---|---|---|---|---|
| MS4-12 | foundation-layout and foundation **without host state**: `FlowRow`/`FlowColumn`, `BasicText`, selection and toggle modifiers, scroll modifiers on today's `ScrollState` | 3 | Corpus samples per family; the lock append test | MS4-07 | L1 |
| MS4-13 | The codecs left (design §6): full `TextStyle`/`SpanStyle`, `AnnotatedString`, `Brush` kinds, `FontFamily` (host-registered names), path-built `ImageVector`, `Painter` kinds, `BorderStroke` factories | 4 | Round-trip tests (guest encode, host decode) on jvm, js, wasm and iOS; corpus samples | MS4-06 | L2 |
| MS4-14 | The theme snapshot for guest reads (§7), plus the generated groups in #290's host baseline profile | 3 | A guest reading `colorScheme.primary.luminance()` sees the host's value. A dark-mode switch recomposes nothing in the guest. The profile rules list the new packages | MS4-13 | L2 |
| MS4-15 | **Foundation's host-owned state objects**, as `LazyListState` today: `ScrollState` commands, `LazyGridState`, `LazyStaggeredGridState`, `PagerState`, `TextFieldState` (with `BasicTextField(state)`) and interaction sources | 6 | Per object: the commands and fields round-trip (guest to host, host to guest), with a host fake for those whose widgets come in MS4-38; a process-death restore test. `ScrollState` and `TextFieldState` on corpus samples | MS4-07 | L2 |
| MS4-38 | Lazy grids (`LazyVerticalGrid`, `LazyHorizontalGrid`), staggered grids, and `HorizontalPager`/`VerticalPager`, on MS4-15's states. NF1's lazy-window prediction for grids | 3 | Corpus samples; `LazyScrollTest`'s cases for grids; same-source swipe frames for the pagers | MS4-12, MS4-15 | L1 |
| MS4-39 | **Material 3's host-owned state objects**: `SheetState`, `DrawerState`, `SnackbarHostState`, `DatePickerState`, `TimePickerState`, `TopAppBarState` (with the scroll behaviours' nested-scroll connection), `TooltipState`, `SwipeToDismissBoxState` | 5 | Per object: the commands round-trip and a process-death restore test, with host fakes (their widgets come in MS4-18) | MS4-15 | L2 |
| MS4-16 | Material 3, part 1: text, buttons (all kinds), surfaces, cards, chips, list items, dividers, badges, progress indicators | 4 | Corpus samples; lock append | MS4-09, MS4-13 | L1 |
| MS4-17 | Material 3, part 2: inputs (`TextField`/`OutlinedTextField` on `TextFieldState`, checkbox, switch, radio, slider, segmented buttons) | 5 | Corpus samples. A typing round-trip on the emulator, on MS4-35's route | MS4-15, MS4-16, MS4-35 | L1 |
| MS4-18 | Material 3, part 3: containers and navigation (`Scaffold` complete, top app bars, navigation bar, rail and drawer, tabs, `ModalBottomSheet`, dialogs, snackbar host, menus, date and time pickers, tooltips, swipe to dismiss) on MS4-39's states. Top-app-bar scroll behaviours (host nested scrolling) and window insets | 6 | Corpus samples; the `SheetState` and `DrawerState` command tests against the real widgets | MS4-17, MS4-39 | L1 |
| MS4-19 | Icons: an `icons-core` group, and `icons-extended` opt-in with an app allowlist | 2 | Bundle size with and without extended. An unknown icon gives a placeholder and one report | MS4-01 | L1 |
| MS4-20 | Animation complete (§10): `AnimatedContent` (all overloads), `Crossfade`, `updateTransition`, `rememberInfiniteTransition`, `keyframes`/`repeatable`/`infiniteRepeatable`, `animateItem` specs, animated references on widget parameters. Guest reads per Q3 | 5 | Frame-by-frame same-source tests on JVM and the iOS simulator; 0 change batches while host animations run | MS4-09, Q3 | A (after NF5's B5) |
| MS4-21 | The guest-clock `Animatable` behind an opt-in at ERROR level (maintainer decision, was Q4). Untracked animated values (`1f - alpha`) are reported to the mismatch channel in debug builds | 1 | A compile-fail test without the opt-in; a debug report test | MS4-01 | L1 |
| MS4-22 | `Canvas`, `drawBehind`, `drawWithContent`, `graphicsLayer {}`: commands recorded in the guest and replayed on the host; `size` from the host after layout. **Scheduled early** (L2, days 20–27), so that a slip shows while there is time (Q7) | 7 | Same-source pixels for paths, arcs, text and gradients. A layer-phase read in `graphicsLayer` is an error stub | MS4-13 | L2 |
| MS4-36 | **NF5's frame rules for the generated groups** (review H2). The generator classifies every generated property for NF5's A1 rules (`@Replayable`, `@Scrolls`, `@NeverCached`, `@BlocksFrameWhenTrue`, `@StyleToken`, `@NotData`) and emits them on the generated schemas. So `keliver-tooling-codegen` writes their `FrameRules`, in strict mode, as for the legacy schemas. The generated host gains NF5's A3c hooks: <br>- `ScrollOrigin` on `LazyColumn`/`LazyRow`, the grids, `verticalScroll`/`horizontalScroll` and the pagers; <br>- `TextLines` on `Text`/`BasicText`; <br>- text fields replayed empty (NF5's decision 14). <br>Every coverage PR after it classifies its own properties: strict mode fails the build otherwise | 4 | **NF5's A4b pixel test on the corpus:** 0 differing pixels, replayed against live, on JVM and the iOS simulator. Per scroller, a scroll of one pixel blocks the write. The review list is empty | NF5's A1, A3c and A4b; MS4-02, MS4-05, MS4-38 | L2 |

**MS4d. Gate, bridges and MS6's library pieces.** Exit: D2 covers the generated groups and app
components, and WS3's library half is done.

| PR | Scope | Days | Gate / test | Needs | Lane |
|---|---|---|---|---|---|
| MS4-23 | **The compatibility gate** (design §12, Q5). `keliver.compose` is written into the signed manifest from the guest's generated `ComposeApi` constants. It carries the group levels, and **the app components' group (tags from 100) at the app lock's level** (WS2: "widget groups, generated-API version and app components"). `keliver-publish` derives `constraints.compose`. Both host templates skip at lookup and refuse at load (`compose-missing`), on the paths `bridges` takes. **The templates declare their levels through NF5's H0 host-feature constants**, and `admits` reads that one declaration. The manifest half is library (Gradle plugin); the host and publish halves reach adopters with MS5's tools release | 6 | Relay and template tests (as `BridgeGateTest`). Reference-app scenarios on MS4-35's route, on the emulator and the simulator: a bundle needing a higher group level is skipped; a bundle needing an app-component level the host lacks is skipped; a forged manifest is refused | MS4-06, MS4-11, MS4-35, NF5's H0, Q5 | L2 |
| MS4-24 | B5c, library half: `keliver-bridge-annotations` (and what the editor compiles) gains wasmJs | 1 | `wasmJsTest`; apiDump of the new klib target | none | A (after NF5's B5) |
| MS4-25 | B5b on the iOS simulator (a shared source set with `iosTest`) | 3 | The round trip passes on `iosSimulatorArm64Test` | none | A (after NF5's B5) |
| MS4-26 | `keliver.bridges` written from the generated constants (Gradle plugin), as `keliver.compose` is | 2 | A plugin functional test; the manifest carries the list; no hand-written list is needed | MS4-23 | L1 |
| MS4-27 | `keliver-nav` on wasmJs (ED2's prerequisite). **Lands before NF5's B1 touches `keliver-nav`** | 2 | `keliver-nav:wasmJsTest`; apiDump | none | L2 (days 3–5) |
| MS4-28 | The generated `@ScreenState` registry (#233): the library half that lets a device open a named state. The device route itself is MS6's (ED2, ED3). **Lands before NF5's B1** | 4 | A registry generated for the sample; a JVM test opens each state with its fixtures | MS4-27 | L2 (days 5–9) |

**MS4e. Editor, migration and proof.** Exit: every candidate check in section 3 runs.

| PR | Scope | Days | Gate / test | Needs | Lane |
|---|---|---|---|---|---|
| MS4-29 | ED0 ingest on by default for the generated groups. The spike's `for`-as-Repeat reading is removed (the D6 amendment excludes it). The relay serves the generated catalog, and the tools classpath carries the values module | 4 | D14 on a real screen; `ParityGoldenTest`; 0 RawCode on the corpus within the supported subset; `ShippedClasspathTest` updated | MS4-10 | L2 |
| MS4-30 | The canvas wired into `portal-editor` (a guest composition in the browser feeding host `ScopedChildren`, the inspector reading the metadata's kinds); lazy lists on the canvas | 8 | Protocol equality, canvas against device, for every corpus sample (wasm pixels cannot be captured, U41); the editor E2E suite | MS4-29 | L2 |
| MS4-37 | **Adoption finding 8's open point** (PLAN section 7): `Compiled_<screen>.kt`'s stamp changes on every edit and churns in git. It should change only when what it stamps changes | 1 | Two edits that leave the compiled output the same leave the file byte-identical; an edit that changes it changes the stamp once; an ingest round-trip on a real screen | MS4-29 | L1 |
| MS4-31 | The migration tool, legacy to generated (`Background(colorArgb)` to `Modifier.background(Color(…))`, …): a tools command built on `portal-ingest`'s parse (Q13: confirm) | 6 | Converts the `sample/` and `reference/inventory` screens. Each converted screen compiles, ingests with 0 RawCode, and matches its old screenshot within the legacy-to-Compose tolerance the PR records | MS4-18 | L1 |
| MS4-32 | The reference app ported (by MS4-31, then by hand where needed), checked on MS4-35's route | 3 | `reference-app.yml` (emulator) and `ios-host.yml` (simulator) green, T0–T4 and S17* included | MS4-31, MS4-23, MS4-35 | L1 |
| MS4-33 | Golden screens: every corpus sample and every named state of the reference app, on the Android emulator (`google_apis`), the iOS simulator and the web canvas (protocol equality) | 4 | Goldens checked in; a deliberate one-pixel change fails | **all of MS4c**, MS4-30, MS4-32 | L2 |
| MS4-34 | `llms.txt` and the agent guide generated from the catalog, with a staleness guard; docs for the generated packages; the 0.5.0 notes for a new adopter | 3 | A freshness check in CI. An agent writes a screen from `llms.txt` alone and it compiles and renders (Gate B's agent test, repeated) | **all of MS4c**, MS4-11 | L1 |

**MS4f. The candidate.** A branch with `KELIVER_VERSION = 0.5.0` and the lock frozen. Section 3's checks,
C1–C16, are run and written up in one report; then the owner's go (MS5 publishes). It waits for every
MS4 PR **and NF5's A6 and B5**. 3 days, plus device sessions.

**Totals:** about **147 MS4 agent-days**. By milestone: MS4a 20, MS4b 19, MS4c 58, MS4d 18, MS4e 29,
MS4f 3. Revision 1 had 126. The 21 added days:
- MS4-35, the local-Maven harness (M2);
- MS4-36, NF5's rules for the generated groups (H2);
- MS4-37, finding 8 (M5);
- MS4-38 and MS4-39, the state objects re-scoped (M3);
- MS4-40, the prototype's retirement (H3);
- MS4-09, MS4-15 and MS4-23 grown by a day each (M3, M4);
- one more day for the candidate (C14–C16).

Two PRs shrank: MS4-12 (the pagers and grids moved out) and MS4-32 (the route moved to MS4-35).

NF5, by this plan's estimate, is about **57 agent-days** on Track A:
- H0 2;
- (A): A1 3, A2 2, A3a–c 9, A4a–b 8, A5 3, A6 2;
- (B): R1 2, B1 6, B0 3, B2 6, B3 2, B4 6, B5 3.

Together that is about 204 agent-days before the go.

### 2.2 Two tracks: where NF5 and MS4 meet

NF5 (Track A) is designed and approved (`docs/SHOW_BEFORE_COMPUTE.md`, #308,
revision 5). Its H0 and (A) start on day 0. Its (B) waits for two steps:
- **R0**, the A12 trace of one floor step, on **2026-11-25**. Its build comes from #308's head, and it
  **runs no MS4 code**: nothing of MS4 is merged by then.
- **R1**, a reveal-only arm on the prototype.

Decision 7 is already answered, (b).

**#308 merges before MS4-01 opens.**

| Shared area | NF5 touches | MS4 touches | Rule |
|---|---|---|---|
| The prototype modules: `keliver-compose-gatea-composeui` (with its dependencies, the other `gatea-*` modules), `keliver-compose-gatea-profile`, `sample/` (the three-way bench) | #308 adds `CachedFrameReplayBenchmark` (composeui), `Nf5Stack.kt` and `QuickJsProfile.kt` (profile) and the bench's `variedDetail` (sample). R1 adds a bench arm. NF5's B1 is a spike "against the prototype" on gate A's container and the guest profile | MS4-01 and MS4-02 add the product modules; MS4-40 retires the prototype | **The measurement modules are not moved or renamed until NF5's B1 closes.** MS4-01 and MS4-02 **copy rather than move**, so MS4-01 moves nothing at all. "The rest" it could have moved is only the generator and `keliver-compose-values`, which keep their names (Q15); the other `gatea-*` modules are the composeui module's own dependencies, so they stay with it. Copying is possible because the generator takes one config per group, so the product groups are generated beside `gatea` while the prototype keeps building. Neither waits for B1. The cost is two generated copies and the prototype's tests (and its iOS link) in CI from about day 3 to day 48. NF5's B1 runs on the prototype's container, as NF5 plans. MS4-40 moves NF5's measurement code onto the product modules after B1 and A6, and before B5 (L2, days 45–48), agreed with Track A. A generator change regenerates the prototype too (`checkComposeApi`); NF5's measurements compare within one build |
| The production container | NF5's **B0** builds the retained container's entry states and `retainedContainer@2` "in NF1's production container" | **MS4-02** puts NF1's container in the product host module | **MS4-02 is a named prerequisite of NF5's B0** (MS4-02 ends on day 11, B0 starts on day 37) |
| `keliver-nav` | NF5's B1 (spike), then B2–B4: held effects, parked pops, kinds, the reveal | MS4-27 (wasmJs), MS4-28 (registry) | **MS4-27 and MS4-28 land before NF5's B1 starts** (L2, days 3–9; B1 from day 9). From B1 on, Track A owns `keliver-nav`, and no MS4 PR edits it |
| `keliver-tooling-codegen` and the `keliver-schema` annotations (review H2) | NF5's **A1**: the frame-rule annotations, `FrameRules` and strict mode | **MS4-03** (absent groups in the host factory); **MS4-36** (the generated schemas carry A1's annotations) | A1 (Track A, days 4–7) and MS4-03 (L1, days 3–6) overlap. They edit different outputs of the module (A1 adds `FrameRules`; MS4-03 changes the host factory). Whichever merges second merges `origin/main` and reruns the codegen tests, and neither renames shared code. MS4-36 starts after A1, A3c and A4b |
| Host features and templates (`scripts/templates/*/BundleIndex.kt`, `HostConfig`) and `keliver-treehouse-host` (review M7) | **H0**: host feature names as constants in one place, which templates declare. A3a–c, A5 (the start table, storage, the off switch), B3, B4 | MS4-04 (mismatch reports), MS4-23 (the compose gate's levels in both templates' lookup and load) | **H0 comes first** (Track A, days 0–2). MS4-04's template wiring and MS4-23's `constraints.compose` build on H0's constants: a template declares its group levels there, and `admits` reads that declaration. **There is no second list.** MS4-23 (days 48–54) lands after NF5's cold-start code (A5, days 32–35). The gate's changes stay in `BundleIndex.kt`'s constraint and manifest checks, and NF5's stay in the start path. Each names the other's PR in its body |
| The protocol | NF5's A2: `FrameCacheTags` in `keliver-protocol` (lock test); B0: `retainedContainer@2`; B4: `reveal@1` | MS4-01 and MS4-06: product tags, free until the freeze | NF5's protocol changes are additive, under their own lock tests and host features. They do not wait for the generated groups' lock, and the generated groups' lock does not wait for them. **The generated lock freezes at the `v0.5.0` tag (Q2), and NF5's A2, B0 and B4 land before the candidate**, so 0.5.0 carries both. A stored frame is keyed by the release (sequence, manifest sha256, module digest), so a tag change before the freeze never replays a stale tree |
| The reference-app workflows | H0's and A5's gates | MS4-04, -17, -23, -32; C7, C8 | Library changes are checked on **MS4-35's route** (L2, days 0–3). H0's workflow gate runs on that route once it merges |
| API dumps (`keliver-nav.api`, `keliver-treehouse-host.api`, `keliver-protocol.api`, klib dumps) | New experimental API | New API | Merge `origin/main`, then run `apiDump`. Never hand-merge a dump |
| Phone sessions | R0 (2026-11-25), R1, A6 (A-1…A-8), B5 (B-1…B-10) | The candidate's A12 and iPhone runs (C11, C14, C15) | One session at a time (PD7). MS4f's runs share NF5's next scheduled session where the timing allows |

### 2.3 Lanes, load and duration (review M1)

**Lane loads** (agent-days; the start and end days are from a schedule that respects every "Needs"; day 0
is 2026-11-25):

| Lane | Agent | PRs in order (start–end day) | Load | Ends |
|---|---|---|---|---|
| L1 | Track B's agent | MS4-01 (0–3), 03 (3–6), 06 (6–7), 07 (7–11), 09 (11–16), 10 (16–18), 08 (18–20), 12 (20–23), 16 (23–27), 11 (27–33), 19 (33–35), 38 (35–38), 17 (38–43), 18 (43–49), 31 (49–55), 32 (55–58), 37 (58–59), 26 (59–61), 21 (61–62), 34 (62–65) | 65 | day 65 |
| L2 | a dedicated second MS4 agent | MS4-35 (0–3), 27 (3–5), 28 (5–9), 02 (9–11), 05 (11–14), 04 (14–16), 13 (16–20), 22 (20–27), 15 (27–33), 39 (33–38), 14 (38–41), 29 (41–45), 40 (45–48), 23 (48–54), 36 (54–58), 30 (58–66), 33 (66–70) | 70 | day 70 |
| A | Track A's agent | NF5: H0 (0–2), R1 (2–4), A1 (4–7), A2 (7–9), B1 (9–15), A3a (15–18), A3b (18–21), A3c (21–24), A4a (24–28), A4b (28–32), A5 (32–35), A6 (35–37), B0 (37–40), B2 (40–46), B3 (46–48), B4 (48–54), B5 (54–57); then MS4-20 (57–62), 24 (62–63), 25 (63–66) | 57 + 9 | day 66 |
| MS4f | any | the candidate (70–73) | 3 | day 73 |

**The binding constraint is lane load, not the dependency path** (review M1). In revision 1, lane B1's 59
days bound the plan, not its 44-day critical path, and even that assumed a second agent who was mostly
free. In this revision:
- L1 holds 65 days and cannot be split: every PR in it edits the generator and the lock, one after
  another.
- L2 (70 days) and Track A's tail (9 days) take everything that can move.
- So the floor is the total, about 204 agent-days over three agents, or about 68 days.

The dependency critical path is about 44 days: MS4-01 → 06 → 07 → 09 → 16 → 17 → 18 → 31 → 32 → 33 →
MS4f. NF5's longest chain, from R1 through NF5's B1, B0 and B2–B5, is shorter (about 32 days). So
neither one sets the date.

**Duration:**
- **Three agents** (L1, L2 and Track A, as above): the candidate opens on about **day 70** and is
  written up by day 73. That is about 15 weeks of working days. Allow **16–17 weeks**, which is mid- to
  late March 2027, for review rounds, a shared machine's CI queue, the phone sessions (R1, A6, B5, the
  candidate) and the owner's answers.
- **Without a dedicated second MS4 agent** (Track B's agent alone on MS4, Track A joining after NF5's
  B5): at least 102 days for 204 agent-days over two agents. Allow about **22–24 weeks**.
- **If Track A does not join after NF5:** MS4 alone is about 147 days, about **30 weeks**.

**What the schedule assumes:**
- Q1 and Q14 are answered by day 0, and Q2 by day 6 (section 6). If Q1 or Q14 is late, L1
  takes MS4-24 and MS4-25 first (4 days, no dependencies; they move from Track A's tail), and the plan
  slips by any wait beyond that.
- NF5's B1 passes. If it fails, (B) goes back to the owner (NF5 section 9). Then MS4-40 still runs after
  B1, and the candidate waits for the owner's ruling on (B).

## 3. The 0.5.0 candidate: "passes every check"

Everything below runs on one commit with `KELIVER_VERSION = 0.5.0`, and is written up in one candidate
report. The candidate waits for NF5's A6 and B5.

| # | Check | Pass when |
|---|---|---|
| C1 | The full graph's `check` and CI's API step (`apiCheck`, `ziplineApiCheck`, `redwoodApiCheck`, `spotlessCheck`, `validatePlugins`) on hosted macOS. The nightly full build (#306, sharded by #309) is **dispatched by hand on the candidate branch**: its schedule runs on `main` | Green |
| C2 | API dumps | Every new published module has JVM and klib dumps, under Q14's and Q15's names. The diff against 0.4.1 is summarised (declarations added and removed per module) and reviewed. No `gatea` name remains in any dump (MS4-40) |
| C3 | Generated code fresh | `checkComposeApi`, `checkPortalCode`, `checkAppendOnly` against the **frozen** lock, `FrameRules` (strict) and `llms.txt` freshness are all up to date. MS4-34's agent test is repeated on the candidate: a screen written from `llms.txt` alone compiles and renders |
| C4 | Same-source corpus | Every lock entry is used by at least one sample. `onlyImportsDiffer` for every pair. 0 differing pixels on the JVM and the iOS simulator, with animations checked frame by frame |
| C5 | Golden screens | The corpus and the reference app's states match on the Android emulator and the iOS simulator. The web canvas is protocol-equal to the device |
| C6 | Editor (D14), **both grammars** | Generated: every reference-app screen and every corpus sample within the supported subset compiles, ingests with 0 RawCode, has a device screenshot and a byte-stable write-back. **Legacy** (it still ships as a group): the same on `portal-ingest`'s legacy round-trip suite and on one `sample/` screen kept unconverted as the legacy fixture. Also `ParityGoldenTest` and the editor E2E suite |
| C7 | The reference app ported | `reference-app.yml` and `ios-host.yml` are green on MS4-35's route against the candidate: T0–T4, S17–S17d and MS4-23's gate scenarios (group level, **app-component level**, forged manifest) included. Every bundle names `quickjs-2026` |
| C8 | The adopter path | `publishToMavenLocal -PkeliverVersion=0.5.0 -DRELEASE_SIGNING_ENABLED=false`. A new app built from this checkout's scaffolders against that repository builds, publishes and renders on the emulator and the simulator |
| C9 | Size (Q11) | Against 0.4.1, for the reference app: the release APK's **increase** is at most +2.5 MB; the **bundle in total** is at most 2.5 MB; the iOS app's **increase** (thinned arm64 build) is at most Q11's budget. Measured with and without the legacy group. NF5's halves are each under 50 KB of dex (NF5's decision 17) |
| C10 | Compile time | Guest compile, Android host build and iOS host link, each **at most 1.5× 0.4.1's** on the same runner. A breach names its cause in the report, and the owner accepts it or not |
| C11 | Speed on phones (PD7, batched) | **A12:** open, back, cold start and scrolling no worse than the 2026-11-25 re-measure on the same harness. **The tap:** the first visible change after the touch-down at parity with native Compose's own button (series K's method; the owner's bar of 2026-11-22). **iPhone 13:** first frame and pixels (`keliver-gateb-ios-device.sh`). If signing is not available, the iPhone half is recorded as not run, and the go then needs the owner's explicit acceptance of simulator-only iOS evidence (with A-8 and B-7) |
| C12 | U14's tripwire (WS1) | The two ignored `ProtocolTest`s are re-run on the candidate (Kotlin 2.4.21 and CMP 1.12.2 since 0.4.1), and the result is written down |
| C13 | Docs | `docs-check` clean. CHANGELOG's 0.5.0 section is complete and written for a new adopter (the owner's 2026-11-23 rule): the generated API, `session()` replacing `discardPrefetched()`, and `keliver-nav`'s behaviour changes (NF5's decision 5) included |
| C14 | **NF5 (A)**: A-1…A-8 | A-1…A-8 pass as run by NF5's A6 on the A12 and the iOS simulator. Re-run on the candidate commit: A-7's correctness checks and (A)'s host tests in CI; A-1, A-3, A-4 and A-5 in the candidate's phone session. MS4-36's pixel test on the corpus passes |
| C15 | **NF5 (B)**: B-1…B-10 | Each gate passes under decision 7's answer (b), as run by NF5's B5. A miss is not waived in the report: NF5 sends a miss on B-1 or B-2 to the owner with the parked fallbacks (PLAN section 12), and the candidate waits for that ruling. Re-run on the candidate commit: B-5's inertness tests in CI; B-1, B-2, B-4 and B-8 in the candidate's phone session |
| C16 | Item 2's M4 split | One emulator run: a host built from this checkout's templates reads `KeliverTrace.composed()` and reports the composition and encoding split for one open (the templates ship in MS5's tools release) |

**What "0.5.0 go" needs from the owner:**
- read the candidate report (C1–C16), including NF5's gate results and any ruling on a miss (C15);
- agree the frozen lock (the wire committed to, section 5) and C2's API summary;
- a phone session for C11, C14 and C15 (the A12, and the iPhone with an Apple account in Xcode);
- then the go for `publish-maven-central.yml` at `v0.5.0`. Publishing stays on the owner's explicit go;
  the owner chose on 2026-11-22 not to add a GitHub Environment for it now.

MS5's tools release follows: B5c's flags, MS4-23's host and publish halves, and the scaffolders
switching to the fork for 0.5.0 apps.

## 4. Gate B's open sub-decisions (#243–#246)

| PR | Question | Options | Recommendation and evidence | Blocks MS4-01? |
|---|---|---|---|---|
| #243 | The iOS same-source pixel test costs about 15.5 min of CI's Build job (14.7 min linking the test binary) | (a) keep it on every PR; (b) only when generated-API paths change, plus nightly; (c) nightly or manual only | **(b), decided by the maintainer** (was Q6). The corpus grows with MS4c, and so does the link. The nightly full build (#306) catches what the filter misses. The simulator app script (`keliver-gateb-ios-sim.sh`) runs by hand for the candidate (C4) on `keliver-mac`. "+89 ms on the simulator" was settled by Gate B's confirmation | No |
| #244 (1) | `animate*AsState` on the host (animated references) or on the guest's frame clock | host; guest | **Host.** On the emulator, about 20 changes/s against about 330, and the guest clock gives about 29 values/s against about 55 host frames. On the A12 (#245) the guest clock gives **2.8 values/s**, so it steps. Host references are pixel-identical to native, frame by frame, on JVM and the iOS simulator ([`GATE_B_RESULTS.md`](GATE_B_RESULTS.md)) | No |
| #244 (2) | How a guest reads an animated value | tagging (built): reads are off by ULPs, `== target` is false, `0f` reads `Float.MIN_VALUE`; a read log (not built): exact reads, but misses a value read early and kept | **Read log** (Q3). Tagging turns guest logic wrong silently (`if (alpha == 0f)` differs from Compose, against bar 1). A read log's failure is a missing animation, which is visible, and a debug-build check can report unconsumed log entries. The wire (`ExtraAnimated`) is the same either way, so the lock does not wait for it | No; blocks MS4-20 |
| #244 (3) | A host factory that accepts absent groups | yes; no | **Yes** (MS4-03). R8 already removes 79% of Material 3 unaided. The measurable saving is the legacy renderers (92 classes, plus the Material 3 and Coil code only they reach) and Coil (170 classes) | No; part of Q1 |
| #245 | How strongly to flag the guest-clock `Animatable` escape hatch | a lint warning (design); an opt-in at ERROR; an error unless suppressed | **An opt-in at ERROR, decided by the maintainer** (was Q4; MS4-21). 2.8 values/s on the A12 is not an animation. A compile-time opt-in fits bar 5 and costs about a day | No |
| #246 | The iPhone pixel test (blocked at signing on 2026-11-14) | — | **Done that evening:** 0 differing pixels on three samples on an iPhone 13, first frame +142 ms warm and +820 ms with an empty cache. Left: the wrapper's UIKit link fix never ran on the device signing path, and a personal team's 7-day profile recurs at every session (C11) | No |

## 5. Risks

| Risk | Evidence today | Mitigation | Fallback |
|---|---|---|---|
| Binary size: generated renderers and groups | +1.79 MB APK (+17.9%) with the route, of which the bundle is 1.95 MB; R8 removed 79% of Material 3; iOS app size not measured | Groups with absent-group support (MS4-03, which also measures the iOS app first); legacy and Coil optional; `icons-extended` allowlisted; C9 measures the APK, the iOS app and the bundle | Ship more, smaller groups in 0.5.x (append-only: new groups, never moved widgets); a release-size report (WS6) |
| Compile and link time | The iOS test link alone is 14.7 min on a 3-core runner (#243). Until MS4-40, the prototype's and the product's tests both link | Adopters consume published klibs (they do not compile our generated code). C10 measures the iOS host link against a threshold. #243's path filter | Split the generated host module per group, so a host links only what it uses; Kotlin/Native release link caching on CI |
| Compose version coupling | The generator reads the CMP 1.12.2 and material3 1.9.0 jars; copied default expressions depend on them; `checkComposeApi` fails on a bump (design §20) | 0.5.x supports CMP 1.12.x only, declared in the Gradle metadata (Q9). A nightly job builds the groups against the next CMP. Hidden overloads keep their tags across a bump (§3) | A Compose minor bump is a Keliver minor release with regenerated groups; an app on a newer Compose waits for it |
| The API surface committed to | The wire (lock tags) lives on devices for as long as bundles do. The guest stubs follow Compose. The mirrors and host-state commands are Keliver's own | Freeze only the lock at 0.5.0 (Q2). Keliver-only API (extras, app components, host-state commands, nav retention) goes behind `@Experimental…` opt-ins (Q10); mirrors' internals are non-public | The pre-1.0 policy ([`API_STABILITY.md`](API_STABILITY.md)) allows breaking guest-source API in 0.x. The wire cannot break, so a mistake becomes a hidden entry with a new tag |
| Scope: WS2 in full plus NF5 is about 204 agent-days | Section 2.3 | Three agents. Coverage is config once each category exists (state objects, slot parameters, recording). `Canvas` recording, the most novel category, is scheduled early (MS4-22, days 20–27) | Q7: only if a category is late does the owner decide whether to amend PD4. PD4 stands until then |
| NF5 and MS4 collide | Section 2.2 | Area ownership, the order (H0 first; MS4-27/28 before NF5's B1; MS4-02 before NF5's B0; MS4-23 after A5), and the prototype copied rather than moved | NF5's protocol changes are additive under their own lock tests and host features, and land before the candidate; the generated lock freezes at the tag (Q2) either way |
| NF5 (B)'s gates miss | NF5's appendix B expects B-1 at 120–150 ms against about 100, and B-2 at 225–275 against 150 or less | R0 and R1 first; decision 7's split (b) | The owner rules on the parked fallbacks (PLAN section 12) with NF5's evidence; the candidate waits for that ruling (C15) |
| The prototype and product copies drift | MS4-02 copies NF1's host pieces | Fixes go to the product copy only; the prototype is frozen except for NF5's measurement code | MS4-40 retires the prototype by day 48 |
| Old 0.4 bundles | One engine since NF2: every 0.5 bundle is `quickjs-2026`, and hosts take only their engine's bundles (#275) | None needed: the 0.4 wire is not served to 0.5 hosts, so MS4 may reset tags before the freeze | — |

## 6. Decisions

### 6.1 Owner decisions needed

"Blocks" names the first PR that cannot start without the answer, and its start day in section 2.3.

| # | Question | Recommendation | Cost | Blocks |
|---|---|---|---|---|
| Q1 | The group layout: which groups, and which root? | A generated root with the legacy `keliver-material` an optional dependency. Groups: compose core (foundation-layout, foundation, ui, material3, animation, extras), `icons-core`, `icons-extended` (opt-in), `coil` (optional) | A codegen change for absent groups (3 days). Legacy tags move under an offset (moot: 0.4 bundles never reach 0.5 hosts) | **MS4-01, day 0**, and everything after it |
| Q14 | **Maven Central artifact names of the generated group modules.** They are permanent once published; Q1 names the groups, not the artifacts | `dev.keliver:keliver-compose-<group>-<role>`, with the roles the prototype has (`-schema`, `-widget`, `-modifiers`, `-compose`, `-protocol-guest`, `-protocol-host`, `-composeui`); e.g. `keliver-compose-material3-composeui`, `keliver-compose-icons-core-compose` | Long names. A layout change after publishing means new artifacts and a relocation | **MS4-01, day 0** |
| Q15 | **Artifact names of the shared modules**: the values runtime and the generator (which Q8 publishes for adopters' builds) | Keep `dev.keliver:keliver-compose-values` and `dev.keliver:keliver-compose-generator`; the app-components task lives in the existing `keliver-gradle-plugin` | None now; permanent once published | MS4-11, day 27; C2 |
| Q2 | When does the lock become append-only for good? | Fresh product locks at level 1 now; free changes until the candidate; frozen at the `v0.5.0` tag | The prototype's lock fixtures are retired with the prototype (MS4-40) | **MS4-06, day 6** (on the critical path) |
| Q10 | Which generated API is stable in 0.5.0? | Compose mirrors stable; Keliver-only API experimental (opt-in) | Opt-in annotations in adopters' code | MS4-07, day 7. Until answered, Keliver-only API is generated as experimental, which is the recommendation |
| Q8 | App components: generated in the adopter's build? | Yes, a `keliver-gradle-plugin` task, with the lock in the app's repository | 6 days; the plugin gains the generator's classpath | MS4-11, day 27 (and so MS4-23) |
| Q5 | Gate granularity: the whole group level, or the levels actually used? | The whole level in 0.5.0 (simple, refuses more, and no older hosts exist), with the app components' level beside it | Coarser refusals once 0.5.x hosts age | MS4-23, day 48 |
| Q13 | **Confirm:** the migration tool (MS4-31) stays in 0.5.0, as PLAN section 8 and WS2 already include it | Confirm. It ports `sample/`, the reference app and MS7's 13 private screens, and its rules double as the agent guide's "legacy to Compose" table | 6 days | MS4-31, day 49, only if the answer is no |
| Q3 | Guest reads of animated values: tagging or a read log? | A read log, with a debug check for unconsumed reads | 3–4 days; tagging's tests rewritten | MS4-20, day 57 |
| Q7 | **An amendment of PD4, only if needed:** if one per-frame category (`Canvas` recording) misses, may 0.5.0 ship it as an error stub that names an app component, and add it in 0.5.1? That would cut "WS2 in full", which PD4 requires | **No amendment now.** MS4-22 is scheduled early (days 20–27), so a slip shows by about day 40 with a month of margin. The maintainer brings the amendment, with the evidence, only then | None unless it happens; if it does, a guest that draws waits for 0.5.1 | Nothing now; the go, only in the slip case |
| Q9 | The Compose range for 0.5.x | CMP 1.12.x only, declared; the next CMP built nightly | Apps cannot move to CMP 1.13 before Keliver 0.6 | MS4-34 (the adopter notes), day 62; the candidate |
| Q11 | The size budget for C9 | APK increase at most +2.5 MB; bundle at most 2.5 MB in total; iOS app increase at most +3 MB (not yet measured: MS4-03 measures it first, and the plan comes back to you if that first measurement is already over) | A budget that may force the group split earlier | C9 at the candidate; MS4-03's measurement, day 3 |

### 6.2 Decided by the maintainer (were owner questions in revision 1)

| Was | Decision | Why |
|---|---|---|
| Q4 | The guest-clock `Animatable` sits behind an **opt-in at ERROR** (MS4-21) | 2.8 values/s on the A12 is not an animation; one day; it is reversible (it can be relaxed to a warning) |
| Q6 | The iOS same-source test runs **path-filtered on PRs, and in full nightly** (#243 option (b)) | It is a CI-cost call. A regression outside the filter shows the next morning, and the candidate runs it in full (C4) |
| Q12 | Track A does **not** take MS4's `keliver-nav` PRs: it is on NF5 from day 0. **L2 takes MS4-27 and MS4-28 first**, merged before NF5's B1, and Track A owns `keliver-nav` from B1 on. Track A takes MS4-20, -24 and -25 after NF5's B5 | NF5 (A) starts now, so Track A has no review window to spare (review M1) |
| (new) | **Staffing: three agents** (L1, L2 and Track A) | It is the only plan near 16–17 weeks. Two agents take 22–24 weeks (section 2.3) |

Recorded owner decisions this plan follows, and does not re-ask:
- NF5 (A) and (B) are in 0.5.0, so the candidate waits for both (2026-11-24);
- NF5's decision 7 is (b), and its other decisions are as recommended (2026-11-24);
- the tap bar is parity with native (2026-11-22);
- publishing is on the owner's explicit go, with no GitHub Environment (2026-11-22).

Record and changelog: none from this note; each MS4 pull request carries its own blocks.
