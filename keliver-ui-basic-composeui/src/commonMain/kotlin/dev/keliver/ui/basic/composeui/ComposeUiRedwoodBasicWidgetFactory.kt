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
package dev.keliver.ui.basic.composeui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import coil3.ImageLoader
import dev.keliver.layout.composeui.ComposeUiRedwoodLayoutWidgetFactory
import dev.keliver.lazylayout.composeui.ComposeUiRedwoodLazyLayoutWidgetFactory
import dev.keliver.ui.basic.modifier.Reuse
import dev.keliver.ui.basic.widget.Button
import dev.keliver.ui.basic.widget.Image
import dev.keliver.ui.basic.widget.RedwoodUiBasicWidgetFactory
import dev.keliver.ui.basic.widget.RedwoodUiBasicWidgetSystem
import dev.keliver.ui.basic.widget.Text
import dev.keliver.ui.basic.widget.TextInput

public class ComposeUiRedwoodUiBasicWidgetFactory(
  private val imageLoader: ImageLoader,
) : RedwoodUiBasicWidgetFactory<@Composable (Modifier) -> Unit> {
  override fun TextInput(): TextInput<@Composable (Modifier) -> Unit> = ComposeUiTextInput()
  override fun Text(): Text<@Composable (Modifier) -> Unit> = ComposeUiText()
  override fun Image(): Image<@Composable (Modifier) -> Unit> = ComposeUiImage(imageLoader)
  override fun Button(): Button<@Composable ((Modifier) -> Unit)> = ComposeUiButton()
  override fun Reuse(value: @Composable (Modifier) -> Unit, modifier: Reuse) {
  }
}

@Suppress("FunctionName") // Acting like a type.
public fun ComposeUiRedwoodUiBasicWidgetSystem(
  imageLoader: ImageLoader,
): RedwoodUiBasicWidgetSystem<@Composable (Modifier) -> Unit> {
  return RedwoodUiBasicWidgetSystem(
    RedwoodUiBasic = ComposeUiRedwoodUiBasicWidgetFactory(imageLoader),
    RedwoodLayout = ComposeUiRedwoodLayoutWidgetFactory(),
    RedwoodLazyLayout = ComposeUiRedwoodLazyLayoutWidgetFactory(),
  )
}
