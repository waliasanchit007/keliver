package feed.screens

import androidx.compose.runtime.Composable
import dev.keliver.layout.compose.Column
import dev.keliver.layout.compose.Spacer
import dev.keliver.material.compose.Button
import dev.keliver.material.compose.ListItem
import dev.keliver.material.compose.StyledText
import dev.keliver.ui.Dp

@Composable
fun FeedScreen(b: FeedScreenBindings) {
  Column {
    StyledText(
      text = "Articles",
      fontSize = 24,
      bold = true,
    )
    StyledText(
      text = b.statusLine,
      fontSize = 14,
    )
    Spacer(
      height = Dp(8.0),
    )
    b.rows.forEach { row ->
      ListItem(
        headline = row.title,
        leadingIcon = "Star",
      )
    }
    if (b.isError) {
      Button(
        text = "Retry",
        onClick = { b.retry() },
      )
    }
  }
}

interface FeedScreenBindings {
  val isLoading: Boolean
  val isEmpty: Boolean
  val isError: Boolean
  val statusLine: String
  val rows: List<FeedRow>
  fun retry()
}

interface FeedRow {
  val title: String
}
