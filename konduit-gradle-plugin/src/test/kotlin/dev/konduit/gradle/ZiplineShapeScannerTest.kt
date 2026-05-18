/*
 * Copyright (C) 2026 Konduit contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.konduit.gradle

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.startsWith
import org.junit.Test

class ZiplineShapeScannerTest {

  @Test
  fun rejects_function_typed_parameter() {
    val source = """
      interface HostNavigator : ZiplineService {
          fun onItemSelected(id: String, callback: (Boolean) -> Unit)
      }
    """.trimIndent()

    val findings = ZiplineShapeScanner.scan(source, sourceLabel = "Test.kt")

    assertThat(findings).hasSize(1)
    assertThat(findings[0].interfaceName).isEqualTo("HostNavigator")
    assertThat(findings[0].parameterSnippet).startsWith("callback")
    assertThat(findings[0].sourceLabel).isEqualTo("Test.kt")
  }

  @Test
  fun rejects_nullable_function_typed_parameter() {
    val source = """
      interface Foo : ZiplineService {
          fun bar(onDone: (() -> Unit)?)
      }
    """.trimIndent()

    assertThat(ZiplineShapeScanner.scan(source)).hasSize(1)
  }

  @Test
  fun accepts_serializable_parameters() {
    val source = """
      @Serializable data class Quote(val text: String)

      interface HostQuotes : ZiplineService {
          fun getQuotes(filter: String?): List<Quote>
          fun snapshot(): Quote
      }
    """.trimIndent()

    assertThat(ZiplineShapeScanner.scan(source)).isEmpty()
  }

  @Test
  fun accepts_callback_service_parameter() {
    val source = """
      interface ResultCallback : ZiplineService {
          fun onResult(value: String)
      }

      interface HostFoo : ZiplineService {
          fun doThing(callback: ResultCallback)
      }
    """.trimIndent()

    assertThat(ZiplineShapeScanner.scan(source)).isEmpty()
  }

  @Test
  fun handles_nested_braces_in_interface_body() {
    val source = """
      interface Outer : ZiplineService {
          fun ok() {
              val x = object {
                  fun nested() = Unit
              }
          }
          fun bad(cb: (Int) -> Int)
      }
    """.trimIndent()

    val findings = ZiplineShapeScanner.scan(source)

    assertThat(findings).hasSize(1)
    assertThat(findings[0].interfaceName).isEqualTo("Outer")
    assertThat(findings[0].parameterSnippet).contains("cb")
  }

  @Test
  fun reports_multiple_offenders_in_one_interface() {
    val source = """
      interface Multi : ZiplineService {
          fun a(cb1: (String) -> Unit)
          fun b(cb2: (Int) -> Boolean)
      }
    """.trimIndent()

    assertThat(ZiplineShapeScanner.scan(source)).hasSize(2)
  }

  @Test
  fun ignores_function_types_outside_zipline_service() {
    val source = """
      class NotAService {
          fun whatever(cb: (Int) -> Int) = Unit
      }
    """.trimIndent()

    assertThat(ZiplineShapeScanner.scan(source)).isEmpty()
  }

  @Test
  fun catches_fully_qualified_zipline_service_reference() {
    // Adopters who don't import ZiplineService (or who use the FQN
    // for disambiguation) used to slip past the regex. The qualified
    // form is now matched.
    val source = """
      interface HostBad : app.cash.zipline.ZiplineService {
          fun action(cb: (Int) -> Unit)
      }
    """.trimIndent()

    val findings = ZiplineShapeScanner.scan(source)

    assertThat(findings).hasSize(1)
    assertThat(findings[0].interfaceName).isEqualTo("HostBad")
  }

  @Test
  fun still_catches_short_zipline_service_reference() {
    // Sanity check that adding the optional qualifier didn't break
    // the canonical short form.
    val source = """
      interface HostBad : ZiplineService {
          fun action(cb: (Int) -> Unit)
      }
    """.trimIndent()

    assertThat(ZiplineShapeScanner.scan(source)).hasSize(1)
  }

  @Test
  fun formatError_names_findings_and_references_known_bugs() {
    val findings = listOf(
      ZiplineShapeScanner.Finding(
        interfaceName = "HostNav",
        parameterSnippet = "cb: (Int) -> Unit",
        sourceLabel = "shared/src/commonMain/.../Protocol.kt",
      ),
    )

    val message = ZiplineShapeScanner.formatError(findings)

    assertThat(message).contains("HostNav")
    assertThat(message).contains("cb:")
    assertThat(message).contains("KNOWN_BUGS")
    assertThat(message).contains("Workaround")
  }
}
