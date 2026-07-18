package dev.keliver.portal.render

import androidx.compose.runtime.Composable

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
fun interface ScreenPreview {
  @Composable
  fun present(env: PreviewEnv): PreviewFrame
}

/** What the editor provides to a presenting screen. */
class PreviewEnv(
  /** Log a line into the editor's action console (e.g. navigation intents). */
  val log: (String) -> Unit,
)

class PreviewFrame(
  val values: Map<String, String>,
  val dispatch: (action: String, arg: String?) -> Unit,
)

interface AppPreviewEntry {
  /** portal screen name (doc name, e.g. "feed") -> its live presenter wiring. */
  val screens: Map<String, ScreenPreview>
  /** Shown in the fidelity panel, e.g. "portal-app-lib (Field Notes)". */
  val label: String get() = "app preview entry"
}

/** Set once by the per-app editor build's main(); null = mock tier only. */
var appPreviewEntry: AppPreviewEntry? = null

/**
 * Helper for list-of-rows contract fields: emits the row COUNT under [field]
 * (drives [PreviewBindings.rowCount]) and each row field's values pipe-joined
 * under "item.field" keys (drives [resolveItemRow]).
 */
fun MutableMap<String, String>.putRows(
  field: String,
  itemVar: String,
  rows: List<Map<String, String>>,
) {
  this[field] = rows.size.toString()
  val keys = rows.flatMap { it.keys }.toSet()
  for (k in keys) {
    this["$itemVar.$k"] = rows.joinToString("|") { it[k].orEmpty().replace("|", "/") }
  }
}
