import dev.keliver.portal.PropChoice
import dev.keliver.portal.PropKind
import dev.keliver.portal.modifierSpecs
import dev.keliver.portal.widgetSpec
import dev.keliver.portal.widgetSpecs
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLOptionElement
import org.w3c.dom.HTMLSelectElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.KeyboardEventInit
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

  @Test fun aColourIsShownWithItsAlphaAndParsedBackExactly() {
    val translucentWhite = 0x99FFFFFF.toInt() // a 60%-white card
    assertEquals("#99FFFFFF", argbHex(translucentWhite))
    assertEquals(60, opacityPercent(translucentWhite))
    assertEquals(translucentWhite, parseArgbHex("#99FFFFFF", 0))
    assertEquals(translucentWhite, parseArgbHex("99ffffff", 0))
    // A 6- or 3-digit hex changes the colour and keeps the alpha …
    assertEquals(0x99FF8800.toInt(), parseArgbHex("#FF8800", translucentWhite))
    assertEquals(0x99FF8800.toInt(), parseArgbHex("#F80", translucentWhite))
    // … and over a fully transparent 0 ("default") it is opaque, so it shows.
    assertEquals(0xFFFF8800.toInt(), parseArgbHex("#FF8800", 0))
    for (bad in listOf("", "#", "red", "#12345", "#GG0000", "#123456789")) assertEquals(null, parseArgbHex(bad, 0), bad)
    assertEquals(0x80FFFFFF.toInt(), withOpacity(-1, 50))
    assertEquals(0x00FFFFFF, withOpacity(-1, 0))
    // The system picker has no alpha: its colour keeps the old alpha byte.
    assertEquals(0x99112233.toInt(), withRgb(translucentWhite, 0x112233))
    assertEquals(0xFF112233.toInt(), withRgb(0, 0x112233))
  }

  @Test fun theColourControlCommitsOnlyValidValuesAndKeepsAlpha() {
    val sent = mutableListOf<Int>()
    val ctl = colorControl(0x99FFFFFF.toInt()) { sent += it }
    val inputs = ctl.querySelectorAll("input")
    val picker = inputs.item(0) as HTMLInputElement
    val hex = inputs.item(1) as HTMLInputElement
    val alpha = inputs.item(2) as HTMLInputElement
    assertEquals("color", picker.type)
    assertEquals("#99FFFFFF", hex.value)
    assertEquals("60", alpha.value)
    assertTrue(ctl.querySelector(".swatch") != null)

    picker.value = "#112233"
    picker.dispatchEvent(Event("change"))
    assertEquals(listOf(0x99112233.toInt()), sent) // alpha survived the picker
    assertEquals("#99112233", hex.value)

    hex.value = "nope"
    hex.dispatchEvent(Event("change"))
    assertEquals(1, sent.size) // nothing sent
    assertTrue(hex.classList.contains("invalid"))
    assertTrue((ctl.querySelector(".field-err")?.textContent ?: "").startsWith("Not a colour"))

    hex.value = "#40000000"
    hex.dispatchEvent(Event("change"))
    assertEquals(0x40000000, sent.last())
    assertTrue(!hex.classList.contains("invalid"))
    assertEquals("", ctl.querySelector(".field-err")?.textContent)

    alpha.value = "150"
    alpha.dispatchEvent(Event("change"))
    assertEquals(2, sent.size)
    assertTrue(alpha.classList.contains("invalid"))
    alpha.value = "100"
    alpha.dispatchEvent(Event("change"))
    assertEquals(0xFF000000.toInt(), sent.last())

    // Escape puts the committed value back; Enter and Escape never reach the editor's shortcuts.
    var reachedDocument = 0
    val outer = Ui.el("div")
    outer.appendChild(ctl)
    outer.addEventListener("keydown", { _ -> reachedDocument++ })
    hex.value = "#12"
    hex.dispatchEvent(KeyboardEvent("keydown", KeyboardEventInit(key = "Escape", bubbles = true)))
    assertEquals("#FF000000", hex.value)
    hex.dispatchEvent(KeyboardEvent("keydown", KeyboardEventInit(key = "Enter", bubbles = true)))
    assertEquals(0, reachedDocument)
  }

  @Test fun aComponentsColourNamedIntParamIsAColourEvenFromAnOlderRelay() {
    val reg = parseComponents(
      """[{"name":"Tag","props":[{"name":"tintArgb","kind":"Int","label":"TintArgb"},{"name":"count","kind":"Int","label":"Count"}],
      "events":[],"defaults":{},"body":null,"transparent":false,"dependencies":[]}]""",
    )
    assertEquals(listOf(PropKind.Color, PropKind.Int), reg.spec("Tag")!!.props.map { it.kind })
  }
}
