package dev.keliver.portal.codegen

import kotlin.test.Test
import kotlin.test.assertContains

class EmitCatalogTest {
  private val text = WidgetPlan.Include(
    name = "StyledText", composePackage = "dev.keliver.material.compose", category = "Material",
    props = listOf(
      MappedProp("text", MappedKind.TEXT, required = true, defaultExpr = null),
      MappedProp("fontSize", MappedKind.INT, required = false, defaultExpr = "14"),
      MappedProp("colorArgb", MappedKind.INT, required = false, defaultExpr = "0"),
    ),
    skippedProps = emptyList(), events = emptyList(), hasChildren = false,
  )
  private val card = WidgetPlan.Include(
    name = "Card", composePackage = "dev.keliver.material.compose", category = "Material",
    props = emptyList(), skippedProps = emptyList(), events = emptyList(), hasChildren = true,
  )

  @Test fun emitsWidgetSpecsAndCompatFunction() {
    val src = emitCatalog(listOf(text, card))
    assertContains(src, "package dev.keliver.portal")
    assertContains(src, "val widgetSpecs: List<WidgetSpec> = listOf(")
    assertContains(src, "WidgetSpec(\"StyledText\", \"Material\", listOf(")
    assertContains(src, "PropSpec(\"text\", PropKind.Text, \"Text\")")
    assertContains(src, "PropSpec(\"colorArgb\", PropKind.Color, \"Color argb\")")
    assertContains(src, "acceptsChildren = true")
    assertContains(src, "\"text\" to \"New StyledText\"")
    assertContains(src, "fun editableProps(type: String): List<PropSpec>")
  }

  @Test fun marginIsCataloguedAsFourDoubleSides() {
    val row = card.copy(name = "Row", category = "Layout",
      props = listOf(MappedProp("margin", MappedKind.MARGIN, required = false, defaultExpr = "Margin.Zero")))
    val src = emitCatalog(listOf(row))
    for (side in listOf("start", "end", "top", "bottom")) {
      assertContains(src, "PropSpec(\"margin.$side\", PropKind.Double, \"Margin $side\")")
    }
  }

  @Test fun scopedModifierSpecsCarryScopesExtensionAndArgTypes() {
    val size = ModPlan("Size", "size", "dev.keliver.material.compose", listOf(
      MappedProp("widthDp", MappedKind.INT, required = true, defaultExpr = null),
      MappedProp("heightDp", MappedKind.INT, required = true, defaultExpr = null),
    ))
    val layoutSize = ModPlan("Size", "size", "dev.keliver.layout.compose", listOf(
      MappedProp("width", MappedKind.DP, required = true, defaultExpr = null),
      MappedProp("height", MappedKind.DP, required = true, defaultExpr = null),
    ), scopes = listOf("RowScope", "ColumnScope", "BoxScope"))
    val mods = disambiguateModifiers(listOf(size, layoutSize))
    val src = emitCatalog(emptyList(), mods)
    assertContains(src, "ModifierSpec(\"Size\", listOf(PropSpec(\"widthDp\", PropKind.Int, \"Width dp\"), PropSpec(\"heightDp\", PropKind.Int, \"Height dp\")), argTypes = listOf(\"Int\", \"Int\")),")
    assertContains(src, "ModifierSpec(\"LayoutSize\", listOf(PropSpec(\"width\", PropKind.Double, \"Width\"), PropSpec(\"height\", PropKind.Double, \"Height\")), scopes = listOf(\"Row\", \"Column\", \"Box\"), extension = \"size\", argTypes = listOf(\"Dp\", \"Dp\")),")
  }

  /** N8: this copy must equal portal-core's MARGIN_SIDES (asserted in portal-document's LayoutInvariantsTest). */
  @Test fun marginSidesAreTheWireOrder() {
    kotlin.test.assertEquals(listOf("start", "end", "top", "bottom"), MARGIN_SIDES)
  }

  @Test fun everyWidgetPropRecordsItsSourceTypeForDesignTokens() {
    val row = card.copy(name = "Row", category = "Layout", props = listOf(
      MappedProp("width", MappedKind.CONSTRAINT, required = false, defaultExpr = "Constraint.Wrap"),
      MappedProp("margin", MappedKind.MARGIN, required = false, defaultExpr = "Margin.Zero"),
    ))
    val src = emitCatalog(listOf(text, row))
    // K2: a token fits only a plain literal of its own type — the catalog says which.
    assertContains(src, "internal val widgetPropSourceTypes: Map<String, String> = mapOf(")
    assertContains(src, "\"StyledText.text\" to \"String\",")
    assertContains(src, "\"StyledText.colorArgb\" to \"Int\",")
    assertContains(src, "\"Row.width\" to \"Constraint\",")
    assertContains(src, "\"Row.margin.start\" to \"Dp\",")
  }
}
