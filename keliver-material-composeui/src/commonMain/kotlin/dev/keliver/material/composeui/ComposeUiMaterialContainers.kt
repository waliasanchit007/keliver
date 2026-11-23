/*
 * Copyright (C) 2026 Keliver contributors.
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

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowColumn as ComposeFlowColumn
import androidx.compose.foundation.layout.FlowRow as ComposeFlowRow
import androidx.compose.material3.ElevatedCard as M3ElevatedCard
import androidx.compose.material3.OutlinedCard as M3OutlinedCard
import androidx.compose.material3.Surface as M3Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.keliver.Modifier as RedwoodModifier
import dev.keliver.material.widget.ElevatedCard
import dev.keliver.material.widget.FlowColumn
import dev.keliver.material.widget.FlowRow
import dev.keliver.material.widget.OutlinedCard
import dev.keliver.material.widget.Surface
import dev.keliver.widget.Widget
import dev.keliver.widget.compose.ComposeWidgetChildren

internal class ComposeUiSurface : Surface<@Composable (Modifier) -> Unit> {
  override val children: Widget.Children<@Composable (Modifier) -> Unit> = ComposeWidgetChildren()
  override var modifier: RedwoodModifier = RedwoodModifier
  override val value: @Composable (Modifier) -> Unit = { m ->
    M3Surface(modifier = m) { (children as ComposeWidgetChildren).Render() }
  }
}

internal class ComposeUiElevatedCard : ElevatedCard<@Composable (Modifier) -> Unit> {
  override val children: Widget.Children<@Composable (Modifier) -> Unit> = ComposeWidgetChildren()
  override var modifier: RedwoodModifier = RedwoodModifier
  override val value: @Composable (Modifier) -> Unit = { m ->
    M3ElevatedCard(modifier = m) { (children as ComposeWidgetChildren).Render() }
  }
}

internal class ComposeUiOutlinedCard : OutlinedCard<@Composable (Modifier) -> Unit> {
  override val children: Widget.Children<@Composable (Modifier) -> Unit> = ComposeWidgetChildren()
  override var modifier: RedwoodModifier = RedwoodModifier
  override val value: @Composable (Modifier) -> Unit = { m ->
    M3OutlinedCard(modifier = m) { (children as ComposeWidgetChildren).Render() }
  }
}

@OptIn(ExperimentalLayoutApi::class)
internal class ComposeUiFlowRow : FlowRow<@Composable (Modifier) -> Unit> {
  override val children: Widget.Children<@Composable (Modifier) -> Unit> = ComposeWidgetChildren()
  override var modifier: RedwoodModifier = RedwoodModifier
  override val value: @Composable (Modifier) -> Unit = { m ->
    ComposeFlowRow(modifier = m) { (children as ComposeWidgetChildren).Render() }
  }
}

@OptIn(ExperimentalLayoutApi::class)
internal class ComposeUiFlowColumn : FlowColumn<@Composable (Modifier) -> Unit> {
  override val children: Widget.Children<@Composable (Modifier) -> Unit> = ComposeWidgetChildren()
  override var modifier: RedwoodModifier = RedwoodModifier
  override val value: @Composable (Modifier) -> Unit = { m ->
    ComposeFlowColumn(modifier = m) { (children as ComposeWidgetChildren).Render() }
  }
}
