package dev.keliver.portal.ingest

import dev.keliver.portal.document.DocNode
import dev.keliver.portal.document.UiDocument
import java.io.File

/**
 * ED0 spike fixtures: the generated catalog (Gate A's group, as `keliver-compose-generator` writes
 * it) and, for a slot shape the group does not have (one required and four optional slots), a
 * Material 3 `ListItem` described the way the generator describes a widget. The group's own
 * `TopAppBar` has the other shape (a required title, optional slots, one scoped).
 */
internal object ComposeFixtures {
  val gateAJson: String by lazy { File(System.getProperty("dev.keliver.portal.ed0.editorJson")).readText() }

  /** `ListItem` (one required, four optional slots). */
  const val SLOT_WIDGETS: String = """
    {"group": "ed0-slots", "widgets": [
      {"name": "ListItem", "compose": "androidx.compose.material3.ListItem", "guest": "dev.keliver.compose.material3.ListItem", "palette": "androidx.compose.material3", "tag": 101, "schemaClass": "GenListItem1", "params": [
        {"name": "headlineContent", "kind": "slot"},
        {"name": "modifier", "kind": "modifier"},
        {"name": "overlineContent", "kind": "slot", "optional": true, "default": "null", "defaultCase": "none"},
        {"name": "supportingContent", "kind": "slot", "optional": true, "default": "null", "defaultCase": "none"},
        {"name": "leadingContent", "kind": "slot", "optional": true, "default": "null", "defaultCase": "none"},
        {"name": "trailingContent", "kind": "slot", "optional": true, "default": "null", "defaultCase": "none"},
        {"name": "tonalElevation", "kind": "dp", "type": "dev.keliver.compose.ui.unit.Dp", "default": "ListItemDefaults.Elevation", "defaultCase": "expression"}
      ]}
    ], "modifiers": []}
  """

  val catalog: ComposeCatalog by lazy { ComposeCatalog.parse(gateAJson, SLOT_WIDGETS) }

  val sampleCard: String by lazy { File(System.getProperty("dev.keliver.portal.ed0.sample")).readText() }

  fun <T> ed0(block: () -> T): T = ComposeIngest.with(catalog, block)

  fun recognize(source: String, file: String = "app/screens/InboxScreen.kt", asComponent: Boolean = false): Recognized =
    ed0 { Recognizer.recognize(file, source, asComponent = asComponent) } ?: error("no screen")

  fun doc(source: String, file: String = "app/screens/InboxScreen.kt", asComponent: Boolean = false): UiDocument =
    recognize(source, file, asComponent).let { UiDocument("inbox", it.root, it.contract, 0, 1000) }

  fun raws(n: DocNode): List<DocNode.RawCode> = when (n) {
    is DocNode.RawCode -> listOf(n)
    is DocNode.Widget -> n.children.flatMap(::raws)
  }

  /** Every widget node, depth first. */
  fun widgets(n: DocNode): List<DocNode.Widget> = when (n) {
    is DocNode.RawCode -> emptyList()
    is DocNode.Widget -> listOf(n) + n.children.flatMap(::widgets)
  }

  /** A Style B screen in Compose's shape, the guest imports only. */
  val INBOX_SCREEN: String = """
    package app.screens

    import androidx.compose.runtime.Composable
    import dev.keliver.compose.foundation.background
    import dev.keliver.compose.foundation.layout.Arrangement
    import dev.keliver.compose.foundation.layout.Column
    import dev.keliver.compose.foundation.layout.Row
    import dev.keliver.compose.foundation.layout.fillMaxWidth
    import dev.keliver.compose.foundation.layout.padding
    import dev.keliver.compose.foundation.shape.RoundedCornerShape
    import dev.keliver.compose.material3.MaterialTheme
    import dev.keliver.compose.material3.Text
    import dev.keliver.compose.ui.Alignment
    import dev.keliver.compose.ui.Modifier
    import dev.keliver.compose.ui.graphics.Color
    import dev.keliver.compose.ui.text.font.FontWeight
    import dev.keliver.compose.ui.unit.dp
    import dev.keliver.compose.ui.unit.sp

    @Composable
    fun InboxScreen(b: InboxScreenBindings) {
      Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // The header row.
        Row(
          modifier = Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(b.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
          Text(b.unread, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        }
        Text("Recent messages", fontSize = 16.sp, color = Color(0xFF1F1F1F), maxLines = 1)
      }
    }

    interface InboxScreenBindings {
      val title: String
      val unread: String
    }
  """.trimIndent() + "\n"
}
