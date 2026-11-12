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
package dev.keliver.compose.gatea.composeui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/** The same-source samples (shared by the JVM and iOS pixel tests) differ only in their imports. */
class SameSourceFilesTest {
  @Test fun onlyImportsDiffer() {
    val dir = File("src/sameSourceTest/kotlin/dev/keliver/compose/gatea/composeui/samples")
    fun body(f: File) = f.readLines().filterNot {
      it.startsWith("import ") || it.startsWith("package ") || it.startsWith("//")
    }
    for (sample in listOf("SampleCard.kt", "SampleOrders.kt")) {
      assertEquals(body(File(dir, "native/$sample")), body(File(dir, "guest/$sample")), sample)
      val guestImports = File(dir, "guest/$sample").readLines().filter { it.startsWith("import ") }
      val nativeImports = File(dir, "native/$sample").readLines().filter { it.startsWith("import ") }
      // Sorted: the formatter orders imports lexically, and the rewrite changes a prefix.
      assertEquals(nativeImports.sorted(), guestImports.map { it.replace("import dev.keliver.compose.", "import androidx.compose.") }.sorted(), sample)
    }
  }
}
