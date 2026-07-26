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
package dev.keliver.portal.render

import androidx.compose.runtime.Composable
import dev.keliver.capabilities.HostHttp

/**
 * P3-12: the EXPLICIT per-app preview entry point. The consumer app's logic
 * compiles INTO the preview binary (Kotlin/Wasm has no dynamic linking) and
 * registers one [AppPreviewEntry] mapping portal screen names to real
 * presenters. No reflection, no naming heuristics — the app hand-writes the
 * same kind of wiring it already writes in its PublishedEntry.
 *
 * Contract per screen: a @Composable [ScreenPreview.present] runs the REAL
 * presenter (remember/LaunchedEffect/flows all work; the editor keys it on
 * (project, screen, build) so switching screens disposes it) and returns each
 * recomposition's [PreviewFrame]:
 *  - [PreviewFrame.values]: contract field -> STRINGIFIED value, matching the
 *    string-typed [PreviewBindings.mocks] transport (list fields are
 *    pipe-joined per row-field; see [joinRows]). Everything downstream —
 *    typed getters, Repeat row resolution, component expansion — works
 *    unchanged on top of this.
 *  - [PreviewFrame.dispatch]: portal actions (canvas taps, ⚡ console) are
 *    delivered here; the app routes them to the real bindings members.
 */
public fun interface ScreenPreview {
  @Composable
  public fun present(env: PreviewEnv): PreviewFrame
}

/** What the editor provides to a presenting screen. */
public class PreviewEnv(
  /** Log a line into the editor's action console (e.g. navigation intents). */
  public val log: (String) -> Unit,
  /**
   * #13 F4: for a FLOW preview, the screen to START the walkthrough on (null =
   * the flow's declared start). A FlowPreview inits its back-stack from this, so
   * the editor can preview a flow from ANY node (deep-link / mid-flow state),
   * not only the entry screen.
   */
  public val flowStart: String? = null,
  /**
   * Roadmap #16: the app-owned capability/domain start-state selected in the
   * editor. Preview wiring creates a fresh typed capability graph from this
   * persona; null preserves the pre-persona behavior for existing entries.
   */
  public val persona: PreviewPersona? = null,
  /**
   * #16 H1: deterministic text HTTP capability for real repositories in the
   * browser. Null means the selected persona has no usable replay fixture.
   */
  public val http: HostHttp? = null,
)

public class PreviewFrame(
  public val values: Map<String, String>,
  public val dispatch: (action: String, arg: String?) -> Unit,
)

public interface AppPreviewEntry {
  /** portal screen name (doc name, e.g. "feed") -> its live presenter wiring. */
  public val screens: Map<String, ScreenPreview>

  /** Shown in the fidelity panel, e.g. "portal-app-lib (Field Notes)". */
  public val label: String get() = "app preview entry"

  /** App-owned, reviewed start-states available to real presenter preview. */
  public val personas: List<PreviewPersona> get() = emptyList()

  /** Initial selection. Must name one of [personas] when non-null. */
  public val defaultPersonaId: String? get() = personas.firstOrNull()?.id
}

/** Set once by the per-app editor build's main(); null = mock tier only. */
public var appPreviewEntry: AppPreviewEntry? = null

/**
 * Helper for list-of-rows contract fields: emits the row COUNT under [field]
 * (drives [PreviewBindings.rowCount]) and each row field's values pipe-joined
 * under "item.field" keys (drives [resolveItemRow]).
 */
public fun MutableMap<String, String>.putRows(
  field: String,
  itemVar: String,
  rows: List<Map<String, String>>,
) {
  this[field] = rows.size.toString()
  val keys = rows.flatMap { it.keys }.toSet()
  for (k in keys) {
    // Namespaced by [field] so multiple lists sharing an itemVar stay distinct
    // (resolveItemRow reads "$itemsField.$itemVar.$k", falling back to the plain key).
    this["$field.$itemVar.$k"] = rows.joinToString("|") { it[k].orEmpty().replace("|", "/") }
  }
}
