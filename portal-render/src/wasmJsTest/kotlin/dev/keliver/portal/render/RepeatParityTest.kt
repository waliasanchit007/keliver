package dev.keliver.portal.render

import dev.keliver.layout.api.Constraint
import dev.keliver.layout.compose.Column
import dev.keliver.material.compose.StyledText
import dev.keliver.material.testing.KeliverMaterialTester
import dev.keliver.portal.Bind
import dev.keliver.portal.WidgetNode
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

/**
 * K1a — the FIRST preview<->device parity gate (docs/superpowers/specs/
 * 2026-07-24-ui-consistency-plan.md).
 *
 * Two implementations of "what this screen means" must agree:
 *
 *  A. COMPILED guest Kotlin — what a device runs. `Repeat` does not exist at
 *     runtime; the guest executes a real `forEach`, so rows land directly in
 *     the enclosing layout.
 *  B. The portal INTERPRETER (`RenderNode`) — what the editor preview runs.
 *
 * The regression this pins: B used to wrap `Repeat` children in a bare
 * `Column` (wrap-content by default), which A never has. That silently shrank
 * every repeated row that relied on filling its parent — rows filled their card
 * on Android/iOS but stopped short on web (fixed in 37f94f239).
 *
 * Scope note: this compares SCHEMA value trees. It proves structure, props and
 * modifiers match; it deliberately cannot see host-side Compose internals
 * (e.g. ComposeUiListItem's own fillMaxWidth) or event lambdas, which are
 * excluded from generated value equality. Those need layout tests, K3
 * screenshots, and separate action-sink tests respectively.
 */
class RepeatParityTest {
  private val rows = listOf("Notifications", "App Settings", "Get Help")

  @AfterTest fun reset() {
    // Preview state is global; never leak it into the next test.
    PreviewBindings.mocks.clear()
  }

  /** B: a fill-width Column whose only child is a Repeat over `rows`. */
  private fun interpretedTree() = WidgetNode(
    type = "Column",
    // 1 == Constraint.Fill on the wire (see constraintOf in RenderSupport).
    props = mapOf("width" to 1),
    children = listOf(
      WidgetNode(
        type = "Repeat",
        props = mapOf("items" to "rows", "item" to "row"),
        children = listOf(
          WidgetNode("StyledText", mapOf("text" to Bind("row.label"))),
        ),
      ),
    ),
  )

  @Test
  fun repeatEmitsChildrenWithoutAWrapperContainer() = runTest {
    // A — compiled: the real forEach a device executes.
    val compiled = KeliverMaterialTester {
      setContentAndSnapshot {
        Column(width = Constraint.Fill) {
          rows.forEach { StyledText(text = it) }
        }
      }
    }

    // B — interpreted: same screen as a portal tree, mocks seeded to the SAME
    // state the compiled bindings carry (row count + per-row values).
    PreviewBindings.mocks["rows"] = rows.size.toString()
    PreviewBindings.mocks["row.label"] = rows.joinToString("|")
    val interpreted = KeliverMaterialTester {
      setContentAndSnapshot { RenderNode(interpretedTree()) }
    }

    assertEquals(compiled, interpreted)
  }
}
