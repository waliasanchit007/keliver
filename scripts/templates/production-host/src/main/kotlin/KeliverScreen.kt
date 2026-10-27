/*
 * The Keliver screen: what a KeliverHost shows. A Composable for Compose apps
 * (KeliverScreen) and a View for apps built on Views (KeliverView). Both only
 * observe the host's state; neither looks anything up or loads anything, so
 * any number of them, recreated on every configuration change, share the
 * host's one load. Scaffolded by keliver-new-production-host.sh (W2).
 */
@file:OptIn(dev.keliver.leaks.RedwoodLeakApi::class)

package @@PACKAGE@@

import android.content.Context
import android.util.AttributeSet
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.keliver.material.composeui.ComposeUiKeliverMaterialWidgetSystem
import dev.keliver.treehouse.TreehouseContentSource
import dev.keliver.treehouse.composeui.TreehouseContent

/**
 * Shows [host]: [loading] while it looks up and loads, then the guest's screen.
 * Starts the host if nobody has.
 *
 * When the host cannot show a screen ([KeliverHostState.Unavailable]: no
 * bundle, a refused build, a bundle that did not load), [onUnavailable] is
 * called on the main thread each time this screen meets one: open what this screen replaced there
 * (your web page or native screen), with the route you were showing.
 * [unavailable] is drawn meanwhile, or instead when there is no
 * [onUnavailable].
 *
 * The default [loading] and [unavailable] show the host's own text in a debug
 * build, which names addresses and paths, and in a release build only
 * "Loading…" and a generic line: a user never sees a developer's message.
 */
@Composable
fun KeliverScreen(
  host: KeliverHost,
  modifier: Modifier = Modifier,
  onUnavailable: ((KeliverHostState.Unavailable) -> Unit)? = null,
  loading: @Composable () -> Unit = { DefaultLoading() },
  unavailable: @Composable (KeliverHostState.Unavailable) -> Unit = { DefaultUnavailable(it) },
) {
  remember(host) { KeliverTiming.mark("open") } // debug and benchmark builds only (M0)
  LaunchedEffect(host) { host.start() }
  // Live updates (keliver.updates=live, debug builds): the host listens for publishes while a screen is on show.
  val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
  DisposableEffect(host, lifecycle) {
    var shown = false
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_START && !shown) {
        shown = true
        host.screenShown()
      } else if (event == Lifecycle.Event.ON_STOP && shown) {
        shown = false
        host.screenHidden()
      }
    }
    lifecycle.addObserver(observer)
    onDispose {
      lifecycle.removeObserver(observer)
      if (shown) host.screenHidden()
    }
  }
  val state by host.state.collectAsState()
  MaterialTheme {
    Surface(modifier = modifier) {
      when (val s = state) {
        KeliverHostState.Loading -> loading()
        is KeliverHostState.Unavailable -> {
          val latest by rememberUpdatedState(onUnavailable)
          LaunchedEffect(s) { latest?.invoke(s) }
          unavailable(s)
        }
        is KeliverHostState.Running -> {
          // A screen created while a resume update check runs waits for it (at most 5 s): bound to
          // the running code at once, it was drawn twice — the old bundle, then the update.
          val checking by host.resumeCheckRunning.collectAsState()
          var waited by remember { mutableStateOf(false) }
          var shown by remember { mutableStateOf(false) }
          LaunchedEffect(Unit) { kotlinx.coroutines.delay(5_000); waited = true }
          if (checking && !shown && !waited) {
            if (BuildConfig.DEBUG) MessageScreen("Updating", "Loading the newest version…") else loading()
          } else {
            SideEffect { shown = true } // once drawn, an update swaps in place
            val widgetSystem = remember(host) { ComposeUiKeliverMaterialWidgetSystem(host.imageLoader) }
            val contentSource = remember {
              object : TreehouseContentSource<PortalPresenter> {
                // first-frame, in debug and benchmark builds only (M0); the UI itself elsewhere.
                override fun get(app: PortalPresenter) = KeliverTiming.firstFrame(app.launch())
              }
            }
            TreehouseContent(
              treehouseApp = s.app,
              widgetSystem = widgetSystem,
              contentSource = contentSource,
              modifier = Modifier.fillMaxSize(),
            )
          }
        }
      }
    }
  }
}

/** KeliverScreen for apps built on Views: add it to a layout, then set [host]. */
class KeliverView @JvmOverloads constructor(
  context: Context,
  attrs: AttributeSet? = null,
  defStyleAttr: Int = 0,
) : AbstractComposeView(context, attrs, defStyleAttr) {
  var host: KeliverHost? by mutableStateOf(null)

  /** [KeliverScreen]'s onUnavailable: open what this view replaced when the host cannot show a screen. */
  var onUnavailable: ((KeliverHostState.Unavailable) -> Unit)? by mutableStateOf(null)

  @Composable
  override fun Content() {
    host?.let { KeliverScreen(it, Modifier.fillMaxSize(), onUnavailable = onUnavailable) }
  }
}

/** [KeliverScreen]'s default while the host looks up and loads. */
@Composable
fun DefaultLoading() {
  if (BuildConfig.DEBUG) MessageScreen("Loading", "Looking up the latest bundle…") else MessageScreen("Loading…", null)
}

/**
 * [KeliverScreen]'s default when the host cannot show a screen: the host's own
 * text in a debug build; in a release build a generic line, never the host's
 * (it can name addresses and paths).
 */
@Composable
fun DefaultUnavailable(state: KeliverHostState.Unavailable) {
  if (BuildConfig.DEBUG) MessageScreen(state.title, state.message)
  else MessageScreen("Not available", "This screen can't be shown right now. Please try again later.")
}

@Composable
private fun MessageScreen(title: String, message: String?) {
  Column(
    modifier = Modifier.fillMaxSize().padding(24.dp),
    verticalArrangement = Arrangement.Center,
  ) {
    Text(text = title, style = MaterialTheme.typography.h6)
    if (message != null) {
      Spacer(Modifier.height(12.dp))
      Text(text = message)
    }
  }
}
