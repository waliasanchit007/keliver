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
import app.cash.zipline.ZiplineException
import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.startsWith
import kotlin.test.Test
import kotlinx.coroutines.CancellationException
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
    val reports = mutableListOf<String>()
    val lifecycle = lifecycle().apply { report = { reports += it } }
    lifecycle.start(FakeHost { throw ZiplineApiMismatchException("no such method (incompatible API versions?)") })

    assertThat(lifecycle.hostFeatures).isEmpty()
    assertThat(reports).isEmpty()
  }

  @Test fun anyOtherFailureOfTheProbeAlsoMeansNoFeatures() {
    // The mismatch may arrive as a plain ZiplineException carrying the host's text, or as anything
    // else: the probe is optional, so none of them may stop a screen from starting.
    val reports = mutableListOf<String>()
    val plain = lifecycle().apply { report = { reports += it } }
    plain.start(FakeHost { throw ZiplineException("no such method (incompatible API versions?)") })
    assertThat(plain.hostFeatures).isEmpty()
    assertThat(reports, "the mismatch is expected: silent").isEmpty()

    // A host whose own features getter fails: still no features, but the console says why, once.
    val other = lifecycle().apply { report = { reports += it } }
    other.start(FakeHost { throw ZiplineException("java.lang.IllegalStateException: boom") })
    assertThat(other.hostFeatures).isEmpty()
    assertThat(other.hostFeatures).isEmpty()
    assertThat(reports.size).isEqualTo(1)
    assertThat(reports.single()).startsWith("Keliver: the host's features could not be read; the guest sees none: ")
    assertThat(reports.single()).contains("boom")

    val local = lifecycle().apply { report = { reports += it } }
    local.start(FakeHost { throw IllegalStateException("bang") })
    assertThat(local.hostFeatures).isEmpty()
    assertThat(reports.size).isEqualTo(2)
    assertThat(reports.last()).contains("bang")
  }

  @Test fun cancellationIsPassedOn() {
    val lifecycle = lifecycle()
    lifecycle.start(FakeHost { throw CancellationException("cancelled") })

    assertFailure { lifecycle.hostFeatures }.isInstanceOf<CancellationException>()
  }
}
