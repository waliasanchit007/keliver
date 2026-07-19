# Typed Routes + derived nav graph + flow preview (Roadmap #13, folding FlowScope #14)

**Status:** DECIDED 2026-07-20 (user delegated: "decide and start the voyage").
**F1 DONE + LIVE-VERIFIED** (a171f3d00): flow{} DSL, FlowRecognizer (3/3 tests,
Login→OTP→Dashboard fixture, both edge kinds + literal precedence), /flow served
the derived graph `feed --openNote--> detail` from the real recognized trees.
**F2 code-complete** (43ffa5c3c) — in-browser walkthrough gate pending.
**Author:** agent, 2026-07-19. **Reviewer:** (you).

## 0. DECISIONS (2026-07-20)

1. **Route declaration — v1 is STRING TOKENS via a tiny `flow{}` DSL**, not the
   sealed interface. Rationale: screens ALREADY carry the tokens
   (`b.open("SETTINGS")` — P1-4 literal args), so tokens need zero new screen
   grammar; the DSL is trivially PSI-derivable AND runtime-real (one declaration
   serves the relay's static graph and the app's runtime nav); sealed Route
   interfaces return in F4 as typed sugar when route PARAMS land (`Help(topic)`).
   Deviation from §3.1 recorded deliberately — the sealed form derived poorly
   (token→Route mapping lives inside presenter `when`s, fragile to parse).
2. **Separate `appFlowEntry`** beside `appPreviewEntry` — screen-only apps stay
   untouched (§Q2 proposal, confirmed).
3. **Back-stack** nav model (§Q3 proposal, confirmed).
4. **Derived edges**, with one widening: a route key matches a nav action's
   LITERAL ARG (`open("SETTINGS")`) **or its ACTION NAME** (`openNote(item.id)` —
   dynamic data args can't be tokens; the action name is the stable edge label).
5. **F2 first cut = screen-swap-on-nav + a minimal Flow select** in the chrome.
   The visual graph is F3.

The flow declaration (dogfood):

```kotlin
// portal-app-lib/src/commonMain/kotlin/flows/FieldNotesFlow.kt
val FieldNotesFlow = flow("FieldNotes", start = "feed") {
  route("openNote", to = "detail")   // action-name edge (dynamic arg = note id)
}
```

## 1. Why

The portal edits and previews ONE screen. Real apps are **flows**: Login → OTP →
Dashboard; a list → detail → back; Profile → "App Settings" → Settings. Today
`▶ Live` runs a screen's presenter but a nav action (`b.open("SETTINGS")`) just
logs a "nav intent" to the console — the editor can't *walk* the flow. This is
the roadmap's declared next feature and the application-scale review's top gap:
**you can't design a flow you can't traverse.**

Goal: press a nav action in Live preview and the editor **navigates** to the
target screen's live presenter, with flow-lifetime state preserved — the same
UDF a native app runs, previewed in the browser.

## 2. What already exists (build on, don't reinvent)

- **Actions carry literal args** (P1-4): `Action("open", arg = "\"SETTINGS\"")`.
  The route is already in the tree — we just don't interpret it as navigation.
- **LiveEngine keys presenters by screen** (P3-12): `appPreviewEntry.screens[name]`;
  switching the active screen disposes the old presenter (cancels its effects)
  and composes the new one. **Navigation ≈ changing the active screen key.**
- **AppPreviewEntry** is the per-app seam: `Map<String, ScreenPreview>`.
- **Recognizer** produces `Contract.actionParams` (typed action args).

So flow preview is mostly: (a) a typed **Route → screen** binding, (b) a
**FlowScope** that owns the current route, (c) LiveEngine switching screens on a
route change instead of only on the dropdown.

## 3. Design

### 3.1 Route contract (app-authored, hand-owned like Bindings)

A feature declares its destinations as a sealed type — one place, typed, greppable:

```kotlin
sealed interface ProfileRoute {
  data object Profile : ProfileRoute
  data object Settings : ProfileRoute
  data object Notifications : ProfileRoute
  data class Help(val topic: String) : ProfileRoute   // params allowed
}
```

Each route maps to a **screen name + a bindings factory** in the app's
`AppFlowEntry` (mirrors `AppPreviewEntry`, hand-written, compiles in):

```kotlin
object ProfileFlow : AppFlowEntry {
  override val start = ProfileRoute.Profile
  override fun screenFor(route: Any) = when (route) {
    is ProfileRoute.Profile      -> "ProfileScreen"
    is ProfileRoute.Settings     -> "SettingsScreen"
    is ProfileRoute.Notifications-> "NotificationsScreen"
    is ProfileRoute.Help         -> "HelpScreen"
    else -> null
  }
}
```

Route params (`Help(topic)`) are how one screen serves many entries (detail
pages) — the FlowScope hands them to the screen's presenter.

### 3.2 FlowScope presenter (Roadmap #14, folded in)

The named pattern for "a parent presenter composing screen presenters, owning
flow-lifetime draft state." It holds `currentRoute` as state and exposes
`navigate(route)` / `back()`; child screen presenters get their nav intents
routed here:

```kotlin
@Composable
fun ProfileFlowScope(): FlowModel {
  var stack by remember { mutableStateOf(listOf<ProfileRoute>(ProfileRoute.Profile)) }
  return FlowModel(
    current = stack.last(),
    navigate = { stack = stack + it },
    back = { if (stack.size > 1) stack = stack.dropLast(1) },
  )
}
```

Flow-lifetime draft state (a multi-step form's accumulating input) lives here,
surviving screen switches — the thing per-screen presenters can't own.

### 3.3 Recognizer derives the nav graph (portal-ingest)

The recognizer already sees each screen's `Action(name, arg)`. New pass: for a
project with a Route sealed type + FlowEntry, map each nav action's literal arg
to its target screen via `screenFor`, producing edges:

```
ProfileScreen --open("SETTINGS")--> SettingsScreen
ProfileScreen --open("NOTIFS")----> NotificationsScreen
SettingsScreen --back-------------> (pop)
```

Served at a new `GET /flow?project=…` endpoint as `{nodes, edges}`. This is
DERIVED (no hand-maintained graph) — the same "source is truth" principle as the
contract.

### 3.4 Flow preview in the editor (the payoff)

Extend `AppPreviewEntry` (or a sibling `appFlowEntry`) so the editor can run a
FLOW, not just a screen:

- **▶ Live in flow mode:** LiveEngine composes the FlowScope; the previewed
  screen = `screenFor(flow.current)`. A nav action dispatched from the canvas
  (`open("SETTINGS")`) calls `flow.navigate(Settings)` → `current` changes →
  LiveEngine swaps the active screen key → the Settings presenter mounts and its
  tree (ingested by the relay) renders. **You just navigated, in the browser.**
- Back button (or a Back affordance) pops the stack.
- Flow-lifetime state persists across the switch (it's in the FlowScope, which
  LiveEngine keys by FLOW, not screen).

The relay must serve every screen's tree the flow can reach (it already ingests
all `screens/`, so `/doc?screen=SettingsScreen` is available) — the editor just
requests the target screen's tree on navigate.

### 3.5 Nav-graph visualization (editor chrome)

A "Flow" view: the derived graph as nodes (screens) + edges (nav actions).
Click a node → edit that screen. Click an edge → highlights the action. Start a
"walkthrough": pick a start route + (later) a persona, press Live, tap through.
v1 can be a simple DOM list ("From ProfileScreen: SETTINGS→SettingsScreen, …");
the visual graph is polish.

## 4. Phasing (C1–C4 style, each independently verifiable)

- **F1 — Model + derived graph (no UI).** `Route`/`FlowEntry`/`FlowModel` types
  in portal-core; recognizer nav-graph derivation; `/flow` endpoint. Gate: unit
  test on a fixture flow (Login→OTP→Dashboard) → expected edges; `/flow` JSON.
- **F2 — Flow preview engine.** LiveEngine flow mode (route change → screen
  swap, flow-lifetime state preserved); `appFlowEntry` seam. Gate: in the editor,
  ▶ Live on a flow, tap a nav action → target screen's real presenter renders;
  back pops. Verify in-browser (Field Notes: feed → detail → back is the
  ready-made 2-screen flow).
- **F3 — Editor flow view + walkthrough.** Nav-graph list/visual + start-route
  picker. Gate: click-through a 3-screen flow in the editor.
- **F4 — Personas / start-state (overlaps #16).** Begin a flow in a given
  capability/auth state. Deferred; designed here only as the seam.

Dogfood target: stashfin Profile → "App Settings" → a new `SettingsScreen`
(2-node flow), then feed→detail on konduit. Device parity: FlowScope is a normal
guest presenter, so it runs on device unchanged (like every Style-B presenter).

## 5. Open questions for review

1. **Route declaration:** sealed interface (typed, params, verbose) vs a simpler
   `enum`/string routes (less ceremony, no params)? Proposal: **sealed
   interface**, because detail screens need params and it greps well — but it's
   more boilerplate for tiny flows. Your call on the default.
2. **FlowEntry vs fold into AppPreviewEntry:** separate `appFlowEntry` (clean
   separation, one more file) vs extend `AppPreviewEntry` with an optional flow?
   Proposal: **separate**, so screen-only apps stay simple.
3. **Nav model:** back-stack (list) vs single-current + explicit graph edges?
   Proposal: **back-stack** — matches real apps, gives Back for free.
4. **How much does the recognizer INFER vs the app DECLARE?** Fully derive edges
   from `screenFor` + action args (zero hand-maintenance, but "SETTINGS"→screen
   mapping must be unambiguous), or let the app annotate nav actions? Proposal:
   **derive**, matching the contract philosophy.
5. **Scope of F2 first cut:** just screen-swap-on-nav (no visual graph, no
   personas) — is that a satisfying first demo, or do you want the graph view in
   the same increment?

## 6. Recommendation

Approve the model (§3.1–3.3) and the F1→F2 slice as the first build; F3/F4 after
seeing F2 live. I'd start with **F1 + a Login→OTP→Dashboard test fixture** (pure,
fast to verify) so the graph derivation is proven before touching the editor.

**Nothing is built yet — this is for your sign-off.**
