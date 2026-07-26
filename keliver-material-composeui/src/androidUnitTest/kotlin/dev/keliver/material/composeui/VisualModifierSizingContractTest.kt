/*
 * Copyright (C) 2026 Square, Inc.
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

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import coil3.ImageLoader
import dev.keliver.Modifier as KeliverModifier
import dev.keliver.material.modifier.FillWidth
import dev.keliver.material.modifier.Padding
import dev.keliver.material.modifier.Size
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule

/** K2a: pins Compose-order semantics for universal sizing modifiers. */
class VisualModifierSizingContractTest {
  @get:Rule
  val paparazzi = Paparazzi(
    deviceConfig = DeviceConfig.PIXEL_6,
    theme = "android:Theme.Material.Light.NoActionBar",
  )

  @Test
  fun sizingModifiersApplyInDeclarationOrder() {
    val sizeThenFill = KeliverModifier
      .then(SizeElement(40, 30))
      .then(FillWidthElement)
    val fillThenSize = KeliverModifier
      .then(FillWidthElement)
      .then(SizeElement(40, 30))
    val sizeThenPadding = KeliverModifier
      .then(SizeElement(40, 30))
      .then(PaddingElement(10))
    val paddingThenSize = KeliverModifier
      .then(PaddingElement(10))
      .then(SizeElement(40, 30))

    var sizeThenFillMeasured = IntSize.Zero
    var fillThenSizeMeasured = IntSize.Zero
    var sizeThenPaddingMeasured = IntSize.Zero
    var paddingThenSizeMeasured = IntSize.Zero
    var fixedSize = IntSize.Zero
    var filledSize = IntSize.Zero
    var paddedSize = IntSize.Zero

    paparazzi.snapshot {
      with(LocalDensity.current) {
        fixedSize = IntSize(40.dp.roundToPx(), 30.dp.roundToPx())
        filledSize = IntSize(200.dp.roundToPx(), 30.dp.roundToPx())
        val paddingPx = 10.dp.roundToPx()
        paddedSize = IntSize(
          fixedSize.width + paddingPx * 2,
          fixedSize.height + paddingPx * 2,
        )
      }
      Column {
        Frame(sizeThenFill) { sizeThenFillMeasured = it }
        Frame(fillThenSize) { fillThenSizeMeasured = it }
        Frame(sizeThenPadding) { sizeThenPaddingMeasured = it }
        Frame(paddingThenSize) { paddingThenSizeMeasured = it }
      }
    }

    assertEquals(fixedSize, sizeThenFillMeasured, "outer Size constrains a later FillWidth")
    assertEquals(filledSize, fillThenSizeMeasured, "outer FillWidth constrains a later Size width")
    assertEquals(fixedSize, sizeThenPaddingMeasured, "padding inside a fixed size consumes its space")
    assertEquals(paddedSize, paddingThenSizeMeasured, "padding outside a fixed size adds to its space")
  }

  @Test
  fun widgetSpecificFillWidthContract() {
    val styledBox = ComposeUiStyledBox().apply {
      fillWidth(true)
      heightDp(30)
    }
    val imageLoader = ImageLoader.Builder(paparazzi.context).build()
    val fixedImage = ComposeUiAsyncImage(imageLoader).apply {
      widthDp(40)
      heightDp(30)
    }
    val fillImage = ComposeUiAsyncImage(imageLoader).apply {
      widthDp(40)
      heightDp(30)
      fillWidth(true)
    }
    var styledBoxSize = IntSize.Zero
    var fixedImageSize = IntSize.Zero
    var fillImageSize = IntSize.Zero
    var fixedSize = IntSize.Zero
    var filledSize = IntSize.Zero

    paparazzi.snapshot {
      with(LocalDensity.current) {
        fixedSize = IntSize(40.dp.roundToPx(), 30.dp.roundToPx())
        filledSize = IntSize(200.dp.roundToPx(), 30.dp.roundToPx())
      }
      Column {
        WidgetFrame(styledBox.value) { styledBoxSize = it }
        WidgetFrame(fixedImage.value) { fixedImageSize = it }
        WidgetFrame(fillImage.value) { fillImageSize = it }
      }
    }

    assertEquals(filledSize, styledBoxSize, "legacy StyledBox fillWidth remains supported")
    assertEquals(fixedSize, fixedImageSize, "AsyncImage uses widthDp when fillWidth is false")
    assertEquals(filledSize, fillImageSize, "AsyncImage fillWidth overrides widthDp")
  }

  @androidx.compose.runtime.Composable
  private fun Frame(
    keliverModifier: KeliverModifier,
    onSizeChanged: (IntSize) -> Unit,
  ) {
    Box(
      Modifier
        .size(200.dp, 100.dp)
        .background(Color(0xffeeeeee)),
    ) {
      Spacer(
        Modifier
          .onSizeChanged(onSizeChanged)
          .applyKeliverVisuals(keliverModifier)
          .background(Color(0xff336699)),
      )
    }
  }

  @androidx.compose.runtime.Composable
  private fun WidgetFrame(
    widget: @androidx.compose.runtime.Composable (Modifier) -> Unit,
    onSizeChanged: (IntSize) -> Unit,
  ) {
    Box(
      Modifier
        .size(200.dp, 100.dp)
        .background(Color(0xffeeeeee)),
    ) {
      widget(Modifier.onSizeChanged(onSizeChanged))
    }
  }

  private data class SizeElement(
    override val widthDp: Int,
    override val heightDp: Int,
  ) : Size

  private data object FillWidthElement : FillWidth

  private data class PaddingElement(
    override val allDp: Int,
  ) : Padding
}
