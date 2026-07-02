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
}
