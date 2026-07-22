import dev.keliver.portal.ComponentEventSpec
import dev.keliver.portal.ComponentRegistry
import dev.keliver.portal.ComponentSpec
import dev.keliver.portal.ComponentSlotSpec
import dev.keliver.portal.EmptyComponentRegistry
import dev.keliver.portal.MapComponentRegistry
import dev.keliver.portal.PropKind
import dev.keliver.portal.PropSpec
import dev.keliver.portal.deserializeTree
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
var editorComponents: ComponentRegistry = EmptyComponentRegistry
  private set

fun setEditorComponents(reg: ComponentRegistry) { editorComponents = reg }

fun parseComponents(json: String): ComponentRegistry = runCatching {
  val arr = Json.parseToJsonElement(json).jsonArray
  val specs = arr.map { el ->
    val o = el.jsonObject
    val props = o["props"]!!.jsonArray.map { p ->
      val po = p.jsonObject
      PropSpec(
        po["name"]!!.jsonPrimitive.content,
        PropKind.valueOf(po["kind"]!!.jsonPrimitive.content),
        po["label"]!!.jsonPrimitive.content,
      )
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
      paramTypes = emptyMap(), // contract typing is relay-side; editor doesn't need it
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
