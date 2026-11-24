/*
 * Copyright (C) 2026 Keliver contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.keliver.realguest

import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/** The `ui` artifact's value types: Alignment (androidx.compose.ui) and ImageVector (…graphics.vector). */
fun uiCases(): List<Case> = listOf(
  Case("ui.alignment", bench = { n ->
    var x = 0
    repeat(n) { x += Alignment.Center.align(IntSize(10, 10), IntSize(100, 100), LayoutDirection.Rtl).x }
    x
  }) {
    val a = Alignment.BottomEnd
    "center=${Alignment.Center} placed=${a.align(IntSize(10, 10), IntSize(100, 100), LayoutDirection.Rtl)} " +
      "eq=${Alignment.Center == BiasAlignment(0f, 0f)} bias=${(a as BiasAlignment).horizontalBias},${a.verticalBias} " +
      "vertical=${Alignment.CenterVertically} plus=${Alignment.Start}"
  },
  Case("ui.imageVector", bench = { n ->
    var v: ImageVector? = null
    repeat(n) { v = searchIcon() }
    v
  }) {
    val v = searchIcon()
    val first = v.root.iterator().next() as VectorPath
    val dsl = ImageVector.Builder("box", 24.dp, 24.dp, 24f, 24f)
      .path(fill = SolidColor(Color.Black)) {
        moveTo(2f, 2f)
        lineTo(22f, 2f)
        lineTo(22f, 22f)
        close()
      }.build()
    "name=${v.name} size=${v.defaultWidth}x${v.defaultHeight} viewport=${v.viewportWidth} " +
      "children=${v.root.size} nodes=${first.pathData.size} fill=${first.fill} dslNodes=${(dsl.root.iterator().next() as VectorPath).pathData} " +
      "eq=${v == searchIcon()}"
  },
)

private fun searchIcon(): ImageVector =
  ImageVector.Builder("Filled.Search", 24.dp, 24.dp, 24f, 24f)
    .addPath(pathData = PathParser().parsePathString(SEARCH_PATH).toNodes(), fill = SolidColor(Color.Black))
    .build()
