/*
 * The palette's preview (owner, 25 Oct): hovering a palette row shows what it adds, drawn by the
 * canvas's own renderer — a component with its sample values, a widget as it is inserted — in a
 * small second canvas beside the palette. No guess at what a name looks like before dropping it.
 */
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeViewport
import dev.keliver.portal.WidgetNode
import dev.keliver.portal.render.AppPreviewEntry
import dev.keliver.portal.render.RenderNode
import dev.keliver.widget.compose.ComposeWidgetChildren

/** The node the palette preview draws; null while no row is hovered. */
internal val palettePreview = mutableStateOf<WidgetNode?>(null)

/** The element the preview canvas renders into (inside the palette's preview card). */
internal const val PALETTE_PREVIEW_HOST_ID = "PalettePreviewHost"

/**
 * [node] as the preview draws it: an empty container gets [samples] children (a Column of nothing
 * draws nothing — three items show how it lays them out), and every node an id below zero — the
 * preview's nodes are no document's, and never share a handle with the canvas's selection boxes.
 */
internal fun previewOf(node: WidgetNode, acceptsChildren: Boolean, samples: Int = 3): WidgetNode {
  val filled = if (acceptsChildren && node.children.isEmpty()) {
    node.copy(children = (1..samples).map { WidgetNode("StyledText", mapOf("text" to "Item $it")) })
  } else {
    node
  }
  var next = -1_000_000
  fun renumber(n: WidgetNode): WidgetNode = n.copy(id = next--, children = n.children.map(::renumber))
  return renumber(filled)
}

/** Mounts the preview canvas into [PALETTE_PREVIEW_HOST_ID] (once, after the editor's own canvas). */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
internal fun mountPalettePreview(entry: AppPreviewEntry) {
  val host = kotlinx.browser.document.getElementById(PALETTE_PREVIEW_HOST_ID) ?: return
  ComposeViewport(viewportContainer = host) {
    val widgetSystem = remember { editorWidgetSystem() }
    val root = remember { ComposeWidgetChildren() }
    LaunchedEffect(Unit) {
      runGuest(root, widgetSystem, onError = { _: Throwable -> palettePreview.value = null }) {
        dev.keliver.portal.render.AppScreenFrame(entry) { palettePreview.value?.let { RenderNode(it) } }
      }
    }
    // Centred: a small widget alone in a corner of the card read as missing.
    val content: @Composable () -> Unit = {
      Box(Modifier.fillMaxSize().padding(12.dp), contentAlignment = androidx.compose.ui.Alignment.Center) { root.Render() }
    }
    val wrapper = entry.hostWrapper
    if (wrapper != null) wrapper.Wrap { content() } else content()
  }
}
