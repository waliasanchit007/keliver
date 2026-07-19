package dev.keliver.portal

/**
 * P2 preview fidelity for lists: resolve ONE mock row of a Repeat child
 * subtree. Item-scoped Binds ("note.title") become literal strings from
 * [mockOf] — pipe-separated per-row values ("First|Second"), clamped to the
 * last entry. Unmocked binds fall back to FIELD-AWARE defaults (P1-5): icon
 * props get valid icon names (never the ⓘ fallback glyph), image-ish fields
 * get a real placeholder URL, money/date/phone/email fields get plausible
 * values, and everything else gets a humanized "Title 1" style label.
 * Actions and screen binds pass through; a nested Repeat keeps its own scope.
 */
fun resolveItemRow(
  node: WidgetNode,
  itemVar: String,
  itemsField: String,
  index: Int,
  mockOf: (String) -> String?,
): WidgetNode {
  if (node.type == "Repeat") return node
  val props = node.props.mapValues { (key, v) ->
    if (v is Bind && v.field.startsWith("$itemVar.")) {
      // Namespace row content by the LIST field ("accountItems.item.title") so
      // several lists that reuse the same itemVar ("item") don't collide — the
      // single-list dogfood never hit this, but real screens (Stashfin Profile:
      // account/security/support) do. Fall back to the un-namespaced key for
      // editor per-item mocks and single-list back-compat.
      val raw = mockOf("$itemsField.${v.field}") ?: mockOf(v.field)
      val rows = raw?.split('|')?.map { it.trim() }?.filter { it.isNotEmpty() }
      if (rows.isNullOrEmpty()) defaultMock(v.field, key, node.type, index) else rows[minOf(index, rows.size - 1)]
    } else {
      v
    }
  }
  return node.copy(props = props, children = node.children.map { resolveItemRow(it, itemVar, itemsField, index, mockOf) })
}

private val MOCK_ICONS = listOf("Star", "Settings", "Notifications", "Person", "Info")

private fun defaultMock(field: String, propKey: String, widgetType: String, index: Int): String {
  val n = index + 1
  val name = field.substringAfterLast('.')
  val f = name.lowercase()
  val iconProp = propKey.contains("icon", ignoreCase = true) || (widgetType == "Icon" && propKey == "name")
  return when {
    iconProp -> MOCK_ICONS[index % MOCK_ICONS.size]
    f.contains("url") || f.contains("image") || f.contains("avatar") || f.contains("photo") ->
      "https://picsum.photos/seed/keliver$n/200"
    f.contains("amount") || f.contains("price") || f.contains("balance") || f.contains("total") -> "₹$n,250"
    f.contains("date") || f.contains("time") -> "1$n Jul 2026"
    f.contains("phone") || f.contains("mobile") -> "+91 98765 4321$n"
    f.contains("email") -> "user$n@example.com"
    else -> "${name.replaceFirstChar { it.uppercaseChar() }} $n"
  }
}
