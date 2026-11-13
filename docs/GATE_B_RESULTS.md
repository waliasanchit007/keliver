# Gate B results (MS3)

Gate B of [`PLAN_2026-11.md`](PLAN_2026-11.md) §8, part by part. The design is
[`COMPOSE_GENERATOR_DESIGN.md`](COMPOSE_GENERATOR_DESIGN.md); Gate A's verdict is
[`GATE_A_VERDICT.md`](GATE_A_VERDICT.md). Each part adds its own section; the verdict comes when the
parts are in.

## Gate B-e results: animation, timed, and app size with groups

What it answers: the design's §10 animation rows (host-side `AnimatedVisibility` and
`animateContentSize`; `animate*AsState` as host-side *animated references* against the guest's
frame clock), its risks 7 (animated values on the host against the guest's frame clock) and 8
(renderers that reference Material 3 keep R8 from removing it), and §14's "Gate B measures app size
and decides". How it is built: design §22.

**Recommendation: host references for `animate*AsState`.** On the emulator both forms draw host
frames equally fast, but the guest's frame clock delivered about 29 new values a second to a host
drawing about 55 frames a second, so a guest-side animation advances on about every other frame,
and it sends ~330 changes a second over the bridge against ~20 for host references. Host references
are also pixel-identical to native Compose frame by frame (JVM). Keep `Animatable` on the guest's
frame clock as the escape hatch (it is what a `finishedListener` or per-frame guest logic needs),
flagged by a lint check, as the design proposed.

### What was built (prototype only, `-PkeliverComposePrototype`)

| Piece | Generated or hand-written | Where |
|---|---|---|
| `AnimatedVisibility` (plain, `ColumnScope.`, `RowScope.`), `Modifier.animateContentSize` (both overloads), `offset`, `rotate`, `scale` | **Generated** from Compose's API (lock level 5). Generator changes: a spec can name an extension receiver (`AnimatedVisibility@ColumnScope(…)`), a scoped overload's renderer calls the plain one with the scoped defaults, and a generic Compose type (`FiniteAnimationSpec<IntSize>`) maps to one wire class | `gatea.json`, `keliver-compose-generator` |
| Which parameters take an animated reference (`alpha`, `size`, `width`, `height`, `padding(all)`, `background(color)`, `offset`, `rotate`, `scale`), the stubs' `ExtraAnimated` element, the renderers' `animate*AsState` wrapping | **Generated**, from a config list (`animated`) | same |
| `animateFloatAsState`, `animateDpAsState`, `animateColorAsState` (animated references); `tween`/`spring`/`snap`, easings; `EnterTransition`/`ExitTransition` and their factories; `IntSize`/`IntOffset` mirrors | **Hand-written** mirrors and codecs (design §6: codecs are written once). Compose's `animate*AsState` are generic state functions, not widgets or modifiers, so the generator has nothing to emit for them; their tagging (below) is runtime code | `keliver-compose-values` (`dev.keliver.compose.animation*`) |
| The `ExtraAnimated` modifier (tag 500), the guest helper the stubs call, the host's `animated()` wrappers and transition converters | **Hand-written** runtime (one each, like the shimmer extra) | `keliver-compose-gatea-schema/Extras.kt`, `…-compose/internal/Animated.kt`, `…-composeui/Animation.kt` |
| `Animatable` (Float, Dp; Color in Oklab) with `animateTo`, `snapTo`, `stop` on `withFrameNanos` | **Hand-written**: the guest-side alternative; Compose's `Animatable` is not usable in the guest (it takes the real `ui-unit` and `ui-graphics` types) | `keliver-compose-values` |

How an animated reference crosses: the guest's `animate*AsState` returns the target *tagged* (a
`Float`/`Dp` a few ULPs from the target; a `Color` in spare packing bits), the stub recognises the
tag, sends the exact target plus an `ExtraAnimated(param, {id, spec, label})` element, and the
renderer wraps that argument in the real `animate*AsState` keyed by the id. A guest read gets the
target (within ~1e-6 relative for Float and Dp); arithmetic on it gives a plain literal; a host that
predates the element ignores it and shows the target without animation.

### Method

1. **JVM, deterministic (CI):** `SameSourceTest` gains the animation sample
   (`samples/{guest,native}/AnimatedPanel.kt`, only imports differ, checked by `onlyImportsDiffer`).
   Native Compose and the guest (through the real protocol, as JSON, into the generated renderers)
   render side by side on the test's clock, which the test drives by hand.
   - `hostAnimationMatchesNativeFrameByFrame`: `animateFloatAsState`, `animateDpAsState`,
     `animateColorAsState` (400 ms linear tweens), `ColumnScope.AnimatedVisibility` and
     `animateContentSize` (springs); captured at the start, 16, 96, 192, 304, 400 ms after the
     toggle and at the end.
   - `guestFrameClockAnimationReachesNativeEndState`: two `Animatable`s in `LaunchedEffect`s, the
     guest's clock sent the same frame times as the host's.
2. **Emulator, Macrobenchmark:** the sample (`sample/`, built like an adopter's app against the
   prototype published to a scratch Maven repository as `0.0.0-gateb`) with
   `-PkeliverComposePrototype` gets `GatebActivity`, a debug/benchmark-only route. Its screen,
   `sample/gateb-guest/…/AnimationBench.kt`, is plain Compose written once: 12 boxes whose alpha,
   size and colour animate back and forth every 700 ms (600 ms linear tweens), plus an
   `AnimatedVisibility` and an `animateContentSize`. Four forms: `host` (guest, `animate*AsState` →
   host references), `guest` (guest, `Animatable` on the guest's frame clock), and the same file
   compiled as native Compose in the host (`native-host`, `native-guest`; the copy is written by a
   Gradle task that rewrites the imports). The guest bundle (production build, signed with the
   sample's demo key) ships in the APK's assets. `GatebAnimationBenchmark` (FrameTimingMetric,
   `benchmark` build type, 5 iterations × 5 s of animation per form, after the route has drawn and
   settled 1 s). Jank = frames with a positive `frameOverrunMs`. Bridge traffic: the route counts
   `sendChanges` calls and the changes in them (a Zipline `EventListener.callStart`) and logs both
   once a second; read from the same `benchmark` APK for 15 s per form.
3. **App size:** the sample's release build (R8, `proguard-android-optimize.txt`), on the prototype
   stack, without the route (A) and with it (B, `-PkeliverGatebInRelease`: the activity, the
   generated renderers, their root protocol, keliver-material's renderers that root needs, the
   native copy and the bundle in assets). R8's mapping (kept classes) and `-printusage` (removed
   classes) count what stays of Material 3.

Device: one Android emulator (`google_apis`, API 35, 720 × 1600), on a shared development machine.
**An emulator is not a phone**: the numbers below are for comparing the forms with each other on
one device, not absolute frame times.

### Results

**JVM (deterministic):**

| Test | Result |
|---|---|
| Host references, `AnimatedVisibility`, `animateContentSize` against native, frame by frame | **0 differing pixels** at all 7 captured frames (start, 16, 96, 192, 304, 400 ms, end). **0 change batches** crossed while the host animated; the reference itself travelled once |
| Guest frame clock against native `Animatable` | 0 differing pixels at the start, at two mid frames and at the end (linear tweens, the guest's clock fed the host's frame times). **25 change batches** for a 400 ms animation at 16 ms frames: one per frame |

**Emulator, frame timing** (two runs of the whole benchmark; per run, the median frame count over
5 iterations of 5 s, and percentiles over all frames of the 5 iterations):

| Form | Run | Frames / 5 s | Frame CPU p50 | p90 | p99 | Overrun p50 | p90 | p99 | Janky frames |
|---|---|---|---|---|---|---|---|---|---|
| host references (guest) | 1 | 273 | 3.7 ms | 4.1 ms | 4.8 ms | −9.8 ms | −8.3 ms | −4.8 ms | 0 of 1,363 |
| | 2 | 272 | 3.7 ms | 4.1 ms | 4.9 ms | −9.7 ms | −8.1 ms | −5.1 ms | 0 of 1,358 |
| guest frame clock (guest) | 1 | 285 | 3.7 ms | 4.4 ms | 5.6 ms | −10.1 ms | −7.5 ms | −3.8 ms | 0 of 1,423 |
| | 2 | 283 | 3.4 ms | 4.0 ms | 4.8 ms | −10.5 ms | −8.6 ms | −4.3 ms | 0 of 1,410 |
| native, `animate*AsState` | 1 | 272 | 3.7 ms | 4.2 ms | 5.1 ms | −9.7 ms | −8.2 ms | −4.9 ms | 0 of 1,362 |
| | 2 | 273 | 3.8 ms | 4.3 ms | 5.3 ms | −9.7 ms | −8.0 ms | −4.9 ms | 0 of 1,362 |
| native, `Animatable` | 1 | 273 | 3.8 ms | 4.2 ms | 4.9 ms | −9.7 ms | −8.2 ms | −5.0 ms | 0 of 1,366 |
| | 2 | 273 | 3.8 ms | 4.2 ms | 5.1 ms | −9.7 ms | −8.3 ms | −4.9 ms | 0 of 1,365 |

**Emulator, bridge traffic** (the same `benchmark` APK, 15 s per form, once-a-second readings):

| Form | Change batches / s | Changes / s |
|---|---|---|
| host references | 1.4 (one per toggle of the 700 ms period) | ~20 |
| guest frame clock | 28.5 (range 27.5–29.5) | ~330 (310–345) |

Reading the two together: the host's own frames cost the same in every form (the differences
between forms are smaller than between the two runs of one form: the guest frame clock's p90/p99
were the highest in run 1 and the lowest in run 2; it does draw ~4% more frames in both runs, the
extra recompositions its changes cause), and no form drops a frame on this device. What differs is
how often the animated values change: host references and native Compose compute a new value for
every host frame (~55 a second here); the guest frame clock produced ~29, so its animation moves on
about every other displayed frame, which FrameTimingMetric does not see because the host still
draws on time. The guest also pays ~330 property and modifier changes a second through QuickJS,
serialization and the bridge for 12 animated boxes, where host references send one batch per
change of target.

**App size** (release, R8; prototype stack):

| | A: without the route | B: with the route | Difference |
|---|---|---|---|
| APK | 10,003,950 B | 11,796,563 B | +1,792,613 B (+17.9%) |
| `classes.dex` | 2,555,808 B | 3,970,848 B | +1,415,040 B (+55%) |
| Kept classes (mapping) | 3,621 | 5,533 | +1,912 |
| … of which `dev.keliver.compose.gatea.*` (generated group) | 0 | 370 | |
| … `dev.keliver.material.*` (legacy renderers the group's root includes) | 0 | 92 | |
| … `androidx.compose.material3.*` | 0 | 448 | |
| … `androidx.compose.foundation.*`, `…animation.*` | 627 | 1,012 | +385 |
| … `coil3.*` | 0 | 170 | |
| The guest bundle in assets (uncompressed) | — | 1,948 KB | (the rest of the APK difference, compressed) |

**Risk 8 (does R8 keep all of Material 3?): no.** R8 kept 448 of material3's 2,166 classes and
removed 1,718 (79%). The renderers reference only the Compose functions the group generates, and R8
removes the rest as for any app. Of the kept classes, 28 belong to widget families the generated
group does not have (DatePicker, TimePicker, NavigationBar, SearchBar, Slider, bottom sheets,
carousel): they come from keliver-material's legacy renderers, which the generated group's root
schema includes as a dependency (§19's "one group, its own root schema"). **What a group split
would save:** with R8 on, little for the generated group itself; the measurable saving is not
shipping the legacy keliver-material renderers (92 classes here plus the Material 3 and Coil code
only they reach) in an app that uses only generated widgets, which needs the host factory to accept
absent groups (§14). Coil (170 classes) is pulled in by both `AsyncImage` renderers and would be a
natural optional group.

### Caveats

- **Emulator only, one session, a shared machine** (a CI job and other builds were running at
  times). Relative numbers only; the phone checkpoint (plan MS3) should repeat the benchmark and the
  bridge counter. On a phone the guest's rate may be closer to the display rate, or further from it
  at 90/120 Hz.
- The bridge counter covers `sendChanges` calls into the host (Zipline's `callStart`), not the
  host's `sendFrame` calls into the guest; on the guest frame clock those run every frame too.
- The guest's 29 values a second is measured from batches, so it is an upper bound on distinct
  frames of the animation (each batch carries all 12 boxes' changes for one guest frame).
- The sample's prototype mode moves its stack to main's (Kotlin 2.4.20, Zipline 1.28, CMP 1.12.2,
  compileSdk 37) but stays on AGP 8.13 and Gradle 9.0, so androidx Compose 1.12's "AGP 9.1 or
  higher" AAR-metadata check is switched off in prototype builds, and JDK 17 is used where the
  sample asks for 11. The default sample build is unchanged.
- Size B includes the legacy keliver-material renderers because the generated group's root needs
  them today; a build with the generated renderers alone was not possible without the host-factory
  change above. B's dex also includes the route's native copy of the screen (small).
- The guest-side `Animatable`'s tween and spring are written to match Compose's curves; the JVM test
  shows identical pixels for linear tweens at the frames it samples, not for every easing.
- Animated references on **widget** parameters (a `Text`'s colour) are supported by the generator
  but no widget is configured yet; only the modifiers listed above take one.
- Not mirrored: `keyframes`, `repeatable`, `infiniteRepeatable`, `rememberInfiniteTransition`,
  `AnimatedContent`, `Crossfade`, `updateTransition`, `animateItem`'s specs (still dropped),
  transitions' lambda parameters, `finishedListener` (an `ERROR` overload).
