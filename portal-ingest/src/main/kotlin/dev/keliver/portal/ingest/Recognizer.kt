package dev.keliver.portal.ingest

import dev.keliver.portal.PropKind
import dev.keliver.portal.document.Contract
import dev.keliver.portal.document.DocNode
import dev.keliver.portal.document.Handle
import dev.keliver.portal.document.PropValue
import dev.keliver.portal.modifierSpecs
import dev.keliver.portal.widgetSpec
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtLambdaArgument
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty

/**
 * The Portal Compose recognizer (design §3/§5): turns a screen .kt into a
 * parsed DocNode tree (TEMP handles — the Reconciler maps them onto the live
 * document) + the Contract from the sibling Bindings interface.
 * INVARIANT: anything outside the grammar lands in RawCode VERBATIM.
 */
data class Recognized(
  val root: DocNode.Widget,
  val contract: Contract,
  /** null when the file has no recognizable screen function. */
  val screenName: String?,
)

object Recognizer {
  fun recognize(fileName: String, source: String): Recognized? {
    val file = PsiEnv.parse(fileName, source)
    val fn = file.declarations.filterIsInstance<KtNamedFunction>()
      .firstOrNull { f -> f.annotationEntries.any { it.shortName?.asString() == "Composable" } }
      ?: return null
    val bindingsParam = fn.valueParameters.firstOrNull()?.name // "b" by convention
    val body = fn.bodyExpression as? KtBlockExpression ?: return null

    var temp = -1L // temp handles are NEGATIVE; the Reconciler replaces them
    fun nextTemp() = Handle(temp--)

    fun statementToNode(expr: KtExpression): DocNode {
      val call = expr as? KtCallExpression
      val type = call?.calleeExpression?.text
      val spec = type?.let { widgetSpec(it) }
      if (call == null || spec == null) return rawNode(expr, ::nextTemp)

      val props = mutableMapOf<String, PropValue>()
      val modifiers = mutableMapOf<String, PropValue>()
      for (arg in call.valueArguments) {
        if (arg is KtLambdaArgument) continue
        val name = arg.getArgumentName()?.asName?.asString() ?: return rawNode(expr, ::nextTemp)
        val ve = arg.getArgumentExpression() ?: return rawNode(expr, ::nextTemp)
        if (name == "modifier") {
          val mods = parseModifierChain(ve.text) ?: return rawNode(expr, ::nextTemp)
          modifiers += mods
          continue
        }
        val value = parseValue(ve, bindingsParam) ?: return rawNode(expr, ::nextTemp)
        props[name] = value
      }
      val children = call.lambdaArguments.firstOrNull()
        ?.getLambdaExpression()?.bodyExpression?.statements.orEmpty()
        .map { statementToNode(it) }
      return DocNode.Widget(nextTemp(), type, props, modifiers, children)
    }

    val rootStatements = body.statements.map { statementToNode(it) }
    // One top-level widget = the root; several = wrap (shouldn't happen with our exporter).
    val root = rootStatements.singleOrNull() as? DocNode.Widget
      ?: DocNode.Widget(nextTemp(), "Column", children = rootStatements)

    val contract = file.declarations.filterIsInstance<KtClass>()
      .firstOrNull { it.isInterface() && it.name?.endsWith("Bindings") == true }
      ?.let { iface ->
        Contract(
          fields = iface.declarations.filterIsInstance<KtProperty>()
            .associate { (it.name ?: "?") to (it.typeReference?.text ?: "String") },
          actions = iface.declarations.filterIsInstance<KtNamedFunction>().mapNotNull { it.name },
        )
      } ?: Contract()

    return Recognized(root, contract, fn.name)
  }

  private fun rawNode(expr: KtExpression, nextTemp: () -> Handle): DocNode.RawCode {
    val text = expr.text
    val hint = when {
      text.startsWith("if") || text.startsWith("when") -> "condition"
      ".forEach" in text || text.startsWith("for ") || text.startsWith("repeat") -> "loop"
      "remember" in text || "LaunchedEffect" in text -> "effect"
      else -> null
    }
    return DocNode.RawCode(nextTemp(), text, hint)
  }

  /** Literal / bind / action argument expressions. Null = not in the grammar. */
  private fun parseValue(expr: KtExpression, bindingsParam: String?): PropValue? {
    val t = expr.text.trim()
    // b.field / b::action / { b.action() }
    if (bindingsParam != null) {
      Regex("^${Regex.escape(bindingsParam)}\\.([A-Za-z_][A-Za-z0-9_]*)$").find(t)
        ?.let { return PropValue.Bind(it.groupValues[1]) }
      Regex("^${Regex.escape(bindingsParam)}::([A-Za-z_][A-Za-z0-9_]*)$").find(t)
        ?.let { return PropValue.Action(it.groupValues[1]) }
      Regex("^\\{\\s*${Regex.escape(bindingsParam)}\\.([A-Za-z_][A-Za-z0-9_]*)\\(\\)\\s*}$").find(t)
        ?.let { return PropValue.Action(it.groupValues[1]) }
    }
    return parseLiteral(t)
  }

  internal fun parseLiteral(t: String): PropValue.Lit? = when {
    t == "true" -> PropValue.Lit("b", b = true)
    t == "false" -> PropValue.Lit("b", b = false)
    Regex("^-?\\d+$").matches(t) -> PropValue.Lit("i", i = t.toInt())
    Regex("^-?\\d*\\.\\d+$").matches(t) -> PropValue.Lit("d", d = t.toDouble())
    t.length >= 2 && t.startsWith('"') && t.endsWith('"') ->
      PropValue.Lit("s", s = t.substring(1, t.length - 1).replace("\\\"", "\"").replace("\\\\", "\\"))
    t.startsWith("Dp(") && t.endsWith(")") ->
      t.removePrefix("Dp(").removeSuffix(")").toDoubleOrNull()?.let { PropValue.Lit("d", d = it) }
    t.startsWith("listOf(") && t.endsWith(")") -> parseList(t.removePrefix("listOf(").removeSuffix(")"))
    t.startsWith("Constraint.") -> PropValue.Lit("i", i = if (t.endsWith("Fill")) 1 else 0)
    t.startsWith("CrossAxisAlignment.") -> enumLit(t, listOf("Start", "Center", "End", "Stretch"))
    t.startsWith("MainAxisAlignment.") -> enumLit(t, listOf("Start", "Center", "End", "SpaceBetween", "SpaceAround", "SpaceEvenly"))
    t.startsWith("Overflow.") -> PropValue.Lit("i", i = if (t.endsWith("Scroll")) 1 else 0)
    else -> null
  }

  private fun enumLit(t: String, names: List<String>): PropValue.Lit? =
    names.indexOf(t.substringAfterLast('.')).takeIf { it >= 0 }?.let { PropValue.Lit("i", i = it) }

  private fun parseList(inner: String): PropValue.Lit? {
    val items = inner.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    if (items.isEmpty()) return PropValue.Lit("li", li = emptyList())
    return if (items.all { it.endsWith("f") }) {
      val floats = items.map { it.removeSuffix("f").toFloatOrNull() ?: return null }
      PropValue.Lit("lf", lf = floats)
    } else {
      val ints = items.map { it.toIntOrNull() ?: return null }
      PropValue.Lit("li", li = ints)
    }
  }

  /** "Modifier.padding(8).animateContentSize()" → {"Padding.allDp": 8, "AnimateContentSize": true}. */
  internal fun parseModifierChain(text: String): Map<String, PropValue>? {
    val t = text.trim()
    if (t == "Modifier") return emptyMap()
    if (!t.startsWith("Modifier.")) return null
    val out = mutableMapOf<String, PropValue>()
    // split top-level chain segments: name(args)
    for (m in Regex("([A-Za-z_][A-Za-z0-9_]*)\\(([^()]*)\\)").findAll(t.removePrefix("Modifier."))) {
      val ext = m.groupValues[1]
      val argsText = m.groupValues[2].trim()
      val spec = modifierSpecs.firstOrNull { it.name.replaceFirstChar { c -> c.lowercase() } == ext } ?: return null
      if (spec.props.isEmpty()) {
        out[spec.name] = PropValue.Lit("b", b = true)
      } else {
        val args = if (argsText.isEmpty()) emptyList() else splitTopLevel(argsText)
        if (args.size != spec.props.size) return null
        spec.props.forEachIndexed { i, p ->
          val lit = parseLiteral(args[i].trim()) ?: return null
          out["${spec.name}.${p.name}"] = coerce(lit, p.kind)
        }
      }
    }
    return out
  }

  /** Exported modifier args are positional primitives; coerce to catalog kind. */
  private fun coerce(lit: PropValue.Lit, kind: PropKind): PropValue.Lit = when {
    kind == PropKind.Double && lit.tag == "i" -> PropValue.Lit("d", d = lit.i?.toDouble())
    else -> lit
  }

  private fun splitTopLevel(s: String): List<String> {
    val parts = mutableListOf<String>()
    var depth = 0
    var start = 0
    s.forEachIndexed { i, c ->
      when (c) {
        '(', '[' -> depth++
        ')', ']' -> depth--
        ',' -> if (depth == 0) { parts += s.substring(start, i); start = i + 1 }
      }
    }
    parts += s.substring(start)
    return parts
  }
}
