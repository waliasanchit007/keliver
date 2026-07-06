package dev.keliver.portaldevice

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.keliver.layout.api.Constraint
import dev.keliver.layout.api.CrossAxisAlignment
import dev.keliver.layout.compose.Column
import dev.keliver.material.compose.StyledBox
import dev.keliver.material.compose.StyledText
import dev.keliver.material.protocol.guest.KeliverMaterialProtocolWidgetSystemFactory
import dev.keliver.portal.deserializeTree
import dev.keliver.portal.render.RenderNode
import dev.keliver.portal.sql.HostSqlDriver
import dev.keliver.portalpublished.PublishedEntry
import dev.keliver.portalpublished.screens.COMPILED_VERSION_main
import dev.keliver.treehouse.StandardAppLifecycle
import dev.keliver.treehouse.TreehouseUi
import dev.keliver.treehouse.ZiplineTreehouseUi
import dev.keliver.treehouse.asZiplineTreehouseUi
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json

// The Android emulator reaches the host machine's relay at 10.0.2.2.
private const val TREE = "http://10.0.2.2:8077/tree"
private const val DEVSTATE = "http://10.0.2.2:8077/devstate"

class RealPortalPresenter(
  private val hostApi: HostApi,
  private val sql: HostSqlDriver?, // M9: the compiled screen's data layer (bound in dev too)
  json: Json,
) : PortalPresenter {
  override val appLifecycle = StandardAppLifecycle(
    protocolWidgetSystemFactory = KeliverMaterialProtocolWidgetSystemFactory,
    json = json,
    widgetVersion = 1U,
  )

  override fun launch(): ZiplineTreehouseUi =
    OverlayTreehouseUi(hostApi, sql).asZiplineTreehouseUi(appLifecycle)
}

/**
 * V2 M9 — the OVERLAY dev runtime (design §10). The COMPILED screen
 * ([PublishedEntry]) is always primary. When the live doc version of the
 * active screen is AHEAD of the version this bundle was compiled from, we
 * overlay the interpreter (RenderNode over the live tree) for instant (~1s)
 * feedback. Once the dev bundle recompiles (serve --continuous) and its baked
 * [COMPILED_VERSION_main] catches up, the overlay is auto-discarded. One
 * Zipline instance; "overlay" is purely a routing decision.
 */
private class OverlayTreehouseUi(
  private val hostApi: HostApi,
  private val sql: HostSqlDriver?,
) : TreehouseUi {
  @Composable
  override fun Show() {
    var liveVersion by remember { mutableStateOf(-1) }
    var treeJson by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
      while (true) {
        liveVersion = runCatching { hostApi.httpCall(DEVSTATE) }.getOrNull()
          ?.let { Regex("\"version\":(\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: liveVersion
        treeJson = runCatching { hostApi.httpCall(TREE) }.getOrNull()
        delay(1000)
      }
    }

    val overlaying = liveVersion > COMPILED_VERSION_main
    if (overlaying && treeJson != null && treeJson != "{}") {
      // Fast interpreter overlay for the screen being edited.
      Column(width = Constraint.Fill, horizontalAlignment = CrossAxisAlignment.Stretch) {
        StyledBox(fillWidth = true, colorArgb = 0xFF3A2E00.toInt(), paddingDp = 6) {
          StyledText(
            text = "⚡ live overlay — doc v$liveVersion (compiled v$COMPILED_VERSION_main, catching up…)",
            fontSize = 11,
            colorArgb = 0xFFFFD770.toInt(),
          )
        }
        RenderNode(deserializeTree(treeJson!!))
      }
    } else {
      // Caught up (or never edited) → the REAL compiled screen + logic.
      PublishedEntry(sql)
    }
  }
}
