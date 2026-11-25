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
package dev.keliver.treehouse

import android.util.Log
import assertk.assertThat
import assertk.assertions.containsExactly
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/** The host's build line goes to logcat at debug level, tag `Keliver`: what the local-maven route greps. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class AndroidTreehousePlatformLogTest {
  @Test fun logDebugWritesOneDebugLineTaggedKeliver() {
    ShadowLog.clear()
    AndroidTreehousePlatform(RuntimeEnvironment.getApplication()).logDebug(HOST_BUILD_LINE)

    assertThat(ShadowLog.getLogsForTag("Keliver").map { it.type to it.msg })
      .containsExactly(Log.DEBUG to HOST_BUILD_LINE)
  }
}
