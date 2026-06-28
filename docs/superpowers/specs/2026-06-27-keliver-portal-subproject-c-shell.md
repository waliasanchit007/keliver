# Portal Sub-project C — Portal Shell UI (design)

> Branch: `spike/keliver-web`. Builds on A (engine) + B (export).
> Designed/decided autonomously (user AFK, "go to next step"); recorded for review.

## Goal

The portal **shell**: a DOM chrome (palette + export) around the live wasm canvas
preview, sharing one tree. Proves the full portal loop — *edit a tree via UI →
live preview (engine A) + Export-to-Kotlin (B)* — end to end in the browser.

## Decision (tech)

**Kotlin DOM chrome (`kotlinx-browser`) + the wasm canvas preview, sharing a
module-level Compose tree state.** Why: real DOM controls are robust (sidestep the
canvas-button quirk seen in A); all-Kotlin means the chrome shares `WidgetNode` and
calls `exportKotlin` directly (no JS↔wasm bridge); no heavy new deps. Compose-HTML
and React are deferred. **MVP = the shell** (palette add / remove / export); full
drag-drop, a property editor, and a tree outline are the next increment.

## Architecture

```
 DOM chrome (kotlinx-browser)              wasm canvas (#ComposeTarget)
 ┌─────────────────────────┐
 │ [Add Text][Add Spacer]  │  writes      ┌───────────────────────────┐
 │ [Add Button][Remove]    │ ───────────► │ CanvasBasedWindow content │
 │ [Export Kotlin]         │  portalTree  │   RenderNode(portalTree)  │ (engine A)
 │ <pre> exported source   │ ◄─────────── │   → host canvas preview   │
 └─────────────────────────┘  exportKotlin└───────────────────────────┘
```

- **`portalTree: MutableState<WidgetNode>`** — module-level, so DOM event handlers
  (outside any composition) write it and the canvas composition (which reads
  `portalTree.value` via `RenderNode`) recomposes. After each write the handler
  calls `Snapshot.sendApplyNotifications()` so the canvas updates promptly.
- The editable column children are held in a `MutableList<WidgetNode>`; each edit
  rebuilds `portalTree` = `StyledBox { Column { children } }`.
- **Palette** buttons append a `StyledText` / `Spacer` / `Button` node; **Remove**
  drops the last; **Export Kotlin** sets a `<pre>` to `exportKotlin(portalTree.value)`.
- The canvas content drops the gate-5 host `RawButton`/auto-driver — the chrome now
  drives edits; the canvas shows only `root.Render()` (the preview).

## Verification (browser)

In Chrome (dev server + extension): the page shows the DOM toolbar above the canvas
preview. Clicking **Add Text/Spacer/Button** grows the live preview with that widget;
**Remove last** shrinks it; **Export Kotlin** fills the `<pre>` with valid keliver
Kotlin that matches the tree. DOM buttons are real elements, so clicks are reliable.

## Out of scope (YAGNI)

Real HTML5 drag-drop; a per-widget property editor / metadata catalog; a tree
outline panel; selection/reordering; persistence; multi-screen; device sync (M2).
These are the next increment on top of this shell.

## Done when

The DOM palette edits the tree, the canvas preview updates live, and Export shows
matching Kotlin — verified with screenshots in Chrome.
