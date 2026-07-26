import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import app.cash.sqldelight.db.SqlDriver
import dev.keliver.capabilities.AuthState
import dev.keliver.capabilities.CapabilityFixtures
import dev.keliver.portal.render.AppPreviewEntry
import dev.keliver.portal.render.PreviewEnv
import dev.keliver.portal.render.PreviewFrame
import dev.keliver.portal.render.PreviewPersona
import dev.keliver.portal.render.ScreenPreview
import dev.keliver.portal.render.putRows
import dev.keliver.portalpublished.logic.FeedPresenter
import dev.keliver.portalpublished.logic.MainPresenter
import dev.keliver.portalpublished.logic.SettingsPresenter

@Composable
private fun previewDriver(personaId: String?): SqlDriver? {
  val host = remember(personaId) { PreviewSqlHost() }
  return remember(host) {
    if (PreviewCapabilities.sqlAvailable) PreviewSqlDriver(host) else null
  }
}

/**
 * P3-12: the APP-OWNED preview entry for this repo's dogfood (Field Notes).
 * This is the per-app wiring the contract asks every consumer app to write —
 * the same explicitness as PublishedEntry, one small function per screen.
 * The presenters below are the REAL ones (portal-app-lib commonMain), running
 * against the preview SQL capability; only the driver differs from devices.
 */
object AppLibPreview : AppPreviewEntry {
  override val label = "portal-app-lib (Field Notes)"

  override val personas: List<PreviewPersona> = listOf(
    PreviewPersona(
      id = "signed-out",
      label = "Signed out",
      description = "No authenticated subject; flags use their conservative defaults.",
    ),
    PreviewPersona(
      id = "field-researcher",
      label = "Field researcher",
      description = "Authenticated researcher with the new profile treatment enabled.",
      auth = AuthState.SignedIn(
        subject = "researcher-42",
        displayName = "Maya Chen",
        attributes = mapOf("plan" to "field"),
      ),
      flags = mapOf("new-profile" to true),
      httpFixtureSet = "field-researcher",
    ),
    PreviewPersona(
      id = "kyc-pending",
      label = "KYC pending",
      description = "Authenticated account waiting for identity review.",
      auth = AuthState.SignedIn(
        subject = "applicant-17",
        displayName = "Ari Patel",
        attributes = mapOf("kyc" to "pending"),
      ),
      states = mapOf("HostKyc@1" to "pending"),
    ),
  )

  override val defaultPersonaId: String = "field-researcher"

  override val screens: Map<String, ScreenPreview> = mapOf(
    "main" to ScreenPreview { env ->
      val b = MainPresenter(previewDriver(env.persona?.id))
      PreviewFrame(
        values = mapOf("text" to b.text),
        dispatch = { a, _ -> if (a == "buyTapped") b.buyTapped() },
      )
    },
    "feed" to ScreenPreview { env ->
      val b = FeedPresenter(
        previewDriver(env.persona?.id),
        onOpenNote = { env.log("→ open note #$it (nav intent)") },
      )
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
      val fixtures = remember(env.persona?.id) {
        env.persona?.createCapabilityFixtures() ?: CapabilityFixtures()
      }
      val b = SettingsPresenter(
        auth = fixtures.auth,
        flags = fixtures.flags,
        analytics = fixtures.analytics,
        http = env.http,
        onOpen = { env.log("→ open $it (analytics recorded; nav intent)") },
      )
      PreviewFrame(
        values = mapOf("name" to b.name),
        dispatch = { a, arg -> if (a == "open") b.open(arg ?: "?") },
      )
    },
  )
}
