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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BridgeCallFailedTest {
  @Test fun theMessageIsOneLine() {
    assertEquals(
      "KeliverBridge: ShopActions.share: no address on file",
      BridgeCallFailed("ShopActions", "share", "no address\r\n  on\nfile").message,
    )
    assertEquals("KeliverBridge: ShopActions.share: ", BridgeCallFailed("ShopActions", "share", "\r").message)
  }

  // Zipline 1.22 sends a throwable as stackTraceToString().trim() and the guest's
  // ZiplineException carries that text: on a JVM or Kotlin/Native host it begins with the
  // qualified class name, then the message, then the host's stack frames.
  @Test fun theGuestFindsTheFailureInZiplinesText() {
    val text = "dev.keliver.bridge.BridgeCallFailed: KeliverBridge: ShopActions.share: threw IllegalStateException\n" +
      "\tat dev.keliver.bridge.BridgeGuard.call(BridgeGuard.kt:70)\n\tat …"
    assertEquals(
      "KeliverBridge: ShopActions.share: threw IllegalStateException",
      BridgeCallFailed.fromZiplineMessage(text)?.message,
    )
  }

  @Test fun otherFailuresAreNotTakenForOne() {
    assertNull(BridgeCallFailed.fromZiplineMessage(null))
    assertNull(BridgeCallFailed.fromZiplineMessage("app.cash.zipline.ZiplineException: boom"))
    // The prefix anywhere but at the start of the first line is someone else's text.
    assertNull(BridgeCallFailed.fromZiplineMessage("java.lang.IllegalStateException: dev.keliver.bridge.BridgeCallFailed: KeliverBridge: x.y: z"))
    assertNull(BridgeCallFailed.fromZiplineMessage("boom\ndev.keliver.bridge.BridgeCallFailed: KeliverBridge: x.y: z"))
  }
}
