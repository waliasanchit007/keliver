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

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier as UiModifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.keliver.compose.foundation.layout.Column
import dev.keliver.compose.foundation.layout.fillMaxSize
import dev.keliver.compose.foundation.lazy.LazyColumn
import dev.keliver.compose.foundation.lazy.items
import dev.keliver.compose.gatea.protocol.host.KeliverComposeGateAHostProtocol
import dev.keliver.compose.material3.Text
import dev.keliver.compose.ui.Modifier as GuestModifier
import dev.keliver.layout.composeui.ComposeUiRedwoodLayoutWidgetFactory
import dev.keliver.lazylayout.composeui.ComposeUiRedwoodLazyLayoutWidgetFactory
import dev.keliver.leaks.LeakDetector
import dev.keliver.material.composeui.ComposeUiKeliverMaterialWidgetFactory
import dev.keliver.material.widget.KeliverMaterialWidgetSystem
import dev.keliver.protocol.Change
import dev.keliver.protocol.ChildrenChange
import dev.keliver.protocol.ChildrenTag
import dev.keliver.protocol.Create
import dev.keliver.protocol.Id
import dev.keliver.protocol.ModifierChange
import dev.keliver.protocol.PropertyChange
import dev.keliver.protocol.PropertyTag
import dev.keliver.protocol.SnapshotChangeList
import dev.keliver.protocol.guest.guestRedwoodVersion
import dev.keliver.protocol.host.HostProtocolAdapter
import dev.keliver.protocol.host.ProtocolMismatchHandler
import dev.keliver.protocol.host.UiChange
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * NF5's go/no-go for the cached first frame (docs/SHOW_BEFORE_COMPUTE.md, appendix): what it costs
 * the HOST to draw a screen from a stored tree, with no guest running. A MEASUREMENT, not the
 * design's code: the fold and the replay here are the smallest stand-ins that answer the question.
 *
 * 1. The guest's batches for the list screen (with `-Dkeliver.nf5.wire=<file>`: the three-way
 *    list's first batch, written by the guest profile in Zipline's QuickJS, `nf5=` runs; without it:
 *    a small list composed here, a smoke run) are folded into one snapshot of the current tree
 *    ([fold]: Create, the last value of each property and modifier, then Adds; Move and Remove
 *    applied, removed subtrees dropped), which [SnapshotChangeList] accepts.
 * 2. The snapshot's JSON is replayed into a fresh [HostProtocolAdapter] with gate A's Compose UI
 *    widgets, and drawn. Its pixels must equal those of a host that applied the guest's batches as
 *    they came (the fold is checked, not assumed).
 * 3. Timed (each step on the UI thread, then one frame): reading the file, decoding it, the
 *    protocol mapping, the adapter's apply, the frame. Events the replayed widgets send (the lazy
 *    list's viewport report) are counted and dropped: no guest is there to take them.
 *
 * Numbers are the Mac's (a JIT-warmed JVM, Skia on the CPU): relative only, written to
 * `build/nf5-cached-frame.txt`. A plain jvmTest run (CI's) is the smoke run: it checks the fold
 * and the replay, times 5 replays with no warm-up, and its numbers mean nothing. A measurement:
 * `-Dkeliver.nf5.wire=<file> -Dkeliver.nf5.measured=40` (forwarded by the module's jvmTest task).
 */
@OptIn(ExperimentalTestApi::class)
class CachedFrameReplayBenchmark {
  // The bridge's format (Zipline's JSON): sealed types as ["type", {...}] arrays. The snapshot is
  // stored in it too.
  private val json = Json { useArrayPolymorphism = true }

  @Test
  fun replayTheListScreenFromAFoldedSnapshot() = runComposeUiTest {
    val wireFile = System.getProperty("keliver.nf5.wire")?.let(::File)
    val measuredProperty = System.getProperty("keliver.nf5.measured")?.toInt()
    val warmup = if (measuredProperty == null) 0 else 40
    val measured = measuredProperty ?: 5
    val protocol = KeliverComposeGateAHostProtocol.create(Json.Default, ProtocolMismatchHandler.Throwing)

    // 1. The guest's batches, as they came over the wire.
    val batches: List<String> = if (wireFile != null) {
      listOf(wireFile.readText())
    } else {
      var loaded by mutableStateOf(false)
      val guest = guestOnHost {
        Column(GuestModifier.fillMaxSize()) {
          Text("Orders")
          if (!loaded) {
            Text("Loading…")
          } else {
            LazyColumn(GuestModifier.fillMaxSize()) {
              items(List(20) { it }) { Text("Order #${1000 + it} · Ceramic mug set") }
            }
          }
        }
      }
      loaded = true
      Snapshot.sendApplyNotifications()
      repeat(3) { guest.pump() }
      // The harness's wire is plain Json: re-encoded as the bridge sends it.
      guest.wire.map { json.encodeToString(ListSerializer(Change.serializer()), Json.decodeFromString(ListSerializer(Change.serializer()), it)) }
    }
    val decoded = batches.map { json.decodeFromString(ListSerializer(Change.serializer()), it) }
    val folded = fold(decoded)
    val snapshotJson = json.encodeToString(SnapshotChangeList.serializer(), SnapshotChangeList(folded))
    val snapshotFile = File.createTempFile("nf5-snapshot", ".json").apply {
      deleteOnExit()
      writeText(snapshotJson)
    }

    // 2. Live (the batches as they came) and replayed (the snapshot), each into a fresh host.
    var dropped = 0
    fun newHost(): Pair<ScopedChildren, HostProtocolAdapter<@androidx.compose.runtime.Composable (UiModifier) -> Unit>> {
      val root = ScopedChildren()
      val material = KeliverMaterialWidgetSystem(
        KeliverMaterial = ComposeUiKeliverMaterialWidgetFactory(coil3.ImageLoader.Builder(coil3.PlatformContext.INSTANCE).build()),
        RedwoodLayout = ComposeUiRedwoodLayoutWidgetFactory(),
        RedwoodLazyLayout = ComposeUiRedwoodLazyLayoutWidgetFactory(),
      )
      return root to HostProtocolAdapter(
        guestVersion = guestRedwoodVersion,
        container = root,
        protocol = protocol,
        widgetSystem = keliverComposeGateAWidgetSystem(material),
        eventSink = { dropped++ },
        leakDetector = LeakDetector.none(),
      )
    }
    // One box, so both are drawn at the same position (anti-aliasing depends on it): first the
    // live host's tree, captured; then each replay.
    var shown by mutableStateOf<ScopedChildren?>(null)
    setContent {
      Box(UiModifier.size(360.dp, 640.dp).testTag("frame")) { shown?.Render(null) }
    }
    var liveApplyMs = 0.0
    runOnUiThread {
      val (root, adapter) = newHost()
      val t = System.nanoTime()
      for (batch in decoded) adapter.sendChanges(batch.mapNotNull { UiChange.fromProtocol(protocol, it) })
      liveApplyMs = (System.nanoTime() - t) / 1e6
      shown = root
    }
    waitForIdle()
    val liveImage = onNodeWithTag("frame").captureToImage()

    // 3. Replays, timed; the drawn result compared with the live host's after each.
    class Times(val readDecode: Double, val apply: Double, val frame: Double) {
      val total get() = readDecode + apply + frame
    }
    val times = ArrayList<Times>()
    var firstReplay: Times? = null
    var differing = -1
    repeat(warmup + measured) { i ->
      runOnUiThread { shown = null }
      waitForIdle()
      mainClock.autoAdvance = false
      var readDecode = 0.0
      var apply = 0.0
      runOnUiThread {
        var t = System.nanoTime()
        val snapshot = json.decodeFromString(SnapshotChangeList.serializer(), snapshotFile.readText())
        val changes = snapshot.changes.mapNotNull { UiChange.fromProtocol(protocol, it) }
        readDecode = (System.nanoTime() - t) / 1e6
        t = System.nanoTime()
        val (root, adapter) = newHost()
        adapter.sendChanges(changes)
        shown = root
        Snapshot.sendApplyNotifications()
        apply = (System.nanoTime() - t) / 1e6
      }
      val t = System.nanoTime()
      mainClock.advanceTimeByFrame()
      val frame = (System.nanoTime() - t) / 1e6
      mainClock.autoAdvance = true
      waitForIdle()
      if (i >= warmup) times += Times(readDecode, apply, frame)
      if (i == 0) firstReplay = Times(readDecode, apply, frame)
      if (i == warmup + measured - 1) {
        val replayedImage = onNodeWithTag("frame").captureToImage()
        differing = differingPixels(liveImage, replayedImage, "nf5/replay-diff.png")
        saveImage("nf5/replayed.png", replayedImage)
      }
    }

    fun List<Double>.median() = sorted()[size / 2]
    fun List<Double>.p90() = sorted()[minOf(size - 1, (size * 9 + 9) / 10 - 1)]
    val wireBytes = batches.sumOf { it.encodeToByteArray().size }
    val snapshotBytes = snapshotJson.encodeToByteArray().size
    val lines = listOf(
      "input: ${wireFile ?: "smoke list composed here"}; ${batches.size} batch(es), ${decoded.sumOf { it.size }} changes, $wireBytes bytes" +
        if (measuredProperty == null) " [smoke run, no warm-up: not a measurement]" else "",
      "folded snapshot: ${folded.size} changes (${folded.count { it is Create }} widgets), $snapshotBytes bytes JSON, ${gzip(snapshotJson).size} bytes gzipped",
      "live apply of the guest's batches (once, cold JIT): ${"%.3f".format(liveApplyMs)} ms",
      firstReplay!!.let { "first replay (JIT barely warm): read+decode %.3f, apply %.3f, frame %.3f, total %.3f ms".format(it.readDecode, it.apply, it.frame, it.total) },
      "replay, n=${times.size} after $warmup warm-up: " +
        listOf("read+decode" to times.map { it.readDecode }, "apply" to times.map { it.apply }, "frame" to times.map { it.frame }, "total" to times.map { it.total })
          .joinToString("; ") { (name, xs) -> "$name median %.3f p90 %.3f".format(xs.median(), xs.p90()) },
      "events from the replayed widgets, dropped: $dropped (over ${warmup + measured} replays)",
      "pixels differing, replayed vs live: $differing",
    )
    lines.forEach(::println)
    writeArtifact("nf5-cached-frame.txt", lines.joinToString("\n", postfix = "\n").encodeToByteArray())
    assertTrue(folded.none { it is ChildrenChange.Move || it is ChildrenChange.Remove })
    assertEquals(0, differing, "the replayed snapshot draws what the live host drew")
  }

  private fun gzip(s: String): ByteArray {
    val out = ByteArrayOutputStream()
    GZIPOutputStream(out).use { it.write(s.encodeToByteArray()) }
    return out.toByteArray()
  }
}

/**
 * Folds change batches into the current tree, as one list a fresh host can apply: per live widget
 * its Create, its properties' last values (first-set order), its last modifier; then its children
 * (recursively) and their Adds, in order. Move and Remove are applied while folding (Move as the
 * Compose applier and [ScopedChildren.move] define it); a Remove that is not a detach drops the
 * subtree. A stand-in for NF5's fold, for the measurement only.
 */
internal fun fold(batches: List<List<Change>>): List<Change> {
  class Node(val create: Create?) {
    val properties = LinkedHashMap<PropertyTag, PropertyChange>()
    var modifier: ModifierChange? = null
    val children = LinkedHashMap<ChildrenTag, MutableList<Id>>()
  }
  val nodes = HashMap<Id, Node>()
  nodes[Id.Root] = Node(null)
  fun drop(id: Id) {
    val node = nodes.remove(id) ?: return
    node.children.values.forEach { it.forEach(::drop) }
  }
  for (batch in batches) {
    for (change in batch) {
      when (change) {
        is Create -> nodes[change.id] = Node(change)
        is PropertyChange -> nodes.getValue(change.id).properties[change.propertyTag] = change
        is ModifierChange -> nodes.getValue(change.id).modifier = change
        is ChildrenChange.Add -> nodes.getValue(change.id).children.getOrPut(change.tag) { ArrayList() }.add(change.index, change.childId)
        is ChildrenChange.Move -> {
          val list = nodes.getValue(change.id).children.getValue(change.tag)
          val moved = (0 until change.count).map { list.removeAt(change.fromIndex) }
          list.addAll(if (change.toIndex > change.fromIndex) change.toIndex - change.count else change.toIndex, moved)
        }
        is ChildrenChange.Remove -> {
          val list = nodes.getValue(change.id).children.getValue(change.tag)
          @Suppress("DEPRECATION")
          repeat(change.count) {
            val removed = list.removeAt(change.index)
            if (!change.detach) drop(removed)
          }
        }
      }
    }
  }
  val out = ArrayList<Change>()
  fun emit(id: Id) {
    val node = nodes.getValue(id)
    node.create?.let(out::add)
    out += node.properties.values
    node.modifier?.let(out::add)
    for ((tag, children) in node.children) {
      children.forEachIndexed { index, child ->
        emit(child)
        out += ChildrenChange.Add(id, tag, child, index)
      }
    }
  }
  emit(Id.Root)
  return out
}
