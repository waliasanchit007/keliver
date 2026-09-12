import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The app lock, and specifically the takeover of a lock whose holder is gone.
 *
 * A lock that blocks relay startup is itself a way to wedge the portal, so a
 * dead holder's lock has to be reclaimable — but the naive form (see the pid is
 * dead, then delete the directory) can delete a lock a DIFFERENT contender
 * acquired in between, which is the two-writers failure the lock exists to
 * prevent. Every case here is a CONSTRUCTED interleaving, not a timing loop:
 * the state that a racing contender would have left is written directly.
 */
class StoreLockTest {
  private fun app(): File = Files.createTempDirectory("lockapp").toFile().also { it.deleteOnExit() }
  private fun lockOf(app: File) = File(app, ".gradle/keliver-store.lock")

  /** A pid that is certainly not running: a child that has already been reaped. */
  private fun deadPid(): Long {
    val p = ProcessBuilder("/usr/bin/true").start()
    p.waitFor()
    return p.pid()
  }

  private fun seedLock(app: File, contents: Map<String, String>): File {
    val lock = lockOf(app)
    lock.mkdirs()
    contents.forEach { (name, text) -> File(lock, name).writeText(text) }
    return lock
  }

  /**
   * Run withStoreLock with a short wait. Returns null when it refused, and
   * records WHY in [refusal] — "busy" or "unavailable: <reason>". Both are
   * Nothing-returning in production (the relay exits), so the test turns them
   * into an exception.
   */
  private var refusal: String? = null

  private fun <T> attempt(
    app: File,
    waitMillis: Long = 400,
    afterFailedCreate: () -> Unit = {},
    block: () -> T,
  ): T? {
    refusal = null
    return try {
      withStoreLock(
        app,
        waitMillis = waitMillis,
        onBusy = { refusal = "busy"; throw Refused() },
        onUnavailable = { _, why -> refusal = "unavailable: $why"; throw Refused() },
        afterFailedCreate = afterFailedCreate,
      ) { block() }
    } catch (_: Refused) {
      null
    }
  }

  private fun <T> withStoreLockAttempt(
    app: File,
    waitMillis: Long = 400,
    afterAcquire: () -> Unit,
    block: () -> T,
  ): T? {
    refusal = null
    return try {
      withStoreLock(
        app,
        waitMillis = waitMillis,
        onBusy = { refusal = "busy"; throw Refused() },
        onUnavailable = { _, why -> refusal = "unavailable: $why"; throw Refused() },
        afterAcquire = afterAcquire,
      ) { block() }
    } catch (_: Refused) {
      null
    }
  }

  private class Refused : RuntimeException()

  @Test
  fun theLockIsExclusiveWhileItIsHeld() {
    val a = app()
    val inside = CountDownLatch(1)
    val release = CountDownLatch(1)
    val second = AtomicBoolean(false)
    val holder = Thread {
      withStoreLock(a, onBusy = { error("holder should not be busy") }, onUnavailable = { _, w -> error(w) }) {
        inside.countDown()
        release.await(10, TimeUnit.SECONDS)
      }
    }
    holder.start()
    assertTrue(inside.await(10, TimeUnit.SECONDS))
    assertEquals(null, attempt(a) { second.set(true) }, "a second holder must be refused")
    assertFalse(second.get(), "the block must not run without the lock")
    release.countDown()
    holder.join(10_000)
    // and the lock is gone afterwards
    assertFalse(lockOf(a).exists(), "the lock must be released")
  }

  @Test
  fun aLockWhoseHolderIsGoneIsTakenOver() {
    val a = app()
    seedLock(a, mapOf("pid" to "${deadPid()}\n"))
    val ran = attempt(a) { "ok" }
    assertEquals("ok", ran, "a lock held by a dead process must be reclaimable")
    assertFalse(lockOf(a).exists(), "the reclaimed lock must be released again")
  }

  @Test
  fun aLockWhoseHolderIsAliveIsNotTakenOver() {
    val a = app()
    seedLock(a, mapOf("pid" to "${ProcessHandle.current().pid()}\n"))
    assertEquals(null, attempt(a) { "ok" }, "a live holder's lock must not be stolen")
    assertTrue(lockOf(a).exists(), "and it must still be there")
  }

  @Test
  fun aLockWithNoReadablePidIsWaitedOnNotStolen() {
    val a = app()
    seedLock(a, emptyMap())
    assertEquals(null, attempt(a) { "ok" }, "an unknown holder means wait, not steal")
    assertTrue(lockOf(a).exists())

    val b = app()
    seedLock(b, mapOf("pid" to "not-a-pid\n"))
    assertEquals(null, attempt(b) { "ok" }, "an unparseable pid means wait, not steal")
    assertTrue(lockOf(b).exists())
  }

  @Test
  fun aTakeoverAlreadyClaimedByAnotherContenderIsNotCompletedTwice() {
    // THE INTERLEAVING THAT MATTERS, constructed rather than raced: contender B
    // has already claimed the takeover — it renamed `pid` aside and has not yet
    // recreated the directory. A third contender arriving now must NOT delete
    // the directory, because B is about to own it.
    val a = app()
    val lock = seedLock(a, mapOf("pid.stale.999999" to "${deadPid()}\n"))
    assertEquals(null, attempt(a) { "ok" }, "a claimed takeover must not be completed by someone else")
    assertTrue(lock.exists(), "the directory B claimed must still be there")
    assertTrue(File(lock, "pid.stale.999999").isFile, "B's claim must be intact")
  }

  @Test
  fun aStaleMarkerReplacedByALiveHoldersIsPutBack() {
    // The ABA shape: the pid inspected was dead, but by the time the claim is
    // made the marker belongs to a live holder. The claim is content-checked,
    // so it is returned rather than acted on. Constructed by seeding a live
    // pid — the claim attempt must leave the marker exactly where it was.
    val a = app()
    val live = ProcessHandle.current().pid()
    val lock = seedLock(a, mapOf("pid" to "$live\n"))
    assertEquals(null, attempt(a) { "ok" })
    assertTrue(File(lock, "pid").isFile, "the live holder's marker must still be in place")
    assertEquals("$live", File(lock, "pid").readText().trim())
    assertTrue(lock.listFiles()!!.none { it.name.startsWith("pid.stale") }, "no claim should be left behind")
  }

  @Test
  fun aContenderThatArrivesBetweenTheLivenessCheckAndTheClaimIsNotClobbered() {
    // The window the naive form gets wrong: decide the holder is dead, and by
    // the time you delete the directory somebody else owns it. Driven through
    // the seam in claimStaleLock, so the interleaving is exact rather than
    // hoped for.
    val a = app()
    val live = ProcessHandle.current().pid()
    val lock = seedLock(a, mapOf("pid" to "${deadPid()}\n"))

    val claimed = claimStaleLock(lock) {
      // Another contender completes its own takeover right here.
      File(lock, "pid").writeText("$live\n")
    }

    assertFalse(claimed, "the takeover must not complete once the marker has changed hands")
    assertTrue(lock.exists(), "the other contender's lock must not be deleted")
    assertEquals("$live", File(lock, "pid").readText().trim(), "its marker must be intact")
    assertTrue(lock.listFiles()!!.none { it.name.startsWith("pid.stale") }, "no claim left behind")
  }

  @Test
  fun aClaimOnAGenuinelyStaleLockSucceedsOnce() {
    val a = app()
    val lock = seedLock(a, mapOf("pid" to "${deadPid()}\n"))
    assertTrue(claimStaleLock(lock), "a dead holder's lock must be reclaimable")
    assertFalse(lock.exists(), "a completed claim clears the directory for a retry")
    assertFalse(claimStaleLock(lock), "and a second claim on nothing must not succeed")
  }

  @Test
  fun theHolderRecordsItsOwnPid() {
    val a = app()
    var recorded: String? = null
    withStoreLock(a, onBusy = { error("not busy") }, onUnavailable = { _, w -> error(w) }) {
      recorded = File(lockOf(a), "pid").readText().trim()
    }
    assertEquals("${ProcessHandle.current().pid()}", recorded)
  }

  @Test
  fun anAppTreeWhereTheLockCannotBeCreatedRefusesRatherThanRunningUnlocked() {
    // .gradle is a regular file, so the lock directory cannot exist. This used
    // to run the block anyway and announce it — the single-writer guarantee
    // waived at exactly the moment the filesystem was behaving unusually.
    val a = app()
    File(a, ".gradle").writeText("not a directory\n")
    var ran = false
    assertEquals(null, attempt(a) { ran = true }, "it must refuse, not run unlocked")
    assertFalse(ran, "the block must not have executed")
    assertTrue(refusal!!.startsWith("unavailable"), "wrong refusal: $refusal")
  }

  @Test
  fun aLockDirectoryThatCannotBeCreatedAtAllRefuses() {
    // .gradle exists and is a directory, but is not writable, so `mkdir` of the
    // lock fails AND the lock is absent — the shape that used to be read as
    // "cannot create it, so proceed unlocked".
    val a = app()
    val gradle = File(a, ".gradle")
    gradle.mkdirs()
    assertTrue(gradle.setWritable(false, false), "could not make the directory read-only")
    try {
      var ran = false
      assertEquals(null, attempt(a, waitMillis = 200) { ran = true }, "it must refuse, not run unlocked")
      assertFalse(ran, "the block must not have executed")
      assertTrue(refusal!!.startsWith("unavailable"), "wrong refusal: $refusal")
      assertFalse(lockOf(a).exists())
    } finally {
      gradle.setWritable(true, true)
    }
  }

  @Test
  fun aLockThatVanishesBetweenTheFailedCreateAndTheExistsCheckIsRetried() {
    // The reported interleaving: `mkdir` fails because the holder still has the
    // lock, and the holder releases it before the exists() check. A missing
    // directory there is a reason to try again, not permission to proceed
    // unlocked. Driven through a seam so it is exact, not raced.
    val a = app()
    val lock = seedLock(a, mapOf("pid" to "${ProcessHandle.current().pid()}\n"))
    var released = false
    var ran = false
    val out = attempt(
      a,
      waitMillis = 5_000,
      afterFailedCreate = {
        if (!released) {
          released = true
          File(lock, "pid").delete()
          lock.delete()
        }
      },
    ) { ran = true; "ok" }
    assertTrue(released, "the seam must have fired — otherwise this proves nothing")
    assertEquals("ok", out, "it must acquire on the retry, refusal was: $refusal")
    assertTrue(ran)
    assertFalse(lock.exists(), "and release it again")
  }

  @Test
  fun cleanupDoesNotRemoveALaterHoldersLock() {
    // A hard kill leaves the marker behind; a later holder takes the lock over.
    // This process's own finally/shutdown-hook cleanup must not then delete it.
    val a = app()
    var observed: String? = null
    withStoreLock(a, onBusy = { error("not busy") }, onUnavailable = { _, w -> error(w) }) {
      // Somebody else's takeover completes while this block is running.
      File(lockOf(a), "pid").writeText("999999\n")
      observed = File(lockOf(a), "pid").readText().trim()
    }
    assertEquals("999999", observed)
    assertTrue(lockOf(a).exists(), "the later holder's lock must survive this process's cleanup")
    assertEquals("999999", File(lockOf(a), "pid").readText().trim(), "and keep its marker")
  }

  @Test
  fun anUnwritableMarkerIsTreatedAsAFailedAcquisition() {
    // Holding a lock nobody can identify is worse than not holding one: no
    // contender can take it over and this process cannot safely release it.
    //
    // The marker write only happens AFTER this process owns the directory, so
    // it cannot be made to fail from outside — an earlier version of this test
    // pre-created the lock, which meant `mkdir` never succeeded and the run
    // ended at onBusy instead, passing on the wrong branch. The seam puts the
    // obstacle in place at the one moment it is reachable.
    val a = app()
    var ran = false
    val out = withStoreLockAttempt(a, afterAcquire = { File(lockOf(a), "pid").mkdirs() }) { ran = true }
    assertEquals(null, out)
    assertFalse(ran, "the block must not run without an identifiable holder")
    assertTrue(refusal!!.startsWith("unavailable"), "wrong refusal branch: $refusal")
    assertTrue(refusal!!.contains("holder marker"), "wrong reason: $refusal")
  }
}
