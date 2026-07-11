package dev.keliver.portal.ingest

import dev.keliver.portal.document.Contract
import dev.keliver.portal.document.DocNode
import dev.keliver.portal.document.Handle
import dev.keliver.portal.document.PropValue
import dev.keliver.portal.document.UiDocument
import dev.keliver.portal.document.lit
import dev.keliver.portal.document.toWidgetTree
import dev.keliver.portal.exportKotlin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RecognizerTest {
  private val screen = """
    import androidx.compose.runtime.Composable
    import dev.keliver.material.compose.Button
    import dev.keliver.material.compose.StyledBox
    import dev.keliver.material.compose.StyledText
    import dev.keliver.layout.compose.Column
    import dev.keliver.layout.compose.Spacer
    import dev.keliver.Modifier
    import dev.keliver.material.compose.padding
    import dev.keliver.material.compose.animateContentSize
    import dev.keliver.ui.Dp

    // human comment
    @Composable
    fun CheckoutScreen(b: CheckoutBindings) {
      StyledBox(
        cornerRadiusDp = 12,
        fillWidth = true,
        gradientColorsArgb = listOf(-2840, -5674),
        gradientStops = listOf(0.0f, 1.0f),
      ) {
        Column {
          StyledText(
            modifier = Modifier.padding(8).animateContentSize(),
            text = b.title,
            fontSize = 22,
          )
          if (b.title.length > 40) {
            StyledText(text = "long", fontSize = 9)
          }
          Spacer(height = Dp(12.0))
          Button(text = "Buy", onClick = b::buy)
        }
      }
    }

    interface CheckoutBindings {
      val title: String
      fun buy()
    }
  """.trimIndent()

  @Test fun goldenRecognize() {
    val r = Recognizer.recognize("CheckoutScreen.kt", screen)!!
    assertEquals("CheckoutScreen", r.screenName)
    assertEquals(Contract(fields = mapOf("title" to "String"), actions = listOf("buy")), r.contract)

    val box = r.root
    assertEquals("StyledBox", box.type)
    assertEquals(lit(12), box.props["cornerRadiusDp"])
    assertEquals(lit(true), box.props["fillWidth"])
    assertEquals(PropValue.Lit("li", li = listOf(-2840, -5674)), box.props["gradientColorsArgb"])
    assertEquals(PropValue.Lit("lf", lf = listOf(0.0f, 1.0f)), box.props["gradientStops"])

    val column = box.children[0] as DocNode.Widget
    val text = column.children[0] as DocNode.Widget
    assertEquals(PropValue.Bind("title"), text.props["text"])
    assertEquals(lit(22), text.props["fontSize"])
    assertEquals(lit(8), text.modifiers["Padding.allDp"])
    assertEquals(lit(true), text.modifiers["AnimateContentSize"])

    val raw = assertIs<DocNode.RawCode>(column.children[1])
    assertTrue(raw.text.startsWith("if (b.title.length > 40)"))
    assertEquals("condition", raw.kindHint)

    val spacer = column.children[2] as DocNode.Widget
    assertEquals(PropValue.Lit("d", d = 12.0), spacer.props["height"])

    val button = column.children[3] as DocNode.Widget
    assertEquals(PropValue.Action("buy"), button.props["onClick"])
  }

  /** THE M3 gate: the dual-written exporter output re-ingests to the same document. */
  @Test fun exporterRoundTrip() {
    val doc = UiDocument(
      screen = "main",
      root = DocNode.Widget(Handle(1), "StyledBox",
        props = mapOf("cornerRadiusDp" to lit(12), "fillWidth" to lit(true)),
        children = listOf(
          DocNode.Widget(Handle(2), "Column", children = listOf(
            DocNode.Widget(Handle(3), "StyledText",
              props = mapOf("text" to PropValue.Bind("title"), "fontSize" to lit(22)),
              modifiers = mapOf("Padding.allDp" to lit(8))),
            DocNode.Widget(Handle(4), "Button",
              props = mapOf("text" to lit("Buy"), "onClick" to PropValue.Action("buy"))),
            DocNode.Widget(Handle(5), "Spacer", props = mapOf("height" to PropValue.Lit("d", d = 12.0))),
          )),
        )),
      contract = Contract(fields = mapOf("title" to "String"), actions = listOf("buy")),
      version = 7, nextHandle = 10,
    )
    val exported = exportKotlin(doc.toWidgetTree(), functionName = "PortalScreen")
    val r = Recognizer.recognize("main.kt", exported)!!
    val reconciled = Reconciler.reconcile(doc, r)

    // Same semantic content, version bumped, HANDLES PRESERVED.
    assertEquals(doc.root, reconciled.root)
    assertEquals(doc.contract, reconciled.contract)
    assertEquals(doc.version + 1, reconciled.version)
  }

  @Test fun recognizesConditionAndRepeatAsEditableNodes() {
    val src = """
      import androidx.compose.runtime.Composable
      import dev.keliver.material.compose.StyledText
      import dev.keliver.layout.compose.Column

      @Composable
      fun S(b: SBindings) {
        Column {
          if (b.loggedIn) {
            StyledText(text = "Welcome")
          }
          b.rows.forEach { row ->
            StyledText(text = "item")
          }
        }
      }

      interface SBindings {
        val loggedIn: Boolean
        val rows: List<String>
      }
    """.trimIndent()
    val r = Recognizer.recognize("S.kt", src)!!
    val col = r.root
    val cond = col.children[0] as DocNode.Widget
    assertEquals("Condition", cond.type)
    assertEquals(PropValue.Lit("s", s = "loggedIn"), cond.props["field"])
    assertEquals("StyledText", (cond.children[0] as DocNode.Widget).type)
    val rep = col.children[1] as DocNode.Widget
    assertEquals("Repeat", rep.type)
    assertEquals(PropValue.Lit("s", s = "rows"), rep.props["items"])
    assertEquals(PropValue.Lit("s", s = "row"), rep.props["item"])
  }

  @Test fun perItemRepeatBindingRoundTrips() {
    val src = """
      import androidx.compose.runtime.Composable
      import dev.keliver.layout.compose.Column
      import dev.keliver.material.compose.StyledText
      @Composable
      fun Feed(b: FeedBindings) {
        Column {
          b.posts.forEach { post ->
            StyledText(text = post.title, fontSize = 18)
          }
        }
      }
      interface FeedBindings {
        val posts: List<Post>
      }
      interface Post {
        val title: String
      }
    """.trimIndent()
    val r = Recognizer.recognize("Feed.kt", src)!!
    val rep = (r.root.children[0] as DocNode.Widget) // Repeat
    assertEquals("Repeat", rep.type)
    val text = rep.children[0] as DocNode.Widget
    // the loop-var reference became an item-scoped bind
    assertEquals(PropValue.Bind("post.title"), text.props["text"])

    // Export: real forEach with item.title + a typed item interface.
    val doc = UiDocument("main", r.root, r.contract, 0, 100)
    val exported = exportKotlin(doc.toWidgetTree(), functionName = "Feed")
    assertTrue("b.posts.forEach { post ->" in exported, exported)
    assertTrue("text = post.title," in exported, exported)          // item-scoped, NOT b.post.title
    assertTrue("val posts: List<Post>" in exported)
    assertTrue("interface Post {" in exported)
    assertTrue("val title: String" in exported)

    // Round-trips back to an equal document.
    val r2 = Recognizer.recognize("Feed.kt", exported)!!
    val re = Reconciler.reconcile(doc, r2)
    assertEquals(doc.root, re.root)
  }

  @Test fun conditionRepeatRoundTripThroughExport() {
    val doc = UiDocument(
      screen = "main",
      root = DocNode.Widget(Handle(1), "Column", children = listOf(
        DocNode.Widget(Handle(2), "Condition", mapOf("field" to PropValue.Lit("s", s = "showBanner")),
          children = listOf(DocNode.Widget(Handle(3), "StyledText", mapOf("text" to lit("Hi"))))),
        DocNode.Widget(Handle(4), "Repeat",
          mapOf("items" to PropValue.Lit("s", s = "cards"), "item" to PropValue.Lit("s", s = "card")),
          children = listOf(DocNode.Widget(Handle(5), "StyledText", mapOf("text" to lit("row"))))),
      )),
      contract = Contract(), version = 0, nextHandle = 10,
    )
    val exported = exportKotlin(doc.toWidgetTree(), functionName = "S")
    // Real control flow in the output + a typed contract.
    assertTrue("if (b.showBanner) {" in exported, exported)
    assertTrue("b.cards.forEach { card ->" in exported, exported)
    assertTrue("val showBanner: Boolean" in exported)
    assertTrue("val cards: List<String>" in exported)
    // Round-trips back to the same document.
    val r = Recognizer.recognize("S.kt", exported)!!
    val re = Reconciler.reconcile(doc, r)
    assertEquals(doc.root, re.root)
  }

  /**
   * P2: single-arg action lambdas — `{ b.onX(it) }` carries the event payload,
   * `{ b.onX(item.field) }` carries item-scoped data (e.g. a row id). Both must
   * recognize (no RawCode), export byte-identically, contribute typed action
   * signatures, and feed item interfaces.
   */
  @Test fun singleArgActionEventsRoundTrip() {
    val src = """
      import androidx.compose.runtime.Composable
      import dev.keliver.layout.compose.Column
      import dev.keliver.material.compose.Clickable
      import dev.keliver.material.compose.StyledText
      import dev.keliver.material.compose.TextField

      @Composable
      fun FormScreen(b: FormScreenBindings) {
        Column {
          TextField(text = b.draft, onValueChange = { b.onDraftChange(it) })
          b.notes.forEach { note ->
            Clickable(onClick = { b.openNote(note.id) }) {
              StyledText(text = note.title, fontSize = 16)
            }
          }
        }
      }

      interface FormScreenBindings {
        val draft: String
        val notes: List<Note>
        fun onDraftChange(value: String)
        fun openNote(value: String)
      }

      interface Note {
        val id: String
        val title: String
      }
    """.trimIndent()

    val r = Recognizer.recognize("FormScreen.kt", src)!!
    fun walk(n: DocNode): List<DocNode> = when (n) {
      is DocNode.Widget -> listOf(n) + n.children.flatMap { walk(it) }
      else -> listOf(n)
    }
    val all = walk(r.root)
    assertTrue(all.none { it is DocNode.RawCode }, all.filterIsInstance<DocNode.RawCode>().toString())

    val tf = all.filterIsInstance<DocNode.Widget>().first { it.type == "TextField" }
    assertEquals(PropValue.Action("onDraftChange", arg = "it"), tf.props["onValueChange"])
    val click = all.filterIsInstance<DocNode.Widget>().first { it.type == "Clickable" }
    assertEquals(PropValue.Action("openNote", arg = "note.id"), click.props["onClick"])
    assertEquals("String", r.contract.actionParams["onDraftChange"])

    val doc = UiDocument("form", r.root, r.contract, version = 0, nextHandle = 100)
    val exported = exportKotlin(doc.toWidgetTree(), functionName = "FormScreen")
    assertTrue("onValueChange = { b.onDraftChange(it) }" in exported, exported)
    assertTrue("onClick = { b.openNote(note.id) }" in exported, exported)
    assertTrue("fun onDraftChange(value: String)" in exported, exported)
    assertTrue("fun openNote(value: String)" in exported, exported)
    assertTrue("val id: String" in exported, exported)

    val r2 = Recognizer.recognize("FormScreen.kt", exported)!!
    assertEquals(doc.root, Reconciler.reconcile(doc, r2).root)
  }

  /**
   * DOGFOOD (Field Notes): the real screen shipped in portal-app-lib must be
   * FULLY portal-recognized — the "no escape hatches" contract means every node
   * ingests as a Widget (zero RawCode), the per-item Repeat binds resolve, the
   * empty-state is a Condition, and the contract is exactly what the presenter
   * implements. This locks the dogfood app against recognizer regressions.
   */
  @Test fun recognizesDogfoodFeedScreenWithNoRawCode() {
    val src = """
      import androidx.compose.runtime.Composable
      import dev.keliver.Modifier
      import dev.keliver.layout.compose.Column
      import dev.keliver.layout.compose.Spacer
      import dev.keliver.material.compose.Button
      import dev.keliver.material.compose.Card
      import dev.keliver.material.compose.Clickable
      import dev.keliver.material.compose.Divider
      import dev.keliver.material.compose.ScrollableColumn
      import dev.keliver.material.compose.StyledBox
      import dev.keliver.material.compose.StyledText
      import dev.keliver.material.compose.TextButton
      import dev.keliver.material.compose.TextField
      import dev.keliver.material.compose.padding
      import dev.keliver.ui.Dp

      @Composable
      fun FeedScreen(b: FeedScreenBindings) {
        StyledBox(paddingDp = 20, fillWidth = true) {
          ScrollableColumn {
            StyledText(text = "Field Notes", fontSize = 28, bold = true)
            StyledText(text = b.subtitle, fontSize = 13)
            Spacer(height = Dp(14.0))
            TextField(text = b.draft, placeholder = "What did you notice?", onValueChange = { b.onDraftChange(it) })
            Button(text = "Add note", onClick = { b.addNote() })
            if (b.isEmpty) {
              StyledText(text = "No notes yet.", fontSize = 14)
            }
            b.notes.forEach { note ->
              Clickable(onClick = { b.openNote(note.id) }) {
                Card {
                  Column {
                    StyledText(modifier = Modifier.padding(4), text = note.title, fontSize = 17, bold = true)
                    StyledText(modifier = Modifier.padding(4), text = note.body, fontSize = 14)
                    StyledText(modifier = Modifier.padding(4), text = note.time, fontSize = 11)
                  }
                }
              }
              Spacer(height = Dp(10.0))
            }
            Divider()
            TextButton(text = "Clear all", onClick = { b.clearAll() })
          }
        }
      }

      interface FeedScreenBindings {
        val subtitle: String
        val draft: String
        val isEmpty: Boolean
        val notes: List<Note>
        fun onDraftChange(value: String)
        fun addNote()
        fun clearAll()
        fun openNote(value: String)
      }

      interface Note {
        val id: String
        val title: String
        val body: String
        val time: String
      }
    """.trimIndent()

    val r = Recognizer.recognize("feed.kt", src)!!

    // No escape hatches: every node in the tree is a recognized Widget.
    fun walk(n: DocNode): List<DocNode> = when (n) {
      is DocNode.Widget -> listOf(n) + n.children.flatMap { walk(it) }
      else -> listOf(n)
    }
    val all = walk(r.root)
    assertTrue(all.none { it is DocNode.RawCode }, "expected zero RawCode, got: " + all.filterIsInstance<DocNode.RawCode>())

    // The Condition empty-state and the per-item Repeat feed both recognized.
    val types = all.filterIsInstance<DocNode.Widget>().map { it.type }
    assertTrue("Condition" in types, types.toString())
    val repeat = all.filterIsInstance<DocNode.Widget>().first { it.type == "Repeat" }
    assertEquals(PropValue.Lit("s", s = "notes"), repeat.props["items"])
    assertEquals(PropValue.Lit("s", s = "note"), repeat.props["item"])
    // Per-item binds (P1-B) + the item-carrying action arg (P2) inside the Repeat.
    val repeatWidgets = walk(repeat).filterIsInstance<DocNode.Widget>()
    val itemBinds = repeatWidgets
      .flatMap { it.props.values }.filterIsInstance<PropValue.Bind>().map { it.field }.toSet()
    assertEquals(setOf("note.title", "note.body", "note.time"), itemBinds)
    val itemActionArgs = repeatWidgets
      .flatMap { it.props.values }.filterIsInstance<PropValue.Action>().mapNotNull { it.arg }.toSet()
    assertEquals(setOf("note.id"), itemActionArgs)

    // P2 single-arg actions: typed text entry + the tapped row's id.
    val tf = all.filterIsInstance<DocNode.Widget>().first { it.type == "TextField" }
    assertEquals(PropValue.Action("onDraftChange", arg = "it"), tf.props["onValueChange"])
    val click = all.filterIsInstance<DocNode.Widget>().first { it.type == "Clickable" }
    assertEquals(PropValue.Action("openNote", arg = "note.id"), click.props["onClick"])

    // Contract is exactly what FeedPresenter implements.
    assertEquals(setOf("subtitle", "draft", "isEmpty", "notes"), r.contract.fields.keys)
    assertEquals(setOf("onDraftChange", "addNote", "clearAll", "openNote"), r.contract.actions.toSet())
    assertEquals("String", r.contract.actionParams["onDraftChange"])
    assertEquals("String", r.contract.actionParams["openNote"])
  }

  /** P4 STRING_LIST: options lists recognize + export round-trip (Dropdown/Segmented). */
  @Test fun stringListOptionsRoundTrip() {
    val src = """
      import androidx.compose.runtime.Composable
      import dev.keliver.layout.compose.Column
      import dev.keliver.material.compose.SegmentedButtonRow

      @Composable
      fun S(b: SBindings) {
        Column {
          SegmentedButtonRow(options = listOf("Day", "Week", "Month"), selectedIndex = 1, onSelect = { b.onRangeSelect(it) })
        }
      }

      interface SBindings {
        fun onRangeSelect(value: Int)
      }
    """.trimIndent()
    val r = Recognizer.recognize("S.kt", src)!!
    val seg = (r.root as DocNode.Widget).children[0] as DocNode.Widget
    assertEquals("SegmentedButtonRow", seg.type)
    assertEquals(PropValue.Lit("ls", ls = listOf("Day", "Week", "Month")), seg.props["options"])
    assertEquals(PropValue.Action("onRangeSelect", arg = "it"), seg.props["onSelect"])

    val doc = UiDocument("s", r.root, r.contract, version = 0, nextHandle = 50)
    val exported = exportKotlin(doc.toWidgetTree(), functionName = "S")
    assertTrue("options = listOf(\"Day\", \"Week\", \"Month\")," in exported, exported)
    assertTrue("fun onRangeSelect(value: Int)" in exported, exported)
    val r2 = Recognizer.recognize("S.kt", exported)!!
    assertEquals(doc.root, Reconciler.reconcile(doc, r2).root)
  }

  @Test fun reconcilerPreservesHandlesAcrossEditsAndAllocatesNew() {
    val old = UiDocument(
      screen = "main",
      root = DocNode.Widget(Handle(1), "Column", children = listOf(
        DocNode.Widget(Handle(2), "StyledText", mapOf("text" to lit("a"))),
        DocNode.Widget(Handle(3), "Button", mapOf("text" to lit("Buy"))),
      )),
      contract = Contract(), version = 5, nextHandle = 10,
    )
    // parsed: text edited, a Spacer inserted between, button kept
    val parsed = Recognized(
      root = DocNode.Widget(Handle(-1), "Column", children = listOf(
        DocNode.Widget(Handle(-2), "StyledText", mapOf("text" to lit("EDITED"))),
        DocNode.Widget(Handle(-3), "Spacer", mapOf("height" to PropValue.Lit("d", d = 8.0))),
        DocNode.Widget(Handle(-4), "Button", mapOf("text" to lit("Buy"))),
      )),
      contract = Contract(),
      screenName = "PortalScreen",
    )
    val out = Reconciler.reconcile(old, parsed)
    val root = out.root as DocNode.Widget
    assertEquals(Handle(1), root.handle)
    assertEquals(Handle(2), root.children[0].handle)            // matched: kept
    assertEquals(lit("EDITED"), (root.children[0] as DocNode.Widget).props["text"])
    assertEquals(Handle(10), root.children[1].handle)           // new: allocated
    assertEquals(Handle(3), root.children[2].handle)            // matched: kept
    assertEquals(11, out.nextHandle)
  }
}
