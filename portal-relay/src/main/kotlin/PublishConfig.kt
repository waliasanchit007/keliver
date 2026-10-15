/*
 * The relay reads keliver.portal.json once, at start. A changed publishTask or
 * publishOutput (switching a guest to its Production bundle, say) used to need
 * a relay restart, and until then publish silently built the old task. Each
 * publish now reads the file again and uses its publishTask and publishOutput;
 * any other setting that changed is named in the response as needing a restart,
 * since the relay's routes, store and watchers were set up from the old values.
 */
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

internal object PublishConfig {
  /** The settings publish takes from the file as it is now. */
  val PER_PUBLISH = setOf("publishTask", "publishOutput")

  private val json = Json { encodeDefaults = true }

  /** The file now, or why it cannot be used: a publish never guesses past a broken config. */
  fun current(repoDir: File): Result<PortalConfig> = runCatching { loadPortalConfig(repoDir) }

  /** Settings other than [PER_PUBLISH] that differ between [started] and [now], sorted. */
  fun restartNeeded(started: PortalConfig, now: PortalConfig): List<String> {
    val a = json.encodeToJsonElement(PortalConfig.serializer(), started).jsonObject
    val b = json.encodeToJsonElement(PortalConfig.serializer(), now).jsonObject
    return (a.keys + b.keys).filter { it !in PER_PUBLISH && a[it] != b[it] }.sorted()
  }
}
