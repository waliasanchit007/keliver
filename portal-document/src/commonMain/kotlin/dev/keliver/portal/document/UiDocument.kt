package dev.keliver.portal.document

import dev.keliver.portal.isDpProp
import dev.keliver.portal.isValidUnder
import dev.keliver.portal.modifierSpecs
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
        val (allocated, next) = allocate(op.node, nextHandle)
        val renumbered = sanitize(allocated, childScope(parent), deep = true)
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
        val moved = sanitize(node, deleted.childScope(target), deep = false)
        val res = deleted.apply(InsertExisting(op.newParent, op.after, moved))
        val inverse = if (moved == node) {
          DocOp.MoveNode(op.target, oldParent.handle, oldAfter)
        } else {
          MoveRestore(op.target, oldParent.handle, oldAfter, node) // put the stripped modifiers back too
        }
        TryResult(ApplyResult(res.doc, inverse), null)
      }

      is MoveRestore -> {
        val current = parentOf(root, op.target) ?: return err("unknown handle ${op.target.v}")
        val idx = current.children.indexOfFirst { it.handle == op.target }
        val after = if (idx == 0) null else current.children[idx - 1].handle
        val deleted = apply(DocOp.DeleteNode(op.target)).doc
        val res = deleted.apply(InsertExisting(op.parent, op.after, op.node))
        TryResult(ApplyResult(res.doc, DocOp.MoveNode(op.target, current.handle, after)), null)
      }

      is DocOp.SetProp -> withWidget(op.target) { w ->
        val old = w.props[op.name]
        val inv = if (old == null) DocOp.RemoveProp(op.target, op.name) else DocOp.SetProp(op.target, op.name, old)
        val value = if (isDpProp(w.type, op.name)) clampDp(op.value) else op.value
        ApplyResult(replaced(w.copy(props = w.props + (op.name to value))), inv)
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

  private fun replaced(newNode: DocNode): UiDocument = copy(root = replaceIn(root, newNode))

  /** The scope-giving widget for children of [parent] (Condition/Repeat looked through). */
  private fun childScope(parent: DocNode.Widget): String? =
    if (parent.type in LOGIC_TYPES) scopeParentOf(root, parent.handle) else parent.type
}

/**
 * Internal op: the inverse of a Move that stripped scoped modifiers — move the
 * node back AND restore its original subtree (original handles). Never
 * accepted from clients.
 */
@Serializable
internal data class MoveRestore(val target: Handle, val parent: Handle, val after: Handle?, val node: DocNode) : DocOp

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
private fun sanitize(n: DocNode, scope: String?, deep: Boolean): DocNode {
  if (n !is DocNode.Widget) return n
  val modifiers = n.modifiers
    .filterKeys { key -> modifierSpecs.firstOrNull { it.name == key.substringBefore('.') }?.isValidUnder(scope) ?: true }
    .mapValues { (key, v) -> if (isDpProp(n.type, "mod.$key")) clampDp(v) else v }
  val props = n.props.mapValues { (key, v) -> if (isDpProp(n.type, key)) clampDp(v) else v }
  val children = n.children.map { c ->
    when {
      n.type in LOGIC_TYPES -> sanitize(c, scope, deep)
      deep -> sanitize(c, n.type, deep)
      else -> c
    }
  }
  val out = n.copy(props = props, modifiers = modifiers, children = children)
  return if (out == n) n else out
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
