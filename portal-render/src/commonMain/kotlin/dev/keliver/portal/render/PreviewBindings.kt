package dev.keliver.portal.render

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import dev.keliver.portal.Action
import dev.keliver.portal.Bind
import dev.keliver.portal.Cond
import dev.keliver.portal.WidgetNode
import dev.keliver.portal.chooseBranch
import dev.keliver.portal.condOf
import dev.keliver.portal.elseNode
import dev.keliver.portal.thenChildren
import dev.keliver.portal.resolveItemRow
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
 * as unknown — see [Cond.holds]).
 */
public fun PreviewBindings.holds(cond: Cond): Boolean =
  mocks[cond.field].let { cond.holds(it, present = it != null) }

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
  else -> default
}

public fun WidgetNode.intB(key: String, default: Int = 0): Int = when (val v = tokenValue(PreviewBindings.chosen(props[key]))) {
  is Bind -> PreviewBindings.mocks[v.field]?.toIntOrNull() ?: default
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
  is Double -> v
  is Int -> v.toDouble()
  else -> default
}
