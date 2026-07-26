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
package dev.keliver.layout.composeui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import dev.keliver.layout.api.Constraint
import dev.keliver.layout.api.CrossAxisAlignment
import dev.keliver.layout.api.MainAxisAlignment
import dev.keliver.layout.api.Overflow
import dev.keliver.ui.Margin
import dev.keliver.widget.Widget
import dev.keliver.yoga.FlexDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule

/**
 * K2a: executable contract for the sizing layers most likely to be confused.
 *
 * These assertions intentionally exercise the real Compose modifiers and Yoga
 * measurement path. They are narrower and easier to diagnose than the broad
 * layout snapshot matrix.
 */
class ComposeUiSizingContractTest {
  @get:Rule
  val paparazzi = Paparazzi(
    deviceConfig = DeviceConfig.PIXEL_6,
    theme = "android:Theme.Material.Light.NoActionBar",
  )

  @Test
  fun layoutContainerConstraintContract() {
    val wrap = flexContainer(Constraint.Wrap, Constraint.Wrap)
    val fill = flexContainer(Constraint.Fill, Constraint.Fill)
    val fixedIncoming = flexContainer(Constraint.Fill, Constraint.Fill)
    val fillIncoming = flexContainer(Constraint.Wrap, Constraint.Wrap)

    var wrapSize = IntSize.Zero
    var fillSize = IntSize.Zero
    var fixedIncomingSize = IntSize.Zero
    var fillIncomingSize = IntSize.Zero
    var contentSize = IntSize.Zero
    var frameSize = IntSize.Zero
    var fixedSize = IntSize.Zero

    paparazzi.snapshot {
      with(LocalDensity.current) {
        contentSize = IntSize(40.dp.roundToPx(), 30.dp.roundToPx())
        frameSize = IntSize(200.dp.roundToPx(), 100.dp.roundToPx())
        fixedSize = IntSize(80.dp.roundToPx(), 60.dp.roundToPx())
      }
      Column {
        Frame {
          wrap.value(Modifier.onSizeChanged { wrapSize = it })
        }
        Frame {
          fill.value(Modifier.onSizeChanged { fillSize = it })
        }
        Frame {
          fixedIncoming.value(
            Modifier
              .size(80.dp, 60.dp)
              .onSizeChanged { fixedIncomingSize = it },
          )
        }
        Frame {
          fillIncoming.value(
            Modifier
              .fillMaxWidth()
              .onSizeChanged { fillIncomingSize = it },
          )
        }
      }
    }

    assertEquals(contentSize, wrapSize, "Constraint.Wrap follows content")
    assertEquals(frameSize, fillSize, "Constraint.Fill consumes bounded space")
    assertEquals(
      fixedSize,
      fixedIncomingSize,
      "an incoming fixed size constrains the container before Constraint is applied",
    )
    assertEquals(
      IntSize(frameSize.width, contentSize.height),
      fillIncomingSize,
      "an incoming fill modifier can make a Wrap container fill its parent",
    )
  }

  @Test
  fun boxConstraintContract() {
    val wrap = composeBox(Constraint.Wrap, Constraint.Wrap)
    val fill = composeBox(Constraint.Fill, Constraint.Fill)
    var wrapSize = IntSize.Zero
    var fillSize = IntSize.Zero
    var contentSize = IntSize.Zero
    var frameSize = IntSize.Zero

    paparazzi.snapshot {
      with(LocalDensity.current) {
        contentSize = IntSize(40.dp.roundToPx(), 30.dp.roundToPx())
        frameSize = IntSize(200.dp.roundToPx(), 100.dp.roundToPx())
      }
      Column {
        Frame {
          wrap.value(Modifier.onSizeChanged { wrapSize = it })
        }
        Frame {
          fill.value(Modifier.onSizeChanged { fillSize = it })
        }
      }
    }

    assertEquals(contentSize, wrapSize, "Box Wrap follows its widest/tallest child")
    assertEquals(frameSize, fillSize, "Box Fill consumes bounded space")
  }

  @Test
  fun crossAxisStretchSizesChildrenOnlyWhenTheContainerHasSpace() {
    val start = flexContainer(
      width = Constraint.Fill,
      height = Constraint.Wrap,
      crossAxisAlignment = CrossAxisAlignment.Start,
    )
    val stretch = flexContainer(
      width = Constraint.Fill,
      height = Constraint.Wrap,
      crossAxisAlignment = CrossAxisAlignment.Stretch,
    )
    val startChild = start.children.widgets.single() as MeasuringChild
    val stretchChild = stretch.children.widgets.single() as MeasuringChild
    var contentSize = IntSize.Zero
    var stretchedSize = IntSize.Zero

    paparazzi.snapshot {
      with(LocalDensity.current) {
        contentSize = IntSize(40.dp.roundToPx(), 30.dp.roundToPx())
        stretchedSize = IntSize(200.dp.roundToPx(), 30.dp.roundToPx())
      }
      Column {
        Frame { start.value(Modifier) }
        Frame { stretch.value(Modifier) }
      }
    }

    assertEquals(contentSize, startChild.measuredSize)
    assertEquals(
      stretchedSize,
      stretchChild.measuredSize,
      "Column Stretch fills the resolved cross axis without changing the main axis",
    )
  }

  private fun flexContainer(
    width: Constraint,
    height: Constraint,
    crossAxisAlignment: CrossAxisAlignment = CrossAxisAlignment.Start,
  ): ComposeUiFlexContainer = ComposeUiFlexContainer(FlexDirection.Column).apply {
    width(width)
    height(height)
    margin(Margin.Zero)
    overflow(Overflow.Clip)
    crossAxisAlignment(crossAxisAlignment)
    mainAxisAlignment(MainAxisAlignment.Start)
    onScroll(null)
    children.insert(0, MeasuringChild())
  }

  private fun composeBox(
    width: Constraint,
    height: Constraint,
  ): ComposeUiBox = ComposeUiBox().apply {
    width(width)
    height(height)
    margin(Margin.Zero)
    horizontalAlignment(CrossAxisAlignment.Start)
    verticalAlignment(CrossAxisAlignment.Start)
    children.insert(0, MeasuringChild())
  }

  @Composable
  private fun Frame(content: @Composable () -> Unit) {
    Box(
      Modifier
        .size(200.dp, 100.dp)
        .background(Color(0xffeeeeee)),
    ) {
      content()
    }
  }

  private class MeasuringChild : Widget<@Composable (Modifier) -> Unit> {
    var measuredSize: IntSize = IntSize.Zero
    override var modifier: dev.keliver.Modifier = dev.keliver.Modifier
    override val value: @Composable (Modifier) -> Unit = { incoming ->
      Spacer(
        incoming
          .size(40.dp, 30.dp)
          .background(Color(0xff336699))
          .onSizeChanged { measuredSize = it },
      )
    }
  }
}
