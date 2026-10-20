package dev.keliver.portal.document

import dev.keliver.portal.CONDITION_JOIN_PROP
import dev.keliver.portal.CondJoin
import dev.keliver.portal.CondTest
import dev.keliver.portal.conditionKey
import dev.keliver.portal.isConditionKey
import dev.keliver.portal.termTypeMisfit
import dev.keliver.portal.ELSE_TYPE
import dev.keliver.portal.LOGIC_NODE_TYPES
import dev.keliver.portal.PropKind
import dev.keliver.portal.isDpProp
import dev.keliver.portal.isValidUnder
import dev.keliver.portal.listElementType
import dev.keliver.portal.listKindOf
import dev.keliver.portal.modifierSpecs
import dev.keliver.portal.propSourceType
import dev.keliver.portal.templateRefTypeFits
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
 *
 * K4: a template ([PropValue.Template]) sits only on a String prop (a
 * component instance's String parameter: the server checks the signature),
 * also as a conditional's branch; its parts are text, bindings (a plain
 * screen field, or an item field `item.x` inside a Repeat over `item`) and
 * design tokens of a String, Int or Boolean kind. Never a Double token, nor
 * a field the contract declares other than `String`/`Int`/`Long`/`Boolean`
 * (a `Double`/`Float`, a nullable type): the device (Kotlin/JS) prints `1.0`
 * as `1` and a null as `null`, the preview `1.0` and nothing, so no preview
 * of one is faithful ([dev.keliver.portal.templateRefTypeFits]). A list with
 * references ([PropValue.ListOf]) sits only on a `List<Int>`/`List<String>`
 * prop of a catalogued widget, its tag that prop's, each element a literal
 * or a token of the element kind or a binding (a field the contract
 * declares is declared as the element type) — never conditional. Every op
 * stores both in canonical form ([canonicalValue]); an item reference
 * outside a Repeat over its item is refused where it is placed (as for K3).
 * A field a template or a list reads is not a Boolean unless the contract
 * declares it `Boolean` (a template prints one): otherwise a Boolean test on
 * it is refused (as for a field bound to a text prop), and — unlike a plain
 * binding — so is placing a template or a list that reads a field the
 * screen already tests as a Boolean. A field a Repeat iterates (a List) is
 * read by no template, no list element and no binding on a prop that is not
 * a List — refused both ways round. A field whose type the contract cannot
 * give is no template's reference ([unreadableWhy]): one it types
 * `<unknown>` (declared with no type), and one it does not list when
 * [Contract.unlistedFieldsUnknown] says an unlisted field's type is not
 * readable in the screen's file (no Bindings parameter, an interface not in
 * the file or extending one declared elsewhere) — refused where it is placed
 * (set, insert, a conditional's branch, an undo reinserting it), because
 * ingest reads such a template as RawCode and write-back would refuse it,
 * blocking every later edit of the screen. A plain binding of such a field
 * and a list element of it are allowed (nothing is printed: the compile
 * types them, and ingest reads them back). Not checked here: an item field's
 * type (the contract does not carry item types) — write-back refuses such an
 * edit (the file would not read back as the document).
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
        refValueMisfit(op.node, contract)?.let { return err(it) }
        elseMisfit(parent, op.node, moving = null)?.let { return err(it) }
        val (allocated, next) = allocate(op.node, nextHandle)
        val renumbered = sanitize(allocated, childScope(parent), deep = true, stripped = null)
        val idx = if (op.after == null) 0 else parent.children.indexOfFirst { it.handle == op.after } + 1
        val newParent = parent.copy(children = parent.children.toMutableList().apply { add(idx, renumbered) })
        val inserted = replaced(newParent).copy(nextHandle = next)
        conditionsMisfit(inserted.root, renumbered.handle, contract.fields)?.let { return err(it) }
        insertedEqualityMisfit(inserted.root, renumbered.handle, contract)?.let { return err(it) }
        TryResult(ApplyResult(inserted, DocOp.DeleteNode(renumbered.handle)), null)
      }

      is InsertExisting -> { // internal: undo of Delete/Move reinserts with ORIGINAL handles
        val parent = find(op.parent) as? DocNode.Widget ?: return err("unknown handle ${op.parent.v}")
        // K4: a template the contract can no longer type would not be written either.
        unreadableRefMisfit(op.node, contract)?.let { return err(it) }
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
        conditionsMisfit(res.doc.root, op.target, contract.fields)?.let { return err(it) }
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
        val canonical = canonicalValue(op.value)
        tokenMisfit(w.type, op.name, canonical)?.let { return err(it) }
        conditionalMisfit(w.type, op.name, canonical)?.let { return err(it) }
        conditionPropMisfit(w.type, op.name, canonical)?.let { return err(it) }
        conditionPropOpMisfit(w, op.name, canonical)?.let { return err(it) }
        refValueMisfit(w.type, op.name, canonical, contract)?.let { return err(it) }
        val old = w.props[op.name]
        val inv = if (old == null) DocOp.RemoveProp(op.target, op.name) else DocOp.SetProp(op.target, op.name, old)
        val value = if (isDpProp(w.type, op.name)) clampDp(canonical) else canonical
        val set = replaced(w.copy(props = w.props + (op.name to value)))
        val condKey = if (w.type == "Condition" && isConditionKey(op.name)) null else op.name
        conditionMisfitAt(set.root, op.target, condKey, contract.fields)?.let { return err(it) }
        // K6 review: an equality on a field whose type the document cannot read follows a comparison it has.
        val previous = if (condKey == null) w.conditionTerms() else (old as? PropValue.Conditional)?.docTerms().orEmpty()
        unreadableEqualityMisfit(set.root, op.target, condKey, previous, contract)?.let { return err(it) }
        itemRefMisfitAt(set.root, op.target, op.name)?.let { return err(it) }
        refBooleanMisfitAt(set.root, op.target, op.name, contract.fields)?.let { return err(it) }
        repeatListMisfitAt(set.root, op.target, op.name)?.let { return err(it) }
        TryResult(ApplyResult(set, inv), null)
      }

      is DocOp.RemoveProp -> {
        val w = find(op.target) as? DocNode.Widget ?: return err("unknown handle ${op.target.v}")
        conditionPropOpMisfit(w, op.name, null)?.let { return err(it) }
        val old = w.props[op.name]
        val inv = if (old == null) null else DocOp.SetProp(op.target, op.name, old)
        val removed = replaced(w.copy(props = w.props - op.name))
        // K6 review: a Condition with a prop removed is still a condition the field's type takes.
        if (w.type == "Condition" && isConditionKey(op.name)) conditionMisfitAt(removed.root, op.target, null, contract.fields)?.let { return err(it) }
        TryResult(ApplyResult(removed, inv), null)
      }

      is DocOp.SetModifier -> {
        val w = find(op.target) as? DocNode.Widget ?: return err("unknown handle ${op.target.v}")
        val spec = modifierSpecs.firstOrNull { it.name == op.name.substringBefore('.') }
        val scope = scopeParentOf(root, op.target)
        if (spec != null && !spec.isValidUnder(scope)) {
          return err("${spec.name} is only valid inside ${spec.scopes.joinToString("/")} (this node's parent is ${scope ?: "none"})")
        }
        val canonical = canonicalValue(op.value)
        tokenMisfit(w.type, "mod.${op.name}", canonical)?.let { return err(it) }
        conditionalMisfit(w.type, "mod.${op.name}", canonical)?.let { return err(it) }
        refValueMisfit(w.type, "mod.${op.name}", canonical, contract)?.let { return err(it) }
        val old = w.modifiers[op.name]
        val inv = if (old == null) DocOp.RemoveModifier(op.target, op.name) else DocOp.SetModifier(op.target, op.name, old)
        val value = if (isDpProp(w.type, "mod.${op.name}")) clampDp(canonical) else canonical
        val set = replaced(w.copy(modifiers = w.modifiers + (op.name to value)))
        conditionMisfitAt(set.root, op.target, "mod.${op.name}", contract.fields)?.let { return err(it) }
        unreadableEqualityMisfit(set.root, op.target, "mod.${op.name}", (old as? PropValue.Conditional)?.docTerms().orEmpty(), contract)?.let { return err(it) }
        itemRefMisfitAt(set.root, op.target, "mod.${op.name}")?.let { return err(it) }
        repeatListMisfitAt(set.root, op.target, "mod.${op.name}")?.let { return err(it) }
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

      // K4: whether an unlisted field's type is readable is the file's to say, not an op's.
      is DocOp.ContractEdit ->
        TryResult(ApplyResult(copy(contract = op.contract.copy(unlistedFieldsUnknown = contract.unlistedFieldsUnknown)), DocOp.ContractEdit(contract)), null)
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
  val modifiers = kept.associate { it.key to canonicalValue(it.value) }
    .mapValues { (key, v) -> if (isDpProp(n.type, "mod.$key")) clampDp(v) else v }
  val props = n.props.mapValues { (key, v) -> canonicalValue(v).let { c -> if (isDpProp(n.type, key)) clampDp(c) else c } }
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
  // K6: a Condition's equality operand is checked as an operand ([operandMisfit]).
  if (nodeType == "Condition" && isConditionKey(key)) return null
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
  // K6: every term of a compound condition, each as the first one is checked.
  for (t in v.docTerms()) termMisfit(t, "conditional $where")?.let { return it }
  if (CondJoin.fromWire(v.join) == null) return "conditional $where: unknown join '${v.join}' (none for &&, \"or\" for ||)"
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
        CondTest.Bool, CondTest.Empty, CondTest.Blank, CondTest.Eq -> null
        else -> "Condition.test is \"\" (Boolean), \"empty\", \"blank\" or \"eq\""
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

/**
 * K3: why a condition somewhere in the subtree at [h] (already placed in
 * [root]) could not compile: a `Condition`'s own props ([conditionPropMisfit]
 * — an inserted node skips SetProp), an item field read outside a Repeat
 * over that item, or a test the field's other uses contradict
 * ([conditionTypeMisfit]). Null = every condition fits.
 */
private fun conditionsMisfit(root: DocNode, h: Handle, fields: Map<String, String>): String? {
  val top = findIn(root, h) as? DocNode.Widget ?: return null
  fun walk(n: DocNode): String? {
    if (n !is DocNode.Widget) return null
    if (n.type == "Condition") {
      n.props.forEach { (k, v) -> conditionPropMisfit(n.type, k, v)?.let { return it } }
      conditionShapeMisfit(n)?.let { return it }
      conditionMisfitAt(root, n.handle, null, fields)?.let { return it }
    }
    (n.props.keys + n.modifiers.keys.map { "mod.$it" }).forEach { k ->
      conditionMisfitAt(root, n.handle, k, fields)?.let { return it }
      itemRefMisfitAt(root, n.handle, k)?.let { return it }
      refBooleanMisfitAt(root, n.handle, k, fields)?.let { return it }
      repeatListMisfitAt(root, n.handle, k)?.let { return it }
    }
    return n.children.firstNotNullOfOrNull(::walk)
  }
  return walk(top)
}

/**
 * K3: why the condition at node [h] of [root] could not compile — its own
 * condition when [key] is null (a `Condition`), else the conditional value at
 * wire key [key] ("mod.X.p" for a modifier). Null when it fits (or is none).
 */
private fun conditionMisfitAt(root: DocNode, h: Handle, key: String?, fields: Map<String, String>): String? {
  val w = findIn(root, h) as? DocNode.Widget ?: return null
  val (terms, where) = if (key == null) {
    w.conditionTerms().filter { it.field.isNotEmpty() } to "Condition"
  } else {
    val v = (if (key.startsWith("mod.")) w.modifiers[key.removePrefix("mod.")] else w.props[key]) as? PropValue.Conditional ?: return null
    v.docTerms() to "conditional ${w.type}.$key"
  }
  // K6: two terms of one condition must agree on what their field is.
  terms.groupBy { it.field }.forEach { (field, same) ->
    val kinds = same.mapNotNull { termKind(it) }.toSet()
    if (kinds.size > 1) return "$where reads `$field` as ${kinds.sorted().joinToString(" and as ")} (one field has one type)"
  }
  for (t in terms) {
    val test = CondTest.fromWire(t.test) ?: return null
    if ('.' in t.field) {
      val item = t.field.substringBefore('.')
      if (item !in itemVarsAt(root, h)) return "$where reads `${t.field}` outside a Repeat over `$item` (an item field is read only inside its Repeat)"
      continue
    }
    conditionTypeMisfit(root, t.field, test, operandKind(t.operand), skip = h to key, where = where, fields = fields)?.let { return it }
  }
  return null
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
 * evidence. Fields a project component types are left alone. K4: a template
 * reading the field is evidence only when the contract ([fields]) does not
 * declare it `Boolean` — a template prints a declared Boolean.
 */
private fun conditionTypeMisfit(
  root: DocNode,
  field: String,
  test: CondTest,
  operand: String?,
  skip: Pair<Handle, String?>,
  where: String,
  fields: Map<String, String>,
): String? {
  // K6: an equality with a Boolean is a Boolean test; with a String or an Int, a test of that type.
  val boolean = test == CondTest.Bool || (test == CondTest.Eq && operand == "Boolean")
  val eqType = operand.takeIf { test == CondTest.Eq && it != "Boolean" }
  if (!boolean && eqType == null && test != CondTest.Blank && test != CondTest.Empty) return null
  var list = false
  var notBoolean = false
  var testedBoolean = false
  var testedText = false
  val eqTypes = LinkedHashSet<String>()
  val boundKinds = LinkedHashSet<PropKind>()
  fun cond(terms: List<DocTerm>) {
    for (t in terms) {
      if (t.field != field) continue
      val tt = CondTest.fromWire(t.test) ?: continue
      when {
        tt == CondTest.Bool -> testedBoolean = true
        tt == CondTest.Eq -> operandKind(t.operand)?.let { k -> if (k == "Boolean") testedBoolean = true else { eqTypes += k; notBoolean = true } }
        else -> { notBoolean = true; if (tt == CondTest.Empty || tt == CondTest.Blank) testedText = true }
      }
    }
  }
  fun walk(n: DocNode) {
    if (n !is DocNode.Widget) return
    if (n.type == "Repeat" && (n.props["items"] as? PropValue.Lit)?.s == field) list = true
    if (n.type == "Condition" && skip != (n.handle to null)) cond(n.conditionTerms())
    val spec = widgetSpec(n.type)
    n.props.forEach { (k, v) ->
      if (skip == (n.handle to k)) return@forEach
      if (n.type == "Condition" && isConditionKey(k)) return@forEach
      // K4: a template prints its fields into text and a list holds Ints or Strings: neither is
      // a Boolean the contract does not declare (a list never binds a declared Boolean).
      if (field in refFields(v) && !isBooleanType(fields[field])) notBoolean = true
      if (v is PropValue.Conditional) cond(v.docTerms())
      val kind = spec?.props?.firstOrNull { it.name == k }?.kind
      val binds = if (v is PropValue.Conditional) listOfNotNull(v.whenTrue, v.whenFalse) else listOf(v)
      if (kind != null && binds.any { it is PropValue.Bind && it.field == field }) {
        boundKinds += kind
        if (kind != PropKind.Bool) notBoolean = true
      }
    }
    n.modifiers.forEach { (k, v) -> if (v is PropValue.Conditional && skip != (n.handle to "mod.$k")) cond(v.docTerms()) }
    n.children.forEach(::walk)
  }
  walk(root)
  val declared = fields[field]?.replace(" ", "")?.removePrefix("kotlin.")?.takeIf { it != UNKNOWN_TYPE }
  return when {
    // K6 review: the declared type decides first — K3 single terms too.
    // Only a type KNOWN not to fit (termTypeMisfit), or a nullable one (a Bindings property is never smart-cast).
    boolean && declared != null && (declared.endsWith("?") || termTypeMisfit(CondTest.Bool, declared) != null) ->
      "$where tests `$field` as a Boolean, but the Bindings interface declares it $declared"
    (test == CondTest.Empty || test == CondTest.Blank) && declared != null && (declared.endsWith("?") || termTypeMisfit(test, declared) != null) ->
      "$where tests `$field` with ${if (test == CondTest.Empty) "isEmpty()" else "isBlank()"}, but the Bindings interface declares it $declared"
    boolean && list -> "$where tests `$field` as a Boolean, but it is a Repeat's List (test it with isEmpty())"
    boolean && notBoolean -> "$where tests `$field` as a Boolean, but the screen reads it as a String or number elsewhere (a prop, a template, a list element or another test)"
    test == CondTest.Blank && list -> "$where: `$field` is a Repeat's List, which has isEmpty() but no isBlank()"
    (test == CondTest.Empty || test == CondTest.Blank) && (testedBoolean || "Int" in eqTypes) ->
      "$where tests `$field` as a String, but the screen tests it as a ${if (testedBoolean) "Boolean" else "Int"} elsewhere"
    eqType != null && declared != null && declared != eqType -> "$where compares `$field` with $eqType, but the Bindings interface declares it $declared"
    eqType != null && list -> "$where compares `$field` with $eqType, but it is a Repeat's List"
    eqType != null && testedBoolean -> "$where compares `$field` with $eqType, but the screen tests it as a Boolean elsewhere"
    eqType != null && (eqTypes - eqType).isNotEmpty() -> "$where compares `$field` with $eqType, but the screen compares it with ${(eqTypes - eqType).first()} elsewhere"
    eqType == "Int" && testedText -> "$where compares `$field` with Int, but the screen tests it as a String elsewhere (isEmpty()/isBlank())"
    eqType != null && boundKinds.any { kotlinTypeOfKind(it) != eqType } ->
      "$where compares `$field` with $eqType, but the screen binds it to a ${kotlinTypeOfKind(boundKinds.first { kotlinTypeOfKind(it) != eqType })} prop elsewhere"
    else -> null
  }
}

/** The Kotlin type a field bound to a prop of [kind] is declared with (a Color is an Int). */
private fun kotlinTypeOfKind(kind: PropKind): String = dev.keliver.portal.kotlinTypeOf(kind)

/**
 * K6: one term of a condition as the document holds it — the first term of a
 * [PropValue.Conditional] or a `Condition` node, or one of its further terms.
 */
internal data class DocTerm(val field: String, val test: String?, val negate: Boolean, val operand: PropValue?)

/** K6: every term of a conditional value, in source order. */
internal fun PropValue.Conditional.docTerms(): List<DocTerm> =
  listOf(DocTerm(field, test, negate, operand)) + more.map { DocTerm(it.field, it.test, it.negate, it.operand) }

/** K6: every term of a `Condition` node (its condition props, [conditionKey]), in index order. */
internal fun DocNode.Widget.conditionTerms(): List<DocTerm> {
  val out = ArrayList<DocTerm>()
  var i = 0
  while (i == 0 || conditionKey("field", i) in props) {
    out += DocTerm(
      (props[conditionKey("field", i)] as? PropValue.Lit)?.s.orEmpty(),
      (props[conditionKey("test", i)] as? PropValue.Lit)?.s,
      (props[conditionKey("negate", i)] as? PropValue.Lit)?.b == true,
      props[conditionKey("operand", i)],
    )
    i++
  }
  return out
}

/** K6: the Kotlin type of an equality operand — "String", "Int" or "Boolean"; null for anything else. */
private fun operandKind(op: PropValue?): String? = when (op) {
  is PropValue.Lit -> when (op.tag) { "s" -> "String"; "i" -> "Int"; "b" -> "Boolean"; else -> null }
  is PropValue.Token -> operandKind(op.value)
  else -> null
}

/** K6: the type a term reads its field as — an equality's operand's, "Boolean", or "String" (emptiness); null when unknown. */
private fun termKind(t: DocTerm): String? = when (CondTest.fromWire(t.test)) {
  CondTest.Bool -> "Boolean"
  CondTest.Eq -> operandKind(t.operand)
  CondTest.Empty, CondTest.Blank -> null // a String, or a Repeat's List
  else -> null
}

/**
 * K6: why one term may not stand in a condition [where]: a field that is not
 * a field name, an unknown test, a null test (a screen contract cannot type
 * a nullable field), an equality without a fitting operand, or an operand on
 * any other test. Null = it may.
 */
private fun termMisfit(t: DocTerm, where: String): String? {
  if (!CONDITION_FIELD.matches(t.field)) return "$where: '${t.field}' is not a field name"
  return when (CondTest.fromWire(t.test)) {
    null -> "$where: unknown test '${t.test}' (none, \"empty\", \"blank\" or \"eq\")"
    CondTest.Null -> "$where: a null test needs a nullable field, which a screen contract cannot declare"
    CondTest.Eq -> operandMisfit(t.operand)?.let { "$where: $it" }
    else -> if (t.operand != null) "$where: only an equality (test \"eq\") has an operand" else null
  }
}

/**
 * K6: why [op] cannot be an equality's operand — it is a String, Int or
 * Boolean literal, or a design token of one of those kinds. Never a Double
 * (the device and the preview do not compare one alike) nor `Int.MIN_VALUE`
 * as a literal (`-2147483648` is a Long in Kotlin source). Null = it can.
 */
private fun operandMisfit(op: PropValue?): String? = when {
  op == null -> "an equality (test \"eq\") needs an operand"
  op is PropValue.Lit && op.tag == "i" && op.i == Int.MIN_VALUE -> "-2147483648 is a Long in Kotlin source, not an Int operand"
  op is PropValue.Lit && op.tag in OPERAND_TAGS && (op.tag != "i" || op.i != null) -> null
  op is PropValue.Token && op.value.tag in OPERAND_TAGS -> null
  else -> "an equality compares with a String, Int or Boolean literal or design token (never a Double, a binding or an expression)"
}

private val OPERAND_TAGS = setOf("s", "i", "b")

/**
 * K6: why a `Condition` node's condition props, as a whole, are not one
 * condition source can spell: its terms numbered 0, 1, 2, … with nothing
 * after the last ([conditionKey]), every further term naming a field, an
 * equality's operand present (and only an equality's), a join only on two or
 * more terms. Null = they are (or the node is no Condition).
 */
private fun conditionShapeMisfit(n: DocNode.Widget): String? {
  if (n.type != "Condition") return null
  val terms = n.conditionTerms()
  for ((k, v) in n.props) {
    if (!isConditionKey(k)) continue
    when (k.substringBefore('.')) {
      "negate" -> if (v !is PropValue.Lit || v.tag != "b") return "Condition.$k is a Boolean"
      "field", "test", CONDITION_JOIN_PROP -> if (v !is PropValue.Lit || v.tag != "s") return "Condition.$k is a String"
    }
    if ('.' !in k) continue
    val i = k.substringAfter('.').toInt()
    if (i >= terms.size) return "Condition.$k belongs to no term (a term ${i} needs `${conditionKey("field", i)}`, and every term before it)"
  }
  terms.forEachIndexed { i, t ->
    if (i > 0 && t.field.isEmpty()) return "Condition term $i needs a field"
    if (i > 0 && !SCREEN_FIELD.matches(t.field)) return "Condition.${conditionKey("field", i)} is a screen binding's name"
    if (t.field.isNotEmpty() || i > 0) termMisfit(t, "Condition")?.let { return it }
  }
  val join = (n.props[CONDITION_JOIN_PROP] as? PropValue.Lit)?.s
  if (CONDITION_JOIN_PROP in n.props) {
    if (terms.size < 2) return "Condition.join joins two or more terms"
    if (join == null || CondJoin.fromWire(join) == null) return "Condition.join is \"or\" (||), or absent (&&)"
  }
  return null
}

/**
 * K6 review: why an equality term of the condition at node [h] (its own when
 * [key] is null, else the conditional value at [key]) of [root] compares a
 * field whose type the document cannot read — an item field (the contract
 * carries no item types), a field declared without a type, or an unlisted
 * field when the screen's interface is not read whole
 * ([Contract.unlistedFieldsUnknown]) — with a kind nothing vouches for. As K4
 * refuses a template on such a field, such an equality is made in code:
 * `b.kind == 1` would not compile for a `String`, and ingest cannot tell. It
 * may when the condition there before ([previous]) or another condition in
 * the screen already compares that field with the same kind. Null = fine.
 */
private fun unreadableEqualityMisfit(
  root: DocNode,
  h: Handle,
  key: String?,
  previous: List<DocTerm>,
  contract: Contract,
  /** A subtree being inserted: none of its conditions is evidence for another (it would vouch for itself). */
  excluded: Handle? = null,
): String? {
  val w = findIn(root, h) as? DocNode.Widget ?: return null
  val terms = if (key == null) {
    if (w.type != "Condition") return null
    w.conditionTerms()
  } else {
    ((if (key.startsWith("mod.")) w.modifiers[key.removePrefix("mod.")] else w.props[key]) as? PropValue.Conditional)?.docTerms() ?: return null
  }
  for (t in terms) {
    if (CondTest.fromWire(t.test) != CondTest.Eq || typeReadable(t.field, contract)) continue
    val kind = operandKind(t.operand) ?: continue
    // An item field is the same field only inside a Repeat over the same list (its item type).
    val scope = itemScopeOf(root, h, t.field)
    fun vouches(o: DocTerm) = o.field == t.field && CondTest.fromWire(o.test) == CondTest.Eq && operandKind(o.operand) == kind
    if (previous.any(::vouches) || otherEqualities(root, h to key, excluded).any { (o, s) -> s == scope && vouches(o) }) continue
    return "an equality compares `${t.field}` with ${if (kind == "Int") "an" else "a"} $kind, but the type of `${t.field}` is not readable here " +
      "(${if ('.' in t.field) "an item field's type is not in the contract" else unreadableReason(t.field, contract)}) and nothing in the screen " +
      "compares it with ${if (kind == "Int") "an" else "a"} $kind: make this change in code"
  }
  return null
}

/** K6 review: [unreadableEqualityMisfit] for every condition of an inserted subtree at [h] (none was there before). */
private fun insertedEqualityMisfit(root: DocNode, h: Handle, contract: Contract): String? {
  val top = findIn(root, h) as? DocNode.Widget ?: return null
  fun walk(n: DocNode): String? {
    if (n !is DocNode.Widget) return null
    if (n.type == "Condition") unreadableEqualityMisfit(root, n.handle, null, emptyList(), contract, excluded = h)?.let { return it }
    (n.props.keys + n.modifiers.keys.map { "mod.$it" }).forEach { k ->
      unreadableEqualityMisfit(root, n.handle, k, emptyList(), contract, excluded = h)?.let { return it }
    }
    return n.children.firstNotNullOfOrNull(::walk)
  }
  return walk(top)
}

/**
 * Every condition term in [root] except the condition at [skip] (node, key —
 * null = a Condition's own) and any inside the subtree [excluded], each with
 * its item scope: the list field of the Repeat binding an item field's item
 * variable (null for a screen field).
 */
private fun otherEqualities(root: DocNode, skip: Pair<Handle, String?>, excluded: Handle?): List<Pair<DocTerm, String?>> {
  val out = ArrayList<Pair<DocTerm, String?>>()
  fun walk(n: DocNode, items: Map<String, String>) {
    if (n !is DocNode.Widget || n.handle == excluded) return
    fun add(terms: List<DocTerm>) = terms.forEach { t -> out += t to (if ('.' in t.field) items[t.field.substringBefore('.')] else null) }
    if (n.type == "Condition" && skip != (n.handle to null)) add(n.conditionTerms())
    n.props.forEach { (k, v) -> if (v is PropValue.Conditional && skip != (n.handle to k) && !(n.type == "Condition" && isConditionKey(k))) add(v.docTerms()) }
    n.modifiers.forEach { (k, v) -> if (v is PropValue.Conditional && skip != (n.handle to "mod.$k")) add(v.docTerms()) }
    val inner = if (n.type == "Repeat") {
      val item = (n.props["item"] as? PropValue.Lit)?.s?.takeIf { it.isNotBlank() } ?: "item"
      val list = (n.props["items"] as? PropValue.Lit)?.s.orEmpty()
      items + (item to list)
    } else items
    n.children.forEach { walk(it, inner) }
  }
  walk(root, emptyMap())
  return out
}

/** The list field of the nearest Repeat around [h] binding [field]'s item variable — null for a screen field (or none in scope). */
private fun itemScopeOf(root: DocNode, h: Handle, field: String): String? {
  if ('.' !in field) return null
  val item = field.substringBefore('.')
  var p = parentOf(root, h)
  while (p != null) {
    if (p.type == "Repeat" && ((p.props["item"] as? PropValue.Lit)?.s?.takeIf { it.isNotBlank() } ?: "item") == item) {
      return (p.props["items"] as? PropValue.Lit)?.s.orEmpty()
    }
    p = parentOf(root, p.handle)
  }
  return null
}

/** K6 review: whether the document reads [field]'s type — a screen field the contract types, or one it derives. */
private fun typeReadable(field: String, contract: Contract): Boolean = when {
  '.' in field -> false
  contract.fields[field] == UNKNOWN_TYPE -> false
  field !in contract.fields && contract.unlistedFieldsUnknown != null -> false
  else -> true
}

private fun unreadableReason(field: String, contract: Contract): String =
  if (contract.fields[field] == UNKNOWN_TYPE) "`$field` is declared with no type" else contract.unlistedFieldsUnknown ?: "unknown"

/**
 * K6: why an op that sets ([v]) or removes ([v] null) the `Condition` node
 * [w]'s prop [key] would leave a condition source cannot spell. Its first
 * term's field and negation are set as K3 set them; each further term's
 * field and negation, and an equality's operand, may change; but the SHAPE
 * — how many terms, which is an equality, the join — is the code's: a
 * compound or equality condition is placed by inserting a whole `Condition`
 * (or written in code), so no sequence of single ops passes through a
 * condition that could not be written. Null = the op may.
 */
private fun conditionPropOpMisfit(w: DocNode.Widget, key: String, v: PropValue?): String? {
  if (w.type != "Condition" || !isConditionKey(key)) return null
  val terms = w.conditionTerms()
  val name = key.substringBefore('.')
  val index = if (key == CONDITION_JOIN_PROP) -1 else key.substringAfter('.', "0").toInt()
  val shape = "a compound or equality condition's shape is edited in code (or by replacing the Condition)"
  // K6 review: the join changes as a conditional value's does — set to "or" (`||`), removed for `&&`.
  if (key == CONDITION_JOIN_PROP) return when {
    terms.size < 2 -> "Condition.join joins two or more terms"
    v == null -> null
    v is PropValue.Lit && v.tag == "s" && v.s == CondJoin.Or.wire -> null
    else -> "Condition.join is \"or\" (||), or absent (&&)"
  }
  if (index >= terms.size) return "Condition.$key: $shape"
  val term = terms[index]
  val isEq = CondTest.fromWire(term.test) == CondTest.Eq
  return when (name) {
    "field" -> when {
      v == null && index > 0 -> "Condition.$key: $shape"
      index > 0 && (v as? PropValue.Lit)?.s.isNullOrEmpty() -> "Condition.$key: a term needs a field"
      index > 0 && !SCREEN_FIELD.matches((v as PropValue.Lit).s!!) -> "Condition.$key is a screen binding's name"
      else -> null
    }
    "negate" -> if (v == null || (v is PropValue.Lit && v.tag == "b")) null else "Condition.$key is a Boolean"
    "test" -> {
      val next = if (v == null) CondTest.Bool else CondTest.fromWire((v as? PropValue.Lit)?.s)
      when {
        index > 0 -> "Condition.$key: $shape"
        isEq && next != CondTest.Eq -> "Condition.test: this condition is an equality; $shape"
        !isEq && next == CondTest.Eq -> "Condition.test: an equality needs its operand; $shape"
        else -> null
      }
    }
    "operand" -> when {
      !isEq -> "Condition.$key: only an equality (test \"eq\") has an operand"
      v == null -> "Condition.$key: an equality needs its operand"
      else -> operandMisfit(v)?.let { "Condition.$key: $it" }
    }
    else -> null
  }
}

/**
 * K4: the token tags a template part may hold — String, Int, Boolean; never
 * a Double, which the device (Kotlin/JS: `1.0` prints `1`) and the preview
 * (wasm/JVM: `1.0`) print differently ([dev.keliver.portal.templateTokenFits]).
 */
private val TEMPLATE_TOKEN_TAGS = setOf("s", "i", "b")

/**
 * The contract type ingest gives a field whose type it cannot read (a
 * property without a declared type, `val x get() = 1.5`): a template never
 * reads it ([unreadableWhy]); a list element of it is left to the compile.
 */
private const val UNKNOWN_TYPE = "<unknown>"

/** Why a template may not read the field [field] declared [type] (see [templateRefTypeFits]). */
private fun refTypeWhy(field: String, type: String): String = when {
  type.trim().endsWith("?") -> "`$field` is declared $type: the device prints a null as \"null\", the preview as nothing"
  type.trim() == "Double" || type.trim() == "Float" ->
    "`$field` is declared $type: the device (Kotlin/JS) prints 1.0 as \"1\", the preview (wasm) as \"1.0\""
  else -> "`$field` is declared $type, which a template does not read (only String, Int, Long and Boolean print alike on the device and in the preview)"
}

/**
 * K4: why a template may not read the screen field [field] because [contract]
 * cannot type it — declared with no type (`<unknown>`), or not listed where
 * an unlisted field's type is not readable ([Contract.unlistedFieldsUnknown]:
 * the interface is not in the screen's file, or extends one declared
 * elsewhere). Ingest reads such a template as RawCode, so it would not be
 * written back as the document: refused when placed, never at write-back
 * (which would block every later edit of the screen). An item field
 * (`row.x`) is typed by its item, not the contract. Null = it may.
 */
private fun unreadableWhy(field: String, contract: Contract): String? {
  if ('.' in field) return null
  val type = contract.fields[field]
  val why = when {
    type == UNKNOWN_TYPE -> "`$field` is declared with no type"
    type == null -> contract.unlistedFieldsUnknown ?: return null
    else -> return null
  }
  return "the screen's Bindings type for `$field` is not readable here ($why); " +
    (if (type == UNKNOWN_TYPE) "declare its type, " else "") + "bind it directly or make this change in code"
}

/** [unreadableWhy] for every template part in the subtree [n] (also in conditionals' branches). */
private fun unreadableRefMisfit(n: DocNode, contract: Contract): String? {
  if (n !is DocNode.Widget) return null
  fun templates(v: PropValue?): List<PropValue.Template> = when (v) {
    is PropValue.Conditional -> templates(v.whenTrue) + templates(v.whenFalse)
    is PropValue.Template -> listOf(v)
    else -> emptyList()
  }
  (n.props + n.modifiers.mapKeys { "mod.${it.key}" }).forEach { (k, v) ->
    templates(v).forEach { t ->
      t.parts.filterIsInstance<PropValue.Bind>().forEach { p ->
        unreadableWhy(p.field, contract)?.let { return "template ${n.type}.$k: $it" }
      }
    }
  }
  return n.children.firstNotNullOfOrNull { unreadableRefMisfit(it, contract) }
}

/** K4: whether the contract type [type] is `Boolean` (also spelled `kotlin.Boolean`). */
private fun isBooleanType(type: String?): Boolean = type?.replace(" ", "")?.removePrefix("kotlin.") == "Boolean"

/** K4: the plain fields (and item fields) a template's parts or a list's elements read — also in a conditional's branches. */
private fun refFields(v: PropValue?): List<String> = when (v) {
  is PropValue.Conditional -> refFields(v.whenTrue) + refFields(v.whenFalse)
  is PropValue.Template -> v.parts.filterIsInstance<PropValue.Bind>().map { it.field }
  is PropValue.ListOf -> v.items.filterIsInstance<PropValue.Bind>().map { it.field }
  else -> emptyList()
}

/**
 * K4: why the template or list-with-references [v] (also as a conditional's
 * branch) may not sit at wire key [key] on a node of [nodeType], or null
 * when it may (or [v] is neither). [v] is canonical ([canonicalValue]). A
 * template on a project component's parameter is left to the server, which
 * knows the signature; a component parameter is never a list.
 */
private fun refValueMisfit(nodeType: String, key: String, v: PropValue, contract: Contract): String? {
  val fields = contract.fields
  val where = "$nodeType.$key"
  if (v is PropValue.Conditional) {
    if (listOfNotNull(v.whenTrue, v.whenFalse).any { it is PropValue.ListOf }) return "conditional $where: a list prop cannot be conditional"
    return listOfNotNull(v.whenTrue, v.whenFalse).firstNotNullOfOrNull { refValueMisfit(nodeType, key, it, contract) }
  }
  if (v is PropValue.Template) {
    if (key.startsWith("mod.")) return "template $where: a template fits only a String prop"
    if (widgetSpec(nodeType) != null) {
      val type = propSourceType(nodeType, key)
      if (type != "String") return "template $where: a template fits only a String prop (this one is ${type ?: "not a prop"})"
    }
    for (p in v.parts) {
      when (p) {
        is PropValue.Lit -> if (p.tag != "s") return "template $where: a text part is a String"
        is PropValue.Bind -> {
          if (!CONDITION_FIELD.matches(p.field)) return "template $where: '${p.field}' is not a field name"
          unreadableWhy(p.field, contract)?.let { return "template $where: $it" }
          fields[p.field]?.let { t -> if (!templateRefTypeFits(t)) return "template $where: ${refTypeWhy(p.field, t)}" }
        }
        is PropValue.Token -> if (p.value.tag !in TEMPLATE_TOKEN_TAGS) {
          return if (p.value.tag == "d") "template $where: token ${p.ref} is a Double, which the device (Kotlin/JS) prints as \"1\" where the preview prints \"1.0\" (a template reads String, Int and Boolean tokens)"
          else "template $where: token ${p.ref} is not a String, Int or Boolean"
        }
        else -> return "template $where: a part is text, a binding or a design token"
      }
    }
    return null
  }
  if (v !is PropValue.ListOf) return null
  if (key.startsWith("mod.") || widgetSpec(nodeType) == null) return "list $where: a list with references fits only a widget's list prop"
  val type = propSourceType(nodeType, key)
  val tag = listKindOf(type) ?: return "list $where: a list with references fits only a List<Int> or List<String> prop (this one is ${type ?: "not a prop"})"
  if (v.tag != tag) return "list $where: the list's tag is ${v.tag}, the prop's is $tag"
  val element = listElementType(tag)
  for (e in v.items) {
    when (e) {
      is PropValue.Lit -> if (e.tag != (if (tag == "ls") "s" else "i")) return "list $where: a literal element is a${if (tag == "ls") " String" else "n Int"}"
      is PropValue.Token -> if (!tokenFits(element, e.value.toAny())) {
        return "list $where: token ${e.ref} (${tokenKindOf(e.value.toAny()) ?: e.value.tag}) does not fit a $type element"
      }
      is PropValue.Bind -> {
        if (!CONDITION_FIELD.matches(e.field)) return "list $where: '${e.field}' is not a field name"
        fields[e.field]?.takeIf { it != UNKNOWN_TYPE }?.let { t -> if (t.replace(" ", "").removePrefix("kotlin.") != element) return "list $where: `${e.field}` is declared $t, not $element" }
      }
      else -> return "list $where: an element is a literal, a design token or a binding"
    }
  }
  return null
}

/** [refValueMisfit] for every prop and modifier of an inserted subtree. */
private fun refValueMisfit(n: DocNode, contract: Contract): String? {
  if (n !is DocNode.Widget) return null
  n.props.forEach { (k, v) -> refValueMisfit(n.type, k, canonicalValue(v), contract)?.let { return it } }
  n.modifiers.forEach { (k, v) -> refValueMisfit(n.type, "mod.$k", canonicalValue(v), contract)?.let { return it } }
  return n.children.firstNotNullOfOrNull { refValueMisfit(it, contract) }
}

/**
 * K4: a screen field the template or the list at wire key [key] of node [h]
 * reads that the screen tests as a Boolean elsewhere (a `Condition`, or a
 * conditional value) — the contract cannot type it both. A field the
 * contract ([fields]) declares `Boolean` is both tested and printed (a list
 * binding one is refused by its element type). Null = none.
 */
private fun refBooleanMisfitAt(root: DocNode, h: Handle, key: String, fields: Map<String, String>): String? {
  val w = findIn(root, h) as? DocNode.Widget ?: return null
  val v = (if (key.startsWith("mod.")) w.modifiers[key.removePrefix("mod.")] else w.props[key]) ?: return null
  val read = refFields(v).filter { '.' !in it && !isBooleanType(fields[it]) }.toSet()
  if (read.isEmpty()) return null
  fun tested(n: DocNode): String? {
    if (n !is DocNode.Widget) return null
    // K6: every term; an equality with a Boolean tests one too.
    fun booleanTerm(terms: List<DocTerm>): String? = terms.firstOrNull { t ->
      t.field in read && CondTest.fromWire(t.test).let { it == CondTest.Bool || (it == CondTest.Eq && operandKind(t.operand) == "Boolean") }
    }?.field
    if (n.type == "Condition") booleanTerm(n.conditionTerms())?.let { return it }
    (n.props.values + n.modifiers.values).forEach { x ->
      if (x is PropValue.Conditional) booleanTerm(x.docTerms())?.let { return it }
    }
    return n.children.firstNotNullOfOrNull(::tested)
  }
  val f = tested(root) ?: return null
  return "${w.type}.$key reads `$f` as text or a list element, but the screen tests it as a Boolean elsewhere (the contract cannot type it both)"
}

/**
 * K4: the plain screen fields the value [v] at wire key [key] of a node of
 * [nodeType] reads as ONE value — a template part, a list element, or a
 * binding on a prop or modifier argument whose source type is not a List
 * (also in a conditional's branches). A binding on a project component's
 * parameter is left out (the server knows its type).
 */
private fun scalarReads(nodeType: String, key: String, v: PropValue?): Set<String> {
  val out = refFields(v).filterTo(LinkedHashSet()) { '.' !in it }
  val binds = (if (v is PropValue.Conditional) listOfNotNull(v.whenTrue, v.whenFalse) else listOfNotNull(v))
    .filterIsInstance<PropValue.Bind>().filter { '.' !in it.field }
  if (binds.isNotEmpty()) {
    val type = propSourceType(nodeType, key)?.replace(" ", "")
    if (type != null && !type.startsWith("List<")) binds.forEach { out += it.field }
  }
  return out
}

/**
 * K4: a screen field a Repeat iterates (its `items`, a List) that the screen
 * also reads as one value ([scalarReads]) — a template would print the
 * list, and neither a list element nor a text or number prop holds one. At
 * wire key `items` of a Repeat [h], the Repeat's field against every such
 * read in [root]; at any other key, the fields that value reads against
 * every Repeat. Null = none. Whatever the contract declares: no type is both
 * a List and what a template, a list element or a scalar prop reads.
 */
private fun repeatListMisfitAt(root: DocNode, h: Handle, key: String): String? {
  val w = findIn(root, h) as? DocNode.Widget ?: return null
  fun why(field: String) =
    "`$field` is a Repeat's List: a template, a list element or a prop that is not a List does not read the whole list (read its items inside the Repeat)"
  if (w.type == "Repeat" && key == "items") {
    val field = (w.props["items"] as? PropValue.Lit)?.s?.takeIf { it.isNotEmpty() } ?: return null
    fun reads(n: DocNode): Boolean {
      if (n !is DocNode.Widget) return false
      if (n.props.any { (k, v) -> field in scalarReads(n.type, k, v) }) return true
      if (n.modifiers.any { (k, v) -> field in scalarReads(n.type, "mod.$k", v) }) return true
      return n.children.any(::reads)
    }
    return if (reads(root)) why(field) else null
  }
  val v = (if (key.startsWith("mod.")) w.modifiers[key.removePrefix("mod.")] else w.props[key]) ?: return null
  val read = scalarReads(w.type, key, v)
  if (read.isEmpty()) return null
  fun iterated(n: DocNode): String? {
    if (n !is DocNode.Widget) return null
    if (n.type == "Repeat") (n.props["items"] as? PropValue.Lit)?.s?.takeIf { it in read }?.let { return it }
    return n.children.firstNotNullOfOrNull(::iterated)
  }
  return iterated(root)?.let(::why)
}

/**
 * K4: an item field (`item.city`) a template part or a list element at wire
 * key [key] of node [h] reads outside a Repeat over that item — null when
 * every such reference is in scope.
 */
private fun itemRefMisfitAt(root: DocNode, h: Handle, key: String): String? {
  val w = findIn(root, h) as? DocNode.Widget ?: return null
  val v = (if (key.startsWith("mod.")) w.modifiers[key.removePrefix("mod.")] else w.props[key]) ?: return null
  fun refs(x: PropValue?): List<String> = when (x) {
    is PropValue.Conditional -> refs(x.whenTrue) + refs(x.whenFalse)
    is PropValue.Template -> x.parts.filterIsInstance<PropValue.Bind>().map { it.field }
    is PropValue.ListOf -> x.items.filterIsInstance<PropValue.Bind>().map { it.field }
    else -> emptyList()
  }
  val dotted = refs(v).filter { '.' in it }
  if (dotted.isEmpty()) return null
  val inScope = itemVarsAt(root, h)
  val outside = dotted.firstOrNull { it.substringBefore('.') !in inScope } ?: return null
  return "${w.type}.$key reads `$outside` outside a Repeat over `${outside.substringBefore('.')}` (an item field is read only inside its Repeat)"
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
