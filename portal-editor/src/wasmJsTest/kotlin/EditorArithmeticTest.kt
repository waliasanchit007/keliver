/*
 * Copyright (C) 2026 Keliver contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import dev.keliver.portal.Arith
import dev.keliver.portal.ArithOp
import dev.keliver.portal.Bind
import dev.keliver.portal.Cond
import dev.keliver.portal.Conditional
import dev.keliver.portal.IntDp
import dev.keliver.portal.PropKind
import dev.keliver.portal.Token
import dev.keliver.portal.document.DocOp
import dev.keliver.portal.document.Handle
import dev.keliver.portal.document.lit
import dev.keliver.portal.render.PreviewBindings
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** K7: an arithmetic value is shown read-only as its source and replaced whole by its value. */
class EditorArithmeticTest {
  @AfterTest fun reset() = PreviewBindings.mocks.clear()

  private val expr = Arith(ArithOp.Div, Arith(ArithOp.Times, Bind("size"), 2), 5)

  @Test fun anExpressionIsSpelledAsSource() {
    assertTrue(isArithmeticValue(expr))
    assertTrue(isArithmeticValue(IntDp(Bind("gap"), ctor = true)))
    assertFalse(isArithmeticValue(Bind("size")))
    assertEquals("b.size * 2 / 5", arithmeticSource(expr))
    assertEquals("Dp(b.gap.toDouble())", arithmeticSource(IntDp(Bind("gap"), ctor = true)))
    assertEquals("(row.n + C.Gap).dp", arithmeticSource(IntDp(Arith(ArithOp.Plus, Bind("row.n"), Token("C.Gap", 8)))))
  }

  @Test fun replacingItWholeSendsTheValueItComputesNow() {
    assertEquals(DocOp.SetProp(Handle(3), "fontSize", lit(0)), arithmeticLiteralOp(3, "fontSize", expr)) // unknown: 0
    PreviewBindings.mocks["size"] = "40"
    assertEquals(DocOp.SetProp(Handle(3), "fontSize", lit(16)), arithmeticLiteralOp(3, "fontSize", expr))
    PreviewBindings.mocks["gap"] = "6"
    assertEquals(DocOp.SetProp(Handle(3), "height", lit(6.0)), arithmeticLiteralOp(3, "height", IntDp(Bind("gap"))))
  }

  @Test fun anArithmeticBranchIsReadOnlyAndTheOtherStaysEditable() {
    val sent = mutableListOf<DocOp>()
    val row = conditionalRow(7, "StyledText", "fontSize", "Font size", PropKind.Int, Conditional(Cond("big"), expr, 12)) { op, _, _ -> sent += op }
    assertTrue("b.size * 2 / 5" in (row.textContent ?: ""), row.textContent)
  }
}
