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

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.runComposeUiTest
import dev.keliver.Modifier as KeliverModifier
import dev.keliver.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * NF1: a generated renderer holds one snapshot state (a version its setters bump), not one per
 * property. A value set again to an equal value must not recompose the renderer (as the old
 * per-property `mutableStateOf`'s structural policy did not), and a changed one must, for every
 * kind of property: a required value, a value with a default, an event, the modifier, and a lazy
 * list's before and after counts. Setters run as Redwood's host runs them: on the UI thread,
 * outside composition.
 */
@OptIn(ExperimentalTestApi::class)
class OneStatePerWidgetTest {
  /** Counts the renderer's runs: every run applies its own modifiers, which walks this chain once. */
  private class Runs {
    var count = 0
  }

  /** A guest modifier with no elements, equal to another of the same [id]. */
  private class CountingModifier(val id: Int, val runs: Runs) : KeliverModifier {
    override fun forEach(block: (KeliverModifier.Element) -> Unit) {}
    override fun forEachScoped(block: (KeliverModifier.ScopedElement) -> Unit) {}
    override fun forEachUnscoped(block: (KeliverModifier.UnscopedElement) -> Unit) {
      runs.count++
    }

    override fun equals(other: Any?) = other is CountingModifier && other.id == id
    override fun hashCode() = id
  }

  private fun ComposeUiTest.assertRecomposes(runs: Runs, expected: Boolean, what: String, change: () -> Unit) {
    waitForIdle()
    val before = runs.count
    runOnIdle {
      change()
      Snapshot.sendApplyNotifications()
    }
    waitForIdle()
    if (expected) {
      assertTrue(runs.count > before, "$what: recomposed (${runs.count} runs, $before before)")
    } else {
      assertEquals(before, runs.count, "$what: not recomposed")
    }
  }

  @Test fun aRequiredValueAndAValueWithADefault() = runComposeUiTest {
    val runs = Runs()
    val text = ComposeUiGenText2().apply {
      modifier = CountingModifier(1, runs)
      text("a")
    }
    setContent { text.value(Modifier) }
    waitForIdle()
    assertTrue(runs.count > 0, "the renderer ran")

    assertRecomposes(runs, false, "the same text") { text.text("a") }
    assertRecomposes(runs, true, "a new text") { text.text("b") }
    assertRecomposes(runs, true, "a color sent the first time") { text.color(Color(0xFF112233)) }
    assertRecomposes(runs, false, "an equal color") { text.color(Color(0xFF112233)) }
    assertRecomposes(runs, true, "a new color") { text.color(Color(0xFF445566)) }
  }

  @Test fun anEvent() = runComposeUiTest {
    val runs = Runs()
    val button = ComposeUiGenButton1().apply { modifier = CountingModifier(1, runs) }
    val first: () -> Unit = {}
    button.onClick(first)
    setContent { button.value(Modifier) }
    waitForIdle()

    assertRecomposes(runs, false, "the same lambda") { button.onClick(first) }
    assertRecomposes(runs, true, "a new lambda") { button.onClick {} }
    assertRecomposes(runs, true, "no lambda") { button.onClick(null) }
    assertRecomposes(runs, false, "no lambda again") { button.onClick(null) }
  }

  @Test fun theModifier() = runComposeUiTest {
    val runs = Runs()
    val box = ComposeUiGenBox2().apply { modifier = CountingModifier(1, runs) }
    setContent { box.value(Modifier) }
    waitForIdle()

    assertRecomposes(runs, false, "an equal modifier") { box.modifier = CountingModifier(1, runs) }
    assertRecomposes(runs, true, "a new modifier") { box.modifier = CountingModifier(2, runs) }
  }

  @Test fun aLazyListsCounts() = runComposeUiTest {
    val runs = Runs()
    val list = ComposeUiGenLazyColumn1().apply { modifier = CountingModifier(1, runs) }
    setContent { list.value(Modifier) }
    waitForIdle()

    assertRecomposes(runs, false, "the same before count") { list.contentBefore(0) }
    assertRecomposes(runs, true, "a new after count") { list.contentAfter(10) }
    assertRecomposes(runs, false, "the same after count") { list.contentAfter(10) }
    // The list's content lambda runs in a derived state: a count changed alone (no child added or
    // removed) must still reach the list, which now has ten placeholders to scroll to.
    onNode(hasScrollToIndexAction()).performScrollToIndex(9)
    assertRecomposes(runs, true, "a new before count") { list.contentBefore(5) }
    onNode(hasScrollToIndexAction()).performScrollToIndex(14)
  }
}
