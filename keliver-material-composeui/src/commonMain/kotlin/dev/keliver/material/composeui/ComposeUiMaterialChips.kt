/*
 * Copyright (C) 2026 Keliver contributors.
 * Licensed under the Apache License, Version 2.0.
 */
@file:OptIn(ExperimentalMaterial3Api::class)

package dev.keliver.material.composeui

import androidx.compose.material3.Badge as M3Badge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip as M3FilterChip
import androidx.compose.material3.InputChip as M3InputChip
import androidx.compose.material3.SuggestionChip as M3SuggestionChip
import androidx.compose.material3.Text as M3Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.keliver.Modifier as RedwoodModifier
import dev.keliver.material.widget.Badge
import dev.keliver.material.widget.FilterChip
import dev.keliver.material.widget.InputChip
import dev.keliver.material.widget.SuggestionChip

internal class ComposeUiFilterChip : FilterChip<@Composable (Modifier) -> Unit> {
  private var label by mutableStateOf("")
  private var selected by mutableStateOf(false)
  private var onClick by mutableStateOf<(() -> Unit)?>(null)
  override var modifier: RedwoodModifier = RedwoodModifier
  override val value: @Composable (Modifier) -> Unit = { m ->
    M3FilterChip(
      selected = selected,
      onClick = { onClick?.invoke() },
      label = { M3Text(label) },
      modifier = m,
    )
  }
  override fun label(label: String) { this.label = label }
  override fun selected(selected: Boolean) { this.selected = selected }
  override fun onClick(onClick: (() -> Unit)?) { this.onClick = onClick }
}

internal class ComposeUiInputChip : InputChip<@Composable (Modifier) -> Unit> {
  private var label by mutableStateOf("")
  private var selected by mutableStateOf(false)
  private var onClick by mutableStateOf<(() -> Unit)?>(null)
  override var modifier: RedwoodModifier = RedwoodModifier
  override val value: @Composable (Modifier) -> Unit = { m ->
    M3InputChip(
      selected = selected,
      onClick = { onClick?.invoke() },
      label = { M3Text(label) },
      modifier = m,
    )
  }
  override fun label(label: String) { this.label = label }
  override fun selected(selected: Boolean) { this.selected = selected }
  override fun onClick(onClick: (() -> Unit)?) { this.onClick = onClick }
}

internal class ComposeUiSuggestionChip : SuggestionChip<@Composable (Modifier) -> Unit> {
  private var label by mutableStateOf("")
  private var onClick by mutableStateOf<(() -> Unit)?>(null)
  override var modifier: RedwoodModifier = RedwoodModifier
  override val value: @Composable (Modifier) -> Unit = { m ->
    M3SuggestionChip(
      onClick = { onClick?.invoke() },
      label = { M3Text(label) },
      modifier = m,
    )
  }
  override fun label(label: String) { this.label = label }
  override fun onClick(onClick: (() -> Unit)?) { this.onClick = onClick }
}

internal class ComposeUiBadge : Badge<@Composable (Modifier) -> Unit> {
  private var text by mutableStateOf("")
  override var modifier: RedwoodModifier = RedwoodModifier
  override val value: @Composable (Modifier) -> Unit = { m ->
    if (text.isEmpty()) M3Badge(modifier = m) else M3Badge(modifier = m) { M3Text(text) }
  }
  override fun text(text: String) { this.text = text }
}
