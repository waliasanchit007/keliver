package dev.keliver.screenstates.fixture.screens

import dev.keliver.nav.NamedState
import dev.keliver.nav.ScreenState
import dev.keliver.screenstates.fixture.logic.AppRoute

// The fixture has no widget library, so its screens are their bindings and states: the tests
// assert the bindings a presenter returns, which are what the screen would draw.

interface CatalogScreenBindings {
  val loading: Boolean
  val title: String
  val rows: List<String>
  fun open(id: String)
}

@ScreenState("loaded")
fun catalogLoaded() = NamedState(route = AppRoute.Catalog, fixtures = "catalog")

@ScreenState("empty")
fun catalogEmpty() = NamedState(route = AppRoute.Catalog, fixtures = "catalog-empty")
