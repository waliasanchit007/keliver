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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.unit.dp
import dev.keliver.material.composeui.ComposeUiKeliverMaterialWidgetSystem
import dev.keliver.treehouse.TreehouseContentSource
import dev.keliver.treehouse.composeui.TreehouseContent

/** Shows [host]: its messages while it looks up and loads, then the guest's screen. Starts the host if nobody has. */
@Composable
fun KeliverScreen(host: KeliverHost, modifier: Modifier = Modifier) {
  LaunchedEffect(host) { host.start() }
  val state by host.state.collectAsState()
  MaterialTheme {
    Surface(modifier = modifier) {
      when (val s = state) {
        is KeliverHostState.Message -> MessageScreen(s.title, s.message)
        is KeliverHostState.Running -> {
          val widgetSystem = remember(host) { ComposeUiKeliverMaterialWidgetSystem(host.imageLoader) }
          val contentSource = remember {
            object : TreehouseContentSource<PortalPresenter> {
              override fun get(app: PortalPresenter) = app.launch()
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

/** KeliverScreen for apps built on Views: add it to a layout, then set [host]. */
class KeliverView @JvmOverloads constructor(
  context: Context,
  attrs: AttributeSet? = null,
  defStyleAttr: Int = 0,
) : AbstractComposeView(context, attrs, defStyleAttr) {
  var host: KeliverHost? by mutableStateOf(null)

  @Composable
  override fun Content() {
    host?.let { KeliverScreen(it, Modifier.fillMaxSize()) }
  }
}

@Composable
private fun MessageScreen(title: String, message: String) {
  Column(
    modifier = Modifier.fillMaxSize().padding(24.dp),
    verticalArrangement = Arrangement.Center,
  ) {
    Text(text = title, style = MaterialTheme.typography.h6)
    Spacer(Modifier.height(12.dp))
    Text(text = message)
  }
}
