package dev.keliver.portaldevice.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * U22 regression. The generic development host must refuse production mode, and
 * no host may load a production bundle without signature verification.
 *
 * Before the fix `MainActivity` did this inline:
 *
 *   if (prodMode) Log.w(TAG, "prod mode WITHOUT embedded public key — falling
 *       back to NO_SIGNATURE_CHECKS")
 *   ManifestVerifier.NO_SIGNATURE_CHECKS
 *
 * — it warned and carried on, then fetched and loaded the production bundle
 * unverified. There was also no notion of a development-only build, so the
 * generic tools APK ran prod mode against whatever key its builder's machine
 * happened to have.
 */
class HostTrustPolicyTest {

  @Test
  fun theDevelopmentHostRefusesProductionMode() {
    val t = decideHostTrust(prodMode = true, devOnlyHost = true, publicKeyHex = null)
    assertTrue(t is HostTrust.Refused, "the dev-only host must refuse prod mode, got $t")
    val m = (t as HostTrust.Refused).message
    assertTrue("development-only" in m, "the message must say what this binary is: $m")
    assertTrue("your app's own device" in m.lowercase() || "own device host" in m, "and what to do instead: $m")
  }

  /** Even if a key somehow got embedded, the generic host is still not a prod host. */
  @Test
  fun theDevelopmentHostRefusesProductionModeEvenWithAKey() {
    val t = decideHostTrust(prodMode = true, devOnlyHost = true, publicKeyHex = "abcdef01")
    assertTrue(t is HostTrust.Refused, "dev-only must not run prod even with a key, got $t")
  }

  @Test
  fun aProductionHostWithoutAKeyRefusesInsteadOfSkippingVerification() {
    for (missing in listOf(null, "", "   ")) {
      val t = decideHostTrust(prodMode = true, devOnlyHost = false, publicKeyHex = missing)
      assertTrue(t is HostTrust.Refused, "missing key must refuse, got $t for ${missing?.let { "'$it'" }}")
      assertTrue(
        "without signature verification" in (t as HostTrust.Refused).message,
        "the refusal must name the reason: ${t.message}",
      )
    }
  }

  @Test
  fun aProductionHostWithAnUnusableKeyRefuses() {
    val t = decideHostTrust(prodMode = true, devOnlyHost = false, publicKeyHex = "not-hex!!")
    assertTrue(t is HostTrust.Refused, "an unparseable key must refuse, got $t")
  }

  @Test
  fun aProductionHostWithItsKeyVerifies() {
    val t = decideHostTrust(prodMode = true, devOnlyHost = false, publicKeyHex = " ABCDEF0123 ")
    assertEquals(HostTrust.ProductionVerified("ABCDEF0123"), t, "the trimmed key must be used")
  }

  /** The route adopters actually use must stay open in both binaries. */
  @Test
  fun theDevelopmentRouteRemainsUsable() {
    assertEquals(
      HostTrust.DevelopmentUnsigned,
      decideHostTrust(prodMode = false, devOnlyHost = true, publicKeyHex = null),
      "the generic host's dev route",
    )
    assertEquals(
      HostTrust.DevelopmentUnsigned,
      decideHostTrust(prodMode = false, devOnlyHost = false, publicKeyHex = "abcdef01"),
      "an app host can still be used for development",
    )
  }
}
