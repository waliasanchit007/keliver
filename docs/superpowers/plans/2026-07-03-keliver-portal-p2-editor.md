# Portal Phase 2: Editor Redesign + Persistence — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:executing-plans. Executed autonomously (user AFK) as part of the all-phases SOTA push.

**Goal:** Replace the bare-DOM chrome with a professional Kotlin-DOM editor (design system, undo/redo, searchable 60-widget palette, modifier panel, delete/duplicate, device-frame presets, save state) and give the platform projects/screens/drafts persistence via `portal-server` (evolved from `portal-relay`), so refresh never loses work.

**Architecture:** `portal-relay`'s JDK HttpServer grows into the local-first portal-server: file-backed store (`~/.keliver-portal/<project>/<screen>.json` — spec allows SQLite *or* files; files keep the module dependency-free), REST endpoints for projects/screens/drafts, and the legacy `/tree` endpoint kept verbatim so the device guest keeps polling unchanged (it mirrors the ACTIVE screen's draft). The editor (`web-spike/Portal.kt` + new `PortalUi.kt`) is rebuilt on a small typed UI kit + one injected stylesheet (dark theme), a 3-pane layout, and an undo/redo snapshot stack over the immutable `WidgetNode` (already value-semantic — snapshots are free).

**Tech Stack:** kotlinx-browser DOM, injected CSS (no framework), JDK HttpServer + java.io files (no new deps).

**Key contracts (exact):**
- `GET /projects` → `["default", ...]`; `POST /projects` body=name → 204
- `GET /screens?project=P` → `["main", ...]`; `POST /screens?project=P` body=name → 204
- `GET /draft?project=P&screen=S` → tree JSON or `{}`; `PUT /draft?project=P&screen=S` body=tree JSON → 204 (also updates the active-screen mirror if P/S is active)
- `POST /active?project=P&screen=S` → 204 (marks which screen the device mirrors)
- `GET|POST /tree` → unchanged legacy: the ACTIVE screen's draft (device-compat)
- Store layout: `~/.keliver-portal/<project>/<screen>.json` + `~/.keliver-portal/active` (two lines: project, screen). `default/main` auto-created.

### Task 1: portal-server (evolve Relay.kt) + verify with curl
Files: `portal-relay/src/main/kotlin/Relay.kt` (rewrite), keep module name.
Steps: implement store + endpoints (CORS * on all; OPTIONS 204); boot auto-creates `default/main`; `/tree` GET serves active draft, POST writes it (device + legacy portal both keep working). Verify: curl create project `demo`, screen `home`, PUT draft, GET it back, GET /tree mirrors active, POST /active switches mirror. Commit.

### Task 2: Editor UI kit + stylesheet (`web-spike/src/wasmJsMain/kotlin/PortalUi.kt`)
Dark-theme design tokens (CSS vars), classes for panels/buttons/inputs/rows/tree-rows/chips, `Ui.el/button/input/select/section` helpers, one `installStylesheet()`. Commit with Task 3 (compile unit is the editor).

### Task 3: Portal.kt rebuild on the kit
3-pane grid (left: project/screen switcher + searchable grouped palette + outline; center: canvas in a device frame w/ size presets Phone/Tablet/Web; right: properties + modifiers + node ops), top bar (screen title breadcrumb · undo/redo · save state dot · Export). Undo/redo = `ArrayDeque<WidgetNode>` snapshots (cap 100), Cmd/Ctrl+Z + Shift+Cmd/Ctrl+Z via document keydown. Node ops: Delete (removeNode; guard root), Duplicate (deep copy w/ fresh ids). Modifier panel: add-modifier `<select>` from `modifierSpecs`, per-modifier prop inputs writing `"mod.<Name>.<prop>"` props, ✕ removes the modifier's props. Autosave: 400ms debounced `PUT /draft` (+ legacy `POST /tree` for the live device) + "Saved/Saving…" indicator; boot loads `GET /draft` for the active screen (fallback `initialTree()`). Project/screen switchers `POST /active` + reload draft. Palette: search input filters; click/drag adds via catalog `sampleProps`.
Steps: write code; `:web-spike:compileKotlinWasmJs` green; dist build green. Commit.

### Task 4: Browser verification (chrome-ext)
Fresh dist on :8096 (cache-bust). Verify: dark 3-pane layout renders; palette search filters (e.g. "sw" → Switch); add widgets; select→properties; edit prop → canvas updates; add modifier (Padding 16) → canvas + export reflect `Modifier.padding(16)`; undo reverts / redo reapplies (keyboard); delete node works; device-frame preset switches canvas width; save dot turns Saved; RELOAD PAGE → tree persists (the persistence proof); create screen `two` → blank, switch back → original intact. Device: emulator still mirrors the active screen (screenshot). Commit + memory update.

**Self-review:** contracts above are the single source of truth for both sides; `/tree` untouched for the device; no new deps anywhere; undo stack lives editor-side only (drafts store the latest state, not history) — accepted for P2.
