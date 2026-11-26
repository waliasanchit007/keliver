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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
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
 * Not a static local: every entry's slot provides it, and the value changes as the entry is hidden
 * or shown, in the frame of a host back. A static local's change recomposes everything under the
 * slot without skipping (the whole retained screen: about 100 widgets on the three-way prototype's
 * heavy detail, the NF1 gate's 56.5 ms back); this one recomposes only its readers.
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
  compositionLocalOf { null }

/**
 * Whether the content is in a hidden entry of a retained stack ([ExtraRetained]). Host renderers
 * that animate on their own (the shimmer) stop while it is true, so a hidden entry costs no frames.
 */
public val LocalRetainedHidden: ProvidableCompositionLocal<Boolean> = compositionLocalOf { false }

/**
 * NF5 R1, a BENCH-ONLY hook (docs/nf5-r1/README.md; docs/SHOW_BEFORE_COMPUTE.md, section 7): a
 * host's policy for a retained stack's PREPARED entries, and the host reveal. Null (the default)
 * changes nothing: the stack shows its last entry, as without it.
 *
 * With a policy, an entry whose key [isPrepared] is composed and hidden, as any hidden entry, but
 * the stack's order never shows it: the stack shows its last entry that is not prepared. With
 * [revealOnClick], a click in the shown entry shows the newest entry whose key [isRevealable] in the
 * frame after the click, before the guest has run (the reveal), until the guest's own state shows
 * that entry (its key no longer prepared: the reveal is then "live"), or the entry leaves the stack.
 * With [measurePrepared], prepared entries are measured with the shown entry's constraints, and not
 * placed, whenever they change, so the reveal's frame places and draws them without measuring.
 *
 * What a production reveal needs and this does not do (section 3.2, I13): the guest is never told
 * of the reveal (no token, answer or cancel), the revealed entry is not inert, back and `REVEAL_LIMIT`
 * are not handled, and a guest that declines leaves the reveal on screen. The click is observed, not
 * dispatched: an up that a handler below consumed, in a gesture no scroll took, seen in the final
 * pass of the same input event as the click (so the reveal lands in the same frame a click in the
 * generated dispatch would, M3); any click in the shown entry reveals, not only a marked row's.
 */
public interface RetainedReveal {
  /** Whether the entry with [key] is prepared: composed and hidden, never shown by the stack's order. */
  public fun isPrepared(key: String): Boolean

  /** Whether a click may reveal the prepared entry with [key] (an entry may be prepared, hidden, and not revealable). */
  public fun isRevealable(key: String): Boolean = isPrepared(key)

  /** Whether a click in the shown entry reveals the newest revealable entry, before the guest runs. */
  public val revealOnClick: Boolean

  /** Whether prepared entries are measured (not placed) while hidden, so a reveal does not measure them. */
  public val measurePrepared: Boolean

  /** The host revealed the entry with [key]: on the main thread, in the input event that carried the click. */
  public fun onReveal(key: String) {}

  /** The revealed entry was drawn for the first time: on the main thread, while the frame records its draw. */
  public fun onRevealDrawn(key: String) {}

  /** The guest's own state shows the revealed entry: the reveal is over (main thread, after that frame's composition). */
  public fun onRevealLive(key: String) {}
}

/** NF5 R1's bench-only reveal policy for retained stacks ([RetainedReveal]); null, the default, turns it off. */
public val LocalRetainedReveal: ProvidableCompositionLocal<RetainedReveal?> = staticCompositionLocalOf { null }

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

  /** NF5 R1: the host's policy ([LocalRetainedReveal]), as the last composition read it; null = none. */
  private var reveal: RetainedReveal? = null

  /** NF5 R1: the entry the host revealed on a click, until the guest shows it or it leaves the stack. */
  private var revealed: ComposeUiExtraRetainedEntry? by mutableStateOf(null)

  /** [revealed] for the draw phase, which must not read state (a read there would redraw on every change). */
  private var revealedForDraw: ComposeUiExtraRetainedEntry? = null
  private var revealDrawReported = false

  override fun backHandled(backHandled: Int) {
    guestHandled = backHandled
    if (backHandled >= sent) hostShown = null
  }

  override fun onBack(onBack: (String) -> Unit) {
    this.onBack = onBack
  }

  /** NF5 R1: whether this widget is an entry the host's policy calls prepared (never, without a policy). */
  private fun Any.isPreparedEntry(): Boolean {
    val policy = reveal ?: return false
    val key = (this as? ComposeUiExtraRetainedEntry)?.key ?: return false
    return policy.isPrepared(key)
  }

  /** The index of the entry the guest's own state shows: its last entry that is not prepared. */
  private fun guestShownIndex(): Int {
    val widgets = entries.widgets
    return if (reveal == null) widgets.lastIndex else widgets.indexOfLast { !it.isPreparedEntry() }
  }

  /** The index of the entry on screen. */
  internal val shownIndex: Int
    get() {
      val widgets = entries.widgets
      val revealedEntry = revealed
      if (revealedEntry != null && revealedEntry.isPreparedEntry()) {
        val index = widgets.indexOf(revealedEntry)
        if (index >= 0) return index
      }
      val shown = hostShown
      if (shown != null && guestHandled < sent) {
        val index = widgets.indexOfFirst { (it as? ComposeUiExtraRetainedEntry)?.key == shown }
        if (index >= 0) return index
      }
      return guestShownIndex()
    }

  /**
   * NF5 R1: shows the newest revealable entry other than the shown one, in the next frame, and tells
   * the policy. False when there is no policy asking for it, a reveal is outstanding, or nothing to
   * reveal. Main thread.
   */
  internal fun revealOnClick(): Boolean {
    val policy = reveal ?: return false
    if (!policy.revealOnClick || revealed != null) return false
    val widgets = entries.widgets
    val shown = shownIndex
    val index = widgets.indices.lastOrNull { i ->
      i != shown && (widgets[i] as? ComposeUiExtraRetainedEntry)?.key?.let(policy::isRevealable) == true
    } ?: return false
    val entry = widgets[index] as ComposeUiExtraRetainedEntry
    revealed = entry
    revealedForDraw = entry
    revealDrawReported = false
    policy.onReveal(entry.key.orEmpty())
    return true
  }

  private fun clearReveal() {
    revealed = null
    revealedForDraw = null
  }

  /** NF5 R1: an entry's slot is being drawn (draw phase: reads no state). */
  private fun onEntryDrawn(entry: Any) {
    if (entry !== revealedForDraw || revealDrawReported) return
    revealDrawReported = true
    reveal?.onRevealDrawn((entry as ComposeUiExtraRetainedEntry).key.orEmpty())
  }

  /**
   * Whether a back press now would be handled here, on the host. Every condition [goBack] needs is
   * here, so [goBack] fails only when this is false: a callback that passes a press on (and disables
   * itself) is then removed and added again as this changes. (If this goes false and true again within
   * one frame, a callback that disabled itself on the failed press stays off until it next changes.)
   */
  internal val canGoBack: Boolean
    get() = keyBelowShown() != null

  /** The key of the entry below the shown one, if the host may go back to it; else null. */
  private fun keyBelowShown(): String? {
    val index = shownIndex
    if (index <= 0 || onBack == null) return null
    val widgets = entries.widgets
    if ((widgets[index] as? ComposeUiExtraRetainedEntry)?.hostBack != true) return null
    // A prepared entry (NF5 R1) is never a place to go back to; without a policy this is index - 1.
    val below = (index - 1 downTo 0).firstOrNull { !widgets[it].isPreparedEntry() } ?: return null
    return (widgets[below] as? ComposeUiExtraRetainedEntry)?.key
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
    // NF5 R1: read before anything that asks which entry is shown.
    val policy = LocalRetainedReveal.current
    reveal = policy
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
    // NF5 R1: prepared entries measured while hidden (with measurePrepared), and the reveal's end.
    val measuredHidden = if (policy?.measurePrepared == true) {
      widgets.indices.filter { it != shown && widgets[it].isPreparedEntry() }
    } else {
      emptyList()
    }
    if (policy != null) {
      SideEffect {
        val revealedEntry = revealed ?: return@SideEffect
        val index = entries.widgets.indexOf(revealedEntry)
        if (index < 0) {
          // The revealed entry left the stack.
          clearReveal()
        } else if (!revealedEntry.isPreparedEntry()) {
          // The guest's own state took it out of preparation: the reveal is over.
          clearReveal()
          if (guestShownIndex() == index) policy.onRevealLive(revealedEntry.key.orEmpty())
        }
      }
    }
    Layout(
      modifier = incoming.applyGenerated(modifier, this)
        .then(if (policy?.revealOnClick == true) Modifier.revealOnClickInput(this) else Modifier),
      content = {
        for (index in widgets.indices) {
          val widget = widgets[index]
          key(widget) {
            val onDraw: (() -> Unit)? = if (policy != null) remember { { onEntryDrawn(widget) } } else null
            RetainedSlot(hidden = index != shown, onDraw = onDraw) {
              widget.value(Modifier.applyScoped(widget.modifier, null))
            }
          }
        }
      },
    ) { measurables, constraints ->
      // NF5 R1 (measurePrepared): prepared entries are measured, never placed, so not drawn or hit.
      for (index in measuredHidden) measurables.getOrNull(index)?.measure(constraints)
      // Only the shown entry is measured and placed: a hidden one is not laid out, drawn or hit.
      val placeable = measurables.getOrNull(shown)?.measure(constraints)
      layout(placeable?.width ?: constraints.minWidth, placeable?.height ?: constraints.minHeight) {
        placeable?.place(0, 0)
      }
    }
  }
}

/**
 * NF5 R1: reveals ([ComposeUiExtraRetained.revealOnClick]) on a click below. A click is an up that
 * a handler consumed in its main pass, in a gesture whose moves no scroll consumed; it is seen in
 * the final pass of that up, after the click's handler ran in the same input event. Consumes nothing.
 */
private fun Modifier.revealOnClickInput(stack: ComposeUiExtraRetained): Modifier = pointerInput(stack) {
  awaitEachGesture {
    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
    var scrolled = false
    // The down's own final pass comes first; the loop ends with the gesture.
    while (true) {
      val changes = awaitPointerEvent(PointerEventPass.Final).changes
      if (changes.any { it.isConsumed && it.positionChanged() }) scrolled = true
      if (changes.all { it.changedToUpIgnoreConsumed() }) {
        if (!scrolled && changes.any { it.isConsumed }) stack.revealOnClick()
        break
      }
    }
  }
}

/**
 * One entry's slot: semantics cleared, focus refused and dropped while [hidden]. A hidden entry also
 * sees no [LocalHostBackDispatcher] (a stack nested in it never takes a press) and
 * [LocalRetainedHidden] true (host animations in it stop). [onDraw] (NF5 R1) is called whenever the
 * slot's draw is recorded.
 */
@Composable
private fun RetainedSlot(hidden: Boolean, onDraw: (() -> Unit)? = null, content: @Composable () -> Unit) {
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
      .then(
        if (onDraw != null) {
          Modifier.drawWithContent {
            onDraw()
            drawContent()
          }
        } else {
          Modifier
        },
      )
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
