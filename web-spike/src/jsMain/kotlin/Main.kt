/*
 * spike/keliver-web — renders keliver-material widgets in a browser via
 * Compose-for-Web (JS canvas), using the SAME renderer code as Android/iOS.
 */
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.CanvasBasedWindow
import coil3.ImageLoader
import coil3.PlatformContext
import dev.keliver.composeui.RedwoodContent
import dev.keliver.layout.api.Constraint
import dev.keliver.layout.api.CrossAxisAlignment
import dev.keliver.layout.compose.Column
import dev.keliver.layout.compose.Spacer
import dev.keliver.material.api.TextSpan
import dev.keliver.material.compose.AnimatedBorder
import dev.keliver.material.compose.RichText
import dev.keliver.material.compose.StyledBox
import dev.keliver.material.compose.StyledText
import dev.keliver.material.composeui.ComposeUiKeliverMaterialWidgetSystem
import dev.keliver.ui.Dp

private const val WHITE = 0xFFFFFFFF.toInt()
private const val BLACK = 0xFF111111.toInt()
private const val BORDER = 0xFFFAD4D4.toInt()
private val CARD = listOf(0xFFFFFCFC.toInt(), 0xFFFFEEEE.toInt(), 0xFFFFF3EE.toInt())
private val CARD_STOPS = listOf(0.0f, 0.55f, 1.0f)
private val RED = listOf(0xFFFF3E3E.toInt(), 0xFFFF2D4E.toInt(), 0xFFFF552B.toInt())

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
  val w = kotlinx.browser.window.asDynamic()
  w.__step = "start"
  try {
    val imageLoader = ImageLoader.Builder(PlatformContext.INSTANCE).build()
    w.__step = "imageLoader"
    val widgetSystem = ComposeUiKeliverMaterialWidgetSystem(imageLoader)
    w.__step = "widgetSystem"
    CanvasBasedWindow(canvasElementId = "ComposeTarget") {
      RedwoodContent(widgetSystem = widgetSystem) {
        Column(width = Constraint.Fill) {
          StyledText(text = "keliver renders on WEB", fontSize = 22, bold = true, colorArgb = BLACK)
          Spacer(height = Dp(8.0))
          StyledText(text = "same Kotlin widgets as Android + iOS", fontSize = 14, colorArgb = 0xFF8A8A8A.toInt())
        }
      }
    }
    w.__step = "canvasWindow"
  } catch (t: Throwable) {
    w.__err = t.toString()
    kotlinx.browser.document.title = "ERR"
  }
}

@Composable
private fun MiniNudge() {
  StyledBox(
    gradientColorsArgb = CARD, gradientStops = CARD_STOPS, gradientDirection = 3,
    borderColorArgb = BORDER, borderWidthDp = 1, cornerRadiusDp = 12,
    fillWidth = true, paddingDp = 20,
  ) {
    Column(width = Constraint.Fill, horizontalAlignment = CrossAxisAlignment.Stretch) {
      StyledText(text = "Rendered on WEB by keliver 🎉", fontSize = 12, bold = true, colorArgb = 0xFF8A8A8A.toInt())
      Spacer(height = Dp(12.0))
      RichText(
        spans = listOf(
          TextSpan(text = "Make your UPI ID ", bold = true, colorArgb = BLACK),
          TextSpan(text = "sound like you", bold = true, gradientColorsArgb = RED),
        ),
        fontSize = 20,
      )
      Spacer(height = Dp(20.0))
      AnimatedBorder(effect = 1 /* gradientSweep */, cornerRadiusDp = 10, strokeWidthDp = 2, colorsArgb = RED) {
        StyledBox(fillWidth = true, heightDp = 48, contentAlignment = 4) {
          StyledText(text = "Personalise my UPI ID now", fontSize = 14, bold = true, colorArgb = BLACK)
        }
      }
    }
  }
}
