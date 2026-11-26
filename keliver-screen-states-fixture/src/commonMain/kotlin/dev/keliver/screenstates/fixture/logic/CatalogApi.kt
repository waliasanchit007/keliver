package dev.keliver.screenstates.fixture.logic

import dev.keliver.capabilities.HostHttp
import dev.keliver.capabilities.HostHttpRequest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class CatalogItem(val id: String, val title: String, val price: String)

/** Guest-owned endpoints and parsing; only the generic transport is a capability. */
class CatalogApi(
  private val http: HostHttp,
  private val json: Json = Json { ignoreUnknownKeys = true },
) {
  suspend fun items(): List<CatalogItem> = get("/v1/catalog")

  suspend fun item(id: String): CatalogItem = get("/v1/items/$id")

  private suspend inline fun <reified T> get(path: String): T {
    val response = http.execute(
      HostHttpRequest(method = "GET", path = path, headers = mapOf("Accept" to "application/json")),
    )
    check(response.status in 200..299) { "GET $path: HTTP ${response.status}" }
    return json.decodeFromString(response.body)
  }
}
