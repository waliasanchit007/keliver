# Component slots — container molecules ("components v2")

**Status:** COMPLETE + WEB/ANDROID/iOS VERIFIED — 2026-07-23.
**Author:** agent, 2026-07-23. **Reviewer:** user approved implementation.

## 1. Why

Component v1 (C1–C4) is **leaf-only**: a component's `@Composable` signature
maps scalar params → editor props and `() -> Unit` params → events, and its body
is a fixed tree. That covers MenuRow / SectionHeader. But the **stashfin Profile
dogfood hit the wall immediately**: its white "section card" wrapper —

```kotlin
StyledBox(colorArgb = -1, cornerRadiusDp = 16, elevationDp = 2,
          borderColorArgb = -1183757, borderWidthDp = 1, fillWidth = true) {
  // …the section's rows…
}
```

— appears **5×** and is the app's canonical "card container," but it CAN'T be a
component because it wraps CONTENT. Real design systems are mostly containers
(Card, Section, ListGroup, Row-with-actions). Without slots, the component system
can capture atoms but not the layout molecules that carry a design system.

Goal: a component can declare a **children slot**, so `SectionCard(label="ACCOUNT"){ … }`
is one recognized, editable, previewable, exportable unit — with its slot content
still fully editable in the portal.

## 2. What exists (build on)

- `ComponentSpec(props, events, paramTypes, body, transparent, …)` — signature-
  derived, no annotations (D4).
- Recognizer: scalar params → `PropSpec`; `()->Unit`/`(T)->Unit` params →
  `ComponentEventSpec`; body → tree with param binds/actions.
- `expandForPreview(node, registry)` — substitutes instance args into the body.
- The **screen** grammar already recognizes a trailing `{ … }` block as children
  (every container widget does). Slots reuse that machinery.

## 3. Design

### 3.1 Recognizing a slot (the ONE new signature rule)

A parameter typed **`@Composable () -> Unit`** (optionally a receiver:
`@Composable ColumnScope.() -> Unit`) is a **SLOT**, not an event. The ONLY
discriminator is the `@Composable` annotation on the lambda type — an event
(`onClick: () -> Unit`) has none. New model:

```kotlin
data class ComponentSlotSpec(val name: String, val required: Boolean = true)

data class ComponentSpec(
  … existing …,
  val slots: List<ComponentSlotSpec> = emptyList(),  // NEW
)
```

v1 scope: **exactly one required** slot per component (the trailing-lambda content),
named by its parameter (conventionally `content`). Multiple/named slots
(`header`/`footer`) are a v2.1 follow-up — flagged opaque with a diagnostic for
now, never silently mis-recognized. Optional/defaulted slots are also opaque in
v2: exporting an omitted optional slot as `{}` could change its default
semantics. The slot invocation must sit inside a recognized grammar container;
a wrapperless pass-through slot is opaque so preview never invents layout.

### 3.2 The body's slot call site

In the definition body, the slot param is INVOKED — `content()` — at the place
children belong:

```kotlin
@Composable
fun SectionCard(label: String, content: @Composable () -> Unit) {
  StyledText(text = label, fontSize = 12, bold = true, colorArgb = -6643546)
  StyledBox(colorArgb = -1, cornerRadiusDp = 16, elevationDp = 2, fillWidth = true) {
    content()          // ← slot call site
  }
}
```

The recognizer records the body tree with a **`Slot` marker node** where
`content()` is called (a new `WidgetNode` type `"Slot"` carrying the slot name),
exactly as it records `RawCode` for unrecognized calls — no protocol change, it's
just another tree node type the renderer/exporter understand.

### 3.3 Instance recognition (screen side)

```kotlin
SectionCard(label = "ACCOUNT") {
  MenuRow(title = "Profile", subtitle = b.name, icon = "Person", onClick = { b.open("P") })
}
```

The recognizer already walks trailing `{ … }` into children for containers. For
a component call **with a slot**, it captures the lambda body's statements as the
instance node's **children** (recognized with the full registry, so nested
components/grammar work). So a `SectionCard` instance node = `type="SectionCard"`,
props `{label}`, `children=[MenuRow…]`. Zero new wire shape — component instances
gain children the same way containers have them.

### 3.4 Expansion (preview)

`expandForPreview` today substitutes prop/event args into the body. Add: when it
reaches the body's `Slot` marker node, **splice in the instance node's children**
(each recognized against the registry, so nested components expand too). Result:
a fully-primitive tree with the card wrapper AND the real rows — rendered live,
selectable, editable. Recursion + the existing cycle guard are unchanged.

### 3.5 Export (write-back)

`exportKotlin` emits a component instance call; for a slotted component it emits
the trailing lambda from the instance's children (reusing the container child-
emit path). Round-trips byte-stably like any container. Surgical write-back on
the children is the existing container path.

### 3.6 Editor

Component-instance nodes already render via the `componentPreview` hook
(expansion). With slots, the expansion includes the slot children, so they show
and are click-selectable (the instance carries the outer handle; children carry
their own — same tagging as Repeat rows). The palette drops a slotted component
as an empty-slot instance; the outline shows its children; dragging into the slot
inserts into the instance's children. Mostly falls out of treating the instance
as a container.

## 4. Phasing (each independently verifiable)

- **CS1 — model + recognizer (no UI).** `ComponentSlotSpec`; `@Composable`-
  lambda param → slot; body `Slot` marker; instance children capture. Gate:
  unit tests — recognize `SectionCard`, its spec has 1 slot + the body Slot node;
  recognize a `SectionCard{ MenuRow }` instance → children captured; a
  2-slot component recognizes OPAQUE with a diagnostic.
- **CS2 — expansion + export.** Slot splice in `expandForPreview`; export the
  trailing lambda. Gate: `expandForPreview` on a `SectionCard{MenuRow×2}` yields
  the wrapper + 2 ListItems; `exportKotlin` round-trips byte-stably (extend the
  ComponentRecognizerTest round-trip).
- **CS3 — editor + stashfin dogfood.** Palette/outline/insert for slotted
  instances; refactor stashfin Profile's 5 card wrappers into `SectionCard`.
  Gate: in stashfin's editor, Profile renders identically with SectionCard
  instances (0 RawCode), components expand, ▶ Live through them; then on-device
  (Android + iOS sims) — the card is a plain guest `@Composable`, renders native.
- **CS4 — scaffold + docs.** `keliver-new-component --slot` flag; PORTAL_USAGE +
  DECISIONS note (slots = the one v2 component capability).

D14 bar per screen: compile → 0 RawCode ingest → device render → surgical
write-back round-trip.

## 5. Resolved questions

1. **Slot discriminator:** `@Composable` on the lambda type is
   parseable from PSI at recognition time (we parse type text, not resolved
   types)? Proposal: match the annotation in the param's type reference text
   (`@Composable` prefix); ambiguous definitions fall back to opaque.
2. **One slot vs named slots:** v2 = single trailing-lambda slot; header/footer
   named slots are deferred.
3. **Slot marker node type:** a reserved `"Slot"` WidgetNode type (like
   `"RawCode"`/`"Condition"`/`"Repeat"`) is implemented.
4. **Receiver-scoped slots** (`ColumnScope.() -> Unit`) are recognized as slots;
   preview ignores the receiver while compiled device code retains it.
5. **CS3 scope:** render/edit and drag/click insertion shipped together.

## 6. Outcome

CS1–CS4 shipped as designed. The in-repo Settings screen and the motivating
Stashfin Profile both use SectionCard with zero RawCode. Web editor behavior,
real-presenter preview, palette insertion/undo, signed Android rendering, and
signed iOS rendering are recorded in `docs/CURRENT_STATE.md`.
