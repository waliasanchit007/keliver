/*
 * This app's one screen: the Keliver screen of the host its Application owns
 * (KeliverHost.kt has what the host does). A rotation recreates this activity,
 * not the host: nothing is looked up or loaded again. Scaffolded by
 * keliver-new-production-host.sh.
 */
package @@PACKAGE@@

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

class MainActivity : ComponentActivity() {
  /**
   * K1: the route this activity was opened with (a deep link's path), and a re-delivered
   * intent's new one. Only [ROUTE_QUERY_KEYS] of its query reach the screen. This host's
   * manifest declares no deep link; an app that adds one gets its route here.
   */
  private var route by mutableStateOf<KeliverRoute?>(null)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    Log.d(TAG, "screen created (the host is the Application's, not this activity's)")
    route = KeliverRoute.from(intent?.dataString, ROUTE_QUERY_KEYS)
    setContent { KeliverScreen((application as HostApp).keliver, Modifier.fillMaxSize(), route = route) }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    route = KeliverRoute.from(intent.dataString, ROUTE_QUERY_KEYS)
  }

  // With keliver.updates=on-resume, back in the foreground: look for a newer
  // bundle and apply it in place (W5). Otherwise this does nothing.
  override fun onResume() {
    super.onResume()
    (application as HostApp).keliver.resumed()
  }

  private companion object {
    /** The deep-link query keys a screen may see; every other parameter is dropped. */
    val ROUTE_QUERY_KEYS: Set<String> = emptySet()
  }
}
