package dev.keliver.portal.ingest

import dev.keliver.portal.COMPONENT_FORWARDS_MODIFIER_PROP
import dev.keliver.portal.Expansion
import dev.keliver.portal.MapComponentRegistry
import dev.keliver.portal.WidgetNode
import dev.keliver.portal.acceptsModifier
import dev.keliver.portal.expandForPreview
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * K5: a component that declares `modifier: Modifier` and passes it to its root
 * is grammar (it used to make the whole component opaque), and the preview
 * gives that root the INSTANCE's modifiers, as the compiled call does.
 */
class ComponentModifierForwardingTest {
  private fun component(name: String, signature: String, body: String) = Recognizer.recognizeComponent(
    "$name.kt",
    """
      import androidx.compose.runtime.Composable
      import dev.keliver.Modifier
      import dev.keliver.layout.api.Constraint
      import dev.keliver.layout.compose.Column
      import dev.keliver.material.compose.StyledText
      import dev.keliver.material.compose.padding

      @Composable
      fun $name($signature) {
        $body
      }
    """.trimIndent(),
  )!!.spec

  private val card = component(
    "MyCard",
    "title: String, modifier: Modifier = Modifier",
    "Column(width = Constraint.Fill, modifier = modifier) { StyledText(text = title) }",
  )

  private fun tree(e: Expansion) = (e as Expansion.Transparent).tree

  @Test fun forwardingTheModifierParameterToTheRootIsGrammar() {
    assertTrue(card.transparent, card.diagnostic)
    assertTrue(card.acceptsModifier)
    assertEquals(true, card.body!!.props[COMPONENT_FORWARDS_MODIFIER_PROP])
  }

  @Test fun theBodysOwnChainAfterTheParameterIsKept() {
    val padded = component(
      "Padded",
      "modifier: Modifier",
      "Column(modifier = modifier.padding(8)) { StyledText(text = \"x\") }",
    )
    assertTrue(padded.transparent, padded.diagnostic)
    assertEquals(8, padded.body!!.props["mod.Padding.allDp"])
  }

  @Test fun forwardingBelowTheRootStaysOpaque() {
    val nested = component(
      "Nested",
      "modifier: Modifier = Modifier",
      "Column { Column(modifier = modifier) { StyledText(text = \"x\") } }",
    )
    assertFalse(nested.transparent)
  }

  @Test fun aParameterThatIsNotAModifierIsNotForwarding() {
    val other = component(
      "Other",
      "modifier: String",
      "Column(modifier = modifier) { StyledText(text = \"x\") }",
    )
    assertFalse(other.transparent)
  }

  @Test fun instanceModifiersLandOnTheRootBeforeTheBodysOwn() {
    val padded = component(
      "Padded",
      "modifier: Modifier",
      "Column(modifier = modifier.padding(8)) { StyledText(text = \"x\") }",
    )
    val reg = MapComponentRegistry(listOf(padded))
    val root = tree(
      expandForPreview(WidgetNode("Padded", mapOf("mod.Flex.value" to 1.0, "mod.Alpha.pct" to 50)), reg),
    )
    assertNull(root.props[COMPONENT_FORWARDS_MODIFIER_PROP])
    assertEquals(
      listOf("mod.Flex.value", "mod.Alpha.pct", "mod.Padding.allDp"),
      root.props.keys.filter { it.startsWith("mod.") },
    )
    assertEquals(1.0, root.props["mod.Flex.value"])
  }

  @Test fun aModifierBothSidesSetPreviewsWithTheInstanceValue() {
    val padded = component(
      "Padded",
      "modifier: Modifier",
      "Column(modifier = modifier.padding(8)) { StyledText(text = \"x\") }",
    )
    val root = tree(
      expandForPreview(WidgetNode("Padded", mapOf("mod.Padding.allDp" to 2)), MapComponentRegistry(listOf(padded))),
    )
    assertEquals(2, root.props["mod.Padding.allDp"])
  }

  @Test fun aComponentThatDoesNotForwardIgnoresInstanceModifiers() {
    val ignoring = component(
      "Ignoring",
      "modifier: Modifier = Modifier",
      "Column { StyledText(text = \"x\") }",
    )
    assertTrue(ignoring.transparent)
    val root = tree(
      expandForPreview(WidgetNode("Ignoring", mapOf("mod.Flex.value" to 1.0)), MapComponentRegistry(listOf(ignoring))),
    )
    assertTrue(root.props.keys.none { it.startsWith("mod.") }, "${root.props}")
  }

  @Test fun forwardingThroughANestedComponentReachesItsRoot() {
    val outer = Recognizer.recognizeComponent(
      "Outer.kt",
      """
        import androidx.compose.runtime.Composable
        import dev.keliver.Modifier

        @Composable
        fun Outer(title: String, modifier: Modifier = Modifier) {
          MyCard(title = title, modifier = modifier)
        }
      """.trimIndent(),
      MapComponentRegistry(listOf(card)),
    )!!.spec
    assertTrue(outer.transparent, outer.diagnostic)
    val reg = MapComponentRegistry(listOf(card, outer))
    val root = tree(expandForPreview(WidgetNode("Outer", mapOf("title" to "T", "mod.Flex.value" to 2.0)), reg))
    assertEquals("Column", root.type)
    assertEquals(2.0, root.props["mod.Flex.value"])
    assertEquals("T", root.children.single().props["text"])
  }
}
