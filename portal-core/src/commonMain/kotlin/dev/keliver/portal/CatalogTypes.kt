package dev.keliver.portal

/** Editor-facing property kinds. Keep in sync with the editor's property panel. */
enum class PropKind { Text, Int, Bool, Color, Double, IntList, FloatList, StringList }

data class PropSpec(val name: String, val kind: PropKind, val label: String)

/** An unscoped modifier the editor can attach to any node (props ride as "mod.<Name>.<prop>"). */
data class ModifierSpec(val name: String, val props: List<PropSpec>)

data class WidgetSpec(
  val type: String,
  val category: String,
  val props: List<PropSpec>,
  val acceptsChildren: Boolean,
  /** Values a palette-add starts with: every required prop, sensible defaults. */
  val sampleProps: Map<String, Any?>,
  /** Event names the editor can wire to named Actions (P3 bindings). */
  val events: List<String> = emptyList(),
)
