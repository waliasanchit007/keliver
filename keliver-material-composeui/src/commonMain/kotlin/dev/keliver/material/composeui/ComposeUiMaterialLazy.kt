/*
 * Copyright (C) 2026 Keliver contributors.
 * Licensed under the Apache License, Version 2.0.
 */
@file:OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)

package dev.keliver.material.composeui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid as M3LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid as M3LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager as M3HorizontalPager
import androidx.compose.foundation.pager.VerticalPager as M3VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text as M3Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.keliver.Modifier as RedwoodModifier
import dev.keliver.material.widget.HorizontalPager
import dev.keliver.material.widget.LazyHorizontalGrid
import dev.keliver.material.widget.LazyVerticalGrid
import dev.keliver.material.widget.Tooltip
import dev.keliver.material.widget.VerticalPager
import dev.keliver.widget.Widget
import dev.keliver.widget.compose.ComposeWidgetChildren

internal class ComposeUiLazyVerticalGrid : LazyVerticalGrid<@Composable (Modifier) -> Unit> {
  private var columns by mutableStateOf(2)
  override val children: Widget.Children<@Composable (Modifier) -> Unit> = ComposeWidgetChildren()
  override var modifier: RedwoodModifier = RedwoodModifier
  override val value: @Composable (Modifier) -> Unit = { m ->
    val kids = children as ComposeWidgetChildren
    M3LazyVerticalGrid(columns = GridCells.Fixed(columns.coerceAtLeast(1)), modifier = m) {
      items(kids.widgets.size) { index -> kids.widgets[index].value(Modifier) }
    }
  }
  override fun columns(columns: Int) { this.columns = columns }
}

internal class ComposeUiLazyHorizontalGrid : LazyHorizontalGrid<@Composable (Modifier) -> Unit> {
  private var rows by mutableStateOf(2)
  override val children: Widget.Children<@Composable (Modifier) -> Unit> = ComposeWidgetChildren()
  override var modifier: RedwoodModifier = RedwoodModifier
  override val value: @Composable (Modifier) -> Unit = { m ->
    val kids = children as ComposeWidgetChildren
    M3LazyHorizontalGrid(rows = GridCells.Fixed(rows.coerceAtLeast(1)), modifier = m) {
      items(kids.widgets.size) { index -> kids.widgets[index].value(Modifier) }
    }
  }
  override fun rows(rows: Int) { this.rows = rows }
}

internal class ComposeUiHorizontalPager : HorizontalPager<@Composable (Modifier) -> Unit> {
  private var autoScrollMs by mutableStateOf(0)
  private var showIndicator by mutableStateOf(false)
  private var indicatorActiveArgb by mutableStateOf(0)
  private var indicatorInactiveArgb by mutableStateOf(0)
  private var contentPaddingDp by mutableStateOf(0)
  override val children: Widget.Children<@Composable (Modifier) -> Unit> = ComposeWidgetChildren()
  override var modifier: RedwoodModifier = RedwoodModifier
  override val value: @Composable (Modifier) -> Unit = { m ->
    val kids = children as ComposeWidgetChildren
    val count = kids.widgets.size
    val state = rememberPagerState(pageCount = { count })
    // Auto-advance (wrap to 0), but not while the user is dragging.
    if (autoScrollMs > 0 && count > 1) {
      LaunchedEffect(state, count, autoScrollMs) {
        while (true) {
          delay(autoScrollMs.toLong())
          if (!state.isScrollInProgress) {
            state.animateScrollToPage((state.currentPage + 1) % count)
          }
        }
      }
    }
    Column(modifier = m) {
      M3HorizontalPager(
        state = state,
        contentPadding = PaddingValues(horizontal = contentPaddingDp.dp),
        modifier = Modifier.fillMaxWidth(),
      ) { page ->
        if (page < count) kids.widgets[page].value(Modifier)
      }
      if (showIndicator && count > 1) {
        Spacer(Modifier.height(8.dp))
        val active = if (indicatorActiveArgb != 0) Color(indicatorActiveArgb) else Color(0xFFEF696C)
        val inactive = if (indicatorInactiveArgb != 0) Color(indicatorInactiveArgb) else Color(0xFFD3D3D3)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
          repeat(count) { i ->
            Box(
              Modifier.padding(horizontal = 2.dp).size(6.dp).clip(CircleShape)
                .background(if (i == state.currentPage) active else inactive),
            )
          }
        }
      }
    }
  }
  override fun autoScrollMs(autoScrollMs: Int) { this.autoScrollMs = autoScrollMs }
  override fun showIndicator(showIndicator: Boolean) { this.showIndicator = showIndicator }
  override fun indicatorActiveArgb(indicatorActiveArgb: Int) { this.indicatorActiveArgb = indicatorActiveArgb }
  override fun indicatorInactiveArgb(indicatorInactiveArgb: Int) { this.indicatorInactiveArgb = indicatorInactiveArgb }
  override fun contentPaddingDp(contentPaddingDp: Int) { this.contentPaddingDp = contentPaddingDp }
}

internal class ComposeUiVerticalPager : VerticalPager<@Composable (Modifier) -> Unit> {
  override val children: Widget.Children<@Composable (Modifier) -> Unit> = ComposeWidgetChildren()
  override var modifier: RedwoodModifier = RedwoodModifier
  override val value: @Composable (Modifier) -> Unit = { m ->
    val kids = children as ComposeWidgetChildren
    val state = rememberPagerState(pageCount = { kids.widgets.size })
    M3VerticalPager(state = state, modifier = m) { page ->
      if (page < kids.widgets.size) kids.widgets[page].value(Modifier)
    }
  }
}

internal class ComposeUiTooltip : Tooltip<@Composable (Modifier) -> Unit> {
  private var text by mutableStateOf("")
  override val children: Widget.Children<@Composable (Modifier) -> Unit> = ComposeWidgetChildren()
  override var modifier: RedwoodModifier = RedwoodModifier
  override val value: @Composable (Modifier) -> Unit = { m ->
    TooltipBox(
      positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
      tooltip = { PlainTooltip { M3Text(text) } },
      state = rememberTooltipState(),
      modifier = m,
    ) { (children as ComposeWidgetChildren).Render() }
  }
  override fun text(text: String) { this.text = text }
}
