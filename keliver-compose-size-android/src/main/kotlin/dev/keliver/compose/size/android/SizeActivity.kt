/*
 * Copyright (C) 2026 Keliver contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.keliver.compose.size.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.keliver.compose.core.composeui.samples.native.ItemDetail
import dev.keliver.compose.core.composeui.samples.native.SampleCard
import dev.keliver.compose.core.composeui.samples.native.SampleOrders

/**
 * MS4-03's size harness: a same-source sample drawn by native Compose (`--es mode native`, the
 * default) or, in the product flavors, loaded from the guest bundle in the APK's assets through the
 * generated root's renderers (`--es mode guest`). `--es sample orders|card|item`.
 */
class SizeActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val sample = intent.getStringExtra("sample") ?: "orders"
    val route = guestRoute?.takeIf { intent.getStringExtra("mode") == "guest" }
    setContent {
      MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
          if (route != null) route.Content(this, sample) else NativeSample(sample)
        }
      }
    }
  }
}

/** The guest route of the product flavors (src/route); null in nativeOnly. */
internal interface GuestRoute {
  @Composable
  fun Content(activity: ComponentActivity, sample: String)
}

@Composable
private fun NativeSample(sample: String) {
  when (sample) {
    "card" -> Box(Modifier.fillMaxSize()) {
      SampleCard("A long item title that will not fit on one line", "Qty 12", listOf("Fragile", "Bulk", "In stock")) {}
    }
    "item" -> ItemDetail(onBack = {}, onAdjust = {}, onReorder = {})
    else -> SampleOrders(listOf("Order 1042" to "Out for delivery", "Order 1041" to "Packed"), onBack = {}, onOpen = {})
  }
}
