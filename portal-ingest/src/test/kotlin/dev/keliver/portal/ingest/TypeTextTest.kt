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
package dev.keliver.portal.ingest

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** K3 review nit: a declared type is nullable only by a `?` that closes it at top level. */
class TypeTextTest {
  @Test fun aTrailingQuestionMarkAtTopLevelIsNullable() {
    for (t in listOf("String?", " Int? ", "(() -> Unit)?", "((Int) -> Unit)?", "Map<String, () -> Unit>?", "List<String?>?")) {
      assertTrue(isNullableType(t), t)
    }
  }

  @Test fun aFunctionTypeReturningANullableIsNotNullable() {
    // `() -> Unit?` is a non-null function returning `Unit?`: a nullable function type is parenthesized.
    for (t in listOf("() -> Unit?", "(Int) -> String?", "suspend () -> Unit?", "@Composable () -> Unit?", "String.() -> Int?")) {
      assertFalse(isNullableType(t), t)
    }
  }

  @Test fun otherTypesAreNotNullable() {
    for (t in listOf("String", "List<String?>", "() -> Unit", "(() -> Unit?)", "", "  ")) {
      assertFalse(isNullableType(t), t)
    }
    assertFalse(isNullableType(null))
  }
}
