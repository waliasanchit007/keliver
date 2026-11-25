# MS4 plan: the 0.5.0 build

**Status: draft, revision 4, 2026-11-26.** The delivery plan for MS4 of
[`PLAN_2026-11.md`](PLAN_2026-11.md) (section 8's MS4 row, section 9's Track B). It orders the work. It
decides nothing that [`DECISIONS.md`](DECISIONS.md) or the plan of record decide. Section 6 lists what
the owner still has to decide, what the owner has decided, and what the maintainer has decided. Records
stay in [`DELIVERY_PLAN.md`](DELIVERY_PLAN.md).

Revision 2 answered the first review (NF5 in 0.5.0, the lanes re-planned, the order where NF5 and MS4
meet, section 2.2). Revision 3 answered the re-review (who edits the generated configs and locks, the
prototype's lock and NF5's measurements, MS4-36's scope, staffing). **Revision 4** folds in:
- **the owner's answers of 2026-11-26** (section 6.2): Q1, Q2 and Q14 as recommended; three agents
  (Q16); per-frame lambdas by a host-side expression binding, with compile errors for what it cannot
  express (MS4-51); the stable Compose API that no PR covered, in 0.5.0 (MS4-44…MS4-50, provisional
  sizes); Compose 1.12's experimental FlexBox, Grid and Style as compile errors; no second Mac now; the
  engine settled;
- **the efficiency review of 2026-11-25** (its synthesis, revision 2, and its findings: eff-codegen,
  eff-samples, eff-throughput, eff-guest; the maintainer's working notes, cited by name below): PR
  #312's generic mechanisms as G0–G3, C1-lite, generated samples and Google's own samples as the
  corpus, the 14-kind slot classifier, Scaffold's padding as a host-resolved handle, the throughput
  levers, and three review notes. Each lands in a PR row of section 2.1, a lane and an estimate;
- **a recommendation on the legacy library** (Q17, section 6.1), for one owner confirmation;
- **the lanes and the calendar recomputed** from day 0 = 2026-11-26 (section 2.3), when H0, MS4-01 and
  MS4-35 started.

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
- **The owner's answers of 2026-11-26** (section 6.2), including the scope added to WS2 in full: the
  stable Compose API that no PR covered goes in 0.5.0.
- **The engine is settled** (the owner, 2026-11-26): QuickJS 2026 with BigInt, on our Zipline fork.
  MS4 has no engine work.
- **The toolchain on `main`:** the 0.5 line's host toolchain is merged (#299, `ae6147350`: AGP 9.3.3,
  Gradle 9.7.1).
- **Decisions:** D2 is extended, not replaced (WS2's gate). D1, D3, D4 and D14 are unchanged. NF5's D1
  clarification is NF5's (its section 10).

**IDs in this note:**
- milestones **MS4a–MS4f**;
- pull requests **MS4-01…MS4-52**. MS4-41…MS4-52 are new in revision 4. #312's mechanisms keep their
  spike names beside their numbers: **G0** is MS4-41, **G1** MS4-42, **G2** MS4-43, and **G3** is
  MS4-22, which it replaces;
- candidate checks **C1–C16**;
- owner questions **Q1–Q18**: Q1, Q2, Q14 and Q16 were decided on 2026-11-26; Q4, Q6 and Q12 are
  maintainer decisions; Q13 is a "confirm"; Q7 is contingent; Q17 (the legacy library) and Q18 (the
  calendar) are new;
- lanes **L1**, **L2** and **A**.

None of these is item 2's M0–M5 or item 4's B1–B5. NF5's steps keep NF5's names: H0, A1–A6, R0, R1 and
B0–B5. This note always writes them as "NF5's B1", and so on. The efficiency review's candidate ids
(C1-lite, C6, C7, U1–U4) are its own, not candidate checks.

## 0. What 0.5.0 carries, and what is left

| Item | State on `main` (`4f0e08d88`) | Left for 0.5.0 |
|---|---|---|
| Kotlin 2.4.21 | Merged (#303) | Nothing; re-proven by the candidate's checks (section 3) |
| Zipline fork `dev.keliver.zipline` 1.28.0-keliver.1, one engine `quickjs-2026` | Published 2026-11-24. Keliver is on it (#294, merged). The 0.5 line's host toolchain is merged too (#299 at `ae6147350`: AGP 9.3.3, Gradle 9.7.1; the hosts take Coil and Ktor from `keliver-image`). The engine is settled (the owner, 2026-11-26) | Nothing; re-proven by the candidate. No engine work |
| Compose-generated API (WS2) | A prototype behind `-PkeliverComposePrototype`, unpublished: one group `gatea`, with 29 widget, 36 modifier and 15 factory tags at lock level 5 (`keliver-compose-generator/gatea.lock.json`). **PR #312, a spike (unmerged, JVM only):** call records, remote objects and recorded draw scopes, each proven on real Compose 1.12.2 (`docs/COMPOSE_GENERIC_MECHANISMS.md` on its branch) | Most of MS4: section 1 |
| ED0 (the editor on the generated catalog) | A spike behind a flag (#239, [`ED0_SPIKE.md`](ED0_SPIKE.md)); the canvas is not wired into `portal-editor` | MS4e |
| Bridge modules (WS3) | `keliver-bridge`, `-annotations` and `-codegen` merged, never published; B5b on the JVM (#211) | B5c's library half (`keliver-bridge-annotations` gains wasmJs), B5b on the iOS simulator, `keliver.bridges` from generated constants (MS4d) |
| Item 2's M4 split | **Library half merged:** `KeliverTrace.composed()` (`keliver-treehouse-guest/src/commonMain/kotlin/dev/keliver/treehouse/KeliverTrace.kt:38`, called at `StandardAppLifecycle.kt:133`; #237) | The host templates' reading of it ships in the tools release after 0.5.0. One emulator run in the candidate (C16) |
| NF1's library changes | Merged as experimental in `keliver-nav`, `keliver-compose` and `keliver-treehouse*` (#259, #262, #265, #266, #271, #278). The host-side parts (lazy window prediction, placeholders, retained host back, one state per widget) live **only in the prototype** `keliver-compose-gatea-composeui` (#258, #265, #276, #280) | Copied into the real generated host module (MS4-02), which is also NF5's B0 prerequisite. The press signal stays off by default (owner, 2026-11-22) |
| NF4's library changes | `keliver-treehouse-host`'s baseline profile (#286); the opt-in plugin for generated host code (#290) | The generated groups' host code covered by #290's plugin (MS4-14) |
| MS6's library pieces | `@ScreenState`/`NamedState` in `keliver-nav` (#226), `keliver-http-fixtures` (#224), `keliver-http-replay-debug` (#232) | `keliver-nav` on wasmJs (ED2; its targets are js and jvm only, `TreehouseGuest` in `RedwoodBuildPlugin.kt:484`); a generated `@ScreenState` registry (#233 left it unbuilt) |
| **NF5 (A), the cached first frame: library changes** | **Design on `main`**, revision 6 ([`SHOW_BEFORE_COMPUTE.md`](SHOW_BEFORE_COMPUTE.md); #308 merged at `4f0e08d88`; owner's decisions 2026-11-24). H0 started on 2026-11-26. No production code yet | The library side, by NF5's PRs: <br>- `session()` and `CachedFrames(accounts = NONE)`, with `discardPrefetched()` deprecated (NF5's decision 3; A5); <br>- `FrameCacheTags` in `keliver-protocol`, with a lock test (A2); <br>- the frame-rule annotations in `keliver-schema` and `FrameRules` from `keliver-tooling-codegen` (A1); <br>- the fold, store and recorder in `keliver-treehouse-host` (A3a–c); <br>- the hooks in the legacy renderer modules (A4a); <br>- `CachedFrameContent` and `FrameHandOff` in `keliver-treehouse-host-composeui` (A4b). <br>The generated groups get the same rules and hooks in MS4-36 |
| **NF5 (B), pre-composed destinations: library changes** | Approved after fixes, with decision 7 = (b). R0 (the A12 floor trace) was scheduled for 2026-11-25; its reading is NF5's record. (B)'s PRs wait for it and for R1 | The library side, by NF5's PRs: <br>- `keliver-nav`'s behaviour changes (NF5's decision 5: held effects for every prepared entry, parked pops for declared kinds, per-visit show counts, loads at the show) and `KeliverNavKind(dataArrives)` (B2, B3); <br>- the container's `keliver.nav.retainedContainer@2` (B0, on MS4-02's production container); <br>- `keliver.nav.reveal@1` (B4) |
| **The legacy library**: `keliver-material` (9 modules: the 76-widget schema and its renderers) | Published at 0.4.1. The prototype's root requires it (`keliver-compose-gatea-composeui/…/WidgetSystem.kt:27-34`). The published editor modules depend on it (`portal-editor/build.gradle:29-35`, `portal-render/build.gradle:51-54`), and so do the reference app's screens | Under Q17's recommendation (section 6.1): **not published at 0.5.0** (0.4.1 stays on Maven Central); kept in the repository, unpublished, while the editor's own routes need it; removed by one PR after MS6's ED3, with a tag on the last commit that has it |

NF5's PRs are Track A's and are not counted in MS4's agent-days. Section 2 counts them as Track A's load
(this plan's estimates; NF5's design gives no sizes). MS4-36 (NF5's rules for the generated groups) and
the shared-area rules of section 2.2 are MS4's.

## 1. WS2: what exists against what it needs

Evidence is `path:line` on `main` at 7014fb08e. It was re-checked at `dc22ce504`: #299, #309 and #311
changed none of the cited files except `ci.yml`, whose lines are updated below. The efficiency review
re-read the generator's cited lines at `4f0e08d88` and found them unchanged; this revision's new rows
were read at `4f0e08d88`.

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
| Generator inputs: Compose's published API and sources | **Yes**, on the pinned catalog: Kotlin metadata plus ASM over the desktop jars, PSI over the `-sources.jar`s; CMP 1.12.2, material3 1.9.0, coil 3.6.3 | `gen/ApiReader.kt:42`, `gen/SourceReader.kt:40`, `gen/Main.kt:69` (`generate`); pins `gradle/libs.versions.toml:14,98,27` | **What** is generated is a hand list: 70 declarations in `kc-generator/gatea.json` (`"declarations"`, from line 10), plus 3 curated errors (line 95). Include by package (MS4-07), on #312's `GenericIndex`, which enumerates every public declaration with hidden-overload detection |
| Outputs: guest stubs, wire schema, host renderers, editor metadata | **Yes**, all four, checked in and staleness-guarded (`checkComposeApi`) | `gen/Emit.kt:98` (schema), `:172` (guest), `:429` (host), `:719` (`*Defaults` mirrors), `:874` (editor JSON), `:933` (canvas interpreter) | One group only (`gatea.json:2`). The product groups are generated beside it (MS4-01). Per-group outputs and tasks (G0, MS4-41): today a second group's run deletes the first group's `GeneratedDefaults.kt`, and every run writes the same editor interpreter file (#312 §3) |
| Lock file of tags, append-only | **Yes**: canonical-signature keys, per-parameter `wire` levels, `since`, refusal of any non-append | `kc-generator/gatea.lock.json` (level 5; 29 widgets, 36 modifiers, 15 factories); `gen/Lock.kt:29` (`Lock`), `:42` (`LockEntry`), `:177` (`checkAppendOnly`) | Fresh product locks, frozen at `v0.5.0` (MS4-06, Q2 decided), plus one shared `values` lock for call records, remote members and scope members (G1) |
| Value codecs | **Most**: `Dp`, `TextUnit`, `Color`, `Shape`s, `TextStyle`, font types, alignments, arrangements, `PaddingValues`, `BorderStroke`, `ColorFilter`, `Painter`, `ContentScale`, `Role`, animation specs, `*Defaults` call records | e.g. `values/ui/graphics/Color.kt:115` (references), `values/material3/GeneratedDefaults.kt`; host converters in `kc-gatea-composeui/…/Converters.kt` | **`Brush` is a placeholder** (`kc-gatea-compose/stubs/ui/graphics/Generated.kt:9`; `background(brush)` is an ERROR stub, `…/foundation/Generated.kt:30`). Also missing: `AnnotatedString`, path-built `ImageVector`, host-registered fonts. **Call records** (G1, MS4-42) replace the hand codecs: 124 value types from 574 producers (#312 §2). Left by hand (MS4-13): the arithmetic value classes with `Offset`, `Size` and `Rect` added, host-registered `FontFamily` names, `Painter` references |
| Theme references | **Yes**: `colorScheme`, `typography`, `shapes`, `LocalContentColor`, `LocalTextStyle` resolve on the host | `values/material3/MaterialTheme.kt` (guest), `values/ui/graphics/Color.kt:154` (`ColorRefs`), `Converters.kt:119` (`resolveColor`) | The theme snapshot for guest reads (design §7) is not built (MS4-14) |
| Host-owned state objects | **`LazyListState` only** (commands, viewport fields, `Saver`); `ScrollState` carries its initial offset only | `gen/Classify.kt:72` (`hostState`); `values/foundation/lazy/LazyListState.kt:59`; `values/foundation/ScrollState.kt:32` ("commands … are Gate B") | **Remote objects** (G2, MS4-43): 19 state classes, 92 observed fields, 24 awaited commands generated from metadata (#312 §2). Then config, links and restore tests: foundation's (MS4-15) and Material 3's (MS4-39). `LazyListState` and most of `TextFieldState` stay hand-written |
| Per-frame APIs | **Partly**: `AnimatedVisibility` (3 overloads), `animateContentSize`; `animate*AsState` as tagged host references; guest `Animatable` | `kc-gatea-compose/stubs/animation/Generated.kt`; `values/animation/core/AnimatedReferences.kt:267`; `values/animation/core/Animatable.kt:110` | `AnimatedContent`, `Crossfade`, transitions, `keyframes` (MS4-20). **`Canvas` recording: none** (`values/ui/graphics/drawscope/DrawScope.kt:22`, constants only): recorded draw scopes (G3, MS4-22). **Per-frame lambdas** (`progress: () -> Float`, `offset { }`, custom slider thumbs, swipe and pull backgrounds): the host-side expression binding (MS4-51) |
| Compile-error stubs | **The mechanism, yes**; the list is curated: `Layout`, `pointerInput`, `Scaffold`, and every dropped parameter becomes an ERROR overload | `…/stubs/ui/layout/Generated.kt:10`, `…/stubs/ui/input/pointer/Generated.kt:8`, `…/material3/Generated.kt:507`; type messages in `gatea.json` (`"typeMessages"`) | A generic stub for every non-generated public name; Android locals, resources, `AndroidView`; Compose 1.12's experimental FlexBox, Grid and Style; the 136 "never by design" of Google's samples (MS4-07) |
| Slots: optional, several, with parameters | **Several named slots with defaults: yes** (`TopAppBar`'s `navigationIcon` and `actions`: `kc-gatea-schema/src/main/kotlin/dev/keliver/compose/gatea/Generated.kt:430`). **Nullable optional slots (design §9's "present" flag): no. Slots with parameters: no** (`Scaffold` is an ERROR stub; `TopAppBar`'s `scrollBehavior` and `windowInsets` are dropped, `…/material3/Generated.kt:494`) | as left | MS4-09, on the 14-kind slot classifier (eff-codegen C7) |
| App components, opt-in | **None** (design §14 only) | `docs/COMPOSE_GENERATOR_DESIGN.md` §14, "app components (100+)" | MS4-11 (Q8, Q15) |
| Compatibility gate (extends D2) | **Not for the generated API.** Lock levels exist and are tested against an old host. B4's `bridges` and NF2's `engine` gates are the pattern | `kc-gatea-composeui/src/jvmTest/…/HostCompatibilityTest.kt`; relay `portal-relay/src/main/kotlin/StaticIndex.kt:98` (`signedBridges`); hosts `scripts/templates/production-host/src/main/kotlin/BundleIndex.kt:84` (`KNOWN_CONSTRAINTS`), `:387` (`admits`) | `keliver.compose` (group levels and the app components' level) in the signed manifest, `constraints.compose`, host skip and refusal (MS4-23, Q5) |
| Cached first frame (NF5 (A)) for generated screens | **None.** NF5's A1 and A4 cover the three legacy schemas only, and nothing is replayable by default (NF5 2.4), so until MS4-36 a generated screen never gets a frame | NF5 section 9, A1, A4a and A4b | Frame rules and host hooks for the generated groups (MS4-36) |
| Legacy schema and migration tool | Legacy `keliver-material` **stays**, as a required dependency of the prototype root. **No migration tool** | `kc-gatea-composeui/…/WidgetSystem.kt:27-34` (passes through material, layout and lazy layout); `kc-gatea-schema/build.gradle` (`api` on all three schemas) | The product root declares no legacy group (MS4-03); legacy not published at 0.5.0 (Q17, MS4-30); migration tool (MS4-31, Q13) |
| Widget groups | **None**: one schema, dependency tags 1–3 | `gatea.json:5-9` | Q1 and Q14 decided (2026-11-26); MS4-01, G0, MS4-03 |
| Same-source corpus | **Started**: 4 guest/native pairs (orders, card, item detail, animated panel), plus a guest-only long list for the lazy tests; JVM and iOS simulator, 0 differing pixels | `kc-gatea-composeui/src/sameSourceTest/…/SameSourceTest.kt`, `…/samples/{guest,native}/` | The harness at scale (MS4-05): samples generated per lock entry; **Google's own samples** (695; 41 compile as guest code today; eff-samples) as the realistic half, with a ratchet; a semantics oracle beside the pixel one |
| Stable Compose API that no PR covered | **None.** Measured by Google's samples: 71 samples need API in no PR (eff-samples §4); three groups found by the slot classifier (eff-codegen C7: inline content and links, per-frame patterns, shared elements) | eff-samples §4–5; eff-codegen C7 | **In 0.5.0** (owner, 2026-11-26): MS4-44…MS4-51, each sized after a short design note |
| Editor (ED0) | **A spike behind a flag**: ingest with 0 RawCode, byte-stable write-back, a canvas library (not in `portal-editor`) | [`ED0_SPIKE.md`](ED0_SPIKE.md) §1, §4 | MS4-29, MS4-30 |
| Tests today | 149 `@Test` annotations (counted in source) across the prototype modules: generator 24, values 28, guest 16, host 72 (including same-source 8 and lazy scroll 12, on JVM and iOS), profile 9 | CI runs them at `.github/workflows/ci.yml:153-160` and `:304` | Copied to the product modules by MS4-01 and MS4-02. The prototype's own tests are retired by MS4-40 |

## 2. The pull requests

Sizes are working days for one agent, review included, CI excluded. The quarter and half days come
from the efficiency review's per-PR deltas; they are no more precise than the whole days around them.

**Three lanes, three agents** (Q16, decided by the owner on 2026-11-26):
- **L1**: the generator and codegen. Track B's agent.
- **L2**: runtime, values, host, editor, harness and bridges. The dedicated second MS4 agent.
- **A**: Track A's agent. It runs NF5 from day 0, and takes five MS4 PRs after NF5's B5: MS4-20, -48,
  -51, -24 and -25.

**What the efficiency review changes in the shape** (#312 §3–4, eff-codegen C8):
- #312's three mechanisms are separate emitters, so **G1 runs on L1 while G2 and G3 run on L2**
  (days 11–19). After MS4-09 and G1–G3, a coverage PR is config, classification, samples and behaviour
  tests, so the families no longer wait on one another's generator features.
- **PR #312 itself is not merged.** G0 (MS4-41) takes its generator classes (`GenericIndex`,
  `GenericSurvey` and its test, `SourceReader.defaultsOf`) and its doc, `COMPOSE_GENERIC_MECHANISMS.md`.
  G1, G2 and G3 each move the spike's test for their mechanism (`CallRecordsTest`, `RemoteObjectTest`,
  `RecordedScopeTest`) into the product modules. The JVM-only module `keliver-compose-generic-spike` is
  not merged. #312 is closed when G0 merges, with a pointer to it.
- **Corpus samples are generated** (MS4-05), and Google's sample replaces each family's hand-written
  realistic one: about −0.25 day in each of nine family PRs (eff-samples §6).

### 2.1 Milestones and their PRs

**MS4a. Productise (no new API; the wire may still change).** Exit: the product modules carry the
prototype's tests under the real names, with no prototype flag. The prototype itself is retired later,
by MS4-40, after NF5's B1 closes (section 2.2).

| PR | Scope | Days | Gate / test | Needs | Lane |
|---|---|---|---|---|---|
| MS4-35 | **Reference-app harness against a local Maven repository.** On the PR route, this checkout's libraries are published to a disposable local Maven repository (never `~/.m2`), and the reference app's hosts and guest build against it. This is a third, labelled route beside published-only and repo-scaffold. Today the PR route takes the libraries from Maven Central, so no library change can be checked there. **Started on day 0** | 3 | `reference-app.yml` (emulator) and `ios-host.yml` (simulator) green on the new route, on a PR that changes a library: a marker in `keliver-treehouse-host` shows in the device log. The route is named in the results | none | L2 |
| MS4-01 | **The product groups generated beside the prototype** (Q1, Q14): the group configs, the generated root schema and the product modules under their real names. The generator and `keliver-compose-values` keep their names (Q15). **Every `keliver-compose-gatea-*` module and `sample/` stay where they are**, because NF5 measures with them. They are not frozen: generator and values changes regenerate and recompile them, and the prototype's lock may be reset (section 2.2). **Started on day 0** | 3 | `checkComposeApi` for both the prototype and the product groups. The product modules' copies of `SameSourceTest` and `HostCompatibilityTest` pass with the prototype's counts. `redwoodApiCheck` | Q1, Q14 (decided), #308 merged | L1 |
| MS4-52 | **The throughput levers** (eff-throughput §3–4), early: <br>- housekeeping: a scheduled clean-up of merged worktrees' build outputs and dead scratch trees, a free-disk alert at 15 GB (the lock's guard is 6 GB), and idle Gradle and Kotlin daemons stopped by an idle timeout in the agents' wrappers; <br>- one log line per heavy job in `heavy.sh` (waited, held, slot, memory, disk), which gives the second-Mac revisit its numbers (section 6.2); <br>- a half-day test of Kotlin/Native compiler caches (`kotlin.native.cacheKind=none` today, `gradle.properties:5-7`, probably behind the 14.7-minute iOS test link): the link timed before and after, and adopted by a one-line PR if it links. <br>The wrappers and `heavy.sh` live outside the repository; only the cache change, if adopted, is a PR. **Already in effect from 2026-11-26:** agents dispatch CI's hosted full graph on their branch instead of a local full build before each push; a generator lane's inner loop is the generator's tests plus the group it changes | 1.5 | The disk alert fires in a dry run; idle daemons are gone 10 minutes after a build; every heavy job leaves one log line; the cache test reports both link times | none | L1 |
| MS4-02 | **Copy** the NF1 host pieces (`LazyRuntime`, placeholders, retained host back, one state per widget, press timing) verbatim into the product host module. The prototype keeps its copy for NF5's measurements until MS4-40. Fixes go to the product copy. **This is the production container NF5's B0 needs** | 2 | `LazyScrollTest`, `RetainedHostBackTest`, `OneStatePerWidgetTest` and `PressHostTest` pass unchanged in the product module, on JVM and the iOS simulator | MS4-01 | L2 |
| MS4-03 | **Absent groups** (Q1): `keliver-tooling-codegen`'s host factory accepts a missing dependency group (`coil`, `icons-extended`), and its widgets report as unknown (#244 decision 3). **The product root declares no legacy group** (Q17): neither `keliver-material` nor its dependencies RedwoodLayout and RedwoodLazyLayout, whose widgets no generated code emits (`WidgetSystem.kt:27-34` only passes their factories through). The release APK and the iOS app are measured once, without legacy (Q11's baseline). If Q17 is answered (i), revision 3's scope instead: legacy as an optional group, measured with and without it (3 days) | 2.5 | A host built without Coil renders a generated-only tree, and unknown tags are reported once. The release APK and the iOS app measured | MS4-01, Q17 | L1 |
| MS4-04 | `schema-mismatch` reports instead of silent drops (design §12, "inside the gate"): a library `EventListener` hook, plus template wiring. The wiring builds on NF5's H0, which is the one place a template declares what it implements. It adds no second list | 2 | An unknown widget, modifier or property is reported once per tag, and the relay's `/report` accepts the reason. On MS4-35's route, an emulator run of a test bundle with one unknown tag gives one report | MS4-03, NF5's H0, MS4-35 | L2 |
| MS4-05 | **The same-source corpus at scale** (eff-codegen C6, eff-samples U1): <br>- **generated samples**: per lock entry, an all-defaults call and an all-explicit call with a representative argument per parameter kind (a call-record type takes its first replayable producer from #312's index); the native copy by the import rewrite; one parameterised oracle test, on the JVM per PR and the iOS simulator nightly (Q6). One hand-written realistic sample per family stays only where Google has none; <br>- **Google's own samples** as the realistic half: the AndroidX `@Sampled` functions of material3 1.4.0 and of foundation, foundation-layout, animation, animation-core and ui 1.12.1 (the versions the pinned CMP 1.12.2 and material3 1.9.0 require), 695 in all, **vendored** under Apache-2.0 with their headers kept, a NOTICE line and the folder exempt from Spotless; rewritten as the same-source test rewrites imports, with the Material 2 to Material 3 swap in both copies and `ui.util` exempt; an expected-status manifest as a **ratchet** (41 compile today; about 350 of the 612 that are not Android-only after revision 3's scope, and more with MS4-44…MS4-51). ≈0.25 day per Compose bump after; <br>- **a semantics oracle** beside the pixel one: the native and guest semantics trees compared (roles, descriptions, actions, state); <br>- the coverage report in **SkipUI's per-API table format** (one row per declaration with its support level: a review note). U4's diff of Compose's API dump against the stubs is optional | 4.75 | The report lists 0 uncovered entries for the current product lock; 0 differing pixels and 0 semantics differences; the ratchet fails on a sample that regresses and lists the newly compiling ones | MS4-01; G1 (MS4-42) for call-record arguments | L2 |
| MS4-06 | Fresh product locks at level 1 (Q2). The prototype's lock and its fixture tests stay until MS4-40 | 1 | `checkAppendOnly` against the new locks; the generator's golden tests | Q2 (decided), MS4-01 | L1 |
| MS4-40 | **Retire the prototype, after NF5's B1 closes.** Move NF5's measurement code (`CachedFrameReplayBenchmark`, `Nf5Stack.kt`, the guest profile, the three-way bench's prototype arm) onto the product modules. Delete `keliver-compose-gatea-*`, `gatea.json` and `gatea.lock.json`, and the prototype's CI steps. With it goes `sample/`'s only link to legacy (its Android host takes `keliver-material-composeui` for the prototype's root, `sample/host-android/build.gradle.kts:165`). Timed with Track A: after NF5's A6, before NF5's B5 | 3 | The guest profile and the three-way bench's arms (R1's, A6's) build and run on the emulator with the same outputs. No `gatea` remains in `settings.gradle`, CI or any dump. The next A12 session re-baselines `x=retainhost`, and NF5's B5 compares within that session | NF5's B1 and A6, MS4-02, MS4-05, MS4-06 | L2 |

**MS4b. The generator complete, with #312's mechanisms.** Exit: every WS2 generator output exists for
the groups of Q1, and G0–G2 are in the product.

| PR | Scope | Days | Gate / test | Needs | Lane |
|---|---|---|---|---|---|
| MS4-41 | **G0: per-group outputs and tasks** (#312 §3): `GeneratedDefaults_<group>.kt`, with the stale check limited to the group's own files; one editor interpreter file per group plus a generated dispatcher; `generateComposeApi` and `checkComposeApi` per group. Takes #312's generator classes and its doc (above) | 2 | Two groups generated in either order give the same files; each group's staleness guard runs alone | MS4-01 | L1 |
| MS4-07 | Include lists by package, plus a **generic error stub** for every public declaration of an included package that is not generated (design §11, §19), on #312's `GenericIndex`. Curated messages for `Layout`, `SubcomposeLayout`, `Modifier.layout`, `pointerInput`, `detect*Gestures`, Android locals, resources, `AndroidView`. **Compose 1.12's experimental FlexBox, Grid and Style are error stubs that name them** (the owner, 2026-11-26), generated in a 0.5.x once Compose stabilises them (additive under the append-only lock). **The 136 "never by design"** (custom layouts, raw pointer input, `Modifier.Node`, synchronous host reads) stay errors that name the alternative. Text selection state and custom `Indication`, which are not in the owner's list, stay errors with their messages. Keliver-only API is generated behind the Q10 opt-ins | 3 | A compile-fail test per curated stub, with the message text asserted. No "unresolved reference" for any public name in an included package (scan test). Google's samples in the never and experimental classes (136 and 53) fail with a curated message | MS4-06 | L1 |
| MS4-42 | **G1: call records** (#312 §1.1) in the product: a value built in the guest crosses as the call that built it (`{"call":…,"args":…}`), and the host's generated replay makes the same call. The shared **`values` lock** (producers, remote members, scope members; keyed by Compose declaration); receiver call records (`typography.titleMedium.copy(…)`, `fadeIn() + slideIn()`); readable constructor-backed properties (design §5 case 1); vararg producers; lock tags instead of string ids; editor kinds. Replaces revision 3's hand codecs in MS4-13: 124 value types from 574 producers. Guest subclasses of open types stay error stubs. **The prototype's lock is reset if a call record changes an existing entry's wire** (section 2.2) | 5 | `CallRecordsTest`, moved from #312 and extended: each replayed value equals the value Compose builds from the same source text, on jvm, js, wasm and the iOS simulator | MS4-41, MS4-06 | L1 |
| MS4-43 | **G2: remote objects** (#312 §1.2): a host-owned state class's guest proxy and host dispatcher, generated from its metadata: observed fields reported at most once a frame from their first read, awaited and unawaited commands, the `remember*` factory with its `Saver` and process-death restore, the state bound to its widget as a property with an event back, dirty observation, command results. Covers 19 state classes, 92 observed fields and 24 awaited commands; 44 members (synchronous returns, lambdas, types that do not cross) are error stubs | 4 | `RemoteObjectTest`, moved from #312: `PagerState` driven through the real protocol (not an in-memory channel); a restore test | MS4-41 | L2 |
| MS4-08 | Default case 3 (presence split, design §5) and nullable-modifier parameters (§19), on #312's `hostCall` presence split. The split changes the wire of existing entries, so the prototype is regenerated with a **reset lock** (section 2.2's prototype rule) | 2 | The generator's golden tests; a same-source sample per case; the prototype's tests green on the reset lock | MS4-06 | L1 |
| MS4-09 | Nullable optional slots (the "present" flag) and slots with parameters (§9), **on the 14-kind slot classifier** (eff-codegen C7): every function-typed parameter is classified from its type (K1–K14), with a generated report of every function-typed parameter by kind (extending `GenericSurveyTest`). It builds K2–K8, K11 (host-to-guest event arguments: the reverse decoder) and K12 producers. **K7 and K12 parameters that a guest would recompute every frame** (`progress: () -> Float`, `offset { }`, custom thumbs) **are error stubs naming the declarative alternative until MS4-51 binds them** (the owner, 2026-11-26); K13 (synchronous answers) and K14 (per-pointer-event code) stay errors by design. **Scaffold's first frame (U3):** `innerPadding` crosses as a host-resolved handle, so `Modifier.padding(innerPadding)` and `contentPadding = innerPadding` resolve on the host with no prediction and no round trip; only arithmetic on the values waits for the host's report (fallback: predicted padding corrected by the report). **It generates what its own tests use:** <br>- `Scaffold` (top bar, FAB slot, content with `PaddingValues`); <br>- `FloatingActionButton`; <br>- `BoxWithConstraints`; <br>- `AnimatedContent` (one overload, default transition); <br>- `ListItem`; <br>- `BasicTextField`'s value overload with `decorationBox`. <br>MS4-16, MS4-18 and MS4-20 complete those families. The "present" flag changes the wire of existing slot entries, so the prototype is regenerated with a **reset lock** (section 2.2) | 7 | Same-source samples, 0 differing pixels: `Scaffold` with a top bar and a FAB, with no extra round trip before its content for `padding(innerPadding)`; `BoxWithConstraints`; `ListItem` with and without its optional slots; a decorated field. The report classifies every function-typed parameter. The prototype's tests green on the reset lock | MS4-07 | L1 |
| MS4-10 | Editor metadata for every group (kinds, defaults as text, KDoc, slots, opt-ins), and `portal-schema-codegen`'s `MappedKind` additions | 2 | `checkPortalCode` fresh; a catalog snapshot test | MS4-07 | L1 |
| MS4-11 | App components, opt-in (Q8): an annotation on an app's multiplatform composable. The generator runs as a `keliver-gradle-plugin` task in the app's build, with its lock in the app's repository and group tags from 100 | 6 | A fixture app exports two components (one with a slot); same-source pixels; the append-only check in the app's build; a non-multiplatform component refused with a reason | MS4-10, Q8, Q15 | L1 |

**MS4c. Coverage, values and per-frame APIs.** Exit: the groups of Q1 are generated at the scope of
section 1 and of the owner's list of 2026-11-26, and the corpus covers 100% of lock entries. **Kuikly's
coverage list** was read as a cross-check of the Material 3 family order (a review note): MS4-16…18
already follow it.

| PR | Scope | Days | Gate / test | Needs | Lane |
|---|---|---|---|---|---|
| MS4-12 | foundation-layout and foundation **without host state**: `FlowRow`/`FlowColumn`, `BasicText`, selection and toggle modifiers, scroll modifiers on today's `ScrollState`. **Widened** (U1; Google's samples ranked these missing names highest): `wrapContentSize`/`-Width`/`-Height`, `requiredHeight` and the `required*` family, `aspectRatio`, `IntrinsicSize`, `focusable` | 1.5 | Generated corpus samples; Google's samples that need only these names compile and match; the lock append test | MS4-07 | L1 |
| MS4-13 | **The value kinds G1 leaves by hand:** host-registered `FontFamily` names, `Painter` resource references, and **`Offset`, `Size` and `Rect`** (U1: 39 and 14 of Google's samples need the first two). **C1-lite, here only:** these three come from the real ui-geometry (with ui-unit and ui-util) in the guest instead of new mirrors (+30 KB of bundle, about +7 ms of load on the A12, estimated; eff-guest), if the wire and theme tests pass. Moving the existing `Dp` and `TextUnit` mirrors to the real ui-unit happens only if it costs nothing more (alone it is +1.5–2 days); otherwise it is skipped | 2 | Round-trip tests (guest encode, host decode) on jvm, js, wasm and iOS; the bundle-size change recorded | MS4-42 | L2 |
| MS4-14 | The theme snapshot for guest reads (§7), plus the generated groups in #290's host baseline profile | 3 | A guest reading `colorScheme.primary.luminance()` sees the host's value. A dark-mode switch recomposes nothing in the guest. The profile rules list the new packages | MS4-13 | L2 |
| MS4-15 | **Foundation's host-owned state objects**, now G2 config: `ScrollState` commands, `LazyGridState`, `LazyStaggeredGridState`, `PagerState`; the `TextFieldState` link with `BasicTextField(state)` (largely hand-written: editing is a lambda) and interaction sources | 2.25 | Per object: the commands and fields round-trip (guest to host, host to guest), with a host fake for those whose widgets come in MS4-38; a process-death restore test. `ScrollState` and `TextFieldState` on corpus samples | MS4-43, MS4-07 | L2 |
| MS4-38 | Lazy grids (`LazyVerticalGrid`, `LazyHorizontalGrid`), staggered grids, and `HorizontalPager`/`VerticalPager`, on MS4-15's states. NF1's lazy-window prediction for grids | 2.75 | Corpus samples; `LazyScrollTest`'s cases for grids; same-source swipe frames for the pagers | MS4-02 (NF1's lazy-window prediction lives in the copied `LazyRuntime`), MS4-12, MS4-15 | L1 |
| MS4-39 | **Material 3's host-owned state objects**, as G2 config: `SheetState`, `DrawerState`, `SnackbarHostState`, `DatePickerState`, `TimePickerState`, `TopAppBarState` (with the scroll behaviours' nested-scroll connection, hand-written), `TooltipState`, `SwipeToDismissBoxState` | 1.75 | Per object: the commands round-trip and a process-death restore test, with host fakes (their widgets come in MS4-18) | MS4-15 | L2 |
| MS4-16 | Material 3, part 1: text, buttons (all kinds), surfaces, cards, chips, list items, dividers, badges, progress indicators | 2.25 | Corpus samples; Google's material3 samples for these families compile and match; lock append | MS4-09, MS4-13 | L1 |
| MS4-17 | Material 3, part 2: inputs (`TextField`/`OutlinedTextField` on `TextFieldState`, checkbox, switch, radio, slider, segmented buttons) | 2.75 | Corpus samples. A typing round-trip on the emulator, on MS4-35's route | MS4-15, MS4-16, MS4-35 | L1 |
| MS4-18 | Material 3, part 3: containers and navigation (`Scaffold` complete, top app bars, navigation bar, rail and drawer, tabs, `ModalBottomSheet`, dialogs, snackbar host, menus, date and time pickers, tooltips, swipe to dismiss) on MS4-39's states. Top-app-bar scroll behaviours (host nested scrolling) and window insets | 3.25 | Corpus samples; the `SheetState` and `DrawerState` command tests against the real widgets | MS4-17, MS4-39 | L1 |
| MS4-19 | Icons: an `icons-core` group, and `icons-extended` opt-in with an app allowlist | 1.5 | Bundle size with and without extended. An unknown icon gives a placeholder and one report | MS4-01 | L1 |
| MS4-20 | Animation complete (§10): `AnimatedContent` (all overloads), `Crossfade`, `updateTransition`, `rememberInfiniteTransition`, `keyframes`/`repeatable`/`infiniteRepeatable`, `animateItem` specs, animated references on widget parameters; spec factories and `+` as call records (G1). Guest reads per Q3 | 3.75 | Frame-by-frame same-source tests on JVM and the iOS simulator; 0 change batches while host animations run | MS4-09, MS4-42, Q3 | A |
| MS4-21 | The guest-clock `Animatable` behind an opt-in at ERROR level (maintainer decision, was Q4). Untracked animated values (`1f - alpha`) are reported to the mismatch channel in debug builds | 1 | A compile-fail test without the opt-in; a debug report test | MS4-01 | L1 |
| MS4-22 | **G3: recorded draw scopes** (#312 §1.3), replacing revision 3's hand recorder: `Canvas`, `drawBehind`, `drawWithContent`, `graphicsLayer {}` and the nested blocks (`translate`, `rotate`, `clipPath`, `withTransform`, …), `Path` as a recorded interface, `size` from the host after layout. `drawText` (the host's `TextMeasurer`), `drawIntoCanvas` and `drawImage` stay hand pieces or errors. **Scheduled early** (L2, days 15–19.25), so that a slip shows while there is time (Q7) | 4.25 | `RecordedScopeTest`, moved from #312: 0 differing pixels for paths, arcs, text and gradients, each drawn at one place (Skia dithers gradients by device position). A layer-phase read in `graphicsLayer` is an error stub | MS4-41; G1 for gradients | L2 |
| MS4-36 | **NF5's frame rules for the generated groups** (review H2). The generator classifies every generated property for NF5's A1 rules (`@Replayable`, `@Scrolls`, `@NeverCached`, `@BlocksFrameWhenTrue`, `@StyleToken`, `@NotData`) and emits them on the generated schemas, **by default from each parameter's category** (eff-codegen C6(c): a hand codec or call record is `@Replayable`, a theme reference `@StyleToken`, an event `@NotData`, a host-state handle gets its hook), so strict mode fails only on the exceptions. So `keliver-tooling-codegen` writes their `FrameRules`, in strict mode, as for the legacy schemas. The generated host gains **NF5's A4a hooks on A3c's interfaces**, as A4a adds them to the legacy widgets: <br>- `ScrollOrigin` on `LazyColumn`/`LazyRow`, the grids, `verticalScroll`/`horizontalScroll` and the pagers; <br>- `TextLines` on `Text`/`BasicText`; <br>- text fields replayed empty (NF5's decision 14); <br>- replay snapping for `AnimatedVisibility` and `animateContentSize`; <br>- the pagers' auto-advance waiting for `LocalContentShown`; <br>- the extras classified: `Shimmer` excluded (NF5 2.4), `LazyItem`, and `Retained`/`RetainedEntry`. <br>Every coverage PR after it classifies its own exceptions: strict mode fails the build otherwise | 4.5 | **NF5's A4b pixel test on the corpus:** 0 differing pixels, replayed against live, on JVM and the iOS simulator. **I4's height-equality test** (A4a's) for every generated text-bearing widget and property, so a generated `Text` keeps its height at the hand-off. Per scroller, a scroll of one pixel blocks the write. Snapped animations and a held auto-advance shown in host tests. The review list is empty | NF5's A1, A3c, A4a and A4b; MS4-02, MS4-05, MS4-38 | L2 |

**The stable Compose API that no PR covered** (the owner, 2026-11-26: in 0.5.0). Each PR opens with a
**short design note** (inside its days), reviewed before its code. **The sizes are provisional:** the
efficiency review called 2–4 days each a floor, with shared elements and inline content possibly well
above it. When a note's size differs, the PR's body re-runs section 2.3. Sample counts are Google's
(eff-samples §5).

| PR | Scope | Days | Gate / test | Needs | Lane |
|---|---|---|---|---|---|
| MS4-44 | **Focus:** `FocusRequester` as a G2 remote object (`requestFocus`, `freeFocus`), `Modifier.focusRequester`, `onFocusChanged`/`onFocusEvent` (K11 events carrying `FocusState`), `focusProperties`, `FocusManager`'s commands | 2.5 (provisional, 2–3) | Google's focus samples (23) compile and match; a focus round trip on the JVM host | MS4-09, MS4-12, MS4-43 | L1 |
| MS4-45 | **Semantics** (also accessibility, part of feeling native): `Modifier.semantics {}` and `clearAndSetSemantics {}` as recorded scopes (G3's mechanism), with `contentDescription`, `role`, `stateDescription`, `testTag`, `heading`, `traversalIndex`. Custom actions answer synchronously (K13): the note decides between an answer of `true` with the action sent as an event, and an error | 2.5 (provisional, 2–3) | The semantics oracle (MS4-05) on Google's semantics samples (15): equal trees | MS4-05, MS4-09, MS4-22 | L2 |
| MS4-46 | **Size and position callbacks:** `onSizeChanged`, `onGloballyPositioned`, `onPlaced`, `onVisibilityChanged`, as K11 events with host values (`IntSize`; a snapshot of the bounds in the root and the window instead of `LayoutCoordinates`, whose methods are synchronous host reads) | 2 (provisional) | Google's samples (23) compile; a resize reaches the guest once per layout change | MS4-09 | L1 |
| MS4-47 | **Gesture states with callbacks:** `draggable`/`rememberDraggableState`, `anchoredDraggable` with `AnchoredDraggableState` (G2; `DraggableAnchors {}` recorded), `transformable`, `scrollable`, `NestedScrollConnection`. The gesture stays on the host; a guest callback per delta is sent as an event; a callback that must answer synchronously (`rememberScrollableState`'s consumed delta, K13) is an error. Visuals that follow the drag each frame (`offset { state.requireOffset() }`) bind through MS4-51 | 5 (provisional, 4–6) | Google's samples (30) compile; same-source drag frames for `anchoredDraggable` | MS4-09, MS4-43 | L2 |
| MS4-48 | **Shared elements:** `SharedTransitionLayout`, `Modifier.sharedElement`, `sharedBounds`, `rememberSharedContentState`: scope objects threaded across slots and across `keliver-nav` destinations, on NF1's retained container and NF5's reveal. On Track A, which owns `keliver-nav` from NF5's B1 on | 6 (provisional, 4–8) | Google's samples (12) compile; same-source frames of a shared-element transition between two destinations, on JVM and the iOS simulator | MS4-20, NF5's B4 | A |
| MS4-49 | **AnnotatedString's inline content and links:** `InlineTextContent` (a composable inside a value: a placeholder child the host fills, K8) and `LinkAnnotation` listeners (a guest callback inside a value: an event carrying the link's id) | 4 (provisional, 3–5) | Same-source pixels of text with inline content; a link tap reaches the guest | MS4-09, MS4-13, MS4-42 | L1 |
| MS4-50 | **Material 3 1.4's additions:** the carousels (`HorizontalMultiBrowseCarousel`, `HorizontalUncontainedCarousel`, with `CarouselState` as a G2 remote object), `WideNavigationRail` and the loading indicators | 2.5 (provisional, 2–3) | Google's samples for them (7) compile and match | MS4-18, MS4-43 | L1 |
| MS4-51 | **Per-frame lambdas: the host-side expression binding** (the owner, 2026-11-26: option (c), with (b) compile errors for anything it cannot express). A lambda passed where Compose reads a value every frame (`LinearProgressIndicator(progress = { … })`, `Modifier.offset { … }`, `graphicsLayer { alpha = … }`, custom slider thumbs, swipe and pull backgrounds reading `progress` or `distanceFraction`) is **evaluated on the host each frame** when it derives from host values (animated references; remote-object fields such as `SwipeToDismissBoxState.progress`). It crosses as an expression (arithmetic, `lerp`, `coerceIn`, comparisons and conditionals, constructors of hand-codec values), not as a value the guest re-sends; without it the guest clock steps at about 2.8 values/s on the A12 (#245). **The design note settles the mechanism first.** Kotlin's `Float` arithmetic cannot be recorded at run time, so either the lambdas passed to the generated per-frame parameters are transformed at compile time (a narrow compiler plugin: an IR lowering, plus a FIR checker that gives (b)'s errors, which the IDE may not show; eff-codegen C4), or the binding accepts only forms it can recognise at run time (a bare read such as `{ anim.value }`) and (b) covers the rest. The size follows the note. **Review material only:** Remote Compose's expression-engine docs, the precedent for host-evaluated expressions (its code is not adopted; efficiency review C10) | 9 (provisional, 7–12) | Frame-by-frame same-source tests on JVM and the iOS simulator: a progress bar following an animated value, an `offset {}` following a drag; 0 change batches while they run; each unsupported form fails to compile with its message | MS4-09, MS4-20, MS4-22, MS4-43 | A |

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
| MS4-29 | ED0 ingest on by default for the generated groups. The spike's `for`-as-Repeat reading is removed (the D6 amendment excludes it). The relay serves the generated catalog, and the tools classpath carries the values module. **Plus a generated value table** (eff-codegen C6(c), a cost the review found): the editor's Compose value reader is a hand table of 20 types (`portal-ingest`'s `ComposeValues.kt`, 667 lines), and G1 takes the generated API to about 124 value types, so the reader and speller for call-record types are generated from G1's producer index (a call record's wire is the source call). The maintainer reads a generated table as D6's "table that grows by entries" | 6 | D14 on a real screen; `ParityGoldenTest`; 0 RawCode on the corpus within the supported subset; `ShippedClasspathTest` updated | MS4-10, MS4-42 | L2 |
| MS4-30 | The canvas wired into `portal-editor` (a guest composition in the browser feeding host `ScopedChildren`, the inspector reading the metadata's kinds); lazy lists on the canvas. **Under Q17, the published editor without legacy:** `portal-editor` and `portal-render` depend on `keliver-material-compose`, `keliver-material-protocol-guest-web` and `keliver-layout-compose` today; the legacy canvas moves into an unpublished module, which the in-repo dogfood (`portal-app-lib`, `web-spike`) uses until MS6, and from here on `keliver-material-*` and `portal-render` publish only with `-PkeliverLegacy` (as the prototype does with `-PkeliverComposePrototype`; the Central workflow passes neither). The editor E2E suite's fixture (2 screens, 6 components) moves to the generated groups, by MS4-31's rules | 11.5 | Protocol equality, canvas against device, for every corpus sample (wasm pixels cannot be captured, U41); the editor E2E suite on its generated fixture; a `publishToMavenLocal` without the flag lists no `keliver-material` or `portal-render` artifact | MS4-29 | L2 |
| MS4-37 | **Adoption finding 8's open point** (PLAN section 7): `Compiled_<screen>.kt`'s stamp changes on every edit and churns in git. It should change only when what it stamps changes | 1 | Two edits that leave the compiled output the same leave the file byte-identical; an edit that changes it changes the stamp once; an ingest round-trip on a real screen | MS4-29 | L1 |
| MS4-31 | The migration tool, legacy to generated (`Background(colorArgb)` to `Modifier.background(Color(…))`, …): a tools command built on `portal-ingest`'s parse (Q13: confirm). **U2 first:** the legacy host widgets (`keliver-material-composeui`) already call the real Material 3 composables, so their property-to-parameter mapping seeds the rules (it may save 1–2 of the 6 days). `sample/`'s guest is written against its own app schema (`dev.keliver.sample.schema`, `sample/guest/…/Main.kt:12-14`), not `keliver-material`, so the tool does not apply to it | 6 | Converts the reference app's screens and the editor E2E fixture. Each converted screen compiles, ingests with 0 RawCode, and matches its old screenshot within the legacy-to-Compose tolerance the PR records | MS4-18 | L1 |
| MS4-32 | The reference app ported (by MS4-31, then by hand where needed), checked on MS4-35's route. **The templates' host widget system becomes the generated root** (`production-host`'s `KeliverHost.kt` and `KeliverScreen.kt`, `ios-host`'s `MainViewController.kt`) | 3 | `reference-app.yml` (emulator) and `ios-host.yml` (simulator) green, T0–T4 and S17* included | MS4-31, MS4-23, MS4-35 | L1 |
| MS4-33 | **Golden screens** (bar 9). For the corpus (the generated samples and Google's), **the native rendering is the golden**: one host app per platform draws each sample natively and through the guest in the same place, one after the other, on the Android emulator (`google_apis`) and the iOS simulator, and compares pixels and semantics (the oracle Gate A, Gate B and #246 used). A control sample that must differ replaces "a deliberate one-pixel change fails". **Checked-in goldens stay only for the reference app's named states** (no native copy exists) and the web canvas's protocol equality (U41) | 3.5 | 0 differing pixels and semantics for every corpus sample, and the control differs; the reference app's goldens checked in, and a one-pixel change to one fails | **all of MS4c**, MS4-30, MS4-32 | L2 |
| MS4-34 | `llms.txt` and the agent guide generated from the catalog, with a staleness guard; docs for the generated packages; the 0.5.0 notes for a new adopter (Google's samples as examples, with their attribution) | 3 | A freshness check in CI. An agent writes a screen from `llms.txt` alone and it compiles and renders (Gate B's agent test, repeated) | **all of MS4c**, MS4-11 | L1 |

**MS4f. The candidate.** A branch with `KELIVER_VERSION = 0.5.0` and the lock frozen. Section 3's checks,
C1–C16, are run and written up in one report; then the owner's go (MS5 publishes). It waits for every
MS4 PR **and NF5's A6 and B5**. 2.5 days (one grammar in C6 and one size measurement in C9, Q17), plus
device sessions.

**Totals:** about **178 MS4 agent-days**: MS4a 22.75, MS4b 31, MS4c 70, MS4d 18, MS4e 34, MS4f 2.5.
**33.5 of them are provisional** (MS4-44…MS4-51; 26–42 on their ranges). From revision 3's 150:
- #312's mechanisms: −8 (MS4b and MS4c, 80 to 72; #312 §4);
- the codegen adoptions C6 and C7, and the cost the review found (MS4-29's value table, +2): −1
  (eff-codegen C8: 142 to 141);
- Google's samples (U1): −1 (MS4-05 +1.25; −0.25 in each of MS4-12, -15, -16, -17, -18, -20, -22, -38
  and -39);
- the scope lines widened (U1): +0.75 (MS4-12, MS4-13);
- the legacy recommendation (Q17): +2.5 (MS4-03 −0.5, MS4-30 +3.5, MS4f −0.5);
- the throughput levers (MS4-52): +1.5;
- so **about 145 for revision 3's scope**;
- the owner's added scope of 2026-11-26: **+33.5, provisional** (MS4-44…MS4-51).

NF5, by this plan's estimate, is about **57 agent-days** on Track A, unchanged:
- H0 2;
- (A): A1 3, A2 2, A3a–c 9, A4a–b 8, A5 3, A6 2;
- (B): R1 2, B1 6, B0 3, B2 6, B3 2, B4 6, B5 3.

Together that is about **235 agent-days before the go** (228–244 on the provisional ranges).

### 2.2 Two tracks: where NF5 and MS4 meet

NF5 (Track A) is designed and approved ([`SHOW_BEFORE_COMPUTE.md`](SHOW_BEFORE_COMPUTE.md), revision 6,
on `main` since #308 merged at `4f0e08d88`). Its H0 started on day 0 (2026-11-26) and its (A) follows.
Its (B) waits for two steps:
- **R0**, the A12 trace of one floor step, scheduled for **2026-11-25**. Its build comes from #308's
  head, and it **runs no MS4 code**: nothing of MS4 was merged by then.
- **R1**, a reveal-only arm on the prototype.

Decision 7 is already answered, (b). **#308 merged before MS4-01 opened.**

| Shared area | NF5 touches | MS4 touches | Rule |
|---|---|---|---|
| The prototype modules: `keliver-compose-gatea-composeui` (with its dependencies, the other `gatea-*` modules), `keliver-compose-gatea-profile`, `sample/` (the three-way bench) | #308 added `CachedFrameReplayBenchmark` (composeui), `Nf5Stack.kt` and `QuickJsProfile.kt` (profile) and the bench's `variedDetail` (sample). R1 adds a bench arm. NF5's B1 is a spike "against the prototype" on gate A's container and the guest profile | MS4-01 and MS4-02 add the product modules; MS4-40 retires the prototype | **The measurement modules are not moved or renamed until NF5's B1 closes.** MS4-01 and MS4-02 **copy rather than move**, so MS4-01 moves nothing at all. "The rest" it could have moved is only the generator and `keliver-compose-values`, which keep their names (Q15); the other `gatea-*` modules are the composeui module's own dependencies, so they stay with it. Copying is possible because the generator takes one config per group, so the product groups are generated beside `gatea` while the prototype keeps building. Neither waits for B1. The cost is two generated copies and the prototype's tests (and its iOS link) in CI from about day 3 to day 40.5. NF5's B1 runs on the prototype's container, as NF5 plans. MS4-40 moves NF5's measurement code onto the product modules after B1 and A6, and before B5 (L2, days 37.5–40.5), agreed with Track A. <br>**The prototype is kept, not frozen** (re-review M2). The generator and `keliver-compose-values` keep changing under it (G0, MS4-07, G1, MS4-09 and MS4-08 on L1; G2, G3, MS4-13, -14, -15 and -39 on L2), so every such change regenerates and recompiles it (`checkComposeApi`), during NF5's R1 (days 2–4), B1 (9–15: G0, MS4-07, G1 and G2 fall in it) and A6 (35–37). Two rules: <br>- **The prototype's lock may be reset** when a generator change requires it. `gatea.lock.json` was never published, so no device holds its tags. MS4-08's presence split and MS4-09's "present" flag change the wire of existing entries, so each resets it, and so does G1 if a call record changes an existing entry's wire; each says so in its body. The product locks stay append-only from MS4-06 on. <br>- **Every NF5 measurement (R1, B1, A6) records the commit it was built from**, in its result. A comparison never crosses a regeneration: both arms of a before/after pair are built from one commit, or the baseline is re-run on the later one. <br>The upkeep costs L1 about 2 days (inside MS4-08's and MS4-09's estimates; G1's inside its own); the values PRs on L2 keep the prototype compiling within their own estimates. <br>A6's `x=cachedframe` arm records frames of the prototype's generated widgets, which no A1 rule covers (A1 annotates the three legacy schemas; MS4-36 the product groups, days 52.5–57): Track A says in A6's body how that arm's widgets are classified |
| The generated groups' configs and locks (`<group>.json`, `<group>.lock.json`), the shared `values` lock, and the generator's classification (re-review M1) | NF5's **B0**: `retainedContainer@2` changes the `Retained`/`RetainedEntry` extras (today in `gatea.json:120`, in the product core group from MS4-01), so it edits the **product group's config and lock** (days 37–40). **B4** does too, if `reveal@1` touches the schema (days 48–54). Track A names this in those PRs' bodies | L1's generator PRs; L2's G2, G3, MS4-13, -15 (`BasicTextField(state)`), -36, -39, -45 and -47; Track A's MS4-20, -48 and -51 | **The same rule as the API dumps:** merge `origin/main`, take its lock, then regenerate (`generateComposeApi` in its own Gradle call, then the tests). The branch's new entries are appended after `main`'s, and their tags may change, which is free until the freeze (Q2). **Never hand-merge a lock or a generated file.** The shared **`values` lock** (G1: producers, remote members and scope members, keyed by Compose declaration) is the one serial point every group appends to; the same rule resolves parallel appends. **Each mechanism has its own emitter class** (#312 §3, item 6): L1 owns the widget emitter (`Emit.kt`); G2 and G3 (L2) and MS4-51 (Track A) add their own emitter classes, and only their integration touches `Emit.kt`, merged in turn under the same rule. The other PRs add config entries and classifications |
| The production container | NF5's **B0** builds the retained container's entry states and `retainedContainer@2` "in NF1's production container" | **MS4-02** puts NF1's container in the product host module | **MS4-02 is a named prerequisite of NF5's B0** (MS4-02 ends on day 11, B0 starts on day 37) |
| `keliver-nav` | NF5's B1 (spike), then B2–B4: held effects, parked pops, kinds, the reveal | MS4-27 (wasmJs), MS4-28 (registry); MS4-48 (shared elements across destinations) | **MS4-27 and MS4-28 land before NF5's B1 starts** (L2, days 3–9; B1 from day 9). From B1 on, Track A owns `keliver-nav`, and no MS4 PR edits it except **Track A's own MS4-48, after NF5's B5** |
| `keliver-tooling-codegen` and the `keliver-schema` annotations (review H2) | NF5's **A1**: the frame-rule annotations, `FrameRules` and strict mode, on the three legacy schemas | **MS4-03** (absent groups in the host factory); **MS4-36** (the generated schemas carry A1's annotations) | A1 (Track A, days 4–7) and MS4-03 (L1, days 4.5–7) overlap. They edit different outputs of the module (A1 adds `FrameRules`; MS4-03 changes the host factory). Whichever merges second merges `origin/main` and reruns the codegen tests, and neither renames shared code. MS4-36 starts after A1, A3c and A4b. **Under Q17, A1, A4a and A4b still annotate and hook the three legacy schemas, as NF5's design says**: they are the only schemas with real widgets while (A) is built. MS4-36 carries the rules to the generated groups, and the legacy hooks are removed with legacy after MS6 |
| Host features and templates (`scripts/templates/*/BundleIndex.kt`, `HostConfig`) and `keliver-treehouse-host` (review M7) | **H0**: host feature names as constants in one place, which templates declare. A3a–c, A5 (the start table, storage, the off switch), B3, B4 | MS4-04 (mismatch reports), MS4-23 (the compose gate's levels in both templates' lookup and load), MS4-32 (the templates' widget system becomes the generated root) | **H0 comes first** (Track A, days 0–2). MS4-04's template wiring and MS4-23's `constraints.compose` build on H0's constants: a template declares its group levels there, and `admits` reads that declaration. **There is no second list.** MS4-23 (days 46.5–52.5) lands after NF5's cold-start code (A5, days 32–35). The gate's changes stay in `BundleIndex.kt`'s constraint and manifest checks, and NF5's stay in the start path. Each names the other's PR in its body. MS4-32 (days 66–69) changes which widget system the templates build, not the start path |
| The protocol | NF5's A2: `FrameCacheTags` in `keliver-protocol` (lock test); B0: `retainedContainer@2`; B4: `reveal@1` | MS4-01 and MS4-06: product tags, free until the freeze | NF5's protocol changes are additive, under their own lock tests and host features. A2's `FrameCacheTags` does not touch the generated groups' lock. **B0's `retainedContainer@2` (and B4's `reveal@1`, if it touches the schema) does**: it lands in the product group's config and lock under the row above, and is a Track A edit of the product group's extras. **The generated lock freezes at the `v0.5.0` tag (Q2), and NF5's A2, B0 and B4 land before the candidate**, so 0.5.0 carries both. A stored frame is keyed by the release (sequence, manifest sha256, module digest), so a tag change before the freeze never replays a stale tree |
| The reference-app workflows | H0's and A5's gates | MS4-04, -17, -23, -32; C7, C8 | Library changes are checked on **MS4-35's route** (L2, days 0–3). H0's workflow gate runs on that route once it merges |
| API dumps (`keliver-nav.api`, `keliver-treehouse-host.api`, `keliver-protocol.api`, klib dumps) | New experimental API | New API | Merge `origin/main`, then run `apiDump`. Never hand-merge a dump |
| Phone sessions | R0 (2026-11-25), R1, A6 (A-1…A-8), B5 (B-1…B-10) | The candidate's A12 and iPhone runs (C11, C14, C15) | One session at a time (PD7). MS4f's runs share NF5's next scheduled session where the timing allows |

### 2.3 Lanes, load and duration

**Lane loads** (agent-days). Day 0 is **2026-11-26**, a Thursday; days are working days. H0, MS4-01 and
MS4-35 started on day 0. Every start and end respects the PR's "Needs".

| Lane | Agent | PRs in order (start–end day) | Load | Ends |
|---|---|---|---|---|
| L1 | Track B's agent | MS4-01 (0–3), 52 (3–4.5), 03 (4.5–7), 06 (7–8), 41 (8–10), 07 (10–13), 42 (13–18), 09 (18–25), 10 (25–27), 08 (27–29), 12 (29–30.5), 16 (30.5–32.75), 11 (32.75–38.75), 19 (38.75–40.25), 38 (40.25–43), 17 (43–45.75), 18 (45.75–49), 50 (49–51.5), 46 (51.5–53.5), 44 (53.5–56), 49 (56–60), 31 (60–66), 32 (66–69), 37 (69–70), 26 (70–72), 21 (72–73), 34 (75.75–78.75) | 76 | day 78.75 |
| L2 | the dedicated second MS4 agent | MS4-35 (0–3), 27 (3–5), 28 (5–9), 02 (9–11), 43 (11–15), 22 (15–19.25), 05 (19.25–24), 04 (24–26), 13 (26–28), 15 (28–30.25), 39 (30.25–32), 14 (32–35), 45 (35–37.5), 40 (37.5–40.5), 29 (40.5–46.5), 23 (46.5–52.5), 36 (52.5–57), 47 (57–62), 30 (62–73.5), 33 (75.75–79.25) | 77 | day 79.25 |
| A | Track A's agent | NF5: H0 (0–2), R1 (2–4), A1 (4–7), A2 (7–9), B1 (9–15), A3a (15–18), A3b (18–21), A3c (21–24), A4a (24–28), A4b (28–32), A5 (32–35), A6 (35–37), B0 (37–40), B2 (40–46), B3 (46–48), B4 (48–54), B5 (54–57); then MS4-20 (57–60.75), 48 (60.75–66.75), 51 (66.75–75.75), 24 (75.75–76.75), 25 (76.75–79.75) | 57 + 22.75 | day 79.75 |
| MS4f | any | the candidate (79.75–82.25) | 2.5 | day 82.25 |

The tightest "Needs":
- **MS4-33 and MS4-34 start on day 75.75**, when the last coverage PR (MS4-51, Track A) ends. L1 waits
  2.75 days (73–75.75) and L2 2.25 (73.5–75.75); an overrun of a provisional PR shows there first.
- MS4-36 starts on day 52.5, after A4b (day 32) and MS4-38 (day 43).
- MS4-40 runs days 37.5–40.5, after B1 (day 15) and A6 (day 37) and before B5 (day 54).
- MS4-27 and MS4-28 end on day 9, when B1 starts; MS4-02 ends on day 11, and B0 starts on day 37.
- MS4-23 starts on day 46.5, after A5 (day 35) and MS4-11 (day 38.75).
- MS4-48 starts on day 60.75, after B4 (day 54), B5 and MS4-20.
- G2 (day 11) and G3 (day 15) start after G0 (day 10); G3's gradient samples need G1 (day 18), which
  merges first.

**The binding constraint is total load, not the dependency path.** About 233 agent-days before the
candidate over three agents is about 77.6 days each. The schedule reaches 79.75 because MS4-33 and
MS4-34 must follow the last coverage PR. Track A's lane is the longest (79.75): NF5's 57 serial days,
then the MS4 PRs that need `keliver-nav` (MS4-48) or follow MS4-20 (MS4-51). The longest dependency
path is shorter: NF5's B4 (day 54) → MS4-48 → MS4-33 → MS4f is about 66 days, and the generator spine
MS4-01 → 06 → 07 → 09 → 16 → 17 → 18 → 31 → 32 → 33 → MS4f about 38.

**Duration.** The plan's agent-day figures were judged from PRs built under the same shared lock, so they
already carry the ≈10% loss measured from 2026-11-11 to 11-24 (efficiency ≈0.90; eff-throughput §2).
Three build-heavy agents run at about 0.85–0.93 without the levers and 0.93–0.97 with them, so the
dates scale by ×0.97–1.06 without and ×0.93–0.97 with. The provisional sizes move the base by about −2.5
to +3 days.

| Case | Candidate opens | Written up | Calendar, with the 2–3-week allowance (reviews, the CI queue, phone sessions, the owner's answers, the year-end break) |
|---|---|---|---|
| Levers in effect (MS4-52, the hosted full graph); provisional sizes at their low end | ≈ day 72 | ≈ day 74.5 | late March 2027 |
| **Plan on:** no credit taken for the levers; provisional sizes at their middle | **≈ day 80** | **≈ day 82.5** | 16.5 weeks of working days, then the allowance: **early-to-mid April 2027** (the weeks of 5 and 12 April) |
| No levers; provisional sizes at their high end | ≈ day 88 | ≈ day 90.5 | late April 2027 |

Revision 3 said mid-to-late March. The difference is the owner's added scope (+33.5 agent-days,
provisional), less what #312's mechanisms save. A fourth agent was not chosen (Q16); the efficiency
review's four-agent rows needed NF5 split over two agents, which three agents rule out.

**What moves the date most**, in order:
1. **Account capacity.** Every row assumes three agents working continuously. The records have not
   shown that, even for three: from 2026-11-11 to 11-24, about 202 agent-hours of work sessions
   averaged about 0.6 agents running, in bursts of 3–6, with two empty days; 43 sessions stopped on
   account limits and resumed hours later (eff-throughput §1b). **Each 10% of agent time lost** (to
   limits, or to waiting for reviews or answers) **adds about 8–9 working days**, nearly two weeks.
   Track A's lane ends last, so a day lost there is a day on the date; L1 and L2 have 2–3 days of slack
   before MS4-33 and MS4-34.
2. **The provisional sizes** (MS4-44…MS4-51: 26–42 agent-days). MS4-51 (7–12) and MS4-48 (4–8) carry
   most of the range, and both sit on Track A's tail: an overrun there moves the date day for day unless
   L1 or L2 takes the PR, and both are within a day of Track A's end, so a move gains little. Each
   design note re-runs this section.
3. **NF5's B1 and B5.** If B1 fails, (B) goes back to the owner (NF5 section 9); if B5 misses B-1 or
   B-2, the owner rules (C15). The candidate waits either way.
4. **The machine:** ×0.93 to ×1.06, about ±4 days (the levers; iOS CI kept on hosted macOS).
5. **Phone sessions, review rounds and the owner's answers**, inside the allowance: Q17 blocks MS4-03 on
   day 4.5; Q8 and Q15 block MS4-11 on day 32.75; Q5 MS4-23 on day 46.5; Q3 MS4-20 on day 57.

**What the schedule assumes:**
- three agents from day 0 (Q16, decided);
- Q17 confirmed by day 4.5, when MS4-03 starts. Answered (i) instead, MS4-03 grows by 0.5, MS4-30
  shrinks by 3.5 and MS4f grows by 0.5: L2 frees about 3 days, and the date does not move (Track A still
  ends last). Answered (ii), the date moves about 4–9 working days later (section 6.1);
- NF5's B1 passes. If it fails, (B) goes back to the owner (NF5 section 9). Then MS4-40 still runs after
  B1, and the candidate waits for the owner's ruling on (B).

## 3. The 0.5.0 candidate: "passes every check"

Everything below runs on one commit with `KELIVER_VERSION = 0.5.0`, and is written up in one candidate
report. The candidate waits for NF5's A6 and B5.

| # | Check | Pass when |
|---|---|---|
| C1 | The full graph's `check` and CI's API step (`apiCheck`, `ziplineApiCheck`, `redwoodApiCheck`, `spotlessCheck`, `validatePlugins`) on hosted macOS. The nightly full build (#306, sharded by #309) is **dispatched by hand on the candidate branch**: its schedule runs on `main` | Green |
| C2 | API dumps | Every new published module has JVM and klib dumps, under Q14's and Q15's names. The diff against 0.4.1 is summarised (declarations added and removed per module) and reviewed. No `gatea` name remains in any dump (MS4-40). **No `keliver-material-*` or `portal-render` artifact is published at 0.5.0** (Q17); 0.4.1's stay on Maven Central |
| C3 | Generated code fresh | `checkComposeApi`, `checkPortalCode`, `checkAppendOnly` against the **frozen** lock, `FrameRules` (strict) and `llms.txt` freshness are all up to date. MS4-34's agent test is repeated on the candidate: a screen written from `llms.txt` alone compiles and renders. Every per-frame parameter (K7, K12) either binds on the host (MS4-51) or fails to compile with its message |
| C4 | Same-source corpus | Every lock entry is used by at least one generated sample. `onlyImportsDiffer` for every pair. 0 differing pixels and 0 semantics differences on the JVM and the iOS simulator, with animations checked frame by frame. Google's samples at or above their expected statuses in the ratchet |
| C5 | Golden screens (bar 9) | The corpus: the native rendering compared in place with the guest's on the Android emulator and the iOS simulator (MS4-33), the control differing. The reference app's named states match their checked-in goldens on both. The web canvas is protocol-equal to the device |
| C6 | Editor (D14) | Every reference-app screen and every corpus sample within the supported subset compiles, ingests with 0 RawCode, has a device screenshot and a byte-stable write-back. **One grammar:** 0.5.0 ships no legacy group (Q17); the legacy round-trip suite keeps running in CI while legacy stays in the repository, but it is not a candidate check. Also `ParityGoldenTest` and the editor E2E suite on its generated fixture (MS4-30) |
| C7 | The reference app ported | `reference-app.yml` and `ios-host.yml` are green on MS4-35's route against the candidate: T0–T4, S17–S17d and MS4-23's gate scenarios (group level, **app-component level**, forged manifest) included. Every bundle names `quickjs-2026` |
| C8 | The adopter path | `publishToMavenLocal -PkeliverVersion=0.5.0 -DRELEASE_SIGNING_ENABLED=false`. A new app built from this checkout's scaffolders against that repository builds, publishes and renders on the emulator and the simulator, with the generated root as its only widget system |
| C9 | Size (Q11) | Against 0.4.1, for the reference app: the release APK's **increase** is at most +2.5 MB; the **bundle in total** is at most 2.5 MB (C1-lite's +30 KB included); the iOS app's **increase** (thinned arm64 build) is at most Q11's budget. Measured once: there is no legacy group (Q17). NF5's halves are each under 50 KB of dex (NF5's decision 17) |
| C10 | Compile time | Guest compile, Android host build and iOS host link, each **at most 1.5× 0.4.1's** on the same runner. A breach names its cause in the report, and the owner accepts it or not |
| C11 | Speed on phones (PD7, batched) | **A12:** open, back, cold start and scrolling no worse than the 2026-11-25 re-measure on the same harness. **The tap:** the first visible change after the touch-down at parity with native Compose's own button (series K's method; the owner's bar of 2026-11-22). **iPhone 13:** first frame and pixels (`keliver-gateb-ios-device.sh`). If signing is not available, the iPhone half is recorded as not run, and the go then needs the owner's explicit acceptance of simulator-only iOS evidence (with A-8 and B-7) |
| C12 | U14's tripwire (WS1) | The two ignored `ProtocolTest`s are re-run on the candidate (Kotlin 2.4.21 and CMP 1.12.2 since 0.4.1), and the result is written down |
| C13 | Docs | `docs-check` clean. CHANGELOG's 0.5.0 section is complete and written for a new adopter (the owner's 2026-11-23 rule): the generated API, `session()` replacing `discardPrefetched()`, `keliver-nav`'s behaviour changes (NF5's decision 5), and the legacy library's status (not in 0.5.0, 0.4.1 on Maven Central, the migration tool) included |
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
switching to the fork and to the generated root for 0.5.0 apps.

## 4. Gate B's open sub-decisions (#243–#246)

| PR | Question | Options | Recommendation and evidence | Blocks MS4-01? |
|---|---|---|---|---|
| #243 | The iOS same-source pixel test costs about 15.5 min of CI's Build job (14.7 min linking the test binary) | (a) keep it on every PR; (b) only when generated-API paths change, plus nightly; (c) nightly or manual only | **(b), decided by the maintainer** (was Q6). The corpus grows with MS4c and with the generated and Google's samples (MS4-05), and so does the link; the generated set runs on the JVM per PR and on iOS nightly. The nightly full build (#306) catches what the filter misses. MS4-52 tests whether Kotlin/Native compiler caches shorten the link. The simulator app script (`keliver-gateb-ios-sim.sh`) runs by hand for the candidate (C4) on `keliver-mac`. "+89 ms on the simulator" was settled by Gate B's confirmation | No |
| #244 (1) | `animate*AsState` on the host (animated references) or on the guest's frame clock | host; guest | **Host.** On the emulator, about 20 changes/s against about 330, and the guest clock gives about 29 values/s against about 55 host frames. On the A12 (#245) the guest clock gives **2.8 values/s**, so it steps. Host references are pixel-identical to native, frame by frame, on JVM and the iOS simulator ([`GATE_B_RESULTS.md`](GATE_B_RESULTS.md)). MS4-51 extends the same principle to values derived from them | No |
| #244 (2) | How a guest reads an animated value | tagging (built): reads are off by ULPs, `== target` is false, `0f` reads `Float.MIN_VALUE`; a read log (not built): exact reads, but misses a value read early and kept | **Read log** (Q3). Tagging turns guest logic wrong silently (`if (alpha == 0f)` differs from Compose, against bar 1). A read log's failure is a missing animation, which is visible, and a debug-build check can report unconsumed log entries. The wire (`ExtraAnimated`) is the same either way, so the lock does not wait for it | No; blocks MS4-20 |
| #244 (3) | A host factory that accepts absent groups | yes; no | **Yes** (MS4-03). R8 already removes 79% of Material 3 unaided. The measurable saving is Coil (170 classes); under Q17 the legacy renderers (92 classes, plus the Material 3 and Coil code only they reach) are not in 0.5.0 at all | No; part of Q1 |
| #245 | How strongly to flag the guest-clock `Animatable` escape hatch | a lint warning (design); an opt-in at ERROR; an error unless suppressed | **An opt-in at ERROR, decided by the maintainer** (was Q4; MS4-21). 2.8 values/s on the A12 is not an animation. A compile-time opt-in fits bar 5 and costs about a day | No |
| #246 | The iPhone pixel test (blocked at signing on 2026-11-14) | — | **Done that evening:** 0 differing pixels on three samples on an iPhone 13, first frame +142 ms warm and +820 ms with an empty cache. Left: the wrapper's UIKit link fix never ran on the device signing path, and a personal team's 7-day profile recurs at every session (C11) | No |

## 5. Risks

| Risk | Evidence today | Mitigation | Fallback |
|---|---|---|---|
| Binary size: generated renderers and groups | +1.79 MB APK (+17.9%) with the route, of which the bundle is 1.95 MB; R8 removed 79% of Material 3; iOS app size not measured | Groups with absent-group support (MS4-03, which also measures the iOS app first); legacy not shipped (Q17); Coil optional; `icons-extended` allowlisted; C1-lite limited to +30 KB; C9 measures the APK, the iOS app and the bundle | Ship more, smaller groups in 0.5.x (append-only: new groups, never moved widgets); a release-size report (WS6) |
| Compile and link time | The iOS test link alone is 14.7 min on a 3-core runner (#243). Until MS4-40, the prototype's and the product's tests both link. Generated and Google's samples add to the corpus | Adopters consume published klibs (they do not compile our generated code). C10 measures the iOS host link against a threshold. #243's path filter; generated samples on iOS nightly only. MS4-52's Kotlin/Native cache test | Split the generated host module per group, so a host links only what it uses; Kotlin/Native release link caching on CI |
| Compose version coupling | The generator reads the CMP 1.12.2 and material3 1.9.0 jars; copied default expressions depend on them; `checkComposeApi` fails on a bump (design §20). Google's samples are vendored per version | 0.5.x supports CMP 1.12.x only, declared in the Gradle metadata (Q9). A nightly job builds the groups against the next CMP. Hidden overloads keep their tags across a bump (§3). The samples' update is about 0.25 day per bump | A Compose minor bump is a Keliver minor release with regenerated groups; an app on a newer Compose waits for it |
| The API surface committed to | The wire (lock tags) lives on devices for as long as bundles do. The guest stubs follow Compose. The mirrors and host-state commands are Keliver's own | Freeze only the lock at 0.5.0 (Q2). Keliver-only API (extras, app components, host-state commands, nav retention) goes behind `@Experimental…` opt-ins (Q10); mirrors' internals are non-public. Legacy's wire is not committed to in 0.5.x (Q17) | The pre-1.0 policy ([`API_STABILITY.md`](API_STABILITY.md)) allows breaking guest-source API in 0.x. The wire cannot break, so a mistake becomes a hidden entry with a new tag |
| Scope: WS2 in full, the added API and NF5 are about 235 agent-days, 33.5 of them provisional | Sections 2.1 and 2.3 | Three agents. Coverage is config once each mechanism exists (G1–G3, the slot classifier). The added PRs open with design notes, so their sizes are known early. G3, the most novel recording, is scheduled early (days 15–19.25) | Q7: only if a category is late does the owner decide whether to amend PD4. PD4 stands until then |
| **Account capacity** | Average ≈0.6 work agents over 2026-11-11 to 11-24; 43 sessions stopped on account limits (eff-throughput §1b) | Three sessions kept running; L1 and L2 keep 2–3 days of slack before MS4-33/34 | The date moves by about 8–9 working days per 10% of agent time lost (section 2.3) |
| **The expression binding's mechanism** (MS4-51) | Kotlin's `Float` arithmetic cannot be recorded at run time; a compile-time transform is a narrow compiler plugin that must follow each Kotlin version, and its errors may not show in the IDE | The design note first; (b) error stubs from MS4-09 on, so nothing ships wrong in the meantime | The narrow form (bare reads only), with (b) for the rest: those overloads then miss bar 2 in 0.5.0, an owner call with the evidence (Q7's path) |
| **The legacy split** (Q17) | The published editor depends on legacy today (`portal-editor/build.gradle:29-35`) | MS4-30 moves the legacy canvas into an unpublished module; the in-repo dogfood keeps it until MS6 | If MS4-30's split slips, 0.5.0 publishes legacy unchanged, as option (i): an owner call |
| NF5 and MS4 collide | Section 2.2 | Area ownership, the order (H0 first; MS4-27/28 before NF5's B1; MS4-02 before NF5's B0; MS4-23 after A5; MS4-48 on Track A after B5), and the prototype copied rather than moved | NF5's protocol changes are additive under their own lock tests and host features, and land before the candidate; the generated lock freezes at the tag (Q2) either way |
| NF5 (B)'s gates miss | NF5's appendix B expects B-1 at 120–150 ms against about 100, and B-2 at 225–275 against 150 or less | R0 and R1 first; decision 7's split (b) | The owner rules on the parked fallbacks (PLAN section 12) with NF5's evidence; the candidate waits for that ruling (C15) |
| The prototype and product copies drift | MS4-02 copies NF1's host pieces; generator and values changes regenerate the prototype under NF5's measurements | Fixes go to the product copy only. The prototype changes only by regeneration (its lock may be reset) and by NF5's measurement code; each NF5 measurement records its commit and never compares across a regeneration (section 2.2) | MS4-40 retires the prototype by day 40.5 |
| Old 0.4 bundles | One engine since NF2: every 0.5 bundle is `quickjs-2026`, and hosts take only their engine's bundles (#275) | None needed: the 0.4 wire is not served to 0.5 hosts, so MS4 may reset tags before the freeze | — |

## 6. Decisions

### 6.1 Owner decisions needed

"Blocks" names the first PR that cannot start without the answer, and its start day in section 2.3.

| # | Question | Recommendation | Cost | Blocks |
|---|---|---|---|---|
| Q17 | **Confirm: the legacy library** (`keliver-material`'s 9 modules, the 76-widget schema and its renderers). The owner: it need not be kept in 0.5.0 if the Compose API covers everything; "keep it saved somewhere" | **(iii) below: not published at 0.5.0; kept in the repository, unpublished, while the editor's own routes need it; removed by one PR after MS6's ED3, with a tag `keliver-material-last` on the last commit that has it.** 0.4.1 stays on Maven Central. RedwoodLayout and RedwoodLazyLayout stay. The maintainer's next plan PR amends PLAN_2026-11's WS2 line ("today's hand-written widgets stay as a legacy schema") | About +2.5 agent-days before the candidate and no calendar days (they fit L2's slack); about 3 days after the go (the deletion, in MS6) | **MS4-03, day 4.5** |
| Q18 | **Acknowledge the calendar:** with three agents the candidate opens on about day 80 and is written up by about day 82.5: **plan on early-to-mid April 2027** (late March at best, late April at worst). It assumes three agents working continuously, which the records have not shown (section 2.3) | Acknowledge | — | Nothing; the dates in this plan |
| Q15 | **Artifact names of the shared modules**: the values runtime and the generator (which Q8 publishes for adopters' builds) | Keep `dev.keliver:keliver-compose-values` and `dev.keliver:keliver-compose-generator`; the app-components task lives in the existing `keliver-gradle-plugin` | None now; permanent once published | MS4-11, day 32.75; C2 |
| Q10 | Which generated API is stable in 0.5.0? | Compose mirrors stable; Keliver-only API experimental (opt-in). Compose's own experimental API (material3's) keeps its opt-in markers | Opt-in annotations in adopters' code | **The freeze at the candidate (C2), day 80.** Nothing before it: MS4-07 (day 10) generates Keliver-only API as experimental, which is the recommendation, and a "no" only drops opt-ins before the freeze |
| Q8 | App components: generated in the adopter's build? | Yes, a `keliver-gradle-plugin` task, with the lock in the app's repository | 6 days; the plugin gains the generator's classpath | MS4-11, day 32.75 (and so MS4-23) |
| Q5 | Gate granularity: the whole group level, or the levels actually used? | The whole level in 0.5.0 (simple, refuses more, and no older hosts exist), with the app components' level beside it | Coarser refusals once 0.5.x hosts age | MS4-23, day 46.5 |
| Q13 | **Confirm:** the migration tool (MS4-31) stays in 0.5.0, as PLAN section 8 and WS2 already include it | Confirm. Under Q17 it is the route off legacy: it ports the reference app, the editor E2E fixture and MS7's 13 private screens, and its rules double as the agent guide's "legacy to Compose" table. (`sample/`'s guest is on its own app schema, so it needs no port) | 6 days | Nothing if confirmed. **If no:** MS4-31 (day 60) is dropped, and MS4-32 (day 66) ports the reference app by hand: about 4 more days on MS4-32 (net about 2 fewer on L1), and MS7's 13 private screens are then ported by hand too |
| Q3 | Guest reads of animated values: tagging or a read log? | A read log, with a debug check for unconsumed reads | 3–4 days; tagging's tests rewritten | MS4-20, day 57 |
| Q7 | **Contingent; no answer needed now.** An amendment of PD4, only if needed: if one per-frame category (recorded drawing, G3, or the expression binding, MS4-51) misses, may 0.5.0 ship it as error stubs that name an app component, and add it in 0.5.1? That would cut "WS2 in full", which PD4 requires | **No amendment now.** G3 is scheduled early (days 15–19.25), so a slip there shows by about day 30. MS4-51's design note comes on about day 67. The maintainer brings the amendment, with the evidence, only then | None unless it happens; if it does, a guest that uses the category waits for 0.5.1 | Nothing now; the go, only in the slip case |
| Q9 | The Compose range for 0.5.x | CMP 1.12.x only, declared; the next CMP built nightly | Apps cannot move to CMP 1.13 before Keliver 0.6 | MS4-34 (the adopter notes), day 75.75; the candidate |
| Q11 | The size budget for C9 | APK increase at most +2.5 MB; bundle at most 2.5 MB in total; iOS app increase at most +3 MB (not yet measured: MS4-03 measures it first, and the plan comes back to you if that first measurement is already over) | A budget that may force the group split earlier | C9 at the candidate; MS4-03's measurement, day 4.5 |

**Q17 in detail: the three options.** The owner's (ii) and revision 3's (i) were evaluated against a
third, (iii). Days are agent-days before the candidate, against revision 3.

| | (i) Revision 3: legacy ships in 0.5.0 as an optional group | (ii) Unpublished, then removed by one PR before the candidate, tagged | **(iii) Recommended:** not published at 0.5.0; removed by one PR after MS6's ED3, tagged |
|---|---|---|---|
| What 0.5.0 publishes | Legacy's 9 modules, as an optional group beside the generated root | No legacy module | No legacy module (`-PkeliverLegacy` only, from MS4-30) |
| NF5's A1, A4a, A4b | Run on the three legacy schemas as designed, and ship | As designed; their legacy parts (A1's annotations on `keliver-material`, A4a's hooks in its renderers) are deleted before the candidate | As designed; unpublished at 0.5.0; deleted with legacy after MS6 |
| MS4-03 | 3 (legacy optional, measured with and without it) | 2.5 | 2.5 |
| MS4-31 (Q13), MS4-32 | The tool is a convenience: legacy screens keep running on 0.5.0. MS4-32 adds the generated root to the templates | The tool is the route off legacy; after the deletion it reads a frozen copy of the legacy catalog. MS4-32 replaces the templates' widget system | As (ii); the frozen catalog comes with the deletion in MS6 |
| C6, C9, C2 | Both grammars; size measured with and without legacy; legacy's API in the 0.5.0 diff | One grammar; one measurement; no legacy artifact | As (ii) |
| The editor | Both canvases and grammars, published | Before the candidate: the published editor without legacy (+2); the E2E fixture moved (+1.5); the editor's own app (`portal-app-lib`: 6 screens, 4 components) converted (+1.5); the legacy parity fixtures retired (+0.5); the device preview (`portal-device-*`) and the relay's legacy publish guest deleted (about +1.5, leaving no live device preview until MS6's ED3) or ported (about +5–6 more, then redone by ED3); the deletion PR (+2.5) | Before the candidate (MS4-30): the published editor without legacy (+2) and the E2E fixture moved (+1.5). The editor's own app, device preview and legacy publish guest stay on the unpublished modules until MS6, where ED3 ("on a phone") replaces the device preview |
| `sample/` | No change in any option: its guest is on its own schema (`dev.keliver.sample.schema`); its Android host links legacy only through the prototype root, which MS4-40 retires | (same) | (same) |
| `reference/inventory` | Ported by MS4-32 (C7 checks it ported) | Ported by MS4-32 | Ported by MS4-32 |
| MS7's 13 private screens | Run on 0.5.0's legacy group, ported one by one, mixing legacy and generated widgets | Stay on 0.4.1 until ported (MS7's scope is "all 13 on the new stack" either way), by MS4-31's tool; no mix on 0.5.0 | As (ii) |
| After 0.5.0 | 0.5.x hosts render legacy tags for as long as 0.5 bundles live (the wire is append-only, Q2); legacy's renderers follow every Compose bump (they call the real Material 3); two grammars in the editor | None | The deletion PR in MS6 (about 3 days: the modules, dumps, CI steps, NF5's legacy hooks, the dogfood app's conversion, the frozen catalog), then none |
| Agent-days before the candidate | 0 | About +9 (device preview deleted) to +14 (ported) | About +2.5 |
| The date | Day 80 | About 4–9 working days later (after L2's slack) | Day 80 (the days fit L2's slack before MS4-33) |

**Why (iii).** It gives the owner's result for 0.5.0 (no legacy in the release, nothing of legacy's wire
committed to in 0.5.x) at no cost to the date, and it does not build twice what MS6's ED3 rebuilds on
the generated catalog anyway. (ii) puts the editor's internal routes and the deletion on the
candidate's path. (i) keeps a second widget set, grammar and wire alive through 0.5.x.

**"Kept saved somewhere":** a plain git tag, `keliver-material-last`, on the last commit that has
legacy. It matches none of the release tag patterns (`v*`, `portal-tools-v*`, `zipline-fork-v*`), so
it fires no workflow. With 0.4.1's artifacts on Maven Central and the repository's history, that is
enough. A branch or a separate repository would need upkeep and would not build alone (the modules need
this repository's build-support).

**RedwoodLayout and RedwoodLazyLayout** are legacy as an authoring API (a 0.5.0 guest writes Compose's
`Row` and `LazyColumn`), but no option removes them. They are `keliver-material`'s own dependency
groups (`KeliverMaterial.kt:151-153`), and the prototype's root declares them (`gatea.json:5-9`), but
no generated widget emits their tags: the prototype's host only passes their factories through
(`WidgetSystem.kt:27-34`). They are also core's test schemas (in `keliver-treehouse-host`,
`keliver-tooling-codegen`, `keliver-protocol-guest` and `keliver-widget-shared-test`, and under the
`ui-basic` test schema), and the generated `LazyColumn`/`LazyRow` reuse `keliver-lazylayout-compose`'s
windowing classes (`kc-gatea-compose/…/foundation/lazy/LazyDsl.kt:29-31`). So the generated root does
not need them: MS4-03 stops declaring them, a 0.5.0 host links their renderers only if it adds them,
and they stay published unchanged (0 days). Unpublishing them later needs those windowing classes moved
first.

### 6.2 Decided by the owner

| When | Decision | Where the plan follows it |
|---|---|---|
| 2026-11-26 | **Q1**, the group layout, as recommended: a generated root; groups compose core (foundation-layout, foundation, ui, material3, animation, extras), `icons-core`, `icons-extended` (opt-in), `coil` (optional). Its legacy part (legacy as an optional dependency) is superseded by Q17 | MS4-01, MS4-03 |
| 2026-11-26 | **Q14**, Maven Central names: `dev.keliver:keliver-compose-<group>-<role>`, with the prototype's roles | MS4-01; C2 |
| 2026-11-26 | **Q2**: fresh product locks at level 1; free until the `v0.5.0` tag, then append-only | MS4-06; C3 |
| 2026-11-26 | **Q16**: three agents (L1, L2 and Track A). Not four, so NF5 is not split | Section 2.3 |
| 2026-11-26 | **Per-frame lambdas:** (c), a host-side expression binding, with (b), compile errors, for anything it cannot express | MS4-09 (errors until it lands), MS4-51 |
| 2026-11-26 | **The stable Compose API that no PR covered** (focus, semantics, size and position callbacks, gesture states with callbacks, shared elements, AnnotatedString's inline content and links, per-frame patterns, Material 3 1.4's carousel, wide navigation rail and loading indicator): in 0.5.0, each as PRs sized after a short design note | MS4-44…MS4-51 |
| 2026-11-26 | **Compose 1.12's experimental FlexBox, Grid and Style:** compile errors in 0.5.0, added in a 0.5.x once stable. **The 136 "never by design"** stay compile errors | MS4-07 |
| 2026-11-26 | **A second Mac:** not now; revisit after two weeks of `heavy.sh` logs | MS4-52's log line (from about day 3), so the revisit falls in mid-December |
| 2026-11-26 | **The engine is settled:** QuickJS 2026 with BigInt, on our Zipline fork; no engine work in MS4 | — |
| 2026-11-24 | NF5 (A) and (B) are in 0.5.0, so the candidate waits for both | MS4f; C14, C15 |
| 2026-11-24 | NF5's decision 7 is (b), and its other decisions are as recommended | Section 2.2 |
| 2026-11-22 | The tap bar is parity with native | C11 |
| 2026-11-22 | Publishing is on the owner's explicit go, with no GitHub Environment | MS4f |
| For information | From the efficiency review: Google's samples are vendored into the repository (Apache-2.0, headers kept, a NOTICE line, Spotless exempts the folder); for the corpus, bar 9's golden screens are the automatic native-against-guest comparison, and checked-in goldens stay for the reference app's named states | MS4-05, MS4-33 |

### 6.3 Decided by the maintainer

| Was | Decision | Why |
|---|---|---|
| Q4 | The guest-clock `Animatable` sits behind an **opt-in at ERROR** (MS4-21) | 2.8 values/s on the A12 is not an animation; one day; it is reversible (it can be relaxed to a warning) |
| Q6 | The iOS same-source test runs **path-filtered on PRs, and in full nightly** (#243 option (b)) | It is a CI-cost call. A regression outside the filter shows the next morning, and the candidate runs it in full (C4) |
| Q12 | Track A does **not** take MS4's `keliver-nav` PRs before NF5: **L2 takes MS4-27 and MS4-28 first**, merged before NF5's B1, and Track A owns `keliver-nav` from B1 on. After NF5's B5, Track A takes MS4-20, -48, -51, -24 and -25 | Track A has no slack until NF5 ends; MS4-48 needs `keliver-nav`, and MS4-51 follows MS4-20 |
| — | **PR #312** is closed unmerged when G0 merges: G0 takes its generator classes and its doc; G1–G3 take its tests | The spike module is JVM-only and decides nothing; the product PRs carry what is proven |
| — | **One emitter class per mechanism**: L1 owns `Emit.kt`; G2, G3 and MS4-51 add their own emitters and merge their integration in turn (section 2.2) | Lets G1 and G2/G3 run on two lanes |
| — | **C1-lite only inside MS4-13**, for `Offset`, `Size` and `Rect`; the existing `Dp` and `TextUnit` mirrors move only if that costs nothing more | eff-guest: +30 KB and about +7 ms on the A12 (estimated); +1.5–2 days if done alone |
| — | **Scaffold's first frame by U3** (`innerPadding` as a host-resolved handle; fallback: predicted padding corrected by the host's report) | No round trip at open for the common uses, against an open bar that is not yet met |
| — | **D6's reading:** a value table generated from G1's producer index is still "a table that grows by entries" (MS4-29) | It is entries, generated; nothing is evaluated |
| — | **Text selection state and custom `Indication`** stay compile errors in 0.5.0 (not in the owner's list of 2026-11-26) | Custom indications run per frame on the host. Together they are 8 of Google's samples (text selection state 5, custom `Indication` 3; eff-samples §4). The owner can add them as further PRs |
| — | **iOS host CI stays on hosted macOS through MS4**; the hosted full graph before each push (in effect from 2026-11-26) is a pilot, measured (latency 85–100 min; hosted macOS concurrency unverified) | The self-hosted runner's jobs held the lock in 64% of the measured slot waits (eff-throughput §1a) |

Record and changelog: none from this note; each MS4 pull request carries its own blocks.
