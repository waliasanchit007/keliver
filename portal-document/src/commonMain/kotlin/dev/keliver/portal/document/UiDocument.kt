package dev.keliver.portal.document

import dev.keliver.portal.CondTest
import dev.keliver.portal.ELSE_TYPE
import dev.keliver.portal.LOGIC_NODE_TYPES
import dev.keliver.portal.PropKind
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
 *
 * K3: a conditional value ([PropValue.Conditional]) sits only where each of its
 * branches could ([conditionalMisfit]): never nested, never on a list prop, a
 * `null` or an Action branch only on an event (every catalog event is
 * nullable), at least one branch a value; the condition is a screen binding
 * tested as a Boolean or for String emptiness/blankness (a screen's contract
 * cannot type a nullable field, so no null test). Every op that places a
 * condition — SetProp/SetModifier, and Insert/Move for every condition in the
 * subtree ([conditionsMisfit]) — also refuses: a `Condition` field that is not
 * a plain screen binding name; an item field (`item.flag`) outside a Repeat
 * over that item; a Boolean test on a field the document reads as a List (a
 * Repeat's items) or a String (bound to a non-Boolean prop, or tested for
 * emptiness); `isBlank()` on a Repeat's List. Only the op placing a condition
 * is checked: a later op that changes how the document reads its field
 * (binding it to a text prop, renaming the enclosing Repeat's `item`) is not
 * refused for conditions already placed — the compile reports those. An
 * `Else` node (a `Condition`'s else branch) sits only directly under a
 * `Condition`, at most one per Condition.
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
        conditionalMisfit(op.node)?.let { return err(it) }
        elseMisfit(parent, op.node, moving = null)?.let { return err(it) }
        val (allocated, next) = allocate(op.node, nextHandle)
        val renumbered = sanitize(allocated, childScope(parent), deep = true, stripped = null)
        val idx = if (op.after == null) 0 else parent.children.indexOfFirst { it.handle == op.after } + 1
        val newParent = parent.copy(children = parent.children.toMutableList().apply { add(idx, renumbered) })
        val inserted = replaced(newParent).copy(nextHandle = next)
        conditionsMisfit(inserted.root, renumbered.handle)?.let { return err(it) }
        TryResult(ApplyResult(inserted, DocOp.DeleteNode(renumbered.handle)), null)
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
        elseMisfit(target, node, moving = op.target)?.let { return err(it) }
        // K1: the node's (and, through Condition/Repeat, its children's) scope may change.
        val stripped = LinkedHashMap<Long, Map<String, PropValue>>()
        val moved = sanitize(node, deleted.childScope(target), deep = false, stripped = stripped)
        val res = deleted.apply(InsertExisting(op.newParent, op.after, moved))
        // K3: an item-scoped condition stays inside a Repeat over its item.
        conditionsMisfit(res.doc.root, op.target)?.let { return err(it) }
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
        conditionalMisfit(w.type, op.name, op.value)?.let { return err(it) }
        conditionPropMisfit(w.type, op.name, op.value)?.let { return err(it) }
        val old = w.props[op.name]
        val inv = if (old == null) DocOp.RemoveProp(op.target, op.name) else DocOp.SetProp(op.target, op.name, old)
        val value = if (isDpProp(w.type, op.name)) clampDp(op.value) else op.value
        val set = replaced(w.copy(props = w.props + (op.name to value)))
        conditionMisfitAt(set.root, op.target, if (w.type == "Condition" && op.name in CONDITION_PROPS) null else op.name)?.let { return err(it) }
        TryResult(ApplyResult(set, inv), null)
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
        conditionalMisfit(w.type, "mod.${op.name}", op.value)?.let { return err(it) }
        val old = w.modifiers[op.name]
        val inv = if (old == null) DocOp.RemoveModifier(op.target, op.name) else DocOp.SetModifier(op.target, op.name, old)
        val value = if (isDpProp(w.type, "mod.${op.name}")) clampDp(op.value) else op.value
        val set = replaced(w.copy(modifiers = w.modifiers + (op.name to value)))
        conditionMisfitAt(set.root, op.target, "mod.${op.name}")?.let { return err(it) }
        TryResult(ApplyResult(set, inv), null)
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

private val LOGIC_TYPES = LOGIC_NODE_TYPES

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
  if (v is PropValue.Conditional) {
    return listOfNotNull(v.whenTrue, v.whenFalse).firstNotNullOfOrNull { tokenMisfit(nodeType, key, it) }
  }
  if (v !is PropValue.Token) return null
  if (!key.startsWith("mod.") && widgetSpec(nodeType) == null) return null
  val type = propSourceType(nodeType, key)
  val value = v.value.toAny()
  if (tokenFits(type, value)) return null
  return "token ${v.ref} (${tokenKindOf(value) ?: v.value.tag}) does not fit $nodeType.$key (${type ?: "unknown"})"
}

private val CONDITION_FIELD = Regex("^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)?$")

/**
 * K3: why the conditional value [v] may not sit at wire key [key] on a node
 * of [nodeType], or null when it may (or [v] is not conditional). Keys of a
 * project component's parameters are left to the server, which knows the
 * signature (which parameters are events or nullable).
 */
private fun conditionalMisfit(nodeType: String, key: String, v: PropValue): String? {
  if (v !is PropValue.Conditional) return null
  val where = "$nodeType.$key"
  if (!CONDITION_FIELD.matches(v.field)) return "conditional $where: '${v.field}' is not a field name"
  when (CondTest.fromWire(v.test)) {
    null -> return "conditional $where: unknown test '${v.test}' (none, \"empty\" or \"blank\")"
    CondTest.Null -> return "conditional $where: a null test needs a nullable field, which a screen contract cannot declare"
    else -> {}
  }
  val branches = listOf(v.whenTrue, v.whenFalse)
  if (branches.all { it == null }) return "conditional $where: at least one branch has a value"
  if (branches.any { it is PropValue.Conditional }) return "conditional $where: branches do not nest"
  val spec = widgetSpec(nodeType)
  if (key.startsWith("mod.")) {
    if (branches.any { it == null || it is PropValue.Action }) return "conditional $where: a modifier argument takes a value in both branches"
    return null
  }
  if (spec == null) return null // a component instance: the server checks its signature
  val isEvent = key in spec.events
  if (isEvent) {
    if (branches.any { it != null && it !is PropValue.Action }) return "conditional $where: an event's branches are actions or null"
    return null
  }
  if (branches.any { it == null || it is PropValue.Action }) return "conditional $where: a value prop takes a value in both branches (null fits only an event)"
  val kind = spec.props.firstOrNull { it.name == key }?.kind
  if (kind == PropKind.IntList || kind == PropKind.FloatList || kind == PropKind.StringList) {
    return "conditional $where: a list prop cannot be conditional"
  }
  return null
}

/** [conditionalMisfit] for every prop and modifier of an inserted subtree. */
private fun conditionalMisfit(n: DocNode): String? {
  if (n !is DocNode.Widget) return null
  n.props.forEach { (k, v) -> conditionalMisfit(n.type, k, v)?.let { return it } }
  n.modifiers.forEach { (k, v) -> conditionalMisfit(n.type, "mod.$k", v)?.let { return it } }
  return n.children.firstNotNullOfOrNull { conditionalMisfit(it) }
}

/** K3: a `Condition`'s own `test`/`negate` props take only what its source can spell. */
private fun conditionPropMisfit(nodeType: String, key: String, v: PropValue): String? {
  if (nodeType != "Condition") return null
  return when (key) {
    "test" -> {
      val t = (v as? PropValue.Lit)?.s
      when (if (v is PropValue.Lit && v.tag == "s") CondTest.fromWire(t) else null) {
        CondTest.Bool, CondTest.Empty, CondTest.Blank -> null
        else -> "Condition.test is \"\" (Boolean), \"empty\" or \"blank\""
      }
    }
    "negate" -> if (v is PropValue.Lit && v.tag == "b") null else "Condition.negate is a Boolean"
    // Empty while an editor's field is being retyped (exported as a placeholder until named).
    // Never dotted: `if (item.x) { … }` is not grammar (an item field is read by a conditional value).
    "field" -> if (v is PropValue.Lit && v.tag == "s" && (v.s.isNullOrEmpty() || SCREEN_FIELD.matches(v.s))) null
      else "Condition.field is a screen binding's name (an item field fits only a conditional value)"
    else -> null
  }
}

/**
 * K3: why [node] (or an `Else` inside it) may not go under [parent]: an
 * `Else` sits only directly under a `Condition`, which holds at most one
 * ([moving] = the node being moved, which does not count against its own
 * destination).
 */
private fun elseMisfit(parent: DocNode.Widget, node: DocNode, moving: Handle?): String? {
  if (node is DocNode.Widget && node.type == ELSE_TYPE) {
    if (parent.type != "Condition") return "an Else branch sits only directly under a Condition"
    if (parent.children.any { it is DocNode.Widget && it.type == ELSE_TYPE && it.handle != moving }) {
      return "this Condition already has an Else branch"
    }
  }
  fun inner(n: DocNode): String? {
    if (n !is DocNode.Widget) return null
    n.children.forEach { c ->
      if (c is DocNode.Widget && c.type == ELSE_TYPE && n.type != "Condition") return "an Else branch sits only directly under a Condition"
    }
    if (n.children.count { it is DocNode.Widget && it.type == ELSE_TYPE } > 1) return "a Condition has at most one Else branch"
    return n.children.firstNotNullOfOrNull(::inner)
  }
  return inner(node)
}

private val SCREEN_FIELD = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

/** A `Condition` node's own props — together, its condition. */
private val CONDITION_PROPS = setOf("field", "test", "negate")

/**
 * K3: why a condition somewhere in the subtree at [h] (already placed in
 * [root]) could not compile: a `Condition`'s own props ([conditionPropMisfit]
 * — an inserted node skips SetProp), an item field read outside a Repeat
 * over that item, or a test the field's other uses contradict
 * ([conditionTypeMisfit]). Null = every condition fits.
 */
private fun conditionsMisfit(root: DocNode, h: Handle): String? {
  val top = findIn(root, h) as? DocNode.Widget ?: return null
  fun walk(n: DocNode): String? {
    if (n !is DocNode.Widget) return null
    if (n.type == "Condition") {
      n.props.forEach { (k, v) -> conditionPropMisfit(n.type, k, v)?.let { return it } }
      conditionMisfitAt(root, n.handle, null)?.let { return it }
    }
    (n.props.keys + n.modifiers.keys.map { "mod.$it" }).forEach { k -> conditionMisfitAt(root, n.handle, k)?.let { return it } }
    return n.children.firstNotNullOfOrNull(::walk)
  }
  return walk(top)
}

/**
 * K3: why the condition at node [h] of [root] could not compile — its own
 * condition when [key] is null (a `Condition`), else the conditional value at
 * wire key [key] ("mod.X.p" for a modifier). Null when it fits (or is none).
 */
private fun conditionMisfitAt(root: DocNode, h: Handle, key: String?): String? {
  val w = findIn(root, h) as? DocNode.Widget ?: return null
  val (field, test, where) = if (key == null) {
    val field = (w.props["field"] as? PropValue.Lit)?.s?.takeIf { it.isNotEmpty() } ?: return null
    Triple(field, CondTest.fromWire((w.props["test"] as? PropValue.Lit)?.s) ?: return null, "Condition")
  } else {
    val v = (if (key.startsWith("mod.")) w.modifiers[key.removePrefix("mod.")] else w.props[key]) as? PropValue.Conditional ?: return null
    Triple(v.field, CondTest.fromWire(v.test) ?: return null, "conditional ${w.type}.$key")
  }
  if ('.' in field) {
    val item = field.substringBefore('.')
    return if (item in itemVarsAt(root, h)) null
    else "$where reads `$field` outside a Repeat over `$item` (an item field is read only inside its Repeat)"
  }
  return conditionTypeMisfit(root, field, test, skip = h to key, where = where)
}

/** The item variables in scope at node [h]: each enclosing Repeat's `item`. */
private fun itemVarsAt(root: DocNode, h: Handle): Set<String> {
  val out = HashSet<String>()
  var p = parentOf(root, h)
  while (p != null) {
    if (p.type == "Repeat") out += (p.props["item"] as? PropValue.Lit)?.s?.takeIf { it.isNotBlank() } ?: "item"
    p = parentOf(root, p.handle)
  }
  return out
}

/**
 * K3: a [test] on screen field [field] that its other uses in [root]
 * contradict, where that is cheaply known: a Boolean test on a field the
 * document reads as a List (a Repeat's items) or a String (bound to a text
 * or numeric prop, or tested for emptiness/blankness); `isBlank()` on a List. The use
 * being checked ([skip]: node, key — null = a Condition's own) is not
 * evidence. Fields a project component types are left alone.
 */
private fun conditionTypeMisfit(root: DocNode, field: String, test: CondTest, skip: Pair<Handle, String?>, where: String): String? {
  if (test != CondTest.Bool && test != CondTest.Blank) return null
  var list = false
  var notBoolean = false
  fun cond(f: String, t: CondTest?) {
    if (f == field && t != null && t != CondTest.Bool) notBoolean = true
  }
  fun walk(n: DocNode) {
    if (n !is DocNode.Widget) return
    if (n.type == "Repeat" && (n.props["items"] as? PropValue.Lit)?.s == field) list = true
    if (n.type == "Condition" && skip != (n.handle to null)) {
      cond((n.props["field"] as? PropValue.Lit)?.s.orEmpty(), CondTest.fromWire((n.props["test"] as? PropValue.Lit)?.s))
    }
    val spec = widgetSpec(n.type)
    n.props.forEach { (k, v) ->
      if (skip == (n.handle to k)) return@forEach
      if (v is PropValue.Conditional) cond(v.field, CondTest.fromWire(v.test))
      val kind = spec?.props?.firstOrNull { it.name == k }?.kind
      val binds = if (v is PropValue.Conditional) listOfNotNull(v.whenTrue, v.whenFalse) else listOf(v)
      if (kind != null && kind != PropKind.Bool && binds.any { it is PropValue.Bind && it.field == field }) notBoolean = true
    }
    n.modifiers.forEach { (k, v) -> if (v is PropValue.Conditional && skip != (n.handle to "mod.$k")) cond(v.field, CondTest.fromWire(v.test)) }
    n.children.forEach(::walk)
  }
  walk(root)
  return when {
    test == CondTest.Bool && list -> "$where tests `$field` as a Boolean, but it is a Repeat's List (test it with isEmpty())"
    test == CondTest.Bool && notBoolean -> "$where tests `$field` as a Boolean, but the screen reads it as a String or number elsewhere"
    test == CondTest.Blank && list -> "$where: `$field` is a Repeat's List, which has isEmpty() but no isBlank()"
    else -> null
  }
}

/** [tokenMisfit] for every prop and modifier of an inserted subtree. */
private fun tokenMisfit(n: DocNode): String? {
  if (n !is DocNode.Widget) return null
  n.props.forEach { (k, v) -> tokenMisfit(n.type, k, v)?.let { return it } }
  n.modifiers.forEach { (k, v) -> tokenMisfit(n.type, "mod.$k", v)?.let { return it } }
  return n.children.firstNotNullOfOrNull { tokenMisfit(it) }
}

/** A Dp is never negative: a negative literal becomes 0 (K3: in either branch of a conditional). */
private fun clampDp(v: PropValue): PropValue = when {
  v is PropValue.Conditional -> v.copy(whenTrue = v.whenTrue?.let(::clampDp), whenFalse = v.whenFalse?.let(::clampDp))
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
