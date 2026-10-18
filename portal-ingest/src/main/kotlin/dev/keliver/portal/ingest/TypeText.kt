package dev.keliver.portal.ingest

/**
 * Whether the Kotlin type text [type] (a declared parameter type) is nullable:
 * a `?` that closes the WHOLE type. An unparenthesized function type's `?`
 * belongs to its return type — `() -> Unit?` is a non-null function returning
 * `Unit?`; the nullable function type is `(() -> Unit)?`. So a type with an
 * `->` outside every `(…)`/`<…>` is a function type, never nullable.
 * The one rule ingest (a null branch) and the relay's gate both read.
 */
fun isNullableType(type: String?): Boolean {
  val t = type?.trim().orEmpty()
  if (!t.endsWith("?")) return false
  var depth = 0
  var i = 0
  while (i < t.length) {
    when {
      t.startsWith("->", i) -> {
        if (depth == 0) return false
        i++
      }
      t[i] == '(' || t[i] == '<' -> depth++
      t[i] == ')' || t[i] == '>' -> depth--
    }
    i++
  }
  return true
}
