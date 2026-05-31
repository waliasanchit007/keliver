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

import com.google.auto.service.AutoService
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider

/**
 * KSP entry point — registered with the
 * `com.google.auto.service` META-INF/services file at build time via
 * `dev.zacsweers.autoservice:auto-service-ksp` (see this module's
 * `build.gradle`). Adopters add `ksp(libs.keliver.treehouse.codegen)`
 * to the module that defines their `@KeliverAppService`-annotated
 * interface; the processor runs on every compile.
 */
@AutoService(SymbolProcessorProvider::class)
public class KeliverAppServiceCodegenProvider : SymbolProcessorProvider {
  override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
    KeliverAppServiceCodegen(
      codeGenerator = environment.codeGenerator,
      logger = environment.logger,
    )
}
