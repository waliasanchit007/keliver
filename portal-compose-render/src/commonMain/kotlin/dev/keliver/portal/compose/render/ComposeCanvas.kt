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
package dev.keliver.portal.compose.render

import androidx.compose.runtime.Composable
import dev.keliver.compose.ui.Modifier
import dev.keliver.portal.document.DocNode
import dev.keliver.portal.document.PropValue
import dev.keliver.portal.document.toAny
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/**
 * ED0 spike (docs/ED0_SPIKE.md): what the canvas needs from the editor for one document — preview
 * values for bindings, a sink for the actions a tap runs, and a place to report what it could not
 * draw.
 */
public class CanvasEnv(
  /** A binding's preview value (a component parameter, a screen field `x` of `b.x`), or null. */
  public val bind: (String) -> Any? = { null },
  /** An action the canvas ran: its name and argument. */
  public val action: (String, Any?) -> Unit = { _, _ -> },
  /** Something the canvas did not draw, and why. */
  public val problem: (String) -> Unit = {},
)

/**
 * Draws [root], a document read from Compose-shaped code, inside the guest composition the editor
 * runs: each Compose node (`<guest fq>#<tag>`) calls its generated guest stub, Repeats and
 * Conditions are evaluated against [env]'s preview values. Called where a guest composable is
 * (the browser guest of the editor, or a test's guest composition).
 */
@Composable
public fun ComposeCanvas(root: DocNode, env: CanvasEnv) {
  CanvasNode(root, env, emptyMap(), null)
}

@Composable
private fun CanvasNode(node: DocNode, env: CanvasEnv, items: Map<String, Any?>, scope: Any?) {
  when (node) {
    is DocNode.RawCode -> env.problem("RawCode is not drawn: ${node.text.lineSequence().first()}")
    is DocNode.Widget -> when (node.type) {
      "Repeat" -> {
        val field = (node.props["items"] as? PropValue.Lit)?.s.orEmpty()
        val item = (node.props["item"] as? PropValue.Lit)?.s ?: "it"
        val list = (if (field in items) items[field] else env.bind(field)) as? List<*> ?: emptyList<Any?>()
        for (x in list) {
          for (c in node.children) CanvasNode(c, env, items + (item to x), scope)
        }
      }
      "Condition" -> {
        val holds = condition(node, env, items)
        val elseNode = node.children.lastOrNull()?.takeIf { it is DocNode.Widget && it.type == "Else" } as? DocNode.Widget
        val chosen = if (holds) node.children.filter { it !== elseNode } else elseNode?.children.orEmpty()
        for (c in chosen) CanvasNode(c, env, items, scope)
      }
      else -> {
        val modifier = modifierOf(node.modifiers, env, items, scope)
        val args = CanvasArgs(node.props, env, items, node.type)
        if (!renderGenerated(node.type, args, modifier, CanvasSlots(node, env, items))) {
          env.problem("${node.type} is not a widget of the generated catalog")
        }
      }
    }
  }
}

/** A single-term condition (`field`, `test`, `negate`), as a Condition node's props hold it. */
private fun condition(node: DocNode.Widget, env: CanvasEnv, items: Map<String, Any?>): Boolean {
  val field = (node.props["field"] as? PropValue.Lit)?.s ?: return false
  val v = if (field in items) items[field] else env.bind(field)
  val holds = when ((node.props["test"] as? PropValue.Lit)?.s) {
    null -> v == true
    "empty" -> (v as? String).isNullOrEmpty()
    "blank" -> (v as? String).isNullOrBlank()
    else -> false.also { env.problem("the ED0 canvas reads only single-term Boolean and emptiness conditions") }
  }
  return holds != ((node.props["negate"] as? PropValue.Lit)?.b == true)
}

/** A Compose node's modifier map, element by element in chain order, applied through the stubs. */
@Composable
private fun modifierOf(mods: Map<String, PropValue>, env: CanvasEnv, items: Map<String, Any?>, scope: Any?): Modifier {
  var m: Modifier = Modifier
  val heads = mods.keys.filter { '.' !in it && ELEMENT.matches(it) }.sortedBy { it.substringBefore(':').toInt() }
  for (h in heads) {
    val args = mods.filterKeys { it.startsWith("$h.") }.mapKeys { it.key.substringAfter("$h.") }
    m = applyGenerated(m, h.substringAfter(':'), CanvasArgs(args, env, items, h), scope)
      ?: m.also { env.problem("the modifier $h is not drawn (not generated, an argument missing, or outside its scope)") }
  }
  if (mods.keys.any { !ELEMENT.matches(it.substringBefore('.')) }) env.problem("a legacy modifier on a Compose node is not drawn")
  return m
}

private val ELEMENT = Regex("""^\d+:[A-Za-z_]\w*#\d+$""")

/** A node's (or one modifier element's) arguments, decoded as the generated stub call asks for them. */
public class CanvasArgs internal constructor(
  private val values: Map<String, PropValue>,
  private val env: CanvasEnv,
  private val items: Map<String, Any?>,
  private val owner: String,
) {
  public fun has(name: String): Boolean = name in values

  public fun string(name: String): String? = resolve(values[name])?.let { it as? String ?: it.toString() }

  public fun int(name: String): Int? = (resolve(values[name]) as? Number)?.toInt()

  public fun bool(name: String): Boolean? = resolve(values[name]) as? Boolean

  public fun float(name: String): Float? = (resolve(values[name]) as? Number)?.toFloat()

  /** A Compose value, decoded from its wire JSON with its own codec (null: absent or `null`). */
  public fun <T> value(name: String, codec: KSerializer<T>): T? {
    val lit = values[name] as? PropValue.Lit ?: return null.also { if (name in values) env.problem("$owner: `$name` is bound; the ED0 canvas draws a bound Compose value at its default") }
    if (lit.tag != "c") return null
    val json = lit.s ?: return null
    return if (json == "null") null else Json.decodeFromString(codec, json)
  }

  public fun action0(name: String): () -> Unit = { fire(name, null) }

  public fun action1(name: String): (Any?) -> Unit = { fire(name, it) }

  /** A required argument the node lacks: reported, and the node is not drawn. */
  public fun missing(name: String): Boolean {
    env.problem("$owner: `$name` is missing")
    return true
  }

  private fun fire(name: String, payload: Any?) {
    val a = values[name] as? PropValue.Action ?: return
    env.action(a.name, if (a.arg == "it") payload else a.arg)
  }

  private fun resolve(v: PropValue?): Any? = when (v) {
    null -> null
    is PropValue.Lit -> if (v.tag == "c") v.s?.takeIf { it != "null" } else v.toAny()
    is PropValue.Bind -> lookup(v.field)
    is PropValue.Template -> v.parts.joinToString("") { p -> ((p as? PropValue.Lit)?.toAny() ?: resolve(p))?.toString().orEmpty() }
    is PropValue.Token -> v.value.toAny()
    else -> null.also { env.problem("$owner: a ${v::class.simpleName} value is drawn at its default by the ED0 canvas") }
  }

  private fun lookup(field: String): Any? = when {
    field in items -> items[field]
    else -> env.bind(field)
  }
}

/** A node's slots: its own children (one required content slot) or its `slot:<name>` nodes' children. */
public class CanvasSlots internal constructor(
  private val node: DocNode.Widget,
  private val env: CanvasEnv,
  private val items: Map<String, Any?>,
) {
  private val slotted = node.children.any { it is DocNode.Widget && it.type.startsWith("slot:") }

  /** Whether the node passes the slot [name] (an optional slot it leaves out is absent, not empty). */
  public fun has(name: String): Boolean = if (slotted) node.children.any { it is DocNode.Widget && it.type == "slot:$name" } else true

  /** The slot [name]'s content, its scoped modifiers resolved against [scope] (the slot lambda's receiver). */
  @Composable
  public fun render(name: String, scope: Any?) {
    val children = if (slotted) {
      (node.children.firstOrNull { it is DocNode.Widget && it.type == "slot:$name" } as? DocNode.Widget)?.children.orEmpty()
    } else {
      node.children
    }
    for (c in children) CanvasNode(c, env, items, scope)
  }
}
