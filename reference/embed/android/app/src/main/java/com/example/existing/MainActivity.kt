package com.example.existing

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import inventory.host.KeliverRoute
import inventory.host.KeliverView

/** The existing app's home screen: native views, and one Keliver view among them. */
class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    Log.d("ExistingApp", "native screen created")
    val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    root.addView(TextView(this).apply { text = "Native header"; textSize = 22f; contentDescription = "native-header" })
    root.addView(Button(this).apply {
      text = "Native details"
      contentDescription = "native-details"
      setOnClickListener { startActivity(Intent(this@MainActivity, DetailsActivity::class.java)) }
    })
    // KELIVER EMBED: the guest's screen, below the native views.
    val keliver = KeliverView(this)
    keliver.apply {
      host = (application as ExistingApp).keliver
      contentDescription = "keliver-view"
      // The host kit: a deep link's route (only its "tab" parameter reaches the guest), and
      // the native view that takes the Keliver view's place when it closes or cannot show.
      route = KeliverRoute.from(intent?.dataString, setOf("tab"))
      onClose = {
        Log.d("ExistingApp", "keliver closed")
        replace(root, keliver, "Keliver closed")
      }
      onUnavailable = { state ->
        Log.d("ExistingApp", "keliver unavailable: ${state.outcome}")
        replace(root, keliver, "Native fallback")
      }
    }
    root.addView(keliver, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
    setContentView(root)
  }

  /** Puts a native text where [view] was (once: a second call finds it gone). */
  private fun replace(root: LinearLayout, view: View, text: String) {
    val at = root.indexOfChild(view)
    if (at < 0) return
    root.removeViewAt(at)
    root.addView(
      TextView(this).apply { this.text = text; textSize = 18f; contentDescription = "native-fallback" },
      at, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
    )
  }
}
