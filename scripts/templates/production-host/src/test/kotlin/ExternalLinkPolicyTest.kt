package @@PACKAGE@@

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * K1: which links leave the app, and how: the scheme decision and the rules for a parsed intent: link
 * (only VIEW, never this app, no refused data scheme). Applying them to an Intent (no component or
 * selector, BROWSABLE, no flags) and asking the package manager need Android: a device check (K4).
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

  private fun intentRefusal(action: String? = "android.intent.action.VIEW", pkg: String? = null, scheme: String? = "zxing", toThisApp: Boolean = false) =
    ExternalLinkPolicy.intentRefusal(action, pkg, scheme, "com.example.thisapp", toThisApp)

  @Test fun aViewIntentForAnotherAppIsAllowed() {
    assertEquals(null, intentRefusal())
    assertEquals(null, intentRefusal(action = null, pkg = "com.example.scanner"))
  }

  @Test fun anIntentLinkMayNotOpenThisApp() {
    assertTrue(intentRefusal(pkg = "com.example.thisapp")!!.contains("may not open this app"))
    // No package named, but this app's own deep link would take it.
    assertTrue(intentRefusal(scheme = "thisapp", toThisApp = true)!!.contains("may not open this app"))
  }

  @Test fun anIntentLinkMayOnlyView() {
    assertTrue(intentRefusal(action = "android.intent.action.CALL")!!.contains("may only VIEW"))
    assertTrue(intentRefusal(action = "android.intent.action.SEND")!!.contains("may only VIEW"))
  }

  @Test fun anIntentLinkMayNotCarryWhatABareLinkMayNot() {
    for (scheme in listOf("file", "content", "http", "javascript", "data", "intent", "HTTP")) {
      assertTrue(scheme, intentRefusal(scheme = scheme)!!.contains("may not carry a ${scheme.lowercase()}: URI"))
    }
  }

  @Test fun anUpperCaseIntentPrefixIsStillAnIntentLink() {
    assertTrue(decide("INTENT://x#Intent;scheme=zxing;end") is ExternalLinkPolicy.Decision.BrowsableIntent)
  }

  @Test fun aFallbackIsUsedOnlyWhenItIsHttps() {
    assertTrue(ExternalLinkPolicy.fallbackAllowed("https://example.com/install"))
    assertFalse(ExternalLinkPolicy.fallbackAllowed("http://example.com/install"))
    assertFalse(ExternalLinkPolicy.fallbackAllowed("javascript:alert(1)"))
    assertFalse(ExternalLinkPolicy.fallbackAllowed(null))
  }
}
