# MS4 plan: the 0.5.0 build

**Status: draft, 2026-11-24.** The delivery plan for MS4 of [`PLAN_2026-11.md`](PLAN_2026-11.md)
(section 8's MS4 row, section 9's Track B). It orders the work; it decides nothing that
[`DECISIONS.md`](DECISIONS.md) or the plan of record decide, and section 6 lists what the owner
still has to. Records stay in [`DELIVERY_PLAN.md`](DELIVERY_PLAN.md).

**Binding inputs.** PD4 (the owner, 2026-11-24): **one** 0.5.0 libraries release carries Kotlin
2.4.21, the Zipline fork, the Compose-generated API (WS2 in full; Gate B confirmed 2026-11-24, D6
and D9 amended), the bridge modules (WS3), item 2's M4 composition/encoding split, NF1's and NF4's
library changes, and the library pieces MS6 needs. D2 is extended, not replaced (WS2's gate). D1,
D3, D4, D14 unchanged.

**IDs in this note:** milestones **MS4a–MS4f**, pull requests **MS4-01…MS4-34**, owner decisions
**Q1–Q13**. They are not item 2's M0–M5 or item 4's B1–B5.

## 0. What 0.5.0 carries, and what is left

| Item (PD4) | State on `main` (7014fb08e) | Left for 0.5.0 |
|---|---|---|
| Kotlin 2.4.21 | Merged (#303) | Nothing; re-proven by the candidate's checks (section 3) |
| Zipline fork `dev.keliver.zipline` 1.28.0-keliver.1, one engine `quickjs-2026` | Published 2026-11-24; Keliver on it (#294, merged) | #299 (the 0.5 line's host toolchain, open) |
| Compose-generated API (WS2) | A prototype behind `-PkeliverComposePrototype`, unpublished: one group `gatea`, 29 widget, 36 modifier and 15 factory tags at lock level 5 (`keliver-compose-generator/gatea.lock.json`) | Most of MS4: section 1 |
| ED0 (editor on the generated catalog) | A spike behind a flag (#239, [`ED0_SPIKE.md`](ED0_SPIKE.md)); canvas not wired into `portal-editor` | MS4e |
| Bridge modules (WS3) | `keliver-bridge`, `-annotations`, `-codegen` merged, never published; B5b on the JVM (#211) | B5c's library half (`keliver-bridge-annotations` gains wasmJs), B5b on the iOS simulator, `keliver.bridges` from generated constants (MS4d) |
| Item 2's M4 split | **Library half merged:** `KeliverTrace.composed()` (`keliver-treehouse-guest/src/commonMain/kotlin/dev/keliver/treehouse/KeliverTrace.kt:38`, called at `StandardAppLifecycle.kt:133`; #237) | The host templates' reading of it ships in the tools release after 0.5.0; one emulator run in the candidate |
| NF1's library changes | Merged as experimental in `keliver-nav`, `keliver-compose`, `keliver-treehouse*` (#259, #262, #265, #266, #271, #278); host-side parts (lazy window prediction, placeholders, retained host back, one state per widget) live **only in the prototype** `keliver-compose-gatea-composeui` (#258, #265, #276, #280) | Carried into the real generated host module (MS4-02); the press signal stays off by default (owner, 2026-11-22) |
| NF4's library changes | `keliver-treehouse-host`'s baseline profile (#286); the opt-in plugin for generated host code (#290) | The generated groups' host code covered by #290's plugin (MS4-14) |
| MS6's library pieces | `@ScreenState`/`NamedState` in `keliver-nav` (#226), `keliver-http-fixtures` (#224), `keliver-http-replay-debug` (#232) | `keliver-nav` on wasmJs (ED2; its targets are js + jvm only, `TreehouseGuest` in `RedwoodBuildPlugin.kt:484`); a generated `@ScreenState` registry so a device can open a state (#233 left it unbuilt) |

## 1. WS2: what exists against what it needs

Evidence is `path:line` on `main` at 7014fb08e. "Prototype" means the modules are always built
(`settings.gradle:66-74`) but publish only with `-PkeliverComposePrototype`
(`keliver-compose-values/build.gradle:13`), which the Central workflow never passes. None of them has
an `api/` dump. Paths below shorten `keliver-compose-` to `kc-`, the generator's
`src/main/kotlin/dev/keliver/compose/generator/` to `gen/`, `keliver-compose-values/src/commonMain/kotlin/dev/keliver/compose/`
to `values/`, and the guest stubs' `src/commonMain/kotlin/dev/keliver/compose/` to `stubs/`.

| WS2 needs | Exists today | Evidence | Gap for 0.5.0 (PR) |
|---|---|---|---|
| Generator inputs: Compose's published API and sources | **Yes**, on the pinned catalog: Kotlin metadata plus ASM over the desktop jars, PSI over the `-sources.jar`s; CMP 1.12.2, material3 1.9.0, coil 3.6.3 | `gen/ApiReader.kt:42`, `gen/SourceReader.kt:40`, `gen/Main.kt:69` (`generate`); pins `gradle/libs.versions.toml:14,98,27` | **What** is generated is a hand list: 70 declarations in `kc-generator/gatea.json` (`"declarations"`, from line 10), plus 3 curated errors (line 95). Include by package (MS4-07) |
| Outputs: guest stubs, wire schema, host renderers, editor metadata | **Yes**, all four, checked in, staleness-guarded (`checkComposeApi`) | `gen/Emit.kt:98` (schema), `:172` (guest), `:429` (host), `:719` (`*Defaults` mirrors), `:874` (editor JSON), `:933` (canvas interpreter) | One group only (`gatea.json:2`); productise and split by Q1 (MS4-01) |
| Lock file of tags, append-only | **Yes**: canonical-signature keys, per-parameter `wire` levels, `since`, refusal of any non-append | `kc-generator/gatea.lock.json` (level 5; 29 widgets, 36 modifiers, 15 factories); `gen/Lock.kt:29` (`Lock`), `:42` (`LockEntry`), `:177` (`checkAppendOnly`) | Fresh product locks; frozen at `v0.5.0` (MS4-06, Q2) |
| Value codecs | **Most**: `Dp`, `TextUnit`, `Color`, `Shape`s, `TextStyle`, font types, alignments, arrangements, `PaddingValues`, `BorderStroke`, `ColorFilter`, `Painter`, `ContentScale`, `Role`, animation specs, `*Defaults` call records | e.g. `values/ui/graphics/Color.kt:115` (references), `values/material3/GeneratedDefaults.kt`; host converters in `kc-gatea-composeui/…/Converters.kt` | **`Brush` is a placeholder** (`kc-gatea-compose/stubs/ui/graphics/Generated.kt:9`; `background(brush)` an ERROR stub, `…/foundation/Generated.kt:30`); `AnnotatedString`, path-built `ImageVector`, host-registered fonts (MS4-13) |
| Theme references | **Yes**: `colorScheme`, `typography`, `shapes`, `LocalContentColor`, `LocalTextStyle` resolve on the host | `values/material3/MaterialTheme.kt` (guest), `values/ui/graphics/Color.kt:154` (`ColorRefs`), `Converters.kt:119` (`resolveColor`) | The theme snapshot for guest reads (design §7) is not built (MS4-14) |
| Host-owned state objects | **`LazyListState` only** (commands, viewport fields, `Saver`); `ScrollState` carries its initial offset only | `gen/Classify.kt:72` (`hostState`); `values/foundation/lazy/LazyListState.kt:59`; `values/foundation/ScrollState.kt:32` ("commands … are Gate B") | `ScrollState` commands, `PagerState`, `SheetState`, `TextFieldState`, interaction sources (now ERROR stubs, `…/material3/Generated.kt:192`) (MS4-15) |
| Per-frame APIs | **Partly**: `AnimatedVisibility` (3 overloads), `animateContentSize`; `animate*AsState` as tagged host references; guest `Animatable` | `kc-gatea-compose/stubs/animation/Generated.kt`; `values/animation/core/AnimatedReferences.kt:267`; `values/animation/core/Animatable.kt:110` | `AnimatedContent`, `Crossfade`, transitions, `keyframes` (MS4-20); **`Canvas` recording: none** (`values/ui/graphics/drawscope/DrawScope.kt:22`, constants only) (MS4-22) |
| Compile-error stubs | **Mechanism yes**, list curated: `Layout`, `pointerInput`, `Scaffold`, and every dropped parameter becomes an ERROR overload | `…/stubs/ui/layout/Generated.kt:10`, `…/stubs/ui/input/pointer/Generated.kt:8`, `…/material3/Generated.kt:507`; type messages in `gatea.json` (`"typeMessages"`) | Generic stub for every non-generated public name; Android locals, resources, `AndroidView` (MS4-07) |
| Slots: optional, several, with parameters | **Several named slots with defaults: yes** (`TopAppBar`'s `navigationIcon`, `actions`: `kc-gatea-schema/src/main/kotlin/dev/keliver/compose/gatea/Generated.kt:430`). **Nullable optional slots (design §9's "present" flag): no. With parameters: no** (`Scaffold` is an ERROR stub; `TopAppBar`'s `scrollBehavior` and `windowInsets` dropped, `…/material3/Generated.kt:494`) | as left | MS4-09 |
| App components, opt-in | **None** (design §14 only) | `docs/COMPOSE_GENERATOR_DESIGN.md` §14, "app components (100+)" | MS4-11 (Q8) |
| Compatibility gate (extends D2) | **Not for the generated API.** Lock levels exist and are tested against an old host; B4's `bridges` and NF2's `engine` gates are the pattern | `kc-gatea-composeui/src/jvmTest/…/HostCompatibilityTest.kt`; relay `portal-relay/src/main/kotlin/StaticIndex.kt:98` (`signedBridges`); hosts `scripts/templates/production-host/src/main/kotlin/BundleIndex.kt:84` (`KNOWN_CONSTRAINTS`), `:387` (`admits`) | `keliver.compose` in the signed manifest, `constraints.compose`, host skip and refusal (MS4-23, Q5) |
| Legacy schema and migration tool | Legacy `keliver-material` **stays**, as a required dependency of the prototype root; **no migration tool** | `kc-gatea-composeui/…/WidgetSystem.kt:27-34` (needs material, layout, lazy layout) | Legacy optional (MS4-03); migration tool (MS4-31) |
| Widget groups | **None**: one schema, dependency tags 1–3 | `gatea.json:5-9` | Q1, MS4-01, MS4-03 |
| Same-source corpus | **Started**: 4 guest/native pairs (orders, card, item detail, animated panel) plus a guest-only long list for the lazy tests; JVM and iOS simulator, 0 differing pixels | `kc-gatea-composeui/src/sameSourceTest/…/SameSourceTest.kt`, `…/samples/{guest,native}/` | Harness at scale plus coverage (MS4-05), samples with every coverage PR |
| Editor (ED0) | **Spike behind a flag**: ingest 0 RawCode, byte-stable write-back, canvas library (not in `portal-editor`) | [`ED0_SPIKE.md`](ED0_SPIKE.md) §1, §4 | MS4-29, MS4-30 |
| Tests today | 149 `@Test` annotations (counted in source) across the prototype modules: generator 24, values 28, guest 16, host 72 (incl. same-source 8 and lazy scroll 12, JVM and iOS), profile 9 | CI runs them at `.github/workflows/ci.yml:153-158` and `:289` | Carried by MS4-01 under the new names |

## 2. The pull requests

Sizes are working days for one agent, review included, CI excluded. **Lane B1** is the generator
and codegen; **lane B2** is runtime, values, host, editor and bridges. Track B runs one agent; when
NF5 is in design review (docs only), Track A takes the PRs marked **A?** so the two lanes run at
once (section 2.2).

### 2.1 Milestones and their PRs

**MS4a. Productise (no new API; the wire may still change).** Exit: the prototype's tests pass under
the real module names, with no prototype flag.

| PR | Scope | Days | Gate / test | Needs | Lane |
|---|---|---|---|---|---|
| MS4-01 | Group layout and module names (Q1): generated root schema with legacy `keliver-material` as an optional dependency group; module set renamed from `gatea`; groups per design §14 (Q1's answer) | 3 | `checkComposeApi`, `SameSourceTest` and `HostCompatibilityTest` counts unchanged; `redwoodApiCheck` | Q1 | B1 |
| MS4-02 | Move the NF1 host pieces (`LazyRuntime`, placeholders, retained host back, one state per widget, press timing) from `keliver-compose-gatea-composeui` into the real host module verbatim | 2 | `LazyScrollTest`, `RetainedHostBackTest`, `OneStatePerWidgetTest`, `PressHostTest` pass unchanged on JVM and iOS sim | MS4-01 | B2 |
| MS4-03 | Absent groups: `keliver-tooling-codegen`'s host factory accepts a missing dependency group; its widgets report as unknown (#244 decision 3) | 3 | A host built without legacy and coil renders a generated-only tree; unknown tags reported once; release APK without legacy renderers measured | MS4-01 | B1 |
| MS4-04 | `schema-mismatch` reports instead of silent drops (design §12, "inside the gate"): library `EventListener` hook plus template wiring | 2 | Unknown widget/modifier/property reported once per tag; relay `/report` accepts the reason | MS4-03 | B2, A? |
| MS4-05 | Same-source corpus harness at scale: one sample file per widget family, coverage report (every lock entry used by at least one sample), shared by jvmTest and iosTest | 3 | Report lists 0 uncovered entries for the current lock; 0 differing pixels | MS4-01 | B2 |
| MS4-06 | Fresh product locks at level 1 (Q2); the prototype lock retired with its fixture tests | 1 | `checkAppendOnly` against the new lock; generator golden tests | Q2, MS4-01 | B1 |

**MS4b. The generator complete.** Exit: every WS2 generator output exists for the groups of Q1.

| PR | Scope | Days | Gate / test | Needs | Lane |
|---|---|---|---|---|---|
| MS4-07 | Include lists by package plus a **generic error stub** for every public declaration of an included package that is not generated (design §11, §19); curated messages for `Layout`, `SubcomposeLayout`, `Modifier.layout`, `pointerInput`, `detect*Gestures`, Android locals, resources, `AndroidView` | 4 | A compile-fail test per curated stub (message text asserted); no "unresolved reference" for any public name in an included package (scan test) | MS4-06 | B1 |
| MS4-08 | Default case 3 (presence split, design §5) and nullable-modifier parameters (§19) | 2 | Generator golden tests; same-source sample per case | MS4-06 | B1 |
| MS4-09 | Nullable optional slots (the "present" flag, needed by `ListItem`) and slots with parameters (§9): host-measured values as events (`Scaffold`'s `PaddingValues`, `BoxWithConstraints`), guest-data slots (`AnimatedContent`), placeholder children (`decorationBox`) | 4 | Same-source samples: Scaffold with top bar and FAB; BoxWithConstraints; 0 differing pixels | MS4-07 | B1 |
| MS4-10 | Editor metadata for every group (kinds, defaults as text, KDoc, slots, opt-ins) and `portal-schema-codegen`'s `MappedKind` additions | 2 | `checkPortalCode` fresh; catalog snapshot test | MS4-07 | B1 |
| MS4-11 | App components, opt-in (Q8): an annotation on an app's multiplatform composable; the generator as a `keliver-gradle-plugin` task in the app build, its lock in the app repo, group tags from 100 | 6 | A fixture app exports two components (one with a slot); same-source pixels; append-only check in the app build; a non-multiplatform component refused with a reason | MS4-10, Q8 | B1 |

**MS4c. Coverage, values and per-frame APIs.** Exit: the groups of Q1 generated at the scope of
section 1; corpus coverage 100% of lock entries.

| PR | Scope | Days | Gate / test | Needs | Lane |
|---|---|---|---|---|---|
| MS4-12 | foundation-layout and foundation complete (`FlowRow`/`FlowColumn`, lazy grids, `HorizontalPager`/`VerticalPager`, `BasicText`, selection/toggle modifiers, `scroll`) | 4 | Corpus samples per family; lock append test | MS4-07 | B1 |
| MS4-13 | Codecs left (design §6): full `TextStyle`/`SpanStyle`, `AnnotatedString`, `Brush` kinds, `FontFamily` (host-registered names), path-built `ImageVector`, `Painter` kinds, `BorderStroke` factories | 4 | Round-trip tests (guest encode, host decode) on jvm, js, wasm, iOS; corpus samples | MS4-06 | B2 |
| MS4-14 | Theme snapshot for guest reads (§7) plus the generated groups in #290's host baseline profile | 3 | Guest reading `colorScheme.primary.luminance()` sees the host's value; dark-mode switch recomposes nothing in the guest; profile rules list the new packages | MS4-13 | B2 |
| MS4-15 | Host-owned state objects: `ScrollState` commands, `PagerState`, `SheetState`, `TextFieldState`, interaction sources (as `LazyListState` today) | 5 | Commands and fields round-trip; process-death restore test as for `LazyListState`; corpus samples | MS4-12 | B2 |
| MS4-16 | material3, part 1: text, buttons (all kinds), surfaces, cards, chips, list items, dividers, badges, progress indicators | 4 | Corpus samples; lock append | MS4-09, MS4-13 | B1 |
| MS4-17 | material3, part 2: inputs (`TextField`/`OutlinedTextField` on `TextFieldState`, checkbox, switch, radio, slider, segmented buttons) | 5 | Corpus samples; typing round-trip on the emulator | MS4-15, MS4-16 | B1 |
| MS4-18 | material3, part 3: containers and navigation (`Scaffold`, top app bars, navigation bar/rail/drawer, tabs, `ModalBottomSheet`, dialogs, snackbar host, menus, date and time pickers); top-app-bar scroll behaviours (host nested scrolling) and window insets | 6 | Corpus samples; `SheetState` command test | MS4-17 | B1 |
| MS4-19 | Icons: `icons-core` group, `icons-extended` opt-in with an app allowlist | 2 | Bundle size with and without extended; unknown icon gives a placeholder and one report | MS4-01 | B1, A? |
| MS4-20 | Animation complete (§10): `AnimatedContent`, `Crossfade`, `updateTransition`, `rememberInfiniteTransition`, `keyframes`/`repeatable`/`infiniteRepeatable`, `animateItem` specs, animated references on widget parameters; guest reads per Q3 | 5 | Frame-by-frame same-source tests on JVM and iOS sim; 0 change batches while host animations run | MS4-09, Q3 | B2 |
| MS4-21 | Guest-clock `Animatable` behind an opt-in at ERROR level (Q4); untracked animated values (`1f - alpha`) reported to the mismatch channel in debug builds | 1 | Compile-fail test without the opt-in; debug report test | Q4 | B2, A? |
| MS4-22 | `Canvas`, `drawBehind`, `drawWithContent`, `graphicsLayer {}`: commands recorded in the guest, replayed on the host; `size` from the host after layout (Q7) | 7 | Same-source pixels for paths, arcs, text, gradients; a layer-phase read in `graphicsLayer` is an error stub | MS4-13, Q7 | B2 |

**MS4d. Gate, bridges and MS6's library pieces.** Exit: D2 covers generated groups; WS3's library
half done.

| PR | Scope | Days | Gate / test | Needs | Lane |
|---|---|---|---|---|---|
| MS4-23 | Compatibility gate (design §12, Q5): `keliver.compose` written into the signed manifest from the guest's generated `ComposeApi` constants; `keliver-publish` derives `constraints.compose`; both host templates skip at lookup and refuse at load (`compose-missing`), on the paths `bridges` takes. The manifest half is library (Gradle plugin); the host and publish halves reach adopters with MS5's tools release | 5 | Relay and template tests (as `BridgeGateTest`); a reference-app scenario on emulator and simulator: a bundle needing a higher level is skipped, a forged manifest refused | MS4-06, Q5 | B2 |
| MS4-24 | B5c, library half: `keliver-bridge-annotations` (and what the editor compiles) gains wasmJs | 1 | `wasmJsTest`; apiDump of the new klib target | none | A? |
| MS4-25 | B5b on the iOS simulator (shared source set with `iosTest`) | 3 | The round trip passes on `iosSimulatorArm64Test` | none | A? |
| MS4-26 | `keliver.bridges` written from the generated constants (Gradle plugin), as `keliver.compose` is | 2 | Plugin functional test; manifest carries the list; no hand-written list needed | MS4-23 | B2 |
| MS4-27 | `keliver-nav` on wasmJs (ED2's prerequisite) | 2 | `keliver-nav:wasmJsTest`; apiDump | none | **A** (owns `keliver-nav`) |
| MS4-28 | Generated `@ScreenState` registry (a device opens a state; #233) | 4 | Registry generated for the sample; a JVM test opens each state with its fixtures | MS4-27 | A? |

**MS4e. Editor, migration and proof.** Exit: every candidate check in section 3 runs.

| PR | Scope | Days | Gate / test | Needs | Lane |
|---|---|---|---|---|---|
| MS4-29 | ED0 ingest on by default for generated groups; the spike's `for`-as-Repeat reading removed (the D6 amendment excludes it); the relay serves the generated catalog; tools classpath carries the values module | 4 | D14 on a real screen; `ParityGoldenTest`; 0 RawCode on the corpus within the supported subset; `ShippedClasspathTest` updated | MS4-10 | B2 |
| MS4-30 | The canvas wired into `portal-editor` (a guest composition in the browser feeding host `ScopedChildren`, inspector reading the metadata's kinds); lazy lists on the canvas | 8 | Protocol equality canvas against device for every corpus sample (wasm pixels cannot be captured, U41); editor E2E suite | MS4-29 | B2 |
| MS4-31 | Migration tool, legacy to generated (`Background(colorArgb)` to `Modifier.background(Color(…))`, …), a tools command built on `portal-ingest`'s parse | 6 | Converts `sample/` and `reference/inventory` screens; each converted screen compiles, ingests with 0 RawCode and matches its old screenshot within the legacy-to-Compose tolerance the PR records | MS4-18 | B1 |
| MS4-32 | The reference app ported (via MS4-31, then by hand where needed), on the PR route against this checkout's libraries in a disposable local Maven repository (the harness gains that route, as B5c's self-tests do; today the PR route takes the libraries from Maven Central) | 4 | `reference-app.yml` (emulator) and `ios-host.yml` (simulator) green, T0–T4 and S17* included | MS4-31, MS4-23 | B1 |
| MS4-33 | Golden screens: every corpus sample and every reference-app named state, on the Android emulator (`google_apis`), the iOS simulator and the web canvas (protocol equality) | 4 | Goldens checked in; a deliberate one-pixel change fails | MS4-30, MS4-32 | B2 |
| MS4-34 | `llms.txt` and the agent guide generated from the catalog, with a staleness guard; docs for the generated packages; the 0.5.0 notes for a new adopter | 3 | Freshness check in CI; an agent writes a screen from `llms.txt` alone and it compiles (Gate B's agent test, repeated) | MS4-18 | B1 |

**MS4f. The candidate.** A branch with `KELIVER_VERSION = 0.5.0`, the lock frozen, section 3's
checks run and written up; then the owner's go (MS5 publishes). 2 days plus device sessions.

**Totals:** about 126 agent-days (MS4a 14, MS4b 18, MS4c 46, MS4d 17, MS4e 29, MS4f 2). With two
lanes from MS4b on (Track B's agent on B1, B2 shared with Track A while NF5 is in design or waiting
on the phone), about **11–13 weeks** of wall time; one lane only, about 25 weeks. The critical path
is MS4-01 → 06 → 07 → 12 → 15 → 17 → 18 → 31 → 32 → 33 → MS4f (about 44 days).

### 2.2 Two tracks: where NF5 and MS4 meet

NF5 (Track A) starts with `docs/SHOW_BEFORE_COMPUTE.md` (docs only), then code in two halves: cold
start (the host draws the guest's last screen from storage) and open (destinations composed ahead
of the tap).

| Shared area | NF5 touches | MS4 touches | How to avoid conflicts |
|---|---|---|---|
| `keliver-nav` | Open half: composing likely destinations ahead (extends `KeliverNavPreparation`) | MS4-27 (wasmJs), MS4-28 (registry) | **Track A owns `keliver-nav`**: it takes MS4-27 and MS4-28 itself, during NF5's design review, so no MS4 PR edits `keliver-nav` while NF5 codes |
| `keliver-treehouse-host` and the host templates (`scripts/templates/*/BundleIndex.kt`, `HostConfig`) | Cold-start half: storing and drawing the last screen; a start path change | MS4-04 (mismatch reports), MS4-23 (compose gate in both templates' lookup and load) | MS4-23 lands **before** NF5's cold-start code, or after it, never in parallel; the gate's changes stay in `BundleIndex.kt`'s constraint check and the manifest check, and NF5's in the start path. Each names the other's PR in its body |
| The protocol and the generated host module | The stored screen is a protocol change list replayed on the host: it depends on the wire | MS4-01, MS4-06 change module names and tags until the lock freezes | NF5's code starts on MS4a's module names (after MS4-06). The stored screen is keyed by the bundle's manifest hash and the group levels, so a lock change can never replay a stale tree; NF5's design names this invariant |
| `keliver-compose-gatea-composeui` (prototype) | Any NF5 prototype on the host renderers | MS4-01/02 rename and move it | No NF5 code in `gatea-*` after MS4-01 is open; MS4-02 is a verbatim move, so NF5 rebases its design's file references only |
| API dumps (`keliver-nav.api`, `keliver-treehouse-host.api`, klib dumps) | New experimental API | New API | Merge `origin/main`, then run `apiDump`; never hand-merge a dump |
| Phone sessions | A12 gates for each half | The candidate's A12 and iPhone runs | One session at a time (PD7); MS4f's runs share NF5's next scheduled session |

## 3. The 0.5.0 candidate: "passes every check"

All on one commit with `KELIVER_VERSION = 0.5.0`, written up in one candidate report:

| # | Check | Pass when |
|---|---|---|
| C1 | Full graph `check` and CI's API step (`apiCheck`, `ziplineApiCheck`, `redwoodApiCheck`, `spotlessCheck`, `validatePlugins`) on hosted macOS, plus the nightly full build (#306) on that commit | Green |
| C2 | API dumps | Every new published module has JVM and klib dumps; the diff against 0.4.1 is summarised (declarations added and removed per module) and reviewed; no `gatea` name remains in any dump |
| C3 | Generated code fresh | `checkComposeApi`, `checkPortalCode`, `checkAppendOnly` against the **frozen** lock, `llms.txt` freshness: all up to date |
| C4 | Same-source corpus | Every lock entry used by at least one sample; `onlyImportsDiffer` for every pair; 0 differing pixels on the JVM and the iOS simulator, animations frame by frame |
| C5 | Golden screens | Corpus and reference-app states match on the Android emulator and the iOS simulator; the web canvas is protocol-equal to the device |
| C6 | Editor (D14) | Every reference-app screen and every corpus sample within the supported subset: compile, 0 RawCode, device screenshot, byte-stable write-back; `ParityGoldenTest`; the editor E2E suite |
| C7 | Reference app ported | `reference-app.yml` and `ios-host.yml` green on the PR route against the candidate in a local Maven repository, T0–T4, S17–S17d and MS4-23's gate scenario included; every bundle names `quickjs-2026` |
| C8 | Adopter path | `publishToMavenLocal -PkeliverVersion=0.5.0 -DRELEASE_SIGNING_ENABLED=false`; a new app built from this checkout's scaffolders against that repository builds, publishes and renders on the emulator and the simulator |
| C9 | Size | Release APK and IPA of the reference app, and its bundle, against 0.4.1's: the deltas written down, within Q11's budget; with and without the legacy group |
| C10 | Compile time | Guest compile, Android host build and iOS host link times against 0.4.1's, written down |
| C11 | Speed on phones (PD7, batched) | A12: open, back, cold start and scrolling no worse than the 2026-11-25 re-measure on the same harness; iPhone 13: first frame and pixels (`keliver-gateb-ios-device.sh`), if signing is available |
| C12 | `llms.txt` | Generated, fresh, and an agent's screen written from it alone compiles and renders |
| C13 | Docs | `docs-check` clean; CHANGELOG's 0.5.0 section complete, written for a new adopter (the owner's 2026-11-23 rule) |

**What "0.5.0 go" needs from the owner:** read the candidate report (C1–C13); agree the frozen lock
(the wire committed to, section 5's last risk) and C2's API summary; a phone session for C11 (the
A12; the iPhone with an Apple account in Xcode); then the go for `publish-maven-central.yml` at
`v0.5.0` (publishing stays on the owner's explicit go; the owner chose on 2026-11-22 not to add a
GitHub Environment for it now). MS5's tools release
follows (B5c's flags, the scaffolders switching to the fork for 0.5.0 apps).

## 4. Gate B's open sub-decisions (#243–#246)

| PR | Question | Options | Recommendation and evidence | Blocks MS4-01? |
|---|---|---|---|---|
| #243 | The iOS same-source pixel test costs about 15.5 min of CI's Build job (14.7 min linking the test binary) | (a) keep on every PR; (b) only when generated-API paths change, plus nightly; (c) nightly or manual only | **(b)**: the corpus grows with MS4c, and so does the link; the nightly full build (#306) catches what the filter misses; the simulator app script (`keliver-gateb-ios-sim.sh`) runs by hand for the candidate (C4) on `keliver-mac`. "+89 ms on the simulator" was settled by Gate B's confirmation | No (Q6) |
| #244 (1) | `animate*AsState` on the host (animated references) or on the guest's frame clock | host; guest | **Host.** Emulator: ~20 against ~330 changes/s, the guest clock ~29 values/s against ~55 host frames; A12 (#245): the guest clock **2.8 values/s**, so it steps; host references pixel-identical to native frame by frame on JVM and iOS sim ([`GATE_B_RESULTS.md`](GATE_B_RESULTS.md)) | No |
| #244 (2) | How a guest reads an animated value | tagging (built): reads are off by ULPs, `== target` false, `0f` reads `Float.MIN_VALUE`; read log (not built): exact reads, misses a value read early and kept | **Read log** (Q3): tagging turns guest logic wrong silently (`if (alpha == 0f)` differs from Compose, against bar 1); a read log's failure is a missing animation, visible, and a debug-build check can report unconsumed log entries. The wire (`ExtraAnimated`) is the same either way, so the lock does not wait for it | No; blocks MS4-20 |
| #244 (3) | Host factory accepting absent groups | yes; no | **Yes** (MS4-03): R8 already removes 79% of Material 3 unaided; the measurable saving is the legacy renderers (92 classes plus the Material 3 and Coil code only they reach) and Coil (170 classes) | No; part of Q1 |
| #245 | How strongly to flag the guest-clock `Animatable` escape hatch | lint warning (design); opt-in at ERROR; error unless suppressed | **Opt-in at ERROR** (Q4): 2.8 values/s on the A12 is not an animation; a compile-time opt-in fits bar 5 and costs about a day | No |
| #246 | The iPhone pixel test (blocked at signing on 2026-11-14) | — | **Done that evening:** 0 differing pixels on three samples on an iPhone 13, first frame +142 ms warm, +820 ms empty cache. Left: the wrapper's UIKit link fix never ran on the device signing path; a personal team's 7-day profile recurs at every session (C11) | No |

## 5. Risks

| Risk | Evidence today | Mitigation | Fallback |
|---|---|---|---|
| Binary size: generated renderers and groups | +1.79 MB APK (+17.9%) with the route, bundle 1.95 MB of it; R8 removed 79% of Material 3; iOS app size not measured | Groups with absent-group support (MS4-03); legacy and Coil optional; `icons-extended` allowlisted; C9 measures APK, IPA and bundle | Ship more, smaller groups at 0.5.x (append-only: new groups, never moved widgets); a release-size report (WS6) |
| Compile and link time | The iOS test link alone is 14.7 min on a 3-core runner (#243) | Adopters consume published klibs (they do not compile our generated code); C10 measures the iOS host link | Split the generated host module per group so a host links only what it uses; Kotlin/Native release link caching on CI |
| Compose version coupling | The generator reads the CMP 1.12.2 and material3 1.9.0 jars; copied default expressions depend on them; `checkComposeApi` fails on a bump (design §20) | 0.5.x supports CMP 1.12.x only, declared in the Gradle metadata (Q9); a nightly job builds the groups against the next CMP; hidden overloads keep their tags across a bump (§3) | A Compose minor bump is a Keliver minor release with regenerated groups; an app on a newer Compose waits for it |
| The API surface committed to | The wire (lock tags) lives on devices for as long as bundles do; the guest stubs follow Compose; the mirrors and host-state commands are Keliver's own | Freeze only the lock at 0.5.0 (Q2); Keliver-only API (extras, app components, host-state commands, nav retention) behind `@Experimental…` opt-ins (Q10); mirrors' internals non-public | Pre-1.0 policy ([`API_STABILITY.md`](API_STABILITY.md)) allows breaking guest-source API in 0.x; the wire cannot break, so a mistake becomes a hidden entry with a new tag |
| Scope: "WS2 in full" is about 126 agent-days | Section 2 | Two lanes; coverage is config once each category exists (state objects, slot parameters, recording) | Q7: a late category (`Canvas` recording first) ships as an error stub naming an app component, and comes in 0.5.1 |
| NF5 and MS4 collide in the host | Section 2.2 | Area ownership, ordering MS4-23 against NF5's cold-start code | If NF5's design needs a protocol change, it lands in MS4a before the lock freezes |
| Old 0.4 bundles | One engine since NF2: every 0.5 bundle is `quickjs-2026`, and hosts take only their engine's bundles (#275) | None needed: the 0.4 wire is not served to 0.5 hosts, so MS4 may reset tags before the freeze | — |

## 6. Owner decisions needed

| # | Question | Recommendation | Cost |
|---|---|---|---|
| Q1 | Group layout: which groups, and which root? | Generated root with the legacy `keliver-material` an optional dependency; groups: compose core (foundation-layout, foundation, ui, material3, animation, extras), `icons-core`, `icons-extended` (opt-in), `coil` (optional) | Codegen change for absent groups (3 days); legacy tags move under an offset (moot: 0.4 bundles never reach 0.5 hosts) |
| Q2 | When does the lock become append-only for good? | Fresh product locks at level 1 now; free changes until the candidate; frozen at the `v0.5.0` tag | The prototype's lock fixtures are retired |
| Q3 | Guest reads of animated values: tagging or read log? | Read log, with a debug check for unconsumed reads | 3–4 days; tagging's tests rewritten |
| Q4 | Guest-clock `Animatable`: lint or opt-in at ERROR? | Opt-in at ERROR | 1 day; one annotation for its users |
| Q5 | Gate granularity: whole group level or levels actually used? | Whole level in 0.5.0 (simple, refuses more; no older hosts exist) | Coarser refusals once 0.5.x hosts age |
| Q6 | iOS same-source test in CI (#243) | Path-filtered on PRs, full nightly | A regression outside the filter shows the next morning |
| Q7 | If one per-frame category (`Canvas` recording) is late, does 0.5.0 wait? | Wait at most one week, then ship it as an error stub and add it in 0.5.1 | A guest that draws waits for 0.5.1 |
| Q8 | App components: generated in the adopter's build? | Yes, a `keliver-gradle-plugin` task with the lock in the app repo | 6 days; the plugin gains the generator's classpath |
| Q9 | Compose range for 0.5.x | CMP 1.12.x only, declared; the next CMP built nightly | Apps cannot move to CMP 1.13 before Keliver 0.6 |
| Q10 | Which generated API is stable in 0.5.0? | Compose mirrors stable; Keliver-only API experimental (opt-in) | Opt-in annotations in adopters' code |
| Q11 | Size budget for C9 | Reference app release APK at most +2.5 MB and bundle at most 2.5 MB against 0.4.1 | A budget that may force the group split earlier |
| Q12 | Track A takes `keliver-nav`'s MS4 PRs (MS4-27, MS4-28) and the **A?** PRs during NF5's design review? | Yes | NF5's code starts a few days later |
| Q13 | With no external adopters, build the migration tool (MS4-31) or port by hand? | Build it: it ports `sample/`, the reference app and MS7's 13 private screens, and its rules double as the agent guide's "legacy to Compose" table | 6 days |

Record and changelog: none from this note; each MS4 pull request carries its own blocks.
