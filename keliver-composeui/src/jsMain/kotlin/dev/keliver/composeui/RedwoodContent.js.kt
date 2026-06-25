/*
 * Copyright (C) 2026 Keliver contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.keliver.composeui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.keliver.ui.Cancellable
import dev.keliver.ui.OnBackPressedCallback
import dev.keliver.ui.OnBackPressedDispatcher

// Web has no system back gesture — a no-op dispatcher (mirrors the JVM actual).
@Composable
internal actual fun platformOnBackPressedDispatcher(): OnBackPressedDispatcher {
  return remember {
    object : OnBackPressedDispatcher {
      override fun addCallback(onBackPressedCallback: OnBackPressedCallback): Cancellable =
        object : Cancellable {
          override fun cancel() = Unit
        }
    }
  }
}
