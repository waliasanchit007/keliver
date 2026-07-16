package dev.keliver.portalpublished.components

import androidx.compose.runtime.Composable
import dev.keliver.material.compose.ListItem

@Composable
fun MenuRow(
  title: String,
  subtitle: String,
  icon: String = "Star",
  onClick: () -> Unit,
) {
  ListItem(
    headline = title,
    supporting = subtitle,
    leadingIcon = icon,
    trailingIcon = "KeyboardArrowRight",
    onClick = onClick,
  )
}
