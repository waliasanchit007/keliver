package dev.keliver.portal.ingest

import dev.keliver.portal.document.UiDocument
import dev.keliver.portal.document.toWidgetTree
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P2b-a: the first real-user papercut — an editor op inserted a Repeat bound to
 * `b.items`, the interface didn't declare it, and the guest compile went red.
 * ContractWriteBack must add the member (DEFAULTED, so presenters still
 * compile), append the item interface, and clean both up again when ops
 * un-require them — without ever touching hand-written members.
 */
class ContractWriteBackTest {
  // The screen ALREADY contains the new Repeat (as written by WriteBack),
  // but the interface predates it — exactly the broken intermediate state.
  private val file = """
    import androidx.compose.runtime.Composable
    import dev.keliver.layout.compose.Column
    import dev.keliver.material.compose.ListItem
    import dev.keliver.material.compose.StyledText

    @Composable
    fun MenuScreen(b: MenuScreenBindings) {
      Column {
        StyledText(text = b.title, fontSize = 16)
        b.items.forEach { item ->
          ListItem(headline = item.label, onClick = { b.open(item.route) })
        }
      }
    }

    interface MenuScreenBindings {
      val title: String
      fun handWritten()
    }
  """.trimIndent()

  private fun treeOf(src: String): dev.keliver.portal.WidgetNode {
    val rec = Recognizer.recognize("MenuScreen.kt", src)!!
    return UiDocument("m", rec.root, rec.contract, 0, 0).toWidgetTree()
  }

  @Test fun addsDefaultedMemberActionAndItemInterface() {
    val out = ContractWriteBack.ensure(file, treeOf(file), "MenuScreen")
    assertTrue("val items: List<Item> get() = emptyList()" in out, out)
    assertTrue("fun open(value: String) {}" in out, out)
    assertTrue("interface Item {" in out, out)
    assertTrue("val label: String" in out, out)
    assertTrue("val route: String" in out, out)
    assertTrue("TODO(portal)" in out, out)
    // Hand-written members untouched.
    assertTrue("val title: String" in out)
    assertTrue("fun handWritten()" in out)
    // Idempotent: a second pass changes nothing.
    assertEquals(out, ContractWriteBack.ensure(out, treeOf(out), "MenuScreen"))
  }

  @Test fun unimplementedMembersDrivesThePublishGate() {
    // A synced-but-unimplemented contract must be REJECTED by publish: the gate
    // reports each TODO(portal)-marked member so the author knows what to write.
    val synced = ContractWriteBack.ensure(file, treeOf(file), "MenuScreen")
    val unimpl = ContractWriteBack.unimplementedMembers(synced)
    assertTrue("val items: List<Item> get() = emptyList()" in unimpl, unimpl.toString())
    assertTrue("fun open(value: String) {}" in unimpl, unimpl.toString())
    // Hand-written members are never flagged.
    assertTrue(unimpl.none { "handWritten" in it || "val title" in it }, unimpl.toString())
    // A marker-free contract (the original fixture) is safe to ship.
    assertTrue(ContractWriteBack.unimplementedMembers(file).isEmpty())
  }

  @Test fun handNamedItemInterfacesAreNotDuplicated() {
    // The hand-written contract names its item type ProfileMenuEntry; the
    // exporter derives Item/Entry names from the itemVar. A synced file with
    // ONLY hand-written list members must gain nothing.
    val hand = """
      import androidx.compose.runtime.Composable
      import dev.keliver.layout.compose.Column
      import dev.keliver.material.compose.ListItem

      @Composable
      fun HandScreen(b: HandScreenBindings) {
        Column {
          b.rows.forEach { row ->
            ListItem(headline = row.name)
          }
        }
      }

      interface HandScreenBindings {
        val rows: List<MyRow>
      }

      interface MyRow {
        val name: String
      }
    """.trimIndent()
    assertEquals(hand, ContractWriteBack.ensure(hand, treeOf2(hand), "HandScreen"))
  }

  private fun treeOf2(src: String): dev.keliver.portal.WidgetNode {
    val rec = Recognizer.recognize("HandScreen.kt", src)!!
    return UiDocument("h", rec.root, rec.contract, 0, 0).toWidgetTree()
  }

  @Test fun componentInstanceGainsItsImport() {
    // P3-12 live-loop regression: a palette-inserted component call must gain
    // `import <pkg>.<Name>`; idempotent; same-package components need none.
    val spec = Recognizer.recognizeComponent(
      "MenuRow.kt",
      """
        package app.components

        import androidx.compose.runtime.Composable
        import dev.keliver.material.compose.ListItem
        @Composable
        fun MenuRow(title: String, onClick: () -> Unit) {
          ListItem(headline = title, onClick = onClick)
        }
      """.trimIndent(),
    )!!.spec
    val reg = dev.keliver.portal.MapComponentRegistry(listOf(spec))
    val screen = """
      package app.screens

      import androidx.compose.runtime.Composable
      import dev.keliver.layout.compose.Column

      @Composable
      fun HomeScreen(b: HomeScreenBindings) {
        Column {
          MenuRow(title = "T", onClick = { b.go() })
        }
      }

      interface HomeScreenBindings {
        fun go()
      }
    """.trimIndent()
    val out = ContractWriteBack.ensure(screen, treeOf3(screen, reg), "HomeScreen", reg)
    assertTrue("import app.components.MenuRow" in out, out)
    // Idempotent: no duplicate import on a second pass.
    val out2 = ContractWriteBack.ensure(out, treeOf3(out, reg), "HomeScreen", reg)
    assertEquals(out, out2)
  }

  private fun treeOf3(src: String, reg: dev.keliver.portal.ComponentRegistry): dev.keliver.portal.WidgetNode {
    val rec = Recognizer.recognize("HomeScreen.kt", src, reg)!!
    return UiDocument("h", rec.root, rec.contract, 0, 0).toWidgetTree()
  }

  @Test fun removesMarkedResidueWhenOpsUnrequireIt() {
    // Simulate undo: the file was synced while the Repeat existed; then ops
    // removed the Repeat — marked members + the marked item iface must go,
    // hand-written members must survive.
    val synced = ContractWriteBack.ensure(file, treeOf(file), "MenuScreen")
    val repeatBlock = "    b.items.forEach { item ->\n" +
      "      ListItem(headline = item.label, onClick = { b.open(item.route) })\n" +
      "    }\n"
    assertTrue(repeatBlock in synced, "test fixture must contain the repeat block:\n$synced")
    val syncedScreenRemoved = synced.replace(repeatBlock, "")
    val out = ContractWriteBack.ensure(syncedScreenRemoved, treeOf(syncedScreenRemoved), "MenuScreen")
    assertTrue("items" !in out.substringAfter("interface MenuScreenBindings"), out)
    assertTrue("interface Item" !in out, out)
    assertTrue("fun open" !in out, out)
    // Hand-written members survive.
    assertTrue("val title: String" in out)
    assertTrue("fun handWritten()" in out)
  }
}
