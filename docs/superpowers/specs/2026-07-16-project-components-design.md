# Project Components ("molecules") — design & phased plan

Status: **planned** (C1–C4 below). Decision context: D9 (design systems integrate
as metadata; the grammar owns composition), roadmap #15 (transparent components)
and #17 (@PortalComponent catalog). This spec merges both into one mechanism.

## The problem

Today the palette offers only keliver's ~60 primitive widgets. A real app
immediately wants its own vocabulary — `SectionCard`, `MenuRow`, `AmountText` —
composed FROM those primitives, defined once, reused across screens, and:

1. callable from guest code like any `@Composable` (works today, trivially);
2. **insertable from the portal palette** (does not work today);
3. **editable** with a typed prop panel per instance (does not work today);
4. previewable in the canvas and renderable on devices identically.

## Core design: a component is a screen-shaped file with a parameter list

```kotlin
// guest/.../guest/components/MenuRow.kt          ← NEW portal-owned dir, sibling of screens/
@Composable
fun MenuRow(title: String, subtitle: String, icon: String, onClick: () -> Unit) {
  ListItem(
    headline = title,
    supporting = subtitle,
    leadingIcon = icon,
    trailingIcon = "KeyboardArrowRight",
    onClick = onClick,
  )
}
```

- **The signature IS the spec.** Params → typed props (`String`/`Int`/`Boolean`/
  `Double` → prop kinds; `() -> Unit` / `(T) -> Unit` → events). No annotation,
  no sidecar file, nothing to rot. (An optional `@PortalComponent(icon = "...")`
  annotation can later add palette polish — thumbnail, category — but absence
  never blocks recognition.)
- **The body is grammar over the params** — same recognizer, with the param
  names playing the role `b.<field>` plays in screens. A component whose body
  falls outside the grammar still registers (from its signature) but is
  **opaque**: insertable, prop-editable, previewed by executing nothing —
  a labeled placeholder box in the canvas, real rendering on devices. This is
  the D9 transparent/opaque split falling out of one mechanism.

### Instance semantics (the Figma rule)

A component USE in a screen is a single node: `MenuRow(title = "...", icon =
"...", onClick = { b.open("X") })`. In the portal:

- the instance shows the component's prop panel (from the spec);
- the instance's INTERNALS are not editable at the use site — you edit the
  DEFINITION (its own document, one level down), and every instance updates.
  Master/instance is what makes components a consistency tool instead of a
  copy-paste accelerant. "Detach instance" (inline the body as plain grammar)
  is an explicit editor action, later.

### How each layer handles a component call

| Layer | Mechanism |
|---|---|
| Recognizer | files under `components/` recognize as `ComponentDoc` (spec + body tree). Screen recognition treats a call to a known component name as `DocNode.Widget(type = componentName)` — needs the component registry available during screen ingest (relay owns both). |
| Relay | ingests/watches `components/` like `screens/`; `/components` endpoint serves specs + body trees; component docs get the same ops/undo/write-back engine (they ARE documents). |
| Exporter / write-back | a component call is a named-arg call — the EXISTING surgical machinery applies; type lookup falls back from the widget catalog to the component registry. |
| Editor palette | "Project components" section, fetched from `/components` at load; insert = a call node with the spec's defaults. |
| Preview (RenderNode) | transparent: macro-expand the body tree, substituting instance props for param binds and routing instance events; opaque: labeled placeholder. Expansion is recursive with a cycle guard (A uses B uses A → error chip). |
| Devices | nothing special — compiled Kotlin calls the real composable. Zero runtime cost, no protocol change, no widget tags consumed. |
| Contract | component params consume binds/actions at the USE site like any widget props — ContractWriteBack already handles new binds appearing there. |
| Click-to-select | an instance is ONE selectable node (its handle). Double-click-to-open-definition is the later affordance. |

## Why this shape (alternatives rejected)

- **Schema widgets per molecule** (add to keliver-material): consumes protocol
  tags, requires host releases per molecule, couples app vocabulary to the
  platform. Rejected — components must be app-owned and OTA-shipped.
- **Editor-side "saved subtrees" (snippets)**: no single definition, edits
  don't propagate, nothing usable from code. Rejected — violates git-canonical
  Kotlin (D4).
- **Annotation-required registry**: sidecar metadata rots; signature-derived
  specs can't. Annotation stays optional polish.

## Phases

- **C1 — recognizer + relay registry.** `ComponentDoc` recognition
  (signature→spec, body→tree), `components/` in the app model +
  `keliver.portal.json`, `/components` endpoint, boot scan + watcher parity,
  screen ingest consults the registry so component calls don't RawCode.
  Gate: round-trip test (component + screen using it, 0 RawCode, byte-stable).
- **C2 — exporter/write-back for uses + definitions.** Call-site emission,
  surgical edits on instance args, component-definition docs editable via the
  same ops (edit master → all screens re-render in preview).
  Gate: WriteBack-style byte tests at both levels.
- **C3 — editor + preview.** Palette section, prop panel from spec, RenderNode
  transparent expansion (+ opaque placeholder, cycle guard).
  Gate: Chrome-verified insert/edit/preview; device render via dev loop.
- **C4 — DX + dogfood.** `keliver-new-component` scaffold, docs
  (ARCHITECTURE.md component rules), Stashfin dogfood: extract `SectionCard` +
  `MenuRow` from ProfileScreen and rebuild its sections with them (the real
  test: the Profile gets SHORTER and stays 0-RawCode).
- Later (unchanged from roadmap #17): opaque design-system integration at
  scale — annotation-driven thumbnails/categories, DS-team-owned catalogs.

## Risks / open questions

- **Screen ingest ordering**: a screen referencing a component ingested later
  must re-reconcile when the registry updates (relay: re-ingest dependent
  screens on component change — the registry knows the dependency edge).
- **Param defaults** (`icon: String = "Star"`): recognize into the spec so the
  palette insert can omit them; emission omits args equal to defaults.
- **Slot params** (`content: @Composable () -> Unit`): defer to C-later;
  v1 components are leaf-composites (no children slots). This covers the
  molecule case (rows, cards, headers) without opening the slot-grammar can.
