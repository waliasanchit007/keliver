/*
 * Copyright (C) 2026 Konduit contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.konduit.http

import kotlinx.serialization.Serializable

/**
 * Wire-level HTTP request envelope passed across the Zipline boundary from
 * guest to host. The host translates this into a real network call using its
 * own `HttpClient` (Ktor / Retrofit / OkHttp / whatever the adopter wired).
 *
 * Every field except [method] and [path] is default-valued; new fields are
 * appended additively so older adopters' deserializers keep working.
 */
@Serializable
public data class HttpRequest(
  public val method: String,
  public val path: String,
  public val query: Map<String, String> = emptyMap(),
  public val headers: Map<String, String> = emptyMap(),
  public val body: String? = null,
)

/**
 * Wire-level HTTP response envelope. The host adapter populates this from the
 * underlying network call's result. Non-2xx status values do **not** raise an
 * exception at this layer — adopters who want exception semantics use
 * [KonduitHttp]'s typed helpers, which translate non-2xx into
 * [KonduitHttpException].
 *
 * Every field except [status] and [body] is default-valued.
 */
@Serializable
public data class HttpResponse(
  public val status: Int,
  public val body: String,
  public val headers: Map<String, String> = emptyMap(),
)

/**
 * Thrown by [KonduitHttp] typed helpers when the underlying HTTP call returned
 * a status outside the 2xx range. Adopters who want to inspect 4xx / 5xx
 * without exceptions can use [HostHttpProvider.execute] directly and read the
 * [HttpResponse.status] field.
 */
public class KonduitHttpException(
  public val status: Int,
  public val body: String,
) : Exception("HTTP $status: ${body.take(200)}")
