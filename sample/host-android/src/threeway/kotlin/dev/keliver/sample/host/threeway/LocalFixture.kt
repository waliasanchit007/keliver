/*
 * Copyright (C) 2026 Keliver contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.keliver.sample.host.threeway

import android.util.Log
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Locale
import kotlin.concurrent.thread
import org.json.JSONObject

/**
 * The fixture API inside the app (`--ez localApi true`; docs/THREE_WAY_BENCHMARK.md, "How to run
 * it"), BENCHMARK-ONLY: `GET /items?size=small|large&seq=N` on 127.0.0.1:8491, the same bytes as the
 * Mac-side fixture (small: 20 items, 4,473 B; large: 1,000 items, 224,145 B), after the same fixed
 * 100 ms delay, HTTP/1.1 keep-alive, CORS `*`. Every form still goes through its own network stack
 * (OkHttp for native and the guest, Chromium for the page); only the adb tunnel and the Mac are left
 * out. For runs where no Mac-side server can run.
 */
internal object LocalFixture {
  private const val PORT = 8491
  private const val DELAY_MILLIS = 100L
  @Volatile private var started = false

  private val STATUSES = listOf("Paid", "Pending", "Shipped", "Refunded", "Failed")
  private val NAMES = listOf("Asha Rao", "Vikram Mehta", "Lena Fischer", "Tom Okafor", "Mei Lin", "Ravi Kumar", "Sara Haddad", "Jon Berg")
  private val CITIES = listOf("Pune", "Leeds", "Austin", "Lyon", "Osaka", "Porto", "Nairobi", "Perth")

  /** As the Mac-side fixture's JSON: compact, keys in this order. */
  private fun payload(n: Int): String {
    val items = StringBuilder()
    for (i in 0 until n) {
      val name = NAMES[i % NAMES.size]
      val city = CITIES[(i * 3) % CITIES.size]
      val amount = 199 + (i * 7919) % 250000
      if (i > 0) items.append(',')
      items.append("{\"id\":").append(i + 1)
        .append(",\"title\":").append(JSONObject.quote("Order #${10000 + i + 1}"))
        .append(",\"subtitle\":").append(JSONObject.quote("$name - ${1 + i % 9} items - ${1 + i % 28} Nov"))
        .append(",\"amount\":").append(JSONObject.quote(String.format(Locale.US, "%,d.%02d", amount / 100, amount % 100)))
        .append(",\"status\":").append(JSONObject.quote(STATUSES[i % STATUSES.size]))
        .append(",\"address\":").append(JSONObject.quote("${12 + i % 80} Market Street, Block ${'A' + i % 6}, $city"))
        .append(",\"note\":").append(JSONObject.quote("Leave at the front desk."))
        .append(",\"reference\":").append(JSONObject.quote(String.format(Locale.US, "REF-%06d-%s", (i * 104729L) % 1000000, city.uppercase(Locale.US))))
        .append('}')
    }
    return "{\"seq\":0,\"count\":$n,\"items\":[$items]}"
  }

  private val templates by lazy { mapOf("small" to payload(20), "large" to payload(1000)) }

  fun start() {
    if (started) return
    started = true
    val sizes = templates.mapValues { it.value.toByteArray().size }
    Log.i("Threeway", "localApi on 127.0.0.1:$PORT, delay $DELAY_MILLIS ms, sizes $sizes")
    // Bound off the main thread (no socket work there), and before any form makes its first request.
    var bound: ServerSocket? = null
    thread { bound = ServerSocket(PORT, 16, InetAddress.getByName("127.0.0.1")) }.join()
    val server = checkNotNull(bound) { "localApi: could not bind 127.0.0.1:$PORT" }
    thread(name = "threeway-fixture", isDaemon = true) {
      while (true) {
        val socket = server.accept()
        thread(name = "threeway-fixture-conn", isDaemon = true) { serve(socket) }
      }
    }
  }

  private fun serve(socket: Socket): Unit = socket.use {
    val input = BufferedInputStream(socket.getInputStream())
    val output = socket.getOutputStream()
    while (true) {
      val requestLine = readLine(input) ?: return
      while (true) {
        val header = readLine(input) ?: return
        if (header.isEmpty()) break
      }
      val (method, target) = requestLine.split(' ').let { it.getOrElse(0) { "" } to it.getOrElse(1) { "/" } }
      val path = target.substringBefore('?')
      val query = target.substringAfter('?', "").split('&').filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
      val head = StringBuilder()
      val body: ByteArray
      when {
        method == "OPTIONS" -> {
          head.append("HTTP/1.1 204 No Content\r\nAccess-Control-Allow-Origin: *\r\nAccess-Control-Allow-Headers: *\r\n")
          body = ByteArray(0)
        }
        path != "/items" -> {
          head.append("HTTP/1.1 404 Not Found\r\n")
          body = "not found".toByteArray()
        }
        else -> {
          val template = templates[query["size"]] ?: templates.getValue("small")
          val seq = query["seq"]?.toIntOrNull() ?: 0
          body = template.replaceFirst("\"seq\":0,", "\"seq\":$seq,").toByteArray()
          Thread.sleep(DELAY_MILLIS)
          head.append("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nAccess-Control-Allow-Origin: *\r\nCache-Control: no-store\r\n")
        }
      }
      head.append("Content-Length: ${body.size}\r\n\r\n")
      output.write(head.toString().toByteArray())
      output.write(body)
      output.flush()
    }
  }

  private fun readLine(input: InputStream): String? {
    val line = StringBuilder()
    while (true) {
      val c = input.read()
      if (c < 0) return null
      if (c == '\n'.code) return line.toString().trimEnd('\r')
      line.append(c.toChar())
    }
  }
}
