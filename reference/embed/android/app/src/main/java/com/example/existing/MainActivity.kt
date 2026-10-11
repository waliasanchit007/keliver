package com.example.existing

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
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
    root.addView(
      KeliverView(this).apply {
        host = (application as ExistingApp).keliver
        contentDescription = "keliver-view"
      },
      LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
    )
    setContentView(root)
  }
}
