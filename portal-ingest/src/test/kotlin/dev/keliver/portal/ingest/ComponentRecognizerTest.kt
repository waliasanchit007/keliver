package dev.keliver.portal.ingest

import dev.keliver.portal.MapComponentRegistry
import dev.keliver.portal.PropKind
import dev.keliver.portal.collectContract
import dev.keliver.portal.document.DocNode
import dev.keliver.portal.document.UiDocument
import dev.keliver.portal.document.toWidgetTree
import dev.keliver.portal.exportKotlin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** C1: signature-derived component specs + component-aware screen recognition. */
class ComponentRecognizerTest {

  private val menuRow = """
    import androidx.compose.runtime.Composable
    import dev.keliver.material.compose.ListItem

    @Composable
    fun MenuRow(
      title: String,
      subtitle: String,
      icon: String = "Star",
      count: Int = 0,
      highlighted: Boolean = false,
      onClick: () -> Unit,
    ) {
      ListItem(
        headline = title,
        supporting = subtitle,
        leadingIcon = icon,
        trailingIcon = "KeyboardArrowRight",
        onClick = onClick,
      )
    }
  """.trimIndent()

  @Test fun signatureToSpecCoversAllScalarsEventsAndDefaults() {
    val rc = Recognizer.recognizeComponent("MenuRow.kt", menuRow)
    assertNotNull(rc)
    val s = rc.spec
    assertEquals("MenuRow", s.name)
    assertTrue(s.transparent, "body is pure grammar: ${s.diagnostic}")
    assertEquals(PropKind.Text, s.props.first { it.name == "title" }.kind)
    assertEquals(PropKind.Text, s.props.first { it.name == "icon" }.kind)
    assertEquals(PropKind.Int, s.props.first { it.name == "count" }.kind)
    assertEquals(PropKind.Bool, s.props.first { it.name == "highlighted" }.kind)
    assertEquals(listOf("onClick"), s.events.map { it.name })
    assertEquals(null, s.events.first().paramType) // () -> Unit
    // Defaults parsed; required params (title/subtitle/onClick) absent.
    assertEquals("Star", s.defaults["icon"])
    assertEquals(0, s.defaults["count"])
    assertEquals(false, s.defaults["highlighted"])
    assertTrue("title" !in s.defaults)
  }

  @Test fun bodyBareParamsBecomeBindsAndActions() {
    val rc = Recognizer.recognizeComponent("MenuRow.kt", menuRow)!!
    val body = rc.root!!
    // ListItem(headline = title -> Bind("title"), onClick = onClick -> Action("onClick"))
    val li = body // single root
    assertEquals(dev.keliver.portal.document.PropValue.Bind("title"), li.props["headline"])
    assertEquals(dev.keliver.portal.document.PropValue.Bind("subtitle"), li.props["supporting"])
    assertEquals(dev.keliver.portal.document.PropValue.Bind("icon"), li.props["leadingIcon"])
    assertEquals(dev.keliver.portal.document.PropValue.Action("onClick"), li.props["onClick"])
    // Literal stays literal.
    assertEquals(dev.keliver.portal.document.PropValue.Lit("s", s = "KeyboardArrowRight"), li.props["trailingIcon"])
  }

  @Test fun eventWithPayloadParam() {
    val src = """
      import androidx.compose.runtime.Composable
      import dev.keliver.material.compose.TextField

      @Composable
      fun LabeledField(value: String, onChange: (String) -> Unit) {
        TextField(text = value, onValueChange = { onChange(it) })
      }
    """.trimIndent()
    val rc = Recognizer.recognizeComponent("LabeledField.kt", src)!!
    val s = rc.spec
    assertEquals("String", s.events.first { it.name == "onChange" }.paramType)
    // rc.root is a DocNode tree (PropValue), s.body is the WidgetNode projection.
    val tf = rc.root!!
    assertEquals(dev.keliver.portal.document.PropValue.Action("onChange", arg = "it"), tf.props["onValueChange"])
    assertEquals(dev.keliver.portal.Action("onChange", "it"), s.body!!.props["onValueChange"])
  }

  @Test fun nestedComponentRecognizes() {
    val registry = MapComponentRegistry(listOf(Recognizer.recognizeComponent("MenuRow.kt", menuRow)!!.spec))
    val section = """
      import androidx.compose.runtime.Composable
      import dev.keliver.layout.compose.Column

      @Composable
      fun MenuSection(rowTitle: String, onRow: () -> Unit) {
        Column {
          MenuRow(title = rowTitle, subtitle = "sub", onClick = onRow)
        }
      }
    """.trimIndent()
    val rc = Recognizer.recognizeComponent("MenuSection.kt", section, registry)!!
    assertTrue(rc.spec.transparent, "nested component body is grammar: ${rc.spec.diagnostic}")
    assertTrue("MenuRow" in rc.spec.dependencies)
    val col = rc.root!!
    val nested = col.children.first() as DocNode.Widget
    assertEquals("MenuRow", nested.type)
    assertEquals(dev.keliver.portal.document.PropValue.Bind("rowTitle"), nested.props["title"])
    assertEquals(dev.keliver.portal.document.PropValue.Action("onRow"), nested.props["onClick"])
  }

  @Test fun unsupportedBodyMakesComponentOpaque() {
    val src = """
      import androidx.compose.runtime.Composable
      import androidx.compose.runtime.remember
      import dev.keliver.material.compose.StyledText

      @Composable
      fun Fancy(label: String) {
        val x = remember { computeSomething() }
        StyledText(text = label)
      }
    """.trimIndent()
    val s = Recognizer.recognizeComponent("Fancy.kt", src)!!.spec
    assertTrue(!s.transparent)
    assertEquals(null, s.body)
    assertNotNull(s.diagnostic)
    // Signature spec still present so it's insertable/prop-editable.
    assertEquals(PropKind.Text, s.props.first { it.name == "label" }.kind)
  }

  @Test fun slotParamIsRejectedAsOpaqueDiagnostic() {
    val src = """
      import androidx.compose.runtime.Composable
      import dev.keliver.layout.compose.Column

      @Composable
      fun Wrapper(title: String, content: @Composable () -> Unit) {
        Column { content() }
      }
    """.trimIndent()
    val s = Recognizer.recognizeComponent("Wrapper.kt", src)!!.spec
    assertTrue(!s.transparent)
    assertTrue(s.diagnostic!!.contains("slot"), s.diagnostic!!)
  }

  private val screenUsingMenuRow = """
    import androidx.compose.runtime.Composable
    import dev.keliver.layout.compose.Column

    @Composable
    fun MenuScreen(b: MenuScreenBindings) {
      Column {
        MenuRow(title = b.name, subtitle = "Account", onClick = { b.open("PROFILE") })
      }
    }

    interface MenuScreenBindings {
      val name: String
      fun open(value: String)
    }
  """.trimIndent()

  @Test fun screenUsingKnownComponentIngestsZeroRawCode() {
    val registry = MapComponentRegistry(listOf(Recognizer.recognizeComponent("MenuRow.kt", menuRow)!!.spec))
    val rc = Recognizer.recognize("MenuScreen.kt", screenUsingMenuRow, registry)!!
    val col = rc.root
    val menu = col.children.first() as DocNode.Widget
    assertEquals("MenuRow", menu.type)
    assertTrue(!containsRaw(rc.root), "no RawCode with the registry")
    assertEquals(dev.keliver.portal.document.PropValue.Bind("name"), menu.props["title"])
    assertEquals(dev.keliver.portal.document.PropValue.Action("open", arg = "\"PROFILE\""), menu.props["onClick"])
  }

  @Test fun sameScreenWithoutRegistryIsRawCode() {
    val rc = Recognizer.recognize("MenuScreen.kt", screenUsingMenuRow)!! // Empty registry
    assertTrue(containsRaw(rc.root), "MenuRow call should be RawCode without the registry")
  }

  @Test fun screenContractTypesComponentInstanceBinds() {
    val registry = MapComponentRegistry(listOf(Recognizer.recognizeComponent("MenuRow.kt", menuRow)!!.spec))
    val rc = Recognizer.recognize("MenuScreen.kt", screenUsingMenuRow, registry)!!
    val tree = UiDocument("m", rc.root, rc.contract, 0, 0).toWidgetTree()
    val contract = collectContract(tree, registry)
    assertEquals(PropKind.Text, contract.fields["name"]) // title: String -> Text
    assertTrue("open" in contract.actions)
  }

  @Test fun screenWithComponentRoundTripsThroughExport() {
    val registry = MapComponentRegistry(listOf(Recognizer.recognizeComponent("MenuRow.kt", menuRow)!!.spec))
    val rc = Recognizer.recognize("MenuScreen.kt", screenUsingMenuRow, registry)!!
    val tree = UiDocument("m", rc.root, rc.contract, 0, 0).toWidgetTree()
    val exported = exportKotlin(tree, functionName = "MenuScreen", components = registry)
    assertTrue("MenuRow(" in exported, exported)
    assertTrue("title = b.name," in exported, exported)
    assertTrue("""onClick = { b.open("PROFILE") },""" in exported, exported)
    // Re-recognize the exported screen → same component instance, still 0 RawCode.
    val rc2 = Recognizer.recognize("MenuScreen.kt", exported, registry)!!
    assertTrue(!containsRaw(rc2.root))
    val menu2 = rc2.root.children.first() as DocNode.Widget
    assertEquals("MenuRow", menu2.type)
    assertEquals(dev.keliver.portal.document.PropValue.Bind("name"), menu2.props["title"])
  }

  private fun containsRaw(n: DocNode): Boolean = when (n) {
    is DocNode.RawCode -> true
    is DocNode.Widget -> n.children.any { containsRaw(it) }
  }
}
