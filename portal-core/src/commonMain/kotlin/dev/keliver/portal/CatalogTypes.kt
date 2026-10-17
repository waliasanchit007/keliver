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
public class ModifierSpec(
  public val name: String,
  public val props: List<PropSpec>,
  public val scopes: List<String> = emptyList(),
  public val extension: String = name.replaceFirstChar { it.lowercase() },
  public val argTypes: List<String> = emptyList(),
) {
  // A regular class (not a data class) so the pre-K1 binary surface —
  // (name, props) constructor, component1/2, copy(name, props) — stays intact
  // while the K1 fields are added.

  /** The pre-K1 shape (unscoped, untyped args). */
  public constructor(name: String, props: List<PropSpec>) : this(name, props, emptyList())

  public operator fun component1(): String = name
  public operator fun component2(): List<PropSpec> = props

  /** The pre-K1 copy: a new name/props, keeping [scopes], [extension] and [argTypes]. */
  public fun copy(name: String = this.name, props: List<PropSpec> = this.props): ModifierSpec =
    ModifierSpec(name, props, scopes, extension, argTypes)

  override fun equals(other: Any?): Boolean =
    other is ModifierSpec && name == other.name && props == other.props && scopes == other.scopes &&
      extension == other.extension && argTypes == other.argTypes

  override fun hashCode(): Int {
    var h = name.hashCode()
    h = 31 * h + props.hashCode()
    h = 31 * h + scopes.hashCode()
    h = 31 * h + extension.hashCode()
    h = 31 * h + argTypes.hashCode()
    return h
  }

  override fun toString(): String =
    "ModifierSpec(name=$name, props=$props, scopes=$scopes, extension=$extension, argTypes=$argTypes)"
}

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

/**
 * True when [key] on a node of [nodeType] holds a `Dp` (a Dp widget prop, a
 * Margin side, or a Dp/Margin modifier argument — see the generated
 * [dpPropKeys]). Dp is non-negative (its constructor requires it), so the
 * document, the exporter, write-back and the preview all clamp these at 0.
 */
public fun isDpProp(nodeType: String, key: String): Boolean =
  if (key.startsWith("mod.")) key in dpPropKeys else "$nodeType.$key" in dpPropKeys

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
