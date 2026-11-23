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

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Which files Spotless gives the Keliver header ([GETS_KELIVER_HEADER], used with `find()`). */
class LicenseHeaderPatternTest {
  private fun getsHeader(content: String) = GETS_KELIVER_HEADER.toRegex().containsMatchIn(content)

  @Test fun fileWithPackageGetsHeader() {
    assertTrue(getsHeader("package dev.keliver\n\nclass A\n"))
    assertTrue(getsHeader("/*\n * Copyright (C) 2026 Keliver contributors.\n */\npackage dev.keliver\n"))
    assertTrue(getsHeader("/*\n * Copyright (C) 2026 Keliver contributors.\n */\n@file:JvmName(\"A\")\n\npackage dev.keliver\n"))
  }

  @Test fun rootPackageFileWithImportOrFileAnnotationGetsHeader() {
    assertTrue(getsHeader("import kotlin.math.max\n\nfun f() = max(1, 2)\n"))
    assertTrue(getsHeader("@file:Suppress(\"x\")\n\nfun f() = 1\n"))
  }

  @Test fun redwoodDerivedFileKeepsSquareNotice() {
    assertFalse(getsHeader("/*\n * Copyright (C) 2023 Square, Inc.\n *\n */\npackage app.cash\n"))
    assertFalse(getsHeader("/*\n * Copyright (C) 2021-2023 Square, Inc.\n */\npackage app.cash\n"))
  }

  @Test fun shortKeliverHeaderIsMadeTheFullOne() {
    assertTrue(getsHeader("/*\n * Copyright (C) 2026 Keliver contributors.\n * Licensed under the Apache License, Version 2.0.\n */\npackage a\n"))
  }

  @Test fun fileDocumentationAbovePackageIsKept() {
    // Spotless would replace this comment with the header.
    assertFalse(getsHeader("/*\n * V2 M7: what this host does.\n */\npackage dev.keliver\n"))
    assertFalse(getsHeader("// What this file is for.\npackage dev.keliver\n"))
  }

  @Test fun rootPackageFileWithoutAnchorKeepsItsLeadingComment() {
    // Spotless would replace this comment with the header, or fail to find where it ends.
    assertFalse(getsHeader("/*\n * What this file is for.\n */\n// More.\nfun main() = run()\n"))
    // A Keliver header with nothing below it to anchor to.
    assertFalse(getsHeader("/*\n * Copyright (C) 2026 Keliver contributors.\n */\ninternal fun f() = 1\n"))
    // "package" inside a comment is not a line that starts with it.
    assertFalse(getsHeader("/*\n * Copyright (C) 2026 Keliver contributors.\n * package docs\n */\ninternal fun f() = 1\n"))
  }
}
