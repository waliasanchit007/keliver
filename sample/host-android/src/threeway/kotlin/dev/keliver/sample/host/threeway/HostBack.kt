/*
 * Copyright (C) 2026 Keliver contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.keliver.sample.host.threeway

import androidx.activity.OnBackPressedCallback as AndroidOnBackPressedCallback
import androidx.activity.OnBackPressedDispatcher as AndroidOnBackPressedDispatcher
import dev.keliver.ui.Cancellable
import dev.keliver.ui.OnBackPressedCallback
import dev.keliver.ui.OnBackPressedDispatcher

/**
 * The activity's back dispatcher for gate A's retained container (LocalHostBackDispatcher): the
 * same one the guest's BackHandlers reach through TreehouseContent, so the container's callback,
 * added when it becomes enabled, is the newest and takes the press first.
 */
internal class AndroidHostBackDispatcher(private val delegate: AndroidOnBackPressedDispatcher) : OnBackPressedDispatcher {
  override fun addCallback(onBackPressedCallback: OnBackPressedCallback): Cancellable {
    val android = object : AndroidOnBackPressedCallback(onBackPressedCallback.isEnabled) {
      override fun handleOnBackPressed() = onBackPressedCallback.handleOnBackPressed()
    }
    onBackPressedCallback.enabledChangedCallback = { android.isEnabled = onBackPressedCallback.isEnabled }
    delegate.addCallback(android)
    return object : Cancellable {
      override fun cancel() {
        onBackPressedCallback.enabledChangedCallback = null
        android.remove()
      }
    }
  }
}
