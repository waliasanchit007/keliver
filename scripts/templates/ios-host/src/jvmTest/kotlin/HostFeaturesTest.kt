package @@PACKAGE@@

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * NF5 H0: the host declares exactly the host features it implements (HostFeatures.kt). A feature
 * added to the host is added here in the same change; one declared without its implementation, or
 * implemented without being declared, fails here. Keliver's own build also holds each name to
 * Keliver's list (`dev.keliver.compose.KeliverHostFeatures`) and to what each feature requires.
 */
class HostFeaturesTest {
  /** What this host implements. Today: none of Keliver's optional host features. */
  private val expected = setOf<String>()

  @Test
  fun theHostDeclaresExactlyWhatItImplements() {
    assertEquals(expected, HostFeatures.implemented)
  }

  @Test
  fun everyNameIsAKeliverFeatureAtALevel() {
    for (name in HostFeatures.implemented) {
      assertTrue(Regex("""keliver(\.[a-z][A-Za-z0-9]*)+@[1-9][0-9]*""").matches(name), name)
    }
  }
}
