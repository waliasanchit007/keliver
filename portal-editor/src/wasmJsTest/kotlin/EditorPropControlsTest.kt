import dev.keliver.portal.PropChoice
import dev.keliver.portal.modifierSpecs
import dev.keliver.portal.widgetSpec
import dev.keliver.portal.widgetSpecs
import org.w3c.dom.HTMLOptionElement
import org.w3c.dom.HTMLSelectElement
import org.w3c.dom.events.Event
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The properties panel's typed controls: what they show and what they commit. */
class EditorPropControlsTest {
  private fun HTMLSelectElement.labels() = (0 until options.length).map { (options.item(it) as HTMLOptionElement).textContent }

  @Test fun anIntCodedPropIsADropdownOfItsNames() {
    val align = widgetSpec("StyledText")!!.props.first { it.name == "align" }
    val picked = mutableListOf<Int>()
    val sel = choiceSelect(align.choices, 1) { picked += it }
    assertEquals(listOf("Start", "Center", "End", "Justify"), sel.labels())
    assertEquals("1", sel.value)
    sel.value = "2"
    sel.dispatchEvent(Event("change"))
    assertEquals(listOf(2), picked)
  }

  @Test fun everyLayoutEnumAndListedCodeIsOffered() {
    fun choicesOf(type: String, prop: String) = widgetSpec(type)!!.props.first { it.name == prop }.choices.map { it.label }
    assertEquals(listOf("Wrap", "Fill"), choicesOf("Column", "width"))
    assertEquals(listOf("Clip", "Scroll"), choicesOf("Column", "overflow"))
    assertEquals(listOf("Start", "Center", "End", "Stretch"), choicesOf("Column", "horizontalAlignment"))
    assertEquals(listOf("Start", "Center", "End", "Space between", "Space around", "Space evenly"), choicesOf("Column", "verticalAlignment"))
    assertEquals(listOf("Clip", "Ellipsis (…)", "Visible"), choicesOf("StyledText", "overflow"))
    assertEquals("Use colorArgb", choicesOf("StyledText", "colorRole").first())
    assertEquals(listOf("Fit", "Crop", "Fill bounds"), choicesOf("AsyncImage", "contentScale"))
    // A scoped layout modifier's enum argument too.
    assertEquals(
      listOf("Start", "Center", "End", "Stretch"),
      modifierSpecs.first { it.name == "HorizontalAlignment" }.props.single().choices.map { it.label },
    )
    // A plain number stays a number.
    assertTrue(widgetSpec("StyledText")!!.props.first { it.name == "maxLines" }.choices.isEmpty())
    assertTrue(widgetSpec("StyledText")!!.props.first { it.name == "colorArgb" }.choices.isEmpty())
  }

  @Test fun everyChoicePropRendersItsLabelsInOrder() {
    for (spec in widgetSpecs) for (p in spec.props) if (p.choices.isNotEmpty()) {
      assertEquals(p.choices.map { it.label }, choiceSelect(p.choices, p.choices.first().value) {}.labels(), "${spec.type}.${p.name}")
    }
  }

  @Test fun aValueTheSchemaDoesNotNameIsKeptNotReplaced() {
    val picked = mutableListOf<Int>()
    val sel = choiceSelect(listOf(PropChoice(0, "Start"), PropChoice(1, "End")), 7) { picked += it }
    assertEquals(listOf("Start", "End", "Other (7)"), sel.labels())
    assertEquals("7", sel.value)
    assertTrue(picked.isEmpty()) // rendering never writes
  }
}
