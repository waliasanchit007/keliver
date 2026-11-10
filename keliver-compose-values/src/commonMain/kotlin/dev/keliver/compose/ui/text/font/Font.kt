/*
 * Copyright (C) 2026 Keliver contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.keliver.compose.ui.text.font

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import dev.keliver.compose.wire.JsonCodec
import kotlin.jvm.JvmInline
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

/** The guest mirror of `androidx.compose.ui.text.font.FontWeight`. Wire: the weight, 1–1000. */
@Immutable
@Serializable(with = FontWeightCodec::class)
public class FontWeight(public val weight: Int) : Comparable<FontWeight> {
  init {
    require(weight in 1..1000) { "Font weight can be in range [1, 1000]. Current value: $weight" }
  }
  override fun compareTo(other: FontWeight): Int = weight.compareTo(other.weight)
  override fun equals(other: Any?): Boolean = other is FontWeight && other.weight == weight
  override fun hashCode(): Int = weight
  override fun toString(): String = "FontWeight(weight=$weight)"

  public companion object {
    @Stable public val W100: FontWeight = FontWeight(100)
    @Stable public val W200: FontWeight = FontWeight(200)
    @Stable public val W300: FontWeight = FontWeight(300)
    @Stable public val W400: FontWeight = FontWeight(400)
    @Stable public val W500: FontWeight = FontWeight(500)
    @Stable public val W600: FontWeight = FontWeight(600)
    @Stable public val W700: FontWeight = FontWeight(700)
    @Stable public val W800: FontWeight = FontWeight(800)
    @Stable public val W900: FontWeight = FontWeight(900)
    @Stable public val Thin: FontWeight = W100
    @Stable public val ExtraLight: FontWeight = W200
    @Stable public val Light: FontWeight = W300
    @Stable public val Normal: FontWeight = W400
    @Stable public val Medium: FontWeight = W500
    @Stable public val SemiBold: FontWeight = W600
    @Stable public val Bold: FontWeight = W700
    @Stable public val ExtraBold: FontWeight = W800
    @Stable public val Black: FontWeight = W900
  }
}

public object FontWeightCodec : JsonCodec<FontWeight>("dev.keliver.compose.FontWeight") {
  override fun toJson(value: FontWeight): JsonElement = JsonPrimitive(value.weight)
  override fun fromJson(json: JsonElement): FontWeight = FontWeight(json.jsonPrimitive.int)
}

/** The guest mirror of `androidx.compose.ui.text.font.FontStyle`. Wire: 0 normal, 1 italic. */
@Immutable
@JvmInline
@Serializable(with = FontStyleCodec::class)
public value class FontStyle(public val value: Int) {
  override fun toString(): String = if (value == 1) "Italic" else "Normal"
  public companion object {
    @Stable public val Normal: FontStyle = FontStyle(0)
    @Stable public val Italic: FontStyle = FontStyle(1)
  }
}

public object FontStyleCodec : JsonCodec<FontStyle>("dev.keliver.compose.FontStyle") {
  override fun toJson(value: FontStyle): JsonElement = JsonPrimitive(value.value)
  override fun fromJson(json: JsonElement): FontStyle = FontStyle(json.jsonPrimitive.int)
}

/**
 * The guest mirror of `androidx.compose.ui.text.font.FontFamily`: the generic families, or a
 * family the host registers by name. A resource font is not possible in a guest (§11).
 * Wire: `"default"`, `"sans"`, `"serif"`, `"mono"`, `"cursive"`, or a registered name.
 */
@Immutable
@Serializable(with = FontFamilyCodec::class)
public class FontFamily private constructor(public val name: String) {
  override fun equals(other: Any?): Boolean = other is FontFamily && other.name == name
  override fun hashCode(): Int = name.hashCode()
  override fun toString(): String = "FontFamily($name)"

  public companion object {
    @Stable public val Default: FontFamily = FontFamily("default")
    @Stable public val SansSerif: FontFamily = FontFamily("sans")
    @Stable public val Serif: FontFamily = FontFamily("serif")
    @Stable public val Monospace: FontFamily = FontFamily("mono")
    @Stable public val Cursive: FontFamily = FontFamily("cursive")
    /** A family the host registered under [name] (a Keliver extra, not Compose API). */
    public fun named(name: String): FontFamily = FontFamily(name)
  }
}

public object FontFamilyCodec : JsonCodec<FontFamily>("dev.keliver.compose.FontFamily") {
  override fun toJson(value: FontFamily): JsonElement = JsonPrimitive(value.name)
  override fun fromJson(json: JsonElement): FontFamily = FontFamily.named(json.jsonPrimitive.content)
}
