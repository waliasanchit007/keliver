package @@PACKAGE@@

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * K1: which links leave the app, and how. The scheme decision is checked here; what [ExternalLinkPolicy.open]
 * does with an intent: link (no component, BROWSABLE, no URI grants, not this app) needs a device (K4).
 */
class ExternalLinkPolicyTest {
  private fun decide(url: String) = ExternalLinkPolicy.classify(url)

  @Test fun httpsOpensInTheAppsBrowserTab() {
    assertEquals(ExternalLinkPolicy.Decision.InAppBrowser("https://example.com/help"), decide("https://example.com/help"))
    assertEquals(ExternalLinkPolicy.Decision.InAppBrowser("HTTPS://example.com"), decide("  HTTPS://example.com "))
  }

  @Test fun intentLinksGoThroughTheBrowserRules() {
    val url = "intent://scan/#Intent;scheme=zxing;package=com.example.scanner;end"
    assertEquals(ExternalLinkPolicy.Decision.BrowsableIntent(url), decide(url))
  }

  @Test fun everythingElseIsRefused() {
    for (url in listOf(
      "http://example.com", "javascript:alert(1)", "file:///sdcard/a.txt", "content://contacts/people/1",
      "myapp://open?x=1", "market://details?id=x", "data:text/html,hi", "//example.com", "example.com", "",
      "https:///no-host", "https:/example.com",
    )) {
      assertTrue(url, decide(url) is ExternalLinkPolicy.Decision.Refused)
    }
  }

  @Test fun aFallbackIsUsedOnlyWhenItIsHttps() {
    assertTrue(ExternalLinkPolicy.fallbackAllowed("https://example.com/install"))
    assertFalse(ExternalLinkPolicy.fallbackAllowed("http://example.com/install"))
    assertFalse(ExternalLinkPolicy.fallbackAllowed("javascript:alert(1)"))
    assertFalse(ExternalLinkPolicy.fallbackAllowed(null))
  }
}
