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
import assertk.assertions.containsExactlyInAnyOrder
import java.lang.reflect.Modifier
import kotlin.test.Test

@OptIn(ExperimentalKeliverHostFeaturesApi::class)
class KeliverHostFeaturesJvmTest {
  /** A constant added without its row in `requirements` (or a row without its constant) fails here. */
  @Test
  fun everyConstantHasItsRowAndEveryRowItsConstant() {
    val constants = KeliverHostFeatures::class.java.declaredFields
      .filter { Modifier.isPublic(it.modifiers) && Modifier.isStatic(it.modifiers) && it.type == String::class.java }
      .map { it.get(null) as String }

    assertThat(KeliverHostFeatures.all).containsExactlyInAnyOrder(*constants.toTypedArray())
  }
}
