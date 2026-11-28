/*
 * Copyright (C) 2026 Keliver contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.keliver.compose.generator

import java.io.File
import kotlin.metadata.ClassKind
import kotlin.metadata.KmClass
import kotlin.metadata.KmClassifier
import kotlin.metadata.KmFunction
import kotlin.metadata.KmProperty
import kotlin.metadata.KmType
import kotlin.metadata.Visibility
import kotlin.metadata.declaresDefaultValue
import kotlin.metadata.jvm.signature
import kotlin.metadata.kind
import kotlin.metadata.visibility

/** One curated message: it applies to a declaration that [match]es by name, or names one of [types] in its signature. */
class StubMessage(
  /** Fully qualified names; a `*` stands for any run of characters (`androidx.compose.foundation.gestures.detect*Gestures`). */
  val match: List<String>,
  /** Compose types (fully qualified) whose appearance in a signature selects this message. */
  val types: List<String>,
  val message: String,
  /** What the owner reads in the stub counts: the message's class (`curated`, `never`, `notYet`). */
  val kind: String,
)

/** A declaration the desktop jars do not have (Android-only), named in the config so it still fails with a message. */
class ExtraStub(
  /** Fully qualified Compose name (`androidx.compose.ui.platform.LocalContext`). */
  val name: String,
  /** `fun` or `val`. */
  val kind: String,
  /** A function's parameter names; the last takes a trailing lambda. */
  val params: List<String>,
  val message: String,
)

/**
 * MS4-07 (design §11, §19): for every public declaration of an included package that the group does
 * not generate, a stub that fails to compile with a message, so no public Compose name in an included
 * package is an "unresolved reference" in a guest.
 *
 * - A top-level function becomes a function with Compose's parameter names, every type widened to
 *   `Any?`, and a default (`= null`) only where Compose has one: a call fails on the deprecation, and
 *   still resolves by name, position and trailing lambda. Compose's types are not needed (and many
 *   have no mirror). A function whose receiver is `Modifier` or a generated scope keeps that receiver.
 * - A top-level property becomes an `Any?` property; a top-level class, interface or object an empty
 *   one (with Compose's type parameters), unless the guest already declares that name.
 * - The members of a generated scope interface that are not generated become member stubs.
 *
 * The message comes from the config's curated list (by name, or by a type in the signature), else it
 * is the generic "not generated yet" text naming the declaration's category.
 */
class ErrorStubs(
  private val config: Config,
  private val index: GenericIndex,
  private val plans: List<Plan>,
  /** Mirror package → names the guest declares outside this generation's stubs (hand-written, values, generated). */
  private val existing: Map<String, Set<String>>,
  /** The generated scopes (`androidx.compose.foundation.layout.RowScope`). */
  private val scopes: List<String>,
) {
  /** One stub's text and the class of its message, for the counts. */
  class Stub(
    val pkg: String,
    val name: String,
    val text: String,
    val kind: String,
    val message: String,
    /** A function stub's signature as Kotlin tells overloads apart (receiver, name, arity), so two that collide keep one. */
    val erased: String? = null,
  )

  private val messages: List<StubMessage> = config.stubMessages
  private val matchers = messages.map { m -> m to m.match.map(::globToRegex) }

  private fun globToRegex(glob: String) = Regex(glob.split('*').joinToString(".*") { Regex.escape(it) })

  private fun curated(fq: String, typeNames: Set<String>): StubMessage? {
    for ((m, rs) in matchers) if (rs.any { it.matches(fq) }) return m
    for (m in messages) if (m.types.any { it in typeNames }) return m
    return null
  }

  private fun messageFor(pkg: String, name: String, category: String, typeNames: Set<String>): Pair<String, String> {
    curated("$pkg.$name", typeNames)?.let { return "Keliver: ${it.message}" to it.kind }
    return "Keliver: `$name` ($category in `$pkg`) is not in the generated API. Use a generated alternative, an app component or a bridge ($DESIGN §11)." to "generic"
  }

  /** `(pkg, name, receiver simple name or owner, parameter names)` of everything the group already stubs or generates. */
  private val handled: Set<String> = plans.map { p -> sig(p.decl.pkg, p.decl.owner ?: p.decl.receiver?.simpleName, p.decl.name, p.decl.params.map { it.name }) }.toSet()

  private fun sig(pkg: String, owner: String?, name: String, params: List<String>) = "$pkg|${owner ?: ""}|$name|${params.joinToString(",")}"

  private val hardKeywords = setOf(
    "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in", "interface", "is", "null", "object",
    "package", "return", "super", "this", "throw", "true", "try", "typealias", "typeof", "val", "var", "when", "while",
  )

  private fun id(n: String) = if (n in hardKeywords || !n.all { it.isLetterOrDigit() || it == '_' }) "`$n`" else n

  private fun classNameOf(t: KmType?): String? = (t?.classifier as? KmClassifier.Class)?.name?.replace('/', '.')

  /** The guest type a stub's receiver keeps: the guest's `Modifier`, a generated scope's mirror, else `Any?`. */
  private fun receiverText(t: KmType?): String? {
    val fq = classNameOf(t ?: return null)
    return when {
      fq == MODIFIER -> "dev.keliver.compose.ui.Modifier"
      fq != null && fq in scopes -> mirrorFq(fq)
      else -> "Any?"
    }
  }

  private fun typeNames(f: KmFunction): Set<String> = buildSet {
    classNameOf(f.receiverParameterType)?.let(::add)
    f.valueParameters.forEach { p -> collect(p.type, this) }
    f.returnType.let { collect(it, this) }
  }

  private fun collect(t: KmType, into: MutableSet<String>) {
    classNameOf(t)?.let(into::add)
    t.arguments.forEach { a -> a.type?.let { collect(it, into) } }
    t.abbreviatedType?.let { collect(it, into) }
  }

  private fun returnText(f: KmFunction): String = when (classNameOf(f.returnType)) {
    "kotlin.Unit" -> "Unit"
    MODIFIER -> "dev.keliver.compose.ui.Modifier"
    else -> "Nothing"
  }

  private fun isComposable(f: KmFunction) = f.signature?.descriptor?.contains("Landroidx/compose/runtime/Composer;") == true

  private fun category(f: KmFunction, receiver: String?): String = when {
    isComposable(f) -> "composable function"
    receiver == "Modifier" || classNameOf(f.receiverParameterType) == MODIFIER -> "modifier"
    else -> "function"
  }

  private fun paramList(f: KmFunction, requireFirst: Boolean): String = f.valueParameters.withIndex().joinToString(", ") { (i, p) ->
    val vararg = if (p.varargElementType != null) "vararg " else ""
    val default = if (p.declaresDefaultValue && p.varargElementType == null && !(requireFirst && i == 0)) " = null" else ""
    "$vararg${id(p.name)}: Any?$default"
  }

  private fun render(message: String, decl: String): String =
    "@Deprecated(${kotlinString(message)}, level = DeprecationLevel.ERROR)\n$decl\n\n"

  private fun functionStub(f: KmFunction, pkg: String, owner: String?, member: Boolean): Stub? {
    val params = f.valueParameters.map { it.name }
    val receiverSimple = owner ?: classNameOf(f.receiverParameterType)?.substringAfterLast('.')
    if (sig(pkg, receiverSimple, f.name, params) in handled) return null
    // A name the guest already has (generated, hand-written, a class): a stub that a call with no arguments, or only
    // defaulted ones, could pick is ambiguous with the real one, so its first parameter is required, and a stub with
    // no parameters would be the real one's twin.
    val nameTaken = f.name in existing[mirrorPackage(pkg)].orEmpty() || "$pkg|${f.name}" in handledNames
    if (nameTaken && f.valueParameters.isEmpty()) return null
    val (msg, kind) = messageFor(pkg, f.name, category(f, receiverSimple), typeNames(f))
    val receiver = receiverText(f.receiverParameterType)?.let { "$it." } ?: ""
    val ret = returnText(f)
    val text = render(msg, "public fun $receiver${id(f.name)}(${paramList(f, requireFirst = nameTaken)}): $ret = ${body()}")
    // Two overloads that differ only in parameter names have one signature in Kotlin: the first stays.
    // The receiver is a parameter on the JVM, so it is part of the signature, as an `Any?` one is `Object`.
    val erased = f.name + "(" + (listOfNotNull(receiver.removeSuffix(".").ifEmpty { null }) + f.valueParameters.map { if (it.varargElementType != null) "vararg" else "Any?" }).joinToString(",") + ")"
    return Stub(mirrorPackage(pkg), f.name, text, kind, msg, erased)
  }

  private fun body() = "throw UnsupportedOperationException()"

  private val handledNames: Set<String> = plans.map { "${it.decl.pkg}|${it.decl.name}" }.toSet()

  private fun propertyStub(p: KmProperty, pkg: String, member: Boolean): Stub? {
    val (msg, kind) = messageFor(pkg, p.name, "property", buildSet { classNameOf(p.receiverParameterType)?.let(::add); collect(p.returnType, this) })
    val receiver = receiverText(p.receiverParameterType)?.let { "$it." } ?: ""
    val text = render(msg, "public val $receiver${id(p.name)}: Any?\n  get() = ${body()}")
    return Stub(mirrorPackage(pkg), p.name, text, kind, msg)
  }

  private fun classStub(fq: String, c: KmClass): Stub {
    val pkg = packageOf(fq)
    val simple = fq.removePrefix("$pkg.")
    val (msg, kind) = messageFor(pkg, simple, "class", emptySet())
    val generics = if (c.typeParameters.isEmpty()) "" else c.typeParameters.joinToString(", ", "<", ">") { id(it.name) }
    val decl = when (c.kind) {
      ClassKind.INTERFACE -> "public interface $simple$generics"
      ClassKind.OBJECT -> "public object $simple"
      ClassKind.ANNOTATION_CLASS -> "public annotation class $simple"
      else -> "public class $simple$generics private constructor()"
    }
    return Stub(mirrorPackage(pkg), simple, render(msg, decl), kind, msg)
  }

  private fun isPublic(v: Visibility) = v == Visibility.PUBLIC

  /** Every stub for [config]'s included packages and extra stubs, in a stable order. */
  fun stubs(): List<Stub> {
    val out = mutableListOf<Stub>()
    val seen = mutableSetOf<String>()
    fun add(s: Stub?) {
      if (s != null && seen.add(s.pkg + "|" + (s.erased ?: s.text))) out += s
    }
    for (pkg in config.includePackages.sorted()) {
      val mirror = mirrorPackage(pkg)
      val taken = existing[mirror].orEmpty()
      for (pf in index.packageFunctions[pkg].orEmpty().sortedBy { it.fn.name }) {
        if (index.isHidden(pf.facade, pf.fn) || pf.fn.name.contains('$') || pf.fn.name.contains('-')) continue
        add(functionStub(pf.fn, pkg, null, member = false))
      }
      for (p in index.packageProperties[pkg].orEmpty().sortedBy { it.name }) {
        if (p.name in taken || p.name.contains('$') || p.name.contains('-')) continue
        add(propertyStub(p, pkg, member = false))
      }
      val classes = index.classes.filter { (fq, c) ->
        // The package is what precedes the class's last '/', exactly (`packageOf` guesses from letter case).
        c.name.substringBeforeLast('/', "") == pkg.replace('.', '/') && '.' !in c.name.substringAfterLast('/') && isPublic(c.visibility) &&
          c.kind != ClassKind.COMPANION_OBJECT && c.kind != ClassKind.ENUM_ENTRY && !fq.contains('$') && fq.removePrefix("$pkg.").first().isUpperCase()
      }
      for ((fq, c) in classes.toSortedMap()) {
        val simple = fq.removePrefix("$pkg.")
        if (simple in taken) continue
        add(classStub(fq, c))
      }
    }
    for (e in config.extraStubs) {
      val pkg = e.name.substringBeforeLast('.')
      val name = e.name.substringAfterLast('.')
      val msg = "Keliver: ${e.message}"
      val decl = if (e.kind == "val") {
        render(msg, "public val ${id(name)}: Any?\n  get() = ${body()}")
      } else {
        val params = e.params.mapIndexed { i, p -> "${id(p)}: Any?" + if (i > 0 || e.params.size == 1) " = null" else "" }
        render(msg, "public fun ${id(name)}(${params.joinToString(", ")}): Nothing = ${body()}")
      }
      add(Stub(mirrorPackage(pkg), name, decl, "curated", msg))
    }
    return out
  }

  /** The member stubs of the generated scope [scopeFq] (indented for the interface's body). */
  fun scopeMembers(scopeFq: String): List<Stub> {
    val pkg = packageOf(scopeFq)
    if (pkg !in config.includePackages) return emptyList()
    val c = index.classes[scopeFq] ?: return emptyList()
    val simple = scopeFq.removePrefix("$pkg.")
    val out = mutableListOf<Stub>()
    for (f in c.functions.filter { isPublic(it.visibility) }.sortedBy { it.name }) {
      if (index.isHidden(index.jvmName(scopeFq), f)) continue
      functionStub(f, pkg, simple, member = true)?.let { out += it }
    }
    for (p in c.properties.filter { isPublic(it.visibility) }.sortedBy { it.name }) propertyStub(p, pkg, member = true)?.let { out += it }
    return out.distinctBy { it.erased ?: it.text }
  }
}

/** The stub counts by message class: `1234 (curated 40, never 136, notYet 3, generic 1055)`. */
fun stubCounts(stubs: List<ErrorStubs.Stub>): String {
  val by = stubs.groupingBy { it.kind }.eachCount()
  return "${stubs.size} (" + by.entries.sortedBy { it.key }.joinToString { "${it.key} ${it.value}" } + ")"
}

/** Names declared at the top level of the Kotlin sources in [roots] (files named in [skip] excluded), by package. */
fun scanDeclaredNames(roots: List<File>, skip: Set<String>): Map<String, Set<String>> {
  val out = mutableMapOf<String, MutableSet<String>>()
  for (root in roots) {
    if (!root.exists()) continue
    for (f in root.walk().filter { it.isFile && it.extension == "kt" && it.name !in skip }.sortedBy { it.path }) {
      declaredNames(f.readText(), out)
    }
  }
  return out
}

private val packageLine = Regex("""^package\s+([\w.]+)""", RegexOption.MULTILINE)
private val topLevel = Regex(
  """^(?:@\w+(?:\([^)]*\))?\s+)*(?:(?:public|internal|private|data|sealed|enum|value|annotation|abstract|open|inline|const|fun|expect|actual)\s+)*(?:class|interface|object|typealias|fun|val|var)\s+(?:<[^>]*>\s*)?(?:[\w.<>?, ]+?\.)?`?(\w+)`?""",
  RegexOption.MULTILINE,
)

/** Adds the top-level names [text] declares to [into], under its package. */
fun declaredNames(text: String, into: MutableMap<String, MutableSet<String>>) {
  val pkg = packageLine.find(text)?.groupValues?.get(1) ?: return
  val names = into.getOrPut(pkg) { mutableSetOf() }
  for (m in topLevel.findAll(text)) names += m.groupValues[1]
}
