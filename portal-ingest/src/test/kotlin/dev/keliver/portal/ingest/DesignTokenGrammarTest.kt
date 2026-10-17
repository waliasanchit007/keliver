package dev.keliver.portal.ingest

import dev.keliver.portal.MapComponentRegistry
import dev.keliver.portal.Token
import dev.keliver.portal.document.DocNode
import dev.keliver.portal.document.DocOp
import dev.keliver.portal.document.Handle
import dev.keliver.portal.document.PropValue
import dev.keliver.portal.document.UiDocument
import dev.keliver.portal.document.lit
import dev.keliver.portal.document.toWidgetTree
import dev.keliver.portal.exportKotlin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * K2: references to the app's design-token constants (`C.Ink`,
 * `Assets.Logo`) are grammar — values resolved at ingest, references kept
 * through export and write-back — and never silently wrong: an unknown,
 * private, cyclic or mismatched reference stays RawCode.
 */
class DesignTokenGrammarTest {
  private val tokensKt = """
    package app.components

    object C {
      const val White = -1 // #FFFFFF
      const val Ink = -14737633 // #1F1F1F
      const val Gap = 12.0
      const val Dense = true
      const val Title = "Funds"
      const val Wide = 0xFFFFFFFF
      const val Big: Long = 7
      private const val Secret = -16777216
      const val Mirror = Secret
      const val Loop1: String = Loop2
      const val Loop2: String = "x${'$'}Loop1"
      const val Broken = Missing
    }
  """.trimIndent()

  // An object in a file with other declarations (a component lives here too).
  private val homePartsKt = """
    package app.components

    import androidx.compose.runtime.Composable

    object Assets {
      private const val WEB = "https://example.com"
      const val HumansLogo = "${'$'}WEB/humansLogo.png"
      const val Banner = "${'$'}{WEB}/b" + "anner.png"
      val Plain = "plain"
      val NotConstant = listOf(1)
      val Escaped = "a\"b\n${'$'}{C.Title}"
    }

    const val TopGap = 4.0
    private const val FilePrivate = "fp"

    @Composable
    fun Unrelated() {}
  """.trimIndent()

  private val tokens = TokenTable.scan(listOf("Tokens.kt" to tokensKt, "HomeParts.kt" to homePartsKt))

  private fun entry(ref: String) = tokens.entries.single { it.decl.ref == ref }
  private fun value(ref: String) = entry(ref).value

  private fun containsRaw(n: DocNode): Boolean = when (n) {
    is DocNode.RawCode -> true
    is DocNode.Widget -> n.children.any(::containsRaw)
  }

  /** A screen in [pkg] importing [imports] whose body is [body]. */
  private fun screen(body: String, imports: List<String> = listOf("app.components.C", "app.components.Assets"), pkg: String = "app.screens") =
    "package $pkg\n\nimport androidx.compose.runtime.Composable\n" + imports.joinToString("") { "import $it\n" } +
      "\n@Composable\nfun S() {\n" + body.trimIndent().lines().joinToString("\n") { "  $it" } + "\n}\n"

  private fun root(text: String): DocNode.Widget = Recognizer.recognize("S.kt", text, tokens = tokens)!!.root

  private fun token(ref: String, v: Any, import: String) = PropValue.Token(ref, lit(v), import)

  @Test fun constantsResolveTransitivelyAtIngest() {
    assertEquals(-1, value("C.White"))
    assertEquals(-14737633, value("C.Ink"))
    assertEquals(12.0, value("C.Gap"))
    assertEquals(true, value("C.Dense"))
    assertEquals("Funds", value("C.Title"))
    assertEquals(0xFFFFFFFFL, value("C.Wide")) // too big for Int: Kotlin types it Long
    assertEquals(7L, value("C.Big"))
    // A private constant evaluates (others are built from it) …
    assertEquals(-16777216, value("C.Secret"))
    assertEquals(-16777216, value("C.Mirror"))
    assertEquals("https://example.com/humansLogo.png", value("Assets.HumansLogo"))
    assertEquals("https://example.com/banner.png", value("Assets.Banner"))
    assertEquals("plain", value("Assets.Plain")) // a `val` with a constant initializer
    assertEquals("a\"b\nFunds", value("Assets.Escaped"))
    assertEquals(4.0, value("TopGap"))
    // … but what does not compute is not a token.
    assertNull(value("Assets.NotConstant"))
    assertNull(value("C.Loop1")) // a cycle
    assertNull(value("C.Loop2"))
    assertNull(value("C.Broken")) // an unknown reference
    // Only exported, resolvable constants are offered to the editor.
    val offered = tokens.offered.map { it.decl.ref }.toSet()
    assertTrue("C.Ink" in offered && "Assets.HumansLogo" in offered && "TopGap" in offered, "$offered")
    assertFalse("C.Secret" in offered || "Assets.WEB" in offered || "FilePrivate" in offered, "$offered")
    assertFalse("C.Loop1" in offered || "Assets.NotConstant" in offered, "$offered")
    assertEquals("app.components.C", entry("C.Ink").decl.importFq)
    assertEquals("app.components.TopGap", entry("TopGap").decl.importFq)
  }

  @Test fun aComponentUsingTokensIsTransparent() {
    val card = """
      package app.components

      import androidx.compose.runtime.Composable
      import dev.keliver.Modifier
      import dev.keliver.material.compose.AsyncImage
      import dev.keliver.material.compose.StyledBox
      import dev.keliver.material.compose.StyledText
      import dev.keliver.material.compose.background

      @Composable
      fun BrandCard(label: String, tint: Int = C.Ink) {
        StyledBox(colorArgb = C.White, fillWidth = C.Dense) {
          AsyncImage(url = Assets.HumansLogo, widthDp = 24)
          StyledText(text = label, colorArgb = C.Ink, modifier = Modifier.background(C.White))
          StyledText(text = C.Title, colorArgb = tint)
        }
      }
    """.trimIndent()
    val rc = Recognizer.recognizeComponent("BrandCard.kt", card, tokens = tokens)!!
    assertTrue(rc.spec.transparent, rc.spec.diagnostic)
    assertFalse(containsRaw(rc.root!!))
    // Signature defaults preview as the token's value.
    assertEquals(-14737633, rc.spec.defaults["tint"])
    val box = rc.root!!
    // Same package: no import needed, but the token still records where it lives.
    assertEquals(token("C.White", -1, "app.components.C"), box.props["colorArgb"])
    assertEquals(token("C.Dense", true, "app.components.C"), box.props["fillWidth"])
    val (image, text, title) = box.children.map { it as DocNode.Widget }
    assertEquals(token("Assets.HumansLogo", "https://example.com/humansLogo.png", "app.components.Assets"), image.props["url"])
    assertEquals(token("C.Ink", -14737633, "app.components.C"), text.props["colorArgb"])
    assertEquals(token("C.White", -1, "app.components.C"), text.modifiers["Background.colorArgb"])
    assertEquals(token("C.Title", "Funds", "app.components.C"), title.props["text"])
    // The body the preview expands carries the resolved value with the reference.
    assertEquals(Token("C.Ink", -14737633, "app.components.C"), rc.spec.body!!.children[1].props["colorArgb"])
  }

  @Test fun privateConstantsAreUsableInsideTheirObjectOnly() {
    // `Assets.HumansLogo` is built from the private WEB …
    assertIs<PropValue.Token>((root(screen("""AsyncImage(url = Assets.HumansLogo)""")) ).props["url"])
    // … but WEB itself cannot be referenced from another file (it would not compile).
    assertIs<DocNode.RawCode>(root(screen("""Column { AsyncImage(url = Assets.WEB) }""")).children.single())
    assertIs<DocNode.RawCode>(root(screen("""Column { StyledText(text = FilePrivate) }""", listOf("app.components.FilePrivate"))).children.single())
  }

  @Test fun unknownCyclicOrMismatchedReferencesStayRawCode() {
    fun raw(call: String) = assertIs<DocNode.RawCode>(root(screen("Column {\n  $call\n}")).children.single(), call)
    raw("StyledText(text = C.Nope)") // unknown
    raw("StyledText(text = C.Loop1)") // cyclic
    raw("StyledText(text = Assets.NotConstant)") // not a constant
    raw("StyledText(text = C.Ink)") // Int into a String
    raw("StyledText(text = \"a\", colorArgb = C.Gap)") // Double into an Int
    raw("StyledText(text = \"a\", colorArgb = C.Wide)") // Long into an Int
    raw("Row(width = C.Ink) {}") // Int into a Constraint
    raw("Spacer(height = C.Gap)") // Double into a Dp
    raw("StyledText(text = \"a\", modifier = Modifier.background(C.Title))") // String into an Int argument
  }

  @Test fun aReferenceResolvesOnlyAsKotlinWouldFindIt() {
    val call = "StyledText(text = \"a\", colorArgb = C.Ink)"
    // Imported exactly, by a star import, or from the same package.
    assertIs<DocNode.Widget>(root(screen(call)))
    assertIs<DocNode.Widget>(root(screen(call, listOf("app.components.*"))))
    assertIs<DocNode.Widget>(root(screen(call, emptyList(), pkg = "app.components")))
    // Not imported: Kotlin would not find `C`.
    assertIs<DocNode.RawCode>(root(screen("Column {\n  $call\n}", emptyList())).children.single())
    // An aliased import is not followed (the reference could not be inserted elsewhere).
    assertIs<DocNode.RawCode>(root(screen("Column {\n  StyledText(text = \"a\", colorArgb = K.Ink)\n}", listOf("app.components.C as K"))).children.single())
    // A bare top-level constant (here a Double for a scoped `flex(Double)` argument).
    val bare = root(screen("Row {\n  StyledText(text = \"a\", modifier = Modifier.flex(TopGap))\n}", listOf("app.components.TopGap")))
    assertFalse(containsRaw(bare))
    assertEquals(token("TopGap", 4.0, "app.components.TopGap"), (bare.children.single() as DocNode.Widget).modifiers["Flex.value"])
  }

  // ── export and write-back ───────────────────────────────────────────────

  private val home = screen(
    """
    Column {
      StyledText(text = C.Title, colorArgb = C.Ink, fontSize = 12)
      StyledText(text = "b", modifier = Modifier.background(C.White).padding(4))
    }
    """,
    listOf("app.components.C", "dev.keliver.Modifier", "dev.keliver.layout.compose.Column", "dev.keliver.material.compose.StyledText", "dev.keliver.material.compose.background", "dev.keliver.material.compose.padding"),
  )

  private fun doc(text: String = home): UiDocument {
    val rec = Recognizer.recognize("S.kt", text, tokens = tokens)!!
    assertFalse(containsRaw(rec.root), "$text")
    return UiDocument("s", rec.root, rec.contract, version = 0, nextHandle = 100)
  }

  private fun child(d: UiDocument, i: Int) = (d.root as DocNode.Widget).children[i] as DocNode.Widget

  @Test fun exportKeepsReferencesAndReingestsToTheSameTree() {
    val d = doc()
    val exported = exportKotlin(d.toWidgetTree(), functionName = "S")
    assertTrue("text = C.Title," in exported && "colorArgb = C.Ink," in exported, exported)
    assertTrue("Modifier.background(C.White).padding(4)" in exported, exported)
    assertTrue("import app.components.C\n" in exported, exported)
    val again = Recognizer.recognize("S.kt", "package app.screens\n\n$exported", tokens = tokens)!!.root
    assertEquals(d.root.withoutHandles(), again.withoutHandles())
  }

  @Test fun surgicalWriteBackLeavesAnUnchangedReferenceAlone() {
    val d = doc()
    val first = child(d, 0)
    val target = d.apply(DocOp.SetProp(first.handle, "fontSize", lit(14))).doc
    assertEquals(home.replace("fontSize = 12", "fontSize = 14"), WriteBack.merge(home, target, tokens = tokens))
    // A modifier argument beside a token argument.
    val second = child(d, 1)
    val padded = d.apply(DocOp.SetModifier(second.handle, "Padding.allDp", lit(8))).doc
    assertEquals(home.replace(".padding(4)", ".padding(8)"), WriteBack.merge(home, padded, tokens = tokens))
  }

  @Test fun bindingATokenWritesTheReferenceAndAddsItsImport() {
    val d = doc()
    val first = child(d, 0)
    val logo = token("Assets.HumansLogo", "https://example.com/humansLogo.png", "app.components.Assets")
    val target = d.apply(DocOp.SetProp(first.handle, "text", logo)).doc
    val merged = WriteBack.merge(home, target, tokens = tokens)!!
    assertTrue("StyledText(text = Assets.HumansLogo, colorArgb = C.Ink, fontSize = 12)" in merged, merged)
    assertTrue("import app.components.Assets\nimport app.components.C\n" in merged, merged)
    // Detach: the literal replaces the reference (the import stays; it is harmless).
    val detached = target.apply(DocOp.SetProp(first.handle, "text", lit("https://example.com/humansLogo.png"))).doc
    val again = WriteBack.merge(merged, detached, tokens = tokens)!!
    assertTrue("StyledText(text = \"https://example.com/humansLogo.png\", colorArgb = C.Ink, fontSize = 12)" in again, again)
  }

  @Test fun insertingATokenAddsTheImportOnceAndRespectsStarImportsAndThePackage() {
    val node = DocNode.Widget(Handle(0), "StyledText", props = mapOf("text" to lit("n"), "colorArgb" to token("C.Ink", -14737633, "app.components.C")))
    fun insert(text: String): String {
      val d = doc(text)
      val after = d.apply(DocOp.InsertNode(d.root.handle, child(d, 0).handle, node)).doc
      return WriteBack.merge(text, after, tokens = tokens)!!
    }
    val plain = screen("Column {\n  StyledText(text = \"a\")\n}", listOf("dev.keliver.layout.compose.Column", "dev.keliver.material.compose.StyledText"))
    val added = insert(plain)
    assertTrue("StyledText(\n" in added && "colorArgb = C.Ink," in added, added)
    assertEquals(1, Regex("import app\\.components\\.C\\n").findAll(added).count(), added)
    // Already imported, star-imported, or the same package: nothing added.
    val starred = plain.replace("import dev.keliver.layout", "import app.components.*\nimport dev.keliver.layout")
    assertFalse("import app.components.C\n" in insert(starred))
    val samePkg = plain.replace("package app.screens", "package app.components")
    assertFalse("import app.components.C\n" in insert(samePkg))
  }

  @Test fun aComponentInstanceTakesATokenOnlyOfItsParameterType() {
    val row = Recognizer.recognizeComponent(
      "MenuRow.kt",
      "package app.components\n\nimport androidx.compose.runtime.Composable\n\n@Composable\nfun MenuRow(title: String, tint: Int) {\n  StyledText(text = title, colorArgb = tint)\n}\n",
    )!!.spec
    val reg = MapComponentRegistry(listOf(row))
    fun first(call: String) = Recognizer.recognize("S.kt", screen("Column {\n  $call\n}"), reg, tokens)!!.root.children.single()
    val ok = first("MenuRow(title = C.Title, tint = C.Ink)") as DocNode.Widget
    assertEquals(token("C.Title", "Funds", "app.components.C"), ok.props["title"])
    assertIs<DocNode.RawCode>(first("MenuRow(title = C.Ink, tint = C.Ink)"))
    // And the export writes the reference back.
    val tree = UiDocument("s", DocNode.Widget(Handle(1), "Column", children = listOf(ok)), dev.keliver.portal.document.Contract(), 0, 9).toWidgetTree()
    val src = exportKotlin(tree, components = reg)
    assertTrue("title = C.Title," in src && "tint = C.Ink," in src && "import app.components.C\n" in src, src)
  }

  @Test fun aDeclarationChangeReResolvesOrTurnsTheArgumentRaw() {
    val text = screen("Column {\n  StyledText(text = \"a\", colorArgb = C.Ink)\n}")
    fun table(ink: String) = TokenTable.scan(listOf("Tokens.kt" to "package app.components\nobject C { const val Ink = $ink }\n"))
    fun recognized(t: TokenTable) = Recognizer.recognize("S.kt", text, tokens = t)!!.root.children.single()
    assertEquals(token("C.Ink", 5, "app.components.C"), (recognized(table("5")) as DocNode.Widget).props["colorArgb"])
    assertEquals(token("C.Ink", 6, "app.components.C"), (recognized(table("6")) as DocNode.Widget).props["colorArgb"])
    assertIs<DocNode.RawCode>(recognized(table("\"now a string\""))) // changed kind
    assertIs<DocNode.RawCode>(recognized(TokenTable.EMPTY)) // gone
    assertNotNull(table("5").signature().singleOrNull())
    assertFalse(table("5").signature() == table("6").signature())
  }

  private fun DocNode.withoutHandles(): DocNode = when (this) {
    is DocNode.RawCode -> copy(handle = Handle(0))
    is DocNode.Widget -> copy(handle = Handle(0), children = children.map { it.withoutHandles() })
  }
}
