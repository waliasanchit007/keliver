# Capability vocabulary and named personas

**Status:** implementation-ready design, 2026-07-26.
**Roadmap:** item #16, personas/capability fixtures only.
**Explicitly separate:** recorded HTTP/HAR replay.

## 1. Problem

Live preview already runs the app's real presenters. Its remaining state gap is
host-owned inputs: authentication, feature flags, analytics, and app-specific
domain state. Today each app can hardcode those values in its
`AppPreviewEntry`, but the editor cannot name, select, preserve, or report them.

That makes important states such as signed-out, logged-in, KYC-pending, or a
flagged rollout:

- implicit in app wiring;
- difficult to reproduce between engineers;
- unavailable as a flow start-state;
- invisible in the fidelity panel; and
- easy to accidentally share between preview runs.

The goal is deterministic real-presenter preview from a named app-owned state:

```text
select persona -> rebuild preview capability graph -> restart presenter/flow
               -> render real bindings -> dispatch real actions
```

## 2. Boundaries

This slice delivers:

1. a small, pure-Kotlin capability vocabulary for auth, flags, and analytics;
2. reusable in-memory fixtures for presenter tests and browser preview;
3. an app-owned named-persona catalog;
4. persona selection in the reusable editor shell;
5. persona identity in `PreviewEnv`, beside `flowStart`;
6. presenter/flow re-keying when the selected persona changes;
7. fidelity-panel evidence of the active fixture states; and
8. an in-repo dogfood using a real presenter.

This slice does not deliver:

- HTTP recording, matching, redaction, or replay;
- a design database or relay-owned persona files;
- editor-authored persona values;
- production credential/token storage;
- automatic reflection over an app's DI graph; or
- a generic key/value replacement for typed app capabilities.

Personas are Kotlin in the consumer app for the same reason screens and flows
are Kotlin: code review, typing, and one composition root remain authoritative.

## 3. Capability vocabulary

Add a publishable `:keliver-capabilities` module targeting JVM, iOS, JS, and
Wasm. It deliberately does not depend on Zipline.

The public vocabulary is:

```kotlin
interface HostAuth {
  val state: StateFlow<AuthState>
}

sealed interface AuthState {
  data object SignedOut : AuthState
  data class SignedIn(
    val subject: String,
    val displayName: String? = null,
    val attributes: Map<String, String> = emptyMap(),
  ) : AuthState
}

interface HostFlags {
  val values: StateFlow<Map<String, Boolean>>
  fun isEnabled(name: String): Boolean
}

interface HostAnalytics {
  fun track(event: AnalyticsEvent)
}

data class AnalyticsEvent(
  val name: String,
  val properties: Map<String, String> = emptyMap(),
)
```

These are the interfaces guest presenters and repositories consume. Device
composition roots adapt their real native/Zipline services to them at the edge,
as required by D11 and the existing SQL pattern. Keeping Zipline out is
load-bearing: the same interfaces must compile into the Wasm editor.

The module also provides:

- `FixtureHostAuth`, backed by `MutableStateFlow`;
- `FixtureHostFlags`, backed by `MutableStateFlow`;
- `RecordingHostAnalytics`, with deterministic recorded events; and
- the canonical capability-name constants `HostAuth@1`, `HostFlags@1`, and
  `HostAnalytics@1`.

Fixtures are useful outside the editor too: presenter unit tests consume the
same capability interfaces as production.

## 4. Persona contract

`portal-render` gains a small metadata/data type:

```kotlin
data class PreviewPersona(
  val id: String,
  val label: String = id,
  val description: String = "",
  val auth: AuthState = AuthState.SignedOut,
  val flags: Map<String, Boolean> = emptyMap(),
  val states: Map<String, String> = emptyMap(),
)
```

`states` names additional app-specific capability/domain fixtures without
pretending the framework understands their types:

```kotlin
PreviewPersona(
  id = "kyc-pending",
  label = "KYC pending",
  auth = AuthState.SignedIn("user-42", "Ari"),
  flags = mapOf("new-profile" to true),
  states = mapOf("HostKyc@1" to "pending"),
)
```

The app still maps `states` into its own typed capability implementation inside
its composition root. The generic map is editor metadata and fixture input, not
the runtime dependency API.

`AppPreviewEntry` gains:

```kotlin
val personas: List<PreviewPersona> get() = emptyList()
val defaultPersonaId: String? get() = personas.firstOrNull()?.id
```

Existing consumer editors remain source-compatible. Before mounting, the shell
validates that persona IDs are non-blank and unique and that the default exists.
An invalid catalog fails loudly with the app entry label.

`PreviewEnv` gains:

```kotlin
val persona: PreviewPersona? = null
```

The selected persona is supplied to both `ScreenPreview` and `FlowPreview`.
`flowStart` and `persona` are intentionally peers: one selects the navigation
node, the other selects the capability/domain start-state.

## 5. Lifecycle and determinism

The editor owns only the selected persona ID. The app owns fixture construction.

App wiring follows:

```kotlin
ScreenPreview { env ->
  val fixtures = remember(env.persona?.id) {
    capabilityFixtures(env.persona ?: SignedOutPersona)
  }
  val bindings = SettingsPresenter(
    fixtures.auth,
    fixtures.flags,
    fixtures.analytics,
  )
  PreviewFrame(...)
}
```

Changing persona while Live is active:

1. updates observable editor state;
2. clears the previous binding projection;
3. changes the presenter/flow composition key;
4. disposes the previous presenter and its effects;
5. creates a fresh app capability graph for the new persona; and
6. preserves the chosen flow and `flowStart`.

No SQL rows, presenter `remember` state, analytics events, or flow stack may
leak between persona keys. A persona switch is a deterministic cold start for
the live app scope represented by that editor composition.

Stopping Live does not forget the selected persona. Starting Live again uses
the visible selection.

## 6. Editor surface

When an app declares personas, the top bar shows a selector immediately before
the flow/Live controls. It displays persona labels and keeps IDs as stable
values. Apps with no personas retain the current UI.

The fidelity panel shows:

- active persona label and description;
- auth state;
- flag values;
- app-specific state labels; and
- whether every required host capability has either a normal preview provider
  or an active persona fixture.

`HostSqlDriver@1` remains the normal in-memory provider. Auth/flags/analytics
are full-fidelity only when the active persona supplies their fixtures. Unknown
capabilities remain reduced-fidelity.

## 7. In-repo dogfood

Konduit's `AppLibPreview` declares at least:

- `signed-out`;
- `field-researcher`; and
- `kyc-pending`.

The settings preview uses a real `SettingsPresenter` consuming `HostAuth`,
`HostFlags`, and `HostAnalytics`. Its visible binding changes with auth/flag
state, and its action records analytics before logging navigation. This proves
the selected persona changes a real presenter through typed capabilities rather
than branching directly on an editor string.

The Field Notes flow also receives the selected persona through `PreviewEnv`.
Its existing SQL flow scope remains unchanged; the persona is part of the flow
composition key so state cannot leak across personas.

## 8. Verification

Mechanical gates:

1. `:keliver-capabilities:allTests` — auth/flag mutation and analytics recording;
2. `:portal-render:allTests` — catalog validation, default/fallback resolution,
   and fixture-state descriptions;
3. `:portal-render:apiCheck`, `:portal-editor:apiCheck`, and root `apiCheck`;
4. `:portal-editor:compileKotlinWasmJs` and `:web-spike:wasmJsBrowserDistribution`;
5. focused browser test: select each persona, enter Live, observe different real
   settings presenter values and fidelity rows, switch persona while Live, and
   confirm state restarts;
6. flow browser test: start from `detail` under a persona and navigate/back;
7. Android and iOS device builds/renders to prove the published guest path is
   unaffected by the preview-only selector; and
8. docs/current-state update with exact evidence.

## 9. Follow-on: recorded HTTP

HAR replay is a separate design after personas are proven. It must define:

- request matching and deterministic ordering;
- secrets/PII redaction before persistence;
- fixture ownership, review, expiry, and refresh;
- miss behavior and reduced-fidelity reporting;
- streaming/binary limitations; and
- relay proxy trust boundaries.

Personas may later select an HTTP fixture set by name, but no HAR concern is
allowed to distort this slice's typed capability and lifecycle contract.
