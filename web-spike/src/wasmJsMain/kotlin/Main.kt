/*
 * spike/keliver-web GATE 3 — the HOST build. A GENERIC web host: it fetches
 * screen.json (serialized protocol, produced by the separate guest build) at
 * runtime and renders it via the keliver protocol + ComposeUi. It has NO
 * compile-time reference to the screen — change the guest, regenerate
 * screen.json, reload → the web shows the new UI with this host unchanged.
 */
import androidx.compose.foundation.layout.Column as RawColumn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text as RawText
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.CanvasBasedWindow
import coil3.ImageLoader
import coil3.PlatformContext
import dev.keliver.leaks.LeakDetector
import dev.keliver.material.composeui.ComposeUiKeliverMaterialWidgetSystem
import dev.keliver.material.protocol.host.KeliverMaterialHostProtocol
import dev.keliver.protocol.SnapshotChangeList
import dev.keliver.protocol.guest.guestRedwoodVersion
import dev.keliver.protocol.host.HostProtocolAdapter
import dev.keliver.protocol.host.ProtocolMismatchHandler
import dev.keliver.protocol.host.UiChange
import dev.keliver.protocol.host.UiEventSink
import dev.keliver.widget.compose.ComposeWidgetChildren
import kotlinx.serialization.json.Json

private val JSON = Json { ignoreUnknownKeys = true }

/** Synchronous fetch of the served UI description (spike-grade; blocks briefly). */
private fun fetchTextSync(url: String): String =
  js("(function(){var x=new XMLHttpRequest();x.open('GET',url,false);x.send();return x.responseText;})()")

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
  CanvasBasedWindow(canvasElementId = "ComposeTarget") {
    val widgetSystem = remember { ComposeUiKeliverMaterialWidgetSystem(ImageLoader.Builder(PlatformContext.INSTANCE).build()) }
    val root = remember { ComposeWidgetChildren() }
    remember {
      val raw = fetchTextSync("screen.json")
      val hostProtocol = KeliverMaterialHostProtocol.Factory.create(JSON, ProtocolMismatchHandler.Throwing)
      val adapter = HostProtocolAdapter(
        guestVersion = guestRedwoodVersion,
        container = root,
        protocol = hostProtocol,
        widgetSystem = widgetSystem,
        eventSink = UiEventSink { },
        leakDetector = LeakDetector.none(),
      )
      val changes = JSON.decodeFromString(SnapshotChangeList.serializer(), raw).changes
        .mapNotNull { UiChange.fromProtocol(hostProtocol, it) }
      adapter.sendChanges(changes)
      println("keliver-web gate3 host: fetched ${raw.length} chars, applied ${changes.size} changes from screen.json — host never compiled against this screen")
      Unit
    }
    RawColumn(modifier = Modifier.padding(8.dp)) {
      RawText("generic keliver web host · UI fetched from screen.json at runtime", fontSize = 11.sp)
      root.Render()
    }
  }
}
