/*
 * Copyright (C) 2026 Keliver contributors.
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
