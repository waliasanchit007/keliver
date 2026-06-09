# keliver-material — Compose-parity SDUI widget library

**Status:** Design — approved to plan
**Date:** 2026-06-10
**Tracking:** Track A of the Stashfin SDUI program. Track B (the Stashfin
Android/iOS integration) consumes this library; it is specced separately.

---

## 1. Why this exists (and why it didn't, until now)

keliver's founding goal is to make writing **server-driven UI feel like writing
native Compose Multiplatform** — an adopter should write `AsyncImage`, `Card`,
`Switch` exactly as they would in Compose, with **no per-app widget schema**.
That requires a rich, batteries-included widget catalog shipped *in* keliver.

What actually shipped is the opposite. keliver inherited only Redwood's **10
primitive widgets** — `Row/Column/Box/Spacer` (`keliver-layout`),
`Text/Button/Image/TextInput` (`keliver-ui-basic`), `LazyRow/LazyColumn`
(`keliver-lazylayout`). Every richer widget was built inside the *demo apps'*
own schemas (`sample/schema` = 9 widgets; `ServerDrivenUI/schema` = 27 widgets,
incl. `AsyncImage`, `Card`, `Switch`, `Divider`, `Chip`, `BottomSheet`, …) and
**never promoted into the library**.

The drift had three causes, all visible in the project history:
1. **We followed Redwood's grain**, and Redwood deliberately ships only
   primitives and expects each app to own its widget schema — the opposite of a
   batteries-included catalog.
2. **Every milestone was infra/proof** (Maven Central publishing, the
   Konduit→Keliver rebrand, iOS parity, hot-reload, signed manifests, CI, the
   API-stability audit). "Ship the widget catalog" was never a *named*
   deliverable, so it was never scheduled — it fell into the gap between
   "keliver the library" and "the apps that prove it."
3. **Widgets got built where they were immediately needed**, as throwaway demo
   code, not library code. The thing that was actually the product got mistaken
   for scaffolding.

The infra was not wasted — a rich widget set on a broken pipeline is worthless,
and the pipeline is now solid (0.1.0 on Central, Android + iOS, hot-reload). But
the catalog should have been a first-class workstream the whole time. **This
spec is the correction.**

## 2. Goal & non-goals

**Goal.** A published keliver widget library — **`keliver-material`** — that an
adopter depends on to get the common Compose/Material3 widget surface as SDUI,
with **zero custom schema**, rendering on **Android + iOS** via Compose
Multiplatform. **Definition of done = A-to-Z parity:** every common
Compose/Material3 widget has a keliver equivalent.

**Non-goals.**
- **Pixel-exact replication of one app's design system.** keliver-material ships
  sensible Material3 defaults; apps override host-side rendering for their brand
  (§6).
- **App-shell / navigation-framework widgets** (`ScreenStack`, `BackHandler`,
  `AppScaffold`, `CoachGrid`) — app-specific composition, not generic widgets.
  They stay app-side.
- **Re-shipping keliver's existing primitives** — keliver-material *composes on
  top of* `keliver-layout` / `keliver-ui-basic` / `keliver-lazylayout`.

## 3. Architecture

### 3.1 Module structure (mirror `keliver-ui-basic`)
A 7-module group, identical in shape to the existing `keliver-ui-basic-*`:

| Module | Role |
|---|---|
| `keliver-material-api` | base widget API surface |
| `keliver-material-schema` | the `@Schema`/`@Widget` definitions — the wire contract |
| `keliver-material-compose` | generated guest composables (authoring side) |
| `keliver-material-widget` | generated host widget interfaces |
| `keliver-material-composeui` | hand-written `ComposeUi<Widget>` host renderers (`commonMain` → Android + iOS) |
| `keliver-material-modifiers` | any material-specific modifiers |
| `keliver-material-testing` | snapshot/test helpers |

Schema wiring follows `keliver-ui-basic-schema` exactly:

```groovy
apply plugin: 'dev.keliver.schema'
redwoodSchema { type = 'dev.keliver.material.KeliverMaterial' }
dependencies {
  api projects.keliverMaterialApi
  api projects.keliverUiBasicSchema     // compose on the basic widgets
  api projects.keliverLayoutSchema      // and the layout widgets
  api projects.keliverLazylayoutSchema
}
```

### 3.2 Host rendering
Each widget gets a hand-written `ComposeUi<Widget>.kt` in `commonMain` (a single
impl renders on Android + iOS via CMP), plus a generated `WidgetFactory`. These
are **ported from ServerDrivenUI's proven `composeApp/.../shared/Protocol.kt`**
implementations — already on-device-verified on both platforms — generalized to
Material3 defaults with exposed `@Property`/`@Modifier` for customization.
ServerDrivenUI feature worktrees carry additional, further-along widgets (e.g.
`PullToRefreshBox`); the implementation plan inventories all of them to pick the
most complete impl per widget before each batch.

### 3.3 WidgetSystem composition
The host assembles a combined `WidgetSystem` from `layout + lazylayout +
ui-basic + material` factories (keliver supports multi-schema widget systems;
`sample` and `ServerDrivenUI` already combine factories). Guest and host agree
on widget tags automatically because both consume the same published schemas.

### 3.4 Wire stability
Every `@Widget(n)` tag in `keliver-material-schema` is **wire-stable once
shipped**. We assign a fresh, contiguous ID space (starting at 1) and never
renumber; new widgets append. Enforced by binary-compatibility-validator
baselines on the schema/widget modules.

## 4. The widget inventory (A-to-Z target)

Legend: ✅ already in keliver · 🌱 Seed (Batch 0, proven in ServerDrivenUI/sample) ·
🎯 parity target (later batch).

**Layout & foundation**
- ✅ Row, Column, Box, Spacer (`keliver-layout`) · ✅ LazyRow, LazyColumn (`keliver-lazylayout`)
- 🎯 Surface, FlowRow, FlowColumn, LazyVerticalGrid, LazyHorizontalGrid, HorizontalPager, VerticalPager

**Text**
- ✅ Text (basic) · 🌱 StyledText (weight/size/color/align) · 🎯 SelectionContainer

**Buttons & actions**
- ✅ Button (basic) · 🌱 IconButton
- 🎯 OutlinedButton, TextButton, ElevatedButton, FilledTonalButton, FloatingActionButton, ExtendedFAB, SegmentedButton

**Inputs & selection**
- ✅ TextInput (basic) · 🌱 TextField, Switch, Checkbox
- 🎯 OutlinedTextField, RadioButton, TriStateCheckbox, Slider, RangeSlider, DropdownMenu / ExposedDropdownMenu

**Containers & surfaces**
- 🌱 Card · 🎯 ElevatedCard, OutlinedCard, Surface

**Images & icons**
- ✅ Image (basic) · 🌱 AsyncImage · 🎯 Icon

**Navigation & scaffolding**
- 🌱 ScrollableColumn
- 🎯 PullToRefresh (a reference impl exists in ServerDrivenUI WIP worktrees, not yet in its main schema), Scaffold, TopAppBar (+ CenterAligned/Medium/Large), BottomAppBar, NavigationBar + item, NavigationRail, TabRow + Tab, ScrollableTabRow

**Chips & badges**
- 🌱 Chip · 🎯 AssistChip, FilterChip, InputChip, SuggestionChip, Badge, BadgedBox

**Feedback & overlays**
- 🌱 BottomSheet (ModalBottomSheet), Divider
- 🎯 AlertDialog, Dialog, Snackbar, CircularProgressIndicator, LinearProgressIndicator, Tooltip, HorizontalDivider/VerticalDivider (M3 split)

> This list *is* the parity scope; exact names/props are refined per batch against
> the current Compose Material3 API. A live checklist tracks % complete toward
> "done = A-to-Z".

## 5. Delivery plan (incremental → A-to-Z)

Each batch = append `@Widget` defs + port/write `ComposeUi` impls + `apiDump` +
tests + dogfood in `sample/`. The 7-module scaffold is built once in Batch 0;
per-widget cost after is small.

- **Batch 0 — Foundation + Seed** *(first implementation plan)*: scaffold the 7
  modules; port the proven seed — `AsyncImage, Card, Switch, Checkbox, TextField,
  StyledText, Divider, Chip, IconButton, ScrollableColumn, BottomSheet` (11
  widgets, all verified on-device in ServerDrivenUI main / sample). Publish
  `keliver-material` = a usable set + the foundation. **Unblocks Track B (Stashfin).**
- **Batch 1 — Buttons & inputs parity:** button variants, FAB, RadioButton,
  Slider, OutlinedTextField, DropdownMenu.
- **Batch 2 — Containers & layout parity:** Surface, card variants, FlowRow/Column, grids.
- **Batch 3 — Navigation & scaffolding:** Scaffold, app bars, NavigationBar, Tabs.
- **Batch 4 — Feedback & overlays:** dialogs, Snackbar, progress, Tooltip, Pager.
- **Batch 5 — Chips, badges & cleanup:** chip variants, Badge, SegmentedButton,
  divider split, remaining gaps.
- **Done = A-to-Z:** all batches shipped; the §4 checklist hits 100%.

## 6. Styling & theming
keliver-material ships **Material3 defaults**. Apps needing their own design
system override the **host-side `composeui` impl** for a widget — no schema or
guest change (the schema is the style-agnostic contract; the composeui is the
rendering, same override path as `keliver-ui-basic`). Future enhancement: a
host-side `KeliverMaterialTheme` to retint defaults globally without per-widget
overrides.

## 7. Verification
- `apiDump` baselines for all new modules (wire + ABI stability), enforced in CI.
- Unit/snapshot tests via `keliver-material-testing` (mirror `ui-basic-testing`).
- **Dogfood:** add each batch's widgets to keliver `sample/` and render on an
  emulator/simulator.
- **Real proof:** Track B (Stashfin) renders the seed set on-device, Android first.

## 8. Risks & open questions
- **iOS rendering parity:** some Material3 components behave differently under CMP
  on iOS — verify each on a simulator. The seed widgets already render on iOS in
  ServerDrivenUI, de-risking the foundation.
- **Wire-ID discipline:** IDs are permanent once published; baselines + review enforce.
- **Scope creep within batches:** keep each batch shippable; defer exotic widgets
  (DatePicker, TimePicker, M3 adaptive/navigation-suite) to an explicit "Batch 6 —
  advanced" rather than blocking parity-core.
- **Name:** `keliver-material` (Material3 default rendering). Alternative
  `keliver-widgets` if we want to signal style-agnosticism. **Decision: keliver-material.**
- **Versioning:** ships under the `0.2.0` line; the seed can publish first, batches
  follow as `0.2.x` minors.
