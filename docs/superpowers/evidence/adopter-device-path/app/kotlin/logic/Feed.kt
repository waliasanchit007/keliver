package newsstand.logic

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.keliver.http.HostHttpProvider
import dev.keliver.http.KeliverHttp
import kotlinx.serialization.Serializable
import newsstand.screens.FeedRow
import newsstand.screens.FeedScreenBindings

@Serializable
data class Article(val id: String, val title: String)

class FeedRepository(provider: HostHttpProvider) {
  private val http = KeliverHttp(provider)
  suspend fun load(): List<Article> = http.get("/articles")
}

enum class FeedState { Loading, Populated, Empty, Error }

@Composable
fun FeedPresenter(repo: FeedRepository): FeedScreenBindings {
  var state by remember { mutableStateOf(FeedState.Loading) }
  var articles by remember { mutableStateOf(emptyList<Article>()) }
  var attempt by remember { mutableStateOf(0) }

  LaunchedEffect(attempt) {
    state = FeedState.Loading
    runCatching { repo.load() }
      .onSuccess {
        articles = it
        state = if (it.isEmpty()) FeedState.Empty else FeedState.Populated
      }
      .onFailure { articles = emptyList(); state = FeedState.Error }
  }

  val current = state
  val mapped = articles.map { a -> object : FeedRow { override val title = a.title } }
  return object : FeedScreenBindings {
    override val statusLine = when (current) {
      FeedState.Loading -> "Loading…"
      FeedState.Populated -> "${mapped.size} articles"
      FeedState.Empty -> "No articles yet"
      FeedState.Error -> "Couldn't load articles"
    }
    override val isError = current == FeedState.Error
    override val rows = mapped
    override fun retry() { attempt += 1 }
  }
}
