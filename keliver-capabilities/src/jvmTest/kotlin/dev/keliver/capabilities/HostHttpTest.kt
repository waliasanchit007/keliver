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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking

class HostHttpTest {
  @Test fun fakeExecutesSharedRequest() = runBlocking {
    val http = HostHttp { request ->
      HostHttpResponse(200, """{"path":"${request.path}"}""")
    }

    assertEquals(
      """{"path":"/v1/profile"}""",
      http.execute(HostHttpRequest("GET", "/v1/profile")).body,
    )
  }

  @Test fun requestRejectsAbsoluteUrl() {
    assertFailsWith<IllegalArgumentException> {
      HostHttpRequest("GET", "https://example.com/profile")
    }
  }

  @Test fun responseRejectsInvalidStatus() {
    assertFailsWith<IllegalArgumentException> {
      HostHttpResponse(700, "invalid")
    }
  }
}
