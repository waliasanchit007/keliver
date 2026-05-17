/*
 * Copyright (C) 2026 Konduit contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.konduit.http

import app.cash.zipline.ZiplineService

/**
 * Generic HTTP proxy bound by the host and called by the guest. Adopters
 * implement this once with their existing `HttpClient` (Ktor / Retrofit /
 * OkHttp / etc.) and bind a single `"http"` service; guest code then makes
 * arbitrary HTTP calls through [KonduitHttp] without writing per-endpoint
 * `HostXxxProvider` ZiplineServices.
 *
 * Reference adapter (Ktor) — copy / paste into your host module:
 *
 * ```
 * private class KtorHostHttpProvider(
 *     private val client: HttpClient,
 *     private val baseUrl: String,
 * ) : HostHttpProvider {
 *     override suspend fun execute(request: HttpRequest): HttpResponse {
 *         val rs = client.request(baseUrl + request.path) {
 *             method = HttpMethod.parse(request.method)
 *             request.query.forEach { (k, v) -> parameter(k, v) }
 *             request.headers.forEach { (k, v) -> header(k, v) }
 *             request.body?.let { setBody(it) }
 *         }
 *         return HttpResponse(
 *             status = rs.status.value,
 *             body = rs.bodyAsText(),
 *             headers = rs.headers.entries().associate { (k, v) -> k to v.joinToString(",") },
 *         )
 *     }
 * }
 * ```
 *
 * Bind it the standard way:
 *
 * ```
 * zipline.bind<HostHttpProvider>(
 *     "http",
 *     retain(KtorHostHttpProvider(httpClient, "https://api.example.com")),
 * )
 * ```
 *
 * Authentication, retries, timeouts, base URL — all configured once on the
 * host's `HttpClient` (or its interceptors / engine configuration). The wire
 * surface deliberately stays minimal; future additions (cache policy,
 * streaming, binary bodies) will append fields additively.
 */
public interface HostHttpProvider : ZiplineService {
  /**
   * Execute a single HTTP request. Non-2xx responses are NOT thrown as
   * exceptions here — they come back as [HttpResponse] with the appropriate
   * [HttpResponse.status] so callers can inspect 4xx / 5xx without
   * try/catch.
   *
   * Network errors (connection failure, DNS resolution failure, timeout)
   * propagate as Kotlin exceptions through Zipline. Cancellation propagates
   * from the guest's coroutine to the host's network call via Zipline's
   * standard suspend-cancellation semantics.
   */
  public suspend fun execute(request: HttpRequest): HttpResponse
}
