import dev.keliver.portal.document.DocNode
import dev.keliver.portal.document.DocOp
import dev.keliver.portal.document.OpBatch
import dev.keliver.portal.document.PropValue
import dev.keliver.portal.document.lit
import dev.keliver.portal.ingest.TokenEntry
import dev.keliver.portal.ingest.TokenTable
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * K2: per-project design-token tables (project isolation, thread-safe), like
 * [Components]. Built at boot from the scanned sources — the screens and
 * components dirs, plus any `tokensDirs` in keliver.portal.json — and rebuilt
 * whenever one of those files changes. [rebuild] reports whether anything
 * resolution can observe changed, so the relay re-ingests dependent screens
 * (the canvas re-resolves; a token that vanished or changed kind becomes
 * RawCode) only when it must.
 */
object Tokens {
  private val byProject = ConcurrentHashMap<String, TokenTable>()

  fun table(project: String): TokenTable = byProject[project] ?: TokenTable.EMPTY

  /**
   * Rescan [flatDirs] (their own `.kt` files, as the screens/components dirs
   * are read) and [deepDirs] (recursively). True when the table changed.
   */
  fun rebuild(project: String, flatDirs: List<File>, deepDirs: List<File> = emptyList()): Boolean {
    val files = LinkedHashMap<String, File>()
    fun take(f: File) {
      if (f.isFile && f.name.endsWith(".kt") && !f.name.startsWith("Compiled_")) files[f.canonicalPath] = f
    }
    flatDirs.forEach { d -> d.listFiles()?.forEach(::take) }
    deepDirs.forEach { d -> if (d.isDirectory) d.walkTopDown().forEach(::take) }
    val sources = files.values.sortedBy { it.canonicalPath }
      .mapNotNull { f -> runCatching { f.name to f.readText() }.getOrNull() }
    val next = TokenTable.scan(sources)
    val previous = byProject.put(project, next)
    return (previous ?: TokenTable.EMPTY).signature() != next.signature()
  }

  /** Deterministic JSON for `GET /tokens`: every constant another file may reference. */
  fun toJson(project: String): String = toJson(table(project))

  fun toJson(table: TokenTable): String =
    table.entries.filter { it.decl.isExported && it.value != null }
      .joinToString(",", "[", "]") { entryJson(it) }

  private fun entryJson(e: TokenEntry): String {
    fun str(s: String?) = if (s == null) "null" else JsonPrimitive(s).toString()
    val value = when (val v = e.value) {
      is String -> JsonPrimitive(v).toString()
      is Number, is Boolean -> v.toString()
      else -> "null"
    }
    return "{" +
      "\"ref\":${str(e.decl.ref)}," +
      "\"owner\":${str(e.decl.owner)}," +
      "\"name\":${str(e.decl.name)}," +
      "\"kind\":${str(e.kind)}," +
      "\"value\":$value," +
      "\"const\":${e.decl.isConst}," +
      "\"package\":${str(e.decl.packageName)}," +
      "\"import\":${str(e.decl.importFq)}," +
      "\"file\":${str(e.decl.fileName)}," +
      "\"ambiguous\":${e.ambiguous}" +
      "}"
  }

  /**
   * The client-op gate for token values (null = acceptable). A client names a
   * token by its reference and import; the server — never the client — owns
   * its value, so every token in [batch] must be one [table] resolves:
   * offered (exported, unambiguous), or a file-scoped one declared in
   * [fileName] itself. Returns the batch with each token's value replaced by
   * the table's (a stale client value cannot be saved).
   */
  fun normalize(batch: OpBatch, table: TokenTable, fileName: String): Pair<OpBatch?, String?> {
    var error: String? = null
    fun fix(v: PropValue): PropValue {
      if (v !is PropValue.Token || error != null) return v
      val entry = table.entries.firstOrNull { e ->
        e.decl.ref == v.ref && e.decl.importFq == v.import && e.value != null && !e.ambiguous &&
          !e.decl.isPrivate && (!e.decl.ownerPrivate || e.decl.fileName == fileName)
      }
      if (entry == null) {
        error = "unknown design token ${v.ref}" + (v.import?.let { " ($it)" } ?: "")
        return v
      }
      return v.copy(value = lit(entry.value))
    }
    fun fixNode(n: DocNode): DocNode = when (n) {
      is DocNode.RawCode -> n
      is DocNode.Widget -> n.copy(
        props = n.props.mapValues { fix(it.value) },
        modifiers = n.modifiers.mapValues { fix(it.value) },
        children = n.children.map(::fixNode),
      )
    }
    val ops = batch.ops.map { op ->
      when (op) {
        is DocOp.SetProp -> op.copy(value = fix(op.value))
        is DocOp.SetModifier -> op.copy(value = fix(op.value))
        is DocOp.InsertNode -> op.copy(node = fixNode(op.node))
        else -> op
      }
    }
    return if (error != null) null to error else batch.copy(ops = ops) to null
  }
}
