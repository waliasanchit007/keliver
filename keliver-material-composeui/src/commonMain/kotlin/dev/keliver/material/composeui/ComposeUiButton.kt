/*
 * Copyright (C) 2022 Square, Inc.
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
package dev.keliver.material.composeui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button as M3Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text as M3Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.keliver.Modifier as RedwoodModifier
import dev.keliver.material.widget.Button

internal class ComposeUiButton : Button<@Composable (Modifier) -> Unit> {
  private var text by mutableStateOf("")
  private var isEnabled by mutableStateOf(true)
  private var onClick by mutableStateOf({})
  private var containerArgb by mutableStateOf(0)
  private var contentArgb by mutableStateOf(0)
  private var cornerRadiusDp by mutableStateOf(0)

  override var modifier: RedwoodModifier = RedwoodModifier

  override val value: @Composable (Modifier) -> Unit = { modifier ->
    val colors = if (containerArgb != 0 || contentArgb != 0) {
      ButtonDefaults.buttonColors(
        containerColor = if (containerArgb != 0) Color(containerArgb) else ButtonDefaults.buttonColors().containerColor,
        contentColor = if (contentArgb != 0) Color(contentArgb) else ButtonDefaults.buttonColors().contentColor,
      )
    } else {
      ButtonDefaults.buttonColors()
    }
    M3Button(
      onClick = onClick,
      enabled = isEnabled,
      colors = colors,
      shape = if (cornerRadiusDp > 0) RoundedCornerShape(cornerRadiusDp.dp) else ButtonDefaults.shape,
      modifier = modifier.fillMaxWidth(),
    ) {
      M3Text(text)
    }
  }

  override fun text(text: String?) { this.text = text ?: "" }
  override fun enabled(enabled: Boolean) { this.isEnabled = enabled }
  override fun onClick(onClick: (() -> Unit)?) { this.onClick = onClick ?: {} }
  override fun containerArgb(containerArgb: Int) { this.containerArgb = containerArgb }
  override fun contentArgb(contentArgb: Int) { this.contentArgb = contentArgb }
  override fun cornerRadiusDp(cornerRadiusDp: Int) { this.cornerRadiusDp = cornerRadiusDp }
}
