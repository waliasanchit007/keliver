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
import dev.keliver.portal.ingest.ScreenStateRecognizer
import dev.keliver.portal.ingest.ScreenStateRegistryGenerator
import java.io.File
import java.lang.reflect.Modifier
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
    assertFailsWith<IllegalArgumentException> { ScreenStateRegistryConfig(out = "States.java", packageName = "app") }
    assertFailsWith<IllegalArgumentException> { ScreenStateRegistryConfig(out = "S.kt", packageName = "app", valName = "app states") }
    assertFailsWith<IllegalArgumentException> { ScreenStateRegistryConfig(out = "S.kt", packageName = "app..gen") }
    assertEquals("AppScreenStates", ScreenStateRegistryConfig(out = "S.kt", packageName = "app").valName)
  }

  /** Review L5: no default root package; a block without one is a config error that names the field. */
  @Test fun thePackageIsRequired() {
    val blank = assertFailsWith<IllegalArgumentException> { ScreenStateRegistryConfig(out = "S.kt", packageName = " ") }
    assertEquals("screenStateRegistry.packageName is required: the generated registry's package (for example \"app.generated\")", blank.message)
    val missing = assertFails { loadPortalConfig(app("""{"screensDir": "screens", "screenStateRegistry": {"out": "gen/S.kt"}}""")) }
    assertTrue("packageName" in missing.message.orEmpty() && "required" in missing.message.orEmpty(), missing.message)
  }

  /** Review M1: the command every generated header (and the docs) names is a class with a main, and the Gradle task runs it. */
  @Test fun theCommandTheHeaderNamesExists() {
    val main = Class.forName(ScreenStateRegistryFile.COMMAND).getMethod("main", Array<String>::class.java)
    assertTrue(Modifier.isStatic(main.modifiers))
    assertEquals("ScreenStateRegistryKt", ScreenStateRegistryFile.COMMAND)
    assertTrue("`ScreenStateRegistryKt <app-dir> --check`" in ScreenStateRegistryFile.REGENERATE_HINT)
    assertTrue("mainClass = 'ScreenStateRegistryKt'" in File(repoRoot, "portal-relay/build.gradle").readText())
    assertTrue("`ScreenStateRegistryKt <app-dir> --check`" in File(repoRoot, "docs/PORTAL_USAGE.md").readText())
  }

  /**
   * Review L1: a state the generated call could not compile against (`@Composable`, a return type
   * other than NamedState, a possible opt-in marker) is the same problem in `GET /states` and in
   * the registry, and has no entry.
   */
  @Test fun aStateTheRegistryCannotCallIsTheSameProblemInGetStates() {
    val dir = app()
    File(dir, "screens/cart.kt").writeText(
      "package app.screens\n\nimport androidx.compose.runtime.Composable\nimport app.flags.ExperimentalCheckout\n" +
        "import dev.keliver.nav.NamedState\nimport dev.keliver.nav.ScreenState\n\n" +
        "@ScreenState(\"drawn\") @Composable fun drawn() = NamedState(route = Route.Home, fixtures = \"d\")\n" +
        "@ScreenState(\"any\") fun asAny(): Any = NamedState(route = Route.Home, fixtures = \"a\")\n" +
        "@ScreenState(\"marked\") @ExperimentalCheckout fun marked() = NamedState(route = Route.Home, fixtures = \"m\")\n",
    )
    val config = loadPortalConfig(dir)
    val states = ScreenStatesIndex().forDir(File(dir, "screens"))
    val gen = assertNotNull(ScreenStateRegistryFile.generate(dir, config))
    assertEquals(states, gen.screens)

    // What GET /states answers for the screen.
    val cart = Json.parseToJsonElement(ScreenStatesIndex.toJson(states)).jsonObject["screens"]!!.jsonObject["cart"]!!.jsonObject
    assertEquals(0, cart["states"]!!.jsonArray.size)
    val problems = cart["problems"]!!.jsonArray.map { it.jsonPrimitive.content }
    val starts = listOf(
      "drawn: a state function must not be @Composable",
      "asAny: a state function must return NamedState",
      "marked: @ExperimentalCheckout on a state function",
    )
    assertEquals(starts.size, problems.size, problems.toString())
    starts.zip(problems).forEach { (start, problem) -> assertTrue(problem.startsWith(start), problem) }
    assertEquals(problems.map { "cart: $it" }, gen.problems)

    // The written registry: the same problems, no call to any of them, and it compiles as the rest.
    assertNull(ScreenStateRegistryFile.emit(dir, config))
    val source = File(dir, "gen/AppStates.kt").readText()
    assertTrue("{ home__signedIn() }" in source, source)
    listOf("drawn", "asAny", "marked").forEach { assertTrue("cart__$it" !in source, it) }
    problems.forEach { assertTrue("      \"$it\",\n" in source, it) }
  }

  /**
   * Review L2: a screen file the relay can't read (or the recognizer fails on) is a "cannot read …"
   * problem in the registry exactly as in `GET /states`, not a failed write.
   */
  @Test fun anUnreadableScreenFileIsAProblemInTheRegistryAsInGetStates() {
    val dir = app()
    val cart = File(dir, "screens/cart.kt").apply { writeText(screen("cart-empty", "cartEmpty", "empty")) }
    val config = loadPortalConfig(dir)
    val failing = ScreenStatesIndex { name, source -> if (name == "cart.kt") error("boom") else ScreenStateRecognizer.recognize(name, source) }
    assertNull(ScreenStateRegistryFile.emit(dir, config, failing))
    assertEquals(failing.forDir(File(dir, "screens")), assertNotNull(ScreenStateRegistryFile.generate(dir, config, failing)).screens)
    val source = File(dir, "gen/AppStates.kt").readText()
    assertTrue("{ home__signedIn() }" in source && "cart__cartEmpty" !in source, source)
    assertTrue("  problems = mapOf(\n    \"cart\" to listOf(\n      \"cannot read cart.kt: boom\",\n    ),\n  ),\n" in source, source)

    // A file without read permission, through the real index (skipped where permissions don't
    // apply, as for root).
    if (cart.setReadable(false) && !cart.canRead()) {
      try {
        val states = ScreenStatesIndex().forDir(File(dir, "screens"))
        assertTrue(states.getValue("cart").problems.single().startsWith("cannot read cart.kt: "), states.toString())
        assertNull(ScreenStateRegistryFile.emit(dir, config))
        assertEquals(states, assertNotNull(ScreenStateRegistryFile.generate(dir, config)).screens)
      } finally {
        cart.setReadable(true)
      }
    }
  }

  /** Review L3: a block the relay will never act on (no previewBuildTask) is a startup warning, as compiled components' is. */
  @Test fun aBlockWithoutAPreviewBuildIsAStartupWarning() {
    val warning = assertNotNull(ScreenStateRegistryFile.configWarning(loadPortalConfig(app())))
    assertEquals(
      "portal-server: keliver.portal.json sets screenStateRegistry but no previewBuildTask — this relay will not write " +
        "gen/AppStates.kt; run `ScreenStateRegistryKt <app-dir>` after changing a @ScreenState, and " +
        "`ScreenStateRegistryKt <app-dir> --check` in CI",
      warning,
    )
    val building = app(CONFIG.replace("{\"screensDir\"", "{\"previewBuildTask\": \":app:jsBrowserDistribution\", \"screensDir\""))
    assertNull(ScreenStateRegistryFile.configWarning(loadPortalConfig(building)))
    assertNull(ScreenStateRegistryFile.configWarning(loadPortalConfig(app("""{"screensDir": "screens"}"""))))
  }

  /**
   * Review L4: every directory the relay watches (recursively) is refused as the target, not only
   * the screens directory: a write there would start another preview build, and the components or
   * tokens scan would read the file as one of theirs.
   */
  @Test fun theRegistryCannotGoInAnyDirectoryTheRelayWatches() {
    val dirs = """"screensDir": "screens", "componentsDir": "ui/components", "flowsDir": "ui/flows", """ +
      """"tokensDirs": ["ui/tokens"], "logicDirs": ["logic", "shared/logic"]"""
    val targets = listOf(
      "screens" to "screens/AppStates.kt",
      "components" to "ui/components/AppStates.kt",
      "flows" to "ui/flows/AppStates.kt",
      "tokens" to "ui/tokens/deep/AppStates.kt",
      "logic" to "shared/logic/gen/AppStates.kt",
    )
    for ((kind, target) in targets) {
      val dir = app(CONFIG.replace("gen/AppStates.kt", target).replace("\"screensDir\": \"screens\"", dirs))
      val config = loadPortalConfig(dir)
      val problem = assertNotNull(ScreenStateRegistryFile.emit(dir, config), kind)
      assertTrue("must not be in the $kind directory" in problem && "the relay watches it" in problem, problem)
      assertTrue(!File(dir, target).exists(), kind)
      assertEquals(problem, ScreenStateRegistryFile.check(dir, config), kind)
    }

    // The defaults: components, flows and logic beside the screens directory.
    for (kind in listOf("components", "flows", "logic")) {
      val dir = app(CONFIG.replace("gen/AppStates.kt", "$kind/AppStates.kt"))
      val problem = assertNotNull(ScreenStateRegistryFile.emit(dir, loadPortalConfig(dir)), kind)
      assertTrue("must not be in the $kind directory ($kind)" in problem, problem)
    }
    // A directory beside them is fine.
    val beside = app(CONFIG.replace("gen/AppStates.kt", "generated/AppStates.kt").replace("\"screensDir\": \"screens\"", dirs))
    assertNull(ScreenStateRegistryFile.emit(beside, loadPortalConfig(beside)))
  }

  private companion object {
    const val CONFIG = """{"screensDir": "screens", "screenStateRegistry": {"out": "gen/AppStates.kt", "packageName": "app.gen", "valName": "AppStates"}}"""
  }
}
