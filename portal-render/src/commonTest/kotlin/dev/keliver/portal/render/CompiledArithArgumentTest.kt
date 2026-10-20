package dev.keliver.portal.render

import dev.keliver.Modifier
import dev.keliver.portal.Arith
import dev.keliver.portal.ArithOp
import dev.keliver.portal.Bind
import dev.keliver.portal.Token
import dev.keliver.portal.WidgetNode
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** K7: an opaque component drawn compiled (#132) decodes an arithmetic Int argument as the canvas computes it. */
class CompiledArithArgumentTest {
  @AfterTest fun reset() = PreviewBindings.mocks.clear()

  private fun call(v: Any?) = CompiledCall(WidgetNode("Disc", mapOf("size" to v)), Modifier)

  @Test fun anArithmeticArgumentDecodesToItsValue() {
    assertEquals(16, call(Arith(ArithOp.Times, Token("C.Gap", 8), 2)).int("size", 5))
    assertEquals(16, call(Arith(ArithOp.Times, Token("C.Gap", 8), 2)).intOrNull("size", null))
    // An unknown operand keeps the default, as a widget property does.
    assertEquals(5, call(Arith(ArithOp.Plus, Bind("s"), 1)).int("size", 5))
    PreviewBindings.mocks["s"] = "2147483647"
    assertEquals(Int.MIN_VALUE, call(Arith(ArithOp.Plus, Bind("s"), 1)).int("size", 5))
  }
}
