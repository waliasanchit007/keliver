/*
 * Copyright (C) 2026 Keliver contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.keliver.http.codegen

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
 * Fixture-based tests for the KSP processor via `kctfork:ksp`. Each
 * test compiles a Kotlin source fragment with the processor installed
 * and asserts on either the exit code (for validation paths) or the
 * generated source file's content (for happy-path emission).
 */
@OptIn(ExperimentalCompilerApi::class)
class KeliverHttpCodegenTest {

  private fun compile(source: SourceFile): Pair<JvmCompilationResult, File> {
    val compilation = KotlinCompilation().apply {
      sources = listOf(source)
      inheritClassPath = true
      messageOutputStream = System.out
      // Match keliver-http-codegen's main JVM target so the generated
      // Impl's inline calls into KeliverHttp aren't rejected as
      // "JVM target 11 cannot inline into JVM target 1.8".
      jvmTarget = "11"
      configureKsp {
        symbolProcessorProviders.add(KeliverHttpCodegenProvider())
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
  fun simple_get_emits_expected_impl() {
    val (result, kspDir) = compile(
      SourceFile.kotlin(
        "QuotesApi.kt",
        """
          package com.example

          import dev.keliver.http.api.*

          @KeliverApi
          interface QuotesApi {
              @GET("/quotes")
              suspend fun list(@Query("filter") filter: String?): List<String>
          }
        """.trimIndent(),
      ),
    )
    assertThat(result.exitCode).isEqualTo(KotlinCompilation.ExitCode.OK)

    val impl = kspDir.findFile("QuotesApiImpl.kt").readText()
    assertThat(impl).contains("public class QuotesApiImpl")
    assertThat(impl).contains("override suspend fun list")
    assertThat(impl).contains("http.get")
    assertThat(impl).contains("\"/quotes\"")
    assertThat(impl).contains("if (filter != null)")
  }

  @Test
  fun post_with_body_routes_to_post_helper() {
    val (result, kspDir) = compile(
      SourceFile.kotlin(
        "QuotesApi.kt",
        """
          package com.example

          import dev.keliver.http.api.*
          import kotlinx.serialization.Serializable

          @Serializable data class NewQuote(val text: String)
          @Serializable data class Quote(val id: Int, val text: String)

          @KeliverApi
          interface QuotesApi {
              @POST("/quotes")
              suspend fun create(@Body body: NewQuote): Quote
          }
        """.trimIndent(),
      ),
    )
    assertThat(result.exitCode).isEqualTo(KotlinCompilation.ExitCode.OK)

    val impl = kspDir.findFile("QuotesApiImpl.kt").readText()
    assertThat(impl).contains("http.post")
    assertThat(impl).contains("body = body")
  }

  @Test
  fun path_placeholder_emits_string_template() {
    val (result, kspDir) = compile(
      SourceFile.kotlin(
        "QuotesApi.kt",
        """
          package com.example

          import dev.keliver.http.api.*

          @KeliverApi
          interface QuotesApi {
              @GET("/users/{userId}/quotes/{quoteId}")
              suspend fun get(
                  @Path("userId") userId: String,
                  @Path("quoteId") quoteId: Int,
              ): String
          }
        """.trimIndent(),
      ),
    )
    assertThat(result.exitCode).isEqualTo(KotlinCompilation.ExitCode.OK)

    val impl = kspDir.findFile("QuotesApiImpl.kt").readText()
    assertThat(impl).contains("/users/")
    assertThat(impl).contains("userId.toString()")
    assertThat(impl).contains("quoteId.toString()")
  }

  @Test
  fun header_map_with_non_nullable_values_emits_putAll() {
    val (result, kspDir) = compile(
      SourceFile.kotlin(
        "QuotesApi.kt",
        """
          package com.example

          import dev.keliver.http.api.*

          @KeliverApi
          interface QuotesApi {
              @GET("/quotes")
              suspend fun list(@HeaderMap headers: Map<String, String>): List<String>
          }
        """.trimIndent(),
      ),
    )
    assertThat(result.exitCode).isEqualTo(KotlinCompilation.ExitCode.OK)
    val impl = kspDir.findFile("QuotesApiImpl.kt").readText()
    assertThat(impl).contains("putAll(headers)")
  }

  @Test
  fun header_map_with_nullable_values_filters_via_forEach() {
    val (result, kspDir) = compile(
      SourceFile.kotlin(
        "QuotesApi.kt",
        """
          package com.example

          import dev.keliver.http.api.*

          @KeliverApi
          interface QuotesApi {
              @GET("/quotes")
              suspend fun list(@HeaderMap headers: Map<String, String?>): List<String>
          }
        """.trimIndent(),
      ),
    )
    assertThat(result.exitCode).isEqualTo(KotlinCompilation.ExitCode.OK)
    val impl = kspDir.findFile("QuotesApiImpl.kt").readText()
    assertThat(impl).contains("headers.forEach")
    assertThat(impl).contains("if (v != null)")
  }

  // --- validation paths ---

  @Test
  fun rejects_keliver_api_on_class() {
    val (result, _) = compile(
      SourceFile.kotlin(
        "Bad.kt",
        """
          package com.example
          import dev.keliver.http.api.*
          @KeliverApi
          class NotAnInterface {
              @GET("/x") suspend fun foo(): String = ""
          }
        """.trimIndent(),
      ),
    )
    assertThat(result.exitCode).isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR)
    assertThat(result.messages).contains("@KeliverApi can only be applied to interfaces")
  }

  @Test
  fun rejects_non_suspend_method() {
    val (result, _) = compile(
      SourceFile.kotlin(
        "Bad.kt",
        """
          package com.example
          import dev.keliver.http.api.*
          @KeliverApi
          interface Bad {
              @GET("/x") fun foo(): String
          }
        """.trimIndent(),
      ),
    )
    assertThat(result.exitCode).isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR)
    assertThat(result.messages).contains("must be `suspend`")
  }

  @Test
  fun rejects_body_on_get() {
    val (result, _) = compile(
      SourceFile.kotlin(
        "Bad.kt",
        """
          package com.example
          import dev.keliver.http.api.*
          @KeliverApi
          interface Bad {
              @GET("/x") suspend fun foo(@Body body: String): String
          }
        """.trimIndent(),
      ),
    )
    assertThat(result.exitCode).isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR)
    assertThat(result.messages).contains("@Body is only valid on @POST or @PUT")
  }

  @Test
  fun rejects_path_placeholder_without_matching_param() {
    val (result, _) = compile(
      SourceFile.kotlin(
        "Bad.kt",
        """
          package com.example
          import dev.keliver.http.api.*
          @KeliverApi
          interface Bad {
              @GET("/users/{userId}") suspend fun foo(): String
          }
        """.trimIndent(),
      ),
    )
    assertThat(result.exitCode).isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR)
    assertThat(result.messages).contains("has no matching @Path")
  }

  @Test
  fun rejects_orphan_path_param() {
    val (result, _) = compile(
      SourceFile.kotlin(
        "Bad.kt",
        """
          package com.example
          import dev.keliver.http.api.*
          @KeliverApi
          interface Bad {
              @GET("/quotes") suspend fun foo(@Path("userId") userId: String): String
          }
        """.trimIndent(),
      ),
    )
    assertThat(result.exitCode).isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR)
    assertThat(result.messages).contains("don't appear in the path template")
  }

  @Test
  fun rejects_header_map_with_wrong_value_type() {
    val (result, _) = compile(
      SourceFile.kotlin(
        "Bad.kt",
        """
          package com.example
          import dev.keliver.http.api.*
          @KeliverApi
          interface Bad {
              @GET("/x") suspend fun foo(@HeaderMap h: Map<String, Int>): String
          }
        """.trimIndent(),
      ),
    )
    assertThat(result.exitCode).isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR)
    assertThat(result.messages).contains("must be of type `Map<String, String>`")
  }
}
