import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** G9 (feedback round 2): a new screen's function is named in Kotlin's style; an existing file keeps its own. */
class ScreenFunctionNameTest {
  @Test fun aNewScreensFunctionIsPascalCase() {
    assertEquals("HomeScreen", screenFunctionName("home"))
    assertEquals("OrderStatusScreen", screenFunctionName("order_status"))
    assertEquals("Fb2TestScreen", screenFunctionName("fb2_test"))
    assertEquals("ReviewScreen", screenFunctionName("Review"))
  }

  @Test fun anExistingFileKeepsTheFunctionItDeclares() {
    val dir = Files.createTempDirectory("screen-name").toFile().apply { deleteOnExit() }
    val f = dir.resolve("order_status.kt").apply {
      writeText("package app.screens\n\nimport androidx.compose.runtime.Composable\n\n/** Status. */\n@Composable\nfun Order_statusScreen(b: Order_statusBindings) {\n}\n")
    }
    assertEquals("Order_statusScreen", declaredScreenFunction(f), "a file written with the old naming is not renamed")
    assertNull(declaredScreenFunction(dir.resolve("missing.kt")))
  }

  @Test fun theScreenIsTheFirstNonPrivateTopLevelComposableNotAHelperOrAComment() {
    val dir = Files.createTempDirectory("screen-name").toFile().apply { deleteOnExit() }
    val f = dir.resolve("home.kt").apply {
      writeText(
        """
        package app.screens

        import androidx.compose.runtime.Composable

        /** Calls @Composable fun Example() in its docs. */
        @Composable
        private fun Badge(text: String) {}

        // @Composable fun Commented() {}
        @androidx.compose.runtime.Composable
        @Suppress("x")
        fun HomeScreen(b: HomeBindings) {
          Badge("a")
        }
        """.trimIndent(),
      )
    }
    assertEquals("HomeScreen", declaredScreenFunction(f))
  }
}
