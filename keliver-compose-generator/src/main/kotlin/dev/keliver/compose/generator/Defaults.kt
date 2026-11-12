/*
 * Copyright (C) 2026 Square, Inc.
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
import java.lang.reflect.Method
import java.net.URLClassLoader

/**
 * The `*Defaults` objects the generated set's default expressions name (`ButtonDefaults.shape`,
 * `CardDefaults.cardColors()`, `DividerDefaults.Thickness`), mirrored for the guest
 * (docs/COMPOSE_GENERATOR_DESIGN.md §5–§7). Each member is one of:
 *
 * - [Member.Reference]: a `@Composable` getter of a colour, shape or text style. The guest's value
 *   is a reference by name; the host resolves it by calling the real getter where the widget renders.
 * - [Member.Snapshot]: a plain value (`Dp`, `PaddingValues`, …), read from the jar at generation
 *   time and written into the guest as a literal. As a parameter's default it never travels (the
 *   host evaluates the default itself); a guest that passes it explicitly sends the snapshot.
 * - [Member.Factory]: a function returning a type only factories make (`ButtonColors`). Every
 *   public overload is mirrored; a call is recorded in the guest and made by the host, by its tag in
 *   the lock. A factory parameter whose default names non-public API (`FilledButtonTokens.…`) gets
 *   that value as a snapshot, so the host can always call with every argument.
 */
class DefaultsMirror(
  private val api: ApiReader,
  private val src: SourceReader,
  classpath: List<File>,
  /**
   * Types the config says cannot cross (its `typeMessages`, by type): a factory of one
   * (`ScrollableDefaults.flingBehavior()`) is not mirrored, so a parameter defaulting to it is left
   * out of the stub with that message, as a parameter of that type would be.
   */
  private val refused: Map<String, String> = emptyMap(),
) {
  sealed interface Member {
    val ownerFq: String
    val name: String

    data class Reference(override val ownerFq: String, override val name: String, val type: TypeRef) : Member
    data class Snapshot(override val ownerFq: String, override val name: String, val type: TypeRef, val literal: String) : Member
    data class Factory(override val ownerFq: String, override val name: String, val overloads: List<DeclInfo>) : Member

    /** Not mirrored: an ERROR property, or an ERROR function when Compose's member is a function ([call]). */
    data class Unsupported(override val ownerFq: String, override val name: String, val reason: String, val call: Boolean = false) : Member
  }

  private val loader by lazy { URLClassLoader(classpath.map { it.toURI().toURL() }.toTypedArray(), ClassLoader.getPlatformClassLoader()) }
  private val members = linkedMapOf<String, Member>()

  /** Everything resolved, in first-reference order. */
  val all: Collection<Member> get() = members.values

  /** The members [expr] names, resolved (and their factories' defaults, recursively). */
  fun resolve(expr: String, imports: Map<String, String>, pkg: String): List<Member> =
    DEFAULTS_REF.findAll(expr).map { m ->
      val obj = m.groupValues[1]
      val ownerFq = imports[obj] ?: "$pkg.$obj"
      member(ownerFq, m.groupValues[2], isCall = m.groupValues[3].isNotEmpty())
    }.toList()

  private fun member(ownerFq: String, name: String, isCall: Boolean): Member {
    val key = "$ownerFq.$name"
    members[key]?.let { return it }
    val m = if (isCall) factory(ownerFq, name) else property(ownerFq, name)
    members[key] = m
    if (m is Member.Factory) {
      // A factory's return type is mirrored as an opaque value before its parameters are classified.
      m.overloads.map { it.returns.fq }.filter { !TypeMap.isMapped(TypeRef(it)) }.forEach { TypeMap.opaque += it }
    }
    return m
  }

  private fun property(ownerFq: String, name: String): Member {
    val type = api.property(ownerFq, name) ?: return Member.Unsupported(ownerFq, name, "no public property $name")
    val source = src.property(ownerFq, name) ?: return Member.Unsupported(ownerFq, name, "no source for $name")
    return when {
      source.composableGetter && type.fq in REFERENCE_TYPES && !type.nullable -> Member.Reference(ownerFq, name, type)
      !source.composableGetter && type.fq in SNAPSHOT_TYPES && !type.nullable ->
        snapshot(ownerFq, name, type)?.let { Member.Snapshot(ownerFq, name, type, it) }
          ?: Member.Unsupported(ownerFq, name, "its value could not be read")
      else -> Member.Unsupported(ownerFq, name, "a ${if (source.composableGetter) "composable " else ""}${type.canonical()} has no mirror")
    }
  }

  private fun factory(ownerFq: String, name: String): Member {
    // Overloads the sources do not show (platform-specific ones) are left out.
    val overloads = api.functions(ownerFq, name).filter { runCatching { src.complete(it) }.isSuccess }
    if (overloads.isEmpty()) return Member.Unsupported(ownerFq, name, "no public function $name", call = true)
    val visible = overloads.filter { !it.isHidden }
    visible.map { it.returns.fq }.distinct().singleOrNull()?.let { refused[it] }
      ?.let { return Member.Unsupported(ownerFq, name, it.removeSuffix("."), call = true) }
    return Member.Factory(ownerFq, name, visible)
  }

  /**
   * [expr] (a factory parameter's default) with each value it names that code outside Compose
   * cannot (a member of a non-public object such as `FilledButtonTokens.ContainerElevation`, or a
   * non-public top-level constant such as material3's `DisabledAlpha`) replaced by its value when
   * this was generated, written for the guest's mirrors ([mirror]) or for Compose (the host);
   * [expr] itself when it names none; null when one cannot be read.
   */
  fun snapshotTokens(expr: String, imports: Map<String, String>, pkg: String, mirror: Boolean): String? {
    var failed = false
    val out = TOKEN.replace(expr) { m ->
      val (dot, owner, member, call) = m.destructured
      if (dot.isNotEmpty() || call.isNotEmpty() || failed) return@replace m.value
      val ownerFq = imports[owner] ?: "$pkg.$owner"
      if (member.isNotEmpty()) {
        // `Owner.member`: only a non-public owner needs a snapshot.
        if (api.isPublicClass(ownerFq) != false) return@replace m.value
        val type = api.anyProperty(ownerFq, member)
        val literal = type?.let { snapshot(ownerFq, member, it, mirror) }
        if (literal == null) failed = true
        literal ?: m.value
      } else {
        // A bare name: a non-public top-level property of the declaration's package.
        if (owner in imports) return@replace m.value
        val (facade, type, public) = api.topLevelProperty(pkg, owner) ?: return@replace m.value
        if (public) return@replace m.value
        val literal = runCatching { literal(staticValue(loader.loadClass(facade), owner)!!, type, mirror) }.getOrNull()
        if (literal == null) failed = true
        literal ?: m.value
      }
    }
    return if (failed) null else out
  }

  private fun snapshot(ownerFq: String, name: String, type: TypeRef, mirror: Boolean = true): String? {
    val value = runCatching {
      val cls = loader.loadClass(binaryName(ownerFq))
      val instance = cls.getField("INSTANCE").get(null)
      getter(cls, name)!!.invoke(instance)
    }.getOrNull() ?: return null
    return literal(value, type, mirror)
  }

  private fun getter(cls: Class<*>, name: String): Method? {
    val base = "get" + name.replaceFirstChar { it.uppercase() }
    // Public members, then a non-public object's (`getFoo-D9Ej5fM` for a value class, `getFoo$module` if internal).
    return (cls.methods.asSequence() + cls.declaredMethods.asSequence())
      .firstOrNull { it.parameterCount == 0 && (it.name == base || it.name.startsWith("$base-") || it.name.startsWith("$base$")) }
      ?.also { it.isAccessible = true }
  }

  /** A top-level property's value: a `const val`'s static field, or its static getter. */
  private fun staticValue(facade: Class<*>, name: String): Any? {
    facade.declaredFields.firstOrNull { it.name == name && java.lang.reflect.Modifier.isStatic(it.modifiers) }
      ?.let { return it.also { f -> f.isAccessible = true }.get(null) }
    return getter(facade, name)?.invoke(null)
  }

  /** A JVM value of a Compose type as the guest ([mirror]) or the host writes it. */
  private fun literal(value: Any, type: TypeRef, mirror: Boolean = true): String? {
    val written = when (type.fq) {
      "androidx.compose.ui.unit.Dp" -> (value as Float).let { "dev.keliver.compose.ui.unit.Dp(${fmt(it)}f)" }
      "kotlin.Float" -> "${fmt(value as Float)}f"
      "kotlin.Int" -> "$value"
      "kotlin.Boolean" -> "$value"
      "androidx.compose.ui.graphics.Color" -> (value as Long).let { packed ->
        if ((packed and 0x3FL) != 0L) null else "dev.keliver.compose.ui.graphics.Color(0x${((packed ushr 32) and 0xFFFFFFFFL).toString(16).uppercase().padStart(8, '0')})"
      }
      "androidx.compose.foundation.layout.PaddingValues" -> paddingLiteral(value)
      else -> null
    } ?: return null
    // The mirrors have Compose's constructors, so the host's literal is the same text in Compose's packages.
    return if (mirror) written else written.replace("dev.keliver.compose.", "androidx.compose.")
  }

  private fun paddingLiteral(value: Any): String? {
    val ltr = loader.loadClass("androidx.compose.ui.unit.LayoutDirection").getField("Ltr").get(null)
    fun call(prefix: String, vararg args: Any): Float? =
      value.javaClass.methods.firstOrNull { it.name == prefix || it.name.startsWith("$prefix-") }
        ?.also { it.isAccessible = true }?.invoke(value, *args) as Float?
    val start = call("calculateLeftPadding", ltr) ?: return null
    val top = call("calculateTopPadding") ?: return null
    val end = call("calculateRightPadding", ltr) ?: return null
    val bottom = call("calculateBottomPadding") ?: return null
    fun dp(v: Float) = "dev.keliver.compose.ui.unit.Dp(${fmt(v)}f)"
    return "dev.keliver.compose.foundation.layout.PaddingValues(start = ${dp(start)}, top = ${dp(top)}, end = ${dp(end)}, bottom = ${dp(bottom)})"
  }

  private fun fmt(f: Float): String = if (f == f.toInt().toFloat()) f.toInt().toString() else f.toString()

  companion object {
    val DEFAULTS_REF = Regex("""\b([A-Z][A-Za-z0-9]*Defaults)\.([A-Za-z_][A-Za-z0-9_]*)(\s*\()?""")

    /** A capitalised name not after a dot, optionally `.member`, and whether a call follows. */
    private val TOKEN = Regex("""(\.?)\b([A-Z][A-Za-z0-9_]*)(?:\.([A-Za-z_][A-Za-z0-9_]*))?\b(\s*\()?""")

    /** Types a reference can stand for: their mirrors carry a name the host resolves. */
    val REFERENCE_TYPES = setOf("androidx.compose.ui.graphics.Color", "androidx.compose.ui.graphics.Shape")

    /** Types a snapshot can be written as. */
    val SNAPSHOT_TYPES = setOf(
      "androidx.compose.ui.unit.Dp",
      "kotlin.Float",
      "kotlin.Int",
      "kotlin.Boolean",
      "androidx.compose.ui.graphics.Color",
      "androidx.compose.foundation.layout.PaddingValues",
    )
  }
}

/** `androidx.compose.material3.ButtonDefaults` → its JVM binary name (nested classes joined by `$`). */
fun binaryName(fq: String): String {
  val pkg = packageOf(fq)
  return "$pkg." + fq.removePrefix("$pkg.").replace('.', '$')
}
