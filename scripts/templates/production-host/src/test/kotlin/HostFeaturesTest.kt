package @@PACKAGE@@

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * NF5 H0: every host feature this host declares (HostFeatures.kt, its line's copy) is a feature at a
 * level, `<feature>@<level>`: one of Keliver's (`keliver.…`) or one of the app's own (any other
 * name). Keliver's own build holds each line's declaration to Keliver's list
 * (`dev.keliver.compose.KeliverHostFeatures`) and to what each feature requires.
 */
class HostFeaturesTest {
  @Test
  fun everyNameIsAFeatureAtALevel() {
    for (name in HostFeatures.implemented) {
      assertTrue(Regex("""[a-z][A-Za-z0-9]*(\.[a-z][A-Za-z0-9]*)+@[1-9][0-9]*""").matches(name), name)
    }
  }
}
