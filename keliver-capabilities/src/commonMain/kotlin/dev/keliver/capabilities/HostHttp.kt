/*
 * Copyright (C) 2026 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.keliver.capabilities

import kotlinx.serialization.Serializable

/** Stable manifest/fidelity name for the generic HTTP capability. */
public const val HOST_HTTP_CAPABILITY: String = "HostHttp@1"

/**
 * Zipline-free HTTP transport used by guest repositories and presenters.
 *
 * Production composition roots adapt the device-edge `HostHttpProvider`.
 * Browser preview supplies deterministic relay replay. Endpoints and DTOs stay
 * in guest Kotlin.
 */
public fun interface HostHttp {
  public suspend fun execute(request: HostHttpRequest): HostHttpResponse
}

/** Text-only request supported by the first portable HTTP capability version. */
@Serializable
public data class HostHttpRequest(
  public val method: String,
  public val path: String,
  public val query: Map<String, String> = emptyMap(),
  public val headers: Map<String, String> = emptyMap(),
  public val body: String? = null,
) {
  init {
    require(method.isNotBlank()) { "HTTP method must not be blank" }
    require(path.startsWith('/')) { "HTTP path must be relative and start with '/': $path" }
    require("://" !in path) { "HTTP path must not contain a scheme or host: $path" }
  }
}

/** Text-only response returned to shared guest code. */
@Serializable
public data class HostHttpResponse(
  public val status: Int,
  public val body: String,
  public val headers: Map<String, String> = emptyMap(),
) {
  init {
    require(status in 100..599) { "HTTP status must be in 100..599: $status" }
  }
}

/** Raised by browser preview when a deterministic replay cannot answer. */
public class HostHttpReplayException(
  public val reason: String,
) : Exception("HTTP replay failed: $reason")
