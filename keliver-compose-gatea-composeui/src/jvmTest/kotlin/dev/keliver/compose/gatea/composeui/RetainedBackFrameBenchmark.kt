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

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import dev.keliver.compose.foundation.background
import dev.keliver.compose.foundation.clickable
import dev.keliver.compose.foundation.layout.Box
import dev.keliver.compose.foundation.layout.Column
import dev.keliver.compose.foundation.layout.Row
import dev.keliver.compose.foundation.layout.fillMaxSize
import dev.keliver.compose.foundation.layout.fillMaxWidth
import dev.keliver.compose.foundation.layout.height
import dev.keliver.compose.foundation.layout.padding
import dev.keliver.compose.foundation.layout.width
import dev.keliver.compose.foundation.lazy.LazyColumn
import dev.keliver.compose.foundation.lazy.items
import dev.keliver.compose.foundation.shape.RoundedCornerShape
import dev.keliver.compose.gatea.compose.ExtraRetained
import dev.keliver.compose.gatea.compose.ExtraRetainedEntry
import dev.keliver.compose.material3.Text
import dev.keliver.compose.ui.Alignment
import dev.keliver.compose.ui.Modifier
import dev.keliver.compose.ui.graphics.Color
import dev.keliver.compose.ui.text.font.FontWeight
import dev.keliver.compose.ui.text.style.TextOverflow
import dev.keliver.compose.ui.unit.dp
import dev.keliver.compose.ui.unit.sp
import dev.keliver.nav.ExperimentalKeliverNavRetentionApi
import dev.keliver.nav.KeliverNavHostFrame
import dev.keliver.nav.KeliverNavRetention
import dev.keliver.nav.KeliverRetainedStack
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * NF1, back on the heavy screen: the host's frame that shows a retained entry after a back press it
 * handles itself (gate A's `ExtraRetained`), timed on the JVM. The three-way prototype's list and
 * its two details (the simple one and the `heavy` one, 102 widgets) are ported here on the same
 * generated guest API, so the host gets the same widget tree as on the phone, through the real
 * protocol. Each back is pressed with the guest held (as on the phone, where the guest's answer
 * comes about 150 ms later), and the one frame after the press is timed: recomposition, measure,
 * layout and draw, all on the host.
 *
 * The numbers are the Mac's (a JIT-warmed JVM, Skia on the CPU): relative only. They are written to
 * `build/retained-back-frame.txt` and to the test's output. Nothing is asserted on time (CI machines
 * vary); the check that the frame does not recompose the entries is
 * [RetainedHostBackTest.goingBackRecomposesNeitherEntrysContent].
 *
 * A plain `jvmTest` run (CI's included) presses back 5 times with no warm-up: it checks the path
 * runs, and its numbers mean nothing. A measurement passes system properties on the Gradle command
 * line; the module's `jvmTest` task forwards every `keliver.retainedBack.*` one to the test JVM:
 * - `-Dkeliver.retainedBack.measured=60`: 40 warm-up backs, then 60 timed (the documented table);
 * - `-Dkeliver.retainedBack.measured=3000 -Dkeliver.retainedBack.sample=true`: a profiler's run,
 *   which adds per-method sample counts of the timed frame to the same file.
 *
 * On a phone, the three-way benchmark's `firstDraw` mark (a `ViewTreeObserver.OnDrawListener`) runs
 * after the frame's recomposition and apply but before `AndroidComposeView` measures and lays out
 * (which, when the root keeps its size, it does inside its own draw): of these phases it sees only
 * recomposition and apply.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalKeliverNavRetentionApi::class)
class RetainedBackFrameBenchmark {
  private object HostFrame : KeliverNavHostFrame {
    private val Fill = Modifier.fillMaxSize()

    @Composable
    override fun HostStack(backHandled: Int, onBack: (entryId: String) -> Unit, content: @Composable () -> Unit) {
      ExtraRetained(backHandled = backHandled, onBack = onBack, modifier = Fill) { content() }
    }

    @Composable
    override fun HostEntry(entryId: String, hostBack: Boolean, content: @Composable () -> Unit) {
      ExtraRetainedEntry(key = entryId, hostBack = hostBack, modifier = Fill) { content() }
    }

    @Composable
    override fun Stack(content: @Composable () -> Unit) = HostStack(0, {}, content)

    @Composable
    override fun Entry(visible: Boolean, content: @Composable () -> Unit) = HostEntry("", false, content)
  }

  private class Item(val id: Int, val title: String, val subtitle: String, val amount: String, val status: String)

  private val items = List(20) { i ->
    Item(i, "Order ${1000 + i}", "Customer $i, City ${i % 7}", "${100 + i * 7}.50", listOf("Paid", "Pending", "Shipped", "Refunded")[i % 4])
  }

  private var keys by mutableStateOf(listOf("list"))

  @Test
  fun simpleScreen() = measure(heavy = false)

  @Test
  fun heavyScreen() = measure(heavy = true)

  private fun measure(heavy: Boolean) = runComposeUiTest {
    val back = PressDispatcher()
    val tap = EventTap()
    val host = guestOnHost(tap, back) {
      KeliverRetainedStack(keys, KeliverNavRetention(HostFrame, depth = 1), onHostBack = { key -> keys = keys.subList(0, keys.indexOf(key) + 1) }) { key ->
        // Each screen is one column, as the prototype's entry content.
        if (key == "list") ListScreen() else if (heavy) HeavyDetailScreen(items[3]) else DetailScreen(items[3])
      }
    }
    setContent { CompositionLocalProvider(LocalHostBackDispatcher provides back) { host.root.Render(null) } }
    sync(host)

    // A plain jvmTest run (CI included) does 5 backs: a smoke run, not a measurement. The numbers in
    // docs/THREE_WAY_BENCHMARK.md are -Dkeliver.retainedBack.measured=60 (40 warm-up backs, then 60).
    val measuredProperty = System.getProperty("keliver.retainedBack.measured")?.toInt()
    val warmup = if (measuredProperty == null) 0 else 40
    val measured = measuredProperty ?: 5
    val frames = mutableListOf<Double>()
    val sampler = if (System.getProperty("keliver.retainedBack.sample") != null) FrameSampler(runOnUiThread { Thread.currentThread() }) else null
    repeat(warmup + measured) { i ->
      keys = keys + "detail"
      sync(host)
      tap.hold = true
      mainClock.autoAdvance = false
      runOnIdle { assertTrue(back.press(), "the host takes the press") }
      val began = System.nanoTime()
      sampler?.inFrame = i >= warmup
      mainClock.advanceTimeByFrame()
      sampler?.inFrame = false
      val ms = (System.nanoTime() - began) / 1e6
      mainClock.autoAdvance = true
      if (i >= warmup) frames += ms
      tap.release()
      sync(host)
    }
    frames.sort()
    val line = "retained back frame, ${if (heavy) "heavy" else "simple"} screen: median %.3f ms, p90 %.3f, min %.3f, max %.3f (n=%d)%s".format(
      frames[frames.size / 2], frames[frames.size * 9 / 10], frames.first(), frames.last(), frames.size,
      if (measuredProperty == null) " [smoke run, no warm-up: not a measurement]" else "",
    )
    println(line)
    results += line
    sampler?.let { results += it.stop(if (heavy) "heavy" else "simple") }
    writeArtifact("retained-back-frame.txt", results.joinToString("\n", postfix = "\n").encodeToByteArray())
  }

  private fun ComposeUiTest.sync(host: GuestOnHost) {
    repeat(3) {
      Snapshot.sendApplyNotifications()
      host.pump()
      waitForIdle()
    }
  }

  /**
   * A profiler's run only (-Dkeliver.retainedBack.sample): samples [target]'s stack while it is in
   * the timed frame, and reports inclusive counts per method (a sample counts once per method).
   */
  private class FrameSampler(private val target: Thread) {
    @Volatile var inFrame = false

    @Volatile private var running = true
    private val inclusive = HashMap<String, Int>()
    private val leaf = HashMap<String, Int>()
    private var samples = 0
    private val thread = Thread {
      while (running) {
        if (inFrame) {
          val stack = target.stackTrace
          if (inFrame && stack.isNotEmpty()) {
            synchronized(this) {
              samples++
              val first = stack[0]
              leaf.merge("${first.className.substringAfterLast('.')}.${first.methodName}", 1, Int::plus)
              stack.mapTo(HashSet()) { "${it.className.substringAfterLast('.')}.${it.methodName}" }
                .forEach { inclusive.merge(it, 1, Int::plus) }
            }
          }
        }
        Thread.onSpinWait()
      }
    }.apply {
      isDaemon = true
      start()
    }

    fun stop(label: String): String {
      running = false
      thread.join()
      return synchronized(this) {
        buildString {
          appendLine("samples in the frame ($label): $samples")
          appendLine("inclusive:")
          inclusive.entries.sortedByDescending { it.value }.take(500).forEach { appendLine("  ${it.value} ${it.key}") }
          appendLine("leaf:")
          leaf.entries.sortedByDescending { it.value }.take(40).forEach { appendLine("  ${it.value} ${it.key}") }
        }
      }
    }
  }

  /** Android's rule: a press goes to the newest enabled callback. */
  private class PressDispatcher : HostBackDispatcher {
    val callbacks = mutableListOf<dev.keliver.ui.OnBackPressedCallback>()

    override fun onBackPressed() {
      press()
    }

    override fun addCallback(onBackPressedCallback: dev.keliver.ui.OnBackPressedCallback): dev.keliver.ui.Cancellable {
      callbacks += onBackPressedCallback
      return object : dev.keliver.ui.Cancellable {
        override fun cancel() {
          callbacks -= onBackPressedCallback
        }
      }
    }

    fun press(): Boolean {
      val callback = callbacks.lastOrNull { it.isEnabled } ?: return false
      callback.handleOnBackPressed()
      return true
    }
  }

  // The three-way prototype's screens (sample/threeway-guest, ThreewayScreens.kt), on the same API.

  @Composable
  private fun ListScreen() = Screen {
    Header("Orders", onBack = null)
    Row(Modifier.fillMaxWidth().height(48.dp)) {
      Action("Load 20", Modifier.weight(1f)) {}
      Action("Load 1,000", Modifier.weight(1f)) {}
    }
    Text("20 items · request 1", Modifier.fillMaxWidth().height(24.dp).padding(horizontal = 16.dp), color = Muted, fontSize = 13.sp)
    LazyColumn(Modifier.fillMaxSize()) {
      items(items, key = { it.id }) { item -> ItemRow(item) }
    }
  }

  /** The prototype's entry content: one full-size white column. */
  @Composable
  private fun Screen(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().background(Color.White)) { content() }
  }

  @Composable
  private fun ItemRow(item: Item) {
    Row(Modifier.fillMaxWidth().height(72.dp).clickable {}.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f)) {
        Text(item.title, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(item.subtitle, color = Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
      }
      Column(horizontalAlignment = Alignment.End) {
        Text(item.amount, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        StatusChip(item.status)
      }
    }
  }

  @Composable
  private fun StatusChip(status: String) {
    Box(Modifier.padding(top = 4.dp).background(ChipBg, RoundedCornerShape(10.dp)).padding(horizontal = 8.dp, vertical = 2.dp)) {
      Text(status, color = Ink, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
  }

  @Composable
  private fun DetailScreen(item: Item) = Screen {
    Header(item.title, onBack = {})
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(item.amount, Modifier.weight(1f), color = Ink, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        StatusChip(item.status)
      }
      for ((label, value) in listOf("Customer" to item.subtitle, "Ship to" to "1 Main St", "Reference" to "R-1", "Note" to "None")) {
        Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
          Text(label, color = Muted, fontSize = 12.sp)
          Text(value, color = Ink, fontSize = 15.sp)
        }
      }
      Text("Order id ${item.id} · opened 1×", Modifier.padding(top = 16.dp), color = Muted, fontSize = 12.sp)
    }
  }

  /** 102 widgets, as the prototype's `heavy` detail. */
  @Composable
  private fun HeavyDetailScreen(item: Item) = Screen {
    Header(item.title, onBack = {})
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
      Row(Modifier.height(40.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(item.amount, Modifier.weight(1f), color = Ink, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        StatusChip(item.status)
      }
      Row(Modifier.height(28.dp)) {
        for (chip in listOf("Paid", "Shipped", "Refunded", "Pending")) StatusChip(chip)
      }
      for (section in listOf("Order", "Customer", "Shipping", "Payment")) {
        Text(section, Modifier.padding(top = 8.dp), color = Indigo, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        for (row in 1..6) {
          Row(Modifier.fillMaxWidth().height(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("$section $row", Modifier.width(112.dp), color = Muted, fontSize = 13.sp)
            Text("Value ${item.id * row} of $section", color = Ink, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
          }
        }
      }
      Row(Modifier.fillMaxWidth().height(48.dp).padding(top = 8.dp)) {
        for (label in listOf("Call", "Email", "Cancel")) Action(label, Modifier.weight(1f)) {}
      }
      Text("Order id ${item.id} · opened 1×", Modifier.padding(top = 8.dp), color = Muted, fontSize = 12.sp)
    }
  }

  @Composable
  private fun Header(title: String, onBack: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().height(56.dp).background(Indigo), verticalAlignment = Alignment.CenterVertically) {
      if (onBack != null) {
        Box(Modifier.width(72.dp).height(56.dp).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
          Text("Back", color = Color.White, fontSize = 16.sp)
        }
      } else {
        Box(Modifier.width(16.dp).height(56.dp))
      }
      Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
  }

  @Composable
  private fun Action(label: String, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.height(48.dp).padding(4.dp).background(ActionBg, RoundedCornerShape(8.dp)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
      Text(label, color = Indigo, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
  }

  private companion object {
    val Indigo = Color(0xFF3F51B5)
    val Ink = Color(0xFF1C1B1F)
    val Muted = Color(0xFF6B6B73)
    val ActionBg = Color(0xFFE8EAF6)
    val ChipBg = Color(0xFFDFF5E1)

    /** Both screens' lines, in one file (the tests share a JVM). */
    val results = mutableListOf<String>()
  }
}
