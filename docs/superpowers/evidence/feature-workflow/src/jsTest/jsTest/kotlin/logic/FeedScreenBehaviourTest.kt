package feed.logic

import dev.keliver.http.HostHttpProvider
import dev.keliver.http.HttpRequest
import dev.keliver.http.HttpResponse
import dev.keliver.material.testing.ButtonValue
import dev.keliver.material.testing.KeliverMaterialTester
import dev.keliver.material.testing.ListItemValue
import dev.keliver.material.testing.StyledTextValue
import dev.keliver.testing.flatten
import feed.screens.FeedScreen
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest

/**
 * Drives the REAL presenter through the REAL screen and asserts on rendered
 * widget values. Unlike FeedRepositoryTest (which exercises the repository in
 * isolation) this covers presenter -> screen -> action wiring: a repository
 * test would pass even if retry() were never reachable from the button.
 *
 * Responses are deterministic fixtures, gated so the loading interval is
 * observable rather than instantaneous.
 */
private class GatedProvider(private val steps: List<Pair<Int, String>>) : HostHttpProvider {
  var calls = 0
    private set
  private var gate = CompletableDeferred<Unit>()
  fun release() { gate.complete(Unit) }
  fun rearm() { gate = CompletableDeferred() }
  override suspend fun execute(request: HttpRequest): HttpResponse {
    gate.await()
    val (s, b) = steps[minOf(calls, steps.lastIndex)]
    calls += 1
    return HttpResponse(status = s, body = b)
  }
}

class FeedScreenBehaviourTest {
  private fun List<*>.status(): String? =
    filterIsInstance<StyledTextValue>().getOrNull(1)?.text
  private fun List<*>.rowTitles(): List<String?> =
    filterIsInstance<ListItemValue>().map { it.headline }
  private fun List<*>.retry(): ButtonValue? =
    filterIsInstance<ButtonValue>().firstOrNull { it.text == "Retry" }

  @Test
  fun loadingThenPopulated() = runTest {
    val provider = GatedProvider(listOf(200 to """[{"id":"1","title":"Alpha"},{"id":"2","title":"Beta"}]"""))
    val repo = FeedRepository(provider)

    KeliverMaterialTester {
      val loading = setContentAndSnapshot { FeedScreen(FeedPresenter(repo)) }.flatten().toList()
      assertEquals("Loading…", loading.status(), "loading state must be observable before the response")
      assertEquals(emptyList(), loading.rowTitles())

      provider.release()
      val populated = awaitSnapshot().flatten().toList()
      assertEquals("2 articles", populated.status())
      assertEquals(listOf("Alpha", "Beta"), populated.rowTitles())
    }
  }

  @Test
  fun loadingThenEmpty() = runTest {
    val provider = GatedProvider(listOf(200 to "[]"))
    val repo = FeedRepository(provider)
    KeliverMaterialTester {
      setContentAndSnapshot { FeedScreen(FeedPresenter(repo)) }
      provider.release()
      val empty = awaitSnapshot().flatten().toList()
      assertEquals("No articles yet", empty.status())
      assertEquals(emptyList(), empty.rowTitles())
    }
  }

  @Test
  fun errorThenRetryButtonRecoversThroughTheUi() = runTest {
    val provider = GatedProvider(
      listOf(500 to """{"message":"boom"}""", 200 to """[{"id":"9","title":"Recovered"}]"""),
    )
    val repo = FeedRepository(provider)

    KeliverMaterialTester {
      setContentAndSnapshot { FeedScreen(FeedPresenter(repo)) }
      provider.release()
      val errored = awaitSnapshot().flatten().toList()
      assertEquals("Couldn't load articles", errored.status())
      assertEquals(emptyList(), errored.rowTitles())

      // Tap the actual rendered button — not repo.load() again.
      val button = assertNotNull(errored.retry(), "error state must offer a Retry button")
      provider.rearm()
      assertNotNull(button.onClick).invoke()

      // Collect frames until recovery. The Loading frame is emitted when
      // LaunchedEffect restarts, which is asynchronous to the click, so assert
      // that it APPEARS in the sequence rather than that it is the next frame.
      // Keep the second response gated so the loading interval is observable
      // rather than coalesced away by an instant reply.
      val seen = mutableListOf<String?>()
      repeat(3) {
        val frame = runCatching { awaitSnapshot().flatten().toList() }.getOrNull()
        if (frame != null) seen += frame.status()
      }
      assertTrue(
        seen.contains("Loading…"),
        "tapping Retry must show a loading state while the request is in flight; frames were $seen",
      )

      provider.release()
      var recovered: List<Any?> = emptyList()
      repeat(4) {
        val frame = runCatching { awaitSnapshot().flatten().toList() }.getOrNull()
        if (frame != null && frame.status() == "1 articles") recovered = frame
      }
      assertEquals("1 articles", recovered.status(), "frames after release: $seen")
      assertEquals(listOf("Recovered"), recovered.rowTitles())
      assertEquals(2, provider.calls, "retry must have issued a second request")
    }
  }
}
