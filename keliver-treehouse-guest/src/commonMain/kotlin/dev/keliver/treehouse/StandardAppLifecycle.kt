/*
 * Copyright (C) 2023 Square, Inc.
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

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.MonotonicFrameClock
import app.cash.zipline.ZiplineApiMismatchException
import dev.keliver.protocol.EventTag
import dev.keliver.protocol.Id
import dev.keliver.protocol.RedwoodVersion
import dev.keliver.protocol.WidgetTag
import dev.keliver.protocol.guest.ProtocolMismatchHandler
import dev.keliver.protocol.guest.ProtocolWidgetSystemFactory
import dev.keliver.protocol.guest.guestRedwoodVersion
import dev.keliver.treehouse.AppLifecycle.Host
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.Json

public class StandardAppLifecycle(
  internal val protocolWidgetSystemFactory: ProtocolWidgetSystemFactory,
  internal val json: Json,
  internal val widgetVersion: UInt,
) : AppLifecycle {
  private var started = false
  private lateinit var host: Host
  private val frameListeners = mutableListOf<FrameListener>()

  override val guestProtocolVersion: RedwoodVersion
    get() = guestRedwoodVersion

  internal val hostProtocolVersion: RedwoodVersion get() {
    return try {
      host.hostProtocolVersion
    } catch (_: ZiplineApiMismatchException) {
      RedwoodVersion.Unknown
    }
  }

  /**
   * The host's optional features ([Host.hostFeatures]), asked once. A host older than 0.5.0 has no
   * such property: Zipline fails the call, and that host has no optional features.
   *
   * The probe is optional, so any failure means "no features": the mismatch may reach the guest as
   * [ZiplineApiMismatchException], as a plain `ZiplineException` carrying the host's text, or as
   * something else, and none of them may stop a screen from starting. Only cancellation passes.
   * The mismatch is expected and silent; any other failure (a host whose features getter throws) is
   * [report]ed once, so the host's console says why the guest saw no features.
   */
  internal val hostFeatures: Set<String> by lazy {
    try {
      host.hostFeatures.toSet()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      if (!e.isApiMismatch()) report("Keliver: the host's features could not be read; the guest sees none: $e")
      emptySet()
    }
  }

  /** Where [hostFeatures] reports a failure that is not a mismatch; tests replace it. */
  internal var report: (String) -> Unit = ::println

  private fun Exception.isApiMismatch(): Boolean =
    this is ZiplineApiMismatchException || message?.contains(API_MISMATCH_TEXT) == true

  private val broadcastFrameClock = BroadcastFrameClock {
    if (started) {
      host.requestFrame()
    }
  }
  public val frameClock: MonotonicFrameClock get() = broadcastFrameClock

  internal val mismatchHandler: ProtocolMismatchHandler = object : ProtocolMismatchHandler {
    override fun onUnknownEvent(widgetTag: WidgetTag, tag: EventTag) {
      host.onUnknownEvent(widgetTag, tag)
    }

    override fun onUnknownEventNode(id: Id, tag: EventTag) {
      host.onUnknownEventNode(id, tag)
    }
  }

  private val coroutineExceptionHandler = object : CoroutineExceptionHandler {
    override val key: CoroutineContext.Key<*>
      get() = CoroutineExceptionHandler.Key

    override fun handleException(context: CoroutineContext, exception: Throwable) {
      host.handleUncaughtException(exception)
    }
  }

  internal val coroutineScope = CoroutineScope(coroutineExceptionHandler)

  /** How [start] finds the host's [KeliverTrace]; tests replace it. */
  internal var keliverTraceLookup: () -> KeliverTrace? = ::takeKeliverTrace

  /**
   * Where each composition ends, for a host that profiles frames (M4); null when the host binds no
   * [KeliverTrace], and then a frame costs one null check more.
   */
  internal var frameTrace: FrameTrace? = null
    private set

  override fun start(host: Host) {
    check(!started) { "already started" }
    this.started = true
    this.host = host

    prepareEnvironment(coroutineExceptionHandler)
    frameTrace = keliverTraceLookup()?.let(::FrameTrace)
  }

  override fun sendFrame(timeNanos: Long) {
    // Each Recomposer recomposes, and applies to the guest's widgets, in its frame callback, so
    // the frame's composition is done when this returns.
    broadcastFrameClock.sendFrame(timeNanos)
    frameTrace?.composed()
    // Each UI sends the change list its composition made.
    for (frameListener in frameListeners) {
      frameListener.onFrame(timeNanos)
    }
  }

  internal fun addFrameListener(listener: FrameListener) {
    frameListeners += listener
  }

  internal fun removeFrameListener(listener: FrameListener) {
    frameListeners.remove(listener)
  }

  internal fun interface FrameListener {
    fun onFrame(timeNanos: Long)
  }
}

/** Zipline's text for a call the other side does not have, when it arrives as a plain exception. */
private const val API_MISMATCH_TEXT = "incompatible API versions"
