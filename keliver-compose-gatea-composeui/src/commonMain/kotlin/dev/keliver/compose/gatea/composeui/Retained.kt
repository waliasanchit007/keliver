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

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.clearAndSetSemantics
import dev.keliver.Modifier as KeliverModifier
import dev.keliver.compose.gatea.widget.ExtraRetained
import dev.keliver.compose.gatea.widget.ExtraRetainedEntry
import dev.keliver.ui.Cancellable
import dev.keliver.ui.OnBackPressedCallback
import dev.keliver.ui.OnBackPressedDispatcher

/**
 * A host's back dispatcher for retained stacks ([ExtraRetained]): the platform dispatcher the guest's
 * `BackHandler`s reach, which can also dispatch a press again ([onBackPressed]).
 */
public interface HostBackDispatcher : OnBackPressedDispatcher {
  /**
   * Dispatches a back press to the newest enabled callback, as the platform does for a real press.
   * A stack whose callback took a press it can no longer handle (a second press in the frame of the
   * first) disables that callback and passes the press on through this, so it is never swallowed.
   */
  public fun onBackPressed()
}

/**
 * The host's back dispatcher for retained stacks ([ExtraRetained]): a stack whose shown entry allows
 * it adds a callback here, and goes back on the host, in the frame of the press, with no guest round
 * trip. Null (the default) turns host back off: every back goes to the guest, as without the
 * container. A host provides it around its guest content, over the same platform dispatcher the
 * guest's `BackHandler`s reach. A stack adds its callback each time it becomes enabled, so on a
 * dispatcher where the newest enabled callback wins (Android's) it is ahead of callbacks added
 * before, such as an app's root `BackHandler` that pops the same stack in the guest. A hidden entry
 * of a stack sees null here, so a stack nested in it never takes a press.
 *
 * Prototype only. Known gaps, to close before gate A leaves the prototype:
 * - Host back is ahead of an enabled guest `BackHandler` registered earlier OUTSIDE the stack (a
 *   root-level sheet's, say): the guest refuses a press only for a handler in the entry it leaves.
 *   Until that is fixed, overlays that take back must live inside an entry.
 * - No predictive back: [OnBackPressedCallback] has no started/progressed/cancelled hooks, so on
 *   Android 14+ the switch happens on commit, with no swipe preview. iOS has no host-back trigger
 *   yet (TreehouseContent's iOS dispatcher is a no-op and nothing provides this), so iOS stays on
 *   the guest round trip.
 * - Registration order at start-up: the guest's root `BackHandler` registers asynchronously; if it
 *   lands after the stack's callback it is newer and takes presses (safe, but host back is off)
 *   until the stack's callback is added again.
 * - Every host wires this by hand, and forgetting it silently turns host back off.
 *   `TreehouseContent` should provide it from the dispatcher it already builds, and it should be
 *   marked experimental.
 */
public val LocalHostBackDispatcher: ProvidableCompositionLocal<HostBackDispatcher?> =
  staticCompositionLocalOf { null }

/**
 * Whether the content is in a hidden entry of a retained stack ([ExtraRetained]). Host renderers
 * that animate on their own (the shimmer) stop while it is true, so a hidden entry costs no frames.
 */
public val LocalRetainedHidden: ProvidableCompositionLocal<Boolean> = compositionLocalOf { false }

/**
 * A retained stack (NF1). Shows the guest's last entry, or, after a back it handled itself, the
 * entry it went back to until the guest has handled every back sent ([backHandled]). Hidden entries
 * stay composed (native state survives) but are not measured or placed (so not drawn and given no
 * input), expose no semantics, refuse focus traversal, and lose focus when they are hidden.
 */
internal class ComposeUiExtraRetained :
  ExtraRetained<@Composable (Modifier) -> Unit>,
  GeneratedWidget {
  override var modifier: KeliverModifier by mutableStateOf<KeliverModifier>(KeliverModifier)
  override var modifierEventSink: ((Int) -> Unit)? = null

  private var guestHandled by mutableIntStateOf(0)
  private var onBack: ((String) -> Unit)? by mutableStateOf(null)

  /** Back events sent to the guest. */
  private var sent by mutableIntStateOf(0)

  /** The key of the entry the host went back to, while the guest has not handled every back. */
  private var hostShown: String? by mutableStateOf(null)

  override val entries: ScopedChildren = ScopedChildren()

  override fun backHandled(backHandled: Int) {
    guestHandled = backHandled
    if (backHandled >= sent) hostShown = null
  }

  override fun onBack(onBack: (String) -> Unit) {
    this.onBack = onBack
  }

  /** The index of the entry on screen. */
  internal val shownIndex: Int
    get() {
      val widgets = entries.widgets
      val shown = hostShown
      if (shown != null && guestHandled < sent) {
        val index = widgets.indexOfFirst { (it as? ComposeUiExtraRetainedEntry)?.key == shown }
        if (index >= 0) return index
      }
      return widgets.lastIndex
    }

  /**
   * Whether a back press now would be handled here, on the host. Every condition [goBack] needs is
   * here, so [goBack] fails only when this is false: a callback that passes a press on (and disables
   * itself) is then removed and added again as this changes, never left off while this stays true.
   */
  internal val canGoBack: Boolean
    get() = keyBelowShown() != null

  /** The key of the entry below the shown one, if the host may go back to it; else null. */
  private fun keyBelowShown(): String? {
    val index = shownIndex
    if (index <= 0 || onBack == null) return null
    val widgets = entries.widgets
    if ((widgets[index] as? ComposeUiExtraRetainedEntry)?.hostBack != true) return null
    return (widgets[index - 1] as? ComposeUiExtraRetainedEntry)?.key
  }

  /** Shows the entry below the shown one and tells the guest; false if this stack cannot go back. */
  internal fun goBack(): Boolean {
    val key = keyBelowShown() ?: return false
    hostShown = key
    sent++
    onBack?.invoke(key)
    return true
  }

  override val value: @Composable (Modifier) -> Unit = { incoming ->
    val shown = shownIndex
    val enabled = canGoBack
    val dispatcher = LocalHostBackDispatcher.current
    // Added each time it becomes enabled (and removed when it is not): the newest callback.
    DisposableEffect(dispatcher, enabled) {
      val cancellable: Cancellable? = if (dispatcher != null && enabled) {
        dispatcher.addCallback(
          object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
              // A second press in the frame of the first can find this stack unable to go back
              // before recomposition removes the callback: pass it on rather than swallow it.
              if (!goBack()) {
                isEnabled = false
                dispatcher.onBackPressed()
              }
            }
          },
        )
      } else {
        null
      }
      onDispose { cancellable?.cancel() }
    }
    val widgets = entries.widgets
    Layout(
      modifier = incoming.applyGenerated(modifier, this),
      content = {
        for (index in widgets.indices) {
          val widget = widgets[index]
          key(widget) {
            RetainedSlot(hidden = index != shown) {
              widget.value(Modifier.applyScoped(widget.modifier, null))
            }
          }
        }
      },
    ) { measurables, constraints ->
      // Only the shown entry is measured and placed: a hidden one is not laid out, drawn or hit.
      val placeable = measurables.getOrNull(shown)?.measure(constraints)
      layout(placeable?.width ?: constraints.minWidth, placeable?.height ?: constraints.minHeight) {
        placeable?.place(0, 0)
      }
    }
  }
}

/**
 * One entry's slot: semantics cleared, focus refused and dropped while [hidden]. A hidden entry also
 * sees no [LocalHostBackDispatcher] (a stack nested in it never takes a press) and
 * [LocalRetainedHidden] true (host animations in it stop).
 */
@Composable
private fun RetainedSlot(hidden: Boolean, content: @Composable () -> Unit) {
  val focus = remember { FocusHolder() }
  val focusManager = LocalFocusManager.current
  SideEffect {
    focus.hidden = hidden
    // A hidden entry must not keep focus (or the keyboard a focused text field holds).
    if (hidden && focus.hasFocus) focusManager.clearFocus(force = true)
  }
  Box(
    Modifier
      .then(if (hidden) Modifier.clearAndSetSemantics {} else Modifier)
      .onFocusChanged { focus.hasFocus = it.hasFocus }
      .focusProperties { onEnter = { if (focus.hidden) cancelFocusChange() } }
      .focusGroup(),
    propagateMinConstraints = true,
  ) {
    CompositionLocalProvider(
      LocalHostBackDispatcher provides if (hidden) null else LocalHostBackDispatcher.current,
      LocalRetainedHidden provides hidden,
      content = content,
    )
  }
}

private class FocusHolder {
  var hasFocus = false
  var hidden = false
}

/** One entry of a retained stack: its key, whether the host may go back from it, and its content. */
internal class ComposeUiExtraRetainedEntry :
  ExtraRetainedEntry<@Composable (Modifier) -> Unit>,
  GeneratedWidget {
  override var modifier: KeliverModifier by mutableStateOf<KeliverModifier>(KeliverModifier)
  override var modifierEventSink: ((Int) -> Unit)? = null

  var key: String? by mutableStateOf(null)
    private set
  var hostBack: Boolean by mutableStateOf(false)
    private set

  override fun key(key: String) {
    this.key = key
  }

  override fun hostBack(hostBack: Boolean) {
    this.hostBack = hostBack
  }

  override val content: ScopedChildren = ScopedChildren()

  override val value: @Composable (Modifier) -> Unit = { incoming ->
    Box(incoming.applyGenerated(modifier, this)) { content.Render(this) }
  }
}
