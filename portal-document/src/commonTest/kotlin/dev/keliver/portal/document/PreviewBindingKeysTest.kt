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
package dev.keliver.portal.document

import dev.keliver.portal.Bind
import dev.keliver.portal.WidgetNode
import dev.keliver.portal.collectPreviewBindingKeys
import kotlin.test.Test
import kotlin.test.assertEquals

class PreviewBindingKeysTest {
  @Test fun includesDirectConditionAndNamespacedRepeatBindings() {
    val tree = WidgetNode(
      "Column",
      children = listOf(
        WidgetNode("StyledText", mapOf("text" to Bind("title"))),
        WidgetNode("Condition", mapOf("field" to "enabled")),
        WidgetNode(
          "Repeat",
          mapOf("items" to "notes", "item" to "note"),
          listOf(
            WidgetNode(
              "ListItem",
              mapOf(
                "headline" to Bind("note.title"),
                "supporting" to Bind("subtitle"),
              ),
            ),
          ),
        ),
      ),
    )

    assertEquals(
      setOf("title", "enabled", "notes", "notes.note.title", "subtitle"),
      collectPreviewBindingKeys(tree),
    )
  }
}
