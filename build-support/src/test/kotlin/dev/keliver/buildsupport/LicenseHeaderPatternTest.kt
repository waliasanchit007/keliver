/*
 * Copyright (C) 2026 Keliver contributors.
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
package dev.keliver.buildsupport

import com.diffplug.spotless.FormatterStep
import com.diffplug.spotless.OnMatch
import com.diffplug.spotless.generic.LicenseHeaderStep
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Which files Spotless gives the Keliver header ([GETS_KELIVER_HEADER], used with `find()`), and
 * what its header step then does with [KELIVER_HEADER_DELIMITER] (the plugin's Kotlin settings).
 */
class LicenseHeaderPatternTest {
  private fun getsHeader(content: String) = GETS_KELIVER_HEADER.toRegex().containsMatchIn(content)

  private val header = "/*\n * Copyright (C) \$YEAR Keliver contributors.\n *\n * Licensed.\n */\n"

  private val step: FormatterStep = LicenseHeaderStep.headerDelimiter(header, KELIVER_HEADER_DELIMITER)
    .build()
    .filterByContent(OnMatch.INCLUDE, GETS_KELIVER_HEADER)

  /** What `spotlessApply` writes; `spotlessCheck` fails while this differs from [raw]. */
  private fun apply(raw: String): String = step.format(raw, File("A.kt")) ?: raw

  private fun headed(year: String, rest: String) =
    "/*\n * Copyright (C) $year Keliver contributors.\n *\n * Licensed.\n */\n$rest"

  @Test fun everyFileButARedwoodDerivedOneGetsHeader() {
    assertTrue(getsHeader("package dev.keliver\n\nclass A\n"))
    assertTrue(getsHeader("/*\n * Copyright (C) 2026 Keliver contributors.\n */\npackage dev.keliver\n"))
    assertTrue(getsHeader("import kotlin.math.max\n\nfun f() = max(1, 2)\n"))
    assertTrue(getsHeader("@file:Suppress(\"x\")\n\nfun f() = 1\n"))
    // A file's own documentation above its package line does not exempt it.
    assertTrue(getsHeader("/*\n * V2 M7: what this host does.\n */\npackage dev.keliver\n"))
    assertTrue(getsHeader("// What this file is for.\npackage dev.keliver\n"))
  }

  @Test fun redwoodDerivedFileKeepsSquareNotice() {
    assertFalse(getsHeader("/*\n * Copyright (C) 2023 Square, Inc.\n *\n */\npackage app.cash\n"))
    assertFalse(getsHeader("/*\n * Copyright (C) 2021-2023 Square, Inc.\n */\npackage app.cash\n"))
    val square = "/*\n * Copyright (C) 2023 Square, Inc.\n */\npackage app.cash\n"
    assertEquals(square, apply(square))
    // Upstream's newest notice is 2025.
    val square2025 = "/*\n * Copyright (C) 2025 Square, Inc.\n */\npackage app.cash\n"
    assertFalse(getsHeader(square2025))
    assertEquals(square2025, apply(square2025))
    assertFalse(getsHeader("/*\n * Copyright (C) 2019 Square, Inc.\n */\npackage app.cash\n"))
  }

  @Test fun squareNoticeDatedAfterTheForkGetsHeader() {
    // The fork began in 2026; no upstream file carries a 2026 Square notice, so one is a copied
    // template, not Redwood code.
    val rest = "package dev.keliver\n\nclass A\n"
    val square2026 = "/*\n * Copyright (C) 2026 Square, Inc.\n *\n * Licensed.\n */\n$rest"
    assertTrue(getsHeader(square2026))
    val applied = apply(square2026)
    assertNotEquals(square2026, applied)
    assertTrue(applied.startsWith("/*\n * Copyright (C) "), applied)
    assertFalse("Square" in applied, applied)
    assertTrue(applied.endsWith("*/\n$rest"), applied)
    assertEquals(applied, apply(applied))
    // A later year, and a range that runs past 2025, are not Redwood's either.
    assertTrue(getsHeader("/*\n * Copyright (C) 2027 Square, Inc.\n */\npackage a\n"))
    assertTrue(getsHeader("/*\n * Copyright (C) 2024-2026 Square, Inc.\n */\npackage a\n"))
    assertTrue(getsHeader("/*\n * Copyright (C) 2026-2027 Square, Inc.\n */\npackage a\n"))
  }

  @Test fun fileWithoutHeaderGetsIt() {
    val raw = "package dev.keliver\n\nclass A\n"
    val applied = apply(raw)
    assertTrue(applied.startsWith("/*\n * Copyright (C) "), applied)
    assertTrue(applied.endsWith("*/\n$raw"), applied)
    assertEquals(applied, apply(applied))
  }

  @Test fun fileStartingWithPlainCommentGetsHeaderAndFailsCheckUntilApplied() {
    val raw = "/*\n * What this file is for.\n */\npackage dev.keliver\n\nclass A\n"
    val applied = apply(raw)
    // spotlessCheck fails: the file is not what spotlessApply writes.
    assertNotEquals(raw, applied)
    // spotlessApply replaces the comment with the header, which is why a file's documentation
    // goes below its package/import block.
    assertTrue(applied.startsWith("/*\n * Copyright (C) "), applied)
    assertTrue(applied.endsWith("*/\npackage dev.keliver\n\nclass A\n"), applied)
    assertFalse("What this file is for" in applied)
    // Once applied, spotlessCheck passes.
    assertEquals(applied, apply(applied))
  }

  @Test fun documentationBelowImportsIsKept() {
    val rest = "import java.io.File\n\n/*\n * What this file is for.\n */\n\nclass A(val f: File)\n"
    assertEquals(rest, apply(headed("2026", rest)).substringAfter(" */\n"))
    assertEquals(headed("2026", rest), apply(headed("2026", rest)))
  }

  @Test fun shortKeliverHeaderIsMadeTheFullOne() {
    val raw = "/*\n * Copyright (C) 2026 Keliver contributors.\n * Licensed under the Apache License, Version 2.0.\n */\npackage a\n"
    assertTrue(getsHeader(raw))
    assertEquals(headed("2026", "package a\n"), apply(raw))
  }

  @Test fun rootPackageFileWithoutImportsAnchorsOnItsFirstKDoc() {
    val rest = "/**\n * What [f] is for, and this file.\n */\nfun f() = 1\n"
    val applied = apply(rest)
    assertTrue(applied.endsWith("*/\n$rest"), applied)
    assertEquals(applied, apply(applied))
    // A plain comment above that KDoc is replaced.
    assertEquals(applied, apply("/*\n * Lost.\n */\n\n$rest"))
  }

  @Test fun rootPackageFileWithNeitherImportsNorKDocFails() {
    assertFailsWith<Throwable> { apply("/*\n * What this file is for.\n */\nfun main() = run()\n") }
    // "package" inside a comment is not a line that starts with it.
    assertFailsWith<Throwable> { apply("/*\n * package docs\n */\ninternal fun f() = 1\n") }
  }
}
