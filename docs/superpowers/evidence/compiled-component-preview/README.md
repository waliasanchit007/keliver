# Compiled component preview — browser session, 2026-10-20

What: the dogfood editor (`web-spike`, built from `main` at `84d0447b0`)
drawing an **opaque** project component (`NoteCountPill`, whose text is
computed with `removeSuffix`/`uppercase` — outside the portal grammar)
through the app's compiled composable, then selecting and editing it.

How (all local, loopback only; nothing published):
- a throwaway worktree of `main` with two instances added to the feed screen
  (`NoteCountPill(count = 3)`, `NoteCountPill(count = 1, label = "items")`)
  and `keliver.portal.json`'s port changed to 8277;
- `:web-spike:wasmJsBrowserDistribution` and `:portal-relay:installDist`;
  the relay run with `PORTAL_REPO` = the worktree and `PORTAL_STORE` = a
  scratch directory; the editor dist served on 127.0.0.1:8297 and opened as
  `?relay=8277`;
- headless Chrome driven over the DevTools protocol (screenshot, a mouse
  click on the first pill, then setting the Count field in the properties
  panel).

| File | Shows |
|---|---|
| `1-drawn-compiled.png` | both instances drawn compiled: "3 NOTES", "1 ITEM"; the palette marks the component `opaque` |
| `2-selected.png` | a click on the first pill selects it as one unit: properties say `NoteCountPill #5 · component (opaque)`, the outline highlights the instance |
| `3-edited.png` | Count set to 7: the canvas redraws "7 NOTES" |
| `feed-after-edit.diff` | the worktree's whole diff against `main` after the edit, so it shows the end state only: the harness's port change in `keliver.portal.json` (which also dropped that file's final newline), and in the feed screen the import and the two inserted calls with the first at `count = 7`. The 3 → 7 step itself is what screenshots 2 and 3 show; nothing else in the screen file changed |

Not shown: a device screenshot of the same screen (D14).
