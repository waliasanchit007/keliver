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

import dev.keliver.bridge.api.BridgeTimeout
import dev.keliver.bridge.api.KeliverBridge
import kotlinx.serialization.Serializable

@Serializable
class AddressRequest(val purpose: Purpose, val prefill: String?)

@Serializable
class AddressResult(val line: String, val tags: List<String>)

enum class Purpose { DELIVERY, BILLING }

/** The fixture bridge: one of each kind of function the processor accepts. */
@KeliverBridge(name = "ShopActions", version = 2)
interface ShopActions {
  suspend fun openRoute(route: String): Boolean

  suspend fun share(text: String, url: String?)

  @BridgeTimeout(seconds = 0)
  suspend fun pickAddress(request: AddressRequest): AddressResult

  suspend fun findCoupon(code: String): String?

  fun track(event: String, properties: Map<String, String>)
}
