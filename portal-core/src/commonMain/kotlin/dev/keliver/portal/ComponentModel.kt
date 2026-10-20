package dev.keliver.portal

/**
 * C1 Project Components ("molecules"): an app-owned reusable @Composable built
 * from keliver primitives (and other components), whose Kotlin SIGNATURE is its
 * portal spec — no annotation, no sidecar file (D4). Screens call it like any
 * widget; the portal recognizes, edits, exports, and previews the call without
 * a schema tag or protocol change (D6/D9).
 */

/** A lambda parameter: `() -> Unit` (paramType null) or `(T) -> Unit` (paramType = T). */
public data class ComponentEventSpec(val name: String, val paramType: String? = null, val required: Boolean = true)

/** A single trailing `@Composable () -> Unit` content slot. */
public data class ComponentSlotSpec(val name: String, val required: Boolean = true)

/**
 * The signature-derived spec for one component plus its recognized body.
 * [transparent] = the body is fully in the grammar and can be macro-expanded in
 * preview; opaque otherwise (still a real device composable — [diagnostic] says
 * why it can't be previewed transparently).
 */
public data class ComponentSpec(
  val name: String,
  /** Scalar value parameters (String/Int/Boolean/Double) as editor props. */
  val props: List<PropSpec>,
  /** Lambda parameters as portal events. */
  val events: List<ComponentEventSpec>,
  /** Every parameter's Kotlin type text (props + events), for contract typing. */
  val paramTypes: Map<String, String>,
  /** Content lambdas. Components v2 supports exactly one trailing slot. */
  val slots: List<ComponentSlotSpec> = emptyList(),
  /** Parsed literal defaults, keyed by parameter name (absent = required). */
  val defaults: Map<String, Any?> = emptyMap(),
  /** The recognized body tree with parameter binds/actions; null when opaque. */
  val body: WidgetNode? = null,
  val transparent: Boolean = body != null,
  /** Component names referenced in the body (dependency edges). */
  val dependencies: Set<String> = emptySet(),
  /** Human diagnostic when opaque, invalid, or in a cycle; null when clean. */
  val diagnostic: String? = null,
  /** The definition file's package — screens importing the component need it. */
  val packageName: String? = null,
) {
  /** Editor sample value for a required scalar prop with no signature default. */
  public fun sampleFor(prop: PropSpec): Any? = defaults[prop.name] ?: when (prop.kind) {
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
public interface ComponentRegistry {
  public fun spec(name: String): ComponentSpec?
  public fun names(): Set<String>
  public fun isComponent(type: String): Boolean = spec(type) != null
}

/** The primitive-only default: current callers behave exactly as before. */
public object EmptyComponentRegistry : ComponentRegistry {
  override fun spec(name: String): ComponentSpec? = null
  override fun names(): Set<String> = emptySet()
}

/** An immutable snapshot registry (relay builds one per project per ingest generation). */
public class MapComponentRegistry(specs: Collection<ComponentSpec>) : ComponentRegistry {
  private val byName = specs.associateBy { it.name }
  override fun spec(name: String): ComponentSpec? = byName[name]
  override fun names(): Set<String> = byName.keys
}

/** Names reserved by the grammar — a component may not shadow them. */
public val RESERVED_COMPONENT_NAMES: Set<String> = setOf("Condition", "Repeat", "RawCode", "Slot", ELSE_TYPE)

/**
 * K5: set (true) on a component body's ROOT when it forwards the component's
 * own `modifier` parameter (`Column(modifier = modifier)` or
 * `modifier.padding(8)`). Preview expansion then gives that root the
 * instance's modifiers before the body's own, as the compiled call does.
 */
public const val COMPONENT_FORWARDS_MODIFIER_PROP: String = "portal.forwardsModifier"

/**
 * The ONE spelling of the diagnostic the relay's component registry gives a
 * component on a dependency cycle (it is marked opaque). The editor reads it
 * back through [onDependencyCycle]; nothing else should match the text.
 */
public fun cycleDiagnostic(name: String): String = "$CYCLE_DIAGNOSTIC_PREFIX involving $name"

private const val CYCLE_DIAGNOSTIC_PREFIX = "cyclic component dependency"

/** True when the registry flagged this component as on a dependency cycle ([cycleDiagnostic]). */
public val ComponentSpec.onDependencyCycle: Boolean
  get() = !transparent && diagnostic?.startsWith("$CYCLE_DIAGNOSTIC_PREFIX involving ") == true

/** Internal expansion provenance consumed by the editor's selection tagging. */
public const val COMPONENT_SOURCE_HANDLE_PROP: String = "portal.componentSourceHandle"

/** True when [type] resolves to a primitive widget OR a project component. */
public fun isKnownWidgetType(type: String, registry: ComponentRegistry = EmptyComponentRegistry): Boolean =
  widgetSpec(type) != null || registry.isComponent(type)

/**
 * K1: true when the component's signature declares a `modifier: Modifier`
 * parameter — only then can an instance carry modifiers (exported as
 * `modifier = Modifier…`); otherwise the relay refuses them and the exporter
 * never writes them.
 */
public val ComponentSpec.acceptsModifier: Boolean
  get() = paramTypes["modifier"]?.let(::isModifierType) == true

/** K5: true when a parameter's Kotlin type text is keliver's `Modifier` (optionally nullable). */
public fun isModifierType(typeText: String): Boolean =
  typeText.trim().removeSuffix("?").trim().let { it == "Modifier" || it == "dev.keliver.Modifier" }
