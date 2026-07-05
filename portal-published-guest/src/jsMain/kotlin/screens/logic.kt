package dev.keliver.portalpublished.screens

import androidx.compose.runtime.Composable
import dev.keliver.layout.api.Constraint
import dev.keliver.layout.api.CrossAxisAlignment
import dev.keliver.layout.api.MainAxisAlignment
import dev.keliver.layout.api.Overflow
import dev.keliver.layout.compose.Column
import dev.keliver.material.compose.StyledText

@Composable
fun LogicScreen(b: LogicScreenBindings) {
  Column(
  ) {
    if (b.isPremium) {
      StyledText(
        text = "Premium!",
      )
    }
    b.items.forEach { item ->
      StyledText(
        text = "row",
      )
    }
  }
}

/** The round-trip boundary: implement this by hand; the portal never touches it. */
interface LogicScreenBindings {
  val isPremium: Boolean
  val items: List<String>
}
