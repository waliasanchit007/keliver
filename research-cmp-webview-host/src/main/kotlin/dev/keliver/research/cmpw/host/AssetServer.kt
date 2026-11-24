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
package dev.keliver.research.cmpw.host

import android.content.res.AssetManager
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * A loopback HTTP/1.1 server for the APK's assets, with a year's `Cache-Control` and a fixed ETag,
 * so the WebView loads the web build through Chromium's network stack: its HTTP cache keeps the
 * files and V8's code cache can keep compiled code across processes, as an over-the-air bundle
 * served from a CDN would be. A fixed port: the caches are keyed by the URL.
 */
internal object AssetServer {
  private const val PORT = 8497

  @Volatile private var started = false

  fun start(assets: AssetManager): Int {
    synchronized(this) {
      if (started) return PORT
      started = true
    }
    val server = ServerSocket(PORT, 16, InetAddress.getByName("127.0.0.1"))
    thread(name = "asset-server", isDaemon = true) {
      while (true) {
        val socket = server.accept()
        thread(name = "asset-conn", isDaemon = true) { serve(assets, socket) }
      }
    }
    return PORT
  }

  private fun serve(assets: AssetManager, socket: Socket) {
    socket.use { s ->
      val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.ISO_8859_1))
      val requestLine = reader.readLine() ?: return
      var conditional = false
      while (true) {
        val header = reader.readLine() ?: break
        if (header.isEmpty()) break
        if (header.startsWith("If-None-Match", ignoreCase = true)) conditional = true
      }
      val path = requestLine.split(' ').getOrNull(1).orEmpty().substringBefore('?').removePrefix("/")
      val out = s.getOutputStream()
      if (conditional) {
        out.write("HTTP/1.1 304 Not Modified\r\nETag: \"v1\"\r\nCache-Control: public, max-age=31536000\r\nConnection: close\r\n\r\n".toByteArray())
        out.flush()
        return
      }
      val bytes = try {
        assets.open(path).use { it.readBytes() }
      } catch (e: Exception) {
        Log.w("Cmpw", "asset server: missing $path")
        out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
        out.flush()
        return
      }
      val headers = "HTTP/1.1 200 OK\r\nContent-Type: ${mimeOf(path)}\r\nContent-Length: ${bytes.size}\r\n" +
        "Cache-Control: public, max-age=31536000\r\nETag: \"v1\"\r\nConnection: close\r\n\r\n"
      out.write(headers.toByteArray())
      out.write(bytes)
      out.flush()
    }
  }
}
