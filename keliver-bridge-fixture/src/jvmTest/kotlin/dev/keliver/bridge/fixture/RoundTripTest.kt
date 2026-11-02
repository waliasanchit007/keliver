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

import app.cash.zipline.Zipline
import app.cash.zipline.loader.DefaultFreshnessCheckerNotFresh
import app.cash.zipline.loader.LoadResult
import app.cash.zipline.loader.ManifestVerifier
import app.cash.zipline.loader.ZiplineHttpClient
import app.cash.zipline.loader.ZiplineLoader
import dev.keliver.bridge.BridgeCall
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.ByteString
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/**
 * B5b (docs/BRIDGE_CODEGEN_DESIGN.md, section "B5", §2): the first calls from a guest to a host
 * through the generated bridges. keliver-bridge-fixture-guest (Kotlin/JS, built by the Zipline
 * plugin) is loaded through ZiplineLoader into a real Zipline (QuickJS on the JVM), with the host
 * side bound before its code runs, as the hosts do; the guest takes the bridges and answers the
 * test's steps through its RoundTripDriver.
 */
class RoundTripTest {
  private val guestDir = checkNotNull(System.getProperty("keliver.fixtureGuestDir")) { "run through Gradle's jvmTest" }.toPath()

  /** Serves the guest's development build from disk, by file name. */
  private class Files(private val dir: Path) : ZiplineHttpClient() {
    override suspend fun download(url: String, requestHeaders: List<Pair<String, String>>): ByteString =
      FileSystem.SYSTEM.read(dir / url.substringAfterLast('/')) { readByteString() }
  }

  /**
   * Loads the guest with [bind] binding the host's side ([main] standing in for the app's main
   * thread), runs each of [steps] in the guest, then [beforeClose] while the guest is still open
   * (closing it cancels the host's calls, so what the guest's own cancel did is checked there),
   * and returns what the guest answered.
   */
  private fun roundTrip(
    bind: (Zipline, CoroutineContext) -> Unit,
    vararg steps: String,
    beforeClose: suspend () -> Unit = {},
  ): List<String> {
    val ziplineThread = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    try {
      return runBlocking(ziplineThread) {
        val loader = ZiplineLoader(ziplineThread, ManifestVerifier.NO_SIGNATURE_CHECKS, Files(guestDir))
        val result = loader.loadOnce("fixture-guest", DefaultFreshnessCheckerNotFresh, "https://fixture.invalid/manifest.zipline.json") {
          bind(it, main)
        }
        val zipline = (result as? LoadResult.Success)?.zipline ?: error("the guest did not load: $result")
        try {
          val driver = zipline.take<RoundTripDriver>(RoundTripDriver.NAME)
          steps.map { withTimeout(20_000) { driver.run(it) } }.also { beforeClose() }
        } finally {
          zipline.close()
        }
      }
    } finally {
      ziplineThread.close()
      main.close()
    }
  }

  private val waitForever = object : Waits {
    override suspend fun waitBriefly(): String = awaitCancellation()
  }

  @Test fun eachKindOfCallReachesTheAppsImplementationAndBack() {
    val shared = mutableListOf<String>()
    val tracked = CompletableDeferred<String>()
    val pickCancelled = CompletableDeferred<Unit>()
    val impl = object : ShopActions {
      override suspend fun openRoute(route: String) = route == "/cart"
      override suspend fun share(text: String, url: String?) {
        shared += "$text $url"
      }
      override suspend fun pickAddress(request: AddressRequest): AddressResult =
        if (request.purpose == Purpose.DELIVERY) {
          try {
            awaitCancellation()
          } finally {
            pickCancelled.complete(Unit)
          }
        } else {
          AddressResult("1 Main St", listOf(request.purpose.name, request.prefill ?: "-"))
        }
      override suspend fun findCoupon(code: String): String? = if (code == "WELCOME") "10OFF" else null
      override fun track(event: String, properties: Map<String, String>) {
        tracked.complete("$event $properties")
      }
    }
    val answers = roundTrip(
      { zipline, main ->
        ShopActionsBridge.bind(zipline, impl, main)
        WaitsBridge.bind(zipline, waitForever, main)
      },
      "openRoute", "share", "pickAddress", "findCoupon", "findCouponFound", "track", "waitBriefly", "cancelPick",
      beforeClose = {
        // Before the guest closes: closing would cancel the host's call by itself.
        withTimeout(5_000) { pickCancelled.await() }
        assertEquals("viewed {item=beans}", withTimeout(5_000) { tracked.await() }, "fire and forget, run on the host")
      },
    )
    assertEquals(
      listOf(
        "ok true",
        "ok",
        "ok 1 Main St [BILLING, 1 Main]",
        "ok null",
        "ok 10OFF",
        "ok",
        "failed KeliverBridge: Waits.waitBriefly: no answer within 1 s",
        "ok null",
      ),
      answers,
    )
    assertEquals(listOf("hello null"), shared)
  }

  @Test fun aFailureOnTheHostReachesTheGuestAsOneLine() {
    val impl = object : ShopActions {
      override suspend fun openRoute(route: String): Boolean = error("token=secret")
      override suspend fun share(text: String, url: String?) = throw NotImplementedError("secret")
      override suspend fun pickAddress(request: AddressRequest): AddressResult = error("unused")
      override suspend fun findCoupon(code: String): String? = error("unused")
      override fun track(event: String, properties: Map<String, String>) = Unit
    }
    val logged = mutableListOf<String>()
    val answers = roundTrip(
      { zipline, main ->
        ShopActionsBridge.bind(zipline, impl, main) { synchronized(logged) { logged += it } }
        WaitsBridge.bind(zipline, waitForever, main)
      },
      "openRoute",
      "share",
      "rawOpenRoute",
    )
    assertEquals(
      listOf(
        "failed KeliverBridge: ShopActions.openRoute: threw IllegalStateException",
        "failed KeliverBridge: ShopActions.share: threw NotImplementedError",
      ),
      answers.take(2),
    )
    // The whole text Zipline gave the guest for a failure, stack trace included, holds the one
    // line and nothing of the app's message: the guard throws BridgeCallFailed with no cause.
    val raw = answers[2]
    assertTrue(raw.startsWith("raw ") && "KeliverBridge: ShopActions.openRoute: threw IllegalStateException" in raw, raw)
    assertTrue(answers.none { "secret" in it }, "the host's message stays on the host: $raw")
    assertTrue(synchronized(logged) { logged.any { "token=secret" in it } }, "and in the host's log: $logged")
  }

  @Test fun swiftStyleCallbacksAnswerOnTheMainContext() {
    val threads = java.util.Collections.synchronizedMap(mutableMapOf<String, Thread>())
    fun on(what: String) {
      threads[what] = Thread.currentThread()
    }
    val pickerClosed = CompletableDeferred<Boolean>()
    val tracked = CompletableDeferred<Unit>()
    val callbacks = object : ShopActionsCallbacks {
      override fun openRoute(route: String, call: BridgeCall, done: (Boolean) -> Unit, fail: (String) -> Unit) {
        on("openRoute")
        done(route == "/cart")
      }
      override fun share(text: String, url: String?, call: BridgeCall, done: () -> Unit, fail: (String) -> Unit) {
        on("share")
        done()
      }
      override fun pickAddress(request: AddressRequest, call: BridgeCall, done: (AddressResult) -> Unit, fail: (String) -> Unit) {
        if (request.purpose == Purpose.DELIVERY) {
          on("pickAddress (left open)")
          call.onCancel {
            on("onCancel")
            pickerClosed.complete(call.isCancelled)
          }
        } else {
          on("pickAddress")
          done(AddressResult("2 High St", emptyList()))
        }
      }
      override fun findCoupon(code: String, call: BridgeCall, done: (String?) -> Unit, fail: (String) -> Unit) {
        on("findCoupon")
        fail("no coupon $code")
      }
      override fun track(event: String, properties: Map<String, String>) {
        on("track")
        tracked.complete(Unit)
      }
    }
    var mainThread: Thread? = null
    val answers = roundTrip(
      { zipline, main ->
        runBlocking(main) { mainThread = Thread.currentThread() }
        ShopActionsBridge.bind(zipline, callbacks, main)
        WaitsBridge.bind(zipline, waitForever, main)
      },
      "openRoute",
      "share",
      "pickAddress",
      "findCoupon",
      "track",
      "cancelPick",
      beforeClose = {
        // Before the guest closes: closing would cancel the picker's call by itself.
        assertTrue(withTimeout(5_000) { pickerClosed.await() }, "onCancel ran when the guest stopped waiting")
        withTimeout(5_000) { tracked.await() }
      },
    )
    assertEquals(listOf("ok true", "ok", "ok 2 High St []", "failed KeliverBridge: ShopActions.findCoupon: no coupon NONE", "ok", "ok null"), answers)
    assertEquals(
      setOf("openRoute", "share", "pickAddress", "findCoupon", "track", "pickAddress (left open)", "onCancel"),
      threads.keys,
    )
    assertEquals(setOf(checkNotNull(mainThread)), threads.values.toSet(), "every callback, and onCancel, on the context bind was given: $threads")
  }
}
