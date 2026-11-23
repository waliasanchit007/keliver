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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * L2 (feedback round 5): for each screen, the order of the session's edits across its documents —
 * the screen and the components opened from it — as the editor keeps it (its `SessionHistory`,
 * an opaque JSON object here). Kept by the relay beside the undo stacks that order describes, so
 * every browser on the screen undoes in the same order (a browser of its own had an empty history
 * and undid the screen's older steps before a component edit), and, held in memory as the stacks
 * are, it never outlives them (a relay restart empties both).
 */
internal class SessionHistories(private val maxChars: Int = 64 * 1024) {
  private val byScreen = HashMap<String, String>()

  /** [screen]'s history in [project], or null when no editor has kept one since the relay started. */
  @Synchronized
  fun get(project: String, screen: String): String? = byScreen[key(project, screen)]

  /** Keeps [text] as [screen]'s history; false (nothing kept) unless it is a JSON object of at most [maxChars]. */
  @Synchronized
  fun put(project: String, screen: String, text: String): Boolean {
    if (text.length > maxChars) return false
    if (runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() == null) return false
    byScreen[key(project, screen)] = text
    return true
  }

  private fun key(project: String, screen: String) = "$project/$screen"
}
