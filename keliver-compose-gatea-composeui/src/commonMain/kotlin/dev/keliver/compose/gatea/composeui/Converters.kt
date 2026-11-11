/*
 * Copyright (C) 2026 Square, Inc.
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
package dev.keliver.compose.gatea.composeui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.keliver.compose.foundation.BorderStroke as K_BorderStroke
import dev.keliver.compose.foundation.ScrollState as K_ScrollState
import dev.keliver.compose.foundation.layout.Arrangement as K_Arrangement
import dev.keliver.compose.foundation.layout.PaddingValues as K_PaddingValues
import dev.keliver.compose.foundation.lazy.LazyListStateWire as K_LazyListStateWire
import dev.keliver.compose.foundation.shape.CornerBasedShape as K_CornerBasedShape
import dev.keliver.compose.foundation.shape.CornerSize as K_CornerSize
import dev.keliver.compose.foundation.shape.CutCornerShape as K_CutCornerShape
import dev.keliver.compose.ui.Alignment as K_Alignment
import dev.keliver.compose.ui.graphics.BlendMode as K_BlendMode
import dev.keliver.compose.ui.graphics.Color as K_Color
import dev.keliver.compose.ui.graphics.ColorFilter as K_ColorFilter
import dev.keliver.compose.ui.graphics.FilterQuality as K_FilterQuality
import dev.keliver.compose.ui.graphics.Shape as K_Shape
import dev.keliver.compose.ui.graphics.ShapeReference as K_ShapeReference
import dev.keliver.compose.ui.graphics.painter.ColorPainter as K_ColorPainter
import dev.keliver.compose.ui.graphics.painter.Painter as K_Painter
import dev.keliver.compose.ui.graphics.painter.VectorPainter as K_VectorPainter
import dev.keliver.compose.ui.graphics.vector.ImageVector as K_ImageVector
import dev.keliver.compose.ui.layout.ContentScale as K_ContentScale
import dev.keliver.compose.ui.semantics.Role as K_Role
import dev.keliver.compose.ui.text.TextStyle as K_TextStyle
import dev.keliver.compose.ui.text.font.FontFamily as K_FontFamily
import dev.keliver.compose.ui.text.font.FontStyle as K_FontStyle
import dev.keliver.compose.ui.text.font.FontWeight as K_FontWeight
import dev.keliver.compose.ui.text.style.TextAlign as K_TextAlign
import dev.keliver.compose.ui.text.style.TextDecoration as K_TextDecoration
import dev.keliver.compose.ui.text.style.TextOverflow as K_TextOverflow
import dev.keliver.compose.ui.unit.Dp as K_Dp
import dev.keliver.compose.ui.unit.TextUnit as K_TextUnit
import dev.keliver.compose.ui.unit.TextUnitType as K_TextUnitType
import dev.keliver.compose.wire.DefaultsValue

/*
 * The host half of the value codecs (docs/COMPOSE_GENERATOR_DESIGN.md §6, §7), written by hand
 * once: each guest mirror becomes the real Compose value, and a theme reference is resolved here,
 * under the host's MaterialTheme, where the widget renders.
 */

internal fun K_Dp.toCompose(): Dp = Dp(value)

internal fun K_TextUnit.toCompose(): TextUnit = when (type) {
  K_TextUnitType.Sp -> value.sp
  K_TextUnitType.Em -> value.em
  else -> TextUnit.Unspecified
}

@Composable
internal fun K_Color.toCompose(): Color {
  val ref = referenceName ?: return if (this == K_Color.Unspecified) Color.Unspecified else Color(argb)
  val base = resolveColor(ref)
  return if (alpha == 1f) base else base.copy(alpha = alpha)
}

@Composable
private fun resolveColor(name: String): Color {
  if (name == "LocalContentColor") return LocalContentColor.current
  if (!name.startsWith("colorScheme.")) return defaultsColor(name) ?: Color.Unspecified
  val s = MaterialTheme.colorScheme
  return when (name.removePrefix("colorScheme.")) {
    "primary" -> s.primary
    "onPrimary" -> s.onPrimary
    "primaryContainer" -> s.primaryContainer
    "onPrimaryContainer" -> s.onPrimaryContainer
    "inversePrimary" -> s.inversePrimary
    "secondary" -> s.secondary
    "onSecondary" -> s.onSecondary
    "secondaryContainer" -> s.secondaryContainer
    "onSecondaryContainer" -> s.onSecondaryContainer
    "tertiary" -> s.tertiary
    "onTertiary" -> s.onTertiary
    "tertiaryContainer" -> s.tertiaryContainer
    "onTertiaryContainer" -> s.onTertiaryContainer
    "background" -> s.background
    "onBackground" -> s.onBackground
    "surface" -> s.surface
    "onSurface" -> s.onSurface
    "surfaceVariant" -> s.surfaceVariant
    "onSurfaceVariant" -> s.onSurfaceVariant
    "surfaceTint" -> s.surfaceTint
    "inverseSurface" -> s.inverseSurface
    "inverseOnSurface" -> s.inverseOnSurface
    "error" -> s.error
    "onError" -> s.onError
    "errorContainer" -> s.errorContainer
    "onErrorContainer" -> s.onErrorContainer
    "outline" -> s.outline
    "outlineVariant" -> s.outlineVariant
    "scrim" -> s.scrim
    "surfaceBright" -> s.surfaceBright
    "surfaceContainer" -> s.surfaceContainer
    "surfaceContainerHigh" -> s.surfaceContainerHigh
    "surfaceContainerHighest" -> s.surfaceContainerHighest
    "surfaceContainerLow" -> s.surfaceContainerLow
    "surfaceContainerLowest" -> s.surfaceContainerLowest
    "surfaceDim" -> s.surfaceDim
    else -> Color.Unspecified
  }
}

private fun K_CornerSize.toCompose(): CornerSize = percentOrNull()?.let { CornerSize(it) } ?: CornerSize(dpOrNull()!!.toCompose())

@Composable
internal fun K_Shape.toCompose(): Shape = when (this) {
  is K_CutCornerShape -> CutCornerShape(topStart.toCompose(), topEnd.toCompose(), bottomEnd.toCompose(), bottomStart.toCompose())
  is K_CornerBasedShape -> RoundedCornerShape(topStart.toCompose(), topEnd.toCompose(), bottomEnd.toCompose(), bottomStart.toCompose())
  is K_ShapeReference -> if (!name.startsWith("shapes.")) {
    defaultsShape(name) ?: RectangleShape // a generated `*Defaults` shape (`ButtonDefaults.shape`)
  } else {
    MaterialTheme.shapes.let { s ->
      when (name.removePrefix("shapes.")) {
        "extraSmall" -> s.extraSmall
        "small" -> s.small
        "medium" -> s.medium
        "large" -> s.large
        "extraLarge" -> s.extraLarge
        else -> RectangleShape
      }
    }
  }
  else -> RectangleShape
}

@Composable
internal fun K_TextStyle.toCompose(): TextStyle {
  val base = when (val r = reference) {
    null -> TextStyle.Default
    "LocalTextStyle" -> LocalTextStyle.current
    else -> MaterialTheme.typography.let { t ->
      when (r.removePrefix("typography.")) {
        "displayLarge" -> t.displayLarge
        "displayMedium" -> t.displayMedium
        "displaySmall" -> t.displaySmall
        "headlineLarge" -> t.headlineLarge
        "headlineMedium" -> t.headlineMedium
        "headlineSmall" -> t.headlineSmall
        "titleLarge" -> t.titleLarge
        "titleMedium" -> t.titleMedium
        "titleSmall" -> t.titleSmall
        "bodyLarge" -> t.bodyLarge
        "bodyMedium" -> t.bodyMedium
        "bodySmall" -> t.bodySmall
        "labelLarge" -> t.labelLarge
        "labelMedium" -> t.labelMedium
        "labelSmall" -> t.labelSmall
        else -> TextStyle.Default
      }
    }
  }
  return base.merge(
    TextStyle(
      color = color.toCompose(),
      fontSize = fontSize.toCompose(),
      fontWeight = fontWeight?.toCompose(),
      fontStyle = fontStyle?.toCompose(),
      fontFamily = fontFamily?.toCompose(),
      letterSpacing = letterSpacing.toCompose(),
      textDecoration = textDecoration?.toCompose(),
      textAlign = textAlign.toCompose(),
      lineHeight = lineHeight.toCompose(),
    ),
  )
}

internal fun K_FontWeight.toCompose(): FontWeight = FontWeight(weight)
internal fun K_FontStyle.toCompose(): FontStyle = if (this == K_FontStyle.Italic) FontStyle.Italic else FontStyle.Normal
internal fun K_FontFamily.toCompose(): FontFamily = when (name) {
  "sans" -> FontFamily.SansSerif
  "serif" -> FontFamily.Serif
  "mono" -> FontFamily.Monospace
  "cursive" -> FontFamily.Cursive
  else -> FontFamily.Default // host-registered families: Gate B
}

internal fun K_TextAlign.toCompose(): TextAlign = when (this) {
  K_TextAlign.Left -> TextAlign.Left
  K_TextAlign.Right -> TextAlign.Right
  K_TextAlign.Center -> TextAlign.Center
  K_TextAlign.Justify -> TextAlign.Justify
  K_TextAlign.Start -> TextAlign.Start
  K_TextAlign.End -> TextAlign.End
  else -> TextAlign.Unspecified
}

internal fun K_TextOverflow.toCompose(): TextOverflow = when (this) {
  K_TextOverflow.Ellipsis -> TextOverflow.Ellipsis
  K_TextOverflow.Visible -> TextOverflow.Visible
  K_TextOverflow.StartEllipsis -> TextOverflow.StartEllipsis
  K_TextOverflow.MiddleEllipsis -> TextOverflow.MiddleEllipsis
  else -> TextOverflow.Clip
}

internal fun K_TextDecoration.toCompose(): TextDecoration = TextDecoration.combine(
  buildList {
    if (K_TextDecoration.Underline in this@toCompose) add(TextDecoration.Underline)
    if (K_TextDecoration.LineThrough in this@toCompose) add(TextDecoration.LineThrough)
  },
)

internal fun K_Alignment.toCompose(): Alignment = BiasAlignment(horizontalBias, verticalBias)
internal fun K_Alignment.Horizontal.toCompose(): Alignment.Horizontal = BiasAlignment.Horizontal(bias)
internal fun K_Alignment.Vertical.toCompose(): Alignment.Vertical = BiasAlignment.Vertical(bias)

internal fun K_Arrangement.Horizontal.toCompose(): Arrangement.Horizontal = when (code) {
  "end" -> Arrangement.End
  "center" -> Arrangement.Center
  "se" -> Arrangement.SpaceEvenly
  "sb" -> Arrangement.SpaceBetween
  "sa" -> Arrangement.SpaceAround
  "sp" -> (this as K_Arrangement.SpacedAligned).let { s ->
    s.bias?.let { Arrangement.spacedBy(s.space.toCompose(), BiasAlignment.Horizontal(it)) } ?: Arrangement.spacedBy(s.space.toCompose())
  }
  else -> Arrangement.Start
}

internal fun K_Arrangement.Vertical.toCompose(): Arrangement.Vertical = when (code) {
  "bottom" -> Arrangement.Bottom
  "center" -> Arrangement.Center
  "se" -> Arrangement.SpaceEvenly
  "sb" -> Arrangement.SpaceBetween
  "sa" -> Arrangement.SpaceAround
  "sp" -> (this as K_Arrangement.SpacedAligned).let { s ->
    s.bias?.let { Arrangement.spacedBy(s.space.toCompose(), BiasAlignment.Vertical(it)) } ?: Arrangement.spacedBy(s.space.toCompose())
  }
  else -> Arrangement.Top
}

internal fun K_PaddingValues.toCompose(): PaddingValues = PaddingValues(start.toCompose(), top.toCompose(), end.toCompose(), bottom.toCompose())

internal fun K_ContentScale.toCompose(): ContentScale = when (name) {
  "Crop" -> ContentScale.Crop
  "FillHeight" -> ContentScale.FillHeight
  "FillWidth" -> ContentScale.FillWidth
  "Inside" -> ContentScale.Inside
  "None" -> ContentScale.None
  "FillBounds" -> ContentScale.FillBounds
  else -> ContentScale.Fit
}

internal fun K_FilterQuality.toCompose(): FilterQuality = when (this) {
  K_FilterQuality.None -> FilterQuality.None
  K_FilterQuality.Medium -> FilterQuality.Medium
  K_FilterQuality.High -> FilterQuality.High
  else -> FilterQuality.Low
}

internal fun K_Role.toCompose(): Role = when (this) {
  K_Role.Checkbox -> Role.Checkbox
  K_Role.Switch -> Role.Switch
  K_Role.RadioButton -> Role.RadioButton
  K_Role.Tab -> Role.Tab
  K_Role.Image -> Role.Image
  K_Role.DropdownList -> Role.DropdownList
  else -> Role.Button
}

/** An icon outside the host's icon groups renders a neutral placeholder, never a crash. */
internal fun K_ImageVector.toCompose(): ImageVector = iconByName(name) ?: Icons.Filled.Info

/** Host-owned state (§10): the real ScrollState lives where the scroll modifier renders. */
@Composable
internal fun K_ScrollState.toCompose(): ScrollState = rememberScrollState(initial)

/**
 * Host-owned state (§10): the real LazyListState lives where the list renders; each new scroll
 * command from the guest runs once on it.
 */
@Composable
internal fun K_LazyListStateWire.toCompose(): LazyListState {
  val state = rememberLazyListState(initialIndex, initialOffset)
  val command = command
  LaunchedEffect(command?.id) {
    if (command == null) return@LaunchedEffect
    if (command.animated) state.animateScrollToItem(command.index, command.offset) else state.scrollToItem(command.index, command.offset)
  }
  return state
}

/** A border: solid, or made by the real `*Defaults` factory the guest recorded a call to. */
@Composable
internal fun K_BorderStroke.toCompose(): BorderStroke =
  call?.let { defaultsBorderStroke(it) } ?: BorderStroke(width.toCompose(), color.toCompose())

/** An icon's vector painter or a colour painter (the painters a guest can describe, §6). */
@Composable
internal fun K_Painter.toCompose(): Painter = when (this) {
  is K_VectorPainter -> rememberVectorPainter(image.toCompose())
  is K_ColorPainter -> ColorPainter(color.toCompose())
  else -> ColorPainter(Color.Unspecified)
}

@Composable
internal fun K_ColorFilter.toCompose(): ColorFilter = ColorFilter.tint(color.toCompose(), blendMode.toCompose())

internal fun K_BlendMode.toCompose(): BlendMode = when (this) {
  K_BlendMode.Clear -> BlendMode.Clear
  K_BlendMode.Src -> BlendMode.Src
  K_BlendMode.Dst -> BlendMode.Dst
  K_BlendMode.SrcOver -> BlendMode.SrcOver
  K_BlendMode.DstOver -> BlendMode.DstOver
  K_BlendMode.DstIn -> BlendMode.DstIn
  K_BlendMode.SrcOut -> BlendMode.SrcOut
  K_BlendMode.DstOut -> BlendMode.DstOut
  K_BlendMode.SrcAtop -> BlendMode.SrcAtop
  K_BlendMode.DstAtop -> BlendMode.DstAtop
  K_BlendMode.Xor -> BlendMode.Xor
  K_BlendMode.Plus -> BlendMode.Plus
  K_BlendMode.Modulate -> BlendMode.Modulate
  K_BlendMode.Screen -> BlendMode.Screen
  K_BlendMode.Overlay -> BlendMode.Overlay
  K_BlendMode.Darken -> BlendMode.Darken
  K_BlendMode.Lighten -> BlendMode.Lighten
  K_BlendMode.ColorDodge -> BlendMode.ColorDodge
  K_BlendMode.ColorBurn -> BlendMode.ColorBurn
  K_BlendMode.Hardlight -> BlendMode.Hardlight
  K_BlendMode.Softlight -> BlendMode.Softlight
  K_BlendMode.Difference -> BlendMode.Difference
  K_BlendMode.Exclusion -> BlendMode.Exclusion
  K_BlendMode.Multiply -> BlendMode.Multiply
  K_BlendMode.Hue -> BlendMode.Hue
  K_BlendMode.Saturation -> BlendMode.Saturation
  K_BlendMode.Color -> BlendMode.Color
  K_BlendMode.Luminosity -> BlendMode.Luminosity
  else -> BlendMode.SrcIn
}

/** The guest passed an explicit `null` where Compose's default is not null (design §5). */
internal fun Any.isExplicitNull(): Boolean = when (this) {
  is DefaultsValue -> call.isExplicitNull
  is K_BorderStroke -> call?.isExplicitNull == true
  else -> false
}
