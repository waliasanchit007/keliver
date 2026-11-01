/*
 * Copyright (C) 2026 Square, Inc.
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
package dev.keliver.bridge

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * One call the guest is waiting on, handed to the app's code with it: whether the guest has
 * stopped waiting (it cancelled, or the call ran out of time), and a way to hear it, to close a
 * picker the guest no longer wants.
 */
@OptIn(ExperimentalAtomicApi::class)
public class BridgeCall internal constructor(
  // Runs a block on the binding's context, in the guard's scope.
  private val dispatch: (() -> Unit) -> Unit,
) {
  // A List of the blocks waiting for a cancel, then CANCELLED or ANSWERED.
  private val state = AtomicReference<Any>(emptyList<() -> Unit>())

  public val isCancelled: Boolean get() = state.load() === CANCELLED

  /**
   * Runs [block] once, on the binding's context, when the guest stops waiting; if it already
   * has, [block] is posted to that context at once. Never run for a call that was answered.
   */
  public fun onCancel(block: () -> Unit) {
    while (true) {
      val now = state.load()
      when {
        now === CANCELLED -> return dispatch(block)
        now === ANSWERED -> return
        @Suppress("UNCHECKED_CAST")
        state.compareAndSet(now, (now as List<() -> Unit>) + block)
        -> return
      }
    }
  }

  internal fun cancel() {
    @Suppress("UNCHECKED_CAST")
    (state.exchange(CANCELLED) as? List<() -> Unit>)?.forEach(dispatch)
  }

  internal fun answered() {
    state.store(ANSWERED)
  }

  private companion object {
    val CANCELLED = Any()
    val ANSWERED = Any()
  }
}
