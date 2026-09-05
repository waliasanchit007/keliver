import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** P3-12: the rebuild state machine — debounce/coalesce, stale-reject, last-known-good. */
class PreviewBuilderTest {

  private class FakeRunner(
    private val results: MutableList<String?>, // per-build outcome: null = ok
    private val onBuild: (() -> Unit)? = null,
  ) : PreviewBuilder.Runner {
    val builds = AtomicInteger(0)
    val promotes = AtomicInteger(0)
    override fun build(cancelled: () -> Boolean): String? {
      builds.incrementAndGet()
      onBuild?.invoke()
      return if (results.isEmpty()) null else results.removeAt(0)
    }
    override fun promote() { promotes.incrementAndGet() }
  }

  private fun await(cond: () -> Boolean, ms: Long = 3000) {
    val end = System.currentTimeMillis() + ms
    while (!cond() && System.currentTimeMillis() < end) Thread.sleep(20)
    assertTrue(cond(), "condition not met within ${ms}ms")
  }

  /**
   * An app with no editor of its own (empty previewBuildTask) must not attempt
   * a build. Consumers used to inherit this repo's `:web-spike:` default and
   * got a permanent red "preview build failed" chip on their very first run,
   * against a Gradle project that does not exist in their build.
   */
  @Test fun disabledBuilderNeverBuildsAndStaysIdle() {
    val runner = FakeRunner(mutableListOf(null))
    val b = PreviewBuilder(runner, debounceMs = 50, enabled = false)
    repeat(3) { b.trigger() }
    Thread.sleep(250)
    assertEquals(0, runner.builds.get(), "disabled builder must not run gradle")
    assertEquals(0, runner.promotes.get())
    assertEquals("idle", b.status.state, "disabled must not look like a failure")
    assertTrue(b.statusJson().contains("\"enabled\":false"))
  }

  @Test fun rapidTriggersCoalesceIntoOneBuild() {
    val runner = FakeRunner(mutableListOf(null))
    val b = PreviewBuilder(runner, debounceMs = 100)
    repeat(5) { b.trigger(); Thread.sleep(10) } // all within the debounce window
    await({ b.status.state == "ok" })
    assertEquals(1, runner.builds.get(), "5 rapid triggers -> 1 build")
    assertEquals(1, runner.promotes.get())
    assertTrue(b.promotedId > 0)
  }

  @Test fun failureKeepsLastKnownGoodPromotion() {
    val runner = FakeRunner(mutableListOf(null, "e: boom"))
    val b = PreviewBuilder(runner, debounceMs = 50)
    b.trigger()
    await({ b.status.state == "ok" })
    val goodId = b.promotedId
    b.trigger()
    await({ b.status.state == "failed" })
    assertEquals(goodId, b.promotedId, "failed build must not replace the promoted id")
    assertTrue(b.status.error!!.contains("boom"))
    assertEquals(1, runner.promotes.get(), "no promote on failure")
  }

  @Test fun staleBuildResultIsRejected() {
    // Build 1 blocks until we let it finish; build 2 is triggered meanwhile.
    val gate = CountDownLatch(1)
    val entered = CountDownLatch(1)
    val runner = object : PreviewBuilder.Runner {
      val builds = AtomicInteger(0)
      val promotes = AtomicInteger(0)
      override fun build(cancelled: () -> Boolean): String? {
        val n = builds.incrementAndGet()
        if (n == 1) { entered.countDown(); gate.await(3, TimeUnit.SECONDS) }
        return null
      }
      override fun promote() { promotes.incrementAndGet() }
    }
    val b = PreviewBuilder(runner, debounceMs = 30)
    b.trigger()
    assertTrue(entered.await(2, TimeUnit.SECONDS))
    b.trigger()           // supersedes build 1 while it runs
    gate.countDown()      // build 1 finishes AFTER being superseded -> stale, rejected
    await({ b.status.state == "ok" && runner.builds.get() == 2 })
    assertEquals(1, runner.promotes.get(), "only the latest build promotes")
    assertEquals(2, b.promotedId)
  }
}
