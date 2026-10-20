package dev.keliver.portal.render

import kotlin.js.Promise
import kotlin.test.Test
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.promise

/**
 * K7 — arithmetic parity on js, the device guest's target ([ArithParity]).
 * kotlinx-coroutines-test is kept out of the js test compilation (it crashes
 * the Kotlin/JS IR backend here), so each test returns a Promise.
 */
@OptIn(DelicateCoroutinesApi::class)
class ArithParityJsTest {
  @Test fun everyLiteralArgumentComputesWhatTheDeviceComputes(): Promise<Unit> =
    GlobalScope.promise { ArithParity.everyLiteralArgumentComputesWhatTheDeviceComputes() }

  @Test fun screenBindingArgumentsAreComputedFromTheirMocks(): Promise<Unit> =
    GlobalScope.promise { ArithParity.screenBindingArgumentsAreComputedFromTheirMocks() }
}
