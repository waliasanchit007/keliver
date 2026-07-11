/*
 * Copyright (C) 2026 Keliver contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.keliver.material.composeui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon as M3Icon
import androidx.compose.material3.ListItem as M3ListItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text as M3Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.keliver.Modifier as RedwoodModifier
import dev.keliver.material.widget.Icon
import dev.keliver.material.widget.ListItem

/** Batch 15: Material icon by NAME over the curated core set (MaterialIcons.kt). */
internal class ComposeUiIcon : Icon<@Composable (Modifier) -> Unit> {
  private var name by mutableStateOf("")
  private var sizeDp by mutableStateOf(24)
  private var tintArgb by mutableStateOf(0)
  private var contentDescription by mutableStateOf("")
  override var modifier: RedwoodModifier = RedwoodModifier
  override val value: @Composable (Modifier) -> Unit = { m ->
    M3Icon(
      imageVector = iconOrPlaceholder(name),
      contentDescription = contentDescription.ifEmpty { name },
      tint = if (tintArgb == 0) LocalContentColor.current else Color(tintArgb),
      modifier = m.size(sizeDp.dp),
    )
  }
  override fun name(name: String) { this.name = name }
  override fun sizeDp(sizeDp: Int) { this.sizeDp = sizeDp }
  override fun tintArgb(tintArgb: Int) { this.tintArgb = tintArgb }
  override fun contentDescription(contentDescription: String) { this.contentDescription = contentDescription }
}

/** Batch 15: Material3 list row with name-based leading/trailing icons. */
internal class ComposeUiListItem : ListItem<@Composable (Modifier) -> Unit> {
  private var headline by mutableStateOf("")
  private var supporting by mutableStateOf("")
  private var overline by mutableStateOf("")
  private var leadingIcon by mutableStateOf("")
  private var trailingIcon by mutableStateOf("")
  private var onClick by mutableStateOf<(() -> Unit)?>(null)
  override var modifier: RedwoodModifier = RedwoodModifier
  override val value: @Composable (Modifier) -> Unit = { m ->
    val click = onClick
    M3ListItem(
      headlineContent = { M3Text(headline) },
      supportingContent = if (supporting.isEmpty()) null else ({ M3Text(supporting) }),
      overlineContent = if (overline.isEmpty()) null else ({ M3Text(overline) }),
      leadingContent = if (leadingIcon.isEmpty()) null else ({
        M3Icon(iconOrPlaceholder(leadingIcon), contentDescription = leadingIcon)
      }),
      trailingContent = if (trailingIcon.isEmpty()) null else ({
        M3Icon(iconOrPlaceholder(trailingIcon), contentDescription = trailingIcon)
      }),
      modifier = if (click == null) m else m.clickable { click() },
    )
  }
  override fun headline(headline: String) { this.headline = headline }
  override fun supporting(supporting: String) { this.supporting = supporting }
  override fun overline(overline: String) { this.overline = overline }
  override fun leadingIcon(leadingIcon: String) { this.leadingIcon = leadingIcon }
  override fun trailingIcon(trailingIcon: String) { this.trailingIcon = trailingIcon }
  override fun onClick(onClick: (() -> Unit)?) { this.onClick = onClick }
}
