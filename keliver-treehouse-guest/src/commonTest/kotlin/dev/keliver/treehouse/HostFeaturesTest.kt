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
package dev.keliver.treehouse

import app.cash.zipline.ZiplineApiMismatchException
import assertk.assertThat
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import kotlin.test.Test
import kotlinx.serialization.json.Json

/** D2: the guest reads the host's optional features at run time; an older host has none. */
class HostFeaturesTest {
  private fun lifecycle() = StandardAppLifecycle(
    protocolWidgetSystemFactory = UnusedWidgetSystemFactory,
    json = Json,
    widgetVersion = 1U,
  )

  @Test fun theHostsFeaturesAreReadOnce() {
    var calls = 0
    val lifecycle = lifecycle()
    lifecycle.start(
      FakeHost {
        calls++
        listOf("keliver.nav.retainedContainer@1", "other@2")
      },
    )

    assertThat(lifecycle.hostFeatures).containsExactlyInAnyOrder("keliver.nav.retainedContainer@1", "other@2")
    assertThat(lifecycle.hostFeatures).containsExactlyInAnyOrder("keliver.nav.retainedContainer@1", "other@2")
    assertThat(calls).isEqualTo(1)
  }

  @Test fun aHostOlderThanHostFeaturesHasNone() {
    // A host built before 0.5.0 has no hostFeatures: Zipline fails the call. It must not throw.
    val lifecycle = lifecycle()
    lifecycle.start(FakeHost { throw ZiplineApiMismatchException("no such method (incompatible API versions?)") })

    assertThat(lifecycle.hostFeatures).isEmpty()
  }
}
