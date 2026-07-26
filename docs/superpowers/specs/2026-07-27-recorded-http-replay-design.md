# Recorded HTTP replay

**Date:** 2026-07-27  
**Roadmap:** item #16, recorded HTTP follow-on  
**Status:** implementation-ready, phased  
**Predecessor:** `2026-07-26-capability-personas-design.md`

## 1. Decision

Build recorded HTTP in three bounded phases:

1. **H1 — deterministic, app-owned replay**: a checked-in fixture format,
   relay matcher/session engine, browser preview transport, persona selection,
   miss reporting, and one API-backed dogfood presenter;
2. **H2 — explicit recording proxy**: allowlisted upstreams, opt-in recording,
   secret injection at the relay edge, mandatory redaction, and atomic fixture
   refresh; and
3. **H3 — lifecycle tooling**: expiry warnings, refresh/diff commands, fixture
   coverage reporting, and HAR import/export where the wire model can represent
   the exchange without loss.

H1 comes first. A replay engine is deterministic and safe to prove entirely
with reviewed repository data. Recording is not hidden inside H1: accepting
credentials and persisting upstream responses changes the relay's trust
boundary and needs its own gate.

Roadmap #16 remains open until H2 is delivered. H1 is still a useful product
slice: real repositories and presenters can run against captured API states in
the editor without network access or endpoint-specific mocks.

## 2. Existing seams and the necessary split

Keliver already ships `dev.keliver.http.HostHttpProvider` and `KeliverHttp`.
That is the production device-edge wire described by D13. It extends
`ZiplineService`, so it cannot be referenced by app logic compiled into the
Wasm editor: Zipline has no Wasm target.

Do not replace or duplicate that wire. Add a small Zipline-free capability in
`:keliver-capabilities`:

```kotlin
const val HOST_HTTP_CAPABILITY = "HostHttp@1"

fun interface HostHttp {
  suspend fun execute(request: HostHttpRequest): HostHttpResponse
}
```

The request and response are text-only, serializable value objects matching the
existing provider's semantics: method, relative path, query, headers, optional
UTF-8 body; status, UTF-8 body, and headers.

Composition roots adapt it:

```text
production guest: HostHttpProvider (Zipline) -> HostHttp adapter -> repository
browser preview: relay replay endpoint       -> HostHttp adapter -> repository
tests:           in-memory fake              -> repository
```

Presenters and repositories depend only on `HostHttp`. Endpoints, DTOs, parsing,
and retry policy remain guest-owned. The production `HostHttpProvider` API and
artifact remain source/binary compatible.

## 3. Fixture ownership and location

`keliver.portal.json` gains:

```json
{
  "httpFixturesDir": "portal-fixtures/http"
}
```

The default is `portal-fixtures/http` relative to the app repository. The relay
resolves the canonical path and rejects a configured directory outside the
repository. Each `*.json` file is one named fixture set; the file stem is its
stable ID. Fixtures are app-owned source, committed to git, reviewed like code,
and never stored under `~/.keliver-portal`.

A persona selects a set explicitly:

```kotlin
PreviewPersona(
  id = "kyc-pending",
  httpFixtureSet = "profile-kyc-pending",
)
```

The editor owns only the selected persona ID. The persona owns the fixture-set
ID. The relay owns loading and deterministic session cursors.

## 4. H1 fixture format

```json
{
  "formatVersion": 1,
  "recordedAt": "2026-07-27T10:30:00Z",
  "expiresAt": "2026-08-27T10:30:00Z",
  "matchHeaders": ["accept", "content-type"],
  "entries": [
    {
      "request": {
        "method": "GET",
        "path": "/v1/profile",
        "query": {"view": "full"},
        "headers": {"accept": "application/json"},
        "body": null
      },
      "response": {
        "status": 200,
        "headers": {"content-type": "application/json"},
        "body": "{\"name\":\"Maya Chen\"}"
      }
    }
  ]
}
```

Rules:

- `formatVersion` must be exactly `1`;
- the request path is relative, begins with `/`, and contains no scheme/host;
- methods are normalized to uppercase;
- query keys and values are matched after deterministic key sorting;
- header names are normalized to lowercase, and only `matchHeaders` participate
  in matching;
- request bodies are exact UTF-8 strings in H1;
- response status is `100..599`;
- bodies are text-only and limited to 1 MiB;
- duplicate request signatures are legal and form a response sequence in file
  order; and
- unknown or invalid fixture fields fail that set closed.

H1 deliberately excludes streaming, multipart bodies, binary bodies, redirects
as transport behavior, cache semantics, timing simulation, and WebSockets.
Those exchanges are reported unsupported rather than approximated.

## 5. Matching and deterministic ordering

The canonical match key is:

```text
UPPER(method)
LF path
LF sorted percent-decoded query pairs
LF selected lowercase header pairs
LF exact body-or-empty
```

The relay loads immutable fixture snapshots. Every browser Live cold start gets
a fresh opaque replay session ID. For each `(fixtureSet, sessionId, matchKey)`,
the relay consumes duplicate entries in file order.

One entry may be reused by setting `"reuse": true`; otherwise an exhausted
sequence is a miss. Session cursors are in memory, bounded, and evicted after
inactivity. Editing a fixture invalidates its sessions by content revision.

This makes repeated endpoints explicit. It avoids a global cursor whose result
would depend on another editor tab or persona.

## 6. Relay API

### `GET /http-fixtures?project=P`

Returns descriptors for every set:

```json
[
  {
    "id": "profile-kyc-pending",
    "revision": "c14a...",
    "entries": 4,
    "expiresAt": "2026-08-27T10:30:00Z",
    "expired": false,
    "valid": true,
    "error": null
  }
]
```

Invalid sets remain visible with `valid=false`; the editor must not call them
full fidelity.

### `POST /http-replay?project=P&fixtureSet=F&session=S`

The body is `HostHttpRequest` JSON. A match returns `HostHttpResponse` JSON.

Misses return HTTP `424` with a stable JSON error:

```json
{
  "error": "replay_miss",
  "fixtureSet": "profile-kyc-pending",
  "method": "GET",
  "path": "/v1/profile",
  "reason": "no matching request"
}
```

Missing, invalid, or expired sets also fail closed. There is **never a live
network fallback** from the replay endpoint.

The endpoint accepts only `POST`, validates bounded names/body sizes, and does
not accept an upstream URL.

## 7. Privacy gate

H1 fixtures are read-only but still receive a load-time privacy lint. A set is
invalid if it contains:

- request or response headers named `authorization`, `proxy-authorization`,
  `cookie`, `set-cookie`, `x-api-key`, or `x-auth-token`;
- obvious secret query or JSON keys such as `token`, `secret`, `password`,
  `session`, `authorization`, or `apiKey`, unless their value is exactly
  `<redacted>`;
- an absolute request URL;
- a body over the size limit; or
- control characters outside normal JSON/text whitespace.

This is a guardrail, not a proof that data is non-sensitive. Fixture review
remains an app-team responsibility. H2 must redact before any bytes are written,
so unsafe captures never briefly exist in the repository.

## 8. Preview lifecycle and fidelity

`PreviewEnv` gains `http: HostHttp? = null`. The editor creates one relay-backed
provider per Live composition key `(project, persona)` and gives it a new
session ID. Persona changes already cold-start that key, so HTTP sequences
cannot leak across personas.

`PreviewCapabilities` treats `HostHttp@1` as:

- full fidelity when the persona selects a present, valid, non-expired fixture
  set;
- reduced fidelity when no set is selected, the set is missing/invalid/expired,
  or the catalog request failed.

A request miss is also written to the Live console and changes the HTTP row to
reduced fidelity for the current session. Presenter code receives a typed
`PreviewHttpReplayException`; failures are not converted to fabricated data.

Stopping and restarting Live creates a new replay session. Navigation inside a
flow retains the same provider and cursors because the FlowScope composition
survives screen swaps.

## 9. H1 dogfood

Add one small Field Notes API repository in `portal-app-lib`:

1. its endpoint and DTO live in common Kotlin;
2. it takes `HostHttp`;
3. the real Settings presenter loads a profile summary through it;
4. the Field Researcher persona selects a checked-in fixture set;
5. the Wasm editor supplies the relay-backed capability; and
6. tests use an in-memory `HostHttp`.

The production guest composition adapts `HostHttpProvider` when bound. Android
and iOS dogfood hosts bind their existing native HTTP stacks once. There are no
endpoint-specific host services.

## 10. H1 acceptance gates

1. `:keliver-capabilities:allTests` covers request/response validation and fake
   behavior;
2. `:portal-relay:test` covers canonical matching, duplicate ordering,
   per-session isolation, reuse, missing/invalid/expired sets, privacy rejection,
   path confinement, body limits, and no live fallback;
3. `:portal-render:apiCheck`, `:portal-editor:apiCheck`, and root `apiCheck`;
4. browser tests cover persona fixture resolution and HTTP fidelity states;
5. a fresh editor distribution loads the Field Researcher persona, runs the
   real Settings presenter, and visibly renders the recorded profile value;
6. a controlled unmatched request produces a visible replay miss and reduced
   fidelity;
7. Android and iOS hosts compile, bind the production edge, install, and render
   the existing Field Notes flow; and
8. current state and roadmap record exact evidence.

## 11. H2 recording constraints

H2 may start only after a separate implementation section pins:

- allowlisted upstream IDs and base URLs in app config—never caller-supplied
  destinations;
- loopback-only recording access plus an origin allowlist;
- explicit `PORTAL_HTTP_RECORD=1` startup and a per-run record token;
- environment-sourced auth injection that is never returned to the browser;
- redaction before hashing, logging, or persistence;
- atomic write to a new candidate file followed by privacy lint;
- a human-readable diff before replacing a reviewed fixture;
- bounded redirects and SSRF defenses, including DNS rebinding considerations;
  and
- recorder audit output that contains no request/response bodies.

Until H2 exists, the relay has no outbound HTTP code and no endpoint can turn a
replay miss into a real request.

## 12. H1 delivery evidence — 2026-07-27

H1 shipped in `e472c9ffd` after design commit `0ba6d0b27`.

Delivered:

- portable `HostHttp@1` contracts and the existing `HostHttpProvider` adapter;
- confined, privacy-linted fixture loading with revisioned, bounded,
  per-session deterministic matching and no network fallback;
- fixture catalog and replay relay endpoints with stable client/miss errors;
- persona fixture selection, Live-session isolation, and fidelity downgrade on
  replay miss;
- a relay-backed Wasm preview provider and Android/iOS native bindings; and
- Field Researcher dogfood through the real Settings presenter.

Verification:

- `:keliver-capabilities:jvmTest`, `:keliver-http:jvmTest`,
  `:portal-relay:test`, `:portal-render:wasmJsTest`,
  `:portal-editor:wasmJsBrowserTest`, `:portal-app-lib:jsTest`, and
  `:portal-app-lib:wasmJsBrowserTest` passed;
- capability, HTTP, render, and editor `apiCheck` tasks passed;
- Android APK assembly and iOS simulator framework linking passed;
- a fresh production Wasm editor visibly showed
  `Maya Chen · recorded API`, Full fidelity, and
  `HostHttp@1 — replay: field-researcher (1 exchanges)`;
- relay probes returned 200 for the checked-in match, 424 `replay_miss` for an
  unmatched request, and 400 for malformed JSON; and
- rebuilt Android and iOS hosts installed, loaded the OTA guest, and visibly
  rendered Field Notes on Pixel 9 and iPhone 16 Pro simulators.

H2 is not implied by this delivery. The recording constraints in §11 remain the
next #16 implementation boundary.
