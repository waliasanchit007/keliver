/*
 * Copyright (C) 2026 Keliver contributors.
 * Licensed under the Apache License, Version 2.0.
 */
@file:OptIn(KeliverGeneratedApi::class)

package dev.keliver.compose.gatea.profile

import dev.keliver.compose.foundation.lazy.LazyWindowSizing
import dev.keliver.compose.wire.KeliverGeneratedApi
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

  /** NF3: the press composes the detail; the click that follows only shows it. */
  @Test fun aPreparedOpenCreatesNothingAtTheClick() {
    for (depth in 0..1) {
      val run = ProfileRun(Encoding.KOTLINX, timeCalls = false, items = 20, prepared = depth, press = true)
      try {
        run.first()
        repeat(3) { i ->
          val press = run.press(i)
          val open = run.open(i)
          // The detail (22 widgets) inside its entry's box and column, all at the press.
          assertEquals(24, press.creates, "depth $depth press $i")
          assertEquals(0, open.creates, "depth $depth open $i: ${open.toJson()}")
          run.back()
        }
      } finally {
        run.close()
      }
    }
  }

  /** NF3: a cancelled press disposes what it composed; the next click composes as without a press. */
  @Test fun aCancelledPressIsDropped() {
    val run = ProfileRun(Encoding.KOTLINX, timeCalls = false, items = 20, prepared = 0, press = true)
    try {
      run.first()
      assertEquals(24, run.press(1).creates)
      val cancel = run.cancel(1)
      assertEquals(0, cancel.creates)
      assertEquals(1, cancel.structural, "the hidden entry's removal")
      assertEquals(24, run.open(1).creates)
    } finally {
      run.close()
    }
  }

  /**
   * NF3: with the host's viewport report (10 rows), the list composed again on back starts with
   * 11 rows (the viewport and one), not the fixed 15: 4 rows of 9 widgets fewer.
   */
  @Test fun backStartsWithAViewportSizedWindow() {
    fun backCreates(sized: Boolean): Int {
      LazyWindowSizing.viewportSized = sized
      val run = ProfileRun(Encoding.KOTLINX, timeCalls = false, items = 20, viewportRows = 10)
      try {
        run.first()
        checkNotNull(run.viewport()) { "the first list was reported" }
        run.open(1)
        val back = run.back()
        // The host reports the new list; the window then grows to the strategy's (as without NF3).
        checkNotNull(run.viewport())
        return back.creates
      } finally {
        run.close()
        LazyWindowSizing.viewportSized = true
      }
    }
    assertEquals(145, backCreates(sized = false))
    assertEquals(145 - 4 * 9, backCreates(sized = true))
  }

  @Test fun noneRecordsStructureOnly() {
    val s = steps(Encoding.NONE)
    assertEquals(145, s.back.creates)
    assertTrue(s.back.bytes < 20_000)
  }
}
