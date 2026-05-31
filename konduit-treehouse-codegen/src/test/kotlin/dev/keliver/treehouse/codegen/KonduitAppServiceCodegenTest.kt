/*
 * Copyright (C) 2026 Konduit contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.keliver.treehouse.codegen

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.configureKsp
import com.tschuchort.compiletesting.kspSourcesDir
import java.io.File
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.Test

/**
 * Fixture-based tests for the KSP processor via `kctfork:ksp`.
 * Mirrors the pattern in `konduit-http-codegen`'s test file —
 * each test compiles a fixture Kotlin source with the processor
 * installed and asserts on either the exit code (for validation
 * paths) or the generated `*Adapter.kt` content (for happy-path
 * emission).
 */
@OptIn(ExperimentalCompilerApi::class)
class KonduitAppServiceCodegenTest {

  private fun compile(source: SourceFile): Pair<JvmCompilationResult, File> {
    val compilation = KotlinCompilation().apply {
      sources = listOf(source)
      inheritClassPath = true
      messageOutputStream = System.out
      jvmTarget = "11"
      configureKsp {
        symbolProcessorProviders.add(KonduitAppServiceCodegenProvider())
      }
    }
    val result = compilation.compile()
    return result to compilation.kspSourcesDir
  }

  private fun File.findFile(name: String): File =
    walkTopDown().firstOrNull { it.name == name }
      ?: throw AssertionError(
        "Generated file `$name` not found under $this.\n" +
          "Available: ${walkTopDown().filter { it.isFile }.map { it.name }.toList()}",
      )

  // --- happy path ---

  @Test
  fun simple_app_service_emits_generated_adapter() {
    val (result, kspDir) = compile(
      SourceFile.kotlin(
        "MyAppService.kt",
        """
          package com.example

          import dev.keliver.treehouse.AppService
          import dev.keliver.treehouse.KonduitAppService
          import dev.keliver.treehouse.ZiplineTreehouseUi

          @KonduitAppService
          interface MyAppService : AppService {
              fun launch(): ZiplineTreehouseUi
          }
        """.trimIndent(),
      ),
    )
    assertThat(result.exitCode).isEqualTo(KotlinCompilation.ExitCode.OK)

    val generated = kspDir.findFile("GeneratedMyAppServiceAdapter.kt").readText()

    // Class header + correct generic over the user interface.
    // INTERNAL not PUBLIC — see comment in KonduitAppServiceCodegen.kt
    // for why; companion-object Adapter wrappers are internal too.
    assertThat(generated).contains("internal open class GeneratedMyAppServiceAdapter")
    assertThat(generated).contains("KonduitAppServiceAdapter<MyAppService>")

    // The user-declared method shows up first (positional id 0).
    assertThat(generated).contains("id = \"launch\"")

    // The inherited members appear too — appLifecycle (from
    // AppService) and close (from ZiplineService).
    assertThat(generated).contains("id = \"appLifecycle\"")
    assertThat(generated).contains("id = \"close\"")

    // ZiplineService returns get ziplineServiceSerializer<T>; Unit
    // goes through serializersModule.serializer<Unit>.
    assertThat(generated).contains("ziplineServiceSerializer<ZiplineTreehouseUi>()")
    assertThat(generated).contains("ziplineServiceSerializer<AppLifecycle>()")
    assertThat(generated).contains("serializersModule.serializer<Unit>()")

    // Outbound impl: user method gets call(this, 0), inherited
    // appLifecycle gets 1, close gets 2 (since launch was declared
    // first on the interface).
    assertThat(generated).contains("callHandler.call(this, 0)")
    assertThat(generated).contains("callHandler.call(this, 1)")
    assertThat(generated).contains("callHandler.call(this, 2)")
  }

  @Test
  fun adapter_includes_serial_name_from_qualified_name() {
    val (result, kspDir) = compile(
      SourceFile.kotlin(
        "MyAppService.kt",
        """
          package com.example.demo

          import dev.keliver.treehouse.AppService
          import dev.keliver.treehouse.KonduitAppService
          import dev.keliver.treehouse.ZiplineTreehouseUi

          @KonduitAppService
          interface MyAppService : AppService {
              fun launch(): ZiplineTreehouseUi
          }
        """.trimIndent(),
      ),
    )
    assertThat(result.exitCode).isEqualTo(KotlinCompilation.ExitCode.OK)
    val generated = kspDir.findFile("GeneratedMyAppServiceAdapter.kt").readText()
    assertThat(generated).contains("\"com.example.demo.MyAppService\"")
  }

  // --- validation paths ---

  @Test
  fun rejects_konduit_app_service_on_class() {
    val (result, _) = compile(
      SourceFile.kotlin(
        "Bad.kt",
        """
          package com.example

          import dev.keliver.treehouse.AppService
          import dev.keliver.treehouse.KonduitAppService
          import dev.keliver.treehouse.ZiplineTreehouseUi

          @KonduitAppService
          class NotAnInterface : AppService {
              override val appLifecycle get() = TODO()
              fun launch(): ZiplineTreehouseUi = TODO()
          }
        """.trimIndent(),
      ),
    )
    assertThat(result.exitCode).isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR)
    assertThat(result.messages).contains("@KonduitAppService can only be applied to interfaces")
  }

  @Test
  fun rejects_interface_not_extending_app_service() {
    val (result, _) = compile(
      SourceFile.kotlin(
        "Bad.kt",
        """
          package com.example

          import dev.keliver.treehouse.KonduitAppService

          @KonduitAppService
          interface NotAnAppService {
              fun doSomething()
          }
        """.trimIndent(),
      ),
    )
    assertThat(result.exitCode).isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR)
    assertThat(result.messages).contains("requires the annotated interface to extend")
  }
}
