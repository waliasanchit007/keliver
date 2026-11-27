package dev.keliver.screenstates.fixture.screens

import dev.keliver.nav.NamedState
import dev.keliver.nav.ScreenState
import dev.keliver.screenstates.fixture.logic.AppRoute

interface ItemScreenBindings {
  val loading: Boolean
  val title: String
  val price: String?
}

@ScreenState("detail")
fun itemDetail() = NamedState(route = AppRoute.Item(id = "42"), fixtures = "item-42")

/** `internal`: the generated registry is in this module, so it can call it. */
@ScreenState("not-found")
internal fun itemNotFound(): NamedState<AppRoute> {
  return NamedState(AppRoute.Item(id = "404"), "item-404")
}
