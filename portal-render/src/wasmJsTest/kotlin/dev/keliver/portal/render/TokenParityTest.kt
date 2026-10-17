package dev.keliver.portal.render

import dev.keliver.material.compose.StyledText
import dev.keliver.material.testing.KeliverMaterialTester
import dev.keliver.portal.Token
import dev.keliver.portal.WidgetNode
import dev.keliver.portal.deserializeTree
import dev.keliver.portal.render.fixture.PARITY_TOKENS_BODY_JSON
import dev.keliver.portal.render.fixture.ParityTokenCard
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

/**
 * K2 — design-token parity: a component written with token constants
 * (`colorArgb = ParityTokens.Ink`, `url = ParityTokens.Logo`, a token modifier
 * argument) builds the same schema value tree compiled (device) and
 * interpreted from the tree portal-ingest recognizes (each token carrying its
 * resolved value), locked to ParityTokens.kt by the JVM golden test.
 */
class TokenParityTest {
  @AfterTest fun reset() {
    PreviewBindings.mocks.clear()
  }

  @Test
  fun aTokenComponentMatchesItsCompiledFixture() = runTest {
    val compiled = KeliverMaterialTester {
      setContentAndSnapshot { ParityTokenCard(label = "Users") }
    }
    PreviewBindings.mocks["label"] = "Users"
    val interpreted = KeliverMaterialTester {
      setContentAndSnapshot { RenderNode(deserializeTree(PARITY_TOKENS_BODY_JSON)) }
    }
    assertEquals(compiled, interpreted)
  }

  @Test
  fun aTokenRendersAsItsResolvedValue() = runTest {
    val compiled = KeliverMaterialTester {
      setContentAndSnapshot { StyledText(text = "x", colorArgb = -14737633) }
    }
    val interpreted = KeliverMaterialTester {
      setContentAndSnapshot {
        RenderNode(WidgetNode("StyledText", mapOf("text" to Token("C.Text", "x"), "colorArgb" to Token("C.Ink", -14737633, "app.C"))))
      }
    }
    assertEquals(compiled, interpreted)
  }
}
