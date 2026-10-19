package dev.keliver.portal.render

import dev.keliver.portal.Template
import dev.keliver.portal.Token
import dev.keliver.portal.templateRefTypeFits
import dev.keliver.portal.templateTokenFits
import dev.keliver.portal.templateValueText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * K4 — template formatting parity across targets. A device guest runs
 * Kotlin/JS; the editor preview runs wasm (and the relay the JVM). This test
 * runs on BOTH portal-render targets (`jsBrowserTest`, `wasmJsBrowserTest`)
 * against the same expected text: on js, Kotlin's own string template is the
 * device's output and the preview's formatting must equal it; on wasm, the
 * preview's runtime must print the same. Edge values: negatives, the Int
 * bounds, Longs past 2^53 (Kotlin/JS emulates Long exactly), non-BMP text.
 *
 * A Double has no faithful preview — Kotlin/JS prints `1.0` as `1`, wasm and
 * the JVM as `1.0` — so a template never reads one: the rule is asserted here
 * beside the values that do print alike.
 */
class TemplateFormatParityTest {
  private val alike: List<Pair<Any, String>> = listOf(
    0 to "0",
    -1 to "-1",
    16777215 to "16777215",
    Int.MAX_VALUE to "2147483647",
    Int.MIN_VALUE to "-2147483648",
    9007199254740993L to "9007199254740993", // 2^53 + 1: not a JS double
    -9007199254740993L to "-9007199254740993",
    Long.MAX_VALUE to "9223372036854775807",
    Long.MIN_VALUE to "-9223372036854775808",
    true to "true",
    false to "false",
    "" to "",
    "null" to "null",
    "a \$b \\ \"c\"" to "a \$b \\ \"c\"",
    "é 𠀀" to "é 𠀀",
  )

  @Test fun thePreviewPrintsWhatTheDevicePrints() {
    for ((value, text) in alike) {
      // This target's own Kotlin string template (on js: the device's).
      assertEquals(text, "$value", "Kotlin prints $value")
      // The preview's formatting, as a value, a token's value and a template part.
      assertEquals(text, templateValueText(value), "templateValueText($value)")
      assertEquals(text, templateValueText(Token("C.X", value)), "a token of $value")
      assertEquals("[$text]", PreviewBindings.text(Template(listOf("[", value, "]"))), "a template part $value")
      assertEquals("[$text]", PreviewBindings.text(Template(listOf("[", Token("C.X", value), "]"))), "a template token $value")
    }
  }

  @Test fun onlyTypesThatPrintAlikeAreTemplateReferences() {
    for (type in listOf("String", "Int", "Long", "Boolean", null)) assertTrue(templateRefTypeFits(type), "$type")
    for (type in listOf("Double", "Float", "String?", "Int?", "Boolean?", "List<String>")) assertFalse(templateRefTypeFits(type), type)
    for (value in listOf<Any>("s", 1, true)) assertTrue(templateTokenFits(value), "$value")
    // 1.0 prints "1" on Kotlin/JS and "1.0" on wasm/JVM: never a template token.
    assertTrue("${1.0}" == "1" || "${1.0}" == "1.0")
    // On Kotlin/JS every number is `is Int` at runtime (1.5 too), so a value's
    // kind cannot be read back there: it is decided where the source type is
    // known — ingest and the relay (JVM), the editor (wasm; the token list's
    // declared kind).
    val jsNumbers = (1.5 as Any) is Int
    if (!jsNumbers) assertFalse(templateTokenFits(1.5), "1.5 is ${dev.keliver.portal.tokenKindOf(1.5)}")
    assertFalse(templateTokenFits(1L)) // no Long token (K2: no portal prop holds one)
  }
}
