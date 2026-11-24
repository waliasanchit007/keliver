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
package dev.keliver.spike

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.keliver.spike.host.PagerStateRemote
import dev.keliver.spike.host.RemoteHost
import dev.keliver.spike.wire.RemoteObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Mechanism 2 (docs/COMPOSE_GENERIC_MECHANISMS.md): the generated guest `PagerState` proxy drives a
 * real `HorizontalPager` through the generated host dispatcher. Everything crosses as JSON text.
 */
@OptIn(ExperimentalTestApi::class)
class RemoteObjectTest {
  private fun text(o: JsonObject): JsonObject = Json.parseToJsonElement(o.toString()).jsonObject

  @Test fun animateScrollToPageAndCurrentPage() = runComposeUiTest {
    val remote = RemoteObject("pager")
    val guest = dev.keliver.spike.compose.foundation.pager.PagerState(remote)
    // A field is observed from its first read, and only the fields read.
    assertEquals(0, guest.currentPage)
    assertEquals(listOf("currentPage"), remote.wire.getValue("obs").jsonArray.map { it.jsonPrimitive.content })

    var wire by mutableStateOf(text(remote.wire))
    lateinit var host: PagerState
    val reports = mutableListOf<JsonObject>()
    setContent {
      host = rememberPagerState(pageCount = { 5 })
      HorizontalPager(host, Modifier.size(100.dp)) { Box(Modifier.fillMaxSize()) }
      RemoteHost(wire, host, PagerStateRemote) { report ->
        reports += report
        remote.onHostReport(text(report))
        wire = text(remote.wire)
      }
    }
    waitForIdle()

    // A suspend command: returns when the host's animateScrollToPage returned.
    val call = CoroutineScope(Dispatchers.Unconfined).async { guest.animateScrollToPage(3) }
    runOnIdle { wire = text(remote.wire) }
    waitUntil(timeoutMillis = 10_000) { call.isCompleted }
    waitForIdle()
    assertEquals(3, host.currentPage)
    assertEquals(3, guest.currentPage)
    // The acknowledged command left the wire.
    assertEquals(0, remote.wire.getValue("cmd").jsonArray.size)

    // A fire-and-forget command, then the same field read back.
    guest.requestScrollToPage(1)
    runOnIdle { wire = text(remote.wire) }
    waitUntil(timeoutMillis = 10_000) { guest.currentPage == 1 }
    assertEquals(1, host.currentPage)
    // Reports carried only the observed field, never every field of the state.
    assertTrue(reports.mapNotNull { it["f"]?.jsonObject }.all { it.keys == setOf("currentPage") })
  }
}
