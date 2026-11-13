package dev.keliver.portal.ingest

import dev.keliver.portal.document.DocNode
import dev.keliver.portal.document.PropValue
import dev.keliver.portal.ingest.ComposeFixtures.INBOX_SCREEN
import dev.keliver.portal.ingest.ComposeFixtures.raws
import dev.keliver.portal.ingest.ComposeFixtures.recognize
import dev.keliver.portal.ingest.ComposeFixtures.widgets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ED0 spike (docs/ED0_SPIKE.md): Compose-shaped code ingests against the generated catalog when
 * the flag is on — Gate A's sample screen with 0 RawCode — and exactly as before when it is off.
 */
class ComposeIngestTest {
  private val m3 = "dev.keliver.compose.material3"
  private val layout = "dev.keliver.compose.foundation.layout"

  @Test fun theFlagIsOffByDefault() {
    assertNull(ComposeIngest.catalog(), "ED0 is on only when a catalog is set")
    // Off, the sample is today's grammar: its Compose calls are not grammar (RawCode), never Compose nodes.
    val off = Recognizer.recognize("SampleCard.kt", ComposeFixtures.sampleCard, asComponent = true)!!
    assertTrue(widgets(off.root).none { '#' in it.type }, "no Compose node with the flag off")
    assertTrue(off.rawReasons.isNotEmpty(), "with the flag off the sample is not today's grammar")
  }

  @Test fun theGateASampleIngestsWithNoRawCode() {
    val rec = recognize(ComposeFixtures.sampleCard, "SampleCard.kt", asComponent = true)
    assertEquals(emptyList(), rec.rawReasons)
    assertEquals(emptyList(), raws(rec.root))
    val types = widgets(rec.root).map { it.type.substringAfterLast('.') }
    assertEquals(
      listOf(
        "Column#4", "Row#3", "Box#1", "Icon#7", "Spacer#5", "Column#4", "Text#10", "Text#10", "Icon#7",
        "FlowRow#9", "Repeat", "Box#1", "Text#10", "Spacer#5",
      ),
      types,
    )
    val row = widgets(rec.root)[1]
    // The modifier list in chain order, every element its overload's key.
    assertEquals(
      listOf("0:fillMaxWidth#9", "1:shadow#25", "2:clip#23", "3:background#18", "4:border#19", "5:clickable#26", "6:padding#2"),
      row.modifiers.keys.filter { '.' !in it },
    )
    assertEquals(PropValue.Lit("c", s = "1"), row.modifiers["1:shadow#25.elevation"])
    assertEquals(PropValue.Lit("c", s = """{"rr":[12,12,12,12]}"""), row.modifiers["1:shadow#25.shape"])
    assertEquals(PropValue.Lit("b", b = false), row.modifiers["1:shadow#25.clip"])
    assertEquals(PropValue.Action("onOpen"), row.modifiers["5:clickable#26.onClick"])
    assertEquals(PropValue.Lit("c", s = "16"), row.modifiers["6:padding#2.horizontal"])
    assertEquals(PropValue.Lit("c", s = "0.0"), row.props["verticalAlignment"])
    val texts = widgets(rec.root).filter { it.type == "$m3.Text#10" }
    assertEquals(PropValue.Bind("title"), texts[0].props["text"])
    assertEquals(PropValue.Lit("c", s = "\"16sp\""), texts[0].props["fontSize"])
    assertEquals(PropValue.Lit("c", s = "600"), texts[0].props["fontWeight"])
    assertEquals(PropValue.Lit("c", s = "4280229663"), texts[0].props["color"])
    assertEquals(PropValue.Lit("i", i = 1), texts[0].props["maxLines"])
    assertEquals(PropValue.Lit("c", s = """{"ref":"LocalTextStyle","set":{"letterSpacing":"1sp"}}"""), texts[1].props["style"])
    assertEquals(PropValue.Lit("c", s = """{"ref":"colorScheme.primary"}"""), texts[1].props["color"])
    // The Repeat over the `tags` parameter, from a `for` loop; its item is a binding.
    val repeat = widgets(rec.root).single { it.type == "Repeat" }
    assertEquals(PropValue.Lit("s", s = "tags"), repeat.props["items"])
    assertEquals(PropValue.Bind("tag"), texts[2].props["text"])
    // `Modifier.weight(1f)` under the Row is RowScope's weight.
    assertEquals(listOf("0:weight#12", "0:weight#12.weight"), widgets(rec.root)[5].modifiers.keys.toList())
    val icon = widgets(rec.root)[3]
    assertEquals(PropValue.Lit("c", s = "\"Filled.Search\""), icon.props["imageVector"])
    assertEquals(PropValue.Lit("c", s = "null"), icon.props["contentDescription"])
  }

  @Test fun aStyleBScreenIngestsWithNoRawCode() {
    val rec = recognize(INBOX_SCREEN)
    assertEquals(emptyList(), rec.rawReasons)
    val texts = widgets(rec.root).filter { it.type == "$m3.Text#10" }
    assertEquals(PropValue.Bind("title"), texts[0].props["text"])
    assertEquals(PropValue.Lit("c", s = """{"ref":"typography.titleMedium"}"""), texts[0].props["style"])
    assertEquals(PropValue.Lit("c", s = "700"), texts[1].props["fontWeight"])
    assertEquals(PropValue.Lit("c", s = """{"ref":"colorScheme.surfaceVariant"}"""), widgets(rec.root)[1].modifiers["1:background#18.color"])
  }

  @Test fun resolutionIsByImportNotBySimpleName() {
    // The legacy `Text` and the generated one, in two files of one project.
    val legacy = """
      package app.screens

      import androidx.compose.runtime.Composable
      import dev.keliver.layout.compose.Column
      import dev.keliver.material.compose.Text

      @Composable
      fun Legacy(b: LegacyBindings) {
        Column {
          Text(text = "Hello")
        }
      }

      interface LegacyBindings
    """.trimIndent()
    val l = recognize(legacy)
    assertEquals(listOf("Column", "Text"), widgets(l.root).map { it.type }, "legacy imports keep today's widgets")
    val generated = legacy.replace("dev.keliver.layout.compose.Column", "$layout.Column").replace("dev.keliver.material.compose.Text", "$m3.Text")
    assertEquals(listOf("$layout.Column#4", "$m3.Text#10"), widgets(recognize(generated).root).map { it.type })
    // One file: a legacy widget and a generated one side by side, and an alias.
    val mixed = """
      package app.screens

      import androidx.compose.runtime.Composable
      import dev.keliver.compose.foundation.layout.Column
      import dev.keliver.compose.material3.Text as M3Text
      import dev.keliver.material.compose.StyledText

      @Composable
      fun Mixed(b: MixedBindings) {
        Column {
          StyledText(text = "legacy")
          M3Text("generated")
        }
      }

      interface MixedBindings
    """.trimIndent()
    assertEquals(listOf("$layout.Column#4", "StyledText", "$m3.Text#10"), widgets(recognize(mixed).root).map { it.type })
    // A guest name the catalog does not generate is never read as the legacy widget of that name.
    val checkbox = generated.replace("import $m3.Text", "import $m3.Checkbox\nimport $m3.Text")
      .replace("Text(text = \"Hello\")", "Checkbox(checked = true, onCheckedChange = null)")
    val rb = recognize(checkbox)
    assertEquals(listOf("`Checkbox(…)` is `$m3.Checkbox`, which the generated catalog does not include"), rb.rawReasons)
  }

  @Test fun overloadsAreChosenAsKotlinChoosesThem() {
    fun chain(mods: String): List<String> = recognize(box("Box(Modifier$mods)")).let { r ->
      assertEquals(emptyList(), r.rawReasons, mods)
      widgets(r.root)[1].modifiers.keys.filter { '.' !in it }
    }
    assertEquals(listOf("0:padding#1"), chain(".padding(16.dp)"))
    assertEquals(listOf("0:padding#2"), chain(".padding(horizontal = 16.dp)"))
    assertEquals(listOf("0:padding#3"), chain(".padding(top = 4.dp)"))
    assertEquals(listOf("0:padding#4"), chain(".padding(PaddingValues(8.dp))"))
    assertEquals(listOf("0:size#5"), chain(".size(36.dp)"))
    assertEquals(listOf("0:size#6"), chain(".size(36.dp, 20.dp)"))
    // Repeats keep their order: an ordered list, not one value per name.
    assertEquals(listOf("0:padding#1", "1:background#18", "2:padding#1", "3:clip#23", "4:background#18"),
      chain(".padding(8.dp).background(Color.White).padding(4.dp).clip(CircleShape).background(Color.Red)"))
    // The Box with content and the one without are two overloads.
    assertEquals(listOf("$layout.Box#2"), widgets(recognize(box("Box(Modifier.size(8.dp))")).root).drop(1).map { it.type })
    assertEquals(listOf("$layout.Box#1"), widgets(recognize(box("Box(Modifier.size(8.dp)) {}")).root).drop(1).map { it.type })
    // `padding(16.dp)` fits #1 with no default and #2/#3 with some: the one without defaults wins.
    // `padding()` fits #2 and #3, both on defaults only: Kotlin's tie-break is yes/no, not a count,
    // so the compiler refuses it as ambiguous, and so does ingest.
    val empty = recognize(box("Text(\"a\", Modifier.padding())")).rawReasons.single()
    assertTrue("`padding(…)` is ambiguous between overloads #2, #3" in empty, empty)
  }

  @Test fun aCallWhoseResolutionIsNotCertainIsRawCode() {
    fun screen(imports: String, body: String, more: String = "") = """
      package app.screens

      import androidx.compose.runtime.Composable
      $imports

      @Composable
      fun S(b: SBindings) {
        Column {
          $body
        }
      }
      $more
      interface SBindings
    """.trimIndent()
    // Two star imports that each give a generated `Text`: the compiler refuses it.
    val twoTexts = ComposeCatalog.parse(
      ComposeFixtures.gateAJson,
      """{"group": "ed0-dup", "widgets": [{"name": "Text", "compose": "androidx.compose.foundation.text.BasicText", "guest": "dev.keliver.compose.foundation.text.Text", "palette": "x", "tag": 103, "schemaClass": "GenText103", "params": [{"name": "text", "kind": "string", "type": "kotlin.String"}]}], "modifiers": []}""",
    )
    val ambiguous = ComposeIngest.with(twoTexts) {
      Recognizer.recognize("S.kt", screen("import $layout.Column\nimport $m3.*\nimport dev.keliver.compose.foundation.text.*", "Text(\"a\")"))!!
    }
    assertEquals(
      listOf("`Text(…)` is ambiguous between `$m3.Text`, `dev.keliver.compose.foundation.text.Text` (two star imports)"),
      ambiguous.rawReasons,
    )
    // Two guest star imports and a name neither generates (`Checkbox`): never a legacy widget of that name.
    val stars = recognize(screen("import $m3.*\nimport $layout.*", "Checkbox(checked = true, onCheckedChange = null)"))
    assertEquals(listOf("$layout.Column#4"), widgets(stars.root).map { it.type })
    assertTrue("may give a guest declaration the generated catalog does not include" in stars.rawReasons.single(), stars.rawReasons.single())
    // An explicit import of Compose's own API is neither a generated nor a legacy widget.
    val own = recognize(screen("import $layout.Column\nimport $m3.Text\nimport androidx.compose.material3.Card", "Card { Text(\"a\") }"))
    assertEquals(listOf("`Card(…)` is `androidx.compose.material3.Card`, Compose's own API: neither a generated nor a legacy widget"), own.rawReasons)
    // A same-file declaration shadows a star import: the call is the file's own `Text`, not Text#10.
    val shadowed = recognize(screen("import $layout.Column\nimport $m3.*", "Text(\"a\")", more = "@Composable fun Text(s: String) {}\n"))
    assertEquals(listOf("`Text(…)` is declared in this file too, so it may not be the generated widget"), shadowed.rawReasons)
    // …and so does a same-file extension for a modifier.
    val ownPadding = recognize(
      screen(
        "import $layout.Column\nimport $m3.Text\nimport $layout.*\nimport dev.keliver.compose.ui.Modifier\nimport dev.keliver.compose.ui.unit.Dp\nimport dev.keliver.compose.ui.unit.dp",
        "Text(\"a\", Modifier.padding(4.dp))",
        more = "fun Modifier.padding(all: Dp): Modifier = this\n",
      ),
    )
    val why = ownPadding.rawReasons.single()
    assertTrue("`.padding(…)` is declared in this file too, so it may not be the generated modifier" in why, why)
    // A file that imports no guest name keeps today's reading, even with the flag on.
    val legacyOnly = recognize(screen("import dev.keliver.layout.compose.Column\nimport androidx.compose.material3.Card", "Card {}"))
    assertTrue(widgets(legacyOnly.root).none { '#' in it.type })
  }

  @Test fun scopedModifiersResolveAgainstTheParentsScope() {
    val inRow = recognize(box("Row { Text(\"a\", Modifier.weight(1f)) }"))
    assertEquals(listOf("0:weight#12", "0:weight#12.weight"), widgets(inRow.root)[2].modifiers.keys.toList())
    val inColumn = recognize(box("Column { Text(\"a\", Modifier.weight(2f, fill = false)) }"))
    assertEquals(PropValue.Lit("b", b = false), widgets(inColumn.root)[2].modifiers["0:weight#14.fill"])
    val inBox = recognize(box("Box { Text(\"a\", Modifier.align(Alignment.Center)) }"))
    assertEquals(PropValue.Lit("c", s = "[0.0,0.0]"), widgets(inBox.root)[2].modifiers["0:align#16.alignment"])
    val outside = recognize(box("Box { Text(\"a\", Modifier.weight(1f)) }"))
    assertTrue("RowScope/ColumnScope, not of this content's scope (BoxScope)" in outside.rawReasons.single(), outside.rawReasons.single())
  }

  @Test fun optionalAndMultipleSlots() {
    val rec = recognize(
      box(
        """
        ListItem(
          headlineContent = { Text("Inbox") },
          supportingContent = { Text("3 new") },
          leadingContent = { Icon(Icons.Filled.Search, contentDescription = null) },
        )
        """.trimIndent(),
      ).replace("import $m3.Text", "import $m3.Text\nimport $m3.ListItem"),
    )
    assertEquals(emptyList(), rec.rawReasons)
    val item = widgets(rec.root)[1]
    assertEquals("$m3.ListItem#101", item.type)
    // One node per slot passed, in source order; the absent optional slots have none.
    assertEquals(listOf("slot:headlineContent", "slot:supportingContent", "slot:leadingContent"), item.children.map { (it as DocNode.Widget).type })
    assertEquals(PropValue.Lit("s", s = "3 new"), ((item.children[1] as DocNode.Widget).children.single() as DocNode.Widget).props["text"])
    // TopAppBar's scoped `actions` slot, named: its content has RowScope.
    val bar = recognize(
      box("TopAppBar(title = { Text(\"Messages\") }, actions = { Text(\"a\", Modifier.weight(1f)) })")
        .replace("import $m3.Text", "import $m3.Text\nimport $m3.TopAppBar"),
    )
    assertEquals(emptyList(), bar.rawReasons)
    val top = widgets(bar.root)[1]
    assertEquals(listOf("slot:title", "slot:actions"), top.children.map { (it as DocNode.Widget).type })
    // Its last parameter is `scrollBehavior`, not a lambda: a trailing block does not bind, as Kotlin refuses it.
    val trailing = recognize(
      box("TopAppBar(title = { Text(\"Messages\") }) { Text(\"a\") }").replace("import $m3.Text", "import $m3.Text\nimport $m3.TopAppBar"),
    )
    assertEquals(listOf("`TopAppBar(…)`: overload #26 takes no trailing lambda"), trailing.rawReasons)
    assertEquals(listOf("0:weight#12", "0:weight#12.weight"), ((top.children[1] as DocNode.Widget).children.single() as DocNode.Widget).modifiers.keys.toList())
  }

  @Test fun outsideTheGrammarIsRawCodeWithItsReason() {
    fun why(call: String) = recognize(box(call)).rawReasons.single()
    assertTrue("is not a Color the ED0 value table reads" in why("Text(\"a\", color = Color(red = 1f, green = 0f, blue = 0f))"))
    assertTrue("is not a generated modifier" in why("Text(\"a\", Modifier.semantics {})"))
    assertTrue("`overflow` cannot cross to the host" in why("FlowRow(overflow = FlowRowOverflow.Visible) {}"))
    assertTrue("is not a `Modifier.…` chain" in why("Text(\"a\", modifier = someModifier)"))
  }

  /** A screen whose body is one Column holding [call], with the guest imports a test needs. */
  private fun box(call: String): String = """
    package app.screens

    import androidx.compose.runtime.Composable
    import dev.keliver.compose.foundation.background
    import dev.keliver.compose.foundation.layout.Box
    import dev.keliver.compose.foundation.layout.Column
    import dev.keliver.compose.foundation.layout.FlowRow
    import dev.keliver.compose.foundation.layout.PaddingValues
    import dev.keliver.compose.foundation.layout.Row
    import dev.keliver.compose.foundation.layout.padding
    import dev.keliver.compose.foundation.layout.size
    import dev.keliver.compose.foundation.shape.CircleShape
    import dev.keliver.compose.material.icons.Icons
    import dev.keliver.compose.material.icons.filled.Search
    import dev.keliver.compose.material3.Icon
    import dev.keliver.compose.material3.Text
    import dev.keliver.compose.ui.Alignment
    import dev.keliver.compose.ui.Modifier
    import dev.keliver.compose.ui.draw.clip
    import dev.keliver.compose.ui.graphics.Color
    import dev.keliver.compose.ui.unit.dp

    @Composable
    fun S(b: SBindings) {
      Column {
        $call
      }
    }

    interface SBindings
  """.trimIndent() + "\n"
}
