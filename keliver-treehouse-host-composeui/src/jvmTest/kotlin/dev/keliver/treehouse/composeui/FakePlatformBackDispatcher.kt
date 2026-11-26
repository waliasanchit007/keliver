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
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList

/**
 * An activity's back dispatcher, as Android's `OnBackPressedDispatcher` behaves: a press goes to
 * the most recently added callback that is enabled, and to the activity (its fallback) when none
 * is.
 */
internal class FakePlatformBackDispatcher : OnBackPressedDispatcher {
  private val callbacks = mutableListOf<OnBackPressedCallback>()

  /** What received each press: a callback's name, or "activity". */
  val received = mutableListOf<String>()

  val registered: List<OnBackPressedCallback> get() = callbacks.toList()

  override fun addCallback(onBackPressedCallback: OnBackPressedCallback): Cancellable {
    callbacks += onBackPressedCallback
    return object : Cancellable {
      override fun cancel() {
        callbacks -= onBackPressedCallback
      }
    }
  }

  /** A system back press. */
  fun back() {
    val target = callbacks.lastOrNull { it.isEnabled }
    if (target == null) received += "activity" else target.handleOnBackPressed()
  }

  /** A callback of the host's own (a screen beneath, the content on top's) that records presses. */
  fun hostCallback(name: String, enabled: Boolean = true): OnBackPressedCallback =
    object : OnBackPressedCallback(enabled) {
      override fun handleOnBackPressed() {
        received += name
      }
    }
}

/**
 * Android's back dispatcher as Treehouse's Android wrapper registers with it
 * (TreehouseContent.android.kt): each callback becomes a platform callback whose enabled state is
 * the callback's AS READ when it registers, and the wrapper attaches its listener to the callback
 * only after that read. A change to the callback in between never reaches the platform callback.
 * [betweenReadAndListen] runs in that window: what another thread may do there.
 */
internal class FakeAndroidBackDispatcher : OnBackPressedDispatcher {
  class PlatformCallback(enabled: Boolean, val callback: OnBackPressedCallback) {
    @Volatile var isEnabled: Boolean = enabled
  }

  private val callbacks = CopyOnWriteArrayList<PlatformCallback>()

  /** What received each press: a callback's name, or "activity". */
  val received: MutableList<String> = Collections.synchronizedList(mutableListOf())

  val registered: List<PlatformCallback> get() = callbacks.toList()

  var betweenReadAndListen: (() -> Unit)? = null

  override fun addCallback(onBackPressedCallback: OnBackPressedCallback): Cancellable {
    val platformCallback = PlatformCallback(onBackPressedCallback.isEnabled, onBackPressedCallback)
    betweenReadAndListen?.invoke()
    onBackPressedCallback.enabledChangedCallback = {
      platformCallback.isEnabled = onBackPressedCallback.isEnabled
    }
    callbacks += platformCallback
    return object : Cancellable {
      override fun cancel() {
        onBackPressedCallback.enabledChangedCallback = null
        callbacks -= platformCallback
      }
    }
  }

  /** A system back press. */
  fun back() {
    val target = callbacks.lastOrNull { it.isEnabled }
    if (target == null) received += "activity" else target.callback.handleOnBackPressed()
  }
}

/** A guest's back handler, as Treehouse registers it on the host for the guest (TreehouseAppContent). */
internal class GuestBackHandler(
  val name: String,
  enabled: Boolean,
  private val received: MutableList<String>,
) : OnBackPressedCallback(enabled) {
  override fun handleOnBackPressed() {
    received += name
  }
}
