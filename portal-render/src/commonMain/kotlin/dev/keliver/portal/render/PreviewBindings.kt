package dev.keliver.portal.render

import androidx.compose.runtime.mutableStateMapOf
import dev.keliver.portal.Action
import dev.keliver.portal.Bind
import dev.keliver.portal.WidgetNode
import dev.keliver.portal.resolveItemRow

/**
 * P3 dev-preview binding resolution. The editor fills [mocks] (string-typed;
 * the *B getters parse per expected kind) and hooks [actionSink] to its action
 * console. Compose-observable so mock edits recompose the preview live.
 */
object PreviewBindings {
  val mocks = mutableStateMapOf<String, String>()
  var actionSink: (String) -> Unit = {}

  fun fire(name: String) = actionSink(name)

  /** Mocked preview row count for a Repeat: the ITEMS field's mock parses as an int (default 3, clamped 0..10). */
  fun rowCount(itemsField: String): Int = mocks[itemsField]?.trim()?.toIntOrNull()?.coerceIn(0, 10) ?: 3

  /** One preview row: item binds resolved against the mocks map ("a|b|c" = per-row values). */
  fun mockItemRow(node: WidgetNode, itemVar: String, index: Int): WidgetNode =
    resolveItemRow(node, itemVar, index, mocks::get)
}

/** The Action name wired to an event prop, or null. */
fun WidgetNode.actionOf(key: String): String? = (props[key] as? Action)?.name

// Bind-aware getters: resolve Bind via mocks, else use the literal, else default.

fun WidgetNode.strB(key: String, default: String = ""): String = when (val v = props[key]) {
  is Bind -> PreviewBindings.mocks[v.field] ?: "{${v.field}}"
  is String -> v
  else -> default
}

fun WidgetNode.intB(key: String, default: Int = 0): Int = when (val v = props[key]) {
  is Bind -> PreviewBindings.mocks[v.field]?.toIntOrNull() ?: default
  is Int -> v
  else -> default
}

fun WidgetNode.boolB(key: String, default: Boolean = false): Boolean = when (val v = props[key]) {
  is Bind -> PreviewBindings.mocks[v.field]?.toBooleanStrictOrNull() ?: default
  is Boolean -> v
  else -> default
}

fun WidgetNode.dblB(key: String, default: Double = 0.0): Double = when (val v = props[key]) {
  is Bind -> PreviewBindings.mocks[v.field]?.toDoubleOrNull() ?: default
  is Double -> v
  is Int -> v.toDouble()
  else -> default
}
