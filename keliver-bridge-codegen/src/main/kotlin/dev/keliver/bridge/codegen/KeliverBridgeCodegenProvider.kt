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
package dev.keliver.bridge.codegen

import com.google.auto.service.AutoService
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider

/**
 * KSP entry point, registered through auto-service-ksp. An app adds
 * `add("kspCommonMainMetadata", "dev.keliver:keliver-bridge-codegen:…")` to its contract module
 * (`docs/BRIDGE_CODEGEN_DESIGN.md`, section 1).
 */
@AutoService(SymbolProcessorProvider::class)
public class KeliverBridgeCodegenProvider : SymbolProcessorProvider {
  override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
    KeliverBridgeCodegen(environment.codeGenerator, environment.logger)
}
