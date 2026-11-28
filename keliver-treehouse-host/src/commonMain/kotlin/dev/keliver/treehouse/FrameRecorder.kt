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
@file:OptIn(KeliverFrameWidgetApi::class, KeliverFrameMarksApi::class)

package dev.keliver.treehouse

import dev.keliver.protocol.Change
import dev.keliver.protocol.Id
import dev.keliver.protocol.KeliverFrameMarksApi
import dev.keliver.protocol.host.UiEvent
import dev.keliver.protocol.host.UiEventSink
import dev.keliver.widget.KeliverFrameWidgetApi
import dev.keliver.widget.ScrollOrigin
import dev.keliver.widget.TextLines
import dev.keliver.widget.Widget
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The host widgets of a content, asked at the `ready` edge. `HostProtocolAdapter.widgetsUnder(id)`
 * is the real one; the recorder takes the answer as a map from node id to widget.
 */
@KeliverFrameFoldApi
public fun interface HostedWidgets {
  /** The host widgets of [id] and everything under it, by node id. A node with no widget is absent. */
  public fun widgetsUnder(id: Id): Map<Int, Widget<*>>
}

/**
 * Whether a retained container shows something other than its first entry (2.3, condition 3). The
 * retained container's renderer (B0) answers from its explicit entry state; until then nothing is
 * navigated.
 */
@KeliverFrameFoldApi
public fun interface RetainedNavigation {
  /** True when [container]'s group [childrenTag] shows an entry other than its first. */
  public fun isNavigatedInside(container: FoldNode, childrenTag: Int): Boolean

  public companion object {
    public val NeverNavigated: RetainedNavigation = RetainedNavigation { _, _ -> false }
  }
}

/** What the recorder did: it writes at most once per content. */
@KeliverFrameFoldApi
public sealed interface FrameRecording {
  /** The frame was stored, [bytes] long. */
  public class Written(public val bytes: Int) : FrameRecording

  /** Nothing was stored, for [reason] (the first condition of 2.3 that failed). */
  public class Skipped(public val reason: String) : FrameRecording
}

/** The recorder's two telemetry calls (`cachedFrame.written`, `cachedFrame.writeSkipped`). */
@KeliverFrameFoldApi
public interface FrameRecorderListener {
  public fun written(bytes: Int) {}

  /** [reason] is a fixed code, never content: see [FrameRecorder]. */
  public fun writeSkipped(reason: String) {}

  public companion object {
    public val None: FrameRecorderListener = object : FrameRecorderListener {}
  }
}

/**
 * The generated `<Schema>FrameRules` (docs/SHOW_BEFORE_COMPUTE.md 2.4) in one value. The generated
 * object carries the lists under these names, so wiring is one call:
 *
 * ```
 * RecordingRules(
 *   replayableWidgets = MyFrameRules.replayableWidgets,
 *   scrollingWidgets = MyFrameRules.scrollingWidgets,
 *   …
 * )
 * ```
 *
 * The generated object does not list URL-bearing properties apart from text ones, nor lazy item key
 * properties, so those two lists have empty defaults here (a URL is then replaced by a placeholder in
 * `Shell`, which a cache-only image loader misses, as for any unknown URL).
 */
@KeliverFrameFoldApi
public class RecordingRules(
  public val replayableWidgets: Set<Int> = emptySet(),
  public val replayableModifiers: Set<Int> = emptySet(),
  public val scrollingWidgets: Set<Int> = emptySet(),
  public val scrollingModifiers: Set<Int> = emptySet(),
  public val blockingWidgetProperties: Map<Int, Set<Int>> = emptyMap(),
  public val blockingModifierProperties: Map<Int, Set<String>> = emptyMap(),
  public val textWidgetProperties: Map<Int, Set<Int>> = emptyMap(),
  public val textModifierProperties: Map<Int, Set<String>> = emptyMap(),
  public val neverCachedWidgetProperties: Map<Int, Set<Int>> = emptyMap(),
  public val neverCachedModifierProperties: Map<Int, Set<String>> = emptyMap(),
  public val urlWidgetProperties: Map<Int, Set<Int>> = emptyMap(),
  public val urlModifierProperties: Map<Int, Set<String>> = emptyMap(),
  public val positionKeyWidgetProperties: Map<Int, Set<Int>> = emptyMap(),
  public val positionKeyModifierProperties: Map<Int, Set<String>> = emptyMap(),
) {
  /** The lists [ChangeFolder] reads. */
  public fun foldRules(): FrameFoldRules = FrameFoldRules(
    replayableWidgets = replayableWidgets,
    replayableModifiers = replayableModifiers,
    textWidgetProperties = textWidgetProperties,
    textModifierProperties = textModifierProperties,
    neverCachedWidgetProperties = neverCachedWidgetProperties,
    neverCachedModifierProperties = neverCachedModifierProperties,
    urlWidgetProperties = urlWidgetProperties,
    urlModifierProperties = urlModifierProperties,
    positionKeyWidgetProperties = positionKeyWidgetProperties,
    positionKeyModifierProperties = positionKeyModifierProperties,
  )
}

/**
 * NF5 A3c: the only writer of the cached first frame (docs/SHOW_BEFORE_COMPUTE.md 2.3; I1, I11). It
 * watches one content's change batches, folds them with [ChangeFolder], and writes to [store] once,
 * at the first `ready` edge, if and only if the content is in a clean state. Otherwise it records why
 * not, as [listener]'s `writeSkipped(reason)` and in [recording].
 *
 * **Feeding it, in the order a batch travels** (all on the UI dispatcher):
 * 1. [batchReceived] with the guest's raw batch, before `HostProtocolAdapter` gets it;
 * 2. the adapter applies the batch;
 * 3. [batchApplied]: the recorder reads the marker's `ready`, and on the first edge asks the host's
 *    widgets (scrollers, text) and decides. [onBatch] does 1 to 3 for a sink that applies at once.
 *
 * Input comes from the root observer through [inputSeen] and from the content's event sink, wrapped by
 * [eventSink]. A4a wires both into `TreehouseContent`; this class has no lifecycle hook, so nothing is
 * ever written at `ON_STOP` or at any other time but the first `ready` edge.
 *
 * **The decision** (first failure wins; every reason is a fixed code):
 * - `noMark`: the content's first batch carries no `cachedFrame` marker (the release dropped the
 *   opt-in), `inconsistent`: a batch the fold could not follow;
 * - `multipleMarks`: not exactly one node carries the marker (hidden entries count);
 * - `notVisible`: the content is hidden ([contentVisible]) or the marked node is in a hidden entry;
 * - `notRootChild`: the marked node is not a direct child of the root;
 * - `navigatedInside`: [navigation] says a retained container in the marked subtree shows another
 *   than its first entry;
 * - `input`: [inputSeen], or an event that is not a report ([reportEvents]), came before the edge;
 * - `noScrollOrigin:<tag>`: a node the rules list as scrolling has a widget that is not
 *   [ScrollOrigin] (fail closed); `notAtOrigin`: one is not at its origin;
 * - `notReplayable:widget:<tag>` or `notReplayable:modifier:<tag>`: the fold's, a widget or modifier
 *   the rules do not list (a dialog, a sheet, a skeleton), `notCachedRoot`;
 * - `refreshing`: a `@BlocksFrameWhenTrue` property is true;
 * - `notMeasured:<tag>`: in `Shell`, a widget with a text to replace is not [TextLines] (fail closed);
 * - `sizeCap`, `noSession`, `sessionChanged`, `io`: the store's refusals.
 *
 * Once it has decided (or the first batch showed it never can) it drops the fold and ignores
 * everything after, so a content that lives for hours pays nothing.
 */
@KeliverFrameFoldApi
public class FrameRecorder(
  private val rules: RecordingRules,
  private val store: FrameStore,
  private val cacheName: String,
  /** Read at the edge: route, configuration and variant are the ones in force then. */
  private val key: () -> FrameKey,
  private val widgets: HostedWidgets,
  entryPolicy: HiddenEntryPolicy = HiddenEntryPolicy.NothingHidden,
  private val navigation: RetainedNavigation = RetainedNavigation.NeverNavigated,
  private val contentVisible: () -> Boolean = { true },
  /** Per widget tag, the event tags that are reports and not input (the lazy layouts' viewport reports). */
  private val reportEvents: Map<Int, Set<Int>> = emptyMap(),
  private val listener: FrameRecorderListener = FrameRecorderListener.None,
) {
  private val measured = MeasuredText()
  private var folder: ChangeFolder? = ChangeFolder(rules.foldRules(), entryPolicy, measured)
  private var receivedAny = false
  private var pending = false
  private var input = false

  /** What the recorder did, or null while it has not decided. */
  public var recording: FrameRecording? = null
    private set

  /** True once the recorder has decided and stopped watching. */
  public val finished: Boolean get() = recording != null

  /** Feeds the guest's raw batch, before the adapter applies it. */
  public fun batchReceived(changes: List<Change>) {
    val folder = folder ?: return
    folder.apply(changes)
    pending = true
  }

  /** The adapter has applied the batch given to [batchReceived]: look for the `ready` edge. */
  public fun batchApplied() {
    val folder = folder ?: return
    if (!pending) return
    pending = false
    val first = !receivedAny
    receivedAny = true

    if (folder.isInconsistent) return finish("inconsistent")
    val marks = folder.marks()
    if (marks.isEmpty()) {
      if (first) finish("noMark")
      return
    }
    if (marks.none { it.ready }) return
    finish(decide(folder, marks))
  }

  /** [batchReceived], [apply], [batchApplied], for a sink that applies a batch before it returns. */
  public inline fun onBatch(changes: List<Change>, apply: () -> Unit) {
    batchReceived(changes)
    apply()
    batchApplied()
  }

  /** A touch, scroll, focus, key or text input has reached the content (2.3, condition 4). */
  public fun inputSeen() {
    if (!finished) input = true
  }

  /**
   * [delegate] wrapped so that every event the content sends before the edge counts as input, except
   * the ones [reportEvents] lists (2.3, condition 4: accessibility actions send events and not touches).
   */
  public fun eventSink(delegate: UiEventSink): UiEventSink = UiEventSink { event ->
    if (!finished && !isReport(event)) input = true
    delegate.sendEvent(event)
  }

  private fun isReport(event: UiEvent): Boolean {
    if (reportEvents.isEmpty()) return false
    val folder = folder ?: return false
    val protocol = event.toProtocol()
    val widgetTag = folder.widgetTagOf(protocol.id) ?: return false
    return protocol.tag.value in reportEvents[widgetTag.value].orEmpty()
  }

  private fun finish(reason: String) = finish(Decision.Skip(reason))

  private fun finish(decision: Decision) {
    folder = null
    measured.clear()
    when (decision) {
      is Decision.Write -> {
        recording = FrameRecording.Written(decision.bytes)
        listener.written(decision.bytes)
      }

      is Decision.Skip -> {
        recording = FrameRecording.Skipped(decision.reason)
        listener.writeSkipped(decision.reason)
      }
    }
  }

  private sealed interface Decision {
    class Write(val bytes: Int) : Decision

    class Skip(val reason: String) : Decision
  }

  private fun decide(folder: ChangeFolder, marks: List<MarkedNode>): Decision {
    if (marks.size > 1) return Decision.Skip("multipleMarks")
    val mark = marks.single()
    if (!contentVisible() || !mark.shown) return Decision.Skip("notVisible")
    if (!mark.rootChild) return Decision.Skip("notRootChild")

    val subtree = folder.shownSubtree(mark.id)
    for (node in subtree) {
      for (tag in node.childrenTags) {
        if (navigation.isNavigatedInside(node, tag)) return Decision.Skip("navigatedInside")
      }
    }

    if (input) return Decision.Skip("input")

    val hosted = widgets.widgetsUnder(mark.id)
    for (node in subtree) {
      if (!scrolls(node)) continue
      val origin = hosted[node.id.value] as? ScrollOrigin
        ?: return Decision.Skip("noScrollOrigin:${node.widgetTag.value}")
      if (!origin.atOrigin) return Decision.Skip("notAtOrigin")
    }

    measured.widgets = hosted
    val result = folder.fold()
    if (result is FoldResult.Blocked) return Decision.Skip(result.reason)
    result as FoldResult.Folded

    if (subtree.any { blocksFrame(it) }) return Decision.Skip("refreshing")
    measured.unmeasuredTag?.let { return Decision.Skip("notMeasured:$it") }

    return when (
      val write = store.write(cacheName, key(), result.snapshot, result.content, result.maxAgeMillis)
    ) {
      is FrameWrite.Stored -> Decision.Write(write.bytes)
      is FrameWrite.Rejected -> Decision.Skip(
        when (write.reason) {
          FrameReject.Closed -> "noSession"
          FrameReject.TooLarge -> "sizeCap"
          FrameReject.SessionChanged -> "sessionChanged"
          FrameReject.Io -> "io"
        },
      )
    }
  }

  private fun scrolls(node: FoldNode): Boolean =
    node.widgetTag.value in rules.scrollingWidgets ||
      node.modifierElements.any { it.tag.value in rules.scrollingModifiers }

  /** A `@BlocksFrameWhenTrue` property of the node (or of one of its modifiers) is true. */
  private fun blocksFrame(node: FoldNode): Boolean {
    for (propertyTag in rules.blockingWidgetProperties[node.widgetTag.value].orEmpty()) {
      if (node.property(propertyTag) == JsonPrimitive(true)) return true
    }
    for (element in node.modifierElements) {
      val names = rules.blockingModifierProperties[element.tag.value] ?: continue
      val value = element.value as? JsonObject ?: continue
      if (names.any { value[it] == JsonPrimitive(true) }) return true
    }
    return false
  }

  /** The fold's window onto the widgets' `TextLines`, and which widget could not say. */
  private inner class MeasuredText : TextLineShapes {
    var widgets: Map<Int, Widget<*>> = emptyMap()
    var unmeasuredTag: Int? = null

    fun clear() {
      widgets = emptyMap()
    }

    override fun lineLengths(nodeId: Id, propertyTag: Int, text: String): List<Int>? {
      val widget = widgets[nodeId.value]
      val lines = widget as? TextLines
      if (lines == null) {
        if (unmeasuredTag == null) unmeasuredTag = folder?.widgetTagOf(nodeId)?.value ?: -1
        return null
      }
      return lines.lines(propertyTag)?.map { it.characterCount }
    }
  }
}
