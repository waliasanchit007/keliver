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
/**
 * Why [name] cannot name a new [what] ("screen" / "project"), or null when it can.
 *
 * E40: naming a new screen or project. A screen's name is its .kt file's and
 * its function's (`home` → `home.kt`, `HomeScreen`), so it must be one Kotlin
 * can read, and a name another screen already has (in any case: a macOS or
 * Windows file system does not tell `Home.kt` from `home.kt`) is refused
 * before anything is created.
 */
internal fun nameProblem(name: String, existing: Collection<String>, what: String): String? {
  val n = name.trim()
  return when {
    n.isEmpty() -> "Enter a name for the $what"
    !Regex("[A-Za-z][A-Za-z0-9_]*").matches(n) ->
      "Letters, digits and _ only, starting with a letter — it names a .kt file and a Kotlin function"
    n.length > 60 -> "At most 60 characters"
    existing.any { it.equals(n, ignoreCase = true) } -> "A $what named “${existing.first { it.equals(n, ignoreCase = true) }}” already exists"
    else -> null
  }
}
