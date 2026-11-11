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

import app.cash.zipline.ZiplineApiMismatchException
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import dev.keliver.Modifier
import dev.keliver.RedwoodCodegenApi
import dev.keliver.protocol.EventTag
import dev.keliver.protocol.Id
import dev.keliver.protocol.ModifierTag
import dev.keliver.protocol.RedwoodVersion
import dev.keliver.protocol.WidgetTag
import dev.keliver.protocol.guest.GuestProtocolAdapter
import dev.keliver.protocol.guest.ProtocolMismatchHandler
import dev.keliver.protocol.guest.ProtocolWidgetSystemFactory
import dev.keliver.widget.WidgetSystem
import kotlin.test.Test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json

/**
 * M4's composition/encoding split, the guest's half: [StandardAppLifecycle] tells the host's
 * KeliverTrace where each frame's composition ends, between composing and sending its change
 * lists; with no KeliverTrace bound it does nothing per frame.
 *
 * A frame-clock awaiter stands in for a Recomposer (which recomposes and applies in its frame
 * callback), and a frame listener for a UI sending its change list.
 */
class FrameTraceTest {
  private val log = mutableListOf<String>()

  private val trace = object : KeliverTrace {
    override fun composed() {
      log += "composed"
    }
  }

  @Test fun eachFrameReportsTheEndOfItsCompositionBeforeItsChangeLists() {
    val lifecycle = lifecycle { trace }
    lifecycle.start(FakeHost())
    lifecycle.addFrameListener { log += "send changes" }

    repeat(2) { frame ->
      lifecycle.composeInNextFrame("compose $frame")
      lifecycle.sendFrame(frame.toLong())
    }

    assertThat(log).containsExactly(
      "compose 0",
      "composed",
      "send changes",
      "compose 1",
      "composed",
      "send changes",
    )
  }

  @Test fun aFrameWithNothingToComposeIsReportedToo() {
    // The host counts every frame it sends; one that composed nothing still has a composition span.
    val lifecycle = lifecycle { trace }
    lifecycle.start(FakeHost())

    lifecycle.sendFrame(0L)

    assertThat(log).containsExactly("composed")
  }

  @Test fun theHostsTraceIsLookedUpOnceWhenTheLifecycleStarts() {
    var lookups = 0
    val lifecycle = lifecycle {
      lookups++
      trace
    }
    assertThat(lookups).isEqualTo(0)

    lifecycle.start(FakeHost())
    repeat(3) { lifecycle.sendFrame(it.toLong()) }

    assertThat(lookups).isEqualTo(1)
    assertThat(log).containsExactly("composed", "composed", "composed")
  }

  @Test fun withNoKeliverTraceBoundAFrameReportsNothing() {
    var lookups = 0
    val lifecycle = lifecycle {
      lookups++
      null
    }
    lifecycle.start(FakeHost())
    lifecycle.addFrameListener { log += "send changes" }

    repeat(2) { frame ->
      lifecycle.composeInNextFrame("compose $frame")
      lifecycle.sendFrame(frame.toLong())
    }

    // Off: nothing was made for it, so a frame costs one null check; and it is not asked again.
    assertThat(lifecycle.frameTrace).isNull()
    assertThat(lookups).isEqualTo(1)
    assertThat(log).containsExactly("compose 0", "send changes", "compose 1", "send changes")
  }

  @Test fun aHostWhoseKeliverTraceHasNoComposedIsAskedOnce() {
    // The hosts of tools 0.3.10 to 0.3.18 bind KeliverTrace with mark(name) only: Zipline fails the call.
    var calls = 0
    val olderHost = object : KeliverTrace {
      override fun composed() {
        calls++
        throw ZiplineApiMismatchException("no such method (incompatible API versions?)")
      }
    }
    val lifecycle = lifecycle { olderHost }
    lifecycle.start(FakeHost())
    lifecycle.addFrameListener { log += "send changes" }

    repeat(3) { frame ->
      lifecycle.composeInNextFrame("compose $frame")
      lifecycle.sendFrame(frame.toLong())
    }

    assertThat(calls).isEqualTo(1)
    assertThat(lifecycle.frameTrace).isNotNull()
    // The frames go on as without a trace.
    assertThat(log).containsExactly(
      "compose 0",
      "send changes",
      "compose 1",
      "send changes",
      "compose 2",
      "send changes",
    )
  }

  @Test fun aHostWithoutComposedTurnsTheProfileOffSilently() {
    val reports = mutableListOf<String>()
    var calls = 0
    val frameTrace = FrameTrace(
      service = object : KeliverTrace {
        override fun composed() {
          calls++
          throw ZiplineApiMismatchException("no such method (incompatible API versions?)")
        }
      },
      report = { reports += it },
    )

    repeat(3) { frameTrace.composed() }

    assertThat(calls).isEqualTo(1)
    assertThat(reports).isEmpty()
  }

  @Test fun anyOtherFailureTurnsTheProfileOffAndIsReportedOnce() {
    val reports = mutableListOf<String>()
    var calls = 0
    val frameTrace = FrameTrace(
      service = object : KeliverTrace {
        override fun composed() {
          calls++
          throw IllegalStateException("the host's composed() broke")
        }
      },
      report = { reports += it },
    )

    repeat(3) { frameTrace.composed() }

    assertThat(calls).isEqualTo(1)
    assertThat(reports).hasSize(1)
    assertThat(reports.single()).contains("the host's composed() broke")
  }

  @Test fun beforeStartThereIsNoTrace() {
    var lookups = 0
    val lifecycle = lifecycle {
      lookups++
      trace
    }

    assertThat(lifecycle.frameTrace).isNull()
    assertThat(lookups).isEqualTo(0)
  }

  private fun lifecycle(lookup: () -> KeliverTrace?) = StandardAppLifecycle(
    protocolWidgetSystemFactory = UnusedWidgetSystemFactory,
    json = Json,
    widgetVersion = 1U,
  ).apply { keliverTraceLookup = lookup }

  /** Records [entry] in the next frame's callback, as a Recomposer composes in it. */
  private fun StandardAppLifecycle.composeInNextFrame(entry: String) {
    CoroutineScope(Dispatchers.Unconfined).launch(start = CoroutineStart.UNDISPATCHED) {
      frameClock.withFrameNanos { log += entry }
    }
  }
}

private class FakeHost : AppLifecycle.Host {
  override val hostProtocolVersion: RedwoodVersion get() = RedwoodVersion.Unknown

  override fun requestFrame() {
  }

  override fun onUnknownEvent(widgetTag: WidgetTag, tag: EventTag) = error("unexpected")

  override fun onUnknownEventNode(id: Id, tag: EventTag) = error("unexpected")

  override fun handleUncaughtException(exception: Throwable) = throw exception
}

@OptIn(RedwoodCodegenApi::class)
private object UnusedWidgetSystemFactory : ProtocolWidgetSystemFactory {
  override fun create(
    guestAdapter: GuestProtocolAdapter,
    mismatchHandler: ProtocolMismatchHandler,
  ): WidgetSystem<Unit> = error("unexpected")

  override fun <T : Modifier.Element> modifierTagAndSerializationStrategy(
    element: T,
  ): Pair<ModifierTag, SerializationStrategy<T>?> = error("unexpected")
}
