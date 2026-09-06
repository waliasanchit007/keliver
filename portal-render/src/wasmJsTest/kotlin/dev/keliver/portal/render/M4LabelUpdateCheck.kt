package dev.keliver.portal.render

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.keliver.material.testing.KeliverMaterialTester
import dev.keliver.material.testing.StyledTextValue
import dev.keliver.portal.render.fixture.M4CounterBindings
import dev.keliver.portal.render.fixture.M4CounterScreen
import dev.keliver.testing.flatten
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

/**
 * M4 fixture 1 — EVALUATOR CHECK. Not a participant artifact.
 *
 * Requirement under test, stated independently of any implementation:
 *
 *   The screen shows a summary label. Invoking `addItem()` is a deterministic
 *   state transition that changes the presenter's `summary` from "0 items" to
 *   "1 items". After that transition the rendered summary label MUST read
 *   "1 items".
 *
 * This composes the REAL screen composable against a real, state-backed
 * bindings object and asserts on rendered widget values across a genuine
 * recomposition. It is a runtime observation, not source inspection.
 *
 * Note the first assertion passes for a hardcoded label too — that is
 * deliberate and is what makes the defect survive a single screenshot. Only
 * the post-transition assertion separates them.
 */
class M4LabelUpdateCheck {
  private class StateBackedBindings : M4CounterBindings {
    private var count by mutableStateOf(0)
    override val summary: String get() = "$count items"
    override fun addItem() { count += 1 }
  }

  private fun List<*>.summaryLabels(): List<String> =
    filterIsInstance<StyledTextValue>().map { it.text }

  @Test
  fun summaryLabelReflectsStateAfterTransition() = runTest {
    val bindings = StateBackedBindings()

    KeliverMaterialTester {
      val before = setContentAndSnapshot { M4CounterScreen(bindings) }
        .flatten().toList().summaryLabels()
      assertEquals(
        listOf("Basket", "0 items"),
        before,
        "precondition: initial render should show the zero-state summary",
      )

      // The deterministic state transition.
      bindings.addItem()

      // A screen that does not depend on the changed state produces NO
      // recomposition, so awaitSnapshot times out rather than returning a
      // stale frame. Treat that as "the rendering did not change" and fall
      // back to the last observed frame, so the assertion below reports the
      // requirement rather than an ambiguous timeout.
      val after = runCatching { awaitSnapshot() }
        .map { it.flatten().toList().summaryLabels() }
        .getOrDefault(before)

      assertEquals(
        listOf("Basket", "1 items"),
        after,
        "REQUIREMENT: the summary label must reflect state after addItem()",
      )
    }
  }
}
