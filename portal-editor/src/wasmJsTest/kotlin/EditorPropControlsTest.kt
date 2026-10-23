import dev.keliver.portal.PropChoice
import dev.keliver.portal.PropKind
import dev.keliver.portal.modifierSpecs
import dev.keliver.portal.widgetSpec
import dev.keliver.portal.widgetSpecs
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLOptionElement
import org.w3c.dom.HTMLSelectElement
import dev.keliver.portal.document.PropValue
import dev.keliver.portal.document.lit
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

  private fun key(k: String, shift: Boolean = false) = KeyboardEvent("keydown", KeyboardEventInit(key = k, shiftKey = shift, bubbles = true, cancelable = true))

  @Test fun aTextFieldPreviewsEachKeystrokeButWritesOncePerCommit() {
    val previews = mutableListOf<String>()
    val commits = mutableListOf<String>()
    val area = textField("Monthly", onPreview = { previews += it }, onCommit = { commits += it })
    for (typed in listOf("M", "Mo", "Mon")) {
      area.value = typed
      area.dispatchEvent(Event("input"))
    }
    assertEquals(listOf("M", "Mo", "Mon"), previews)
    assertTrue(commits.isEmpty()) // nothing written while typing
    area.dispatchEvent(Event("change")) // leaving the field
    assertEquals(listOf("Mon"), commits)
    area.dispatchEvent(Event("change"))
    assertEquals(listOf("Mon"), commits) // an unchanged field writes nothing

    // Escape puts the committed text back on the field and the canvas.
    area.value = "Monday"
    area.dispatchEvent(Event("input"))
    area.dispatchEvent(key("Escape"))
    assertEquals("Mon", area.value)
    assertEquals("Mon", previews.last())
    assertEquals(listOf("Mon"), commits)
  }

  @Test fun aShiftEnterIsALineBreakAndAPlainEnterIsTheFieldsCommit() {
    val area = textField("a") {}
    val shift = key("Enter", shift = true)
    area.dispatchEvent(shift)
    assertTrue(!shift.defaultPrevented) // the browser types the line break
    val enter = key("Enter")
    area.dispatchEvent(enter)
    assertTrue(enter.defaultPrevented)
  }

  @Test fun aFieldRemovedWhileFocusedStillCommitsItsTypedEdit() {
    val commits = mutableListOf<String>()
    val area = textField("a", onPreview = {}, onCommit = { commits += it })
    area.value = "abc"
    area.dispatchEvent(Event("input"))
    flushPendingField() // what the panel does before it re-renders
    assertEquals(listOf("abc"), commits)
    flushPendingField()
    assertEquals(listOf("abc"), commits) // once
  }

  /** E48: {} starts the template from the field's text when pressed, not the text it was rendered with. */
  @Test fun aTemplateStartsFromTheTypedText() {
    val area = textField("Plan") {}
    area.value = "Plan ahead"
    area.dispatchEvent(Event("input"))
    assertEquals(
      PropValue.Template(listOf(lit("Plan ahead"), PropValue.Bind("text"))),
      startTemplate(area.value, "text"),
    )
    assertEquals(PropValue.Template(listOf(PropValue.Bind("text"))), startTemplate("", "text"))
  }

  @Test fun aRowPutsItsLabelAndActionsOnOneLineAndItsControlUnder() {
    val row = PropRow("Text").control(Ui.textarea()).action(Ui.button("@") {}).action(null)
    assertEquals(listOf("prop-label", "prop-ctl", "prop-actions"), (0 until row.root.children.length).map { row.root.children.item(it)!!.className })
    assertEquals("prop", row.root.className)
    assertEquals(1, row.root.querySelectorAll(".prop-actions button").length)
    assertEquals("prop compact", PropRow("Width", PropRow.Layout.Compact).root.className)
    // The label names (and focuses) the row's first field.
    val area = row.root.querySelector("textarea")!!
    assertTrue(area.id.isNotEmpty())
    assertEquals(area.id, row.root.querySelector("label")!!.getAttribute("for"))
  }

  @Test fun numbersAreReadStrictlyAndRangeChecked() {
    val int = NumberRule(whole = true)
    assertEquals(24, parseNumber(" 24 ", int).getOrNull())
    assertEquals(-5, parseNumber("-5", int).getOrNull())
    for (bad in listOf("", "abc", "1.5", "12px", "9999999999")) assertTrue(parseNumber(bad, int).isFailure, bad)
    assertEquals("That number is too large", parseNumber("9999999999", int).exceptionOrNull()?.message)
    val dp = numberRule("Spacer", "height", dev.keliver.portal.PropKind.Double)
    assertEquals(9.0, parseNumber("9", dp).getOrNull())
    assertEquals("A size can't be negative", parseNumber("-1", dp).exceptionOrNull()?.message)
    val fontSize = numberRule("StyledText", "fontSize", dev.keliver.portal.PropKind.Int)
    assertEquals("Font size must be at least 1", parseNumber("0", fontSize).exceptionOrNull()?.message)
    assertEquals("Font size must be at least 1", parseNumber("-5", fontSize).exceptionOrNull()?.message)
    assertTrue(numberRule("StyledText", "maxLines", dev.keliver.portal.PropKind.Int).min == null)
    assertEquals("8", showNumber(8.0))
    assertEquals("0.5", showNumber(0.5))
  }

  @Test fun aNumberFieldWritesOnlyAValidCommittedValueOnce() {
    val previews = mutableListOf<Number>()
    val commits = mutableListOf<Number>()
    val ctl = numberField(24, numberRule("StyledText", "fontSize", dev.keliver.portal.PropKind.Int), { previews += it }) { commits += it }
    val input = ctl.querySelector("input") as HTMLInputElement
    val err = ctl.querySelector(".field-err")!!
    fun type(v: String) { input.value = v; input.dispatchEvent(Event("input")) }
    fun leave() = input.dispatchEvent(Event("change"))

    type(""); type("abc"); leave()
    assertTrue(commits.isEmpty()) // the empty field is not 0, and abc is nothing
    assertTrue(input.classList.contains("invalid"))
    assertEquals("Enter a whole number", err.textContent)
    type("-5"); leave()
    assertTrue(commits.isEmpty())
    assertEquals("Font size must be at least 1", err.textContent)

    // Each invalid commit put the canvas back to the written value (24), sending nothing.
    assertEquals(listOf<Number>(24, 24), previews)
    previews.clear()
    type("1"); type("18") // typing a two-digit value previews, writes nothing
    assertEquals(listOf<Number>(1, 18), previews)
    assertTrue(commits.isEmpty())
    input.dispatchEvent(key("Enter"))
    leave() // Enter blurs, which fires change
    assertEquals(listOf<Number>(18), commits) // one write, one undo step
    assertTrue(!input.classList.contains("invalid"))
    assertEquals("", err.textContent)
    leave()
    assertEquals(1, commits.size)

    // Arrows step (Shift: 10), Escape puts the committed value back.
    input.dispatchEvent(KeyboardEvent("keydown", KeyboardEventInit(key = "ArrowUp", shiftKey = true, cancelable = true)))
    assertEquals("28", input.value)
    input.dispatchEvent(key("Escape"))
    assertEquals("18", input.value)
    assertEquals(18, previews.last())
    assertEquals(1, commits.size)
  }

  @Test fun aDpFieldTakesDecimalsAndNeverGoesBelowZero() {
    val commits = mutableListOf<Number>()
    val ctl = numberField(8.0, numberRule("Spacer", "height", dev.keliver.portal.PropKind.Double)) { commits += it }
    val input = ctl.querySelector("input") as HTMLInputElement
    assertEquals("8", input.value)
    input.dispatchEvent(KeyboardEvent("keydown", KeyboardEventInit(key = "ArrowDown", shiftKey = true, cancelable = true)))
    assertEquals("0", input.value) // clamped at the minimum
    input.value = "9"
    input.dispatchEvent(Event("input"))
    input.dispatchEvent(Event("change"))
    assertEquals(listOf<Number>(9.0), commits)
  }

  @Test fun aPropTheCallDoesNotPassShowsAsTheDefaultNotFalseOrBlack() {
    assertTrue(isUnset(emptyMap(), "enabled", dev.keliver.portal.PropKind.Bool))
    assertTrue(!isUnset(mapOf("enabled" to false), "enabled", dev.keliver.portal.PropKind.Bool))
    assertTrue(!isUnset(emptyMap(), "text", dev.keliver.portal.PropKind.Text), "an empty text is shown as empty")

    val box = PropRow("Enabled", PropRow.Layout.Inline).control(
      (kotlinx.browser.document.createElement("input") as HTMLInputElement).also { it.type = "checkbox" },
    ).root
    markUnset(box)
    assertTrue(box.classList.contains("unset"))
    assertTrue((box.querySelector("input") as HTMLInputElement).indeterminate)

    val size = PropRow("Size", PropRow.Layout.Compact).control(numberField(0, numberRule("StyledText", "fontSize", dev.keliver.portal.PropKind.Int)) {}).root
    markUnset(size)
    val input = size.querySelector("input") as HTMLInputElement
    assertEquals("", input.value)
    assertEquals("default", input.placeholder)

    val sel = PropRow("Width").control(choiceSelect(listOf(dev.keliver.portal.PropChoice(0, "Wrap"), dev.keliver.portal.PropChoice(1, "Fill")), null) {}).root
    markUnset(sel)
    assertTrue(sel.classList.contains("unset"))
    assertEquals("Wrap", (sel.querySelector("select") as org.w3c.dom.HTMLSelectElement).let { it.options.item(it.selectedIndex)?.textContent }, "its first name, a coded enum's default")
  }

  @Test fun aFontSizeTypoIsCaughtNotWritten() {
    val rule = numberRule("StyledText", "fontSize", dev.keliver.portal.PropKind.Int)
    assertEquals("Font size must be at most 400", parseNumber("2428", rule).exceptionOrNull()?.message)
    assertEquals(24, parseNumber("24", rule).getOrNull())
  }
}
