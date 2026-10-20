package dev.keliver.portal.render

import dev.keliver.Modifier
import dev.keliver.layout.api.Constraint
import dev.keliver.layout.api.CrossAxisAlignment
import dev.keliver.layout.compose.Box
import dev.keliver.layout.compose.Column
import dev.keliver.layout.compose.Row
import dev.keliver.material.compose.StyledBox
import dev.keliver.material.compose.StyledText
import dev.keliver.material.compose.padding
import dev.keliver.material.modifier.SelectionTag
import dev.keliver.material.testing.KeliverMaterialTester
import dev.keliver.material.testing.ButtonValue
import dev.keliver.material.testing.StyledBoxValue
import dev.keliver.material.testing.StyledTextValue
import dev.keliver.portal.Action
import dev.keliver.portal.Bind
import dev.keliver.portal.Cond
import dev.keliver.portal.Conditional
import dev.keliver.portal.Template
import dev.keliver.portal.Token
import dev.keliver.portal.WidgetNode
import dev.keliver.portal.placeholder
import dev.keliver.portal.render.fixture.ParityCompiledComponents
import dev.keliver.portal.render.fixture.ParityOpaqueBadge
import dev.keliver.portal.render.fixture.ParityOpaqueFrame
import dev.keliver.portal.render.fixture.ParityOpaqueGap
import dev.keliver.portal.render.fixture.ParityOpaqueMeter
import dev.keliver.portal.render.fixture.`in`.ParityOpaqueKeywords
import dev.keliver.portal.render.fixture.CompiledComponent as NamedCompiledComponent
import dev.keliver.testing.WidgetValue
import dev.keliver.testing.flatten
import dev.keliver.ui.Margin
import dev.keliver.ui.dp
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Compiled component preview — parity: an OPAQUE component instance rendered
 * through its generated adapter (ParityCompiledComponents) builds the widget
 * tree the compiled call builds on a device, for the same arguments.
 *
 * The hook below is the editor's opaque branch without its selection/handle
 * plumbing (portal-editor's CompiledComponentEditorTest covers the real hook).
 */
class CompiledComponentParityTest {
  @BeforeTest fun install() {
    componentPreviewInScope = { node, scope ->
      val adapter = ParityCompiledComponents[node.type]
      if (adapter == null) {
        RenderNode(placeholder(node.type, "no adapter"), scope)
      } else {
        RenderCompiledComponent(node, scope, adapter) { reason -> RenderNode(placeholder(node.type, reason), scope) }
      }
    }
  }

  @AfterTest fun reset() {
    PreviewBindings.mocks.clear()
    PreviewBindings.actionSink = { _, _ -> }
    componentPreviewInScope = null
    componentPreview = null
    compiledComponentProbe = null
  }

  private fun badge(vararg props: Pair<String, Any?>, children: List<WidgetNode> = emptyList()) =
    WidgetNode("ParityOpaqueBadge", mapOf(*props), children)

  @Test
  fun literalArgumentsBuildTheCompiledTree() = runTest {
    val compiled = KeliverMaterialTester {
      setContentAndSnapshot { Column { ParityOpaqueBadge(label = "Funds", count = 4, highlighted = true, accentArgb = -65_536) } }
    }
    val interpreted = KeliverMaterialTester {
      setContentAndSnapshot {
        RenderNode(WidgetNode("Column", children = listOf(badge("label" to "Funds", "count" to 4, "highlighted" to true, "accentArgb" to -65_536))))
      }
    }
    assertEquals(compiled, interpreted)
  }

  @Test
  fun omittedArgumentsTakeTheComponentsDefaults() = runTest {
    val compiled = KeliverMaterialTester { setContentAndSnapshot { ParityOpaqueBadge(label = "Funds", count = 0) } }
    val interpreted = KeliverMaterialTester { setContentAndSnapshot { RenderNode(badge("label" to "Funds", "count" to 0)) } }
    assertEquals(compiled, interpreted)
  }

  @Test
  fun tokensBindingsTemplatesAndConditionalsResolveAsTheCanvasResolvesThem() = runTest {
    PreviewBindings.mocks["name"] = "Ada"
    PreviewBindings.mocks["total"] = "7"
    PreviewBindings.mocks["vip"] = "true"
    val compiled = KeliverMaterialTester {
      setContentAndSnapshot { ParityOpaqueBadge(label = "Hi Ada", count = 7, highlighted = true, accentArgb = -16_777_216) }
    }
    val interpreted = KeliverMaterialTester {
      setContentAndSnapshot {
        RenderNode(
          badge(
            "label" to Template(listOf("Hi ", Bind("name"))),
            "count" to Bind("total"),
            "highlighted" to Conditional(Cond("vip"), true, false),
            "accentArgb" to Token("AppColors.Ink", -16_777_216),
          ),
        )
      }
    }
    assertEquals(compiled, interpreted)
  }

  @Test
  fun aContentSlotRendersTheInstanceChildren() = runTest {
    val compiled = KeliverMaterialTester {
      setContentAndSnapshot { ParityOpaqueFrame(title = "  Plan  ") { StyledText(text = "child") } }
    }
    val interpreted = KeliverMaterialTester {
      setContentAndSnapshot {
        RenderNode(
          WidgetNode(
            "ParityOpaqueFrame",
            mapOf("title" to "  Plan  "),
            listOf(WidgetNode("StyledText", mapOf("text" to "child"))),
          ),
        )
      }
    }
    assertEquals(compiled, interpreted)
  }

  @Test
  fun aComponentWithoutModifierParameterStillRendersExactly() = runTest {
    val compiled = KeliverMaterialTester { setContentAndSnapshot { Column { ParityOpaqueGap(units = 3) } } }
    val interpreted = KeliverMaterialTester {
      setContentAndSnapshot { RenderNode(WidgetNode("Column", children = listOf(WidgetNode("ParityOpaqueGap", mapOf("units" to 3))))) }
    }
    assertEquals(compiled, interpreted)
  }

  /** (d) scope: the instance's modifiers are built in its parent's scope, as the compiled call's are. */
  @Test
  fun scopedModifiersInARowAndABoxMatchTheCompiledCall() = runTest {
    val compiled = KeliverMaterialTester {
      setContentAndSnapshot {
        Column(width = Constraint.Fill) {
          Row(width = Constraint.Fill) {
            ParityOpaqueBadge(label = "a", count = 1, modifier = Modifier.flex(1.0).margin(Margin(start = 4.dp)).padding(2))
            ParityOpaqueBadge(label = "b", count = 2, modifier = Modifier.verticalAlignment(CrossAxisAlignment.End))
          }
          Box {
            ParityOpaqueBadge(label = "c", count = 3, modifier = Modifier.horizontalAlignment(CrossAxisAlignment.Center))
          }
          ParityOpaqueBadge(label = "d", count = 4, modifier = Modifier.horizontalAlignment(CrossAxisAlignment.Stretch))
        }
      }
    }
    val interpreted = KeliverMaterialTester {
      setContentAndSnapshot {
        RenderNode(
          WidgetNode(
            "Column", mapOf("width" to 1),
            listOf(
              WidgetNode(
                "Row", mapOf("width" to 1),
                listOf(
                  badge("label" to "a", "count" to 1, "mod.Flex.value" to 1.0, "mod.Margin.margin.start" to 4.0, "mod.Padding.allDp" to 2),
                  badge("label" to "b", "count" to 2, "mod.VerticalAlignment.alignment" to 2),
                ),
              ),
              WidgetNode("Box", emptyMap(), listOf(badge("label" to "c", "count" to 3, "mod.HorizontalAlignment.alignment" to 1))),
              badge("label" to "d", "count" to 4, "mod.HorizontalAlignment.alignment" to 3),
            ),
          ),
        )
      }
    }
    assertEquals(compiled, interpreted)
  }

  @Test
  fun aScopedModifierOutsideItsScopeIsDroppedAsOnADevice() = runTest {
    // StyledBox's content lambda has no receiver: a device cannot write flex there.
    val compiled = KeliverMaterialTester {
      setContentAndSnapshot { StyledBox { ParityOpaqueBadge(label = "x", count = 0) } }
    }
    val interpreted = KeliverMaterialTester {
      setContentAndSnapshot {
        RenderNode(WidgetNode("StyledBox", emptyMap(), listOf(badge("label" to "x", "count" to 0, "mod.Flex.value" to 1.0))))
      }
    }
    assertEquals(compiled, interpreted)
  }

  /** (c) the instance's selection tag lands on the compiled ROOT only: one selectable unit. */
  @Test
  fun theSelectionTagRidesOnTheCompiledRootOnly() = runTest {
    val values = KeliverMaterialTester {
      setContentAndSnapshot { RenderNode(badge("label" to "x", "count" to 0, "mod.SelectionTag.handle" to 42)) }
    }.flatten().toList()
    val tags = values.map { v -> v to tagsOf(v) }
    assertTrue(values.first() is StyledBoxValue, "root is the component's StyledBox: $values")
    assertEquals(listOf(42), tags.first().second)
    assertTrue(tags.drop(1).all { it.second.isEmpty() }, "internals carry no tag: $tags")
  }

  @Test
  fun anEventFiresTheInstancesActionLikeATransparentInstance() = runTest {
    val fired = mutableListOf<String>()
    PreviewBindings.actionSink = { name, arg -> fired += "$name:$arg" }
    val values = KeliverMaterialTester {
      setContentAndSnapshot { RenderNode(badge("label" to "x", "count" to 0, "onTap" to Action("open", "\"funds\""))) }
    }.flatten().toList()
    val box = values.filterIsInstance<StyledBoxValue>().single()
    box.onClick!!.invoke()
    assertEquals(listOf("open:funds"), fired)
  }

  /** (b, decode half) an argument the adapter cannot decode → placeholder with the reason. */
  @Test
  fun anUndecodableArgumentFallsBackToThePlaceholder() = runTest {
    val values = KeliverMaterialTester {
      setContentAndSnapshot {
        RenderNode(
          WidgetNode(
            "Column", emptyMap(),
            listOf(
              badge("label" to "x", "count" to "not a number"),
              WidgetNode("StyledText", mapOf("text" to "after")),
            ),
          ),
        )
      }
    }.flatten().toList()
    val texts = values.filterIsInstance<StyledTextValue>().map { it.text }
    assertTrue(texts.any { "'count' expects Int" in it }, "placeholder reason missing: $texts")
    assertTrue("after" in texts, "the rest of the canvas still renders: $texts")
  }

  @Test
  fun aProbedFailureFallsBackAndAPassingProbeRendersCompiled() = runTest {
    compiledComponentProbe = CompiledComponentProbe { IllegalStateException("boom") }
    val failed = KeliverMaterialTester {
      setContentAndSnapshot { RenderNode(badge("label" to "x", "count" to 0)) }
    }.flatten().toList().filterIsInstance<StyledTextValue>().map { it.text }
    assertTrue(failed.any { "compiled preview failed: IllegalStateException: boom" in it }, "$failed")

    compiledComponentProbe = CompiledComponentProbe { null }
    val compiled = KeliverMaterialTester { setContentAndSnapshot { ParityOpaqueBadge(label = "x", count = 0) } }
    val interpreted = KeliverMaterialTester { setContentAndSnapshot { RenderNode(badge("label" to "x", "count" to 0)) } }
    assertEquals(compiled, interpreted)
  }

  private fun tagsOf(v: WidgetValue): List<Int> {
    val out = mutableListOf<Int>()
    v.modifier.forEach { if (it is SelectionTag) out += it.handle }
    return out
  }

  // ── review m8: decoding paths a mutation could break unnoticed ──

  @Test
  fun anUnmockedTextBindingPreviewsItsFieldPlaceholder() = runTest {
    val texts = KeliverMaterialTester {
      setContentAndSnapshot { RenderNode(badge("label" to Bind("missing"), "count" to 0)) }
    }.flatten().toList().filterIsInstance<StyledTextValue>().map { it.text }
    assertTrue("{MISSING}" in texts, "$texts")
  }

  /** Round-2 minor 1: a MOCKED text binding decodes to its preview value. */
  @Test
  fun aMockedTextBindingDecodesToItsPreviewValue() = runTest {
    PreviewBindings.mocks["name"] = "Ada"
    val compiled = KeliverMaterialTester { setContentAndSnapshot { ParityOpaqueBadge(label = "Ada", count = 0) } }
    val interpreted = KeliverMaterialTester { setContentAndSnapshot { RenderNode(badge("label" to Bind("name"), "count" to 0)) } }
    assertEquals(compiled, interpreted)
    assertTrue("ADA" in interpreted.flatten().toList().filterIsInstance<StyledTextValue>().map { it.text })
  }

  @Test
  fun anIntArgumentForADoubleParameterIsWidened() = runTest {
    val compiled = KeliverMaterialTester { setContentAndSnapshot { ParityOpaqueMeter(ratio = 1.0) } }
    val interpreted = KeliverMaterialTester { setContentAndSnapshot { RenderNode(WidgetNode("ParityOpaqueMeter", mapOf("ratio" to 1))) } }
    assertEquals(compiled, interpreted)
  }

  @Test
  fun nullableScalarsSetAndOmittedMatchTheCompiledCall() = runTest {
    val compiledSet = KeliverMaterialTester {
      setContentAndSnapshot { ParityOpaqueMeter(ratio = 0.5, caption = "c", step = 3, shown = true) }
    }
    val interpretedSet = KeliverMaterialTester {
      setContentAndSnapshot {
        RenderNode(WidgetNode("ParityOpaqueMeter", mapOf("ratio" to 0.5, "caption" to "c", "step" to 3, "shown" to true)))
      }
    }
    assertEquals(compiledSet, interpretedSet)
    val compiledOmitted = KeliverMaterialTester { setContentAndSnapshot { ParityOpaqueMeter(ratio = 0.5) } }
    val interpretedOmitted = KeliverMaterialTester {
      setContentAndSnapshot { RenderNode(WidgetNode("ParityOpaqueMeter", mapOf("ratio" to 0.5, "caption" to null))) }
    }
    assertEquals(compiledOmitted, interpretedOmitted)
  }

  @Test
  fun oneArgumentEventsFireTheInstancesActionsWithTheirValue() = runTest {
    val fired = mutableListOf<String>()
    PreviewBindings.actionSink = { name, arg -> fired += "$name:$arg" }
    val buttons = KeliverMaterialTester {
      setContentAndSnapshot {
        RenderNode(
          WidgetNode(
            "ParityOpaqueMeter",
            mapOf("ratio" to 0.1, "step" to 4, "onPick" to Action("pick", "it"), "onStep" to Action("stepTo", "it")),
          ),
        )
      }
    }.flatten().toList().filterIsInstance<ButtonValue>()
    buttons.forEach { it.onClick!!.invoke() }
    assertEquals(listOf("pick:a", "stepTo:4"), fired)

    // A nullable event the instance does not wire stays null: no "pick" button, as on a device.
    val unwired = KeliverMaterialTester {
      setContentAndSnapshot { RenderNode(WidgetNode("ParityOpaqueMeter", mapOf("ratio" to 0.1))) }
    }.flatten().toList().filterIsInstance<ButtonValue>().map { it.text }
    assertEquals(listOf("step"), unwired)
  }

  // ── review M1: generator edge cases compile AND render like the compiled call ──

  @Test
  fun keywordParametersAndAQualifiedComposableSlotMatchTheCompiledCall() = runTest {
    val compiled = KeliverMaterialTester {
      setContentAndSnapshot { ParityOpaqueKeywords(`in` = "abc", `object` = 3) { StyledText(text = "slot") } }
    }
    val interpreted = KeliverMaterialTester {
      setContentAndSnapshot {
        RenderNode(
          WidgetNode("ParityOpaqueKeywords", mapOf("in" to "abc", "object" to 3), listOf(WidgetNode("StyledText", mapOf("text" to "slot")))),
        )
      }
    }
    assertEquals(compiled, interpreted)
  }

  @Test
  fun aComponentNamedCompiledComponentMatchesTheCompiledCall() = runTest {
    val compiled = KeliverMaterialTester { setContentAndSnapshot { NamedCompiledComponent(label = "abc") } }
    val interpreted = KeliverMaterialTester { setContentAndSnapshot { RenderNode(WidgetNode("CompiledComponent", mapOf("label" to "abc"))) } }
    assertEquals(compiled, interpreted)
  }
}
