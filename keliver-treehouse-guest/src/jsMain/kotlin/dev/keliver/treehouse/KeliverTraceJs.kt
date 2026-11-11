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
// Zipline's internals, here only (see takeKeliverTrace).
@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package dev.keliver.treehouse

import app.cash.zipline.Zipline
import app.cash.zipline.theOnlyZipline

/**
 * Asks Zipline which services the host bound, so a host without KeliverTrace (every store build)
 * is never called, not even once.
 *
 * The guest's Zipline is the one its `main` made with `Zipline.get(itsSerializersModule)`, read
 * from Zipline's internal `theOnlyZipline`: calling [Zipline.get] here would have to name that
 * same module, which this library cannot know (a different one throws). Zipline's internals are
 * reached with the suppression, as [prepareEnvironment] reaches into kotlinx.coroutines; a Zipline
 * (1.28 today) that renames them fails this module's compilation, and one that fails at run time
 * turns the profile off.
 */
internal actual fun takeKeliverTrace(): KeliverTrace? = try {
  val zipline: Zipline? = theOnlyZipline
  if (zipline != null && KELIVER_TRACE in zipline.clientNames) {
    zipline.take<KeliverTrace>(KELIVER_TRACE)
  } else {
    null
  }
} catch (e: Throwable) {
  // Not a Zipline guest, or one this lookup cannot reach: no frame profile, and nothing else lost.
  null
}
