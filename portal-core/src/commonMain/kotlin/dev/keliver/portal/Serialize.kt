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
 */

private val JSON = Json { }

private fun encodeValue(v: Any?): JsonObject = buildJsonObject {
  when (v) {
    is Int -> put("i", v)
    is Double -> put("d", v)
    is Boolean -> put("b", v)
    is String -> put("s", v)
    is Bind -> put("bind", v.field)
    is Action -> put("action", v.name)
    is List<*> -> {
      if (v.firstOrNull() is Float) put("lf", buildJsonArray { v.forEach { add(JsonPrimitive(it as Float)) } })
      else put("li", buildJsonArray { v.forEach { add(JsonPrimitive(it as Int)) } })
    }
    else -> put("s", v?.toString() ?: "")
  }
}

private fun decodeValue(o: JsonObject): Any? = when {
  "i" in o -> o.getValue("i").jsonPrimitive.int
  "d" in o -> o.getValue("d").jsonPrimitive.double
  "b" in o -> o.getValue("b").jsonPrimitive.boolean
  "s" in o -> o.getValue("s").jsonPrimitive.content
  "li" in o -> o.getValue("li").jsonArray.map { it.jsonPrimitive.int }
  "lf" in o -> o.getValue("lf").jsonArray.map { it.jsonPrimitive.float }
  "bind" in o -> Bind(o.getValue("bind").jsonPrimitive.content)
  "action" in o -> Action(o.getValue("action").jsonPrimitive.content)
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

fun serializeTree(node: WidgetNode): String = nodeToJson(node).toString()

fun deserializeTree(json: String): WidgetNode = jsonToNode(JSON.parseToJsonElement(json).jsonObject)
