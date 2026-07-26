# Editor/flow distribution productization — artifact graph

**Status:** IN BUILD 2026-07-24 (user picked; naming approved).
**Goal:** a consumer app builds its own portal editor from PUBLISHED artifacts —
no Keliver source checkout, no composite build.
**Boundary:** everything here is reversible. The Maven Central push is
IRREVERSIBLE and stays user-triggered (same as 0.3.0).

## 1. The gap (measured)

`:portal-editor` needs 14 project dependencies. **9 are already published**
(keliver-layout-compose, keliver-leak-detector, keliver-material-compose,
keliver-material-composeui, keliver-protocol, keliver-protocol-guest,
keliver-protocol-host, keliver-runtime, keliver-widget-composeui).

**5 are not**, plus `portal-editor` itself, plus `portal-flow` (an app that
declares flows compiles it into its guest):

| new artifact | today | notes |
|---|---|---|
| `portal-core` | unpublished | WidgetNode/catalog/exporter/component model |
| `portal-document` | unpublished | UiDocument + op engine |
| `portal-render` | unpublished | generated RenderNode + preview contracts |
| `portal-flow` | unpublished | FlowSpec + `flow{}` DSL (#13) |
| `portal-editor` | unpublished | the shell: `runPortalEditor(entry, flows)` |
| `keliver-material-protocol-host-web` | `web-spike-protocol-host` | RENAME |
| `keliver-material-protocol-guest-web` | `web-spike-protocol-guest` | RENAME |

`portal-sql` is the precedent: already published, same `dev.keliver` group.

## 2. Naming decision (approved)

The two protocol modules are spike-named but are really "keliver-material
protocol adapters for the web/wasm target." Publishing would bake `web-spike`
into a permanent public coordinate, so they are renamed:

- `web-spike-protocol-host` → **`keliver-material-protocol-host-web`**
- `web-spike-protocol-guest` → **`keliver-material-protocol-guest-web`**

`portal-*` modules keep their names (consistent with published `portal-sql`).

## 3. The real cost: explicit API mode

`redwoodBuild { publishing() }` does three things — Maven publish config,
**`explicitApi()`**, and **binary-compatibility validation (apiDump)**. The
portal modules were never written in explicit-API mode.

Measured on `portal-core`: **61 violations across 11 files**, split:

- **52 hand-written** (ComponentModel 13, WidgetTree 12, ComponentExpansion 7,
  Bindings 6, EditTree 5, CatalogTypes 4, Serialize 2, FlowGraph 2, ItemMocks 1)
- **9 generated** (GeneratedExporter 5, GeneratedCatalog 4) — these must be fixed
  in the GENERATOR (`portal-schema-codegen` Emit*.kt), never by hand-editing
  generated output, or the next `generatePortalCode` reverts them.

This is a feature, not a tax: explicit API forces us to state the SUPPORTED
surface. `portal-editor`'s real surface is `runPortalEditor(entry, flows)` plus
the preview/flow contracts; the DOM chrome and LiveEngine internals become
`internal`. That is exactly what a published editor shell should expose.

## 4. Phasing

- **D2 — rename** the two protocol modules (dirs, settings.gradle, all
  consumers). Gate: full compile green.
- **D3 — explicit API + publishing**, innermost-first so each layer compiles
  before the next: portal-core → portal-document → portal-render → portal-flow →
  portal-editor, then the two renamed protocol modules. Generated-file
  violations fixed in the codegen emitters (+ `checkPortalCode` stays green).
  Then `publishing()` + `apiDump` per module. Gate: `apiCheck` green.
- **D4 — the proof** (the gate that matters): `publishToMavenLocal` at a
  snapshot version, then flip `stashfin-sdui/editor/settings.gradle.kts` from
  `includeBuild(konduit)` to plain mavenLocal coordinates, DELETE the composite
  build, and verify the editor builds and runs in-browser (relay, ProfileScreen,
  components, ▶ Live). A consumer editor with zero Keliver checkout.
- **D5 — docs + scaffold**: `keliver-new-editor` emits published coordinates
  instead of a composite build; CURRENT_STATE/ROADMAP/PORTAL_USAGE updated;
  release checklist written for the user-triggered Central push.

## 5. Open risk

`portal-editor` is wasmJs-only and depends on Compose/skiko; publishing a
Compose-dependent wasm library is less trodden than the JVM/JS libs already
published. If `apiDump`/klib validation misbehaves on wasm-only targets, the
fallback is to publish the portal libs (core/document/render/flow) + the
protocol adapters, and ship the editor shell as source via the tools bundle —
still removing the composite build for everything but the shell. Decide only if
that failure actually occurs.
