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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** E40: a new screen's or project's name is checked before anything is created. */
class EditorNamesTest {
  private val screens = listOf("home", "order_status")

  @Test fun aKotlinReadableNewNameIsAccepted() {
    assertNull(nameProblem("faq", screens, "screen"))
    assertNull(nameProblem("  order_review2 ", screens, "screen"))
  }

  @Test fun aNameThatCannotNameAFileAndAFunctionIsRefused() {
    assertTrue("letter" in nameProblem("9lives", screens, "screen")!!)
    assertTrue("letter" in nameProblem("my screen", screens, "screen")!!)
    assertTrue("letter" in nameProblem("a-b", screens, "screen")!!)
    assertEquals("Enter a name for the screen", nameProblem(" ", screens, "screen"))
  }

  @Test fun anExistingNameIsRefusedInAnyCase() {
    assertEquals("A screen named “home” already exists", nameProblem("Home", screens, "screen"))
  }
}
