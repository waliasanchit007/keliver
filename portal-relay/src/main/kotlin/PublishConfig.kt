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
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/*
 * The relay reads keliver.portal.json once, at start. A changed publishTask or
 * publishOutput (switching a guest to its Production bundle, say) used to need
 * a relay restart, and until then publish silently built the old task. Each
 * publish now reads the file again and uses its publishTask and publishOutput;
 * any other setting that changed is named in the response as needing a restart,
 * since the relay's routes, store and watchers were set up from the old values.
 */

internal object PublishConfig {
  /** The settings publish takes from the file as it is now. */
  val PER_PUBLISH = setOf("publishTask", "publishOutput")

  private val json = Json { encodeDefaults = true }

  /**
   * The file now, or why it cannot be used: a publish never guesses past a broken config. A file
   * that is not there when it was at start ([existedAtStart]) is a failure too, not the defaults
   * (which name Keliver's own guest project).
   */
  fun current(repoDir: File, existedAtStart: Boolean): Result<PortalConfig> = runCatching {
    check(!existedAtStart || File(repoDir, "keliver.portal.json").isFile) { "keliver.portal.json is gone (it was there at start)" }
    loadPortalConfig(repoDir)
  }

  /** Settings other than [PER_PUBLISH] that differ between [started] and [now], sorted. */
  fun restartNeeded(started: PortalConfig, now: PortalConfig): List<String> {
    val a = json.encodeToJsonElement(PortalConfig.serializer(), started).jsonObject
    val b = json.encodeToJsonElement(PortalConfig.serializer(), now).jsonObject
    return (a.keys + b.keys).filter { it !in PER_PUBLISH && a[it] != b[it] }.sorted()
  }
}
