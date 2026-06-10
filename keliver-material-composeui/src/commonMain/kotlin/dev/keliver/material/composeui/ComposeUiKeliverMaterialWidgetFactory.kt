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

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import coil3.ImageLoader
import dev.keliver.layout.composeui.ComposeUiRedwoodLayoutWidgetFactory
import dev.keliver.lazylayout.composeui.ComposeUiRedwoodLazyLayoutWidgetFactory
import dev.keliver.material.modifier.Reuse
import dev.keliver.material.widget.Button
import dev.keliver.material.widget.Image
import dev.keliver.material.widget.KeliverMaterialWidgetFactory
import dev.keliver.material.widget.KeliverMaterialWidgetSystem
import dev.keliver.material.widget.Text
import dev.keliver.material.widget.TextInput

public class ComposeUiKeliverMaterialWidgetFactory(
  private val imageLoader: ImageLoader,
) : KeliverMaterialWidgetFactory<@Composable (Modifier) -> Unit> {
  override fun TextInput(): TextInput<@Composable (Modifier) -> Unit> = ComposeUiTextInput()
  override fun Text(): Text<@Composable (Modifier) -> Unit> = ComposeUiText()
  override fun Image(): Image<@Composable (Modifier) -> Unit> = ComposeUiImage(imageLoader)
  override fun Button(): Button<@Composable ((Modifier) -> Unit)> = ComposeUiButton()
  override fun Reuse(value: @Composable (Modifier) -> Unit, modifier: Reuse) {
  }
}

@Suppress("FunctionName") // Acting like a type.
public fun ComposeUiKeliverMaterialWidgetSystem(
  imageLoader: ImageLoader,
): KeliverMaterialWidgetSystem<@Composable (Modifier) -> Unit> {
  return KeliverMaterialWidgetSystem(
    KeliverMaterial = ComposeUiKeliverMaterialWidgetFactory(imageLoader),
    RedwoodLayout = ComposeUiRedwoodLayoutWidgetFactory(),
    RedwoodLazyLayout = ComposeUiRedwoodLazyLayoutWidgetFactory(),
  )
}
