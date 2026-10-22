package fieldnotes.screens

import androidx.compose.runtime.Composable
import dev.keliver.layout.compose.Column
import dev.keliver.layout.compose.Spacer
import dev.keliver.material.compose.StyledBox
import dev.keliver.material.compose.StyledText
import dev.keliver.ui.Dp
import fieldnotes.components.EntryRow

/**
 * A screen taller than the phone frame: a plain root Column (it does not
 * scroll itself), conditions, a Repeat over the entries, and a footer that is
 * only reachable by scrolling.
 */
@Composable
fun LogbookScreen(b: LogbookScreenBindings) {
  Column {
    StyledText(
      text = "Logbook",
      fontSize = 22,
      bold = true,
      colorArgb = -15000801,
    )
    if (b.syncPending) {
      StyledBox(
        colorArgb = -36797,
        widthDp = 350,
        heightDp = 48,
        cornerRadiusDp = 10,
        paddingDp = 12,
      ) {
        StyledText(
          text = "Sync pending",
          fontSize = 13,
          colorArgb = -1,
        )
      }
    }
    Spacer(
      height = Dp(12.0),
    )
    b.entries.forEach { entry ->
      EntryRow(
        title = entry.title,
        detail = entry.detail,
      )
      Spacer(
        height = Dp(8.0),
      )
    }
    if (b.isEmpty) {
      StyledText(
        text = "No entries yet",
        fontSize = 13,
        colorArgb = -10525848,
      )
    } else {
      StyledText(
        text = "Newest first",
        fontSize = 11,
        colorArgb = -10525848,
      )
    }
    StyledBox(
      colorArgb = -2634552,
      widthDp = 350,
      heightDp = 420,
      cornerRadiusDp = 12,
      paddingDp = 12,
    ) {
      StyledText(
        text = "Archive",
        fontSize = 14,
        colorArgb = -15000801,
      )
    }
    Spacer(
      height = Dp(12.0),
    )
    StyledBox(
      colorArgb = -16732991,
      widthDp = 350,
      heightDp = 80,
      cornerRadiusDp = 12,
      paddingDp = 12,
    ) {
      StyledText(
        text = "End of log",
        fontSize = 14,
        colorArgb = -1,
      )
    }
  }
}

/** The round-trip boundary: implement this by hand; the portal never touches it. */
interface LogbookScreenBindings {
  val syncPending: Boolean
  val isEmpty: Boolean
  val entries: List<LogEntry>
}

/** One row of the logbook's Repeat. */
interface LogEntry {
  val title: String
  val detail: String
}
