package feed.logic

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import feed.screens.FeedRow
import feed.screens.FeedScreenBindings

/** The five states the feature must exercise. */
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
      .onFailure {
        articles = emptyList()
        state = FeedState.Error
      }
  }

  val current = state
  val rows = articles.map { a -> object : FeedRow { override val title = a.title } }
  return object : FeedScreenBindings {
    override val isLoading = current == FeedState.Loading
    override val isEmpty = current == FeedState.Empty
    override val isError = current == FeedState.Error
    override val statusLine = when (current) {
      FeedState.Loading -> "Loading…"
      FeedState.Populated -> "${rows.size} articles"
      FeedState.Empty -> "No articles yet"
      FeedState.Error -> "Couldn't load articles"
    }
    override val rows = rows
    override fun retry() { attempt += 1 }
  }
}
