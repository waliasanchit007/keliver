# IDE setup — native-like completion for keliver screens

The short version: **there is nothing special to set up.** Keliver screens are
plain Kotlin files calling generated `@Composable` functions, so IntelliJ IDEA
and Android Studio give you the exact experience you have with native Compose —
completion, parameter hints, quick-doc, go-to-definition, refactoring.

## Why it works

The widget *schema* (`keliver-material-schema`) is the source of truth, but you
never write against the schema. The build generates real Kotlin from it:

- `dev.keliver.material.compose.StyledText(...)`, `Button(...)`, `Icon(...)` —
  guest-side `@Composable` functions with full signatures and defaults,
- KDoc from the schema's property documentation flows into the generated code,
- typed event lambdas (`onValueChange: ((String) -> Unit)?`) and typed
  modifiers (`Modifier.padding(8).cornerRadius(12)`).

So `Ctrl+Space` after `StyledText(` lists `text, fontSize, bold, colorArgb,
align, maxLines, overflow, lineHeightSp, letterSpacingX100, italic, underline,
strikethrough, weight, colorRole` — the same way `androidx.compose.material3.Text`
would.

## Working in this repo

Open the repo root in IntelliJ/Android Studio (it's a normal Gradle build).
Your app's screens live in `portal-app-lib/src/jsMain/kotlin/screens/` — a
module with ordinary dependencies on the generated compose modules. Everything
resolves after the first Gradle sync.

Tip: run `scripts/keliver-dev.sh` alongside the IDE — every save of a screen
file is ingested live into the portal editor and mirrored to dev devices
within ~1s (M3 file-watch), so the IDE **is** a first-class editor of the
same document the visual portal edits.

## Consuming published artifacts

Every `dev.keliver:*` artifact on Maven Central ships a `-sources.jar`
(verified: e.g. `keliver-material-compose-0.2.0-sources.jar`), so external
consumers get the same completion + docs without cloning this repo. Depend on:

```kotlin
implementation("dev.keliver:keliver-material-compose:0.2.0") // guest screens
```

## The portal grammar (the one thing worth memorizing)

Screens stay fully portal-editable when props are literals, binds, or one of
exactly three event shapes:

```kotlin
onClick = { b.addNote() }               // zero-arg action
onValueChange = { b.onDraftChange(it) } // the event's payload
onClick = { b.openNote(note.id) }       // item data, inside that item's forEach
```

Anything else is preserved verbatim as `RawCode` — never lost, just not
visually editable. `if (b.flag) { … }` and `b.items.forEach { item -> … }`
are recognized as editable Condition/Repeat nodes.

## AI-assisted editing

`portal-mcp` is a stdio MCP server over the same document engine — point
Claude Code / any MCP client at it and the agent gets `get_catalog`,
`get_document`, `apply_ops` (transactional, dry-run), `find_usages`:

```bash
PORTAL_REPO=$PWD portal-mcp/build/install/portal-mcp/bin/portal-mcp
```
