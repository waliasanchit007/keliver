# Keliver Portal — Usage Guide

> **V2 update:** the portal is now **bidirectional** — the visual editor, any
> code editor, and AI agents all edit the same screen (one `UiDocument`, with
> the `.kt` file as the git-versioned source of truth). See
> `PORTAL_V2_COMPLETE.md` for the architecture. This guide covers the day-to-day
> workflow.

The portal's loop: **design a screen (in the browser, your IDE, or via an AI
agent) → see it live everywhere → publish it as compiled, signed Kotlin →
implement the data/logic behind the generated contract by hand.**

## Where the app lives (V2)

A portal project is a real Gradle project in git:

```
portal-app-lib/src/jsMain/kotlin/
├── screens/  main.kt          ← canonical, portal-editable (edit here OR in the portal — both sync)
│             Compiled_main.kt  ← generated version stamp (M9 overlay catch-up); don't edit
│             capabilities.txt  ← host capabilities this app needs (e.g. HostSqlDriver@1)
├── logic/    MainPresenter.kt  ← hand-owned presenter → produces the screen's Bindings (Style B)
│             TapStore.kt        ← hand-owned data layer (schema + queries ship OTA)
└── dev/keliver/portalpublished/PublishedEntry.kt  ← hand-owned: wires presenter → screen
```

**The round-trip boundary:** the portal owns `screens/*.kt` (layout + bindings +
`if`/`forEach`); engineers own `logic/` and `PublishedEntry.kt`. The portal
never touches the hand-owned files. Editing a screen in the portal produces a
**surgical git diff** in `screens/main.kt` — comments and any non-portal
(`RawCode`) constructs are preserved.

## Three ways to edit — all converge on the same document

1. **Portal UI** (`:8096`) — drag, tweak props, bind fields (`@`), wire actions,
   add `if`/`forEach` from the Logic palette.
2. **Any code editor** — edit `screens/main.kt` directly; the file-watcher
   ingests it and the portal + devices update live (no portal restart).
3. **AI agent** — the `portal-mcp` stdio server exposes `get_catalog`,
   `get_document`, `apply_ops` (transactional, `dryRun` to validate),
   `find_usages`, etc. Every widget/prop is catalog-grounded.

## Try it with zero install (playground)

The editor deploys to GitHub Pages (`.github/workflows/pages.yml`). With no
portal-server reachable it enters **playground mode**: the full editor — 60+
widget palette, props/modifiers, bindings, Repeat mock rows, undo/redo, Export
Kotlin — backed by a local in-browser document engine. Edits stay in the tab;
publish/devices/`.kt` sync need the real stack below.

## Start the stack

```bash
scripts/keliver-dev.sh            # portal-server + editor + live device bundle
scripts/keliver-dev.sh --android  # …and install + launch the Android host
```

Open **http://localhost:8096**. Ctrl-C stops everything. Everything you author
is saved into the app project (`portal-app-lib/src/jsMain/kotlin/screens/`).

## Layout / styling / bindings → edit the SCREEN (portal or file — both sync)

Add widgets, tweak props, attach modifiers, bind props with **@**, wire events
to named actions, drop `if`/`forEach` from the Logic palette. Every edit lands
in `screens/main.kt` (surgically — comments preserved).

- **Web preview:** updates as you type.
- **Android / iOS dev device:** mirrors the active screen within ~1s — no
  rebuild, no publish (the interpreter overlay renders the live tree):
  ```bash
  ./gradlew :portal-device-guest:serveDevelopmentZipline &
  ./gradlew :portal-device-android:installDebug
  adb shell am start -n dev.keliver.portaldevice/dev.keliver.portaldevice.host.MainActivity
  ```

## Events, lists & input (P2)

Events accept exactly three shapes — anything else becomes `RawCode` on purpose:

```kotlin
onClick = { b.addNote() }              // zero-arg
onValueChange = { b.onDraftChange(it) } // the event's payload (TextField text, Slider value…)
onClick = { b.openNote(note.id) }       // item-scoped data, inside that item's forEach
```

The generated Bindings interface types these for you (`fun onDraftChange(value:
String)` — param types come from the widget schema). In the editor, each event
row has a small **arg** input (`it` or `item.field`); item-carrying args also
add the field to the item interface (`Note.id`).

**Repeat preview mock rows:** the preview renders **3 mock rows** per `forEach`
by default. In the Bindings panel:

- the items field's mock (`notes`) sets the **row count** (`2` → two rows),
- an item field's mock (`note.title`) holds **per-row values**: `First|Second`
  (rows past the list clamp to the last; unmocked shows `{note.title} N`),
- a `Condition` field mocked `false` hides its branch in the preview.

The compiled device path always runs the real `forEach`/`if` — mocks are
preview-only.

## Data / logic → edit `logic/` (your hand-owned Kotlin)

`logic/MainPresenter.kt` produces the screen's `MainScreenBindings` (values +
action handlers); `logic/TapStore.kt` is the guest-owned data layer (schema +
queries — they ship OTA in the signed bundle; the host is just a SQL executor).
If a portal edit changes the contract (you bind a new field), the presenter
stops compiling until you add it — the round-trip boundary enforcing itself.

## Preview fidelity (M8)

Press **▶ Live** in the editor. The preview runs the **real logic** and
auto-substitutes a preview implementation per host capability
(`HostSqlDriver@1` → in-memory SQLite). The **Preview fidelity** panel shows
**Full** when every capability has a preview impl, or **Reduced** naming the
stubbed ones (Camera/BLE/…). The **State inspector** shows the live binding
values; **⚡ action** buttons fire wired actions.

Apps can also expose named preview personas through `AppPreviewEntry.personas`.
The app owns each persona's typed capability fixtures; the editor only remembers
the selected persona ID and reports its fixture state in **Preview fidelity**.
Changing persona while Live is active cold-starts the presenter or flow with a
fresh capability graph, so mutable fixture, SQL, and navigation state cannot
leak between personas. Apps that declare no personas keep the existing editor
UI and behavior.

## Ship it (Publish)

Hit **Publish** (or `curl -X POST localhost:8077/publish`). This compiles the
**canonical project as-is** (`screens/` + your `logic/`), **Ed25519-signs** the
manifest, and stores a versioned bundle with its required capabilities. Prod
devices resolve the newest **compatible** bundle at launch (widget-protocol +
host-capability gated) and verify the signature:

```bash
adb shell am start -n dev.keliver.portaldevice/dev.keliver.portaldevice.host.MainActivity --es mode prod
```

## Project config & scaffolding (separability groundwork)

`keliver.portal.json` at the repo root tells the portal-server everything about
the app repo it serves: `port`, `screensDir`, `componentsDir`, `flowsDir`,
`logicDirs`, `publishTask`, `publishOutput`, `store`, `previewBuildTask`,
`previewDist`, and `previewServeDir`. Path fields default relative to
`screensDir`, so the file can stay small. `PORTAL_REPO` can point one relay at
an external app checkout.

```bash
scripts/keliver-new-screen.sh Profile   # scaffolds screens/profile.kt + logic/ProfilePresenter.kt
scripts/keliver-new-component.sh MenuRow
scripts/keliver-new-component.sh --slot SectionCard
scripts/keliver-new-editor.sh MyApp     # consumer-owned real-presenter web editor
```

## Dev runtime (M9 overlay)

Dev devices run the **compiled** screen (real logic + data) by default. While
you're editing a screen, they show a **live interpreter overlay** (with a badge)
for instant feedback; once `serveDevelopmentZipline` recompiles the bundle, the
overlay auto-discards and the compiled screen returns (**versioned catch-up**).
Prod mode runs compiled Kotlin only and rejects tampered bundles
(`codeLoadFailed: checksum mismatch`).

## Project components (molecules)

Build reusable composables from keliver primitives under `components/` (a
sibling of `screens/`, or set `componentsDir` in keliver.portal.json). Their
Kotlin signature is the portal spec — screens call them, and the editor shows
them under **"Project components"** in the palette. See
`docs/SCREEN_ARCHITECTURE.md` §7 for the full model.

- Scaffold one: `scripts/keliver-new-component.sh MenuRow`
- Scaffold a container: `scripts/keliver-new-component.sh --slot SectionCard`
- Use it in a screen: `MenuRow(title = b.name, subtitle = "Account", onClick = { b.open("X") })`
- A container declares exactly one required `content: @Composable () -> Unit`,
  invokes `content()` exactly once inside a grammar container, and is used with
  a trailing lambda. Its children
  stay editable, selectable, draggable, exportable, and surgically writable.
- Edit the definition file → every instance's preview updates live (no reload).
- Endpoint: `GET /components?project=<p>` returns each spec + body tree.
- Optional/defaulted, wrapperless, and multiple slots are not guessed: those
  definitions are **opaque** with a diagnostic. Any other non-grammar body is likewise opaque (renders on
  devices, previews as a placeholder).

## Live-presenter preview (P3-12)

Press **▶ Live** on a screen whose project registered an `AppPreviewEntry`: the
REAL presenter runs in the browser against preview capabilities (SQL =
in-memory). Edit `logic/*.kt` → the relay rebuilds the preview binary
(debounced, single-flight) and the editor auto-reloads preserving your
project/screen/selection; a FAILED build keeps the last-known-good preview and
shows the error in the topbar chip. Config: `logicDirs`, `previewBuildTask`,
`previewDist`, `previewServeDir` in keliver.portal.json. See
SCREEN_ARCHITECTURE §8 for the entry-point contract.

The relay validates the generated `index.html`, JavaScript, and Wasm before
promotion. If Gradle reports success but the webpack distribution is absent or
incomplete, it retries once with `--rerun-tasks`; an incomplete retry is a
failed build and the last-known-good editor remains served.

## Flow authoring and preview (#13)

Declare app flows with the `portal-flow` `flow {}` DSL under `flows/` (or
`flowsDir`). The relay recognizes the declaration and derives graph edges from
the recognized screen actions. In the editor, choose a flow and press **▶ Live**
to walk it through real presenters; its back stack and flow-lifetime state stay
alive across screen changes. The graph overlay opens any screen, and each node's
▶ action starts the live flow from that node.
