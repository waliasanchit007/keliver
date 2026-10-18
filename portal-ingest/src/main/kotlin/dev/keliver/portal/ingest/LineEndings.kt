package dev.keliver.portal.ingest

/**
 * CRLF files (K3 review nit). The PSI parser misreads `\r\n` — a
 * `@Composable` before it is dropped, so the screen was not recognized — so
 * recognition parses the LF text ([toLf]); nothing does offset surgery on the
 * source it was given (write-back edits the parsed file's own LF text). What
 * is written back keeps the file's line endings ([restore]).
 */
object LineEndings {
  fun toLf(text: String): String = if ('\r' in text) text.replace("\r\n", "\n") else text

  /** Whether every line of [text] ends in `\r\n` (and there is at least one). */
  fun isCrlf(text: String): Boolean {
    val crlf = Regex("\r\n").findAll(text).count()
    return crlf > 0 && crlf == text.count { it == '\n' }
  }

  /**
   * [text] with [original]'s line endings: CRLF when [original] is CRLF
   * throughout; otherwise [text] as it is (an LF file stays LF, a mixed one
   * is written as LF — there is no one ending to keep).
   */
  fun restore(original: String, text: String): String =
    if (isCrlf(original)) toLf(text).replace("\n", "\r\n") else text
}
