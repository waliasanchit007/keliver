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
import dev.keliver.portal.acceptsModifier
import dev.keliver.portal.document.UiDocument
import dev.keliver.portal.document.isClientOp
import dev.keliver.portal.document.lit
import dev.keliver.portal.document.toAny
import dev.keliver.portal.document.toPropValue
import dev.keliver.portal.document.toWidgetTree
import dev.keliver.portal.exportKotlin
import dev.keliver.portal.document.PropValue
import dev.keliver.portal.ingest.TokenTable
import dev.keliver.portal.propSourceType
import dev.keliver.portal.tokenFits
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
  /** C1: the project's component registry provider (component-aware write-back). */
  private val components: () -> dev.keliver.portal.ComponentRegistry = { dev.keliver.portal.EmptyComponentRegistry },
  /** K2: the project's design-token table provider (token-aware ingest, gate and write-back). */
  private val tokens: () -> TokenTable = { TokenTable.EMPTY },
  /** K2: the screen file's identity for token visibility — its repo-relative path in the relay. */
  private val tokenFileId: String = kotlinFile.name,
  /** K2: the project's token-table version, sent with every change event (editors refresh their picker). */
  private val tokensVersion: () -> Long = { 0L },
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

  /**
   * The client-op gate shared by [dryRun] and [submit]: the batch to apply, or
   * the refusal. K2: token values are first replaced by the server's table
   * (the client's value is never trusted — not even for the fit checks that
   * follow), then every token must mean, in THIS file, the declaration the
   * client chose, without changing what any existing reference means.
   */
  private fun gate(batch: OpBatch): Pair<OpBatch?, String?> {
    if (batch.ops.any { !it.isClientOp() }) return null to "internal op not accepted"
    val table = tokens()
    val (normalized, refused) = Tokens.normalize(batch, table, tokenFileId)
    if (normalized == null) return null to refused
    refusal(normalized)?.let { return null to it }
    val chosen = Tokens.tokensIn(normalized)
    if (chosen.isNotEmpty() && kotlinFile.exists()) {
      // One scope through the whole batch, seeded with the imports the
      // document's tokens already imply (it may be ahead of the file).
      val pending = Tokens.importsIn(doc)
      table.bindingProblem(kotlinFile.readText(), tokenFileId, chosen.toList(), pending)?.let { return null to it }
    }
    return normalized to null
  }

  /**
   * The client-op refusals (null = acceptable):
   *  - the engine's internal ops (undo inverses) are never accepted from clients;
   *  - K1: a project-component instance carries modifiers only when its
   *    signature declares a `modifier: Modifier` parameter;
   *  - K2: a design token on a project-component instance must fit the
   *    parameter's declared type (the document checks catalogued widgets).
   */
  private fun refusal(batch: OpBatch): String? {
    if (batch.ops.any { !it.isClientOp() }) return "internal op not accepted"
    val registry = components()
    fun refuseToken(type: String, key: String, v: PropValue): String? {
      if (v !is PropValue.Token || !registry.isComponent(type)) return null
      val sourceType = propSourceType(type, key, registry)
      return if (tokenFits(sourceType, v.value.toAny())) null
      else "token ${v.ref} does not fit $type.$key (${sourceType ?: "not a scalar parameter"})"
    }
    fun walkTokens(n: DocNode): String? = (n as? DocNode.Widget)?.let { w ->
      w.props.entries.firstNotNullOfOrNull { (k, v) -> refuseToken(w.type, k, v) }
        ?: w.children.firstNotNullOfOrNull(::walkTokens)
    }
    for (op in batch.ops) {
      when (op) {
        is DocOp.SetProp -> (doc.find(op.target) as? DocNode.Widget)?.let { w -> refuseToken(w.type, op.name, op.value)?.let { return it } }
        is DocOp.InsertNode -> walkTokens(op.node)?.let { return it }
        else -> {}
      }
    }
    fun refuseMods(type: String, hasMods: Boolean): String? {
      if (!hasMods || !registry.isComponent(type)) return null
      return if (registry.spec(type)?.acceptsModifier == true) null
      else "component $type declares no `modifier: Modifier` parameter"
    }
    fun walk(n: DocNode): String? = (n as? DocNode.Widget)?.let { w ->
      refuseMods(w.type, w.modifiers.isNotEmpty()) ?: w.children.firstNotNullOfOrNull(::walk)
    }
    for (op in batch.ops) {
      when (op) {
        is DocOp.SetModifier -> (doc.find(op.target) as? DocNode.Widget)?.let { w -> refuseMods(w.type, true)?.let { return it } }
        is DocOp.InsertNode -> walk(op.node)?.let { return it }
        else -> {}
      }
    }
    return null
  }

  /** M2: validate + report WITHOUT committing — the agent's safe exploration path. */
  @Synchronized
  fun dryRun(batch: OpBatch): OpAck {
    if (batch.baseVersion != doc.version) {
      return OpAck(false, doc.version, "stale base version ${batch.baseVersion}, document is at ${doc.version}")
    }
    val (gated, refused) = gate(batch)
    if (gated == null) return OpAck(false, doc.version, refused)
    val res = doc.applyBatch(gated.ops)
    return if (res.result == null) OpAck(false, doc.version, res.error) else OpAck(true, doc.version)
  }

  @Synchronized
  fun submit(batch: OpBatch): OpAck {
    if (batch.baseVersion != doc.version) {
      return OpAck(false, doc.version, "stale base version ${batch.baseVersion}, document is at ${doc.version}")
    }
    val (gated, refused) = gate(batch)
    if (gated == null) return OpAck(false, doc.version, refused)
    val res = doc.applyBatch(gated.ops)
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
    // Peek. A token refusal keeps the entry (restoring the declaration makes
    // it work again); an inverse the tree can no longer take is dropped, as
    // before K2 — it never could apply, and keeping it would wedge the stack.
    val stack = undoStacks[session]
    val inverses = stack?.lastOrNull()
      ?: return OpAck(false, doc.version, "nothing to undo")
    val res = doc.applyBatch(inverses)
    val ok = res.result ?: run {
      stack.removeLastOrNull()
      return OpAck(false, doc.version, "undo failed: ${res.error}")
    }
    // K2: an inverse carries token values from when it was recorded; re-resolve
    // the tokens it restores (and only those).
    val (fresh, stale) = Tokens.refresh(doc, ok.doc, tokens(), tokenFileId, components())
    if (fresh == null) return OpAck(false, doc.version, "undo failed: $stale")
    stack.removeLastOrNull()
    redoStacks.getOrPut(session) { ArrayDeque() }.addLast(ok.inverseBatch)
    doc = fresh.copy(version = doc.version + 1)
    changed()
    return OpAck(true, doc.version)
  }

  @Synchronized
  fun redo(session: String): OpAck {
    val stack = redoStacks[session]
    val inverses = stack?.lastOrNull()
      ?: return OpAck(false, doc.version, "nothing to redo")
    val res = doc.applyBatch(inverses)
    val ok = res.result ?: run {
      stack.removeLastOrNull()
      return OpAck(false, doc.version, "redo failed: ${res.error}")
    }
    val (fresh, stale) = Tokens.refresh(doc, ok.doc, tokens(), tokenFileId, components())
    if (fresh == null) return OpAck(false, doc.version, "redo failed: $stale")
    stack.removeLastOrNull()
    undoStacks.getOrPut(session) { ArrayDeque() }.addLast(ok.inverseBatch)
    doc = fresh.copy(version = doc.version + 1)
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

  /**
   * K2: re-recognize the file as it is on disk against the CURRENT component
   * and token registries — a declaration it depends on changed although the
   * file did not (so the self-write check must not skip it). A token whose
   * value changed re-resolves; one that vanished or changed kind becomes
   * RawCode. False when a write-back is still pending (the document is ahead
   * of the file — the caller retries after it lands).
   */
  @Synchronized
  fun reingest(): Boolean {
    if (pendingWrite?.isDone == false) return false
    if (!kotlinFile.exists()) return true
    val rec = dev.keliver.portal.ingest.Recognizer.recognize(tokenFileId, kotlinFile.readText(), components(), tokens())
      ?: return true
    val next = dev.keliver.portal.ingest.Reconciler.reconcile(doc, rec)
    if (next.root != doc.root || next.contract != doc.contract) acceptExternal(next)
    return true
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

  /**
   * K2: tell subscribers something they show changed although the document
   * did not (the project's design tokens) — same event, current version.
   */
  fun announce() {
    listeners.removeAll { out -> runCatching { sendEvent(out, doc.version) }.isFailure }
  }

  private fun sendEvent(out: OutputStream, version: Long) {
    out.write("data: {\"version\":$version,\"tokens\":${tokensVersion()}}\n\n".toByteArray())
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
      val registry = components()
      val existing = if (kotlinFile.exists()) kotlinFile.readText() else null
      // C1: component-aware surgical merge so a screen using a component isn't
      // forced into a whole-file regen (its call stays byte-stable in place).
      val surgical = existing?.let { dev.keliver.portal.ingest.WriteBack.merge(it, doc, registry, tokens(), tokenFileId) }
      // P0: the file's OWN package wins over the configured default — a full-
      // export fallback must never rewrite a foreign-package screen (e.g.
      // com.stashfin.*) into dev.keliver.portalpublished.screens.
      val pkgName = existing?.let { PKG_RE.find(it)?.groupValues?.get(1) } ?: packageName
      val pkg = pkgName?.let { "package $it\n\n" } ?: ""
      val merged = surgical ?: (pkg + exportKotlin(doc.toWidgetTree(), functionName = functionName, components = registry))
      // P2b-a: keep the Bindings interface in sync with the tree — new binds
      // gain defaulted, TODO(portal)-marked members so the guest keeps
      // compiling; marker-carrying members that ops un-required are removed.
      val tree = doc.toWidgetTree()
      val text = runCatching {
        dev.keliver.portal.ingest.ContractWriteBack.ensure(merged, tree, functionName, registry)
      }.getOrDefault(merged)
      lastWrittenText = text
      kotlinFile.writeText(text)
      // M9 versioned catch-up: bake the doc version this screen was written at
      // into a sibling const. Rebuilding the dev bundle (serve --continuous)
      // picks it up; the device router compares it to the live doc version.
      pkgName?.let { p ->
        val screen = kotlinFile.nameWithoutExtension
        File(kotlinFile.parentFile, "Compiled_$screen.kt").writeText(
          "package $p\n\n// GENERATED (M9) — the doc version this compiled screen reflects.\nconst val COMPILED_VERSION_$screen: Int = ${doc.version}\n",
        )
      }
    }.onFailure { println("writeKotlin failed for $screenKey: $it") }
  }

  companion object {
    private val PKG_RE = Regex("""^package\s+([\w.]+)""", RegexOption.MULTILINE)

    /** M6: the canonical .kt file bootstraps the document when it exists (file = truth). */
    fun fromFileOrTree(
      screenKey: String,
      treeJson: String?,
      onProjected: (WidgetNode) -> Unit,
      kotlinFile: File,
      functionName: String,
      packageName: String?,
      components: () -> dev.keliver.portal.ComponentRegistry = { dev.keliver.portal.EmptyComponentRegistry },
      tokens: () -> TokenTable = { TokenTable.EMPTY },
      tokenFileId: String = kotlinFile.name,
      tokensVersion: () -> Long = { 0L },
    ): DocumentService {
      if (kotlinFile.exists()) {
        val rec = runCatching {
          dev.keliver.portal.ingest.Recognizer.recognize(tokenFileId, kotlinFile.readText(), components(), tokens())
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
          return DocumentService(screenKey, doc, onProjected, kotlinFile, functionName, packageName, components, tokens, tokenFileId, tokensVersion)
            .also { it.onProjected(doc.toWidgetTree()) } // draft mirror catches up
        }
      }
      return fromTree(screenKey, treeJson, onProjected, kotlinFile, functionName, packageName, components, tokens, tokenFileId, tokensVersion)
    }

    /** M1 bootstrap: lift an existing V1 draft tree into a Document. */
    fun fromTree(
      screenKey: String,
      treeJson: String?,
      onProjected: (WidgetNode) -> Unit,
      kotlinFile: File,
      functionName: String = "PortalScreen",
      packageName: String? = null,
      components: () -> dev.keliver.portal.ComponentRegistry = { dev.keliver.portal.EmptyComponentRegistry },
      tokens: () -> TokenTable = { TokenTable.EMPTY },
      tokenFileId: String = kotlinFile.name,
      tokensVersion: () -> Long = { 0L },
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
              is dev.keliver.portal.Token -> v.toPropValue()
              else -> lit(v)
            }
          },
        modifiers = n.props.filterKeys { it.startsWith("mod.") }
          .map { (k, v) -> k.removePrefix("mod.") to if (v is dev.keliver.portal.Token) v.toPropValue() else lit(v) }.toMap(),
        children = n.children.map { lift(it) },
      )
      val root = treeJson?.takeIf { it.isNotBlank() && it != "{}" }
        ?.let { lift(deserializeTree(it)) }
        ?: DocNode.Widget(Handle(next++), "Column")
      val doc = UiDocument(screenKey, root, Contract(), version = 0, nextHandle = next)
      return DocumentService(screenKey, doc, onProjected, kotlinFile, functionName, packageName, components, tokens, tokenFileId, tokensVersion)
    }
  }
}
