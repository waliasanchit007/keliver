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
package dev.keliver.http.codegen

import com.squareup.kotlinpoet.ClassName

/**
 * Fully-qualified names the processor recognizes. Kept centralized so
 * a future annotation rename touches one place instead of being
 * scattered across [KeliverHttpCodegen]'s symbol-resolution paths.
 *
 * Mirrors `dev.keliver.http.api.*` from `keliver-http-annotations`.
 */
internal object FqNames {
  // Class-level marker.
  const val KELIVER_API = "dev.keliver.http.api.KeliverApi"

  // Method annotations.
  const val GET = "dev.keliver.http.api.GET"
  const val POST = "dev.keliver.http.api.POST"
  const val PUT = "dev.keliver.http.api.PUT"
  const val DELETE = "dev.keliver.http.api.DELETE"

  // Parameter annotations.
  const val PATH = "dev.keliver.http.api.Path"
  const val QUERY = "dev.keliver.http.api.Query"
  const val BODY = "dev.keliver.http.api.Body"
  const val HEADER = "dev.keliver.http.api.Header"
  const val HEADER_MAP = "dev.keliver.http.api.HeaderMap"

  // Runtime targets — the generated code emits calls to these.
  val KELIVER_HTTP = ClassName("dev.keliver.http", "KeliverHttp")
  val UNIT = ClassName("kotlin", "Unit")
}
