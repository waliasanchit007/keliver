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
package dev.keliver.tooling.codegen

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.MAP
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.SET
import com.squareup.kotlinpoet.STRING
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.joinToCode
import dev.keliver.tooling.schema.FramePropertyRule
import dev.keliver.tooling.schema.FramePropertyRule.NeverCached
import dev.keliver.tooling.schema.FramePropertyRule.StyleToken
import dev.keliver.tooling.schema.ProtocolModifier
import dev.keliver.tooling.schema.ProtocolSchemaSet
import dev.keliver.tooling.schema.ProtocolWidget
import dev.keliver.tooling.schema.ProtocolWidget.ProtocolProperty

// NF5 (A)'s frame rules (docs/SHOW_BEFORE_COMPUTE.md 2.4): the lists the host's fold and recorder read,
// generated from the schema's frame-rule annotations (dev.keliver.schema's frameRules.kt), and the
// review list of the values a `Shell` frame keeps.

/*
public object ExampleFrameRules {
  public val replayableWidgets: Set<Int> = setOf(1, 3)
  public val replayableModifiers: Set<Int> = setOf(2)
  public val scrollingWidgets: Set<Int> = setOf(3)
  public val scrollingModifiers: Set<Int> = emptySet()
  public val blockingWidgetProperties: Map<Int, Set<Int>> = mapOf(3 to setOf(4))
  public val blockingModifierProperties: Map<Int, Set<String>> = emptyMap()
  public val textWidgetProperties: Map<Int, Set<Int>> = mapOf(1 to setOf(1, 2))
  public val textModifierProperties: Map<Int, Set<String>> = mapOf(2 to setOf("label"))
  public val neverCachedWidgetProperties: Map<Int, Set<Int>> = emptyMap()
  public val neverCachedModifierProperties: Map<Int, Set<String>> = emptyMap()
}
*/
/**
 * Whether any schema of the set uses a frame-rule annotation. A set that uses none gets no
 * `<Schema>FrameRules` (its generated host code is as before): the host then replays nothing of it,
 * as for a schema whose rules list nothing.
 */
internal fun ProtocolSchemaSet.declaresFrameRules(): Boolean = all.any { schema ->
  schema.widgets.any { w ->
    w.replayable || w.scrolls || w.traits.any { it is ProtocolProperty && it.frameRule != null }
  } ||
    schema.modifiers.any { m -> m.replayable || m.scrolls || m.properties.any { it.frameRule != null } }
}

internal fun generateFrameRules(schemaSet: ProtocolSchemaSet): FileSpec {
  val schema = schemaSet.schema
  val type = ClassName(schema.hostProtocolPackage(), "${schema.type.flatName}FrameRules")
  val rules = FrameRules(schemaSet)
  val tags = SET.parameterizedBy(INT)
  val widgetProperties = MAP.parameterizedBy(INT, SET.parameterizedBy(INT))
  val modifierProperties = MAP.parameterizedBy(INT, SET.parameterizedBy(STRING))
  return buildFileSpec(type) {
    addType(
      TypeSpec.objectBuilder(type)
        .addKdoc(
          "The frame rules of schema `%L` and its dependencies: what a stored first frame may hold " +
            "(NF5, docs/SHOW_BEFORE_COMPUTE.md 2.4). Tags are as on the wire, with each dependency's " +
            "offset. A modifier's properties are named as in its serialized value. Generated from the " +
            "schema's frame-rule annotations; a widget or modifier that is not listed as replayable " +
            "blocks a recording.",
          schema.type,
        )
        .addProperty(property("replayableWidgets", tags, intSet(rules.replayableWidgets), "Widgets that may be replayed (`@Replayable`)."))
        .addProperty(property("replayableModifiers", tags, intSet(rules.replayableModifiers), "Modifiers that may be replayed (`@Replayable`)."))
        .addProperty(property("scrollingWidgets", tags, intSet(rules.scrollingWidgets), "Widgets that scroll (`@Scrolls`): a frame is recorded only at their origin."))
        .addProperty(property("scrollingModifiers", tags, intSet(rules.scrollingModifiers), "Modifiers that make their node scroll (`@Scrolls`)."))
        .addProperty(property("blockingWidgetProperties", widgetProperties, intMap(rules.blockingWidgetProperties), "Per widget, the `@BlocksFrameWhenTrue` properties: nothing is recorded while one is true."))
        .addProperty(property("blockingModifierProperties", modifierProperties, nameMap(rules.blockingModifierProperties), "Per modifier, its `@BlocksFrameWhenTrue` properties."))
        .addProperty(property("textWidgetProperties", widgetProperties, intMap(rules.textWidgetProperties), "Per replayable widget, its text- and URL-bearing properties (a type that is or contains a String, `@StyleToken` and `@NeverCached` aside): `Shell` replaces their strings."))
        .addProperty(property("textModifierProperties", modifierProperties, nameMap(rules.textModifierProperties), "Per replayable modifier, its text- and URL-bearing properties."))
        .addProperty(property("neverCachedWidgetProperties", widgetProperties, intMap(rules.neverCachedWidgetProperties), "Per replayable widget, its `@NeverCached` properties: never stored."))
        .addProperty(property("neverCachedModifierProperties", modifierProperties, nameMap(rules.neverCachedModifierProperties), "Per replayable modifier, its `@NeverCached` properties."))
        .build(),
    )
  }
}

private fun property(name: String, type: TypeName, initializer: CodeBlock, kdoc: String) =
  PropertySpec.builder(name, type).initializer(initializer).addKdoc("%L", kdoc).build()

private fun intSet(values: Collection<Int>): CodeBlock =
  if (values.isEmpty()) CodeBlock.of("emptySet()") else CodeBlock.of("setOf(%L)", values.joinToString())

private fun nameSet(values: Collection<String>): CodeBlock =
  CodeBlock.builder().add("setOf(").apply {
    values.forEachIndexed { i, value -> add(if (i == 0) "%S" else ", %S", value) }
  }.add(")").build()

private fun intMap(map: Map<Int, List<Int>>): CodeBlock =
  if (map.isEmpty()) CodeBlock.of("emptyMap()") else map.entries.map { (tag, values) -> CodeBlock.of("%L to %L", tag, intSet(values)) }.joinToCode(prefix = "mapOf(", suffix = ")")

private fun nameMap(map: Map<Int, List<String>>): CodeBlock =
  if (map.isEmpty()) CodeBlock.of("emptyMap()") else map.entries.map { (tag, values) -> CodeBlock.of("%L to %L", tag, nameSet(values)) }.joinToCode(prefix = "mapOf(", suffix = ")")

/** The lists of `<Schema>FrameRules`, for a schema and its dependencies, in tag order. */
internal class FrameRules(schemaSet: ProtocolSchemaSet) {
  private val widgets = schemaSet.all.flatMap { it.widgets }.sortedBy { it.tag }
  private val modifiers = schemaSet.all.flatMap { it.modifiers }.sortedBy { it.tag }
  private val replayable = widgets.filter { it.replayable }
  private val replayableMods = modifiers.filter { it.replayable }

  val replayableWidgets: List<Int> = replayable.map { it.tag }
  val replayableModifiers: List<Int> = replayableMods.map { it.tag }
  val scrollingWidgets: List<Int> = widgets.filter { it.scrolls }.map { it.tag }
  val scrollingModifiers: List<Int> = modifiers.filter { it.scrolls }.map { it.tag }

  val blockingWidgetProperties: Map<Int, List<Int>> = widgets.associateNonEmpty { w ->
    w.traits.filterIsInstance<ProtocolProperty>().filter { it.frameRule == FramePropertyRule.BlocksFrameWhenTrue }.map { it.tag }
  }
  val blockingModifierProperties: Map<Int, List<String>> = modifiers.associateNonEmpty { m ->
    m.properties.filter { it.frameRule == FramePropertyRule.BlocksFrameWhenTrue }.map { it.name }
  }
  val textWidgetProperties: Map<Int, List<Int>> = replayable.associateNonEmpty { w ->
    w.traits.filterIsInstance<ProtocolProperty>().filter { it.containsString && it.frameRule !in KEPT_OR_DROPPED }.map { it.tag }
  }
  val textModifierProperties: Map<Int, List<String>> = replayableMods.associateNonEmpty { m ->
    m.properties.filter { it.containsString && it.frameRule !in KEPT_OR_DROPPED }.map { it.name }
  }
  val neverCachedWidgetProperties: Map<Int, List<Int>> = replayable.associateNonEmpty { w ->
    w.traits.filterIsInstance<ProtocolProperty>().filter { it.frameRule == NeverCached }.map { it.tag }
  }
  val neverCachedModifierProperties: Map<Int, List<String>> = replayableMods.associateNonEmpty { m ->
    m.properties.filter { it.frameRule == NeverCached }.map { it.name }
  }

  private companion object {
    /** A style token is kept as it is; a never-cached value is dropped: neither is replaced. */
    val KEPT_OR_DROPPED = setOf(StyleToken, NeverCached)
  }
}

@JvmName("associateWidgets")
private inline fun <V> List<ProtocolWidget>.associateNonEmpty(values: (ProtocolWidget) -> List<V>): Map<Int, List<V>> =
  mapNotNull { w -> values(w).takeIf { it.isNotEmpty() }?.let { w.tag to it } }.toMap()

@JvmName("associateModifiers")
private inline fun <V> List<ProtocolModifier>.associateNonEmpty(values: (ProtocolModifier) -> List<V>): Map<Int, List<V>> =
  mapNotNull { m -> values(m).takeIf { it.isNotEmpty() }?.let { m.tag to it } }.toMap()

/**
 * A member's name in the review list, without what a lock assigns: a generated name `GenSurface2`
 * (`Gen`, the Compose name, the lock's overload number) reads `Surface`. A hand-written schema's
 * names are kept as they are.
 */
internal fun reviewName(flatName: String): String =
  if (Regex("Gen[A-Z].*\\d+").matches(flatName)) flatName.removePrefix("Gen").trimEnd { it.isDigit() } else flatName

/**
 * The review list of a schema set, one section per schema (the schema itself, then each dependency),
 * as lines. Keyed by member and property NAME only: no wire tag and no lock-assigned number, so a
 * tag renumbering or a lock reset changes nothing here. It holds
 * - a line per `@Replayable` widget and modifier (`widget Surface`, with ` x2` when overloads share
 *   the name: a new overload changes the count), so adding a member is a reviewed choice; and
 * - a line per non-String value of one that is neither `@NotData` nor `@NeverCached` (nor
 *   `@BlocksFrameWhenTrue`, whose stored value is always false): a `Shell` frame keeps it as it is.
 *   Overloads' equal lines are one.
 * Each line starts with its schema's name.
 */
internal fun frameRulesReview(schemaSet: ProtocolSchemaSet): List<String> = buildList {
  for (schema in schemaSet.all) {
    val section = schema.type.flatName
    fun members(kind: String, byName: Map<String, List<List<Pair<String, String>>>>) {
      for ((name, overloads) in byName.toSortedMap()) {
        add("$section: $kind $name${if (overloads.size > 1) " x${overloads.size}" else ""}")
        overloads.flatten().toSortedSet(compareBy({ it.first }, { it.second })).forEach { (property, type) ->
          add("$section: $kind $name.$property: $type")
        }
      }
    }
    members(
      "widget",
      schema.widgets.filter { it.replayable }.groupBy({ reviewName(it.type.flatName) }) { w ->
        w.traits.filterIsInstance<ProtocolProperty>().filter { !it.containsString && it.frameRule == null }
          .map { it.name to it.type.toString() }
      },
    )
    members(
      "modifier",
      schema.modifiers.filter { it.replayable }.groupBy({ reviewName(it.type.flatName) }) { m ->
        m.properties.filter { !it.containsString && it.frameRule == null }.map { it.name to it.type.toString() }
      },
    )
  }
}

/** The review file's text for [schemaSet]: a header, then one section per schema. */
internal fun frameRulesReviewText(schemaSet: ProtocolSchemaSet): String = buildString {
  val schema = schemaSet.schema
  appendLine("# ${schema.type.flatName}FrameRules review list (${schema.type}), with its dependencies.")
  appendLine("# Generated by keliver-tooling-codegen; docs/SHOW_BEFORE_COMPUTE.md 2.4. A line per @Replayable")
  appendLine("# widget and modifier, and a line per non-String value of one that is neither @NotData,")
  appendLine("# @NeverCached nor @BlocksFrameWhenTrue: a stored Shell frame keeps it as it is. Keyed by name,")
  appendLine("# not by tag. This file is the reviewed list: with strict frame rules a change fails the build")
  appendLine("# until the member or value is classified in the schema or accepted here.")
  var section: String? = null
  for (line in frameRulesReview(schemaSet)) {
    val name = line.substringBefore(':')
    if (name != section) {
      section = name
      appendLine("# $name")
    }
    appendLine(line)
  }
}

/** The entries of a review file's [text] (its comment lines and blank lines aside). */
internal fun parseFrameRulesReview(text: String): List<String> =
  text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toList()
