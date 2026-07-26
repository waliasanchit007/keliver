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
package dev.keliver.portalpublished.logic

import dev.keliver.capabilities.HostHttp
import dev.keliver.capabilities.HostHttpRequest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
public data class ProfileSummary(
  public val displayName: String,
  public val source: String,
)

/** Guest-owned endpoint and parsing; only the generic transport is a capability. */
public class ProfileSummaryApi(
  private val http: HostHttp,
  private val json: Json = Json { ignoreUnknownKeys = true },
) {
  public suspend fun load(): ProfileSummary {
    val response = http.execute(
      HostHttpRequest(
        method = "GET",
        path = "/v1/profile/summary",
        headers = mapOf("Accept" to "application/json"),
      ),
    )
    check(response.status in 200..299) { "profile summary HTTP ${response.status}" }
    return json.decodeFromString(response.body)
  }
}
