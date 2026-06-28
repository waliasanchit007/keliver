# Portal — Rich Editor (design)

> Branch: `spike/keliver-web`. Builds on the portal shell (sub-project C).
> Approved by the user 2026-06-27.

## Goal

Turn the portal shell into a real editor: a **tree outline** with selection, a
**property panel** for the selected node, and **drag-drop** (palette→insert,
reorder) — all driving the shared tree, with the live canvas preview + export.

## Build in 4 layers (each verifiable)

1. Model & helpers + property catalog (`portal-core`) — compile-gated.
2. Tree outline panel + selection (DOM) — browser-verified.
3. Property panel (DOM) — browser-verified.
4. Drag-drop (HTML5 DnD) — verified via synthetic DOM events.

## Layer 1 — model, helpers, catalog (`portal-core`)

`WidgetNode` becomes a `data class` with a stable id (auto-assigned; `copy()`
preserves it). `RenderNode`/`exportKotlin` ignore `id`, so they are unaffected.

```kotlin
private var nodeIdCounter = 0
fun nextNodeId(): Int = ++nodeIdCounter

data class WidgetNode(
  val type: String,
  val props: Map<String, Any?> = emptyMap(),
  val children: List<WidgetNode> = emptyList(),
  val id: Int = nextNodeId(),
)
```

Immutable tree-edit helpers (preserve untouched nodes' ids via `copy`):

```kotlin
fun WidgetNode.findNode(id: Int): WidgetNode? =
  if (this.id == id) this else children.firstNotNullOfOrNull { it.findNode(id) }

fun WidgetNode.updateProps(id: Int, props: Map<String, Any?>): WidgetNode =
  if (this.id == id) copy(props = props) else copy(children = children.map { it.updateProps(id, props) })

fun WidgetNode.removeNode(id: Int): WidgetNode =
  copy(children = children.filter { it.id != id }.map { it.removeNode(id) })

fun WidgetNode.insertChild(parentId: Int, child: WidgetNode, index: Int): WidgetNode =
  if (this.id == parentId) copy(children = children.toMutableList().apply { add(index.coerceIn(0, size), child) })
  else copy(children = children.map { it.insertChild(parentId, child, index) })

fun WidgetNode.moveNode(id: Int, newParentId: Int, index: Int): WidgetNode {
  val node = findNode(id) ?: return this
  if (id == newParentId || node.findNode(newParentId) != null) return this // no move into self/descendant
  return removeNode(id).insertChild(newParentId, node, index)
}
```

Property catalog (curated subset):

```kotlin
enum class PropKind { Text, Int, Bool, Color, Double }
data class PropSpec(val name: String, val kind: PropKind, val label: String)

fun editableProps(type: String): List<PropSpec> = when (type) {
  "StyledText" -> listOf(PropSpec("text", PropKind.Text, "Text"), PropSpec("fontSize", PropKind.Int, "Font size"), PropSpec("bold", PropKind.Bool, "Bold"), PropSpec("colorArgb", PropKind.Color, "Color"))
  "Button" -> listOf(PropSpec("text", PropKind.Text, "Text"))
  "Spacer" -> listOf(PropSpec("height", PropKind.Double, "Height"))
  "AsyncImage" -> listOf(PropSpec("url", PropKind.Text, "URL"))
  "StyledBox" -> listOf(PropSpec("paddingDp", PropKind.Int, "Padding"), PropSpec("cornerRadiusDp", PropKind.Int, "Corner radius"), PropSpec("borderWidthDp", PropKind.Int, "Border width"), PropSpec("borderColorArgb", PropKind.Color, "Border color"))
  else -> emptyList()
}
```

## Layers 2–4 — the editor UI (`web-spike` `Portal.kt`)

A fixed-position **left editor panel** (palette · outline · properties · export)
over the full-bleed canvas preview. State: the module-level `portalTree`
(`MutableState<WidgetNode>`) plus a module-level `selectedId: Int?`. A `refresh()`
re-renders the outline + property containers from current state; tree edits go
`portalTree.value = portalTree.value.<helper>(...)` then
`Snapshot.sendApplyNotifications()` (preview) + `refresh()` (panel).

- **Outline** (layer 2): render the tree recursively as indented rows; clicking a
  row sets `selectedId` and re-renders (selected row highlighted). Each row shows
  `type` + a short label (e.g. its `text` prop).
- **Property panel** (layer 3): for the selected node, one input per `editableProps`
  entry — `Text`→text input, `Int`/`Double`→number, `Bool`→checkbox, `Color`→
  `<input type=color>` (hex ↔ ARGB Int). On `input`, `updateProps(selectedId,
  node.props + (name to value))`.
- **Drag-drop** (layer 4, HTML5 DnD): palette entries are `draggable` and on
  `dragstart` put `"new:<Type>"` in the dataTransfer; outline rows are `draggable`
  (`dragstart` → `"move:<id>"`) and drop targets (`dragover` preventDefault;
  `drop` reads the payload → `insertChild(targetId, newNode, end)` for `new:`, or
  `moveNode(id, targetId, end)` for `move:`). The existing click-add buttons stay.

Color helper: ARGB `Int` ↔ `#rrggbb` hex (drop alpha for the input; keep
`0xFF` alpha when writing back).

## Verification

- Layer 1: `:portal-core:compileKotlinJvm/WasmJs` green; the export verifier still
  compiles (`:web-spike-guest-compiler:run` + `compileKotlin`) — proves the model
  change didn't break A/B.
- Layers 2–3 (Chrome): click an outline row → it highlights + the property panel
  shows its fields; edit a field → the canvas preview updates live; read state via JS.
- Layer 4 (Chrome): dispatch synthetic `dragstart`/`dragover`/`drop` DOM events via
  `javascript_tool` (the automation has no drag gesture) and confirm the tree
  mutated (insert / reorder) + the preview updated.

## Out of scope (YAGNI)

Multi-select; undo/redo; nested-container drop precision (drop = append to target's
children, not positional within); copy/paste; keyboard nav; the cosmetic
export-panel-offscreen layout (separate follow-up); schema-generated catalog.

## Done when

In Chrome: select a node in the outline, edit its properties and see the preview
update, and drag a palette item / reorder a node (via synthetic events) and see the
tree + preview change — all on top of the working A-render and B-export.
