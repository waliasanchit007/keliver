/*
 * E40: naming a new screen or project. A screen's name is its .kt file's and
 * its function's (`home` → `home.kt`, `HomeScreen`), so it must be one Kotlin
 * can read, and a name another screen already has (in any case: a macOS or
 * Windows file system does not tell `Home.kt` from `home.kt`) is refused
 * before anything is created.
 */

/** Why [name] cannot name a new [what] ("screen" / "project"), or null when it can. */
internal fun nameProblem(name: String, existing: Collection<String>, what: String): String? {
  val n = name.trim()
  return when {
    n.isEmpty() -> "Enter a name for the $what"
    !Regex("[A-Za-z][A-Za-z0-9_]*").matches(n) ->
      "Letters, digits and _ only, starting with a letter — it names a .kt file and a Kotlin function"
    n.length > 60 -> "At most 60 characters"
    existing.any { it.equals(n, ignoreCase = true) } -> "A $what named “${existing.first { it.equals(n, ignoreCase = true) }}” already exists"
    else -> null
  }
}
