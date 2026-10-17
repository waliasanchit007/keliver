import dev.keliver.portal.Token
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** K5: small properties-panel and frame facts. */
class EditorPropsTest {
  @Test fun boldFollowsAnExplicitWeightAsTheDeviceDoes() {
    assertEquals(true, boldFromWeight(mapOf("weight" to 700)))
    assertEquals(true, boldFromWeight(mapOf("weight" to 600)))
    assertEquals(false, boldFromWeight(mapOf("weight" to 500, "bold" to true)))
    assertEquals(true, boldFromWeight(mapOf("weight" to Token("C.Heavy", 800, "app.C"))))
    // 0 (unset) and out-of-range weights leave Bold in charge.
    assertNull(boldFromWeight(mapOf("weight" to 0, "bold" to true)))
    assertNull(boldFromWeight(mapOf("weight" to 1000)))
    assertNull(boldFromWeight(emptyMap()))
  }

  @Test fun aLargeModernPhoneFrameIsOffered() {
    assertTrue(DEVICE_PRESETS.any { it.second == "412x915" })
    assertTrue(DEVICE_PRESETS.any { it.second == "390x780" }) // stored selections stay valid
  }
}
