package dev.keliver.portal.render

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import dev.keliver.portal.Action
import dev.keliver.portal.Arith
import dev.keliver.portal.Bind
import dev.keliver.portal.IntDp
import dev.keliver.portal.arithValue
import dev.keliver.portal.Cond
import dev.keliver.portal.ListOf
import dev.keliver.portal.Template
import dev.keliver.portal.templateText
import dev.keliver.portal.templateValueText
import dev.keliver.portal.WidgetNode
import dev.keliver.portal.chooseBranch
import dev.keliver.portal.intList
import dev.keliver.portal.strList
import dev.keliver.portal.condOf
import dev.keliver.portal.elseNode
import dev.keliver.portal.thenChildren
import dev.keliver.portal.resolveItemRow
import dev.keliver.portal.mockedIntElement
import dev.keliver.portal.tokenValue

/**
 * P3 dev-preview binding resolution. The editor fills [mocks] (string-typed;
 * the *B getters parse per expected kind) and hooks [actionSink] to its action
 * console. Compose-observable so mock edits recompose the preview live.
 */
public object PreviewBindings {
  public val mocks: SnapshotStateMap<String, String> = mutableStateMapOf()
  public var actionSink: (String, String?) -> Unit = { _, _ -> }

  public fun fire(action: Action, eventValue: Any? = null): Unit =
    actionSink(action.name, action.previewArg(eventValue))

  /** Mocked preview row count for a Repeat: the ITEMS field's mock parses as an int (default 3, clamped 0..10). */
  public fun rowCount(itemsField: String): Int = mocks[itemsField]?.trim()?.toIntOrNull()?.coerceIn(0, 10) ?: 3

  /** One preview row: item binds resolved against the mocks map ("a|b|c" = per-row
   *  values), namespaced by [itemsField] so lists sharing an itemVar don't collide. */
  public fun mockItemRow(node: WidgetNode, itemVar: String, itemsField: String, index: Int): WidgetNode =
    resolveItemRow(node, itemVar, itemsField, index, mocks::get)
}

/**
 * K3: whether [cond] holds against the preview mocks (an unmocked field reads
 * as unknown — see [Cond.holds]). K6: each term reads its own field's mock
 * ([Cond.holdsIn]).
 */
public fun PreviewBindings.holds(cond: Cond): Boolean = cond.holdsIn(mocks)

/** K3: [v] with a conditional value replaced by the branch the mocks choose. */
public fun PreviewBindings.chosen(v: Any?): Any? = chooseBranch(v, ::holds)

/** K3: the children a `Condition` node renders — its then-branch, or its `else` branch's children. */
internal fun WidgetNode.previewBranch(): List<WidgetNode> {
  val cond = condOf(this) ?: return thenChildren()
  return if (PreviewBindings.holds(cond)) thenChildren() else elseNode()?.children.orEmpty()
}

/** The Action wired to an event prop (K3: a conditional one's chosen branch), or null. */
public fun WidgetNode.actionOf(key: String): Action? = PreviewBindings.chosen(props[key]) as? Action

/** Resolve the source-shaped Action.arg into the value a real presenter receives. */
public fun Action.previewArg(eventValue: Any?): String? {
  val sourceArg = arg
  return when (sourceArg) {
    null -> null
    "it" -> eventValue?.toString()
    else -> sourceArg.decodePreviewLiteral()
  }
}

private fun String.decodePreviewLiteral(): String {
  val t = trim()
  return dev.keliver.portal.parseKotlinStringLiteral(t) ?: t
}

// Bind-aware getters: resolve Bind via mocks, else use the literal (a design
// token's resolved value — K2 — counts as its literal), else default. K3: a
// conditional value first resolves to the branch its condition picks.

public fun WidgetNode.strB(key: String, default: String = ""): String = when (val v = tokenValue(PreviewBindings.chosen(props[key]))) {
  is Bind -> PreviewBindings.mocks[v.field] ?: "{${v.field}}"
  is String -> v
  is Template -> PreviewBindings.text(v)
  else -> default
}

/**
 * K4: a template's text in the preview — each binding its mock (else a
 * `{field}` placeholder), each token its value, a conditional part its
 * chosen branch, a nested template (a component argument) its own text.
 */
public fun PreviewBindings.text(t: Template): String = templateText(t, ::partText)

private fun PreviewBindings.partText(p: Any?): String? = when (val v = tokenValue(chosen(p))) {
  is Bind -> mocks[v.field]
  is Template -> text(v)
  else -> templateValueText(v)
}

/**
 * K4: an Int list prop — a token element its value, a bound element its mock
 * (else 0). A mock is a decimal or a colour ([mockedIntElement]); any other
 * mock leaves its element out, as the device wire does.
 */
public fun WidgetNode.intListB(key: String): List<Int> = when (val v = props[key]) {
  is ListOf -> v.items.mapNotNull { e ->
    when (val x = tokenValue(PreviewBindings.chosen(e))) {
      is Int -> x
      is Bind -> PreviewBindings.mocks[x.field].let { m -> if (m == null) 0 else mockedIntElement(m) }
      is String -> mockedIntElement(x) // an item field's row value
      else -> null
    }
  }
  else -> intList(key)
}

/** K4: a String list prop — a token element its value, a bound element its mock (else a placeholder). */
public fun WidgetNode.strListB(key: String): List<String> = when (val v = props[key]) {
  // An element a component argument substituted may be a template (or a conditional): evaluated like a part.
  is ListOf -> v.items.map { e -> PreviewBindings.partText(e) ?: (tokenValue(PreviewBindings.chosen(e)) as? Bind)?.let { "{${it.field}}" } ?: "" }
  else -> strList(key)
}

/**
 * K7: an Int value in the preview — an arithmetic value computed with Kotlin's
 * Int operators from its operands: a token its value, a binding its mock read
 * as a decimal, an item field its row's, a conditional operand (a component
 * argument) its chosen branch. Null = unknown (an unmocked operand, a mock
 * that is not a decimal): the prop then renders as an unmocked binding does.
 */
public fun PreviewBindings.intValueOf(v: Any?): Int? = arithValue(v, ::operandInt)

private fun PreviewBindings.operandInt(v: Any?): Int? = when (val x = tokenValue(chosen(v))) {
  is Int -> x
  is Arith -> intValueOf(x)
  is Bind -> mocks[x.field]?.toIntOrNull()
  is String -> x.toIntOrNull() // an item field's row value, a mock substituted for a binding
  else -> null
}

public fun WidgetNode.intB(key: String, default: Int = 0): Int = when (val v = tokenValue(PreviewBindings.chosen(props[key]))) {
  is Bind -> PreviewBindings.mocks[v.field]?.toIntOrNull() ?: default
  is Arith -> PreviewBindings.intValueOf(v) ?: default
  is Int -> v
  else -> default
}

public fun WidgetNode.boolB(key: String, default: Boolean = false): Boolean = when (val v = tokenValue(PreviewBindings.chosen(props[key]))) {
  is Bind -> PreviewBindings.mocks[v.field]?.toBooleanStrictOrNull() ?: default
  is Boolean -> v
  else -> default
}

public fun WidgetNode.dblB(key: String, default: Double = 0.0): Double = when (val v = tokenValue(PreviewBindings.chosen(props[key]))) {
  is Bind -> PreviewBindings.mocks[v.field]?.toDoubleOrNull() ?: default
  // K7: an Int used as a Dp, computed as an Int (see intValueOf).
  is IntDp -> PreviewBindings.intValueOf(v.value)?.toDouble() ?: default
  is Double -> v
  is Int -> v.toDouble()
  else -> default
}
