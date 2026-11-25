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
package dev.keliver.treehouse.composeui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import app.cash.zipline.Zipline
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import dev.keliver.Modifier as RedwoodModifier
import dev.keliver.compose.ExperimentalKeliverBackApi
import dev.keliver.treehouse.AppService
import dev.keliver.treehouse.Content
import dev.keliver.treehouse.TreehouseApp
import dev.keliver.treehouse.TreehouseContentSource
import dev.keliver.treehouse.TreehouseDispatchers
import dev.keliver.treehouse.TreehouseView
import dev.keliver.ui.Cancellable
import dev.keliver.ui.OnBackPressedDispatcher
import dev.keliver.ui.UiConfiguration
import dev.keliver.widget.WidgetSystem
import kotlin.test.Test
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * NF5 H0: content composed with `backEnabled = false` (the hidden app of an update swap; later the
 * live content beneath a cached first frame) gives none of its guest's back handlers a back press.
 * The guests here register their handlers as Treehouse does for a real guest: on the dispatcher of
 * the view the content binds to (TreehouseAppContent.addOnBackPressedCallback).
 */
@OptIn(ExperimentalTestApi::class, ExperimentalKeliverBackApi::class)
class TreehouseContentBackTest {
  private val platform = FakePlatformBackDispatcher()

  @Test
  fun backWithHiddenContentReachesNoGuest() = runComposeUiTest {
    val shown = FakeApp("shown guest", platform.received)
    val hidden = FakeApp("hidden guest", platform.received)
    setContent {
      // As the host templates draw an update swap: the hidden app beneath, the app on show on top.
      Box {
        Content(hidden, backEnabled = false)
        Content(shown, backEnabled = true)
      }
    }
    waitForIdle()
    shown.guestRegistersBackHandler()
    hidden.guestRegistersBackHandler() // the newer app's guest registers last, as on a real swap

    platform.back()
    platform.back()

    assertThat(platform.received).containsExactly("shown guest", "shown guest")
  }

  @Test
  fun hiddenContentAloneLeavesBackToTheActivity() = runComposeUiTest {
    val hidden = FakeApp("hidden guest", platform.received)
    setContent { Content(hidden, backEnabled = false) }
    waitForIdle()
    hidden.guestRegistersBackHandler()

    platform.back()

    assertThat(platform.received).containsExactly("activity")
  }

  @Test
  fun contentThatIsShownGetsItsGuestsBackFromThatFrameOn() = runComposeUiTest {
    val old = FakeApp("old guest", platform.received)
    val new = FakeApp("new guest", platform.received)
    var front by mutableStateOf(old)
    setContent {
      Box {
        // The swap: the new app stays composed (key) and is shown; the old one goes.
        for (app in listOf(new, old).filter { it === new || it === front }) {
          key(app) { Content(app, backEnabled = app === front) }
        }
      }
    }
    waitForIdle()
    old.guestRegistersBackHandler()
    new.guestRegistersBackHandler()
    platform.back()

    front = new
    waitForIdle()
    platform.back()

    assertThat(platform.received).containsExactly("old guest", "new guest")
    assertThat(old.boundViews).isEmpty() // the old app's content unbound, its handlers removed
  }

  @Test
  fun contentWithoutTheGateIsTheGuestsBackAsBefore() = runComposeUiTest {
    val app = FakeApp("guest", platform.received)
    setContent { Content(app, backEnabled = true) }
    waitForIdle()
    app.guestRegistersBackHandler()

    platform.back()

    assertThat(platform.received).containsExactly("guest")
  }

  @Composable
  private fun Content(app: FakeApp, backEnabled: Boolean) {
    // The public overload, on a desktop "activity" whose back dispatcher behaves as Android's.
    CompositionLocalProvider(LocalDesktopBackDispatcher provides platform) {
      TreehouseContent(
        treehouseApp = app,
        widgetSystem = NoWidgets,
        contentSource = { error("the fake content never asks for a UI") },
        backEnabled = backEnabled,
      )
    }
  }
}

internal interface FakeAppService : AppService

internal object NoWidgets : WidgetSystem<@Composable (Modifier) -> Unit> {
  override fun apply(value: @Composable (Modifier) -> Unit, element: RedwoodModifier.UnscopedElement) = Unit
}

/** A Treehouse app whose one guest registers a back handler when the test says so. */
internal class FakeApp(
  private val guestName: String,
  private val received: MutableList<String>,
) : TreehouseApp<FakeAppService>() {
  override val name: String = guestName
  override val dispatchers: TreehouseDispatchers get() = error("unused")
  override val zipline: StateFlow<Zipline?> = MutableStateFlow(null)

  /** Views bound now, each with the back handlers its guest registered on it. */
  val boundViews = mutableMapOf<TreehouseView<*>, MutableList<Cancellable>>()

  /** The guest's code ran and composed a `BackHandler`: Treehouse registers it on the bound view. */
  fun guestRegistersBackHandler() {
    for ((view, handlers) in boundViews) {
      handlers += view.onBackPressedDispatcher.addCallback(GuestBackHandler(guestName, enabled = true, received))
    }
  }

  override fun createContent(source: TreehouseContentSource<FakeAppService>): Content = object : Content {
    var bound: TreehouseView<*>? = null
    override val state = MutableStateFlow(Content.State(loadCount = 0, attached = false, deliveredChangeCount = 0))

    override fun preload(onBackPressedDispatcher: OnBackPressedDispatcher, uiConfiguration: StateFlow<UiConfiguration>) = Unit

    override fun bind(view: TreehouseView<*>) {
      bound = view
      boundViews[view] = mutableListOf()
    }

    override fun unbind() {
      val view = bound ?: return
      bound = null
      boundViews.remove(view)?.forEach { it.cancel() }
    }
  }

  override fun start() = Unit
  override fun stop() = Unit
  override fun restart() = Unit
  override fun close() = Unit
}
