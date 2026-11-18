/*
 * Copyright (C) 2026 Square, Inc.
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import dev.keliver.compose.BackHandler
import dev.keliver.compose.LocalOnBackPressedDispatcher
import dev.keliver.ui.Cancellable
import dev.keliver.ui.OnBackPressedCallback
import dev.keliver.ui.OnBackPressedDispatcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalKeliverNavRetentionApi::class)
class KeliverNavRetentionTest {
  private sealed interface Route {
    data object Home : Route
    data class Detail(val id: Int) : Route
  }

  /** A node of the test tree; [visible] is set by [TestFrame]'s entries only. */
  private class Node(val name: String) {
    val children = mutableListOf<Node>()
    var visible = true

    fun dump(): String =
      name + (if (!visible) "(hidden)" else "") +
        (if (children.isEmpty()) "" else children.joinToString(",", "[", "]") { it.dump() })
  }

  /** Counts what a host would be sent: node creations (inserts) and property updates. */
  private class Counts {
    var inserts = 0
    var removes = 0
    var visibilityUpdates = 0
    val compositions = mutableMapOf<String, Int>()

    fun reset() {
      inserts = 0
      removes = 0
      visibilityUpdates = 0
      compositions.clear()
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
      counts.removes += count
    }

    override fun move(from: Int, to: Int, count: Int) {
      current.children.move(from, to, count)
    }

    override fun onClear() {
      root.children.clear()
    }
  }

  private class TestFrame(private val counts: Counts) : KeliverNavFrame {
    @Composable
    override fun Stack(content: @Composable () -> Unit) {
      ComposeNode<Node, TestApplier>(factory = { Node("stack") }, update = {}, content = content)
    }

    @Composable
    override fun Entry(visible: Boolean, content: @Composable () -> Unit) {
      ComposeNode<Node, TestApplier>(
        factory = { Node("entry") },
        update = {
          set(visible) {
            this.visible = it
            counts.visibilityUpdates++
          }
        },
        content = content,
      )
    }
  }

  @Composable
  private fun Leaf(name: String) {
    ComposeNode<Node, TestApplier>(factory = { Node(name) }, update = {})
  }

  private fun nameOf(route: Route): String = when (route) {
    Route.Home -> "home"
    is Route.Detail -> "detail${route.id}"
  }

  /** A composition driven by hand: [settle] runs frames until nothing is pending. */
  private class Harness(val scope: TestScope, val counts: Counts) {
    val root = Node("root")
    private val clock = BroadcastFrameClock()
    private val recomposer = Recomposer(scope.coroutineContext + clock)
    private val composition = Composition(TestApplier(root, counts), recomposer)
    private var frame = 0L

    init {
      scope.backgroundScope.launch(clock) { recomposer.runRecomposeAndApplyChanges() }
    }

    fun setContent(content: @Composable () -> Unit) {
      composition.setContent(content)
      settle()
    }

    fun settle() {
      repeat(10) {
        // runCurrent, not advanceUntilIdle: the recomposer runs in backgroundScope, whose tasks
        // advanceUntilIdle does not wait for.
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

  private fun TestScope.navHost(
    retention: (Counts) -> KeliverNavRetention?,
    screen: @Composable (Route, Counts) -> Unit = { route, counts -> Leaf(nameOf(route)) },
  ): Triple<Harness, KeliverNavController<Route>, Counts> {
    val counts = Counts()
    val harness = Harness(this, counts)
    val nav = KeliverNavController<Route>(Route.Home)
    val r = retention(counts)
    harness.setContent {
      KeliverNavHost(nav, r) { route ->
        val name = nameOf(route)
        counts.compositions[name] = (counts.compositions[name] ?: 0) + 1
        screen(route, counts)
      }
    }
    return Triple(harness, nav, counts)
  }

  @Test
  fun backToRetainedEntryComposesNothingAndCreatesNothing() = runTest {
    val (harness, nav, counts) = navHost({ KeliverNavRetention(TestFrame(it)) })
    nav.navigate(Route.Detail(1))
    harness.settle()
    nav.navigate(Route.Detail(2))
    harness.settle()
    assertEquals(
      "root[stack[entry(hidden)[home],entry(hidden)[detail1],entry[detail2]]]",
      harness.root.dump(),
    )

    counts.reset()
    nav.pop()
    harness.settle()
    assertEquals("root[stack[entry(hidden)[home],entry[detail1]]]", harness.root.dump())
    assertEquals(emptyMap(), counts.compositions, "the retained entry must not compose")
    assertEquals(0, counts.inserts, "no node may be created on back")
    assertEquals(1, counts.removes, "only the popped entry's frame (with its subtree) is removed")
    assertEquals(1, counts.visibilityUpdates, "only the shown entry's frame changes")

    counts.reset()
    nav.pop()
    harness.settle()
    assertEquals("root[stack[entry[home]]]", harness.root.dump())
    assertEquals(emptyMap(), counts.compositions)
    assertEquals(0, counts.inserts)
    harness.close()
  }

  @Test
  fun withoutRetentionBackComposesThePreviousScreenAgain() = runTest {
    val (harness, nav, counts) = navHost({ null })
    nav.navigate(Route.Detail(1))
    harness.settle()
    assertEquals("root[detail1]", harness.root.dump())

    counts.reset()
    nav.pop()
    harness.settle()
    assertEquals("root[home]", harness.root.dump())
    assertEquals(mapOf("home" to 1), counts.compositions)
    assertEquals(1, counts.inserts)
    harness.close()
  }

  @Test
  fun depthZeroIsThePlainHost() = runTest {
    val (harness, nav, counts) = navHost({ KeliverNavRetention(TestFrame(it), depth = 0) })
    nav.navigate(Route.Detail(1))
    harness.settle()
    assertEquals("root[detail1]", harness.root.dump())
    counts.reset()
    nav.pop()
    harness.settle()
    assertEquals(mapOf("home" to 1), counts.compositions)
    harness.close()
  }

  @Test
  fun depthLimitEvictsTheOldestAndItsSavedStateComesBack() = runTest {
    var homeCounter: Int? = null
    var bumpHome: () -> Unit = {}
    val (harness, nav, counts) = navHost(
      { KeliverNavRetention(TestFrame(it), depth = 1) },
      screen = { route, _ ->
        if (route == Route.Home) {
          var n by rememberSaveable { mutableStateOf(0) }
          homeCounter = n
          bumpHome = { n++ }
        }
        Leaf(nameOf(route))
      },
    )
    bumpHome()
    harness.settle()
    assertEquals(1, homeCounter)

    nav.navigate(Route.Detail(1))
    harness.settle()
    nav.navigate(Route.Detail(2))
    harness.settle()
    // Depth 1: home (the oldest) left the composition; detail1 is retained.
    assertEquals("root[stack[entry(hidden)[detail1],entry[detail2]]]", harness.root.dump())

    counts.reset()
    nav.pop()
    harness.settle()
    assertEquals(emptyMap(), counts.compositions, "detail1 is retained")
    assertEquals(0, counts.inserts)

    counts.reset()
    nav.pop()
    harness.settle()
    assertEquals("root[stack[entry[home]]]", harness.root.dump())
    assertEquals(mapOf("home" to 1), counts.compositions, "home was evicted, so it composes again")
    assertEquals(1, homeCounter, "its rememberSaveable state is restored")
    harness.close()
  }

  @Test
  fun hiddenEntriesArePausedAndDoNotRunWork() = runTest {
    var requests = 0
    var running = 0
    val lifecycles = mutableMapOf<String, KeliverNavEntryLifecycle>()
    val (harness, nav, counts) = navHost(
      { KeliverNavRetention(TestFrame(it)) },
      screen = { route, _ ->
        val name = nameOf(route)
        val lifecycle = keliverNavEntryLifecycle()
        LaunchedEffect(lifecycle) {
          androidx.compose.runtime.snapshotFlow { lifecycle.value }.collect { lifecycles[name] = it }
        }
        if (route == Route.Home) {
          LaunchedWhileVisible {
            requests++
            running++
            try {
              awaitCancellation()
            } finally {
              running--
            }
          }
        }
        Leaf(name)
      },
    )
    assertEquals(1, requests)
    assertEquals(1, running)
    assertEquals(KeliverNavEntryLifecycle.Visible, lifecycles["home"])

    nav.navigate(Route.Detail(1))
    harness.settle()
    assertEquals(KeliverNavEntryLifecycle.Hidden, lifecycles["home"])
    assertEquals(KeliverNavEntryLifecycle.Visible, lifecycles["detail1"])
    assertEquals(0, running, "the hidden entry's work is cancelled")
    assertEquals(1, requests, "and no new work starts while it is hidden")

    counts.reset()
    nav.pop()
    harness.settle()
    assertEquals(KeliverNavEntryLifecycle.Visible, lifecycles["home"])
    assertEquals(1, running, "shown again, its work runs again")
    assertEquals(2, requests)
    assertEquals(emptyMap(), counts.compositions, "showing it again does not recompose it")
    harness.close()
  }

  @Test
  fun lifecycleOutsideARetainedStackIsVisible() = runTest {
    var seen: KeliverNavEntryLifecycle? = null
    val harness = Harness(this, Counts())
    harness.setContent { seen = keliverNavEntryLifecycle().value }
    assertEquals(KeliverNavEntryLifecycle.Visible, seen)
    harness.close()
  }

  @Test
  fun replaceAllDisposesEveryRetainedEntry() = runTest {
    val disposed = mutableListOf<String>()
    val (harness, nav, _) = navHost(
      { KeliverNavRetention(TestFrame(it)) },
      screen = { route, _ ->
        val name = nameOf(route)
        DisposableEffect(name) { onDispose { disposed += name } }
        Leaf(name)
      },
    )
    nav.navigate(Route.Detail(1))
    harness.settle()
    nav.replaceAll(Route.Detail(9))
    harness.settle()
    assertEquals("root[stack[entry[detail9]]]", harness.root.dump())
    assertEquals(setOf("home", "detail1"), disposed.toSet())
    harness.close()
  }

  @Test
  fun retainedStackWithoutAController() = runTest {
    val counts = Counts()
    val harness = Harness(this, counts)
    var keys by mutableStateOf(listOf("list"))
    val retention = KeliverNavRetention(TestFrame(counts))
    harness.setContent {
      App(keys, retention, counts)
    }
    keys = listOf("list", "detail")
    harness.settle()
    assertEquals("root[stack[entry(hidden)[list],entry[detail]]]", harness.root.dump())

    counts.reset()
    keys = listOf("list")
    harness.settle()
    assertEquals("root[stack[entry[list]]]", harness.root.dump())
    assertEquals(emptyMap(), counts.compositions)
    assertEquals(0, counts.inserts)
    harness.close()
  }

  /**
   * An app that keeps its own navigation state (as the three-way prototype does). It recomposes on
   * every navigation, so Compose re-runs its content lambda (a lambda with captures is updated);
   * the screen inside it must still be skipped.
   */
  @Composable
  private fun App(keys: List<String>, retention: KeliverNavRetention, counts: Counts) {
    KeliverRetainedStack(keys, retention) { key ->
      Screen(key, counts)
    }
  }

  @Composable
  private fun Screen(name: String, counts: Counts) {
    counts.compositions[name] = (counts.compositions[name] ?: 0) + 1
    Leaf(name)
  }

  /** A dispatcher like the host's: the most recently added enabled callback takes the press. */
  private class TestBackDispatcher : OnBackPressedDispatcher {
    val callbacks = mutableListOf<OnBackPressedCallback>()

    override fun addCallback(onBackPressedCallback: OnBackPressedCallback): Cancellable {
      callbacks += onBackPressedCallback
      return object : Cancellable {
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

  @Test
  fun hiddenEntryBackHandlerDoesNotTakeBackPresses() = runTest {
    val presses = mutableListOf<String>()
    val dispatcher = TestBackDispatcher()
    val counts = Counts()
    val harness = Harness(this, counts)
    val nav = KeliverNavController<Route>(Route.Home)
    val retention = KeliverNavRetention(TestFrame(counts))
    harness.setContent {
      CompositionLocalProvider(LocalOnBackPressedDispatcher provides dispatcher) {
        // The app's own back: added first, so any enabled screen handler is preferred to it.
        BackHandler {
          presses += "root"
          nav.pop()
        }
        KeliverNavHost(nav, retention) { route ->
          when (route) {
            // Home's handler is always enabled (an open search, a "discard changes?" form).
            Route.Home -> BackHandler { presses += "home" }
            is Route.Detail -> if (route.id == 1) {
              BackHandler {
                presses += "detail1"
                nav.pop()
              }
            }
          }
          Leaf(nameOf(route))
        }
      }
    }
    dispatcher.press()
    assertEquals(listOf("home"), presses, "the visible Home's handler takes the press")

    presses.clear()
    nav.navigate(Route.Detail(1))
    harness.settle()
    nav.navigate(Route.Detail(2))
    harness.settle()
    assertEquals(
      "root[stack[entry(hidden)[home],entry(hidden)[detail1],entry[detail2]]]",
      harness.root.dump(),
    )
    // detail2 has no handler: the press reaches the root's, not hidden Home's or hidden detail1's.
    dispatcher.press()
    harness.settle()
    assertEquals(listOf("root"), presses)
    // detail1 is shown again: its handler is enabled again (without composing it).
    counts.reset()
    dispatcher.press()
    harness.settle()
    assertEquals(listOf("root", "detail1"), presses)
    assertEquals(emptyMap(), counts.compositions)
    // Home is shown again: its handler works again.
    dispatcher.press()
    assertEquals(listOf("root", "detail1", "home"), presses)
    harness.close()
  }

  /** What the inner stack's top entry saw: its lifecycle, its work, its back presses. */
  private class InnerTop {
    var lifecycle: KeliverNavEntryLifecycle? = null
    var running = 0
    var starts = 0
    val presses = mutableListOf<String>()
  }

  /** Home holds its own retained stack (as a tab would); the outer stack opens Detail over Home. */
  @Composable
  private fun Nested(outerKeys: List<String>, retention: KeliverNavRetention, counts: Counts, top: InnerTop) {
    KeliverRetainedStack(outerKeys, retention) { outer ->
      if (outer == "home") {
        KeliverRetainedStack(listOf("tab", "tabTop"), retention) { inner ->
          InnerEntry(inner, counts, top)
        }
      } else {
        Screen(outer, counts)
      }
    }
  }

  @Composable
  private fun InnerEntry(name: String, counts: Counts, top: InnerTop) {
    counts.compositions[name] = (counts.compositions[name] ?: 0) + 1
    if (name == "tabTop") {
      val lifecycle = keliverNavEntryLifecycle()
      LaunchedEffect(lifecycle) {
        androidx.compose.runtime.snapshotFlow { lifecycle.value }.collect { top.lifecycle = it }
      }
      LaunchedWhileVisible {
        top.starts++
        top.running++
        try {
          awaitCancellation()
        } finally {
          top.running--
        }
      }
      BackHandler { top.presses += "tabTop" }
    }
    Leaf(name)
  }

  @Test
  fun nestedStackInsideAHiddenEntryIsHiddenToo() = runTest {
    val dispatcher = TestBackDispatcher()
    val counts = Counts()
    val harness = Harness(this, counts)
    val retention = KeliverNavRetention(TestFrame(counts))
    val top = InnerTop()
    var outerKeys by mutableStateOf(listOf("home"))
    harness.setContent {
      CompositionLocalProvider(LocalOnBackPressedDispatcher provides dispatcher) {
        // The app's own back: added first, so any enabled screen handler is preferred to it.
        BackHandler { top.presses += "root" }
        Nested(outerKeys, retention, counts, top)
      }
    }
    assertEquals(KeliverNavEntryLifecycle.Visible, top.lifecycle)
    assertEquals(1, top.running)
    dispatcher.press()
    assertEquals(listOf("tabTop"), top.presses)

    top.presses.clear()
    outerKeys = listOf("home", "detail")
    harness.settle()
    // The inner "tab" was never shown, so it was never composed; only the inner top is retained.
    assertEquals(
      "root[stack[entry(hidden)[stack[entry[tabTop]]],entry[detail]]]",
      harness.root.dump(),
    )
    // The inner top is still the top of its own stack, but its enclosing entry is hidden.
    assertEquals(KeliverNavEntryLifecycle.Hidden, top.lifecycle)
    assertEquals(0, top.running, "the inner top's work is cancelled")
    assertEquals(1, top.starts)
    dispatcher.press()
    assertEquals(listOf("root"), top.presses, "the inner top's handler does not take the press")

    top.presses.clear()
    counts.reset()
    outerKeys = listOf("home")
    harness.settle()
    assertEquals(KeliverNavEntryLifecycle.Visible, top.lifecycle)
    assertEquals(1, top.running, "shown again, its work runs again")
    assertEquals(2, top.starts)
    dispatcher.press()
    assertEquals(listOf("tabTop"), top.presses, "and its handler takes the press again")
    assertEquals(emptyMap(), counts.compositions, "no inner entry recomposes")
    assertEquals(0, counts.inserts)
    harness.close()
  }

  @Test
  fun completedLaunchedWhileVisibleRunsAgainEachTimeTheEntryIsShown() = runTest {
    var runs = 0
    val (harness, nav, _) = navHost(
      { KeliverNavRetention(TestFrame(it)) },
      screen = { route, _ ->
        if (route == Route.Home) LaunchedWhileVisible { runs++ }
        Leaf(nameOf(route))
      },
    )
    assertEquals(1, runs)
    nav.navigate(Route.Detail(1))
    harness.settle()
    assertEquals(1, runs)
    nav.pop()
    harness.settle()
    // The contract (as repeatOnLifecycle): a block that completed is started again on show. A
    // one-shot load belongs in LaunchedEffect.
    assertEquals(2, runs)
    harness.close()
  }

  @Test
  fun popUntilAcrossSeveralRetainedEntriesComposesNothing() = runTest {
    val (harness, nav, counts) = navHost({ KeliverNavRetention(TestFrame(it), depth = 3) })
    for (i in 1..3) {
      nav.navigate(Route.Detail(i))
      harness.settle()
    }
    counts.reset()
    nav.popUntil { it == Route.Home }
    harness.settle()
    assertEquals("root[stack[entry[home]]]", harness.root.dump())
    assertEquals(emptyMap(), counts.compositions)
    assertEquals(0, counts.inserts)
    assertEquals(3, counts.removes, "the three popped entries' frames")
    assertEquals(1, counts.visibilityUpdates)
    harness.close()
  }

  @Test
  fun popThenPushKeepsTheRetainedEntry() = runTest {
    val (harness, nav, counts) = navHost({ KeliverNavRetention(TestFrame(it)) })
    nav.navigate(Route.Detail(1))
    harness.settle()
    nav.pop()
    harness.settle()

    counts.reset()
    nav.navigate(Route.Detail(2))
    harness.settle()
    assertEquals("root[stack[entry(hidden)[home],entry[detail2]]]", harness.root.dump())
    assertEquals(mapOf("detail2" to 1), counts.compositions, "home is not composed again")
    assertEquals(2, counts.inserts, "only detail2's frame and leaf are created")
    assertEquals(0, counts.removes)
    harness.close()
  }

  @Test
  fun evictedEntryWorkDoesNotComeBack() = runTest {
    var starts = 0
    var running = 0
    val (harness, nav, _) = navHost(
      { KeliverNavRetention(TestFrame(it), depth = 1) },
      screen = { route, _ ->
        if (route == Route.Home) {
          LaunchedWhileVisible {
            starts++
            running++
            try {
              awaitCancellation()
            } finally {
              running--
            }
          }
        }
        Leaf(nameOf(route))
      },
    )
    nav.navigate(Route.Detail(1))
    harness.settle()
    nav.navigate(Route.Detail(2))
    harness.settle()
    // Home was evicted (depth 1); back to detail1 must not wake it.
    nav.pop()
    harness.settle()
    assertEquals(1, starts)
    assertEquals(0, running)
    // Home composed again: its work starts once (the evicted composition's collector is gone).
    nav.pop()
    harness.settle()
    assertEquals(2, starts)
    assertEquals(1, running)
    harness.close()
    runCurrent()
    assertEquals(0, running, "disposing the host cancels it")
  }

  /**
   * Pins a cost (KDoc on KeliverNavRetention): a caller whose content lambda captures a value that
   * changes on navigation makes back re-run the retained entry's content. Its nodes are kept.
   */
  @Test
  fun changedContentLambdaReRunsTheRetainedEntry() = runTest {
    val counts = Counts()
    val harness = Harness(this, counts)
    var keys by mutableStateOf(listOf("list"))
    val retention = KeliverNavRetention(TestFrame(counts))
    val labels = mutableMapOf<String, Int>()
    harness.setContent {
      val depth = keys.size
      KeliverRetainedStack(keys, retention) { key ->
        counts.compositions[key] = (counts.compositions[key] ?: 0) + 1
        labels[key] = depth
        Leaf(key)
      }
    }
    keys = listOf("list", "detail")
    harness.settle()

    counts.reset()
    keys = listOf("list")
    harness.settle()
    assertEquals("root[stack[entry[list]]]", harness.root.dump())
    assertEquals(mapOf("list" to 1), counts.compositions, "the retained entry's content ran again")
    assertEquals(0, counts.inserts, "but nothing is created")
    assertEquals(1, labels["list"])
    harness.close()
  }

  @Test
  fun duplicateKeysAreRefused() = runTest {
    val harness = Harness(this, Counts())
    val retention = KeliverNavRetention(TestFrame(Counts()))
    val e = assertFailsWith<IllegalArgumentException> {
      harness.setContent { KeliverRetainedStack(listOf("a", "b", "a"), retention) { Leaf(it) } }
    }
    assertTrue("unique" in e.message.orEmpty(), e.message)
    runCatching { harness.close() }
  }

  @Test
  fun negativeDepthIsRefused() {
    assertFailsWith<IllegalArgumentException> { KeliverNavRetention(TestFrame(Counts()), depth = -1) }
    assertTrue(KeliverNavRetention.DEFAULT_DEPTH in 1..3)
  }
}
