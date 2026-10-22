package dev.keliver.portal.codegen

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** E8: Int-coded props get named choices from the schemas — KDoc code lists and layout enums. */
class PropChoicesTest {
  private fun labels(doc: String, default: Int = 0) = choicesFromDoc(doc, default).map { "${it.value}=${it.label}" }

  @Test fun aKDocCodeListBecomesChoices() {
    assertEquals(listOf("0=Start", "1=Center", "2=End", "3=Justify"), labels("0 start, 1 center, 2 end, 3 justify."))
    assertEquals(
      listOf("0=Clip", "1=Ellipsis (…)", "2=Visible"),
      labels("Overflow when clamped: 0 clip, 1 ellipsis (\"…\"), 2 visible."),
    )
    // No space after the comma, and a list wrapped over lines.
    assertEquals(
      listOf("0=Top start", "1=Top center", "4=Center", "5=Center end"),
      labels("Content alignment: 0 topStart,1 topCenter,4 center,\n   5 centerEnd."),
    )
    // Free-text names keep their words.
    assertEquals(listOf("0=Diagonal TL→BR", "1=Vertical T→B"), labels("Direction: 0 diagonal TL→BR, 1 vertical T→B."))
  }

  @Test fun theDefaultsSentinelJoinsAListThatLeavesItOut() {
    assertEquals(
      listOf("0=Use colorArgb", "1=Primary", "2=On primary", "9=Outline"),
      labels("Theme color role (resolved from the enclosing [Theme]); 0 => use [colorArgb]. 1 primary, 2 onPrimary, 9 outline."),
    )
  }

  @Test fun aNumberWithASentinelIsNotAList() {
    assertEquals(emptyList(), labels("0 => unlimited."))
    assertEquals(emptyList(), labels("Line height in sp; 0 => font default."))
    assertEquals(emptyList(), labels("Letter spacing in 1/100 sp (e.g. 50 => 0.5sp); 0 => default."))
    assertEquals(emptyList(), labels("Explicit weight 100..900; 0 => use [bold]."))
    assertEquals(emptyList(), labels("2+ ARGB stops => gradient palette; empty => solid."))
    // A repeated code is not a code list.
    assertEquals(emptyList(), labels("0 a, 0 b."))
  }

  @Test fun layoutEnumNamesComeFromTheValueClassesThemselves() {
    val enums = layoutEnumChoices(javaClass.classLoader)
    assertEquals(listOf("Wrap", "Fill"), enums.getValue(MappedKind.CONSTRAINT).map { it.label })
    assertEquals(listOf("Start", "Center", "End", "Stretch"), enums.getValue(MappedKind.CROSS_AXIS).map { it.label })
    assertEquals(
      listOf("Start", "Center", "End", "Space between", "Space around", "Space evenly"),
      enums.getValue(MappedKind.MAIN_AXIS).map { it.label },
    )
    assertEquals(listOf("Clip", "Scroll"), enums.getValue(MappedKind.OVERFLOW).map { it.label })
    // The wire value is the ordinal — what portal-render's crossAxisOf/… decode.
    assertEquals(listOf(0, 1, 2, 3), enums.getValue(MappedKind.CROSS_AXIS).map { it.value })
  }

  @Test fun kDocIsReadFromTheSchemaSources() {
    val dir = createTempDirectory("prop-docs").toFile()
    File(dir, "Schema.kt").writeText(
      """
      package x

      /** A text. */
      @Widget(1)
      data class Label(
        @Property(1) val text: String,
        /** 0 start, 1 center,
         *  2 end. */
        @Property(2) val align: Int = 0,
        // a line comment is not KDoc
        @Property(3) val size: Int = 0,
      )
      """.trimIndent(),
    )
    val docs = readPropDocs(dir)
    assertEquals(setOf("Label.align"), docs.keys)
    assertEquals(listOf("Start", "Center", "End"), choicesFromDoc(docs["Label.align"], 0).map { it.label })
    dir.deleteRecursively()
  }

  @Test fun choicesAreAttachedOnlyToFreeIntPropsAndLayoutEnums() {
    val enums = mapOf(MappedKind.CONSTRAINT to listOf(ChoicePlan(0, "Wrap"), ChoicePlan(1, "Fill")))
    val docs = mapOf("W.align" to "0 start, 1 end.", "W.tintArgb" to "0 none, 1 some.")
    val plan = WidgetPlan.Include(
      "W", "p", "Material",
      listOf(
        MappedProp("align", MappedKind.INT, required = false, defaultExpr = "0"),
        MappedProp("tintArgb", MappedKind.INT, required = false, defaultExpr = "0"), // a colour: never choices
        MappedProp("width", MappedKind.CONSTRAINT, required = false, defaultExpr = "Constraint.Wrap"),
        MappedProp("size", MappedKind.INT, required = false, defaultExpr = "0"),
      ),
      emptyList(), emptyList(), hasChildren = false,
    ).withChoices(docs, enums) as WidgetPlan.Include
    assertEquals(listOf(2, 0, 2, 0), plan.props.map { it.choices.size })
    val src = emitCatalog(listOf(plan))
    assertContains(src, "PropSpec(\"align\", PropKind.Int, \"Align\", choices = listOf(PropChoice(0, \"Start\"), PropChoice(1, \"End\")))")
    assertContains(src, "PropSpec(\"width\", PropKind.Int, \"Width\", choices = listOf(PropChoice(0, \"Wrap\"), PropChoice(1, \"Fill\")))")
    assertContains(src, "PropSpec(\"size\", PropKind.Int, \"Size\"),")
    assertTrue("PropChoice" !in src.lines().first { "tintArgb" in it })
  }

  @Test fun labelsAreEscapedAsKotlinStrings() {
    assertEquals("\"a \\\"b\\\" \\\$c\"", kotlinString("a \"b\" \$c"))
  }
}
