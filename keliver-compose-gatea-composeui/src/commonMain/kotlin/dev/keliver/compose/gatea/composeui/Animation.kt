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
@file:OptIn(dev.keliver.compose.wire.KeliverGeneratedApi::class)

package dev.keliver.compose.gatea.composeui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandIn
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import dev.keliver.Modifier as KeliverModifier
import dev.keliver.compose.animation.EnterTransition as K_EnterTransition
import dev.keliver.compose.animation.ExitTransition as K_ExitTransition
import dev.keliver.compose.animation.core.AnimationSpecWire
import dev.keliver.compose.animation.core.DecodedSpec
import dev.keliver.compose.animation.core.decode
import dev.keliver.compose.gatea.modifier.ExtraAnimated
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/*
 * Gate B-e (docs/COMPOSE_GENERATOR_DESIGN.md §10), hand-written once: the host's half of animated
 * references and the converters of the animation mirrors. An animated argument is wrapped in the
 * real `animate*AsState`, keyed by the guest's reference id, so the animation runs on the host's
 * frame clock exactly as native Compose runs it.
 */

/** A spec as it travelled, as the real spec for [T] (its visibility threshold decoded for [T]). */
internal inline fun <reified T> AnimationSpecWire.toCompose(): FiniteAnimationSpec<T> = composeSpec(this) { threshold<T>(it) }

@Suppress("UNCHECKED_CAST")
@PublishedApi
internal inline fun <reified T> threshold(json: JsonElement): T? = when (T::class) {
  Float::class -> json.jsonPrimitive.float as T
  Dp::class -> Dp(json.jsonPrimitive.float) as T
  IntSize::class -> json.jsonArray.let { IntSize(it[0].jsonPrimitive.int, it[1].jsonPrimitive.int) } as T
  IntOffset::class -> json.jsonArray.let { IntOffset(it[0].jsonPrimitive.int, it[1].jsonPrimitive.int) } as T
  else -> null
}

@PublishedApi
internal fun <T> composeSpec(wire: AnimationSpecWire, threshold: (JsonElement) -> T?): FiniteAnimationSpec<T> = when (val d = wire.decode()) {
  is DecodedSpec.Tween -> tween(d.durationMillis, d.delay, d.easing?.let { CubicBezierEasing(it[0], it[1], it[2], it[3]) } ?: LinearEasing)
  is DecodedSpec.Spring -> spring(d.dampingRatio, d.stiffness, d.threshold?.let(threshold))
  is DecodedSpec.Snap -> snap(d.delay)
}

/** The animated references at the end of a widget's own chain (they name the widget's parameters). */
internal fun KeliverModifier.trailingAnimated(): List<ExtraAnimated> {
  var trailing = emptyList<ExtraAnimated>()
  forEachUnscoped { e -> trailing = if (e is ExtraAnimated) trailing + e else emptyList() }
  return trailing
}

@Composable
internal fun Float.animated(refs: List<ExtraAnimated>, name: String): Float {
  val ref = refs.lastOrNull { it.param == name }?.ref ?: return this
  val target = this
  return key(ref.id) {
    val spec = remember(ref.spec) { ref.spec?.toCompose<Float>() }
    val threshold = ref.visibilityThreshold ?: 0.01f
    val label = ref.label ?: "FloatAnimation"
    if (spec == null) {
      animateFloatAsState(target, visibilityThreshold = threshold, label = label).value
    } else {
      animateFloatAsState(target, spec, threshold, label).value
    }
  }
}

@Composable
internal fun Dp.animated(refs: List<ExtraAnimated>, name: String): Dp {
  val ref = refs.lastOrNull { it.param == name }?.ref ?: return this
  val target = this
  return key(ref.id) {
    val spec = remember(ref.spec) { ref.spec?.toCompose<Dp>() }
    val label = ref.label ?: "DpAnimation"
    if (spec == null) animateDpAsState(target, label = label).value else animateDpAsState(target, spec, label).value
  }
}

@Composable
internal fun Color.animated(refs: List<ExtraAnimated>, name: String): Color {
  val ref = refs.lastOrNull { it.param == name }?.ref ?: return this
  val target = this
  return key(ref.id) {
    val spec = remember(ref.spec) { ref.spec?.toCompose<Color>() }
    val label = ref.label ?: "ColorAnimation"
    if (spec == null) animateColorAsState(target, label = label).value else animateColorAsState(target, spec, label).value
  }
}

// -------------------------------------------------------------------------------------------------
// Enter and exit transitions: the parts the guest combined, combined again with the real `+`.

private class Part(json: JsonElement) {
  val name: String
  val args: JsonObject

  init {
    val (n, a) = (json as JsonObject).entries.single()
    name = n
    args = a.jsonObject
  }

  val spec: AnimationSpecWire get() = AnimationSpecWire(args.getValue("spec"))
  fun float(key: String): Float = args.getValue(key).jsonPrimitive.float
  fun bool(key: String): Boolean = args.getValue(key).jsonPrimitive.boolean
  fun alignment(key: String): BiasAlignment = args.getValue(key).jsonArray.let { BiasAlignment(it[0].jsonPrimitive.float, it[1].jsonPrimitive.float) }
}

internal fun K_EnterTransition.toCompose(): EnterTransition = parts.fold(EnterTransition.None) { acc, json ->
  val p = Part(json)
  acc + when (p.name) {
    "fadeIn" -> fadeIn(p.spec.toCompose(), p.float("alpha"))
    "scaleIn" -> scaleIn(p.spec.toCompose(), p.float("scale"))
    "expandIn" -> expandIn(p.spec.toCompose(), p.alignment("from"), p.bool("clip"))
    "expandVertically" -> expandVertically(p.spec.toCompose(), BiasAlignment.Vertical(p.float("from")), p.bool("clip"))
    "expandHorizontally" -> expandHorizontally(p.spec.toCompose(), BiasAlignment.Horizontal(p.float("from")), p.bool("clip"))
    "slideInVertically" -> slideInVertically(p.spec.toCompose())
    else -> EnterTransition.None.also { GeneratedMismatches.report("enter transition ${p.name}") }
  }
}

internal fun K_ExitTransition.toCompose(): ExitTransition = parts.fold(ExitTransition.None) { acc, json ->
  val p = Part(json)
  acc + when (p.name) {
    "fadeOut" -> fadeOut(p.spec.toCompose(), p.float("alpha"))
    "scaleOut" -> scaleOut(p.spec.toCompose(), p.float("scale"))
    "shrinkOut" -> shrinkOut(p.spec.toCompose(), p.alignment("to"), p.bool("clip"))
    "shrinkVertically" -> shrinkVertically(p.spec.toCompose(), BiasAlignment.Vertical(p.float("to")), p.bool("clip"))
    "shrinkHorizontally" -> shrinkHorizontally(p.spec.toCompose(), BiasAlignment.Horizontal(p.float("to")), p.bool("clip"))
    "slideOutVertically" -> slideOutVertically(p.spec.toCompose())
    else -> ExitTransition.None.also { GeneratedMismatches.report("exit transition ${p.name}") }
  }
}
