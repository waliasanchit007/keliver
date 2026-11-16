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
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import dev.keliver.compose.BackHandler
import dev.keliver.compose.ExperimentalKeliverBackApi
import dev.keliver.compose.LocalBackHandlersEnabled
import dev.keliver.compose.LocalOnBackPressedDispatcher
import dev.keliver.ui.Cancellable
import dev.keliver.ui.OnBackPressedCallback
import dev.keliver.ui.OnBackPressedDispatcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** The guest's side of host back (KeliverNavHostFrame): what it tells the host, and how it catches up. */
@OptIn(ExperimentalKeliverNavRetentionApi::class, ExperimentalKeliverBackApi::class)
class KeliverNavHostBackTest {
  private sealed interface Route {
    data object Home : Route
    data class Detail(val id: Int) : Route
  }

  private class Node(val name: String) {
    val children = mutableListOf<Node>()
    var entryId = ""
    var hostBack = false
    var backHandled = 0
    var onBack: (String) -> Unit = {}

    fun dump(): String = name +
      (if (name == "entry") "($entryId${if (hostBack) ",hostBack" else ""})" else "") +
      (if (name == "stack") "(handled=$backHandled)" else "") +
      (if (children.isEmpty()) "" else children.joinToString(",", "[", "]") { it.dump() })
  }

  private class TestApplier(root: Node) : AbstractApplier<Node>(root) {
    var composedLeaves = 0

    override fun insertTopDown(index: Int, instance: Node) {
      current.children.add(index, instance)
    }

    override fun insertBottomUp(index: Int, instance: Node) = Unit

    override fun remove(index: Int, count: Int) {
      current.children.remove(index, count)
    }

    override fun move(from: Int, to: Int, count: Int) {
      current.children.move(from, to, count)
    }

    override fun onClear() {
      root.children.clear()
    }
  }

  /** The host container, recorded: the stack's `backHandled`/`onBack`, each entry's id and `hostBack`. */
  private object RecordingHostFrame : KeliverNavHostFrame {
    @Composable
    override fun HostStack(backHandled: Int, onBack: (entryId: String) -> Unit, content: @Composable () -> Unit) {
      ComposeNode<Node, TestApplier>(
        factory = { Node("stack") },
        update = {
          set(backHandled) { this.backHandled = it }
          set(onBack) { this.onBack = it }
        },
        content = content,
      )
    }

    @Composable
    override fun HostEntry(entryId: String, hostBack: Boolean, content: @Composable () -> Unit) {
      ComposeNode<Node, TestApplier>(
        factory = { Node("entry") },
        update = {
          set(entryId) { this.entryId = it }
          set(hostBack) { this.hostBack = it }
        },
        content = content,
      )
    }

    @Composable
    override fun Stack(content: @Composable () -> Unit) = HostStack(0, {}, content)

    @Composable
    override fun Entry(visible: Boolean, content: @Composable () -> Unit) = HostEntry("", false, content)
  }

  @Composable
  private fun Leaf(name: String) {
    ComposeNode<Node, TestApplier>(factory = { Node(name) }, update = {})
  }

  /** The guest's dispatcher: newest enabled callback first, as on Android. */
  private class Dispatcher : OnBackPressedDispatcher {
    val callbacks = mutableListOf<OnBackPressedCallback>()

    override fun addCallback(onBackPressedCallback: OnBackPressedCallback): Cancellable {
      callbacks += onBackPressedCallback
      return object : Cancellable {
        override fun cancel() {
          callbacks -= onBackPressedCallback
        }
      }
    }
  }

  private class Harness(val scope: TestScope) {
    val root = Node("root")
    val dispatcher = Dispatcher()
    private val clock = BroadcastFrameClock()
    private val recomposer = Recomposer(scope.coroutineContext + clock)
    private val composition = Composition(TestApplier(root), recomposer)
    private var frame = 0L

    init {
      scope.backgroundScope.launch(clock) { recomposer.runRecomposeAndApplyChanges() }
    }

    fun setContent(content: @Composable () -> Unit) {
      composition.setContent {
        CompositionLocalProvider(LocalOnBackPressedDispatcher provides dispatcher, content = content)
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

    val stack: Node get() = root.children.single()

    /** The host went back to the entry below [fromIndex] and sends the event. */
    fun hostBackFrom(fromIndex: Int) {
      val target = stack.children[fromIndex - 1].entryId
      stack.onBack(target)
    }

    fun close() {
      composition.dispose()
      recomposer.cancel()
    }
  }

  private fun nameOf(route: Route): String = when (route) {
    Route.Home -> "home"
    is Route.Detail -> "detail${route.id}"
  }

  private var detailHandlerEnabled by mutableStateOf(false)
  private var detailHandled = 0
  private val compositions = mutableMapOf<String, Int>()

  private fun TestScope.navHost(depth: Int = 2): Pair<Harness, KeliverNavController<Route>> {
    val harness = Harness(this)
    val nav = KeliverNavController<Route>(Route.Home)
    val retention = KeliverNavRetention(RecordingHostFrame, depth)
    harness.setContent {
      KeliverNavHost(nav, retention) { route ->
        val name = nameOf(route)
        compositions[name] = (compositions[name] ?: 0) + 1
        if (route is Route.Detail) BackHandler(enabled = detailHandlerEnabled) { detailHandled++ }
        Leaf(name)
      }
    }
    return harness to nav
  }

  @Test
  fun entriesTellTheHostItMayGoBack() = runTest {
    val (harness, nav) = navHost()
    nav.navigate(Route.Detail(1))
    harness.settle()
    // Entry ids are the controller's entry ids; the bottom entry's hostBack does not matter.
    assertEquals(
      "root[stack(handled=0)[entry(0,hostBack)[home],entry(1,hostBack)[detail1]]]",
      harness.root.dump(),
    )
    harness.close()
  }

  @Test
  fun controllerPopsToTheEntryTheHostShowsAndConfirms() = runTest {
    val (harness, nav) = navHost()
    nav.navigate(Route.Detail(1))
    harness.settle()
    compositions.clear()

    harness.hostBackFrom(1)
    harness.settle()
    assertEquals(listOf<Route>(Route.Home), nav.backstack)
    assertEquals("root[stack(handled=1)[entry(0,hostBack)[home]]]", harness.root.dump())
    assertEquals(emptyMap(), compositions, "catching up composes nothing")
    harness.close()
  }

  @Test
  fun anEnabledBackHandlerTurnsHostBackOffForItsEntry() = runTest {
    val (harness, nav) = navHost()
    nav.navigate(Route.Detail(1))
    harness.settle()
    detailHandlerEnabled = true
    harness.settle()
    assertEquals(
      "root[stack(handled=0)[entry(0,hostBack)[home],entry(1)[detail1]]]",
      harness.root.dump(),
    )
    detailHandlerEnabled = false
    harness.settle()
    assertEquals(
      "root[stack(handled=0)[entry(0,hostBack)[home],entry(1,hostBack)[detail1]]]",
      harness.root.dump(),
    )
    harness.close()
  }

  @Test
  fun aHiddenEntryKeepsTheValueItHadWhenShown() = runTest {
    val (harness, nav) = navHost()
    nav.navigate(Route.Detail(1))
    harness.settle()
    detailHandlerEnabled = true
    harness.settle()
    nav.navigate(Route.Detail(2))
    harness.settle()
    // Detail 1's handler is disabled by the gate while hidden; the host still hears "not free".
    // Detail 2's handler (enabled too) keeps host back off for the top.
    assertEquals(
      "root[stack(handled=0)[entry(0,hostBack)[home],entry(1)[detail1],entry(2)[detail2]]]",
      harness.root.dump(),
    )
    harness.close()
  }

  @Test
  fun aHandlerEnabledInTheRaceTakesThePressAndNothingIsPopped() = runTest {
    val (harness, nav) = navHost()
    nav.navigate(Route.Detail(1))
    harness.settle()
    // The host still believes it may go back (its batch has not arrived) when the press comes.
    val onBack = harness.stack.onBack
    detailHandlerEnabled = true
    harness.settle()
    onBack("0")
    harness.settle()
    assertEquals(1, detailHandled, "the top's handler takes the press")
    assertEquals(listOf(Route.Home, Route.Detail(1)), nav.backstack)
    assertEquals(1, harness.stack.backHandled, "the refusal is acknowledged, so the host shows the top again")
    harness.close()
  }

  @Test
  fun twoQuickBacksPopTwiceAndAcknowledgeBoth() = runTest {
    val (harness, nav) = navHost()
    nav.navigate(Route.Detail(1))
    harness.settle()
    nav.navigate(Route.Detail(2))
    harness.settle()
    val onBack = harness.stack.onBack
    onBack("1")
    onBack("0")
    harness.settle()
    assertEquals(listOf<Route>(Route.Home), nav.backstack)
    assertEquals(2, harness.stack.backHandled)
    harness.close()
  }

  @Test
  fun backDuringAForwardNavigationPopsToWhatTheHostShows() = runTest {
    val (harness, nav) = navHost()
    nav.navigate(Route.Detail(1))
    harness.settle()
    val onBack = harness.stack.onBack
    // The guest pushes detail 2 while the host, still showing detail 1, goes back to home.
    nav.navigate(Route.Detail(2))
    harness.settle()
    onBack("0")
    harness.settle()
    assertEquals(listOf<Route>(Route.Home), nav.backstack, "the guest resolves to what the user saw")
    assertEquals(1, harness.stack.backHandled)
    harness.close()
  }

  @Test
  fun anUnknownEntryIsOnlyAcknowledged() = runTest {
    val (harness, nav) = navHost()
    nav.navigate(Route.Detail(1))
    harness.settle()
    val onBack = harness.stack.onBack
    nav.replaceAll(Route.Detail(9))
    harness.settle()
    onBack("0")
    harness.settle()
    assertEquals(listOf<Route>(Route.Detail(9)), nav.backstack)
    assertEquals(1, harness.stack.backHandled)
    harness.close()
  }

  @Test
  fun aNeverShownEntryIsNotFreeAndADoubleBackStopsAtItsHandler() = runTest {
    val harness = Harness(this)
    // A deep link opened in a stack nested in a hidden entry (a single navigate of several routes
    // composes only the top, so it is the enclosing entry being hidden that composes entries no one
    // has seen): home, a form with a "discard changes?" handler, a confirm screen.
    val parent = mutableStateOf(KeliverNavEntryLifecycle.Hidden)
    val parentHandlers = mutableStateOf(false)
    val nav = KeliverNavController<Route>(Route.Home)
    var formHandled = 0
    harness.setContent {
      CompositionLocalProvider(
        LocalKeliverNavEntryLifecycle provides parent,
        LocalBackHandlersEnabled provides parentHandlers,
      ) {
        KeliverNavHost(nav, KeliverNavRetention(RecordingHostFrame, 2)) { route ->
          if (route == Route.Detail(1)) BackHandler(enabled = true) { formHandled++ }
          Leaf(nameOf(route))
        }
      }
    }
    nav.navigate(Route.Detail(1))
    harness.settle()
    nav.navigate(Route.Detail(2))
    harness.settle()
    parent.value = KeliverNavEntryLifecycle.Visible
    parentHandlers.value = true
    harness.settle()
    // Entries never shown tell the host it may not go back from them on its own.
    assertEquals(
      "root[stack(handled=0)[entry(0)[home],entry(1)[detail1],entry(2,hostBack)[detail2]]]",
      harness.root.dump(),
    )

    // A host that went back twice anyway, both presses handled in one guest tick: the second is
    // checked against the live stack (top: the form), and the form never said "free".
    val onBack = harness.stack.onBack
    onBack("1")
    onBack("0")
    harness.settle()
    assertEquals(listOf(Route.Home, Route.Detail(1)), nav.backstack, "the double back stops at the form")
    assertEquals(2, harness.stack.backHandled, "both presses are acknowledged")
    assertEquals(
      "root[stack(handled=2)[entry(0)[home],entry(1)[detail1]]]",
      harness.root.dump(),
      "the form, shown, has its handler enabled: host back stays off",
    )

    // The next press goes through the guest, where the form's handler takes it.
    harness.dispatcher.callbacks.last { it.isEnabled }.handleOnBackPressed()
    harness.settle()
    assertEquals(1, formHandled)
    assertEquals(listOf(Route.Home, Route.Detail(1)), nav.backstack)
    harness.close()
  }

  @Test
  fun twoBacksInOneTickSeeTheFirstPopOnARetainedStack() = runTest {
    val harness = Harness(this)
    var keys by mutableStateOf(listOf("a"))
    val calls = mutableListOf<String>()
    harness.setContent {
      KeliverRetainedStack(
        keys,
        KeliverNavRetention(RecordingHostFrame),
        onHostBack = { key ->
          calls += key
          keys = keys.subList(0, keys.indexOf(key) + 1)
        },
      ) { key ->
        if (key == "b") BackHandler(enabled = true) {}
        Leaf(key)
      }
    }
    keys = listOf("a", "b")
    harness.settle()
    keys = listOf("a", "b", "c")
    harness.settle()
    assertEquals(
      "root[stack(handled=0)[entry(0,hostBack)[a],entry(1)[b],entry(2,hostBack)[c]]]",
      harness.root.dump(),
    )
    // Both in one tick: the second leaves b (popped to by the first), which had a handler enabled.
    val onBack = harness.stack.onBack
    onBack("1")
    onBack("0")
    harness.settle()
    assertEquals(listOf("b"), calls)
    assertEquals(listOf("a", "b"), keys)
    assertEquals(2, harness.stack.backHandled)
    harness.close()
  }

  @Test
  fun retainedStackWithoutOnHostBackNeverAllowsHostBack() = runTest {
    val harness = Harness(this)
    var keys by mutableStateOf(listOf("a"))
    harness.setContent {
      KeliverRetainedStack(keys, KeliverNavRetention(RecordingHostFrame), onHostBack = null) { Leaf(it) }
    }
    keys = listOf("a", "b")
    harness.settle()
    assertEquals("root[stack(handled=0)[entry(0)[a],entry(1)[b]]]", harness.root.dump())
    harness.close()
  }

  @Test
  fun retainedStackCallsOnHostBackWithTheKeyTheHostShows() = runTest {
    val harness = Harness(this)
    var keys by mutableStateOf(listOf("a"))
    val calls = mutableListOf<String>()
    harness.setContent {
      KeliverRetainedStack(
        keys,
        KeliverNavRetention(RecordingHostFrame),
        onHostBack = { key ->
          calls += key
          keys = keys.subList(0, keys.indexOf(key) + 1)
        },
      ) { Leaf(it) }
    }
    keys = listOf("a", "b")
    harness.settle()
    keys = listOf("a", "b", "c")
    harness.settle()
    assertEquals(
      "root[stack(handled=0)[entry(0,hostBack)[a],entry(1,hostBack)[b],entry(2,hostBack)[c]]]",
      harness.root.dump(),
    )
    harness.hostBackFrom(2)
    harness.settle()
    assertEquals(listOf("b"), calls)
    assertEquals("root[stack(handled=1)[entry(0,hostBack)[a],entry(1,hostBack)[b]]]", harness.root.dump())
    harness.close()
  }
}
