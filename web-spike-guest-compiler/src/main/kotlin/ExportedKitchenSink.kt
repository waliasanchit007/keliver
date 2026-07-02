import androidx.compose.runtime.Composable
import dev.keliver.Modifier
import dev.keliver.layout.api.Constraint
import dev.keliver.layout.api.CrossAxisAlignment
import dev.keliver.layout.api.MainAxisAlignment
import dev.keliver.layout.api.Overflow
import dev.keliver.layout.compose.Box
import dev.keliver.layout.compose.Column
import dev.keliver.layout.compose.Row
import dev.keliver.layout.compose.Spacer
import dev.keliver.material.compose.AlertDialog
import dev.keliver.material.compose.AnimatedBorder
import dev.keliver.material.compose.AnimatedVisibility
import dev.keliver.material.compose.AsyncImage
import dev.keliver.material.compose.Badge
import dev.keliver.material.compose.BottomAppBar
import dev.keliver.material.compose.BottomSheet
import dev.keliver.material.compose.Button
import dev.keliver.material.compose.Card
import dev.keliver.material.compose.Checkbox
import dev.keliver.material.compose.Chip
import dev.keliver.material.compose.CircularProgressIndicator
import dev.keliver.material.compose.Clickable
import dev.keliver.material.compose.Dialog
import dev.keliver.material.compose.Divider
import dev.keliver.material.compose.ElevatedButton
import dev.keliver.material.compose.ElevatedCard
import dev.keliver.material.compose.ExtendedFloatingActionButton
import dev.keliver.material.compose.FilledTonalButton
import dev.keliver.material.compose.FilterChip
import dev.keliver.material.compose.FloatingActionButton
import dev.keliver.material.compose.FlowColumn
import dev.keliver.material.compose.FlowRow
import dev.keliver.material.compose.HorizontalPager
import dev.keliver.material.compose.IconButton
import dev.keliver.material.compose.Image
import dev.keliver.material.compose.InputChip
import dev.keliver.material.compose.LazyHorizontalGrid
import dev.keliver.material.compose.LazyVerticalGrid
import dev.keliver.material.compose.LinearProgressIndicator
import dev.keliver.material.compose.NavigationBar
import dev.keliver.material.compose.NavigationRail
import dev.keliver.material.compose.OutlinedButton
import dev.keliver.material.compose.OutlinedCard
import dev.keliver.material.compose.OutlinedTextField
import dev.keliver.material.compose.RadioButton
import dev.keliver.material.compose.ScrollableColumn
import dev.keliver.material.compose.Shimmer
import dev.keliver.material.compose.Slider
import dev.keliver.material.compose.Snackbar
import dev.keliver.material.compose.StyledBox
import dev.keliver.material.compose.StyledText
import dev.keliver.material.compose.SuggestionChip
import dev.keliver.material.compose.Surface
import dev.keliver.material.compose.Switch
import dev.keliver.material.compose.Tab
import dev.keliver.material.compose.TabRow
import dev.keliver.material.compose.Text
import dev.keliver.material.compose.TextButton
import dev.keliver.material.compose.TextField
import dev.keliver.material.compose.TextInput
import dev.keliver.material.compose.Theme
import dev.keliver.material.compose.Tooltip
import dev.keliver.material.compose.TopAppBar
import dev.keliver.material.compose.VerticalDivider
import dev.keliver.material.compose.VerticalPager
import dev.keliver.material.compose.animateContentSize
import dev.keliver.material.compose.cornerRadius
import dev.keliver.material.compose.padding
import dev.keliver.ui.Dp

@Composable
fun ExportedKitchenSink() {
  Column(
  ) {
    AlertDialog(
      modifier = Modifier.animateContentSize().cornerRadius(4).padding(8),
      title = "New AlertDialog",
      text = "New AlertDialog",
    )
    AnimatedBorder(
    ) {
    }
    AnimatedVisibility(
    ) {
    }
    AsyncImage(
      url = "New AsyncImage",
    )
    Badge(
    )
    BottomAppBar(
    ) {
    }
    BottomSheet(
      visible = false,
    ) {
    }
    Box(
    ) {
    }
    Button(
      text = "New Button",
    )
    Card(
    ) {
    }
    Checkbox(
      checked = false,
    )
    Chip(
      label = "New Chip",
    )
    CircularProgressIndicator(
    )
    Clickable(
    ) {
    }
    Column(
    ) {
    }
    Dialog(
    ) {
    }
    Divider(
    )
    ElevatedButton(
      text = "New ElevatedButton",
    )
    ElevatedCard(
    ) {
    }
    ExtendedFloatingActionButton(
      text = "New ExtendedFloatingActionButton",
    )
    FilledTonalButton(
      text = "New FilledTonalButton",
    )
    FilterChip(
      label = "New FilterChip",
    )
    FloatingActionButton(
    )
    FlowColumn(
    ) {
    }
    FlowRow(
    ) {
    }
    HorizontalPager(
    ) {
    }
    IconButton(
      imageUrl = "New IconButton",
    )
    Image(
      url = "New Image",
    )
    InputChip(
      label = "New InputChip",
    )
    LazyHorizontalGrid(
    ) {
    }
    LazyVerticalGrid(
    ) {
    }
    LinearProgressIndicator(
    )
    NavigationBar(
    ) {
    }
    NavigationRail(
    ) {
    }
    OutlinedButton(
      text = "New OutlinedButton",
    )
    OutlinedCard(
    ) {
    }
    OutlinedTextField(
      text = "New OutlinedTextField",
    )
    RadioButton(
      selected = false,
    )
    Row(
    ) {
    }
    ScrollableColumn(
    ) {
    }
    Shimmer(
    )
    Slider(
    )
    Snackbar(
      message = "New Snackbar",
    )
    Spacer(
    )
    StyledBox(
    ) {
    }
    StyledText(
      text = "New StyledText",
    )
    SuggestionChip(
      label = "New SuggestionChip",
    )
    Surface(
    ) {
    }
    Switch(
      checked = false,
    )
    Tab(
      text = "New Tab",
    )
    TabRow(
    ) {
    }
    Text(
      text = "New Text",
    )
    TextButton(
      text = "New TextButton",
    )
    TextField(
      text = "New TextField",
    )
    TextInput(
    )
    Theme(
    ) {
    }
    Tooltip(
      text = "New Tooltip",
    ) {
    }
    TopAppBar(
      title = "New TopAppBar",
    )
    VerticalDivider(
    )
    VerticalPager(
    ) {
    }
  }
}
