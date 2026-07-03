import androidx.compose.runtime.Composable
import dev.keliver.layout.api.Constraint
import dev.keliver.layout.api.CrossAxisAlignment
import dev.keliver.layout.api.MainAxisAlignment
import dev.keliver.layout.api.Overflow
import dev.keliver.layout.compose.Column
import dev.keliver.material.compose.Button
import dev.keliver.material.compose.StyledText
import dev.keliver.material.compose.Text

@Composable
fun BoundScreen(b: BoundScreenBindings) {
  Column(
  ) {
    StyledText(
      text = b.title,
      fontSize = 22,
      bold = true,
    )
    Text(
      text = b.subtitle,
    )
    Button(
      text = "Buy now",
      onClick = { b.buyTapped() },
    )
  }
}

/** The round-trip boundary: implement this by hand; the portal never touches it. */
interface BoundScreenBindings {
  val title: String
  val subtitle: String
  fun buyTapped()
}
