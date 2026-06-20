/*
 * Copyright (C) 2026 Keliver contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.keliver.material.composeui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.keliver.material.modifier.Alpha
import dev.keliver.material.modifier.AnimateContentSize
import dev.keliver.material.modifier.AspectRatio
import dev.keliver.material.modifier.Background
import dev.keliver.material.modifier.Blur
import dev.keliver.material.modifier.Border
import dev.keliver.material.modifier.CornerRadius
import dev.keliver.material.modifier.CornerRadiusEach
import dev.keliver.material.modifier.FillMaxHeight
import dev.keliver.material.modifier.FillMaxSize
import dev.keliver.material.modifier.FillWidth
import dev.keliver.material.modifier.GradientBackground
import dev.keliver.material.modifier.Offset
import dev.keliver.material.modifier.Padding
import dev.keliver.material.modifier.PaddingEach
import dev.keliver.material.modifier.Rotate
import dev.keliver.material.modifier.Scale
import dev.keliver.material.modifier.Shadow
import dev.keliver.material.modifier.Size
import dev.keliver.Modifier as RedwoodModifier

/**
 * Translates a widget's keliver [RedwoodModifier] chain into a real Compose
 * [Modifier], applied in the order the guest declared — the keliver answer to
 * native `Modifier.background().clip().border().padding()`. Every keliver-material
 * widget renderer calls `incoming.applyKeliverVisuals(modifier)`.
 *
 * Corner radius is pre-scanned so `shadow`/`border`/`clip` share one rounded
 * shape regardless of chain order (matches how a dev reasons about a rounded
 * card with a shadow + border).
 */
public fun Modifier.applyKeliverVisuals(keliver: RedwoodModifier): Modifier {
  var radiusDp = 0
  keliver.forEachUnscoped { if (it is CornerRadius) radiusDp = it.radiusDp }
  val shape = RoundedCornerShape(radiusDp.dp)

  var m = this
  keliver.forEachUnscoped { element ->
    m = when (element) {
      is Shadow -> m.shadow(element.elevationDp.dp, shape, clip = false)
      is CornerRadius -> m.clip(shape)
      is Background -> m.background(Color(element.colorArgb), shape)
      is GradientBackground -> m.background(
        Brush.verticalGradient(listOf(Color(element.startArgb), Color(element.endArgb))),
        shape,
      )
      is Border -> m.border(element.widthDp.dp, Color(element.colorArgb), shape)
      is CornerRadiusEach -> m.clip(
        RoundedCornerShape(
          element.topStartDp.dp, element.topEndDp.dp,
          element.bottomEndDp.dp, element.bottomStartDp.dp,
        ),
      )
      is Size -> {
        var s = m
        if (element.widthDp > 0) s = s.width(element.widthDp.dp)
        if (element.heightDp > 0) s = s.height(element.heightDp.dp)
        s
      }
      is FillWidth -> m.fillMaxWidth()
      is FillMaxHeight -> m.fillMaxHeight()
      is FillMaxSize -> m.fillMaxSize()
      is AspectRatio -> m.aspectRatio(element.ratio)
      is Offset -> m.offset(element.xDp.dp, element.yDp.dp)
      is Rotate -> m.rotate(element.degrees.toFloat())
      is Scale -> m.scale(element.percent / 100f)
      is Blur -> m.blur(element.radiusDp.dp)
      is AnimateContentSize -> m.animateContentSize()
      is Alpha -> m.alpha(element.pct / 100f)
      is Padding -> m.padding(element.allDp.dp)
      is PaddingEach -> m.padding(element.startDp.dp, element.topDp.dp, element.endDp.dp, element.bottomDp.dp)
      else -> m // Reuse + any modifier this host doesn't render: ignore.
    }
  }
  return m
}
