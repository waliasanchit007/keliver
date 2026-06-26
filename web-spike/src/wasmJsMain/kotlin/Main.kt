/*
 * spike/keliver-web GATE 4 — web HOT-RELOAD. The generic host watches
 * screen.json and, whenever the guest regenerates it, rebuilds the widget tree
 * from the new protocol and re-renders — with NO manual reload and NO host
 * rebuild. This is the mobile `serveDevelopmentZipline` hot-reload loop, on web.
 */
import androidx.compose.foundation.layout.Column as RawColumn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text as RawText
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json

private val JSON = Json { ignoreUnknownKeys = true }

/** Synchronous fetch (spike-grade). The cache-busting query forces a fresh read. */
private fun fetchTextSync(url: String): String =
  js("(function(){var x=new XMLHttpRequest();x.open('GET',url,false);x.send();return x.responseText;})()")

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
  CanvasBasedWindow(canvasElementId = "ComposeTarget") {
    val widgetSystem = remember { ComposeUiKeliverMaterialWidgetSystem(ImageLoader.Builder(PlatformContext.INSTANCE).build()) }
    var screenJson by remember { mutableStateOf("") }
    var reloads by remember { mutableStateOf(0) }

    // Watch screen.json; update state whenever the guest regenerates it.
    LaunchedEffect(Unit) {
      var poll = 0
      while (true) {
        val latest = runCatching { fetchTextSync("screen.json?p=${poll++}") }.getOrNull().orEmpty()
        if (latest.isNotEmpty() && latest != screenJson) {
          screenJson = latest
          reloads++
          println("keliver-web gate4 hot-reload #$reloads — applied ${latest.length} chars (no host rebuild, no manual reload)")
        }
        delay(800)
      }
    }

    RawColumn(modifier = Modifier.padding(8.dp)) {
      RawText("generic keliver web host · hot-reloading from screen.json · updates=$reloads", fontSize = 11.sp)
      if (screenJson.isNotEmpty()) {
        // Rebuild the whole widget tree from the latest protocol snapshot.
        val root = remember(screenJson) {
          val r = ComposeWidgetChildren()
          val hostProtocol = KeliverMaterialHostProtocol.Factory.create(JSON, ProtocolMismatchHandler.Throwing)
          val adapter = HostProtocolAdapter(
            guestVersion = guestRedwoodVersion,
            container = r,
            protocol = hostProtocol,
            widgetSystem = widgetSystem,
            eventSink = UiEventSink { },
            leakDetector = LeakDetector.none(),
          )
          val changes = JSON.decodeFromString(SnapshotChangeList.serializer(), screenJson).changes
            .mapNotNull { UiChange.fromProtocol(hostProtocol, it) }
          adapter.sendChanges(changes)
          r
        }
        root.Render()
      } else {
        RawText("Loading UI from server…", fontSize = 13.sp)
      }
    }
  }
}
