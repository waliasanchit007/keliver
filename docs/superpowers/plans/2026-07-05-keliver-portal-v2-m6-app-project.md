# V2 M6: App Project Model Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:executing-plans. Checkbox steps.

**Goal:** A portal project IS a real Gradle guest project in git. The canonical screen `.kt` files live inside the guest module (`screens/`), presenters live beside them (`logic/`, hand-owned), and publish compiles the canonical project directly — the exported `generated/` projection dies.

**Architecture (design §7):** `portal-published-guest` becomes the reference app project:
```
portal-published-guest/src/jsMain/kotlin/
├── screens/   MainScreen.kt      ← canonical; portal-server watches + writes HERE (git-versioned)
├── logic/     MainPresenter.kt   ← hand-owned @Composable presenter returning the Bindings impl
└── PublishedEntry.kt             ← wires MainPresenter() into MainScreen(b) (hand-owned)
```
`portal-server` maps project "default" → `$PORTAL_REPO/portal-published-guest/src/jsMain/kotlin/screens` (fallback: the old `~/.keliver-portal/kotlin` dir when PORTAL_REPO unset). Screen name = file name. The dual-write, watcher, ingest, and surgical write-back all now operate on the in-repo file. Publish = gradle compile of the module AS-IS (no export step) → sign → versioned store (unchanged from P4 onward).

**Presenter-Bindings unification (Style B):** the generated screen keeps `fun MainScreen(b: MainScreenBindings)`; the presenter is `@Composable fun MainPresenter(): MainScreenBindings` producing state-backed bindings; `PublishedEntry` composes them. Engineers/agents own everything except the screen function + interface.

**Tasks**
1. Restructure portal-published-guest: move/replace `generated/` with `screens/MainScreen.kt` (seeded from the current default/main export), write `logic/MainPresenter.kt` + updated `PublishedEntry.kt`; delete the old generated dir + impl file; module compiles.
2. portal-server: `screensDirFor(project)` (PORTAL_REPO-based for "default", legacy fallback), watcher watches it, `docFor`/dual-write use it; publish drops the export step (compile canonical module only). Contract naming: screen fn = `<Screen>Screen`, e.g. `MainScreen` — writeKotlin functionName derives from screen name (capitalized) instead of hardcoded `PortalScreen`.
3. Recognizer/entry compatibility: recognizer already keys off @Composable + *Bindings — fn name free. Update publish smoke + editor: no editor changes needed (server-side mapping only).
4. E2E gates: (a) edit in portal → `screens/MainScreen.kt` in the REPO diffs surgically (git diff visible); (b) edit the repo file → editor live; (c) publish → compiled+signed bundle renders the presenter's real data in prod mode (device if available, else bundle store + manifest verification); (d) full sweep, commit, push, memory.
