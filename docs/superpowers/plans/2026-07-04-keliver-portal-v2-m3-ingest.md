# V2 M3: Kotlin Ingest Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:executing-plans. Checkbox steps.

**Goal:** Editing the screen `.kt` in ANY editor (or by any AI agent) goes live everywhere: file-watch → headless PSI recognizer → identity-preserving reconcile into the live Document → SSE/projection fan-out. The `.kt` file becomes the source of truth.

**Architecture:** New JVM module `portal-ingest` (kotlin-compiler-embeddable per spikes S1/S2): `PsiEnv` (resident environment + mutation EPs, boot once), `Recognizer` (KtFile → parsed DocNode tree + Contract; catalog-grounded arg mapping; anything unrecognized → RawCode with kindHint — code never lost), `Reconciler` (pure function: old UiDocument + parsed tree → new UiDocument PRESERVING matched handles — explicitId → type+order matching; new nodes get fresh handles). `portal-relay` watches `~/.keliver-portal/kotlin/**` with io.methvin directory-watcher (fsevents); self-writes suppressed by content hash; `DocumentService.acceptExternal` bumps version, clears undo stacks (the file is the new baseline — file edits aren't undoable ops), broadcasts.

**Key semantics:** file-originated changes do NOT trigger the .kt dual-write (no clobber loop) and do NOT create undo entries. Editor/agent ops still regenerate the file whole (documented M1 caveat until M4).

**Tasks**
1. `portal-ingest` module: PsiEnv (S1/S2 recipe) + Recognizer + tests — golden recognize of a real dual-written screen (widgets/props incl. listOf + Dp + enum names, binds, `b::action`, modifier chains, contract interface, unknown→RawCode w/ kindHint). THE round-trip gate: `exportKotlin(doc.toWidgetTree())` → recognize → reconcile onto the same doc → document equality (types/props/modifiers/contract; matched handles preserved).
2. Reconciler + tests: handle preservation across prop edits/inserts/deletes/reorders; new-node allocation from nextHandle.
3. Relay wiring: directory-watcher on the kotlin dir (debounced 300ms, hash-suppressed self-writes) → recognize → acceptExternal → SSE; skip dual-write when change is file-originated. End-to-end: `sed` the .kt → `/doc` reflects it; editor SSE refetch (browser spot-check).
4. Gate sweep + register/memory + push.
