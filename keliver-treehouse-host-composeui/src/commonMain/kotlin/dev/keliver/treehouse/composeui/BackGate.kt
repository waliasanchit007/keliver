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
package dev.keliver.treehouse.composeui

import dev.keliver.ui.Cancellable
import dev.keliver.ui.OnBackPressedCallback
import dev.keliver.ui.OnBackPressedDispatcher
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * The back dispatcher a [TreehouseContent] gives its guest: [delegate] (the platform's), behind a
 * gate. While [open] is false every callback the content registered is registered with [delegate]
 * DISABLED, so a back press goes past this content to whatever is below it (the content shown on
 * top, the activity) and reaches none of its guest's handlers. When [open] turns true each
 * callback is enabled again exactly when the guest has it enabled. (NF5 H0: content composed
 * hidden, beneath the content on show.)
 *
 * Treehouse adds callbacks and changes their enabled state on its Zipline thread, while [open]
 * changes on the UI thread. Each change is followed by a [GatedCallback.sync] under one lock, so the
 * last sync sees every change before it and the platform's callback ends in agreement with both.
 * That holds only once the platform listens to the callback: a platform wrapper (Android's) reads
 * a callback's enabled state as it registers it and attaches its listener after, and a change in
 * between would be lost. So a callback is registered with [delegate] before anything can change it
 * (it starts disabled, and neither [open] nor its guest reaches it yet), and only then joined to
 * both and synced.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class BackGate(
  private val delegate: OnBackPressedDispatcher,
  open: Boolean,
) : OnBackPressedDispatcher {
  private val gated = AtomicReference(emptyList<GatedCallback>())

  /**
   * Held while one callback's enabled state is decided and written to [delegate]'s callback. That
   * write runs the platform's listener (on Android, androidx's enabled-callbacks update, which can
   * make a binder call from API 33), so a thread that waits for it blocks rather than spins. Never
   * held while calling into the guest.
   */
  private val lock = BackGateLock()

  @Volatile
  var open: Boolean = open
    set(value) {
      if (field == value) return
      field = value
      for (callback in gated.load()) callback.sync()
    }

  override fun addCallback(onBackPressedCallback: OnBackPressedCallback): Cancellable {
    val callback = GatedCallback(onBackPressedCallback)
    // 1. With the platform first: it reads the callback's state (disabled) and attaches its
    //    listener while nothing else can change that state.
    val registration = delegate.addCallback(callback)
    // 2. Then the guest's changes reach it, and (once it is in the list) [open]'s do.
    onBackPressedCallback.enabledChangedCallback = callback.onGuestChanged
    update { it + callback }
    // 3. This sync covers any change before those two.
    callback.sync()
    return object : Cancellable {
      override fun cancel() {
        update { it - callback }
        if (onBackPressedCallback.enabledChangedCallback === callback.onGuestChanged) {
          onBackPressedCallback.enabledChangedCallback = null
        }
        registration.cancel()
      }
    }
  }

  private inline fun update(change: (List<GatedCallback>) -> List<GatedCallback>) {
    while (true) {
      val before = gated.load()
      if (gated.compareAndSet(before, change(before))) return
    }
  }

  /** What [delegate] holds for [guest]: enabled only while the gate is open and [guest] is enabled. */
  private inner class GatedCallback(
    private val guest: OnBackPressedCallback,
  ) : OnBackPressedCallback(enabled = false) {
    /** Set on [guest]: its enabled state changed. */
    val onGuestChanged: () -> Unit = { sync() }

    private fun wanted() = open && guest.isEnabled

    fun sync() {
      lock.withLock {
        val want = wanted()
        if (isEnabled != want) isEnabled = want
      }
    }

    override fun handleOnBackPressed() {
      // A press the platform routed here before the gate closed still never reaches the guest.
      if (wanted()) guest.handleOnBackPressed()
    }
  }
}

/** A mutual-exclusion lock that blocks a waiting thread: the platform's own. */
internal expect class BackGateLock() {
  fun lock()
  fun unlock()
}

internal inline fun <T> BackGateLock.withLock(block: () -> T): T {
  lock()
  try {
    return block()
  } finally {
    unlock()
  }
}
