# Retrofit-style HTTP API codegen — design

Tracks the multi-phase design for issue [#18](https://github.com/waliasanchit007/keliver/issues/18).
Adopters today write one `KonduitHttp` call per endpoint manually:

```kotlin
suspend fun list(filter: String?): List<Quote> =
    http.get("/quotes", mapOf("filter" to (filter ?: "")))
```

The end-state is a Retrofit-shaped annotation API where a KSP processor
generates the implementation from a typed Kotlin interface:

```kotlin
@KonduitApi
interface QuotesApi {
    @GET("/quotes")
    suspend fun list(@Query("filter") filter: String?): List<Quote>

    @POST("/quotes")
    suspend fun create(@Body body: NewQuote): Quote
}

// Generated:
//   class QuotesApiImpl(private val http: KonduitHttp) : QuotesApi
```

This doc captures the locked-in design so the KSP processor can be
built without further design churn.

## Module structure

| Module | Target group | Ships | Status |
|---|---|---|---|
| `keliver-http-annotations` | Common (JVM + iOS + JS) | `@KonduitApi`, `@GET`, `@POST`, `@PUT`, `@DELETE`, `@Path`, `@Query`, `@Body`, `@Header`, `@HeaderMap` — all `SOURCE`-retention | **landed** (this PR) |
| `keliver-http-codegen` | Tooling (JVM-only) | KSP `SymbolProcessor` + companion Gradle plugin descriptor; depends on `keliver-http-annotations` for the FqName lookups | **next session** |
| `keliver-gradle-plugin` | (existing) | New plugin id `dev.keliver.http-api` that auto-applies the KSP plugin + adds the codegen processor as a `ksp` configuration dep | **next session** |

The annotations are intentionally split into their own module so the
KSP processor module (JVM-only, Tooling target) doesn't pollute the
adopter's runtime classpath — guests on Kotlin/JS pull in
`keliver-http-annotations` (~10 lines of public surface, no
dependencies) and nothing else from the codegen path.

## API surface (committed)

### `@KonduitApi`
Class-level annotation marking an interface as a candidate for
generation. Required on every interface processed.

### HTTP-method annotations
| Annotation | Body required? | Path template substitution? |
|---|---|---|
| `@GET(path)` | no | yes |
| `@POST(path)` | yes (via `@Body`) | yes |
| `@PUT(path)` | yes (via `@Body`) | yes |
| `@DELETE(path)` | no | yes |

### Parameter annotations
| Annotation | What | Notes |
|---|---|---|
| `@Path("name")` | Substitute `{name}` in the URL template | Required-presence check: every `{x}` in the template must have a matching `@Path("x")`; every `@Path` must reference an actual `{x}`. |
| `@Query("name")` | Append to the query string | Nullable parameters are omitted when `null`; the wire envelope (`HttpRequest.query`) is a `Map<String, String>`. |
| `@Body` | JSON-encode + send as the request body | Valid only on `@POST` / `@PUT` methods. Serialized with the adopter-supplied `KonduitHttp.json`. |
| `@Header("name")` | One header | Nullable parameters are omitted when `null`. Non-`String` values get `.toString()`. |
| `@HeaderMap` | Spread a `Map<String, String>` of headers | Accepts `Map<String, String>` (emit `putAll`) or `Map<String, String?>` (emit `forEach` with null-value filter). Other shapes fail the build. |

### Method shape requirements
- Must be `suspend`. Non-suspend functions are rejected with a clear
  message pointing at the `KonduitHttp.execute` non-suspend
  alternative.
- Return type must be a `KSerializer`-resolvable type. The processor
  emits a `requireSerializerOf<ReturnType>()` pre-flight check at
  generation time (mirroring `Spec.requireSerializerOf` semantics)
  and surfaces a clear diagnostic if the serializer chain breaks.
- `Unit` return is special-cased — routes through
  `KonduitHttp.deleteUnit` / `KonduitHttp.postEmpty<Unit>` etc.

## Generated code shape

For the running example above, the KSP processor would emit:

```kotlin
// build/generated/ksp/.../com/example/QuotesApiImpl.kt
package com.example

import dev.keliver.http.KonduitHttp

public class QuotesApiImpl(private val http: KonduitHttp) : QuotesApi {

    override suspend fun list(filter: String?): List<Quote> =
        http.get(
            path = "/quotes",
            query = buildMap {
                if (filter != null) put("filter", filter)
            },
        )

    override suspend fun create(body: NewQuote): Quote =
        http.post(
            path = "/quotes",
            body = body,
        )
}
```

Key generation details:
- Constructor takes a single `KonduitHttp` parameter — no DI assumptions.
- Each method maps one-to-one with a `KonduitHttp` typed helper
  (`get<Res>`, `post<Req, Res>`, `put<Req, Res>`, `delete<Res>` or
  `deleteUnit`).
- `@Query` and `@Header` collection happens inline via `buildMap { … }`
  blocks; null-filtering is generated where the source parameter is
  nullable.
- Path templates are substituted at the call site:
  `"/users/$userId/quotes/$quoteId"` for `@Path("userId") userId,
  @Path("quoteId") quoteId`.
- `@Body` parameters become the `body` argument; the inline-reified
  helper handles JSON encoding via the wrapper's configured `Json`.

The generated class is `public` so adopters can instantiate it from any
module that depends on the annotated interface's module.

## Adopter integration (planned)

Phase 2 (KSP processor) ships with a `dev.keliver.http-api` Gradle
plugin so adoption is a single `plugins { id(…) }` block:

```kotlin
// adopter's :shared (or :guest) build.gradle.kts:
plugins {
    id("dev.keliver.http-api")
}

dependencies {
    implementation(libs.keliver.guest)  // pulls keliver-http transitively
    // Note: keliver-http-annotations is pulled in by the plugin.
}
```

For users who don't want to apply the umbrella plugin, the manual
path is:

```kotlin
plugins {
    id("com.google.devtools.ksp")
}

dependencies {
    implementation("dev.keliver:keliver-http-annotations:$VERSION")
    ksp("dev.keliver:keliver-http-codegen:$VERSION")
}
```

## Out of scope (v1 of the codegen)

Each of these is documented as "out of scope" to lock the v1 surface
and prevent feature creep. Each could land as an additive follow-up
without breaking the generated code from v1:

- **Form-urlencoded bodies** (`application/x-www-form-urlencoded`) —
  adopters use raw `KonduitHttp.execute` for now.
- **Multipart uploads** — same.
- **Streaming responses** (SSE, chunked) — requires the streaming
  addition to `HostHttpProvider` documented in issue #7's "out of
  scope" list. Tied to that work.
- **`@Url` parameter override** (Retrofit's runtime-URL escape hatch)
  — defer until an adopter asks.
- **Method-level `@Headers` arrays** for static headers — defer; a
  `@HeaderMap` parameter wired to a constant covers the common case.
- **Polymorphic response wrappers** (e.g. `Result<T>` / sealed-class
  envelopes) — adopters wrap manually or use `requestRaw` for now.
- **Custom converters / per-endpoint `Json` overrides** — defer until
  an adopter asks.

## Phase plan

| Phase | Scope | Status |
|---|---|---|
| **1** | `keliver-http-annotations` module + this design doc | **landed (caliclan.4)** |
| **2** | KSP processor: `@KonduitApi` + `@GET` + `@POST` + `@PUT` + `@DELETE` + `@Path` + `@Query` + `@Body` + `@Header` | **landed (caliclan.4)** — MVP scope; integration tests + `@HeaderMap` deferred to Phase 3 |
| **3** | `@HeaderMap` + `dev.zacsweers.kctfork:ksp` end-to-end test fixtures (11 tests: 5 happy-path codegen assertions, 6 validation-diagnostic assertions) | **landed (caliclan.4)** |
| **4** | Error handling, response wrappers (Result<T> envelope, etc.) | follow-up |
| **5** | `dev.keliver.http-api` umbrella Gradle plugin that auto-applies KSP + the codegen dep | follow-up |

Phases 2 and 3 are the meaty bits — KSP processor implementation,
KotlinPoet-based code generation, processor tests via
`kotlin-compile-testing-ksp` (or equivalent fixture-based runner).
The work is bounded but real — ~4–6 hours of focused implementation
plus tests.

## Why annotations now, processor later

Splitting the annotations into their own ship-now module lets:

1. **Adopters preview** the API and start drafting their
   `@KonduitApi` interfaces today. Manual implementations against
   `KonduitHttp` still work in the meantime; switching to the
   generated impl will be a single import change once Phase 2 ships.
2. **The annotation API stays stable** — the processor work doesn't
   risk breaking the wire format because the annotations are
   `SOURCE`-retention, never reaching the runtime.
3. **The processor can be developed against a fixed target** — the
   FqName lookups (`dev.keliver.http.api.GET`, etc.) won't shift.

If the v2-of-annotations decision later requires a wire-format-style
change (unlikely — these are pure source-time markers), we can ship
v2 annotations alongside v1 and let the processor accept both.
