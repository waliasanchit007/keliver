# keliver Web Portal — Design (roadmap + sub-project A)

> Branch: `spike/keliver-web`. Builds on the proven spike (gates 1–6, Phase A/B).
> All builds need `JAVA_HOME=$(/usr/libexec/java_home -v 17)`.

## Goal

A drag-drop **web portal** where you compose/edit keliver UI and it reflects live
on the web (and, later, on connected Android/iOS devices), with **Export to
Kotlin** producing the real keliver guest source you ship.

## Decisions (locked with the user, 2026-06-27)

1. **Destination:** the portal (full path), with the foundation as the means to it.
2. **Authoring model:** *runtime-driven live edit + Kotlin export*. The portal edits
   a declarative tree and drives a **live render with no recompile**; "Export to
   Kotlin" generates the durable guest source. Config-driven only while editing;
   never the thing you ship. (Honors "push Kotlin, not JSON" for the shipped
   artifact while getting truly instant edits.)
3. **MVP scope:** **web-first** — portal + instant *web* preview + export. Live
   phone sync is Milestone 2 and reuses the same tree-push.

## Why this is low-risk (grounding in the spike)

- The host renders arbitrary protocol `Change`s regardless of their source
  (gates 2/5) → a tree can drive it.
- The generic web host already works (gates 1–6).
- Network images / http on wasm work via the browser-fetch pattern (gate 6).
- The web target is first-class and CI-guarded (Phase A).

## Components

| Component | Responsibility |
|---|---|
| **Widget-tree model** | Serializable declarative tree; the single source of truth the portal edits. |
| **Runtime engine** | tree → live render, and tree-edit → live update. The keystone (sub-project A). |
| **`keliver-web-host` lib** | The generic web host (from `web-spike`) as a consumable artifact. |
| **Kotlin export** | Codegen: same tree → keliver guest `.kt` source. |
| **Portal UI** | Drag-drop / property editor web app; live preview pane + export button. |
| **Pushed-UI safety** | Versioning / validation / rollback for pushed trees (mostly M2). |

## Roadmap

```
M1 ─ A. Runtime engine + tree model      ← START HERE (keystone, highest uncertainty)
     B. Export-to-Kotlin (tree → .kt)
     C. Portal UI (drag/edit → live web preview; embeds A; export via B)
     D. keliver-web-host extracted as a library (folds into C)
   ─────────────────────────────────────────────────────────────
M2 ─ Device sync: push the SAME tree to a runtime guest on a phone (JS) + safety
```

**Key MVP simplification:** in M1 the portal and the preview are the **same web
app**, so an edit is an in-memory tree change → engine → canvas — **no network
transport in M1**. The WebSocket push appears only in M2 (remote devices).

---

## Sub-project A — Runtime engine + tree model (the first thing we build)

### Tree model

One generic node type, keyed by friendly **type name** (not protocol tags):

```kotlin
class WidgetNode(
  val type: String,                    // "StyledBox", "Button", "Column", ...
  val props: Map<String, Any?>,        // property name -> value (Int/String/List/…)
  val children: List<WidgetNode> = emptyList(),
)
```

In-memory `Any?` values are enough for M1 (portal + preview are one app, no
serialization). Serializable `JsonElement` values come in M2 when the tree is
pushed to remote devices.

### Render backend — `@Composable` interpreter (chosen over a raw protocol translator)

The engine is a guest `@Composable` that maps each node to the real keliver
composable, run as the **gate-5 browser-side guest composition** — the body just
becomes `RenderNode(tree)` instead of `MiniNudge()`, driven by a tree
`MutableState`:

```kotlin
@Composable fun RenderNode(node: WidgetNode) {
  when (node.type) {
    "StyledBox"  -> StyledBox(borderColorArgb = node.int("borderColorArgb"), …) { node.children.forEach { RenderNode(it) } }
    "Column"     -> Column(width = Constraint.Fill, …) { node.children.forEach { RenderNode(it) } }
    "StyledText" -> StyledText(text = node.str("text"), …)
    "Button"     -> Button(text = node.str("text"), onClick = { /* M1: noop */ })
    "AsyncImage" -> AsyncImage(url = node.str("url"))
    "Spacer"     -> Spacer(height = Dp(node.dbl("height")))
    "Row"        -> Row(…) { node.children.forEach { RenderNode(it) } }
  }
}
```

A tree edit = mutate the tree `MutableState` → the guest recomposes → Compose emits
the **minimal** protocol changes → host updates. No tags, no manual diff.

**Why interpreter, not a raw protocol translator:** the merged widget system
namespaces dependency-schema tags (e.g. `Column` is `keliver-layout`, offset inside
`KeliverMaterial`), so a tag-level translator means hand-writing offset tags —
error-prone. The interpreter calls real composables **by name**: correct by
construction, no tag math, reuses the proven gate-5 guest, and Compose diffs for
free. The per-widget `when()` is ~7 cases for the MVP subset and is **codegen-able
from the schema** when we scale — the *same* per-widget mapping also drives
Export-to-Kotlin (sub-project B), so it is written once conceptually.

### Widget subset (MVP: curated)

The interpreter covers the subset we've already exercised — **StyledBox, Column,
Row, StyledText, Button, AsyncImage, Spacer**. For engine A the per-widget
knowledge lives entirely in `RenderNode`'s `when()` (and the typed prop getters);
no tag table is needed. A separate *metadata* catalog (each widget's editable
properties + types, for the portal's property panels) is a **sub-project C**
concern, and generating it — and the `when()` — from the schema is **deferred**
until the hand-written subset proves the shape.

### Verification (browser harness)

In `web-spike`: build an in-memory `WidgetNode` tree, render it via the engine,
and provide a way to mutate it (e.g., a host button that changes a property /
adds a child). Confirm in Chrome (dev server + extension) that **editing the tree
updates the live preview** — the engine working end-to-end, headless of any
drag-drop UI.

### Out of scope for A (YAGNI)

Minimal-diff rendering; schema-generated catalog; events/interactivity bindings in
the tree (preview is portal-driven); persistence; the drag-drop UI; export; any
network/device transport.

## Open questions (deferred, not blocking A)

- Portal UI tech (Compose-for-Web vs a JS framework) — decide at sub-project C.
- Event/interaction model in the tree — design alongside export (B) / portal (C).
- Catalog-from-schema generation — after the hand-written catalog proves the shape.

## Done when (sub-project A)

A `WidgetNode` tree renders in the browser via the protocol-translator engine, and
mutating the tree updates the live preview — verified with real pixels in Chrome.
