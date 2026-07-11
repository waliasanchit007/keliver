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
the app repo it serves — `port`, `screensDir`, `publishTask`, `publishOutput`,
`store`. Every field defaults to this repo's layout, so the file is optional
here and required only for a future split-out app repo (that split, and a full
`keliver init` new-project scaffolder, are deliberately deferred).

```bash
scripts/keliver-new-screen.sh Profile   # scaffolds screens/profile.kt + logic/ProfilePresenter.kt
```

## Dev runtime (M9 overlay)

Dev devices run the **compiled** screen (real logic + data) by default. While
you're editing a screen, they show a **live interpreter overlay** (with a badge)
for instant feedback; once `serveDevelopmentZipline` recompiles the bundle, the
overlay auto-discards and the compiled screen returns (**versioned catch-up**).
Prod mode runs compiled Kotlin only and rejects tampered bundles
(`codeLoadFailed: checksum mismatch`).
