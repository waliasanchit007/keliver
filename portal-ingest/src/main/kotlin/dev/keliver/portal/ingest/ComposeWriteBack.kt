package dev.keliver.portal.ingest

import dev.keliver.portal.document.DocNode
import dev.keliver.portal.document.PropValue
import dev.keliver.portal.kotlinStringLiteral
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtLambdaArgument
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtValueArgument

/**
 * ED0 spike (docs/ED0_SPIKE.md): surgical write-back of a Compose node's arguments and modifier
 * list. Every change is a text splice of the smallest piece that changed — an argument's value
 * expression, one argument added or removed, one modifier element added or removed — so the rest
 * of the call keeps its bytes: names, positions, formatting, comments. A changed value is spelled
 * canonically ([ComposeValues.spell]); an unchanged one is never re-spelled.
 *
 * Not surgical here (the caller reports why): a modifier list that changes by more than one
 * element, removing a positional argument that a later positional argument follows, a template's
 * or a conditional's new value, a slot added or removed, removing an argument with a comment beside
 * it, and an edit that leaves a call with no arguments whose overload is then ambiguous.
 */
internal object ComposeWriteBack {
  sealed interface Result {
    /** Written in place; [imports] are what the new text names. */
    class Ok(val imports: Set<String>) : Result
    class No(val why: String) : Result
  }

  /**
   * Edits [call] (a node of [entry]) from [parsed]'s arguments and modifiers to [target]'s.
   * [overloads] are [entry]'s and its siblings': a call left with no arguments must still bind to
   * [entry] alone (`Modifier.padding()` is ambiguous, and the compiler would refuse it).
   */
  fun editArguments(
    call: KtCallExpression,
    entry: ComposeCatalog.Entry,
    parsed: DocNode.Widget,
    target: DocNode.Widget,
    catalog: ComposeCatalog,
    bindingsParam: String?,
    factory: KtPsiFactory,
    overloads: List<ComposeCatalog.Entry> = listOf(entry),
  ): Result {
    val imports = LinkedHashSet<String>()
    val spell = Speller(bindingsParam, imports)
    // 1. The modifier list (the modifier argument's expression, in place).
    if (!WriteBack.sameModifiers(parsed.modifiers, target.modifiers)) {
      val arg = binding(call, entry)["modifier"]
      if (arg == null) {
        val text = spell.chain(target.modifiers, catalog) ?: return Result.No(spell.why!!)
        val r = editList(call, entry, mapOf("modifier" to Change.Add(text)), factory)
        if (r != null) return Result.No(r)
      } else if (target.modifiers.isEmpty()) {
        // The last element went: the argument goes too, as the inverse of adding it to a call with none.
        editList(call, entry, mapOf("modifier" to Change.Remove), factory)?.let { return Result.No(it) }
      } else {
        val expr = arg.getArgumentExpression()!!
        val text = editChain(expr, parsed.modifiers, target.modifiers, catalog, spell, factory) ?: return Result.No(spell.why!!)
        if (text != expr.text) expr.replace(factory.createExpression(text))
      }
    }
    // 2. The arguments: values in place, then one pass over the argument list for adds/removes.
    val args = binding(call, entry)
    val changes = LinkedHashMap<String, Change>()
    for (name in (parsed.props.keys + target.props.keys).distinct()) {
      val old = parsed.props[name]
      val new = target.props[name]
      if (old == new) continue
      val p = entry.param(name) ?: return Result.No("`${entry.name}` has no parameter `$name`")
      changes[name] = when {
        new == null -> Change.Remove
        else -> {
          val text = spell.value(p, new) ?: return Result.No(spell.why!!)
          if (old == null) Change.Add(text) else Change.Replace(text)
        }
      }
    }
    // A trailing lambda (an event) is outside the argument list: replaced in place.
    for ((name, c) in changes.entries.toList()) {
      val a = args[name] as? KtLambdaArgument ?: continue
      if (c !is Change.Replace) return Result.No("`$name` is a trailing lambda: it is replaced, not added or removed, in the ED0 spike")
      a.getArgumentExpression()!!.replace(factory.createExpression(c.text))
      changes.remove(name)
    }
    if (changes.isNotEmpty()) editList(call, entry, changes, factory)?.let { return Result.No(it) }
    if (call.valueArguments.isEmpty() && overloads.size > 1) {
      ComposeSyntax.emptyCallBindsTo(call, overloads, entry)?.let { return Result.No("the edit would leave `${call.text}`: $it") }
    }
    return Result.Ok(imports)
  }

  /** One argument's change. */
  private sealed interface Change {
    class Replace(val text: String) : Change
    class Add(val text: String) : Change
    data object Remove : Change
  }

  /** [call]'s arguments by parameter, as the recogniser bound them. */
  fun binding(call: KtCallExpression, entry: ComposeCatalog.Entry): Map<String, KtValueArgument> =
    (ComposeSyntax.bind(call, listOf(entry)) { _, _ -> null } as? ComposeSyntax.Bound.Ok)?.binding?.args.orEmpty()

  /**
   * Applies [changes] to [call]'s parenthesised argument list as text: a replaced value is only its
   * expression's range; an added argument is named and goes last (before a trailing lambda, which
   * is outside the list), on its own line when the list puts each argument on one; a removed
   * argument takes its separator with it. Null = done, else why not.
   */
  private fun editList(call: KtCallExpression, entry: ComposeCatalog.Entry, changes: Map<String, Change>, factory: KtPsiFactory): String? {
    val args = binding(call, entry)
    val list = call.valueArgumentList
    if (list == null) {
      val adds = changes.entries.map { (n, c) -> (c as? Change.Add)?.let { "$n = ${it.text}" } ?: return "`$n` is not in the call" }
      call.addAfter(factory.createCallArguments("(${adds.joinToString(", ")})"), call.typeArgumentList ?: call.calleeExpression!!)
      return null
    }
    val base = list.textRange.startOffset
    val text = StringBuilder(list.text)
    val inList = list.arguments
    // Edits as (start, end, replacement) relative to the list, applied from the end.
    val edits = mutableListOf<Triple<Int, Int, String>>()
    val multiLine = inList.isNotEmpty() && inList.all { startsLine(list.text, it.textRange.startOffset - base) }
    val removed = sortedMapOf<Int, String>()
    for ((name, c) in changes) {
      when (c) {
        is Change.Replace -> {
          val e = args[name]?.getArgumentExpression() ?: return "`$name` is not in the call"
          edits += Triple(e.textRange.startOffset - base, e.textRange.endOffset - base, c.text)
        }
        Change.Remove -> {
          val a = args[name] ?: return "`$name` is not in the call"
          val i = inList.indexOf(a)
          if (a.getArgumentName() == null && inList.drop(i + 1).any { it.getArgumentName() == null && inList.indexOf(it) !in removed }) {
            return "removing the positional `$name` would shift the positional arguments after it"
          }
          removed[i] = name
        }
        is Change.Add -> {}
      }
    }
    // Consecutive removed arguments go as one piece (their separators would otherwise overlap).
    val ranges = inList.map { it.textRange.startOffset - base to it.textRange.endOffset - base }
    for (run in runs(removed.keys.toList())) {
      val r = removal(list.text, ranges, run.first(), run.last(), multiLine)
      // A comment beside an argument would go with it: refused, not guessed where it belongs.
      var around = list.text.substring(r.first, r.second)
      for (k in run) around = around.replaceFirst(inList[k].text, "")
      if ("/*" in around || "//" in around) {
        return "a comment beside ${run.joinToString { "`${removed[it]}`" }} would go with it (not written surgically in the ED0 spike)"
      }
      edits += r
    }
    val adds = changes.entries.filter { it.value is Change.Add }.map { (n, c) -> "$n = ${(c as Change.Add).text}" }
    if (adds.isNotEmpty()) {
      val close = list.text.length - 1 // the `)`
      if (inList.isEmpty()) {
        edits += Triple(1, close, adds.joinToString(", "))
      } else if (multiLine) {
        val indent = lineIndent(list.text, inList.last().textRange.startOffset - base)
        val lastEnd = inList.last().textRange.endOffset - base
        val trailingComma = list.text.substring(lastEnd, close).trimStart().startsWith(",")
        val at = if (trailingComma) list.text.indexOf(',', lastEnd) + 1 else lastEnd
        val body = adds.joinToString("") { "\n$indent$it," }.let { if (trailingComma) it else it.removeSuffix(",") }
        edits += Triple(at, at, if (trailingComma) body else ",$body")
      } else {
        val lastEnd = inList.last().textRange.endOffset - base
        edits += Triple(lastEnd, lastEnd, adds.joinToString("") { ", $it" })
      }
    }
    for ((s, e, r) in edits.sortedByDescending { it.first }) text.replace(s, e, r)
    val newList = factory.createCallArguments(text.toString())
    list.replace(newList)
    // No arguments left before a trailing block: `Column { … }`, not `Column() { … }`.
    call.valueArgumentList?.takeIf { it.arguments.isEmpty() && call.lambdaArguments.isNotEmpty() }?.delete()
    return null
  }

  /** [sorted] indices grouped into runs of consecutive ones. */
  private fun runs(sorted: List<Int>): List<List<Int>> {
    val out = mutableListOf<MutableList<Int>>()
    for (i in sorted) if (out.lastOrNull()?.last() == i - 1) out.last() += i else out += mutableListOf(i)
    return out
  }

  /** The range of arguments [i]..[j] of [ranges] (in the list's [text]) with their separators, for removal. */
  private fun removal(text: String, ranges: List<Pair<Int, Int>>, i: Int, j: Int, multiLine: Boolean): Triple<Int, Int, String> {
    val s = ranges[i].first
    val e = ranges[j].second
    if (i == 0 && j == ranges.lastIndex) return Triple(1, text.length - 1, "") // every argument: `()`
    if (multiLine) {
      val lineStart = text.lastIndexOf('\n', s - 1) + 1
      var end = e
      if (text.substring(end).trimStart(' ').startsWith(",")) end = text.indexOf(',', end) + 1
      val nl = text.indexOf('\n', end)
      if (j == ranges.lastIndex && !text.substring(e).trimStart(' ').startsWith(",")) {
        // The argument before the run loses its comma along with the run's lines.
        val comma = text.indexOf(',', ranges[i - 1].second)
        return Triple(comma, if (nl < 0) end else nl, "")
      }
      return Triple(lineStart, if (nl < 0) end else nl + 1, "")
    }
    return if (i > 0) Triple(ranges[i - 1].second, e, "") else Triple(s, ranges[j + 1].first, "")
  }

  private fun startsLine(text: String, offset: Int): Boolean = text.substring(text.lastIndexOf('\n', offset - 1) + 1, offset).isBlank() && '\n' in text.substring(0, offset)

  private fun lineIndent(text: String, offset: Int): String = text.substring(text.lastIndexOf('\n', offset - 1) + 1, offset)

  // ── The modifier list ───────────────────────────────────────────────────────────────────────

  /**
   * [expr] (a `Modifier.…` chain read as [old]) rewritten to [new]: each element's changed
   * arguments in place; one element removed or inserted as its own `.name(…)` piece. Null with
   * [Speller.why] when the change is not one of those.
   */
  private fun editChain(
    expr: KtExpression,
    old: Map<String, PropValue>,
    new: Map<String, PropValue>,
    catalog: ComposeCatalog,
    spell: Speller,
    factory: KtPsiFactory,
  ): String? {
    val calls = ComposeSyntax.chain(expr) ?: return spell.no("the modifier argument is no longer a `Modifier.…` chain")
    val oe = ComposeSyntax.elements(old)
    val ne = ComposeSyntax.elements(new)
    fun kind(head: String) = head.substringAfter(':')
    val base = expr.textRange.startOffset
    val text = StringBuilder(expr.text)
    when {
      oe.map { kind(it.first) } == ne.map { kind(it.first) } -> {
        // The same elements: edit each element's changed arguments, last first (offsets hold).
        for (i in oe.indices.reversed()) {
          if (oe[i].second == ne[i].second) continue
          val entry = catalog.modifier(kind(oe[i].first)) ?: return spell.no("`${kind(oe[i].first)}` is not a generated modifier")
          val call = calls[i]
          // Its siblings: the same name's overloads in the same scope (a scope's members, or the top-level ones).
          val overloads = catalog.modifiersNamed(entry.name).filter { it.scope == entry.scope }
          val edited = editElementCall(call, entry, oe[i].second, ne[i].second, spell, factory, overloads) ?: return null
          text.replace(call.textRange.startOffset - base, call.textRange.endOffset - base, edited)
        }
      }
      oe.size == ne.size + 1 -> {
        // One element removed: its `.name(…)` piece (and the line break before it) goes.
        val i = oe.indices.firstOrNull { k -> oe.filterIndexed { j, _ -> j != k }.map { kind(it.first) to it.second } == ne.map { kind(it.first) to it.second } }
          ?: return spell.no("the modifier list changed by more than removing one element")
        val call = calls[i]
        val dot = call.parent as org.jetbrains.kotlin.psi.KtDotQualifiedExpression
        text.replace(dot.receiverExpression.textRange.endOffset - base, call.textRange.endOffset - base, "")
      }
      oe.size + 1 == ne.size -> {
        // One element inserted: `.name(…)` after the element before it (or after `Modifier`).
        val i = ne.indices.firstOrNull { k -> ne.filterIndexed { j, _ -> j != k }.map { kind(it.first) to it.second } == oe.map { kind(it.first) to it.second } }
          ?: return spell.no("the modifier list changed by more than inserting one element")
        val entry = catalog.modifier(kind(ne[i].first)) ?: return spell.no("`${kind(ne[i].first)}` is not a generated modifier")
        val piece = spell.element(entry, ne[i].second) ?: return null
        val at = if (i == 0) {
          (if (calls.isEmpty()) expr else (calls[0].parent as org.jetbrains.kotlin.psi.KtDotQualifiedExpression).receiverExpression).textRange.endOffset - base
        } else {
          calls[i - 1].textRange.endOffset - base
        }
        // A chain written one element per line gets the new element on a line of its own.
        val next = calls.getOrNull(i)
        val sep = next?.let { n ->
          val dotStart = (n.parent as org.jetbrains.kotlin.psi.KtDotQualifiedExpression).receiverExpression.textRange.endOffset - base
          text.substring(dotStart, n.textRange.startOffset - base).substringBefore('.')
        } ?: calls.lastOrNull()?.let { l ->
          val dotStart = (l.parent as org.jetbrains.kotlin.psi.KtDotQualifiedExpression).receiverExpression.textRange.endOffset - base
          text.substring(dotStart, l.textRange.startOffset - base).substringBefore('.')
        } ?: ""
        text.insert(at, "$sep.$piece")
      }
      else -> return spell.no("the modifier list changed by more than one element (reordering is not written surgically in the ED0 spike)")
    }
    if (spell.why != null) return null
    return text.toString()
  }

  /** One element call's text after its arguments change from [old] to [new]. */
  private fun editElementCall(
    call: KtCallExpression,
    entry: ComposeCatalog.Entry,
    old: Map<String, PropValue>,
    new: Map<String, PropValue>,
    spell: Speller,
    factory: KtPsiFactory,
    overloads: List<ComposeCatalog.Entry>,
  ): String? {
    // Work on a copy: the element is spliced into the chain's text by the caller.
    val copy = factory.createExpression(call.text) as KtCallExpression
    val parsed = DocNode.Widget(dev.keliver.portal.document.Handle(0), entry.type, old)
    val target = DocNode.Widget(dev.keliver.portal.document.Handle(0), entry.type, new)
    return when (val r = editArguments(copy, entry, parsed, target, ComposeCatalog(emptyList(), emptyList()), spell.bindingsParam, factory, overloads)) {
      is Result.Ok -> copy.text.also { spell.imports += r.imports }
      is Result.No -> spell.no(r.why)
    }
  }

  /** Spells values, elements and chains; [why] says what it could not spell. */
  class Speller(val bindingsParam: String?, val imports: MutableSet<String>) {
    var why: String? = null

    fun <T> no(reason: String): T? = null.also { why = reason }

    /** [v] as the argument of [p], or null ([why]). */
    fun value(p: ComposeCatalog.Param, v: PropValue): String? = when (v) {
      is PropValue.Lit -> when (v.tag) {
        "s" -> kotlinStringLiteral(v.s!!)
        "i" -> v.i!!.let { if (it == Int.MAX_VALUE && p.type == "kotlin.Int") "Int.MAX_VALUE" else it.toString() }
        "b" -> v.b!!.toString()
        "d" -> if (p.type == "kotlin.Float") floatText(v.d!!) + "f" else no("a Double is not a ${p.type}")
        "c" -> try {
          ComposeValues.spell(p.type ?: return no("`${p.name}` has no value type"), v.s!!).also { imports += it.imports }.text
        } catch (e: RuntimeException) {
          no("`${p.name}`: ${e.message}")
        }
        else -> no("a `${v.tag}` literal is not a Compose argument")
      }
      is PropValue.Bind -> bindText(v.field)
      is PropValue.Action -> {
        val call = "${actionTarget(v.name)}(${v.arg ?: ""})"
        "{ $call }"
      }
      else -> no("a new ${v::class.simpleName} value is not written surgically in the ED0 spike")
    }

    private fun bindText(field: String): String {
      val param = bindingsParam ?: "b"
      return if ('.' in field || param == COMPONENT_PARAMS) field else "$param.$field"
    }

    private fun actionTarget(name: String): String {
      val param = bindingsParam ?: "b"
      return if (param == COMPONENT_PARAMS) name else "$param.$name"
    }

    private fun floatText(d: Double): String = if (d == d.toLong().toDouble()) d.toLong().toString() else d.toFloat().toString()

    /** One modifier element `name(args)`: required arguments by position, the rest named, an event as a trailing lambda. */
    fun element(entry: ComposeCatalog.Entry, args: Map<String, PropValue>): String? {
      if (entry.scope == null) imports += entry.guest
      val positional = mutableListOf<String>()
      val named = mutableListOf<String>()
      var trailing: String? = null
      for (p in entry.params) {
        val v = args[p.name] ?: continue
        val text = value(p, v) ?: return null
        when {
          p.kind == "event" && p == entry.params.last() -> trailing = text
          p.required && named.isEmpty() -> positional += text
          else -> named += "${p.name} = $text"
        }
      }
      val list = (positional + named).joinToString(", ")
      return entry.name + (if (list.isEmpty() && trailing != null) "" else "($list)") + (trailing?.let { " $it" } ?: "")
    }

    /** A whole chain `Modifier.a(…).b(…)` for a node that had none. */
    fun chain(mods: Map<String, PropValue>, catalog: ComposeCatalog): String? {
      val parts = ComposeSyntax.elements(mods).map { (head, args) ->
        val entry = catalog.modifier(head.substringAfter(':')) ?: return no("`${head.substringAfter(':')}` is not a generated modifier")
        element(entry, args) ?: return null
      }
      imports += "dev.keliver.compose.ui.Modifier"
      return "Modifier" + parts.joinToString("") { ".$it" }
    }
  }
}
