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
package dev.keliver.compose.material3

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import dev.keliver.compose.ui.graphics.Color
import dev.keliver.compose.ui.graphics.ShapeReference
import dev.keliver.compose.ui.text.TextStyle

/**
 * The guest's `MaterialTheme`: every value is a reference the host resolves under its own theme
 * (docs/COMPOSE_GENERATOR_DESIGN.md §7). Providing a theme from the guest is Gate B.
 */
public object MaterialTheme {
  public val colorScheme: ColorScheme
    @Composable @ReadOnlyComposable
    get() = ColorScheme

  public val typography: Typography
    @Composable @ReadOnlyComposable
    get() = Typography

  public val shapes: Shapes
    @Composable @ReadOnlyComposable
    get() = Shapes
}

private fun c(name: String) = Color.reference("colorScheme.$name")

@Immutable
public object ColorScheme {
  public val primary: Color get() = c("primary")
  public val onPrimary: Color get() = c("onPrimary")
  public val primaryContainer: Color get() = c("primaryContainer")
  public val onPrimaryContainer: Color get() = c("onPrimaryContainer")
  public val inversePrimary: Color get() = c("inversePrimary")
  public val secondary: Color get() = c("secondary")
  public val onSecondary: Color get() = c("onSecondary")
  public val secondaryContainer: Color get() = c("secondaryContainer")
  public val onSecondaryContainer: Color get() = c("onSecondaryContainer")
  public val tertiary: Color get() = c("tertiary")
  public val onTertiary: Color get() = c("onTertiary")
  public val tertiaryContainer: Color get() = c("tertiaryContainer")
  public val onTertiaryContainer: Color get() = c("onTertiaryContainer")
  public val background: Color get() = c("background")
  public val onBackground: Color get() = c("onBackground")
  public val surface: Color get() = c("surface")
  public val onSurface: Color get() = c("onSurface")
  public val surfaceVariant: Color get() = c("surfaceVariant")
  public val onSurfaceVariant: Color get() = c("onSurfaceVariant")
  public val surfaceTint: Color get() = c("surfaceTint")
  public val inverseSurface: Color get() = c("inverseSurface")
  public val inverseOnSurface: Color get() = c("inverseOnSurface")
  public val error: Color get() = c("error")
  public val onError: Color get() = c("onError")
  public val errorContainer: Color get() = c("errorContainer")
  public val onErrorContainer: Color get() = c("onErrorContainer")
  public val outline: Color get() = c("outline")
  public val outlineVariant: Color get() = c("outlineVariant")
  public val scrim: Color get() = c("scrim")
  public val surfaceBright: Color get() = c("surfaceBright")
  public val surfaceContainer: Color get() = c("surfaceContainer")
  public val surfaceContainerHigh: Color get() = c("surfaceContainerHigh")
  public val surfaceContainerHighest: Color get() = c("surfaceContainerHighest")
  public val surfaceContainerLow: Color get() = c("surfaceContainerLow")
  public val surfaceContainerLowest: Color get() = c("surfaceContainerLowest")
  public val surfaceDim: Color get() = c("surfaceDim")
}

private fun t(name: String) = TextStyle.reference("typography.$name")

@Immutable
public object Typography {
  public val displayLarge: TextStyle get() = t("displayLarge")
  public val displayMedium: TextStyle get() = t("displayMedium")
  public val displaySmall: TextStyle get() = t("displaySmall")
  public val headlineLarge: TextStyle get() = t("headlineLarge")
  public val headlineMedium: TextStyle get() = t("headlineMedium")
  public val headlineSmall: TextStyle get() = t("headlineSmall")
  public val titleLarge: TextStyle get() = t("titleLarge")
  public val titleMedium: TextStyle get() = t("titleMedium")
  public val titleSmall: TextStyle get() = t("titleSmall")
  public val bodyLarge: TextStyle get() = t("bodyLarge")
  public val bodyMedium: TextStyle get() = t("bodyMedium")
  public val bodySmall: TextStyle get() = t("bodySmall")
  public val labelLarge: TextStyle get() = t("labelLarge")
  public val labelMedium: TextStyle get() = t("labelMedium")
  public val labelSmall: TextStyle get() = t("labelSmall")
}

@Immutable
public object Shapes {
  public val extraSmall: dev.keliver.compose.ui.graphics.Shape get() = ShapeReference.of("shapes.extraSmall")
  public val small: dev.keliver.compose.ui.graphics.Shape get() = ShapeReference.of("shapes.small")
  public val medium: dev.keliver.compose.ui.graphics.Shape get() = ShapeReference.of("shapes.medium")
  public val large: dev.keliver.compose.ui.graphics.Shape get() = ShapeReference.of("shapes.large")
  public val extraLarge: dev.keliver.compose.ui.graphics.Shape get() = ShapeReference.of("shapes.extraLarge")
}

/** `LocalTextStyle.current` is a reference to the host's text style where the widget renders. */
public object LocalTextStyle {
  public val current: TextStyle
    @Composable @ReadOnlyComposable
    get() = TextStyle.reference("LocalTextStyle")
}

/** `LocalContentColor.current` is a reference to the host's content colour where the widget renders. */
public object LocalContentColor {
  public val current: Color
    @Composable @ReadOnlyComposable
    get() = Color.reference("LocalContentColor")
}
