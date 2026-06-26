/*
 * spike/keliver-web GATE 3 — the GUEST build. Composes the guest screen and
 * writes its serialized protocol (screen.json) to the web host's served dir.
 * The web host fetches that file at runtime; it never compiled against this code.
 * Edit MiniNudge, re-run this, reload the host → the web shows the new UI.
 */
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import dev.keliver.layout.api.Constraint
import dev.keliver.layout.api.CrossAxisAlignment
import dev.keliver.layout.compose.Column
import dev.keliver.layout.compose.Spacer
import dev.keliver.material.api.TextSpan
import dev.keliver.material.compose.AnimatedBorder
import dev.keliver.material.compose.RichText
import dev.keliver.material.compose.StyledBox
import dev.keliver.material.compose.StyledText
import dev.keliver.material.protocol.guest.KeliverMaterialProtocolWidgetSystemFactory
import dev.keliver.protocol.SnapshotChangeList
import dev.keliver.protocol.guest.DefaultGuestProtocolAdapter
import dev.keliver.protocol.guest.guestRedwoodVersion
import dev.keliver.testing.TestRedwoodComposition
import dev.keliver.ui.Dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.Json
import java.io.File

private const val OUT = "/Users/sanchitwalia/AndroidStudioProjects/konduit/web-spike/build/dist/wasmJs/developmentExecutable/screen.json"

private const val BLACK = 0xFF111111.toInt()
private const val BORDER = 0xFFC7E0FF.toInt()
private val CARD = listOf(0xFFEAF2FF.toInt(), 0xFFDDE9FF.toInt(), 0xFFFFFFFF.toInt())
private val CARD_STOPS = listOf(0.0f, 0.55f, 1.0f)
private val RED = listOf(0xFF12B76A.toInt(), 0xFF0E8C52.toInt())

fun main() {
  val guestAdapter = DefaultGuestProtocolAdapter(
    hostVersion = guestRedwoodVersion,
    widgetSystemFactory = KeliverMaterialProtocolWidgetSystemFactory,
  )
  val tester = TestRedwoodComposition(
    scope = CoroutineScope(BroadcastFrameClock()),
    widgetSystem = guestAdapter.widgetSystem,
    container = guestAdapter.root,
    createSnapshot = { guestAdapter.takeChanges() },
  )
  val changes = tester.setContentAndSnapshot { MiniNudge() }
  val json = Json.encodeToString(SnapshotChangeList.serializer(), SnapshotChangeList(changes))
  File(OUT).apply { parentFile.mkdirs() }.writeText(json)
  println("guest-compiler: wrote ${json.length} chars (${changes.size} changes) -> $OUT")
}

/** Edit this and re-run to change what the web renders — no host rebuild. */
@Composable
private fun MiniNudge() {
  StyledBox(
    gradientColorsArgb = CARD, gradientStops = CARD_STOPS, gradientDirection = 3,
    borderColorArgb = BORDER, borderWidthDp = 1, cornerRadiusDp = 12,
    fillWidth = true, paddingDp = 20,
  ) {
    Column(width = Constraint.Fill, horizontalAlignment = CrossAxisAlignment.Stretch) {
      StyledText(text = "EDITED GUEST · regenerated screen.json · same host binary", fontSize = 12, bold = true, colorArgb = 0xFF8A8A8A.toInt())
      Spacer(height = Dp(12.0))
      RichText(
        spans = listOf(
          TextSpan(text = "Switch to ", bold = true, colorArgb = BLACK),
          TextSpan(text = "UPI Lite", bold = true, gradientColorsArgb = RED),
          TextSpan(text = " — pay in a tap", bold = true, colorArgb = BLACK),
        ),
        fontSize = 20,
      )
      Spacer(height = Dp(20.0))
      AnimatedBorder(effect = 2 /* pulse */, cornerRadiusDp = 10, strokeWidthDp = 2, cometColorArgb = 0xFF12B76A.toInt()) {
        StyledBox(fillWidth = true, heightDp = 48, contentAlignment = 4) {
          StyledText(text = "Enable UPI Lite", fontSize = 14, bold = true, colorArgb = BLACK)
        }
      }
    }
  }
}
