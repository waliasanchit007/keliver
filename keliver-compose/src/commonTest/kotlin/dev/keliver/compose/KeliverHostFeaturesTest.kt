/*
 * Copyright (C) 2026 Keliver contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.keliver.compose

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import kotlin.test.Test

@OptIn(ExperimentalKeliverHostFeaturesApi::class)
class KeliverHostFeaturesTest {
  @Test
  fun everyNameIsAFeatureAtALevel() {
    for (name in KeliverHostFeatures.all) {
      assertThat(Regex("""keliver(\.[a-z][A-Za-z0-9]*)+@[1-9][0-9]*""").matches(name), name).isTrue()
    }
  }

  @Test
  fun everyRequirementIsAFeatureKeliverDefines() {
    for ((feature, required) in KeliverHostFeatures.requirements) {
      for (name in required) {
        assertThat(name in KeliverHostFeatures.all, "$feature requires $name").isTrue()
      }
    }
  }

  @Test
  fun noFeatureRequiresItself() {
    for ((feature, required) in KeliverHostFeatures.requirements) {
      assertThat(feature in required, feature).isEqualTo(false)
    }
  }

  @Test
  fun todaysFeaturesRequireNothing() {
    assertThat(KeliverHostFeatures.missingRequirements(KeliverHostFeatures.all)).isEmpty()
    assertThat(KeliverHostFeatures.missingRequirements(setOf(KeliverHostFeatures.PRESS_1))).isEmpty()
  }

  /** As `keliver.nav.reveal@1` will require `keliver.nav.retainedContainer@2` (design 3.2, I8). */
  @Test
  fun aFeatureWithoutWhatItRequiresIsReported() {
    val requirements = mapOf(
      "keliver.a@2" to emptySet(),
      "keliver.b@1" to setOf("keliver.a@2"),
    )
    assertThat(KeliverHostFeatures.missingRequirements(setOf("keliver.b@1"), requirements))
      .isEqualTo(mapOf("keliver.b@1" to setOf("keliver.a@2")))
    assertThat(KeliverHostFeatures.missingRequirements(setOf("keliver.b@1", "keliver.a@2"), requirements)).isEmpty()
    assertThat(KeliverHostFeatures.missingRequirements(setOf("keliver.a@2", "app.own@1"), requirements)).isEmpty()
  }
}
