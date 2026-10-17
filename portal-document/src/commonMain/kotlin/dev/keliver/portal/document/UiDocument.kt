package dev.keliver.portal.document

import dev.keliver.portal.isDpProp
import dev.keliver.portal.isValidUnder
import dev.keliver.portal.modifierSpecs
import dev.keliver.portal.propSourceType
import dev.keliver.portal.tokenFits
import dev.keliver.portal.tokenKindOf
import dev.keliver.portal.widgetSpec
import kotlinx.serialization.Serializable

/**
 * The live semantic document (design §1-§2). Immutable: every applied op
 * yields a new document + the INVERSE op that undoes it. Handles are stable
 * for the document's lifetime; InsertNode allocates them from [nextHandle]
 * (clients send handle 0 in inserted subtrees).
 *
 * K1 invariants every op keeps (what the editor shows is what is saved, and
 * saved code compiles):
 *  - a scoped layout modifier (flex, margin, …) sits only on a node whose
 *    scope-giving parent (Condition/Repeat looked through) is one of its
 *    scopes: SetModifier refuses one that isn't; Insert/Move strip the ones a
 *    new position invalidates (undo restores them);
 *  - a Dp-holding value (Dp prop, Margin side, Dp modifier arg) is never
 *    negative: SetProp/SetModifier/Insert clamp it at 0.
 *
 * K2: a design-token reference sits only where its resolved kind is the
 * plain literal kind of the prop or modifier argument (an Int token on an Int
 * or Color prop, a String token on a Text prop, …): SetProp/SetModifier/Insert
 * refuse one that does not fit. Component-instance props are checked by the
 * server, which knows the component signatures.
 */
@Serializable
public data class UiDocument(
  val screen: String,
  val root: DocNode,
  val contract: Contract,
  val version: Long,
  val nextHandle: Long,
) {
  public data class ApplyResult(val doc: UiDocument, val inverse: DocOp?)
  public data class TryResult(val result: ApplyResult?, val error: String?)
  public data class BatchResult(val doc: UiDocument, val inverseBatch: List<DocOp>)
  public data class TryBatch(val result: BatchResult?, val error: String?)

  public fun find(h: Handle): DocNode? = findIn(root, h)

  /** Apply or throw — internal replay + tests. */
  public fun apply(op: DocOp): ApplyResult {
    val t = tryApply(op)
    return t.result ?: throw IllegalArgumentException(t.error)
  }

  /** Replay a stored (inverse) batch in order. */
  public fun replay(ops: List<DocOp>): UiDocument {
    var d = this
    ops.forEach { d = d.apply(it).doc }
    return d
  }

  public fun tryApply(op: DocOp): TryResult {
    fun err(m: String) = TryResult(null, m)
    return when (op) {
      is DocOp.InsertNode -> {
        val parent = find(op.parent) as? DocNode.Widget ?: return err("unknown handle ${op.parent.v}")
        if (op.after != null && parent.children.none { it.handle == op.after }) {
          return err("anchor ${op.after.v} is not a child of ${op.parent.v}")
        }
        tokenMisfit(op.node)?.let { return err(it) }
        val (allocated, next) = allocate(op.node, nextHandle)
        val renumbered = sanitize(allocated, childScope(parent), deep = true, stripped = null)
        val idx = if (op.after == null) 0 else parent.children.indexOfFirst { it.handle == op.after } + 1
        val newParent = parent.copy(children = parent.children.toMutableList().apply { add(idx, renumbered) })
        TryResult(ApplyResult(replaced(newParent).copy(nextHandle = next), DocOp.DeleteNode(renumbered.handle)), null)
      }

      is InsertExisting -> { // internal: undo of Delete/Move reinserts with ORIGINAL handles
        val parent = find(op.parent) as? DocNode.Widget ?: return err("unknown handle ${op.parent.v}")
        val idx = if (op.after == null) 0 else parent.children.indexOfFirst { it.handle == op.after } + 1
        val newParent = parent.copy(children = parent.children.toMutableList().apply { add(idx, op.node) })
        TryResult(ApplyResult(replaced(newParent), DocOp.DeleteNode(op.node.handle)), null)
      }

      is DocOp.DeleteNode -> {
        if (op.target == root.handle) return err("cannot delete the root")
        val parent = parentOf(root, op.target) ?: return err("unknown handle ${op.target.v}")
        val idx = parent.children.indexOfFirst { it.handle == op.target }
        val node = parent.children[idx]
        val after = if (idx == 0) null else parent.children[idx - 1].handle
        val newParent = parent.copy(children = parent.children.filterNot { it.handle == op.target })
        TryResult(ApplyResult(replaced(newParent), InsertExisting(parent.handle, after, node)), null)
      }

      is DocOp.MoveNode -> {
        val node = find(op.target) ?: return err("unknown handle ${op.target.v}")
        if (findIn(node, op.newParent) != null) return err("cannot move a node into its own subtree")
        val oldParent = parentOf(root, op.target) ?: return err("cannot move the root")
        val oldIdx = oldParent.children.indexOfFirst { it.handle == op.target }
        val oldAfter = if (oldIdx == 0) null else oldParent.children[oldIdx - 1].handle
        val deleted = apply(DocOp.DeleteNode(op.target)).doc
        val target = deleted.find(op.newParent) as? DocNode.Widget ?: return err("unknown handle ${op.newParent.v}")
        if (op.after != null && target.children.none { it.handle == op.after }) {
          return err("anchor ${op.after.v} is not a child of ${op.newParent.v}")
        }
        // K1: the node's (and, through Condition/Repeat, its children's) scope may change.
        val stripped = LinkedHashMap<Long, Map<String, PropValue>>()
        val moved = sanitize(node, deleted.childScope(target), deep = false, stripped = stripped)
        val res = deleted.apply(InsertExisting(op.newParent, op.after, moved))
        val inverse = if (stripped.isEmpty()) {
          DocOp.MoveNode(op.target, oldParent.handle, oldAfter)
        } else {
          // Undo = move back, then re-apply ONLY the stripped entries onto the
          // LIVE nodes — never a subtree snapshot (another session may have
          // edited or moved those nodes since).
          MoveBack(op.target, oldParent.handle, oldAfter, stripped, modifierOrder(node, stripped.keys))
        }
        TryResult(ApplyResult(res.doc, inverse), null)
      }

      is MoveBack -> {
        val current = parentOf(root, op.target) ?: return err("unknown handle ${op.target.v}")
        val idx = current.children.indexOfFirst { it.handle == op.target }
        val after = if (idx == 0) null else current.children[idx - 1].handle
        val back = tryApply(DocOp.MoveNode(op.target, op.parent, op.after))
        var d = (back.result ?: return back).doc
        for ((h, entries) in op.restore) {
          val w = d.find(Handle(h)) as? DocNode.Widget ?: continue // gone since: skip
          val scope = scopeParentOf(d.root, Handle(h))
          val keep = entries.filter { (key, _) ->
            key !in w.modifiers && // a concurrent edit set it since: theirs wins
              modifierSpecs.firstOrNull { it.name == key.substringBefore('.') }?.isValidUnder(scope) != false
          }
          if (keep.isEmpty()) continue
          // Back in the original (author) order; entries added since keep their place after.
          val merged = w.modifiers + keep
          val order = op.order[h].orEmpty()
          val ordered = LinkedHashMap<String, PropValue>()
          order.forEach { k -> merged[k]?.let { ordered[k] = it } }
          merged.forEach { (k, v) -> if (k !in ordered) ordered[k] = v }
          d = d.replaced(w.copy(modifiers = ordered))
        }
        // Redo = the forward move, which strips again whatever is invalid there.
        TryResult(ApplyResult(d, DocOp.MoveNode(op.target, current.handle, after)), null)
      }

      is DocOp.SetProp -> {
        val w = find(op.target) as? DocNode.Widget ?: return err("unknown handle ${op.target.v}")
        tokenMisfit(w.type, op.name, op.value)?.let { return err(it) }
        val old = w.props[op.name]
        val inv = if (old == null) DocOp.RemoveProp(op.target, op.name) else DocOp.SetProp(op.target, op.name, old)
        val value = if (isDpProp(w.type, op.name)) clampDp(op.value) else op.value
        TryResult(ApplyResult(replaced(w.copy(props = w.props + (op.name to value))), inv), null)
      }

      is DocOp.RemoveProp -> withWidget(op.target) { w ->
        val old = w.props[op.name]
        val inv = if (old == null) null else DocOp.SetProp(op.target, op.name, old)
        ApplyResult(replaced(w.copy(props = w.props - op.name)), inv)
      }

      is DocOp.SetModifier -> {
        val w = find(op.target) as? DocNode.Widget ?: return err("unknown handle ${op.target.v}")
        val spec = modifierSpecs.firstOrNull { it.name == op.name.substringBefore('.') }
        val scope = scopeParentOf(root, op.target)
        if (spec != null && !spec.isValidUnder(scope)) {
          return err("${spec.name} is only valid inside ${spec.scopes.joinToString("/")} (this node's parent is ${scope ?: "none"})")
        }
        tokenMisfit(w.type, "mod.${op.name}", op.value)?.let { return err(it) }
        val old = w.modifiers[op.name]
        val inv = if (old == null) DocOp.RemoveModifier(op.target, op.name) else DocOp.SetModifier(op.target, op.name, old)
        val value = if (isDpProp(w.type, "mod.${op.name}")) clampDp(op.value) else op.value
        TryResult(ApplyResult(replaced(w.copy(modifiers = w.modifiers + (op.name to value))), inv), null)
      }

      is DocOp.RemoveModifier -> withWidget(op.target) { w ->
        val old = w.modifiers[op.name]
        val inv = if (old == null) null else DocOp.SetModifier(op.target, op.name, old)
        ApplyResult(replaced(w.copy(modifiers = w.modifiers - op.name)), inv)
      }

      is DocOp.RenameId -> withWidget(op.target) { w ->
        ApplyResult(replaced(w.copy(explicitId = op.id)), DocOp.RenameId(op.target, w.explicitId))
      }

      is DocOp.ReplaceRaw -> {
        val raw = find(op.target) as? DocNode.RawCode ?: return err("handle ${op.target.v} is not RawCode")
        TryResult(ApplyResult(replaced(raw.copy(text = op.text)), DocOp.ReplaceRaw(op.target, raw.text)), null)
      }

      is DocOp.ContractEdit ->
        TryResult(ApplyResult(copy(contract = op.contract), DocOp.ContractEdit(contract)), null)
    }
  }

  /** All-or-nothing; inverses in reverse order = one undo entry. */
  public fun applyBatch(ops: List<DocOp>): TryBatch {
    var d = this
    val inverses = mutableListOf<DocOp>()
    for (op in ops) {
      val t = d.tryApply(op)
      val r = t.result ?: return TryBatch(null, t.error)
      d = r.doc
      r.inverse?.let { inverses.add(0, it) }
    }
    return TryBatch(BatchResult(d, inverses), null)
  }

  private inline fun withWidget(h: Handle, f: (DocNode.Widget) -> ApplyResult): TryResult {
    val w = find(h) as? DocNode.Widget ?: return TryResult(null, "unknown handle ${h.v}")
    return TryResult(f(w), null)
  }

  internal fun replaced(newNode: DocNode): UiDocument = copy(root = replaceIn(root, newNode))

  /** The scope-giving widget for children of [parent] (Condition/Repeat looked through). */
  private fun childScope(parent: DocNode.Widget): String? =
    if (parent.type in LOGIC_TYPES) scopeParentOf(root, parent.handle) else parent.type
}

/**
 * Internal op: the inverse of a Move that stripped scoped modifiers — move the
 * node back, then re-apply the stripped modifier entries ([restore]: node
 * handle -> entries) onto the nodes that still exist and still qualify. Never
 * accepted from clients.
 */
@Serializable
internal data class MoveBack(
  val target: Handle,
  val parent: Handle,
  val after: Handle?,
  val restore: Map<Long, Map<String, PropValue>>,
  /** node handle -> its modifier keys' original order, to restore author order. */
  val order: Map<Long, List<String>> = emptyMap(),
) : DocOp

private fun modifierOrder(n: DocNode, handles: Set<Long>): Map<Long, List<String>> {
  val out = LinkedHashMap<Long, List<String>>()
  fun walk(x: DocNode) {
    if (x !is DocNode.Widget) return
    if (x.handle.v in handles) out[x.handle.v] = x.modifiers.keys.toList()
    x.children.forEach(::walk)
  }
  walk(n)
  return out
}

/**
 * False for the engine's internal ops (the inverses [InsertExisting] and
 * [MoveBack]): they carry trusted snapshots/handles and are only ever produced
 * by the engine itself. Servers must refuse them from clients — in validation
 * (dry runs) as well as in submission.
 */
public fun DocOp.isClientOp(): Boolean = this !is InsertExisting && this !is MoveBack

private val LOGIC_TYPES = setOf("Condition", "Repeat")

/** The widget whose content lambda holds [h] — Condition/Repeat (if/forEach) looked through. Null at the root. */
private fun scopeParentOf(root: DocNode, h: Handle): String? {
  var p = parentOf(root, h) ?: return null
  while (p.type in LOGIC_TYPES) p = parentOf(root, p.handle) ?: return null
  return p.type
}

/**
 * K1: drop scoped modifiers not valid under [scope] and clamp negative Dp
 * values. Condition/Repeat pass [scope] to their children; [deep] also checks
 * every other descendant against its own parent (a freshly inserted subtree).
 */
private fun sanitize(
  n: DocNode,
  scope: String?,
  deep: Boolean,
  /** Receives node handle -> the modifier entries removed from it. */
  stripped: MutableMap<Long, Map<String, PropValue>>?,
): DocNode {
  if (n !is DocNode.Widget) return n
  val (kept, removed) = n.modifiers.entries.partition { (key, _) ->
    modifierSpecs.firstOrNull { it.name == key.substringBefore('.') }?.isValidUnder(scope) ?: true
  }
  if (removed.isNotEmpty()) stripped?.put(n.handle.v, removed.associate { it.key to it.value })
  val modifiers = kept.associate { it.key to it.value }
    .mapValues { (key, v) -> if (isDpProp(n.type, "mod.$key")) clampDp(v) else v }
  val props = n.props.mapValues { (key, v) -> if (isDpProp(n.type, key)) clampDp(v) else v }
  val children = n.children.map { c ->
    when {
      n.type in LOGIC_TYPES -> sanitize(c, scope, deep, stripped)
      deep -> sanitize(c, n.type, deep, stripped)
      else -> c
    }
  }
  val out = n.copy(props = props, modifiers = modifiers, children = children)
  return if (out == n) n else out
}

/**
 * K2: why a design-token value may not sit at wire key [key] on a node of
 * [nodeType], or null when it may (or [v] is not a token). Keys the catalog
 * does not know — a project component's parameters — are left to the server.
 */
private fun tokenMisfit(nodeType: String, key: String, v: PropValue): String? {
  if (v !is PropValue.Token) return null
  if (!key.startsWith("mod.") && widgetSpec(nodeType) == null) return null
  val type = propSourceType(nodeType, key)
  val value = v.value.toAny()
  if (tokenFits(type, value)) return null
  return "token ${v.ref} (${tokenKindOf(value) ?: v.value.tag}) does not fit $nodeType.$key (${type ?: "unknown"})"
}

/** [tokenMisfit] for every prop and modifier of an inserted subtree. */
private fun tokenMisfit(n: DocNode): String? {
  if (n !is DocNode.Widget) return null
  n.props.forEach { (k, v) -> tokenMisfit(n.type, k, v)?.let { return it } }
  n.modifiers.forEach { (k, v) -> tokenMisfit(n.type, "mod.$k", v)?.let { return it } }
  return n.children.firstNotNullOfOrNull { tokenMisfit(it) }
}

/** A Dp is never negative: a negative literal becomes 0. */
private fun clampDp(v: PropValue): PropValue = when {
  v is PropValue.Lit && (v.d ?: 0.0) < 0.0 -> v.copy(d = 0.0)
  v is PropValue.Lit && (v.i ?: 0) < 0 -> v.copy(i = 0)
  else -> v
}

/**
 * Internal op: reinsert an existing subtree with its ORIGINAL handles — the
 * inverse of Delete/Move. Never accepted from clients (server rejects it).
 */
@Serializable
internal data class InsertExisting(val parent: Handle, val after: Handle?, val node: DocNode) : DocOp

private fun findIn(n: DocNode, h: Handle): DocNode? {
  if (n.handle == h) return n
  if (n is DocNode.Widget) n.children.forEach { c -> findIn(c, h)?.let { return it } }
  return null
}

private fun parentOf(n: DocNode, h: Handle): DocNode.Widget? {
  if (n !is DocNode.Widget) return null
  if (n.children.any { it.handle == h }) return n
  n.children.forEach { c -> parentOf(c, h)?.let { return it } }
  return null
}

private fun replaceIn(n: DocNode, newNode: DocNode): DocNode = when {
  n.handle == newNode.handle -> newNode
  n is DocNode.Widget -> n.copy(children = n.children.map { replaceIn(it, newNode) })
  else -> n
}

/** Renumber an inserted subtree from [next]; returns node + next free handle. */
private fun allocate(n: DocNode, next: Long): Pair<DocNode, Long> = when (n) {
  is DocNode.RawCode -> n.copy(handle = Handle(next)) to next + 1
  is DocNode.Widget -> {
    var cursor = next + 1
    val kids = n.children.map { c -> allocate(c, cursor).also { cursor = it.second }.first }
    n.copy(handle = Handle(next), children = kids) to cursor
  }
}
