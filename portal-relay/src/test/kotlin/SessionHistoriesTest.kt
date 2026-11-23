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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** L2 (feedback round 5): a screen's cross-document undo order, kept by the relay for every browser. */
class SessionHistoriesTest {
  private val order = """{"undo":[["default","home","Card"],["default","home"]],"redo":[]}"""

  @Test fun aScreensHistoryIsKeptForAnyEditorThatAsks() {
    val h = SessionHistories()
    assertNull(h.get("default", "home"), "none kept since the relay started")
    assertTrue(h.put("default", "home", order))
    assertEquals(order, h.get("default", "home"))
    assertNull(h.get("default", "orders"), "each screen has its own")
    assertNull(h.get("other", "home"), "and each project")
  }

  @Test fun onlyABoundedJsonObjectIsKept() {
    val h = SessionHistories(maxChars = 100)
    assertFalse(h.put("default", "home", "not json"))
    assertFalse(h.put("default", "home", "[1,2]"), "an array is no history")
    assertFalse(h.put("default", "home", """{"undo":"${"x".repeat(200)}"}"""), "too long")
    assertNull(h.get("default", "home"), "nothing refused was kept")
  }
}
