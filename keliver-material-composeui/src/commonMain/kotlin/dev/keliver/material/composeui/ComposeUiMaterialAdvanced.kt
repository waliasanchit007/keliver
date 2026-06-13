/*
 * Copyright (C) 2026 Keliver contributors.
 * Licensed under the Apache License, Version 2.0.
 */
@file:OptIn(ExperimentalMaterial3Api::class)

package dev.keliver.material.composeui

import androidx.compose.material3.DropdownMenu as M3DropdownMenu
import androidx.compose.material3.DropdownMenuItem as M3DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton as M3ExtendedFloatingActionButton
import androidx.compose.material3.NavigationRail as M3NavigationRail
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text as M3Text
import androidx.compose.material3.VerticalDivider as M3VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.keliver.Modifier as RedwoodModifier
import dev.keliver.material.widget.DropdownMenu
import dev.keliver.material.widget.ExtendedFloatingActionButton
import dev.keliver.material.widget.NavigationRail
import dev.keliver.material.widget.SegmentedButtonRow
import dev.keliver.material.widget.VerticalDivider
import dev.keliver.widget.Widget
import dev.keliver.widget.compose.ComposeWidgetChildren

internal class ComposeUiNavigationRail : NavigationRail<@Composable (Modifier) -> Unit> {
  override val children: Widget.Children<@Composable (Modifier) -> Unit> = ComposeWidgetChildren()
  override var modifier: RedwoodModifier = RedwoodModifier
  override val value: @Composable (Modifier) -> Unit = { m ->
    M3NavigationRail(modifier = m) { (children as ComposeWidgetChildren).Render() }
  }
}

internal class ComposeUiVerticalDivider : VerticalDivider<@Composable (Modifier) -> Unit> {
  private var thickness by mutableStateOf(1)
  override var modifier: RedwoodModifier = RedwoodModifier
  override val value: @Composable (Modifier) -> Unit = { m ->
    M3VerticalDivider(modifier = m, thickness = thickness.dp)
  }
  override fun thickness(thickness: Int) { this.thickness = thickness }
}

internal class ComposeUiExtendedFloatingActionButton :
  ExtendedFloatingActionButton<@Composable (Modifier) -> Unit> {
  private var text by mutableStateOf("")
  private var onClick by mutableStateOf<(() -> Unit)?>(null)
  override var modifier: RedwoodModifier = RedwoodModifier
  override val value: @Composable (Modifier) -> Unit = { m ->
    M3ExtendedFloatingActionButton(onClick = { onClick?.invoke() }, modifier = m) { M3Text(text) }
  }
  override fun text(text: String) { this.text = text }
  override fun onClick(onClick: (() -> Unit)?) { this.onClick = onClick }
}

internal class ComposeUiSegmentedButtonRow : SegmentedButtonRow<@Composable (Modifier) -> Unit> {
  private var options by mutableStateOf<List<String>>(emptyList())
  private var selectedIndex by mutableStateOf(0)
  private var onSelect by mutableStateOf<((Int) -> Unit)?>(null)
  override var modifier: RedwoodModifier = RedwoodModifier
  override val value: @Composable (Modifier) -> Unit = { m ->
    SingleChoiceSegmentedButtonRow(modifier = m) {
      options.forEachIndexed { i, opt ->
        SegmentedButton(
          selected = i == selectedIndex,
          onClick = { onSelect?.invoke(i) },
          shape = SegmentedButtonDefaults.itemShape(index = i, count = options.size),
        ) { M3Text(opt) }
      }
    }
  }
  override fun options(options: List<String>) { this.options = options }
  override fun selectedIndex(selectedIndex: Int) { this.selectedIndex = selectedIndex }
  override fun onSelect(onSelect: ((Int) -> Unit)?) { this.onSelect = onSelect }
}

internal class ComposeUiDropdownMenu : DropdownMenu<@Composable (Modifier) -> Unit> {
  private var expanded by mutableStateOf(false)
  private var options by mutableStateOf<List<String>>(emptyList())
  private var onSelect by mutableStateOf<((Int) -> Unit)?>(null)
  private var onDismiss by mutableStateOf<(() -> Unit)?>(null)
  override var modifier: RedwoodModifier = RedwoodModifier
  override val value: @Composable (Modifier) -> Unit = { _ ->
    M3DropdownMenu(expanded = expanded, onDismissRequest = { onDismiss?.invoke() }) {
      options.forEachIndexed { i, opt ->
        M3DropdownMenuItem(text = { M3Text(opt) }, onClick = { onSelect?.invoke(i) })
      }
    }
  }
  override fun expanded(expanded: Boolean) { this.expanded = expanded }
  override fun options(options: List<String>) { this.options = options }
  override fun onSelect(onSelect: ((Int) -> Unit)?) { this.onSelect = onSelect }
  override fun onDismiss(onDismiss: (() -> Unit)?) { this.onDismiss = onDismiss }
}
