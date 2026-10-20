import java.nio.file.Files
import kotlin.test.Test
import dev.keliver.portal.cycleDiagnostic
import dev.keliver.portal.onDependencyCycle
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * U33: ingest now decodes escapes, so a component default can hold a real
 * newline. The hand-written /components JSON must stay valid: the editor parses
 * it in one piece and drops EVERY project component when it fails.
 */
class ComponentsJsonTest {
  @Test fun defaultsWithControlCharactersStayValidJson() {
    val dir = Files.createTempDirectory("components-json").toFile()
    try {
      dir.resolve("Note.kt").writeText(
        """
        import androidx.compose.runtime.Composable
        import dev.keliver.material.compose.StyledText

        @Composable
        fun Note(
          title: String = "two\nlines \"quoted\"",
          tags: List<String> = listOf("a\tb"),
        ) {
          StyledText(text = title)
        }
        """.trimIndent(),
      )
      Components.rebuild("json-test", dir)
      val json = Json.parseToJsonElement(Components.toJson("json-test")).jsonArray
      val defaults = json.single().jsonObject.getValue("defaults").jsonObject
      assertEquals("two\nlines \"quoted\"", defaults.getValue("title").jsonPrimitive.content)
      // A list default is not recorded today (null); a non-primitive default is written
      // as an escaped JSON string if it ever is.
    } finally {
      dir.deleteRecursively()
    }
  }

  @Test fun parameterTypesAreListed() {
    val dir = Files.createTempDirectory("components-json").toFile()
    try {
      dir.resolve("InfoCard.kt").writeText(
        """
        import androidx.compose.runtime.Composable
        import dev.keliver.Modifier
        import dev.keliver.layout.compose.Column

        @Composable
        fun InfoCard(title: String, modifier: Modifier = Modifier) {
          Column(modifier = modifier) {}
        }
        """.trimIndent(),
      )
      Components.rebuild("json-types", dir)
      val card = Json.parseToJsonElement(Components.toJson("json-types")).jsonArray.single().jsonObject
      val types = card.getValue("paramTypes").jsonObject
      assertEquals("String", types.getValue("title").jsonPrimitive.content)
      assertEquals("Modifier", types.getValue("modifier").jsonPrimitive.content)
      assertEquals(true, card.getValue("transparent").jsonPrimitive.content.toBoolean())
    } finally {
      dir.deleteRecursively()
    }
  }

  /** Round-2 nit 2: the cycle flag is one shared spelling (portal-core), read back structurally. */
  @Test fun componentsOnACycleCarryTheSharedCycleFlag() {
    val dir = Files.createTempDirectory("components-cycle").toFile()
    try {
      fun comp(name: String, calls: String?) = dir.resolve("$name.kt").writeText(
        """
        import androidx.compose.runtime.Composable
        import dev.keliver.layout.compose.Column
        import dev.keliver.material.compose.StyledText

        @Composable
        fun $name(label: String) {
          Column {
            StyledText(text = label)
            ${calls?.let { "$it(label = label)" } ?: ""}
          }
        }
        """.trimIndent(),
      )
      comp("Ping", "Pong")
      comp("Pong", "Ping")
      comp("Solo", null)
      val reg = Components.rebuild("cycle-test", dir)
      assertTrue(reg.spec("Ping")!!.onDependencyCycle, reg.spec("Ping").toString())
      assertTrue(reg.spec("Pong")!!.onDependencyCycle)
      assertFalse(reg.spec("Solo")!!.onDependencyCycle)
      // What the editor receives in /components is the same spelling.
      val ping = Json.parseToJsonElement(Components.toJson("cycle-test")).jsonArray
        .single { it.jsonObject.getValue("name").jsonPrimitive.content == "Ping" }
      assertEquals(cycleDiagnostic("Ping"), ping.jsonObject.getValue("diagnostic").jsonPrimitive.content)
    } finally {
      dir.deleteRecursively()
    }
  }
}
