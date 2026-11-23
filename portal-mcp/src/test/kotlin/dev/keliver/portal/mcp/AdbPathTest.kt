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
package dev.keliver.portal.mcp

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/** device_screenshot finds adb where the SDK says it is, not only at macOS's default. */
class AdbPathTest {
  private fun sdkWithAdb(): File = Files.createTempDirectory("sdk").toFile().also {
    File(it, "platform-tools").mkdirs()
    File(it, "platform-tools/adb").writeText("")
  }

  @Test fun androidHomeComesFirst() {
    val sdk = sdkWithAdb()
    val home = Files.createTempDirectory("home").toFile().path
    assertEquals(File(sdk, "platform-tools/adb").absolutePath, adbPath(env = { if (it == "ANDROID_HOME") sdk.path else null }, home = home))
    assertEquals(File(sdk, "platform-tools/adb").absolutePath, adbPath(env = { if (it == "ANDROID_SDK_ROOT") sdk.path else null }, home = home))
  }

  @Test fun theLinuxStudioDefaultIsFound() {
    val home = Files.createTempDirectory("home").toFile()
    File(home, "Android/Sdk/platform-tools").mkdirs()
    File(home, "Android/Sdk/platform-tools/adb").writeText("")
    assertEquals(File(home, "Android/Sdk/platform-tools/adb").absolutePath, adbPath(env = { null }, home = home.path))
  }

  @Test fun withNoSdkItIsAdbOnThePath() {
    val home = Files.createTempDirectory("home").toFile().path
    assertEquals(if (System.getProperty("os.name").startsWith("Windows")) "adb.exe" else "adb", adbPath(env = { null }, home = home))
  }
}
