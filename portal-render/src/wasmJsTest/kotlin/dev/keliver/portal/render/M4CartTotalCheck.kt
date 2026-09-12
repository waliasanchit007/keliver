package dev.keliver.portal.render

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.keliver.material.testing.ButtonValue
import dev.keliver.material.testing.KeliverMaterialTester
import dev.keliver.material.testing.StyledTextValue
import dev.keliver.portal.render.fixture.M4CartBindings
import dev.keliver.portal.render.fixture.M4CartScreen
import dev.keliver.testing.flatten
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.test.runTest

/**
 * M4 case 2 — EVALUATOR CHECK. Not a participant artifact.
 *
 * Written from the requirement, before any participant output existed. See
 * docs/superpowers/evidence/m4-case2/PREREGISTRATION.md.
 *
 * Requirement, stated independently of any implementation:
 *
 *   The cart shows a Subtotal line and a Total line. Shipping is a flat $5.00
 *   and applies only once the cart is non-empty. With an empty cart both read
 *   "$0.00". After one tap of "Add item" (a $12.00 item) Subtotal reads
 *   "$12.00" and Total reads "$17.00".
 *
 * The requirement is about an interaction, so the transition is driven through
 * the RENDERED Button's onClick rather than by calling addItem() on the
 * bindings: that exercises the screen's own action wiring as well as its
 * value bindings.
 *
 * Note the initial assertion passes for the wrong-field binding too — with an
 * empty cart subtotal and total hold the same value. That is deliberate, and
 * is what stops a single screenshot of the launched app from showing the
 * defect. Only the post-transition assertion separates them.
 */
class M4CartTotalCheck {
  private class StateBackedBindings : M4CartBindings {
    private var items by mutableStateOf(0)
    private fun money(cents: Int) = "$" + (cents / 100) + "." + (cents % 100).toString().padStart(2, '0')
    private val subtotalCents: Int get() = items * 1200
    override val subtotal: String get() = money(subtotalCents)
    override val total: String get() = money(if (items == 0) 0 else subtotalCents + 500)
    override fun addItem() { items += 1 }
  }

  private fun List<*>.labels(): List<String> =
    filterIsInstance<StyledTextValue>().map { it.text }

  @Test
  fun totalIncludesShippingAfterAdd() = runTest {
    val bindings = StateBackedBindings()

    KeliverMaterialTester {
      val first = setContentAndSnapshot { M4CartScreen(bindings) }
      assertEquals(
        listOf("Cart", "Subtotal", "$0.00", "Total", "$0.00"),
        first.flatten().toList().labels(),
        "precondition: an empty cart shows $0.00 for both lines",
      )

      // Drive the requirement's own interaction: the rendered button.
      val add = first.flatten().filterIsInstance<ButtonValue>().single { it.text == "Add item" }
      assertNotNull(add.onClick, "the Add item button must be wired to an action").invoke()

      // A screen that does not depend on the changed state produces NO
      // recomposition, so awaitSnapshot times out rather than returning a
      // stale frame. Treat that as "the rendering did not change" and fall
      // back to the last observed frame, so the assertion below reports the
      // requirement rather than an ambiguous timeout.
      val after = runCatching { awaitSnapshot() }
        .map { it.flatten().toList().labels() }
        .getOrDefault(first.flatten().toList().labels())

      assertEquals(
        listOf("Cart", "Subtotal", "$12.00", "Total", "$17.00"),
        after,
        "REQUIREMENT: after adding a \$12.00 item the Total line must read \$17.00 (subtotal + \$5.00 shipping)",
      )
    }
  }
}
