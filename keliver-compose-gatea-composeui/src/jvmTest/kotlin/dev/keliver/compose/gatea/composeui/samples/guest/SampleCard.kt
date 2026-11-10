/*
 * Copyright (C) 2026 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.keliver.compose.gatea.composeui.samples.guest

import androidx.compose.runtime.Composable
import dev.keliver.compose.foundation.background
import dev.keliver.compose.foundation.border
import dev.keliver.compose.foundation.clickable
import dev.keliver.compose.foundation.layout.Arrangement
import dev.keliver.compose.foundation.layout.Box
import dev.keliver.compose.foundation.layout.Column
import dev.keliver.compose.foundation.layout.FlowRow
import dev.keliver.compose.foundation.layout.Row
import dev.keliver.compose.foundation.layout.Spacer
import dev.keliver.compose.foundation.layout.fillMaxWidth
import dev.keliver.compose.foundation.layout.height
import dev.keliver.compose.foundation.layout.padding
import dev.keliver.compose.foundation.layout.size
import dev.keliver.compose.foundation.layout.width
import dev.keliver.compose.foundation.shape.CircleShape
import dev.keliver.compose.foundation.shape.RoundedCornerShape
import dev.keliver.compose.material.icons.Icons
import dev.keliver.compose.material.icons.filled.ChevronRight
import dev.keliver.compose.material.icons.filled.Search
import dev.keliver.compose.material3.Icon
import dev.keliver.compose.material3.LocalTextStyle
import dev.keliver.compose.material3.MaterialTheme
import dev.keliver.compose.material3.Text
import dev.keliver.compose.ui.Alignment
import dev.keliver.compose.ui.Modifier
import dev.keliver.compose.ui.draw.clip
import dev.keliver.compose.ui.draw.shadow
import dev.keliver.compose.ui.graphics.Color
import dev.keliver.compose.ui.text.font.FontWeight
import dev.keliver.compose.ui.text.style.TextAlign
import dev.keliver.compose.ui.text.style.TextOverflow
import dev.keliver.compose.ui.unit.dp
import dev.keliver.compose.ui.unit.sp

@Suppress("ktlint:compose:modifier-missing-check")
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
