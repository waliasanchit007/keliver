package dev.keliver.portalpublished.screens

import androidx.compose.runtime.Composable
import dev.keliver.material.compose.ScrollableColumn
import dev.keliver.portalpublished.components.MenuRow
import dev.keliver.portalpublished.components.SectionHeader

@Composable
fun SettingsScreen(b: SettingsScreenBindings) {
  ScrollableColumn {
    SectionHeader(label = "ACCOUNT")
    MenuRow(title = "Profile", subtitle = b.name, icon = "Person", onClick = { b.open("PROFILE") })
    MenuRow(title = "Notifications", subtitle = "Alerts & updates", icon = "Notifications", onClick = { b.open("NOTIFS") })
    SectionHeader(label = "SECURITY")
    MenuRow(title = "App Lock", subtitle = "PIN or biometric", icon = "Lock", onClick = { b.open("LOCK") })
    SectionHeader(label = "SUPPORT")
    MenuRow(title = "Help", subtitle = "FAQs & contact", icon = "Call", onClick = { b.open("HELP") })
  }
}

interface SettingsScreenBindings {
  val name: String
  fun open(value: String)
}
