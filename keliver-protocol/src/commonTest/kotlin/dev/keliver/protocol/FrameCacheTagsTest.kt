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
package dev.keliver.protocol

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import kotlin.test.Test

/** The lock: a tag never changes and is never reused. Change a literal here only to add a row. */
class FrameCacheTagsTest {
  @Test fun assignedTagsAreLocked() {
    assertThat(FrameCacheTags.CACHED_FRAME).isEqualTo(-4_600_001)
    assertThat(FrameCacheTags.CACHED).isEqualTo(-4_600_002)
    assertThat(FrameCacheTags.NOT_CACHED).isEqualTo(-4_600_003)
  }

  @Test fun theRangeIsLocked() {
    assertThat(FrameCacheTags.RANGE_START).isEqualTo(-4_600_099)
    assertThat(FrameCacheTags.RANGE_END).isEqualTo(-4_600_000)
  }

  @Test fun assignedTagsAreDistinctAndInTheRange() {
    val tags = listOf(FrameCacheTags.CACHED_FRAME, FrameCacheTags.CACHED, FrameCacheTags.NOT_CACHED)
    assertThat(tags.toSet().size).isEqualTo(tags.size)
    for (tag in tags) assertThat(FrameCacheTags.isReserved(tag)).isTrue()
  }

  @Test fun theRangeDoesNotTouchTheReuseTagOrSchemaTags() {
    assertThat(FrameCacheTags.isReserved(-4_543_827)).isFalse()
    assertThat(FrameCacheTags.isReserved(1)).isFalse()
    assertThat(FrameCacheTags.isReserved(0)).isFalse()
    assertThat(FrameCacheTags.isReserved(-4_600_100)).isFalse()
    assertThat(FrameCacheTags.isReserved(-4_599_999)).isFalse()
    // An unassigned tag in the range is still reserved (a newer guest's marker).
    assertThat(FrameCacheTags.isReserved(-4_600_050)).isTrue()
  }
}
