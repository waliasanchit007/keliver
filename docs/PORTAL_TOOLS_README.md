# keliver-portal-tools

The keliver visual portal — server, editor, MCP agent surface, and scaffolder —
runnable against your own app repo without cloning keliver. Requires **Java 17+**
and **python3**.

```
bin/keliver-init <AppName> [dir]   # scaffold a new keliver SDUI project
bin/keliver-portal [app-dir]       # run the visual editor + server against an app
bin/keliver-portal stop [app-dir]  # stop a running instance
bin/keliver-portal status [app-dir]# is it up?
relay/bin/portal-relay             # the portal server alone
mcp/bin/portal-mcp                 # stdio MCP surface (for AI agents)
editor/                            # the wasm editor (static)
```

`keliver-portal` waits for the server to actually answer before reporting
success, and prints the server log if it doesn't — it will not hand you a URL
for a server that failed to boot. If the app has its own editor (see
`keliver-new-editor.sh`), it is rebuilt and served so the preview runs your
**real presenters**; if that build fails the bundled generic editor is served
instead and the failure is reported. Add `--rerun-tasks` to force a clean editor
rebuild when webpack serves a stale distribution, or `--no-editor-build` to skip
it. Run state and logs live under `$TMPDIR/keliver-portal/<hash of app dir>`,
which is how `stop` shuts down exactly the processes this app started — a port
held by an unrelated project is reported, never killed.

## Quick start

```bash
export PATH="$PWD/bin:$PATH"
keliver-init Acme && cd acme
keliver-portal .                   # open http://localhost:8096
```

`keliver-init` creates a standalone Gradle project whose screens
(`src/jsMain/kotlin/screens/`) are real Kotlin Compose against the published
`dev.keliver:*:0.3.3` artifacts — edit them in your IDE (native completion) or
visually in the browser; both stay in sync via `keliver.portal.json`.

No install at all? The hosted playground: **http://keliver.me/keliver/**

## What this bundle does / doesn't do

- **Does:** the web portal loop (author screens visually or in code, live
  preview, the op engine, `.kt` write-back, MCP) against any app dir.
- **Doesn't (yet):** compile/sign the production bundle or drive on-device
  preview — those run in your app's own Gradle. See the keliver repo's
  `docs/PORTAL_USAGE.md` and `docs/SCREEN_ARCHITECTURE.md` for the host wiring.

### bin/keliver-new-component.sh

Scaffold a project component ("molecule") built from keliver primitives:
`bin/keliver-new-component.sh MenuRow`. Reads `componentsDir` from
keliver.portal.json, derives the package from existing sources, and refuses to
overwrite. It appears under "Project components" in the editor palette and is
callable from any screen. Add `--slot` (`--slot SectionCard`) to scaffold a
container with one required editable trailing content slot. (Companion to
`bin/keliver-new-screen.sh`.)
