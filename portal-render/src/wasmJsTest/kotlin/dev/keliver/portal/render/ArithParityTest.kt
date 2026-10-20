package dev.keliver.portal.render

import kotlin.test.Test
import kotlinx.coroutines.test.runTest

/** K7 — arithmetic parity on wasm, the editor's target ([ArithParity]). */
class ArithParityTest {
  @Test fun everyLiteralArgumentComputesWhatTheDeviceComputes() = runTest { ArithParity.everyLiteralArgumentComputesWhatTheDeviceComputes() }

  @Test fun screenBindingArgumentsAreComputedFromTheirMocks() = runTest { ArithParity.screenBindingArgumentsAreComputedFromTheirMocks() }
}
