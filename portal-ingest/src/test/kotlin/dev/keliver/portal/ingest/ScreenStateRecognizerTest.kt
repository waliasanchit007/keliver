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
      import dev.keliver.nav.NamedState
      import dev.keliver.nav.ScreenState

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

  private val imports = "import dev.keliver.nav.NamedState\nimport dev.keliver.nav.ScreenState\n\n"

  @Test fun statesInsideObjectsClassesAndFunctionsAreReportedNotSkipped() {
    val src = imports + """
      object States {
        @ScreenState("a") fun a() = NamedState(route = Home, fixtures = "a")
      }
      class Holder {
        @ScreenState("b") fun b() = NamedState(route = Home, fixtures = "b")
        companion object {
          @ScreenState("c") fun c() = NamedState(route = Home, fixtures = "c")
        }
      }
      interface Api {
        @ScreenState("d") fun d() = NamedState(route = Home, fixtures = "d")
      }
      fun outer() {
        @ScreenState("e") fun e() = NamedState(route = Home, fixtures = "e")
      }
      object Plain {
        fun notAState() = NamedState(route = Home, fixtures = "z")
      }
      @ScreenState("top") fun top() = NamedState(route = Home, fixtures = "top")
    """.trimIndent()
    val r = ScreenStateRecognizer.recognize("S.kt", src)
    assertEquals(listOf("top"), r.states.map { it.name })
    assertEquals(
      listOf(
        "States.a: a state must be a top-level function; this one is inside object States",
        "Holder.b: a state must be a top-level function; this one is inside class Holder",
        "Holder.Companion.c: a state must be a top-level function; this one is inside the companion object of Holder",
        "Api.d: a state must be a top-level function; this one is inside interface Api",
        "outer.e: a state must be a top-level function; this one is inside function outer()",
      ),
      r.problems,
    )
  }

  @Test fun theAnnotationAndNamedStateAreMatchedByWhatTheImportsBind() {
    fun recognize(head: String, body: String) = ScreenStateRecognizer.recognize("S.kt", head + "\n" + body.trimIndent())

    // Fully qualified, with no imports at all.
    val fq = recognize(
      "",
      """@dev.keliver.nav.ScreenState("fq") fun fq() = dev.keliver.nav.NamedState(route = Home, fixtures = "fq")""",
    )
    assertEquals(emptyList(), fq.problems)
    assertEquals(listOf(ScreenStateSpec("fq", "fq", "Home", "Home", "fq")), fq.states)

    // A star import, an alias, and the package itself.
    val star = recognize("import dev.keliver.nav.*", """@ScreenState("s") fun s() = NamedState(route = Home, fixtures = "s")""")
    assertEquals(listOf("s"), star.states.map { it.name })
    val alias = recognize(
      "import dev.keliver.nav.ScreenState as State\nimport dev.keliver.nav.NamedState as Named",
      """@State("al") fun al() = Named(route = Home, fixtures = "al")""",
    )
    assertEquals(listOf("al"), alias.states.map { it.name }, alias.problems.toString())
    val samePackage = recognize("package dev.keliver.nav", """@ScreenState("p") fun p() = NamedState(Home, "p")""")
    assertEquals(listOf("p"), samePackage.states.map { it.name })

    // Someone else's @ScreenState is not ours and is not reported.
    for (head in listOf("import com.example.ScreenState", "import com.example.Other as ScreenState")) {
      assertEquals(ScreenStates(emptyList()), recognize(head, """@ScreenState("x") fun x() = NamedState(route = Home, fixtures = "x")"""), head)
    }
    assertEquals(
      ScreenStates(emptyList()),
      recognize("", """@com.example.ScreenState("x") fun x() = dev.keliver.nav.NamedState(route = Home, fixtures = "x")"""),
    )
    // A file that declares its own ScreenState and only star-imports ours uses its own.
    assertEquals(
      ScreenStates(emptyList()),
      recognize("import dev.keliver.nav.*\nannotation class ScreenState(val name: String)", """@ScreenState("x") fun x() = NamedState(Home, "x")"""),
    )

    // Spelled like ours but bound by no import: reported, with the fix.
    val unbound = recognize("", """@ScreenState("u") fun u() = NamedState(route = Home, fixtures = "u")""")
    assertEquals(emptyList(), unbound.states)
    assertEquals(listOf("u: @ScreenState is not imported from dev.keliver.nav (add `import dev.keliver.nav.ScreenState`)"), unbound.problems)
  }

  @Test fun aNamedStateThatIsNotOursIsReportedWithTheReason() {
    val r = ScreenStateRecognizer.recognize(
      "S.kt",
      """
        import com.example.NamedState
        import dev.keliver.nav.ScreenState
        @ScreenState("other") fun other() = NamedState(route = Home, fixtures = "o")
        @ScreenState("qualified") fun qualified() = com.example.NamedState(route = Home, fixtures = "q")
        @ScreenState("partial") fun partial() = nav.NamedState(route = Home, fixtures = "p")
        @ScreenState("ok") fun ok() = dev.keliver.nav.NamedState(route = Home, fixtures = "ok")
        @ScreenState("generic") fun generic() = dev.keliver.nav.NamedState<Route>(Route.Home, "g")
        @ScreenState("receiver") fun Screen.receiver() = dev.keliver.nav.NamedState(route = Home, fixtures = "r")
      """.trimIndent(),
    )
    assertEquals(listOf("ok", "generic"), r.states.map { it.name })
    assertEquals(
      listOf(
        "other: NamedState here is `com.example.NamedState`, not dev.keliver.nav.NamedState",
        "qualified: NamedState is qualified as `com.example.NamedState`; write `dev.keliver.nav.NamedState(…)` or import it",
        "partial: NamedState is qualified as `nav.NamedState`; write `dev.keliver.nav.NamedState(…)` or import it",
        "receiver: a state function takes no receiver",
      ),
      r.problems,
    )
    val unimported = ScreenStateRecognizer.recognize(
      "S.kt",
      "import dev.keliver.nav.ScreenState\n@ScreenState(\"n\") fun n() = NamedState(route = Home, fixtures = \"n\")\n",
    )
    assertEquals(listOf("n: NamedState is not imported from dev.keliver.nav (add `import dev.keliver.nav.NamedState`)"), unimported.problems)
  }
}
