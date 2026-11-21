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
package dev.keliver.nav

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposeNode
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import dev.keliver.Modifier
import dev.keliver.compose.ExperimentalKeliverHostFeaturesApi
import dev.keliver.compose.LocalHostFeatures
import dev.keliver.compose.LocalOnBackPressedDispatcher
import dev.keliver.ui.Cancellable
import dev.keliver.ui.OnBackPressedCallback
import dev.keliver.ui.OnBackPressedDispatcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * NF3's press signal in keliver-nav: [KeliverNavController.prepare] under [KeliverNavHost] (the
 * preparation folded into the retained stack's path, plain and host frames), and [prepareOnPress]'s
 * gate on the host's feature (D2).
 */
@OptIn(ExperimentalKeliverNavRetentionApi::class, ExperimentalKeliverHostFeaturesApi::class)
class KeliverNavPressTest {
  private class Node(val name: String) {
    val children = mutableListOf<Node>()
    var visible = true
    var hostBack = false

    fun dump(): String =
      name + (if (!visible) "(hidden)" else "") +
        (if (children.isEmpty()) "" else children.joinToString(",", "[", "]") { it.dump() })
  }

  private class Counts {
    var inserts = 0
    var moves = 0
    val compositions = mutableMapOf<String, Int>()
    val disposed = mutableListOf<String>()

    fun reset() {
      inserts = 0
      moves = 0
      compositions.clear()
      disposed.clear()
    }
  }

  private class TestApplier(root: Node, private val counts: Counts) : AbstractApplier<Node>(root) {
    override fun insertTopDown(index: Int, instance: Node) {
      current.children.add(index, instance)
      counts.inserts++
    }

    override fun insertBottomUp(index: Int, instance: Node) = Unit

    override fun remove(index: Int, count: Int) {
      current.children.remove(index, count)
    }

    override fun move(from: Int, to: Int, count: Int) {
      current.children.move(from, to, count)
      counts.moves++
    }

    override fun onClear() {
      root.children.clear()
    }
  }

  private object PlainFrame : KeliverNavFrame {
    @Composable
    override fun Stack(content: @Composable () -> Unit) {
      ComposeNode<Node, TestApplier>(factory = { Node("stack") }, update = {}, content = content)
    }

    @Composable
    override fun Entry(visible: Boolean, content: @Composable () -> Unit) {
      ComposeNode<Node, TestApplier>(
        factory = { Node("entry") },
        update = { set(visible) { this.visible = it } },
        content = content,
      )
    }
  }

  /** A host container: the host shows the last entry, as gate A's `ExtraRetained` does. */
  private object HostFrame : KeliverNavHostFrame {
    @Composable
    override fun HostStack(backHandled: Int, onBack: (entryId: String) -> Unit, content: @Composable () -> Unit) {
      ComposeNode<Node, TestApplier>(factory = { Node("host") }, update = {}, content = content)
    }

    @Composable
    override fun HostEntry(entryId: String, hostBack: Boolean, content: @Composable () -> Unit) {
      ComposeNode<Node, TestApplier>(
        factory = { Node("e") },
        update = { set(hostBack) { this.hostBack = it } },
        content = content,
      )
    }

    @Composable
    override fun Stack(content: @Composable () -> Unit) = HostStack(0, {}, content)

    @Composable
    override fun Entry(visible: Boolean, content: @Composable () -> Unit) = HostEntry("", false, content)
  }

  private class Harness(val scope: TestScope, counts: Counts) {
    val root = Node("root")
    private val clock = BroadcastFrameClock()
    private val recomposer = androidx.compose.runtime.Recomposer(scope.coroutineContext + clock)
    private val composition = Composition(TestApplier(root, counts), recomposer)
    private var frame = 0L

    init {
      scope.backgroundScope.launch(clock) { recomposer.runRecomposeAndApplyChanges() }
    }

    fun setContent(content: @Composable () -> Unit) {
      composition.setContent {
        CompositionLocalProvider(LocalOnBackPressedDispatcher provides NoBack, content = content)
      }
      settle()
    }

    fun settle() {
      repeat(10) {
        Snapshot.sendApplyNotifications()
        scope.runCurrent()
        frame += 16_000_000L
        clock.sendFrame(frame)
        scope.runCurrent()
      }
    }

    fun close() {
      composition.dispose()
      recomposer.cancel()
    }
  }

  private fun hostOf(
    frame: KeliverNavFrame?,
    depth: Int = 1,
    block: TestScope.(Harness, KeliverNavController<String>, Counts) -> Unit,
  ) = runTest {
    val counts = Counts()
    val harness = Harness(this, counts)
    val nav = KeliverNavController("home")
    val retention = frame?.let { KeliverNavRetention(it, depth) }
    val content: @Composable (String) -> Unit = { route ->
      counts.compositions[route] = (counts.compositions[route] ?: 0) + 1
      DisposableEffect(route) { onDispose { counts.disposed += route } }
      ComposeNode<Node, TestApplier>(factory = { Node(route) }, update = {})
    }
    harness.setContent { KeliverNavHost(nav, retention, content) }
    try {
      block(harness, nav, counts)
    } finally {
      harness.close()
    }
  }

  @Test
  fun theControllersPreparedRouteIsComposedHiddenAndTheTapShowsIt() = hostOf(PlainFrame) { harness, nav, counts ->
    counts.reset()
    nav.prepare("detail")
    harness.settle()
    assertEquals("root[stack[entry[home],entry(hidden)[detail]]]", harness.root.dump())
    assertEquals(mapOf("detail" to 1), counts.compositions)
    val preparedId = nav.prepared!!.id

    counts.reset()
    nav.navigate("detail")
    harness.settle()
    assertEquals("root[stack[entry(hidden)[home],entry[detail]]]", harness.root.dump())
    assertEquals(emptyMap(), counts.compositions, "the tap composes nothing")
    assertEquals(0, counts.inserts, "and creates nothing")
    assertEquals(preparedId, nav.entries.last().id, "the prepared entry is the one pushed")
    assertNull(nav.prepared)
  }

  @Test
  fun aCancelledPreparationIsDisposed() = hostOf(PlainFrame) { harness, nav, counts ->
    nav.prepare("detail")
    harness.settle()
    counts.reset()
    nav.cancelPreparation("other")
    harness.settle()
    assertEquals(emptyList(), counts.disposed, "another route's cancel changes nothing")
    nav.cancelPreparation("detail")
    harness.settle()
    assertEquals("root[stack[entry[home]]]", harness.root.dump())
    assertEquals(listOf("detail"), counts.disposed)
  }

  @Test
  fun aTapThatOpensAnotherRouteOrABackDropsThePreparation() = hostOf(PlainFrame) { harness, nav, counts ->
    nav.prepare("detail")
    harness.settle()
    counts.reset()
    nav.navigate("other")
    harness.settle()
    assertEquals(listOf("detail"), counts.disposed)
    assertEquals(mapOf("other" to 1), counts.compositions)
    assertNull(nav.prepared)

    nav.prepare("detail")
    harness.settle()
    counts.reset()
    nav.pop()
    harness.settle()
    assertEquals(listOf("detail", "other").sorted(), counts.disposed.sorted())
    assertEquals(listOf("home"), nav.backstack)
  }

  @Test
  fun withoutARetentionNothingIsComposedAhead() = hostOf(null) { harness, nav, counts ->
    counts.reset()
    nav.prepare("detail")
    harness.settle()
    assertEquals("root[home]", harness.root.dump())
    assertEquals(emptyMap(), counts.compositions)
    nav.navigate("detail")
    harness.settle()
    assertEquals("root[detail]", harness.root.dump())
    assertEquals(mapOf("detail" to 1), counts.compositions)
  }

  @Test
  fun onAHostContainerThePreparedEntryGoesUnderTheWindowAndMovesUpAtTheTap() = hostOf(HostFrame) { harness, nav, counts ->
    nav.navigate("list")
    harness.settle()
    val container = harness.root.children.single()
    assertEquals("host[e[home],e[list]]", container.dump())
    assertTrue(container.children[1].hostBack, "list: the host may go back to home")

    counts.reset()
    nav.prepare("detail")
    harness.settle()
    // First: the host shows the last entry, and goes back only to the one under the shown one.
    assertEquals("host[e[detail],e[home],e[list]]", container.dump())
    assertEquals(listOf(false, false, true), container.children.map { it.hostBack })

    counts.reset()
    nav.navigate("detail")
    harness.settle()
    assertEquals("host[e[list],e[detail]]", container.dump(), "depth 1: home leaves, detail moves to the top")
    assertEquals(emptyMap(), counts.compositions, "moved, not composed again")
    assertEquals(0, counts.inserts)
    assertEquals(1, counts.moves)
  }

  @Test
  fun aPreparedStackOnAHostContainerNeverShowsThePreparedEntry() = runTest {
    val counts = Counts()
    val harness = Harness(this, counts)
    val preparation = KeliverNavPreparation<String>()
    var keys by mutableStateOf(listOf("home"))
    harness.setContent {
      KeliverPreparedStack(keys, preparation, HostFrame, depth = 0) { key ->
        ComposeNode<Node, TestApplier>(factory = { Node(key) }, update = {})
      }
    }
    try {
      preparation.prepare("detail")
      harness.settle()
      val container = harness.root.children.single()
      assertEquals("host[e[detail],e[home]]", container.dump())
      counts.reset()
      keys = listOf("home", "detail")
      harness.settle()
      assertEquals("host[e[detail]]", container.dump())
      assertEquals(0, counts.inserts)
    } finally {
      harness.close()
    }
  }

  /** A schema's press signal that records what it was given. */
  private class RecordingPress(override val hostFeature: String = "test.press@1") : KeliverNavPress {
    var onPress: (() -> Unit)? = null
    var onCancel: (() -> Unit)? = null

    override fun pressSignal(modifier: Modifier, onPress: () -> Unit, onCancel: () -> Unit): Modifier {
      this.onPress = onPress
      this.onCancel = onCancel
      return modifier then Marker
    }
  }

  private object Marker : Modifier.UnscopedElement

  private object NoBack : OnBackPressedDispatcher {
    override fun addCallback(onBackPressedCallback: OnBackPressedCallback): Cancellable = object : Cancellable {
      override fun cancel() = Unit
    }
  }

  @Test
  fun prepareOnPressSendsTheSignalOnlyToAHostThatDeclaresIt() = runTest {
    val preparation = KeliverNavPreparation<String>()
    val press = RecordingPress()
    var captured: Modifier? = null
    run {
      val harness = Harness(this, Counts())
      var features by mutableStateOf(emptySet<String>())
      var provided: KeliverNavPress? by mutableStateOf(null)
      harness.setContent {
        CompositionLocalProvider(LocalKeliverNavPress provides provided, LocalHostFeatures provides features) {
          captured = Modifier.prepareOnPress(preparation, "detail")
        }
      }
      assertSame(Modifier, captured, "no press signal provided: nothing added")

      provided = press
      harness.settle()
      assertSame(Modifier, captured, "a host without the feature (older than the signal): nothing sent")
      assertNull(press.onPress)

      features = setOf("test.press@1")
      harness.settle()
      assertSame(Marker, captured, "a host that declares it: the schema's modifier")
      press.onPress!!()
      assertEquals("detail", preparation.prepared)
      press.onCancel!!()
      assertNull(preparation.prepared)
      harness.close()
    }
  }

  /** What the row on "home" carries: the route it prepares, and whether it is there at all. */
  private class Row {
    var target by mutableStateOf("detail")
    var shown by mutableStateOf(true)
  }

  /** [KeliverNavHost] whose "home" has one row with [prepareOnPress], on a host that declares the signal. */
  private fun pressHost(block: TestScope.(Harness, KeliverNavController<String>, Counts, RecordingPress, Row) -> Unit) = runTest {
    val counts = Counts()
    val harness = Harness(this, counts)
    val nav = KeliverNavController("home")
    val press = RecordingPress()
    val row = Row()
    harness.setContent {
      CompositionLocalProvider(LocalKeliverNavPress provides press, LocalHostFeatures provides setOf(press.hostFeature)) {
        KeliverNavHost(nav, KeliverNavRetention(PlainFrame, depth = 1)) { route ->
          counts.compositions[route] = (counts.compositions[route] ?: 0) + 1
          DisposableEffect(route) { onDispose { counts.disposed += route } }
          ComposeNode<Node, TestApplier>(factory = { Node(route) }, update = {})
          if (route == "home" && row.shown) {
            Modifier.prepareOnPress(nav, row.target)
          }
        }
      }
    }
    try {
      block(harness, nav, counts, press, row)
    } finally {
      harness.close()
    }
  }

  @Test
  fun theCancelAfterAClickThatNavigatedChangesNothing() = pressHost { harness, nav, counts, press, _ ->
    // The host sends press, click, cancel: the cancel comes after the click has navigated.
    press.onPress!!()
    harness.settle()
    assertEquals(1, counts.compositions["detail"])
    val preparedId = nav.prepared!!.id
    counts.reset()
    nav.navigate("detail")
    press.onCancel!!()
    harness.settle()
    assertEquals(listOf("home", "detail"), nav.backstack)
    assertEquals(preparedId, nav.entries.last().id, "the prepared entry was pushed")
    assertEquals(emptyMap(), counts.compositions, "nothing composed again")
    assertEquals(emptyList(), counts.disposed, "nothing dropped")
    assertEquals(0, counts.inserts)
  }

  @Test
  fun aClickThatDoesNotNavigateIsFollowedByTheCancel() = pressHost { harness, nav, counts, press, _ ->
    // A long press, a disabled click, a "log in first" click: the host's cancel at the end drops it.
    press.onPress!!()
    harness.settle()
    counts.reset()
    press.onCancel!!()
    harness.settle()
    assertNull(nav.prepared)
    assertEquals(listOf("detail"), counts.disposed)
    assertEquals(listOf("home"), nav.backstack)
  }

  @Test
  fun theCancelDropsTheRouteThatWasPressedEvenIfTheRowChanged() = pressHost { harness, nav, counts, press, row ->
    press.onPress!!()
    harness.settle()
    assertEquals("detail", nav.prepared?.route)
    // The row now opens another route (its data refreshed mid-press).
    row.target = "other"
    harness.settle()
    counts.reset()
    press.onCancel!!()
    harness.settle()
    assertNull(nav.prepared, "the pressed route was cancelled, not the row's new one")
    assertEquals(listOf("detail"), counts.disposed)
    // The next press prepares the new route.
    press.onPress!!()
    harness.settle()
    assertEquals("other", nav.prepared?.route)
  }

  @Test
  fun aRowRemovedMidPressCancelsItsPreparation() = pressHost { harness, nav, counts, press, row ->
    press.onPress!!()
    harness.settle()
    assertEquals("detail", nav.prepared?.route)
    counts.reset()
    // The list refreshed: the row left the composition while the finger was down. The host's cancel
    // would go to a node the guest no longer has.
    row.shown = false
    harness.settle()
    assertNull(nav.prepared)
    assertEquals(listOf("detail"), counts.disposed)
    // A row removed without a press open cancels nothing (another row's preparation stays).
    row.shown = true
    harness.settle()
    nav.prepare("elsewhere")
    harness.settle()
    row.shown = false
    harness.settle()
    assertEquals("elsewhere", nav.prepared?.route)
  }

  @Test
  fun aPreparedStackKeepsTheCompositionWhenTheCancelArrivesWithThePush() = runTest {
    val counts = Counts()
    val harness = Harness(this, counts)
    val preparation = KeliverNavPreparation<String>()
    var keys by mutableStateOf(listOf("home"))
    harness.setContent {
      KeliverPreparedStack(keys, preparation, PlainFrame, depth = 1) { key ->
        counts.compositions[key] = (counts.compositions[key] ?: 0) + 1
        DisposableEffect(key) { onDispose { counts.disposed += key } }
        ComposeNode<Node, TestApplier>(factory = { Node(key) }, update = {})
      }
    }
    try {
      preparation.prepare("detail")
      harness.settle()
      counts.reset()
      // Click (the app pushes) and the host's cancel, in one frame.
      keys = listOf("home", "detail")
      preparation.cancel("detail")
      harness.settle()
      assertEquals("root[stack[entry(hidden)[home],entry[detail]]]", harness.root.dump())
      assertEquals(emptyMap(), counts.compositions, "the prepared composition is the one shown")
      assertEquals(emptyList(), counts.disposed)
      assertEquals(0, counts.inserts)
    } finally {
      harness.close()
    }
  }
}
