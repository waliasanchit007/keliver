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

  /**
   * Each size's body as bytes before and after the `seq` value, built once at [start]: a request
   * then allocates only its few seq digits, so the measured app's process pays no per-request body
   * build (about 1 MB of allocation for the large one) and the delay is the delay.
   */
  private val bodies by lazy {
    templates.mapValues { (_, t) ->
      val k = t.indexOf("\"seq\":0,") + "\"seq\":".length
      t.substring(0, k).toByteArray() to t.substring(k + 1).toByteArray()
    }
  }

  fun start() {
    if (started) return
    started = true
    val sizes = bodies.mapValues { (_, b) -> b.first.size + 1 + b.second.size }
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
      val receivedNs = System.nanoTime()
      while (true) {
        val header = readLine(input) ?: return
        if (header.isEmpty()) break
      }
      val (method, target) = requestLine.split(' ').let { it.getOrElse(0) { "" } to it.getOrElse(1) { "/" } }
      val path = target.substringBefore('?')
      val query = target.substringAfter('?', "").split('&').filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
      val head = StringBuilder()
      val body: List<ByteArray>
      when {
        method == "OPTIONS" -> {
          head.append("HTTP/1.1 204 No Content\r\nAccess-Control-Allow-Origin: *\r\nAccess-Control-Allow-Headers: *\r\n")
          body = emptyList()
        }
        path != "/items" -> {
          head.append("HTTP/1.1 404 Not Found\r\n")
          body = listOf("not found".toByteArray())
        }
        else -> {
          val (before, after) = bodies[query["size"]] ?: bodies.getValue("small")
          val seq = query["seq"]?.toIntOrNull() ?: 0
          body = listOf(before, seq.toString().toByteArray(), after)
          // The fixed delay counted from the request line, less what the request already took.
          val leftMillis = DELAY_MILLIS - (System.nanoTime() - receivedNs) / 1_000_000
          if (leftMillis > 0) Thread.sleep(leftMillis)
          head.append("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nAccess-Control-Allow-Origin: *\r\nCache-Control: no-store\r\n")
        }
      }
      head.append("Content-Length: ${body.sumOf { it.size }}\r\n\r\n")
      output.write(head.toString().toByteArray())
      body.forEach { output.write(it) }
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
