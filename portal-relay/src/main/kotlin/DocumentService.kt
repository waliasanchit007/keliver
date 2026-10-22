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
  /** E14: the document is a component's body ([kotlinFile] is its file), not a screen ([forComponent]). */
  private val asComponent: Boolean = false,
) {
  @Volatile var doc: UiDocument = initial
    private set

  /**
   * K3 review S-c: the document the file on disk was last written from or
   * ingested as — its handles are the ones ops target, so write-back can tell
   * an edited node from a new one ([dev.keliver.portal.ingest.WriteBack.merge]'s
   * `base`). Null until known; a base that no longer reads as the file is
   * ignored there.
   */
  @Volatile private var fileDoc: UiDocument? = null

  /**
   * K3 fifth review: the exact text [fileDoc] was read from or written as
   * (null = unknown). The file watcher's read of this same text is not a
   * change (B-1: the writer already read it), and write-back compares the
   * file with it as text before anything else (S-c: a token or component
   * changing does not make the file another file).
   */
  @Volatile private var fileText: String? = null

  /**
   * U37: the text the file was last READ as — never one the relay wrote (null
   * = unknown). Every read but a re-ingest that changes nothing clears the
   * undo and redo stacks, so a field or action this text reads through the
   * bindings parameter is one the file vouched for across every step of
   * them: write-back plans with it ([dev.keliver.portal.ingest.ScreenWriter.plan]'s
   * `readText`), and undoing the delete of a screen's only read of an
   * inherited field is written, not refused.
   */
  @Volatile private var readText: String? = null

  /**
   * K3 last review round: what the last write-back of this screen did —
   * [outcome] `surgical` (in place), `export` (the whole file, which held
   * nothing the export drops), `created`, `refused` (NOT written: it would
   * have lost code; the file is untouched) or `failed` (an error) — for the
   * document [version] it tried to write. Or what replaced it (S4): the file
   * was read (`external`: the document is the file again), or read while the
   * document was ahead of it (`discarded`: that change is gone). Sent on the change stream
   * ([subscribe]) and in `/devstate`; the editor shows a refusal or failure.
   */
  data class WriteBackStatus(val version: Long, val outcome: String, val message: String) {
    /** Whether the change is in the file: not `refused`, `failed` or `discarded`. */
    val written: Boolean get() = outcome != "refused" && outcome != "failed" && outcome != "discarded"

    fun json(): String = "{\"version\":$version,\"outcome\":\"$outcome\",\"written\":$written,\"message\":${jsonString(message)}}"

    private fun jsonString(v: String): String = buildString {
      append('"')
      for (c in v) when {
        c == '"' -> append("\\\"")
        c == '\\' -> append("\\\\")
        c == '\n' -> append("\\n")
        c < ' ' -> append("\\u%04x".format(c.code))
        else -> append(c)
      }
      append('"')
    }
  }

  /** The last write-back's status (null = none yet). */
  @Volatile var writeBack: WriteBackStatus? = null
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
    rawRefusal(normalized)?.let { return null to it }
    // S5: a move that would leave a local declaration's use where it is not declared.
    if (normalized.ops.any { it is DocOp.MoveNode }) {
      doc.applyBatch(normalized.ops).result?.doc?.let { after ->
        dev.keliver.portal.ingest.DeclarationScope.problem(doc, after)?.let { return null to "move refused: $it" }
      }
    }
    val chosen = Tokens.tokensIn(normalized)
    val text = if (chosen.isNotEmpty()) fileTextOrNull() else null
    if (text != null) {
      // One scope through the whole batch, seeded with the imports the
      // document's tokens already imply (it may be ahead of the file).
      val pending = Tokens.importsIn(doc)
      table.bindingProblem(text, tokenFileId, chosen.toList(), pending)?.let { return null to it }
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
    fun refuseLeaf(type: String, key: String, v: PropValue): String? {
      if (v !is PropValue.Token || !registry.isComponent(type)) return null
      val sourceType = propSourceType(type, key, registry)
      return if (tokenFits(sourceType, v.value.toAny())) null
      else "token ${v.ref} does not fit $type.$key (${sourceType ?: "not a scalar parameter"})"
    }
    fun refuseToken(type: String, key: String, v: PropValue): String? =
      conditionalRefusal(registry, type, key, v) ?: templateRefusal(registry, type, key, v)
        ?: arithRefusal(registry, type, key, v)
        ?: scalarLeaves(v).firstNotNullOfOrNull { refuseLeaf(type, key, it) }
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

  /**
   * K3 last review round: a RawCode replacement must stand in the file as
   * exactly that one code statement ([dev.keliver.portal.ingest.RawCodeEdit]):
   * not several statements, no comment outside it, not grammar the editor
   * models. Checked against the document as the batch's earlier ops leave it.
   */
  private fun rawRefusal(batch: OpBatch): String? {
    if (batch.ops.none { it is DocOp.ReplaceRaw }) return null
    val fileText = fileTextOrNull()
    var d = doc
    for (op in batch.ops) {
      if (op is DocOp.ReplaceRaw) {
        dev.keliver.portal.ingest.RawCodeEdit.problem(op.text, d, op.target, fileText, tokenFileId, components(), tokens())
          ?.let { return "code edit refused: $it" }
      }
      d = d.tryApply(op).result?.doc ?: return null // the batch itself fails later, with its own error
    }
    return null
  }

  /**
   * K3: a conditional value on a project-component instance — the document
   * checks catalogued widgets; here the signature decides: an event parameter
   * takes actions, a value parameter takes values; either takes a `null`
   * branch only where its declared type is nullable. Null = acceptable.
   */
  private fun conditionalRefusal(registry: dev.keliver.portal.ComponentRegistry, type: String, key: String, v: PropValue): String? {
    if (v !is PropValue.Conditional) return null
    val spec = registry.spec(type) ?: return null
    val branches = listOf(v.whenTrue, v.whenFalse)
    val nullable = dev.keliver.portal.ingest.isNullableType(spec.paramTypes[key])
    if (spec.events.any { it.name == key }) {
      if (branches.any { it != null && it !is PropValue.Action }) return "conditional $type.$key: an event's branches are actions or null"
      // `onGo = if (b.x) b::go else null` compiles only where onGo is `(() -> Unit)?`.
      if (branches.any { it == null } && !nullable) return "conditional $type.$key: null fits only a nullable event (${spec.paramTypes[key] ?: "undeclared"})"
      return null
    }
    if (branches.any { it is PropValue.Action }) return "conditional $type.$key: an action fits only an event parameter"
    if (branches.any { it == null } && !nullable) return "conditional $type.$key: null fits only a nullable parameter"
    return null
  }

  /**
   * The scalar values of [v] a parameter's type must fit: itself, or a
   * conditional's branches — not a template's token parts (a String, Int or
   * Boolean token prints into the String; the document refuses a Double one)
   * nor a list's elements (refused on a component).
   */
  private fun scalarLeaves(v: PropValue): List<PropValue> = when (v) {
    is PropValue.Conditional -> listOfNotNull(v.whenTrue, v.whenFalse).flatMap(::scalarLeaves)
    is PropValue.Template, is PropValue.ListOf -> emptyList()
    else -> listOf(v)
  }

  /**
   * K4: a template on a project-component instance fits only a parameter
   * declared `String` (the document checks catalogued widgets, and every
   * template's parts); a list with references never fits one (component
   * parameters are scalars). Null = acceptable.
   */
  private fun templateRefusal(registry: dev.keliver.portal.ComponentRegistry, type: String, key: String, v: PropValue): String? {
    if (!registry.isComponent(type)) return null
    val values = if (v is PropValue.Conditional) listOfNotNull(v.whenTrue, v.whenFalse) else listOf(v)
    if (values.any { it is PropValue.ListOf }) return "list $type.$key: a component parameter is never a list"
    if (values.none { it is PropValue.Template }) return null
    val sourceType = propSourceType(type, key, registry)
    return if (sourceType == "String") null else "template $type.$key: a template fits only a String parameter (${sourceType ?: "not a scalar parameter"})"
  }

  /**
   * K7: an arithmetic value on a project-component instance fits only a
   * parameter declared `Int` (or `Int?`: an Int is one) — the document checks
   * catalogued widgets and every expression's shape; an Int used as a Dp
   * never fits one (a component parameter is never a Dp). Null = acceptable.
   */
  private fun arithRefusal(registry: dev.keliver.portal.ComponentRegistry, type: String, key: String, v: PropValue): String? {
    if (!registry.isComponent(type)) return null
    val values = if (v is PropValue.Conditional) listOfNotNull(v.whenTrue, v.whenFalse) else listOf(v)
    if (values.any { it is PropValue.IntDp }) return "arithmetic $type.$key: an Int used as a Dp fits only a widget's Dp prop (a component parameter is never a Dp)"
    if (values.none { it is PropValue.Arith }) return null
    val sourceType = propSourceType(type, key, registry)
    return if (sourceType == "Int") null else "arithmetic $type.$key: an arithmetic value fits only an Int parameter (${sourceType ?: "not a scalar parameter"})"
  }

  /**
   * K4 §7a, the write-back preflight: the refusal (null = accept) of a change
   * that takes the document to [after] — when write-back would refuse to write
   * it. The plan is the one the debounced writer runs ([ScreenWriter.plan],
   * same tables, same base), against the file as it is now, read once; the
   * caller holds this service's lock, as the writer does to snapshot and to
   * write. So an edit the document accepts but the file cannot take (a
   * template on an item field typed Double or nullable — the contract carries
   * no item types; a binding in a screen that takes no bindings parameter; or
   * any reason the writer has) is refused when it is made, and never blocks
   * the screen's later edits.
   *
   * Accepted without a refusal of its own:
   *  - a change that leaves the document as it is or brings it back to the
   *    file (nothing new to write; an undo out of a wedge is never refused);
   *  - [ScreenWriter.Reread]: the file changed since it was last read, so no
   *    plan for it exists yet. The change is accepted as before and the
   *    writer re-reads the file, reporting the change `discarded` (§7a 0) —
   *    reading the file here instead would replace the document the client's
   *    batch was made against, under its feet;
   *  - a document the file cannot take already: the plan for the document as
   *    it is now refuses too, so this change is not why (a change the plan
   *    could not see — the file reformatted between an edit and its write —
   *    or a file that does not read as a screen). The writer reports it as
   *    before; an undo of that change is never refused here. But when the
   *    file on disk is not the text last read ([fileText]), the change is
   *    planned against that text too, and refused if that plan refuses: the
   *    author may save the last-read text back (the watcher skips it as
   *    already read), and a change only that file could have refused would
   *    then stay in the document, refused at every write;
   *  - a file that cannot be read at all here (gone, or not a file, since
   *    `exists()`): as for [ScreenWriter.Reread] — accepted, and the writer
   *    reports what it finds. Never an exception out of the op.
   *
   * The debounced write is unchanged: it plans again when it runs (the file
   * may have changed in between) — those refusals and I/O failures remain.
   * Its plan runs outside the lock, so it may overlap this one: both only
   * read (each parses its own text), and the writer writes under the lock
   * after checking the file is still what it planned against.
   */
  private fun writeBackRefusal(after: UiDocument): String? {
    if (sameDocument(after, doc) || fileDoc?.let { sameDocument(after, it) } == true) return null
    val onDisk = try {
      if (kotlinFile.exists()) kotlinFile.readText() else null
    } catch (e: java.io.IOException) {
      return null // gone or unreadable since exists(): as for a file changed since it was read (above)
    }
    val components = components()
    val tokens = tokens()
    fun refusal(d: UiDocument, file: String?): String? = try {
      when (val plan = dev.keliver.portal.ingest.ScreenWriter.plan(
        onDisk = file, doc = d, base = fileDoc, baseText = fileText, components = components, tokens = tokens,
        fileName = tokenFileId, functionName = functionName, packageName = packageName, readText = readText,
        asComponent = asComponent,
      )) {
        is dev.keliver.portal.ingest.ScreenWriter.Refuse -> plan.reason
        else -> null
      }
    } catch (t: Throwable) {
      if (t is VirtualMachineError) throw t
      "writing it would fail: $t"
    }
    fun refused(reason: String) = "this change could not be written to ${kotlinFile.name} without losing code: $reason — make this change in code"
    val reason = refusal(after, onDisk) ?: return null
    if (refusal(doc, onDisk) == null) return refused(reason)
    // The document as it is refuses too, against the file as it is now. When that
    // file is not the one last read (the author mid-edit: it is not a screen), the
    // author may yet save the exact text last read back — which the watcher skips —
    // so the change must also be writable against that text, or it would stay in
    // the document refused at every write.
    val lastRead = fileText ?: return null
    if (onDisk == lastRead) return null
    return refusal(after, lastRead)?.let(::refused)
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
    val ok = res.result ?: return OpAck(false, doc.version, res.error)
    writeBackRefusal(ok.doc)?.let { return OpAck(false, doc.version, it) }
    return OpAck(true, doc.version)
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
    writeBackRefusal(ok.doc)?.let { return OpAck(false, doc.version, it) }
    doc = ok.doc.copy(version = doc.version + 1)
    undoStacks.getOrPut(batch.envelope.session) { ArrayDeque() }.apply {
      addLast(ok.inverseBatch)
      while (size > 100) removeFirst()
    }
    redoStacks.remove(batch.envelope.session)
    changed()
    return OpAck(true, doc.version)
  }

  /** How many undo and redo steps [session] has, for the editor's Undo/Redo buttons. */
  @Synchronized
  fun stackDepths(session: String): Pair<Int, Int> = (undoStacks[session]?.size ?: 0) to (redoStacks[session]?.size ?: 0)

  @Synchronized
  fun undo(session: String): OpAck = step("undo", undoStacks[session]) { redoStacks.getOrPut(session) { ArrayDeque() } }

  @Synchronized
  fun redo(session: String): OpAck = step("redo", redoStacks[session]) { undoStacks.getOrPut(session) { ArrayDeque() } }

  /**
   * One undo/redo: apply the top of [from] and push its inverse onto [to]
   * (created only when the step applies). Peek first; a refused step leaves
   * the document and the other stack untouched, and is
   *  - KEPT ("blocked (step kept)") when the refusal can clear without an
   *    edit of this file — a token missing or unfit (restore the
   *    declaration), a same-package or star-imported owner declared since
   *    (remove it), a clash with another of the document's tokens (undo it);
   *  - DROPPED ("failed (step dropped)") when it never can: the tree can no
   *    longer take the inverse (as before K2), or the file's OWN imports name
   *    a different owner — surgical write-back only adds imports, so usually
   *    only a hand edit clears that, and it arrives as an external change that
   *    clears both stacks anyway. (A whole-file export can drop an unused
   *    import, so such a step might have applied later: dropping is the
   *    chosen trade against a stack wedged behind it.)
   *  - DROPPED too when write-back would refuse the step (K4 §7a preflight:
   *    [writeBackRefusal]).
   * A dropped step's change is not reversed: the next step of [from] applies
   * on top of it (an older undo over a change that was not undone).
   */
  private fun step(verb: String, from: ArrayDeque<List<DocOp>>?, to: () -> ArrayDeque<List<DocOp>>): OpAck {
    val inverses = from?.lastOrNull()
      ?: return OpAck(false, doc.version, "nothing to $verb")
    fun dropped(why: String?): OpAck {
      from.removeLastOrNull()
      return OpAck(false, doc.version, "$verb failed (step dropped): $why")
    }
    val res = doc.applyBatch(inverses)
    val ok = res.result ?: return dropped(res.error)
    // K2: an inverse carries token values from when it was recorded; re-resolve
    // the tokens it restores (and only those) ...
    val table = tokens()
    val (fresh, stale) = Tokens.refresh(doc, ok.doc, table, tokenFileId, components())
    if (fresh == null) return OpAck(false, doc.version, "$verb blocked (step kept): $stale")
    // ... and check, as the gate checks an edit, that each one still means its
    // declaration in this file: something may have been declared or imported
    // since (scenario: a same-package `C` now beats the star import), or
    // another session may have added a different owner of the same simple
    // name. One scope, seeded with the imports of every token the step did NOT
    // restore — the restored ones are checked, not assumed.
    val onDisk = if (fresh.restored.isNotEmpty()) fileTextOrNull() else null
    if (onDisk != null) {
      table.bindingIssue(onDisk, tokenFileId, fresh.restored, fresh.keptImports)?.let { issue ->
        return if (issue.cause == TokenTable.BindingCause.FILE_IMPORT) dropped(issue.message)
        else OpAck(false, doc.version, "$verb blocked (step kept): ${issue.message}")
      }
    }
    // K4 §7a: a step write-back would refuse is never applied (it would block
    // every later edit of the screen). Dropped, not kept: what refuses it is
    // usually the file's own code, which only an edit of the file changes —
    // and that edit, read, clears both stacks anyway. (A component or token
    // changing back might have let it apply later: dropping is the chosen
    // trade against a stack wedged behind it, as for a FILE_IMPORT clash.)
    writeBackRefusal(fresh.doc)?.let { return dropped(it) }
    from.removeLastOrNull()
    to().addLast(ok.inverseBatch)
    doc = fresh.doc.copy(version = doc.version + 1)
    changed()
    return OpAck(true, doc.version)
  }

  fun subscribe(out: OutputStream) {
    listeners += out
    runCatching { sendEvent(out, doc.version) }
  }

  /**
   * V2 M3: a file-originated document (recognize+reconcile done by the
   * caller). No undo entry, no .kt rewrite — the file IS the new baseline:
   * it is what the next write-back merges onto ([fileDoc]), the self-write
   * text is forgotten (B1: a later file equal to an OLD relay write — an IDE
   * undo — is the author's edit, not ours), and a write still pending is
   * dropped (the document is the file; there is nothing to write).
   *
   * The write-back status says so explicitly (S4): `external` — the file was
   * read, nothing was lost — or `discarded` when the document was ahead of the
   * file (an edit not yet written, or one that could not be written): that
   * change is not in the file, and is now gone from the document too. [why]
   * completes "<file> was re-read (…)".
   */
  @Synchronized
  fun acceptExternal(
    newDoc: dev.keliver.portal.document.UiDocument,
    why: String = "it changed in code",
    /** The text [newDoc] was read from (null = unknown). */
    text: String? = null,
  ) {
    // The document is ahead of the file when it holds what the file last
    // written or read does not — by content, not version (an undo can bring
    // it back) — and the file now read does not hold it either.
    val same = sameDocument(doc, newDoc)
    val ahead = !same && (fileDoc?.let { !sameDocument(doc, it) } ?: (doc.version != 0L))
    val refused = writeBack?.takeIf { it.outcome == "refused" || it.outcome == "failed" }
    // Read as the very document a discard left (B-1): nothing new happened — the discard stays what is told.
    val keep = writeBack?.takeIf { same && it.outcome == "discarded" }
    val name = kotlinFile.name
    doc = newDoc
    fileDoc = newDoc
    fileText = text
    readText = text
    lastWrittenText = null
    pendingWrite?.cancel(false)
    firstRefused = null
    writeBack = if (keep != null) {
      keep
    } else if (!ahead) {
      WriteBackStatus(newDoc.version, "external", "$name was re-read ($why); the editor shows it as it is")
    } else {
      val which = if (refused != null) "the change that could not be written" else "the editor's latest change, not yet written,"
      WriteBackStatus(
        newDoc.version, "discarded",
        "$name was re-read ($why), so $which is not in the file and has been discarded: the editor now shows the file as it is — make the change again if you still want it",
      )
    }
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
  /**
   * The screen file's text for an op-time check, or null when it is missing or
   * cannot be read just now (e.g. replaced mid-save): a check skips, as with no
   * file, and the writer reports what it finds when it runs.
   */
  private fun fileTextOrNull(): String? =
    try {
      if (kotlinFile.isFile) kotlinFile.readText() else null
    } catch (_: java.io.IOException) {
      null
    }

  /** E20: the document's own file, and its text as it is on disk (null = none yet) — what Export shows. */
  val file: File get() = kotlinFile
  fun sourceText(): String? = fileTextOrNull()

  @Synchronized
  fun reingest(): Boolean {
    if (pendingWrite?.isDone == false) return false
    if (!kotlinFile.isFile) return true
    // Unreadable for a moment (an IDE's atomic save): not done, the caller retries.
    val text = fileTextOrNull() ?: return false
    val rec = dev.keliver.portal.ingest.Recognizer.recognize(tokenFileId, text, components(), tokens(), asComponent)
      ?: return true
    val next = dev.keliver.portal.ingest.Reconciler.reconcile(doc, rec)
    if (next.root != doc.root || next.contract != doc.contract) {
      acceptExternal(next, "a component or design token it uses changed", text)
    } else {
      // The same document: the stacks stay, and so does what the last read vouched for (U37).
      fileDoc = doc
      fileText = text
    }
    return true
  }

  /** Self-write suppression for the file watcher. */
  @Volatile private var lastWrittenText: String? = null
  fun wasSelfWrite(text: String): Boolean = text == lastWrittenText

  /** What the file watcher's [fileChanged] made of the file's new text. */
  enum class FileChange { SELF_WRITE, ALREADY_READ, NOT_A_SCREEN, INGESTED }

  /**
   * The file watcher: the screen's file now reads [text]. The relay's own
   * write is skipped, and so is the text the relay last read (B-1: the writer
   * re-read an author's save before the watcher got to it — reading it again
   * would turn the discard it reported into "external"); anything else is
   * read as the document (V2 M3: the file is the source of truth).
   */
  @Synchronized
  fun fileChanged(text: String): FileChange {
    if (wasSelfWrite(text)) return FileChange.SELF_WRITE
    if (text == fileText) return FileChange.ALREADY_READ
    // C1: recognize with the project's component registry so component calls
    // become editable Widget nodes instead of RawCode.
    val recognized = dev.keliver.portal.ingest.Recognizer.recognize(tokenFileId, text, components(), tokens(), asComponent)
      ?: return FileChange.NOT_A_SCREEN
    acceptExternal(dev.keliver.portal.ingest.Reconciler.reconcile(doc, recognized), text = text)
    return FileChange.INGESTED
  }

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
    val wb = writeBack?.let { ",\"writeBack\":${it.json()}" } ?: ""
    out.write("data: {\"version\":$version,\"tokens\":${tokensVersion()}$wb}\n\n".toByteArray())
    out.flush()
  }

  /** M6: create the canonical .kt on first sight of a screen (no change needed). */
  fun ensureKotlinFile() {
    if (!kotlinFile.exists()) writer.schedule({ writeKotlin() }, 100, TimeUnit.MILLISECONDS)
  }

  /**
   * Writes the document to its .kt file — the write-back guarantee
   * ([dev.keliver.portal.ingest.ScreenWriter]): surgically when possible; the
   * full export only when that is lossless; otherwise REFUSED (the file is
   * untouched) and reported. Every outcome, including a failure, becomes the
   * screen's [writeBack] status and is sent to subscribers — never silent.
   */
  private fun writeKotlin() {
    // One snapshot: what is merged, exported and recorded as the file's base is the same document.
    val (doc, base, texts) = synchronized(this) { Triple(this.doc, fileDoc, fileText to readText) }
    val (baseText, readText) = texts
    val status = try {
      kotlinFile.parentFile.mkdirs()
      val onDisk = if (kotlinFile.exists()) kotlinFile.readText() else null
      val plan = dev.keliver.portal.ingest.ScreenWriter.plan(
        onDisk = onDisk, doc = doc, base = base, baseText = baseText, components = components(), tokens = tokens(),
        fileName = tokenFileId, functionName = functionName, packageName = packageName, readText = readText,
        asComponent = asComponent,
      )
      when (plan) {
        is dev.keliver.portal.ingest.ScreenWriter.Refuse -> {
          println("write-back REFUSED for $screenKey (v${doc.version}): ${plan.reason} — ${kotlinFile.name} left as it is")
          refusedStatus(doc.version, plan.reason)
        }
        // B1: the file is not what the relay last wrote or read — never merged onto: read it.
        is dev.keliver.portal.ingest.ScreenWriter.Reread -> reread(plan.reason, base)
        is dev.keliver.portal.ingest.ScreenWriter.Write -> written(plan, doc, base, onDisk)
      }
    } catch (t: Throwable) {
      if (t is VirtualMachineError) throw t
      println("writeKotlin failed for $screenKey: $t")
      WriteBackStatus(doc.version, "failed", "writing ${kotlinFile.name} failed: $t; the change is not in the file")
    }
    if (status != null) {
      writeBack = status
      announce()
    }
  }

  /**
   * S6: a refusal, worded for what it means. The document keeps the change
   * that cannot be written, so every later edit of this screen is refused
   * with it until that change is undone (by the session that made it: undo
   * is per session) or made in code; a later refusal names the version that
   * first failed.
   */
  @Synchronized
  private fun refusedStatus(version: Long, reason: String): WriteBackStatus {
    val first = firstRefused ?: version.also { firstRefused = it }
    val until = "undo it (from the session that made it) or make it in code — until then no edit to this screen is written"
    val name = kotlinFile.name
    return WriteBackStatus(
      version, "refused",
      if (first == version) "this change could not be written to $name without losing code: $reason; $until"
      else "this edit (v$version) is not written to $name either: an earlier change in this screen (v$first) can't be written without losing code — $reason; $until",
    )
  }

  /** The document version whose write-back was first refused since the file last matched the document (null = none). */
  @Volatile private var firstRefused: Long? = null

  /**
   * Writes [plan] — only if, at this moment, the file is still [onDisk] and
   * nothing read it since [base] was taken (else the plan is for a file that
   * is gone: re-read, or let the read that happened stand). Null = no status
   * of its own.
   */
  @Synchronized
  private fun written(
    plan: dev.keliver.portal.ingest.ScreenWriter.Write,
    doc: UiDocument,
    base: UiDocument?,
    onDisk: String?,
  ): WriteBackStatus? {
    if (fileDoc !== base) return null // the file was read meanwhile: that read set the status
    val now = if (kotlinFile.exists()) kotlinFile.readText() else null
    if (now != onDisk) return reread("the file changed while the change was being written", base)
    lastWrittenText = plan.text
    kotlinFile.writeText(plan.text)
    fileDoc = doc
    fileText = plan.text
    firstRefused = null
    val how = plan.how.name.lowercase()
    // E43: a component has no compiled-version stamp (only a screen is routed by one).
    if (asComponent) return WriteBackStatus(doc.version, how, "written to ${kotlinFile.name} ($how)")
    // M9 versioned catch-up: bake the doc version this screen was written at
    // into a sibling const. Rebuilding the dev bundle (serve --continuous)
    // picks it up; the device router compares it to the live doc version.
    val compiled = compiledStamp(kotlinFile)
    // The screen IS written now: a failure below is reported as that, never as "not in the file".
    val versionFailure = runCatching {
      (PKG_RE.find(plan.text)?.groupValues?.get(1) ?: packageName)?.let { p ->
        val screen = kotlinFile.nameWithoutExtension
        val stamp = "package $p\n\n// GENERATED (M9) — the doc version this compiled screen reflects.\nconst val COMPILED_VERSION_$screen: Int = ${doc.version}\n"
        // E43: an unchanged stamp is not rewritten (no churn for a tool watching the tree).
        if (!compiled.isFile || compiled.readText() != stamp) compiled.writeText(stamp)
      }
    }.exceptionOrNull()
    if (versionFailure != null) {
      println("write-back of $screenKey: ${kotlinFile.name} written, ${compiled.name} not: $versionFailure")
      return WriteBackStatus(
        doc.version, how,
        "written to ${kotlinFile.name} ($how); but ${compiled.name} (the version a device build compares) could not be written: $versionFailure",
      )
    }
    return WriteBackStatus(doc.version, how, "written to ${kotlinFile.name} ($how)")
  }

  /**
   * B1: the file is not the document it was last written from or read as
   * ([reason]) — read it now, as the file watcher would ([acceptExternal]: the
   * document becomes the file, and the status says the editor's unwritten
   * change was discarded). Null = the read set the status.
   */
  @Synchronized
  private fun reread(reason: String, base: UiDocument?): WriteBackStatus? {
    if (fileDoc !== base) return null // already read meanwhile
    val text = if (kotlinFile.exists()) kotlinFile.readText() else null
    val rec = text?.let {
      runCatching { dev.keliver.portal.ingest.Recognizer.recognize(tokenFileId, it, components(), tokens(), asComponent) }.getOrNull()
    } ?: return WriteBackStatus(
      doc.version, "refused",
      "this change could not be written to ${kotlinFile.name}: $reason, and it does not read as a screen; it is left as it is",
    )
    println("write-back of $screenKey (v${doc.version}): $reason — ${kotlinFile.name} re-read, not written")
    acceptExternal(dev.keliver.portal.ingest.Reconciler.reconcile(doc, rec), reason, text)
    return null
  }

  private fun sameDocument(a: UiDocument, b: UiDocument) = dev.keliver.portal.ingest.WriteBack.sameDocument(a, b)

  companion object {
    private val PKG_RE = Regex("""^package\s+([\w.]+)""", RegexOption.MULTILINE)
    private val STAMP_RE = Regex("""COMPILED_VERSION_\w+\s*:\s*Int\s*=\s*(\d+)""")

    /** M9: the `Compiled_<screen>.kt` stamp beside a screen file. */
    fun compiledStamp(kotlinFile: File): File = File(kotlinFile.parentFile, "Compiled_${kotlinFile.nameWithoutExtension}.kt")

    /**
     * E43: the version a screen's stamp holds (0 = none). A document read from
     * its file starts there, so its version — and the stamp written from it —
     * never goes back on a new relay or store: a stamp that went back from 4 to 1
     * hid the dev overlay (it shows only while the live version is ahead).
     */
    fun stampedVersion(kotlinFile: File): Long = runCatching {
      compiledStamp(kotlinFile).takeIf { it.isFile }?.readText()?.let { STAMP_RE.find(it)?.groupValues?.get(1)?.toLongOrNull() }
    }.getOrNull() ?: 0L

    /**
     * E14: a component's document, read from its file (a component is never
     * created from a tree, and its file is never rewritten whole). Null when
     * the component's body cannot be read as a document.
     */
    fun forComponent(
      key: String,
      kotlinFile: File,
      functionName: String,
      components: () -> dev.keliver.portal.ComponentRegistry,
      tokens: () -> TokenTable,
      tokenFileId: String,
      tokensVersion: () -> Long,
      onChanged: () -> Unit = {},
    ): DocumentService? {
      val text = kotlinFile.takeIf { it.isFile }?.readText() ?: return null
      val rec = runCatching {
        dev.keliver.portal.ingest.Recognizer.recognize(tokenFileId, text, components(), tokens(), asComponent = true)
      }.getOrNull() ?: return null
      val (root, next) = renumbered(rec.root)
      val doc = UiDocument(key, root, rec.contract, version = 0, nextHandle = next)
      return DocumentService(
        key, doc, { onChanged() }, kotlinFile, functionName, null, components, tokens, tokenFileId, tokensVersion, asComponent = true,
      ).also { it.fileDoc = doc; it.fileText = text; it.readText = text }
    }

    /** [root] with handles 1, 2, 3… in document order, and the next free handle. */
    private fun renumbered(root: DocNode): Pair<DocNode, Long> {
      var next = 1L
      fun renumber(n: DocNode): DocNode = when (n) {
        is DocNode.RawCode -> n.copy(handle = Handle(next++))
        is DocNode.Widget -> n.copy(handle = Handle(next++), children = n.children.map { renumber(it) })
      }
      return renumber(root) to next
    }

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
        val text = kotlinFile.readText()
        val rec = runCatching {
          dev.keliver.portal.ingest.Recognizer.recognize(tokenFileId, text, components(), tokens())
        }.getOrNull()
        if (rec != null) {
          var next = 1L
          fun renumber(n: dev.keliver.portal.document.DocNode): dev.keliver.portal.document.DocNode = when (n) {
            is dev.keliver.portal.document.DocNode.RawCode -> n.copy(handle = Handle(next++))
            is dev.keliver.portal.document.DocNode.Widget ->
              n.copy(handle = Handle(next++), children = n.children.map { renumber(it) })
          }
          val root = renumber(rec.root)
          val doc = UiDocument(screenKey, root, rec.contract, version = stampedVersion(kotlinFile), nextHandle = next)
          return DocumentService(screenKey, doc, onProjected, kotlinFile, functionName, packageName, components, tokens, tokenFileId, tokensVersion)
            .also { it.fileDoc = doc; it.fileText = text; it.readText = text } // the file IS this document
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
              is dev.keliver.portal.Action -> dev.keliver.portal.document.PropValue.Action(v.name)
              else -> dev.keliver.portal.document.treeToPropValue(v)
            }
          },
        modifiers = n.props.filterKeys { it.startsWith("mod.") }
          .map { (k, v) -> k.removePrefix("mod.") to dev.keliver.portal.document.treeToPropValue(v) }.toMap(),
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
