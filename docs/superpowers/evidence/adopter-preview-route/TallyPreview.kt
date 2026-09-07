/*
 * Tally's per-app preview entry: maps the portal screen name (the .kt basename
 * the relay ingests — here "home") to a ScreenPreview that runs the app's REAL
 * presenter. No mock logic: the values come from HomePresenter and the action
 * is routed back into it.
 */
import dev.keliver.portal.render.AppPreviewEntry
import dev.keliver.portal.render.PreviewFrame
import dev.keliver.portal.render.ScreenPreview
import tally.logic.HomePresenter

object TallyPreview : AppPreviewEntry {
  override val label = "tally (real presenter)"

  override val screens: Map<String, ScreenPreview> = mapOf(
    "home" to ScreenPreview { env ->
      val b = HomePresenter()                    // the app's own presenter
      PreviewFrame(
        values = mapOf("tally" to b.tally),      // contract field -> current value
        dispatch = { action, _ ->                // portal actions -> the bindings
          when (action) {
            "add" -> b.add()
            else -> env.log("unhandled action: $action")
          }
        },
      )
    },
  )
}
