import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
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

  @Test fun oneScreen() {
    val json = Json.parseToJsonElement(
      ScreenStatesIndex.toJson("Detail", ScreenStatesIndex.forFile(dir().resolve("Detail.kt"))),
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
    val json = Json.parseToJsonElement(ScreenStatesIndex.toJson(ScreenStatesIndex.forDir(dir()))).jsonObject
    assertEquals(setOf("Detail"), json["screens"]!!.jsonObject.keys)
  }

  @Test fun aMissingFileHasNoStates() {
    assertEquals(
      """{"screen":"Gone","states":[],"problems":[]}""",
      ScreenStatesIndex.toJson("Gone", ScreenStatesIndex.forFile(dir().resolve("Gone.kt"))),
    )
  }
}
