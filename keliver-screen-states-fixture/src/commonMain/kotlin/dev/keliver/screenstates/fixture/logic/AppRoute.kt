package dev.keliver.screenstates.fixture.logic

import kotlinx.serialization.Serializable

/** The app's routes (D8: routes are data). */
@Serializable
sealed interface AppRoute {
  @Serializable
  data object Catalog : AppRoute

  @Serializable
  data class Item(val id: String) : AppRoute
}
