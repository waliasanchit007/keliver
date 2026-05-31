/*
 * Copyright (C) 2026 Konduit contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.keliver.http.codegen

import com.google.auto.service.AutoService
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider

/**
 * KSP entry point — registered with the
 * `com.google.auto.service` META-INF/services file at build time via
 * `dev.zacsweers.autoservice:auto-service-ksp` (see this module's
 * `build.gradle`). Adopters add `ksp(libs.keliver.http.codegen)` and
 * the processor runs on every guest-side compile.
 */
@AutoService(SymbolProcessorProvider::class)
public class KonduitHttpCodegenProvider : SymbolProcessorProvider {
  override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
    KonduitHttpCodegen(
      codeGenerator = environment.codeGenerator,
      logger = environment.logger,
    )
}
