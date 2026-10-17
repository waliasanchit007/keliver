package dev.keliver.portal

/** Editor-facing property kinds. Keep in sync with the editor's property panel. */
public enum class PropKind { Text, Int, Bool, Color, Double, IntList, FloatList, StringList }

public data class PropSpec(val name: String, val kind: PropKind, val label: String)

/**
 * A modifier the editor can attach to a node (props ride as "mod.<Name>.<prop>").
 *
 * [scopes] lists the PARENT widget types the modifier is valid under: layout's
 * scoped modifiers (`flex`, `margin`, `width`, …) are member extensions of
 * `RowScope`/`ColumnScope`/`BoxScope`, so they only exist inside those
 * widgets' content lambdas. Empty = valid anywhere (an imported top-level
 * extension).
 *
 * [extension] is the Modifier extension's name in source. It usually equals
 * [name] lower-camel-cased, but a scoped modifier whose schema name collides
 * with an unscoped one gets a distinct catalog/wire name (layout's `Size` is
 * `LayoutSize`) while its extension stays `size`.
 *
 * [argTypes] is the Kotlin source type of each positional argument, one per
 * schema property ("Int", "Double", "Dp", "Margin", "CrossAxisAlignment", …).
 * Ingest uses it to recognize arguments exactly (`size(12, 12)` and
 * `size(12.dp, 12.dp)` are different modifiers). A "Margin" argument spans
 * four Double props — see [MARGIN_SIDES].
 */
public data class ModifierSpec(
  val name: String,
  val props: List<PropSpec>,
  val scopes: List<String> = emptyList(),
  val extension: String = name.replaceFirstChar { it.lowercase() },
  val argTypes: List<String> = emptyList(),
)

/** True when [spec] may sit on a node whose scope-giving parent is [parentType] (null = no layout parent). */
public fun ModifierSpec.isValidUnder(parentType: String?): Boolean =
  scopes.isEmpty() || (parentType != null && parentType in scopes)

/**
 * The wire representation of a `dev.keliver.ui.Margin` value: four Double
 * props `<prop>.start`, `<prop>.end`, `<prop>.top`, `<prop>.bottom` (dp). An
 * absent side is 0. A widget's `margin` prop is catalogued as these four
 * Double PropSpecs; the layout `Margin` modifier rides as
 * `mod.Margin.margin.start` … The exporter prints the shortest exact form
 * (`Margin.Zero`, `Margin(all = …)`, `Margin(horizontal = …, vertical = …)`,
 * else the named sides that are non-zero).
 */
public val MARGIN_SIDES: List<String> = listOf("start", "end", "top", "bottom")

/** The Margin-valued prop names among [props] (prefixes that carry all four [MARGIN_SIDES]). */
public fun marginProps(props: List<PropSpec>): Set<String> {
  val names = props.map { it.name }.toSet()
  return props.asSequence()
    .filter { it.name.endsWith(".start") }
    .map { it.name.removeSuffix(".start") }
    .filter { base -> MARGIN_SIDES.all { "$base.$it" in names } }
    .toSet()
}

public data class WidgetSpec(
  val type: String,
  val category: String,
  val props: List<PropSpec>,
  val acceptsChildren: Boolean,
  /** Values a palette-add starts with: every required prop, sensible defaults. */
  val sampleProps: Map<String, Any?>,
  /** Event names the editor can wire to named Actions (P3 bindings). */
  val events: List<String> = emptyList(),
)
