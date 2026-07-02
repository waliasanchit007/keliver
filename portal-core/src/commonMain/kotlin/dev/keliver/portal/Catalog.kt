package dev.keliver.portal

/** The editable-property metadata catalog, for the portal's property panel. */
// PropKind/PropSpec moved to CatalogTypes.kt (P1); this hand-written catalog is
// replaced by GeneratedCatalog.kt in P1 Task 7.

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
