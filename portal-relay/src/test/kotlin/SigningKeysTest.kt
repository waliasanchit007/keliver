import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.EdECPrivateKeySpec
import java.security.spec.NamedParameterSpec
import java.security.spec.X509EncodedKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * U27: the signing key is created owner-only, an existing one is never changed,
 * and half an identity is reported, never completed by generating (U29).
 *
 * Everything here is in a fresh temporary directory; no store is resolved and no
 * key material is printed — assertions compare bytes in memory.
 *
 * What these cannot cover, and scripts/keliver-key-permissions-check.sh does:
 * the umask (a JVM cannot set its own), a filesystem that ignores modes, and a
 * read attempt by another local user.
 */
class SigningKeysTest {
  private fun store(): File =
    Files.createTempDirectory("signing-keys").toFile().also { it.deleteOnExit() }

  private fun mode(f: File) = PosixFilePermissions.toString(Files.getPosixFilePermissions(f.toPath()))
  private fun chmod(f: File, m: String) = Files.setPosixFilePermissions(f.toPath(), PosixFilePermissions.fromString(m))

  @Test
  fun aFreshKeyIsOwnerOnlyAndNothingIsLeftBehind() {
    val keys = File(store(), "keys")
    assertTrue(ensureSigningKeys(keys))
    assertEquals("rw-------", mode(File(keys, PRIVATE_KEY_FILE)))
    assertEquals("rwx------", mode(keys))
    assertTrue(mode(File(keys, PUBLIC_KEY_FILE)).let { it[4] != 'w' && it[7] != 'w' }, "public key not writable by others")
    assertEquals(KeyProtection.OwnerOnly, keyProtection(keys))
    assertEquals(setOf(PRIVATE_KEY_FILE, PUBLIC_KEY_FILE), keys.list()!!.toSet(), "no staging directory left")
  }

  @Test
  fun theFormatIsUnchangedAndTheHalvesAreOnePair() {
    val keys = File(store(), "keys")
    ensureSigningKeys(keys)
    val priv = File(keys, PRIVATE_KEY_FILE).readText()
    val pub = File(keys, PUBLIC_KEY_FILE).readText()
    // Raw 32-byte keys as lowercase hex, no newline: what Zipline and every
    // reader of these files (`.text.trim()`, decodeHex) already expect.
    assertTrue(Regex("[0-9a-f]{64}").matches(priv), "private key format")
    assertTrue(Regex("[0-9a-f]{64}").matches(pub), "public key format")

    val message = "u27".toByteArray()
    val signer = Signature.getInstance("Ed25519").apply {
      initSign(
        KeyFactory.getInstance("Ed25519")
          .generatePrivate(EdECPrivateKeySpec(NamedParameterSpec.ED25519, unhex(priv))),
      )
      update(message)
    }
    val signature = signer.sign()
    val verifier = Signature.getInstance("Ed25519").apply {
      initVerify(
        KeyFactory.getInstance("Ed25519")
          .generatePublic(X509EncodedKeySpec(unhex("302a300506032b6570032100") + unhex(pub))),
      )
      update(message)
    }
    assertTrue(verifier.verify(signature), "the public key verifies what the private key signs")
  }

  @Test
  fun aRestartKeepsTheIdentity() {
    val keys = File(store(), "keys")
    ensureSigningKeys(keys)
    val priv = File(keys, PRIVATE_KEY_FILE).readBytes()
    val pub = File(keys, PUBLIC_KEY_FILE).readBytes()
    assertFalse(ensureSigningKeys(keys), "nothing generated the second time")
    assertContentEquals(priv, File(keys, PRIVATE_KEY_FILE).readBytes())
    assertContentEquals(pub, File(keys, PUBLIC_KEY_FILE).readBytes())
    assertEquals("rw-------", mode(File(keys, PRIVATE_KEY_FILE)))
  }

  @Test
  fun anExposedExistingKeyIsReportedAndNeverChanged() {
    // The shape every store made before U27 has: 0644 in 0755.
    val keys = File(store(), "keys").apply { mkdirs() }
    File(keys, PRIVATE_KEY_FILE).writeText("11".repeat(32))
    File(keys, PUBLIC_KEY_FILE).writeText("22".repeat(32))
    chmod(File(keys, PRIVATE_KEY_FILE), "rw-r--r--")
    chmod(keys, "rwxr-xr-x")

    assertFalse(ensureSigningKeys(keys))
    val p = keyProtection(keys)
    assertIs<KeyProtection.Exposed>(p)
    assertTrue("rw-r--r--" in p.detail && "read" in p.detail, p.detail)
    assertEquals("rw-r--r--", mode(File(keys, PRIVATE_KEY_FILE)), "the mode was not changed")
    assertEquals("rwxr-xr-x", mode(keys), "the directory was not changed")
    assertEquals("11".repeat(32), File(keys, PRIVATE_KEY_FILE).readText(), "the key was not replaced")
  }

  @Test
  fun aKeysDirectoryOthersCanWriteIsExposedEvenWithAnOwnerOnlyKey() {
    val keys = File(store(), "keys")
    ensureSigningKeys(keys)
    chmod(keys, "rwxrwxr-x")
    val p = keyProtection(keys)
    assertIs<KeyProtection.Exposed>(p)
    assertTrue("replace" in p.detail, p.detail)
  }

  @Test
  fun aTightenedKeyIsOwnerOnlyAgainWithTheSameIdentity() {
    val keys = File(store(), "keys").apply { mkdirs() }
    File(keys, PRIVATE_KEY_FILE).writeText("33".repeat(32))
    File(keys, PUBLIC_KEY_FILE).writeText("44".repeat(32))
    chmod(File(keys, PRIVATE_KEY_FILE), "rw-r--r--")
    // What tightenCommands prints, done by hand.
    chmod(keys, "rwx------")
    chmod(File(keys, PRIVATE_KEY_FILE), "rw-------")
    assertEquals(KeyProtection.OwnerOnly, keyProtection(keys))
    assertFalse(ensureSigningKeys(keys))
    assertEquals("33".repeat(32), File(keys, PRIVATE_KEY_FILE).readText())
  }

  @Test
  fun halfAnIdentityIsReportedAndLeftAsItWas() {
    for (present in listOf(PRIVATE_KEY_FILE, PUBLIC_KEY_FILE)) {
      val keys = File(store(), "keys").apply { mkdirs() }
      File(keys, present).writeText("55".repeat(32))
      assertFalse(ensureSigningKeys(keys), "nothing generated beside $present")
      assertEquals(listOf(present), keys.list()!!.toList(), "nothing was generated beside $present")
      assertEquals("55".repeat(32), File(keys, present).readText(), "$present was not replaced")
      val p = keyProtection(keys)
      assertIs<KeyProtection.Incomplete>(p)
      assertTrue("Nothing was generated" in p.detail, p.detail)
    }
  }

  @Test
  fun noIdentityAtAllIsNotThisChecksBusiness() {
    val keys = File(store(), "keys").apply { mkdirs() }
    assertEquals(null, keyProtection(keys))
  }

  @Test
  fun aWorldWritableStoreDirectoryIsExposedButAGroupWritableOneIsNot() {
    val root = store()
    val keys = File(root, "keys")
    ensureSigningKeys(keys)
    chmod(root, "rwxrwxr-x") // a user-private-group umask (002)
    assertEquals(KeyProtection.OwnerOnly, keyProtection(keys))
    chmod(root, "rwxrwxrwx")
    val p = keyProtection(keys)
    assertIs<KeyProtection.Exposed>(p)
    assertTrue("store directory" in p.detail && p.chmodFixes, p.detail)
    chmod(root, "rwx------")
  }

  @Test
  fun aPublicKeyOthersCanWriteIsExposed() {
    val keys = File(store(), "keys")
    ensureSigningKeys(keys)
    chmod(File(keys, PUBLIC_KEY_FILE), "rw-rw-rw-")
    val p = keyProtection(keys)
    assertIs<KeyProtection.Exposed>(p)
    assertTrue(PUBLIC_KEY_FILE in p.detail, p.detail)
  }

  @Test
  fun thePrintedCommandsRunAsPrintedOnAPathWithAQuote() {
    val root = File(store(), "o'neil's store")
    val keys = File(root, "keys").apply { mkdirs() }
    File(keys, PRIVATE_KEY_FILE).writeText("77".repeat(32))
    File(keys, PUBLIC_KEY_FILE).writeText("88".repeat(32))
    chmod(File(keys, PRIVATE_KEY_FILE), "rw-r--r--")
    chmod(keys, "rwxr-xr-x")
    assertIs<KeyProtection.Exposed>(keyProtection(keys))
    val p = ProcessBuilder("/bin/sh", "-c", tightenCommands(keys)).redirectErrorStream(true).start()
    val out = p.inputStream.bufferedReader().readText()
    assertEquals(0, p.waitFor(), out)
    assertEquals(KeyProtection.OwnerOnly, keyProtection(keys))
    assertEquals("77".repeat(32), File(keys, PRIVATE_KEY_FILE).readText(), "modes only; the key is the same")
  }

  @Test
  fun anInterruptedGenerationsStagingIsRemoved() {
    val keys = File(store(), "keys").apply { mkdirs() }
    File(keys, ".keygen-123/$PRIVATE_KEY_FILE").apply { parentFile.mkdirs(); writeText("66".repeat(32)) }
    assertTrue(ensureSigningKeys(keys))
    assertEquals(setOf(PRIVATE_KEY_FILE, PUBLIC_KEY_FILE), keys.list()!!.toSet())
    assertFalse(File(keys, PRIVATE_KEY_FILE).readText() == "66".repeat(32), "the staged key was not adopted")
  }

  @Test
  fun aFreshKeyIsNotPutWhereOthersCouldReplaceIt() {
    val keys = File(store(), "keys").apply { mkdirs() }
    chmod(keys, "rwxrwxrwx")
    val e = assertFailsWith<SigningKeyException> { ensureSigningKeys(keys) }
    assertTrue("rwxrwxrwx" in e.message!!, e.message)
    assertEquals(emptyList(), keys.list()!!.toList(), "no key was created")
  }

  @Test
  fun aNoownersVolumeIsFoundByDeviceThenByTheLongestMountPoint() {
    // The shape of macOS `mount` output, including the line MEASURED for a FAT
    // image attached with hdiutil.
    val listing = """
      /dev/disk3s1s1 on / (apfs, sealed, local, read-only, journaled)
      /dev/disk3s5 on /System/Volumes/Data (apfs, local, journaled, nobrowse, protect)
      /dev/disk6s1 on /private/tmp/run/fat (msdos, local, nodev, nosuid, noowners, noatime, nobrowse, fskit, mounted by someone)
      /dev/disk7s1 on /Volumes/My Drive (apfs, local, nodev, nosuid, journaled, noowners)
    """.trimIndent()
    assertEquals("/private/tmp/run/fat", noownersMountFor(listing, null, "/private/tmp/run/fat/store/keys"))
    assertEquals("/private/tmp/run/fat", noownersMountFor(listing, null, "/private/tmp/run/fat"))
    assertEquals("/Volumes/My Drive", noownersMountFor(listing, null, "/Volumes/My Drive/app/keys"))
    assertEquals(null, noownersMountFor(listing, null, "/private/tmp/run/fatter/keys"), "a prefix of a name is not a mount")
    assertEquals(null, noownersMountFor(listing, null, "/Users/me/.keliver-portal/apps/a/keys"))
    assertEquals(null, noownersMountFor(listing, null, "/System/Volumes/Data/Users/me/keys"))
    // By device: a firmlinked /Users path is on the Data volume, whatever its prefix says.
    val dataNoowners = listing.replace("journaled, nobrowse, protect", "journaled, nobrowse, noowners")
    assertEquals("/System/Volumes/Data", noownersMountFor(dataNoowners, "/dev/disk3s5", "/Users/me/.keliver-portal/apps/a/keys/ed25519.priv"))
    assertEquals(null, noownersMountFor(listing, "/dev/disk3s5", "/Users/me/.keliver-portal/apps/a/keys/ed25519.priv"))
    assertEquals("/private/tmp/run/fat", noownersMountFor(listing, "/dev/disk6s1", "/private/tmp/run/fat/k"))
  }

  private fun unhex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
}
