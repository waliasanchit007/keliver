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
 * - `https:` opens in an in-app browser tab (Custom Tabs), on any host;
 * - `intent:` is handled only as a browser would, and stricter: the intent is
 *   a VIEW (or names no action), names no component and no selector, is
 *   limited to activities that declare BROWSABLE, keeps none of the link's
 *   launch flags and grants no URI permission. It is refused when it names or
 *   resolves to this app (its own deep links included), or carries a `file:`,
 *   `content:`, `http:`, `javascript:`, `data:` or `intent:` URI. Otherwise it
 *   reaches any app with a BROWSABLE activity for it, custom schemes included;
 *   one whose data is an https page opens in the browser tab instead. When
 *   nothing can handle it, its `S.browser_fallback_url` is opened if it
 *   is https;
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
      scheme == "intent" -> Decision.BrowsableIntent(trimmed) // parsed and checked by open()
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
  } catch (e: SecurityException) {
    false.also { Log.w(TAG, "link not opened: ${e.message}") }
  }

  /**
   * Why a parsed `intent:` link may not be opened, or null. Pure (no Android calls), so the rules
   * are tested on the JVM; [resolvesToThisApp] is whether any activity of this app would take it.
   */
  internal fun intentRefusal(action: String?, targetPackage: String?, dataScheme: String?, thisPackage: String, resolvesToThisApp: Boolean): String? = when {
    action != null && action != Intent.ACTION_VIEW -> "an intent: link may only VIEW (it asks for $action)"
    targetPackage == thisPackage || resolvesToThisApp -> "an intent: link may not open this app"
    dataScheme != null && dataScheme.lowercase() in REFUSED_DATA_SCHEMES -> "an intent: link may not carry a ${dataScheme.lowercase()}: URI"
    else -> null
  }

  private fun browsableIntent(context: Context, url: String): Boolean {
    val intent = try {
      // parseUri knows only a lower-case "intent:".
      Intent.parseUri("intent:" + url.substringAfter(':'), Intent.URI_INTENT_SCHEME)
    } catch (e: Exception) { // URISyntaxException, and a bad typed extra's NumberFormatException
      return false.also { Log.w(TAG, "link refused: a malformed intent: URL") }
    }
    val fallback = intent.getStringExtra("browser_fallback_url")
    // As a browser does, and stricter: no explicit target, browsable activities only, none of the
    // link's flags (no task or recents games, no URI grants), no clip data.
    intent.component = null
    intent.selector = null
    intent.addCategory(Intent.CATEGORY_BROWSABLE)
    intent.flags = 0
    intent.clipData = null
    if (intent.action == null) intent.action = Intent.ACTION_VIEW
    val resolvesToThisApp = context.packageManager
      .queryIntentActivities(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
      .any { it.activityInfo?.packageName == context.packageName }
    val refused = intentRefusal(intent.action, intent.`package`, intent.data?.scheme, context.packageName, resolvesToThisApp)
    if (refused != null) return false.also { Log.w(TAG, "link refused: $refused") }
    // An https page opens the way a bare https link does: in the app's browser tab.
    if (intent.data?.scheme?.lowercase() == "https" && fallbackAllowed(intent.dataString)) return inAppBrowser(context, intent.dataString!!)
    if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
      context.startActivity(intent)
      true
    } catch (_: ActivityNotFoundException) {
      if (fallbackAllowed(fallback)) inAppBrowser(context, fallback!!)
      else false.also { Log.w(TAG, "link not opened: nothing handles it, and no https fallback") }
    } catch (e: SecurityException) { // the activity that matched needs a permission this app lacks
      false.also { Log.w(TAG, "link not opened: ${e.message}") }
    }
  }

  private fun httpsHost(url: String): String? =
    runCatching { java.net.URI(url).host }.getOrNull()?.takeIf { it.isNotEmpty() }

  private val REFUSED_DATA_SCHEMES = setOf("file", "content", "http", "javascript", "data", "intent")
}
