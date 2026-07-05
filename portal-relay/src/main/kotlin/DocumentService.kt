/*
 * V2 M1 — the per-screen live Document engine (design §2): validate -> apply
 * -> bump version -> broadcast (SSE) -> project into the V1 draft store ->
 * debounced dual-write of the projected .kt (whole-file until M4 write-back).
 */
import dev.keliver.portal.WidgetNode
import dev.keliver.portal.deserializeTree
import dev.keliver.portal.document.Contract
import dev.keliver.portal.document.DocNode
import dev.keliver.portal.document.DocOp
import dev.keliver.portal.document.Handle
import dev.keliver.portal.document.OpAck
import dev.keliver.portal.document.OpBatch
import dev.keliver.portal.document.UiDocument
import dev.keliver.portal.document.lit
import dev.keliver.portal.document.toWidgetTree
import dev.keliver.portal.exportKotlin
import java.io.File
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class DocumentService(
  private val screenKey: String,
  initial: UiDocument,
  private val onProjected: (WidgetNode) -> Unit,
  private val kotlinFile: File,
  /** M6: screen function name (e.g. "MainScreen") — Bindings iface derives from it. */
  private val functionName: String = "PortalScreen",
  /** M6: package line for full exports of in-project screens (null = none/legacy). */
  private val packageName: String? = null,
) {
  @Volatile var doc: UiDocument = initial
    private set

  private val undoStacks = ConcurrentHashMap<String, ArrayDeque<List<DocOp>>>()
  private val redoStacks = ConcurrentHashMap<String, ArrayDeque<List<DocOp>>>()
  private val listeners = CopyOnWriteArrayList<OutputStream>()
  private val writer = Executors.newSingleThreadScheduledExecutor { r ->
    Thread(r, "doc-writeback").apply { isDaemon = true }
  }
  private var pendingWrite: ScheduledFuture<*>? = null

  /** M2: validate + report WITHOUT committing — the agent's safe exploration path. */
  @Synchronized
  fun dryRun(batch: OpBatch): OpAck {
    if (batch.baseVersion != doc.version) {
      return OpAck(false, doc.version, "stale base version ${batch.baseVersion}, document is at ${doc.version}")
    }
    val res = doc.applyBatch(batch.ops)
    return if (res.result == null) OpAck(false, doc.version, res.error) else OpAck(true, doc.version)
  }

  @Synchronized
  fun submit(batch: OpBatch): OpAck {
    if (batch.baseVersion != doc.version) {
      return OpAck(false, doc.version, "stale base version ${batch.baseVersion}, document is at ${doc.version}")
    }
    if (batch.ops.any { it::class.simpleName == "InsertExisting" }) {
      return OpAck(false, doc.version, "internal op not accepted")
    }
    val res = doc.applyBatch(batch.ops)
    val ok = res.result ?: return OpAck(false, doc.version, res.error)
    doc = ok.doc.copy(version = doc.version + 1)
    undoStacks.getOrPut(batch.envelope.session) { ArrayDeque() }.apply {
      addLast(ok.inverseBatch)
      while (size > 100) removeFirst()
    }
    redoStacks.remove(batch.envelope.session)
    changed()
    return OpAck(true, doc.version)
  }

  @Synchronized
  fun undo(session: String): OpAck {
    val inverses = undoStacks[session]?.removeLastOrNull()
      ?: return OpAck(false, doc.version, "nothing to undo")
    val res = doc.applyBatch(inverses)
    val ok = res.result ?: return OpAck(false, doc.version, "undo failed: ${res.error}")
    redoStacks.getOrPut(session) { ArrayDeque() }.addLast(ok.inverseBatch)
    doc = ok.doc.copy(version = doc.version + 1)
    changed()
    return OpAck(true, doc.version)
  }

  @Synchronized
  fun redo(session: String): OpAck {
    val inverses = redoStacks[session]?.removeLastOrNull()
      ?: return OpAck(false, doc.version, "nothing to redo")
    val res = doc.applyBatch(inverses)
    val ok = res.result ?: return OpAck(false, doc.version, "redo failed: ${res.error}")
    undoStacks.getOrPut(session) { ArrayDeque() }.addLast(ok.inverseBatch)
    doc = ok.doc.copy(version = doc.version + 1)
    changed()
    return OpAck(true, doc.version)
  }

  fun subscribe(out: OutputStream) {
    listeners += out
    runCatching { sendEvent(out, doc.version) }
  }

  /** V2 M3: a file-originated document (recognize+reconcile done by the caller).
   * No undo entry, no .kt rewrite — the file IS the new baseline. */
  @Synchronized
  fun acceptExternal(newDoc: dev.keliver.portal.document.UiDocument) {
    doc = newDoc
    undoStacks.clear()
    redoStacks.clear()
    changed(writeBack = false)
  }

  /** Self-write suppression for the file watcher. */
  @Volatile private var lastWrittenText: String? = null
  fun wasSelfWrite(text: String): Boolean = text == lastWrittenText

  private fun changed(writeBack: Boolean = true) {
    onProjected(doc.toWidgetTree())
    listeners.removeAll { out -> runCatching { sendEvent(out, doc.version) }.isFailure }
    if (writeBack) {
      pendingWrite?.cancel(false)
      pendingWrite = writer.schedule({ writeKotlin() }, 400, TimeUnit.MILLISECONDS)
    }
  }

  private fun sendEvent(out: OutputStream, version: Long) {
    out.write("data: {\"version\":$version}\n\n".toByteArray())
    out.flush()
  }

  /** M6: create the canonical .kt on first sight of a screen (no change needed). */
  fun ensureKotlinFile() {
    if (!kotlinFile.exists()) writer.schedule({ writeKotlin() }, 100, TimeUnit.MILLISECONDS)
  }

  private fun writeKotlin() {
    runCatching {
      kotlinFile.parentFile.mkdirs()
      // M4: surgical PSI write-back preserves comments/formatting/RawCode when
      // the file already exists; fall back to a full export on first write or
      // when the change isn't safely surgical (type change, reorder, contract).
      val existing = if (kotlinFile.exists()) kotlinFile.readText() else null
      val surgical = existing?.let { dev.keliver.portal.ingest.WriteBack.merge(it, doc) }
      val pkg = packageName?.let { "package $it\n\n" } ?: ""
      val text = surgical ?: (pkg + exportKotlin(doc.toWidgetTree(), functionName = functionName))
      lastWrittenText = text
      kotlinFile.writeText(text)
    }.onFailure { println("writeKotlin failed for $screenKey: $it") }
  }

  companion object {
    /** M6: the canonical .kt file bootstraps the document when it exists (file = truth). */
    fun fromFileOrTree(
      screenKey: String,
      treeJson: String?,
      onProjected: (WidgetNode) -> Unit,
      kotlinFile: File,
      functionName: String,
      packageName: String?,
    ): DocumentService {
      if (kotlinFile.exists()) {
        val rec = runCatching {
          dev.keliver.portal.ingest.Recognizer.recognize(kotlinFile.name, kotlinFile.readText())
        }.getOrNull()
        if (rec != null) {
          var next = 1L
          fun renumber(n: dev.keliver.portal.document.DocNode): dev.keliver.portal.document.DocNode = when (n) {
            is dev.keliver.portal.document.DocNode.RawCode -> n.copy(handle = Handle(next++))
            is dev.keliver.portal.document.DocNode.Widget ->
              n.copy(handle = Handle(next++), children = n.children.map { renumber(it) })
          }
          val root = renumber(rec.root)
          val doc = UiDocument(screenKey, root, rec.contract, version = 0, nextHandle = next)
          return DocumentService(screenKey, doc, onProjected, kotlinFile, functionName, packageName)
            .also { it.onProjected(doc.toWidgetTree()) } // draft mirror catches up
        }
      }
      return fromTree(screenKey, treeJson, onProjected, kotlinFile, functionName, packageName)
    }

    /** M1 bootstrap: lift an existing V1 draft tree into a Document. */
    fun fromTree(
      screenKey: String,
      treeJson: String?,
      onProjected: (WidgetNode) -> Unit,
      kotlinFile: File,
      functionName: String = "PortalScreen",
      packageName: String? = null,
    ): DocumentService {
      var next = 1L
      fun lift(n: WidgetNode): DocNode.Widget = DocNode.Widget(
        handle = Handle(next++),
        type = n.type,
        props = n.props.filterKeys { !it.startsWith("mod.") }
          .mapValues { (_, v) ->
            when (v) {
              is dev.keliver.portal.Bind -> dev.keliver.portal.document.PropValue.Bind(v.field)
              is dev.keliver.portal.Action -> dev.keliver.portal.document.PropValue.Action(v.name)
              else -> lit(v)
            }
          },
        modifiers = n.props.filterKeys { it.startsWith("mod.") }
          .map { (k, v) -> k.removePrefix("mod.") to lit(v) }.toMap(),
        children = n.children.map { lift(it) },
      )
      val root = treeJson?.takeIf { it.isNotBlank() && it != "{}" }
        ?.let { lift(deserializeTree(it)) }
        ?: DocNode.Widget(Handle(next++), "Column")
      val doc = UiDocument(screenKey, root, Contract(), version = 0, nextHandle = next)
      return DocumentService(screenKey, doc, onProjected, kotlinFile, functionName, packageName)
    }
  }
}
