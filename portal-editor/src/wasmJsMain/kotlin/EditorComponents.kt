import dev.keliver.portal.Bind
import dev.keliver.portal.ComponentEventSpec
import dev.keliver.portal.ComponentRegistry
import dev.keliver.portal.ComponentSpec
import dev.keliver.portal.ComponentSlotSpec
import dev.keliver.portal.EmptyComponentRegistry
import dev.keliver.portal.MapComponentRegistry
import dev.keliver.portal.PropKind
import dev.keliver.portal.PropChoice
import dev.keliver.portal.PropSpec
import dev.keliver.portal.WidgetNode
import dev.keliver.portal.deserializeTree
import dev.keliver.portal.isColorParamName
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * C3: the editor's project-scoped component registry, parsed from `/components`.
 * Shared by the palette, property panel, and the preview expander hook (set in
 * Main). Reassigned whenever the project's registry changes (SSE/poll or reload).
 */
internal var editorComponents: ComponentRegistry = EmptyComponentRegistry
  private set

internal fun setEditorComponents(reg: ComponentRegistry) { editorComponents = reg }

/**
 * Bumped when something the canvas draws with changes outside the screen's tree — the
 * registry's definitions (an instance whose component changed) or a font that arrived — so the
 * canvas is drawn again (RenderNode's input, the screen's tree, did not change).
 */
internal val canvasGeneration = androidx.compose.runtime.mutableIntStateOf(0)

/**
 * F8: the named values a component's [param] takes when its body passes it straight to a widget
 * property that has them — `StyledText(textAlign = align)` → Start, Center, End — also through
 * another component it passes the parameter on to. Empty when the parameter is used otherwise.
 */
internal fun forwardedChoices(
  spec: ComponentSpec,
  param: String,
  registry: ComponentRegistry,
  widgetChoices: (type: String, prop: String) -> List<PropChoice>,
  seen: Set<String> = emptySet(),
): List<PropChoice> {
  if (spec.name in seen) return emptyList()
  fun walk(n: WidgetNode): List<PropChoice>? {
    for ((key, value) in n.props) {
      if (value != Bind(param)) continue
      widgetChoices(n.type, key).takeIf { it.isNotEmpty() }?.let { return it }
      registry.spec(n.type)?.let { inner ->
        forwardedChoices(inner, key, registry, widgetChoices, seen + spec.name).takeIf { it.isNotEmpty() }?.let { return it }
      }
    }
    return n.children.firstNotNullOfOrNull(::walk)
  }
  return spec.body?.let(::walk).orEmpty()
}

internal fun parseComponents(json: String): ComponentRegistry = runCatching {
  val arr = Json.parseToJsonElement(json).jsonArray
  val specs = arr.map { el ->
    val o = el.jsonObject
    val props = o["props"]!!.jsonArray.map { p ->
      val po = p.jsonObject
      val name = po["name"]!!.jsonPrimitive.content
      val kind = PropKind.valueOf(po["kind"]!!.jsonPrimitive.content)
      // E10: a colour-named Int param is a colour (as a current relay already sends it).
      PropSpec(name, if (kind == PropKind.Int && isColorParamName(name)) PropKind.Color else kind, po["label"]!!.jsonPrimitive.content)
    }
    val events = o["events"]!!.jsonArray.map { e ->
      val eo = e.jsonObject
      ComponentEventSpec(
        eo["name"]!!.jsonPrimitive.content,
        eo["paramType"]?.jsonPrimitive?.content,
        required = eo["required"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true,
      )
    }
    val slots = o["slots"]?.jsonArray?.map { s ->
      val so = s.jsonObject
      ComponentSlotSpec(
        so["name"]!!.jsonPrimitive.content,
        required = so["required"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true,
      )
    }.orEmpty()
    val defaults = o["defaults"]!!.jsonObject.mapValues { (_, v) -> jsonToAny(v.toString()) }
    val bodyEl = o["body"]
    val body = if (bodyEl == null || bodyEl is JsonNull) null else deserializeTree(bodyEl.toString())
    ComponentSpec(
      name = o["name"]!!.jsonPrimitive.content,
      props = props,
      events = events,
      // K5: needed for acceptsModifier (modifier panel + preview expansion).
      // A relay older than K5 sends none: no component then takes modifiers here.
      paramTypes = o["paramTypes"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content }.orEmpty(),
      slots = slots,
      defaults = defaults,
      body = body,
      transparent = o["transparent"]!!.jsonPrimitive.boolean,
      dependencies = o["dependencies"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet(),
      diagnostic = o["diagnostic"]?.jsonPrimitive?.content,
    )
  }
  MapComponentRegistry(specs)
}.getOrDefault(EmptyComponentRegistry)

private fun jsonToAny(raw: String): Any? {
  val el = runCatching { Json.parseToJsonElement(raw) }.getOrNull() ?: return raw
  val prim = runCatching { el.jsonPrimitive }.getOrNull() ?: return raw
  if (prim is JsonNull) return null
  if (prim.isString) return prim.content
  prim.intOrNull?.let { return it }
  prim.doubleOrNull?.let { return it }
  return when (prim.content) { "true" -> true; "false" -> false; else -> prim.content }
}
