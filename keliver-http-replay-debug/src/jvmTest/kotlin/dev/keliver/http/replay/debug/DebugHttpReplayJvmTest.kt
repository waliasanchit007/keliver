/*
 * Copyright (C) 2026 Square, Inc.
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
package dev.keliver.http.replay.debug

import dev.keliver.http.HttpRequest
import java.io.File
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking

class DebugHttpReplayJvmTest {
  /** Two identical requests in flight on different threads never get the same position. */
  @Test fun concurrentDuplicatesEachGetTheirOwnPosition() = runBlocking {
    val count = 200
    val entries = (1..count).joinToString(",") {
      """{"request":{"method":"GET","path":"/sequence"},"response":{"status":200,"body":"$it"}}"""
    }
    val file = """{"formatVersion":1,"recordedAt":"2026-07-27T10:00:00Z","entries":[$entries]}"""
    val provider = DebugHttpReplay(mapOf("s" to file)).provider("s")

    val bodies = (1..count).map {
      async(Dispatchers.Default) { provider.execute(HttpRequest("GET", "/sequence")).body }
    }.awaitAll()

    assertEquals((1..count).map(Int::toString).toSet(), bodies.toSet())
    assertEquals(424, provider.execute(HttpRequest("GET", "/sequence")).status)
  }

  /** The provider has no network dependency at all: no HTTP client is on its runtime classpath. */
  @Test fun noHttpClientIsOnTheRuntimeClasspath() {
    listOf(
      "okhttp3.OkHttpClient",
      "io.ktor.client.HttpClient",
      "app.cash.zipline.loader.ZiplineLoader",
    ).forEach { name ->
      assertFailsWith<ClassNotFoundException>(name) { Class.forName(name) }
    }
  }

  /** ... and its own bytecode names no networking API. */
  @Test fun itsClassesReferenceNoNetworkingApi() {
    val classes = moduleClassFiles()
    assertTrue(classes.keys.any { it.endsWith("DebugHttpReplayProvider.class") }, "${classes.keys}")
    val forbidden = listOf("java/net/", "javax/net/", "okhttp3/", "io/ktor/", "app/cash/zipline/loader/")
    classes.forEach { (name, bytes) ->
      // Class references are UTF-8 entries in the constant pool, readable as Latin-1.
      val pool = String(bytes, Charsets.ISO_8859_1)
      forbidden.forEach { api -> assertTrue(api !in pool, "$name references $api") }
    }
  }

  /** A minified build that contains it keeps its class names, for the release self-test. */
  @Test fun itShipsAKeepNamesRuleForR8() {
    val rule = assertNotNull(
      DebugHttpReplay::class.java.classLoader.getResource("META-INF/proguard/keliver-http-replay-debug.pro"),
    ).readText()
    assertTrue(
      rule.lines().any { it.trim() == "-keepnames class dev.keliver.http.replay.debug.**" },
      rule,
    )
  }

  private fun moduleClassFiles(): Map<String, ByteArray> {
    val location = File(DebugHttpReplay::class.java.protectionDomain.codeSource.location.toURI())
    val prefix = "dev/keliver/http/replay/debug/"
    return if (location.isDirectory) {
      location.resolve(prefix).walkTopDown()
        .filter { it.isFile && it.extension == "class" }
        .associate { it.relativeTo(location).invariantSeparatorsPath to it.readBytes() }
    } else {
      ZipFile(location).use { zip ->
        zip.entries().asSequence()
          .filter { it.name.startsWith(prefix) && it.name.endsWith(".class") }
          .associate { it.name to zip.getInputStream(it).readBytes() }
      }
    }
  }
}
