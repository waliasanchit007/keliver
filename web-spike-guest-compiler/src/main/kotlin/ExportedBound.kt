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
@file:Suppress("ktlint:compose:modifier-missing-check") // A spike's exported root screen: nothing places it.

import androidx.compose.runtime.Composable
import dev.keliver.layout.compose.Column
import dev.keliver.material.compose.Button
import dev.keliver.material.compose.StyledText
import dev.keliver.material.compose.Text

@Composable
fun BoundScreen(b: BoundScreenBindings) {
  Column {
    StyledText(
      text = b.title,
      fontSize = 22,
      bold = true,
    )
    Text(
      text = b.subtitle,
    )
    Button(
      text = "Buy now",
      onClick = { b.buyTapped() },
    )
  }
}

/** The round-trip boundary: implement this by hand; the portal never touches it. */
interface BoundScreenBindings {
  val title: String
  val subtitle: String
  fun buyTapped()
}
