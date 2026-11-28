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
package dev.keliver.compose.core.composeui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.keliver.compose.extras.RemoteObjectBinding
import dev.keliver.compose.foundation.pager.PagerState as GuestPagerState
import dev.keliver.compose.foundation.pager.rememberPagerState as guestRememberPagerState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * G2, remote objects (MS4-43; docs/COMPOSE_GENERIC_MECHANISMS.md section 1.2): the generated guest
 * proxy of `PagerState` drives a real `HorizontalPager` on the host. Guest and host are joined by
 * the real protocol, as JSON text: the guest's composition emits `ExtraRemoteObject` (the proxy's
 * wire is its property), the host's reports come back as its event, through the same adapters a
 * Zipline guest uses. Nothing here reaches into the other side's memory except the assertions.
 */
@OptIn(ExperimentalTestApi::class)
class RemoteObjectTest {
  /** One guest, its proxy, and the host's events on their way back to it. */
  private class Guest(val host: GuestOnHost, val tap: EventTap, val proxy: GuestPagerState)

  private fun startGuest(registry: SaveableStateRegistry? = null): Guest {
    lateinit var proxy: GuestPagerState
    val tap = EventTap()
    val host = guestOnHost(tap, saveableStateRegistry = registry) {
      proxy = guestRememberPagerState(pageCount = { 5 })
      RemoteObjectBinding(proxy.remote)
    }
    return Guest(host, tap, proxy)
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

  /** The host's side: the guest's tree, and a real pager on the state the guest's commands run on. */
  private class HostSide(guest: Guest) {
    var guest by mutableStateOf(guest)
    var remoteObjects by mutableStateOf(RemoteObjects())
    var real: PagerState? = null
  }

  private fun ComposeUiTest.setHostContent(side: HostSide) {
    setContent {
      CompositionLocalProvider(LocalRemoteObjects provides side.remoteObjects) {
        Column {
          Box(Modifier.size(1.dp)) { side.guest.host.root.Render(null) }
          val state = side.remoteObjects[side.guest.proxy.remote.id] as? PagerState
          if (state != null) {
            side.real = state
            HorizontalPager(state, Modifier.size(100.dp)) { Box(Modifier.fillMaxSize()) }
          }
        }
      }
    }
  }

  private fun fieldKeys(events: List<dev.keliver.protocol.Event>): Set<String> =
    events.flatMap { e -> e.args.flatMap { a -> (a as? JsonObject)?.get("fields")?.jsonObject?.keys.orEmpty() } }.toSet()

  @Test fun pagerStateDrivenThroughTheRealProtocol() = runComposeUiTest {
    val guest = startGuest()
    val side = HostSide(guest)
    setHostContent(side)
    sync(guest)

    // A field is observed from its first read; the restorable fields (currentPage, its offset) from the start.
    assertEquals(0, guest.proxy.currentPage)
    syncUntil(guest, "the host binds a real PagerState and reports the observed fields") { side.real != null && guest.tap.sent.isNotEmpty() }
    // The wire is JSON text: the property the guest sent carries the class tag, the arguments and the observed fields.
    val sent = guest.host.wire.joinToString("\n")
    assertTrue("\"observed\":[" in sent && "\"type\":1" in sent, "the observed fields crossed: $sent")
    assertTrue("\"pageCount\":5" in sent, "the factory's lambda was sampled and sent: $sent")
    assertEquals(5, side.real!!.pageCount)

    // An awaited command returns when the host's animateScrollToPage returned.
    val call = CoroutineScope(Dispatchers.Unconfined).async { guest.proxy.animateScrollToPage(3) }
    syncUntil(guest, "animateScrollToPage(3) returns") { call.isCompleted }
    sync(guest)
    assertEquals(3, side.real!!.currentPage, "the host's pager is on page 3")
    assertEquals(3, guest.proxy.currentPage, "the guest saw it")
    assertEquals(0, guest.proxy.remote.wire.commands.size, "the acknowledged command left the wire")

    // A command that is not awaited, and the same field read back.
    guest.proxy.requestScrollToPage(1)
    syncUntil(guest, "requestScrollToPage(1) reaches the guest") { guest.proxy.currentPage == 1 }
    assertEquals(1, side.real!!.currentPage)

    // Dirty observation: a field read later adds itself to the property, and is reported from then on.
    val before = guest.proxy.remote.wire.observed.size
    assertEquals(false, guest.proxy.canScrollForward, "no report of a field yet when it is first read")
    syncUntil(guest, "the new field is observed") { guest.proxy.remote.wire.observed.size == before + 1 }
    side.real!!.requestScrollToPage(4)
    syncUntil(guest, "a host-side change reaches the guest") { guest.proxy.currentPage == 4 }

    // Reports carried only observed fields, never every field of the state.
    val observed = guest.proxy.remote.wire.observed.map { it.toString() }.toSet()
    assertTrue(fieldKeys(guest.tap.sent).isNotEmpty() && observed.containsAll(fieldKeys(guest.tap.sent)), "reported ${fieldKeys(guest.tap.sent)} of observed $observed")
    assertTrue(observed.size < 10, "only the fields read were observed: $observed")
  }

  @Test fun eachCommandRunsOnceAndIsAcknowledged() = runComposeUiTest {
    val guest = startGuest()
    val side = HostSide(guest)
    setHostContent(side)
    sync(guest)
    assertEquals(0, guest.proxy.currentPage)
    syncUntil(guest, "the host binds a real PagerState") { side.real != null }
    guest.proxy.requestScrollToPage(2)
    guest.proxy.requestScrollToPage(3)
    syncUntil(guest, "both commands were acknowledged") { guest.proxy.remote.wire.commands.isEmpty() }
    syncUntil(guest, "the last one won") { guest.proxy.currentPage == 3 }
    assertEquals(3, side.real!!.currentPage)
  }

  /**
   * Process death: the host's state dies with the process, and so does the guest, but the guest's saved
   * state (which the host app persists) does not. The factory saved the host's last reported page; the
   * new guest sends it as the host factory's `initialPage`, and the new host creates its pager there.
   */
  @Test fun theStateRestoresAfterProcessDeath() = runComposeUiTest {
    val registry = SaveableStateRegistry(null) { true }
    val first = startGuest(registry)
    val side = HostSide(first)
    setHostContent(side)
    sync(first)
    syncUntil(first, "the host binds a real PagerState") { side.real != null }
    val call = CoroutineScope(Dispatchers.Unconfined).async { first.proxy.animateScrollToPage(3) }
    syncUntil(first, "animateScrollToPage(3) returns") { call.isCompleted }
    syncUntil(first, "the guest saw page 3") { first.proxy.currentPage == 3 }

    // Save the guest's state, and bring up a new guest and a new host from what was saved.
    val saved = registry.performSave()
    assertTrue(saved.isNotEmpty(), "the guest saved the pager's last reported position")
    val second = startGuest(SaveableStateRegistry(saved) { true })
    assertEquals("3", second.proxy.remote.wire.init.getValue("initialPage").jsonPrimitive.content, "the new guest sends the saved page as the factory's argument")
    // The old process' UI is gone: the new guest's tree and a new host state replace it.
    side.real = null
    side.remoteObjects = RemoteObjects()
    side.guest = second
    sync(second)
    syncUntil(second, "the new host binds a pager") { side.real != null }
    assertEquals(3, side.real!!.currentPage, "the new host created its pager on page 3")
    syncUntil(second, "the new guest reads the restored page") { second.proxy.currentPage == 3 }
  }
}
