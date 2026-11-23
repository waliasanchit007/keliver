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
package dev.keliver.portal.render

import kotlin.test.Test
import kotlinx.coroutines.test.runTest

/** K7 — arithmetic parity on wasm, the editor's target ([ArithParity]). */
class ArithParityTest {
  @Test fun everyLiteralArgumentComputesWhatTheDeviceComputes() = runTest { ArithParity.everyLiteralArgumentComputesWhatTheDeviceComputes() }

  @Test fun screenBindingArgumentsAreComputedFromTheirMocks() = runTest { ArithParity.screenBindingArgumentsAreComputedFromTheirMocks() }
}
