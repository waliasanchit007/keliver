/*
 * Copyright (C) 2026 Keliver contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.keliver.material.api

import kotlin.native.ObjCName
import kotlinx.serialization.Serializable

/**
 * One styled run of text inside a `RichText` widget — the keliver equivalent of a
 * Compose `AnnotatedString` span. Lets a single line mix weights, colors, sizes
 * and even gradient fills (e.g. "Make your UPI ID **sound like you**" where the
 * bold part is a gradient).
 */
@Serializable
@ObjCName("TextSpan", exact = true)
public data class TextSpan(
  val text: String,
  val bold: Boolean = false,
  val italic: Boolean = false,
  val underline: Boolean = false,
  /** ARGB; 0 => inherit the RichText color. Ignored when [gradientColorsArgb] set. */
  val colorArgb: Int = 0,
  /** 2+ ARGB stops => fill this span with a horizontal linear gradient. */
  val gradientColorsArgb: List<Int> = emptyList(),
  /** Point size; 0 => inherit the RichText font size. */
  val fontSize: Int = 0,
)
