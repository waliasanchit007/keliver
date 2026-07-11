import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Separability groundwork: everything the portal-server needs to know about
 * the app repo it serves, read from keliver.portal.json at the repo root.
 * Every field defaults to this repo's layout, so the file is optional here
 * and REQUIRED only for a future split-out app repo.
 */
@Serializable
data class PortalConfig(
  val port: Int = 8077,
  val screensDir: String = "portal-app-lib/src/jsMain/kotlin/screens",
  val publishTask: String = ":portal-published-guest:compileDevelopmentZipline",
  val publishOutput: String = "portal-published-guest/build/zipline/Development",
  val store: String = "~/.keliver-portal",
)

fun loadPortalConfig(repoDir: File): PortalConfig {
  val f = File(repoDir, "keliver.portal.json")
  if (!f.exists()) return PortalConfig()
  return Json { ignoreUnknownKeys = true }.decodeFromString(PortalConfig.serializer(), f.readText())
}

fun PortalConfig.storeDir(): File =
  if (store.startsWith("~/")) File(System.getProperty("user.home"), store.removePrefix("~/")) else File(store)
