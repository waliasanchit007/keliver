package com.example.older

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    lifecycleScope.launch { }
    val keliver = (application as OlderApp).keliver
    setContent {
      MaterialTheme {
        Column {
          Text("Native header")
          dev.keliver.floor.host.KeliverScreen(keliver, Modifier.fillMaxSize()) // KELIVER EMBED
        }
      }
    }
  }
}
