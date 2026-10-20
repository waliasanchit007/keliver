import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import dev.keliver.Modifier
import dev.keliver.material.compose.StyledText
import dev.keliver.material.modifier.SelectionTag
import dev.keliver.portal.Action
import dev.keliver.material.testing.KeliverMaterialTester
import dev.keliver.material.testing.StyledBoxValue
import dev.keliver.material.testing.StyledTextValue
import dev.keliver.portal.Bind
import dev.keliver.portal.ComponentSpec
import dev.keliver.portal.EmptyComponentRegistry
import dev.keliver.portal.MapComponentRegistry
import dev.keliver.portal.PropKind
import dev.keliver.portal.PropSpec
import dev.keliver.portal.WidgetNode
import dev.keliver.portal.render.AppPreviewEntry
import dev.keliver.portal.render.CompiledComponent
import dev.keliver.portal.render.PreviewBindings
import dev.keliver.portal.render.RenderNode
import dev.keliver.portal.render.ScreenPreview
import dev.keliver.portal.render.compiledComponentProbe
import dev.keliver.portal.render.componentPreview
import dev.keliver.portal.render.componentPreviewInScope
import dev.keliver.portal.render.fixture.ParityCompiledComponents
import dev.keliver.portal.render.fixture.ParityOpaqueInitials
import dev.keliver.testing.WidgetValue
import dev.keliver.testing.flatten
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Compiled component preview through the EDITOR's own hook
 * (installComponentPreview, as runPortalEditor installs it): the failure probe,
 * selection tagging, nesting inside a transparent component, and precedence.
 */
class CompiledComponentEditorTest {
  @AfterTest fun reset() {
    PreviewBindings.mocks.clear()
    PreviewBindings.actionSink = { _, _ -> }
    componentPreviewInScope = null
    componentPreview = null
    compiledComponentProbe = null
    setEditorComponents(EmptyComponentRegistry)
  }

  private fun entry(compiled: Map<String, CompiledComponent> = ParityCompiledComponents) = object : AppPreviewEntry {
    override val screens: Map<String, ScreenPreview> = emptyMap()
    override val compiledComponents = compiled
  }

  private fun opaque(name: String, vararg props: String) =
    ComponentSpec(name, props.map { PropSpec(it, PropKind.Text, it) }, emptyList(), emptyMap(), transparent = false, diagnostic = "body contains code outside the portal grammar (opaque)")

  private fun texts(values: List<WidgetValue>) = values.filterIsInstance<StyledTextValue>().map { it.text }

  private fun tags(v: WidgetValue): List<Int> = buildList { v.modifier.forEach { if (it is SelectionTag) add(it.handle) } }

  @Test fun theProbeIsInstalledOnlyForAnEntryWithAdapters() {
    installComponentPreview(entry(emptyMap()))
    assertNull(compiledComponentProbe)
    installComponentPreview(entry())
    assertSame(DiscardingCompiledProbe, compiledComponentProbe)
  }

  /** (b) a compiled component that THROWS while composing → its placeholder; the canvas keeps rendering. */
  @Test fun aComponentThatThrowsWhileComposingFallsBackAndTheCanvasStillRenders() = runTest {
    setEditorComponents(MapComponentRegistry(listOf(opaque("ParityOpaqueInitials", "name"))))
    installComponentPreview(entry())
    val values = KeliverMaterialTester {
      setContentAndSnapshot {
        RenderNode(
          selectionTagged(
            WidgetNode(
              "Column", emptyMap(),
              listOf(
                WidgetNode("ParityOpaqueInitials", mapOf("name" to ""), id = 3),
                WidgetNode("ParityOpaqueInitials", mapOf("name" to "ada"), id = 4),
                WidgetNode("StyledText", mapOf("text" to "after"), id = 5),
              ),
              id = 1,
            ),
          ),
        )
      }
    }.flatten().toList()
    val t = texts(values)
    assertTrue(t.any { it == "▢ ParityOpaqueInitials" }, "placeholder title: $t")
    assertTrue(t.any { it.startsWith("compiled preview failed: NoSuchElementException") }, "placeholder reason: $t")
    assertTrue("A" in t, "the passing instance renders compiled: $t")
    assertTrue("after" in t, "the canvas still renders: $t")
    // The placeholder is the instance's: selecting it selects the instance.
    val placeholderBox = values.filterIsInstance<StyledBoxValue>().single()
    assertEquals(listOf(3), tags(placeholderBox))
  }

  @Test fun theDiscardingProbeReportsWhatTheCallThrowsAndNothingWhenItComposes() {
    assertTrue(DiscardingCompiledProbe.failureOf { ParityOpaqueInitials(name = "") } is NoSuchElementException)
    assertNull(DiscardingCompiledProbe.failureOf { ParityOpaqueInitials(name = "ada") })
  }

  /** (c) one selectable unit: the compiled root carries the instance handle; slot children keep their own. */
  @Test fun theInstanceIsOneSelectableUnitAndItsSlotChildrenStaySelectable() = runTest {
    setEditorComponents(MapComponentRegistry(listOf(opaque("ParityOpaqueFrame", "title"))))
    installComponentPreview(entry())
    val values = KeliverMaterialTester {
      setContentAndSnapshot {
        RenderNode(
          selectionTagged(
            WidgetNode("ParityOpaqueFrame", mapOf("title" to "Plan"), listOf(WidgetNode("StyledText", mapOf("text" to "child"), id = 8)), id = 7),
          ),
        )
      }
    }.flatten().toList()
    val byText = values.filterIsInstance<StyledTextValue>().associate { it.text to tags(it) }
    assertEquals(listOf(7), tags(values.first()), "compiled root: $values")
    assertEquals(emptyList(), byText.getValue("Plan"), "a compiled internal is not separately selectable")
    assertEquals(listOf(8), byText.getValue("child"), "slot content stays an ordinary document node")
  }

  /** An opaque component used INSIDE a transparent one draws compiled, tagged with the outer instance. */
  @Test fun anOpaqueComponentNestedInATransparentOneDrawsCompiled() = runTest {
    val outer = ComponentSpec(
      "Outer", listOf(PropSpec("title", PropKind.Text, "Title")), emptyList(), mapOf("title" to "String"),
      body = WidgetNode("Column", emptyMap(), listOf(WidgetNode("ParityOpaqueBadge", mapOf("label" to Bind("title"), "count" to 1), id = 101)), id = 100),
      dependencies = setOf("ParityOpaqueBadge"),
    )
    setEditorComponents(MapComponentRegistry(listOf(outer, opaque("ParityOpaqueBadge", "label"))))
    installComponentPreview(entry())
    val values = KeliverMaterialTester {
      setContentAndSnapshot { RenderNode(selectionTagged(WidgetNode("Outer", mapOf("title" to "Funds"), id = 5))) }
    }.flatten().toList()
    assertTrue("FUNDS" in texts(values), "the compiled body ran (uppercase): ${texts(values)}")
    val box = values.filterIsInstance<StyledBoxValue>().single()
    assertEquals(listOf(5), tags(box), "nested compiled root carries the OUTER instance handle")

    // Without an adapter the nested instance stays a placeholder, as before.
    installComponentPreview(entry(emptyMap()))
    val before = KeliverMaterialTester {
      setContentAndSnapshot { RenderNode(selectionTagged(WidgetNode("Outer", mapOf("title" to "Funds"), id = 5))) }
    }.flatten().toList()
    assertTrue(texts(before).any { it == "▢ ParityOpaqueBadge" }, "${texts(before)}")
  }

  /** Precedence: a TRANSPARENT component keeps its editable grammar view even when an adapter exists. */
  @Test fun aTransparentComponentIsExpandedNotCompiled() = runTest {
    val leaf = ComponentSpec(
      "ParityLeaf", listOf(PropSpec("label", PropKind.Text, "Label")), emptyList(), mapOf("label" to "String"),
      body = WidgetNode("StyledText", mapOf("text" to Bind("label"), "fontSize" to 99), id = 200),
    )
    setEditorComponents(MapComponentRegistry(listOf(leaf)))
    var called = 0
    val spy = ParityCompiledComponents + ("ParityLeaf" to CompiledComponent { call -> called++; ParityCompiledComponents.getValue("ParityLeaf").bind(call) })
    installComponentPreview(entry(spy))
    val values = KeliverMaterialTester {
      setContentAndSnapshot { RenderNode(selectionTagged(WidgetNode("ParityLeaf", mapOf("label" to "x"), id = 9))) }
    }.flatten().toList()
    assertEquals(0, called)
    // The registry's body (fontSize 99) is what drew — not the compiled function.
    assertEquals(99, values.filterIsInstance<StyledTextValue>().single().fontSize)
  }

  // ── review M2: the probe composes inside the canvas's composition locals ──

  @Test fun aComponentThatNeedsALocalTheCanvasProvidesIsNotReportedAsFailing() = runTest {
    // Before the fix the probe composed without the canvas's locals:
    // "compiled preview failed: IllegalStateException: no theme provided".
    setEditorComponents(MapComponentRegistry(listOf(opaque("Themed", "label"))))
    installComponentPreview(entry(mapOf("Themed" to themedAdapter)))
    val t = texts(
      KeliverMaterialTester {
        setContentAndSnapshot {
          CompositionLocalProvider(LocalTestTheme provides 7) {
            RenderNode(selectionTagged(WidgetNode("Themed", mapOf("label" to "x"), id = 2)))
          }
        }
      }.flatten().toList(),
    )
    assertEquals(listOf("x 7"), t)
  }

  @Test fun aComponentWhoseLocalIsMissingStillFallsBackAndTheCanvasRenders() = runTest {
    setEditorComponents(MapComponentRegistry(listOf(opaque("Themed", "label"))))
    installComponentPreview(entry(mapOf("Themed" to themedAdapter)))
    val t = texts(
      KeliverMaterialTester {
        setContentAndSnapshot {
          RenderNode(
            selectionTagged(
              WidgetNode("Column", emptyMap(), listOf(WidgetNode("Themed", mapOf("label" to "x"), id = 2), WidgetNode("StyledText", mapOf("text" to "after"), id = 3)), id = 1),
            ),
          )
        }
      }.flatten().toList(),
    )
    assertTrue(t.any { "no theme provided" in it }, "$t")
    assertTrue("after" in t, "$t")
  }

  // ── review M3: the probe never delivers the component's events ──

  @Test fun aSideEffectInTheProbeDoesNotReachTheActionConsole() = runTest {
    // Before the fix: [shown, shown] — once from the probe, once from the canvas.
    val fired = mutableListOf<String>()
    PreviewBindings.actionSink = { name, _ -> fired += name }
    setEditorComponents(MapComponentRegistry(listOf(opaque("ParityOpaqueAnnounce", "label"))))
    installComponentPreview(entry())
    KeliverMaterialTester {
      setContentAndSnapshot {
        RenderNode(selectionTagged(WidgetNode("ParityOpaqueAnnounce", mapOf("label" to "Hi", "onShown" to Action("shown")), id = 2)))
      }
    }
    assertEquals(listOf("shown"), fired)
  }

  // ── review nit: a component on a dependency cycle is never drawn compiled ──

  @Test fun aCycleFlaggedComponentKeepsItsPlaceholder() = runTest {
    var called = 0
    val spy = mapOf("Loop" to CompiledComponent { call -> called++; call.compose { StyledText(text = "loop") } })
    setEditorComponents(
      MapComponentRegistry(
        listOf(ComponentSpec("Loop", emptyList(), emptyList(), emptyMap(), transparent = false, diagnostic = "cyclic component dependency involving Loop")),
      ),
    )
    installComponentPreview(entry(spy))
    val t = texts(
      KeliverMaterialTester { setContentAndSnapshot { RenderNode(selectionTagged(WidgetNode("Loop", id = 2))) } }.flatten().toList(),
    )
    assertEquals(0, called)
    assertTrue("▢ Loop" in t, "$t")
  }
}

private val LocalTestTheme = staticCompositionLocalOf<Int> { error("no theme provided") }

@Composable
private fun Themed(label: String, modifier: Modifier = Modifier) {
  StyledText(modifier = modifier, text = "$label ${LocalTestTheme.current}")
}

private val themedAdapter = CompiledComponent { call ->
  val label = call.string("label")
  call.compose { Themed(label = label, modifier = call.modifier) }
}
