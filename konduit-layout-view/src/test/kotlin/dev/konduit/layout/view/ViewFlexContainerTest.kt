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
package dev.konduit.layout.view

import android.view.View
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
import dev.konduit.snapshot.testing.ViewSnapshotter
import dev.konduit.snapshot.testing.ViewTestWidgetFactory
import dev.konduit.ui.Px
import dev.konduit.widget.ChangeListener
import dev.konduit.widget.ViewGroupChildren
import dev.konduit.yoga.FlexDirection
import com.android.resources.LayoutDirection
import org.junit.Rule

@Burst
class ViewFlexContainerTest(
  layoutDirection: LayoutDirection = LayoutDirection.LTR,
) : AbstractFlexContainerTest<View>() {

  @get:Rule
  val paparazzi = Paparazzi(
    deviceConfig = DeviceConfig.PIXEL_6.copy(layoutDirection = layoutDirection),
    theme = "android:Theme.Material.Light.NoActionBar",
    supportsRtl = true,
  )

  override val widgetFactory: ViewTestWidgetFactory
    get() = ViewTestWidgetFactory(paparazzi.context)

  override fun flexContainer(
    direction: FlexDirection,
    backgroundColor: Int,
  ): ViewTestFlexContainer {
    val delegate = ViewFlexContainer(paparazzi.context, direction).apply {
      value.setBackgroundColor(backgroundColor)
    }
    return ViewTestFlexContainer(delegate)
      .apply { (this as TestFlexContainer<*>).applyDefaults() }
  }

  override fun row(): Row<View> = ViewRow(paparazzi.context).apply {
    value.setBackgroundColor(defaultBackgroundColor)
    applyDefaults()
  }

  override fun column(): Column<View> = ViewColumn(paparazzi.context).apply {
    value.setBackgroundColor(defaultBackgroundColor)
    applyDefaults()
  }

  override fun spacer(backgroundColor: Int): Spacer<View> {
    return ViewSpacer(paparazzi.context)
      .apply {
        value.setBackgroundColor(backgroundColor)
      }
  }

  override fun snapshotter(widget: View) = ViewSnapshotter(paparazzi, widget)

  class ViewTestFlexContainer internal constructor(
    private val delegate: ViewFlexContainer,
  ) : TestFlexContainer<View>,
    YogaFlexContainer<View> by delegate,
    ChangeListener by delegate {
    override val value: View get() = delegate.value
    override var modifier by delegate::modifier

    override val children: ViewGroupChildren = delegate.children

    override fun width(width: Constraint) = delegate.width(width)
    override fun height(height: Constraint) = delegate.height(height)
    override fun overflow(overflow: Overflow) = delegate.overflow(overflow)
    override fun onScroll(onScroll: ((Px) -> Unit)?) = delegate.onScroll(onScroll)

    override fun scroll(offset: Px) {
      delegate.onScroll?.invoke(offset)
    }
  }
}
