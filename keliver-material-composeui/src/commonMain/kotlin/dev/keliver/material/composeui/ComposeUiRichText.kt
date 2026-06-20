/*
 * Copyright (C) 2026 Keliver contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.keliver.material.composeui

import androidx.compose.material3.Text as M3Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import dev.keliver.Modifier as RedwoodModifier
import dev.keliver.material.api.TextSpan
import dev.keliver.material.widget.RichText

internal class ComposeUiRichText : RichText<@Composable (Modifier) -> Unit> {
  private var spans by mutableStateOf<List<TextSpan>>(emptyList())
  private var fontSize by mutableStateOf(14)
  private var bold by mutableStateOf(false)
  private var align by mutableStateOf(0)
  private var lineHeightSp by mutableStateOf(0)
  private var maxLines by mutableStateOf(0)
  private var colorArgb by mutableStateOf(0)
  override var modifier: RedwoodModifier = RedwoodModifier

  override val value: @Composable (Modifier) -> Unit = { m ->
    val annotated = buildAnnotatedString {
      for (span in spans) {
        val size = (if (span.fontSize > 0) span.fontSize else fontSize).sp
        val weight = if (span.bold || bold) FontWeight.Bold else FontWeight.Normal
        val style = if (span.gradientColorsArgb.size >= 2) {
          SpanStyle(
            brush = buildGradient(span.gradientColorsArgb, emptyList(), 2),
            fontSize = size,
            fontWeight = weight,
            fontStyle = if (span.italic) FontStyle.Italic else FontStyle.Normal,
            textDecoration = if (span.underline) TextDecoration.Underline else null,
          )
        } else {
          SpanStyle(
            color = when {
              span.colorArgb != 0 -> Color(span.colorArgb)
              colorArgb != 0 -> Color(colorArgb)
              else -> Color.Unspecified
            },
            fontSize = size,
            fontWeight = weight,
            fontStyle = if (span.italic) FontStyle.Italic else FontStyle.Normal,
            textDecoration = if (span.underline) TextDecoration.Underline else null,
          )
        }
        withStyle(style) { append(span.text) }
      }
    }
    M3Text(
      text = annotated,
      fontSize = fontSize.sp,
      textAlign = when (align) { 1 -> TextAlign.Center; 2 -> TextAlign.End; 3 -> TextAlign.Justify; else -> TextAlign.Start },
      lineHeight = if (lineHeightSp > 0) lineHeightSp.sp else TextUnit.Unspecified,
      maxLines = if (maxLines > 0) maxLines else Int.MAX_VALUE,
      overflow = if (maxLines > 0) TextOverflow.Ellipsis else TextOverflow.Clip,
      modifier = m,
    )
  }

  override fun spans(spans: List<TextSpan>) { this.spans = spans }
  override fun fontSize(fontSize: Int) { this.fontSize = fontSize }
  override fun bold(bold: Boolean) { this.bold = bold }
  override fun align(align: Int) { this.align = align }
  override fun lineHeightSp(lineHeightSp: Int) { this.lineHeightSp = lineHeightSp }
  override fun maxLines(maxLines: Int) { this.maxLines = maxLines }
  override fun colorArgb(colorArgb: Int) { this.colorArgb = colorArgb }
}
