/*
 * Copyright (C) 2026 Konduit contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.konduit.console

import kotlin.test.Test
import kotlin.test.assertEquals

class KonduitConsoleTest {
  /**
   * Recording subclass — captures lines instead of printing. Doubles as
   * the canonical test-double pattern adopters can copy for their own
   * tests.
   */
  private class RecordingConsole(tag: String = "Konduit") : DefaultKonduitConsole(tag) {
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
  fun default_tag_is_konduit() {
    val console = RecordingConsole()
    console.log(level = "WARN", message = "x")

    assertEquals(listOf("[Konduit/WARN] x"), console.lines)
  }

  @Test
  fun level_string_is_passed_through_uninterpreted() {
    val console = RecordingConsole()
    console.log(level = "CUSTOM_LEVEL_42", message = "y")

    assertEquals(listOf("[Konduit/CUSTOM_LEVEL_42] y"), console.lines)
  }

  @Test
  fun multiple_calls_each_emit_one_line() {
    val console = RecordingConsole()
    console.log("INFO", "first")
    console.log("INFO", "second")
    console.log("ERROR", "third")

    assertEquals(
      listOf(
        "[Konduit/INFO] first",
        "[Konduit/INFO] second",
        "[Konduit/ERROR] third",
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
    val console = object : DefaultKonduitConsole("App") {
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
