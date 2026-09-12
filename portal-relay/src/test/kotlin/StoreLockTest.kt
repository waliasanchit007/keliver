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

  /** Run withStoreLock with a short wait; returns null when it reported busy. */
  private fun <T> attempt(app: File, waitMillis: Long = 400, block: () -> T): T? {
    var busy = false
    val out = try {
      withStoreLock(app, waitMillis = waitMillis, onBusy = { busy = true; throw Busy() }) { block() }
    } catch (_: Busy) {
      null
    }
    return if (busy) null else out
  }

  private class Busy : RuntimeException()

  @Test
  fun theLockIsExclusiveWhileItIsHeld() {
    val a = app()
    val inside = CountDownLatch(1)
    val release = CountDownLatch(1)
    val second = AtomicBoolean(false)
    val holder = Thread {
      withStoreLock(a, onBusy = { error("holder should not be busy") }) {
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
    withStoreLock(a, onBusy = { error("not busy") }) {
      recorded = File(lockOf(a), "pid").readText().trim()
    }
    assertEquals("${ProcessHandle.current().pid()}", recorded)
  }

  @Test
  fun anUnwritableAppTreeRunsUnlockedRatherThanRefusingToStart() {
    // .gradle cannot be created because it is a regular file. Refusing to start
    // there would be worse than an unsynchronised startup nothing contends for.
    val a = app()
    File(a, ".gradle").writeText("not a directory\n")
    val notices = mutableListOf<String>()
    val ran = withStoreLock(a, onUnlocked = notices::add, onBusy = { error("not busy") }) { "ok" }
    assertEquals("ok", ran)
    assertTrue(notices.any { "without the store lock" in it }, "the unlocked start must be announced: $notices")
  }
}
