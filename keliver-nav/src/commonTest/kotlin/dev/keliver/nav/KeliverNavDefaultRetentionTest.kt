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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import dev.keliver.compose.ExperimentalKeliverHostFeaturesApi
import dev.keliver.compose.LocalHostFeatures
import dev.keliver.compose.LocalOnBackPressedDispatcher
import dev.keliver.ui.Cancellable
import dev.keliver.ui.OnBackPressedCallback
import dev.keliver.ui.OnBackPressedDispatcher
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Retention by default (NF1): which frame [KeliverNavHost] picks per host (D2), and every case a
 * retained stack must handle: data changed elsewhere, refresh on return, results, in-flight work,
 * per-route opt-out, stack replacement and deep links, memory pressure.
 */
@OptIn(ExperimentalKeliverNavRetentionApi::class, ExperimentalKeliverHostFeaturesApi::class)
class KeliverNavDefaultRetentionTest {
  private sealed interface Route {
    data object Home : Route
    data class Detail(val id: Int) : Route
  }

  private class Node(val name: String) {
    val children = mutableListOf<Node>()
    var visible = true
    var label = ""

    fun dump(): String =
      name + (if (label.isNotEmpty()) ":$label" else "") + (if (!visible) "(hidden)" else "") +
        (if (children.isEmpty()) "" else children.joinToString(",", "[", "]") { it.dump() })
  }

  private class TestApplier(root: Node) : AbstractApplier<Node>(root) {
    var inserts = 0

    override fun insertTopDown(index: Int, instance: Node) {
      current.children.add(index, instance)
      inserts++
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

  /** A frame of plain widgets every host has. */
  private object GuestFrame : KeliverNavFrame {
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

  /** The host's container: only a host that declares the feature can render these nodes. */
  private object ContainerFrame : KeliverNavHostFrame {
    @Composable
    override fun HostStack(backHandled: Int, onBack: (entryId: String) -> Unit, content: @Composable () -> Unit) {
      ComposeNode<Node, TestApplier>(factory = { Node("container") }, update = {}, content = content)
    }

    @Composable
    override fun HostEntry(entryId: String, hostBack: Boolean, content: @Composable () -> Unit) {
      ComposeNode<Node, TestApplier>(factory = { Node("slot") }, update = {}, content = content)
    }

    @Composable
    override fun Stack(content: @Composable () -> Unit) = HostStack(0, {}, content)

    @Composable
    override fun Entry(visible: Boolean, content: @Composable () -> Unit) = HostEntry("", false, content)
  }

  @Composable
  private fun Leaf(name: String, label: String = "") {
    ComposeNode<Node, TestApplier>(factory = { Node(name) }, update = { set(label) { this.label = it } })
  }

  private object NoBack : OnBackPressedDispatcher {
    override fun addCallback(onBackPressedCallback: OnBackPressedCallback): Cancellable =
      object : Cancellable {
        override fun cancel() = Unit
      }
  }

  private class Harness(val scope: TestScope) {
    val root = Node("root")
    val applier = TestApplier(root)
    private val clock = BroadcastFrameClock()
    private val recomposer = Recomposer(scope.coroutineContext + clock)
    private val composition = Composition(applier, recomposer)
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

  private val compositions = mutableMapOf<String, Int>()

  private fun nameOf(route: Route): String = when (route) {
    Route.Home -> "home"
    is Route.Detail -> "detail${route.id}"
  }

  /**
   * [KeliverNavHost]'s default overload under [defaults] on a host with [features], or the overload
   * with [entryRetention] (and [defaults]' retention) when it is given.
   */
  private fun TestScope.navHost(
    defaults: KeliverNavDefaults? = KeliverNavDefaults(frame = GuestFrame),
    features: Set<String> = emptySet(),
    entryRetention: ((Route) -> KeliverNavEntryRetention)? = null,
    screen: @Composable (Route) -> Unit = { Leaf(nameOf(it)) },
  ): Pair<Harness, KeliverNavController<Route>> {
    val harness = Harness(this)
    val nav = KeliverNavController<Route>(Route.Home)
    harness.setContent {
      CompositionLocalProvider(LocalHostFeatures provides features, LocalKeliverNavDefaults provides defaults) {
        val content: @Composable (Route) -> Unit = { route ->
          val name = nameOf(route)
          compositions[name] = (compositions[name] ?: 0) + 1
          screen(route)
        }
        if (entryRetention == null) {
          KeliverNavHost(nav, content)
        } else {
          KeliverNavHost(nav, defaultKeliverNavRetention(), entryRetention, content)
        }
      }
    }
    return harness to nav
  }

  @AfterTest
  fun resetTrims() {
    retentionTrims.intValue = 0
  }

  // D2: which frame, per host.

  @Test
  fun withoutDefaultsTheHostRetainsNothing() = runTest {
    val (harness, nav) = navHost(defaults = null)
    nav.navigate(Route.Detail(1))
    harness.settle()
    assertEquals("root[detail1]", harness.root.dump())
    compositions.clear()
    nav.pop()
    harness.settle()
    assertEquals(mapOf("home" to 1), compositions, "without defaults back composes the screen again")
    harness.close()
  }

  @Test
  fun anOlderHostGetsTheGuestFrameNeverTheContainer() = runTest {
    // A host that declares no features (every host before 0.5.0) never receives the container's
    // widgets, so it cannot fail on tags it does not know.
    val (harness, nav) = navHost(defaults = KeliverNavDefaults(hostFrame = ContainerFrame, frame = GuestFrame))
    nav.navigate(Route.Detail(1))
    harness.settle()
    assertEquals("root[stack[entry(hidden)[home],entry[detail1]]]", harness.root.dump())
    compositions.clear()
    nav.pop()
    harness.settle()
    assertEquals(emptyMap(), compositions, "retained by the guest frame: back composes nothing")
    harness.close()
  }

  @Test
  fun aHostWithTheContainerGetsTheHostFrame() = runTest {
    val (harness, nav) = navHost(
      defaults = KeliverNavDefaults(hostFrame = ContainerFrame, frame = GuestFrame),
      features = setOf(KeliverNavDefaults.RETAINED_CONTAINER_FEATURE),
    )
    nav.navigate(Route.Detail(1))
    harness.settle()
    assertEquals("root[container[slot[home],slot[detail1]]]", harness.root.dump())
    compositions.clear()
    nav.pop()
    harness.settle()
    assertEquals(emptyMap(), compositions)
    harness.close()
  }

  @Test
  fun onlyAHostFrameOnAnOlderHostMeansNoRetentionAndNoContainer() = runTest {
    val (harness, nav) = navHost(defaults = KeliverNavDefaults(hostFrame = ContainerFrame))
    nav.navigate(Route.Detail(1))
    harness.settle()
    assertEquals("root[detail1]", harness.root.dump())
    harness.close()
  }

  @Test
  fun retentionForPicksPerHost() {
    val both = KeliverNavDefaults(hostFrame = ContainerFrame, frame = GuestFrame, depth = 3)
    assertSame(ContainerFrame, both.retentionFor(setOf(KeliverNavDefaults.RETAINED_CONTAINER_FEATURE))?.frame)
    assertEquals(3, both.retentionFor(emptySet())?.depth)
    assertSame(GuestFrame, both.retentionFor(setOf("something.else@1"))?.frame)
    assertNull(KeliverNavDefaults(hostFrame = ContainerFrame).retentionFor(emptySet()))
    assertNull(KeliverNavDefaults(frame = GuestFrame, depth = 0).retentionFor(emptySet()))
    assertNull(KeliverNavDefaults().retentionFor(setOf(KeliverNavDefaults.RETAINED_CONTAINER_FEATURE)))
  }

  // Data freshness.

  @Test
  fun aHiddenScreenShowsDataChangedElsewhere() = runTest {
    var title by mutableStateOf("old")
    val (harness, nav) = navHost(
      screen = { route -> if (route == Route.Home) Leaf("home", title) else Leaf(nameOf(route)) },
    )
    nav.navigate(Route.Detail(1))
    harness.settle()

    // The detail edits the item the list shows.
    title = "edited"
    harness.settle()
    assertEquals("root[stack[entry(hidden)[home:edited],entry[detail1]]]", harness.root.dump(), "updated while hidden")

    compositions.clear()
    val inserts = harness.applier.inserts
    nav.pop()
    harness.settle()
    assertEquals("root[stack[entry[home:edited]]]", harness.root.dump())
    assertEquals(emptyMap(), compositions, "the update was applied while hidden; back composes nothing")
    assertEquals(inserts, harness.applier.inserts)
    harness.close()
  }

  @Test
  fun launchedOnReturnRunsOnEachReturnNotOnTheFirstShow() = runTest {
    var refreshes = 0
    val (harness, nav) = navHost(
      screen = { route ->
        if (route == Route.Home) LaunchedOnReturn { refreshes++ }
        Leaf(nameOf(route))
      },
    )
    assertEquals(0, refreshes, "the first show is the load's, not a return")

    repeat(2) { i ->
      nav.navigate(Route.Detail(i))
      harness.settle()
      assertEquals(i, refreshes, "hidden: no refresh")
      compositions.clear()
      nav.pop()
      harness.settle()
      assertEquals(i + 1, refreshes, "each return refreshes")
      assertEquals(emptyMap(), compositions, "the refresh itself composes nothing here")
    }
    harness.close()
  }

  @Test
  fun launchedOnReturnDoesNotRunWithoutRetentionWhereTheLoadRunsAgain() = runTest {
    var loads = 0
    var refreshes = 0
    val (harness, nav) = navHost(
      defaults = null,
      screen = { route ->
        if (route == Route.Home) {
          LaunchedEffect(Unit) { loads++ }
          LaunchedOnReturn { refreshes++ }
        }
        Leaf(nameOf(route))
      },
    )
    nav.navigate(Route.Detail(1))
    harness.settle()
    nav.pop()
    harness.settle()
    assertEquals(2, loads, "composed again: its load runs again")
    assertEquals(0, refreshes)
    harness.close()
  }

  @Test
  fun launchedOnceShownRunsOnceAtTheFirstShow() = runTest {
    var views = 0
    val (harness, nav) = navHost(
      screen = { route ->
        if (route == Route.Home) LaunchedOnceShown { views++ }
        Leaf(nameOf(route))
      },
    )
    assertEquals(1, views)
    nav.navigate(Route.Detail(1))
    harness.settle()
    nav.pop()
    harness.settle()
    assertEquals(1, views, "a return is not a new view")
    harness.close()
  }

  @Test
  fun aPreparedScreenIsViewedAtTheTapNotOnThePress() = runTest {
    var views = 0
    var loads = 0
    val harness = Harness(this)
    val preparation = KeliverNavPreparation<String>()
    var keys by mutableStateOf(listOf("list"))
    harness.setContent {
      KeliverPreparedStack(keys, preparation, GuestFrame) { key ->
        if (key == "detail") {
          LaunchedEffect(Unit) { loads++ }
          LaunchedOnceShown { views++ }
        }
        Leaf(key)
      }
    }

    preparation.prepare("detail")
    harness.settle()
    assertEquals(1, loads, "the load starts on press")
    assertEquals(0, views, "about to be shown is not shown")

    preparation.cancel("detail")
    harness.settle()
    assertEquals(0, views, "a cancelled press is never viewed")

    preparation.prepare("detail")
    harness.settle()
    keys = listOf("list", "detail")
    harness.settle()
    assertEquals(1, views, "viewed at the tap")
    assertEquals(2, loads)
    harness.close()
  }

  @Test
  fun popWithResultReachesARetainedScreenOnceWithoutComposingIt() = runTest {
    val received = mutableListOf<String?>()
    val picked = KeliverNavResultKey<String?>("picked")
    val (harness, nav) = navHost(
      screen = { route ->
        if (route == Route.Home) LaunchedOnResult(picked) { received += it }
        Leaf(nameOf(route))
      },
    )
    nav.navigate(Route.Detail(1))
    harness.settle()
    compositions.clear()
    assertTrue(nav.popWithResult(picked, "teal"))
    harness.settle()
    assertEquals(listOf<String?>("teal"), received)
    assertEquals(emptyMap(), compositions)

    // A null result is a result; and each is received once.
    nav.navigate(Route.Detail(2))
    harness.settle()
    nav.popWithResult(picked, null)
    harness.settle()
    nav.navigate(Route.Detail(3))
    harness.settle()
    nav.pop()
    harness.settle()
    assertEquals(listOf("teal", null), received)
    harness.close()
  }

  @Test
  fun popWithResultReachesAScreenThatWasNotRetained() = runTest {
    val received = mutableListOf<Int>()
    val count = KeliverNavResultKey<Int>("count")
    val (harness, nav) = navHost(
      defaults = null,
      screen = { route ->
        if (route == Route.Home) LaunchedOnResult(count) { received += it }
        Leaf(nameOf(route))
      },
    )
    nav.navigate(Route.Detail(1))
    harness.settle()
    nav.popWithResult(count, 3)
    harness.settle()
    assertEquals(listOf(3), received, "received when it is composed again")
    assertFalse(nav.popWithResult(count, 4), "nothing below the root")
    harness.settle()
    assertEquals(listOf(3), received)
    harness.close()
  }

  @Test
  fun aLoadInFlightWhenHiddenFinishesAndIsShownOnReturn() = runTest {
    val response = CompletableDeferred<String>()
    val (harness, nav) = navHost(
      screen = { route ->
        if (route == Route.Home) {
          var data by remember { mutableStateOf("loading") }
          LaunchedEffect(Unit) { data = response.await() }
          Leaf("home", data)
        } else {
          Leaf(nameOf(route))
        }
      },
    )
    nav.navigate(Route.Detail(1))
    harness.settle()
    response.complete("loaded")
    harness.settle()
    assertEquals("root[stack[entry(hidden)[home:loaded],entry[detail1]]]", harness.root.dump())
    compositions.clear()
    nav.pop()
    harness.settle()
    assertEquals("root[stack[entry[home:loaded]]]", harness.root.dump())
    assertEquals(emptyMap(), compositions, "the response was applied while hidden")
    harness.close()
  }

  // Lifecycle.

  @Test
  fun aDisposeRouteLeavesWhenCoveredAndKeepsItsSavedState() = runTest {
    var bump: () -> Unit = {}
    val disposed = mutableListOf<String>()
    val (harness, nav) = navHost(
      entryRetention = { if (it == Route.Home) KeliverNavEntryRetention.Dispose else KeliverNavEntryRetention.Retain },
      screen = { route ->
        val name = nameOf(route)
        DisposableEffect(name) { onDispose { disposed += name } }
        if (route == Route.Home) {
          var n by rememberSaveable { mutableStateOf(0) }
          bump = { n++ }
          Leaf("home", "$n")
        } else {
          Leaf(name)
        }
      },
    )
    bump()
    harness.settle()
    nav.navigate(Route.Detail(1))
    harness.settle()
    assertEquals("root[stack[entry[detail1]]]", harness.root.dump())
    assertEquals(listOf("home"), disposed)
    nav.pop()
    harness.settle()
    assertEquals("root[stack[entry[home:1]]]", harness.root.dump(), "its saved state came back")
    harness.close()
  }

  @Test
  fun aDiscardRouteComesBackNewWithOrWithoutRetention() = runTest {
    for (defaults in listOf(KeliverNavDefaults(frame = GuestFrame), null)) {
      var bump: () -> Unit = {}
      val (harness, nav) = navHost(
        defaults = defaults,
        entryRetention = { if (it is Route.Detail) KeliverNavEntryRetention.Discard else KeliverNavEntryRetention.Retain },
        screen = { route ->
          if (route is Route.Detail) {
            var pin by rememberSaveable { mutableStateOf("") }
            bump = { pin += "1" }
            Leaf(nameOf(route), pin)
          } else {
            Leaf(nameOf(route))
          }
        },
      )
      nav.navigate(Route.Detail(1))
      harness.settle()
      bump()
      harness.settle()
      nav.navigate(Route.Detail(2))
      harness.settle()
      nav.pop()
      harness.settle()
      val dump = harness.root.dump()
      assertTrue("detail1" in dump && "detail1:" !in dump, "the PIN screen must come back empty ($defaults): $dump")
      harness.close()
    }
  }

  @Test
  fun optedOutRoutesTakeNoPlaceInTheDepth() = runTest {
    val (harness, nav) = navHost(
      defaults = KeliverNavDefaults(frame = GuestFrame, depth = 1),
      entryRetention = { if (it == Route.Detail(1)) KeliverNavEntryRetention.Dispose else KeliverNavEntryRetention.Retain },
    )
    nav.navigate(Route.Detail(1))
    harness.settle()
    nav.navigate(Route.Detail(2))
    harness.settle()
    assertEquals("root[stack[entry(hidden)[home],entry[detail2]]]", harness.root.dump())
    nav.pop()
    harness.settle()
    compositions.clear()
    nav.pop()
    harness.settle()
    assertEquals(emptyMap(), compositions, "home was kept within the depth of 1")
    harness.close()
  }

  @Test
  fun replaceStackDisposesEverythingAndComposesOnlyTheDeepLinksTop() = runTest {
    val disposed = mutableListOf<String>()
    val (harness, nav) = navHost(
      screen = { route ->
        val name = nameOf(route)
        DisposableEffect(name) { onDispose { disposed += name } }
        Leaf(name)
      },
    )
    nav.navigate(Route.Detail(1))
    harness.settle()
    nav.navigate(Route.Detail(2))
    harness.settle()

    // Logout then a deep link: every retained screen goes, and only the link's top is composed.
    compositions.clear()
    nav.replaceStack(listOf(Route.Home, Route.Detail(5), Route.Detail(6)))
    harness.settle()
    assertEquals(setOf("home", "detail1", "detail2"), disposed.toSet())
    assertEquals(mapOf("detail6" to 1), compositions)
    assertEquals("root[stack[entry[detail6]]]", harness.root.dump())

    compositions.clear()
    nav.pop()
    harness.settle()
    assertEquals(mapOf("detail5" to 1), compositions, "below the link's top: composed when reached")
    nav.navigate(Route.Detail(7))
    harness.settle()
    compositions.clear()
    nav.pop()
    harness.settle()
    assertEquals(emptyMap(), compositions, "and retained from then on")
    harness.close()
  }

  @Test
  fun trimDisposesHiddenScreensAndKeepsTheirSavedState() = runTest {
    var bump: () -> Unit = {}
    val disposed = mutableListOf<String>()
    val (harness, nav) = navHost(
      screen = { route ->
        val name = nameOf(route)
        DisposableEffect(name) { onDispose { disposed += name } }
        if (route == Route.Home) {
          var n by rememberSaveable { mutableStateOf(0) }
          bump = { n++ }
          Leaf("home", "$n")
        } else {
          Leaf(name)
        }
      },
    )
    bump()
    harness.settle()
    nav.navigate(Route.Detail(1))
    harness.settle()
    nav.navigate(Route.Detail(2))
    harness.settle()

    trimKeliverNavRetention()
    harness.settle()
    assertEquals("root[stack[entry[detail2]]]", harness.root.dump())
    assertEquals(setOf("home", "detail1"), disposed.toSet())

    nav.navigate(Route.Detail(3))
    harness.settle()
    assertEquals("root[stack[entry(hidden)[detail2],entry[detail3]]]", harness.root.dump(), "retained again after")

    nav.popUntil { it == Route.Home }
    harness.settle()
    assertEquals("root[stack[entry[home:1]]]", harness.root.dump(), "composed again with its saved state")
    harness.close()
  }
}
