package inventory.device

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.cash.zipline.Zipline
import app.cash.zipline.ZiplineService
import dev.keliver.http.HostHttpProvider
import dev.keliver.http.HttpRequest
import dev.keliver.layout.compose.Column
import dev.keliver.material.compose.StyledText
import dev.keliver.material.protocol.guest.KeliverMaterialProtocolWidgetSystemFactory
import dev.keliver.treehouse.AppService
import dev.keliver.treehouse.StandardAppLifecycle
import dev.keliver.treehouse.TreehouseUi
import dev.keliver.treehouse.ZiplineTreehouseUi
import dev.keliver.treehouse.asZiplineTreehouseUi
import dev.keliver.hostkit.KeliverScreenHost
import inventory.logic.InventoryApp
import kotlinx.coroutines.CancellationException

/**
 * The service the generic keliver device host take()s.
 *
 * Declared here BY SHAPE on purpose: Zipline binds services by name and
 * signature, so this app does not need the host's own module on its
 * classpath — which matters, because that module is not published.
 */
interface PortalPresenter : AppService, ZiplineService {
  fun launch(): ZiplineTreehouseUi
  fun launchRoute(route: String, screen: KeliverScreenHost): ZiplineTreehouseUi
}

private class AppUi(val route: String = "", private val screen: KeliverScreenHost? = null) : TreehouseUi {
  @Composable
  override fun Show() {
    // Hand-edited: the scaffolder wires one screen + one presenter; this app
    // navigates between two, so it mounts its own root (logic/InventoryApp.kt).
    // K4's k4/policy route shows the request-policy check in its place.
    if (route.substringBefore('?') == "k4/policy") PolicyCheck() else InventoryApp()
    // The inventory is in memory: shown on the first frame (M0's content mark).
    LaunchedEffect(Unit) { KeliverTiming.mark("content") }
    // Hand-edited for the host kit's device checks (K4): the route as the guest received it,
    // as a timing mark, and two routes that close the screen or make it throw.
    if (route.isNotEmpty()) LaunchedEffect(route) {
      KeliverTiming.mark(routeMark(route))
      when (route.substringBefore('?')) {
        "k4/close" -> screen?.closeScreen()
        "k4/crash" -> error("k4: a guest exception, on purpose")
      }
    }
  }

  override fun close() {
    screen?.close() // the host's handle for this screen, released with it
  }
}

/**
 * K4 (the Android check X11): three HostHttp requests, one after another, and what came of each.
 * The embedding app's RequestPolicy allows GET ping and nothing state-changing, so POST ping (a
 * state-changing call) and GET admin (not in its allow-list) must fail here with the host's
 * "HostHttp refused: …" before any network call, and the screen must show that, not a crash.
 * GET ping is the positive control: it reaches the API. Each outcome is also a timing mark
 * (debug builds), so the host's log has the guest's side too. Kept in this file because ios.sh
 * copies this file, not the device/ directory, into its app.
 */
@Composable
private fun PolicyCheck() {
  var lines by remember { mutableStateOf(listOf("Request policy")) }
  LaunchedEffect(Unit) {
    for ((method, path) in listOf("POST" to "ping", "GET" to "admin", "GET" to "ping")) {
      lines = lines + policyAttempt(hostHttp, method, path)
    }
  }
  Column {
    lines.forEach { StyledText(text = it, fontSize = 16) }
  }
}

/**
 * The host's HostHttp, taken once for this code load and never closed: closing a taken service
 * tells the host to drop its end, so a second visit to k4/policy in the same load would fail.
 * Lazy, so a route that never makes a request never takes it.
 */
private val hostHttp: HostHttpProvider by lazy { Zipline.get().take<HostHttpProvider>("HostHttp") }

/** "Refused: POST ping" and the host's reason, "Allowed: GET ping (200)", or "Failed: …" and why. */
private suspend fun policyAttempt(http: HostHttpProvider, method: String, path: String): List<String> {
  val what = "$method $path"
  val mark = "policy-${method.lowercase()}-$path"
  return try {
    val status = http.execute(HttpRequest(method = method, path = path)).status
    KeliverTiming.mark("$mark-$status")
    listOf("Allowed: $what ($status)")
  } catch (e: CancellationException) {
    throw e
  } catch (e: Throwable) {
    // The host's throwable arrives as a ZiplineException carrying its text: the class name,
    // "HostHttp refused: <why>", then the host's frames.
    val text = e.message.orEmpty()
    if ("HostHttp refused: " in text) {
      KeliverTiming.mark("$mark-refused")
      listOf("Refused: $what", text.substringAfter("HostHttp refused: ").lineSequence().first())
    } else {
      KeliverTiming.mark("$mark-failed")
      listOf("Failed: $what", text.lineSequence().first())
    }
  }
}

/** `k4/items?tab=stock` → `route-k4-items-tab-stock`: a timing mark's alphabet (a-z, 0-9, '-'), 32 at most. */
private fun routeMark(route: String): String =
  ("route-" + route.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')).take(32)

private class AppPresenter(json: kotlinx.serialization.json.Json) : PortalPresenter {
  override val appLifecycle = StandardAppLifecycle(
    protocolWidgetSystemFactory = KeliverMaterialProtocolWidgetSystemFactory,
    json = json,
    widgetVersion = 1U,
  )
  override fun launch(): ZiplineTreehouseUi = AppUi().asZiplineTreehouseUi(appLifecycle)
  override fun launchRoute(route: String, screen: KeliverScreenHost): ZiplineTreehouseUi =
    AppUi(route, screen).asZiplineTreehouseUi(appLifecycle)
}

fun main() {
  val zipline = Zipline.get()
  // If your presenter needs HTTP, the host binds a HostHttpProvider under
  // "HostHttp": zipline.take<HostHttpProvider>("HostHttp"), once per load (hostHttp, above).
  zipline.bind<PortalPresenter>("PortalPresenter", AppPresenter(zipline.json))
}
