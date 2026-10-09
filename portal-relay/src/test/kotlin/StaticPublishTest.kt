import app.cash.zipline.ZiplineManifest
import app.cash.zipline.loader.ManifestSigner
import hosttemplate.pickFromIndex
import java.io.File
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
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

  /**
   * A compile task's output: two modules and a manifest naming them, signed by [by] (or unsigned),
   * with [seq] in the signed metadata as the W4 signing block writes it (none when null).
   */
  private fun output(name: String, title: String = name, by: Key? = app, seq: Long? = 1): File {
    val dir = File(tmp, "out-$name").apply { mkdirs() }
    val modules = listOf("lib", "main").associateWith { m ->
      val bytes = "// $m of $title\n".encodeToByteArray()
      File(dir, "$m.zipline").writeBytes(bytes)
      sha256Hex(bytes)
    }
    val json = """{"unsigned":{"signatures":{},"freshAtEpochMs":null,"baseUrl":null},
      |"modules":{${modules.entries.joinToString(",") { (m, sha) -> "\"./$m.js\":{\"url\":\"$m.zipline\",\"sha256\":\"$sha\",\"dependsOnIds\":[]}" }}},
      |"mainModuleId":"./main.js","mainFunction":"zipline.ziplineMain"${if (seq != null) ",\"metadata\":{\"$SEQUENCE_METADATA_KEY\":\"$seq\"}" else ""}}""".trimMargin().replace("\n", "")
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
    val first = publishStatic(output("one"), site, app.publicHex, capabilities = listOf("host-sql@1"), now = at, init = true)
    val second = publishStatic(output("two", seq = 2), site, app.publicHex)
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
    publishStatic(output("one"), site, app.publicHex, init = true)
    publishStatic(output("two", seq = 2), site, app.publicHex, capabilities = listOf("host-http@1"))
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
    val p = publishStatic(output("next", seq = 8), site, app.publicHex)
    assertEquals(4 to 8L, p.version to p.sequence) // past the highest version AND the highest sequence
    assertEquals("kept", index()["note"]!!.jsonPrimitive.content)
    assertEquals(JsonObject(mapOf("minHostVersion" to JsonPrimitive(4))), entries()[0]["constraints"])
  }

  @Test
  fun aVersionDirectoryWithoutAnEntryIsNeverReused() {
    File(bundles, "v5").mkdirs()
    assertEquals(6, publishStatic(output("x"), site, app.publicHex, init = true).version)
  }

  @Test
  fun withoutTheLiveIndexItRefusesUnlessThisIsTheFirstPublish() {
    // An empty CI checkout would otherwise write v1 at sequence 1 again and,
    // once uploaded, replace the v1 hosts already load.
    assertRefusedAndNothingWritten("--init") { publishStatic(output("first"), site, app.publicHex) }
    assertFalse(site.exists())
    File(bundles, "v3").mkdirs() // a v<N> downloaded without its index
    assertRefusedAndNothingWritten("download the served bundles/index.json") { publishStatic(output("x"), site, app.publicHex) }
    assertEquals(4, publishStatic(output("x"), site, app.publicHex, init = true).version)
    // From then on the index is there and --init is not needed.
    assertEquals(5, publishStatic(output("y", seq = 2), site, app.publicHex).version)
  }

  @Test
  fun anOutDirectoryInsideTheCompileOutputIsRefused() {
    val out = output("o")
    val before = out.walkTopDown().map { it.relativeTo(out).path }.toSet()
    val e = assertFailsWith<PublishRefused> { publishStatic(out, File(out, "site"), app.publicHex, init = true) }
    assertTrue("inside the compile output" in e.message!!, e.message)
    assertEquals(before, out.walkTopDown().map { it.relativeTo(out).path }.toSet())
  }

  @Test
  fun aSecondPublishHoldingTheLockIsRefusedWithoutASigningHint() {
    publishStatic(output("one"), site, app.publicHex, init = true)
    java.nio.channels.FileChannel.open(
      File(bundles, ".publish.lock").toPath(),
      java.nio.file.StandardOpenOption.WRITE,
    ).use { ch ->
      ch.lock().use {
        val two = output("two", seq = 2)
        val before = snapshot()
        val e = assertFailsWith<PublishRefused> { publishStatic(two, site, app.publicHex) }
        assertTrue(".publish.lock" in e.message!!, e.message)
        assertFalse(e.aboutSigning)
        assertEquals(before, snapshot())
      }
    }
  }

  @Test
  fun refusalsSayWhetherTheBundleItselfWasTheProblem() {
    val unsigned = assertFailsWith<PublishRefused> { publishStatic(output("u", by = null), site, app.publicHex, init = true) }
    assertTrue(unsigned.aboutSigning)
    val noIndex = assertFailsWith<PublishRefused> { publishStatic(output("n"), site, app.publicHex) }
    assertFalse(noIndex.aboutSigning)
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
    assertRefusedAndNothingWritten("UNSIGNED") { publishStatic(output("u", by = null), site, app.publicHex, init = true) }
    assertFalse(site.exists())
  }

  @Test
  fun aBundleSignedByAnotherKeyIsRefusedAndTheIndexIsUnchanged() {
    publishStatic(output("one"), site, app.publicHex, init = true)
    assertRefusedAndNothingWritten("does not verify") { publishStatic(output("f", by = key(), seq = 2), site, app.publicHex) }
  }

  @Test
  fun anIncompleteOrAlteredBundleIsRefused() {
    val missing = output("m").also { File(it, "lib.zipline").delete() }
    assertRefusedAndNothingWritten("missing") { publishStatic(missing, site, app.publicHex, init = true) }
    val altered = output("a").also { File(it, "lib.zipline").appendText("x") }
    assertRefusedAndNothingWritten("signed manifest says") { publishStatic(altered, site, app.publicHex, init = true) }
  }

  @Test
  fun aModuleUrlOutsideTheBundleIsRefused() {
    val out = output("escape")
    val m = File(out, "manifest.zipline.json")
    val signed = app.signer.sign(ZiplineManifest.decodeJson(m.readText().replace("\"lib.zipline\"", "\"../lib.zipline\""))).encodeJson()
    m.writeText(signed)
    assertRefusedAndNothingWritten("outside the bundle directory") { publishStatic(out, site, app.publicHex, init = true) }
  }

  @Test
  fun anIndexThisToolCannotReadIsRefusedNotOverwritten() {
    bundles.mkdirs()
    val twoAtSequence3 = """{"format":1,"entries":[{"sequence":3,"version":1},{"sequence":3,"version":2}]}"""
    val twoForV1 = """{"format":1,"entries":[{"sequence":1,"version":1},{"sequence":2,"version":1}]}"""
    val outOfRange = """{"format":1,"entries":[{"sequence":1,"version":2147483647}]}"""
    for (bad in listOf("not json", """{"format":2,"entries":[]}""", """{"format":1}""", """{"format":1,"entries":[{"version":1}]}""", twoAtSequence3, twoForV1, outOfRange)) {
      File(bundles, INDEX_FILE).writeText(bad)
      assertRefusedAndNothingWritten("index.json") { publishStatic(output("i"), site, app.publicHex) }
    }
  }

  @Test
  fun aChannelNameIsChecked() {
    assertRefusedAndNothingWritten("channel") { publishStatic(output("c"), site, app.publicHex, channel = "../beta", init = true) }
    assertEquals("beta", publishStatic(output("b"), site, app.publicHex, channel = "beta", init = true).entry["channel"]!!.jsonPrimitive.content)
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
    // Publishes that did not finish (the relay writes meta.json last), and a meta.json without an
    // integer widgetVersion: /bundles/latest never picks these, so the index must not list them.
    output("r3").copyRecursively(File(store, "v3"))
    output("r4").copyRecursively(File(store, "v4"))
    File(store, "v4/meta.json").writeText("""{"version":4,"capabilities":[]}""")
    output("r5").copyRecursively(File(store, "v5"))
    File(store, "v5/meta.json").writeText("not json")
    val idx = Json.parseToJsonElement(relayIndexJson(store)).jsonObject
    val e = idx["entries"]!!.jsonArray.map { it.jsonObject }
    assertEquals(listOf(1L, 2L), e.map { it["sequence"]!!.jsonPrimitive.content.toLong() })
    assertEquals(sha256Hex(File(store, "v2/manifest.zipline.json").readBytes()), e[1]["manifestSha256"]!!.jsonPrimitive.content)
    assertEquals(JsonArray(listOf(JsonPrimitive("host-http@1"))), e[1]["capabilities"])
    assertEquals(2L, pickFromIndex(relayIndexJson(store), listOf("host-http@1")).getOrThrow().sequence)
    assertEquals(1L, pickFromIndex(relayIndexJson(store), emptyList()).getOrThrow().sequence)
  }

  @Test
  fun theSignedSequenceMustBeTheOneThisPublishTakes() {
    publishStatic(output("one"), site, app.publicHex, init = true)
    // Signed for sequence 1 again (a replay of an old build), or for one ahead.
    assertRefusedAndNothingWritten("signed for sequence 1, but this publish is sequence 2") {
      publishStatic(output("stale", seq = 1), site, app.publicHex)
    }
    assertRefusedAndNothingWritten("signed for sequence 3, but this publish is sequence 2") {
      publishStatic(output("ahead", seq = 3), site, app.publicHex)
    }
    assertEquals(2L, publishStatic(output("two", seq = 2), site, app.publicHex).sequence)
  }

  @Test
  fun aManifestWithoutASignedSequenceIsRefusedWithTheUpgradeHint() {
    val e = assertFailsWith<PublishRefused> { publishStatic(output("old-block", seq = null), site, app.publicHex, init = true) }
    assertTrue("no signed $SEQUENCE_METADATA_KEY" in e.message!!, e.message)
    assertFalse(e.aboutSigning) // the fix is the scaffolder upgrade, not the signing key
    assertFalse(site.exists())
  }

  @Test
  fun anInitOverExistingBundlesContinuesTheirSignedSequences() {
    // bundles/v<N>/ downloaded without the index: their signed sequences still bind.
    output("old", seq = 7).copyRecursively(File(bundles, "v3"))
    assertRefusedAndNothingWritten("this publish is sequence 8") { publishStatic(output("x", seq = 1), site, app.publicHex, init = true) }
    val p = publishStatic(output("x8", seq = 8), site, app.publicHex, init = true)
    assertEquals(4 to 8L, p.version to p.sequence)
  }

  @Test
  fun anotherPublishLandingBeforeTheLockIsCaughtUnderIt() {
    publishStatic(output("one"), site, app.publicHex, init = true)
    val e = assertFailsWith<PublishRefused> {
      publishStatic(output("late", seq = 2), site, app.publicHex, beforeLock = {
        // Between this publish's checks and its lock, another takes sequence 2.
        publishStatic(output("early", seq = 2), site, app.publicHex)
      })
    }
    assertTrue("signed for sequence 2, but this publish is sequence 3" in e.message!!, e.message)
    assertEquals(listOf(1L, 2L), entries().map { it["sequence"]!!.jsonPrimitive.content.toLong() })
    assertEquals(setOf(".publish.lock", "index.json", "v1", "v2"), bundles.list()!!.toSet()) // no staging, no v3
  }

  @Test
  fun theSequenceIsInTheSignedPartOfTheManifest() {
    // Rewriting the sequence of a signed manifest breaks its signature: hosts can trust the number.
    val out = output("signed", seq = 1)
    val m = File(out, "manifest.zipline.json")
    m.writeText(m.readText().replace("\"$SEQUENCE_METADATA_KEY\":\"1\"", "\"$SEQUENCE_METADATA_KEY\":\"9\""))
    assertEquals("9", signedSequenceText(m.readText()))
    assertRefusedAndNothingWritten("does not verify") { publishStatic(out, site, app.publicHex, init = true) }
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
    // Without the live index (or --init) nothing is built or published.
    assertEquals(4, KeliverPublish.run(listOf(appDir.path, "--out", out, "--public-key-file", keyFile.path), null) { _, _, _ -> error("built") })
    assertFalse(File(out).exists())
    val ok = KeliverPublish.run(listOf(appDir.path, "--out", out, "--public-key-file", keyFile.path, "--init"), null) { dir, task, seq ->
      built = listOf(dir.path, task, seq.toString()); 0
    }
    assertEquals(0, ok)
    assertEquals(listOf(appDir.absolutePath, ":compileDevelopmentExecutableKotlinJsZipline", "1"), built)
    val e = Json.parseToJsonElement(File(out, "bundles/index.json").readText()).jsonObject["entries"]!!.jsonArray
    assertEquals(listOf("host-sql@1"), e[0].jsonObject["capabilities"]!!.jsonArray.map { it.jsonPrimitive.content })

    assertEquals(3, KeliverPublish.run(listOf(appDir.path, "--out", out, "--public-key-file", keyFile.path), null) { _, _, _ -> 1 })
    assertEquals(1, File(out, "bundles").list()!!.count { it.startsWith("v") })
    // The key from the environment, and a key that is not this app's.
    // --skip-build with the output still signed for sequence 1: the index is at 2 now.
    assertEquals(4, KeliverPublish.run(listOf(appDir.path, "--out", out, "--skip-build"), app.publicHex) { _, _, _ -> error("built") })
    File(appDir, "build/zipline/Development").deleteRecursively()
    output("cli2", seq = 2).copyRecursively(File(appDir, "build/zipline/Development"))
    // An output signed for the right sequence, checked against another app's key: only the key can refuse it.
    assertEquals(4, KeliverPublish.run(listOf(appDir.path, "--out", out, "--skip-build"), key().publicHex) { _, _, _ -> error("built") })
    assertEquals(0, KeliverPublish.run(listOf(appDir.path, "--out", out, "--skip-build"), app.publicHex) { _, _, _ -> error("built") })
    // A build is asked for the next sequence, 3.
    var asked = 0L
    assertEquals(0, KeliverPublish.run(listOf(appDir.path, "--out", out, "--public-key-file", keyFile.path), null) { _, _, seq ->
      asked = seq
      File(appDir, "build/zipline/Development").deleteRecursively()
      output("cli3", seq = seq).copyRecursively(File(appDir, "build/zipline/Development")); 0
    })
    assertEquals(3L, asked)
    assertEquals(3, File(out, "bundles").list()!!.count { it.startsWith("v") })
    // Usage.
    assertEquals(2, KeliverPublish.run(listOf(appDir.path, "--public-key-file", keyFile.path), null) { _, _, _ -> 0 })
    assertEquals(2, KeliverPublish.run(listOf(appDir.path, "--out", out), null) { _, _, _ -> 0 })
    assertEquals(2, KeliverPublish.run(listOf(appDir.path, "--out", out, "--bogus"), app.publicHex) { _, _, _ -> 0 })
    assertEquals(2, KeliverPublish.run(listOf(appDir.path, "--out", "", "--skip-build"), app.publicHex) { _, _, _ -> 0 })
    assertEquals(2, KeliverPublish.run(listOf(appDir.path, "--out", out, "--public-key-file", ""), null) { _, _, _ -> 0 })
  }

  // --- W4.3: --republish (rollback as a new sequence) --------------------------

  /**
   * What the signing block's keliverResign does: the sequence into the copy's metadata, signed
   * again by [by]. [edit] stands for a re-sign that changes more than it may.
   */
  private fun resign(by: Key = app, signFor: Long? = null, edit: (JsonObject) -> JsonObject = { it }): (File, Long) -> Int =
    { dir, seq ->
      val f = File(dir, "manifest.zipline.json")
      val m = Json.parseToJsonElement(f.readText()).jsonObject
      val metadata = (m["metadata"]?.jsonObject ?: JsonObject(emptyMap())) + (SEQUENCE_METADATA_KEY to JsonPrimitive((signFor ?: seq).toString()))
      val next = edit(JsonObject(m + ("metadata" to JsonObject(metadata))))
      f.writeText(by.signer.sign(ZiplineManifest.decodeJson(next.toString())).encodeJson())
      0
    }

  private fun twoPublished() {
    publishStatic(output("one", title = "Depot"), site, app.publicHex, capabilities = listOf("host-sql@1"), init = true)
    publishStatic(output("two", title = "Warehouse", seq = 2), site, app.publicHex, capabilities = listOf("host-sql@1", "host-http@1"))
  }

  @Test
  fun aRepublishIsTheOlderCodeAtTheNextSequence() {
    twoPublished()
    var scratch: File? = null
    val p = republishStatic(site, 1, app.publicHex, now = Instant.parse("2026-10-09T08:00:00Z")) { dir, seq ->
      scratch = dir
      assertEquals(3L, seq)
      resign()(dir, seq)
    }
    assertEquals(3 to 3L, p.version to p.sequence)
    for (m in listOf("lib.zipline", "main.zipline")) {
      assertContentEquals(File(bundles, "v1/$m").readBytes(), File(bundles, "v3/$m").readBytes(), m)
    }
    val manifest = File(bundles, "v3/manifest.zipline.json").readText()
    assertEquals("3", signedSequenceText(manifest))
    assertNull(publishedSignatureProblem(manifest, app.publicHex))
    assertNull(republishProblem(File(bundles, "v1/manifest.zipline.json").readText(), manifest))
    val e = entries().last()
    assertEquals(1, e["republishOf"]!!.jsonPrimitive.int)
    assertEquals("stable", e["channel"]!!.jsonPrimitive.content)
    assertEquals(listOf("host-sql@1"), e["capabilities"]!!.jsonArray.map { it.jsonPrimitive.content }) // v1's, not v2's
    assertEquals(1, e["widgetVersion"]!!.jsonPrimitive.int)
    assertEquals(sha256Hex(manifest.encodeToByteArray()), e["manifestSha256"]!!.jsonPrimitive.content)
    assertEquals("2026-10-09T08:00:00Z", e["createdAt"]!!.jsonPrimitive.content)
    assertFalse(scratch!!.exists(), "the scratch copy was left behind")
    assertEquals(setOf(".publish.lock", "index.json", "v1", "v2", "v3"), bundles.list()!!.toSet())
    // A host that has run v2 picks it: it is the newest entry, and above its floor.
    val pick = pickFromIndex(File(bundles, INDEX_FILE).readText(), listOf("host-sql@1", "host-http@1")).getOrThrow()
    assertEquals(3L to "v3/manifest.zipline.json", pick.sequence to pick.manifestPath)
    assertNull(hosttemplate.rollbackProblem(hosttemplate.manifestSequence(manifest), 2))
  }

  @Test
  fun aRepublishKeepsTheOriginalsChannelUnlessGivenOne() {
    publishStatic(output("one"), site, app.publicHex, channel = "beta", init = true)
    assertEquals("beta", republishStatic(site, 1, app.publicHex, resign = resign()).entry["channel"]!!.jsonPrimitive.content)
    assertEquals("stable", republishStatic(site, 1, app.publicHex, channel = "stable", resign = resign()).entry["channel"]!!.jsonPrimitive.content)
    assertEquals(listOf(1L, 2L, 3L), entries().map { it["sequence"]!!.jsonPrimitive.content.toLong() })
  }

  @Test
  fun aRepublishIsRefusedBeforeSigningWhenTheSourceIsNotAPublishedBundle() {
    val neverCalled: (File, Long) -> Int = { _, _ -> error("re-signed") }
    assertRefusedAndNothingWritten("index.json to republish from") { republishStatic(site, 1, app.publicHex, resign = neverCalled) }
    twoPublished()
    assertRefusedAndNothingWritten("no entry for v9") { republishStatic(site, 9, app.publicHex, resign = neverCalled) }
    output("leftover").copyRecursively(File(bundles, "v7")) // a publish that did not finish
    assertRefusedAndNothingWritten("no entry for v7") { republishStatic(site, 7, app.publicHex, resign = neverCalled) }
    // Checked against another app's key.
    assertRefusedAndNothingWritten("does not verify") { republishStatic(site, 1, key().publicHex, resign = neverCalled) }
    assertTrue(assertFailsWith<PublishRefused> { republishStatic(site, 1, key().publicHex, resign = neverCalled) }.aboutSigning)
    assertRefusedAndNothingWritten("channel 'Beta'") { republishStatic(site, 1, app.publicHex, channel = "Beta", resign = neverCalled) }
    // A v1 directory that is not what the index names.
    val m = File(bundles, "v1/manifest.zipline.json")
    val good = m.readText()
    m.writeText(good + "\n")
    assertRefusedAndNothingWritten("is not the manifest the index entry for v1 names") { republishStatic(site, 1, app.publicHex, resign = neverCalled) }
    m.writeText(good)
    // A module that is not the signed one.
    File(bundles, "v1/lib.zipline").appendText("x")
    assertRefusedAndNothingWritten("signed manifest says") { republishStatic(site, 1, app.publicHex, resign = neverCalled) }
  }

  /** Rewrites v1's index entry with [change]: what a hand edit, or a later tool, may leave there. */
  private fun editFirstEntry(change: (JsonObject) -> JsonObject) {
    val i = index()
    val e = i["entries"]!!.jsonArray.map { it.jsonObject }
    File(bundles, INDEX_FILE).writeText(JsonObject(i + ("entries" to JsonArray(listOf(change(e[0])) + e.drop(1)))).toString())
  }

  @Test
  fun aRepublishKeepsTheOriginalsConstraints() {
    twoPublished()
    val gate = JsonObject(mapOf("minHostVersion" to JsonPrimitive(4)))
    editFirstEntry { JsonObject(it + ("constraints" to gate)) }
    // Hosts that skip the original (a constraint they don't know, or one they fail) skip the republish too.
    assertEquals(gate, republishStatic(site, 1, app.publicHex, resign = resign()).entry["constraints"])
  }

  @Test
  fun aRepublishOfAnEntryHostsWouldReadDifferentlyIsRefused() {
    twoPublished()
    val neverCalled: (File, Long) -> Int = { _, _ -> error("re-signed") }
    val good = File(bundles, INDEX_FILE).readText()
    for ((change, why) in listOf<Pair<(JsonObject) -> JsonObject, String>>(
      { e: JsonObject -> JsonObject(e - "capabilities") } to "has no capabilities list",
      { e: JsonObject -> JsonObject(e + ("capabilities" to JsonArray(listOf(kotlinx.serialization.json.JsonNull)))) } to "not a string",
      { e: JsonObject -> JsonObject(e - "widgetVersion") } to "no integer widgetVersion",
      { e: JsonObject -> JsonObject(e + ("channel" to JsonPrimitive(3))) } to "malformed channel",
      { e: JsonObject -> JsonObject(e + ("constraints" to JsonPrimitive("x"))) } to "constraints that are not an object",
    )) {
      editFirstEntry(change)
      assertRefusedAndNothingWritten(why) { republishStatic(site, 1, app.publicHex, resign = neverCalled) }
      File(bundles, INDEX_FILE).writeText(good)
    }
  }

  @Test
  fun theScratchCopyIsDeletedWhateverHappens() {
    twoPublished()
    var scratch: File? = null
    assertFailsWith<BuildFailed> { republishStatic(site, 1, app.publicHex) { dir, _ -> scratch = dir; 1 } }
    assertFalse(scratch!!.exists())
    assertFailsWith<PublishRefused> { republishStatic(site, 1, app.publicHex) { dir, seq -> scratch = dir; resign(signFor = 1)(dir, seq) } }
    assertFalse(scratch!!.exists())
  }

  @Test
  fun aRepublishThatLosesTheRaceSaysToRunItAgain() {
    twoPublished()
    val e = assertFailsWith<PublishRefused> {
      republishStatic(site, 1, app.publicHex) { dir, seq ->
        publishStatic(output("early", seq = 3), site, app.publicHex) // another publish takes 3 meanwhile
        resign()(dir, seq)
      }
    }
    assertTrue("Run --republish 1 again" in e.message!!, e.message)
    assertEquals(listOf(1L, 2L, 3L), entries().map { it["sequence"]!!.jsonPrimitive.content.toLong() })
  }

  @Test
  fun aReSignThatChangesMoreThanTheSequenceOrSignsAnotherIsRefused() {
    twoPublished()
    assertRefusedAndNothingWritten("modules differs") {
      republishStatic(site, 1, app.publicHex, resign = resign { JsonObject(it + ("modules" to JsonObject(it["modules"]!!.jsonObject - "./lib.js"))) })
    }
    assertRefusedAndNothingWritten("mainFunction differs") {
      republishStatic(site, 1, app.publicHex, resign = resign { JsonObject(it + ("mainFunction" to JsonPrimitive("other.main"))) })
    }
    assertRefusedAndNothingWritten("metadata differs") {
      republishStatic(site, 1, app.publicHex, resign = resign { JsonObject(it + ("metadata" to JsonObject(it["metadata"]!!.jsonObject + ("extra" to JsonPrimitive("1"))))) })
    }
    assertRefusedAndNothingWritten("signed for sequence 1, but this publish is sequence 3") {
      republishStatic(site, 1, app.publicHex, resign = resign(signFor = 1))
    }
    assertRefusedAndNothingWritten("does not verify") { republishStatic(site, 1, app.publicHex, resign = resign(by = key())) }
    val before = snapshot()
    assertEquals(7, assertFailsWith<BuildFailed> { republishStatic(site, 1, app.publicHex) { _, _ -> 7 } }.exitCode)
    assertEquals(before, snapshot())
  }

  @Test
  fun theCliRepublishes() {
    val appDir = File(tmp, "rapp").apply { mkdirs() }
    File(appDir, "keliver.portal.json").writeText(
      """{"screensDir":"src/jsMain/kotlin/screens","publishTask":":compileDevelopmentExecutableKotlinJsZipline","publishOutput":"build/zipline/Development"}""",
    )
    twoPublished()
    val noBuild: (File, String, Long) -> Int = { _, _, _ -> error("built") }
    val noResign: (File, File, Long) -> Int = { _, _, _ -> error("re-signed") }
    fun run(vararg a: String, resign: (File, File, Long) -> Int = noResign) =
      KeliverPublish.run(listOf(appDir.path, "--out", site.path) + a, app.publicHex, resign, noBuild)
    // Usage.
    for (bad in listOf(listOf("--republish"), listOf("--republish", "x"), listOf("--republish", "0"),
      listOf("--republish", "1", "--init"), listOf("--republish", "1", "--skip-build"))) {
      assertEquals(2, run(*bad.toTypedArray()), bad.toString())
    }
    assertEquals(4, run("--republish", "9"))
    assertEquals(3, run("--republish", "1") { _, _, _ -> 1 })
    var asked: Triple<File, File, Long>? = null
    assertEquals(0, run("--republish", "v1") { repo, dir, seq -> asked = Triple(repo, dir, seq); resign()(dir, seq) })
    assertEquals(appDir.absoluteFile, asked!!.first)
    assertEquals(3L, asked!!.third)
    assertEquals(listOf(1L, 2L, 3L), entries().map { it["sequence"]!!.jsonPrimitive.content.toLong() })
    assertEquals(1, entries().last()["republishOf"]!!.jsonPrimitive.int)
  }

  // --- W4.4: channels and promotion ---------------------------------------------

  @Test
  fun aPromotionOffersTheSameBundleOnAnotherChannel() {
    publishStatic(output("one"), site, app.publicHex, capabilities = listOf("host-sql@1"), init = true)
    publishStatic(output("two", seq = 2), site, app.publicHex, channel = "beta", capabilities = listOf("host-sql@1"))
    val idx = { File(bundles, INDEX_FILE).readText() }
    assertEquals(1L, pickFromIndex(idx(), listOf("host-sql@1")).getOrThrow().sequence) // stable hosts: not yet
    assertEquals(2L, pickFromIndex(idx(), listOf("host-sql@1"), channel = "beta").getOrThrow().sequence)
    val before = File(bundles, "v2").walkTopDown().map { it.relativeTo(bundles).path to it.isFile }.toList()
    val p = promoteStatic(site, 2, "stable", app.publicHex, now = Instant.parse("2026-10-09T09:00:00Z"))
    assertEquals(Triple(2L, 2, "beta"), Triple(p.sequence, p.version, p.from))
    val (beta, stable) = entries().filter { it["sequence"]!!.jsonPrimitive.content == "2" }
    assertEquals("stable", stable["channel"]!!.jsonPrimitive.content)
    assertEquals("beta", stable["promotedFrom"]!!.jsonPrimitive.content)
    assertEquals("2026-10-09T09:00:00Z", stable["createdAt"]!!.jsonPrimitive.content)
    for (k in listOf("version", "manifest", "manifestSha256", "capabilities", "widgetVersion")) assertEquals(beta[k], stable[k], k)
    assertEquals(before, File(bundles, "v2").walkTopDown().map { it.relativeTo(bundles).path to it.isFile }.toList()) // no new v<N>/
    assertEquals(setOf(".publish.lock", "index.json", "v1", "v2"), bundles.list()!!.toSet())
    assertEquals(2L, pickFromIndex(idx(), listOf("host-sql@1")).getOrThrow().sequence) // stable hosts now take it
    // The index stays readable, and the next publish continues after it.
    assertEquals(3L, publishStatic(output("three", seq = 3), site, app.publicHex).sequence)
  }

  @Test
  fun aPromotionKeepsTheConstraintsSoAGateNeverFallsAway() {
    publishStatic(output("one"), site, app.publicHex, channel = "beta", init = true)
    val gate = JsonObject(mapOf("minHostVersion" to JsonPrimitive(4)))
    editFirstEntry { JsonObject(it + ("constraints" to gate)) }
    assertEquals(gate, promoteStatic(site, 1, "stable", app.publicHex).entry["constraints"])
  }

  @Test
  fun aPromotionIsRefusedWithNothingWritten() {
    assertRefusedAndNothingWritten("index.json to promote in") { promoteStatic(site, 1, "stable", app.publicHex) }
    publishStatic(output("one"), site, app.publicHex, init = true)
    publishStatic(output("two", seq = 2), site, app.publicHex, channel = "beta")
    publishStatic(output("three", seq = 3), site, app.publicHex)
    assertRefusedAndNothingWritten("no entry at sequence 9") { promoteStatic(site, 9, "stable", app.publicHex) }
    assertRefusedAndNothingWritten("already on channel stable") { promoteStatic(site, 3, "stable", app.publicHex) }
    assertRefusedAndNothingWritten("hosts on channel stable already take sequence 3, above 2") { promoteStatic(site, 2, "stable", app.publicHex) }
    assertRefusedAndNothingWritten("channel 'Stable'") { promoteStatic(site, 2, "Stable", app.publicHex) }
    assertRefusedAndNothingWritten("does not verify") { promoteStatic(site, 3, "canary", key().publicHex) }
    val m = File(bundles, "v3/manifest.zipline.json")
    m.appendText("\n")
    assertRefusedAndNothingWritten("is not the manifest the entry at sequence 3 names") { promoteStatic(site, 3, "canary", app.publicHex) }
    m.writeText(m.readText().trimEnd('\n'))
    // An entry hosts would read differently (the W4.3 lesson applies to promotion too).
    val i = index()
    val e = i["entries"]!!.jsonArray.map { it.jsonObject }
    File(bundles, INDEX_FILE).writeText(JsonObject(i + ("entries" to JsonArray(e.take(2) + JsonObject(e[2] - "capabilities")))).toString())
    assertRefusedAndNothingWritten("has no capabilities list") { promoteStatic(site, 3, "canary", app.publicHex) }
  }

  @Test
  fun theIndexAllowsOneSequenceOnSeveralChannelsButNothingLooser() {
    bundles.mkdirs()
    fun e(seq: Int, v: Int, ch: String) = """{"sequence":$seq,"version":$v,"channel":"$ch"}"""
    File(bundles, INDEX_FILE).writeText("""{"format":1,"entries":[${e(1, 1, "beta")},${e(1, 1, "stable")}]}""")
    assertEquals(2L, nextSequence(readIndex(bundles), bundles))
    for ((bad, why) in listOf(
      "${e(1, 1, "beta")},${e(1, 1, "beta")}" to "two entries with sequence 1 on channel beta",
      "${e(1, 1, "beta")},${e(1, 2, "stable")}" to "sequence 1 for both v1 and v2",
      "${e(1, 1, "beta")},${e(2, 1, "stable")}" to "two entries for v1, at sequences 1 and 2",
    )) {
      File(bundles, INDEX_FILE).writeText("""{"format":1,"entries":[$bad]}""")
      val ex = assertFailsWith<PublishRefused> { readIndex(bundles) }
      assertTrue(why in ex.message!!, ex.message)
    }
  }

  @Test
  fun theCliPromotes() {
    val appDir = File(tmp, "papp").apply { mkdirs() }
    File(appDir, "keliver.portal.json").writeText(
      """{"screensDir":"src/jsMain/kotlin/screens","publishTask":":compileDevelopmentExecutableKotlinJsZipline","publishOutput":"build/zipline/Development"}""",
    )
    publishStatic(output("one"), site, app.publicHex, init = true)
    publishStatic(output("two", seq = 2), site, app.publicHex, channel = "beta")
    fun run(vararg a: String) = KeliverPublish.run(listOf(appDir.path, "--out", site.path) + a, app.publicHex,
      { _, _, _ -> error("re-signed") }, { _, _, _ -> error("built") })
    for (bad in listOf(listOf("--promote", "2"), listOf("--promote", "x", "--channel", "stable"),
      listOf("--promote", "2", "--channel", "stable", "--init"), listOf("--promote", "2", "--channel", "stable", "--skip-build"),
      listOf("--promote", "2", "--channel", "stable", "--republish", "1"))) {
      assertEquals(2, run(*bad.toTypedArray()), bad.toString())
    }
    assertEquals(4, run("--promote", "7", "--channel", "stable"))
    assertEquals(0, run("--promote", "2", "--channel", "stable"))
    assertEquals(listOf("stable", "beta", "stable"), entries().map { it["channel"]!!.jsonPrimitive.content })
  }

  @Test
  fun aHigherEntryThatSomeHostsSkipDoesNotBlockAPromotion() {
    publishStatic(output("one"), site, app.publicHex, capabilities = listOf("host-sql@1"), init = true)
    publishStatic(output("two", seq = 2), site, app.publicHex, channel = "beta", capabilities = listOf("host-sql@1"))
    // Stable's newest needs HostHttp: hosts without it are still on 1, and 2 (beta) is their fix.
    publishStatic(output("three", seq = 3), site, app.publicHex, capabilities = listOf("host-sql@1", "host-http@1"))
    assertEquals("stable", promoteStatic(site, 2, "stable", app.publicHex).entry["channel"]!!.jsonPrimitive.content)
    assertEquals(2L, pickFromIndex(File(bundles, INDEX_FILE).readText(), listOf("host-sql@1")).getOrThrow().sequence)
    assertEquals(3L, pickFromIndex(File(bundles, INDEX_FILE).readText(), listOf("host-sql@1", "host-http@1")).getOrThrow().sequence)
    // To beta, a higher STABLE entry counts too: beta hosts take stable.
    publishStatic(output("four", seq = 4), site, app.publicHex, capabilities = listOf("host-sql@1"))
    publishStatic(output("five", seq = 5), site, app.publicHex, channel = "canary", capabilities = listOf("host-sql@1"))
    assertRefusedAndNothingWritten("hosts on channel beta already take sequence 4") { promoteStatic(site, 3, "beta", app.publicHex) }
  }

  @Test
  fun aRepublishOfAPromotedVersionNamesTheChannel() {
    publishStatic(output("one"), site, app.publicHex, channel = "beta", init = true)
    promoteStatic(site, 1, "stable", app.publicHex)
    assertRefusedAndNothingWritten("is on channels beta, stable") {
      republishStatic(site, 1, app.publicHex) { _, _ -> error("re-signed") }
    }
    assertEquals("stable", republishStatic(site, 1, app.publicHex, channel = "stable", resign = resign()).entry["channel"]!!.jsonPrimitive.content)
  }

  @Test
  fun theIndexRefusesAMalformedChannelAndOneSequenceWithTwoManifests() {
    bundles.mkdirs()
    for ((bad, why) in listOf(
      """{"sequence":1,"version":1,"channel":null}""" to "malformed channel",
      """{"sequence":1,"version":1,"channel":"Beta"}""" to "malformed channel",
      """{"sequence":1,"version":1,"channel":"beta","manifestSha256":"aa"},{"sequence":1,"version":1,"channel":"stable","manifestSha256":"bb"}""" to
        "naming different manifests",
    )) {
      File(bundles, INDEX_FILE).writeText("""{"format":1,"entries":[$bad]}""")
      val ex = assertFailsWith<PublishRefused> { readIndex(bundles) }
      assertTrue(why in ex.message!!, ex.message)
    }
  }
}
