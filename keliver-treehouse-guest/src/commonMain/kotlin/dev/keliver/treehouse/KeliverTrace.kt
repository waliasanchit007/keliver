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
import app.cash.zipline.ZiplineService

/** The name a host binds its timing service under (M0; docs/DEVICE_HOST.md, "Timing marks"). */
internal const val KELIVER_TRACE = "KeliverTrace"

/**
 * The host's timing service, as this library calls it: by name and by shape, as the guest's own
 * `KeliverTiming.mark` does (Zipline matches a service by its name and each function's signature,
 * not by its interface). Only a host's debug and benchmark builds bind it. The host's interface
 * also has `mark(name)`, which this library does not call.
 *
 * The guest frame profile (M4; docs/DEVICE_HOST.md, "The guest frame profile"): the guest calls
 * [composed] once its composition is done, before it sends the change lists that composition made:
 * in each frame (`AppLifecycle.sendFrame`) and in a screen's start (`ZiplineTreehouseUi.start`,
 * whose first composition is sent from there). The host times the call when it arrives. From the
 * frame's start to that moment is the guest's composition; from it to each change list's arrival
 * is the list's encoding, its crossing and its decoding on the host.
 */
internal interface KeliverTrace : ZiplineService {
  fun composed()
}

/**
 * The host's [KeliverTrace], or null when the host binds none (a store build, or any host without
 * the timing marks). Looked up once, when the app's lifecycle starts; never per frame.
 */
internal expect fun takeKeliverTrace(): KeliverTrace?

/**
 * Tells the host where each composition ends (M4). A call that fails ends it, and is never
 * repeated; the frame itself goes on.
 *
 * A host whose KeliverTrace has no [KeliverTrace.composed] (the hosts of tools 0.3.10 to 0.3.18
 * bind one with `mark` only) fails the call with [ZiplineApiMismatchException]: that is expected,
 * and turns the profile off silently. Any other failure (a host whose `composed()` throws, a
 * closed Zipline) is [report]ed once, so the host's console says why its frames lost their split.
 */
internal class FrameTrace(
  private val service: KeliverTrace,
  private val report: (String) -> Unit = ::println,
) {
  private var failed = false

  fun composed() {
    if (failed) return
    try {
      service.composed()
    } catch (e: Throwable) {
      // Not close()d: that would close the host's KeliverTrace, which the guest's marks use too.
      failed = true
      if (e !is ZiplineApiMismatchException) {
        report("Keliver: the guest frame profile is off; KeliverTrace.composed() failed: $e")
      }
    }
  }
}
