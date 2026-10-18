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
   * [text] (LF, the edited [original]) with [original]'s line endings:
   *  - [original] has no `\r\n`: [text] as it is;
   *  - CRLF throughout: every line CRLF;
   *  - MIXED (an LF file with a `\r\n` inside a raw string, say): every
   *    line the edit did not touch keeps its own ending, and new lines are
   *    LF — or null when a CRLF line of [original] is not kept unchanged in
   *    [text] (rewriting it would silently change its ending; the caller
   *    refuses the change instead).
   */
  fun restore(original: String, text: String): String? = when {
    "\r\n" !in original -> text
    isCrlf(original) -> toLf(text).replace("\n", "\r\n")
    else -> restoreMixed(original, text)
  }

  /** Lines matched by a longest common subsequence keep their `\r`; an unmatched CRLF line = null. */
  private fun restoreMixed(original: String, text: String): String? {
    val old = original.split('\n')
    val oldLf = old.map { it.removeSuffix("\r") }
    val new = toLf(text).split('\n')
    // Common prefix and suffix first (an edit touches few lines), LCS in between.
    var pre = 0
    while (pre < oldLf.size && pre < new.size && oldLf[pre] == new[pre]) pre++
    var suf = 0
    while (suf < oldLf.size - pre && suf < new.size - pre && oldLf[oldLf.size - 1 - suf] == new[new.size - 1 - suf]) suf++
    val a = oldLf.subList(pre, oldLf.size - suf)
    val b = new.subList(pre, new.size - suf)
    val keepCr = BooleanArray(new.size)
    for (i in 0 until pre) keepCr[i] = old[i].endsWith("\r")
    for (k in 0 until suf) keepCr[new.size - 1 - k] = old[old.size - 1 - k].endsWith("\r")
    // LCS table over the middle.
    val l = Array(a.size + 1) { IntArray(b.size + 1) }
    for (i in a.indices.reversed()) for (j in b.indices.reversed()) {
      l[i][j] = if (a[i] == b[j]) l[i + 1][j + 1] + 1 else maxOf(l[i + 1][j], l[i][j + 1])
    }
    var i = 0
    var j = 0
    while (i < a.size && j < b.size) {
      when {
        a[i] == b[j] -> { keepCr[pre + j] = old[pre + i].endsWith("\r"); i++; j++ }
        l[i + 1][j] >= l[i][j + 1] -> { if (old[pre + i].endsWith("\r")) return null; i++ }
        else -> j++
      }
    }
    while (i < a.size) { if (old[pre + i].endsWith("\r")) return null; i++ }
    return new.indices.joinToString("\n") { if (keepCr[it]) new[it] + "\r" else new[it] }
  }
}
