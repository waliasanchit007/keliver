import dev.keliver.portal.render.AppPreviewEntry
import dev.keliver.portal.render.PreviewFrame
import dev.keliver.portal.render.ScreenPreview
import ledger.logic.HomePresenter

object LedgerPreview : AppPreviewEntry {
  override val label = "ledger (real presenter)"

  override val screens: Map<String, ScreenPreview> = mapOf(
    "home" to ScreenPreview { env ->
      val b = HomePresenter()
      PreviewFrame(
        values = mapOf("summary" to b.summary),
        dispatch = { action, _ ->
          when (action) {
            "record" -> b.record()
            else -> env.log("unhandled action: $action")
          }
        },
      )
    },
  )
}
