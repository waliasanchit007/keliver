# V2 M2: portal-mcp Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:executing-plans. Checkbox steps.

**Goal:** AI agents author screens through a first-class MCP surface over the M1 op engine — catalog-grounded, transactional, dry-runnable, with behavioral read tools.

**Architecture:** New JVM module `portal-mcp` — a stdio JSON-RPC 2.0 MCP server (no SDK dep; the protocol subset is ~4 methods) that fronts the running portal-server HTTP API (:8077). The server gains `dryRun` on `/ops` (validate + report without committing). Catalog JSON is built from portal-core's generated `widgetSpecs`/`modifierSpecs` in-process.

**Tech Stack:** Kotlin JVM, kotlinx-serialization JsonObject building, java.net.http.HttpClient, stdio.

**Tasks**

### Task 1: dryRun on the op engine
- `DocumentService.dryRun(batch)` = applyBatch without commit → OpAck(ok, version, error) (version unchanged).
- Route: `/ops?...&dryRun=1` dispatches to it. Smoke: invalid op → 422 + error, doc version unchanged; valid op with dryRun → ok:true, version NOT bumped, /doc unchanged.
- Commit.

### Task 2: portal-mcp module
- `portal-mcp/build.gradle`: kotlin-jvm + application (`dev.keliver.portal.mcp.MainKt`), deps: portalCore (catalog), portalDocument (types), kotlinx-serialization-json.
- `Mcp.kt`: stdio loop — read JSON-RPC lines; handle `initialize` (protocolVersion 2024-11-05, serverInfo portal-mcp), `notifications/initialized` (ignore), `tools/list`, `tools/call`; every response Content-Length-free line-delimited JSON (MCP stdio framing = one JSON object per line).
- `Tools.kt`: tool registry —

| tool | behavior |
|---|---|
| `get_catalog` | widgetSpecs + modifierSpecs as JSON (name/category/props{name,kind,label}/acceptsChildren/events + modifier specs) — grounds generation |
| `list_projects` / `list_screens(project)` | proxy GET /projects, /screens |
| `get_document(project,screen)` | proxy GET /doc (raw DocJson through) |
| `apply_ops(project,screen,batchJson,dryRun?)` | POST /ops (+&dryRun=1); returns ack; non-ok → isError with the steering text |
| `undo/redo(project,screen,session?)` | proxy POST |
| `find_usages(project,name)` | walk every screen's /doc; report screens+handles where a Bind(field==name) or Action(name==name) occurs |
| `device_screenshot` | best-effort `adb exec-out screencap -p` → base64 image content (isError if no device) |

- Commit.

### Task 3: end-to-end verification (scripted stdio session)
- Bash: pipe initialize + tools/list + get_catalog + apply_ops(dryRun invalid → steering error) + apply_ops(real insert) + get_document (sees it) + undo into the installed dist binary; assert outputs. Server must be running.
- Commit + register/memory update + push.

**Self-review:** covers spec §6 table minus `execute_action`/`get_action_log`/`screenshot(preview)` (M8-gated, documented) and `validate` (= apply_ops dryRun). MCP resource (usage guide) → include as `get_guide` tool returning docs/PORTAL_USAGE.md text — cheaper than resources support.
