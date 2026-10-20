package dev.keliver.portal.document

import dev.keliver.portal.templateValueText

/*
 * K4: string templates and lists with references, in the document — their
 * canonical form, a walk over every atomic value a prop holds, and the
 * compatibility views the relay serves a client that predates them.
 */

/**
 * The canonical form of a template ([PropValue.Template]): a non-String
 * literal part printed as its text, adjacent literal parts merged, empty ones
 * dropped — and a plain [PropValue.Lit] when no reference is left. This is
 * what the recognizer reads from source, so a document holding it reads back
 * as itself.
 */
public fun PropValue.Template.canonical(): PropValue {
  fun flat(ps: List<PropValue>): List<PropValue> = ps.flatMap { if (it is PropValue.Template) flat(it.parts) else listOf(it) }
  val out = ArrayList<PropValue>()
  for (p in flat(parts)) {
    val part = if (p is PropValue.Lit && p.tag != "s") lit(templateValueText(p.toAny()) ?: "") else p
    val last = out.lastOrNull()
    when {
      part is PropValue.Lit && part.s.isNullOrEmpty() -> {}
      part is PropValue.Lit && last is PropValue.Lit -> out[out.lastIndex] = lit(last.s.orEmpty() + part.s.orEmpty())
      else -> out += part
    }
  }
  return if (out.all { it is PropValue.Lit }) lit(out.joinToString("") { (it as PropValue.Lit).s.orEmpty() }) else PropValue.Template(out)
}

/** The canonical form of a list ([PropValue.ListOf]): a plain [PropValue.Lit] list when every element is a literal. */
public fun PropValue.ListOf.canonical(): PropValue {
  if (items.any { it !is PropValue.Lit }) return this
  return when (tag) {
    "ls" -> PropValue.Lit("ls", ls = items.map { (it as PropValue.Lit).s.orEmpty() })
    else -> PropValue.Lit("li", li = items.map { (it as PropValue.Lit).i ?: 0 })
  }
}

/** [v] in canonical form — a template's or a list's (also inside a conditional's branches); anything else unchanged. */
public fun canonicalValue(v: PropValue): PropValue = when (v) {
  is PropValue.Template -> v.canonical()
  is PropValue.ListOf -> v.canonical()
  // K7: an expression of literals is the literal it computes (an Int used as a Dp, its Dp).
  is PropValue.Arith -> v.canonical()
  is PropValue.IntDp -> v.canonical()
  // K6: a single term is joined by nothing (the recognizer reads no join for it).
  is PropValue.Conditional -> v.copy(
    whenTrue = v.whenTrue?.let(::canonicalValue),
    whenFalse = v.whenFalse?.let(::canonicalValue),
    join = v.join?.takeIf { v.more.isNotEmpty() },
  )
  else -> v
}

/**
 * Every ATOMIC value [v] holds: itself, or — recursively — a conditional's
 * branches (K6: and its equality operands, first), a template's parts and a
 * list's elements (K7: an arithmetic value's operands). (A Kotlin `null`
 * branch holds none.)
 */
public fun PropValue.atoms(): List<PropValue> = when (this) {
  is PropValue.Conditional -> operands() + listOfNotNull(whenTrue, whenFalse).flatMap { it.atoms() }
  is PropValue.Template -> parts.flatMap { it.atoms() }
  is PropValue.ListOf -> items.flatMap { it.atoms() }
  // K7: an arithmetic value's operands (literals, tokens, bindings); an Int used as a Dp, its value's.
  is PropValue.Arith -> left.atoms() + right.atoms()
  is PropValue.IntDp -> value.atoms()
  else -> listOf(this)
}

/**
 * [this] with [f] applied to every atomic value ([atoms]), and each template
 * or list it maps put back in canonical form (a token part replaced by its
 * value becomes text).
 */
public fun PropValue.mapAtoms(f: (PropValue) -> PropValue): PropValue = when (this) {
  is PropValue.Conditional -> mapOperands(f).copy(whenTrue = whenTrue?.mapAtoms(f), whenFalse = whenFalse?.mapAtoms(f))
  is PropValue.Template -> PropValue.Template(parts.map { it.mapAtoms(f) }).canonical()
  is PropValue.ListOf -> PropValue.ListOf(tag, items.map { it.mapAtoms(f) }).canonical()
  // K7: only the whole expression is put in canonical form — an operand tree is never folded.
  is PropValue.Arith -> mapOperandTree(f).canonical()
  is PropValue.IntDp -> PropValue.IntDp(if (value is PropValue.Arith) value.mapOperandTree(f) else f(value), form).canonical()
  else -> f(this)
}

/** K6: a conditional's equality operands, term by term (a term without one has none). */
public fun PropValue.Conditional.operands(): List<PropValue> = listOfNotNull(operand) + more.mapNotNull { it.operand }

/** K6: [this] with [f] applied to each equality operand ([operands]); the branches unchanged. */
public fun PropValue.Conditional.mapOperands(f: (PropValue) -> PropValue): PropValue.Conditional =
  if (operand == null && more.none { it.operand != null }) this
  else copy(operand = operand?.let(f), more = more.map { t -> t.operand?.let { t.copy(operand = f(it)) } ?: t })

/**
 * A template's text with what the document knows: literal parts, token
 * values, a `{field}` placeholder for each binding — what an editor that
 * predates templates is served, and renders.
 */
public fun PropValue.Template.text(): String = parts.joinToString("") { p ->
  when (p) {
    is PropValue.Lit -> templateValueText(p.toAny()) ?: ""
    is PropValue.Token -> templateValueText(p.value.toAny()) ?: ""
    is PropValue.Bind -> "{${p.field}}"
    is PropValue.Template -> p.text()
    else -> ""
  }
}

/** A list's resolved literal: token elements their values, a binding 0 (an Int list) or its `{field}` placeholder. */
public fun PropValue.ListOf.resolved(): PropValue.Lit = when (tag) {
  "ls" -> PropValue.Lit("ls", ls = items.map { e ->
    when (e) {
      is PropValue.Lit -> e.s ?: templateValueText(e.toAny()).orEmpty()
      is PropValue.Token -> templateValueText(e.value.toAny()).orEmpty()
      is PropValue.Bind -> "{${e.field}}"
      else -> ""
    }
  })
  else -> PropValue.Lit("li", li = items.map { e ->
    when (e) {
      is PropValue.Lit -> e.i ?: 0
      is PropValue.Token -> e.value.i ?: 0
      else -> 0
    }
  })
}

private fun UiDocument.mapValues(f: (PropValue) -> PropValue): UiDocument {
  fun node(n: DocNode): DocNode = when (n) {
    is DocNode.RawCode -> n
    is DocNode.Widget -> n.copy(
      props = n.props.mapValues { f(it.value) },
      modifiers = n.modifiers.mapValues { f(it.value) },
      children = n.children.map(::node),
    )
  }
  return copy(root = node(root))
}

/**
 * K4 compatibility: [this] as an editor that predates templates can decode it
 * — every [PropValue.Template] (also a conditional's branch) replaced by its
 * text ([text]). The relay serves this unless the client advertises them
 * (`templates=1`); as with tokens and conditionals, an edit such a client
 * makes to another prop leaves the template in the document and the file
 * (only a prop it rewrites becomes a literal).
 */
public fun UiDocument.withoutTemplates(): UiDocument {
  fun v(x: PropValue): PropValue = when (x) {
    is PropValue.Template -> lit(x.text())
    is PropValue.Conditional -> x.copy(whenTrue = x.whenTrue?.let(::v), whenFalse = x.whenFalse?.let(::v))
    else -> x
  }
  return mapValues(::v)
}

/**
 * K4 compatibility: [this] as an editor that predates lists with references
 * can decode it — every [PropValue.ListOf] replaced by its resolved literal
 * list ([resolved]). The relay serves this unless the client asks `lists=1`.
 */
public fun UiDocument.withoutLists(): UiDocument = mapValues { x -> if (x is PropValue.ListOf) x.resolved() else x }
