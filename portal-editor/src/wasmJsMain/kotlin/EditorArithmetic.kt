import dev.keliver.portal.Arith
import dev.keliver.portal.IntDp
import dev.keliver.portal.arithKotlin
import dev.keliver.portal.document.DocOp
import dev.keliver.portal.document.Handle
import dev.keliver.portal.document.PropValue
import dev.keliver.portal.document.lit
import dev.keliver.portal.render.PreviewBindings
import dev.keliver.portal.render.intValueOf
import org.w3c.dom.HTMLElement

// K7: an Int arithmetic value (`b.size * 2 / 5`) or an Int used as a Dp
// (`Dp(b.gap.toDouble())`) is shown read-only, as its source — its shape is
// the code's. It can still be replaced whole: by a literal (what it computes
// now, else 0) or, from the row's @ button, by a binding.

/** Whether the panel shows [v] read-only as an expression. */
internal fun isArithmeticValue(v: Any?): Boolean = v is Arith || v is IntDp

/** [v] as the screen's source spells it (a screen field through `b`). */
internal fun arithmeticSource(v: Any?): String = arithKotlin(v) { if ('.' in it) it else "b.$it" }

/** The literal replacing [v]: what the preview computes for it now — an Int, or a Dp for an Int used as a Dp — else 0. */
internal fun arithmeticLiteral(v: Any?): PropValue.Lit = when (v) {
  is IntDp -> lit((PreviewBindings.intValueOf(v.value) ?: 0).toDouble().coerceAtLeast(0.0))
  else -> lit(PreviewBindings.intValueOf(v) ?: 0)
}

/** The op replacing the expression at wire key [key] of node [nodeId] by its literal ([arithmeticLiteral]). */
internal fun arithmeticLiteralOp(nodeId: Int, key: String, v: Any?): DocOp =
  if (key.startsWith("mod.")) DocOp.SetModifier(Handle(nodeId.toLong()), key.removePrefix("mod."), arithmeticLiteral(v))
  else DocOp.SetProp(Handle(nodeId.toLong()), key, arithmeticLiteral(v))

/** The read-only source of [v] and a button sending [onLiteral] with the literal that replaces it. */
internal fun arithmeticEditor(v: Any?, onLiteral: (PropValue.Lit) -> Unit): HTMLElement {
  val box = Ui.el("span", "row")
  box.appendChild(Ui.el("code", "", arithmeticSource(v)).also { it.setAttribute("title", "an expression: edited in code (replace it with a value here)") })
  box.appendChild(
    Ui.button("= value", "btn icon") { onLiteral(arithmeticLiteral(v)) }
      .also { it.setAttribute("title", "replace the expression with the value it computes now") },
  )
  return box
}
