/*
 * Copyright (C) 2026 Keliver contributors.
 * Licensed under the Apache License, Version 2.0.
 */
@file:OptIn(ExperimentalMaterial3Api::class)

package dev.keliver.material.composeui

import androidx.compose.material3.BottomAppBar as M3BottomAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.NavigationBar as M3NavigationBar
import androidx.compose.material3.Scaffold as M3Scaffold
import androidx.compose.material3.Tab as M3Tab
import androidx.compose.material3.TabRow as M3TabRow
import androidx.compose.material3.Text as M3Text
import androidx.compose.material3.TopAppBar as M3TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.keliver.Modifier as RedwoodModifier
import dev.keliver.material.widget.BottomAppBar
import dev.keliver.material.widget.NavigationBar
import dev.keliver.material.widget.Scaffold
import dev.keliver.material.widget.Tab
import dev.keliver.material.widget.TabRow
import dev.keliver.material.widget.TopAppBar
import dev.keliver.widget.Widget
import dev.keliver.widget.compose.ComposeWidgetChildren

internal class ComposeUiScaffold : Scaffold<@Composable (Modifier) -> Unit> {
  override val topBar: Widget.Children<@Composable (Modifier) -> Unit> = ComposeWidgetChildren()
  override val content: Widget.Children<@Composable (Modifier) -> Unit> = ComposeWidgetChildren()
  override var modifier: RedwoodModifier = RedwoodModifier
  override val value: @Composable (Modifier) -> Unit = { m ->
    M3Scaffold(
      modifier = m,
      topBar = { (topBar as ComposeWidgetChildren).Render() },
    ) { _ -> (content as ComposeWidgetChildren).Render() }
  }
}

internal class ComposeUiTopAppBar : TopAppBar<@Composable (Modifier) -> Unit> {
  private var title by mutableStateOf("")
  override var modifier: RedwoodModifier = RedwoodModifier
  override val value: @Composable (Modifier) -> Unit = { m ->
    M3TopAppBar(title = { M3Text(title) }, modifier = m)
  }
  override fun title(title: String) { this.title = title }
}

internal class ComposeUiBottomAppBar : BottomAppBar<@Composable (Modifier) -> Unit> {
  override val children: Widget.Children<@Composable (Modifier) -> Unit> = ComposeWidgetChildren()
  override var modifier: RedwoodModifier = RedwoodModifier
  override val value: @Composable (Modifier) -> Unit = { m ->
    M3BottomAppBar(modifier = m) { (children as ComposeWidgetChildren).Render() }
  }
}

internal class ComposeUiNavigationBar : NavigationBar<@Composable (Modifier) -> Unit> {
  override val children: Widget.Children<@Composable (Modifier) -> Unit> = ComposeWidgetChildren()
  override var modifier: RedwoodModifier = RedwoodModifier
  override val value: @Composable (Modifier) -> Unit = { m ->
    M3NavigationBar(modifier = m) { (children as ComposeWidgetChildren).Render() }
  }
}

internal class ComposeUiTabRow : TabRow<@Composable (Modifier) -> Unit> {
  private var selectedIndex by mutableStateOf(0)
  override val tabs: Widget.Children<@Composable (Modifier) -> Unit> = ComposeWidgetChildren()
  override var modifier: RedwoodModifier = RedwoodModifier
  override val value: @Composable (Modifier) -> Unit = { m ->
    M3TabRow(selectedTabIndex = selectedIndex, modifier = m) {
      (tabs as ComposeWidgetChildren).Render()
    }
  }
  override fun selectedIndex(selectedIndex: Int) { this.selectedIndex = selectedIndex }
}

internal class ComposeUiTab : Tab<@Composable (Modifier) -> Unit> {
  private var text by mutableStateOf("")
  private var selected by mutableStateOf(false)
  private var onClick by mutableStateOf<(() -> Unit)?>(null)
  override var modifier: RedwoodModifier = RedwoodModifier
  override val value: @Composable (Modifier) -> Unit = { m ->
    M3Tab(
      selected = selected,
      onClick = { onClick?.invoke() },
      text = { M3Text(text) },
      modifier = m,
    )
  }
  override fun text(text: String) { this.text = text }
  override fun selected(selected: Boolean) { this.selected = selected }
  override fun onClick(onClick: (() -> Unit)?) { this.onClick = onClick }
}
