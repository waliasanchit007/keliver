/*
 * Copyright (C) 2023 Square, Inc.
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
package dev.konduit.treehouse

import dev.konduit.layout.testing.RedwoodLayoutTestingWidgetFactory
import dev.konduit.lazylayout.testing.RedwoodLazyLayoutTestingWidgetFactory
import dev.konduit.testing.WidgetValue
import dev.konduit.treehouse.TreehouseView.ReadyForContentChangeListener
import dev.konduit.ui.OnBackPressedDispatcher
import dev.konduit.ui.UiConfiguration
import dev.konduit.ui.basic.testing.RedwoodUiBasicTestingWidgetFactory
import dev.konduit.widget.MutableListChildren
import dev.konduit.widget.SavedStateRegistry
import com.example.redwood.testapp.testing.TestSchemaTestingWidgetFactory
import com.example.redwood.testapp.widget.TestSchemaWidgetSystem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * An in-memory fake.
 */
internal class FakeTreehouseView(
  private val name: String,
  override val onBackPressedDispatcher: OnBackPressedDispatcher,
  override val uiConfiguration: StateFlow<UiConfiguration> = MutableStateFlow(UiConfiguration()),
) : TreehouseView<WidgetValue> {
  override val children = MutableListChildren<WidgetValue>()

  val views: List<WidgetValue>
    get() = children.map { it.value }

  override val value: WidgetValue
    get() = error("unexpected call")

  override val widgetSystem = TestSchemaWidgetSystem(
    TestSchema = TestSchemaTestingWidgetFactory(),
    RedwoodUiBasic = RedwoodUiBasicTestingWidgetFactory(),
    RedwoodLayout = RedwoodLayoutTestingWidgetFactory(),
    RedwoodLazyLayout = RedwoodLazyLayoutTestingWidgetFactory(),
  )

  override val dynamicContentWidgetFactory = FakeDynamicContentWidgetFactory()

  override var readyForContentChangeListener: ReadyForContentChangeListener<WidgetValue>? = null

  override var readyForContent = false
    set(value) {
      field = value
      readyForContentChangeListener?.onReadyForContentChanged(this)
    }

  override var saveCallback: TreehouseView.SaveCallback? = null

  override val stateSnapshotId: StateSnapshot.Id = StateSnapshot.Id(null)

  override val savedStateRegistry: SavedStateRegistry? = null

  override fun toString() = name
}
