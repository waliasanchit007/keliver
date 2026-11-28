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
@file:OptIn(InternalCallApi::class)

package dev.keliver.calls.composeui

import androidx.compose.foundation.shape.RoundedCornerShape as HostRoundedCornerShape
import androidx.compose.ui.unit.dp as hostDp
import dev.keliver.calls.compose.foundation.shape.RoundedCornerShape
import dev.keliver.calls.runtime.CallRecorded
import dev.keliver.calls.runtime.InternalCallApi
import dev.keliver.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json

/**
 * MS4-42 (G1): a value built in the guest with the generated mirrors crosses as the call that built
 * it, and the host's generated replay makes the same call. The result equals the value Compose builds
 * from the same source text. Moved from #312's spike (docs/COMPOSE_GENERIC_MECHANISMS.md §1.1) and
 * extended; it runs on the JVM, JS, wasm and the iOS simulator (the guest half is common code, and
 * so is Compose's half).
 */
class CallRecordsTest {
  /** Through JSON text, as the protocol carries it. */
  private fun replay(v: CallRecorded): Any = GeneratedReplay.replay(Json.parseToJsonElement(v.call.toJson().toString()))

  @Test fun roundedCornerShape() {
    val guest = RoundedCornerShape(8.dp)
    // The wire: the declaration's tag in the shared values lock (38 is `RoundedCornerShape(size:Dp)`, and
    // the lock never changes a tag), not a string id; the argument by parameter name.
    assertEquals("""{"call":38,"args":{"size":8}}""", guest.call.toJson().toString())
    assertEquals(HostRoundedCornerShape(8.hostDp), replay(guest))
  }
}
