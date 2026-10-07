import app.cash.zipline.ZiplineManifest
import app.cash.zipline.loader.ManifestSigner
import hosttemplate.pickFromIndex
import java.io.File
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.ByteString.Companion.toByteString

/**
 * W3: keliver-publish's static layout (StaticIndex.kt). Bundles here are
 * signed by Zipline's own ManifestSigner, as a compile task signs them, and
 * read back by the hosts' own index reader (the template's BundleIndex.kt).
 */
class StaticPublishTest {
  private val tmp = Files.createTempDirectory("static-publish").toFile()

  @AfterTest fun cleanUp() { tmp.deleteRecursively() }

  private class Key(val publicHex: String, val signer: ManifestSigner)

  private fun key(): Key {
    val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    fun raw32(encoded: ByteArray) = encoded.copyOfRange(encoded.size - 32, encoded.size)
    return Key(
      raw32(pair.public.encoded).toByteString().hex(),
      ManifestSigner.Builder().addEd25519(PORTAL_SIGNING_KEY_NAME, raw32(pair.private.encoded).toByteString()).build(),
    )
  }

  private val app = key()

  /** A compile task's output: two modules and a manifest naming them, signed by [by] (or unsigned). */
  private fun output(name: String, title: String = name, by: Key? = app): File {
    val dir = File(tmp, "out-$name").apply { mkdirs() }
    val modules = listOf("lib", "main").associateWith { m ->
      val bytes = "// $m of $title\n".encodeToByteArray()
      File(dir, "$m.zipline").writeBytes(bytes)
      sha256Hex(bytes)
    }
    val json = """{"unsigned":{"signatures":{},"freshAtEpochMs":null,"baseUrl":null},
      |"modules":{${modules.entries.joinToString(",") { (m, sha) -> "\"./$m.js\":{\"url\":\"$m.zipline\",\"sha256\":\"$sha\",\"dependsOnIds\":[]}" }}},
      |"mainModuleId":"./main.js","mainFunction":"zipline.ziplineMain"}""".trimMargin().replace("\n", "")
    val manifest = ZiplineManifest.decodeJson(json)
    File(dir, "manifest.zipline.json").writeText((by?.signer?.sign(manifest) ?: manifest).encodeJson())
    return dir
  }

  private val site = File(tmp, "site")
  private val bundles = File(site, "bundles")
  private fun index() = Json.parseToJsonElement(File(bundles, INDEX_FILE).readText()).jsonObject
  private fun entries() = index()["entries"]!!.jsonArray.map { it.jsonObject }

  @Test
  fun publishesVersionedDirectoriesAndAnIndexAtTheNextSequence() {
    val at = Instant.parse("2026-10-07T12:00:00Z")
    val first = publishStatic(output("one"), site, app.publicHex, capabilities = listOf("host-sql@1"), now = at)
    val second = publishStatic(output("two"), site, app.publicHex)
    assertEquals(1 to 1L, first.version to first.sequence)
    assertEquals(2 to 2L, second.version to second.sequence)
    assertEquals(1, index()["format"]!!.jsonPrimitive.content.toInt())
    val e = entries()
    assertEquals(listOf(1L, 2L), e.map { it["sequence"]!!.jsonPrimitive.content.toLong() })
    assertEquals("v1/manifest.zipline.json", e[0]["manifest"]!!.jsonPrimitive.content)
    assertEquals("stable", e[0]["channel"]!!.jsonPrimitive.content)
    assertEquals(listOf("host-sql@1"), e[0]["capabilities"]!!.jsonArray.map { it.jsonPrimitive.content })
    assertEquals("2026-10-07T12:00:00Z", e[0]["createdAt"]!!.jsonPrimitive.content)
    for (v in 1..2) {
      val copied = File(bundles, "v$v/manifest.zipline.json")
      assertEquals(sha256Hex(copied.readBytes()), e[v - 1]["manifestSha256"]!!.jsonPrimitive.content)
      assertTrue(File(bundles, "v$v/main.zipline").isFile)
    }
    // No staging directory or temp index is left behind.
    assertEquals(setOf(".publish.lock", "index.json", "v1", "v2"), bundles.list()!!.toSet())
  }

  @Test
  fun theHostsIndexReaderPicksWhatWasPublished() {
    publishStatic(output("one"), site, app.publicHex)
    publishStatic(output("two"), site, app.publicHex, capabilities = listOf("host-http@1"))
    val withHttp = pickFromIndex(File(bundles, INDEX_FILE).readText(), listOf("host-sql@1", "host-http@1")).getOrThrow()
    assertEquals(2L, withHttp.sequence)
    assertEquals(sha256Hex(File(bundles, withHttp.manifestPath).readBytes()), withHttp.manifestSha256)
    // A host without HostHttp gets the newest bundle it can run.
    assertEquals(1L, pickFromIndex(File(bundles, INDEX_FILE).readText(), listOf("host-sql@1")).getOrThrow().sequence)
  }

  @Test
  fun existingEntriesAreKeptVerbatimIncludingFieldsThisToolDoesNotKnow() {
    bundles.mkdirs()
    File(bundles, INDEX_FILE).writeText(
      """{"format":1,"note":"kept","entries":[{"sequence":7,"version":3,"channel":"beta","constraints":{"minHostVersion":4},"manifest":"v3/manifest.zipline.json"}]}""",
    )
    val p = publishStatic(output("next"), site, app.publicHex)
    assertEquals(4 to 8L, p.version to p.sequence) // past the highest version AND the highest sequence
    assertEquals("kept", index()["note"]!!.jsonPrimitive.content)
    assertEquals(JsonObject(mapOf("minHostVersion" to JsonPrimitive(4))), entries()[0]["constraints"])
  }

  @Test
  fun aVersionDirectoryWithoutAnEntryIsNeverReused() {
    File(bundles, "v5").mkdirs()
    assertEquals(6, publishStatic(output("x"), site, app.publicHex).version)
  }

  private fun assertRefusedAndNothingWritten(expect: String, publish: () -> Unit) {
    val before = snapshot()
    val e = assertFailsWith<PublishRefused> { publish() }
    assertTrue(expect in e.message!!, e.message)
    assertEquals(before, snapshot(), "the site changed")
  }

  private fun snapshot(): Map<String, String> =
    site.walkTopDown().filter { it.isFile || it.isDirectory }.associate { it.relativeTo(tmp).path to if (it.isFile) sha256Hex(it.readBytes()) else "dir" }

  @Test
  fun anUnsignedBundleIsRefusedAndNoDirectoryIsCreated() {
    assertRefusedAndNothingWritten("UNSIGNED") { publishStatic(output("u", by = null), site, app.publicHex) }
    assertFalse(site.exists())
  }

  @Test
  fun aBundleSignedByAnotherKeyIsRefusedAndTheIndexIsUnchanged() {
    publishStatic(output("one"), site, app.publicHex)
    assertRefusedAndNothingWritten("does not verify") { publishStatic(output("f", by = key()), site, app.publicHex) }
  }

  @Test
  fun anIncompleteOrAlteredBundleIsRefused() {
    val missing = output("m").also { File(it, "lib.zipline").delete() }
    assertRefusedAndNothingWritten("missing") { publishStatic(missing, site, app.publicHex) }
    val altered = output("a").also { File(it, "lib.zipline").appendText("x") }
    assertRefusedAndNothingWritten("signed manifest says") { publishStatic(altered, site, app.publicHex) }
  }

  @Test
  fun aModuleUrlOutsideTheBundleIsRefused() {
    val out = output("escape")
    val m = File(out, "manifest.zipline.json")
    val signed = app.signer.sign(ZiplineManifest.decodeJson(m.readText().replace("\"lib.zipline\"", "\"../lib.zipline\""))).encodeJson()
    m.writeText(signed)
    assertRefusedAndNothingWritten("outside the bundle directory") { publishStatic(out, site, app.publicHex) }
  }

  @Test
  fun anIndexThisToolCannotReadIsRefusedNotOverwritten() {
    bundles.mkdirs()
    for (bad in listOf("not json", """{"format":2,"entries":[]}""", """{"format":1}""", """{"format":1,"entries":[{"version":1}]}""")) {
      File(bundles, INDEX_FILE).writeText(bad)
      assertRefusedAndNothingWritten("index.json") { publishStatic(output("i"), site, app.publicHex) }
    }
  }

  @Test
  fun aChannelNameIsChecked() {
    assertRefusedAndNothingWritten("channel") { publishStatic(output("c"), site, app.publicHex, channel = "../beta") }
    assertEquals("beta", publishStatic(output("b"), site, app.publicHex, channel = "beta").entry["channel"]!!.jsonPrimitive.content)
  }

  @Test
  fun theRelayServesTheSameFormatFromItsStore() {
    val store = File(tmp, "store-bundles")
    for ((v, caps) in listOf(1 to "", 2 to "\"host-http@1\"")) {
      val dir = File(store, "v$v")
      output("r$v").copyRecursively(dir)
      File(dir, "meta.json").writeText("""{"version":$v,"widgetVersion":1,"capabilities":[$caps],"createdAt":1759838400000}""")
    }
    File(store, "v9-not-a-bundle").mkdirs()
    val idx = Json.parseToJsonElement(relayIndexJson(store)).jsonObject
    val e = idx["entries"]!!.jsonArray.map { it.jsonObject }
    assertEquals(listOf(1L, 2L), e.map { it["sequence"]!!.jsonPrimitive.content.toLong() })
    assertEquals(sha256Hex(File(store, "v2/manifest.zipline.json").readBytes()), e[1]["manifestSha256"]!!.jsonPrimitive.content)
    assertEquals(JsonArray(listOf(JsonPrimitive("host-http@1"))), e[1]["capabilities"])
    assertEquals(2L, pickFromIndex(relayIndexJson(store), listOf("host-http@1")).getOrThrow().sequence)
    assertEquals(1L, pickFromIndex(relayIndexJson(store), emptyList()).getOrThrow().sequence)
  }

  @Test
  fun theCliPublishesRefusesAndReportsBuildFailures() {
    val appDir = File(tmp, "app").apply { mkdirs() }
    output("cli").copyRecursively(File(appDir, "build/zipline/Development"))
    File(appDir, "keliver.portal.json").writeText(
      """{"screensDir":"src/jsMain/kotlin/screens","publishTask":":compileDevelopmentExecutableKotlinJsZipline","publishOutput":"build/zipline/Development"}""",
    )
    File(appDir, "src/jsMain/kotlin/screens").mkdirs()
    File(appDir, "src/jsMain/kotlin/screens/capabilities.txt").writeText("# required\nhost-sql@1\n")
    val keyFile = File(tmp, "app.pub").apply { writeText(app.publicHex + "\n") }
    val out = File(tmp, "cli-site").path

    var built = emptyList<String>()
    val ok = KeliverPublish.run(listOf(appDir.path, "--out", out, "--public-key-file", keyFile.path), null) { dir, task ->
      built = listOf(dir.path, task); 0
    }
    assertEquals(0, ok)
    assertEquals(listOf(appDir.absolutePath, ":compileDevelopmentExecutableKotlinJsZipline"), built)
    val e = Json.parseToJsonElement(File(out, "bundles/index.json").readText()).jsonObject["entries"]!!.jsonArray
    assertEquals(listOf("host-sql@1"), e[0].jsonObject["capabilities"]!!.jsonArray.map { it.jsonPrimitive.content })

    assertEquals(3, KeliverPublish.run(listOf(appDir.path, "--out", out, "--public-key-file", keyFile.path), null) { _, _ -> 1 })
    assertEquals(1, File(out, "bundles").list()!!.count { it.startsWith("v") })
    // The key from the environment, and a key that is not this app's.
    assertEquals(4, KeliverPublish.run(listOf(appDir.path, "--out", out, "--skip-build"), key().publicHex) { _, _ -> error("built") })
    assertEquals(0, KeliverPublish.run(listOf(appDir.path, "--out", out, "--skip-build"), app.publicHex) { _, _ -> error("built") })
    assertEquals(2, File(out, "bundles").list()!!.count { it.startsWith("v") })
    // Usage.
    assertEquals(2, KeliverPublish.run(listOf(appDir.path, "--public-key-file", keyFile.path), null) { _, _ -> 0 })
    assertEquals(2, KeliverPublish.run(listOf(appDir.path, "--out", out), null) { _, _ -> 0 })
    assertEquals(2, KeliverPublish.run(listOf(appDir.path, "--out", out, "--bogus"), app.publicHex) { _, _ -> 0 })
    assertEquals(2, KeliverPublish.run(listOf(appDir.path, "--out", "", "--skip-build"), app.publicHex) { _, _ -> 0 })
    assertEquals(2, KeliverPublish.run(listOf(appDir.path, "--out", out, "--public-key-file", ""), null) { _, _ -> 0 })
  }
}
