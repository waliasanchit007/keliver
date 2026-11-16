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
@file:OptIn(ExperimentalKeliverNavRetentionApi::class)

package dev.keliver.sample.threeway

import androidx.compose.runtime.Composable
import dev.keliver.compose.BackHandler
import dev.keliver.compose.foundation.layout.Box
import dev.keliver.compose.foundation.layout.fillMaxSize
import dev.keliver.compose.foundation.layout.offset
import dev.keliver.compose.gatea.compose.ExtraRetained
import dev.keliver.compose.gatea.compose.ExtraRetainedEntry
import dev.keliver.compose.ui.Modifier
import dev.keliver.compose.ui.draw.alpha
import dev.keliver.compose.ui.unit.dp
import dev.keliver.nav.ExperimentalKeliverNavRetentionApi
import dev.keliver.nav.KeliverNavFrame
import dev.keliver.nav.KeliverNavHostFrame
import dev.keliver.nav.KeliverNavRetention
import dev.keliver.nav.KeliverRetainedStack

// The guest's `retain` experiment (NF1, docs/PLAN_2026-11.md WS7): keliver-nav's retained stack with
// a frame built from the generated API's own widgets. `retainhost`: the same stack in the host's
// retained container (gate A's ExtraRetained), whose system back the host shows in the frame of the
// press. Guest only: the native copy of ThreewayScreens.kt never sees this file.

/**
 * Entries overlap in a full-size box; a hidden entry keeps its size (so the list's viewport does not
 * change and nothing is re-laid out by the guest), is moved far outside the screen (so it gets no
 * input) and is fully transparent. Only the entry box's modifier changes when it is shown or hidden.
 * A prototype's stand-in: it does not take focus or the keyboard from a hidden entry, nor hide it
 * from accessibility on every host (see KeliverNavFrame).
 */
internal object GateAFrame : KeliverNavFrame {
  private val Shown = Modifier.fillMaxSize()
  private val Hidden = Modifier.fillMaxSize().offset(x = 100_000.dp).alpha(0f)

  @Composable
  override fun Stack(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize()) { content() }
  }

  @Composable
  override fun Entry(visible: Boolean, content: @Composable () -> Unit) {
    Box(if (visible) Shown else Hidden) { content() }
  }
}

/** The app's two screens on keliver-nav's retained stack (depth 1: the list under one detail). */
internal object RetainedThreewayStack : ThreewayStack {
  private val retention = KeliverNavRetention(GateAFrame, depth = 1)

  @Composable
  override fun Show(keys: List<Any>, onBack: () -> Unit, content: @Composable (Any) -> Unit) {
    // System back through the guest (the round trip host back removes).
    BackHandler(enabled = keys.size > 1) { onBack() }
    KeliverRetainedStack(keys, retention, content)
  }
}

/**
 * The host's retained container (gate A's `ExtraRetained`): the host shows the last entry, hides
 * the others (not laid out or drawn, no input, focus or semantics), and goes back on its own.
 */
internal object GateAHostFrame : KeliverNavHostFrame {
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

/**
 * `retainhost`: the retained stack in the host's container. A system back press over the detail is
 * shown by the host at once; the guest then goes back ([onBack], from `onHostBack`). The guest's own
 * BackHandler stays as the fallback for presses the host does not take.
 */
internal object RetainedHostThreewayStack : ThreewayStack {
  private val retention = KeliverNavRetention(GateAHostFrame, depth = 1)

  @Composable
  override fun Show(keys: List<Any>, onBack: () -> Unit, content: @Composable (Any) -> Unit) {
    BackHandler(enabled = keys.size > 1) { onBack() }
    KeliverRetainedStack(keys, retention, onHostBack = { onBack() }, content = content)
  }
}
