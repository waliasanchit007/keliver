/*
 * TEMPORARY diagnostic (U19 reconciliation, 2026-09-11). Identical file in every
 * variant under comparison. Counts what the host actually does:
 *
 *   hostFrames      — iterations of EditorShell's frame pump = host frames DELIVERED
 *   hostContent     — recompositions of the ComposeViewport content
 *   presenterFrames — LiveEngine.applyValues calls = presenter frames projected
 *
 * Published to a JS global so a CDP driver can read it without touching the DOM.
 */
internal object FrameProbe {
  private var hostFrames = 0
  private var hostContent = 0
  private var presenterFrames = 0

  internal fun hostFrame() { hostFrames++; publish() }
  internal fun hostContentComposed() { hostContent++; publish() }
  internal fun presenterFrame() { presenterFrames++; publish() }

  private fun publish() = probe(hostFrames, hostContent, presenterFrames)
}

private fun probe(h: Int, c: Int, p: Int): Unit =
  js("void (globalThis.__probe = { hostFrames: h, hostContent: c, presenterFrames: p })")
