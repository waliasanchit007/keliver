/*
 * This app's one screen: the Keliver screen of the host its Application owns
 * (KeliverHost.kt has what the host does). A rotation recreates this activity,
 * not the host: nothing is looked up or loaded again. Scaffolded by
 * keliver-new-production-host.sh.
 */
package @@PACKAGE@@

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    Log.d(TAG, "screen created (the host is the Application's, not this activity's)")
    setContent { KeliverScreen((application as HostApp).keliver, Modifier.fillMaxSize()) }
  }

  // With keliver.updates=on-resume, back in the foreground: look for a newer
  // bundle and apply it in place (W5). Otherwise this does nothing.
  override fun onResume() {
    super.onResume()
    (application as HostApp).keliver.resumed()
  }
}
