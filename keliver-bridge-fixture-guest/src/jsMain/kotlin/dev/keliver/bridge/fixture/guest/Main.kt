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
package dev.keliver.bridge.fixture.guest

import app.cash.zipline.Zipline
import dev.keliver.bridge.BridgeCallFailed
import dev.keliver.bridge.fixture.AddressRequest
import dev.keliver.bridge.fixture.Purpose
import dev.keliver.bridge.fixture.RoundTripDriver
import dev.keliver.bridge.fixture.ShopActions
import dev.keliver.bridge.fixture.ShopActionsBridge
import dev.keliver.bridge.fixture.Waits
import dev.keliver.bridge.fixture.WaitsBridge
import kotlinx.coroutines.withTimeoutOrNull

/** Runs when the host loads this guest: takes the host's bridges, binds the test's driver. */
fun main() {
  val zipline = Zipline.get()
  zipline.bind<RoundTripDriver>(RoundTripDriver.NAME, GuestDriver(ShopActionsBridge.take(zipline), WaitsBridge.take(zipline)))
}

private class GuestDriver(private val shop: ShopActions, private val waits: Waits) : RoundTripDriver {
  override suspend fun run(step: String): String = try {
    when (step) {
      "openRoute" -> "ok ${shop.openRoute("/cart")}"
      "share" -> shop.share("hello", null).let { "ok" }
      "pickAddress" -> shop.pickAddress(AddressRequest(Purpose.BILLING, "1 Main")).let { "ok ${it.line} ${it.tags}" }
      "findCoupon" -> "ok ${shop.findCoupon("NONE")}"
      "findCouponFound" -> "ok ${shop.findCoupon("WELCOME")}"
      "track" -> shop.track("viewed", mapOf("item" to "beans")).let { "ok" }
      "waitBriefly" -> "ok ${waits.waitBriefly()}"
      // The guest stops waiting before the host answers: the host's side is cancelled.
      "cancelPick" -> "ok ${withTimeoutOrNull(300) { shop.pickAddress(AddressRequest(Purpose.DELIVERY, null)) }}"
      else -> "unknown step $step"
    }
  } catch (e: BridgeCallFailed) {
    "failed ${e.message}"
  }
}
