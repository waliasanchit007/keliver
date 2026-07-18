import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.keliver.portal.render.AppPreviewEntry
import dev.keliver.portal.render.PreviewEnv
import dev.keliver.portal.render.PreviewFrame
import dev.keliver.portal.render.ScreenPreview
import dev.keliver.portal.render.putRows
import dev.keliver.portalpublished.logic.FeedPresenter
import dev.keliver.portalpublished.logic.MainPresenter
import dev.keliver.portalpublished.screens.SettingsScreenBindings

/**
 * P3-12: the APP-OWNED preview entry for this repo's dogfood (Field Notes).
 * This is the per-app wiring the contract asks every consumer app to write —
 * the same explicitness as PublishedEntry, one small function per screen.
 * The presenters below are the REAL ones (portal-app-lib commonMain), running
 * against the preview SQL capability; only the driver differs from devices.
 */
object AppLibPreview : AppPreviewEntry {
  override val label = "portal-app-lib (Field Notes)"

  // One shared in-memory SQL capability per editor session (like one device DB).
  private val sqlHost = PreviewSqlHost()
  private fun driver() = if (PreviewCapabilities.sqlAvailable) PreviewSqlDriver(sqlHost) else null

  override val screens: Map<String, ScreenPreview> = mapOf(
    "main" to ScreenPreview { _ ->
      val b = MainPresenter(remember { driver() })
      PreviewFrame(
        values = mapOf("text" to b.text),
        dispatch = { a, _ -> if (a == "buyTapped") b.buyTapped() },
      )
    },
    "feed" to ScreenPreview { env ->
      val b = FeedPresenter(remember { driver() }, onOpenNote = { env.log("→ open note #$it (nav intent)") })
      PreviewFrame(
        values = buildMap {
          put("subtitle", b.subtitle)
          put("draft", b.draft)
          put("isEmpty", b.isEmpty.toString())
          putRows("notes", "note", b.notes.map {
            mapOf("id" to it.id, "title" to it.title, "body" to it.body, "time" to it.time)
          })
        },
        dispatch = { a, arg ->
          when (a) {
            "onDraftChange" -> b.onDraftChange(arg ?: "live draft")
            "addNote" -> b.addNote()
            "clearAll" -> b.clearAll()
            "openNote" -> b.openNote(arg ?: "1")
          }
        },
      )
    },
    "settings" to ScreenPreview { env ->
      // Hand presenter (no repo yet) — proves components + live values together.
      val b = remember {
        object : SettingsScreenBindings {
          override val name: String = "Live Presenter"
          override fun open(value: String) = env.log("→ open $value (nav intent)")
        }
      }
      PreviewFrame(
        values = mapOf("name" to b.name),
        dispatch = { a, arg -> if (a == "open") b.open(arg ?: "?") },
      )
    },
  )
}
