# M4 falsification run 1 — captured artifacts (2026-09-05)

- `planted-home.kt` — the screen with F1/F2/F4/F5 planted, as ingested.
- `doc-response.json` — the verbatim `GET /doc` response for that screen.

Read `doc-response.json` before trusting any claim about what the semantic
channel exposes. In particular it contains **no mock values and no preview
row count**: the `Repeat` node carries only the `items` / `item` field names.
Preview row counts live in the editor's `PreviewBindings.mocks`
(`portal-render/.../PreviewBindings.kt`), which is editor-side state and is
not part of the `UiDocument`. Run 1 initially claimed otherwise; this capture
is what corrected it.

Not captured, and needed before any of run 1's conclusions can be relied on:
a device render, an interaction trace showing whether a control fires, and a
failure-and-fix assertion per case.
