package dev.keliver.portal.ingest

import dev.keliver.portal.document.DocNode
import dev.keliver.portal.document.DocOp
import dev.keliver.portal.document.UiDocument
import dev.keliver.portal.document.lit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** S1 (ADOPTION_PLAN item 5): `@ScreenState` declarations next to a screen. */
class ScreenStateRecognizerTest {
  private val screen = """
    package app.screens

    import androidx.compose.runtime.Composable
    import dev.keliver.layout.compose.Column
    import dev.keliver.material.compose.StyledText
    import dev.keliver.nav.NamedState
    import dev.keliver.nav.ScreenState

    @Composable
    fun ItemDetailScreen(b: ItemDetailScreenBindings) {
      Column {
        StyledText(text = "Item")
        StyledText(text = b.title)
      }
    }

    interface ItemDetailScreenBindings {
      val title: String
    }

    @ScreenState("review")
    fun review() = NamedState(route = ItemDetail(id = "sample"), fixtures = "review")

    @ScreenState(name = "empty")
    fun empty(): NamedState<Route> {
      return NamedState(Route.ItemDetail("none"), "empty-item")
    }

    @ScreenState("home")
    fun home() = NamedState(route = Route.Home, fixtures = "home")
  """.trimIndent() + "\n"

  private fun all(n: DocNode): List<DocNode> =
    listOf(n) + ((n as? DocNode.Widget)?.children.orEmpty().flatMap(::all))

  @Test fun statesAreReadWithTheirRoutesAndFixtureSets() {
    val r = ScreenStateRecognizer.recognize("ItemDetailScreen.kt", screen)
    assertEquals(emptyList(), r.problems)
    assertEquals(
      listOf(
        ScreenStateSpec("review", "review", "ItemDetail(id = \"sample\")", "ItemDetail", "review"),
        ScreenStateSpec("empty", "empty", "Route.ItemDetail(\"none\")", "Route.ItemDetail", "empty-item"),
        ScreenStateSpec("home", "home", "Route.Home", "Route.Home", "home"),
      ),
      r.states,
    )
  }

  @Test fun aScreenThatDeclaresStatesIngestsWithNoRawCodeAndRoundTrips() {
    val rec = assertNotNull(Recognizer.recognize("app/screens/ItemDetailScreen.kt", screen))
    assertEquals("ItemDetailScreen", rec.screenName)
    assertEquals(emptyList(), rec.rawReasons)
    assertTrue(all(rec.root).none { it is DocNode.RawCode })
    val doc = UiDocument("item", rec.root, rec.contract, 0, 1000)
    // Unchanged document writes back byte-identical ...
    assertEquals(screen, WriteBack.merge(screen, doc, fileName = "app/screens/ItemDetailScreen.kt"))
    // ... and a surgical edit leaves the state declarations untouched.
    val first = (doc.root as DocNode.Widget).children[0]
    val edited = assertNotNull(
      WriteBack.merge(
        screen,
        doc.apply(DocOp.SetProp(first.handle, "text", lit("Item details"))).doc,
        fileName = "app/screens/ItemDetailScreen.kt",
      ),
    )
    assertEquals(screen.replace("\"Item\"", "\"Item details\""), edited)
    assertEquals(
      ScreenStateRecognizer.recognize("a.kt", screen),
      ScreenStateRecognizer.recognize("a.kt", edited),
    )
  }

  @Test fun malformedAndDuplicateDeclarationsAreReportedNotListed() {
    val src = """
      @ScreenState("a") fun a() = NamedState(route = Home, fixtures = "a")
      @ScreenState("a") fun again() = NamedState(route = Home, fixtures = "b")
      @ScreenState(NAME) fun named() = NamedState(route = Home, fixtures = "c")
      @ScreenState("d") fun d() = NamedState(route = Home, fixtures = "${'$'}x")
      @ScreenState("e") fun e() = makeState()
      @ScreenState("f") fun f(id: String) = NamedState(route = Item(id), fixtures = "f")
      @ScreenState("g") fun g() = NamedState(route = Home, fixtures = "")
      fun notAState() = NamedState(route = Home, fixtures = "z")
    """.trimIndent()
    val r = ScreenStateRecognizer.recognize("S.kt", src)
    assertEquals(listOf(ScreenStateSpec("a", "a", "Home", "Home", "a")), r.states)
    assertEquals(
      listOf("again", "named", "d", "e", "f", "g"),
      r.problems.map { it.substringBefore(':') },
    )
    assertTrue(r.problems[0].contains("already declared by a()"), r.problems[0])
  }

  @Test fun aFileWithoutStatesHasNone() {
    assertEquals(ScreenStates(emptyList()), ScreenStateRecognizer.recognize("S.kt", "fun x() = 1\n"))
  }
}
