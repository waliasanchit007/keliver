package dev.keliver.portal.ingest

import dev.keliver.portal.document.DocNode
import dev.keliver.portal.document.DocOp
import dev.keliver.portal.document.PropValue
import dev.keliver.portal.document.UiDocument
import dev.keliver.portal.document.lit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * K2 review: a reference is a token only when Kotlin would bind it to that
 * constant (shadowing, aliases, same-package declarations, file identity);
 * values are exact on every target; a token chosen for a modifier argument is
 * written back surgically with its import; the relay can ask whether a
 * token would mean what the client chose in a given file.
 */
class DesignTokenResolutionTest {
  private val tokensKt = """
    package app.components

    object C {
      const val Ink = -14737633
      const val Paper = -1
      const val Title = "Funds"
      const val Line = 0xFFE0E0E0.toInt()
      const val Half = 0.5
      const val Big = 0xFFFFFFFF
      const val HalfText = "x${'$'}Half"
      const val Joined = "a" + Half
      const val Count = "n=${'$'}{Ink}"
      const val Huge = 1e400
    }

    const val label = "top-level label"
  """.trimIndent()

  private val tokens = TokenTable.scan(listOf("app/components/Tokens.kt" to tokensKt))

  private fun value(ref: String) = tokens.entries.single { it.decl.ref == ref }.value

  private fun screen(body: String, imports: List<String>, pkg: String = "app.screens", params: String = "") =
    "package $pkg\n\nimport androidx.compose.runtime.Composable\n" + imports.joinToString("") { "import $it\n" } +
      "\n@Composable\nfun S($params) {\n" + body.trimIndent().lines().joinToString("\n") { "  $it" } + "\n}\n"

  private fun root(text: String, table: TokenTable = tokens, file: String = "app/screens/S.kt"): DocNode.Widget =
    Recognizer.recognize(file, text, tokens = table)!!.root

  private fun nodes(n: DocNode): List<DocNode> = listOf(n) + ((n as? DocNode.Widget)?.children?.flatMap(::nodes) ?: emptyList())

  /** True when the call stayed RawCode (the reference was not a token). */
  private fun raw(text: String, table: TokenTable = tokens, file: String = "app/screens/S.kt") =
    nodes(root(text, table, file)).any { it is DocNode.RawCode }

  /** The tokens recognized anywhere in the body. */
  private fun tokensIn(text: String, table: TokenTable = tokens, file: String = "app/screens/S.kt") =
    nodes(root(text, table, file)).filterIsInstance<DocNode.Widget>().flatMap { it.props.values + it.modifiers.values }
      .filterIsInstance<PropValue.Token>()

  @Test fun valuesAreExactOnEveryTarget() {
    assertEquals(-2039584, value("C.Line")) // 0xFFE0E0E0.toInt(): two's complement, as Kotlin computes it
    assertEquals(0xFFFFFFFFL, value("C.Big"))
    assertEquals("n=-14737633", value("C.Count")) // Int in a template is exact everywhere
    // A Double never renders into a String (JVM "x0.5" vs JS "x0.5"/"x1" diverge), and a
    // non-finite literal is not a value.
    assertNull(value("C.HalfText"))
    assertNull(value("C.Joined"))
    assertNull(value("C.Huge"))
    assertNull(TokenTable.constant("1e400"))
    assertEquals(0.5, TokenTable.constant("0.5"))
    // A Long fits no portal prop: never offered.
    assertFalse("C.Big" in tokens.offered.map { it.decl.ref })
    assertNull(tokens.usable("C.Big", "app.components.C", "x.kt"))
  }

  @Test fun localsLambdaParametersAndParametersShadowTokens() {
    val imports = listOf("app.components.C", "app.components.label")
    // A Repeat item variable named like a top-level constant is the item, not the token.
    val item = screen("b.items.forEach { label ->\n  StyledText(text = label)\n}", imports, params = "b: SBindings")
    assertTrue(raw(item) && tokensIn(item).isEmpty())
    // A local val, a lambda parameter, and a non-scalar function parameter.
    val local = screen("val label = \"local\"\nStyledText(text = label)", imports)
    assertTrue(tokensIn(local).isEmpty())
    val lambda = screen("listOf(1).forEach { label -> }\nStyledText(text = label)", imports)
    assertTrue(tokensIn(lambda).isEmpty())
    val param = screen("Column {\n  StyledText(text = C.Title)\n}", imports, params = "b: SBindings, C: Palette")
    assertTrue(raw(param) && tokensIn(param).isEmpty())
    // Unshadowed, the same references are tokens.
    assertEquals(listOf("label"), tokensIn(screen("StyledText(text = label)", imports)).map { it.ref })
  }

  @Test fun aliasesAndSamePackageDeclarationsWinAsInKotlin() {
    // `import q.C as Colors` shadows a same-package `object Colors`: an aliased owner is never a token.
    val colors = TokenTable.scan(listOf("app/screens/Colors.kt" to "package app.screens\nobject Colors { const val Ink = 1 }\n"))
    val aliased = screen("Column {\n  StyledText(text = \"a\", colorArgb = Colors.Ink)\n}", listOf("app.components.C as Colors"))
    assertTrue(raw(aliased, colors))
    // Without the alias the same-package object resolves.
    val plain = screen("StyledText(text = \"a\", colorArgb = Colors.Ink)", emptyList())
    assertEquals(listOf("Colors.Ink"), tokensIn(plain, colors).map { it.ref })
    // A same-package class C (with a companion, unscanned for tokens) beats a star-imported object C.
    val withClass = TokenTable.scan(
      listOf(
        "app/components/Tokens.kt" to tokensKt,
        "app/screens/Theme.kt" to "package app.screens\nclass C { companion object { const val Ink = 5 } }\n",
      ),
    )
    val starred = screen("Column {\n  StyledText(text = \"a\", colorArgb = C.Ink)\n}", listOf("app.components.*"))
    assertTrue(raw(starred, withClass))
    // …and so does a declaration in the referencing file itself.
    assertTrue(raw(starred + "\ntypealias C = Int\n"))
    // With only the star import it resolves.
    assertEquals(listOf("C.Ink"), tokensIn(starred).map { it.ref })
  }

  @Test fun privateVisibilityIsPerFileNotPerBasename() {
    val a = "package app.a\n\nprivate object P { const val X = 1 }\n"
    val b = "package app.a\n\nimport androidx.compose.runtime.Composable\n\n@Composable\nfun S() {\n  StyledText(text = \"a\", colorArgb = P.X)\n}\n"
    val table = TokenTable.scan(listOf("screens/Tokens.kt" to a))
    // Same basename, different file: P is not visible.
    assertTrue(raw(b, table, file = "components/Tokens.kt"))
    // Same identity: it is.
    val both = a + b.substringAfter("package app.a\n")
    val same = TokenTable.scan(listOf("screens/Tokens.kt" to both))
    assertEquals(listOf("P.X"), tokensIn(both, same, file = "screens/Tokens.kt").map { it.ref })
  }

  @Test fun bindingProblemsAreFoundBeforeAnImportIsAdded() {
    val ink = PropValue.Token("C.Ink", lit(-14737633), "app.components.C")
    val other = TokenTable.scan(
      listOf(
        "app/components/Tokens.kt" to tokensKt,
        "app/screens/C.kt" to "package app.screens\nobject C { const val Ink = 7; const val Muted = 8 }\n",
        "x/C.kt" to "package x\nobject C { const val Ink = 9 }\n",
      ),
    )
    val plain = screen("StyledText(text = \"a\")", emptyList(), pkg = "app.other")
    assertNull(other.bindingProblem(plain, "app/other/S.kt", ink)) // the import will be added
    // (a) the file imports a different C: the reference would bind there.
    assertNotNull(other.bindingProblem(screen("StyledText(text = \"a\")", listOf("x.C"), pkg = "app.other"), "app/other/S.kt", ink))
    // (b) the file's package has its own C: importing app.components.C would rebind every C.Muted.
    assertNotNull(other.bindingProblem(screen("StyledText(text = \"a\", colorArgb = C.Muted)", emptyList()), "app/screens/S.kt", ink))
    // Already imported, or star-imported with no competitor: fine.
    assertNull(other.bindingProblem(screen("StyledText(text = \"a\")", listOf("app.components.C"), pkg = "app.other"), "app/other/S.kt", ink))
    assertNull(other.bindingProblem(screen("StyledText(text = \"a\")", listOf("app.components.*"), pkg = "app.other"), "app/other/S.kt", ink))
    // An unknown token.
    assertNotNull(other.bindingProblem(plain, "app/other/S.kt", PropValue.Token("C.Nope", lit(1), "app.components.C")))
  }

  // ── B1: a token chosen for a modifier argument in a file without the import ──

  private val home = screen(
    """
    Column {
      // The card's body.
      StyledText(text = "a", modifier = Modifier.background(-1).padding(4))
      StyledText(text = "b")
    }
    """,
    listOf("dev.keliver.Modifier", "dev.keliver.layout.compose.Column", "dev.keliver.material.compose.StyledText", "dev.keliver.material.compose.background", "dev.keliver.material.compose.padding"),
  ) + "\nprivate val keep = 1 // another top-level declaration\n"

  private fun doc(): UiDocument {
    val rec = Recognizer.recognize("app/screens/S.kt", home, tokens = tokens)!!
    return UiDocument("s", rec.root, rec.contract, 0, 100)
  }

  private fun child(d: UiDocument, i: Int) = (d.root as DocNode.Widget).children[i] as DocNode.Widget

  private val ink = PropValue.Token("C.Ink", lit(-14737633), "app.components.C")

  @Test fun aTokenOnAnExistingModifierArgumentIsSurgicalAndImported() {
    val d = doc()
    val target = d.apply(DocOp.SetModifier(child(d, 0).handle, "Background.colorArgb", ink)).doc
    val merged = WriteBack.merge(home, target, tokens = tokens, fileName = "app/screens/S.kt")
    assertEquals(
      home.replace("Modifier.background(-1)", "Modifier.background(C.Ink)")
        .replace("import androidx.compose.runtime.Composable\n", "import androidx.compose.runtime.Composable\nimport app.components.C\n"),
      merged,
    )
  }

  @Test fun aNewModifierCarryingATokenBringsEveryImportItNeeds() {
    val d = doc()
    val target = d.apply(DocOp.SetModifier(child(d, 1).handle, "Background.colorArgb", ink)).doc
    val merged = WriteBack.merge(home, target, tokens = tokens, fileName = "app/screens/S.kt")!!
    assertTrue("StyledText(modifier = Modifier.background(C.Ink), text = \"b\")" in merged, merged)
    assertTrue("import app.components.C\n" in merged, merged)
    assertTrue("// The card's body." in merged && "private val keep = 1" in merged, merged) // not a whole-file export
    // A token on a node with no modifier yet, in a file importing nothing it needs.
    val bare = screen("StyledText(text = \"b\")", listOf("dev.keliver.material.compose.StyledText"))
    val rec = Recognizer.recognize("app/screens/S.kt", bare, tokens = tokens)!!
    val d2 = UiDocument("s", rec.root, rec.contract, 0, 100)
    val t2 = d2.apply(DocOp.SetModifier(d2.root.handle, "Background.colorArgb", ink)).doc
    val m2 = WriteBack.merge(bare, t2, tokens = tokens, fileName = "app/screens/S.kt")!!
    for (imp in listOf("app.components.C", "dev.keliver.Modifier", "dev.keliver.material.compose.background")) {
      assertTrue("import $imp\n" in m2, m2)
    }
    // A new modifier joining an existing chain (the chain is rewritten, the rest kept).
    val padded = screen(
      "StyledText(text = \"c\", modifier = Modifier.padding(2))",
      listOf("dev.keliver.Modifier", "dev.keliver.material.compose.StyledText", "dev.keliver.material.compose.padding"),
    )
    val rec3 = Recognizer.recognize("app/screens/S.kt", padded, tokens = tokens)!!
    val d3 = UiDocument("s", rec3.root, rec3.contract, 0, 100)
    val t3 = d3.apply(DocOp.SetModifier(d3.root.handle, "Background.colorArgb", ink)).doc
    val m3 = WriteBack.merge(padded, t3, tokens = tokens, fileName = "app/screens/S.kt")!!
    assertTrue("StyledText(text = \"c\", modifier = Modifier.padding(2).background(C.Ink))" in m3, m3)
    for (imp in listOf("app.components.C", "dev.keliver.material.compose.background")) {
      assertTrue("import $imp\n" in m3, m3)
    }
  }

  // ── re-review: the screen's own names, one scope per batch, default imports, functions ──

  @Test fun aTokenNamedLikeTheScreensOwnNameIsRefused() {
    val inkTop = PropValue.Token("Ink", lit(3), "app.components.Ink")
    val table = TokenTable.scan(listOf("app/components/T.kt" to "package app.components\n\nconst val Ink = 3\n"))
    val local = screen("val Ink = 1\nStyledText(text = \"a\")", emptyList(), pkg = "app.other")
    assertNotNull(table.bindingProblem(local, "app/other/S.kt", inkTop))
    val param = screen("StyledText(text = \"a\")", emptyList(), pkg = "app.other", params = "C: Int")
    assertNotNull(tokens.bindingProblem(param, "app/other/S.kt", ink))
    assertNull(tokens.bindingProblem(screen("StyledText(text = \"a\")", emptyList(), pkg = "app.other"), "app/other/S.kt", ink))
  }

  @Test fun twoOwnersWithOneSimpleNameCannotBothBeImported() {
    val two = TokenTable.scan(
      listOf(
        "x/C.kt" to "package x\nobject C { const val Ink = 9 }\n",
        "q/C.kt" to "package q\nobject C { const val Muted = 8 }\n",
      ),
    )
    val plain = screen("StyledText(text = \"a\")", emptyList(), pkg = "app.other")
    val xInk = PropValue.Token("C.Ink", lit(9), "x.C")
    val qMuted = PropValue.Token("C.Muted", lit(8), "q.C")
    assertNull(two.bindingProblem(plain, "app/other/S.kt", listOf(xInk)))
    // In one batch.
    assertNotNull(two.bindingProblem(plain, "app/other/S.kt", listOf(xInk, qMuted)))
    // Across batches, while the file still lacks the first import: the document's tokens count.
    assertNotNull(two.bindingProblem(plain, "app/other/S.kt", listOf(qMuted), pending = setOf("x.C")))
    assertNull(two.bindingProblem(plain, "app/other/S.kt", listOf(xInk), pending = setOf("x.C")))
  }

  @Test fun importingADefaultImportedNameTheFileUsesIsRefused() {
    val pair = TokenTable.scan(listOf("app/components/Pair.kt" to "package app.components\nobject Pair { const val Gap = 4 }\n"))
    val token = PropValue.Token("Pair.Gap", lit(4), "app.components.Pair")
    val uses = screen("val p = Pair(1, 2)\nStyledText(text = \"a\")", emptyList(), pkg = "app.other")
    assertNotNull(pair.bindingProblem(uses, "app/other/S.kt", token))
    assertNull(pair.bindingProblem(screen("StyledText(text = \"a\")", emptyList(), pkg = "app.other"), "app/other/S.kt", token))
  }

  @Test fun aFunctionOfTheSameNameDoesNotBlockAToken() {
    val withFn = TokenTable.scan(
      listOf(
        "app/components/Tokens.kt" to tokensKt,
        "app/screens/Fns.kt" to "package app.screens\nfun C(): Int = 1\nfun label(): String = \"x\"\n",
      ),
    )
    val starred = screen("Column {\n  StyledText(text = \"a\", colorArgb = C.Ink)\n}", listOf("app.components.*"))
    assertEquals(listOf("C.Ink"), tokensIn(starred, withFn).map { it.ref })
  }

  @Test fun aPrivateTopLevelConstantIsUsableInItsOwnFileOnly() {
    val text = "package app.screens\n\nprivate const val Accent = 5\n"
    val table = TokenTable.scan(listOf("app/screens/home.kt" to text))
    assertNotNull(table.usable("Accent", "app.screens.Accent", "app/screens/home.kt"))
    assertNull(table.usable("Accent", "app.screens.Accent", "app/screens/other.kt"))
  }
}
