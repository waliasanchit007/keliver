package dev.keliver.portal

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/*
 * Type-tagged tree (de)serialization for pushing a WidgetNode tree over the wire
 * (M2 device sync). props is Map<String, Any?>; the render getters need the exact
 * Kotlin type back, so every value is tagged with its kind (i/d/b/s/li/lf).
 *
 * K2: a design-token value carries its RESOLVED literal under the usual tag plus
 * `"token"` (the source reference) and `"tokenImport"`: a reader that predates
 * tokens (an installed device guest) sees — and renders — the plain literal.
 *
 * K3: a conditional value carries its TRUE branch under that branch's usual
 * keys, plus `"cond"` (`{"field", "test"?, "negate"?}`) and `"else"` (the
 * false branch, absent = Kotlin `null`); a null true branch is `{"null":true}`.
 * A reader that predates conditionals decodes the true branch.
 *
 * K4: a template carries its text — literal parts, token values, and a
 * `{field}` placeholder for each binding — under `"s"`, plus `"template"`
 * (each part encoded as a value). A list with references carries its
 * resolved elements under its usual tag (`"li"`/`"lst"`; a binding as 0 or
 * its placeholder) plus `"items"` (each element encoded as a value). A reader
 * that predates K4 decodes the text / the resolved list.
 */

private val JSON = Json { }

private fun encodeValue(v: Any?): JsonObject = buildJsonObject {
  if (v is Conditional) {
    encodeBranch(v.whenTrue).forEach { (k, e) -> put(k, e) }
    put("cond", buildJsonObject {
      put("field", v.cond.field)
      v.cond.test.wire?.let { put("test", it) }
      if (v.cond.negate) put("negate", true)
    })
    v.whenFalse?.let { put("else", encodeValue(it)) }
    return@buildJsonObject
  }
  if (v is Template) {
    put("s", templateText(v) { null })
    put("template", buildJsonArray { v.parts.forEach { add(encodeValue(it)) } })
    return@buildJsonObject
  }
  if (v is ListOf) {
    if (v.kind == "ls") {
      put("lst", buildJsonArray { v.items.forEach { add(JsonPrimitive(templateValueText(it) ?: (it as? Bind)?.let { b -> "{${b.field}}" } ?: "")) } })
    } else {
      put("li", buildJsonArray { v.items.forEach { add(JsonPrimitive((tokenValue(it) as? Int) ?: 0)) } })
    }
    put("items", buildJsonArray { v.items.forEach { add(encodeValue(it)) } })
    return@buildJsonObject
  }
  if (v is Token) {
    encodeValue(v.value).forEach { (k, e) -> put(k, e) }
    put("token", v.ref)
    v.importFq?.let { put("tokenImport", it) }
    return@buildJsonObject
  }
  when (v) {
    is Int -> put("i", v)
    is Double -> put("d", v)
    is Boolean -> put("b", v)
    is String -> put("s", v)
    is Bind -> put("bind", v.field)
    is Action -> {
      put("action", v.name)
      v.arg?.let { put("actionArg", it) }
    }
    is List<*> -> when (v.firstOrNull()) {
      is Float -> put("lf", buildJsonArray { v.forEach { add(JsonPrimitive(it as Float)) } })
      is String -> put("lst", buildJsonArray { v.forEach { add(JsonPrimitive(it as String)) } })
      else -> put("li", buildJsonArray { v.forEach { add(JsonPrimitive(it as Int)) } })
    }
    else -> put("s", v?.toString() ?: "")
  }
}

/** A conditional branch: Kotlin `null` is `{"null":true}` (a plain null prop stays as it always was). */
private fun encodeBranch(v: Any?): JsonObject = if (v == null) buildJsonObject { put("null", true) } else encodeValue(v)

private fun decodeValue(o: JsonObject): Any? = when {
  "cond" in o -> o.getValue("cond").jsonObject.let { c ->
    Conditional(
      Cond(
        c.getValue("field").jsonPrimitive.content,
        CondTest.fromWire(c["test"]?.jsonPrimitive?.content) ?: CondTest.Bool,
        c["negate"]?.jsonPrimitive?.boolean == true,
      ),
      decodeValue(JsonObject(o - "cond" - "else")),
      o["else"]?.jsonObject?.let(::decodeValue),
    )
  }
  "null" in o -> null
  "template" in o -> Template(o.getValue("template").jsonArray.map { decodeValue(it.jsonObject) })
  "items" in o -> ListOf(if ("lst" in o) "ls" else "li", o.getValue("items").jsonArray.map { decodeValue(it.jsonObject) })
  "token" in o -> Token(
    o.getValue("token").jsonPrimitive.content,
    decodeValue(JsonObject(o - "token" - "tokenImport")),
    o["tokenImport"]?.jsonPrimitive?.content,
  )
  "i" in o -> o.getValue("i").jsonPrimitive.int
  "d" in o -> o.getValue("d").jsonPrimitive.double
  "b" in o -> o.getValue("b").jsonPrimitive.boolean
  "s" in o -> o.getValue("s").jsonPrimitive.content
  "li" in o -> o.getValue("li").jsonArray.map { it.jsonPrimitive.int }
  "lf" in o -> o.getValue("lf").jsonArray.map { it.jsonPrimitive.float }
  "lst" in o -> o.getValue("lst").jsonArray.map { it.jsonPrimitive.content }
  "bind" in o -> Bind(o.getValue("bind").jsonPrimitive.content)
  "action" in o -> Action(o.getValue("action").jsonPrimitive.content, o["actionArg"]?.jsonPrimitive?.content)
  else -> null
}

private fun nodeToJson(node: WidgetNode): JsonObject = buildJsonObject {
  put("type", node.type)
  put("id", node.id)
  put("props", buildJsonObject { node.props.forEach { (k, v) -> put(k, encodeValue(v)) } })
  put("children", buildJsonArray { node.children.forEach { add(nodeToJson(it)) } })
}

private fun jsonToNode(o: JsonObject): WidgetNode = WidgetNode(
  type = o.getValue("type").jsonPrimitive.content,
  props = o.getValue("props").jsonObject.mapValues { decodeValue(it.value.jsonObject) },
  children = o.getValue("children").jsonArray.map { jsonToNode(it.jsonObject) },
  id = o.getValue("id").jsonPrimitive.int,
)

public fun serializeTree(node: WidgetNode): String = nodeToJson(node).toString()

public fun deserializeTree(json: String): WidgetNode = jsonToNode(JSON.parseToJsonElement(json).jsonObject)
