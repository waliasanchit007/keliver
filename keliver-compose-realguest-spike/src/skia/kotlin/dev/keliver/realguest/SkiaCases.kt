/*
 * Copyright (C) 2026 Keliver contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.keliver.realguest

import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.LinearGradient
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/*
 * The calls whose implementation on Kotlin/JS is Skia's (skiko): a guest that makes them links
 * skiko-kjs, which imports Skia's wasm loader (./skiko.mjs). The harness bundles a stub for it whose
 * every function throws "skiko call <name>", so these cases show where Skia is first called.
 */
fun skiaCases(): List<Case> = listOf(
  Case("skia.brushShader") {
    val b = Brush.linearGradient(listOf(Color.Red, Color.Blue)) as LinearGradient
    "shader=${b.createShader(Size(10f, 10f))}"
  },
  Case("skia.pathObject") {
    val p = Path()
    p.moveTo(0f, 0f)
    p.lineTo(10f, 10f)
    p.close()
    "bounds=${p.getBounds()}"
  },
  Case("skia.colorFilter") {
    "tint=${ColorFilter.tint(Color.Red, BlendMode.SrcIn)}"
  },
  // CutCornerShape's outline is a generic Path: the platform's (Skia) on JS.
  Case("skia.cutCornerOutline") {
    val c = CutCornerShape(8.dp)
    "shape=$c outline=${c.createOutline(Size(100f, 40f), LayoutDirection.Ltr, Density(2f))}"
  },
)
