/*
 * portal-mcp — MCP (Model Context Protocol) stdio server for the keliver
 * portal. One JSON-RPC 2.0 object per line on stdin/stdout; implements the
 * MCP subset agents need: initialize, tools/list, tools/call.
 *
 * Run:   ./gradlew :portal-mcp:installDist
 *        portal-mcp/build/install/portal-mcp/bin/portal-mcp
 * Env:   PORTAL_SERVER (default http://localhost:8077)
 */
package dev.keliver.portal.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

val json = Json { ignoreUnknownKeys = true }

fun main() {
  System.err.println("portal-mcp: ready (server=${Tools.server})")
  while (true) {
    val line = readLine() ?: break
    if (line.isBlank()) continue
    val msg = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: continue
    val id = msg["id"]
    val method = msg["method"]?.jsonPrimitive?.content ?: continue
    if (id == null) continue // notification (e.g. notifications/initialized) — no response
    val result: JsonObject = when (method) {
      "initialize" -> buildJsonObject {
        put("protocolVersion", "2024-11-05")
        putJsonObject("capabilities") { putJsonObject("tools") {} }
        putJsonObject("serverInfo") {
          put("name", "portal-mcp")
          put("version", "0.1.0")
        }
      }
      "tools/list" -> buildJsonObject {
        put("tools", buildJsonArray { Tools.registry.forEach { add(it.descriptor) } })
      }
      "tools/call" -> {
        val params = msg["params"]?.jsonObject
        val name = params?.get("name")?.jsonPrimitive?.content
        val args = params?.get("arguments")?.jsonObject ?: JsonObject(emptyMap())
        val tool = Tools.registry.firstOrNull { it.name == name }
        if (tool == null) {
          toolText("unknown tool: $name; available: ${Tools.registry.joinToString { it.name }}", isError = true)
        } else {
          runCatching { tool.call(args) }.getOrElse { toolText("tool failed: ${it.message}", isError = true) }
        }
      }
      "ping" -> JsonObject(emptyMap())
      else -> buildJsonObject { put("error", "unsupported method $method") }
    }
    val response = buildJsonObject {
      put("jsonrpc", "2.0")
      put("id", id)
      put("result", result)
    }
    println(response.toString())
    System.out.flush()
  }
}

/** MCP tool result: text content (+isError steers the agent without failing the RPC). */
fun toolText(text: String, isError: Boolean = false): JsonObject = buildJsonObject {
  put("content", buildJsonArray {
    add(buildJsonObject { put("type", "text"); put("text", text) })
  })
  if (isError) put("isError", true)
}

fun toolImage(base64Png: String): JsonObject = buildJsonObject {
  put("content", buildJsonArray {
    add(buildJsonObject { put("type", "image"); put("data", base64Png); put("mimeType", "image/png") })
  })
}

class Tool(
  val name: String,
  description: String,
  params: Map<String, String>, // name -> description (all string-typed; batchJson carries structure)
  required: List<String> = emptyList(),
  val call: (JsonObject) -> JsonObject,
) {
  val descriptor: JsonElement = buildJsonObject {
    put("name", name)
    put("description", description)
    putJsonObject("inputSchema") {
      put("type", "object")
      putJsonObject("properties") {
        params.forEach { (p, d) -> putJsonObject(p) { put("type", "string"); put("description", d) } }
      }
      put("required", buildJsonArray { required.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
    }
  }
}
