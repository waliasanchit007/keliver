/*
 * Copyright (C) 2026 Konduit contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.konduit.http.codegen

import com.squareup.kotlinpoet.ClassName

/**
 * Fully-qualified names the processor recognizes. Kept centralized so
 * a future annotation rename touches one place instead of being
 * scattered across [KonduitHttpCodegen]'s symbol-resolution paths.
 *
 * Mirrors `dev.konduit.http.api.*` from `konduit-http-annotations`.
 */
internal object FqNames {
  // Class-level marker.
  const val KONDUIT_API = "dev.konduit.http.api.KonduitApi"

  // Method annotations.
  const val GET = "dev.konduit.http.api.GET"
  const val POST = "dev.konduit.http.api.POST"
  const val PUT = "dev.konduit.http.api.PUT"
  const val DELETE = "dev.konduit.http.api.DELETE"

  // Parameter annotations.
  const val PATH = "dev.konduit.http.api.Path"
  const val QUERY = "dev.konduit.http.api.Query"
  const val BODY = "dev.konduit.http.api.Body"
  const val HEADER = "dev.konduit.http.api.Header"
  const val HEADER_MAP = "dev.konduit.http.api.HeaderMap"

  // Runtime targets — the generated code emits calls to these.
  val KONDUIT_HTTP = ClassName("dev.konduit.http", "KonduitHttp")
  val UNIT = ClassName("kotlin", "Unit")
}
