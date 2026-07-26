import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.keliver.portal.render.AppFlowEntry
import dev.keliver.portal.render.FlowFrame
import dev.keliver.portal.render.FlowPreview
import dev.keliver.portal.render.PreviewFrame
import dev.keliver.portal.render.putRows
import dev.keliver.portalpublished.flows.FieldNotesFlow
import dev.keliver.portalpublished.logic.DetailPresenter
import dev.keliver.portalpublished.logic.FeedPresenter

/**
 * #13 F2 dogfood: the Field Notes FLOW preview — the app-owned FlowScope for
 * feed --openNote--> detail --back--> feed. The back-stack (screen + note id)
 * and the shared SQL capability live at FLOW scope, so they survive screen
 * swaps; each screen's presenter is screen-lifetime, exactly like a device.
 * The flow's START comes from the same FieldNotesFlow declaration the relay
 * derives the graph from — one source.
 */
object AppLibFlows : AppFlowEntry {
  override val label = "portal-app-lib (Field Notes)"

  override val flows: Map<String, FlowPreview> = mapOf(
    FieldNotesFlow.name to FlowPreview { env ->
      // Each persona owns a fresh app graph. No SQL rows leak across persona
      // switches; state still survives screen navigation within one flow.
      val sqlHost = remember(env.persona?.id) { PreviewSqlHost() }
      // FlowScope: back-stack of (screen, arg). Survives navigation — the
      // editor keys the composition by flow (+start), so a start-override
      // re-inits us. #13 F4: begin on env.flowStart when the editor deep-links.
      var stack by remember {
        mutableStateOf(listOf((env.flowStart ?: FieldNotesFlow.start) to null as String?))
      }
      val (screen, arg) = stack.last()
      val driver = remember(sqlHost) {
        if (PreviewCapabilities.sqlAvailable) PreviewSqlDriver(sqlHost) else null
      }

      val frame: PreviewFrame = when (screen) {
        "detail" -> key("detail:$arg") {
          val b = DetailPresenter(driver, noteId = arg ?: "1", onBack = { stack = stack.dropLast(1) })
          PreviewFrame(
            values = mapOf("title" to b.title, "body" to b.body, "time" to b.time),
            dispatch = { a, _ -> if (a == "back") b.back() },
          )
        }
        else -> key("feed") {
          val b = FeedPresenter(driver, onOpenNote = { id ->
            env.log("→ openNote #$id — navigating to detail")
            stack = stack + ("detail" to id)
          })
          PreviewFrame(
            values = buildMap {
              put("subtitle", b.subtitle)
              put("draft", b.draft)
              put("isEmpty", b.isEmpty.toString())
              putRows("notes", "note", b.notes.map {
                mapOf("id" to it.id, "title" to it.title, "body" to it.body, "time" to it.time)
              })
            },
            dispatch = { a, dispArg ->
              when (a) {
                "onDraftChange" -> b.onDraftChange(dispArg ?: "live draft")
                "addNote" -> b.addNote()
                "clearAll" -> b.clearAll()
                "openNote" -> b.openNote(dispArg ?: "1")
              }
            },
          )
        }
      }
      FlowFrame(screen, frame.values, frame.dispatch)
    },
  )
}
