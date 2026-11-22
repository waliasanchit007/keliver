import app.cash.zipline.QuickJs
import app.cash.zipline.ZiplineManifest
import app.cash.zipline.loader.ManifestSigner
import app.cash.zipline.loader.ZiplineFile
import hosttemplate.ENGINES_BY_QUICKJS_VERSION
import hosttemplate.HostFacts
import hosttemplate.ManifestPinningHttpClient
import hosttemplate.engineOfQuickJsVersion
import hosttemplate.pickFromIndex
import java.io.File
import java.nio.file.Files
import java.security.KeyPairGenerator
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.Buffer
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import okio.IOException
import hosttemplate.DEFAULT_ENGINE as HOST_DEFAULT_ENGINE
import hosttemplate.ENGINE_METADATA_KEY as HOST_ENGINE_METADATA_KEY
import hostlegacy0318.HostFacts as LegacyHostFacts
import hostlegacy0318.pickFromIndex as legacyPickFromIndex

/**
 * NF2 steps 5–7 (docs/DEVICE_HOST.md, "Engines"): one bundle per engine for one release, each
 * signed manifest naming its engine, and hosts that take (at lookup) and run (at load) only a
 * bundle their engine can load. The hosts' side is the template's BundleIndex.kt; the publisher's
 * is StaticIndex.kt. Both are held to one set of rules here, as BridgeGateTest does for bridges.
 */
class EngineGateTest {
  private val tmp = Files.createTempDirectory("engine-gate").toFile()

  @AfterTest fun cleanUp() { tmp.deleteRecursively() }

  private val q2026 = "quickjs-2026"

  // ---- the hosts' side ---------------------------------------------------------------------

  // The lookup's and the load check's pure rules are the host template's own EngineGateTest
  // (hosttemplate.EngineGateTest, compiled in here too); this holds the publisher to them.

  /**
   * A host from before NF2 (tools 0.3.8 to 0.3.18) skips an entry with a constraint key it does not
   * know (W4.5). So it never takes a bundle for another engine, while the default engine's entry
   * names no engine and reaches it as before. Run on the index reader tools 0.3.18 shipped
   * (src/test/legacy, compiled as hostlegacy0318), held here to its released bytes.
   */
  @Test
  fun aHostFromBeforeEnginesOnlyEverTakesTheDefaultEnginesBundle() {
    val legacy = File(System.getProperty("user.dir")).resolve("src/test/legacy/BundleIndex-0.3.18.kt.txt").readBytes()
    val blobId = java.security.MessageDigest.getInstance("SHA-1").run {
      update("blob ${legacy.size}\u0000".encodeToByteArray())
      digest(legacy).toByteString().hex()
    }
    assertEquals("45cc6d82d70359da92cc289db96e46e20a0a0c51", blobId, "the fixture is 878311ae:scripts/templates/production-host/src/main/kotlin/BundleIndex.kt (tools 0.3.18)")
    fun olderHostPick() = legacyPickFromIndex(indexText(), emptyList(), facts = LegacyHostFacts("", null, 0)).getOrThrow()
      .let { it.sequence to it.manifestPath }
    publishBoth()
    assertEquals(null, entries()[0]["constraints"], "the default engine's entry carries no engine (or any) constraint")
    assertEquals(1L to "v1/manifest.zipline.json", olderHostPick())
    // A release for QuickJS 2026 only: older hosts keep release 1, as a default-engine host does.
    publishStatic(out2026("c", 2), site, app.publicHex)
    assertEquals(1L to "v1/manifest.zipline.json", olderHostPick())
    assertEquals(1L to "v1/manifest.zipline.json", hostPick(DEFAULT_ENGINE))
    assertEquals(2L to "v3/manifest.zipline.json", hostPick(q2026))
    // Its next release for both engines: older hosts take its default-engine bundle.
    publishBoth(seq = 3, init = false)
    assertEquals(3L to "v4/manifest.zipline.json", olderHostPick())
  }

  // ---- the hosts' load ---------------------------------------------------------------------

  private class Fake(private val bodies: Map<String, ByteString>) : app.cash.zipline.loader.ZiplineHttpClient() {
    override suspend fun download(url: String, requestHeaders: List<Pair<String, String>>) = bodies.getValue(url)
    override suspend fun openDevelopmentServerWebSocket(url: String, requestHeaders: List<Pair<String, String>>): Flow<String> = error("unused")
  }

  @Test
  fun theManifestDownloadIsHeldToTheHostsEngine() = runBlocking {
    val url = "https://cdn.example/bundles/v3/manifest.zipline.json"
    val m = """{"modules":{},"metadata":{"keliver.sequence":"2","keliver.engine":"$q2026"}}""".encodeUtf8()
    val fake = Fake(mapOf(url to m))
    assertEquals(m, ManifestPinningHttpClient(fake, url, m.sha256().hex(), engine = q2026).download(url, emptyList()))
    // The index can lie (its hash matches): the signed engine still refuses, before any module is fetched.
    val e = assertFailsWith<IOException> { ManifestPinningHttpClient(fake, url, m.sha256().hex()).download(url, emptyList()) }
    assertTrue(e.message!!.startsWith("engine refused:"), e.message)
    // And a default-engine manifest on a 2026 host.
    val old = """{"modules":{},"metadata":{"keliver.sequence":"2"}}""".encodeUtf8()
    val e2 = assertFailsWith<IOException> {
      ManifestPinningHttpClient(Fake(mapOf(url to old)), url, old.sha256().hex(), engine = q2026).download(url, emptyList())
    }
    assertTrue(e2.message!!.startsWith("engine refused:"), e2.message)
  }

  @Test
  fun thisBuildsZiplineIsTheForksEngine() {
    // NF2, one engine: Keliver builds on its Zipline fork (here the JVM's build of the QuickJS the
    // phones run), QuickJS 2026, which every host built from these templates names quickjs-2026. The
    // default engine (a manifest naming none) stays quickjs-2021 in the protocol, so hosts from
    // before NF2 keep skipping these bundles.
    assertEquals("quickjs-2026", engineOfQuickJsVersion(QuickJs.version))
    assertTrue(engineOfQuickJsVersion(QuickJs.version) != HOST_DEFAULT_ENGINE)
  }

  @Test
  fun thePublisherAndTheHostsNameEnginesAlike() {
    assertEquals(DEFAULT_ENGINE, HOST_DEFAULT_ENGINE)
    assertEquals(ENGINE_METADATA_KEY, HOST_ENGINE_METADATA_KEY)
    assertEquals(ENGINE_BYTECODE_VERSIONS.keys, ENGINES_BY_QUICKJS_VERSION.values.toSet())
    // The signing block's table: version -> engine (the default engine named by none).
    val block = File(System.getProperty("user.dir")).resolve("../scripts/templates/publish/signing.gradle").readText()
    val table = Regex("""def keliverEngines = \[(.*)]""").find(block)!!.groupValues[1]
    val fromBlock = Regex("""(\d+): (null|'([a-z0-9.-]+)')""").findAll(table).associate { it.groupValues[1].toInt() to (it.groupValues[3].ifEmpty { DEFAULT_ENGINE }) }
    assertEquals(ENGINE_BYTECODE_VERSIONS.entries.associate { it.value to it.key }, fromBlock)
  }

  // ---- the publisher -----------------------------------------------------------------------

  private class Key(val publicHex: String, val signer: ManifestSigner)

  private val app = run {
    val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    fun raw32(encoded: ByteArray) = encoded.copyOfRange(encoded.size - 32, encoded.size)
    Key(
      raw32(pair.public.encoded).toByteString().hex(),
      ManifestSigner.Builder().addEd25519(PORTAL_SIGNING_KEY_NAME, raw32(pair.private.encoded).toByteString()).build(),
    )
  }

  /** A .zipline file as Zipline's compiler writes it, its bytecode starting with QuickJS's BC_VERSION [bc]. */
  private fun ziplineFile(bc: Int, text: String): ByteArray {
    val buffer = Buffer()
    ZiplineFile(20211020, (byteArrayOf(bc.toByte()) + text.encodeToByteArray()).toByteString()).writeTo(buffer)
    return buffer.readByteArray()
  }

  /**
   * A compile output for the engine whose bytecode version is [bc], signed for [seq], its
   * manifest naming [named] as keliver.engine (none when null), as the signing block writes it.
   */
  private fun output(name: String, bc: Int, named: String?, seq: Long = 1, by: Key = app): File {
    val dir = File(tmp, "out-$name").apply { mkdirs() }
    val modules = listOf("lib", "main").associateWith { m ->
      val bytes = ziplineFile(bc, "$m of $name")
      File(dir, "$m.zipline").writeBytes(bytes)
      sha256Hex(bytes)
    }
    val metadata = listOfNotNull("\"$SEQUENCE_METADATA_KEY\":\"$seq\"", named?.let { "\"$ENGINE_METADATA_KEY\":$it" }).joinToString(",")
    val json = """{"unsigned":{"signatures":{},"freshAtEpochMs":null,"baseUrl":null},
      |"modules":{${modules.entries.joinToString(",") { (m, sha) -> "\"./$m.js\":{\"url\":\"$m.zipline\",\"sha256\":\"$sha\",\"dependsOnIds\":[]}" }}},
      |"mainModuleId":"./main.js","mainFunction":"zipline.ziplineMain","metadata":{$metadata}}""".trimMargin().replace("\n", "")
    File(dir, "manifest.zipline.json").writeText(by.signer.sign(ZiplineManifest.decodeJson(json)).encodeJson())
    return dir
  }

  private fun out2021(name: String, seq: Long = 1) = output(name, 1, null, seq)
  private fun out2026(name: String, seq: Long = 1) = output(name, 5, "\"$q2026\"", seq)

  private val site = File(tmp, "site")
  private val bundles = File(site, "bundles")
  private fun indexText() = File(bundles, INDEX_FILE).readText()
  private fun entries() = Json.parseToJsonElement(indexText()).jsonObject["entries"]!!.jsonArray.map { it.jsonObject }
  private fun hostPick(engine: String, channel: String = "stable") =
    pickFromIndex(indexText(), emptyList(), channel = channel, facts = HostFacts("", null, 0, engine = engine)).getOrThrow()
      .let { it.sequence to it.manifestPath }

  private fun publishBoth(seq: Long = 1, init: Boolean = true) = publishStaticVariants(
    listOf(PublishVariant(out2021("a$seq", seq)), PublishVariant(out2026("b$seq", seq))), site, app.publicHex, init = init,
  )

  @Test
  fun theBytecodeVersionIsReadFromAZiplineFile() {
    assertEquals(1, ziplineBytecodeVersion(ziplineFile(1, "x")))
    assertEquals(5, ziplineBytecodeVersion(ziplineFile(5, "x")))
    assertNull(ziplineBytecodeVersion("// plain JavaScript".encodeToByteArray()))
    assertNull(ziplineBytecodeVersion(ziplineFile(5, "x").copyOf(14)), "a truncated section")
  }

  @Test
  fun oneReleaseIsPublishedOncePerEngineAtOneSequence() {
    val published = publishBoth()
    assertEquals(listOf(1 to 1L, 2 to 1L), published.map { it.version to it.sequence })
    val e = entries()
    assertNull(e[0]["constraints"])
    assertEquals(JsonObject(mapOf("engine" to JsonPrimitive(q2026))), e[1]["constraints"])
    assertEquals(1L to "v1/manifest.zipline.json", hostPick(DEFAULT_ENGINE))
    assertEquals(1L to "v2/manifest.zipline.json", hostPick(q2026))
    // The next release, for both again; readIndex accepts the layout it wrote.
    publishBoth(seq = 2, init = false)
    assertEquals(2L to "v3/manifest.zipline.json", hostPick(DEFAULT_ENGINE))
    assertEquals(2L to "v4/manifest.zipline.json", hostPick(q2026))
    // A release for the default engine only: 2026 hosts keep release 2.
    publishStatic(out2021("c", 3), site, app.publicHex)
    assertEquals(3L to "v5/manifest.zipline.json", hostPick(DEFAULT_ENGINE))
    assertEquals(2L to "v4/manifest.zipline.json", hostPick(q2026))
  }

  private fun assertRefusedAndNothingWritten(expect: String, publish: () -> Unit) {
    val before = if (bundles.exists()) bundles.walk().filter { it.isFile }.associate { it.path to it.readText() } else emptyMap()
    val e = assertFailsWith<PublishRefused> { publish() }
    assertTrue(expect in e.message.orEmpty(), e.message)
    val after = if (bundles.exists()) bundles.walk().filter { it.isFile && it.name != ".publish.lock" }.associate { it.path to it.readText() } else emptyMap()
    assertEquals(before.filterKeys { !it.endsWith(".publish.lock") }, after)
  }

  @Test
  fun aManifestThatMisnamesItsEngineIsRefused() {
    // Bytecode for 2026 signed as the default engine, and the reverse: hosts would fetch code they cannot load.
    assertRefusedAndNothingWritten("holds QuickJS bytecode version 5 ($q2026), but the signed manifest names no engine") {
      publishStatic(output("x", 5, null), site, app.publicHex, init = true)
    }
    assertRefusedAndNothingWritten("holds QuickJS bytecode version 1 (quickjs-2021), but the signed manifest names $q2026") {
      publishStatic(output("y", 1, "\"$q2026\""), site, app.publicHex, init = true)
    }
    assertRefusedAndNothingWritten("is not an engine this tool publishes for") {
      publishStatic(output("z", 5, "\"quickjs-2031\""), site, app.publicHex, init = true)
    }
    // (Zipline's manifest holds metadata as strings; a hand-made one may not.)
    assertEquals("the manifest's keliver.engine is not a string", signedEngine("""{"modules":{},"metadata":{"keliver.engine":5}}""").problem)
    assertEquals(DEFAULT_ENGINE to null, signedEngine("""{"modules":{}}""").let { it.engine to it.problem })
    assertRefusedAndNothingWritten("holds QuickJS bytecode version 9 (no engine this tool knows)") {
      publishStatic(output("v", 9, null), site, app.publicHex, init = true)
    }
    // Nothing was created, not even bundles/.
    assertFalse(bundles.exists())
  }

  @Test
  fun twoOutputsForOneEngineAreRefusedAndOneBadVariantStopsTheRelease() {
    assertRefusedAndNothingWritten("two of the outputs are for engine quickjs-2021") {
      publishStaticVariants(listOf(PublishVariant(out2021("a")), PublishVariant(out2021("b"))), site, app.publicHex, init = true)
    }
    assertRefusedAndNothingWritten("is sequence 1") {
      publishStaticVariants(listOf(PublishVariant(out2021("c")), PublishVariant(out2026("d", seq = 2))), site, app.publicHex, init = true)
    }
    assertFalse(bundles.exists())
  }

  /**
   * L1 (review of PR #275): under the lock, variant 1 passes its checks and variant 2 then fails
   * (its output changed since the pre-check). Nothing becomes a v<N>/: variant 1 is not moved in
   * before variant 2 is checked. Before, v1/ was left with no index entry.
   */
  @Test
  fun aVariantThatFailsUnderTheLockLeavesNoVersionDirectory() {
    publishBoth() // v1, v2 at sequence 1
    val a = out2021("a2", 2)
    val b = out2026("b2", 2)
    val indexBefore = indexText()
    assertRefusedAndNothingWritten("is signed for sequence 3") {
      publishStaticVariants(
        listOf(PublishVariant(a), PublishVariant(b)), site, app.publicHex,
        // Variant 2's output, rebuilt for another sequence after the pre-check.
        beforeLock = { output("b2", 5, "\"$q2026\"", seq = 3) },
      )
    }
    assertEquals(indexBefore, indexText())
    assertEquals(listOf("v1", "v2"), bundles.listFiles()!!.filter { it.isDirectory }.map { it.name }.sorted(), "no v3/, and no staging left")
    // The same, when moving them in fails after the first move: the one moved is removed again.
    var moves = 0
    val e = assertFailsWith<java.io.IOException> {
      publishStaticVariants(
        listOf(PublishVariant(out2021("a3", 2)), PublishVariant(out2026("b3", 2))), site, app.publicHex,
        moveIn = { from, to -> if (++moves == 2) throw java.io.IOException("disk full") else Files.move(from, to) },
      )
    }
    assertEquals("disk full", e.message)
    assertEquals(2, moves)
    assertEquals(indexBefore, indexText())
    assertEquals(listOf("v1", "v2"), bundles.listFiles()!!.filter { it.isDirectory }.map { it.name }.sorted(), "v3/ removed again")
    // And with both checks passing, the release goes out as v3/ and v4/.
    assertEquals(listOf(3, 4), publishStaticVariants(listOf(PublishVariant(a), PublishVariant(out2026("b4", 2))), site, app.publicHex).map { it.version })
  }

  @Test
  fun theIndexHoldsOneEntryPerEngineAtASequenceAndRefusesOneVersionForTwoEngines() {
    publishBoth()
    val text = indexText()
    // v2 claimed for the default engine too (an entry with the same version and no engine).
    val broken = text.replaceFirst("\"version\": 1", "\"version\": 2").replaceFirst("v1/manifest", "v2/manifest")
    File(bundles, INDEX_FILE).writeText(broken)
    val e = assertFailsWith<PublishRefused> { readIndex(bundles) }
    assertTrue("both engine" in e.message.orEmpty() || "for both v" in e.message.orEmpty() || "different manifests" in e.message.orEmpty(), e.message)
  }

  @Test
  fun aPromotionCarriesEveryEnginesBundleOfTheRelease() {
    publishBoth()
    val p = promoteStatic(site, 1, "beta", app.publicHex)
    assertEquals(1, p.also.size)
    val beta = entries().filter { it["channel"]?.jsonPrimitive?.content == "beta" }
    assertEquals(listOf("1", "2"), beta.map { it["version"]!!.jsonPrimitive.content })
    assertEquals(JsonObject(mapOf("engine" to JsonPrimitive(q2026))), beta[1]["constraints"])
    assertEquals(1L to "v2/manifest.zipline.json", hostPick(q2026, "beta"))
  }

  @Test
  fun aRolloutIsSetOnEveryEnginesEntry() {
    publishStaticVariants(
      listOf(PublishVariant(out2021("a")), PublishVariant(out2026("b"))), site, app.publicHex, init = true, constraints = Constraints(rollout = 10),
    )
    setRolloutStatic(site, 1, 50)
    assertEquals(listOf("50", "50"), entries().map { (it["constraints"] as JsonObject)["rollout"]!!.jsonPrimitive.content })
    assertEquals(q2026, (entries()[1]["constraints"] as JsonObject)["engine"]!!.jsonPrimitive.content)
  }

  @Test
  fun aRepublishRollsBackEveryEngineAtOneNewSequence() {
    publishBoth()
    publishBoth(seq = 2, init = false)
    val resigned = mutableListOf<String>()
    val p = republishStatic(site, 1, app.publicHex) { dir, seq ->
      resigned += dir.name
      val f = File(dir, "manifest.zipline.json")
      val m = Json.parseToJsonElement(f.readText()).jsonObject
      val metadata = m["metadata"]!!.jsonObject + (SEQUENCE_METADATA_KEY to JsonPrimitive(seq.toString()))
      f.writeText(app.signer.sign(ZiplineManifest.decodeJson(JsonObject(m + ("metadata" to JsonObject(metadata))).toString())).encodeJson())
      0
    }
    assertEquals(listOf("v1", "v2"), resigned)
    assertEquals(5 to 3L, p.version to p.sequence)
    assertEquals(listOf(6 to 3L), p.also.map { it.version to it.sequence })
    assertEquals(3L to "v5/manifest.zipline.json", hostPick(DEFAULT_ENGINE))
    assertEquals(3L to "v6/manifest.zipline.json", hostPick(q2026))
    val last = entries().takeLast(2)
    assertEquals(listOf("1", "2"), last.map { it["republishOf"]!!.jsonPrimitive.content })
    assertNull(last[0]["constraints"])
    assertEquals(q2026, (last[1]["constraints"] as JsonObject)["engine"]!!.jsonPrimitive.content)
  }

  @Test
  fun theRelaysOwnIndexNamesEnginesAndItsLegacyAnswerIsDefaultEngineOnly() {
    val store = File(tmp, "store").apply { mkdirs() }
    for ((v, out) in listOf(1 to out2021("r1"), 2 to out2026("r2"))) {
      out.copyRecursively(File(store, "v$v"))
      File(store, "v$v/meta.json").writeText("""{"widgetVersion":1,"capabilities":[],"createdAt":0}""")
    }
    val relayIndex = relayIndexJson(store)
    val picks = listOf(DEFAULT_ENGINE, q2026).map { engine ->
      pickFromIndex(relayIndex, emptyList(), facts = HostFacts("", null, 0, engine = engine)).getOrThrow().manifestPath
    }
    assertEquals(listOf("v1/manifest.zipline.json", "v2/manifest.zipline.json"), picks)
    assertEquals("v1", legacyLatest(store, 1, emptySet())?.name)
    assertNotNull(engineBytecodeProblem(File(tmp, "out-r2"), File(tmp, "out-r1/manifest.zipline.json").readText()))
  }

  @Test
  fun theCliBuildsEachEngineTargetForTheSameSequence() {
    val appDir = File(tmp, "app").apply { mkdirs() }
    File(appDir, "keliver.portal.json").writeText(
      """{"screensDir":"src/jsMain/kotlin/screens","publishTask":":compileProductionExecutableKotlinJsZipline",
        |"publishOutput":"build/zipline/Production",
        |"engineTargets":[{"publishTask":":guest2026:compileProductionExecutableKotlinJsZipline","publishOutput":"guest2026/build/zipline/Production"}]}""".trimMargin(),
    )
    val keyFile = File(tmp, "app.pub").apply { writeText(app.publicHex + "\n") }
    val built = mutableListOf<Pair<String, Long>>()
    val code = KeliverPublish.run(listOf(appDir.path, "--out", site.path, "--public-key-file", keyFile.path, "--init"), null) { dir, task, seq ->
      built += task to seq
      when (task) {
        ":compileProductionExecutableKotlinJsZipline" -> out2021("cli-a", seq).copyRecursively(File(dir, "build/zipline/Production"), overwrite = true)
        else -> out2026("cli-b", seq).copyRecursively(File(dir, "guest2026/build/zipline/Production"), overwrite = true)
      }
      0
    }
    assertEquals(0, code)
    assertEquals(listOf(":compileProductionExecutableKotlinJsZipline" to 1L, ":guest2026:compileProductionExecutableKotlinJsZipline" to 1L), built)
    assertEquals(1L to "v1/manifest.zipline.json", hostPick(DEFAULT_ENGINE))
    assertEquals(1L to "v2/manifest.zipline.json", hostPick(q2026))
    // A failed engine build publishes nothing of the release.
    assertEquals(3, KeliverPublish.run(listOf(appDir.path, "--out", site.path, "--public-key-file", keyFile.path), null) { _, task, _ ->
      if (task.startsWith(":guest2026")) 1 else 0
    })
    assertEquals(2, entries().size)
  }
}
