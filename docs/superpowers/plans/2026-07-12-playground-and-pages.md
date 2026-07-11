# Playground Mode + GitHub Pages Deploy Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The wasm editor runs standalone (no portal-server) as a "playground" — local in-memory document engine with undo/redo — and deploys to GitHub Pages on every push to main, giving keliver a public "try it in the browser" URL once the user makes the repo public (their action; visibility changes are theirs alone).

**Architecture:** The editor already treats the server document as truth (`refetchDoc` → `UiDocument` → project to tree). Playground mode short-circuits that loop with a module-local `UiDocument` and the SAME `applyBatch` engine (unit-tested in portal-document): `sendOps` applies locally and pushes inverse batches onto local undo/redo stacks; `refetchDoc` re-projects the local doc. Detection: the startup `/doc` XHR fires `error` while `docVersion == -1` (never connected) → enter playground once. Pages: a standard `actions/deploy-pages` workflow building `:web-spike:wasmJsBrowserDistribution` on `ubuntu-latest` (free once public; queued/failing while private — expected).

**Tech Stack:** Kotlin/wasm (Portal.kt), portal-document apply engine, GitHub Actions Pages deploy. index.html already uses relative paths → safe under `/keliver/`. `docVersion: Long = -1` is the never-loaded sentinel. `applyBatch` does NOT bump version — bump manually.

---

### Task 1: Playground mode in Portal.kt

**Files:** Modify `web-spike/src/wasmJsMain/kotlin/Portal.kt`

- [ ] **Step 1: local engine state + entry** (near `docVersion`):

```kotlin
// ── Playground mode: no portal-server reachable (e.g. the GitHub Pages build).
// The SAME UiDocument apply/invert engine runs locally; edits stay in-memory.
private var playground = false
private var localDoc = UiDocument(
  "playground", DocNode.Widget(Handle(1), "Column"), Contract(), version = 0, nextHandle = 2,
)
private val localUndo = ArrayDeque<List<DocOp>>()
private val localRedo = ArrayDeque<List<DocOp>>()

private fun localRefresh(refreshPanels: Boolean) {
  docVersion = localDoc.version
  portalTree.value = localDoc.toWidgetTree(handleIds = true)
  Snapshot.sendApplyNotifications()
  if (refreshPanels) refresh()
}

private fun enterPlayground() {
  if (playground) return
  playground = true
  crumbEl.textContent = "playground — no server, edits are local (run scripts/keliver-dev.sh for the full loop)"
  Ui.toast("Playground mode: no portal-server — edits are local to this tab")
  localRefresh(true)
}
```
(`crumbEl` = whatever the topbar crumb element is named; match the file. Imports `UiDocument`, `Contract` may already exist — add as needed.)

- [ ] **Step 2: route ops locally** — at the top of `pumpOps()`:

```kotlin
  if (playground) {
    while (true) {
      val (ops, refreshPanels) = opQueue.removeFirstOrNull() ?: break
      val t = localDoc.applyBatch(ops)
      val r = t.result
      if (r == null) {
        Ui.toast(t.error ?: "edit rejected")
      } else {
        localDoc = r.doc.copy(version = localDoc.version + 1)
        localUndo.addLast(r.inverseBatch)
        localRedo.clear()
      }
      localRefresh(refreshPanels)
    }
    return
  }
```

- [ ] **Step 3: refetch + undo/redo + subscriptions honor playground** — top of `refetchDoc`: `if (playground) { localRefresh(refreshPanels); cb?.invoke(); return }`; detection in `refetchDoc`'s XHR: `xhr.addEventListener("error", { _ -> if (docVersion == -1L) enterPlayground() })`; top of `subscribeDocEvents` and `startVersionPoll`: `if (playground) return`; `undo()`/`redo()`:

```kotlin
private fun undo() {
  if (playground) {
    val inv = localUndo.removeLastOrNull() ?: return
    localDoc.applyBatch(inv).result?.let { r ->
      localDoc = r.doc.copy(version = localDoc.version + 1)
      localRedo.addLast(r.inverseBatch)
    }
    localRefresh(true)
    return
  }
  serverPost("/undo") { refetchDoc(true) }
}
```
(redo mirrors with the stacks swapped.)

- [ ] **Step 4: compile** — `./gradlew :web-spike:compileKotlinWasmJs` → green.
- [ ] **Step 5: commit** — `feat(editor): playground mode — server-less local document engine`

### Task 2: production dist builds + subpath sanity

- [ ] `./gradlew :web-spike:wasmJsBrowserDistribution` → green; `ls web-spike/build/dist/wasmJs/productionExecutable` shows `index.html`, `web-spike.js`, `*.wasm`; `grep -E 'src=|href=' index.html` shows only relative paths.

### Task 3: Pages workflow

**Files:** Create `.github/workflows/pages.yml`

```yaml
# Deploys the wasm portal editor (playground mode when no server) to GitHub Pages.
# Requires: repo public (or Pages-enabled plan) + Pages source = "GitHub Actions".
name: pages
on:
  push:
    branches: [main]
  workflow_dispatch: {}
permissions:
  contents: read
  pages: write
  id-token: write
concurrency:
  group: pages
  cancel-in-progress: true
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 17
      - run: ./gradlew :web-spike:wasmJsBrowserDistribution --no-daemon
      - uses: actions/configure-pages@v5
      - uses: actions/upload-pages-artifact@v3
        with:
          path: web-spike/build/dist/wasmJs/productionExecutable
  deploy:
    needs: build
    runs-on: ubuntu-latest
    environment:
      name: github-pages
      url: ${{ steps.deployment.outputs.page_url }}
    steps:
      - id: deployment
        uses: actions/deploy-pages@v4
```

- [ ] commit + push (workflow will be pending/failing while the repo is private — expected and documented).

### Task 4: docs + memory + report

- [ ] PORTAL_USAGE: playground section (what works: full editor + mock previews + export; what needs the server: publish/devices/ingest). Memory update. Final report includes the user's one command to flip visibility and where Pages settings live (Settings → Pages → Source: GitHub Actions).

## Self-review
- applyBatch inverse ordering: it returns inverses reversed (correct batch inverse); applying the inverse batch yields the prior doc — stacks store whole batches. ✓
- Version semantics: local bump per batch mirrors the server's one-version-per-batch. ✓
- Detection races: SSE/poll guarded by `playground`; `error` only enters when never connected (`docVersion == -1`), so a mid-session server crash keeps normal reconnect behavior. ✓
