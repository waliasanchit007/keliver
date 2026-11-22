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
package dev.keliver.treehouse

import app.cash.zipline.Zipline
import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isNotEmpty
import dev.keliver.protocol.host.HostProtocolAdapter
import dev.keliver.protocol.host.UiChange
import java.io.File
import kotlin.test.Test

/**
 * The host's baseline profile (`src/androidMain/baseline-prof.txt`, packed into the AAR and merged
 * into every app's profile by AGP). Without it the host's path from the guest's change batch to the
 * screen runs interpreted on a cold start: decoding the batch (kotlinx.serialization), the bridge
 * (Zipline), applying the changes and the generated widgets (dev.keliver). See
 * docs/THREE_WAY_BENCHMARK.md, "NF4: composed to drawn on a cold start".
 */
class HostBaselineProfileTest {
  private val rules = File("src/androidMain/baseline-prof.txt").readLines().filter { it.isNotBlank() }

  /** The classes a traced cold start showed JIT-compiling between the guest's batch and the frame. */
  private val hotPath = listOf(
    UiChange.Companion::class.java.name,
    HostProtocolAdapter::class.java.name,
    TreehouseApp::class.java.name,
    Zipline::class.java.name,
    "app.cash.zipline.internal.bridge.Endpoint",
    "dev.keliver.protocol.PropertyChange\$\$serializer",
    "kotlinx.serialization.json.internal.StreamingJsonDecoder",
    "kotlinx.serialization.json.internal.StringJsonLexer",
    "kotlinx.serialization.json.internal.JsonTreeReader",
    "kotlinx.serialization.internal.AbstractPolymorphicSerializer",
  )

  @Test fun everyLineIsARule() {
    assertThat(rules).isNotEmpty()
    assertThat(rules.filterNot { RULE.matches(it) }).isEmpty()
  }

  @Test fun hotPathClassesExist() {
    // A renamed class would leave its rule matching nothing, silently.
    assertThat(hotPath.filter { runCatching { Class.forName(it) }.isFailure }).isEmpty()
  }

  @Test fun hotPathMethodsAreHotStartupAndPostStartup() {
    val methodRules = rules.filter { it.contains("->") && it.takeWhile { c -> c != 'L' }.toSet().containsAll(setOf('H', 'S', 'P')) }
      .map { glob(it.dropWhile { c -> c != 'L' }.substringBefore("->")) }
    assertThat(hotPath.filter { name -> methodRules.none { it.matches(descriptor(name)) } }).isEmpty()
  }

  @Test fun hotPathClassesAreStartupClasses() {
    val classRules = rules.filter { it.startsWith("L") && !it.contains("->") }.map { glob(it) }
    assertThat(hotPath.filter { name -> classRules.none { it.matches(descriptor(name)) } }).isEmpty()
  }

  private fun descriptor(className: String) = "L" + className.replace('.', '/') + ";"

  /** The profile's wildcards: `**` any characters, `*` any but `/`, `?` one character. */
  private fun glob(pattern: String): Regex {
    val out = StringBuilder()
    var i = 0
    while (i < pattern.length) {
      when {
        pattern.startsWith("**", i) -> out.append(".*").also { i++ }
        pattern[i] == '*' -> out.append("[^/]*")
        pattern[i] == '?' -> out.append(".")
        else -> out.append(Regex.escape(pattern[i].toString()))
      }
      i++
    }
    return Regex(out.toString())
  }

  private companion object {
    /** Flags, a class descriptor, and an optional method part (`->name(params)return`). */
    val RULE = Regex("""[HSP]*L[\w/$*?]+;(->[\w<>$*?]+\([^)]*\)\S+)?""")
  }
}
