/*
 * Copyright (C) 2026 Konduit contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.konduit.treehouse.codegen

import com.squareup.kotlinpoet.ClassName

internal object FqNames {
  private const val TREEHOUSE = "dev.konduit.treehouse"
  private const val ZIPLINE = "app.cash.zipline"
  private const val SERIALIZATION = "kotlinx.serialization"

  // Annotation the adopter applies to opt in.
  val KonduitAppService = ClassName(TREEHOUSE, "KonduitAppService")

  // Base type the annotated interface MUST extend (directly or
  // transitively). We use this to reject misapplied annotations
  // with a clear error rather than producing broken code.
  val AppService = ClassName(TREEHOUSE, "AppService")

  // Base class the generated adapter extends.
  val KonduitAppServiceAdapter = ClassName(TREEHOUSE, "KonduitAppServiceAdapter")

  // Konduit-blessed aliases for Zipline internals that adopter
  // outbound impls touch — emitted by-name in the generated source.
  val KonduitOutboundCallHandler = ClassName(TREEHOUSE, "KonduitOutboundCallHandler")
  val KonduitOutboundService = ClassName(TREEHOUSE, "KonduitOutboundService")
  val konduitReturningFunction =
    ClassName(TREEHOUSE, "konduitReturningFunction")

  // Used to decide between ziplineServiceSerializer<T>() (for
  // ZiplineService return types) and serializersModule.serializer<T>()
  // (for @Serializable wire types). All AppService methods inherit
  // close() and appLifecycle from this hierarchy.
  val ZiplineService = ClassName(ZIPLINE, "ZiplineService")
  val ZiplineFunction = ClassName(ZIPLINE, "ZiplineFunction")
  val ziplineServiceSerializer =
    ClassName(ZIPLINE, "ziplineServiceSerializer")

  val SerializersModule =
    ClassName("$SERIALIZATION.modules", "SerializersModule")
  val KSerializer = ClassName(SERIALIZATION, "KSerializer")
  val serializer =
    ClassName(SERIALIZATION, "serializer")
}
