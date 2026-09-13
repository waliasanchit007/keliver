import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The U23 / U24 / U25.1 contract, at the level of the resolver and the claim.
 * The process-level versions of the same outcomes are
 * `scripts/keliver-store-identity-repro.sh` and
 * `scripts/keliver-store-recovery-check.sh`; these are the fast ones that run
 * on every build.
 *
 * Note the deliberate use of REAL directories and symlinks rather than only
 * `Files.createTempDirectory` paths. StoreContractTest used only the latter,
 * which is why it could not see U25.1 at all: a temp path's final component is
 * never a symlink, so the canonical and non-canonical basenames always matched.
 */
class StoreIdentityTest {
  private fun tmp(name: String): File =
    Files.createTempDirectory(name).toFile().also { it.deleteOnExit() }

  private fun <T> withUserHome(home: File, block: () -> T): T {
    val previous = System.getProperty("user.home")
    System.setProperty("user.home", home.absolutePath)
    try { return block() } finally { System.setProperty("user.home", previous) }
  }

  private fun app(parent: File, name: String): File =
    File(parent, name).also { it.mkdirs() }

  // --- the slug ---------------------------------------------------------------

  @Test
  fun theSlugIsAsciiCollapsedAndTrimmed() {
    assertEquals("myapp", storeSlug("MyApp"))
    assertEquals("my.app_1-2", storeSlug("my.app_1-2"))
    assertEquals("caf", storeSlug("café"))          // é is two UTF-8 bytes, both mapped, then collapsed
    assertEquals("a-b", storeSlug("a   b"))         // a run of unsafe bytes is ONE dash
    assertEquals("app", storeSlug(""))
    assertEquals("app", storeSlug("---"))
    // A store directory must never be named "." or "..".
    assertEquals("app", storeSlug("."))
    assertEquals("app", storeSlug(".."))
  }

  @Test
  fun theSlugIsStableAcrossAstralCharacters() {
    // An emoji is one code point but two UTF-16 units. The old character-wise
    // rule produced a different number of dashes in Kotlin than in Python.
    assertEquals("a-b", storeSlug("a😀b"))
  }

  // --- resolution -------------------------------------------------------------

  @Test
  fun aSymlinkAndTheRealPathResolveOneStore() {
    val home = tmp("home")
    val work = tmp("work")
    val real = app(work, "app-v2")
    val link = File(work, "current")
    Files.createSymbolicLink(link.toPath(), real.toPath())

    val viaReal = withUserHome(home) { PortalConfig().storeDir(real) }
    val viaLink = withUserHome(home) { PortalConfig().storeDir(link) }
    assertEquals(viaReal.path, viaLink.path)
    assertTrue(viaReal.name.startsWith("app-v2-"), "the store must be named for the REAL directory: ${viaReal.name}")
  }

  @Test
  fun thePointerIsHonouredSoAMovedAppFindsItsOwnStore() {
    val home = tmp("home")
    val work = tmp("work")
    val a = app(work, "before")
    val store = tmp("store")
    File(a, ".gradle").mkdirs()
    File(a, ".gradle/keliver-store-path").writeText(store.absolutePath + "\n")
    assertEquals(store.canonicalPath, withUserHome(home) { PortalConfig().storeDir(a) }.canonicalPath)
  }

  @Test
  fun anExplicitStoreStillOutranksThePointer() {
    val home = tmp("home")
    val a = app(tmp("work"), "app")
    val pointed = tmp("pointed")
    val explicit = tmp("explicit")
    File(a, ".gradle").mkdirs()
    File(a, ".gradle/keliver-store-path").writeText(pointed.absolutePath + "\n")
    assertEquals(
      explicit.canonicalPath,
      withUserHome(home) { PortalConfig(store = explicit.absolutePath).storeDir(a) }.canonicalPath,
    )
  }

  @Test
  fun anExistingStoreUnderAnOlderNameIsAdoptedNotReplaced() {
    val home = tmp("home")
    val a = app(tmp("work"), "app")
    val hash = appStoreHash(a)
    val older = File(home, ".keliver-portal/apps/legacy-slug-$hash").also { it.mkdirs() }

    val notices = mutableListOf<String>()
    val resolved = withUserHome(home) { PortalConfig().storeDir(a, notices::add) }
    assertEquals(older.canonicalPath, resolved.canonicalPath)
    assertTrue(notices.any { "existing store" in it }, "the adoption must be reported: $notices")
  }

  @Test
  fun twoStoresForOneAppAreRefusedRatherThanGuessedBetween() {
    val home = tmp("home")
    val a = app(tmp("work"), "app")
    val hash = appStoreHash(a)
    File(home, ".keliver-portal/apps/current-$hash").mkdirs()
    File(home, ".keliver-portal/apps/app-v2-$hash").mkdirs()

    val e = assertFailsWith<StoreOwnershipException> { withUserHome(home) { PortalConfig().storeDir(a) } }
    assertTrue("store split" in (e.message ?: ""), e.message ?: "")
    assertTrue("keliver-store-recover.sh" in (e.message ?: ""), "the refusal must name the recovery")
  }

  @Test
  fun aSplitIsRefusedEvenWhenOneOfTheTwoHasTheCanonicalName() {
    // The dangerous shape: the app's own canonical name is one of the two, so
    // any short-circuit on "the preferred directory exists" would pick it and
    // never mention the other identity.
    val home = tmp("home")
    val work = tmp("work")
    val app = File(work, "app-v2").also { it.mkdirs() }
    val hash = appStoreHash(app)
    File(home, ".keliver-portal/apps/app-v2-$hash").mkdirs()
    File(home, ".keliver-portal/apps/current-$hash").mkdirs()

    val e = assertFailsWith<StoreOwnershipException> { withUserHome(home) { PortalConfig().storeDir(app) } }
    assertTrue("app-v2-$hash" in (e.message ?: ""), e.message ?: "")
    assertTrue("current-$hash" in (e.message ?: ""), e.message ?: "")
  }

  @Test
  fun aBlankStoreSettingMeansNotSetAndNeverTheAppItself() {
    // `{"store": ""}` used to take the relative-path branch and resolve to
    // File(repoDir, "") — the source tree — while the shell mirror fell through
    // to the default.
    val home = tmp("home")
    val a = app(tmp("work"), "blank")
    val resolved = withUserHome(home) { PortalConfig(store = "").storeDir(a) }
    assertEquals(withUserHome(home) { PortalConfig().storeDir(a) }.canonicalPath, resolved.canonicalPath)
    assertTrue(
      !resolved.canonicalPath.startsWith(a.canonicalPath),
      "the store must never be the app's own tree: $resolved",
    )
  }

  @Test
  fun unrelatedAppsStillGetUnrelatedStores() {
    val home = tmp("home")
    val work = tmp("work")
    val a = app(work, "one")
    val b = app(work, "two")
    val sa = withUserHome(home) { PortalConfig().storeDir(a) }
    val sb = withUserHome(home) { PortalConfig().storeDir(b) }
    assertNotEquals(sa.path, sb.path)
  }

  // --- the claim --------------------------------------------------------------

  @Test
  fun aMovedAppIsRefusedAndPointedAtTheRecovery() {
    val work = tmp("work")
    val before = app(work, "before")
    val store = tmp("store")
    claimStoreFor(store, before)

    // The directory is renamed; the pointer travels with it.
    val after = File(work, "after")
    File(before, ".gradle").mkdirs()
    File(before, ".gradle/keliver-store-path").writeText(store.absolutePath + "\n")
    assertTrue(before.renameTo(after))

    val e = assertFailsWith<StoreOwnershipException> { claimStoreFor(store, after) }
    val m = e.message ?: ""
    assertTrue("this app has moved" in m, m)
    assertTrue("keliver-store-recover.sh" in m, "the refusal must name the recovery: $m")
    assertTrue("rm " in m, "a copy must be told how to become independent: $m")
    // and the marker is untouched
    assertEquals(before.canonicalFile.path, File(store, "owner").readText().trim())
  }

  @Test
  fun anAbsentOwnerPathDoesNotHandOverTheStore() {
    val work = tmp("work")
    val gone = File(work, "gone").also { it.mkdirs() }
    val store = tmp("store")
    claimStoreFor(store, gone)
    assertTrue(gone.delete())

    val other = app(work, "other")
    val e = assertFailsWith<StoreOwnershipException> { claimStoreFor(store, other) }
    val m = e.message ?: ""
    assertTrue("not on disk" in m, m)
    assertTrue("not proof" in m, "absence must not read as permission: $m")
    assertEquals(gone.canonicalFile.path, File(store, "owner").readText().trim())
  }

  @Test
  fun aLiveForeignOwnerStillGetsTheSharingRefusal() {
    val work = tmp("work")
    val a = app(work, "a")
    val b = app(work, "b")
    val store = tmp("store")
    claimStoreFor(store, a)
    val e = assertFailsWith<StoreOwnershipException> { claimStoreFor(store, b) }
    val m = e.message ?: ""
    assertTrue("store conflict" in m, m)
    assertTrue("two live apps" in m, m)
  }

  @Test
  fun theFingerprintNeverReadsThePrivateKey() {
    val store = tmp("store")
    File(store, "keys").mkdirs()
    File(store, "keys/ed25519.pub").writeText("ab".repeat(32))
    File(store, "keys/ed25519.priv").writeText("ff".repeat(32))
    val fp = publicKeyFingerprint(store)
    assertEquals(16, fp.length)
    assertTrue("ff".repeat(32) !in fp)
    // the same public key always fingerprints the same, and a different one does not
    assertEquals(fp, publicKeyFingerprint(store))
    File(store, "keys/ed25519.pub").writeText("cd".repeat(32))
    assertNotEquals(fp, publicKeyFingerprint(store))
  }
}
