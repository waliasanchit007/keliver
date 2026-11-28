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
@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalTestApi::class)

package dev.keliver.compose.core.composeui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.keliver.compose.foundation.pager.PagerState as GuestPagerState
import dev.keliver.compose.foundation.pager.rememberPagerState as guestRememberPagerState
import dev.keliver.compose.material3.TimeInput
import dev.keliver.compose.material3.TimePickerState as GuestTimePickerState
import dev.keliver.compose.material3.rememberTimePickerState as guestRememberTimePickerState
import dev.keliver.compose.remote.RemoteCommandException
import dev.keliver.compose.remote.RemoteObjectWire
import dev.keliver.compose.remote.RemoteReport
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * G2, remote objects (MS4-43; docs/COMPOSE_GENERIC_MECHANISMS.md section 1.2).
 *
 * Part one drives the generated `TimePickerState` through its widget, `TimeInput`, over the real protocol:
 * the guest's composition emits the widget (the proxy's wire is its property, the host's report its
 * event), as JSON text through the same adapters a Zipline guest uses; the renderer makes the real state.
 * Part two puts the generated `PagerState` proxy and dispatcher through the failure, cancellation and
 * restore cases, with the guest's wire and the host's reports passed as JSON text in one composition
 * (no generated product widget takes a pager yet: MS4-15).
 */
class RemoteObjectTest {
  @AfterTest fun resetProbe() {
    remoteStateProbe = null
  }

  // ---------------------------------------------------------------------------------------------
  // Part one: through the widget, over the real protocol.

  private class Guest(val host: GuestOnHost, val tap: EventTap, val states: List<GuestTimePickerState>)

  private class Real {
    val byId = mutableMapOf<String, TimePickerState>()
    val created = mutableListOf<String>()
  }

  private fun probe(real: Real) {
    remoteStateProbe = { id, s ->
      if (s is TimePickerState) {
        if (id !in real.byId) real.created += id
        real.byId[id] = s
      }
    }
  }

  private fun startGuest(registry: SaveableStateRegistry? = null, body: @Composable (List<GuestTimePickerState>) -> Unit): Guest {
    val states = mutableListOf<GuestTimePickerState>()
    val tap = EventTap()
    val host = guestOnHost(tap, saveableStateRegistry = registry) {
      val s1 = guestRememberTimePickerState(initialHour = 3, initialMinute = 40)
      val s2 = guestRememberTimePickerState(initialHour = 5, initialMinute = 10)
      if (states.isEmpty()) {
        states += s1
        states += s2
      }
      body(listOf(s1, s2))
    }
    return Guest(host, tap, states)
  }

  private fun ComposeUiTest.hostContent(guest: () -> Guest) {
    setContent { Box(Modifier.size(400.dp, 300.dp)) { guest().host.root.Render(null) } }
  }

  private fun ComposeUiTest.sync(guest: Guest) {
    repeat(3) {
      Snapshot.sendApplyNotifications()
      guest.host.pump()
      waitForIdle()
    }
  }

  private fun ComposeUiTest.syncUntil(guest: Guest, what: String, done: () -> Boolean) {
    repeat(300) {
      if (done()) return
      sync(guest)
    }
    assertTrue(done(), what)
  }

  private fun fieldKeys(events: List<dev.keliver.protocol.Event>, key: String = "fields"): Set<String> =
    events.flatMap { e -> e.args.flatMap { a -> (a as? JsonObject)?.get(key)?.jsonObject?.keys.orEmpty() } }.toSet()

  @Test fun theStateBindsThroughItsWidgetFromTheFirstFrame() = runComposeUiTest {
    val real = Real().also(::probe)
    val guest = startGuest { s -> TimeInput(s[0]) }
    hostContent { guest }
    // The binding is the widget's property: the real state exists after the first batch, with no effect a frame late.
    sync(guest)
    assertEquals(1, real.byId.size, "the renderer made the real state")
    val wire = guest.host.wire.joinToString("\n")
    assertTrue("\"initialHour\":3" in wire && "\"initialMinute\":40" in wire, "the factory's arguments crossed as JSON: $wire")
    val real0 = real.byId.values.single()
    assertEquals(3, real0.hour)
    assertEquals(40, real0.minute)
    // A field reads its initial value before the host's first report (L5), and is observed from its first read.
    assertEquals(3, guest.states[0].hour)
    assertEquals(0, guest.states[0].remote.wire.observed.size, "a read records the field outside the read; the binding's effect observes it")
    syncUntil(guest, "the field is observed") { guest.states[0].remote.wire.observed.size == 1 }
    real0.hour = 8
    syncUntil(guest, "a host-side change reaches the guest") { guest.states[0].hour == 8 }
    assertTrue("\"observed\"" in guest.host.wire.joinToString("\n"))
  }

  @Test fun aStateWithNoWidgetFailsItsCommandsWithAClearError() = runComposeUiTest {
    val real = Real().also(::probe)
    // s[1] is never passed to a widget.
    val guest = startGuest { s -> TimeInput(s[0]) }
    hostContent { guest }
    sync(guest)
    val e = assertFailsWith<RemoteCommandException> { guest.states[1].hour = 9 }
    assertTrue("TimePickerState is not bound to a widget in this host" in e.message.orEmpty(), e.message)
    assertEquals(1, real.byId.size, "only the bound state exists on the host")
  }

  @Test fun aSettablePrimitiveIsAVarThatCrossesAsACommandThatIsNotAwaited() = runComposeUiTest {
    val real = Real().also(::probe)
    val guest = startGuest { s -> TimeInput(s[0]) }
    hostContent { guest }
    sync(guest)
    val real0 = real.byId.values.single()
    guest.states[0].hour = 9
    assertEquals(9, guest.states[0].hour, "the new value reads back at once")
    assertEquals(1, guest.states[0].remote.wire.commands.size)
    syncUntil(guest, "the host's state is set") { real0.hour == 9 }
    syncUntil(guest, "the command was acknowledged") { guest.states[0].remote.wire.commands.isEmpty() }
  }

  @Test fun aFieldReadInsideSnapshotFlowIsObserved() = runComposeUiTest {
    val real = Real().also(::probe)
    val seen = mutableListOf<Int>()
    val guest = startGuest { s ->
      TimeInput(s[0])
      // snapshotFlow runs its block in a read-only snapshot: the first read of a field writes no Compose state there.
      LaunchedEffect(Unit) { snapshotFlow { s[0].minute }.collect { seen += it } }
    }
    hostContent { guest }
    sync(guest)
    assertEquals(listOf(40), seen, "the flow read the field's initial value without failing")
    syncUntil(guest, "the field is observed") { guest.states[0].remote.wire.observed.isNotEmpty() }
    real.byId.values.single().minute = 42
    syncUntil(guest, "the flow sees the host's change") { seen.lastOrNull() == 42 }
  }

  @Test fun oneBindingNodeSwitchingBetweenTwoProxiesGetsTheirOwnStates() = runComposeUiTest {
    val real = Real().also(::probe)
    var which by mutableStateOf(0)
    val guest = startGuest { s -> TimeInput(s[which]) }
    hostContent { guest }
    sync(guest)
    assertEquals(1, real.byId.size)
    which = 1
    syncUntil(guest, "the second proxy has its own real state") { real.byId.size == 2 }
    val (id0, id1) = real.created
    assertEquals(3, real.byId.getValue(id0).hour)
    assertEquals(5, real.byId.getValue(id1).hour, "a new state, made from the second proxy's arguments")
    guest.states[1].hour = 11
    syncUntil(guest, "the command ran on the second state") { real.byId.getValue(id1).hour == 11 }
    assertEquals(3, real.byId.getValue(id0).hour, "and not on the first")
  }

  @Test fun aScrollWithNoReadsSendsNoFieldReports() = runComposeUiTest {
    val real = Real().also(::probe)
    val guest = startGuest { s -> TimeInput(s[0]) }
    hostContent { guest }
    sync(guest)
    val real0 = real.byId.values.single()
    val before = guest.tap.sent.size
    // The host's state changes on every frame, as a fling does; the screen reads nothing.
    for (h in 4..20) {
      real0.hour = h % 24
      sync(guest)
    }
    assertEquals(emptySet(), fieldKeys(guest.tap.sent.drop(before)), "no field is reported unless the screen reads it")
    assertEquals(emptySet(), fieldKeys(guest.tap.sent.drop(before), "settled"), "and the restorable ones wait for the state to be still")
    // Still for a while: one settled report, which carries the newest value for the saver.
    mainClock.advanceTimeBy(SETTLE_MILLIS + 100)
    syncUntil(guest, "the settled value arrives") { fieldKeys(guest.tap.sent.drop(before), "settled").isNotEmpty() }
    assertEquals(emptySet(), fieldKeys(guest.tap.sent.drop(before)), "still no per-frame field reports")
  }

  @Test fun theStateRestoresAfterProcessDeath() = runComposeUiTest {
    val real = Real().also(::probe)
    val registry = SaveableStateRegistry(null) { true }
    var guest by mutableStateOf(startGuest(registry) { s -> TimeInput(s[0]) })
    setContent { Box(Modifier.size(400.dp, 300.dp)) { guest.host.root.Render(null) } }
    sync(guest)
    val real0 = real.byId.values.single()
    real0.hour = 14
    real0.minute = 25
    // The screen reads nothing: the host reports the settled position once the state has been still.
    mainClock.advanceTimeBy(SETTLE_MILLIS + 100)
    syncUntil(guest, "the position settled") { "14" in registry.performSave().toString() }
    val saved = registry.performSave()
    assertTrue(saved.isNotEmpty(), "the guest saved the settled position")

    // A new process: a new guest from what was saved, and a new host state from its arguments.
    real.byId.clear()
    val second = startGuest(SaveableStateRegistry(saved) { true }) { s -> TimeInput(s[0]) }
    assertEquals(14, second.states[0].hour, "the field reads the restored value before the host's first report")
    assertEquals("14", second.states[0].remote.wire.init.getValue("initialHour").jsonPrimitive.content)
    guest = second
    sync(second)
    assertEquals(14, real.byId.values.single().hour, "the new host created its state on the restored position")
    assertEquals(25, real.byId.values.single().minute)
  }

  // ---------------------------------------------------------------------------------------------
  // Part two: the pager proxy and dispatcher, failure, cancellation and unknown tags.

  /** The guest's proxy and the host's real pager in one composition, joined by JSON text both ways. */
  private class PagerHarness {
    lateinit var proxy: GuestPagerState
    var real: PagerState? = null
    val reports = mutableListOf<RemoteReport>()
    var pageCount = 5
  }

  private fun ComposeUiTest.pagerContent(h: PagerHarness, restore: SaveableStateRegistry? = null) {
    setContent {
      val proxy = guestRememberPagerState(pageCount = { h.pageCount })
      h.proxy = proxy
      val wire = Json.decodeFromString(RemoteObjectWire.serializer(), Json.encodeToString(RemoteObjectWire.serializer(), proxy.remote.bind()))
      Column {
        val state = rememberPagerState(
          initialPage = wire.init["initialPage"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
          pageCount = { h.pageCount },
        )
        h.real = state
        RemoteHost(wire, state, PagerStateRemote) { report ->
          h.reports += report
          proxy.remote.onHostReport(Json.decodeFromString(RemoteReport.serializer(), Json.encodeToString(RemoteReport.serializer(), report)))
        }
        HorizontalPager(state, Modifier.size(100.dp)) { Box(Modifier.fillMaxSize()) }
      }
    }
  }

  private fun ComposeUiTest.idle() {
    repeat(3) {
      Snapshot.sendApplyNotifications()
      waitForIdle()
    }
  }

  private fun ComposeUiTest.until(what: String, done: () -> Boolean) {
    repeat(300) {
      if (done()) return
      idle()
    }
    assertTrue(done(), what)
  }

  @Test fun aCommandThatThrowsOnTheHostFailsTheGuestsCallAndLeavesTheHostRunning() = runComposeUiTest {
    val h = PagerHarness()
    pagerContent(h)
    idle()
    // Compose's `require`: the offset fraction must be in -0.5..0.5.
    val call = GlobalScope.async(Dispatchers.Unconfined) { runCatching { h.proxy.animateScrollToPage(1, 0.8f) } }
    until("the failure came back") { call.isCompleted }
    val failure = call.getCompleted().exceptionOrNull()
    assertTrue(failure is RemoteCommandException, "the guest's call threw: $failure")
    assertTrue(h.proxy.remote.wire.commands.isEmpty(), "a failed command left the wire")
    // The host was not harmed: the next command runs.
    val next = GlobalScope.async(Dispatchers.Unconfined) { h.proxy.animateScrollToPage(2) }
    until("the next command returns") { next.isCompleted }
    assertEquals(2, h.real!!.currentPage)
  }

  @Test fun aCommandComposeCancelsIsReportedCancelledAndTheGuestsCallThrowsCancellation() = runComposeUiTest {
    val h = PagerHarness()
    pagerContent(h)
    idle()
    mainClock.autoAdvance = false
    var outcome: Throwable? = null
    val first = GlobalScope.launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
      try {
        h.proxy.animateScrollToPage(4)
      } catch (e: Throwable) {
        outcome = e
      }
    }
    // The animation is under way; a second scroll of the same priority interrupts it.
    repeat(8) {
      Snapshot.sendApplyNotifications()
      mainClock.advanceTimeByFrame()
    }
    val second = GlobalScope.async(Dispatchers.Unconfined) { h.proxy.scrollToPage(1) }
    mainClock.autoAdvance = true
    until("the second command returns") { second.isCompleted }
    until("the first was reported") { first.isCompleted }
    assertTrue(outcome is CancellationException, "the guest's call threw CancellationException: $outcome")
    assertTrue(h.reports.any { it.cancelled.isNotEmpty() }, "the host reported it as cancelled, not failed")
    assertTrue(h.proxy.remote.wire.commands.isEmpty(), "neither command stays on the wire")
  }

  @Test fun aGuestSideCancelReachesTheHostAndFreesItsSlot() = runComposeUiTest {
    val h = PagerHarness()
    pagerContent(h)
    idle()
    mainClock.autoAdvance = false
    val job: Job = GlobalScope.launch(Dispatchers.Unconfined) { h.proxy.animateScrollToPage(4) }
    repeat(8) {
      Snapshot.sendApplyNotifications()
      mainClock.advanceTimeByFrame()
    }
    assertTrue(h.real!!.isScrollInProgress, "the host's animation is running")
    job.cancel()
    assertTrue(h.proxy.remote.wire.commands.isEmpty(), "the cancelled command left the wire")
    mainClock.autoAdvance = true
    until("the host's command was cancelled") { !h.real!!.isScrollInProgress }
    assertTrue(h.reports.none { it.cancelled.isNotEmpty() || it.failed.isNotEmpty() }, "nobody waits, so nothing is reported")
  }

  @Test fun aFieldFirstReadInsideSnapshotFlowIsObservedOnThePager() = runComposeUiTest {
    val h = PagerHarness()
    val seen = mutableListOf<Int>()
    pagerContent(h)
    idle()
    val flow = GlobalScope.launch(Dispatchers.Unconfined) { snapshotFlow { h.proxy.settledPage }.collect { seen += it } }
    until("the field is observed") { h.proxy.remote.wire.observed.isNotEmpty() }
    h.real!!.requestScrollToPage(3)
    until("the flow sees the settled page") { seen.lastOrNull() == 3 }
    flow.cancel()
  }

  @Test fun unknownTagsAreHarmless() = runComposeUiTest {
    val h = PagerHarness()
    pagerContent(h)
    idle()
    // A field this host does not know (a newer guest's) is ignored; the others are still reported.
    val unknown = h.proxy.remote.read(999)
    assertNull(unknown)
    val page = h.proxy.currentPage
    until("the known field is reported, the unknown one ignored") { h.proxy.remote.wire.observed.size == 2 && h.reports.any { it.fields.isNotEmpty() } }
    assertTrue(h.reports.all { 999 !in it.fields })
    // A command it does not know is answered with a failure, not a crash.
    val call = GlobalScope.async(Dispatchers.Unconfined) { runCatching { h.proxy.remote.call(999, JsonObject(emptyMap())) } }
    until("the unknown command failed") { call.isCompleted }
    val e = call.getCompleted().exceptionOrNull()
    assertTrue(e is RemoteCommandException && "unknown command 999" in e.message.orEmpty(), "$e")
    assertEquals(0, page)
    assertNotNull(h.real)
  }

  @Test fun aPagerScrollWithNoReadsReportsNoFieldsAndSettlesOnce() = runComposeUiTest {
    val h = PagerHarness()
    pagerContent(h)
    idle()
    val before = h.reports.size
    for (p in listOf(1, 2, 3, 4)) {
      h.real!!.requestScrollToPage(p)
      idle()
    }
    assertTrue(h.reports.drop(before).none { it.fields.isNotEmpty() }, "no per-frame field reports: ${h.reports.drop(before)}")
    mainClock.advanceTimeBy(SETTLE_MILLIS + 100)
    until("the settled position was reported once") { h.reports.drop(before).any { it.settled.isNotEmpty() } }
    assertEquals(JsonPrimitive(4), h.reports.drop(before).last { it.settled.isNotEmpty() }.settled.values.first())
  }
}
