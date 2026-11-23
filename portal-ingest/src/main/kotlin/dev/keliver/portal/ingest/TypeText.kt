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
package dev.keliver.portal.ingest

/**
 * Whether the Kotlin type text [type] (a declared parameter type) is nullable:
 * a `?` that closes the WHOLE type. An unparenthesized function type's `?`
 * belongs to its return type — `() -> Unit?` is a non-null function returning
 * `Unit?`; the nullable function type is `(() -> Unit)?`. So a type with an
 * `->` outside every `(…)`/`<…>` is a function type, never nullable.
 * The one rule ingest (a null branch) and the relay's gate both read.
 */
fun isNullableType(type: String?): Boolean {
  val t = type?.trim().orEmpty()
  if (!t.endsWith("?")) return false
  var depth = 0
  var i = 0
  while (i < t.length) {
    when {
      t.startsWith("->", i) -> {
        if (depth == 0) return false
        i++
      }
      t[i] == '(' || t[i] == '<' -> depth++
      t[i] == ')' || t[i] == '>' -> depth--
    }
    i++
  }
  return true
}
