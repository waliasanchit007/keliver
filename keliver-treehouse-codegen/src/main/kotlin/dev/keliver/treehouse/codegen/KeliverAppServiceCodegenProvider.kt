/*
 * Copyright (C) 2026 Keliver contributors.
 * Licensed under the Apache License, Version 2.0.
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
