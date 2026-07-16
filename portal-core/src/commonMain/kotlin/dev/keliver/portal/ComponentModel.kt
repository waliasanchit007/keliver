package dev.keliver.portal

/**
 * C1 Project Components ("molecules"): an app-owned reusable @Composable built
 * from keliver primitives (and other components), whose Kotlin SIGNATURE is its
 * portal spec — no annotation, no sidecar file (D4). Screens call it like any
 * widget; the portal recognizes, edits, exports, and previews the call without
 * a schema tag or protocol change (D6/D9).
 */

/** A lambda parameter: `() -> Unit` (paramType null) or `(T) -> Unit` (paramType = T). */
data class ComponentEventSpec(val name: String, val paramType: String? = null)

/**
 * The signature-derived spec for one component plus its recognized body.
 * [transparent] = the body is fully in the grammar and can be macro-expanded in
 * preview; opaque otherwise (still a real device composable — [diagnostic] says
 * why it can't be previewed transparently).
 */
data class ComponentSpec(
  val name: String,
  /** Scalar value parameters (String/Int/Boolean/Double) as editor props. */
  val props: List<PropSpec>,
  /** Lambda parameters as portal events. */
  val events: List<ComponentEventSpec>,
  /** Every parameter's Kotlin type text (props + events), for contract typing. */
  val paramTypes: Map<String, String>,
  /** Parsed literal defaults, keyed by parameter name (absent = required). */
  val defaults: Map<String, Any?> = emptyMap(),
  /** The recognized body tree with parameter binds/actions; null when opaque. */
  val body: WidgetNode? = null,
  val transparent: Boolean = body != null,
  /** Component names referenced in the body (dependency edges). */
  val dependencies: Set<String> = emptySet(),
  /** Human diagnostic when opaque, invalid, or in a cycle; null when clean. */
  val diagnostic: String? = null,
) {
  /** Editor sample value for a required scalar prop with no signature default. */
  fun sampleFor(prop: PropSpec): Any? = defaults[prop.name] ?: when (prop.kind) {
    PropKind.Text -> prop.name.replaceFirstChar { it.uppercaseChar() }
    PropKind.Int, PropKind.Color -> 0
    PropKind.Double -> 0.0
    PropKind.Bool -> false
    PropKind.IntList, PropKind.FloatList, PropKind.StringList -> emptyList<Any?>()
  }
}

/**
 * A project-scoped view of the components available for recognition, export,
 * contract typing, preview, and the editor. Deliberately tiny so relay projects
 * stay isolated (each holds its own instance — no process-global leakage).
 */
interface ComponentRegistry {
  fun spec(name: String): ComponentSpec?
  fun names(): Set<String>
  fun isComponent(type: String): Boolean = spec(type) != null
}

/** The primitive-only default: current callers behave exactly as before. */
object EmptyComponentRegistry : ComponentRegistry {
  override fun spec(name: String): ComponentSpec? = null
  override fun names(): Set<String> = emptySet()
}

/** An immutable snapshot registry (relay builds one per project per ingest generation). */
class MapComponentRegistry(specs: Collection<ComponentSpec>) : ComponentRegistry {
  private val byName = specs.associateBy { it.name }
  override fun spec(name: String): ComponentSpec? = byName[name]
  override fun names(): Set<String> = byName.keys
}

/** Names reserved by the grammar — a component may not shadow them. */
val RESERVED_COMPONENT_NAMES: Set<String> = setOf("Condition", "Repeat", "RawCode")

/** True when [type] resolves to a primitive widget OR a project component. */
fun isKnownWidgetType(type: String, registry: ComponentRegistry = EmptyComponentRegistry): Boolean =
  widgetSpec(type) != null || registry.isComponent(type)
