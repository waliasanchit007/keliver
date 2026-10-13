import app.cash.zipline.ZiplineManifest
import app.cash.zipline.loader.ManifestSigner
import app.cash.zipline.loader.ManifestVerifier
import hosttemplate.LEGACY_SIGNATURE_NAME
import hosttemplate.floorAcross
import hosttemplate.trustedSignatures
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyPairGenerator
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okio.ByteString
import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.toByteString

/**
 * W8, signing-key rotation. Hosts are Zipline's own ManifestVerifier, built
 * exactly as the host templates build it (BundleIndex.kt trustedSignatures);
 * bundles are signed with Zipline's own ManifestSigner, in the order the
 * signing block (templates/publish/signing.gradle) uses: each key under its own
 * name, then portal-ed25519 by the first key. The publisher's check
 * (checkSignatures) must never accept what such a host refuses.
 */
class KeyRotationTest {
  private val tmp = Files.createTempDirectory("key-rotation").toFile()

  @AfterTest fun cleanUp() { tmp.deleteRecursively() }

  private class Key(val seedHex: String, val publicHex: String)

  private fun key(): Key {
    val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    fun raw32(encoded: ByteArray) = encoded.copyOfRange(encoded.size - 32, encoded.size).toByteString().hex()
    return Key(raw32(pair.private.encoded), raw32(pair.public.encoded))
  }

  /**
   * Zipline's seed-to-key-pair function, reached the way the signing block (Groovy) reaches it:
   * by its JVM name, since Kotlin code outside Zipline may not call it.
   */
  private fun publicKeyOfSeed(seed: ByteString): String {
    val pair = Class.forName("app.cash.zipline.loader.internal.tink.subtle.KeyPairKt")
      .getMethod("newKeyPairFromSeed", ByteString::class.java).invoke(null, seed)
    return (pair.javaClass.getMethod("getPublicKey").invoke(pair) as ByteString).hex()
  }

  private val k0 = key()
  private val k1 = key()
  private val k2 = key()

  /** What the signing block does with a key file holding [keys] (W8). */
  private fun signW8(manifest: ZiplineManifest, vararg keys: Key): ZiplineManifest {
    val builder = ManifestSigner.Builder()
    for (k in keys) {
      // The signing block derives the public key from the seed, as here.
      val pub = publicKeyOfSeed(k.seedHex.decodeHex())
      builder.addEd25519("keliver-ed25519-${pub.take(16)}", k.seedHex.decodeHex())
    }
    return builder.addEd25519(LEGACY_SIGNATURE_NAME, keys.first().seedHex.decodeHex()).build().sign(manifest)
  }

  /** What a signing block before W8 does: one key, as portal-ed25519. */
  private fun signLegacy(manifest: ZiplineManifest, key: Key): ZiplineManifest =
    ManifestSigner.Builder().addEd25519(LEGACY_SIGNATURE_NAME, key.seedHex.decodeHex()).build().sign(manifest)

  private fun manifest(seq: Long = 1, title: String = "x"): ZiplineManifest = ZiplineManifest.decodeJson(
    """{"unsigned":{"signatures":{},"freshAtEpochMs":null,"baseUrl":null},
      |"modules":{"./main.js":{"url":"main.zipline","sha256":"${sha256Hex("// $title".encodeToByteArray())}","dependsOnIds":[]}},
      |"mainModuleId":"./main.js","mainFunction":"zipline.ziplineMain","metadata":{"$SEQUENCE_METADATA_KEY":"$seq"}}""".trimMargin().replace("\n", ""),
  )

  /** A host from the W8 templates trusting [keys]. */
  private fun host(vararg keys: Key): ManifestVerifier = ManifestVerifier.Builder().apply {
    trustedSignatures(keys.map { it.publicHex }).forEach { (name, hex) -> addEd25519(name, hex.decodeHex()) }
  }.build()

  /** A host from before W8: one key, as portal-ed25519. */
  private fun oldHost(key: Key): ManifestVerifier = ManifestVerifier.Builder().addEd25519(LEGACY_SIGNATURE_NAME, key.publicHex.decodeHex()).build()

  private fun ManifestVerifier.accepts(m: ZiplineManifest): Boolean {
    val json = m.encodeJson()
    return runCatching { verify(json.encodeToByteArray().toByteString(), ZiplineManifest.decodeJson(json)) }.isSuccess
  }

  @Test
  fun theSigningBlocksPublicKeyDerivationMatchesTheJdks() {
    for (k in listOf(k0, k1, k2)) assertEquals(k.publicHex, publicKeyOfSeed(k.seedHex.decodeHex()))
  }

  @Test
  fun portalEd25519IsTheLastSignature() {
    val names = Json.parseToJsonElement(signW8(manifest(), k0, k1).encodeJson()).jsonObject["unsigned"]!!
      .jsonObject["signatures"]!!.jsonObject.keys.toList()
    assertEquals(listOf("keliver-ed25519-${k0.publicHex.take(16)}", "keliver-ed25519-${k1.publicHex.take(16)}", LEGACY_SIGNATURE_NAME), names)
  }

  @Test
  fun aRotationInThreeStepsNeverStrandsAHost() {
    val m = manifest()
    val step1 = signW8(m, k0) // hosts trusting k0 and k1 ship; bundles still signed by k0
    val step2 = signW8(m, k0, k1) // dual-signed
    val step3 = signW8(m, k1) // k0 retired
    val legacy = signLegacy(m, k0) // a bundle from a signing block before W8

    // A host shipped before W8.
    assertTrue(oldHost(k0).accepts(step1) && oldHost(k0).accepts(step2) && oldHost(k0).accepts(legacy))
    assertFalse(oldHost(k0).accepts(step3), "a host that trusts only the retired key refuses the new key's bundles")
    // A W8 host with one key.
    assertTrue(host(k0).accepts(step1) && host(k0).accepts(step2) && host(k0).accepts(legacy))
    assertFalse(host(k0).accepts(step3))
    // The rotation host: both keys.
    for (b in listOf(step1, step2, step3, legacy)) assertTrue(host(k0, k1).accepts(b))
    // After the rotation: only the new key.
    assertTrue(host(k1).accepts(step2) && host(k1).accepts(step3))
    assertFalse(host(k1).accepts(step1), "a host that trusts only the new key refuses an old-only bundle")
    assertFalse(host(k1).accepts(legacy))
    // The next rotation works the same way.
    val next = signW8(m, k1, k2)
    assertTrue(host(k1).accepts(next) && host(k1, k2).accepts(next) && host(k2).accepts(next))
    assertTrue(host(k1, k2).accepts(step3))
    assertFalse(host(k2).accepts(step3))
  }

  @Test
  fun aForeignOrTamperedBundleIsRefusedEverywhere() {
    val foreign = signW8(manifest(), k2)
    for (h in listOf(host(k0), host(k0, k1), host(k1), oldHost(k0))) assertFalse(h.accepts(foreign))
    // A signed manifest whose signed part changes afterwards.
    val signed = signW8(manifest(), k0, k1)
    val json = signed.encodeJson()
    val tampered = ZiplineManifest.decodeJson(json.replace("\"$SEQUENCE_METADATA_KEY\":\"1\"", "\"$SEQUENCE_METADATA_KEY\":\"99\"").also { check(it != json) })
    for (h in listOf(host(k0), host(k0, k1), host(k1), oldHost(k0))) assertFalse(h.accepts(tampered))
  }

  @Test
  fun thePublishersCheckNeverAcceptsWhatAHostRefuses() {
    val m = manifest()
    // Bundle name -> (the bundle, the keys that signed it, in signing-key-file order).
    val bundles = mapOf(
      "k0" to (signW8(m, k0) to listOf(k0)), "k0+k1" to (signW8(m, k0, k1) to listOf(k0, k1)),
      "k1+k0" to (signW8(m, k1, k0) to listOf(k1, k0)), "k1" to (signW8(m, k1) to listOf(k1)),
      "k1+k2" to (signW8(m, k1, k2) to listOf(k1, k2)), "k2+k0" to (signW8(m, k2, k0) to listOf(k2, k0)),
      "k2" to (signW8(m, k2) to listOf(k2)), "legacy k0" to (signLegacy(m, k0) to listOf(k0)),
      "legacy k1" to (signLegacy(m, k1) to listOf(k1)),
    )
    val keyFiles = listOf(listOf(k0), listOf(k0, k1), listOf(k1), listOf(k1, k2), listOf(k1, k0))
    for (keys in keyFiles) {
      val text = keys.joinToString("\n") { it.publicHex } + "\n"
      for ((name, signed) in bundles) {
        val (b, signers) = signed
        // A W8 host trusting the file's keys must load it; and when this app's key (the file's first)
        // signed, so must every host from before W8, which trusts only portal-ed25519 with that key.
        val expected = host(*keys.toTypedArray()).accepts(b) && (keys.first() !in signers || oldHost(keys.first()).accepts(b))
        val check = checkSignatures(b.encodeJson(), text)
        assertEquals(expected, check.problem == null, "key file ${keys.map { it.publicHex.take(4) }}, bundle $name: ${check.problem}")
        if (check.problem == null) assertTrue(host(*keys.toTypedArray()).accepts(b))
      }
    }
  }

  @Test
  fun theNextKeyListedFirstIsRefusedBecauseOlderHostsWouldBeStranded() {
    val swapped = signW8(manifest(), k1, k0).encodeJson()
    val problem = checkSignatures(swapped, "${k0.publicHex}\n${k1.publicHex}").problem
    assertNotNull(problem)
    assertTrue("List this app's key FIRST" in problem, problem)
    assertFalse(oldHost(k0).accepts(signW8(manifest(), k1, k0)))
  }

  @Test
  fun aRepublishSourceNeedsOnlyAListedKeysSignature() {
    // A bundle signed before W8 by k0, checked against a file that now lists k1 first.
    val legacy = signLegacy(manifest(), k0).encodeJson()
    assertNotNull(checkSignatures(legacy, "${k1.publicHex}\n${k0.publicHex}").problem)
    assertEquals(listOf(k0.publicHex), checkSignatures(legacy, "${k1.publicHex}\n${k0.publicHex}", authenticOnly = true).signers)
    assertNull(checkSignatures(legacy, "${k1.publicHex}\n${k0.publicHex}", authenticOnly = true).problem)
    // Still refused when no listed key signed it.
    assertNotNull(checkSignatures(legacy, k2.publicHex, authenticOnly = true).problem)
  }

  @Test
  fun theGuardRefusesWhenItCannotTellTheNewestSigner() {
    val site = File(tmp, "site")
    publishStatic(output("a", 1) { signW8(it, k0) }, site, k0.publicHex, init = true)
    File(site, "bundles/v1").deleteRecursively() // a CI job that downloaded only index.json
    val e = assertFailsWith<PublishRefused> { publishStatic(output("b", 2) { signW8(it, k0) }, site, k0.publicHex) }
    assertTrue("cannot tell which key signed" in e.message!!, e.message)
    publishStatic(output("c", 2) { signW8(it, k0) }, site, k0.publicHex, acceptKeyChange = true)
  }

  @Test
  fun thePublishersCheckIsStricterAboutBrokenSignatures() {
    val dual = Json.parseToJsonElement(signW8(manifest(), k0, k1).encodeJson()).jsonObject
    val sigs = dual["unsigned"]!!.jsonObject["signatures"]!!.jsonObject
    val k1Name = "keliver-ed25519-${k1.publicHex.take(16)}"
    val broken = JsonObject(
      dual + ("unsigned" to JsonObject(dual["unsigned"]!!.jsonObject + ("signatures" to JsonObject(sigs + (k1Name to JsonPrimitive("00".repeat(64))))))),
    ).toString()
    // A host trusting k0 first never reads the broken k1 signature, but one trusting only k1 would.
    val problem = checkSignatures(broken, "${k0.publicHex}\n${k1.publicHex}").problem
    assertNotNull(problem)
    assertTrue("$k1Name signature does not verify" in problem, problem)
    // portal-ed25519 must come last.
    val reordered = JsonObject(
      dual + ("unsigned" to JsonObject(dual["unsigned"]!!.jsonObject + ("signatures" to JsonObject(sigs.entries.reversed().associate { it.key to it.value })))),
    ).toString()
    assertTrue("not the last one" in checkSignatures(reordered, k0.publicHex).problem!!)
  }

  @Test
  fun aKeyFileHoldsOneOrTwoDistinctKeys() {
    assertEquals(listOf(k0.publicHex), parsePublicKeys("\n ${k0.publicHex.uppercase()} \n\n").getOrThrow())
    assertEquals(listOf(k0.publicHex, k1.publicHex), parsePublicKeys("${k0.publicHex}\n${k1.publicHex}").getOrThrow())
    assertTrue(parsePublicKeys("${k0.publicHex}\n${k1.publicHex}\n${k2.publicHex}").isFailure)
    assertTrue(parsePublicKeys("${k0.publicHex}\n${k0.publicHex}").isFailure)
    assertTrue(parsePublicKeys("").isFailure)
    assertTrue(parsePublicKeys("${k0.publicHex}\nabc").isFailure)
    assertFailsWith<IllegalArgumentException> { trustedSignatures(listOf(k0.publicHex, k0.publicHex)) }
  }

  @Test
  fun theFloorAcrossKeysIsTheHighest() {
    assertEquals(0L, floorAcross(listOf(null)))
    assertEquals(9L, floorAcross(listOf(3L, null, 9L)))
    assertEquals(Long.MAX_VALUE, floorAcross(listOf(Long.MAX_VALUE, 4L)), "an unreadable floor still refuses everything")
  }

  /** A compile output signed by [sign], at [seq]. */
  private fun output(name: String, seq: Long, sign: (ZiplineManifest) -> ZiplineManifest): File {
    val dir = File(tmp, "out-$name").apply { mkdirs() }
    File(dir, "main.zipline").writeBytes("// $name".encodeToByteArray())
    File(dir, "manifest.zipline.json").writeText(sign(manifest(seq, name)).encodeJson())
    return dir
  }

  @Test
  fun publishingRefusesAnUnannouncedKeyChange() {
    val site = File(tmp, "site")
    val both = "${k0.publicHex}\n${k1.publicHex}\n"
    publishStatic(output("a", 1) { signW8(it, k0) }, site, both, init = true)
    // Straight from k0 to k1: refused, and nothing written.
    val e = assertFailsWith<PublishRefused> { publishStatic(output("b", 2) { signW8(it, k1) }, site, both) }
    assertTrue("--accept-key-change" in e.message!!, e.message)
    assertFalse(File(site, "bundles/v2").exists())
    // Dual-signing first, then the new key alone: never refused.
    publishStatic(output("c", 2) { signW8(it, k0, k1) }, site, both)
    publishStatic(output("d", 3) { signW8(it, k1) }, site, both)
    // A key file that drops the retired key still publishes the new key's bundles.
    publishStatic(output("e", 4) { signW8(it, k1) }, site, "${k1.publicHex}\n")
    // An intended jump to another key, said so.
    publishStatic(output("f", 5) { signW8(it, k2) }, site, "${k2.publicHex}\n", acceptKeyChange = true)
    val entries = Json.parseToJsonElement(File(site, "bundles/index.json").readText()).jsonObject["entries"]!!.jsonArray
    assertEquals(5, entries.size)
    // A key not in the file at all is refused as before, whatever the flag.
    assertFailsWith<PublishRefused> { publishStatic(output("g", 6) { signW8(it, k0) }, site, "${k2.publicHex}\n", acceptKeyChange = true) }
  }

  @Test
  fun aBundleSignedBeforeW8CountsAsSignedByTheFirstKey() {
    val site = File(tmp, "site")
    publishStatic(output("a", 1) { signLegacy(it, k0) }, site, k0.publicHex, init = true)
    // The first W8 publish, with the next key already listed: k0 is shared, so no refusal.
    publishStatic(output("b", 2) { signW8(it, k0, k1) }, site, "${k0.publicHex}\n${k1.publicHex}")
    assertNull(keyChangeProblem(File(site, "bundles"), Json.parseToJsonElement(File(site, "bundles/index.json").readText()).jsonObject,
      signW8(manifest(3), k1).encodeJson(), "${k0.publicHex}\n${k1.publicHex}"))
  }

  @Test
  fun theNextKeyIsWrittenOwnerOnlyAndNeverReplaced() {
    val dir = File(tmp, "next-key")
    val publicHex = writeNewSigningKey(dir)
    val priv = File(dir, PRIVATE_KEY_FILE).toPath()
    assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(priv)))
    assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.toPath())))
    assertEquals(publicHex, File(dir, PUBLIC_KEY_FILE).readText())
    assertEquals(publicHex, publicKeyOfSeed(Files.readString(priv).decodeHex()))
    val before = Files.readString(priv)
    assertFailsWith<IllegalArgumentException> { writeNewSigningKey(dir) }
    assertEquals(before, Files.readString(priv))
  }
}
