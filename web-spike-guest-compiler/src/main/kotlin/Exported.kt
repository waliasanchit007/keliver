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
import dev.keliver.layout.compose.Spacer
import dev.keliver.material.compose.StyledBox
import dev.keliver.material.compose.StyledText
import dev.keliver.ui.Dp

@Composable
fun ExportedScreen() {
  StyledBox(
    cornerRadiusDp = 12,
    paddingDp = 20,
    fillWidth = true,
    borderColorArgb = -9808,
    borderWidthDp = 1,
    gradientColorsArgb = listOf(-2840, -5674),
    gradientStops = listOf(0.0f, 1.0f),
    gradientDirection = 3,
  ) {
    Column {
      StyledText(
        text = "PORTAL PREVIEW · rendered from a tree",
        fontSize = 12,
        bold = true,
        colorArgb = -7697782,
      )
      Spacer(
        height = Dp(10.0),
      )
      StyledText(
        text = "items: 3",
        fontSize = 24,
        bold = true,
        colorArgb = -15658735,
      )
      Spacer(
        height = Dp(8.0),
      )
      StyledText(
        text = "• item 1",
        fontSize = 16,
        colorArgb = -13421773,
      )
      StyledText(
        text = "• item 2",
        fontSize = 16,
        colorArgb = -13421773,
      )
      StyledText(
        text = "• item 3",
        fontSize = 16,
        colorArgb = -13421773,
      )
    }
  }
}
