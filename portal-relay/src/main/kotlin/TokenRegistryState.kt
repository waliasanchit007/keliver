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
 * RawCode) only when it must. A file is re-parsed only when its content
 * changed (a SHA-256 of its bytes; reading is cheap, parsing is not).
 */
/** K3: [this] with [f] applied to each branch of a conditional value, or to the value itself. */
internal fun PropValue.mapLeaves(f: (PropValue) -> PropValue): PropValue =
  if (this is PropValue.Conditional) copy(whenTrue = whenTrue?.let(f), whenFalse = whenFalse?.let(f)) else f(this)

/** K3: a conditional value's branches, or the value itself. */
internal fun PropValue.leaves(): List<PropValue> =
  if (this is PropValue.Conditional) listOfNotNull(whenTrue, whenFalse) else listOf(this)

object Tokens {
  private val byProject = ConcurrentHashMap<String, TokenTable>()
  private val versions = ConcurrentHashMap<String, AtomicLong>()

  private class Cached(val digest: String, val id: String, val scan: TokenFileScan?)
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
    // Forget files that vanished from the scanned dirs.
    val roots = (flatDirs + deepDirs).map { it.absoluteFile.normalize().path + File.separator }
    cache.keys.filter { k -> k !in files && roots.any { k.startsWith(it) } }.forEach { cache.remove(it) }
    val scans = files.entries.sortedBy { it.key }.mapNotNull { (path, f) ->
      val id = fileId(f, root)
      val bytes = runCatching { f.readBytes() }.getOrNull() ?: return@mapNotNull null
      val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
      val hit = cache[path]?.takeIf { it.digest == digest && it.id == id }
      (hit ?: run {
        parses.incrementAndGet()
        val scan = runCatching { TokenTable.scanFile(id, bytes.decodeToString()) }.getOrNull()
        Cached(digest, id, scan).also { cache[path] = it }
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
   * ([TokenTable.usable]: of a literal kind, never private in its object, and
   * either exported, or a private top-level constant or a member of a private
   * object declared in that same file). An
   * ambiguous reference (the same `Owner.Name` in two packages) is accepted
   * here — the binding check that follows decides what it means in the file.
   */
  fun normalize(batch: OpBatch, table: TokenTable, fileId: String): Pair<OpBatch?, String?> {
    var error: String? = null
    fun fix(value: PropValue): PropValue = value.mapLeaves { v ->
      if (v !is PropValue.Token || error != null) return@mapLeaves v
      val entry = table.usable(v.ref, v.import, fileId)
      if (entry == null) {
        error = "unknown design token ${v.ref}" + (v.import?.let { " ($it)" } ?: "")
        return@mapLeaves v
      }
      v.copy(value = lit(entry.value))
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
      (n.props.values + n.modifiers.values).flatMap { it.leaves() }.filterIsInstance<PropValue.Token>().forEach(out::add)
      n.children.forEach(::node)
    }
    for (op in batch.ops) {
      when (op) {
        is DocOp.SetProp -> op.value.leaves().filterIsInstance<PropValue.Token>().forEach(out::add)
        is DocOp.SetModifier -> op.value.leaves().filterIsInstance<PropValue.Token>().forEach(out::add)
        is DocOp.InsertNode -> node(op.node)
        else -> {}
      }
    }
    return out
  }

  /** The imports the tokens already in [doc] imply (write-back adds them). */
  fun importsIn(doc: UiDocument): Set<String> {
    val out = LinkedHashSet<String>()
    fun node(n: DocNode) {
      if (n !is DocNode.Widget) return
      (n.props.values + n.modifiers.values).flatMap { it.leaves() }.filterIsInstance<PropValue.Token>().forEach { t -> t.import?.let(out::add) }
      n.children.forEach(::node)
    }
    node(doc.root)
    return out
  }

  /**
   * What [refresh] produced: the document with every restored token re-read,
   * those [restored] tokens (in document order) and the imports the tokens it
   * did NOT touch imply ([keptImports]) — what the binding check seeds its
   * scope with, so a restored token is checked against every other token the
   * document holds but never against its own (stale) self.
   */
  class Refreshed(val doc: UiDocument, val restored: List<PropValue.Token>, val keptImports: Set<String>)

  /**
   * After an undo/redo turned [before] into [after]: every token the inverse
   * RESTORED (a token value that differs from what that node held before)
   * re-read from [table] — inverse ops carry values from when they were
   * recorded. Refused when a restored token is no longer usable from the
   * file [fileId], or its kind no longer fits the prop. Tokens the inverse did
   * not touch are left alone. The caller still runs the binding check
   * ([TokenTable.bindingProblem]) on [Refreshed.restored], as the gate does
   * for an edit.
   */
  fun refresh(
    before: UiDocument,
    after: UiDocument,
    table: TokenTable,
    fileId: String,
    components: dev.keliver.portal.ComponentRegistry = dev.keliver.portal.EmptyComponentRegistry,
  ): Pair<Refreshed?, String?> {
    val old = HashMap<Long, DocNode.Widget>()
    fun index(n: DocNode) {
      if (n is DocNode.Widget) { old[n.handle.v] = n; n.children.forEach(::index) }
    }
    index(before.root)
    var error: String? = null
    val restored = ArrayList<PropValue.Token>()
    val kept = LinkedHashSet<String>()
    fun fix(type: String, key: String, value: PropValue, was: PropValue?): PropValue {
      // K3: each branch of a conditional is checked against the same branch before.
      if (value is PropValue.Conditional) {
        val prev = was as? PropValue.Conditional
        return value.copy(
          whenTrue = value.whenTrue?.let { fix(type, key, it, prev?.whenTrue) },
          whenFalse = value.whenFalse?.let { fix(type, key, it, prev?.whenFalse) },
        )
      }
      val v = value
      if (v !is PropValue.Token || error != null) return v
      if (v == was) return v.also { t -> t.import?.let(kept::add) }
      val entry = table.usable(v.ref, v.import, fileId)
        ?: return v.also { error = "design token ${v.ref} no longer exists" }
      if (!dev.keliver.portal.tokenFits(dev.keliver.portal.propSourceType(type, key, components), entry.value)) {
        error = "design token ${v.ref} is now a ${entry.kind} and no longer fits $type.$key"
        return v
      }
      return v.copy(value = lit(entry.value)).also(restored::add)
    }
    fun node(n: DocNode): DocNode = when (n) {
      is DocNode.RawCode -> n
      is DocNode.Widget -> {
        val prev = old[n.handle.v]
        n.copy(
          props = n.props.mapValues { (k, v) -> fix(n.type, k, v, prev?.props?.get(k)) },
          modifiers = n.modifiers.mapValues { (k, v) -> fix(n.type, "mod.$k", v, prev?.modifiers?.get(k)) },
          children = n.children.map(::node),
        )
      }
    }
    val root = node(after.root)
    return if (error != null) null to error else Refreshed(after.copy(root = root), restored, kept) to null
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
    fun v(x: PropValue): PropValue = x.mapLeaves { if (it is PropValue.Token) it.value else it }
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
