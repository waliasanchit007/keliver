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
  /**
   * C1: app-owned reusable components ("molecules"). Null = a `components`
   * directory that is a SIBLING of [screensDir] (so old config files stay valid
   * and new repos get it for free). Set explicitly to relocate.
   */
  val componentsDir: String? = null,
  val publishTask: String = ":portal-published-guest:compileDevelopmentZipline",
  val publishOutput: String = "portal-published-guest/build/zipline/Development",
  val store: String = "~/.keliver-portal",
  /**
   * P3-12 live-presenter preview: logic dirs to watch (null = a `logic` sibling
   * of screensDir), the gradle task that rebuilds the per-app editor, where its
   * dist lands, and where SUCCESSFUL builds are promoted for serving (the
   * last-known-good copy an http server should serve).
   */
  val logicDirs: List<String>? = null,
  /** #13 F1: flow declarations (flow{} DSL). Null = a `flows` sibling of [screensDir]. */
  val flowsDir: String? = null,
  val previewBuildTask: String = ":web-spike:wasmJsBrowserDistribution",
  val previewDist: String = "web-spike/build/dist/wasmJs/productionExecutable",
  val previewServeDir: String = "build/portal-editor-live",
)

fun PortalConfig.resolvedLogicDirs(): List<String> =
  logicDirs ?: listOf(
    screensDir.substringBeforeLast('/', "").let { if (it.isEmpty()) "logic" else "$it/logic" },
  )

/** The resolved components dir: explicit [componentsDir], else a `components` sibling of screens. */
fun PortalConfig.resolvedComponentsDir(): String =
  componentsDir ?: (screensDir.substringBeforeLast('/', "") .let { if (it.isEmpty()) "components" else "$it/components" })

/** The resolved flows dir: explicit [flowsDir], else a `flows` sibling of screens. */
fun PortalConfig.resolvedFlowsDir(): String =
  flowsDir ?: (screensDir.substringBeforeLast('/', "").let { if (it.isEmpty()) "flows" else "$it/flows" })

fun loadPortalConfig(repoDir: File): PortalConfig {
  val f = File(repoDir, "keliver.portal.json")
  if (!f.exists()) return PortalConfig()
  return Json { ignoreUnknownKeys = true }.decodeFromString(PortalConfig.serializer(), f.readText())
}

fun PortalConfig.storeDir(): File =
  if (store.startsWith("~/")) File(System.getProperty("user.home"), store.removePrefix("~/")) else File(store)
