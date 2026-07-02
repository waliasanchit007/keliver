package dev.keliver.portaldevice

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.keliver.material.compose.StyledText
import dev.keliver.material.protocol.guest.KeliverMaterialProtocolWidgetSystemFactory
import dev.keliver.portal.deserializeTree
import dev.keliver.portal.render.RenderNode
import dev.keliver.treehouse.StandardAppLifecycle
import dev.keliver.treehouse.TreehouseUi
import dev.keliver.treehouse.ZiplineTreehouseUi
import dev.keliver.treehouse.asZiplineTreehouseUi
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json

// The Android emulator reaches the host machine's relay at 10.0.2.2.
private const val RELAY = "http://10.0.2.2:8077/tree"

class RealPortalPresenter(
  private val hostApi: HostApi,
  json: Json,
) : PortalPresenter {
  override val appLifecycle = StandardAppLifecycle(
    protocolWidgetSystemFactory = KeliverMaterialProtocolWidgetSystemFactory,
    json = json,
    widgetVersion = 1U,
  )

  override fun launch(): ZiplineTreehouseUi =
    PortalTreehouseUi(hostApi).asZiplineTreehouseUi(appLifecycle)
}

/** Polls the relay for the current tree and renders it with the shared RenderNode. */
private class PortalTreehouseUi(private val hostApi: HostApi) : TreehouseUi {
  @Composable
  override fun Show() {
    var json by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
      while (true) {
        json = runCatching { hostApi.httpCall(RELAY) }.getOrNull()
        delay(1000)
      }
    }
    val j = json
    if (j != null && j != "{}") {
      RenderNode(deserializeTree(j))
    } else {
      StyledText(text = "connecting to portal…", fontSize = 16)
    }
  }
}
