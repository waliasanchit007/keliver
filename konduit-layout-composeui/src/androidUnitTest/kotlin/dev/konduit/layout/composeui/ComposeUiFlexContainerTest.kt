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
package dev.konduit.layout.composeui

import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import app.cash.burst.Burst
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import dev.konduit.layout.AbstractFlexContainerTest
import dev.konduit.layout.TestFlexContainer
import dev.konduit.layout.api.Constraint
import dev.konduit.layout.api.Overflow
import dev.konduit.layout.widget.Column
import dev.konduit.layout.widget.Row
import dev.konduit.layout.widget.Spacer
import dev.konduit.snapshot.testing.ComposeSnapshotter
import dev.konduit.snapshot.testing.ComposeUiTestWidgetFactory
import dev.konduit.ui.Px
import dev.konduit.yoga.FlexDirection
import com.android.resources.LayoutDirection
import kotlinx.coroutines.runBlocking
import org.junit.Rule

@Burst
class ComposeUiFlexContainerTest(
  layoutDirection: LayoutDirection = LayoutDirection.LTR,
) : AbstractFlexContainerTest<@Composable (Modifier) -> Unit>() {

  override val widgetFactory = ComposeUiTestWidgetFactory

  @get:Rule
  val paparazzi = Paparazzi(
    deviceConfig = DeviceConfig.PIXEL_6.copy(layoutDirection = layoutDirection),
    theme = "android:Theme.Material.Light.NoActionBar",
    supportsRtl = true,
  )

  override fun flexContainer(
    direction: FlexDirection,
    backgroundColor: Int,
  ): TestFlexContainer<@Composable (Modifier) -> Unit> {
    return ComposeTestFlexContainer(direction, backgroundColor)
      .apply { (this as TestFlexContainer<*>).applyDefaults() }
  }

  override fun row(): Row<@Composable (Modifier) -> Unit> = ComposeUiRow()
    .apply {
      container.testOnlyModifier = Modifier.background(Color(defaultBackgroundColor))
      applyDefaults()
    }

  override fun column(): Column<@Composable (Modifier) -> Unit> = ComposeUiColumn()
    .apply {
      container.testOnlyModifier = Modifier.background(Color(defaultBackgroundColor))
      applyDefaults()
    }

  override fun spacer(backgroundColor: Int): Spacer<@Composable (Modifier) -> Unit> {
    return ComposeUiSpacer().apply {
      testOnlyModifier = Modifier.background(Color(backgroundColor))
    }
  }

  override fun snapshotter(widget: @Composable (Modifier) -> Unit) = ComposeSnapshotter(paparazzi, widget)

  class ComposeTestFlexContainer private constructor(
    private val delegate: ComposeUiFlexContainer,
  ) : TestFlexContainer<@Composable (Modifier) -> Unit>,
    YogaFlexContainer<@Composable (Modifier) -> Unit> by delegate {

    constructor(direction: FlexDirection, backgroundColor: Int) : this(
      ComposeUiFlexContainer(direction).apply {
        testOnlyModifier = Modifier.background(Color(backgroundColor))
      },
    )

    override val value get() = delegate.value
    override var modifier by delegate::modifier

    override val children get() = delegate.children

    override fun width(width: Constraint) = delegate.width(width)
    override fun height(height: Constraint) = delegate.height(height)
    override fun overflow(overflow: Overflow) = delegate.overflow(overflow)
    override fun onScroll(onScroll: ((Px) -> Unit)?) = delegate.onScroll(onScroll)

    override fun scroll(offset: Px) {
      runBlocking {
        delegate.scrollState?.scrollTo(offset.value.toInt())
      }
    }

    override fun onEndChanges() {
    }
  }
}
