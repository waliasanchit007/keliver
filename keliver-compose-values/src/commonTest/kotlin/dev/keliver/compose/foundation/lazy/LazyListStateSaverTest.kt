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
package dev.keliver.compose.foundation.lazy

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.SaverScope
import dev.keliver.compose.wire.KeliverGeneratedApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@OptIn(KeliverGeneratedApi::class)
class LazyListStateSaverTest {
  @Test fun aRestoredStateStartsWhereTheHostWasWithoutACommandToRerun() {
    val clock = BroadcastFrameClock()
    val scope = CoroutineScope(Dispatchers.Unconfined + clock)
    val state = LazyListState()
    scope.launch { state.scrollToItem(42) }
    clock.sendFrame(0L)
    assertEquals(1, state.pendingScroll?.id)
    state.onHostViewport(40, 50, 12)

    @Suppress("UNCHECKED_CAST")
    val saver = LazyListState.Saver as Saver<LazyListState, Any>
    val saved = with(saver) { SaverScope { true }.save(state) }!!
    val restored = saver.restore(saved)!!

    assertEquals(40, restored.firstVisibleItemIndex)
    assertEquals(12, restored.firstVisibleItemScrollOffset)
    assertEquals(LazyListStateWire(40, 12, null), restored.wire, "the host starts there, and no command travels")

    // The next command's id follows the saved one: never an id a host has already run.
    scope.launch { restored.scrollToItem(3) }
    clock.sendFrame(1L)
    assertEquals(2, restored.pendingScroll?.id)
  }
}
