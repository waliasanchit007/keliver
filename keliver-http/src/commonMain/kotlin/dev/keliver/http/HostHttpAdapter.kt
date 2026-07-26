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
package dev.keliver.http

import dev.keliver.capabilities.HostHttp
import dev.keliver.capabilities.HostHttpResponse

/**
 * Adapt the production Zipline service to the pure capability consumed by
 * shared guest repositories. Zipline remains at the composition-root edge.
 */
public fun HostHttpProvider.asHostHttp(): HostHttp = HostHttp { request ->
  execute(
    HttpRequest(
      method = request.method,
      path = request.path,
      query = request.query,
      headers = request.headers,
      body = request.body,
    ),
  ).let { response ->
    HostHttpResponse(
      status = response.status,
      body = response.body,
      headers = response.headers,
    )
  }
}
