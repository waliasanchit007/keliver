/*
 * Copyright (C) 2026 Keliver contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.keliver.realguest

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/** ui-text: TextStyle, SpanStyle, AnnotatedString, FontWeight and the small style types. */
fun textCases(): List<Case> = listOf(
  Case("text.fontWeight") {
    "bold=${FontWeight.Bold} eq700=${FontWeight.Bold == FontWeight(700)} same=${FontWeight.Bold === FontWeight(700)} " +
      "weight=${FontWeight.Bold.weight} cmp=${FontWeight.Medium < FontWeight.Bold} style=${FontStyle.Italic} " +
      "family=${FontFamily.SansSerif} mono=${FontFamily.Monospace}"
  },
  Case("text.textStyle", bench = { n ->
    var s = TextStyle.Default
    val bold = TextStyle(fontWeight = FontWeight.Bold, fontSize = 16.sp)
    repeat(n) { s = TextStyle(color = Color.Red, fontSize = 14.sp).merge(bold).copy(letterSpacing = 0.1.sp) }
    s
  }) {
    val title = TextStyle(
      color = Color(0xFF202124),
      fontSize = 22.sp,
      fontWeight = FontWeight.SemiBold,
      lineHeight = 28.sp,
      letterSpacing = 0.sp,
      textAlign = TextAlign.Center,
      textDecoration = TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough)),
      lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
    )
    val merged = title.merge(TextStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic))
    val withBrush = TextStyle(brush = Brush.linearGradient(listOf(Color.Red, Color.Blue), Offset.Zero, Offset(1f, 1f)), alpha = 0.5f)
    "fields: color=${merged.color} size=${merged.fontSize} weight=${merged.fontWeight} style=${merged.fontStyle} " +
      "line=${merged.lineHeight} align=${merged.textAlign} deco=${merged.textDecoration} lhs=${merged.lineHeightStyle} " +
      "eq=${TextStyle(fontWeight = FontWeight(700)) == TextStyle(fontWeight = FontWeight.Bold)} " +
      "brush=${withBrush.brush} alpha=${withBrush.alpha} spanOf=${merged.toSpanStyle().fontWeight} " +
      "paragraphOf=${merged.toParagraphStyle().textAlign} overflow=${TextOverflow.Ellipsis} toString=${title.toString().length}"
  },
  Case("text.annotatedString", bench = { n ->
    var a = AnnotatedString("")
    repeat(n) {
      a = buildAnnotatedString {
        append("Total ")
        withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Color.Red)) { append("₹1,200") }
      }
    }
    a
  }) {
    val a = buildAnnotatedString {
      append("Hello ")
      withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 1.2.em)) { append("world") }
      pushStringAnnotation(tag = "url", annotation = "https://example.com")
      append(" link")
      pop()
    }
    "text=${a.text} spans=${a.spanStyles.map { "${it.start}-${it.end}:${it.item.fontWeight}" }} " +
      "annotations=${a.getStringAnnotations("url", 0, a.length).map { it.item }} sub=${a.subSequence(6, 11).spanStyles.size}"
  },
)
