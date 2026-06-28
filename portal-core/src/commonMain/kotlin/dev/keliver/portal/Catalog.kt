package dev.keliver.portal

/** The editable-property metadata catalog, for the portal's property panel. */

enum class PropKind { Text, Int, Bool, Color, Double }

data class PropSpec(val name: String, val kind: PropKind, val label: String)

fun editableProps(type: String): List<PropSpec> = when (type) {
  "StyledText" -> listOf(
    PropSpec("text", PropKind.Text, "Text"),
    PropSpec("fontSize", PropKind.Int, "Font size"),
    PropSpec("bold", PropKind.Bool, "Bold"),
    PropSpec("colorArgb", PropKind.Color, "Color"),
  )
  "Button" -> listOf(PropSpec("text", PropKind.Text, "Text"))
  "Spacer" -> listOf(PropSpec("height", PropKind.Double, "Height"))
  "AsyncImage" -> listOf(PropSpec("url", PropKind.Text, "URL"))
  "StyledBox" -> listOf(
    PropSpec("paddingDp", PropKind.Int, "Padding"),
    PropSpec("cornerRadiusDp", PropKind.Int, "Corner radius"),
    PropSpec("borderWidthDp", PropKind.Int, "Border width"),
    PropSpec("borderColorArgb", PropKind.Color, "Border color"),
  )
  else -> emptyList()
}
