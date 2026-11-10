# Gate A verdict (MS2)

Gate A of [`PLAN_2026-11.md`](PLAN_2026-11.md) §8: a generator prototype for one screen of the private
2026-10 feature (its listing screen), on today's stack (Kotlin 2.2.0, Zipline 1.22.0, Compose
Multiplatform 1.8.2), compared on the Android emulator with today's Keliver port and with native
Compose. The design is [`COMPOSE_GENERATOR_DESIGN.md`](COMPOSE_GENERATOR_DESIGN.md). As the plan
requires, the private code and measured values stay out of this repository; this page gives each
criterion's method and result.

**Verdict: PASS_PLACEHOLDER**

## What ran, and where

- **The prototype** (this pull request, new modules only; nothing existing changes):
  - `keliver-compose-generator`: reads Compose's API from the Kotlin metadata of the CMP desktop
    jars and default expressions from their source jars, and writes the checked-in group;
  - `keliver-compose-values`: the guest's mirrors of Compose's value types and their codecs;
  - `keliver-compose-gatea-*`: the generated group `gatea` (a Redwood schema, guest stubs in
    `dev.keliver.compose.*` with Compose's signatures, host renderers that call the real Compose
    functions, the lock file and editor metadata). Lock level 2: 9 widgets and 25 modifiers
    generated from 41 configured declarations, plus 14 ERROR stubs.
- **The comparison**, on one Android emulator (`google_apis`, API 35, 720 × 1600, 320 dpi), in the
  private app's debug build, with one host and one signed bundle:
  - **A**: the listing screen as ported today (keliver-material widgets);
  - **B**: the same screen written once in plain Compose against the generated API;
  - **native**: the same plain-Compose file compiled as native Compose inside the same app, in an
    activity set up exactly as the Keliver screen (the same edge-to-edge window, system-bar styles,
    theme, safe-drawing padding, and the inner `MaterialTheme` and `Surface` the Keliver screen
    adds).
  - Data: the guest's fixtures (no sign-in, no network calls to the app's backend, orders off).
    The native copy draws the same first page, rendered from the fixtures by a guest test on the
    same day. Some fixture orders were given a logo served from the development machine, so that
    `AsyncImage` is exercised.
  - The host and the guest consumed the prototype's libraries from a disposable local Maven
    repository (version `0.0.0-gen`), built from this branch. Bundles were signed with a scratch
    key the debug host was built to trust.
- **On the JVM, in CI** (`SameSourceTest`): one sample screen file compiled against Compose and
  against the generated API; the guest side goes through the real protocol as JSON into the
  generated renderers; both render in the same place; the pixels are compared, and a tap on the host
  runs the guest's `clickable` lambda.
- The web version was not used: it needs a signed-in session.

## Criteria

| Criterion (plan §8) | Method | Result |
|---|---|---|
| Plain Compose with only imports changed | The screen is one plain-Compose file. A script writes the native copy from the guest copy by rewriting the `package` line and the imports (`dev.keliver.compose.` → `androidx.compose.`, `dev.keliver.coil3.` → `coil3.`, and the shimmer, a Keliver extra, to the app's own shimmer with the same signature), then diffs the two files without those lines. Both copies compile (Kotlin/JS guest; Android host) | **PASS**: the bodies are identical; only import and package lines differ. The JVM sample's equivalent is checked in CI (`onlyImportsDiffer`) |
| Screenshots match | Loaded state, each screen opened cold, captured with `screencap` after its content was drawn, compared pixel by pixel below the status bar (its clock changes). B against native: threshold 0 differing pixels. A against both: by eye (today's layouts are Yoga's, not Compose's) | **PASS**: B and native are identical (0 differing pixels). A shows the same content and design with small offsets in padding and card placement |
| No host overrides | Count of hand-written renderers for generated widgets | **PASS**: none. Every generated widget and modifier is drawn by generated code calling the real Compose function. Hand-written once: the value converters, the children/scope runtime, and the one Keliver extra (the shimmer). The host app's change: register the generated widget system and root protocol (two lines) |
| First frame and open time no slower than A | TIMING_METHOD | TIMING_RESULT |
| A new component costs minutes | Add one Compose function outside the set: one line in the generator's config, regenerate, compile, render | **PASS**: `FlowRow` (a new layout with a new scope) went from the config line to a pixel-identical render (JVM `SameSourceTest`) in under 2 minutes. Caveat: a function whose default expressions name `*Defaults` objects (for example `HorizontalDivider`'s `DividerDefaults.Thickness`) also needs those objects mirrored; the generator does not write them yet (Gate B) |
| Also: bundle size | The signed Production bundle with route B, against the same guest without it | BUNDLE_RESULT |
| Also: case-3 parameters (§5 of the design) | Counted by the generator over the generated set | 0. Of the defaults: 46 literal (copied), 7 expressions copied to both sides (`LocalTextStyle.current`, `LocalContentColor.current`, `DefaultAlpha`, `DefaultFilterQuality`, `elevation > 0.dp`, `DefaultShadowColor` twice), none needing the presence split |

Interaction, on the emulator: on B, tapping a filter chip and an order card ran the guest's
lambdas through the generated `clickable` (the modifier-event channel, design §8): the list
filtered, and the card opened the order's status screen. The screen scrolls (host-owned
`ScrollState`).

## Found on the way (all fixed or recorded)

- **The guest's fast protocol path** (Kotlin/JS `encodeToDynamic`) flattened a codec's array value
  (an `Alignment`'s `[h, v]`) to its first element, because the codecs declared a primitive
  descriptor kind. Found on the emulator; fixed (a contextual kind) and covered by a JS test.
- **A circular initialisation** between a mirror's companion and a top-level constant gave
  `DefaultShadowColor` the value 0 on the JVM. Fixed; the JVM same-source test caught it.
- **Spot shadows depend on where in the window they are drawn**, so the JVM comparison draws native
  and guest in the same place, one after the other.
- **Redwood's modifier code generation cannot take a nullable property** (it writes `T?::class`),
  so nullable modifier parameters (`clickable`'s `onClickLabel` and `role`) are left out of the
  stubs for now and kept in an ERROR overload. A change to `keliver-tooling-codegen`, not made here.
- **Scope supertypes are not mirrored** (`FlowRowScope : RowScope`), so `Modifier.weight` inside a
  guest `FlowRow` does not resolve yet.
- **`*Defaults` objects** referenced by default expressions need mirrors (above).
- **`inline` stubs**: Compose's `Row`, `Column` and `Box` are inline; the stubs are not (Redwood's
  children node is not inline), so a `return` from inside their content does not compile in a guest.

## What this does not show (Gate B)

The iOS simulator and a phone; the editor (ingest, 0 `RawCode`, write-back); lazy lists, state
objects beyond a scroll position, slots with parameters, animation timing, `Canvas`; the D2 gate's
extension in the hosts and publishing paths; app size with widget groups; an agent writing a screen;
the upgraded Kotlin and Compose versions; a release-signed build.

## Decision

GATE_DECISION
