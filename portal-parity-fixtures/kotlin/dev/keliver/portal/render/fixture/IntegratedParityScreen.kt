package dev.keliver.portal.render.fixture

import androidx.compose.runtime.Composable
import dev.keliver.Modifier
import dev.keliver.layout.api.Constraint
import dev.keliver.layout.compose.Column
import dev.keliver.material.compose.Button
import dev.keliver.material.compose.TextField
import dev.keliver.material.compose.background
import dev.keliver.material.compose.fillWidth
import dev.keliver.material.compose.padding

@Composable
internal fun IntegratedParityScreen(b: IntegratedParityBindings) {
  Column(
    modifier = Modifier.background(-1118482).padding(8),
    width = Constraint.Fill,
  ) {
    ParitySection {
      Column(width = Constraint.Fill) {
        ParityPanel(title = b.title)
        if (b.showDetails) {
          ParityLeaf(label = b.detail)
        }
        b.rows.forEach { row ->
          Button(
            modifier = Modifier.fillWidth().padding(4),
            text = row.label,
            onClick = { b.openRow(row.id) },
          )
        }
        Button(text = "Refresh", onClick = { b.refresh() })
        Button(text = "Open detail", onClick = { b.open("DETAIL") })
        TextField(
          text = b.draft,
          placeholder = "Draft",
          onValueChange = { b.updateDraft(it) },
        )
      }
    }
  }
}

internal interface IntegratedParityBindings {
  val title: String
  val showDetails: Boolean
  val detail: String
  val rows: List<IntegratedParityRow>
  val draft: String
  fun refresh()
  fun open(value: String)
  fun openRow(value: String)
  fun updateDraft(value: String)
}

internal interface IntegratedParityRow {
  val id: String
  val label: String
}
