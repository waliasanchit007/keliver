package dev.keliver.portalpublished.screens

import androidx.compose.runtime.Composable
import dev.keliver.Modifier
import dev.keliver.layout.compose.Column
import dev.keliver.layout.compose.Spacer
import dev.keliver.material.compose.Button
import dev.keliver.material.compose.Card
import dev.keliver.material.compose.Clickable
import dev.keliver.material.compose.Divider
import dev.keliver.material.compose.ScrollableColumn
import dev.keliver.material.compose.StyledBox
import dev.keliver.material.compose.StyledText
import dev.keliver.material.compose.TextButton
import dev.keliver.material.compose.TextField
import dev.keliver.material.compose.padding
import dev.keliver.ui.Dp

/**
 * DOGFOOD app (Field Notes) v2: still 100% portal-recognized ("no escape
 * hatches"), now exercising the P2 single-arg actions — a real TextField
 * captures typed notes ({ b.onDraftChange(it) }) and tapping a card opens the
 * detail screen with its row id ({ b.openNote(note.id) }). Frictions log:
 * docs/DOGFOOD_NOTES.md.
 */
@Composable
fun FeedScreen(b: FeedScreenBindings) {
  StyledBox(
    paddingDp = 20,
    fillWidth = true,
    gradientColorsArgb = listOf(-1, -1315861),
    gradientStops = listOf(0.0f, 1.0f),
    gradientDirection = 3,
  ) {
    ScrollableColumn {
      StyledText(
        text = "Field Notes",
        fontSize = 28,
        bold = true,
        colorArgb = -14540254,
      )
      StyledText(
        text = b.subtitle,
        fontSize = 13,
        colorArgb = -8355712,
      )
      Spacer(
        height = Dp(14.0),
      )
      TextField(
        text = b.draft,
        placeholder = "What did you notice?",
        onValueChange = { b.onDraftChange(it) },
      )
      Spacer(
        height = Dp(8.0),
      )
      Button(
        text = "Add note",
        onClick = { b.addNote() },
      )
      Spacer(
        height = Dp(14.0),
      )
      if (b.isEmpty) {
        StyledText(
          text = "No notes yet — type one above and tap “Add note”.",
          fontSize = 14,
          colorArgb = -8355712,
        )
      }
      b.notes.forEach { note ->
        Clickable(
          onClick = { b.openNote(note.id) },
        ) {
          Card {
            Column {
              StyledText(
                modifier = Modifier.padding(4),
                text = note.title,
                fontSize = 17,
                bold = true,
                colorArgb = -14540254,
              )
              StyledText(
                modifier = Modifier.padding(4),
                text = note.body,
                fontSize = 14,
                colorArgb = -11184811,
              )
              StyledText(
                modifier = Modifier.padding(4),
                text = note.time,
                fontSize = 11,
                colorArgb = -8355712,
              )
            }
          }
        }
        Spacer(
          height = Dp(10.0),
        )
      }
      Divider()
      TextButton(
        text = "Clear all",
        onClick = { b.clearAll() },
      )
    }
  }
}

/** The round-trip boundary: implement this by hand; the portal never touches it. */
interface FeedScreenBindings {
  val subtitle: String
  val draft: String
  val isEmpty: Boolean
  val notes: List<Note>
  fun onDraftChange(value: String)
  fun addNote()
  fun clearAll()
  fun openNote(value: String)
}

/** Per-item shape for the Repeat feed: binds (title/body/time) + the id carried by openNote. */
interface Note {
  val id: String
  val title: String
  val body: String
  val time: String
}
