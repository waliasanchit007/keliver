/*
 * Copyright (C) 2026 Keliver contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.keliver.sample.host.threeway

import android.os.Trace
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.ui.Modifier
import androidx.compose.ui.node.DelegatableNode
import dev.keliver.compose.gatea.composeui.RetainedReveal
import dev.keliver.sample.threeway.R1Keys

/**
 * NF5 R1, `noripple` (README, "What the run shows"): an indication that draws nothing, provided as
 * `LocalIndication` over a form's content instead of the theme's ripple. The emulator dry run found a
 * RenderThread animation in every guest arm (inferred: the ripple of a tapped row in an entry the
 * retained container hid) that takes the vsync of the first frame after the driver's blocking tap;
 * this arm shows whether the ripple is its source.
 */
internal object NoIndication : IndicationNodeFactory {
  override fun create(interactionSource: InteractionSource): DelegatableNode = object : Modifier.Node() {}

  override fun equals(other: Any?): Boolean = other === this

  override fun hashCode(): Int = 0
}

/**
 * NF5 R1 (docs/nf5-r1/README.md), benchmark-only: the host's reveal policy for the guest's `r1prep`
 * stack (RevealStack.kt), provided as gate A's `LocalRetainedReveal` around the guest content.
 *
 * Always: the detail's prepared and parked entries ([R1Keys]) are never shown by the container's
 * order (without a policy the container would show its last entry, the hidden detail). `reveal`
 * ([revealOnClick]): a click in the list shows the prepared detail in the next frame, before the
 * guest runs. `idlemeasure` ([measurePrepared]): the hidden detail is measured whenever it changes,
 * while idle, so the reveal's frame only places and draws it.
 *
 * Main thread only. Marks, epoch ms on the host's clock, for the open in progress ([takeMarks]):
 * - `revealed`: the click's input event revealed the detail;
 * - `revealDrawn`: the start of the draw of the frame that drew the revealed detail (the window's
 *   OnDrawListener, as the bench's `drawn`, so the two compare), from [onWindowDraw];
 * - `revealRecorded`: when the revealed detail's draw was recorded in that frame (inside
 *   `Record View#draw()`, after Compose's measure and layout);
 * - `revealLive`: the guest's own state showed the detail (its batch applied): the reveal is over.
 *
 * With [trace], each is also a trace section (`r1:reveal`, `r1:revealDrawn`, `r1:revealLive`), so a
 * Perfetto trace finds the reveal's frame: `r1:revealDrawn` nests in its `Record View#draw()`.
 */
internal class R1Reveal(
  override val revealOnClick: Boolean,
  override val measurePrepared: Boolean,
  private val trace: Boolean,
  /**
   * False with `listenonly`, the A/B's control (README, "What the run shows"): the container keeps
   * its click listener (with `reveal`), but no entry is revealable, so a click reveals nothing and
   * the guest's own click shows the detail, as without the reveal.
   */
  private val revealable: Boolean = true,
) : RetainedReveal {
  private val marks = LinkedHashMap<String, Long>()

  /** The start of the window's latest draw (epoch ms). */
  private var lastWindowDraw = 0L

  override fun isPrepared(key: String): Boolean = R1Keys.isPrepared(key)

  override fun isRevealable(key: String): Boolean = revealable && R1Keys.isRevealable(key)

  /** The window's OnDrawListener: every frame's draw starts here, before its views record. */
  fun onWindowDraw(atMillis: Long) {
    lastWindowDraw = atMillis
  }

  override fun onReveal(key: String) {
    marks["revealed"] = System.currentTimeMillis()
    section("r1:reveal")
  }

  override fun onRevealDrawn(key: String) {
    marks["revealDrawn"] = lastWindowDraw
    marks["revealRecorded"] = System.currentTimeMillis()
    section("r1:revealDrawn")
  }

  override fun onRevealLive(key: String) {
    marks["revealLive"] = System.currentTimeMillis()
    section("r1:revealLive")
  }

  /** The marks since the last call (the open that just finished), then none. */
  fun takeMarks(): Map<String, Long> = LinkedHashMap(marks).also { marks.clear() }

  /** A zero-length trace section: a marker in the trace's slice of the main thread. */
  private fun section(name: String) {
    if (!trace) return
    Trace.beginSection(name)
    Trace.endSection()
  }

  companion object {
    /**
     * The policy for the run's experiments (`r1prep`, with `reveal`, `idlemeasure`, `listenonly`,
     * `trace`); null without `r1prep`.
     */
    fun forExperiments(experiments: Set<String>): R1Reveal? =
      if ("r1prep" !in experiments) {
        null
      } else {
        R1Reveal(
          revealOnClick = "reveal" in experiments,
          measurePrepared = "idlemeasure" in experiments,
          trace = "trace" in experiments,
          revealable = "listenonly" !in experiments,
        )
      }
  }
}
