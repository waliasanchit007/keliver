# UI consistency & predictability — concrete plan

**Status:** PLAN — for review, then build in order.
**Author:** agent, 2026-07-24.
**Trigger:** repeated `forEach` rows filled their card on Android/iOS but stopped
short on web (fixed in 37f94f239). The bug was NOT a platform difference — it
was the editor's interpreter inventing layout the device never had.

## 0. The goal, stated as a testable property

> A screen written once renders the same on Android, iOS and web — and the
> editor preview is a faithful proxy for all three.

Today that is a hope enforced by eyeballing screenshots. Every item below turns
one part of it into a mechanical gate.

## 1. What the bug taught us (the real risk model)

There are **three** implementations of "what this screen means", and they can
drift independently:

| # | Path | Runs |
|---|---|---|
| A | Compiled guest Kotlin (`forEach`, `if`, real composables) | devices, prod |
| B | Portal **interpreter** (`RenderNode` over a `WidgetNode` tree) | editor preview |
| C | Exported Kotlin (portal → `.kt`) | must re-compile to A |

C↔A is already gated (byte-idempotent round-trip tests). **B↔A was never gated**
— and that is exactly where the bug lived: B wrapped `Repeat`/`Condition`
children in a container that A does not have. Non-repeated rows looked fine,
which is why it read as a mysterious per-row glitch.

Second lesson: a previous fix (`ae40a15b0`, `ListItem.fillMaxWidth()`) treated
the same symptom at the wrong layer. v0.3.0 has no such call and fills correctly
on device. Symptom-level fixes in shared widgets are how platform behaviour
quietly diverges.

## 2. K1 — Preview↔Device parity gate (build first)

**Mechanically assert that B produces the same widget tree as A.**

Both are `@Composable` over the same widget system, and `keliver-*-testing`
already generates `WidgetValue` classes (`StyledTextValue`, `SurfaceValue`, …)
plus `toChangeList`. So this runs headless, in milliseconds, no device:

```
compiled     = render { ProfileScreen(fakeBindings) }        // path A
interpreted  = render { RenderNode(recognize(src).tree) }    // path B, mocks == fakeBindings
assertEquals(compiled, interpreted)                          // modulo preview-only decoration
```

Normalisation: strip the portal-internal `SelectionTag` modifier and
editor-only handles before comparing; everything else must match exactly.

**Fixture matrix** (each is a past or plausible drift point):
`Repeat` · `Condition` · leaf component · **slotted** component · nested
components · universal modifiers · a screen combining all of them.

Deliverables: a `portal-parity-test` source set, the fixtures, CI wiring.
Also re-evaluate `ListItem.fillMaxWidth()` under this gate — with the real cause
fixed it may now be redundant or actively wrong.

**Why first:** it prevents recurrence of the entire class, and it transitively
gates the recognizer, the interpreter, component expansion and the composables
in a single assertion.

## 3. K2 — One obvious way to size things

A developer can currently say "fill the width" **three** ways, with different
defaults and different layers of effect:

| mechanism | example | layer |
|---|---|---|
| layout parameter | `Column(width = Constraint.Fill)` (default `Wrap`) | Yoga flex |
| universal modifier | `Modifier.fillWidth()` (`FillWidth` in the catalog) | modifier chain |
| widget property | `StyledBox(fillWidth = true)` (2 widgets only) | per-widget Compose modifier |

This is the "is it really Compose-like?" question in concrete form: Compose has
one idiom (`Modifier.fillMaxWidth()`); keliver has three that interact with the
layout engine differently — the direct cause of hours of confusion above.

Plan (staged, because it is an API change):
1. **Document** the layout contract now: what `Constraint.Wrap/Fill` mean, what
   modifiers do, what a Yoga container does to children (cross-axis behaviour),
   and when `fillMaxWidth` inside a widget is a no-op (unbounded constraints).
2. **Pin** each documented rule with a `keliver-layout-testing` unit test.
3. **Converge** in the next minor: make universal modifiers canonical, keep
   layout params for genuine flex semantics, deprecate widget-specific
   `fillWidth` props with a replacement message. Portal palette offers one way.

## 4. K3 — Cross-platform render parity evidence

K1 proves the *tree* matches; it cannot prove Yoga/Compose lay it out the same
on each platform. So: a **layout kitchen-sink screen** exercising fill vs wrap,
nesting, alignment, spacing, text overflow, and images — rendered on the Android
emulator, the iOS simulator, and web, with screenshots committed as evidence and
re-run per release. Automate via the existing snapshot-testing module where it
fits; until then this is a cheap, honest, repeatable manual gate (both
simulators are already scriptable from this environment).

## 5. K4 — Make version skew visible (small, high signal)

The editor currently renders with whatever keliver version it was built
against; the device runs whatever the app resolves. During this session those
were `0.3.1-SNAPSHOT` and `0.3.0` — a legitimate source of "web looks different"
with **nothing in the UI saying so**.

Add to the fidelity panel: the editor's keliver version, the version the app
targets, and a warning when they differ. Cheap, and it removes a whole category
of false bug reports.

## 6. Order and rationale

1. **K1** — mechanical, prevents the class, gates everything else.
2. **K4** — tiny, removes false signals immediately.
3. **K3** — evidence for what K1 cannot cover (real per-platform layout).
4. **K2** — highest long-term DX value but an API change; document + pin first,
   converge in a minor release.

## 7. Open questions

1. Where should the parity tests live — a new `portal-parity-test` module, or
   inside `portal-render`'s test source set? (Proposal: new module, so it can
   depend on both the app-lib fixtures and the testing widget system.)
2. K3 automation: is `keliver-snapshot-testing` usable for wasm/iOS, or is
   committed-screenshot evidence the pragmatic v1? (Proposal: evidence v1.)
3. K2 convergence target — confirm universal modifiers as canonical before any
   deprecation lands.
