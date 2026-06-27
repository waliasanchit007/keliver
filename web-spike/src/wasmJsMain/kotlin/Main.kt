/*
 * spike/keliver-web GATE 5 (Phase B) — BROWSER-SIDE GUEST + EVENT REVERSE-CHANNEL.
 *
 * The keliver guest now runs LIVE IN THE BROWSER (wasm), exactly like it runs
 * on-device on Android/iOS — no JVM tool, no screen.json, no network round-trip.
 *
 *   guest composition (in-browser) --in-memory protocol Changes--> host renderer (canvas)
 *   tap on a widget --UiEvent.toProtocol()--> guest --recompose--> new Changes --> host
 *
 * This is the "fat client" / mobile-parity interactivity model: author Kotlin
 * once, it composes and handles events client-side everywhere. WebSocket (future)
 * is then only for DEV code-push, not for runtime interaction.
 */
import androidx.compose.foundation.layout.Column as RawColumn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text as RawText
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
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
import dev.keliver.material.compose.AsyncImage
import dev.keliver.material.compose.Button
import dev.keliver.material.compose.StyledBox
import dev.keliver.material.compose.StyledText
import dev.keliver.material.composeui.ComposeUiKeliverMaterialWidgetSystem
import dev.keliver.material.protocol.guest.KeliverMaterialProtocolWidgetSystemFactory
import dev.keliver.material.protocol.host.KeliverMaterialHostProtocol
import dev.keliver.protocol.Change
import dev.keliver.protocol.ChangesSink
import dev.keliver.protocol.guest.DefaultGuestProtocolAdapter
import dev.keliver.protocol.guest.ProtocolRedwoodComposition
import dev.keliver.protocol.guest.guestRedwoodVersion
import dev.keliver.protocol.host.HostProtocolAdapter
import dev.keliver.protocol.host.ProtocolMismatchHandler
import dev.keliver.protocol.host.UiChange
import dev.keliver.protocol.host.UiEventSink
import dev.keliver.ui.Cancellable
import dev.keliver.ui.Dp
import dev.keliver.ui.OnBackPressedCallback
import dev.keliver.ui.OnBackPressedDispatcher
import dev.keliver.ui.UiConfiguration
import dev.keliver.widget.compose.ComposeWidgetChildren
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json

private val JSON = Json { ignoreUnknownKeys = true }

/** The web has no hardware back button; a guest that never adds a callback needs nothing here. */
private val NoBackPressedDispatcher = object : OnBackPressedDispatcher {
  override fun addCallback(onBackPressedCallback: OnBackPressedCallback): Cancellable =
    object : Cancellable { override fun cancel() {} }
}

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
  CanvasBasedWindow(canvasElementId = "ComposeTarget") {
    val widgetSystem = remember {
      ComposeUiKeliverMaterialWidgetSystem(
        ImageLoader.Builder(PlatformContext.INSTANCE)
          // wasm has no built-in Coil network stack — register the browser-fetch one.
          .components { add(BrowserFetchFetcher.Factory()) }
          .build(),
      )
    }
    val root = remember { ComposeWidgetChildren() }
    var taps by remember { mutableStateOf(0) }

    // Stand up the fat client: a guest composition + a host renderer, talking via
    // the in-memory protocol. Runs once; stays live for the page's lifetime.
    LaunchedEffect(Unit) {
      val hostProtocol = KeliverMaterialHostProtocol.Factory.create(JSON, ProtocolMismatchHandler.Throwing)

      val guestAdapter = DefaultGuestProtocolAdapter(
        hostVersion = guestRedwoodVersion,
        widgetSystemFactory = KeliverMaterialProtocolWidgetSystemFactory,
      )

      // Host side. Its event sink is the REVERSE CHANNEL: a tap on a rendered widget
      // becomes a UiEvent, which we hand straight back to the guest as a protocol Event.
      val hostAdapter = HostProtocolAdapter(
        guestVersion = guestRedwoodVersion,
        container = root,
        protocol = hostProtocol,
        widgetSystem = widgetSystem,
        eventSink = UiEventSink { uiEvent ->
          taps++
          guestAdapter.sendEvent(uiEvent.toProtocol())
        },
        leakDetector = LeakDetector.none(),
      )

      // Guest -> host: every batch of guest Changes is applied to the host tree in
      // memory (no JSON document; the Changes already carry encoded property values).
      guestAdapter.initChangesSink(
        object : ChangesSink {
          override fun sendChanges(changes: List<Change>) {
            hostAdapter.sendChanges(changes.mapNotNull { UiChange.fromProtocol(hostProtocol, it) })
          }
        },
      )

      // The guest composition runs on its own frame clock, which we tick from the
      // host's real frames — so guest recomposition AND animations stay in sync.
      val guestClock = BroadcastFrameClock()
      val guestScope = CoroutineScope(this.coroutineContext + guestClock)
      val composition = ProtocolRedwoodComposition(
        scope = guestScope,
        guestAdapter = guestAdapter,
        widgetVersion = 1U,
        onBackPressedDispatcher = NoBackPressedDispatcher,
        saveableStateRegistry = null,
        uiConfigurations = MutableStateFlow(UiConfiguration()),
      )
      composition.setContent { GuestUi() }
      guestAdapter.emitChanges() // initial render

      while (true) {
        withFrameNanos { nanos -> guestClock.sendFrame(nanos) }
        guestAdapter.emitChanges() // flush whatever the recomposition produced
      }
    }

    RawColumn(modifier = Modifier.padding(8.dp)) {
      RawText("browser-side keliver guest · taps recompose locally, no network · host events=$taps", fontSize = 11.sp)
      root.Render()
    }
  }
}

/**
 * The GUEST UI — ordinary keliver-material @Composables with real Compose state.
 * Tapping the button mutates `count`; the guest recomposes IN THE BROWSER and the
 * host re-renders the new number. Same code that would run on Android/iOS.
 */
@Composable
private fun GuestUi() {
  var count by remember { mutableStateOf(0) }
  StyledBox(
    gradientColorsArgb = listOf(0xFFFFF4E8.toInt(), 0xFFFFE9D6.toInt()),
    gradientStops = listOf(0.0f, 1.0f),
    gradientDirection = 3,
    borderColorArgb = 0xFFFFD9B0.toInt(),
    borderWidthDp = 1,
    cornerRadiusDp = 12,
    fillWidth = true,
    paddingDp = 20,
  ) {
    Column(width = Constraint.Fill, horizontalAlignment = CrossAxisAlignment.Stretch) {
      StyledText(text = "LIVE · guest runs in the browser", fontSize = 12, bold = true, colorArgb = 0xFF8A8A8A.toInt())
      Spacer(height = Dp(12.0))
      StyledText(text = "Tapped $count times", fontSize = 26, bold = true, colorArgb = 0xFF111111.toInt())
      Spacer(height = Dp(16.0))
      Button(text = "Tap me  (+1)", onClick = { count++ })
      Spacer(height = Dp(16.0))
      StyledText(text = "AsyncImage (network, fetched in-browser):", fontSize = 12, bold = true, colorArgb = 0xFF8A8A8A.toInt())
      Spacer(height = Dp(8.0))
      AsyncImage(url = "https://picsum.photos/seed/keliver/96/96")
    }
  }
}
