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
package dev.keliver.buildsupport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.gradle.api.artifacts.ModuleIdentifier
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier

/** NF2's "one Zipline" guard ([refuseUpstreamZipline]): what it counts as upstream, and its escape. */
class ZiplineForkTest {
  @Test fun upstreamModulesAreFoundSortedAndDistinct() {
    val ids = listOf(
      module("dev.keliver.zipline", "zipline", "1.28.0-keliver.1"),
      module("app.cash.zipline", "zipline-loader", "1.22.0"),
      module("org.jetbrains.kotlin", "kotlin-stdlib", "2.4.21"),
      module("app.cash.zipline", "zipline", "1.22.0"),
      module("app.cash.zipline", "zipline", "1.22.0"),
    )
    assertEquals(
      listOf("app.cash.zipline:zipline-loader:1.22.0", "app.cash.zipline:zipline:1.22.0"),
      upstreamZiplineModules(ids),
    )
  }

  @Test fun theForkAndOtherGroupsAreNotUpstream() {
    val ids = listOf(
      module("dev.keliver.zipline", "zipline", "1.28.0-keliver.1"),
      module("dev.keliver.zipline", "zipline-gradle-plugin", "1.28.0-keliver.1"),
      // Same prefix, a different group: not upstream Zipline.
      module("app.cash.ziplinex", "zipline", "1.0.0"),
      module("app.cash.paparazzi", "paparazzi", "2.0.0"),
    )
    assertEquals(emptyList(), upstreamZiplineModules(ids))
  }

  @Test fun projectComponentsAreIgnored() {
    val project = object : ComponentIdentifier {
      override fun getDisplayName() = "project :app.cash.zipline"
    }
    assertEquals(emptyList(), upstreamZiplineModules(listOf(project)))
  }

  @Test fun onlyAnExplicitNonFalseValueSkipsTheGuard() {
    assertFalse(allowsUpstreamZipline(null), "absent: the guard runs")
    assertFalse(allowsUpstreamZipline("false"), "=false: the guard runs")
    assertFalse(allowsUpstreamZipline(" false "), "=false with spaces: the guard runs")
    assertTrue(allowsUpstreamZipline(""), "-Pkeliver.allowUpstreamZipline: skipped")
    assertTrue(allowsUpstreamZipline("true"), "=true: skipped")
  }

  private fun module(group: String, name: String, version: String): ModuleComponentIdentifier =
    object : ModuleComponentIdentifier {
      override fun getGroup() = group
      override fun getModule() = name
      override fun getVersion() = version
      override fun getModuleIdentifier() = object : ModuleIdentifier {
        override fun getGroup() = group
        override fun getName() = name
      }
      override fun getDisplayName() = "$group:$name:$version"
    }
}
