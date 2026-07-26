package dev.keliver.portal.ingest

import dev.keliver.portal.Action
import dev.keliver.portal.Bind
import dev.keliver.portal.ComponentSpec
import dev.keliver.portal.COMPONENT_SOURCE_HANDLE_PROP
import dev.keliver.portal.Expansion
import dev.keliver.portal.MapComponentRegistry
import dev.keliver.portal.WidgetNode
import dev.keliver.portal.expandForPreview
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** C3: pure transparent-expansion layer (scalars, binds, events, defaults, nesting, cycles). */
class ComponentExpansionTest {
  private val menuRow = Recognizer.recognizeComponent(
    "MenuRow.kt",
    """
      import androidx.compose.runtime.Composable
      import dev.keliver.material.compose.ListItem
      @Composable
      fun MenuRow(title: String, subtitle: String, icon: String = "Star", onClick: () -> Unit) {
        ListItem(headline = title, supporting = subtitle, leadingIcon = icon, trailingIcon = "KeyboardArrowRight", onClick = onClick)
      }
    """.trimIndent(),
  )!!.spec
  private val registry = MapComponentRegistry(listOf(menuRow))

  private fun tree(e: Expansion) = (e as Expansion.Transparent).tree

  @Test fun literalAndScreenBindAndEventSubstitution() {
    // Instance: MenuRow(title = b.name, subtitle = "Account", onClick = { b.open("X") })  (icon omitted)
    val inst = WidgetNode("MenuRow", mapOf(
      "title" to Bind("name"),
      "subtitle" to "Account",
      "onClick" to Action("open", "\"X\""),
    ))
    val li = tree(expandForPreview(inst, registry))
    assertEquals("ListItem", li.type)
    assertEquals(Bind("name"), li.props["headline"])          // param bind -> screen bind
    assertEquals("Account", li.props["supporting"])            // param bind -> instance literal
    assertEquals("Star", li.props["leadingIcon"])              // omitted -> signature default
    assertEquals("KeyboardArrowRight", li.props["trailingIcon"]) // body literal untouched
    assertEquals(Action("open", "\"X\""), li.props["onClick"])  // event param -> instance action
  }

  @Test fun omittedEventBecomesNoOp() {
    val inst = WidgetNode("MenuRow", mapOf("title" to "T", "subtitle" to "S")) // no onClick
    val li = tree(expandForPreview(inst, registry))
    assertEquals(Action("onClick"), li.props["onClick"]) // safe no-op action
  }

  @Test fun nestedComponentExpandsFully() {
    val section = Recognizer.recognizeComponent(
      "Section.kt",
      """
        import androidx.compose.runtime.Composable
        import dev.keliver.layout.compose.Column
        @Composable
        fun Section(rowTitle: String, onRow: () -> Unit) {
          Column {
            MenuRow(title = rowTitle, subtitle = "sub", onClick = onRow)
          }
        }
      """.trimIndent(),
      registry,
    )!!.spec
    val reg2 = MapComponentRegistry(listOf(menuRow, section))
    val inst = WidgetNode("Section", mapOf("rowTitle" to "Hello", "onRow" to Action("go")))
    val col = tree(expandForPreview(inst, reg2))
    assertEquals("Column", col.type)
    val li = col.children.first()
    assertEquals("ListItem", li.type) // nested component fully expanded to primitive
    assertEquals("Hello", li.props["headline"]) // outer param -> nested param -> primitive
    assertEquals(Action("go"), li.props["onClick"])
    assertTrue(col.children.none { registry.isComponent(it.type) }, "no residual component nodes")
  }

  @Test fun contentSlotSplicesAndExpandsInstanceChildren() {
    val card = Recognizer.recognizeComponent(
      "SectionCard.kt",
      """
        import androidx.compose.runtime.Composable
        import dev.keliver.material.compose.StyledBox
        @Composable
        fun SectionCard(label: String, content: @Composable () -> Unit) {
          StyledBox(fillWidth = true, cornerRadiusDp = 16) { content() }
        }
      """.trimIndent(),
    )!!.spec
    val reg = MapComponentRegistry(listOf(menuRow, card))
    val first = WidgetNode("MenuRow", mapOf("title" to "One", "subtitle" to "First"))
    val second = WidgetNode("MenuRow", mapOf("title" to Bind("screenTitle"), "subtitle" to "Second"))
    val expanded = tree(expandForPreview(
      WidgetNode("SectionCard", mapOf("label" to "ACCOUNT"), listOf(first, second)),
      reg,
    ))
    assertEquals("StyledBox", expanded.type)
    assertEquals(2, expanded.children.size)
    assertEquals(listOf("ListItem", "ListItem"), expanded.children.map { it.type })
    assertEquals("One", expanded.children[0].props["headline"])
    assertEquals(Bind("screenTitle"), expanded.children[1].props["headline"])
    assertEquals(first.id, expanded.children[0].props[COMPONENT_SOURCE_HANDLE_PROP])
    assertEquals(second.id, expanded.children[1].props[COMPONENT_SOURCE_HANDLE_PROP])
  }

  @Test fun slotContentDoesNotResolveNamesAgainstWrapperParams() {
    val wrapper = Recognizer.recognizeComponent(
      "Wrapper.kt",
      """
        import androidx.compose.runtime.Composable
        import dev.keliver.layout.compose.Column
        @Composable fun Wrapper(title: String, content: @Composable () -> Unit) {
          Column { content() }
        }
      """.trimIndent(),
    )!!.spec
    val reg = MapComponentRegistry(listOf(wrapper))
    val child = WidgetNode("StyledText", mapOf("text" to Bind("title")))
    val expanded = tree(expandForPreview(
      WidgetNode("Wrapper", mapOf("title" to "wrapper literal"), listOf(child)),
      reg,
    ))
    assertEquals(Bind("title"), expanded.children.single().props["text"])
    assertEquals(child.id, expanded.children.single().props[COMPONENT_SOURCE_HANDLE_PROP])
  }

  @Test fun slotChildMayReuseItsWrapperWithoutAFalseCycle() {
    val wrapper = ComponentSpec(
      "Wrapper",
      emptyList(),
      emptyList(),
      mapOf("content" to "@Composable () -> Unit"),
      slots = listOf(dev.keliver.portal.ComponentSlotSpec("content")),
      body = WidgetNode("Column", children = listOf(
        WidgetNode("Slot", mapOf("name" to "content")),
      )),
      transparent = true,
    )
    val child = ComponentSpec(
      "Child",
      emptyList(),
      emptyList(),
      emptyMap(),
      body = WidgetNode("Wrapper", children = listOf(
        WidgetNode("StyledText", mapOf("text" to "finite")),
      )),
      transparent = true,
      dependencies = setOf("Wrapper"),
    )
    val reg = MapComponentRegistry(listOf(wrapper, child))

    val expanded = tree(expandForPreview(
      WidgetNode("Wrapper", children = listOf(WidgetNode("Child"))),
      reg,
    ))

    val nestedWrapper = expanded.children.single()
    assertEquals("Column", nestedWrapper.type)
    assertEquals("finite", nestedWrapper.children.single().props["text"])
  }

  @Test fun opaqueComponentYieldsPlaceholder() {
    val opaque = ComponentSpec("Fancy", emptyList(), emptyList(), emptyMap(), transparent = false, diagnostic = "has effects")
    val reg = MapComponentRegistry(listOf(opaque))
    val e = expandForPreview(WidgetNode("Fancy", emptyMap()), reg)
    assertTrue(e is Expansion.Opaque)
    assertEquals("Fancy", (e as Expansion.Opaque).name)
  }

  @Test fun unknownTypeIsOpaque() {
    val e = expandForPreview(WidgetNode("Nope", emptyMap()), registry)
    assertTrue(e is Expansion.Opaque)
  }

  @Test fun directCycleIsGuarded() {
    // A uses A (self-reference).
    val a = ComponentSpec(
      "A", emptyList(), emptyList(), emptyMap(),
      body = WidgetNode("A", emptyMap()), transparent = true, dependencies = setOf("A"),
    )
    val reg = MapComponentRegistry(listOf(a))
    // top-level expand of A: body contains A -> nested expand sees A in stack -> Cycle chip node.
    val t = tree(expandForPreview(WidgetNode("A", emptyMap()), reg))
    // The nested A becomes a cycle chip (StyledBox), not infinite recursion.
    assertEquals("StyledBox", t.type)
    assertTrue(t.children.any { (it.props["text"] as? String)?.contains("cycle") == true })
  }

  @Test fun indirectCycleIsGuarded() {
    val a = ComponentSpec("A", emptyList(), emptyList(), emptyMap(),
      body = WidgetNode("B", emptyMap()), transparent = true, dependencies = setOf("B"))
    val b = ComponentSpec("B", emptyList(), emptyList(), emptyMap(),
      body = WidgetNode("A", emptyMap()), transparent = true, dependencies = setOf("A"))
    val reg = MapComponentRegistry(listOf(a, b))
    // Expand A -> body B -> body A(in stack [A,B]) -> cycle chip. No hang.
    val t = tree(expandForPreview(WidgetNode("A", emptyMap()), reg))
    assertEquals("StyledBox", t.type)
    assertTrue(t.children.any { (it.props["text"] as? String)?.contains("cycle") == true })
  }
}
