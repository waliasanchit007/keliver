/*
 * Copyright (C) 2026 Keliver contributors.
 * Licensed under the Apache License, Version 2.0.
 */
@file:OptIn(ExperimentalKeliverNavRetentionApi::class)

package dev.keliver.sample.threeway

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import app.cash.zipline.Zipline
import dev.keliver.capabilities.HostHttp
import dev.keliver.capabilities.HostHttpRequest
import dev.keliver.compose.gatea.protocol.guest.KeliverComposeGateAProtocolWidgetSystemFactory
import dev.keliver.http.HostHttpProvider
import dev.keliver.http.asHostHttp
import dev.keliver.nav.ExperimentalKeliverNavRetentionApi
import dev.keliver.nav.LocalKeliverNavPress
import dev.keliver.sample.threeway.data.Item
import dev.keliver.sample.threeway.data.ItemsPage
import dev.keliver.sample.threeway.data.ThreewayApi
import dev.keliver.sample.threeway.data.ThreewayMarks
import dev.keliver.sample.threeway.data.parseItems
import dev.keliver.treehouse.StandardAppLifecycle
import dev.keliver.treehouse.TreehouseUi
import dev.keliver.treehouse.ZiplineTreehouseUi
import dev.keliver.treehouse.asZiplineTreehouseUi
import kotlin.js.Date
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromDynamic

public fun main() {
  Zipline.get().bind<ThreewayAppService>("app", ThreewayAppServiceImpl())
  Zipline.get().bind<ThreewayGuestStats>("threewayStats", GuestStats)
}

/** The scrolling series' counters (ThreewayGuestStats); counted only when the adapter is wrapped. */
private object GuestStats : ThreewayGuestStats {
  override fun takeStats(): String {
    val s = ProtocolStats
    val json = "{\"creates\":${s.creates},\"props\":${s.propertyChanges},\"mods\":${s.modifierChanges},\"struct\":${s.structural}}"
    s.reset()
    return json
  }
}

private class ThreewayAppServiceImpl : ThreewayAppService {
  override val appLifecycle: StandardAppLifecycle = StandardAppLifecycle(
    // As shipped unless the run asks for a protocol experiment (ProtocolExperiments.kt).
    protocolWidgetSystemFactory = ExperimentWidgetSystemFactory(KeliverComposeGateAProtocolWidgetSystemFactory) { experiments },
    json = Json,
    widgetVersion = 1U,
  )

  override fun launch(): ZiplineTreehouseUi {
    val ui = object : TreehouseUi {
      @Composable
      override fun Show() {
        // `retain` (NF1): the list stays composed under the detail (RetainedStack.kt); `retainhost`:
        // the same in the host's retained container, whose system back the host shows itself.
        // `prepared` (NF3): keliver-nav's prepared stack; with `press`, the host's press signal too.
        val stack = when {
          "prepared" in experiments -> PressPreparedThreewayStack
          "retainhost" in experiments -> RetainedHostThreewayStack
          "retain" in experiments -> RetainedThreewayStack
          else -> null
        }
        if ("press" in experiments) {
          CompositionLocalProvider(LocalKeliverNavPress provides GateAPress) {
            ThreewayApp(GuestApi, GuestMarks, stack)
          }
        } else {
          ThreewayApp(GuestApi, GuestMarks, stack)
        }
      }
    }
    return ui.asZiplineTreehouseUi(appLifecycle)
  }
}

/**
 * The host's HTTP, as an adopter's guest takes it (D13: the guest owns the endpoint, the types and
 * the parsing; the host supplies the app's client). Taken once and never closed.
 */
private val hostHttp: HostHttp by lazy { Zipline.get().take<HostHttpProvider>("HostHttp").asHostHttp() }

private val threewayHost: ThreewayHost by lazy { Zipline.get().take<ThreewayHost>("threewayHost") }

/** The run's experiments (ThreewayHost.experiments); empty for the as-shipped series. */
private val experiments: Set<String> by lazy {
  threewayHost.experiments().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
}

private object GuestApi : ThreewayApi {
  override suspend fun items(size: String, seq: Int): ItemsPage {
    val response = hostHttp.execute(
      HostHttpRequest(method = "GET", path = "/items", query = mapOf("size" to size, "seq" to seq.toString())),
    )
    GuestMarks.mark(seq, "response")
    check(response.status == 200) { "HTTP ${response.status}" }
    val page = when {
      "jsonparse" in experiments -> parseItemsWithJsonParse(response.body, seq)
      "dynamic" in experiments -> parseItemsFromDynamic(response.body, seq)
      else -> parseItems(response.body) // as shipped: kotlinx.serialization's string decoder
    }
    GuestMarks.mark(seq, "parsed")
    return page
  }
}

/**
 * Experiment `jsonparse`: QuickJS's own `JSON.parse` (C), then the objects mapped to [Item] by
 * hand. Marks `jsParsed` between the two steps.
 */
private fun parseItemsWithJsonParse(body: String, seq: Int): ItemsPage {
  val o: dynamic = JSON.parse<dynamic>(body)
  GuestMarks.mark(seq, "jsParsed")
  val array: Array<dynamic> = o.items.unsafeCast<Array<dynamic>>()
  val items = ArrayList<Item>(array.size)
  for (x in array) {
    items += Item(
      id = x.id.unsafeCast<Int>(),
      title = x.title.unsafeCast<String>(),
      subtitle = x.subtitle.unsafeCast<String>(),
      amount = x.amount.unsafeCast<String>(),
      status = x.status.unsafeCast<String>(),
      address = (x.address ?: "").unsafeCast<String>(),
      note = (x.note ?: "").unsafeCast<String>(),
      reference = (x.reference ?: "").unsafeCast<String>(),
    )
  }
  return ItemsPage(seq = o.seq.unsafeCast<Int>(), count = o.count.unsafeCast<Int>(), items = items)
}

private val dynamicJson = Json { ignoreUnknownKeys = true }

/**
 * Experiment `dynamic`: QuickJS's `JSON.parse`, then kotlinx.serialization's `decodeFromDynamic`
 * (the generated serializers, reading JS objects instead of a string). Marks `jsParsed` between.
 */
@OptIn(ExperimentalSerializationApi::class)
private fun parseItemsFromDynamic(body: String, seq: Int): ItemsPage {
  val o: dynamic = JSON.parse<dynamic>(body)
  GuestMarks.mark(seq, "jsParsed")
  return dynamicJson.decodeFromDynamic(ItemsPage.serializer(), o)
}

/** Marks on the guest's clock, buffered and sent with the next `composed` report (one bridge call). */
private object GuestMarks : ThreewayMarks {
  private val pending = LinkedHashMap<Int, LinkedHashMap<String, Long>>()

  override val frameMarks: Boolean get() = "split" in experiments

  override val heavyDetail: Boolean get() = "heavy" in experiments

  override fun now(): Long = Date.now().toLong()

  override fun mark(seq: Int, name: String, atMillis: Long) {
    pending.getOrPut(seq) { LinkedHashMap() }[name] = atMillis
    // The protocol counters cover click → composed.
    if (name == "click") ProtocolStats.reset()
  }

  override fun composed(screen: String, seq: Int) {
    mark(seq, "composed")
    if ("split" in experiments) pending.getOrPut(seq) { LinkedHashMap() }.putAll(ProtocolStats.asMarks())
    val json = pending.entries.joinToString(",", "{", "}") { (s, marks) ->
      marks.entries.joinToString(",", "\"$s\":{", "}") { (name, at) -> "\"$name\":$at" }
    }
    pending.clear()
    threewayHost.composed(screen, seq, json)
  }
}
