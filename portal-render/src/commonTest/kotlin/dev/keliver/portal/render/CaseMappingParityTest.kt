package dev.keliver.portal.render

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * K7 (optional item, REFUSED): `.uppercase()` / `.lowercase()` would be grammar
 * only if the device and the preview mapped case alike. This runs on both
 * portal-render targets (`jsBrowserTest`, `wasmJsBrowserTest`) over a Unicode
 * corpus — full case mappings (ß, ﬁ, ŉ), the Turkish dotted and dotless i,
 * the Greek final sigma, titlecase digraphs, iota subscripts, a letter
 * outside the BMP — against one golden. Browser js and wasm agree on it
 * today, but that does not prove the DEVICE: the device guest is Kotlin/JS
 * running in Zipline's QuickJS, whose case tables are its own (and its
 * Unicode version), not the browser's. So the form stays outside the grammar
 * (`${'$'}{x.uppercase()}` keeps the call RawCode, a component opaque);
 * format the text in the presenter. This test records what the preview
 * targets do, so a change there is seen.
 */
class CaseMappingParityTest {
  private val golden: List<Triple<String, String, String>> = listOf(
    Triple("\u00DF", "SS", "\u00DF"),
    Triple("\uFB01", "FI", "\uFB01"),
    Triple("\u0130", "\u0130", "i\u0307"),
    Triple("\u0131", "I", "\u0131"),
    Triple("I", "I", "i"),
    Triple("i", "I", "i"),
    Triple("\u039F\u0394\u039F\u03A3", "\u039F\u0394\u039F\u03A3", "\u03BF\u03B4\u03BF\u03C2"),
    Triple("\u03A3", "\u03A3", "\u03C3"),
    Triple("\u03C2", "\u03A3", "\u03C2"),
    Triple("\u0149", "\u02BCN", "\u0149"),
    Triple("\u01C5", "\u01C4", "\u01C6"),
    Triple("\u01F0", "J\u030C", "\u01F0"),
    Triple("\u0390", "\u0399\u0308\u0301", "\u0390"),
    Triple("\u1E9E", "\u1E9E", "\u00DF"),
    Triple("\u216B", "\u216B", "\u217B"),
    Triple("\u24D0", "\u24B6", "\u24D0"),
    Triple("\uD801\uDC00", "\uD801\uDC00", "\uD801\uDC28"),
    Triple("\uD801\uDC28", "\uD801\uDC00", "\uD801\uDC28"),
    Triple("Stra\u00DFe", "STRASSE", "stra\u00DFe"),
    Triple("\u0391\u03A3 \u0391\u03A3", "\u0391\u03A3 \u0391\u03A3", "\u03B1\u03C2 \u03B1\u03C2"),
    Triple("\u01C8", "\u01C7", "\u01C9"),
    Triple("\uFB00", "FF", "\uFB00"),
    Triple("\u1FB3", "\u0391\u0399", "\u1FB3"),
    Triple("\u1FBC", "\u0391\u0399", "\u1FB3"),
    Triple("\u03A9", "\u03A9", "\u03C9"),
    Triple("K", "K", "k"),
    Triple("\u00C5", "\u00C5", "\u00E5"),
  )

  @Test fun bothPreviewTargetsMapCaseAsTheGoldenSays() {
    for ((s, upper, lower) in golden) {
      assertEquals(upper, s.uppercase(), "uppercase of $s")
      assertEquals(lower, s.lowercase(), "lowercase of $s")
    }
  }
}
