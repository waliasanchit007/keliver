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

One generic node type (no per-widget classes):

```kotlin
class WidgetNode(
  val tag: Int,                                 // keliver widget tag (schema)
  val properties: Map<Int, JsonElement>,        // propertyTag -> encoded value
  val modifiers: List<ModifierElement>,         // protocol ModifierElement list
  val children: Map<Int, List<WidgetNode>>,     // childrenTag -> child nodes
)
```

It is generic over the schema because it speaks in tags + encoded values, exactly
like the protocol. The same node carries enough to drive **both** backends.

### Render backend — protocol translator (chosen over a Compose interpreter)

`render(tree)` walks the tree and emits protocol changes to the host, the same
pipeline the guest used in gates 2/5 — only sourced from a tree:

```
for each node (depth-first, assigning fresh Ids):
  Create(id, WidgetTag(node.tag))
  node.properties.forEach { (propTag, value) -> PropertyChange(id, widgetTag, PropertyTag(propTag), value) }
  if (node.modifiers.isNotEmpty()) ModifierChange(id, node.modifiers)
  node.children.forEach { (childrenTag, kids) -> kids.forEachIndexed { i, kid -> ChildrenChange.Add(id, ChildrenTag(childrenTag), kid.id, i) } }
→ changes.map { UiChange.fromProtocol(hostProtocol, it) } → hostAdapter.sendChanges(...)
```

On a tree edit (MVP): **rebuild** — fresh `ComposeWidgetChildren` root + re-emit the
whole tree (like gate 4 rebuilt on change). Minimal-diff is a later optimization.

**Why translator, not a `@Composable` interpreter:** the protocol layer is already
tag-based, so the translator is generic over all ~60 widgets with **zero
per-widget code**; it reuses exactly what we proved; and it makes "render" and
"export" two clean backends over one tree. A Compose interpreter would need a
generated tag→composable dispatcher and generic typed-property plumbing — more
code and fidelity risk, no upside for a preview.

### Widget catalog (MVP: curated, hand-written)

A small catalog describing the subset we've already exercised — **StyledBox,
Column, Row, StyledText, Button, AsyncImage, Spacer** — giving for each: widget
name, widget tag, and its properties (name, tag, value encoder). Sources of truth
are the `@Widget(n)` / `@Property(n)` tags in `keliver-material-schema`
(`KeliverMaterial.kt`). This catalog is what lets a tree be built and (later)
exported by friendly names. **Deferred:** generating the catalog from the schema
(so all widgets appear automatically).

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
