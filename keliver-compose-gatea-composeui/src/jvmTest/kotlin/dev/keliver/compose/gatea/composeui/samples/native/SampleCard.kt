// The same screen code as ../guest/SampleCard.kt: only the package and the imports differ
// (SameSourceTest checks that), so this is plain Compose compiled against the guest API.
package dev.keliver.compose.gatea.composeui.samples.native

import androidx.compose.runtime.Composable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SampleCard(title: String, rate: String, tags: List<String>, onOpen: () -> Unit) {
  Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Row(
      modifier = Modifier.fillMaxWidth()
        .shadow(1.dp, RoundedCornerShape(12.dp), clip = false)
        .clip(RoundedCornerShape(12.dp))
        .background(Color(0xFFE7F0FF))
        .border(1.dp, Color(0xFFEDEDED), RoundedCornerShape(12.dp))
        .clickable { onOpen() }
        .padding(horizontal = 16.dp, vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Box(Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF41A69E)), contentAlignment = Alignment.Center) {
        Icon(Icons.Filled.Search, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
      }
      Spacer(Modifier.width(12.dp))
      Column(Modifier.weight(1f)) {
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1F1F1F), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(rate, style = LocalTextStyle.current.copy(letterSpacing = 1.sp), color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
      }
      Icon(Icons.Filled.ChevronRight, contentDescription = "Open")
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      for (tag in tags) {
        Box(Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer).padding(horizontal = 10.dp, vertical = 4.dp)) {
          Text(tag, fontSize = 12.sp, textAlign = TextAlign.Center)
        }
      }
    }
    Spacer(Modifier.height(4.dp))
  }
}
