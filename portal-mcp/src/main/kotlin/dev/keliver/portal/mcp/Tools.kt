package dev.keliver.portal.mcp

import dev.keliver.portal.document.DocJson
import dev.keliver.portal.document.DocNode
import dev.keliver.portal.document.PropValue
import dev.keliver.portal.document.UiDocument
import dev.keliver.portal.modifierSpecs
import dev.keliver.portal.widgetSpecs
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64

object Tools {
  val server: String = System.getenv("PORTAL_SERVER") ?: "http://localhost:8077"
  private val http = HttpClient.newHttpClient()

  private fun get(path: String): String =
    http.send(
      HttpRequest.newBuilder(URI.create("$server$path")).GET().build(),
      HttpResponse.BodyHandlers.ofString(),
    ).body()

  private fun post(path: String, body: String, session: String): Pair<Int, String> {
    val r = http.send(
      HttpRequest.newBuilder(URI.create("$server$path"))
        .header("X-Portal-Session", session)
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build(),
      HttpResponse.BodyHandlers.ofString(),
    )
    return r.statusCode() to r.body()
  }

  private fun JsonObject.str(name: String, default: String? = null): String =
    this[name]?.jsonPrimitive?.content ?: default
    ?: throw IllegalArgumentException("missing required argument '$name'")

  /** The generated catalog as machine-readable JSON — grounds agent generation. */
  private val catalogJson: String by lazy {
    buildJsonObject {
      put("widgets", buildJsonArray {
        widgetSpecs.forEach { w ->
          add(buildJsonObject {
            put("type", w.type)
            put("category", w.category)
            put("acceptsChildren", w.acceptsChildren)
            put("props", buildJsonArray {
              w.props.forEach { p ->
                add(buildJsonObject { put("name", p.name); put("kind", p.kind.name); put("label", p.label) })
              }
            })
            put("events", buildJsonArray { w.events.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
          })
        }
      })
      put("modifiers", buildJsonArray {
        modifierSpecs.forEach { m ->
          add(buildJsonObject {
            put("name", m.name)
            put("props", buildJsonArray {
              m.props.forEach { p ->
                add(buildJsonObject { put("name", p.name); put("kind", p.kind.name) })
              }
            })
          })
        }
      })
      put("opSchema", "ops use kind=dev.keliver.portal.document.DocOp.<InsertNode|DeleteNode|MoveNode|SetProp|RemoveProp|SetModifier|RemoveModifier|RenameId|ReplaceRaw>; prop values kind=...PropValue.<Lit|Bind|Action>; Lit tags: s/i/d/b/li/lf; positions are sibling-anchored ('after' handle or null=first); new nodes use handle 0 (server allocates)")
    }.toString()
  }

  private fun findUsages(project: String, name: String): String {
    val screens = Regex("\"([^\"]+)\"").findAll(get("/screens?project=$project")).map { it.groupValues[1] }
    val hits = StringBuilder()
    screens.forEach { screen ->
      val doc = runCatching { DocJson.decodeFromString<UiDocument>(get("/doc?project=$project&screen=$screen")) }.getOrNull()
        ?: return@forEach
      fun walk(n: DocNode) {
        if (n is DocNode.Widget) {
          n.props.forEach { (prop, v) ->
            when (v) {
              is PropValue.Bind -> if (v.field == name) hits.appendLine("$screen: ${n.type}#${n.handle.v}.$prop binds field '$name'")
              is PropValue.Action -> if (v.name == name) hits.appendLine("$screen: ${n.type}#${n.handle.v}.$prop wires action '$name'")
              else -> {}
            }
          }
          n.children.forEach(::walk)
        }
      }
      walk(doc.root)
    }
    return hits.toString().ifEmpty { "no usages of '$name' in project '$project'" }
  }

  val registry: List<Tool> = listOf(
    Tool(
      "get_catalog",
      "The generated keliver widget/modifier catalog + op schema. Call FIRST: grounds every widget type, prop name, and prop kind you may use.",
      emptyMap(),
    ) { toolText(catalogJson) },

    Tool(
      "get_guide",
      "The portal usage guide (architecture, edit loops, publish).",
      emptyMap(),
    ) {
      val f = File(System.getenv("PORTAL_REPO") ?: ".", "docs/PORTAL_USAGE.md")
      toolText(if (f.exists()) f.readText() else "guide not found at ${f.absolutePath}")
    },

    Tool("list_projects", "List portal projects.", emptyMap()) { toolText(get("/projects")) },

    Tool(
      "list_screens", "List screens in a project.",
      mapOf("project" to "project name (default 'default')"),
    ) { args -> toolText(get("/screens?project=${args.str("project", "default")}")) },

    Tool(
      "get_document",
      "The screen's semantic document: nodes with stable handles, props (Lit/Bind/Action), modifiers, version. Ops target these handles.",
      mapOf("project" to "project (default 'default')", "screen" to "screen (default 'main')"),
    ) { args ->
      toolText(get("/doc?project=${args.str("project", "default")}&screen=${args.str("screen", "main")}"))
    },

    Tool(
      "apply_ops",
      "Apply a transactional op batch to the document (all-or-nothing, one undo entry). Set dryRun='1' to validate WITHOUT committing. batchJson = {baseVersion, envelope:{session,atMillis}, ops:[...]} per the opSchema in get_catalog. On 409 refetch the document; on error read the message — it names the exact problem.",
      mapOf(
        "project" to "project (default 'default')",
        "screen" to "screen (default 'main')",
        "batchJson" to "the OpBatch JSON",
        "dryRun" to "'1' = validate only",
      ),
      required = listOf("batchJson"),
    ) { args ->
      val dry = if (args.str("dryRun", "") == "1") "&dryRun=1" else ""
      val (code, body) = post(
        "/ops?project=${args.str("project", "default")}&screen=${args.str("screen", "main")}$dry",
        args.str("batchJson"),
        session = "agent",
      )
      toolText(body, isError = code !in 200..299)
    },

    Tool(
      "undo", "Undo the agent session's last batch (server-side).",
      mapOf("project" to "project (default 'default')", "screen" to "screen (default 'main')"),
    ) { args ->
      val (code, body) = post("/undo?project=${args.str("project", "default")}&screen=${args.str("screen", "main")}", "", "agent")
      toolText(body, isError = code !in 200..299)
    },

    Tool(
      "redo", "Redo the agent session's last undone batch.",
      mapOf("project" to "project (default 'default')", "screen" to "screen (default 'main')"),
    ) { args ->
      val (code, body) = post("/redo?project=${args.str("project", "default")}&screen=${args.str("screen", "main")}", "", "agent")
      toolText(body, isError = code !in 200..299)
    },

    Tool(
      "find_usages",
      "Where a bound field or wired action name is used across a project's screens.",
      mapOf("project" to "project (default 'default')", "name" to "field or action name"),
      required = listOf("name"),
    ) { args -> toolText(findUsages(args.str("project", "default"), args.str("name"))) },

    Tool(
      "device_screenshot",
      "Screenshot the connected Android dev device/emulator (mirrors the active screen ~1s after edits). Best-effort: errors if no device.",
      emptyMap(),
    ) {
      val adb = File(System.getProperty("user.home"), "Library/Android/sdk/platform-tools/adb")
      val proc = ProcessBuilder(adb.absolutePath, "exec-out", "screencap", "-p").start()
      val bytes = proc.inputStream.readBytes()
      proc.waitFor()
      if (bytes.size < 1000) toolText("no device or screencap failed", isError = true)
      else toolImage(Base64.getEncoder().encodeToString(bytes))
    },
  )
}
