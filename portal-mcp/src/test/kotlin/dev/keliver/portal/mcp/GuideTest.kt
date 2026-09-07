package dev.keliver.portal.mcp

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * Regression: `get_guide` returned "guide not found" for every adopter.
 *
 * It read `<PORTAL_REPO>/docs/PORTAL_USAGE.md`, which exists in this repository
 * and in no app scaffolded by keliver-init, so the tool worked when run from
 * this checkout and failed everywhere else. The guide now ships in the package.
 */
class GuideTest {
  @Test
  fun theGuideIsBundledInThePackage() {
    val res = Tools::class.java.getResourceAsStream("PORTAL_USAGE.md")
    assertTrue(res != null, "PORTAL_USAGE.md must ship as a classpath resource")
    val text = res!!.use { it.readBytes().decodeToString() }
    assertTrue(text.isNotBlank(), "the bundled guide must not be empty")
    assertTrue("Keliver Portal" in text, "the bundled guide should be the usage guide")
  }

  @Test
  fun theBundledGuideIsTheADOPTERGuideNotTheContributorOne() {
    val text = Tools.guideText(repo = null)
    // things only the packaged workflow has
    for (needle in listOf("keliver-portal-tools", "keliver-init", "keliver-store-path.sh", "apply_ops")) {
      assertTrue(needle in text, "the bundled guide should mention '$needle'")
    }
    // things that exist only inside the Keliver repository
    for (repoOnly in listOf("scripts/keliver-dev.sh", "portal-app-lib/", ":portal-device-guest:")) {
      assertFalse(
        repoOnly in text,
        "the bundled guide tells adopters to use '$repoOnly', which their app does not have",
      )
    }
  }

  @Test
  fun theBundledGuideDistinguishesPreviewMocksFromRuntimeValues() {
    val text = Tools.guideText(repo = null)
    assertTrue("placeholder" in text || "mock" in text.lowercase())
    assertTrue("not evidence" in text || "layout checks only" in text)
  }

  @Test
  fun anAppWithoutTheGuideStillGetsOne() {
    val app = createTempDir()          // a scaffolded app: no docs/ at all
    val text = Tools.guideText(repo = app.path)
    assertFalse(text.startsWith("guide unavailable"), "should fall back to the bundled copy")
    assertTrue("Keliver Portal" in text)
  }

  @Test
  fun noRepoAtAllStillGetsTheGuide() {
    val text = Tools.guideText(repo = null)
    assertTrue("Keliver Portal" in text)
  }

  @Test
  fun anAppsOwnGuideWins() {
    val app = createTempDir()
    File(app, "docs").mkdirs()
    File(app, "docs/PORTAL_USAGE.md").writeText("our team's own portal conventions")
    assertEquals("our team's own portal conventions", Tools.guideText(repo = app.path))
  }

  @Test
  fun withNeitherAnAppGuideNorABundledOneTheErrorSaysSo() {
    val app = createTempDir()
    val text = Tools.guideText(repo = app.path, resource = { null })
    assertTrue(text.startsWith("guide unavailable"), "got: $text")
  }

  private fun createTempDir(): File =
    java.nio.file.Files.createTempDirectory("guide-test").toFile().also { it.deleteOnExit() }
}
