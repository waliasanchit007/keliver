package dev.keliver.screenstates.fixture.logic

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.keliver.capabilities.HostHttp
import dev.keliver.nav.KeliverNavHost
import dev.keliver.nav.rememberKeliverNavController
import dev.keliver.screenstates.fixture.screens.CatalogScreenBindings
import dev.keliver.screenstates.fixture.screens.ItemScreenBindings
import kotlin.coroutines.cancellation.CancellationException

/** Loads [load] once per [http]: null while loading, then the value or the failure. */
@Composable
private fun <T> load(http: HostHttp, load: suspend (CatalogApi) -> T): Result<T>? {
  var result by remember(http) { mutableStateOf<Result<T>?>(null) }
  LaunchedEffect(http) {
    result = try {
      Result.success(load(CatalogApi(http)))
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Result.failure(e)
    }
  }
  return result
}

/** The catalog: every item the API lists, each opening its detail. */
@Composable
fun CatalogPresenter(http: HostHttp, onOpen: (AppRoute) -> Unit): CatalogScreenBindings {
  val items = load(http) { it.items() }
  return object : CatalogScreenBindings {
    override val loading = items == null
    override val title = when {
      items == null -> "Loading…"
      items.isFailure -> "Catalog unavailable"
      items.getOrThrow().isEmpty() -> "Nothing for sale"
      else -> "Catalog · ${items.getOrThrow().size} items"
    }
    override val rows = items?.getOrNull().orEmpty().map { "${it.title} · ${it.price}" }

    override fun open(id: String) = onOpen(AppRoute.Item(id))
  }
}

/** One item, by the route's id. */
@Composable
fun ItemPresenter(route: AppRoute.Item, http: HostHttp): ItemScreenBindings {
  val item = load(http) { it.item(route.id) }
  return object : ItemScreenBindings {
    override val loading = item == null
    override val title = when {
      item == null -> "Loading…"
      item.isFailure -> "Item ${route.id} not found"
      else -> item.getOrThrow().title
    }
    override val price = item?.getOrNull()?.price
  }
}

/** The presenter each route opens (Style B: the route's screen draws what it returns). */
@Composable
fun RoutePresenter(route: AppRoute, http: HostHttp, onOpen: (AppRoute) -> Unit): Any = when (route) {
  AppRoute.Catalog -> CatalogPresenter(http, onOpen)
  is AppRoute.Item -> ItemPresenter(route, http)
}

/**
 * The app's composition root: a nav host that starts at [start], every route's presenter on
 * [http]. [show] draws a route's bindings; in an app with a widget library it calls the route's
 * screen (`is CatalogScreenBindings -> CatalogScreen(it)`).
 */
@Composable
fun FixtureApp(start: AppRoute, http: HostHttp, show: @Composable (bindings: Any) -> Unit) {
  val nav = rememberKeliverNavController(start)
  KeliverNavHost(nav) { route ->
    show(RoutePresenter(route, http, onOpen = nav::navigate))
  }
}
