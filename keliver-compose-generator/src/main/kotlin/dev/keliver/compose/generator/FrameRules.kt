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
package dev.keliver.compose.generator

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * NF5 A1 (docs/SHOW_BEFORE_COMPUTE.md 2.4, retargeted to the generated groups by docs/MS4_PLAN.md
 * 2.2): a group's frame rules. When a group's config has them, the [FrameRuleEmitter] writes the
 * frame-rule annotations (`dev.keliver.schema`'s `@Replayable`, `@Scrolls`, `@NotData`,
 * `@StyleToken`, `@NeverCached`, `@BlocksFrameWhenTrue`) onto the group's generated schema, from each
 * parameter's category, with these [exceptions]. A group without them (the product's groups until
 * MS4-36) gets no annotation, so a host replays nothing of it (fail closed).
 */
class FrameRulesConfig(val exceptions: List<FrameRuleException>)

/** One exception to the category default, with the reason the config gives for it. */
class FrameRuleException(
  /** The declaration, as written in `declarations`; null for every declaration of the group. */
  val spec: String?,
  /** The parameter it classifies; null when it takes the whole declaration off [Replayable]. */
  val param: String?,
  /** `NotData`, `StyleToken`, `NeverCached` or `BlocksFrameWhenTrue`; null with [notReplayable]. */
  val rule: String?,
  /** The declaration is never replayed (a dialog, a text field, a skeleton). */
  val notReplayable: Boolean,
  val why: String,
)

private val RULES = setOf("NotData", "StyleToken", "NeverCached", "BlocksFrameWhenTrue")

fun loadFrameRules(o: JsonObject): FrameRulesConfig = FrameRulesConfig(
  o["exceptions"]?.jsonArray.orEmpty().map { e ->
    val x = e.jsonObject
    val spec = x["spec"]?.jsonPrimitive?.content
    val param = x["param"]?.jsonPrimitive?.content
    val rule = x["rule"]?.jsonPrimitive?.content
    val notReplayable = x["notReplayable"]?.jsonPrimitive?.boolean ?: false
    val why = x["why"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
    requireNotNull(why) { "frameRules: every exception says why: $x" }
    if (notReplayable) {
      require(spec != null && param == null && rule == null) { "frameRules: notReplayable names a spec only: $x" }
    } else {
      require(param != null && rule in RULES) { "frameRules: an exception names a param and a rule (one of $RULES): $x" }
    }
    FrameRuleException(spec, param, rule, notReplayable, why)
  },
)

/**
 * Writes the frame-rule annotations of a group's generated schema (its own emitter: `Emit.kt` only
 * calls it). Each generated widget and modifier is `@Replayable` unless the config takes it off, or
 * one of its value parameters has no category (a type the table below does not know): nothing is
 * replayable by default, so an unclassified type fails closed. A declaration in `scrollContainers`
 * is `@Scrolls`. Each value parameter gets its category's annotation:
 *
 * - text (`String`) and URLs (an image's `model`): none, so `Shell` replaces them;
 * - a mirror whose wire form names a style, an asset or a call (a shape, an arrangement, an icon, a
 *   `*Defaults` value, an animation spec): `@StyleToken`, so `Shell` keeps it;
 * - a mirror of a number-like layout or style value (a `Dp`, a `Color`, a `TextAlign`) and the host's
 *   scroll state: `@NotData`;
 * - a primitive (`Boolean`, `Int`, `Float`): none, unless an exception classifies it, so it is on the
 *   review list (a value `Shell` keeps as it is);
 * - wire bookkeeping (a case-2 `…IsDefault` flag, a modifier's event handle, a lazy list's window
 *   counts): `@NotData`.
 */
class FrameRuleEmitter(private val config: Config, private val generated: List<Plan.Generated>) {
  private val rules = config.frameRules
  val enabled: Boolean get() = rules != null

  private val used = sortedSetOf<String>()

  init {
    if (rules != null) {
      val specs = generated.map { it.decl.spec }.toSet()
      val params = generated.associate { it.decl.spec to it.params.map { c -> c.param.name }.toSet() }
      for (e in rules.exceptions) {
        val matches = when {
          e.spec != null && e.spec !in specs -> false
          e.param == null -> true
          e.spec != null -> e.param in params.getValue(e.spec)
          else -> params.values.any { e.param in it }
        }
        require(matches) { "${config.group}.json frameRules: this exception matches no generated parameter: ${e.spec ?: "*"}#${e.param ?: ""}" }
      }
    }
  }

  /** `@Replayable` and `@Scrolls` lines for a declaration's schema class. */
  fun memberAnnotations(p: Plan.Generated): String {
    val rules = rules ?: return ""
    return buildString {
      val off = rules.exceptions.any { it.notReplayable && it.spec == p.decl.spec }
      if (!off && p.params.none { it.kind == ParamKind.VALUE && category(p, it) == null }) append(use("Replayable")).append('\n')
      if (p.decl.spec in config.scrollContainers) append(use("Scrolls")).append('\n')
    }
  }

  /** The annotation of a value parameter's schema property, with a trailing space, or "". */
  fun valueAnnotation(p: Plan.Generated, c: Classified): String {
    if (rules == null) return ""
    return category(p, c)?.takeIf { it.isNotEmpty() }?.let { "${use(it)} " } ?: ""
  }

  /** The annotation of wire bookkeeping (`…IsDefault`, an event handle, a window count), or "". */
  fun bookkeepingAnnotation(): String = if (rules == null) "" else "${use("NotData")} "

  /** The annotation of a modifier's schema property [name] (a parameter, its `…IsDefault`, or an event handle). */
  fun modifierAnnotation(m: Plan.Generated, name: String): String {
    if (rules == null) return ""
    val c = m.params.firstOrNull { it.param.name == name }
    return when {
      c == null -> bookkeepingAnnotation() // `<param>IsDefault`
      c.kind == ParamKind.EVENT -> bookkeepingAnnotation() // the event's handle
      else -> valueAnnotation(m, c)
    }
  }

  /** The import lines for the annotations the schema's members use. */
  fun imports(): String {
    if (rules == null) return ""
    for (p in generated) {
      memberAnnotations(p)
      for (c in p.params) if (c.kind == ParamKind.VALUE) valueAnnotation(p, c)
      val bookkeeping = p.params.any {
        it.kind == ParamKind.LAZY ||
          (p.decl.kind != DeclKind.WIDGET && (it.kind == ParamKind.EVENT || it.defaultCase == DefaultCase.EXPRESSION))
      }
      if (bookkeeping) bookkeepingAnnotation()
    }
    return used.joinToString("") { "import dev.keliver.schema.$it\n" }
  }

  /**
   * The annotation a value parameter gets (`NotData`, `StyleToken`, …), "" for none (text, or a
   * value for review), or null when its type has no category.
   */
  private fun category(p: Plan.Generated, c: Classified): String? {
    val rules = rules!!
    val exception = rules.exceptions.firstOrNull { it.spec == p.decl.spec && it.param == c.param.name }
      ?: rules.exceptions.firstOrNull { it.spec == null && it.param == c.param.name }
    exception?.rule?.let { return it }
    val t = c.param.type
    return when {
      t.fq == "kotlin.String" || t.fq == TypeMap.IMAGE_MODEL -> ""
      t.fq in TypeMap.hostState || t.fq in NOT_DATA -> "NotData"
      t.fq in STYLE_TOKENS || t.fq in TypeMap.opaque || t.fq in TypeMap.generic -> "StyleToken"
      TypeMap.isPrimitive(t) -> ""
      else -> null
    }
  }

  private fun use(annotation: String): String {
    if (annotation.isEmpty()) return ""
    used += annotation
    return "@$annotation"
  }

  private companion object {
    /** Mirrors of number-like layout and style values: no String reaches the wire. */
    val NOT_DATA = setOf(
      "androidx.compose.ui.unit.Dp",
      "androidx.compose.ui.unit.TextUnit",
      "androidx.compose.ui.graphics.Color",
      "androidx.compose.ui.graphics.FilterQuality",
      "androidx.compose.ui.graphics.ColorFilter",
      "androidx.compose.ui.text.font.FontWeight",
      "androidx.compose.ui.text.font.FontStyle",
      "androidx.compose.ui.text.style.TextAlign",
      "androidx.compose.ui.text.style.TextOverflow",
      "androidx.compose.ui.text.style.TextDecoration",
      "androidx.compose.ui.semantics.Role",
    )

    /**
     * Mirrors whose strings are codes (a font family's or an icon's name, an arrangement's code, a
     * theme reference) or that the schema parser cannot see into (an abstract `Shape`, `Painter` or
     * `PaddingValues`; an `Alignment` interface; an animation's JSON parts): style, kept by `Shell`.
     */
    val STYLE_TOKENS = setOf(
      "androidx.compose.ui.graphics.Shape",
      "androidx.compose.ui.text.TextStyle",
      "androidx.compose.ui.text.font.FontFamily",
      "androidx.compose.ui.Alignment",
      "androidx.compose.ui.Alignment.Horizontal",
      "androidx.compose.ui.Alignment.Vertical",
      "androidx.compose.foundation.layout.Arrangement.Horizontal",
      "androidx.compose.foundation.layout.Arrangement.Vertical",
      "androidx.compose.foundation.layout.PaddingValues",
      "androidx.compose.ui.layout.ContentScale",
      "androidx.compose.ui.graphics.vector.ImageVector",
      "androidx.compose.ui.graphics.painter.Painter",
      "androidx.compose.foundation.BorderStroke",
      "androidx.compose.animation.EnterTransition",
      "androidx.compose.animation.ExitTransition",
    )
  }
}
