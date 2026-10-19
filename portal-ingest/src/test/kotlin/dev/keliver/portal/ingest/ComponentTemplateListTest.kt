package dev.keliver.portal.ingest

import dev.keliver.portal.Bind
import dev.keliver.portal.Expansion
import dev.keliver.portal.ListOf
import dev.keliver.portal.MapComponentRegistry
import dev.keliver.portal.Template
import dev.keliver.portal.Token
import dev.keliver.portal.document.DocNode
import dev.keliver.portal.document.DocOp
import dev.keliver.portal.document.PropValue
import dev.keliver.portal.document.UiDocument
import dev.keliver.portal.document.lit
import dev.keliver.portal.document.toWidgetTree
import dev.keliver.portal.expandForPreview
import dev.keliver.portal.intList
import dev.keliver.portal.str
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * K4: a component whose body uses templates over its parameters, or lists of
 * its parameters and tokens, stays TRANSPARENT; an instance's template
 * argument is grammar; the preview substitutes the instance's values.
 */
class ComponentTemplateListTest {
  private val tokens = TokenTable.scan(
    listOf("Tokens.kt" to "package app.components\n\nobject C {\n  const val End = -5674\n  const val Unit = \"pts\"\n}\n"),
  )

  // § is `$`.
  private val scoreKt = """
    package app.components

    import androidx.compose.runtime.Composable
    import dev.keliver.layout.compose.Column
    import dev.keliver.material.compose.StyledBox
    import dev.keliver.material.compose.StyledText

    @Composable
    fun Score(label: String, count: Int, start: Int = -1, note: String = "") {
      Column {
        StyledText(text = "§label: §count §{C.Unit}")
        StyledBox(gradientColorsArgb = listOf(start, C.End)) {
          StyledText(text = "§{note}!")
        }
      }
    }
  """.trimIndent().replace('§', '$')

  private val score = Recognizer.recognizeComponent("app/components/Score.kt", scoreKt, tokens = tokens)!!.spec
  private val registry = MapComponentRegistry(listOf(score))

  @Test fun theComponentIsTransparent() {
    assertTrue(score.transparent, score.diagnostic)
    val text = score.body!!.children[0].props["text"]
    assertEquals(Template(listOf(Bind("label"), ": ", Bind("count"), " ", Token("C.Unit", "pts", "app.components.C"))), text)
    assertEquals(ListOf("li", listOf(Bind("start"), Token("C.End", -5674, "app.components.C"))), score.body!!.children[1].props["gradientColorsArgb"])
  }

  @Test fun aDoubleOrNullableParameterInATemplateKeepsTheComponentOpaque() {
    // The device (Kotlin/JS) prints 1.0 as "1" and a null as "null"; the
    // preview prints "1.0" and nothing — no faithful preview, so not grammar.
    for ((param, use) in listOf(
      "ratio: Double" to "§ratio", "nick: String?" to "§{nick}", "n: Int?" to "x §n", "ok: Boolean?" to "§ok",
    )) {
      val kt = """
        package app.components

        import androidx.compose.runtime.Composable
        import dev.keliver.material.compose.StyledText

        @Composable
        fun Odd(label: String, $param) {
          StyledText(text = "$use")
        }
      """.trimIndent().replace('§', '$')
      val spec = Recognizer.recognizeComponent("app/components/Odd.kt", kt, tokens = tokens)!!.spec
      assertTrue(!spec.transparent, "$param: ${spec.body}")
    }
    // A non-null String / Int / Boolean parameter is grammar.
    val fine = """
      package app.components

      import androidx.compose.runtime.Composable
      import dev.keliver.material.compose.StyledText

      @Composable
      fun Fine(label: String, n: Int, on: Boolean) {
        StyledText(text = "§label §n §on")
      }
    """.trimIndent().replace('§', '$')
    assertTrue(Recognizer.recognizeComponent("app/components/Fine.kt", fine, tokens = tokens)!!.spec.transparent)
  }

  private val screenKt = """
    package app.screens

    import androidx.compose.runtime.Composable
    import app.components.Score
    import dev.keliver.layout.compose.Column

    @Composable
    fun Home(b: HomeBindings) {
      Column {
        Score(label = "Hi §{b.name}", count = 3, note = "§{b.city}")
      }
    }

    interface HomeBindings {
      val name: String
      val city: String
    }
  """.trimIndent().replace('§', '$')

  private fun doc(text: String = screenKt): UiDocument =
    Recognizer.recognize("app/screens/Home.kt", text, registry, tokens)!!.let { UiDocument("home", it.root, it.contract, 0, 100) }

  @Test fun anInstanceTakesATemplateArgumentAndPreviewsIt() {
    val d = doc()
    val inst = (d.root as DocNode.Widget).children.single() as DocNode.Widget
    assertEquals(PropValue.Template(listOf(lit("Hi "), PropValue.Bind("name"))), inst.props["label"])
    val tree = d.toWidgetTree(mocks = mapOf("name" to "Ada"))
    val expanded = assertIs<Expansion.Transparent>(expandForPreview(tree.children.single(), registry)).tree
    assertEquals("Hi Ada: 3 pts", expanded.children[0].str("text"))
    assertEquals(listOf(-1, -5674), expanded.children[1].intList("gradientColorsArgb"))
    assertEquals("{city}!", expanded.children[1].children[0].str("text"))
  }

  @Test fun anInstanceTemplateEditIsSurgical() {
    val d = doc()
    assertEquals(screenKt, WriteBack.merge(screenKt, d, registry, tokens, "app/screens/Home.kt", base = d))
    val inst = (d.root as DocNode.Widget).children.single() as DocNode.Widget
    val out = assertNotNull(
      WriteBack.merge(
        screenKt, d.apply(DocOp.SetProp(inst.handle, "label", PropValue.Template(listOf(lit("Hello "), PropValue.Bind("name"))))).doc,
        registry, tokens, "app/screens/Home.kt", base = d,
      ),
    )
    assertEquals(screenKt.replace("\"Hi \${b.name}\"", "\"Hello \${b.name}\""), out)
  }
}
