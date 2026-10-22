package fieldnotes.screens

import androidx.compose.runtime.Composable
import dev.keliver.layout.compose.Column
import dev.keliver.layout.compose.Row
import dev.keliver.layout.compose.Spacer
import dev.keliver.material.compose.StyledBox
import dev.keliver.material.compose.StyledText
import dev.keliver.ui.Dp
import fieldnotes.components.ActionCard
import fieldnotes.components.CardTitle
import fieldnotes.components.CountBadge
import fieldnotes.components.SummaryCard

/** The field station's overview: a root Column whose children are mostly component instances. */
@Composable
fun OverviewScreen(b: OverviewScreenBindings) {
  Column {
    StyledText(
      text = "Field station",
      fontSize = 22,
      bold = true,
      colorArgb = -15000801,
    )
    StyledText(
      text = b.subtitle,
      fontSize = 12,
      colorArgb = -10525848,
    )
    Spacer(
      height = Dp(12.0),
    )
    SummaryCard(
      title = "This week",
      visits = "12",
      samples = "48",
      alerts = "3",
    )
    Spacer(
      height = Dp(12.0),
    )
    CardTitle(
      text = "Recent sites",
      tintArgb = -5051406,
    )
    StyledBox(
      colorArgb = -12345273,
      widthDp = 350,
      heightDp = 44,
      cornerRadiusDp = 12,
      paddingDp = 12,
    ) {
      StyledText(
        text = "North ridge",
        fontSize = 14,
        colorArgb = -1,
      )
    }
    Spacer(
      height = Dp(6.0),
    )
    StyledBox(
      colorArgb = -12345273,
      widthDp = 350,
      heightDp = 44,
      cornerRadiusDp = 12,
      paddingDp = 12,
    ) {
      StyledText(
        text = "Cedar marsh",
        fontSize = 14,
        colorArgb = -1,
      )
    }
    Spacer(
      height = Dp(6.0),
    )
    StyledBox(
      colorArgb = -12345273,
      widthDp = 350,
      heightDp = 44,
      cornerRadiusDp = 12,
      paddingDp = 12,
    ) {
      StyledText(
        text = "Old quarry",
        fontSize = 14,
        colorArgb = -1,
      )
    }
    Spacer(
      height = Dp(10.0),
    )
    Row {
      StyledText(
        text = "Gloves",
        fontSize = 12,
        colorArgb = -10525848,
      )
      Spacer(
        width = Dp(12.0),
      )
      StyledText(
        text = "Vials",
        fontSize = 12,
        colorArgb = -10525848,
      )
      Spacer(
        width = Dp(12.0),
      )
      StyledText(
        text = "Labels",
        fontSize = 12,
        colorArgb = -10525848,
      )
    }
    Spacer(
      height = Dp(8.0),
    )
    StyledText(
      text = "Synced this morning",
      fontSize = 11,
      colorArgb = -10525848,
    )
    Row {
      StyledText(
        text = "Mon ",
        fontSize = 11,
        colorArgb = -10525848,
      )
      StyledText(
        text = "Tue ",
        fontSize = 11,
        colorArgb = -10525848,
      )
      StyledText(
        text = "Wed ",
        fontSize = 11,
        colorArgb = -10525848,
      )
      StyledText(
        text = "Thu ",
        fontSize = 11,
        colorArgb = -10525848,
      )
      StyledText(
        text = "Fri ",
        fontSize = 11,
        colorArgb = -10525848,
      )
      StyledText(
        text = "Sat",
        fontSize = 11,
        colorArgb = -10525848,
      )
    }
    Spacer(
      height = Dp(8.0),
    )
    CountBadge(
      count = 48,
    )
    Spacer(
      height = Dp(12.0),
    )
    ActionCard(
      label = "Restock the kit",
      hint = "Gloves, vials and labels",
      onAction = { b.restock() },
    )
  }
}

/** The round-trip boundary: implement this by hand; the portal never touches it. */
interface OverviewScreenBindings {
  val subtitle: String
  fun restock()
}
