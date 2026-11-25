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
import dev.keliver.compose.ExperimentalKeliverHostFeaturesApi
import dev.keliver.compose.KeliverHostFeatures
import hosttemplate.HostFeatures
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * NF5 H0 (docs/SHOW_BEFORE_COMPUTE.md I8): the host templates declare their host features in one
 * file (scripts/templates/production-host/src/main/kotlin/HostFeatures.kt, compiled into this test
 * by portal-relay's build.gradle), with names from Keliver's one list (keliver-compose's
 * KeliverHostFeatures); and only the line's own KeliverLine.kt hands them to Treehouse.
 */
@OptIn(ExperimentalKeliverHostFeaturesApi::class)
class HostFeaturesTemplateTest {
  private val templates = File("../scripts/templates")

  @Test
  fun everyFeatureTheHostsDeclareIsOneKeliverDefines() {
    for (name in HostFeatures.implemented) {
      assertTrue(name in KeliverHostFeatures.all, "the host templates declare $name, which KeliverHostFeatures does not define")
    }
  }

  /** As a host declaring `keliver.nav.reveal@1` without `keliver.nav.retainedContainer@2` would (design 3.2). */
  @Test
  fun everyFeatureTheHostsDeclareHasWhatItRequires() {
    assertEquals(emptyMap(), KeliverHostFeatures.missingRequirements(HostFeatures.implemented))
  }

  @Test
  fun theAndroidAndIosHostsShipTheSameDeclarationAndReleaseIdentity() {
    for ((android, ios) in listOf(
      "production-host/src/main/kotlin/HostFeatures.kt" to "ios-host/src/commonMain/kotlin/HostFeatures.kt",
      "production-host/src/test/kotlin/HostFeaturesTest.kt" to "ios-host/src/jvmTest/kotlin/HostFeaturesTest.kt",
      "production-host/src/main/kotlin/ReleaseIdentity.kt" to "ios-host/src/iosMain/kotlin/ReleaseIdentity.kt",
    )) {
      assertEquals(File(templates, android).readText(), File(templates, ios).readText(), "$ios is not $android")
    }
  }

  /** Every line of keliver-runtime-line.sh's table has its KeliverLine.kt; only lines with host features hand them over. */
  @Test
  fun everyKeliverLineHasItsHostFile() {
    val table = File("../scripts/keliver-runtime-line.sh").readText()
    val lines = Regex("""(?m)^LINE\s+(.+)$""").find(table)!!.groupValues[1].trim().split(Regex("""\s+"""))
    assertTrue("0.4" in lines && "0.5" in lines, "the table's lines: $lines")
    for (line in lines) {
      val file = File(templates, "production-host/src/line-$line/kotlin/KeliverLine.kt")
      assertTrue(file.isFile, "no KeliverLine.kt for the $line line")
      val text = file.readText()
      assertTrue("internal const val KELIVER_LINE = \"$line\"" in text, "$file names another line")
      assertTrue("internal abstract class KeliverSpec<A : AppService> : TreehouseApp.Spec<A>()" in text, file.path)
      assertTrue("internal fun <A : AppService> KeliverTreehouseContent(" in text, file.path)
    }
    val old = File(templates, "production-host/src/line-0.4/kotlin/KeliverLine.kt").readText()
    assertFalse("hostFeatures" in old, "the 0.4 line's Treehouse has no host features")
    assertFalse("backEnabled = backEnabled" in old, "the 0.4 line's TreehouseContent has no back gate")
    val current = File(templates, "production-host/src/line-0.5/kotlin/KeliverLine.kt").readText()
    assertTrue("override val hostFeatures: Set<String>\n    get() = HostFeatures.implemented" in current)
    assertTrue("backEnabled = backEnabled," in current)
  }
}
