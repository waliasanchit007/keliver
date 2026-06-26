/*
 * spike/keliver-web — GATE 2: run a guest screen through the keliver PROTOCOL in
 * the browser. The guest composition emits serialized ProtocolChanges; that JSON
 * is the wire format. A host — which never compiled against this screen — then
 * deserializes the JSON and renders it via the ComposeUi widget system.
 * This is "push Kotlin → live on web" with the same protocol used on mobile.
 */
import androidx.compose.foundation.layout.Column as RawColumn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text as RawText
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.CanvasBasedWindow
import coil3.ImageLoader
import coil3.PlatformContext
import dev.keliver.layout.api.Constraint
import dev.keliver.layout.api.CrossAxisAlignment
import dev.keliver.layout.compose.Column
import dev.keliver.layout.compose.Spacer
import dev.keliver.leaks.LeakDetector
import dev.keliver.material.api.TextSpan
import dev.keliver.material.compose.AnimatedBorder
import dev.keliver.material.compose.RichText
import dev.keliver.material.compose.StyledBox
import dev.keliver.material.compose.StyledText
import dev.keliver.material.composeui.ComposeUiKeliverMaterialWidgetSystem
import dev.keliver.material.protocol.guest.KeliverMaterialProtocolWidgetSystemFactory
import dev.keliver.material.protocol.host.KeliverMaterialHostProtocol
import dev.keliver.protocol.SnapshotChangeList
import dev.keliver.protocol.guest.DefaultGuestProtocolAdapter
import dev.keliver.protocol.guest.guestRedwoodVersion
import dev.keliver.protocol.host.HostProtocolAdapter
import dev.keliver.protocol.host.ProtocolMismatchHandler
import dev.keliver.protocol.host.UiChange
import dev.keliver.protocol.host.UiEventSink
import dev.keliver.testing.TestRedwoodComposition
import dev.keliver.ui.Dp
import dev.keliver.widget.compose.ComposeWidgetChildren
import androidx.compose.runtime.BroadcastFrameClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.Json

private const val WHITE = 0xFFFFFFFF.toInt()
private const val BLACK = 0xFF111111.toInt()
private const val BORDER = 0xFFFAD4D4.toInt()
private val CARD = listOf(0xFFFFFCFC.toInt(), 0xFFFFEEEE.toInt(), 0xFFFFF3EE.toInt())
private val CARD_STOPS = listOf(0.0f, 0.55f, 1.0f)
private val RED = listOf(0xFFFF3E3E.toInt(), 0xFFFF2D4E.toInt(), 0xFFFF552B.toInt())

private val JSON = Json { ignoreUnknownKeys = true }

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
  // ── GUEST: compose the screen and emit serialized protocol changes ──
  val guestAdapter = DefaultGuestProtocolAdapter(
    hostVersion = guestRedwoodVersion,
    widgetSystemFactory = KeliverMaterialProtocolWidgetSystemFactory,
  )
  val guestScope = CoroutineScope(BroadcastFrameClock())
  val tester = TestRedwoodComposition(
    scope = guestScope,
    widgetSystem = guestAdapter.widgetSystem,
    container = guestAdapter.root,
    createSnapshot = { guestAdapter.takeChanges() },
  )
  val changes = tester.setContentAndSnapshot { MiniNudge() }
  val wireJson = JSON.encodeToString(SnapshotChangeList.serializer(), SnapshotChangeList(changes))
  println("keliver-web gate2 — serialized ${wireJson.length} chars of protocol JSON for ${changes.size} changes")

  // ── HOST: a fresh host, never compiled against MiniNudge, renders the JSON ──
  val imageLoader = ImageLoader.Builder(PlatformContext.INSTANCE).build()
  val hostWidgetSystem = ComposeUiKeliverMaterialWidgetSystem(imageLoader)
  val root = ComposeWidgetChildren()
  val hostProtocol = KeliverMaterialHostProtocol.Factory.create(JSON, ProtocolMismatchHandler.Throwing)
  val hostAdapter = HostProtocolAdapter(
    guestVersion = guestRedwoodVersion,
    container = root,
    protocol = hostProtocol,
    widgetSystem = hostWidgetSystem,
    eventSink = UiEventSink { },
    leakDetector = LeakDetector.none(),
  )
  val decoded = JSON.decodeFromString(SnapshotChangeList.serializer(), wireJson)
  val uiChanges = decoded.changes.mapNotNull { UiChange.fromProtocol(hostProtocol, it) }
  hostAdapter.sendChanges(uiChanges)

  CanvasBasedWindow(canvasElementId = "ComposeTarget") {
    RawColumn(modifier = Modifier.padding(8.dp)) {
      RawText("⬇ rendered from ${wireJson.length} chars of serialized protocol JSON", fontSize = 11.sp)
      root.Render()
    }
  }
}

/** The guest screen — authored once, emitted as protocol, rendered by the host. */
@Composable
private fun MiniNudge() {
  StyledBox(
    gradientColorsArgb = CARD, gradientStops = CARD_STOPS, gradientDirection = 3,
    borderColorArgb = BORDER, borderWidthDp = 1, cornerRadiusDp = 12,
    fillWidth = true, paddingDp = 20,
  ) {
    Column(width = Constraint.Fill, horizontalAlignment = CrossAxisAlignment.Stretch) {
      StyledText(text = "Rendered on WEB via the keliver PROTOCOL", fontSize = 12, bold = true, colorArgb = 0xFF8A8A8A.toInt())
      Spacer(height = Dp(12.0))
      RichText(
        spans = listOf(
          TextSpan(text = "Make your UPI ID ", bold = true, colorArgb = BLACK),
          TextSpan(text = "sound like you", bold = true, gradientColorsArgb = RED),
        ),
        fontSize = 20,
      )
      Spacer(height = Dp(20.0))
      AnimatedBorder(effect = 1, cornerRadiusDp = 10, strokeWidthDp = 2, colorsArgb = RED) {
        StyledBox(fillWidth = true, heightDp = 48, contentAlignment = 4) {
          StyledText(text = "Personalise my UPI ID now", fontSize = 14, bold = true, colorArgb = BLACK)
        }
      }
    }
  }
}
