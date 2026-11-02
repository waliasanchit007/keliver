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
package dev.keliver.bridge.fixture

import app.cash.zipline.ZiplineService
import dev.keliver.bridge.api.BridgeTimeout
import dev.keliver.bridge.api.KeliverBridge

/**
 * B5b (docs/BRIDGE_CODEGEN_DESIGN.md, section "B5", §2): a second bridge with a short limit, so
 * the round trip through a real guest can show a call that gets no answer in time.
 */
@KeliverBridge(name = "Waits", version = 1)
interface Waits {
  @BridgeTimeout(seconds = 1)
  suspend fun waitBriefly(): String
}

/**
 * The fixture guest's own service (keliver-bridge-fixture-guest), bound as [NAME]: the round-trip
 * test asks it to run one [step], a call from the guest to the host through the generated
 * bridges, and it answers with what the guest saw (`ok …`, or `failed <BridgeCallFailed's message>`).
 * Test-only.
 */
interface RoundTripDriver : ZiplineService {
  suspend fun run(step: String): String

  companion object {
    const val NAME = "RoundTripDriver"
  }
}
