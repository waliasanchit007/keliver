/*
 * Copyright (C) 2026 Keliver contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.keliver.compose.gatea.profile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The guest profile's claims that the numbers rest on (docs/GUEST_PROFILE.md). */
class ProfileRunTest {
  private class Steps(val first: StepResult, val open: StepResult, val back: StepResult)

  private fun steps(encoding: Encoding): Steps {
    val run = ProfileRun(encoding, timeCalls = false, items = 20)
    try {
      val first = run.first()
      val open = run.open(1)
      val back = run.back()
      return Steps(first, open, back)
    } finally {
      run.close()
    }
  }

  @Test fun countsMatchThePhonesSplit() {
    val s = steps(Encoding.KOTLINX)
    // The phone's split, 16-row window: open creates 22 widgets with 22 modifiers, back 145 and 145.
    assertEquals(22, s.open.creates)
    assertEquals(22, s.open.modifiers)
    assertEquals(145, s.back.creates)
    assertEquals(145, s.back.modifiers)
    assertTrue(s.back.properties > 1_000)
  }

  @Test fun directEncodingSendsTheSameBatches() {
    val shipped = steps(Encoding.KOTLINX)
    for (encoding in listOf(Encoding.DIRECT, Encoding.DIRECT_MODCACHE)) {
      val variant = steps(encoding)
      assertEquals(shipped.first.hash, variant.first.hash, "$encoding: first")
      assertEquals(shipped.open.hash, variant.open.hash, "$encoding: open")
      assertEquals(shipped.back.hash, variant.back.hash, "$encoding: back")
      assertEquals(shipped.back.bytes, variant.back.bytes, "$encoding: back bytes")
    }
  }

  /** NF1: with the retained stack, back composes nothing new: no widget is created. */
  @Test fun retainedBackCreatesNothing() {
    val run = ProfileRun(Encoding.KOTLINX, timeCalls = false, items = 20, retain = true)
    try {
      run.first()
      repeat(3) { i ->
        val open = run.open(i)
        val back = run.back()
        // Opening composes the detail as before (22 widgets) inside its entry's box and column.
        assertEquals(24, open.creates, "open $i")
        assertEquals(0, back.creates, "back $i: ${back.toJson()}")
        // Back sends: the detail's removal and the list frame's modifier, nothing else.
        assertEquals(1, back.structural, "back $i structural")
        assertEquals(1, back.modifiers, "back $i modifiers")
        assertEquals(0, back.properties, "back $i properties")
      }
    } finally {
      run.close()
    }
  }

  @Test fun noneRecordsStructureOnly() {
    val s = steps(Encoding.NONE)
    assertEquals(145, s.back.creates)
    assertTrue(s.back.bytes < 20_000)
  }
}
