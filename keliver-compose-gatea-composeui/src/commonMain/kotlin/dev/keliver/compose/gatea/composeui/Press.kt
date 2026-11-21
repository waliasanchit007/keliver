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

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.isOutOfBounds
import androidx.compose.ui.input.pointer.pointerInput
import dev.keliver.compose.gatea.modifier.ExtraPress
import kotlinx.coroutines.CancellationException

/**
 * The host feature a host declares (`TreehouseApp.Spec.hostFeatures`) when it renders gate A's
 * press modifier ([ExtraPress], tag 501) and sends its events: a guest sends the modifier only to a
 * host that declares it (D2), and otherwise falls back to the click.
 */
public const val PRESS_HOST_FEATURE: String = "keliver.press@1"

/**
 * [ExtraPress]: tells the guest a finger went down on the element (its `onPress` handle), at once,
 * before any click, and that the press ended without a click (`onCancel`). Both travel as the
 * owning widget's modifier events, the path a clickable's click takes, so the guest hears the press
 * first and the click (if any) after it.
 *
 * Observed in the final pass, after every other handler, so this sees what they did and consumes
 * nothing: the clickable on the same element keeps its own press, ripple and click. The press ends
 * - in a click, when the finger lifts inside the element: nothing more is sent (the click follows);
 * - in a cancel when the finger lifts outside it, a change is consumed before it lifts (a scroll
 *   parent took the gesture, as the clickable's own press is cancelled then), or the gesture is
 *   stopped (the element left the tree, the system cancelled the touch).
 *
 * Not covered: a release inside the element with no click following (a disabled clickable, or none)
 * sends no cancel; the guest's preparation stays until its next press or navigation drops it.
 * Unlike the clickable's indication, the press is not delayed inside scrollable containers, so a
 * scroll that starts on a row sends a press and then a cancel.
 */
@Composable
internal fun Modifier.applyExtraPress(press: ExtraPress, owner: GeneratedWidget?): Modifier {
  // The handles are read when sent: a guest recomposition that renumbers them does not restart (and
  // cancel) a press under way.
  val onPress by rememberUpdatedState(press.onPress)
  val onCancel by rememberUpdatedState(press.onCancel)
  return pointerInput(owner) {
    awaitEachGesture {
      awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
      owner?.modifierEventSink?.invoke(onPress)
      var clicked = false
      try {
        while (true) {
          val event = awaitPointerEvent(PointerEventPass.Final)
          val changes = event.changes
          if (changes.all { it.changedToUpIgnoreConsumed() }) {
            clicked = changes.none { it.isOutOfBounds(size, extendedTouchPadding) }
            break
          }
          if (changes.any { it.isConsumed || it.isOutOfBounds(size, extendedTouchPadding) }) break
        }
      } catch (e: CancellationException) {
        owner?.modifierEventSink?.invoke(onCancel)
        throw e
      }
      if (!clicked) owner?.modifierEventSink?.invoke(onCancel)
    }
  }
}
