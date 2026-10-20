package dev.keliver.portal.ingest

import dev.keliver.portal.Bind
import dev.keliver.portal.COMPONENT_FORWARDS_MODIFIER_PROP
import dev.keliver.portal.Cond
import dev.keliver.portal.CondJoin
import dev.keliver.portal.CondTest
import dev.keliver.portal.Conditional
import dev.keliver.portal.Expansion
import dev.keliver.portal.MapComponentRegistry
import dev.keliver.portal.Token
import dev.keliver.portal.WidgetNode
import dev.keliver.portal.condOf
import dev.keliver.portal.condOfTerms
import dev.keliver.portal.expandForPreview
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * K6 in a component body: equality conditions on parameters, compound
 * conditions, and DERIVED BOOLEAN LOCALS — `val active = enabled && !loading`
 * read as `active` / `!active` in the body's conditions — keep a component
 * transparent. The local is inlined into each condition that reads it (it is
 * a pure read of parameters, so that is what the device computes), so
 * preview expansion evaluates it with each instance's arguments. Anything
 * else keeps the component opaque and the diagnostic says exactly why.
 */
class DerivedLocalTest {
  private val tokens = TokenTable.scan(
    listOf("Tokens.kt" to "package app.components\n\nobject C {\n  const val Ink = -14737633\n  const val Muted = -6250336\n  const val White = -1\n  const val Accent = -1003069\n  const val Grey = -7829368\n}\n"),
  )

  private fun component(body: String, signature: String = "text: String, enabled: Boolean, loading: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit") = """
    package app.components

    import androidx.compose.runtime.Composable
    import dev.keliver.Modifier
    import dev.keliver.layout.compose.Column
    import dev.keliver.material.compose.StyledBox
    import dev.keliver.material.compose.StyledText

    @Composable
    fun ActionButton($signature) {
    ${body.trimIndent().prependIndent("  ")}
    }
  """.trimIndent()

  private val button = component(
    """
    val active = enabled && !loading
    StyledBox(
      colorArgb = if (active) C.Ink else C.Muted,
      cornerRadiusDp = 12,
      onClick = if (active) onClick else null,
      modifier = modifier,
    ) {
      StyledText(text = if (loading) "Loading…" else text, colorArgb = if (!active) C.White else C.Ink)
      if (!active) {
        StyledText(text = "unavailable")
      }
    }
    """,
  )

  private fun spec(src: String) = Recognizer.recognizeComponent("app/components/ActionButton.kt", src, tokens = tokens)!!.spec

  private val ink = Token("C.Ink", -14737633, "app.components.C")
  private val muted = Token("C.Muted", -6250336, "app.components.C")
  private val white = Token("C.White", -1, "app.components.C")

  @Test fun aDerivedLocalKeepsTheComponentTransparentAndIsInlined() {
    val s = spec(button)
    assertTrue(s.transparent, s.diagnostic)
    val root = s.body!!
    // The local is not a node: the body's single root is the StyledBox, which forwards `modifier`.
    assertEquals("StyledBox", root.type)
    assertEquals(true, root.props[COMPONENT_FORWARDS_MODIFIER_PROP])
    val active = condOfTerms(listOf(Cond("enabled"), Cond("loading", negate = true)))
    assertEquals(Conditional(active, ink, muted), root.props["colorArgb"])
    assertEquals(Conditional(active, dev.keliver.portal.Action("onClick"), null), root.props["onClick"])
    val text = root.children[0]
    assertEquals(Conditional(Cond("loading"), "Loading…", Bind("text")), text.props["text"])
    // `!active` is `!enabled || loading` (De Morgan) — still one flat condition.
    val notActive = condOfTerms(listOf(Cond("enabled", negate = true), Cond("loading")), CondJoin.Or)
    assertEquals(Conditional(notActive, white, ink), text.props["colorArgb"])
    assertEquals(notActive, condOf(root.children[1]))
  }

  @Test fun previewExpansionEvaluatesTheLocalWithTheInstancesArguments() {
    val registry = MapComponentRegistry(listOf(spec(button)))
    fun expand(enabled: Any?, loading: Any?) =
      (expandForPreview(WidgetNode("ActionButton", mapOf("text" to "Pay", "enabled" to enabled, "loading" to loading)), registry) as Expansion.Transparent).tree
    val ready = expand(true, false)
    assertEquals(ink, ready.props["colorArgb"])
    assertEquals(dev.keliver.portal.Action("onClick"), ready.props["onClick"])
    assertEquals(listOf("Pay"), ready.children.map { it.props["text"] })
    val busy = expand(true, true)
    assertEquals(muted, busy.props["colorArgb"])
    assertNull(busy.props["onClick"])
    assertEquals(listOf("Loading…", "unavailable"), busy.children.map { it.props["text"] })
    val off = expand(false, false)
    assertEquals(muted, off.props["colorArgb"])
    assertEquals(listOf("Pay", "unavailable"), off.children.map { it.props["text"] })
    // A screen binding argument: the term is re-pointed at it, decided by the mocks at render time.
    val bound = expand(Bind("canPay"), false)
    assertEquals(Conditional(Cond("canPay"), ink, muted), bound.props["colorArgb"])
    assertEquals(Cond("canPay", negate = true), condOf(bound.children.last()))
  }

  @Test fun anEqualityOnAParameterIsGrammar() {
    val s = spec(
      component(
        """
        Column {
          StyledBox(colorArgb = if (code == "XY") C.Accent else C.Grey) {
            StyledText(text = code, colorArgb = if (code != "XY" && size == 40) C.White else C.Ink)
          }
          if (size == -1 || round == false) {
            StyledText(text = "fallback")
          }
        }
        """,
        signature = "code: String, size: Int = 40, round: Boolean = true",
      ),
    )
    assertTrue(s.transparent, s.diagnostic)
    val box = s.body!!.children[0]
    assertEquals(Cond("code", CondTest.Eq, operand = "XY"), (box.props["colorArgb"] as Conditional).cond)
    val registry = MapComponentRegistry(listOf(s))
    fun expand(vararg args: Pair<String, Any?>) = (expandForPreview(WidgetNode("ActionButton", mapOf(*args)), registry) as Expansion.Transparent).tree
    assertEquals(Token("C.Accent", -1003069, "app.components.C"), expand("code" to "XY").children[0].props["colorArgb"])
    assertEquals(Token("C.Grey", -7829368, "app.components.C"), expand("code" to "BD").children[0].props["colorArgb"])
    assertEquals(white, expand("code" to "BD").children[0].children[0].props["colorArgb"])
    assertEquals(ink, expand("code" to "BD", "size" to 24).children[0].children[0].props["colorArgb"])
    assertEquals(1, expand("code" to "XY").children.size) // size 40 (default), round true
    assertEquals(2, expand("code" to "XY", "round" to false).children.size)
  }

  private fun diagnosticOf(body: String, signature: String = "text: String, enabled: Boolean, loading: Boolean, note: String? = null, ratio: Double = 1.0, onClick: () -> Unit"): String {
    val s = spec(component(body, signature))
    assertFalse(s.transparent, body)
    return s.diagnostic!!
  }

  private fun assertOpaque(body: String, why: String, signature: String? = null) {
    val d = if (signature == null) diagnosticOf(body) else diagnosticOf(body, signature)
    assertTrue(why in d, "expected `$why` in: $d")
  }

  @Test fun everyOtherLocalKeepsTheComponentOpaqueAndSaysWhy() {
    val use = "\nStyledText(text = if (active) \"a\" else \"b\")"
    assertOpaque("var active = enabled && !loading$use", "`var active`")
    assertOpaque("val active by lazy { enabled }$use", "delegated")
    assertOpaque("val active: Int = 1$use", "not a Boolean")
    assertOpaque("val enabled = !loading\nStyledText(text = if (enabled) \"a\" else \"b\")", "shadows the parameter `enabled`")
    assertOpaque("StyledText(text = if (active) \"a\" else \"b\")\nval active = enabled && !loading", "before its definition")
    assertOpaque("val ready = enabled && !loading\nval active = ready && text.isNotEmpty()$use", "reads the local `ready`")
    assertOpaque("val active = text.length > 3$use", "`val active = text.length > 3`")
    assertOpaque("val active = enabled && !loading\nStyledBox(fillWidth = active) {\n  StyledText(text = text)\n}", "a derived local is read only in a condition")
    assertOpaque("val active = enabled && !loading\nStyledText(text = if (active || text.isEmpty()) \"a\" else \"b\")", "mixes `&&` and `||`")
    assertOpaque("val (a, c) = Pair(enabled, loading)$use", "destructuring")
    // Equality forms outside the grammar.
    assertOpaque("StyledText(text = if (note == \"x\") \"a\" else \"b\")", "nullable")
    assertOpaque("StyledText(text = if (ratio == 1.0) \"a\" else \"b\")", "Double")
    assertOpaque("StyledText(text = if (text == note) \"a\" else \"b\")", "two fields")
    assertOpaque("StyledText(text = if (onClick == null) \"a\" else \"b\")", "outside the portal grammar")
  }

  @Test fun aLocalWhoseJoinFitsTheConditionIsFlattened() {
    val s = spec(
      component(
        """
        val active = enabled && !loading
        val idle = !enabled || loading
        Column {
          StyledText(text = if (active && text.isNotEmpty()) "a" else "b")
          StyledText(text = if (!active || text.isEmpty()) "a" else "b")
          StyledText(text = if (idle) "a" else "b")
        }
        """,
      ),
    )
    assertTrue(s.transparent, s.diagnostic)
    val texts = s.body!!.children.map { (it.props["text"] as Conditional).cond }
    assertEquals(condOfTerms(listOf(Cond("enabled"), Cond("loading", negate = true), Cond("text", CondTest.Empty, negate = true))), texts[0])
    assertEquals(condOfTerms(listOf(Cond("enabled", negate = true), Cond("loading"), Cond("text", CondTest.Empty)), CondJoin.Or), texts[1])
    assertEquals(condOfTerms(listOf(Cond("enabled", negate = true), Cond("loading")), CondJoin.Or), texts[2])
  }
}
