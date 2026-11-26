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
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * NF5 H0 (docs/SHOW_BEFORE_COMPUTE.md I8): each Keliver line's host templates declare their host
 * features in one file, the line's own (scripts/templates/production-host/src/line-<line>/kotlin/
 * HostFeatures.kt, the same for the Android and iOS hosts, compiled into this test by portal-relay's
 * build.gradle), with names from Keliver's one list (keliver-compose's KeliverHostFeatures). The
 * host code reads only that declaration, and only the line's own KeliverLine.kt hands it to
 * Treehouse. Declaring a feature on one line never changes another line's host (review of #316, M2).
 */
@OptIn(ExperimentalKeliverHostFeaturesApi::class)
class HostFeaturesTemplateTest {
  private val templates = File("../scripts/templates")

  /** Each line's declaration, as its hosts compile it. */
  private val declared: Map<String, Set<String>> = mapOf(
    "0.4" to hostline_0_4.HostFeatures.implemented,
    "0.5" to hostline_0_5.HostFeatures.implemented,
  )

  /** The lines of keliver-runtime-line.sh's table. */
  private val tableLines: List<String> by lazy {
    val table = File("../scripts/keliver-runtime-line.sh").readText()
    Regex("""(?m)^LINE\s+(.+)$""").find(table)!!.groupValues[1].trim().split(Regex("""\s+"""))
  }

  @Test
  fun everyLineOfTheTableHasItsDeclaration() {
    assertTrue("0.4" in tableLines && "0.5" in tableLines, "the table's lines: $tableLines")
    assertEquals(tableLines.toSet(), declared.keys, "portal-relay's build.gradle (hostLineTestSource) compiles another set of lines")
  }

  /** The 0.4 line's Treehouse hands a guest no host features, so its hosts declare none. */
  @Test
  fun theOldLineDeclaresNone() {
    assertEquals(emptySet(), declared.getValue("0.4"))
  }

  /** Names outside `keliver.` are an app's own; Keliver neither defines nor checks them. */
  @Test
  fun everyKeliverNameALineDeclaresIsOneKeliverDefines() {
    for ((line, names) in declared) {
      for (name in names.filter { it.startsWith("keliver.") }) {
        assertTrue(name in KeliverHostFeatures.all, "the $line line's hosts declare $name, which KeliverHostFeatures does not define")
      }
    }
  }

  /** As a host declaring `keliver.nav.reveal@1` without `keliver.nav.retainedContainer@2` would (design 3.2). */
  @Test
  fun everyFeatureALineDeclaresHasWhatItRequires() {
    for ((line, names) in declared) {
      assertEquals(emptyMap(), KeliverHostFeatures.missingRequirements(names), "the $line line")
    }
  }

  /** The host code compiled into these tests (BundleIndex.kt and the rest, package hosttemplate) reads this checkout's line. */
  @Test
  fun theHostCodeReadsTheDeclarationOfTheLineThisCheckoutBuilds() {
    assertEquals(declared.getValue("0.5"), hosttemplate.HostFeatures.implemented)
  }

  /** Every line has its KeliverLine.kt; only lines with host features hand them over. */
  @Test
  fun everyKeliverLineHasItsHostFiles() {
    for (line in tableLines) {
      val file = File(templates, "production-host/src/line-$line/kotlin/KeliverLine.kt")
      assertTrue(file.isFile, "no KeliverLine.kt for the $line line")
      val text = file.readText()
      assertTrue("internal const val KELIVER_LINE = \"$line\"" in text, "$file names another line")
      assertTrue("internal abstract class KeliverSpec<A : AppService> : TreehouseApp.Spec<A>()" in text, file.path)
      assertTrue("internal fun <A : AppService> KeliverTreehouseContent(" in text, file.path)
      assertTrue(File(templates, "production-host/src/line-$line/kotlin/HostFeatures.kt").isFile, "no HostFeatures.kt for the $line line")
    }
    val old = File(templates, "production-host/src/line-0.4/kotlin/KeliverLine.kt").readText()
    assertFalse("hostFeatures" in old, "the 0.4 line's Treehouse has no host features")
    assertFalse("backEnabled = backEnabled" in old, "the 0.4 line's TreehouseContent has no back gate")
    val current = File(templates, "production-host/src/line-0.5/kotlin/KeliverLine.kt").readText()
    assertTrue("override val hostFeatures: Set<String>\n    get() = HostFeatures.implemented" in current)
    assertTrue("backEnabled = backEnabled," in current)
  }

  /** One declaration per line, and no copy of it (or of the release identity) beside the hosts' shared code (review of #316, Low 1). */
  @Test
  fun theHostsShareTheLinesFilesRatherThanCopies() {
    for (copy in listOf(
      "production-host/src/main/kotlin/HostFeatures.kt",
      "ios-host/src/commonMain/kotlin/HostFeatures.kt",
      "ios-host/src/jvmTest/kotlin/HostFeaturesTest.kt",
      "ios-host/src/iosMain/kotlin/ReleaseIdentity.kt",
    )) {
      assertFalse(File(templates, copy).exists(), "$copy: the scaffolders take this from production-host (its line's files, or main/ and test/)")
    }
  }
}
