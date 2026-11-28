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

import kotlin.metadata.ClassKind
import kotlin.metadata.KmClass
import kotlin.metadata.KmClassifier
import kotlin.metadata.KmType
import kotlin.metadata.Modality
import kotlin.metadata.Visibility
import kotlin.metadata.isOperator
import kotlin.metadata.isSuspend
import kotlin.metadata.isValue
import kotlin.metadata.jvm.signature
import kotlin.metadata.kind
import kotlin.metadata.modality
import kotlin.metadata.visibility

/**
 * Spike (docs/COMPOSE_GENERIC_MECHANISMS.md): checks the three generic mechanisms against the whole
 * public API of the included packages, from Kotlin metadata alone.
 *
 * 1. **Call records** cover a parameter type when the guest can only obtain a value of it from a
 *    public constructor, factory function or constant property: the guest ships the call, the host
 *    makes it. [Cat.HAND] types (arithmetic value classes) keep their hand codecs.
 * 2. **Remote objects** cover host-owned state classes: properties observed, methods as commands.
 * 3. **Recorded interfaces** cover draw and layer scopes: Unit members with recordable arguments.
 */
class GenericSurvey(private val index: GenericIndex) {
  private companion object {
    /** Operators that make a value class arithmetic (a hand codec), not a constant-like one. */
    val ARITHMETIC = setOf("plus", "minus", "times", "div", "unaryMinus", "component1")
  }

  enum class Cat {
    /** Kotlin's own types (Int, String, Boolean, …) and containers of covered types. */
    PRIMITIVE,

    /** An arithmetic value class (Dp, Color, Offset, TextUnit): stays a hand codec. */
    HAND,

    /** A value only public constructors, factories or constant properties produce: a call record. */
    CALL,

    /** Host-owned state (suspend members, or a `…State` class): a remote object. */
    REMOTE,

    /** A function type (slot, callback, scope block): not a value codec's business. */
    FUNCTION,

    /** No public producer, or a type the guest cannot name. */
    UNCOVERED,
  }

  /** A public way to obtain a value: `TextStyle(…)`, `Brush.Companion.linearGradient(…)`, `CircleShape`. */
  class Producer(val id: String, val returns: String, val params: List<KmType>, val isProperty: Boolean)

  private val producersOf: Map<String, List<Producer>> by lazy { buildProducers() }

  private val cache = mutableMapOf<String, Cat>()

  fun producers(fq: String): List<Producer> = producersOf[fq].orEmpty()

  fun category(t: KmType): Cat {
    val c = t.classifier
    if (c is KmClassifier.TypeParameter) return Cat.UNCOVERED
    val fq = when (c) {
      is KmClassifier.Class -> c.name
      is KmClassifier.TypeAlias -> c.name
      else -> error("unreachable")
    }.replace('/', '.')
    if (fq.startsWith("kotlin.Function") || fq.startsWith("kotlin.coroutines.SuspendFunction") || fq.startsWith("kotlin.reflect.")) return Cat.FUNCTION
    if (fq.startsWith("kotlin.")) {
      if (t.arguments.isEmpty()) return if (fq == "kotlin.Any") Cat.UNCOVERED else Cat.PRIMITIVE
      // A container (List, Array, Pair, ranges) is as covered as its worst element.
      val worst = t.arguments.mapNotNull { it.type?.let(::category) }.maxByOrNull { it.ordinal } ?: Cat.PRIMITIVE
      return if (worst.ordinal <= Cat.CALL.ordinal) maxOf(worst, Cat.PRIMITIVE) else worst
    }
    return category(fq)
  }

  fun category(fq: String): Cat = cache.getOrPut(fq) {
    cache[fq] = Cat.UNCOVERED // cycles
    val k = index.classes[fq]
    when {
      k == null -> Cat.UNCOVERED
      k.visibility != Visibility.PUBLIC -> Cat.UNCOVERED
      k.isValue && k.functions.any { it.isOperator && it.name in ARITHMETIC } -> Cat.HAND
      isRemote(fq, k) -> Cat.REMOTE
      producers(fq).isNotEmpty() -> Cat.CALL
      else -> Cat.UNCOVERED
    }
  }

  /**
   * Host-owned state: a class (not a value) with a public suspend member, a `…State` class or
   * interface, or an interaction source. An interface with suspend members that is a behaviour
   * (`FlingBehavior`, `OverscrollEffect`) is not state: its values come from factories.
   */
  fun isRemote(fq: String, k: KmClass): Boolean {
    if (k.isValue || k.kind == ClassKind.ENUM_CLASS || index.isObject(k)) return false
    val simple = fq.substringAfterLast('.')
    if (simple.endsWith("InteractionSource")) return true
    if (simple.endsWith("State") && simple != "State") return true
    if (k.kind != ClassKind.CLASS) return false
    val members = (listOf(k) + index.allSupertypes(fq).mapNotNull { index.classes[it] })
    return members.any { m -> m.functions.any { it.isSuspend && it.visibility == Visibility.PUBLIC } }
  }

  private fun buildProducers(): Map<String, List<Producer>> {
    val out = mutableMapOf<String, MutableList<Producer>>()
    fun add(p: Producer) {
      if (!p.returns.startsWith("androidx.") && !p.returns.startsWith("coil3.")) return
      for (t in listOf(p.returns) + index.allSupertypes(p.returns)) out.getOrPut(t) { mutableListOf() } += p
    }
    for ((fq, k) in index.classes) {
      if (k.visibility != Visibility.PUBLIC) continue
      val jvm = index.jvmName(fq)
      if (k.kind == ClassKind.CLASS && k.modality != Modality.ABSTRACT && k.modality != Modality.SEALED) {
        k.constructors.filter { it.visibility == Visibility.PUBLIC && !index.isHidden(jvm, it) }.forEach { c ->
          add(Producer("$fq#<init>(${c.valueParameters.joinToString(",") { it.name }})", fq, c.valueParameters.map { it.type }, false))
        }
      }
      if (k.kind == ClassKind.ENUM_CLASS) {
        k.kmEnumEntries.forEach { e -> add(Producer("$fq#${e.name}", fq, emptyList(), true)) }
      }
      if (index.isObject(k)) {
        k.functions.filter { it.visibility == Visibility.PUBLIC && !index.isHidden(jvm, it) && it.receiverParameterType == null && !it.isSuspend }.forEach { f ->
          val r = (f.returnType.classifier as? KmClassifier.Class)?.name?.replace('/', '.') ?: return@forEach
          add(Producer("$fq#${f.name}(${f.valueParameters.joinToString(",") { it.name }})", r, f.valueParameters.map { it.type }, false))
        }
        k.properties.filter { it.visibility == Visibility.PUBLIC && it.receiverParameterType == null }.forEach { p ->
          val r = (p.returnType.classifier as? KmClassifier.Class)?.name?.replace('/', '.') ?: return@forEach
          add(Producer("$fq#${p.name}", r, emptyList(), true))
        }
      }
    }
    for ((pkg, fns) in index.packageFunctions) {
      for (pf in fns) {
        val f = pf.fn
        if (index.isHidden(pf.facade, f) || f.isSuspend) continue
        // `fun Color.Companion.hsv(…)`-style factories count; other extensions (`Modifier.x`, `Dp.coerceIn`) do not.
        val recv = (f.receiverParameterType?.classifier as? KmClassifier.Class)?.name
        if (recv != null && !recv.endsWith(".Companion")) continue
        // Composable producers other than `remember*` are composables (widgets), not value factories.
        if (f.signature?.descriptor?.contains("Landroidx/compose/runtime/Composer;") == true) continue
        val r = (f.returnType.classifier as? KmClassifier.Class)?.name?.replace('/', '.') ?: continue
        add(Producer("$pkg.${f.name}(${f.valueParameters.joinToString(",") { it.name }})", r, f.valueParameters.map { it.type }, false))
      }
    }
    for ((pkg, props) in index.packageProperties) {
      for (p in props) {
        if (p.receiverParameterType != null) continue
        val r = (p.returnType.classifier as? KmClassifier.Class)?.name?.replace('/', '.') ?: continue
        add(Producer("$pkg.${p.name}", r, emptyList(), true))
      }
    }
    return out
  }

  /** The public API surface of [packages]: composables and `Modifier` extensions, not hidden. */
  fun apiSurface(packages: List<String>): List<Pair<String, kotlin.metadata.KmFunction>> =
    packages.flatMap { pkg ->
      index.packageFunctions[pkg].orEmpty().filter { pf ->
        val f = pf.fn
        val composable = f.signature?.descriptor?.contains("Landroidx/compose/runtime/Composer;") == true
        val modifier = (f.receiverParameterType?.classifier as? KmClassifier.Class)?.name == "androidx/compose/ui/Modifier"
        !index.isHidden(pf.facade, f) && (composable && !f.name.startsWith("remember") || modifier)
      }.map { "${it.pkg}.${it.fn.name}" to it.fn }
    }

  // ---- Mechanism 2: remote objects ----

  enum class MemberCat { OBSERVE, COMMAND_AWAIT, COMMAND, SYNC_RETURN, LAMBDA, UNCOVERED_TYPE }

  class StateMember(val owner: String, val name: String, val cat: MemberCat, val detail: String)

  /** [fq]'s public members (its own and its supertypes'), classified for a generated remote object. */
  fun stateMembers(fq: String): List<StateMember> {
    val chain = listOf(fq) + index.allSupertypes(fq).filter { it.startsWith("androidx.") }
    val out = mutableListOf<StateMember>()
    val seen = mutableSetOf<String>()
    for (owner in chain) {
      val k = index.classes[owner] ?: continue
      for (p in k.properties.filter { it.visibility == Visibility.PUBLIC && it.receiverParameterType == null }) {
        if (!seen.add("p:" + p.name)) continue
        val cat = category(p.returnType)
        out += StateMember(owner, p.name, if (cat.ordinal <= Cat.CALL.ordinal) MemberCat.OBSERVE else MemberCat.UNCOVERED_TYPE, typeText(p.returnType))
      }
      for (f in k.functions.filter { it.visibility == Visibility.PUBLIC && it.receiverParameterType == null }) {
        if (!seen.add("f:" + f.name + f.valueParameters.map { it.name })) continue
        val params = f.valueParameters.map { category(it.type) }
        val ret = f.returnType.classifier.let { (it as? KmClassifier.Class)?.name }
        val sig = "${f.name}(${f.valueParameters.joinToString { it.name + ": " + typeText(it.type) }})" + (if (ret != "kotlin/Unit") ": " + typeText(f.returnType) else "")
        val cat = when {
          params.any { it == Cat.FUNCTION } -> MemberCat.LAMBDA
          params.any { it.ordinal > Cat.CALL.ordinal } -> MemberCat.UNCOVERED_TYPE
          f.isSuspend -> MemberCat.COMMAND_AWAIT
          ret == "kotlin/Unit" -> MemberCat.COMMAND
          else -> MemberCat.SYNC_RETURN
        }
        out += StateMember(owner, f.name, cat, (if (f.isSuspend) "suspend " else "") + sig)
      }
    }
    return out
  }

  // ---- Mechanism 3: recorded interfaces ----

  enum class ScopeCat { RECORD, NESTED_BLOCK, GUEST_COMPUTE, HOST_VALUE, NEEDS_HOST_RESOURCE, UNCOVERED_TYPE }

  class ScopeMember(val name: String, val cat: ScopeCat, val detail: String)

  /**
   * [scopeFq]'s public members (with its supertypes'), and the top-level extensions on it in
   * [extensionPackages], classified for a recording implementation. [recorded] are other recorded
   * interfaces (a `Path` argument ships the path's own command list).
   */
  fun scopeMembers(scopeFq: String, extensionPackages: List<String>, recorded: Set<String>, hostResources: Set<String>): List<ScopeMember> {
    val out = mutableListOf<ScopeMember>()
    val chain = listOf(scopeFq) + index.allSupertypes(scopeFq).filter { it.startsWith("androidx.") }
    val seen = mutableSetOf<String>()
    fun paramCat(t: KmType): ScopeCat? {
      val fq = (t.classifier as? KmClassifier.Class)?.name?.replace('/', '.')
      if (fq in recorded) return null
      if (fq in hostResources) return ScopeCat.NEEDS_HOST_RESOURCE
      // A `DrawScope.() -> Unit` block nests a recording (the guest runs it against the same recorder).
      if (fq != null && fq.startsWith("kotlin.Function")) return ScopeCat.NESTED_BLOCK
      val c = category(t)
      return if (c.ordinal <= Cat.CALL.ordinal) null else ScopeCat.UNCOVERED_TYPE
    }
    fun classify(name: String, params: List<KmType>, returnsUnit: Boolean, isProperty: Boolean, isVar: Boolean, detail: String) {
      val cats = params.mapNotNull(::paramCat)
      val cat = when {
        isProperty && isVar -> if (cats.isEmpty()) ScopeCat.RECORD else cats.maxBy { it.ordinal }
        isProperty -> ScopeCat.HOST_VALUE
        ScopeCat.UNCOVERED_TYPE in cats -> ScopeCat.UNCOVERED_TYPE
        ScopeCat.NEEDS_HOST_RESOURCE in cats -> ScopeCat.NEEDS_HOST_RESOURCE
        ScopeCat.NESTED_BLOCK in cats -> ScopeCat.NESTED_BLOCK
        returnsUnit -> ScopeCat.RECORD
        else -> ScopeCat.GUEST_COMPUTE
      }
      out += ScopeMember(name, cat, detail)
    }
    for (owner in chain) {
      val k = index.classes[owner] ?: continue
      for (p in k.properties.filter { it.visibility == Visibility.PUBLIC }) {
        if (!seen.add("p:" + p.name + p.receiverParameterType?.let(::typeText))) continue
        val isVar = p.setter != null
        classify(p.name, if (isVar) listOf(p.returnType) else emptyList(), false, true, isVar, "${owner.substringAfterLast('.')}.${p.name}: ${typeText(p.returnType)}")
      }
      for (f in k.functions.filter { it.visibility == Visibility.PUBLIC }) {
        val key = f.name + f.valueParameters.map { it.name } + f.receiverParameterType?.let(::typeText)
        if (!seen.add("f:$key")) continue
        val unit = (f.returnType.classifier as? KmClassifier.Class)?.name == "kotlin/Unit"
        val recv = f.receiverParameterType?.let { typeText(it) + "." } ?: ""
        classify(f.name, f.valueParameters.map { it.type }, unit, false, false, "${owner.substringAfterLast('.')}#$recv${f.name}(${f.valueParameters.joinToString { it.name }})")
      }
    }
    val scopeJvm = scopeFq.replace('.', '/')
    for (pkg in extensionPackages) {
      for (pf in index.packageFunctions[pkg].orEmpty()) {
        val f = pf.fn
        if ((f.receiverParameterType?.classifier as? KmClassifier.Class)?.name != scopeJvm || index.isHidden(pf.facade, f)) continue
        val unit = (f.returnType.classifier as? KmClassifier.Class)?.name == "kotlin/Unit"
        classify(f.name, f.valueParameters.map { it.type }, unit, false, false, "ext ${f.name}(${f.valueParameters.joinToString { it.name }})")
      }
    }
    return out
  }

  fun typeText(t: KmType): String {
    val n = when (val c = t.classifier) {
      is KmClassifier.Class -> c.name
      is KmClassifier.TypeAlias -> c.name
      is KmClassifier.TypeParameter -> "T"
    }.substringAfterLast('/')
    val args = if (t.arguments.isEmpty()) "" else t.arguments.joinToString(",", "<", ">") { a -> a.type?.let(::typeText) ?: "*" }
    return n + args
  }
}
