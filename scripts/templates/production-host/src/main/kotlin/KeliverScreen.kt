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
import dev.keliver.Modifier as RedwoodModifier
import dev.keliver.treehouse.Crashed
import dev.keliver.treehouse.DynamicContentWidgetFactory
import dev.keliver.treehouse.Loading
import dev.keliver.treehouse.TreehouseContentSource
import dev.keliver.treehouse.composeui.TreehouseContent

/**
 * Shows [host]: [loading] while it looks up and loads, then the guest's screen.
 * Starts the host if nobody has.
 *
 * When the host cannot show a screen ([KeliverHostState.Unavailable]: no
 * bundle, a refused build, a bundle that did not load), [onUnavailable] is
 * called on the main thread each time this screen is composed in that state:
 * REPLACE this screen there with what it replaced (your web page or native
 * screen, with the route you were showing): finish or pop it, don't stack the
 * fallback over it, or going back to it calls [onUnavailable] again. After
 * "no bundle" a screen composed later looks up again (the state is Loading
 * meanwhile). [unavailable] is drawn until the app navigates, or instead
 * when there is no [onUnavailable].
 *
 * The default [loading] and [unavailable] show the host's own text only when
 * [KeliverHost.showsDeveloperText] (a debug build of a debuggable app); it
 * can name addresses and paths. Otherwise they show "Loading…" and a generic
 * line: a user never sees a developer's message.
 */
@Composable
fun KeliverScreen(
  host: KeliverHost,
  modifier: Modifier = Modifier,
  route: KeliverRoute? = null,
  onClose: (() -> Unit)? = null,
  onUnavailable: ((KeliverHostState.Unavailable) -> Unit)? = null,
  loading: @Composable () -> Unit = { DefaultLoading(host) },
  unavailable: @Composable (KeliverHostState.Unavailable) -> Unit = { DefaultUnavailable(host, it) },
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
          // Still the state? A screen composed on an old "no bundle" restarts the host just
          // before this runs (the start() effect is earlier), and must not act on that failure.
          LaunchedEffect(s) { if (host.state.value == s) latest?.invoke(s) }
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
            if (host.showsDeveloperText) MessageScreen("Updating", "Loading the newest version…") else loading()
          } else {
            SideEffect { shown = true } // once drawn, an update swaps in place
            val widgetSystem = remember(host) { ComposeUiKeliverMaterialWidgetSystem(host.imageLoader) }
            // K1's routes and close: a new route is a new content source, so the guest launches it
            // (TreehouseContent rebinds). Without onClose, closing finishes the activity (not a back
            // press: the guest's own back handlers, a NavHost's or a sheet's would take that).
            val activity = androidx.compose.ui.platform.LocalContext.current.findActivity()
            val latestOnClose by rememberUpdatedState(onClose ?: { activity?.finish() })
            // A close asked for after this content went (a reply that came late) closes nothing.
            val active = remember(route) { java.util.concurrent.atomic.AtomicBoolean(true) }
            DisposableEffect(active) { onDispose { active.set(false) } }
            val contentSource = remember(route) {
              object : TreehouseContentSource<PortalPresenter> {
                // first-frame, in debug and benchmark builds only (M0); the UI itself elsewhere.
                override fun get(app: PortalPresenter) =
                  KeliverTiming.firstFrame(host.launch(app, s.app, route) { if (active.get()) latestOnClose() })
              }
            }
            // K1's screen-failed: the code threw while this screen showed it.
            val latestOnUnavailable by rememberUpdatedState(onUnavailable)
            val latestUnavailable by rememberUpdatedState(unavailable)
            val failureWidgets = remember(host) { ScreenFailedWidgets(host, { latestOnUnavailable }, { latestUnavailable }) }
            TreehouseContent(
              treehouseApp = s.app,
              widgetSystem = widgetSystem,
              contentSource = contentSource,
              modifier = Modifier.fillMaxSize(),
              dynamicContentWidgetFactory = failureWidgets,
            )
          }
        }
      }
    }
  }
}

/**
 * What [TreehouseContent] draws while a screen's code loads (nothing, as before) and when it throws
 * while the screen shows it (K1's `screen-failed`): the host reports it (W6), the screen calls its
 * `onUnavailable` once per failure, and draws its `unavailable` slot (the host's text only when
 * [KeliverHost.showsDeveloperText]). Treehouse replaces the failed content when code loads again.
 */
private class ScreenFailedWidgets(
  private val host: KeliverHost,
  private val onUnavailable: () -> ((KeliverHostState.Unavailable) -> Unit)?,
  private val unavailable: () -> @Composable (KeliverHostState.Unavailable) -> Unit,
) : DynamicContentWidgetFactory<@Composable (Modifier) -> Unit> {
  // Nothing while code loads, as before; but once the running code has thrown, it never will:
  // a screen opened then says so and calls its onUnavailable.
  override fun Loading(): Loading<@Composable (Modifier) -> Unit> = object : Loading<@Composable (Modifier) -> Unit> {
    override var modifier: RedwoodModifier = RedwoodModifier
    override val value: @Composable (Modifier) -> Unit = {
      val failed by host.crashed.collectAsState()
      failed?.let { f ->
        LaunchedEffect(f) { onUnavailable()?.invoke(f) }
        unavailable()(f)
      }
    }
  }

  override fun Crashed(): Crashed<@Composable (Modifier) -> Unit> = object : Crashed<@Composable (Modifier) -> Unit> {
    private var failed by mutableStateOf<KeliverHostState.Unavailable?>(null)
    override var modifier: RedwoodModifier = RedwoodModifier
    override val value: @Composable (Modifier) -> Unit = {
      failed?.let { f ->
        LaunchedEffect(f) { onUnavailable()?.invoke(f) }
        unavailable()(f)
      }
    }

    override fun uncaughtException(uncaughtException: Throwable) {
      if (failed == null) failed = host.screenFailed(uncaughtException)
    }

    override fun restart(restart: () -> Unit) {}
  }
}

private tailrec fun Context.findActivity(): android.app.Activity? = when (this) {
  is android.app.Activity -> this
  is android.content.ContextWrapper -> baseContext.findActivity()
  else -> null
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

  /** [KeliverScreen]'s route: set a new one (a re-delivered intent's) and the screen shows it. */
  var route: KeliverRoute? by mutableStateOf(null)

  /** [KeliverScreen]'s onClose: the screen asked to close; by default its activity finishes. */
  var onClose: (() -> Unit)? by mutableStateOf(null)

  @Composable
  override fun Content() {
    host?.let { KeliverScreen(it, Modifier.fillMaxSize(), route = route, onClose = onClose, onUnavailable = onUnavailable) }
  }
}

/** [KeliverScreen]'s default while the host looks up and loads. */
@Composable
fun DefaultLoading(host: KeliverHost) {
  if (host.showsDeveloperText) MessageScreen("Loading", "Looking up the latest bundle…") else MessageScreen("Loading…", null)
}

/**
 * [KeliverScreen]'s default when the host cannot show a screen: the host's own
 * text when [KeliverHost.showsDeveloperText]; otherwise a generic line, never
 * the host's (it can name addresses and paths).
 */
@Composable
fun DefaultUnavailable(host: KeliverHost, state: KeliverHostState.Unavailable) {
  if (host.showsDeveloperText) MessageScreen(state.title, state.message)
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
