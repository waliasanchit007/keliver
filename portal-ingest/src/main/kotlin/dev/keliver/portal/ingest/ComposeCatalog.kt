package dev.keliver.portal.ingest

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * ED0 spike (docs/ED0_SPIKE.md): the generated catalog the recogniser reads Compose-shaped code
 * against — the editor metadata `keliver-compose-generator` writes for a group
 * (`keliver-compose-gatea-schema/gatea.editor.json`): each widget overload and modifier with its
 * guest name, lock tag and every parameter in Compose's order.
 *
 * Nothing reads it unless [ComposeIngest] is on: with the flag off (the default) ingest is what
 * it was, and D6/D9 stand.
 */
class ComposeCatalog(val widgets: List<Entry>, val modifiers: List<Entry>) {
  /** One generated widget overload or modifier. */
  class Entry(
    val name: String,
    /** The guest's fully qualified name (`dev.keliver.compose.material3.Text`). */
    val guest: String,
    /** The lock tag: one per overload. */
    val tag: Int,
    /** A scoped modifier's scope (`dev.keliver.compose.foundation.layout.RowScope`), else null. */
    val scope: String?,
    val params: List<Param>,
  ) {
    /** A widget node's type: the guest name and the overload's tag (`…material3.Text#10`). */
    val type: String get() = "$guest#$tag"

    /** A modifier element's key in a node's modifier map, after its index (`padding#2`). */
    val key: String get() = "$name#$tag"

    val slots: List<Param> get() = params.filter { it.kind == "slot" }

    /**
     * The node keeps its slot's statements as its own children (D9's shape) when the widget has
     * exactly one slot and it is required; any other slot layout has a `slot:<name>` node per slot.
     */
    val singleContentSlot: Param? get() = slots.singleOrNull()?.takeIf { !it.optional }

    fun param(name: String): Param? = params.firstOrNull { it.name == name }

    override fun toString(): String = type
  }

  class Param(
    val name: String,
    /** `modifier`, `slot`, `event`, `unsupported`, or a value kind (`dp`, `color`, `string`, …). */
    val kind: String,
    /** A value parameter's guest type (`dev.keliver.compose.ui.unit.Dp`, `kotlin.Int`). */
    val type: String?,
    val nullable: Boolean,
    /** The default expression's source text; null = required. */
    val default: String?,
    /** A slot's receiver scope (`dev.keliver.compose.foundation.layout.RowScope`). */
    val scope: String?,
    /** A slot with a default or a nullable slot: may be absent. */
    val optional: Boolean,
  ) {
    /** A call must pass it (Kotlin requires an argument for a parameter without a default, nullable or not). */
    val required: Boolean get() = when (kind) {
      "slot" -> !optional
      "modifier" -> false
      else -> default == null
    }
  }

  private val widgetsByGuest: Map<String, List<Entry>> = widgets.groupBy { it.guest }
  private val modifiersByName: Map<String, List<Entry>> = modifiers.groupBy { it.name }
  private val byType: Map<String, Entry> = widgets.associateBy { it.type }

  /** Every overload of the widget [guest] names, or empty. */
  fun widgetOverloads(guest: String): List<Entry> = widgetsByGuest[guest].orEmpty()

  /** The generated modifiers named [name] (overloads and scoped variants). */
  fun modifiersNamed(name: String): List<Entry> = modifiersByName[name].orEmpty()

  /** The widget a node's [type] names, or null (a legacy widget, a grammar node). */
  fun widget(type: String): Entry? = byType[type]

  /** The modifier an element key (`padding#2`) names. */
  fun modifier(key: String): Entry? = modifiers.firstOrNull { it.key == key }

  /** Whether a guest name is one this catalog generates (a widget or a top-level modifier). */
  fun isGuestName(fq: String): Boolean = fq in widgetsByGuest || modifiers.any { it.scope == null && it.guest == fq }

  companion object {
    fun parse(vararg texts: String): ComposeCatalog {
      val widgets = mutableListOf<Entry>()
      val modifiers = mutableListOf<Entry>()
      for (text in texts) {
        val o = Json.parseToJsonElement(text).jsonObject
        o["widgets"]?.jsonArray?.forEach { widgets += entry(it.jsonObject) }
        o["modifiers"]?.jsonArray?.forEach { modifiers += entry(it.jsonObject) }
      }
      return ComposeCatalog(widgets, modifiers)
    }

    fun load(files: List<File>): ComposeCatalog = parse(*files.map { it.readText() }.toTypedArray())

    private fun entry(o: JsonObject): Entry {
      fun s(o: JsonObject, k: String) = o[k]?.jsonPrimitive?.content
      return Entry(
        name = s(o, "name")!!,
        guest = s(o, "guest") ?: error("editor metadata without `guest`: regenerate it (:keliver-compose-generator:generateComposeApi)"),
        tag = o["tag"]!!.jsonPrimitive.int,
        scope = s(o, "scope"),
        params = o["params"]!!.jsonArray.map { p ->
          val po = p.jsonObject
          Param(
            name = s(po, "name")!!,
            kind = s(po, "kind")!!,
            type = s(po, "type"),
            nullable = po["nullable"]?.jsonPrimitive?.booleanOrNull == true,
            default = s(po, "default"),
            scope = s(po, "scope"),
            optional = po["optional"]?.jsonPrimitive?.booleanOrNull == true,
          )
        },
      )
    }
  }
}

/**
 * The ED0 flag. Off by default: [catalog] is null and the recogniser and write-back behave exactly
 * as before. On when a test (or a tool) sets a catalog for its thread with [with], or when the
 * system property `keliver.portal.ed0.catalog` names editor-metadata files (path-separated).
 */
object ComposeIngest {
  const val PROPERTY: String = "keliver.portal.ed0.catalog"

  private val override = ThreadLocal<ComposeCatalog?>()

  private val fromProperty: ComposeCatalog? by lazy {
    System.getProperty(PROPERTY)?.takeIf { it.isNotBlank() }
      ?.split(File.pathSeparator)?.map(::File)?.let(ComposeCatalog::load)
  }

  /**
   * The catalog when ED0's Compose-shaped ingest is on, else null. On, it needs the prototype
   * `keliver-compose-values` on the classpath, which no shipped consumer has (portal-ingest takes it
   * `compileOnly`): there it fails here, with that reason, rather than partway through a read.
   */
  fun catalog(): ComposeCatalog? = (override.get() ?: fromProperty)?.also { check(valuesPresent) { MISSING_VALUES } }

  private const val MISSING_VALUES: String =
    "ED0's Compose-shaped ingest is on, but the prototype keliver-compose-values is not on the classpath " +
      "(portal-ingest takes it compileOnly, so no shipped tool carries it)"

  private val valuesPresent: Boolean by lazy {
    try {
      Class.forName("dev.keliver.compose.ui.graphics.Color", false, ComposeIngest::class.java.classLoader)
      true
    } catch (_: ClassNotFoundException) {
      false
    }
  }

  /** Runs [block] with ED0 on for this thread, reading against [catalog]. */
  fun <T> with(catalog: ComposeCatalog, block: () -> T): T {
    val before = override.get()
    override.set(catalog)
    try {
      return block()
    } finally {
      override.set(before)
    }
  }
}
