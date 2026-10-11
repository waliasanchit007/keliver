package com.example.existing

import android.os.Bundle
import android.widget.TextView
import androidx.activity.ComponentActivity

/** A native screen with no Keliver in it. */
class DetailsActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(TextView(this).apply { text = "Native details screen"; textSize = 22f })
  }
}
