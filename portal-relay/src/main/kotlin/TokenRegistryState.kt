import dev.keliver.portal.document.DocNode
import dev.keliver.portal.document.DocOp
import dev.keliver.portal.document.OpBatch
import dev.keliver.portal.document.PropValue
import dev.keliver.portal.document.UiDocument
import dev.keliver.portal.document.lit
import dev.keliver.portal.ingest.TokenEntry
import dev.keliver.portal.ingest.TokenFileScan
import dev.keliver.portal.ingest.TokenTable
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * K2: per-project design-token tables (project isolation, thread-safe), like
 * [Components]. Built at boot from the scanned sources — the screens and
 * components dirs, plus any `tokensDirs` in keliver.portal.json — and rebuilt
 * whenever one of those files changes. [rebuild] reports whether anything
 * resolution can observe changed, so the relay re-ingests dependent screens
 * (the canvas re-resolves; a token that vanished or changed kind becomes
 * RawCode) only when it must. A file is re-parsed only when its modification
 * time or size changed.
 */
object Tokens {
  private val byProject = ConcurrentHashMap<String, TokenTable>()
  private val versions = ConcurrentHashMap<String, AtomicLong>()

  private class Cached(val mtime: Long, val length: Long, val id: String, val scan: TokenFileScan?)
  private val cache = ConcurrentHashMap<String, Cached>()

  /** How many files were parsed (cache misses) — for tests. */
  internal val parses = AtomicInteger()

  fun table(project: String): TokenTable = byProject[project] ?: TokenTable.EMPTY

  /** Bumped every time [project]'s table changes; sent with document events so editors refresh. */
  fun version(project: String): Long = versions[project]?.get() ?: 0L

  /** A file's token identity: its path relative to [root] (absolute when outside it). */
  fun fileId(f: File, root: File): String {
    val file = f.absoluteFile.normalize()
    val base = root.absoluteFile.normalize()
    return if (file.path.startsWith(base.path + File.separator)) file.relativeTo(base).invariantSeparatorsPath
    else file.invariantSeparatorsPath
  }

  /**
   * Rescan [flatDirs] (their own `.kt` files, as the screens/components dirs
   * are read) and [deepDirs] (recursively); file identities are paths
   * relative to [root]. True when the table changed.
   */
  fun rebuild(project: String, flatDirs: List<File>, deepDirs: List<File> = emptyList(), root: File = File(".")): Boolean {
    val files = LinkedHashMap<String, File>()
    fun take(f: File) {
      if (f.isFile && f.name.endsWith(".kt") && !f.name.startsWith("Compiled_")) files[f.absoluteFile.normalize().path] = f
    }
    flatDirs.forEach { d -> d.listFiles()?.forEach(::take) }
    deepDirs.forEach { d -> if (d.isDirectory) d.walkTopDown().forEach(::take) }
    val scans = files.entries.sortedBy { it.key }.mapNotNull { (path, f) ->
      val id = fileId(f, root)
      val mtime = f.lastModified()
      val length = f.length()
      val hit = cache[path]?.takeIf { it.mtime == mtime && it.length == length && it.id == id }
      (hit ?: run {
        parses.incrementAndGet()
        val scan = runCatching { TokenTable.scanFile(id, f.readText()) }.getOrNull()
        Cached(mtime, length, id, scan).also { cache[path] = it }
      }).scan
    }
    val next = TokenTable.of(scans)
    val previous = byProject.put(project, next)
    val changed = (previous ?: TokenTable.EMPTY).signature() != next.signature()
    if (changed) versions.getOrPut(project) { AtomicLong() }.incrementAndGet()
    return changed
  }

  /** Deterministic JSON for `GET /tokens`: every constant another file may reference and a prop can hold. */
  fun toJson(project: String): String = toJson(table(project))

  fun toJson(table: TokenTable): String =
    table.entries.filter { it.decl.isExported && it.isLiteralKind }
      .joinToString(",", "[", "]") { entryJson(it) }

  private fun entryJson(e: TokenEntry): String {
    fun str(s: String?) = if (s == null) "null" else JsonPrimitive(s).toString()
    val value = when (val v = e.value) {
      is String -> JsonPrimitive(v).toString()
      is Number, is Boolean -> v.toString() // finite by construction (non-finite literals are refused)
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
   * The client-op gate for token values: the batch with every token's value
   * replaced by the table's, or the refusal. A client names a token by its
   * reference and import; it must be one [table] lets the file [fileId] use
   * ([TokenTable.usable]: of a literal kind, not private in its object, and
   * exported or declared in a private object of that same file). An
   * ambiguous reference (the same `Owner.Name` in two packages) is accepted
   * here — the binding check that follows decides what it means in the file.
   */
  fun normalize(batch: OpBatch, table: TokenTable, fileId: String): Pair<OpBatch?, String?> {
    var error: String? = null
    fun fix(v: PropValue): PropValue {
      if (v !is PropValue.Token || error != null) return v
      val entry = table.usable(v.ref, v.import, fileId)
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

  /** Every token value a batch writes (SetProp, SetModifier, inserted subtrees). */
  fun tokensIn(batch: OpBatch): Set<PropValue.Token> {
    val out = LinkedHashSet<PropValue.Token>()
    fun node(n: DocNode) {
      if (n !is DocNode.Widget) return
      (n.props.values + n.modifiers.values).filterIsInstance<PropValue.Token>().forEach(out::add)
      n.children.forEach(::node)
    }
    for (op in batch.ops) {
      when (op) {
        is DocOp.SetProp -> (op.value as? PropValue.Token)?.let(out::add)
        is DocOp.SetModifier -> (op.value as? PropValue.Token)?.let(out::add)
        is DocOp.InsertNode -> node(op.node)
        else -> {}
      }
    }
    return out
  }

  /**
   * [doc] with every token's value re-read from [table] — for an undo/redo,
   * whose inverse ops carry values from when they were recorded. Refused
   * when a token is no longer usable.
   */
  fun refresh(doc: UiDocument, table: TokenTable, fileId: String): Pair<UiDocument?, String?> {
    var error: String? = null
    fun fix(v: PropValue): PropValue {
      if (v !is PropValue.Token || error != null) return v
      val entry = table.usable(v.ref, v.import, fileId)
        ?: return v.also { error = "design token ${v.ref} no longer exists" }
      return v.copy(value = lit(entry.value))
    }
    fun node(n: DocNode): DocNode = when (n) {
      is DocNode.RawCode -> n
      is DocNode.Widget -> n.copy(
        props = n.props.mapValues { fix(it.value) },
        modifiers = n.modifiers.mapValues { fix(it.value) },
        children = n.children.map(::node),
      )
    }
    val root = node(doc.root)
    return if (error != null) null to error else doc.copy(root = root) to null
  }

  /**
   * K2 compatibility: [doc] as an editor that predates design tokens can
   * decode it — every token replaced by its resolved literal. The relay
   * serves this unless the client advertises token support (`tokens=1`), so
   * an older per-app editor still renders the values; an edit it makes to an
   * untouched prop leaves the server document's reference, and the file's,
   * alone (only a prop it rewrites becomes a literal — a detach).
   */
  fun withoutTokens(doc: UiDocument): UiDocument {
    fun v(x: PropValue): PropValue = if (x is PropValue.Token) x.value else x
    fun node(n: DocNode): DocNode = when (n) {
      is DocNode.RawCode -> n
      is DocNode.Widget -> n.copy(
        props = n.props.mapValues { v(it.value) },
        modifiers = n.modifiers.mapValues { v(it.value) },
        children = n.children.map(::node),
      )
    }
    return doc.copy(root = node(doc.root))
  }
}
