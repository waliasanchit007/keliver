/*
 * Copyright (C) 2026 Keliver contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.keliver.portal.render

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.currentCompositionLocalContext
import dev.keliver.Modifier
import dev.keliver.portal.Action
import dev.keliver.portal.Bind
import dev.keliver.portal.Template
import dev.keliver.portal.WidgetNode
import dev.keliver.portal.tokenValue

/**
 * Compiled component preview (docs/COMPILED_COMPONENT_PREVIEW.md).
 *
 * A project component whose body is outside the portal grammar is OPAQUE: the
 * canvas cannot interpret it. A per-app editor compiles the app's components
 * for wasm anyway, so it can call the component itself in the canvas's guest
 * composition — the same code a device runs — through one adapter per
 * component. An adapter turns an instance's arguments into the real call:
 *
 * ```
 * "Badge" to CompiledComponent { call ->
 *   val label = call.string("label")
 *   val modifier = call.modifier
 *   call.compose { Badge(label = label, modifier = modifier) }
 * }
 * ```
 *
 * [bind] is plain (non-composable) code, so a value that cannot be decoded
 * fails there and the canvas shows the placeholder with the reason instead.
 * Adapters are normally generated from the components' signatures
 * (`CompiledAdapterGenerator` in portal-ingest); a hand-written one is the
 * same shape.
 */
public fun interface CompiledComponent {
  /** Decode [call]'s arguments and return the compiled call to compose. May throw. */
  public fun bind(call: CompiledCall): @Composable () -> Unit
}

/** A value an adapter could not decode; its message becomes the placeholder's reason. */
public class CompiledArgException(message: String) : IllegalArgumentException(message)

/**
 * One component instance as an adapter sees it. Every value is resolved the
 * way the canvas resolves a widget's property: a design token is its value,
 * a screen binding its preview value, a conditional its chosen branch, a
 * template its text. A required argument the instance leaves out previews
 * the same sample value transparent expansion uses.
 */
public class CompiledCall internal constructor(
  private val node: WidgetNode,
  /** The instance's own modifiers, built in its parent's scope, plus its selection tag. */
  public val modifier: Modifier,
  /**
   * True for the failure probe's copy of the call: its events do nothing (a
   * `SideEffect` in the probe must not reach the action console or a live
   * presenter) and its slot composes nothing.
   */
  private val probing: Boolean = false,
) {
  private val decoded = ArrayList<Any?>()

  /** What [bind] read, in order — the key the failure probe is remembered by. */
  internal fun decodedValues(): List<Any?> = decoded

  /** True when the instance passes [name] (even as an explicit `null`). */
  public fun has(name: String): Boolean = name in node.props

  /**
   * For a parameter whose default is not a literal or a token: the adapter
   * cannot reproduce it, so an instance that leaves it out cannot be
   * previewed compiled.
   */
  public fun requireSet(name: String, defaultText: String) {
    if (!has(name)) throw CompiledArgException("'$name' is not set and its default `$defaultText` is not a literal")
  }

  public fun string(name: String): String = string(name, sample(name))
  public fun string(name: String, default: String): String = record(stringValue(name, default) ?: nullFor(name))
  public fun stringOrNull(name: String, default: String?): String? = record(stringValue(name, default))
  private fun stringValue(name: String, default: String?): String? =
    if (!has(name)) default
    else when (val v = resolved(name)) {
      null -> null
      is String -> v
      is Bind -> PreviewBindings.mocks[v.field] ?: "{${v.field}}"
      is Template -> PreviewBindings.text(v)
      else -> mismatch(name, "String", v)
    }

  public fun int(name: String): Int = int(name, 0)
  public fun int(name: String, default: Int): Int = record(intValue(name, default) ?: nullFor(name))
  public fun intOrNull(name: String, default: Int?): Int? = record(intValue(name, default))
  private fun intValue(name: String, default: Int?): Int? =
    if (!has(name)) default
    else when (val v = resolved(name)) {
      null -> null
      is Int -> v
      // A binding with no usable preview value keeps the default, as a widget property does.
      is Bind -> PreviewBindings.mocks[v.field]?.toIntOrNull() ?: default
      // K7: an arithmetic argument, computed as a widget property's is (unknown keeps the default).
      is dev.keliver.portal.Arith -> PreviewBindings.intValueOf(v) ?: default
      else -> mismatch(name, "Int", v)
    }

  public fun bool(name: String): Boolean = bool(name, false)
  public fun bool(name: String, default: Boolean): Boolean = record(boolValue(name, default) ?: nullFor(name))
  public fun boolOrNull(name: String, default: Boolean?): Boolean? = record(boolValue(name, default))
  private fun boolValue(name: String, default: Boolean?): Boolean? =
    if (!has(name)) default
    else when (val v = resolved(name)) {
      null -> null
      is Boolean -> v
      is Bind -> PreviewBindings.mocks[v.field]?.toBooleanStrictOrNull() ?: default
      else -> mismatch(name, "Boolean", v)
    }

  public fun double(name: String): Double = double(name, 0.0)
  public fun double(name: String, default: Double): Double = record(doubleValue(name, default) ?: nullFor(name))
  public fun doubleOrNull(name: String, default: Double?): Double? = record(doubleValue(name, default))
  private fun doubleValue(name: String, default: Double?): Double? =
    if (!has(name)) default
    else when (val v = resolved(name)) {
      null -> null
      is Double -> v
      is Int -> v.toDouble()
      is Bind -> PreviewBindings.mocks[v.field]?.toDoubleOrNull() ?: default
      else -> mismatch(name, "Double", v)
    }

  /**
   * A `() -> Unit` parameter: the instance's action, delivered to the preview's
   * action console / live presenter exactly as a transparent instance's
   * widget would deliver it; a no-op when the instance wires none.
   */
  public fun event(name: String): () -> Unit = eventOrNull(name) ?: {}

  /** A nullable `(() -> Unit)?` parameter: null when the instance wires none. */
  public fun eventOrNull(name: String): (() -> Unit)? = action(name)?.let { a ->
    if (probing) ({}) else ({ PreviewBindings.fire(a) })
  }

  /** A `(T) -> Unit` parameter; the value reaches the action as `it`. */
  public fun <T> event1(name: String): (T) -> Unit = event1OrNull(name) ?: { _: T -> }

  /** A nullable `((T) -> Unit)?` parameter. */
  public fun <T> event1OrNull(name: String): ((T) -> Unit)? = action(name)?.let { a ->
    if (probing) ({ _: T -> }) else ({ v: T -> PreviewBindings.fire(a, v) })
  }

  /**
   * The component's content slot: the instance's children, rendered by the
   * canvas as ordinary document nodes (each stays selectable on its own). A
   * slot lambda has no layout receiver, so neither do they — as on a device.
   */
  public fun content(): @Composable () -> Unit =
    if (probing) ({}) else ({ node.children.forEach { RenderNode(it, null) } })

  /** For a component with no content slot: an instance with children cannot be previewed compiled. */
  public fun noContent() {
    if (node.children.isNotEmpty()) throw CompiledArgException("the instance has content but the component takes no content slot")
  }

  /** The adapter's result: the compiled call, composed in the canvas. */
  public fun compose(content: @Composable () -> Unit): @Composable () -> Unit = content

  private fun resolved(name: String): Any? = tokenValue(PreviewBindings.chosen(node.props[name]))

  private fun action(name: String): Action? = when (val v = PreviewBindings.chosen(node.props[name])) {
    null -> null
    is Action -> v
    else -> throw CompiledArgException("'$name' is an event but the instance passes ${kindOf(v)}")
  }.also { decoded += it }

  private fun <T> record(v: T): T = v.also { decoded += it }

  private fun nullFor(name: String): Nothing = throw CompiledArgException("'$name' is null but the parameter is not nullable")

  private fun mismatch(name: String, type: String, v: Any): Nothing =
    throw CompiledArgException("'$name' expects $type but the instance passes ${kindOf(v)}")

  private fun kindOf(v: Any?): String = when (v) {
    is Action -> "an event"
    is Bind -> "a binding"
    null -> "null"
    else -> v::class.simpleName ?: "a value"
  }

  /** Transparent expansion's sample for a required text argument (ComponentSpec.sampleFor). */
  private fun sample(name: String): String = name.replaceFirstChar { it.uppercaseChar() }
}

/**
 * Runs a compiled call once OUTSIDE the canvas composition and returns what it
 * threw, or null. Compose cannot catch an exception thrown while composing,
 * and one thrown in the canvas composition stops the whole preview; the probe
 * finds it first, so the instance falls back to its placeholder instead.
 * The call it gets is a probe copy (events do nothing, the slot composes
 * nothing) wrapped in the canvas's composition locals at the instance.
 * Effects launched later and recompositions driven by the component's own
 * state are not probed.
 *
 * Cost: one throwaway guest composition per (component, decoded arguments,
 * modifiers, composition locals) the canvas meets — remembered, so a stable
 * instance is probed once; each Repeat row with different values is its own.
 */
public fun interface CompiledComponentProbe {
  public fun failureOf(content: @Composable () -> Unit): Throwable?
}

/**
 * EDITOR-INTERNAL hook, not for apps: the editor shell sets it (portal-editor's
 * DiscardingCompiledProbe, installed with the entry's adapters). It is public
 * only because the shell lives in another module. Null = no probe.
 */
public var compiledComponentProbe: CompiledComponentProbe? = null

/**
 * Renders the component instance [node] through [adapter]: its arguments
 * decoded, its modifiers (and selection tag) built in [scope] and handed to
 * the component's `modifier` parameter, exactly as the compiled call on a
 * device. When an argument cannot be decoded, or the probe sees the call
 * throw, [fallback] renders instead with the reason.
 */
@Composable
public fun RenderCompiledComponent(
  node: WidgetNode,
  scope: Any?,
  adapter: CompiledComponent,
  fallback: @Composable (reason: String) -> Unit,
) {
  val call = CompiledCall(node, nodeModifier(node, scope))
  val bound: Result<@Composable () -> Unit> = try {
    Result.success(adapter.bind(call))
  } catch (e: Throwable) {
    Result.failure(e)
  }
  val content = bound.getOrNull()
  if (content == null) {
    fallback("compiled preview: ${reasonOf(bound.exceptionOrNull())}")
    return
  }
  val probe = compiledComponentProbe
  // The canvas's composition locals here (the app's screen wrapper Theme,
  // UI configuration, …): the probe composes the call inside them too, so a
  // component that needs one is not reported as failing.
  val locals = currentCompositionLocalContext
  val failure = if (probe == null) {
    null
  } else {
    remember(node.type, call.decodedValues(), call.modifier, locals) {
      runCatching {
        val probeContent = adapter.bind(CompiledCall(node, call.modifier, probing = true))
        // …but never the canvas's saveable-state registry: the probe must
        // neither register state into it nor consume what it restored.
        probe.failureOf {
          CompositionLocalProvider(locals) {
            CompositionLocalProvider(LocalSaveableStateRegistry provides null) { probeContent() }
          }
        }
      }.getOrElse { it }
    }
  }
  if (failure != null) fallback("compiled preview failed: ${reasonOf(failure)}") else content()
}

private fun reasonOf(e: Throwable?): String = when (e) {
  null -> "unknown error"
  is CompiledArgException -> e.message ?: "an argument could not be decoded"
  else -> listOfNotNull(e::class.simpleName, e.message).joinToString(": ").ifEmpty { "error" }
}
