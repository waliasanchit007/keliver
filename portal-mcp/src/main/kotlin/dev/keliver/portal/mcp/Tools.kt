package dev.keliver.portal.mcp

import dev.keliver.portal.document.DocJson
import dev.keliver.portal.document.DocNode
import dev.keliver.portal.document.PropValue
import dev.keliver.portal.document.UiDocument
import dev.keliver.portal.document.condition
import dev.keliver.portal.document.operands
import dev.keliver.portal.modifierSpecs
import dev.keliver.portal.widgetSpecs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
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
            // K1: scoped layout modifiers are valid only under these parent widget types.
            if (m.scopes.isNotEmpty()) {
              put("scopes", buildJsonArray { m.scopes.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
            }
            // The source call: `Modifier.<extension>(<argTypes…>)` — e.g. LayoutSize is `size(Dp, Dp)`.
            put("extension", m.extension)
            put("argTypes", buildJsonArray { m.argTypes.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
            put("props", buildJsonArray {
              m.props.forEach { p ->
                add(buildJsonObject { put("name", p.name); put("kind", p.kind.name) })
              }
            })
          })
        }
      })
      put("opSchema", "ops use kind=dev.keliver.portal.document.DocOp.<InsertNode|DeleteNode|MoveNode|SetProp|RemoveProp|SetModifier|RemoveModifier|RenameId|ReplaceRaw>; prop values kind=...PropValue.<Lit|Bind|Action|Token|Conditional|Template|ListOf>; Lit tags: s/i/d/b/li/lf/ls; Token = {ref, value: Lit, import} names a design-token constant from list_tokens (ref + import exactly as listed; the server supplies the value) and fits only a prop/modifier argument of its kind (Int token on an Int/Color prop, String on Text, Double on Double, Boolean on Bool — never Dp, Float or enum props); Conditional = {field, test?, negate?, whenTrue?, whenFalse?, operand?, more?, join?} is `if (<condition>) whenTrue else whenFalse` on the screen binding `field` (or `item.x` inside a Repeat over `item`; test omitted = the Boolean itself, \"empty\"/\"blank\" = String isEmpty()/isBlank(), \"eq\" = `field == operand` where operand is a Lit s/i/b or a Token of a String, Int or Boolean kind — never a Double, a binding or Int.MIN_VALUE — and the field's type is the operand's; negate = `!`/isNot…/`!=`; a Boolean test never reads a field the screen uses as a list or text, unless the Bindings interface declares it Boolean; more = further terms [{field, test?, negate?, operand?}] joined by join: omitted = `&&`, \"or\" = `||` — never mixed, never nested), each branch a Lit/Bind/Action/Token of the prop's kind, an omitted branch = null (only on a nullable event or component parameter; at least one branch), never nested, never on a list prop; Template = {parts} is a String template (`\"Rating: ${'$'}{b.rating}\"`) and fits only a String prop or a component's String parameter (also as a Conditional branch): each part is a Lit s (text, its decoded value), a Bind (a screen field, or item.x inside a Repeat over item; never a field the Bindings interface declares Double, Float, nullable or without a type, nor an item field its item type declares Double or nullable, nor a field a Repeat iterates, nor one the screen's file cannot type — the document's contract.unlistedFieldsUnknown says why: no Bindings parameter, an interface not in the file or extending one declared elsewhere — bind such a field directly instead; on such a screen a binding or action of a field the file neither declares nor reads yet is refused too, since it may be inherited) or a Token of a String, Int or Boolean kind (never a Double: the device, Kotlin/JS, prints 1.0 as 1 where the preview prints 1.0, and a null as null where the preview prints nothing) — never an expression; the server stores it canonical (adjacent text merged, no reference left = a plain Lit); ListOf = {tag, items} is a list prop's value with references (`listOf(C.Start, -1)`): tag li (a List<Int> prop) or ls (a List<String> prop), each item a Lit of that kind (tag i/s), a Token of that kind or a Bind, never conditional, never on a component; a Condition node takes props field (a plain binding name)/test/negate/operand, its further terms as field.1/test.1/negate.1/operand.1, field.2/… and join (\"or\"; omitted = `&&`) — a compound or equality Condition is inserted whole, after which single ops change only a field, a negation, an operand or the join (SetProp join \"or\", RemoveProp for `&&`); a term is refused on a field whose declared type is known not to take it (a Boolean test on a number, String or collection, isEmpty() on a number or Boolean, isBlank() on a collection, anything nullable); an equality on a field whose type the screen cannot read (an item field, an interface declared elsewhere) only with the operand kind the screen already compares it with — and its last child may be one Else node (its else branch); positions are sibling-anchored ('after' handle or null=first); new nodes use handle 0 (server allocates); an op whose write-back to the screen's file would be refused is refused when it is made (its error gives the reason: make that change in code), as are undo/redo steps")
    }.toString()
  }

  /** A token's declaration: its import plus the member (`app.components.C` + `Ink`), or the reference itself. */
  internal fun tokenFqName(t: PropValue.Token): String {
    val import = t.import ?: return t.ref
    return if ('.' in t.ref) "$import.${t.ref.substringAfter('.')}" else import
  }

  private fun findUsages(project: String, name: String): String {
    val screens = Regex("\"([^\"]+)\"").findAll(get("/screens?project=$project")).map { it.groupValues[1] }
    val hits = StringBuilder()
    screens.forEach { screen ->
      val doc = runCatching { DocJson.decodeFromString<UiDocument>(get("/doc?project=$project&screen=$screen&tokens=1&conditionals=1&extendedConditions=1&templates=1&lists=1")) }.getOrNull()
        ?: return@forEach
      usagesIn(screen, doc, name).forEach { hits.appendLine(it) }
    }
    return hits.toString().ifEmpty { "no usages of '$name' in project '$project'" }
  }

  /**
   * Where [name] — a bound field, a wired action, or (K2) a design token — is
   * used in [doc]. A token matches by its fully-qualified name
   * (`app.components.C.Ink`), or by its reference (`C.Ink`) — each hit names
   * the declaration, so same-named tokens of different packages stay apart.
   */
  internal fun usagesIn(screen: String, doc: UiDocument, name: String): List<String> {
    val hits = mutableListOf<String>()
    fun check(n: DocNode.Widget, key: String, v: PropValue) {
      when (v) {
        // K3: a conditional reads its condition's field and holds its branches.
        // K6: every term's field, and each equality operand (a token is a usage).
        is PropValue.Conditional -> {
          if (name in (listOf(v.field) + v.more.map { it.field })) hits += "$screen: ${n.type}#${n.handle.v}.$key is chosen by field '$name'"
          v.operands().forEach { check(n, key, it) }
          listOfNotNull(v.whenTrue, v.whenFalse).forEach { check(n, key, it) }
        }
        is PropValue.Bind -> if (v.field == name) hits += "$screen: ${n.type}#${n.handle.v}.$key binds field '$name'"
        is PropValue.Action -> if (v.name == name) hits += "$screen: ${n.type}#${n.handle.v}.$key wires action '$name'"
        is PropValue.Token -> {
          val fq = tokenFqName(v)
          if (name == fq || name == v.ref) hits += "$screen: ${n.type}#${n.handle.v}.$key uses token '${v.ref}' ($fq)"
        }
        // K4: a template's references and a list's elements.
        is PropValue.Template -> v.parts.forEach { check(n, key, it) }
        is PropValue.ListOf -> v.items.forEach { check(n, key, it) }
        else -> {}
      }
    }
    fun walk(n: DocNode) {
      if (n is DocNode.Widget) {
        val fields = if (n.type == "Condition") n.condition()?.terms?.map { it.field } ?: listOfNotNull((n.props["field"] as? PropValue.Lit)?.s) else emptyList()
        if (name in fields) {
          hits += "$screen: Condition#${n.handle.v} tests field '$name'"
        }
        n.props.forEach { (prop, v) -> check(n, prop, v) }
        n.modifiers.forEach { (key, v) -> check(n, "mod.$key", v) }
        n.children.forEach(::walk)
      }
    }
    walk(doc.root)
    return hits
  }

  /**
   * Screen ids as `list_screens` returns them are BARE (`home`). The relay's
   * own boot log prints the qualified form (`selected 'default/home'`), and
   * passing that back used to be forwarded verbatim: the server answered a
   * request for the screen literally named `default/home` with a fresh EMPTY
   * document under `default/default_home` instead of an error. A wrong answer
   * from a successful call is the worst shape for a tool an agent is meant to
   * trust, so the id is normalised here and an unknown screen is rejected with
   * the valid ids named. See KNOWN_BUGS U16.
   */
  internal fun normalizeScreen(project: String, screen: String): String {
    val s = screen.trim().trim('/')
    val slash = s.indexOf('/')
    if (slash <= 0) return s
    val prefix = s.substring(0, slash)
    val rest = s.substring(slash + 1)
    return if (prefix == project && rest.isNotEmpty()) rest else s
  }

  /** Screen names the relay reports for a project, or null if it could not be asked. */
  private fun knownScreens(project: String): List<String>? =
    runCatching {
      Json.parseToJsonElement(get("/screens?project=$project")).jsonArray
        .map { it.jsonPrimitive.content }
    }.getOrNull()

  /** null when the screen is usable; an error payload naming the valid ids otherwise. */
  private fun rejectUnknownScreen(project: String, screen: String): JsonObject? {
    val known = knownScreens(project) ?: return null
    if (screen in known) return null
    return toolText(
      "no screen '$screen' in project '$project'. Known screens: ${known.joinToString(", ")}. " +
        "Use the bare name as list_screens returns it, not a 'project/screen' id.",
      isError = true,
    )
  }

  /**
   * The usage guide.
   *
   * It used to be read only from `<PORTAL_REPO>/docs/PORTAL_USAGE.md` — a path
   * that exists in the Keliver repository and in no adopter's app — so the tool
   * worked when run from this checkout and answered "guide not found" for
   * everyone else. The canonical copy now ships inside the package as a
   * classpath resource; an app that keeps its own `docs/PORTAL_USAGE.md` still
   * wins, so a team can document its own portal conventions.
   */
  internal fun guideText(
    repo: String? = System.getenv("PORTAL_REPO"),
    resource: () -> java.io.InputStream? = { Tools::class.java.getResourceAsStream("PORTAL_USAGE.md") },
  ): String {
    val override = File(repo ?: ".", "docs/PORTAL_USAGE.md")
    if (override.isFile) return override.readText()
    resource()?.use { return it.readBytes().decodeToString() }
    return "guide unavailable: no docs/PORTAL_USAGE.md in this app and no copy bundled in this build"
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
    ) { toolText(guideText()) },

    Tool("list_projects", "List portal projects.", emptyMap()) { toolText(get("/projects")) },

    Tool(
      "list_tokens",
      "The app's design tokens: constants (`object C { const val Ink = … }`) declared in its scanned sources, with kind, resolved value, declaring file and the import a screen needs. Use a token instead of a magic literal: SetProp with value {kind:...PropValue.Token, ref, value:{tag,...}, import} exactly as listed (the server supplies the value). A token fits only a prop of its kind; ambiguous ones (same ref in two packages) are refused.",
      mapOf("project" to "project name (default 'default')"),
    ) { args -> toolText(get("/tokens?project=${args.str("project", "default")}")) },

    Tool(
      "list_screens", "List screens in a project.",
      mapOf("project" to "project name (default 'default')"),
    ) { args -> toolText(get("/screens?project=${args.str("project", "default")}")) },

    Tool(
      "get_document",
      "The screen's semantic document, by the BARE screen name list_screens returns (`home`, not `default/home`): nodes with stable handles, props (Lit/Bind/Action/Token/Conditional/Template/ListOf), modifiers, version. Ops target these handles.",
      mapOf("project" to "project (default 'default')", "screen" to "screen (default 'main')"),
    ) { args ->
      val project = args.str("project", "default")
      val screen = normalizeScreen(project, args.str("screen", "main"))
      rejectUnknownScreen(project, screen)
        ?: toolText(get("/doc?project=$project&screen=$screen&tokens=1&conditionals=1&extendedConditions=1&templates=1&lists=1"))
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
      val project = args.str("project", "default")
      val screen = normalizeScreen(project, args.str("screen", "main"))
      rejectUnknownScreen(project, screen)?.let { return@Tool it }
      val (code, body) = post(
        "/ops?project=$project&screen=$screen$dry",
        args.str("batchJson"),
        session = "agent",
      )
      toolText(body, isError = code !in 200..299)
    },

    Tool(
      "undo", "Undo the agent session's last batch (server-side).",
      mapOf("project" to "project (default 'default')", "screen" to "screen (default 'main')"),
    ) { args ->
      val project = args.str("project", "default")
      val screen = normalizeScreen(project, args.str("screen", "main"))
      val (code, body) = post("/undo?project=$project&screen=$screen", "", "agent")
      toolText(body, isError = code !in 200..299)
    },

    Tool(
      "redo", "Redo the agent session's last undone batch.",
      mapOf("project" to "project (default 'default')", "screen" to "screen (default 'main')"),
    ) { args ->
      val project = args.str("project", "default")
      val screen = normalizeScreen(project, args.str("screen", "main"))
      val (code, body) = post("/redo?project=$project&screen=$screen", "", "agent")
      toolText(body, isError = code !in 200..299)
    },

    Tool(
      "find_usages",
      "Where a bound field, wired action name, or design token (`C.Ink`) is used across a project's screens.",
      mapOf("project" to "project (default 'default')", "name" to "field, action or token reference"),
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
