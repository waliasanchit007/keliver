package dev.keliver.portal.mcp

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression for KNOWN_BUGS U16.
 *
 * `list_screens` returns bare names; the relay's log prints `default/home`.
 * Forwarding the qualified form verbatim made the server mint a fresh empty
 * document under `default/default_home` and return it with no error, so an
 * agent that copied the id out of the log was told the screen was empty.
 */
class ScreenIdTest {
  @Test fun bareNamePassesThrough() =
    assertEquals("home", Tools.normalizeScreen("default", "home"))

  @Test fun ownProjectPrefixIsStripped() =
    assertEquals("home", Tools.normalizeScreen("default", "default/home"))

  @Test fun nonDefaultProjectPrefixIsStripped() =
    assertEquals("checkout", Tools.normalizeScreen("shop", "shop/checkout"))

  @Test fun aDifferentProjectPrefixIsNotStripped() =
    assertEquals("shop/checkout", Tools.normalizeScreen("default", "shop/checkout"))

  @Test fun repeatedNormalizationIsStable() {
    val once = Tools.normalizeScreen("default", "default/home")
    assertEquals(once, Tools.normalizeScreen("default", once))
  }

  @Test fun straySlashesAndSpacesAreTolerated() {
    assertEquals("home", Tools.normalizeScreen("default", " default/home "))
    assertEquals("home", Tools.normalizeScreen("default", "/home/"))
  }

  @Test fun anEmptySuffixIsNotTreatedAsAScreen() =
    assertEquals("default", Tools.normalizeScreen("default", "default/"))
}
