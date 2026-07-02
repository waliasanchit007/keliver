package dev.keliver.portal.codegen

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PropModelTest {
  private fun plan(w: FakeWidget) =
    planWidget(composePackage = "dev.keliver.material.compose", category = "Material", widget = w)

  @Test fun allSupportedKindsMap() {
    val w = FakeWidget(fq("dev.keliver.material", "Thing"), listOf(
      FakeProperty("text", fq("kotlin", "String")),
      FakeProperty("count", fq("kotlin", "Int"), defaultExpression = "14"),
      FakeProperty("on", fq("kotlin", "Boolean"), defaultExpression = "false"),
      FakeProperty("ratio", fq("kotlin", "Double"), defaultExpression = "0.0"),
      FakeProperty("pos", fq("kotlin", "Float"), defaultExpression = "0f"),
      FakeProperty("colors", fq("kotlin.collections", "List", params = listOf(fq("kotlin", "Int"))), defaultExpression = "emptyList()"),
      FakeProperty("stops", fq("kotlin.collections", "List", params = listOf(fq("kotlin", "Float"))), defaultExpression = "emptyList()"),
      FakeProperty("height", fq("dev.keliver.ui", "Dp"), defaultExpression = "Dp(0.0)"),
      FakeProperty("width", fq("dev.keliver.layout.api", "Constraint"), defaultExpression = "Constraint.Wrap"),
    ))
    val inc = assertIs<WidgetPlan.Include>(plan(w))
    assertEquals(
      listOf(MappedKind.TEXT, MappedKind.INT, MappedKind.BOOL, MappedKind.DOUBLE, MappedKind.FLOAT,
        MappedKind.INT_LIST, MappedKind.FLOAT_LIST, MappedKind.DP, MappedKind.CONSTRAINT),
      inc.props.map { it.kind },
    )
    assertTrue(inc.props.first { it.name == "text" }.required)
    assertTrue(!inc.props.first { it.name == "count" }.required)
  }

  @Test fun requiredUnsupportedPropExcludesWidget() {
    val w = FakeWidget(fq("dev.keliver.material", "RichText"), listOf(
      FakeProperty("spans", fq("kotlin.collections", "List", params = listOf(fq("dev.keliver.material.api", "TextSpan")))),
    ))
    val ex = assertIs<WidgetPlan.Exclude>(plan(w))
    assertTrue(ex.reason.contains("spans"))
  }

  @Test fun optionalUnsupportedPropIsSkippedNotFatal() {
    val w = FakeWidget(fq("dev.keliver.material", "TextInput"), listOf(
      FakeProperty("state", fq("dev.keliver.material.api", "TextFieldState"), defaultExpression = "TextFieldState()"),
      FakeProperty("hint", fq("kotlin", "String"), defaultExpression = "\"\""),
    ))
    val inc = assertIs<WidgetPlan.Include>(plan(w))
    assertEquals(listOf("hint"), inc.props.map { it.name })
    assertEquals(listOf("state"), inc.skippedProps)
  }

  @Test fun childrenAndEvents() {
    val one = FakeWidget(fq("dev.keliver.material", "Card"), listOf(FakeChildren("children")))
    assertTrue(assertIs<WidgetPlan.Include>(plan(one)).hasChildren)

    val two = FakeWidget(fq("dev.keliver.material", "Scaffold"),
      listOf(FakeChildren("topBar"), FakeChildren("content")))
    assertIs<WidgetPlan.Exclude>(plan(two))

    val ev = FakeWidget(fq("dev.keliver.material", "Button"), listOf(
      FakeProperty("text", fq("kotlin", "String")),
      FakeEvent("onClick", isNullable = true),
    ))
    val inc = assertIs<WidgetPlan.Include>(plan(ev))
    assertEquals(listOf("onClick"), inc.events)

    val evReq = FakeWidget(fq("dev.keliver.material", "Weird"),
      listOf(FakeEvent("onThing", isNullable = false, defaultExpression = null)))
    assertIs<WidgetPlan.Exclude>(plan(evReq))
  }

  @Test fun modifierPlanning() {
    val padding = FakeModifier(fq("dev.keliver.material", "Padding"),
      properties = listOf(FakeModifierProperty("allDp", fq("kotlin", "Int"))))
    val plan = planModifier("dev.keliver.material.compose", padding)!!
    assertEquals("padding", plan.extensionName)
    assertEquals(listOf("allDp"), plan.props.map { it.name })

    val scoped = FakeModifier(fq("dev.keliver.layout", "Margin"),
      scopes = listOf(fq("dev.keliver.layout", "RowScope")),
      properties = emptyList())
    assertEquals(null, planModifier("dev.keliver.layout.compose", scoped))

    val flag = FakeModifier(fq("dev.keliver.material", "AnimateContentSize"))
    assertEquals("animateContentSize", planModifier("dev.keliver.material.compose", flag)!!.extensionName)

    val unsupported = FakeModifier(fq("dev.keliver.material", "Weird"),
      properties = listOf(FakeModifierProperty("thing", fq("dev.keliver.material.api", "TextSpan"))))
    assertEquals(null, planModifier("dev.keliver.material.compose", unsupported))
  }

  @Test fun defaultParsing() {
    assertEquals(14, defaultInt("14"))
    assertEquals(-1, defaultInt("-1"))
    assertEquals(0, defaultInt("Constraint.Wrap"))
    assertEquals(1, constraintDefault("Constraint.Fill"))
    assertEquals(0, constraintDefault("Constraint.Wrap"))
    assertEquals(3, crossAxisDefault("CrossAxisAlignment.Stretch"))
    assertEquals(MappedKind.OVERFLOW, mapType(fq("dev.keliver.layout.api", "Overflow")))
    assertEquals(1, overflowDefault("Overflow.Scroll"))
    assertEquals(true, defaultBool("true"))
    assertEquals(0.0, defaultDouble("0f"))
    assertEquals("", defaultString("\"\""))
    assertEquals("hi", defaultString("\"hi\""))
  }
}
