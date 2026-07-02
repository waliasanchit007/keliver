/*
 * spike/keliver-web PORTAL sub-project A — the runtime engine.
 *
 * The browser-side guest (gate 5) is now driven by a DATA TREE instead of a
 * hardcoded screen: the guest composition is `RenderNode(treeState.value)`, and
 * editing the tree (here, a host "Add item" button) recomposes the guest, which
 * emits the minimal protocol changes to the host canvas — instant, no recompile.
 * This is the portal's engine: edit a WidgetNode tree -> live preview.
 *
 *   tree (MutableState) --RenderNode--> guest composition --protocol--> host (canvas)
 *   edit the tree --recompose--> minimal changes --> live preview update
 */
import androidx.compose.foundation.layout.Column as RawColumn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button as RawButton
import androidx.compose.material3.Text as RawText
import androidx.compose.runtime.BroadcastFrameClock
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
import dev.keliver.leaks.LeakDetector
import dev.keliver.material.composeui.ComposeUiKeliverMaterialWidgetSystem
import dev.keliver.material.protocol.guest.KeliverMaterialProtocolWidgetSystemFactory
import dev.keliver.material.protocol.host.KeliverMaterialHostProtocol
import dev.keliver.portal.render.RenderNode
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
import dev.keliver.ui.OnBackPressedCallback
import dev.keliver.ui.OnBackPressedDispatcher
import dev.keliver.ui.UiConfiguration
import dev.keliver.widget.compose.ComposeWidgetChildren
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
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
  mountPortalChrome()
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
        eventSink = UiEventSink { uiEvent -> guestAdapter.sendEvent(uiEvent.toProtocol()) },
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
      composition.setContent { RenderNode(portalTree.value) }
      guestAdapter.emitChanges() // initial render

      while (true) {
        withFrameNanos { nanos -> guestClock.sendFrame(nanos) }
        guestAdapter.emitChanges() // flush whatever the recomposition produced
      }
    }

    // The DOM chrome (mountPortalChrome) drives edits to portalTree; the canvas
    // just shows the live preview.
    root.Render()
  }
}
