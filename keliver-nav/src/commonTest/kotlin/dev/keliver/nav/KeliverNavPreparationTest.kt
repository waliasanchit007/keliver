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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalKeliverNavRetentionApi::class)
class KeliverNavPreparationTest {
  private class Node(val name: String) {
    val children = mutableListOf<Node>()
    var visible = true

    fun dump(): String =
      name + (if (!visible) "(hidden)" else "") +
        (if (children.isEmpty()) "" else children.joinToString(",", "[", "]") { it.dump() })
  }

  private class Counts {
    var inserts = 0
    var removes = 0
    val compositions = mutableMapOf<String, Int>()
    val disposed = mutableListOf<String>()
    val running = mutableSetOf<String>()

    fun reset() {
      inserts = 0
      removes = 0
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
      counts.removes += count
    }

    override fun move(from: Int, to: Int, count: Int) {
      current.children.move(from, to, count)
    }

    override fun onClear() {
      root.children.clear()
    }
  }

  private object TestFrame : KeliverNavFrame {
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
      composition.setContent(content)
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

  /** The app's state: a stack of screen names, bottom to top. */
  private class App {
    var keys by mutableStateOf(listOf("home"))
    val preparation = KeliverNavPreparation<String>()
  }

  private fun TestScope.start(depth: Int = 0): Triple<Harness, App, Counts> {
    val counts = Counts()
    val harness = Harness(this, counts)
    val app = App()
    // One lambda for the whole test, as an app that keeps its content lambda's identity (see
    // KeliverPreparedStack's caveat): a new one per recomposition would re-run every entry.
    val content: @Composable (String) -> Unit = { key ->
      counts.compositions[key] = (counts.compositions[key] ?: 0) + 1
      DisposableEffect(key) { onDispose { counts.disposed += key } }
      LaunchedWhileVisible(key) {
        counts.running += key
        try {
          awaitCancellation()
        } finally {
          counts.running -= key
        }
      }
      ComposeNode<Node, TestApplier>(factory = { Node(key) }, update = {})
    }
    harness.setContent {
      KeliverPreparedStack(app.keys, app.preparation, TestFrame, depth, content)
    }
    return Triple(harness, app, counts)
  }

  /** Runs [block] on a started stack and disposes the composition after it. */
  private fun stack(depth: Int = 0, block: TestScope.(Harness, App, Counts) -> Unit) = runTest {
    val (harness, app, counts) = start(depth)
    try {
      block(harness, app, counts)
    } finally {
      harness.close()
    }
  }

  @Test
  fun preparingComposesTheScreenHidden() = stack { harness, app, counts ->
    counts.reset()
    app.preparation.prepare("detail")
    harness.settle()
    assertEquals("root[stack[entry[home],entry(hidden)[detail]]]", harness.root.dump())
    assertEquals(mapOf("detail" to 1), counts.compositions)
    // Hidden: work that waits for the screen to be shown has not started.
    assertEquals(setOf("home"), counts.running)
  }

  @Test
  fun theTapShowsThePreparedCompositionWithoutComposingIt() = stack { harness, app, counts ->
    app.preparation.prepare("detail")
    harness.settle()

    counts.reset()
    app.keys = listOf("home", "detail")
    harness.settle()
    assertEquals("root[stack[entry[detail]]]", harness.root.dump())
    assertEquals(emptyMap(), counts.compositions)
    assertEquals(0, counts.inserts, "nothing created at the tap")
    assertEquals(listOf("home"), counts.disposed)
    assertEquals(setOf("detail"), counts.running)
    assertNull(app.preparation.prepared, "adopted: no longer a preparation")
  }

  @Test
  fun aCancelledPressDropsTheComposition() = stack { harness, app, counts ->
    app.preparation.prepare("detail")
    harness.settle()

    counts.reset()
    app.preparation.cancel("other") // not the prepared key: nothing happens
    harness.settle()
    assertEquals("root[stack[entry[home],entry(hidden)[detail]]]", harness.root.dump())

    app.preparation.cancel("detail")
    harness.settle()
    assertEquals("root[stack[entry[home]]]", harness.root.dump())
    assertEquals(listOf("detail"), counts.disposed)
    assertEquals(emptyMap(), counts.compositions, "the screen shown is not composed again")
  }

  @Test
  fun aSecondPressReplacesTheFirst() = stack { harness, app, counts ->
    app.preparation.prepare("a")
    harness.settle()
    counts.reset()
    app.preparation.prepare("b")
    harness.settle()
    assertEquals("root[stack[entry[home],entry(hidden)[b]]]", harness.root.dump())
    assertEquals(listOf("a"), counts.disposed)
  }

  @Test
  fun aTapWithoutAPressComposesAsAPlainStack() = stack { harness, app, counts ->
    counts.reset()
    app.keys = listOf("home", "detail")
    harness.settle()
    assertEquals("root[stack[entry[detail]]]", harness.root.dump())
    assertEquals(mapOf("detail" to 1), counts.compositions)
  }

  @Test
  fun preparingAKeyAlreadyOnTheStackAddsNothing() = stack { harness, app, counts ->
    counts.reset()
    app.preparation.prepare("home")
    harness.settle()
    assertEquals("root[stack[entry[home]]]", harness.root.dump())
    assertEquals(emptyMap(), counts.compositions)
    assertNull(app.preparation.prepared)
  }

  @Test
  fun withDepthTheScreenBelowStaysRetained() = stack(depth = 1) { harness, app, counts ->
    app.preparation.prepare("detail")
    harness.settle()
    counts.reset()
    app.keys = listOf("home", "detail")
    harness.settle()
    assertEquals("root[stack[entry(hidden)[home],entry[detail]]]", harness.root.dump())
    assertEquals(emptyMap(), counts.compositions)
    assertEquals(emptyList(), counts.disposed)

    // Back: home is shown again, composed nothing; the detail leaves.
    app.keys = listOf("home")
    harness.settle()
    assertEquals("root[stack[entry[home]]]", harness.root.dump())
    assertEquals(emptyMap(), counts.compositions)
  }

  @Test
  fun aTapThatOpensAnotherScreenDropsThePreparation() = stack { harness, app, counts ->
    // A conditional click (`if (loggedIn) detail else login`): the press prepared detail, the tap
    // pushed login.
    app.preparation.prepare("detail")
    harness.settle()
    assertEquals(setOf("home"), counts.running)

    counts.reset()
    app.keys = listOf("home", "login")
    harness.settle()
    assertEquals("root[stack[entry[login]]]", harness.root.dump())
    assertEquals(listOf("detail", "home").sorted(), counts.disposed.sorted())
    assertNull(app.preparation.prepared)
    assertEquals(setOf("login"), counts.running)
  }

  @Test
  fun aPopDuringThePressDropsThePreparation() = stack { harness, app, counts ->
    app.keys = listOf("home", "list")
    harness.settle()
    app.preparation.prepare("detail")
    harness.settle()
    assertEquals("root[stack[entry[list],entry(hidden)[detail]]]", harness.root.dump())

    // The host's back arrives while the finger is still down.
    counts.reset()
    app.keys = listOf("home")
    harness.settle()
    assertEquals("root[stack[entry[home]]]", harness.root.dump())
    assertEquals(listOf("detail", "list").sorted(), counts.disposed.sorted())
    assertNull(app.preparation.prepared)
  }

  @Test
  fun aPressAndAClickInTheSameFrameComposeTheScreenOnce() = stack { harness, app, counts ->
    counts.reset()
    app.preparation.prepare("detail")
    app.keys = listOf("home", "detail")
    harness.settle()
    assertEquals("root[stack[entry[detail]]]", harness.root.dump())
    assertEquals(mapOf("detail" to 1), counts.compositions)
    assertNull(app.preparation.prepared)
  }

  @Test
  fun aPressAndAClickElsewhereInTheSameFrameNeverShowThePreparedScreen() = stack { harness, app, counts ->
    counts.reset()
    app.preparation.prepare("detail")
    app.keys = listOf("home", "login")
    harness.settle()
    assertEquals("root[stack[entry[login]]]", harness.root.dump())
    assertEquals(null, counts.compositions["detail"])
    assertNull(app.preparation.prepared)
  }

  @Test
  fun aStackThatDoesNotChangeKeepsThePreparation() = stack { harness, app, _ ->
    app.preparation.prepare("detail")
    harness.settle()
    // An unrelated recomposition with an equal stack: still prepared.
    app.keys = listOf("home")
    harness.settle()
    assertEquals("root[stack[entry[home],entry(hidden)[detail]]]", harness.root.dump())
    assertEquals("detail", app.preparation.prepared)
  }

  @Test
  fun aStateListChangedInPlaceStillDropsAStalePreparation() = runTest {
    // The caller passes one list and changes it in place (as KeliverNavController.entries is).
    val counts = Counts()
    val harness = Harness(this, counts)
    val keys = mutableStateListOf("home", "list")
    val preparation = KeliverNavPreparation<String>()
    val content: @Composable (String) -> Unit = { key ->
      DisposableEffect(key) { onDispose { counts.disposed += key } }
      ComposeNode<Node, TestApplier>(factory = { Node(key) }, update = {})
    }
    try {
      harness.setContent { KeliverPreparedStack(keys, preparation, TestFrame, 0, content) }
      preparation.prepare("detail")
      harness.settle()
      assertEquals("root[stack[entry[list],entry(hidden)[detail]]]", harness.root.dump())

      // A pop while the finger is down, in place.
      counts.reset()
      keys.removeAt(keys.lastIndex)
      harness.settle()
      assertEquals("root[stack[entry[home]]]", harness.root.dump())
      assertEquals(listOf("detail", "list").sorted(), counts.disposed.sorted())
      assertNull(preparation.prepared)

      // A press then a push of that key, in place: adopted, not composed again.
      preparation.prepare("detail")
      harness.settle()
      counts.reset()
      keys.add("detail")
      harness.settle()
      assertEquals("root[stack[entry[detail]]]", harness.root.dump())
      assertEquals(listOf("home"), counts.disposed)
      assertNull(preparation.prepared)
    } finally {
      harness.close()
    }
  }

  @Test
  fun keysAreChecked() = runTest {
    val counts = Counts()
    val harness = Harness(this, counts)
    assertFailsWith<IllegalArgumentException> {
      harness.setContent {
        KeliverPreparedStack(listOf("a", "a"), KeliverNavPreparation(), TestFrame) { }
      }
    }
    runCatching { harness.close() }
  }
}
