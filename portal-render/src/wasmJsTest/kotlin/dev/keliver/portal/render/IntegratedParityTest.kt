package dev.keliver.portal.render

import dev.keliver.material.testing.ButtonValue
import dev.keliver.material.testing.KeliverMaterialTester
import dev.keliver.material.testing.TextFieldValue
import dev.keliver.portal.ComponentSlotSpec
import dev.keliver.portal.ComponentSpec
import dev.keliver.portal.Expansion
import dev.keliver.portal.MapComponentRegistry
import dev.keliver.portal.PropKind
import dev.keliver.portal.PropSpec
import dev.keliver.portal.deserializeTree
import dev.keliver.portal.expandForPreview
import dev.keliver.portal.render.fixture.INTEGRATED_SCREEN_JSON
import dev.keliver.portal.render.fixture.IntegratedParityBindings
import dev.keliver.portal.render.fixture.IntegratedParityRow
import dev.keliver.portal.render.fixture.IntegratedParityScreen
import dev.keliver.portal.render.fixture.PARITY_LEAF_BODY_JSON
import dev.keliver.portal.render.fixture.PARITY_PANEL_BODY_JSON
import dev.keliver.portal.render.fixture.PARITY_SECTION_BODY_JSON
import dev.keliver.testing.flatten
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.test.runTest

/**
 * K1b Wasm half of the source -> golden -> interpreter parity bridge.
 *
 * The compiled fixture is path A (device/production semantics). Path B starts
 * from the exact trees that portal-ingest locks to the fixture sources on JVM.
 */
class IntegratedParityTest {
  private data class Row(
    override val id: String,
    override val label: String,
  ) : IntegratedParityRow

  private class Bindings(
    override val title: String,
    override val showDetails: Boolean,
    override val detail: String,
    override val rows: List<IntegratedParityRow>,
    override val draft: String,
  ) : IntegratedParityBindings {
    override fun refresh() = Unit
    override fun open(value: String) = Unit
    override fun openRow(value: String) = Unit
    override fun updateDraft(value: String) = Unit
  }

  private val registry = MapComponentRegistry(
    listOf(
      ComponentSpec(
        name = "ParityLeaf",
        props = listOf(PropSpec("label", PropKind.Text, "Label")),
        events = emptyList(),
        paramTypes = mapOf("label" to "String"),
        body = deserializeTree(PARITY_LEAF_BODY_JSON),
        transparent = true,
      ),
      ComponentSpec(
        name = "ParitySection",
        props = emptyList(),
        events = emptyList(),
        paramTypes = mapOf("content" to "@Composable () -> Unit"),
        slots = listOf(ComponentSlotSpec("content")),
        body = deserializeTree(PARITY_SECTION_BODY_JSON),
        transparent = true,
      ),
      ComponentSpec(
        name = "ParityPanel",
        props = listOf(PropSpec("title", PropKind.Text, "Title")),
        events = emptyList(),
        paramTypes = mapOf("title" to "String"),
        body = deserializeTree(PARITY_PANEL_BODY_JSON),
        transparent = true,
        dependencies = setOf("ParityLeaf", "ParitySection"),
      ),
    ),
  )

  @AfterTest
  fun resetGlobals() {
    PreviewBindings.mocks.clear()
    PreviewBindings.actionSink = { _, _ -> }
    componentPreview = null
  }

  @Test
  fun integratedFixtureMatchesWithMultipleRowsAndVisibleCondition() = runTest {
    assertParity(
      Bindings(
        title = "Account",
        showDetails = true,
        detail = "KYC verified",
        rows = listOf(Row("row-1", "Notifications"), Row("row-2", "Security")),
        draft = "hello",
      ),
    )
  }

  @Test
  fun integratedFixtureMatchesWithZeroRowsAndHiddenCondition() = runTest {
    assertParity(
      Bindings(
        title = "Empty account",
        showDetails = false,
        detail = "must stay hidden",
        rows = emptyList(),
        draft = "",
      ),
    )
  }

  @Test
  fun interpretedEventsReachTheActionSinkWithConcreteArguments() = runTest {
    val bindings = Bindings(
      title = "Actions",
      showDetails = false,
      detail = "",
      rows = listOf(Row("row-1", "First row"), Row("row-2", "Second row")),
      draft = "before",
    )
    seedMocks(bindings)
    installComponentPreview()

    val snapshot = KeliverMaterialTester {
      setContentAndSnapshot { RenderNode(deserializeTree(INTEGRATED_SCREEN_JSON)) }
    }
    val events = mutableListOf<Pair<String, String?>>()
    PreviewBindings.actionSink = { name, argument -> events += name to argument }

    val buttons = snapshot.flatten().filterIsInstance<ButtonValue>().toList()
    assertNotNull(buttons.single { it.text == "Refresh" }.onClick).invoke()
    assertNotNull(buttons.single { it.text == "Open detail" }.onClick).invoke()
    assertNotNull(buttons.single { it.text == "First row" }.onClick).invoke()
    val field = snapshot.flatten().filterIsInstance<TextFieldValue>().single()
    assertNotNull(field.onValueChange).invoke("after")

    assertEquals(
      listOf(
        "refresh" to null,
        "open" to "DETAIL",
        "openRow" to "row-1",
        "updateDraft" to "after",
      ),
      events,
    )
  }

  private suspend fun assertParity(bindings: Bindings) {
    val compiled = KeliverMaterialTester {
      setContentAndSnapshot { IntegratedParityScreen(bindings) }
    }

    seedMocks(bindings)
    installComponentPreview()
    val interpreted = KeliverMaterialTester {
      setContentAndSnapshot { RenderNode(deserializeTree(INTEGRATED_SCREEN_JSON)) }
    }

    assertEquals(compiled, interpreted)
  }

  private fun seedMocks(bindings: Bindings) {
    PreviewBindings.mocks["title"] = bindings.title
    PreviewBindings.mocks["showDetails"] = bindings.showDetails.toString()
    PreviewBindings.mocks["detail"] = bindings.detail
    PreviewBindings.mocks["rows"] = bindings.rows.size.toString()
    PreviewBindings.mocks["rows.row.id"] = bindings.rows.joinToString("|") { it.id }
    PreviewBindings.mocks["rows.row.label"] = bindings.rows.joinToString("|") { it.label }
    PreviewBindings.mocks["draft"] = bindings.draft
  }

  private fun installComponentPreview() {
    componentPreview = { node ->
      RenderNode(
        when (val expanded = expandForPreview(node, registry)) {
          is Expansion.Transparent -> expanded.tree
          is Expansion.Opaque -> error("opaque parity component ${expanded.name}: ${expanded.reason}")
          is Expansion.Cycle -> error("parity component cycle: ${expanded.path.joinToString(" -> ")}")
        },
      )
    }
  }
}
