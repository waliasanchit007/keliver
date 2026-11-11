package dev.keliver.portal.ingest

import dev.keliver.compose.foundation.layout.Arrangement
import dev.keliver.compose.foundation.layout.HorizontalArrangementCodec
import dev.keliver.compose.foundation.layout.PaddingValues
import dev.keliver.compose.foundation.layout.PaddingValuesCodec
import dev.keliver.compose.foundation.layout.VerticalArrangementCodec
import dev.keliver.compose.foundation.shape.CircleShape
import dev.keliver.compose.foundation.shape.CutCornerShape
import dev.keliver.compose.foundation.shape.RoundedCornerShape
import dev.keliver.compose.material3.MaterialTheme
import dev.keliver.compose.ui.Alignment
import dev.keliver.compose.ui.AlignmentCodec
import dev.keliver.compose.ui.HorizontalAlignmentCodec
import dev.keliver.compose.ui.VerticalAlignmentCodec
import dev.keliver.compose.ui.graphics.Color
import dev.keliver.compose.ui.graphics.ColorCodec
import dev.keliver.compose.ui.graphics.ColorRefs
import dev.keliver.compose.ui.graphics.FilterQuality
import dev.keliver.compose.ui.graphics.FilterQualityCodec
import dev.keliver.compose.ui.graphics.RectangleShape
import dev.keliver.compose.ui.graphics.Shape
import dev.keliver.compose.ui.graphics.ShapeCodec
import dev.keliver.compose.ui.graphics.ShapeReference
import dev.keliver.compose.ui.graphics.vector.ImageVector
import dev.keliver.compose.ui.graphics.vector.ImageVectorCodec
import dev.keliver.compose.ui.layout.ContentScale
import dev.keliver.compose.ui.layout.ContentScaleCodec
import dev.keliver.compose.ui.text.TextStyle
import dev.keliver.compose.ui.text.TextStyleCodec
import dev.keliver.compose.ui.text.font.FontFamily
import dev.keliver.compose.ui.text.font.FontFamilyCodec
import dev.keliver.compose.ui.text.font.FontStyle
import dev.keliver.compose.ui.text.font.FontStyleCodec
import dev.keliver.compose.ui.text.font.FontWeight
import dev.keliver.compose.ui.text.font.FontWeightCodec
import dev.keliver.compose.ui.text.style.TextAlign
import dev.keliver.compose.ui.text.style.TextAlignCodec
import dev.keliver.compose.ui.text.style.TextDecoration
import dev.keliver.compose.ui.text.style.TextDecorationCodec
import dev.keliver.compose.ui.text.style.TextOverflow
import dev.keliver.compose.ui.text.style.TextOverflowCodec
import dev.keliver.compose.ui.unit.Dp
import dev.keliver.compose.ui.unit.DpCodec
import dev.keliver.compose.ui.unit.TextUnit
import dev.keliver.compose.ui.unit.TextUnitCodec
import dev.keliver.compose.ui.unit.em
import dev.keliver.compose.ui.unit.sp
import dev.keliver.compose.wire.JsonCodec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.jetbrains.kotlin.psi.KtBinaryExpressionWithTypeRHS
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtConstantExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtPrefixExpression
import org.jetbrains.kotlin.psi.KtValueArgument

/**
 * ED0 spike (docs/ED0_SPIKE.md): Compose values — `16.dp`, `12.sp`, `Color(0xFF…)`,
 * `MaterialTheme.colorScheme.primary`, `RoundedCornerShape(12.dp)`, `TextStyle(…)`,
 * `LocalTextStyle.current.copy(…)`, `Arrangement.spacedBy(8.dp)`, `Icons.Filled.Search` — read
 * into the guest's own mirrors (keliver-compose-values) and kept as their wire JSON, which is what
 * the canvas decodes and what the device's protocol carries. A fixed table, not an interpreter:
 * anything else is not grammar, and its call becomes RawCode with the reason.
 *
 * [spell] is the inverse: a value's canonical Kotlin, with the imports it needs. Ingest of a spelled
 * value gives back the same JSON ([ComposeValuesTest] checks every kind), so write-back of a changed
 * value reads back as the document.
 */
internal object ComposeValues {
  /** What a value reads as: its wire JSON, or why it is not grammar. */
  sealed interface Read {
    data class Ok(val json: String) : Read
    data class No(val why: String) : Read
  }

  /** The guest types this table reads (a parameter of another type is not grammar). */
  val TYPES: Set<String> = setOf(
    DP, TEXT_UNIT, COLOR, SHAPE, TEXT_STYLE, FONT_WEIGHT, FONT_STYLE, FONT_FAMILY, TEXT_ALIGN, TEXT_OVERFLOW,
    TEXT_DECORATION, ALIGNMENT, ALIGNMENT_H, ALIGNMENT_V, ARRANGEMENT_H, ARRANGEMENT_V, PADDING_VALUES,
    CONTENT_SCALE, IMAGE_VECTOR, FILTER_QUALITY,
  )

  /** Reads [e] as a value of the guest type [type]. */
  fun read(e: KtExpression, type: String): Read {
    val x = unparen(e)
    if (x.text == "null") return Read.No("`null` is not a ${type.substringAfterLast('.')}")
    val value: Any? = try {
      when (type) {
        DP -> dp(x)
        TEXT_UNIT -> textUnit(x)
        COLOR -> color(x)
        SHAPE -> shape(x)
        TEXT_STYLE -> textStyle(x)
        FONT_WEIGHT -> fontWeight(x)
        FONT_STYLE -> named(x, "FontStyle", FONT_STYLES)
        FONT_FAMILY -> named(x, "FontFamily", FONT_FAMILIES)
        TEXT_ALIGN -> named(x, "TextAlign", TEXT_ALIGNS)
        TEXT_OVERFLOW -> named(x, "TextOverflow", TEXT_OVERFLOWS)
        TEXT_DECORATION -> named(x, "TextDecoration", TEXT_DECORATIONS)
        ALIGNMENT -> named(x, "Alignment", ALIGNMENTS)
        ALIGNMENT_H -> named(x, "Alignment", H_ALIGNMENTS)
        ALIGNMENT_V -> named(x, "Alignment", V_ALIGNMENTS)
        ARRANGEMENT_H -> arrangement(x, H_ARRANGEMENTS, H_ALIGNMENTS)
        ARRANGEMENT_V -> arrangement(x, V_ARRANGEMENTS, V_ALIGNMENTS)
        PADDING_VALUES -> paddingValues(x)
        CONTENT_SCALE -> named(x, "ContentScale", CONTENT_SCALES)
        IMAGE_VECTOR -> icon(x)
        FILTER_QUALITY -> named(x, "FilterQuality", FILTER_QUALITIES)
        else -> return Read.No("a ${type.substringAfterLast('.')} value is not in the ED0 value table")
      }
    } catch (e: IllegalArgumentException) {
      return Read.No("`${x.text.oneLine()}`: ${e.message}")
    }
    value ?: return Read.No("`${x.text.oneLine()}` is not a ${type.substringAfterLast('.')} the ED0 value table reads")
    return Read.Ok(encode(type, value))
  }

  /** A Float literal (`1f`, `0.5f`, `1.0F`), or null. */
  fun floatLiteral(e: KtExpression): Float? {
    val t = unparen(e).text.trim()
    return Regex("""-?(\d+(\.\d+)?|\.\d+)[fF]""").matchEntire(t)?.let { t.dropLast(1).toFloat() }
  }

  // ── Spelling (write-back) ───────────────────────────────────────────────────────────────────

  /** A value's canonical Kotlin and the imports it needs. */
  data class Spelled(val text: String, val imports: Set<String>)

  /** The canonical spelling of the wire value [json] of the guest type [type]. */
  fun spell(type: String, json: String): Spelled {
    val j = Json.parseToJsonElement(json)
    if (j is JsonNull) return Spelled("null", emptySet())
    return when (type) {
      DP -> DpCodec.fromJson(j).value.let { v ->
        if (v.isNaN()) Spelled("Dp.Unspecified", setOf("$UNIT.Dp")) else Spelled("${num(v)}.dp", setOf("$UNIT.dp"))
      }
      TEXT_UNIT -> spellTextUnit(TextUnitCodec.fromJson(j))
      COLOR -> spellColor(ColorCodec.fromJson(j))
      SHAPE -> spellShape(ShapeCodec.fromJson(j))
      TEXT_STYLE -> spellTextStyle(TextStyleCodec.fromJson(j))
      FONT_WEIGHT -> spellFontWeight(FontWeightCodec.fromJson(j))
      FONT_STYLE -> spellNamed("FontStyle", FONT, FONT_STYLES, FontStyleCodec.fromJson(j))
      FONT_FAMILY -> spellNamed("FontFamily", FONT, FONT_FAMILIES, FontFamilyCodec.fromJson(j))
      TEXT_ALIGN -> spellNamed("TextAlign", STYLE, TEXT_ALIGNS, TextAlignCodec.fromJson(j))
      TEXT_OVERFLOW -> spellNamed("TextOverflow", STYLE, TEXT_OVERFLOWS, TextOverflowCodec.fromJson(j))
      TEXT_DECORATION -> spellNamed("TextDecoration", STYLE, TEXT_DECORATIONS, TextDecorationCodec.fromJson(j))
      ALIGNMENT -> spellNamed("Alignment", "dev.keliver.compose.ui", ALIGNMENTS, AlignmentCodec.fromJson(j))
      ALIGNMENT_H -> spellNamed("Alignment", "dev.keliver.compose.ui", H_ALIGNMENTS, HorizontalAlignmentCodec.fromJson(j))
      ALIGNMENT_V -> spellNamed("Alignment", "dev.keliver.compose.ui", V_ALIGNMENTS, VerticalAlignmentCodec.fromJson(j))
      ARRANGEMENT_H -> spellArrangement(j, H_ARRANGEMENTS, H_ALIGNMENTS)
      ARRANGEMENT_V -> spellArrangement(j, V_ARRANGEMENTS, V_ALIGNMENTS)
      PADDING_VALUES -> spellPadding(j)
      CONTENT_SCALE -> spellNamed("ContentScale", "dev.keliver.compose.ui.layout", CONTENT_SCALES, ContentScaleCodec.fromJson(j))
      IMAGE_VECTOR -> spellIcon(ImageVectorCodec.fromJson(j).name)
      FILTER_QUALITY -> spellNamed("FilterQuality", GRAPHICS, FILTER_QUALITIES, FilterQualityCodec.fromJson(j))
      else -> error("no spelling for $type")
    }
  }

  // ── Reading ─────────────────────────────────────────────────────────────────────────────────

  private fun unparen(e: KtExpression): KtExpression = when (e) {
    is KtParenthesizedExpression -> e.expression?.let(::unparen) ?: e
    else -> e
  }

  /** `a.b.c` as its names, or null when [e] is not a plain name chain. */
  private fun path(e: KtExpression): List<String>? = when (val x = unparen(e)) {
    is KtNameReferenceExpression -> listOf(x.getReferencedName())
    is KtDotQualifiedExpression -> {
      val sel = x.selectorExpression as? KtNameReferenceExpression ?: return null
      path(x.receiverExpression)?.plus(sel.getReferencedName())
    }
    else -> null
  }

  /** A call `name(args)` with an optional receiver: (receiver, name, arguments). */
  private class Call(val receiver: KtExpression?, val name: String, val args: List<KtValueArgument>) {
    fun named(n: String): KtExpression? = args.firstOrNull { it.getArgumentName()?.asName?.asString() == n }?.getArgumentExpression()
    fun positional(i: Int): KtExpression? = args.getOrNull(i)?.takeIf { it.getArgumentName() == null }?.getArgumentExpression()
    fun arg(i: Int, n: String): KtExpression? = named(n) ?: positional(i)
    val names: Set<String> get() = args.mapNotNull { it.getArgumentName()?.asName?.asString() }.toSet()
  }

  private fun call(e: KtExpression): Call? = when (val x = unparen(e)) {
    is KtCallExpression -> (x.calleeExpression as? KtNameReferenceExpression)?.let { Call(null, it.getReferencedName(), x.valueArguments) }
      ?.takeIf { x.lambdaArguments.isEmpty() && x.typeArgumentList == null }
    is KtDotQualifiedExpression -> (x.selectorExpression as? KtCallExpression)?.let { c ->
      (c.calleeExpression as? KtNameReferenceExpression)?.let { Call(x.receiverExpression, it.getReferencedName(), c.valueArguments) }
        ?.takeIf { c.lambdaArguments.isEmpty() && c.typeArgumentList == null }
    }
    else -> null
  }

  private fun number(e: KtExpression): Float? {
    val x = unparen(e)
    val t = x.text.trim()
    if (x is KtPrefixExpression && t.startsWith("-")) return x.baseExpression?.let(::number)?.let { -it }
    if (x !is KtConstantExpression) return null
    return Regex("""\d+(\.\d+)?[fF]?""").matchEntire(t)?.let { t.trimEnd('f', 'F').toFloat() }
  }

  private fun intLit(e: KtExpression): Int? = unparen(e).text.trim().takeIf { Regex("""-?\d+""").matches(it) }?.toInt()

  /** `16.dp`, `16.5.dp`, `Dp(16f)`. */
  private fun dp(e: KtExpression): Dp? {
    path(e)?.let { p -> if (p == listOf("Dp", "Unspecified")) return Dp(Float.NaN) }
    val x = unparen(e)
    if (x is KtDotQualifiedExpression && (x.selectorExpression as? KtNameReferenceExpression)?.getReferencedName() == "dp") {
      return number(x.receiverExpression)?.let(::Dp)
    }
    val c = call(x) ?: return null
    if (c.receiver == null && c.name == "Dp" && c.args.size == 1) return c.arg(0, "value")?.let(::number)?.let(::Dp)
    return null
  }

  /** `12.sp`, `1.2.em`, `TextUnit.Unspecified`. */
  private fun textUnit(e: KtExpression): TextUnit? {
    if (path(e) == listOf("TextUnit", "Unspecified")) return TextUnit.Unspecified
    val x = unparen(e) as? KtDotQualifiedExpression ?: return null
    val n = number(x.receiverExpression) ?: return null
    return when ((x.selectorExpression as? KtNameReferenceExpression)?.getReferencedName()) {
      "sp" -> n.sp
      "em" -> n.em
      else -> null
    }
  }

  private fun color(e: KtExpression): Color? {
    path(e)?.let { p ->
      if (p.size == 2 && p[0] == "Color") return COLORS[p[1]]
      if (p == listOf("LocalContentColor", "current")) return Color.reference("LocalContentColor")
      if (p.size == 3 && p[0] == "MaterialTheme" && p[1] == "colorScheme") {
        return "colorScheme.${p[2]}".takeIf { it in ColorRefs.names }?.let { Color.reference(it) }
      }
      return null
    }
    val c = call(e) ?: return null
    if (c.receiver == null && c.name == "Color" && c.args.size == 1 && c.args[0].getArgumentName() == null) {
      val t = c.args[0].getArgumentExpression()!!.text.trim()
      val hex = Regex("""0[xX]([0-9a-fA-F]{1,8})L?""").matchEntire(t)?.groupValues?.get(1) ?: return null
      return Color(hex.toLong(16))
    }
    if (c.receiver != null && c.name == "copy" && c.names == setOf("alpha")) {
      val base = color(c.receiver) ?: return null
      return base.copy(alpha = c.named("alpha")?.let(::number) ?: return null)
    }
    return null
  }

  private fun shape(e: KtExpression): Shape? {
    path(e)?.let { p ->
      return when {
        p == listOf("CircleShape") -> CircleShape
        p == listOf("RectangleShape") -> RectangleShape
        p.size == 3 && p[0] == "MaterialTheme" && p[1] == "shapes" && p[2] in THEME_SHAPES -> ShapeReference.of("shapes.${p[2]}")
        else -> null
      }
    }
    val c = call(e) ?: return null
    if (c.receiver != null) return null
    val rounded = when (c.name) {
      "RoundedCornerShape" -> true
      "CutCornerShape" -> false
      else -> return null
    }
    if (c.args.size == 1 && c.args[0].getArgumentName() == null) {
      val a = c.args[0].getArgumentExpression()!!
      dp(a)?.let { return if (rounded) RoundedCornerShape(it) else CutCornerShape(it) }
      intLit(a)?.let { return if (rounded) RoundedCornerShape(it) else CutCornerShape(it) }
      return null
    }
    if (c.names == setOf("size")) return dp(c.named("size")!!)?.let { if (rounded) RoundedCornerShape(it) else CutCornerShape(it) }
    if (c.names == setOf("percent")) return intLit(c.named("percent")!!)?.let { if (rounded) RoundedCornerShape(it) else CutCornerShape(it) }
    if (rounded && c.args.isNotEmpty() && c.args.all { it.getArgumentName() != null } && c.names.all { it in CORNERS }) {
      val v = CORNERS.map { n -> c.named(n)?.let { dp(it) ?: return null } ?: Dp(0f) }
      return RoundedCornerShape(topStart = v[0], topEnd = v[1], bottomEnd = v[2], bottomStart = v[3])
    }
    return null
  }

  private fun textStyle(e: KtExpression): TextStyle? {
    path(e)?.let { p ->
      if (p == listOf("LocalTextStyle", "current")) return TextStyle.reference("LocalTextStyle")
      if (p.size == 3 && p[0] == "MaterialTheme" && p[1] == "typography" && p[2] in TYPE_SCALE) return TextStyle.reference("typography.${p[2]}")
      return null
    }
    val c = call(e) ?: return null
    val base = when {
      c.receiver == null && c.name == "TextStyle" -> TextStyle()
      c.receiver != null && c.name == "copy" -> textStyle(c.receiver) ?: return null
      else -> return null
    }
    if (c.args.any { it.getArgumentName() == null }) return null
    var s = base
    for (a in c.args) {
      val v = a.getArgumentExpression() ?: return null
      s = when (a.getArgumentName()!!.asName.asString()) {
        "color" -> s.copy(color = color(v) ?: return null)
        "fontSize" -> s.copy(fontSize = textUnit(v) ?: return null)
        "fontWeight" -> s.copy(fontWeight = fontWeight(v) ?: return null)
        "fontStyle" -> s.copy(fontStyle = named(v, "FontStyle", FONT_STYLES) ?: return null)
        "fontFamily" -> s.copy(fontFamily = named(v, "FontFamily", FONT_FAMILIES) ?: return null)
        "letterSpacing" -> s.copy(letterSpacing = textUnit(v) ?: return null)
        "textDecoration" -> s.copy(textDecoration = named(v, "TextDecoration", TEXT_DECORATIONS) ?: return null)
        "textAlign" -> s.copy(textAlign = named(v, "TextAlign", TEXT_ALIGNS) ?: return null)
        "lineHeight" -> s.copy(lineHeight = textUnit(v) ?: return null)
        else -> return null
      }
    }
    return s
  }

  private fun fontWeight(e: KtExpression): FontWeight? {
    named(e, "FontWeight", FONT_WEIGHTS)?.let { return it }
    val c = call(e) ?: return null
    if (c.receiver == null && c.name == "FontWeight" && c.args.size == 1) return c.arg(0, "weight")?.let(::intLit)?.let(::FontWeight)
    return null
  }

  private fun <T> named(e: KtExpression, owner: String, values: Map<String, T>): T? {
    val p = path(e) ?: return null
    return if (p.size == 2 && p[0] == owner) values[p[1]] else null
  }

  private fun <T : Any> arrangement(e: KtExpression, names: Map<String, T>, aligns: Map<String, Any>): Any? {
    named(e, "Arrangement", names)?.let { return it }
    val c = call(e) ?: return null
    if (c.receiver == null || path(c.receiver) != listOf("Arrangement") || c.name != "spacedBy") return null
    val space = c.arg(0, "space")?.let(::dp) ?: return null
    val align = c.arg(1, "alignment")
    if (c.args.size == 1) return Arrangement.spacedBy(space)
    val a = align?.let { named(it, "Alignment", aligns) } ?: return null
    return when (a) {
      is Alignment.Horizontal -> Arrangement.spacedBy(space, a)
      is Alignment.Vertical -> Arrangement.spacedBy(space, a)
      else -> null
    }
  }

  private fun paddingValues(e: KtExpression): PaddingValues? {
    val c = call(e)?.takeIf { it.receiver == null && it.name == "PaddingValues" } ?: return null
    if (c.args.size == 1 && c.args[0].getArgumentName() == null) return dp(c.args[0].getArgumentExpression()!!)?.let { PaddingValues(it) }
    if (c.args.any { it.getArgumentName() == null }) return null
    fun d(n: String) = c.named(n)?.let { dp(it) ?: throw IllegalArgumentException("`$n` is not a Dp") } ?: Dp(0f)
    return when {
      c.names == setOf("all") -> PaddingValues(d("all"))
      c.names.all { it in setOf("horizontal", "vertical") } -> PaddingValues(horizontal = d("horizontal"), vertical = d("vertical"))
      c.names.all { it in setOf("start", "top", "end", "bottom") } -> PaddingValues(start = d("start"), top = d("top"), end = d("end"), bottom = d("bottom"))
      else -> null
    }
  }

  /** `Icons.Filled.Search`, `Icons.AutoMirrored.Filled.ArrowBack`, `Icons.Default.Search` (Filled). */
  private fun icon(e: KtExpression): ImageVector? {
    val p = path(e) ?: return null
    if (p.firstOrNull() != "Icons" || p.size !in 3..4) return null
    val theme = p.drop(1).dropLast(1).map { if (it == "Default") "Filled" else it }
    if (theme.last() !in setOf("Filled", "Outlined", "Rounded", "Sharp", "TwoTone")) return null
    if (theme.size == 2 && theme[0] != "AutoMirrored") return null
    return ImageVector((theme + p.last()).joinToString("."))
  }

  private fun encode(type: String, value: Any): String {
    @Suppress("UNCHECKED_CAST")
    val codec: JsonCodec<Any> = when (type) {
      DP -> DpCodec
      TEXT_UNIT -> TextUnitCodec
      COLOR -> ColorCodec
      SHAPE -> ShapeCodec
      TEXT_STYLE -> TextStyleCodec
      FONT_WEIGHT -> FontWeightCodec
      FONT_STYLE -> FontStyleCodec
      FONT_FAMILY -> FontFamilyCodec
      TEXT_ALIGN -> TextAlignCodec
      TEXT_OVERFLOW -> TextOverflowCodec
      TEXT_DECORATION -> TextDecorationCodec
      ALIGNMENT -> AlignmentCodec
      ALIGNMENT_H -> HorizontalAlignmentCodec
      ALIGNMENT_V -> VerticalAlignmentCodec
      ARRANGEMENT_H -> HorizontalArrangementCodec
      ARRANGEMENT_V -> VerticalArrangementCodec
      PADDING_VALUES -> PaddingValuesCodec
      CONTENT_SCALE -> ContentScaleCodec
      IMAGE_VECTOR -> ImageVectorCodec
      FILTER_QUALITY -> FilterQualityCodec
      else -> error(type)
    } as JsonCodec<Any>
    return Json.encodeToString(JsonElement.serializer(), codec.toJson(value))
  }

  // ── Spelling helpers ────────────────────────────────────────────────────────────────────────

  /** 16f → "16", 16.5f → "16.5". */
  private fun num(f: Float): String = if (f == f.toInt().toFloat()) f.toInt().toString() else f.toString()

  private fun spellTextUnit(u: TextUnit): Spelled = when {
    u.isSp -> Spelled("${num(u.value)}.sp", setOf("$UNIT.sp"))
    u.isEm -> Spelled("${num(u.value)}.em", setOf("$UNIT.em"))
    else -> Spelled("TextUnit.Unspecified", setOf("$UNIT.TextUnit"))
  }

  private fun spellColor(c: Color): Spelled {
    val ref = c.referenceName
    val base = when {
      ref == "LocalContentColor" -> Spelled("LocalContentColor.current", setOf("$M3.LocalContentColor"))
      ref != null -> Spelled("MaterialTheme.${ref}", setOf("$M3.MaterialTheme"))
      c == Color.Unspecified -> Spelled("Color.Unspecified", setOf("$GRAPHICS.Color"))
      else -> Spelled("Color(0x${c.argb.toUInt().toString(16).padStart(8, '0').uppercase()})", setOf("$GRAPHICS.Color"))
    }
    return if (ref != null && c.alpha != 1f) Spelled("${base.text}.copy(alpha = ${num(c.alpha)}f)", base.imports) else base
  }

  private fun spellShape(s: Shape): Spelled {
    val json = ShapeCodec.toJson(s)
    if (json is JsonPrimitive) return Spelled("RectangleShape", setOf("$GRAPHICS.RectangleShape"))
    json.jsonObject["ref"]?.let { return Spelled("MaterialTheme.${it.jsonPrimitive.content}", setOf("$M3.MaterialTheme")) }
    val rounded = "rr" in json.jsonObject
    val name = if (rounded) "RoundedCornerShape" else "CutCornerShape"
    val corners = (json.jsonObject["rr"] ?: json.jsonObject["cut"])!!.jsonArray.map { it.jsonPrimitive }
    val imports = setOf("$SHAPE_PKG.$name")
    if (corners.distinct().size == 1) {
      val c = corners[0]
      if (c.isString) {
        val pct = c.content.removeSuffix("%").toInt()
        return if (rounded && pct == 50) Spelled("CircleShape", setOf("$SHAPE_PKG.CircleShape")) else Spelled("$name(percent = $pct)", imports)
      }
      return Spelled("$name(${num(c.float)}.dp)", imports + "$UNIT.dp")
    }
    if (rounded && corners.none { it.isString }) {
      val parts = CORNERS.zip(corners).filter { it.second.float != 0f }.joinToString(", ") { (n, v) -> "$n = ${num(v.float)}.dp" }
      return Spelled("$name($parts)", imports + "$UNIT.dp")
    }
    error("no spelling for the shape $json")
  }

  private fun spellTextStyle(s: TextStyle): Spelled {
    val imports = mutableSetOf<String>()
    val base = when (val ref = s.reference) {
      null -> "TextStyle".also { imports += "dev.keliver.compose.ui.text.TextStyle" }
      "LocalTextStyle" -> "LocalTextStyle.current".also { imports += "$M3.LocalTextStyle" }
      else -> "MaterialTheme.$ref".also { imports += "$M3.MaterialTheme" }
    }
    val set = (TextStyleCodec.toJson(s) as JsonObject)["set"] as? JsonObject
    val args = set?.entries?.joinToString(", ") { (k, v) ->
      val type = when (k) {
        "color" -> COLOR
        "fontSize", "letterSpacing", "lineHeight" -> TEXT_UNIT
        "fontWeight" -> FONT_WEIGHT
        "fontStyle" -> FONT_STYLE
        "fontFamily" -> FONT_FAMILY
        "textDecoration" -> TEXT_DECORATION
        "textAlign" -> TEXT_ALIGN
        else -> error(k)
      }
      val sp = spell(type, Json.encodeToString(JsonElement.serializer(), v))
      imports += sp.imports
      "$k = ${sp.text}"
    }
    val text = when {
      s.reference == null -> "TextStyle(${args.orEmpty()})"
      args == null -> base
      else -> "$base.copy($args)"
    }
    return Spelled(text, imports)
  }

  private fun spellFontWeight(w: FontWeight): Spelled {
    val name = FONT_WEIGHT_NAMES.firstOrNull { FONT_WEIGHTS[it] == w }
    return Spelled(if (name != null) "FontWeight.$name" else "FontWeight(${w.weight})", setOf("$FONT.FontWeight"))
  }

  private fun <T> spellNamed(owner: String, pkg: String, values: Map<String, T>, v: T): Spelled {
    val name = values.entries.firstOrNull { it.value == v }?.key ?: error("no name for $owner $v")
    return Spelled("$owner.$name", setOf("$pkg.$owner"))
  }

  private fun spellArrangement(j: JsonElement, names: Map<String, Any>, aligns: Map<String, Any>): Spelled {
    val imports = setOf("dev.keliver.compose.foundation.layout.Arrangement")
    if (j is JsonPrimitive) {
      val v = HorizontalArrangementCodec.fromJson(j)
      val name = names.entries.firstOrNull { (it.value as? Arrangement.Horizontal)?.code == v.code || (it.value as? Arrangement.Vertical)?.code == v.code }?.key
        ?: error("no name for the arrangement $j")
      return Spelled("Arrangement.$name", imports)
    }
    val o = j.jsonObject
    val space = "${num(o["sp"]!!.jsonPrimitive.float)}.dp"
    val bias = o["a"]?.jsonPrimitive?.float ?: return Spelled("Arrangement.spacedBy($space)", imports + "$UNIT.dp")
    val align = aligns.entries.firstOrNull { (it.value as? Alignment.Horizontal)?.bias == bias || (it.value as? Alignment.Vertical)?.bias == bias }?.key
      ?: error("no alignment for bias $bias")
    return Spelled("Arrangement.spacedBy($space, Alignment.$align)", imports + "$UNIT.dp" + "dev.keliver.compose.ui.Alignment")
  }

  private fun spellPadding(j: JsonElement): Spelled {
    val imports = setOf("dev.keliver.compose.foundation.layout.PaddingValues", "$UNIT.dp")
    val sides = (PaddingValuesCodec.toJson(PaddingValuesCodec.fromJson(j)) as JsonArray).map { num(it.jsonPrimitive.float) }
    return when {
      sides.distinct().size == 1 -> Spelled("PaddingValues(${sides[0]}.dp)", imports)
      sides[0] == sides[2] && sides[1] == sides[3] -> Spelled("PaddingValues(horizontal = ${sides[0]}.dp, vertical = ${sides[1]}.dp)", imports)
      else -> Spelled("PaddingValues(start = ${sides[0]}.dp, top = ${sides[1]}.dp, end = ${sides[2]}.dp, bottom = ${sides[3]}.dp)", imports)
    }
  }

  private fun spellIcon(name: String): Spelled {
    val theme = name.substringBeforeLast('.')
    val icon = name.substringAfterLast('.')
    val pkg = "dev.keliver.compose.material.icons." + theme.split('.').joinToString(".") { it.lowercase() }
    return Spelled("Icons.$name", setOf("dev.keliver.compose.material.icons.Icons", "$pkg.$icon"))
  }

  // ── Tables ──────────────────────────────────────────────────────────────────────────────────

  const val DP = "dev.keliver.compose.ui.unit.Dp"
  const val TEXT_UNIT = "dev.keliver.compose.ui.unit.TextUnit"
  const val COLOR = "dev.keliver.compose.ui.graphics.Color"
  const val SHAPE = "dev.keliver.compose.ui.graphics.Shape"
  const val TEXT_STYLE = "dev.keliver.compose.ui.text.TextStyle"
  const val FONT_WEIGHT = "dev.keliver.compose.ui.text.font.FontWeight"
  const val FONT_STYLE = "dev.keliver.compose.ui.text.font.FontStyle"
  const val FONT_FAMILY = "dev.keliver.compose.ui.text.font.FontFamily"
  const val TEXT_ALIGN = "dev.keliver.compose.ui.text.style.TextAlign"
  const val TEXT_OVERFLOW = "dev.keliver.compose.ui.text.style.TextOverflow"
  const val TEXT_DECORATION = "dev.keliver.compose.ui.text.style.TextDecoration"
  const val ALIGNMENT = "dev.keliver.compose.ui.Alignment"
  const val ALIGNMENT_H = "dev.keliver.compose.ui.Alignment.Horizontal"
  const val ALIGNMENT_V = "dev.keliver.compose.ui.Alignment.Vertical"
  const val ARRANGEMENT_H = "dev.keliver.compose.foundation.layout.Arrangement.Horizontal"
  const val ARRANGEMENT_V = "dev.keliver.compose.foundation.layout.Arrangement.Vertical"
  const val PADDING_VALUES = "dev.keliver.compose.foundation.layout.PaddingValues"
  const val CONTENT_SCALE = "dev.keliver.compose.ui.layout.ContentScale"
  const val IMAGE_VECTOR = "dev.keliver.compose.ui.graphics.vector.ImageVector"
  const val FILTER_QUALITY = "dev.keliver.compose.ui.graphics.FilterQuality"

  private const val UNIT = "dev.keliver.compose.ui.unit"
  private const val GRAPHICS = "dev.keliver.compose.ui.graphics"
  private const val FONT = "dev.keliver.compose.ui.text.font"
  private const val STYLE = "dev.keliver.compose.ui.text.style"
  private const val M3 = "dev.keliver.compose.material3"
  private const val SHAPE_PKG = "dev.keliver.compose.foundation.shape"

  private val CORNERS = listOf("topStart", "topEnd", "bottomEnd", "bottomStart")
  private val THEME_SHAPES = setOf("extraSmall", "small", "medium", "large", "extraLarge")
  private val TYPE_SCALE = setOf(
    "displayLarge", "displayMedium", "displaySmall", "headlineLarge", "headlineMedium", "headlineSmall",
    "titleLarge", "titleMedium", "titleSmall", "bodyLarge", "bodyMedium", "bodySmall", "labelLarge", "labelMedium", "labelSmall",
  )

  private val COLORS: Map<String, Color> = linkedMapOf(
    "Black" to Color.Black, "DarkGray" to Color.DarkGray, "Gray" to Color.Gray, "LightGray" to Color.LightGray,
    "White" to Color.White, "Red" to Color.Red, "Green" to Color.Green, "Blue" to Color.Blue, "Yellow" to Color.Yellow,
    "Cyan" to Color.Cyan, "Magenta" to Color.Magenta, "Transparent" to Color.Transparent, "Unspecified" to Color.Unspecified,
  )

  /** Compose's preferred names first: a spelled weight is `FontWeight.Bold`, not `FontWeight.W700`. */
  private val FONT_WEIGHT_NAMES = listOf(
    "Thin", "ExtraLight", "Light", "Normal", "Medium", "SemiBold", "Bold", "ExtraBold", "Black",
    "W100", "W200", "W300", "W400", "W500", "W600", "W700", "W800", "W900",
  )
  private val FONT_WEIGHTS: Map<String, FontWeight> = linkedMapOf(
    "Thin" to FontWeight.Thin, "ExtraLight" to FontWeight.ExtraLight, "Light" to FontWeight.Light, "Normal" to FontWeight.Normal,
    "Medium" to FontWeight.Medium, "SemiBold" to FontWeight.SemiBold, "Bold" to FontWeight.Bold, "ExtraBold" to FontWeight.ExtraBold,
    "Black" to FontWeight.Black, "W100" to FontWeight.W100, "W200" to FontWeight.W200, "W300" to FontWeight.W300,
    "W400" to FontWeight.W400, "W500" to FontWeight.W500, "W600" to FontWeight.W600, "W700" to FontWeight.W700,
    "W800" to FontWeight.W800, "W900" to FontWeight.W900,
  )
  private val FONT_STYLES = linkedMapOf("Normal" to FontStyle.Normal, "Italic" to FontStyle.Italic)
  private val FONT_FAMILIES = linkedMapOf(
    "Default" to FontFamily.Default, "SansSerif" to FontFamily.SansSerif, "Serif" to FontFamily.Serif,
    "Monospace" to FontFamily.Monospace, "Cursive" to FontFamily.Cursive,
  )
  private val TEXT_ALIGNS = linkedMapOf(
    "Left" to TextAlign.Left, "Right" to TextAlign.Right, "Center" to TextAlign.Center, "Justify" to TextAlign.Justify,
    "Start" to TextAlign.Start, "End" to TextAlign.End, "Unspecified" to TextAlign.Unspecified,
  )
  private val TEXT_OVERFLOWS = linkedMapOf(
    "Clip" to TextOverflow.Clip, "Ellipsis" to TextOverflow.Ellipsis, "Visible" to TextOverflow.Visible,
    "StartEllipsis" to TextOverflow.StartEllipsis, "MiddleEllipsis" to TextOverflow.MiddleEllipsis,
  )
  private val TEXT_DECORATIONS = linkedMapOf(
    "None" to TextDecoration.None, "Underline" to TextDecoration.Underline, "LineThrough" to TextDecoration.LineThrough,
  )
  private val ALIGNMENTS: Map<String, Alignment> = linkedMapOf(
    "TopStart" to Alignment.TopStart, "TopCenter" to Alignment.TopCenter, "TopEnd" to Alignment.TopEnd,
    "CenterStart" to Alignment.CenterStart, "Center" to Alignment.Center, "CenterEnd" to Alignment.CenterEnd,
    "BottomStart" to Alignment.BottomStart, "BottomCenter" to Alignment.BottomCenter, "BottomEnd" to Alignment.BottomEnd,
  )
  private val H_ALIGNMENTS: Map<String, Any> = linkedMapOf(
    "Start" to Alignment.Start, "CenterHorizontally" to Alignment.CenterHorizontally, "End" to Alignment.End,
  )
  private val V_ALIGNMENTS: Map<String, Any> = linkedMapOf(
    "Top" to Alignment.Top, "CenterVertically" to Alignment.CenterVertically, "Bottom" to Alignment.Bottom,
  )
  private val H_ARRANGEMENTS: Map<String, Any> = linkedMapOf(
    "Start" to Arrangement.Start, "End" to Arrangement.End, "Center" to Arrangement.Center,
    "SpaceBetween" to Arrangement.SpaceBetween, "SpaceAround" to Arrangement.SpaceAround, "SpaceEvenly" to Arrangement.SpaceEvenly,
  )
  private val V_ARRANGEMENTS: Map<String, Any> = linkedMapOf(
    "Top" to Arrangement.Top, "Bottom" to Arrangement.Bottom, "Center" to Arrangement.Center,
    "SpaceBetween" to Arrangement.SpaceBetween, "SpaceAround" to Arrangement.SpaceAround, "SpaceEvenly" to Arrangement.SpaceEvenly,
  )
  private val CONTENT_SCALES = linkedMapOf(
    "Crop" to ContentScale.Crop, "Fit" to ContentScale.Fit, "FillHeight" to ContentScale.FillHeight, "FillWidth" to ContentScale.FillWidth,
    "Inside" to ContentScale.Inside, "None" to ContentScale.None, "FillBounds" to ContentScale.FillBounds,
  )
  private val FILTER_QUALITIES = linkedMapOf(
    "None" to FilterQuality.None, "Low" to FilterQuality.Low, "Medium" to FilterQuality.Medium, "High" to FilterQuality.High,
  )
}
