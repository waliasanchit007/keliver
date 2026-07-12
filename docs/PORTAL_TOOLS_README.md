# keliver-portal-tools

The keliver visual portal — server, editor, MCP agent surface, and scaffolder —
runnable against your own app repo without cloning keliver. Requires **Java 17+**
and **python3**.

```
bin/keliver-init <AppName> [dir]   # scaffold a new keliver SDUI project
bin/keliver-portal [app-dir]       # run the visual editor + server against an app
relay/bin/portal-relay             # the portal server alone
mcp/bin/portal-mcp                 # stdio MCP surface (for AI agents)
editor/                            # the wasm editor (static)
```

## Quick start

```bash
export PATH="$PWD/bin:$PATH"
keliver-init Acme && cd acme
keliver-portal .                   # open http://localhost:8096
```

`keliver-init` creates a standalone Gradle project whose screens
(`src/jsMain/kotlin/screens/`) are real Kotlin Compose against the published
`dev.keliver:*:0.3.0` artifacts — edit them in your IDE (native completion) or
visually in the browser; both stay in sync via `keliver.portal.json`.

No install at all? The hosted playground: **http://keliver.me/keliver/**

## What this bundle does / doesn't do

- **Does:** the web portal loop (author screens visually or in code, live
  preview, the op engine, `.kt` write-back, MCP) against any app dir.
- **Doesn't (yet):** compile/sign the production bundle or drive on-device
  preview — those run in your app's own Gradle. See the keliver repo's
  `docs/PORTAL_USAGE.md` and `docs/SCREEN_ARCHITECTURE.md` for the host wiring.
