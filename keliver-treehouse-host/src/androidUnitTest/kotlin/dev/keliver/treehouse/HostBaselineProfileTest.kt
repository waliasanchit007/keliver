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
 * (Zipline) and applying the changes (dev.keliver's treehouse, protocol and widget packages). See
 * docs/THREE_WAY_BENCHMARK.md, "NF4: composed to drawn on a cold start".
 *
 * The rules list library packages one by one. A wildcard over all of `dev.keliver` would also compile an
 * app's own code when the app lives under `dev.keliver` (the sample does), which inflates what the
 * profile appears to buy and costs every adopter space for code that is not theirs.
 *
 * This test reads the source file. `checkBaselineProfilePackaged` (build.gradle) checks that the
 * published AAR carries it.
 */
class HostBaselineProfileTest {
  private val rules = File("src/androidMain/baseline-prof.txt").readLines()
    .filter { it.isNotBlank() && !it.startsWith("#") }

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
    "kotlinx.serialization.encoding.AbstractDecoder",
    "kotlinx.serialization.PolymorphicSerializerKt",
  )

  /** Hot-path classes outside the packages that get startup-class rules (methods only). */
  private val methodRulesOnly = setOf(
    "kotlinx.serialization.encoding.AbstractDecoder",
    "kotlinx.serialization.PolymorphicSerializerKt",
  )

  /** Classes the profile must not cover: an app's own code, and packages off the host's path. */
  private val notCovered = listOf(
    "dev.keliver.sample.host.threeway.ThreewayActivity",
    "dev.keliver.someapp.MainActivity",
    "dev.keliver.material.composeui.ComposeUiButton",
    "dev.keliver.compose.gatea.composeui.GeneratedKt",
    "kotlinx.serialization.cbor.Cbor",
  )

  @Test fun everyLineIsARule() {
    assertThat(rules).isNotEmpty()
    assertThat(rules.filterNot { RULE.matches(it) }).isEmpty()
  }

  @Test fun hotPathClassesExist() {
    // The rules are package wildcards: renaming a class inside a covered package is harmless (this
    // list then needs the new name), but moving it to another package leaves it uncovered.
    val loader = javaClass.classLoader
    assertThat(hotPath.filter { runCatching { Class.forName(it, false, loader) }.isFailure }).isEmpty()
  }

  @Test fun hotPathMethodsAreHotStartupAndPostStartup() {
    assertThat(hotPath.filter { name -> methodRules.none { it.matches(descriptor(name)) } }).isEmpty()
  }

  @Test fun hotPathClassesAreStartupClasses() {
    assertThat((hotPath - methodRulesOnly).filter { name -> classRules.none { it.matches(descriptor(name)) } }).isEmpty()
  }

  @Test fun appCodeAndOtherLibrariesAreNotCovered() {
    assertThat(notCovered.filter { name -> (methodRules + classRules).any { it.matches(descriptor(name)) } }).isEmpty()
  }

  private val methodRules get() = rules
    .filter { it.contains("->") && it.takeWhile { c -> c != 'L' }.toSet().containsAll(setOf('H', 'S', 'P')) }
    .map { glob(it.dropWhile { c -> c != 'L' }.substringBefore("->")) }

  private val classRules get() = rules.filter { it.startsWith("L") && !it.contains("->") }.map { glob(it) }

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
