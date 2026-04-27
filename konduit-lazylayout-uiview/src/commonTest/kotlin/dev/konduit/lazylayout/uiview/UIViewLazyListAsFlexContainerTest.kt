/*
 * Copyright (C) 2024 Square, Inc.
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
package dev.konduit.lazylayout.uiview

import dev.konduit.layout.AbstractFlexContainerTest
import dev.konduit.layout.TestFlexContainer
import dev.konduit.layout.api.MainAxisAlignment
import dev.konduit.layout.api.Overflow
import dev.konduit.layout.uiview.UIViewRedwoodLayoutWidgetFactory
import dev.konduit.layout.widget.Spacer
import dev.konduit.lazylayout.toUIColor
import dev.konduit.lazylayout.widget.LazyList
import dev.konduit.snapshot.testing.UIViewSnapshotCallback
import dev.konduit.snapshot.testing.UIViewSnapshotter
import dev.konduit.snapshot.testing.UIViewTestWidgetFactory
import dev.konduit.ui.Px
import dev.konduit.widget.ChangeListener
import dev.konduit.widget.Widget
import dev.konduit.yoga.FlexDirection
import platform.UIKit.UIView

class UIViewLazyListAsFlexContainerTest(
  private val callback: UIViewSnapshotCallback,
) : AbstractFlexContainerTest<UIView>() {
  override val widgetFactory = UIViewTestWidgetFactory

  private val lazyLayoutWidgetFactory = UIViewRedwoodLazyLayoutWidgetFactory()

  override val viewMeasurementIsImpreciseAfterAnItemSizeChanges = true

  override fun flexContainer(
    direction: FlexDirection,
    backgroundColor: Int,
  ) = ViewTestFlexContainer(lazyLayoutWidgetFactory.LazyList(), direction, backgroundColor)
    .apply { applyDefaults() }

  override fun row() = UIViewRedwoodLayoutWidgetFactory().Row()

  override fun column() = UIViewRedwoodLayoutWidgetFactory().Column()

  override fun spacer(backgroundColor: Int): Spacer<UIView> {
    return UIViewRedwoodLayoutWidgetFactory().Spacer()
      .apply {
        value.backgroundColor = backgroundColor.toUIColor()
      }
  }

  override fun snapshotter(widget: UIView) = UIViewSnapshotter.framed(callback, widget)

  class ViewTestFlexContainer private constructor(
    private val delegate: LazyList<UIView>,
  ) : TestFlexContainer<UIView>,
    LazyList<UIView> by delegate {
    private var onScroll: ((Px) -> Unit)? = null

    constructor(delegate: LazyList<UIView>, direction: FlexDirection, backgroundColor: Int) : this(
      delegate.apply {
        isVertical(direction == FlexDirection.Column)
        value.backgroundColor = backgroundColor.toUIColor()
      },
    )

    override val children: Widget.Children<UIView> = delegate.items

    override fun onScroll(onScroll: ((Px) -> Unit)?) {
      this.onScroll = onScroll
    }

    override fun scroll(offset: Px) {
      onScroll?.invoke(offset)
    }

    override fun mainAxisAlignment(mainAxisAlignment: MainAxisAlignment) {
    }

    override fun overflow(overflow: Overflow) {
    }

    override fun onEndChanges() {
      (delegate as ChangeListener).onEndChanges()
    }
  }
}
