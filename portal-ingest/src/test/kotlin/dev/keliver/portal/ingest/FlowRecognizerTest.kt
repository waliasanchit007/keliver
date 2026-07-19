package dev.keliver.portal.ingest

import dev.keliver.portal.FlowEdge
import dev.keliver.portal.deriveFlowEdges
import dev.keliver.portal.document.UiDocument
import dev.keliver.portal.document.toWidgetTree
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #13 F1 gate — the design doc's Login→OTP→Dashboard fixture: the flow DSL
 * parses back to its FlowSpec, and the nav graph derives from real recognized
 * screen trees, covering BOTH edge kinds (literal action arg + action name)
 * and literal-over-name precedence.
 */
class FlowRecognizerTest {
  private val flowSrc = """
    package dev.keliver.portalpublished.flows

    import dev.keliver.portal.flow.flow

    val LoginFlow = flow("Login", start = "LoginScreen") {
      route("OTP", to = "OtpScreen")
      route("DASHBOARD", to = "DashboardScreen")
      route("verify", to = "WrongScreen")     // name-key that a literal must OUTRANK
      route("openItem", to = "DetailScreen")  // action-name edge (dynamic item arg)
    }
  """.trimIndent()

  private val login = """
    import androidx.compose.runtime.Composable
    import dev.keliver.layout.compose.Column
    import dev.keliver.material.compose.Button

    @Composable
    fun LoginScreen(b: LoginScreenBindings) {
      Column {
        Button(text = "Send OTP", onClick = { b.open("OTP") })
      }
    }
  """.trimIndent()

  private val otp = """
    import androidx.compose.runtime.Composable
    import dev.keliver.layout.compose.Column
    import dev.keliver.material.compose.Button

    @Composable
    fun OtpScreen(b: OtpScreenBindings) {
      Column {
        Button(text = "Verify", onClick = { b.verify("DASHBOARD") })
        Button(text = "Back", onClick = { b.back() })
      }
    }
  """.trimIndent()

  private val dashboard = """
    import androidx.compose.runtime.Composable
    import dev.keliver.layout.compose.Column
    import dev.keliver.material.compose.ListItem

    @Composable
    fun DashboardScreen(b: DashboardScreenBindings) {
      Column {
        b.items.forEach { item ->
          ListItem(headline = item.title, onClick = { b.openItem(item.id) })
        }
      }
    }
  """.trimIndent()

  private fun treeOf(name: String, src: String): dev.keliver.portal.WidgetNode {
    val rec = Recognizer.recognize(name, src)!!
    return UiDocument("f", rec.root, rec.contract, 0, 0).toWidgetTree()
  }

  @Test fun flowDslParsesBackToItsSpec() {
    val spec = FlowRecognizer.recognize("LoginFlow.kt", flowSrc)!!
    assertEquals("Login", spec.name)
    assertEquals("LoginScreen", spec.start)
    assertEquals("OtpScreen", spec.routes["OTP"])
    assertEquals("DetailScreen", spec.routes["openItem"])
    assertEquals(4, spec.routes.size)
  }

  @Test fun positionalArgsAndNonFlowFilesHandled() {
    val positional = FlowRecognizer.recognize(
      "F.kt",
      """val F = flow("Mini", "HomeScreen") { route("GO", to = "ThereScreen") }""",
    )!!
    assertEquals("HomeScreen", positional.start)
    assertEquals("ThereScreen", positional.routes["GO"])
    // A flows/ helper file that is not a flow declaration is simply skipped.
    assertNull(FlowRecognizer.recognize("Helper.kt", "fun helper(x: Int) = x + 1"))
  }

  @Test fun graphDerivesFromRecognizedScreensWithBothEdgeKinds() {
    val spec = FlowRecognizer.recognize("LoginFlow.kt", flowSrc)!!
    val screens = mapOf(
      "LoginScreen" to treeOf("LoginScreen.kt", login),
      "OtpScreen" to treeOf("OtpScreen.kt", otp),
      "DashboardScreen" to treeOf("DashboardScreen.kt", dashboard),
    )
    val edges = deriveFlowEdges(spec.routes, screens)
    // Literal-arg edges.
    assertTrue(FlowEdge("LoginScreen", "OTP", "OtpScreen") in edges, edges.toString())
    // Literal "DASHBOARD" outranks the "verify" name-key (NOT WrongScreen).
    assertTrue(FlowEdge("OtpScreen", "DASHBOARD", "DashboardScreen") in edges, edges.toString())
    assertTrue(edges.none { it.to == "WrongScreen" }, edges.toString())
    // Action-NAME edge for the dynamic item arg.
    assertTrue(FlowEdge("DashboardScreen", "openItem", "DetailScreen") in edges, edges.toString())
    // b.back() matches no route -> no edge.
    assertEquals(3, edges.size, edges.toString())
  }
}
