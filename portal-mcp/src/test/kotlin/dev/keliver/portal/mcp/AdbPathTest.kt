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
