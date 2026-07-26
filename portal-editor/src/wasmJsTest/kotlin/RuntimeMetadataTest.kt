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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RuntimeMetadataTest {
  private val editor = AppRuntimeMetadata("0.3.1-SNAPSHOT", 1)

  @Test fun parsesDeclaredRuntime() {
    val parsed = parseRuntimeMetadata(
      """{"appRuntime":{"keliverVersion":"0.3.0","widgetVersion":1}}""",
    )

    assertEquals(
      AppRuntimeMetadata("0.3.0", 1),
      assertIs<RuntimeMetadataLoad.Declared>(parsed).appRuntime,
    )
  }

  @Test fun parsesUndeclaredRuntime() {
    assertIs<RuntimeMetadataLoad.Undeclared>(
      parseRuntimeMetadata("""{"appRuntime":null}"""),
    )
  }

  @Test fun rejectsInvalidRuntime() {
    val parsed = assertIs<RuntimeMetadataLoad.Unavailable>(
      parseRuntimeMetadata(
        """{"appRuntime":{"keliverVersion":"","widgetVersion":0}}""",
      ),
    )

    assertTrue(parsed.reason.contains("keliverVersion"))
  }

  @Test fun reportsExactMatch() {
    val report = compareRuntimeMetadata(editor, editor)

    assertEquals(RuntimeCompatibilityStatus.Match, report.status)
  }

  @Test fun reportsKeliverSkewWhenWidgetProtocolMatches() {
    val report = compareRuntimeMetadata(
      editor,
      AppRuntimeMetadata("0.3.0", 1),
    )

    assertEquals(RuntimeCompatibilityStatus.KeliverSkew, report.status)
  }

  @Test fun widgetMismatchTakesPrecedence() {
    val report = compareRuntimeMetadata(
      editor,
      AppRuntimeMetadata("0.3.0", 2),
    )

    assertEquals(RuntimeCompatibilityStatus.WidgetMismatch, report.status)
  }
}
