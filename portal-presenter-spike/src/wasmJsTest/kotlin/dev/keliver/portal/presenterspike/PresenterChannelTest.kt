package dev.keliver.portal.presenterspike

import kotlin.test.Test
import kotlin.test.assertEquals

/** Runs IN wasm in ChromeHeadless — proves the serialized state/action channel. */
class PresenterChannelTest {
  @Test
  fun stateAndActionsCrossTheBoundaryAsJson() {
    val p = CounterPresenter()
    assertEquals("""{"title":"Cart","count":0,"total":"$0.0"}""", presenterStateJson(p))
    presenterActionJson(p, "add")
    val after = presenterActionJson(p, "add")
    assertEquals("""{"title":"Cart","count":2,"total":"$99.98"}""", after)
    assertEquals("""{"title":"Cart","count":1,"total":"$49.99"}""", presenterActionJson(p, "remove"))
  }
}
