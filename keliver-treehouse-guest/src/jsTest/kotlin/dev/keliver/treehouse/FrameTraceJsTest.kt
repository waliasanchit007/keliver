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
package dev.keliver.treehouse

import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlin.test.Test

class FrameTraceJsTest {
  @Test fun aFailureThatIsNotAKotlinExceptionEndsItToo() {
    // In QuickJS a failing call can surface as a plain JavaScript error: it must not reach the
    // frame, and the host is not asked again.
    var calls = 0
    val failing = object : KeliverTrace {
      override fun composed() {
        calls++
        js("throw new TypeError('not a Kotlin exception')")
      }
    }
    val frameTrace = FrameTrace(failing)

    repeat(3) { frameTrace.composed() }

    assertThat(calls).isEqualTo(1)
  }
}
