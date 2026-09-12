import java.io.File
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression: ownership acquisition must be atomic.
 *
 * `claimStoreFor` used to do `exists()` then `writeText()`. Two relays starting
 * at the same moment against an unowned store both saw "no owner" and both
 * wrote — so both proceeded, and the store they then shared is the very defect
 * the owner marker exists to prevent (U17).
 */
class StoreClaimRaceTest {
  private fun tmp(name: String): File =
    java.nio.file.Files.createTempDirectory(name).toFile().also { it.deleteOnExit() }

  @Test
  fun onlyOneRepoWinsASimultaneousFirstClaim() {
    repeat(20) { attempt ->
      val store = tmp("race-store-$attempt")
      val n = 16
      val barrier = CyclicBarrier(n)
      val pool = Executors.newFixedThreadPool(n)
      val winners = AtomicInteger()
      val losers = AtomicInteger()
      val repos = (0 until n).map { tmp("race-repo-$attempt-$it") }
      try {
        val futures = repos.map { repo ->
          pool.submit {
            barrier.await(10, TimeUnit.SECONDS)
            runCatching { claimStoreFor(store, repo) }
              .onSuccess { winners.incrementAndGet() }
              .onFailure { losers.incrementAndGet() }
          }
        }
        futures.forEach { it.get(20, TimeUnit.SECONDS) }
      } finally {
        pool.shutdownNow()
      }
      assertEquals(
        1, winners.get(),
        "attempt $attempt: exactly one repo may win an unowned store, got ${winners.get()}",
      )
      assertEquals(n - 1, losers.get(), "attempt $attempt: every other repo must be refused")

      // and the marker names exactly one of them, intact
      val owner = File(store, "owner").readText().trim()
      assertTrue(repos.any { it.canonicalFile.path == owner }, "owner marker is not one of the claimants: $owner")
    }
  }

  @Test
  fun theLoserCannotOverwriteTheWinnersMarker() {
    val store = tmp("race-store-loser")
    val winner = tmp("race-winner")
    val loser = tmp("race-loser")
    claimStoreFor(store, winner)
    val before = File(store, "owner").readText()
    repeat(5) { runCatching { claimStoreFor(store, loser) } }
    assertEquals(before, File(store, "owner").readText(), "a refused claimant must not alter the marker")
    assertEquals(winner.canonicalFile.path, File(store, "owner").readText().trim())
  }

  @Test
  fun theLegitimateOwnerCanReclaimAcrossRestarts() {
    val store = tmp("race-store-restart")
    val owner = tmp("race-owner")
    repeat(5) { claimStoreFor(store, owner) }   // 5 "restarts"
    assertEquals(owner.canonicalFile.path, File(store, "owner").readText().trim())
  }
}
