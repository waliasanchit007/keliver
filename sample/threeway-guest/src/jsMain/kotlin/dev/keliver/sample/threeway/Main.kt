/*
 * Copyright (C) 2026 Keliver contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.keliver.sample.threeway

import androidx.compose.runtime.Composable
import app.cash.zipline.Zipline
import dev.keliver.capabilities.HostHttp
import dev.keliver.capabilities.HostHttpRequest
import dev.keliver.compose.gatea.protocol.guest.KeliverComposeGateAProtocolWidgetSystemFactory
import dev.keliver.http.HostHttpProvider
import dev.keliver.http.asHostHttp
import dev.keliver.sample.threeway.data.ItemsPage
import dev.keliver.sample.threeway.data.ThreewayApi
import dev.keliver.sample.threeway.data.ThreewayMarks
import dev.keliver.sample.threeway.data.parseItems
import dev.keliver.treehouse.StandardAppLifecycle
import dev.keliver.treehouse.TreehouseUi
import dev.keliver.treehouse.ZiplineTreehouseUi
import dev.keliver.treehouse.asZiplineTreehouseUi
import kotlin.js.Date
import kotlinx.serialization.json.Json

public fun main() {
  Zipline.get().bind<ThreewayAppService>("app", ThreewayAppServiceImpl())
}

private class ThreewayAppServiceImpl : ThreewayAppService {
  override val appLifecycle: StandardAppLifecycle = StandardAppLifecycle(
    protocolWidgetSystemFactory = KeliverComposeGateAProtocolWidgetSystemFactory,
    json = Json,
    widgetVersion = 1U,
  )

  override fun launch(): ZiplineTreehouseUi {
    val ui = object : TreehouseUi {
      @Composable
      override fun Show() {
        ThreewayApp(GuestApi, GuestMarks)
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

private object GuestApi : ThreewayApi {
  override suspend fun items(size: String, seq: Int): ItemsPage {
    val response = hostHttp.execute(
      HostHttpRequest(method = "GET", path = "/items", query = mapOf("size" to size, "seq" to seq.toString())),
    )
    GuestMarks.mark(seq, "response")
    check(response.status == 200) { "HTTP ${response.status}" }
    val page = parseItems(response.body)
    GuestMarks.mark(seq, "parsed")
    return page
  }
}

/** Marks on the guest's clock, buffered and sent with the next `composed` report (one bridge call). */
private object GuestMarks : ThreewayMarks {
  private val pending = LinkedHashMap<Int, LinkedHashMap<String, Long>>()

  override fun now(): Long = Date.now().toLong()

  override fun mark(seq: Int, name: String, atMillis: Long) {
    pending.getOrPut(seq) { LinkedHashMap() }[name] = atMillis
  }

  override fun composed(screen: String, seq: Int) {
    mark(seq, "composed")
    val json = pending.entries.joinToString(",", "{", "}") { (s, marks) ->
      marks.entries.joinToString(",", "\"$s\":{", "}") { (name, at) -> "\"$name\":$at" }
    }
    pending.clear()
    threewayHost.composed(screen, seq, json)
  }
}
