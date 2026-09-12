package feed.logic

import dev.keliver.http.HostHttpProvider
import dev.keliver.http.HttpRequest
import dev.keliver.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Deterministic local HTTP responses. Each scenario is a fixed status+body,
 * so every state is exercisable repeatably. These are FIXTURES, not a live
 * service — nothing here says anything about a real API.
 */
private class FixedProvider(
  private val status: Int,
  private val body: String,
) : HostHttpProvider {
  var calls = 0
    private set
  override suspend fun execute(request: HttpRequest): HttpResponse {
    calls += 1
    return HttpResponse(status = status, body = body)
  }
}

private class SequencedProvider(
  private val steps: List<Pair<Int, String>>,
) : HostHttpProvider {
  var calls = 0
    private set
  override suspend fun execute(request: HttpRequest): HttpResponse {
    val (s, b) = steps[minOf(calls, steps.lastIndex)]
    calls += 1
    return HttpResponse(status = s, body = b)
  }
}

class FeedRepositoryTest {
  @Test
  fun populated() = runTest {
    val repo = FeedRepository(FixedProvider(200, """[{"id":"1","title":"First"},{"id":"2","title":"Second"}]"""))
    val rows = repo.load()
    assertEquals(2, rows.size)
    assertEquals("First", rows[0].title)
  }

  @Test
  fun empty() = runTest {
    val repo = FeedRepository(FixedProvider(200, "[]"))
    assertEquals(emptyList(), repo.load())
  }

  @Test
  fun errorStatusRaises() = runTest {
    val repo = FeedRepository(FixedProvider(500, """{"message":"boom"}"""))
    val result = runCatching { repo.load() }
    assertTrue(result.isFailure, "a 500 must not be reported as success")
  }

  @Test
  fun retryAfterFailureSucceeds() = runTest {
    val provider = SequencedProvider(
      listOf(500 to "", 200 to """[{"id":"1","title":"Recovered"}]"""),
    )
    val repo = FeedRepository(provider)
    assertTrue(runCatching { repo.load() }.isFailure, "first attempt should fail")
    val rows = repo.load()
    assertEquals(listOf("Recovered"), rows.map { it.title })
    assertEquals(2, provider.calls, "retry must issue a second request")
  }
}
