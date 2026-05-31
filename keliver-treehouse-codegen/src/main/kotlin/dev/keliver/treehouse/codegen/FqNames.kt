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
package dev.keliver.treehouse.codegen

import com.squareup.kotlinpoet.ClassName

internal object FqNames {
  private const val TREEHOUSE = "dev.keliver.treehouse"
  private const val ZIPLINE = "app.cash.zipline"
  private const val SERIALIZATION = "kotlinx.serialization"

  // Annotation the adopter applies to opt in.
  val KeliverAppService = ClassName(TREEHOUSE, "KeliverAppService")

  // Base type the annotated interface MUST extend (directly or
  // transitively). We use this to reject misapplied annotations
  // with a clear error rather than producing broken code.
  val AppService = ClassName(TREEHOUSE, "AppService")

  // Base class the generated adapter extends.
  val KeliverAppServiceAdapter = ClassName(TREEHOUSE, "KeliverAppServiceAdapter")

  // Keliver-blessed aliases for Zipline internals that adopter
  // outbound impls touch — emitted by-name in the generated source.
  val KeliverOutboundCallHandler = ClassName(TREEHOUSE, "KeliverOutboundCallHandler")
  val KeliverOutboundService = ClassName(TREEHOUSE, "KeliverOutboundService")
  val keliverReturningFunction =
    ClassName(TREEHOUSE, "keliverReturningFunction")

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
