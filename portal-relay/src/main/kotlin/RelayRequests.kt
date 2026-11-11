import com.sun.net.httpserver.HttpExchange
import java.net.URLDecoder

// Request helpers shared by Relay.kt and the routes that register themselves on its
// server (ScreenStatesIndex). Kept out of Relay.kt so a route can be tested on its own
// server without initialising the relay's top-level state (its store, config, services).

/** project/screen names are path segments — restrict to a safe charset. */
internal fun safe(name: String): String = name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifEmpty { "unnamed" }

internal fun query(ex: HttpExchange): Map<String, String> =
  (ex.requestURI.rawQuery ?: "").split('&').filter { '=' in it }.associate {
    val (k, v) = it.split('=', limit = 2)
    k to URLDecoder.decode(v, "UTF-8")
  }
