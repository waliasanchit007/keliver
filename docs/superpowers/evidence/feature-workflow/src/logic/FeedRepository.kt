package feed.logic

import dev.keliver.http.HostHttpProvider
import dev.keliver.http.KeliverHttp
import kotlinx.serialization.Serializable

@Serializable
data class Article(val id: String, val title: String)

/** Plain class, no Compose — unit-testable without a UI or a device. */
class FeedRepository(provider: HostHttpProvider) {
  private val http = KeliverHttp(provider)
  suspend fun load(): List<Article> = http.get("/articles")
}
