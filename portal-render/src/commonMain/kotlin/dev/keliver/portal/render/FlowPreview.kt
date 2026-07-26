package dev.keliver.portal.render

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * #13 F2 — the per-app FLOW preview entry, the flow-shaped sibling of
 * [AppPreviewEntry]. A [FlowPreview] composes the app's FlowScope (a back-stack
 * of routes + flow-lifetime state) and, each recomposition, returns which
 * screen the flow is showing NOW plus that screen's frame. The editor keys the
 * composition by FLOW (not screen), so navigating between screens preserves
 * flow state; when [FlowFrame.screen] changes, the editor follows by loading
 * that screen's tree — navigation in the preview, the same UDF a device runs.
 */
public fun interface FlowPreview {
  @Composable
  public fun present(env: PreviewEnv): FlowFrame
}

public class FlowFrame(
  /** The screen this flow is currently showing (a portal doc name, e.g. "feed"). */
  public val screen: String,
  public val values: Map<String, String>,
  public val dispatch: (action: String, arg: String?) -> Unit,
)

public interface AppFlowEntry {
  /** flow name (as declared in flows/, e.g. "FieldNotes") -> its preview wiring. */
  public val flows: Map<String, FlowPreview>
  public val label: String get() = "app flow entry"
}

/** Set by the per-app editor build's main(); null = no flow preview available. */
public var appFlowEntry: AppFlowEntry? = null

/**
 * The back-stack every FlowScope needs (design §3.2): [current] is the top,
 * [navigate] pushes, [back] pops (never below the start). Flow-lifetime state
 * belongs beside this in the app's FlowPreview — it survives screen switches
 * because the editor keys the composition by flow.
 */
public class FlowNav<T>(
  public val current: T,
  public val canGoBack: Boolean,
  public val navigate: (T) -> Unit,
  public val back: () -> Unit,
)

@Composable
public fun <T> rememberFlowNav(start: T): FlowNav<T> {
  var stack by remember { mutableStateOf(listOf(start)) }
  return FlowNav(
    current = stack.last(),
    canGoBack = stack.size > 1,
    navigate = { stack = stack + it },
    back = { if (stack.size > 1) stack = stack.dropLast(1) },
  )
}
