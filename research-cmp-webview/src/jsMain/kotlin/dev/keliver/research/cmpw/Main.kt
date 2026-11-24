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
package dev.keliver.research.cmpw

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport

/** index.html's glue: logs the mark and reports the painted frame to the host (KMark). */
private fun kmark(name: String, seq: Int) {
  js("window.kmark(name, seq)")
}

private fun kpoint(name: String) {
  js("window.kpoint(name)")
}

/** The page's `?n=` (the list's length), 50 without it. */
private fun rowCount(): Int = js("parseInt(new URLSearchParams(window.location.search).get('n') || '50')") as Int

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
  kpoint("kotlinMain")
  val count = rowCount()
  ComposeViewport {
    CmpApp(count) { name, seq -> kmark(name, seq) }
  }
}
