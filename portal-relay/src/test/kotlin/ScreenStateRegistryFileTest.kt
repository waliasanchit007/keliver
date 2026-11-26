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
import dev.keliver.portal.ingest.ScreenStateRegistryGenerator
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** MS4-28: the app's generated `@ScreenState` registry, as the relay and the check write and read it. */
class ScreenStateRegistryFileTest {
  private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
    .first { File(it, "settings.gradle").isFile && File(it, "keliver-nav").isDirectory }
  private val fixtureApp = File(repoRoot, "keliver-screen-states-fixture")

  @Test fun theFixtureAppsCheckedInRegistryIsCurrent() {
    assertNull(ScreenStateRegistryFile.check(fixtureApp, loadPortalConfig(fixtureApp)))
  }

  /**
   * The registry and `GET /states` read the same files with the same recognizer. The JSON below
   * is the table keliver-screen-states-fixture's FixtureScreenStatesTest asserts of the compiled
   * registry, so the two answers are pinned to one list.
   */
  @Test fun theRegistryListsWhatGetStatesListsForTheSameScreens() {
    val config = loadPortalConfig(fixtureApp)
    val states = ScreenStatesIndex().forDir(File(fixtureApp, config.screensDir))
    val generated = assertNotNull(ScreenStateRegistryFile.generate(fixtureApp, config))
    assertEquals(states, generated.screens)
    assertEquals(4, generated.states)
    fun state(name: String, function: String, route: String, routeType: String, fixtures: String) =
      """{"name":"$name","function":"$function","route":"$route","routeType":"$routeType","fixtures":"$fixtures"}"""
    assertEquals(
      """{"screens":{""" +
        """"catalog":{"states":[""" +
        state("loaded", "catalogLoaded", "AppRoute.Catalog", "AppRoute.Catalog", "catalog") + "," +
        state("empty", "catalogEmpty", "AppRoute.Catalog", "AppRoute.Catalog", "catalog-empty") +
        """],"problems":[]},""" +
        """"item":{"states":[""" +
        state("detail", "itemDetail", """AppRoute.Item(id = \"42\")""", "AppRoute.Item", "item-42") + "," +
        state("not-found", "itemNotFound", """AppRoute.Item(id = \"404\")""", "AppRoute.Item", "item-404") +
        """],"problems":[]}}}""",
      ScreenStatesIndex.toJson(states),
    )
  }

  private fun app(config: String = CONFIG): File = Files.createTempDirectory("registry-app").toFile().apply {
    File(this, "keliver.portal.json").writeText(config)
    File(this, "screens").mkdirs()
    File(this, "screens/home.kt").writeText(screen("home", "signedIn", "signed-in"))
  }

  private fun screen(fixtures: String, function: String, name: String) =
    "package app.screens\n\nimport dev.keliver.nav.NamedState\nimport dev.keliver.nav.ScreenState\n\n" +
      "@ScreenState(\"$name\") fun $function() = NamedState(route = Route.Home, fixtures = \"$fixtures\")\n"

  @Test fun theRelayWritesOnlyWhenTheTextChangesAndTheCheckSeesAStaleFile() {
    val dir = app()
    val config = loadPortalConfig(dir)
    val out = File(dir, "gen/AppStates.kt")
    assertEquals("gen/AppStates.kt does not exist", ScreenStateRegistryFile.check(dir, config))
    assertNull(ScreenStateRegistryFile.emit(dir, config))
    assertTrue(out.readText().startsWith(ScreenStateRegistryGenerator.GENERATED_HEADER))
    assertTrue("public val AppStates: ScreenStateRegistry" in out.readText())
    assertNull(ScreenStateRegistryFile.check(dir, config))

    // Unchanged screens: the file is left alone (no recompile).
    out.setLastModified(1_000_000L)
    assertNull(ScreenStateRegistryFile.emit(dir, config))
    assertEquals(1_000_000L, out.lastModified())

    // A new state makes the checked-in file stale until it is written again.
    File(dir, "screens/cart.kt").writeText(screen("cart-empty", "cartEmpty", "empty"))
    assertEquals(
      "gen/AppStates.kt is stale: regenerate it (start the relay, or run this without --check)",
      ScreenStateRegistryFile.check(dir, config),
    )
    assertNull(ScreenStateRegistryFile.emit(dir, config))
    assertTrue("{ cart__cartEmpty() }" in out.readText())
    assertNull(ScreenStateRegistryFile.check(dir, config))
  }

  @Test fun aCrlfCheckoutStaysCrlf() {
    val dir = app()
    val config = loadPortalConfig(dir)
    assertNull(ScreenStateRegistryFile.emit(dir, config))
    val out = File(dir, "gen/AppStates.kt")
    out.writeText(out.readText().replace("\n", "\r\n"))
    assertNull(ScreenStateRegistryFile.check(dir, config))
    File(dir, "screens/cart.kt").writeText(screen("cart-empty", "cartEmpty", "empty"))
    assertNull(ScreenStateRegistryFile.emit(dir, config))
    assertTrue("\r\n" in out.readText() && "\n" !in out.readText().replace("\r\n", ""))
  }

  @Test fun aFileTheGeneratorDidNotWriteIsNeverOverwritten() {
    val dir = app()
    File(dir, "gen").mkdirs()
    File(dir, "gen/AppStates.kt").writeText("package app.gen\n\nval AppStates = 1\n")
    val problem = assertNotNull(ScreenStateRegistryFile.emit(dir, loadPortalConfig(dir)))
    assertTrue("was not written by the generator" in problem, problem)
    assertEquals("package app.gen\n\nval AppStates = 1\n", File(dir, "gen/AppStates.kt").readText())
  }

  @Test fun theRegistryCannotGoInTheScreensDirectoryOrOutsideTheApp() {
    val inScreens = app(CONFIG.replace("gen/AppStates.kt", "screens/AppStates.kt"))
    val problem = assertNotNull(ScreenStateRegistryFile.emit(inScreens, loadPortalConfig(inScreens)))
    assertTrue("must not be in the screens directory" in problem, problem)
    assertTrue(!File(inScreens, "screens/AppStates.kt").exists())
    assertEquals(problem, ScreenStateRegistryFile.check(inScreens, loadPortalConfig(inScreens)))

    val outside = app(CONFIG.replace("gen/AppStates.kt", "../AppStates.kt"))
    val escape = assertNotNull(ScreenStateRegistryFile.emit(outside, loadPortalConfig(outside)))
    assertTrue("must stay inside the app repository" in escape, escape)
  }

  @Test fun withoutTheBlockTheRelayWritesNothingAndTheCheckSaysWhy() {
    val dir = app("""{"screensDir": "screens"}""")
    val config = loadPortalConfig(dir)
    assertNull(ScreenStateRegistryFile.emit(dir, config))
    assertNull(ScreenStateRegistryFile.generate(dir, config))
    assertEquals("keliver.portal.json has no screenStateRegistry block", ScreenStateRegistryFile.check(dir, config))
  }

  @Test fun theBlockIsValidated() {
    assertFailsWith<IllegalArgumentException> { ScreenStateRegistryConfig(out = "States.java") }
    assertFailsWith<IllegalArgumentException> { ScreenStateRegistryConfig(out = "S.kt", valName = "app states") }
    assertFailsWith<IllegalArgumentException> { ScreenStateRegistryConfig(out = "S.kt", packageName = "app..gen") }
    assertEquals("AppScreenStates", ScreenStateRegistryConfig(out = "S.kt").valName)
  }

  private companion object {
    const val CONFIG = """{"screensDir": "screens", "screenStateRegistry": {"out": "gen/AppStates.kt", "packageName": "app.gen", "valName": "AppStates"}}"""
  }
}
