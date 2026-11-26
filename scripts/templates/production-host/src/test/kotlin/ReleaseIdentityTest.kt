package @@PACKAGE@@

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8

/** NF5 H0: the module digest the last good bundle's record carries (ReleaseIdentity.kt). */
class ReleaseIdentityTest {
  private val stdlib = "./kotlin-kotlin-stdlib.js" to "stdlib bytes".encodeUtf8().sha256()
  private val main = "./main.js" to "main bytes".encodeUtf8().sha256()

  @Test
  fun theDigestIsSha256OverIdAndSha256LinesInIdOrder() {
    // The format, written out independently: every host and every later reader of the record
    // (NF5's cached first frame) must agree on it byte for byte.
    val text = "./kotlin-kotlin-stdlib.js\n${stdlib.second.hex()}\n./main.js\n${main.second.hex()}\n"
    val expected = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
      .joinToString("") { "%02x".format(it) }
    assertEquals(expected, moduleDigest(mapOf(main, stdlib)))
  }

  @Test
  fun theManifestsOrderDoesNotMatter() {
    assertEquals(moduleDigest(linkedMapOf(stdlib, main)), moduleDigest(linkedMapOf(main, stdlib)))
  }

  @Test
  fun anyModuleChangeGivesAnotherDigest() {
    val before = moduleDigest(mapOf(stdlib, main))
    assertNotEquals(before, moduleDigest(mapOf(stdlib, main.first to "other main bytes".encodeUtf8().sha256())))
    assertNotEquals(before, moduleDigest(mapOf(stdlib)))
    assertNotEquals(before, moduleDigest(mapOf(stdlib, main, "./extra.js" to ByteString.EMPTY.sha256())))
    assertNotEquals(before, moduleDigest(mapOf(stdlib, "./renamed.js" to main.second)))
  }

  @Test
  fun noModulesIsTheDigestOfNothing() {
    assertEquals(ByteString.EMPTY.sha256().hex(), moduleDigest(emptyMap()))
  }
}
