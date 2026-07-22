package dev.keliver.portal.document

import dev.keliver.portal.Action
import dev.keliver.portal.Bind
import dev.keliver.portal.WidgetNode
import dev.keliver.portal.resolveItemRow
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * P2 preview fidelity for lists: resolveItemRow rewrites ONE Repeat-child
 * subtree into a concrete mock row (lives in portal-core; tested here because
 * portal-render has no JVM target and this module already depends on core).
 */
class ItemMockTest {
  private val row = WidgetNode("Clickable", mapOf("onClick" to Action("openNote", "note.id")), listOf(
    WidgetNode("StyledText", mapOf("text" to Bind("note.title"), "fontSize" to 16)),
    WidgetNode("StyledText", mapOf("text" to Bind("subtitle"))), // screen bind: untouched
  ))

  @Test fun resolvesPipeSeparatedRowValuesAndClamps() {
    val mocks = mapOf("note.title" to "First|Second")
    val r0 = resolveItemRow(row, "note", "notes", 0, mocks::get)
    val r1 = resolveItemRow(row, "note", "notes", 1, mocks::get)
    val r2 = resolveItemRow(row, "note", "notes", 2, mocks::get)
    assertEquals("First", r0.children[0].props["text"])
    assertEquals("Second", r1.children[0].props["text"])
    assertEquals("Second", r2.children[0].props["text"]) // clamped to last
    assertEquals(Bind("subtitle"), r0.children[1].props["text"]) // screen binds untouched
    assertEquals(Action("openNote", "Id 1"), r0.props["onClick"])
    assertEquals(Action("openNote", "Id 2"), r1.props["onClick"])
  }

  @Test fun unmockedItemBindShowsHumanizedDefault() {
    val r = resolveItemRow(row, "note", "notes", 1) { null }
    assertEquals("Title 2", r.children[0].props["text"])
  }

  // P1-5: field-aware defaults — icon props must mock to VALID icon names
  // (never the ⓘ fallback), image fields to a real URL, money to a rupee value.
  @Test fun fieldAwareDefaultsForIconImageAndMoney() {
    val li = WidgetNode("ListItem", mapOf(
      "headline" to Bind("item.title"),
      "leadingIcon" to Bind("item.icon"),
    ))
    val r0 = resolveItemRow(li, "item", "items", 0) { null }
    assertEquals("Star", r0.props["leadingIcon"])
    assertEquals("Title 1", r0.props["headline"])
    val r1 = resolveItemRow(li, "item", "items", 1) { null }
    assertEquals("Settings", r1.props["leadingIcon"])

    val img = WidgetNode("AsyncImage", mapOf("url" to Bind("item.imageUrl")))
    assertEquals(
      "https://picsum.photos/seed/keliver1/200",
      resolveItemRow(img, "item", "items", 0) { null }.props["url"],
    )

    val amt = WidgetNode("StyledText", mapOf("text" to Bind("item.amount")))
    assertEquals("₹2,250", resolveItemRow(amt, "item", "items", 1) { null }.props["text"])
  }

  @Test fun nestedRepeatKeepsItsOwnScope() {
    val nested = WidgetNode("Repeat", mapOf("items" to "tags", "item" to "tag"),
      listOf(WidgetNode("StyledText", mapOf("text" to Bind("tag.name")))))
    val out = resolveItemRow(WidgetNode("Column", emptyMap(), listOf(nested)), "note", "notes", 0) { null }
    assertEquals(Bind("tag.name"), out.children[0].children[0].props["text"])
  }

  // Stashfin Profile surfaced this: account/security/support all iterate with
  // `forEach { item -> }`, so their rows share the itemVar "item". Rows are
  // namespaced by the LIST field so they stay distinct; the plain "item.title"
  // key is the shared fallback (editor per-item mocks / single-list back-compat).
  @Test fun listsSharingItemVarStayDistinctByFieldNamespace() {
    val li = WidgetNode("ListItem", mapOf("headline" to Bind("item.title")))
    val mocks = mapOf(
      "accountItems.item.title" to "Notifications",
      "securityItems.item.title" to "App Settings",
      "item.title" to "SHARED-FALLBACK",
    )
    assertEquals("Notifications", resolveItemRow(li, "item", "accountItems", 0, mocks::get).props["headline"])
    assertEquals("App Settings", resolveItemRow(li, "item", "securityItems", 0, mocks::get).props["headline"])
    // supportItems has no namespaced key → falls back to the plain key.
    assertEquals("SHARED-FALLBACK", resolveItemRow(li, "item", "supportItems", 0, mocks::get).props["headline"])
  }
}
