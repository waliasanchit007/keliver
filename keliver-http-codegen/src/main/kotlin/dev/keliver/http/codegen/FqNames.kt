/*
 * Copyright (C) 2026 Keliver contributors.
 * Licensed under the Apache License, Version 2.0.
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
