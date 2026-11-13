package dev.keliver.portal.ingest

import dev.keliver.portal.ingest.ComposeValues.ALIGNMENT
import dev.keliver.portal.ingest.ComposeValues.ALIGNMENT_H
import dev.keliver.portal.ingest.ComposeValues.ALIGNMENT_V
import dev.keliver.portal.ingest.ComposeValues.ARRANGEMENT_H
import dev.keliver.portal.ingest.ComposeValues.ARRANGEMENT_V
import dev.keliver.portal.ingest.ComposeValues.COLOR
import dev.keliver.portal.ingest.ComposeValues.CONTENT_SCALE
import dev.keliver.portal.ingest.ComposeValues.DP
import dev.keliver.portal.ingest.ComposeValues.FILTER_QUALITY
import dev.keliver.portal.ingest.ComposeValues.FONT_FAMILY
import dev.keliver.portal.ingest.ComposeValues.FONT_STYLE
import dev.keliver.portal.ingest.ComposeValues.FONT_WEIGHT
import dev.keliver.portal.ingest.ComposeValues.IMAGE_VECTOR
import dev.keliver.portal.ingest.ComposeValues.PADDING_VALUES
import dev.keliver.portal.ingest.ComposeValues.SHAPE
import dev.keliver.portal.ingest.ComposeValues.TEXT_ALIGN
import dev.keliver.portal.ingest.ComposeValues.TEXT_DECORATION
import dev.keliver.portal.ingest.ComposeValues.TEXT_OVERFLOW
import dev.keliver.portal.ingest.ComposeValues.TEXT_STYLE
import dev.keliver.portal.ingest.ComposeValues.TEXT_UNIT
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ED0 spike: the Compose value table. Every kind reads into the guest mirror's wire JSON, and the
 * canonical spelling of that JSON reads back to the same JSON — the property write-back of a
 * changed value relies on (its read-back check would refuse anything else).
 */
class ComposeValuesTest {
  private fun read(text: String, type: String): String =
    when (val r = ComposeValues.read(PsiEnv.factory.createExpression(text), type)) {
      is ComposeValues.Read.Ok -> r.json
      is ComposeValues.Read.No -> throw AssertionError("$text as $type: ${r.why}")
    }

  private val cases = listOf(
    DP to "16.dp", DP to "16.5.dp", DP to "Dp.Unspecified",
    TEXT_UNIT to "12.sp", TEXT_UNIT to "1.2.em", TEXT_UNIT to "TextUnit.Unspecified",
    COLOR to "Color(0xFFEEF0F4)", COLOR to "Color.White", COLOR to "Color.Unspecified", COLOR to "LocalContentColor.current",
    COLOR to "MaterialTheme.colorScheme.primary", COLOR to "MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)",
    SHAPE to "RoundedCornerShape(12.dp)", SHAPE to "CircleShape", SHAPE to "RectangleShape", SHAPE to "RoundedCornerShape(percent = 25)",
    SHAPE to "RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)", SHAPE to "CutCornerShape(8.dp)", SHAPE to "MaterialTheme.shapes.medium",
    TEXT_STYLE to "MaterialTheme.typography.titleMedium", TEXT_STYLE to "LocalTextStyle.current",
    TEXT_STYLE to "LocalTextStyle.current.copy(letterSpacing = 1.sp)", TEXT_STYLE to "TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold)",
    TEXT_STYLE to "MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.error, textAlign = TextAlign.End)",
    FONT_WEIGHT to "FontWeight.SemiBold", FONT_WEIGHT to "FontWeight(650)", FONT_STYLE to "FontStyle.Italic", FONT_FAMILY to "FontFamily.Monospace",
    TEXT_ALIGN to "TextAlign.Center", TEXT_OVERFLOW to "TextOverflow.Ellipsis", TEXT_DECORATION to "TextDecoration.Underline",
    ALIGNMENT to "Alignment.BottomEnd", ALIGNMENT_H to "Alignment.CenterHorizontally", ALIGNMENT_V to "Alignment.Bottom",
    ARRANGEMENT_H to "Arrangement.SpaceBetween", ARRANGEMENT_H to "Arrangement.spacedBy(8.dp)", ARRANGEMENT_H to "Arrangement.spacedBy(8.dp, Alignment.End)",
    ARRANGEMENT_V to "Arrangement.Top", ARRANGEMENT_V to "Arrangement.spacedBy(4.dp, Alignment.CenterVertically)",
    PADDING_VALUES to "PaddingValues(16.dp)", PADDING_VALUES to "PaddingValues(horizontal = 16.dp, vertical = 8.dp)",
    PADDING_VALUES to "PaddingValues(start = 1.dp, top = 2.dp, end = 3.dp, bottom = 4.dp)",
    CONTENT_SCALE to "ContentScale.Crop", IMAGE_VECTOR to "Icons.Filled.Search", IMAGE_VECTOR to "Icons.AutoMirrored.Filled.ArrowBack",
    IMAGE_VECTOR to "Icons.Default.Close", FILTER_QUALITY to "FilterQuality.High",
  )

  @Test fun everyKindReadsAndItsSpellingReadsBackTheSame() {
    for ((type, text) in cases) {
      val json = read(text, type)
      val spelled = ComposeValues.spell(type, json)
      assertEquals(json, read(spelled.text, type), "$text → ${spelled.text}")
    }
  }

  @Test fun aCanonicallyWrittenValueIsSpelledAsWritten() {
    for ((type, text) in cases) {
      val spelled = ComposeValues.spell(type, read(text, type)).text
      val canonical = text !in setOf(
        "Color.White", // a named colour is spelled as its value
        "MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)", // the alpha is a byte: 0.5019608f
        "Icons.Default.Close", // Default is Filled
      )
      if (canonical) assertEquals(text, spelled, "$type $text")
    }
    assertEquals("Color(0xFFFFFFFF)", ComposeValues.spell(COLOR, read("Color.White", COLOR)).text)
  }

  @Test fun aSpellingNamesItsImports() {
    assertEquals(
      setOf("dev.keliver.compose.material.icons.Icons", "dev.keliver.compose.material.icons.automirrored.filled.ArrowBack"),
      ComposeValues.spell(IMAGE_VECTOR, "\"AutoMirrored.Filled.ArrowBack\"").imports,
    )
    assertEquals(setOf("dev.keliver.compose.material3.MaterialTheme"), ComposeValues.spell(COLOR, """{"ref":"colorScheme.primary"}""").imports)
    assertEquals(
      setOf("dev.keliver.compose.foundation.layout.Arrangement", "dev.keliver.compose.ui.unit.dp", "dev.keliver.compose.ui.Alignment"),
      ComposeValues.spell(ARRANGEMENT_H, """{"sp":8,"a":1.0}""").imports,
    )
  }

  @Test fun outsideTheTableSaysWhy() {
    fun why(text: String, type: String) = (ComposeValues.read(PsiEnv.factory.createExpression(text), type) as ComposeValues.Read.No).why
    assertTrue("is not a Color" in why("brandColor", COLOR))
    assertTrue("is not a Dp" in why("16", DP), "an Int is not a Dp")
    assertTrue("is not a Shape" in why("GenericShape { _, _ -> }", SHAPE))
    assertTrue("unknown colour reference" in why("MaterialTheme.colorScheme.brand", COLOR) || "is not a Color" in why("MaterialTheme.colorScheme.brand", COLOR))
  }
}
