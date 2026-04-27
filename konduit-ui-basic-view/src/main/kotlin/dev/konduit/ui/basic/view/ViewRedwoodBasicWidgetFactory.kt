/*
 * Copyright (C) 2025 Square, Inc.
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
package dev.konduit.ui.basic.view

import android.content.Context
import android.view.View
import android.widget.Button as PlatformButton
import android.widget.ImageView
import android.widget.TextView
import dev.konduit.layout.view.ViewRedwoodLayoutWidgetFactory
import dev.konduit.lazylayout.view.ViewRedwoodLazyLayoutWidgetFactory
import dev.konduit.ui.basic.modifier.Reuse
import dev.konduit.ui.basic.widget.Button
import dev.konduit.ui.basic.widget.Image
import dev.konduit.ui.basic.widget.RedwoodUiBasicWidgetFactory
import dev.konduit.ui.basic.widget.RedwoodUiBasicWidgetSystem
import dev.konduit.ui.basic.widget.Text
import dev.konduit.ui.basic.widget.TextInput
import coil3.ImageLoader

public class ViewRedwoodUiBasicWidgetFactory(
  private val context: Context,
  private val imageLoader: ImageLoader,
) : RedwoodUiBasicWidgetFactory<View> {
  override fun TextInput(): TextInput<View> = ViewTextInput(context)
  override fun Text(): Text<View> = ViewText(TextView(context))
  override fun Image(): Image<View> = ViewImage(ImageView(context), imageLoader)
  override fun Button(): Button<View> = ViewButton(PlatformButton(context))
  override fun Reuse(value: View, modifier: Reuse) {
  }
}

@Suppress("FunctionName") // Acting like a type.
public fun ViewRedwoodUiBasicWidgetSystem(
  context: Context,
  imageLoader: ImageLoader,
): RedwoodUiBasicWidgetSystem<View> {
  return RedwoodUiBasicWidgetSystem(
    RedwoodUiBasic = ViewRedwoodUiBasicWidgetFactory(context, imageLoader),
    RedwoodLayout = ViewRedwoodLayoutWidgetFactory(context),
    RedwoodLazyLayout = ViewRedwoodLazyLayoutWidgetFactory(context),
  )
}
