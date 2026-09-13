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

  // U25.2: the length bound. Without it a truncated key returned
  // ProductionVerified and then threw inside decodeHex() in onCreate — a crash
  // instead of the refusal screen. These must all take the normal refusal path.
  @Test
  fun aMalformedPublicKeyIsRefusedBeforeAnythingIsLoaded() {
    val valid = "ab".repeat(32)
    val bad = listOf(
      "" to "empty",
      "ab".repeat(31) to "62 chars, one byte short",
      valid.dropLast(1) to "63 chars, odd length — the decodeHex crash",
      valid + "ab" to "66 chars, one byte long",
      valid.dropLast(1) + "z" to "64 chars but not hex",
      " $valid" to "leading space",
    )
    for ((key, why) in bad) {
      val trust = hostTrust(prodMode = true, devOnlyHost = false, publicKeyHex = key)
      assertTrue(trust is HostTrust.Refused, "must refuse ($why), got $trust")
    }
    val ok = hostTrust(prodMode = true, devOnlyHost = false, publicKeyHex = valid)
    assertTrue(ok is HostTrust.ProductionVerified, "a 64-char hex key must still verify, got $ok")
  }

  @Test
  fun aRefusedKeyNeverReachesDecodeHex() {
    // The crash was decodeHex() on what this policy had already blessed, so the
    // invariant is: anything ProductionVerified decodes to exactly 32 bytes.
    val trust = hostTrust(prodMode = true, devOnlyHost = false, publicKeyHex = "ab".repeat(32))
    val hex = (trust as HostTrust.ProductionVerified).publicKeyHex
    assertEquals(64, hex.length)
    assertEquals(32, hex.chunked(2).size)
  }
}
