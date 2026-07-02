package dev.keliver.portal

/** Editor-facing property kinds. Keep in sync with the editor's property panel. */
enum class PropKind { Text, Int, Bool, Color, Double, IntList, FloatList }

data class PropSpec(val name: String, val kind: PropKind, val label: String)

data class WidgetSpec(
  val type: String,
  val category: String,
  val props: List<PropSpec>,
  val acceptsChildren: Boolean,
  /** Values a palette-add starts with: every required prop, sensible defaults. */
  val sampleProps: Map<String, Any?>,
)
