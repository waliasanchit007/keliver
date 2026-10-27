package @@PACKAGE@@

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.browser.customtabs.CustomTabsIntent

/**
 * How a link a Keliver screen asks to open leaves the app (adoption item 3, K1).
 * Call [open] from the action your bridge exposes for "open this URL"; nothing
 * else in the host opens links.
 *
 * - `https:` opens in an in-app browser tab (Custom Tabs);
 * - `intent:` is handled only as a browser would: the intent may name no
 *   component and no selector, is limited to activities that declare
 *   BROWSABLE, grants no URI permission, may not target this app, and may not
 *   carry a `file:` or `content:` URI. When nothing can handle it, its
 *   `S.browser_fallback_url` is opened if it is https;
 * - everything else (`http:`, `javascript:`, `file:`, `content:`, custom
 *   schemes) is refused.
 */
object ExternalLinkPolicy {
  /** What [classify] decided for a link. */
  sealed interface Decision {
    data class InAppBrowser(val url: String) : Decision
    data class BrowsableIntent(val url: String) : Decision
    data class Refused(val reason: String) : Decision
  }

  /** The decision for [url] by its scheme alone; [open] applies it. Pure: no Android calls. */
  fun classify(url: String): Decision {
    val trimmed = url.trim()
    val scheme = trimmed.substringBefore(':', missingDelimiterValue = "").lowercase()
    return when {
      scheme == "https" && httpsHost(trimmed) != null -> Decision.InAppBrowser(trimmed)
      scheme == "https" -> Decision.Refused("an https link without a host")
      scheme == "intent" -> Decision.BrowsableIntent(trimmed)
      scheme.isEmpty() -> Decision.Refused("not an absolute URL")
      else -> Decision.Refused("the $scheme: scheme is not opened")
    }
  }

  /** A `browser_fallback_url` is used only when it is itself an https link with a host. */
  fun fallbackAllowed(fallback: String?): Boolean =
    fallback != null && classify(fallback) is Decision.InAppBrowser

  /** Opens [url] under these rules; false (and a log line) when it was refused or nothing could open it. */
  fun open(context: Context, url: String): Boolean = when (val d = classify(url)) {
    is Decision.InAppBrowser -> inAppBrowser(context, d.url)
    is Decision.BrowsableIntent -> browsableIntent(context, d.url)
    is Decision.Refused -> false.also { Log.w(TAG, "link refused: ${d.reason}") }
  }

  private fun inAppBrowser(context: Context, url: String): Boolean = try {
    val tab = CustomTabsIntent.Builder().build()
    if (context !is android.app.Activity) tab.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    tab.launchUrl(context, Uri.parse(url))
    true
  } catch (e: ActivityNotFoundException) {
    false.also { Log.w(TAG, "link not opened: no browser (${e.message})") }
  }

  private fun browsableIntent(context: Context, url: String): Boolean {
    val intent = try {
      Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
    } catch (e: java.net.URISyntaxException) {
      return false.also { Log.w(TAG, "link refused: a malformed intent: URL") }
    }
    val fallback = intent.getStringExtra("browser_fallback_url")
    // As a browser does: no explicit target, browsable activities only, no granted URI access.
    intent.component = null
    intent.selector = null
    intent.addCategory(Intent.CATEGORY_BROWSABLE)
    intent.flags = intent.flags and URI_GRANT_FLAGS.inv()
    intent.clipData = null
    val dataScheme = intent.data?.scheme?.lowercase()
    val refused = when {
      intent.`package` == context.packageName -> "an intent: link may not target this app"
      dataScheme == "file" || dataScheme == "content" -> "an intent: link may not carry a $dataScheme: URI"
      else -> null
    }
    if (refused != null) return false.also { Log.w(TAG, "link refused: $refused") }
    if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
      context.startActivity(intent)
      true
    } catch (_: ActivityNotFoundException) {
      if (fallbackAllowed(fallback)) inAppBrowser(context, fallback!!)
      else false.also { Log.w(TAG, "link not opened: nothing handles it, and no https fallback") }
    }
  }

  private fun httpsHost(url: String): String? =
    runCatching { java.net.URI(url).host }.getOrNull()?.takeIf { it.isNotEmpty() }

  private const val URI_GRANT_FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION or
    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
}
