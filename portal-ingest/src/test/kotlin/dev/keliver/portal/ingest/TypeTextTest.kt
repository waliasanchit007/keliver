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
