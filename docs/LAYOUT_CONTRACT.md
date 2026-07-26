# Keliver sizing and layout contract

**Status:** current behavior, pinned by K2a on 2026-07-26.

This document answers one question: when a screen says “wrap,” “fill,” or “use
this size,” which mechanism owns that decision?

K2a does not deprecate or change an API. It names the existing semantic layers
so new code has one obvious mechanism for each job. Any convergence of legacy
properties is a later, versioned change.

## The short rule

| Intent | Mechanism |
|---|---|
| Size a `Row`, `Column`, or layout `Box` itself | `width` / `height` with `Constraint` |
| Lay out children inside those containers | parent alignment plus layout-scoped child modifiers |
| Size or decorate a material/non-layout widget | universal `Modifier` chain |
| Make an image a full-bleed, aspect-scaled banner | `AsyncImage(fillWidth = true)` |
| Existing `StyledBox(fillWidth = true)` code | supported legacy convenience; do not combine with `widthDp` |

For portal-authored screens, `Constraint`, parent alignment, and universal
modifiers are in the recognized grammar. Layout-scoped child modifiers are
framework APIs but are not currently portal grammar; using one in a portal-owned
screen can preserve that expression as `RawCode`.

## 1. Container size: `Constraint`

`Row`, `Column`, and layout `Box` default both axes to `Constraint.Wrap`.

- `Wrap` follows measured content on that axis.
- `Fill` consumes the finite space offered by the parent on that axis.
- `Fill` cannot invent a size under unbounded constraints. Content or another
  explicit constraint must provide it.
- These properties size the container, not its children.

```kotlin
Column(
  width = Constraint.Fill,
  height = Constraint.Wrap,
) {
  // Full available width; height follows the children.
}
```

A screen root normally fills width and wraps height unless it deliberately owns
the whole viewport:

```kotlin
Column(width = Constraint.Fill) {
  // screen
}
```

`Constraint` is not an alias for `Modifier.fillWidth()`. The Compose host
receives universal modifiers first and appends the container's `Constraint`
behavior afterward. Consequently, mixing both sizing layers on one layout
widget can produce Compose-order interactions:

```kotlin
Column(
  modifier = Modifier.size(80, 60),
  width = Constraint.Fill,
  height = Constraint.Fill,
) { /* ... */ }
```

The incoming fixed size bounds the container at `80 × 60`; `Fill` consumes that
bounded space. Conversely, an incoming `Modifier.fillWidth()` can make a
`Constraint.Wrap` container occupy its parent's width while its content remains
start-aligned. Prefer `Constraint` for layout-container axes and avoid combining
the mechanisms.

## 2. Child layout: Yoga and parent scope

Containers use Yoga to measure and place their direct children.

- `CrossAxisAlignment.Start`, `Center`, and `End` keep a child at its measured
  cross-axis size and place it within the resolved container.
- `CrossAxisAlignment.Stretch` gives each child the resolved cross-axis size.
  It does not make a wrapping parent acquire otherwise unavailable space.
- `MainAxisAlignment` distributes already-resolved remaining main-axis space.
- Layout-scoped `width`, `height`, `size`, `grow`, `shrink`, `flex`, `margin`,
  and per-child alignment participate in Yoga before the child renderer.

```kotlin
Column(
  width = Constraint.Fill,
  horizontalAlignment = CrossAxisAlignment.Stretch,
) {
  StyledBox(colorArgb = 0xffeeeeee.toInt()) {
    // Stretched to the column's resolved width.
  }
}
```

For one child rather than every child, framework Kotlin can use a
`ColumnScope` modifier:

```kotlin
Column(width = Constraint.Fill) {
  StyledText(
    modifier = Modifier.width(160.dp),
    text = "Fixed Yoga child width",
  )
}
```

That scoped `Dp` modifier is distinct from the universal material
`Modifier.size(widthDp: Int, heightDp: Int)`.

## 3. Universal modifiers: ordered like Compose

Universal modifiers are available on every widget and are translated to real
Compose modifiers in declaration order:

```kotlin
StyledText(
  modifier = Modifier
    .fillWidth()
    .padding(16)
    .background(0xffeeeeee.toInt()),
  text = "Example",
)
```

Order is observable:

- `size(40, 30).fillWidth()` keeps the outer fixed size.
- `fillWidth().size(40, 30)` fills the bounded parent width.
- `size(40, 30).padding(10)` consumes padding inside the fixed size.
- `padding(10).size(40, 30)` produces a `60 × 50` outer footprint.

This is normal Compose outer-to-inner modifier behavior. Reordering a chain is
a semantic edit, not formatting.

Sizing modifiers still need bounded input:

- `fillWidth()` uses a finite maximum width from the parent.
- `fillMaxHeight()` uses a finite maximum height.
- `fillMaxSize()` needs both.
- `size(0, n)` or `size(n, 0)` leaves the zero axis unconstrained.

For a non-layout material widget, universal sizing modifiers are the preferred
authoring mechanism.

## 4. Widget-specific properties

Two current properties use the name `fillWidth`, but they are not equivalent.

### `StyledBox.fillWidth`

This is an older convenience that applies Compose `fillMaxWidth()` inside the
widget renderer. Existing code remains supported in K2a. New code should prefer
`modifier = Modifier.fillWidth()` because it participates visibly in the
ordered modifier chain.

Do not combine `fillWidth = true` with `widthDp`; conflicting widget-local
sizing is not a supported authoring pattern. Migration and deprecation, if any,
belong to K2b and require a release boundary.

### `AsyncImage.fillWidth`

This is semantic rather than just geometric. When true, the renderer:

1. ignores `widthDp`;
2. fills the available width; and
3. uses `ContentScale.FillWidth`, overriding `contentScale`.

Keep this property for full-bleed banners. A universal
`Modifier.fillWidth()` changes geometry only and deliberately does not select an
image scaling policy.

## 5. Control flow and components add no layout

Portal `Condition`, `Repeat`, and transparent component expansion do not add
containers. Their children occupy exactly the layout positions the equivalent
compiled Kotlin would occupy. K1 mechanically compares those semantic widget
trees; the tests listed below pin the host sizing rules.

## Mechanical evidence

- `ComposeUiSizingContractTest` measures the real Compose/Yoga path for:
  container `Wrap`/`Fill`, layout `Box`, bounded incoming size precedence,
  incoming fill interaction, and cross-axis stretch.
- `VisualModifierSizingContractTest` measures real universal modifier order for
  size, fill, and padding, plus the supported `StyledBox.fillWidth` geometry and
  `AsyncImage.fillWidth` precedence over `widthDp`.
- The existing `AbstractFlexContainerTest` and `AbstractBoxTest` Paparazzi
  matrices continue to cover alignment, nesting, scrolling, margins, flex, and
  dynamic updates.
- K1 separately gates compiled Kotlin against portal interpretation.

K3 remains responsible for fixed-environment Android, iOS, and web screenshots.
These unit tests prove Compose-host measurement rules; they do not claim
tri-platform pixel identity.
