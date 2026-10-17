import java.io.File

/** What a changed `.kt` file under a watched directory means to the relay. */
enum class KtRoute { COMPONENT, FLOW, SCREEN, TOKENS, OTHER }

/**
 * K2 (review B2): the handlers a file event goes to. Membership in the
 * screens, components and flows dirs decides first — a `tokensDirs` entry may
 * well be an ANCESTOR of those dirs (`src/commonMain/kotlin`), and a screen
 * edit must still be ingested as a screen. The screen and component handlers
 * rescan tokens themselves; any other file inside a tokens dir additionally
 * goes to the token handler.
 */
fun routeKtEvent(
  f: File,
  isComponent: (File) -> Boolean,
  isFlow: (File) -> Boolean,
  screenDirs: List<File>,
  legacyScreensRoot: File?,
  tokenDirs: List<File>,
): List<KtRoute> {
  val parent = f.absoluteFile.normalize().parentFile
  val inTokens = tokenDirs.any { d -> f.absoluteFile.normalize().path.startsWith(d.absoluteFile.normalize().path + File.separator) }
  val isScreen = screenDirs.any { it.absoluteFile.normalize() == parent } ||
    (legacyScreensRoot != null && parent?.parentFile == legacyScreensRoot.absoluteFile.normalize())
  return when {
    isComponent(f) -> listOf(KtRoute.COMPONENT)
    isFlow(f) -> if (inTokens) listOf(KtRoute.FLOW, KtRoute.TOKENS) else listOf(KtRoute.FLOW)
    isScreen -> listOf(KtRoute.SCREEN)
    inTokens -> listOf(KtRoute.TOKENS)
    else -> listOf(KtRoute.OTHER)
  }
}
