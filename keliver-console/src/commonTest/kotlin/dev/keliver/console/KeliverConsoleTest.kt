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
package dev.keliver.console

import kotlin.test.Test
import kotlin.test.assertEquals

class KeliverConsoleTest {
  /**
   * Recording subclass — captures lines instead of printing. Doubles as
   * the canonical test-double pattern adopters can copy for their own
   * tests.
   */
  private class RecordingConsole(tag: String = "Keliver") : DefaultKeliverConsole(tag) {
    val lines: MutableList<String> = mutableListOf()
    override fun output(line: String) {
      lines += line
    }
  }

  @Test
  fun default_formats_messages_with_tag_and_level() {
    val console = RecordingConsole(tag = "TestApp")
    console.log(level = "INFO", message = "hello")

    assertEquals(listOf("[TestApp/INFO] hello"), console.lines)
  }

  @Test
  fun default_tag_is_keliver() {
    val console = RecordingConsole()
    console.log(level = "WARN", message = "x")

    assertEquals(listOf("[Keliver/WARN] x"), console.lines)
  }

  @Test
  fun level_string_is_passed_through_uninterpreted() {
    val console = RecordingConsole()
    console.log(level = "CUSTOM_LEVEL_42", message = "y")

    assertEquals(listOf("[Keliver/CUSTOM_LEVEL_42] y"), console.lines)
  }

  @Test
  fun multiple_calls_each_emit_one_line() {
    val console = RecordingConsole()
    console.log("INFO", "first")
    console.log("INFO", "second")
    console.log("ERROR", "third")

    assertEquals(
      listOf(
        "[Keliver/INFO] first",
        "[Keliver/INFO] second",
        "[Keliver/ERROR] third",
      ),
      console.lines,
    )
  }

  @Test
  fun fully_custom_subclass_can_route_anywhere() {
    // Demo of the routing-hook pattern — adopter routes WARN/ERROR
    // to one sink and INFO to another. Verifies the override hook
    // gives full control.
    val errors = mutableListOf<String>()
    val info = mutableListOf<String>()
    val console = object : DefaultKeliverConsole("App") {
      override fun output(line: String) {
        when {
          line.contains("ERROR") || line.contains("WARN") -> errors += line
          else -> info += line
        }
      }
    }

    console.log("INFO", "hi")
    console.log("ERROR", "boom")
    console.log("WARN", "watch out")

    assertEquals(listOf("[App/INFO] hi"), info)
    assertEquals(listOf("[App/ERROR] boom", "[App/WARN] watch out"), errors)
  }
}
