package dev.keliver.portal.codegen

import java.io.File
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType

// E8: the named values of an Int-coded prop, so the editor offers a dropdown
// ("Start / Center / End") instead of a bare number. Two sources, both the
// schemas' own:
//
//  - a layout enum (`Constraint`, `CrossAxisAlignment`, `MainAxisAlignment`,
//    `Overflow`) rides on the wire as its ordinal; its names are read from the
//    value class itself (`toString` of each ordinal), so they cannot drift;
//  - an Int prop whose KDoc lists its codes — `/** 0 start, 1 center, 2 end. */`
//    — gets those codes. A list is two or more `<int> <name>` entries separated
//    by commas; a `<default> => <meaning>` sentinel for the prop's default value
//    (`0 => use [colorArgb]`) joins the list when the list leaves it out. A lone
//    sentinel (`0 => unlimited`) is not a list: the prop stays a number.

/** One named value of an Int-coded prop: emitted as a catalog `PropChoice`. */
data class ChoicePlan(val value: Int, val label: String)

private const val CODE = """-?\d+\s+[A-Za-z][^,;.]*"""
private val LIST_RUN = Regex("""(?<![\w/.])$CODE(?:\s*,\s*$CODE)+""")
private val LIST_SPLIT = Regex("""\s*,\s*(?=-?\d+\s+[A-Za-z])""")
private val SENTINEL = Regex("""(?<![\w/.])(-?\d+)\s*=>\s*([^.;]+)""")
private val IDENTIFIER = Regex("""[A-Za-z][A-Za-z0-9]*""")

/** The codes [doc] lists for an Int prop whose default value is [defaultValue]; empty when it lists none. */
internal fun choicesFromDoc(doc: String?, defaultValue: Int): List<ChoicePlan> {
  if (doc == null) return emptyList()
  val text = doc.replace(Regex("""\s+"""), " ")
  val run = LIST_RUN.find(text) ?: return emptyList()
  val listed = run.value.split(LIST_SPLIT).map { entry ->
    val value = entry.substringBefore(' ').toInt()
    ChoicePlan(value, choiceLabel(entry.substringAfter(' ')))
  }
  if (listed.map { it.value }.toSet().size != listed.size) return emptyList() // not a code list
  val sentinel = if (listed.none { it.value == defaultValue }) {
    SENTINEL.findAll(text).firstOrNull { it.groupValues[1].toInt() == defaultValue }
      ?.let { ChoicePlan(defaultValue, choiceLabel(it.groupValues[2])) }
  } else {
    null
  }
  return (listOfNotNull(sentinel) + listed).sortedBy { it.value }
}

/** "onSurfaceVariant" → "On surface variant"; free text keeps its words ("diagonal TL→BR" → "Diagonal TL→BR"). */
internal fun choiceLabel(raw: String): String {
  val text = raw.replace("[", "").replace("]", "").replace("\"", "").trim()
  return if (IDENTIFIER.matches(text)) humanize(text) else text.replaceFirstChar { it.uppercase() }
}

/**
 * The named values of a layout value class ([fqName], e.g.
 * `dev.keliver.layout.api.Constraint`), by ordinal: the wire value of each is
 * its ordinal (see portal-render's RenderSupport). Read through the value
 * class's own `toString` — `toString-impl(int)` on the JVM — until it refuses
 * an ordinal.
 */
internal fun valueClassChoices(fqName: String, loader: ClassLoader): List<ChoicePlan> {
  val cls = Class.forName(fqName, false, loader)
  val toStringImpl = cls.getMethod("toString-impl", Int::class.javaPrimitiveType)
  return generateSequence(0) { it + 1 }
    .map { ordinal -> runCatching { toStringImpl.invoke(null, ordinal) as String }.getOrNull()?.let { ChoicePlan(ordinal, humanize(it)) } }
    .takeWhile { it != null }
    .filterNotNull()
    .toList()
}

/** The layout enum kinds and their choices, read from the value classes on [loader]'s classpath. */
internal fun layoutEnumChoices(loader: ClassLoader): Map<MappedKind, List<ChoicePlan>> =
  listOf(MappedKind.CONSTRAINT, MappedKind.CROSS_AXIS, MappedKind.MAIN_AXIS, MappedKind.OVERFLOW)
    .associateWith { valueClassChoices(kindImports(it).single(), loader) }

/**
 * The KDoc of every primary-constructor parameter of every class in the
 * Kotlin sources under [dir], keyed `"<Class>.<param>"` (widgets and
 * modifiers alike). The schema parser does not carry KDoc, so it is read here.
 */
internal fun readPropDocs(dir: File): Map<String, String> {
  val disposable = Disposer.newDisposable()
  try {
    val env = KotlinCoreEnvironment.createForProduction(disposable, CompilerConfiguration(), EnvironmentConfigFiles.JVM_CONFIG_FILES)
    val factory = KtPsiFactory(env.project)
    val out = linkedMapOf<String, String>()
    dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.sortedBy { it.path }.forEach { f ->
      out += propDocs(factory.createFile(f.name, f.readText()))
    }
    return out
  } finally {
    Disposer.dispose(disposable)
  }
}

internal fun propDocs(file: KtFile): Map<String, String> = buildMap {
  for (cls in file.collectDescendantsOfType<KtClass>()) {
    val owner = cls.name ?: continue
    for (p in cls.primaryConstructorParameters) {
      val doc = p.docComment?.getDefaultSection()?.getContent()?.trim()?.takeIf { it.isNotEmpty() } ?: continue
      put("$owner.${p.name}", doc)
    }
  }
}

/** [p] with its choices: a layout enum's from [enums], an Int prop's from its KDoc in [docs] (under [owner]). */
internal fun withChoices(p: MappedProp, owner: String, docs: Map<String, String>, enums: Map<MappedKind, List<ChoicePlan>>): MappedProp =
  when {
    p.kind in enums -> p.copy(choices = enums.getValue(p.kind))
    p.kind == MappedKind.INT && !p.isColor -> p.copy(choices = choicesFromDoc(docs["$owner.${p.name}"], defaultInt(p.defaultExpr)))
    else -> p
  }

internal fun WidgetPlan.withChoices(docs: Map<String, String>, enums: Map<MappedKind, List<ChoicePlan>>): WidgetPlan =
  if (this is WidgetPlan.Include) copy(props = props.map { withChoices(it, name, docs, enums) }) else this

internal fun ModPlan.withChoices(schemaName: String, docs: Map<String, String>, enums: Map<MappedKind, List<ChoicePlan>>): ModPlan =
  copy(props = props.map { withChoices(it, schemaName, docs, enums) })
