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

import app.cash.zipline.EventListener
import app.cash.zipline.Zipline
import app.cash.zipline.ZiplineException
import app.cash.zipline.ZiplineService
import dev.keliver.bridge.BridgeCall
import dev.keliver.bridge.BridgeCallFailed
import dev.keliver.bridge.BridgeGuard
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

// The classes keliver-bridge-codegen wrote for ShopActions: the host's two services (the app's
// suspend implementation, Swift's callbacks) through a real guard, the guest's wrapper over a
// service that fails the way Zipline delivers a host's failure, and bind against a real Zipline.
@OptIn(ExperimentalCoroutinesApi::class)
class GeneratedBridgeTest {
  private val logged = mutableListOf<String>()

  @Test fun theConstants() {
    assertEquals("ShopActions", ShopActionsBridge.NAME)
    assertEquals(2, ShopActionsBridge.VERSION)
    assertTrue(ShopActionsBridge.SHAPE.matches(Regex("[0-9a-f]{16}")))
  }

  @Test fun theAppsImplementationServesEachKindOfCall() = runTest {
    val tracked = mutableListOf<String>()
    val shared = mutableListOf<String?>()
    val impl = object : ShopActions {
      override suspend fun openRoute(route: String) = route == "/cart"
      override suspend fun share(text: String, url: String?) {
        shared += url
      }
      override suspend fun pickAddress(request: AddressRequest) = AddressResult("1 Main St", listOf(request.purpose.name))
      override suspend fun findCoupon(code: String): String? = null
      override fun track(event: String, properties: Map<String, String>) {
        tracked += event
      }
    }
    val service = ShopActionsGuardedService(impl, BridgeGuard("ShopActions", StandardTestDispatcher(testScheduler)))
    assertTrue(service.openRoute("/cart"))
    service.share("hi", null)
    assertEquals(listOf<String?>(null), shared)
    assertEquals(listOf("BILLING"), service.pickAddress(AddressRequest(Purpose.BILLING, null)).tags)
    assertNull(service.findCoupon("X"))
    service.track("viewed", emptyMap())
    assertEquals(emptyList(), tracked, "fire and forget: posted, not run inline")
    advanceUntilIdle()
    assertEquals(listOf("viewed"), tracked)
  }

  @Test fun theAppsThrowFailsTheCallWithOneLineAndLogsItsMessage() = runTest {
    val impl = object : ShopActions by unused() {
      override suspend fun openRoute(route: String): Boolean = error("token=secret")
    }
    val guard = BridgeGuard("ShopActions", StandardTestDispatcher(testScheduler)) { logged += it }
    val e = assertFailsWith<BridgeCallFailed> { ShopActionsGuardedService(impl, guard).openRoute("/cart") }
    assertEquals("KeliverBridge: ShopActions.openRoute: threw IllegalStateException", e.message)
    assertEquals(listOf("KeliverBridge: ShopActions.openRoute threw IllegalStateException: token=secret"), logged)
  }

  @Test fun theDefaultLimitIsThirtySeconds() = runTest {
    // timeoutSeconds is left at its default on the fixture: the processor's fallback (KSP may
    // not see the annotation's defaults in this metadata compilation) must be @KeliverBridge's.
    val impl = object : ShopActions by unused() {
      override suspend fun openRoute(route: String): Boolean = kotlinx.coroutines.awaitCancellation()
    }
    val service = ShopActionsGuardedService(impl, BridgeGuard("ShopActions", StandardTestDispatcher(testScheduler)))
    val e = assertFailsWith<BridgeCallFailed> { service.openRoute("/cart") }
    assertEquals("KeliverBridge: ShopActions.openRoute: no answer within 30 s", e.message)
  }

  @Test fun swiftsCallbacksServeEachKindOfCall() = runTest {
    var picker: BridgeCall? = null
    var pickerClosed = false
    var shared = false
    val tracked = mutableListOf<String>()
    val callbacks = object : ShopActionsCallbacks {
      override fun openRoute(route: String, call: BridgeCall, done: (Boolean) -> Unit, fail: (String) -> Unit) = done(true)
      override fun share(text: String, url: String?, call: BridgeCall, done: () -> Unit, fail: (String) -> Unit) {
        shared = true
        done()
      }
      override fun pickAddress(request: AddressRequest, call: BridgeCall, done: (AddressResult) -> Unit, fail: (String) -> Unit) {
        // The user is still picking; the picker closes when the guest stops waiting.
        picker = call
        call.onCancel { pickerClosed = true }
      }
      override fun findCoupon(code: String, call: BridgeCall, done: (String?) -> Unit, fail: (String) -> Unit) = fail("no coupon $code")
      override fun track(event: String, properties: Map<String, String>) {
        tracked += event
      }
    }
    val service = ShopActionsCallbackAdapter(callbacks, BridgeGuard("ShopActions", StandardTestDispatcher(testScheduler)))
    assertTrue(service.openRoute("/cart"))
    service.share("hi", "https://example.com")
    assertTrue(shared)
    val e = assertFailsWith<BridgeCallFailed> { service.findCoupon("X") }
    assertEquals("KeliverBridge: ShopActions.findCoupon: no coupon X", e.message)
    // pickAddress has no limit (@BridgeTimeout(seconds = 0)): the guest's cancel ends it.
    val picking = async { service.pickAddress(AddressRequest(Purpose.DELIVERY, null)) }
    runCurrent()
    picking.cancel()
    advanceUntilIdle()
    assertTrue(picker!!.isCancelled)
    assertTrue(pickerClosed)
    service.track("viewed", emptyMap())
    advanceUntilIdle()
    assertEquals(listOf("viewed"), tracked)
  }

  @Test fun theGuestGetsTheHostsFailureBack() = runTest {
    // Zipline 1.22 delivers a host's throwable as a ZiplineException with its stack-trace text.
    val hostText = BridgeCallFailed("ShopActions", "openRoute", "no answer within 30 s").stackTraceToString().trim()
    val other = ZiplineException("app.cash.zipline.ZiplineApiMismatchException: no such method")
    val taken = ShopActionsTaken(
      object : ShopActionsService, ShopActions by unused() {
        override suspend fun openRoute(route: String): Boolean = throw ZiplineException(hostText)
        override suspend fun findCoupon(code: String): String? = throw other
      },
    )
    val e = assertFailsWith<BridgeCallFailed> { taken.openRoute("/cart") }
    assertEquals("KeliverBridge: ShopActions.openRoute: no answer within 30 s", e.message)
    assertSame(other, assertFailsWith<ZiplineException> { taken.findCoupon("X") })
  }

  @Test fun bindRegistersBothKindsOfServiceWithARealZipline() {
    // The Zipline compiler plugin rewrote bind<ShopActionsService>(…) in the generated code:
    // without that rewrite, bind throws. A real Zipline (JVM, QuickJS) reports each binding.
    val bound = mutableListOf<Pair<String, ZiplineService>>()
    val listener = object : EventListener() {
      override fun bindService(zipline: Zipline, name: String, service: ZiplineService) {
        bound += name to service
      }
    }
    val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    try {
      runBlocking(dispatcher) {
        val zipline = Zipline.create(dispatcher, eventListener = listener)
        try {
          ShopActionsBridge.bind(zipline, unused())
        } finally {
          zipline.close()
        }
        val second = Zipline.create(dispatcher, eventListener = listener)
        try {
          ShopActionsBridge.bind(second, unusedCallbacks(), dispatcher)
        } finally {
          second.close()
        }
      }
    } finally {
      dispatcher.close()
    }
    // Zipline binds its own zipline/host service too.
    val ours = bound.filter { it.first == "ShopActions" }.map { it.second }
    assertEquals(2, ours.size)
    assertTrue(ours[0] is ShopActionsGuardedService)
    assertTrue(ours[1] is ShopActionsCallbackAdapter)
  }

  private fun unused(): ShopActions = object : ShopActions {
    override suspend fun openRoute(route: String): Boolean = error("unused")
    override suspend fun share(text: String, url: String?) = error("unused")
    override suspend fun pickAddress(request: AddressRequest): AddressResult = error("unused")
    override suspend fun findCoupon(code: String): String? = error("unused")
    override fun track(event: String, properties: Map<String, String>) = error("unused")
  }

  private fun unusedCallbacks(): ShopActionsCallbacks = object : ShopActionsCallbacks {
    override fun openRoute(route: String, call: BridgeCall, done: (Boolean) -> Unit, fail: (String) -> Unit) = error("unused")
    override fun share(text: String, url: String?, call: BridgeCall, done: () -> Unit, fail: (String) -> Unit) = error("unused")
    override fun pickAddress(request: AddressRequest, call: BridgeCall, done: (AddressResult) -> Unit, fail: (String) -> Unit) = error("unused")
    override fun findCoupon(code: String, call: BridgeCall, done: (String?) -> Unit, fail: (String) -> Unit) = error("unused")
    override fun track(event: String, properties: Map<String, String>) = error("unused")
  }
}
