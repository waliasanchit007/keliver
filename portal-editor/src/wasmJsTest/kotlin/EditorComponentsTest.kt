import dev.keliver.portal.Bind
import dev.keliver.portal.ComponentSpec
import dev.keliver.portal.MapComponentRegistry
import dev.keliver.portal.PropChoice
import dev.keliver.portal.PropKind
import dev.keliver.portal.PropSpec
import dev.keliver.portal.WidgetNode
import dev.keliver.portal.acceptsModifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** K5: the editor's component registry knows each component's parameter types. */
class EditorComponentsTest {
  @Test fun parameterTypesFromTheRelayDecideAcceptsModifier() {
    val reg = parseComponents(
      """
      [
        {"name":"Card","props":[{"name":"title","kind":"Text","label":"Title"}],"events":[],"slots":[],
         "defaults":{},"paramTypes":{"title":"String","modifier":"Modifier"},"transparent":true,
         "dependencies":[],"body":null},
        {"name":"Plain","props":[],"events":[],"slots":[],"defaults":{},"paramTypes":{},
         "transparent":true,"dependencies":[],"body":null}
      ]
      """.trimIndent(),
    )
    assertEquals(setOf("Card", "Plain"), reg.names())
    assertTrue(reg.spec("Card")!!.acceptsModifier)
    assertFalse(reg.spec("Plain")!!.acceptsModifier)
  }

  @Test fun aRelayWithoutParameterTypesStillParses() {
    val reg = parseComponents(
      """[{"name":"Old","props":[],"events":[],"defaults":{},"transparent":false,"dependencies":[],"body":null}]""",
    )
    assertFalse(reg.spec("Old")!!.acceptsModifier)
  }

  // F8: a parameter the component passes to a widget's named values is shown with those names.
  private val align = listOf(PropChoice(0, "Start"), PropChoice(1, "Center"), PropChoice(2, "End"))
  private fun widgetChoices(type: String, prop: String) = if (type == "StyledText" && prop == "textAlign") align else emptyList()

  @Test fun anIntParameterPassedToANamedWidgetPropertyTakesItsNamesAlsoThroughAnotherComponent() {
    val label = ComponentSpec(
      "Label", listOf(PropSpec("text", PropKind.Text, "Text"), PropSpec("align", PropKind.Int, "Align"), PropSpec("size", PropKind.Int, "Size")),
      emptyList(), emptyMap(),
      body = WidgetNode("StyledText", mapOf("text" to Bind("text"), "textAlign" to Bind("align"), "fontSize" to Bind("size"))),
    )
    val row = ComponentSpec(
      "Row2", listOf(PropSpec("valueAlign", PropKind.Int, "Value align")), emptyList(), emptyMap(),
      body = WidgetNode("Column", emptyMap(), listOf(WidgetNode("Label", mapOf("text" to "x", "align" to Bind("valueAlign"))))),
    )
    val reg = MapComponentRegistry(listOf(label, row))
    assertEquals(align, forwardedChoices(label, "align", reg, ::widgetChoices))
    assertEquals(emptyList(), forwardedChoices(label, "size", reg, ::widgetChoices), "a plain number stays a number")
    assertEquals(align, forwardedChoices(row, "valueAlign", reg, ::widgetChoices), "through Label")
  }

  @Test fun aComponentThatPassesAParameterToItselfDoesNotLoop() {
    val loop = ComponentSpec("Loop", listOf(PropSpec("n", PropKind.Int, "N")), emptyList(), emptyMap(), body = WidgetNode("Loop", mapOf("n" to Bind("n"))))
    assertEquals(emptyList(), forwardedChoices(loop, "n", MapComponentRegistry(listOf(loop)), ::widgetChoices))
  }
}
