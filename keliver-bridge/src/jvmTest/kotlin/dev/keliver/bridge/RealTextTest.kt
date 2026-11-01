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

// A host is a JVM or Kotlin/Native process. Zipline 1.22 sends its throwable as
// stackTraceToString().trim(), and the guest must find the failure in this platform's real text
// (BridgeCallFailed.fromZiplineMessage matches the qualified class name it begins with).
class RealTextTest {
  @Test fun theGuestFindsTheFailureInThisHostsRealText() {
    val text = BridgeCallFailed("ShopActions", "share", "timed out").stackTraceToString().trim()
    assertEquals(
      "KeliverBridge: ShopActions.share: timed out",
      BridgeCallFailed.fromZiplineMessage(text)?.message,
      "the text was: ${text.lineSequence().first()}",
    )
  }
}
