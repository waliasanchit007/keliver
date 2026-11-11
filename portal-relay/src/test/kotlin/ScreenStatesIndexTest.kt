import com.sun.net.httpserver.HttpServer
import dev.keliver.portal.ingest.ScreenStateRecognizer
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** S1: what `GET /states` answers, from the screen files. */
class ScreenStatesIndexTest {
  private val withStates = """
    package app.screens

    import androidx.compose.runtime.Composable
    import dev.keliver.nav.NamedState
    import dev.keliver.nav.ScreenState

    @Composable
    fun DetailScreen(b: DetailScreenBindings) {}

    @ScreenState("review")
    fun review() = NamedState(route = ItemDetail(id = "sample"), fixtures = "review")

    @ScreenState("broken")
    fun broken() = NamedState(route = Home, fixtures = name)
  """.trimIndent()

  private fun dir() = Files.createTempDirectory("states").toFile().apply {
    resolve("Detail.kt").writeText(withStates)
    resolve("Plain.kt").writeText("@Composable\nfun PlainScreen() {}\n")
    resolve("capabilities.txt").writeText("x")
  }

  /** An index that counts the parses it does. */
  private class Counting {
    var parses = 0
    val index = ScreenStatesIndex { name, source -> parses++; ScreenStateRecognizer.recognize(name, source) }
  }

  @Test fun oneScreen() {
    val json = Json.parseToJsonElement(
      ScreenStatesIndex.toJson("Detail", ScreenStatesIndex().forFile(dir().resolve("Detail.kt"))),
    ).jsonObject
    assertEquals("Detail", json["screen"]!!.jsonPrimitive.content)
    val state = json["states"]!!.jsonArray.single().jsonObject
    assertEquals("review", state["name"]!!.jsonPrimitive.content)
    assertEquals("review", state["function"]!!.jsonPrimitive.content)
    assertEquals("ItemDetail(id = \"sample\")", state["route"]!!.jsonPrimitive.content)
    assertEquals("ItemDetail", state["routeType"]!!.jsonPrimitive.content)
    assertEquals("review", state["fixtures"]!!.jsonPrimitive.content)
    assertEquals(1, json["problems"]!!.jsonArray.size)
  }

  @Test fun everyScreenWithStates() {
    val json = Json.parseToJsonElement(ScreenStatesIndex.toJson(ScreenStatesIndex().forDir(dir()))).jsonObject
    assertEquals(setOf("Detail"), json["screens"]!!.jsonObject.keys)
  }

  @Test fun aMissingFileHasNoStates() {
    assertEquals(
      """{"screen":"Gone","states":[],"problems":[]}""",
      ScreenStatesIndex.toJson("Gone", ScreenStatesIndex().forFile(dir().resolve("Gone.kt"))),
    )
  }

  @Test fun aFileIsParsedAgainOnlyWhenItChanges() {
    val d = dir()
    val file = d.resolve("Detail.kt")
    val c = Counting()
    val first = c.index.forFile(file)
    assertEquals(first, c.index.forFile(file))
    c.index.forDir(d)
    assertEquals(2, c.parses, "Detail.kt once, Plain.kt once")

    // A change in content and size is seen.
    file.writeText(withStates.replace("\"review\")", "\"look\")"))
    assertEquals(listOf("look"), c.index.forFile(file).states.map { it.name })
    assertEquals(3, c.parses)

    // So is a same-size rewrite, through its modification time.
    val sameSize = withStates.replace("\"review\")", "\"weiver\")")
    file.writeText(sameSize)
    file.setLastModified(file.lastModified() + 2_000)
    assertEquals(listOf("weiver"), c.index.forFile(file).states.map { it.name })
    assertEquals(4, c.parses)

    // Touching the time alone re-parses too (the parse is cheap; staleness is not).
    Files.setLastModifiedTime(file.toPath(), FileTime.fromMillis(file.lastModified() + 2_000))
    c.index.forFile(file)
    assertEquals(5, c.parses)
    c.index.forFile(file)
    assertEquals(5, c.parses)

    // A same-size file renamed over it with the same modification time (an editor's
    // atomic save, `cp -p`) is seen through its inode.
    val time = Files.getLastModifiedTime(file.toPath())
    val replacement = d.resolve(".Detail.kt.tmp")
    replacement.writeText(withStates.replace("\"review\")", "\"REVIEW\")"))
    Files.setLastModifiedTime(replacement.toPath(), time)
    Files.move(replacement.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    assertEquals(time, Files.getLastModifiedTime(file.toPath()))
    assertEquals(listOf("REVIEW"), c.index.forFile(file).states.map { it.name })
    assertEquals(6, c.parses)
  }

  @Test fun aDeletedFileIsForgottenAndARecreatedOneIsRead() {
    val d = dir()
    val file = d.resolve("Detail.kt")
    val c = Counting()
    assertEquals(setOf("Detail"), c.index.forDir(d).keys)
    file.delete()
    assertEquals(emptyMap(), c.index.forDir(d))
    assertEquals(ScreenStatesIndex.toJson("Detail", c.index.forFile(file)), """{"screen":"Detail","states":[],"problems":[]}""")
    val before = c.parses
    file.writeText(withStates)
    assertEquals(listOf("review"), c.index.forFile(file).states.map { it.name })
    assertEquals(before + 1, c.parses)
  }

  // ── GET /states over HTTP ────────────────────────────────────────────────

  private class App(val screensDir: File, val otherProjectDir: File, val drafts: Set<String>) : ScreenStatesIndex.Screens {
    override fun dir(project: String) = if (project == "default") screensDir else otherProjectDir.resolve(project)
    override fun known(project: String, screen: String) =
      "$project/$screen" in drafts || File(dir(project), "$screen.kt").exists()
    override fun names(project: String) =
      (dir(project).list()?.filter { it.endsWith(".kt") }?.map { it.removeSuffix(".kt") }.orEmpty() +
        drafts.filter { it.startsWith("$project/") }.map { it.substringAfter('/') }).sorted()
  }

  private fun <T> withRoute(app: App, block: (call: (String, String) -> Pair<Int, String>) -> T): T {
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    ScreenStatesIndex.register(server, ScreenStatesIndex(), app) { _, run -> run() }
    server.start()
    try {
      val base = "http://127.0.0.1:${server.address.port}"
      return block { method, path ->
        val c = URI("$base$path").toURL().openConnection() as HttpURLConnection
        c.requestMethod = method
        val code = c.responseCode
        val text = (if (code < 400) c.inputStream else c.errorStream)?.use { it.readBytes().decodeToString() }.orEmpty()
        code to text
      }
    } finally {
      server.stop(0)
    }
  }

  private fun app(): App {
    val other = Files.createTempDirectory("projects").toFile()
    other.resolve("shop").mkdirs()
    other.resolve("shop/Cart.kt").writeText(withStates.replace("\"review\")", "\"cart\")"))
    return App(dir(), other, drafts = setOf("default/Draft"))
  }

  @Test fun theRouteDefaultsToTheDefaultProject() = withRoute(app()) { call ->
    val (code, body) = call("GET", "/states")
    assertEquals(200, code)
    assertEquals(setOf("Detail"), Json.parseToJsonElement(body).jsonObject["screens"]!!.jsonObject.keys)
    assertEquals(body, call("GET", "/states?project=default").second)
    // Another project is read from its own directory.
    val shop = Json.parseToJsonElement(call("GET", "/states?project=shop").second).jsonObject["screens"]!!.jsonObject
    assertEquals(setOf("Cart"), shop.keys)
    val one = Json.parseToJsonElement(call("GET", "/states?screen=Detail").second).jsonObject
    assertEquals("Detail", one["screen"]!!.jsonPrimitive.content)
    assertEquals(listOf("review"), one["states"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content })
  }

  @Test fun anUnknownScreenIs404AndNamesTheKnownOnes() = withRoute(app()) { call ->
    val (code, body) = call("GET", "/states?screen=Nope")
    assertEquals(404, code)
    val error = Json.parseToJsonElement(body).jsonObject["error"]!!.jsonPrimitive.content
    assertEquals("no screen 'Nope' in project 'default'; known screens: Detail, Draft, Plain", error)
    assertEquals(404, call("GET", "/states?project=shop&screen=Detail").first)
    // A name is made safe before it is looked up: no path escapes the screens directory.
    assertEquals(404, call("GET", "/states?screen=..%2F..%2Fetc%2Fpasswd").first)
  }

  @Test fun aDraftWithoutAFileHasNoStatesNot404() = withRoute(app()) { call ->
    assertEquals(200 to """{"screen":"Draft","states":[],"problems":[]}""", call("GET", "/states?screen=Draft"))
  }

  @Test fun onlyGetIsAllowed() = withRoute(app()) { call ->
    for (method in listOf("POST", "PUT", "DELETE")) {
      assertEquals(405, call(method, "/states").first, method)
      assertEquals(405, call(method, "/states?screen=Detail").first, method)
    }
    assertTrue(call("GET", "/states").first == 200)
  }
}
