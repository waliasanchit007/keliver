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

import app.cash.zipline.loader.LoaderEventListener
import app.cash.zipline.loader.ManifestVerifier
import app.cash.zipline.loader.ZiplineCache
import app.cash.zipline.loader.ZiplineHttpClient
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import dev.keliver.leaks.LeakDetector
import dev.keliver.protocol.host.HostProtocol
import dev.keliver.protocol.host.ProtocolMismatchHandler
import kotlin.test.Test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import okio.ByteString

/** The one line a host logs about its build (HostBuild.kt), which the local-maven route looks for. */
class HostBuildTest {
  @Test
  fun theLineNamesTheModuleAndThisBuildsVersion() {
    assertThat(HOST_BUILD_LINE).isEqualTo("keliver-treehouse-host $KELIVER_HOST_VERSION")
    // A version as the build gives it (0.4.1, 0.5.0-local.<commit>), never Gradle's "unspecified".
    assertThat(Regex("""[0-9]+\.[0-9]+\.[0-9]+([-.+][0-9A-Za-z.+-]+)?""").matches(KELIVER_HOST_VERSION)).isTrue()
  }

  @Test
  fun aFactoryLogsTheLineOnceWhenItIsCreated() {
    val logged = mutableListOf<String>()
    val platform = object : TreehousePlatform {
      override fun newCache(name: String, maxSizeInBytes: Long, loaderEventListener: LoaderEventListener): ZiplineCache =
        error("unexpected call")

      override fun newDispatchers(applicationName: String): TreehouseDispatchers = error("unexpected call")

      override fun logDebug(line: String) {
        logged += line
      }
    }
    val factory = RealTreehouseApp.Factory(
      platform = platform,
      httpClient = object : ZiplineHttpClient() {
        override suspend fun download(url: String, requestHeaders: List<Pair<String, String>>): ByteString =
          error("unexpected call")
      },
      frameClockFactory = object : FrameClock.Factory {
        override fun create(scope: CoroutineScope, dispatchers: TreehouseDispatchers): FrameClock = error("unexpected call")
      },
      manifestVerifier = ManifestVerifier.NO_SIGNATURE_CHECKS,
      embeddedFileSystem = null,
      embeddedDir = null,
      cacheName = "zipline",
      cacheMaxSizeInBytes = 0L,
      ziplineLoaderDispatcher = Dispatchers.Unconfined,
      loaderEventListener = LoaderEventListener.None,
      concurrentDownloads = 1,
      stateStore = MemoryStateStore(),
      leakDetector = LeakDetector.none(),
      hostProtocolFactory = object : HostProtocol.Factory {
        override fun create(json: Json, mismatchHandler: ProtocolMismatchHandler): HostProtocol = error("unexpected call")
      },
    )
    factory.close()

    assertThat(logged).containsExactly(HOST_BUILD_LINE)
  }
}
