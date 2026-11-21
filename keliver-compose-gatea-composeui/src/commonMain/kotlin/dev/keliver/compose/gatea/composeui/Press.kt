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
package dev.keliver.compose.gatea.composeui

import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.isOutOfBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.TraversableNode
import androidx.compose.ui.node.traverseAncestors
import dev.keliver.compose.gatea.modifier.ExtraPress
import kotlinx.coroutines.CancellationException

/**
 * The host feature a host declares (`TreehouseApp.Spec.hostFeatures`) when it renders gate A's
 * press modifier ([ExtraPress], tag 501) and sends its events: a guest sends the modifier only to a
 * host that declares it (D2), and otherwise falls back to the click.
 */
public const val PRESS_HOST_FEATURE: String = "keliver.press@1"

/**
 * How the host times the press signal inside a scroll container (a `verticalScroll` or
 * `horizontalScroll` element, a lazy list): the press is sent once the finger has stayed within
 * [holdSlopFraction] of the touch slop for [scrollDelayMillis] after touch-down, and never when the
 * finger moved further first (a scroll starting on the element) or the touch-down stopped the
 * container while it was moving (a fling). Outside scroll containers the press is sent at once.
 *
 * Host-side and tunable: a scroll that starts on a row then costs the guest nothing, and a tap that
 * is held past the delay keeps most of the press's head start. Tune [scrollDelayMillis] on a phone
 * against how long taps last and how long a fling takes to pass the touch slop.
 */
@Immutable
public data class PressSignalTiming(
  public val scrollDelayMillis: Long = 50L,
  public val holdSlopFraction: Float = 0.5f,
) {
  init {
    require(scrollDelayMillis >= 0) { "scrollDelayMillis must not be negative: $scrollDelayMillis" }
    require(holdSlopFraction > 0f) { "holdSlopFraction must be positive: $holdSlopFraction" }
  }
}

/** The host's [PressSignalTiming] (the default unless a host provides another). */
public val LocalPressSignalTiming: ProvidableCompositionLocal<PressSignalTiming> =
  staticCompositionLocalOf { PressSignalTiming() }

/**
 * [ExtraPress]: tells the guest a finger went down on the element (its `onPress` handle), before
 * any click, and that the press ended (`onCancel`). Both travel as the owning widget's modifier
 * events, the path a clickable's click takes. See [pressSignal] for when each is sent.
 */
@Composable
internal fun Modifier.applyExtraPress(press: ExtraPress, owner: GeneratedWidget?): Modifier {
  // The handles are read when sent: a guest recomposition that renumbers them does not restart (and
  // cancel) a press under way.
  val onPress by rememberUpdatedState(press.onPress)
  val onCancel by rememberUpdatedState(press.onCancel)
  return pressSignal(
    key = owner,
    onPress = { owner?.modifierEventSink?.invoke(onPress) },
    onCancel = { owner?.modifierEventSink?.invoke(onCancel) },
  )
}

/**
 * The press signal on the host: [onPress] when a finger goes down on this element, before any
 * click, and [onCancel] when that gesture ends, whatever ended it, if [onPress] was sent.
 *
 * Observed without consuming anything (the down in the initial pass, the rest in the final pass,
 * after every other handler), so the clickable on the same element keeps its own press, ripple and
 * click. The cancel is sent at the end of every gesture that sent a press, from the final pass of
 * the event that ends it: after the up, the clickable has already sent its click (in the main pass
 * of that event), so a guest receives press, click, cancel, and its cancel after a click that
 * navigated finds nothing prepared. That covers every press that did not open what it prepared: the
 * finger lifted outside, a scroll took the gesture, a button inside the element took the click, a
 * long press, a double click, a disabled clickable, a click that does not navigate, and a gesture
 * the system cancelled or the element leaving the tree (the gesture is stopped). The system cancel
 * relies on Compose ending a cancelled gesture with an all-up event or stopping the handler; it is
 * not exercised by the tests (the desktop test host's `cancel()` delivers nothing to the handler).
 *
 * Inside a scroll container (marked by [pressScrollContainer]) the press waits, as
 * [PressSignalTiming] says: a scroll that starts on the element sends nothing.
 */
@Composable
internal fun Modifier.pressSignal(key: Any?, onPress: () -> Unit, onCancel: () -> Unit): Modifier {
  val sendPress by rememberUpdatedState(onPress)
  val sendCancel by rememberUpdatedState(onCancel)
  val timing = LocalPressSignalTiming.current
  val probe = remember { ScrollContainerProbe() }
  return this.then(ScrollContainerProbeElement(probe)).pointerInput(key, timing) {
    awaitEachGesture {
      // The initial pass: whether a scroll container is moving is read before it handles the down
      // (stopping a fling on it), and nothing is consumed.
      val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
      val container = probe.scrollContainer()
      // A touch-down that stops a moving list is not a tap on the row under it.
      var eligible = container == null || !container.state.isScrollInProgress
      var pressed = false
      if (container == null) {
        pressed = true
        sendPress()
      }
      val holdSlop = viewConfiguration.touchSlop * timing.holdSlopFraction
      try {
        // The down's own final pass comes first; the loop ends with the gesture, or early (below).
        while (true) {
          val waiting = !pressed && eligible
          val elapsed = currentEvent.changes.firstOrNull()?.uptimeMillis?.minus(down.uptimeMillis) ?: 0L
          val event: PointerEvent? = if (waiting) {
            withTimeoutOrNull((timing.scrollDelayMillis - elapsed).coerceAtLeast(0L)) {
              awaitPointerEvent(PointerEventPass.Final)
            }
          } else {
            awaitPointerEvent(PointerEventPass.Final)
          }
          if (event == null) {
            // Held still for the delay: a tap, not the start of a scroll.
            pressed = true
            sendPress()
            continue
          }
          val changes = event.changes
          if (changes.all { it.changedToUpIgnoreConsumed() }) break
          // A scroll took the gesture (it consumes the moves), or the finger left the element.
          val taken = changes.any {
            (it.isConsumed && it.positionChanged()) || it.isOutOfBounds(size, extendedTouchPadding)
          }
          if (waiting) {
            // Moved further than the hold slop first: the start of a scroll, never a press.
            if (taken || changes.any { (it.position - down.position).getDistance() > holdSlop }) eligible = false
          } else if (pressed && taken) {
            // The press is over before the finger lifts: cancel now (awaitEachGesture waits for the
            // remaining pointers to lift).
            break
          }
        }
      } catch (e: CancellationException) {
        if (pressed) sendCancel()
        throw e
      }
      if (pressed) sendCancel()
    }
  }
}

/**
 * Marks a scroll container for the press signal ([pressSignal]): a press on an element inside it
 * waits ([PressSignalTiming]). The generated renderers add it after every scroll container they
 * render (gatea.json's `scrollContainers`: `verticalScroll`, `horizontalScroll`, the lazy lists),
 * with the container's [state] (is it moving?) and whether the user may scroll it ([enabled]).
 */
internal fun Modifier.pressScrollContainer(state: ScrollableState, enabled: Boolean): Modifier =
  this then PressScrollContainerElement(state, enabled)

private object PressScrollContainerKey

private data class PressScrollContainerElement(
  val state: ScrollableState,
  val enabled: Boolean,
) : ModifierNodeElement<PressScrollContainerNode>() {
  override fun create() = PressScrollContainerNode(state, enabled)

  override fun update(node: PressScrollContainerNode) {
    node.state = state
    node.enabled = enabled
  }
}

private class PressScrollContainerNode(
  var state: ScrollableState,
  var enabled: Boolean,
) : Modifier.Node(), TraversableNode {
  override val traverseKey: Any get() = PressScrollContainerKey
}

/** Finds, when asked, the nearest enabled scroll container above the element it is attached to. */
private class ScrollContainerProbe {
  var node: ScrollContainerProbeNode? = null

  fun scrollContainer(): PressScrollContainerNode? {
    val from = node ?: return null
    var found: PressScrollContainerNode? = null
    from.traverseAncestors(PressScrollContainerKey) {
      val container = it as PressScrollContainerNode
      if (container.enabled) found = container
      found == null
    }
    return found
  }
}

private data class ScrollContainerProbeElement(val probe: ScrollContainerProbe) : ModifierNodeElement<ScrollContainerProbeNode>() {
  override fun create() = ScrollContainerProbeNode(probe)

  override fun update(node: ScrollContainerProbeNode) {
    if (node.probe !== probe) {
      if (node.probe.node === node) node.probe.node = null
      node.probe = probe
      probe.node = node
    }
  }
}

private class ScrollContainerProbeNode(var probe: ScrollContainerProbe) : Modifier.Node() {
  override fun onAttach() {
    probe.node = this
  }

  override fun onDetach() {
    if (probe.node === this) probe.node = null
  }
}
