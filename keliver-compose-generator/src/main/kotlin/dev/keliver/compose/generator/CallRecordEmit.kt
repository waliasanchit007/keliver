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
import kotlin.metadata.KmClassifier
import kotlin.metadata.KmType
import kotlin.metadata.KmValueParameter
import kotlin.metadata.Modality
import kotlin.metadata.Visibility
import kotlin.metadata.declaresDefaultValue
import kotlin.metadata.isNullable
import kotlin.metadata.isOperator
import kotlin.metadata.isSuspend
import kotlin.metadata.jvm.getterSignature
import kotlin.metadata.jvm.signature
import kotlin.metadata.kind
import kotlin.metadata.modality
import kotlin.metadata.visibility
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * What the values emitter reads (`values.json`, MS4-42). The guest's mirrors go to [guestRoot] plus
 * the Compose package (`dev.keliver.calls.compose.ui.text.TextStyle`; MS4-13 moves the root to
 * `dev.keliver` when it deletes the hand mirrors of the same names, so a mirror's name is Compose's
 * under `dev.keliver`, as the widgets' are).
 */
class ValuesConfig(
  val guestRoot: String,
  val hostPackage: String,
  /** The Compose packages whose public producers (constructors, factories, constants) are considered. */
  val packages: List<String>,
  /** Types that never become call records (`Modifier`: a widget concern, and the hand codecs). */
  val excludeTypes: Set<String>,
  /** Producers left out by their key, each with the reason (so the record shows why a type does not cross). */
  val excludeKeys: Map<String, String>,
)

/** What one generation produced and what it left out, for the PR body and the tests. */
class ValuesReport(
  val types: Int,
  val producers: Int,
  val members: Int,
  val readableProperties: Int,
  val propertyStubs: Int,
  /** Why a candidate does not cross, by reason, with a few examples each. */
  val rejected: Map<String, List<String>>,
) {
  /** The report as a checked-in file (values.report.md): what crosses, and why the rest does not. */
  fun text(): String = buildString {
    appendLine("# Call records: what crosses (generated; do not edit)")
    appendLine()
    appendLine("Written by `:keliver-compose-generator:generateComposeApiValues` from `values.json`. The tests assert its counts do not shrink.")
    appendLine()
    appendLine("- $types mirrored types, $producers producers (constructors, factories, constants), $members receiver members")
    appendLine("- $readableProperties readable constructor-backed properties; $propertyStubs property error stubs (computed by Compose on the host)")
    appendLine()
    appendLine("## Does not cross")
    for ((reason, what) in rejected.toSortedMap()) {
      appendLine()
      appendLine("### $reason (${what.size})")
      appendLine()
      for (w in what.sorted()) appendLine("- `$w`")
    }
  }
}

class ValuesOutput(
  /** Guest mirrors, path (relative to the guest root) → text. */
  val guest: Map<String, String>,
  /** The host's replay, path (relative to the host root) → text. */
  val host: Map<String, String>,
  val editor: String,
  val lock: ValuesLock,
  val report: ValuesReport,
)

/**
 * G1, call records (docs/COMPOSE_GENERIC_MECHANISMS.md §1.1, MS4-42): a value built in the guest
 * crosses as the call that built it, `{"call":<lock tag>,"args":{…}}` (`"on":<record>` for a call on
 * a recorded value), and the host's generated `GeneratedReplay` makes the same call on Compose.
 *
 * It is its own emitter: no widget, no lock section but the shared `values` one. Every defaulted
 * parameter is nullable in the guest, with `null` as "not passed"; the wire leaves it out, and the
 * host evaluates Compose's own default expression (copied from the sources) at the call site Compose
 * would. A default that does not compile there (it names a sibling or a private member) takes
 * design §5's presence split.
 */
class CallRecordEmitter(
  private val index: GenericIndex,
  private val src: SourceReader,
  private val cfg: ValuesConfig,
  private val oldLock: ValuesLock,
) {
  private enum class Kind { CTOR, STATIC, STATIC_PROP, PACKAGE_FN, PACKAGE_PROP, MEMBER }

  /** One way to obtain (or derive) a value: a constructor, factory, constant or a call on a recorded value. */
  private inner class Cand(
    val kind: Kind,
    /** The Compose package of the producer (the type's, or the file's for a top-level one). */
    val pkg: String,
    /** The class the call is made on: the type (constructor, member), its `Companion`, or null for a top-level declaration. */
    val ownerFq: String?,
    val name: String,
    val returns: String,
    val params: List<KmValueParameter>,
    val isOperator: Boolean = false,
  ) {
    /** The type whose mirror hosts a companion producer or a member. */
    val typeFq: String? get() = ownerFq?.removeSuffix(".Companion")
    val isProperty: Boolean get() = kind == Kind.STATIC_PROP || kind == Kind.PACKAGE_PROP

    /** The canonical key (design §3): the Compose declaration, with every parameter's type. */
    val key: String by lazy {
      val head = when (kind) {
        Kind.CTOR -> "$ownerFq#<init>"
        Kind.STATIC, Kind.STATIC_PROP, Kind.MEMBER -> "$ownerFq#$name"
        Kind.PACKAGE_FN, Kind.PACKAGE_PROP -> "$pkg.$name"
      }
      if (isProperty) head else head + params.joinToString(",", "(", ")") { p -> p.name + ":" + typeText(p.varargElementType ?: p.type) + (if (p.varargElementType != null) "..." else "") }
    }
    var defaults: Map<String, String?> = emptyMap()
    var imports: Map<String, String> = emptyMap()
  }

  private val rejected = linkedMapOf<String, MutableList<String>>()
  private fun reject(reason: String, what: String) {
    rejected.getOrPut(reason) { mutableListOf() } += what
  }

  // ---------------------------------------------------------------- type support

  /** The hand codecs that stay (design §6): arithmetic value classes. Guest type, encoder, host decoder. */
  private class Hand(val guest: String, val enc: (String) -> String, val dec: (String) -> String)

  private val hand = mapOf(
    "androidx.compose.ui.graphics.Color" to Hand("dev.keliver.compose.ui.graphics.Color", { "dev.keliver.compose.ui.graphics.ColorCodec.toJson($it)" }, { "decodeColor($it)" }),
    "androidx.compose.ui.unit.Dp" to Hand("dev.keliver.compose.ui.unit.Dp", { "dev.keliver.compose.ui.unit.DpCodec.toJson($it)" }, { "decodeDp($it)" }),
    "androidx.compose.ui.unit.TextUnit" to Hand("dev.keliver.compose.ui.unit.TextUnit", { "dev.keliver.compose.ui.unit.TextUnitCodec.toJson($it)" }, { "decodeTextUnit($it)" }),
    "androidx.compose.ui.geometry.Offset" to Hand("dev.keliver.calls.compose.ui.geometry.Offset", { "$it.toJson()" }, { "decodeOffset($it)" }),
    "androidx.compose.ui.geometry.Size" to Hand("dev.keliver.calls.compose.ui.geometry.Size", { "$it.toJson()" }, { "decodeSize($it)" }),
  )

  private val primitives = mapOf(
    "kotlin.Float" to "float",
    "kotlin.Double" to "double",
    "kotlin.Int" to "int",
    "kotlin.Long" to "long",
    "kotlin.Boolean" to "boolean",
    "kotlin.String" to "content",
  )

  private fun fqOf(t: KmType): String? = when (val c = t.classifier) {
    is KmClassifier.Class -> c.name.replace('/', '.')
    else -> null
  }

  /** The types with a mirror: the fixpoint of [admit]. */
  private var mirrored: Set<String> = emptySet()

  private fun supported(t: KmType): Boolean {
    val fq = fqOf(t) ?: return false
    if (t.arguments.any { it.type == null }) return false
    return when (fq) {
      "kotlin.collections.List" -> t.arguments.singleOrNull()?.type?.let(::supported) == true
      "kotlin.Pair" -> t.arguments.size == 2 && t.arguments.all { supported(it.type!!) }
      else -> fq in primitives || fq in hand || fq in mirrored
    }
  }

  /** A parameter's type: the vararg's element type for a `vararg`. */
  private val KmValueParameter.valueType: KmType get() = varargElementType ?: type

  // ---------------------------------------------------------------- names

  private fun guestPkg(pkg: String) = cfg.guestRoot + "." + pkg.removePrefix("androidx.")

  private fun guestFq(fq: String) = guestPkg(packageOf(fq)) + "." + fq.removePrefix(packageOf(fq) + ".")

  private fun simpleOf(fq: String) = fq.removePrefix(packageOf(fq) + ".")

  private fun guestType(t: KmType, forceNullable: Boolean = false): String {
    val fq = fqOf(t)!!
    val base = when {
      fq == "kotlin.collections.List" -> "kotlin.collections.List<${guestType(t.arguments.single().type!!)}>"
      fq == "kotlin.Pair" -> "kotlin.Pair<${guestType(t.arguments[0].type!!)}, ${guestType(t.arguments[1].type!!)}>"
      fq in primitives -> fq
      fq in hand -> hand.getValue(fq).guest
      else -> guestFq(fq)
    }
    return if (t.isNullable || forceNullable) "$base?" else base
  }

  private fun hostType(t: KmType): String {
    val fq = fqOf(t)!!
    val args = if (t.arguments.isEmpty()) "" else t.arguments.joinToString(",", "<", ">") { a -> a.type?.let(::hostType) ?: "*" }
    return fq + args + (if (t.isNullable) "?" else "")
  }

  /** Guest: the JSON of [v] (non-null). */
  private fun enc(t: KmType, v: String): String {
    val fq = fqOf(t)!!
    return when {
      fq == "kotlin.collections.List" -> "kotlinx.serialization.json.JsonArray($v.map { ${enc(t.arguments.single().type!!, "it")} })"
      fq == "kotlin.Pair" -> "kotlinx.serialization.json.JsonArray(listOf(${enc(t.arguments[0].type!!, "$v.first")}, ${enc(t.arguments[1].type!!, "$v.second")}))"
      fq in primitives -> "kotlinx.serialization.json.JsonPrimitive($v)"
      fq in hand -> hand.getValue(fq).enc(v)
      else -> "$v.call.toJson()"
    }
  }

  /** Host: the Compose value of JSON [j] (non-null JSON). */
  private fun dec(t: KmType, j: String): String {
    val fq = fqOf(t)!!
    val v = when {
      fq == "kotlin.collections.List" -> "$j.jsonArray.map { ${dec(t.arguments.single().type!!, "it")} }"
      fq == "kotlin.Pair" -> "$j.jsonArray.let { Pair(${dec(t.arguments[0].type!!, "it[0]")}, ${dec(t.arguments[1].type!!, "it[1]")}) }"
      fq in primitives -> "$j.jsonPrimitive.${primitives.getValue(fq)}"
      fq in hand -> hand.getValue(fq).dec(j)
      else -> "(GeneratedReplay.replay($j) as ${hostType(t).removeSuffix("?")})"
    }
    return if (t.isNullable) "(if ($j is kotlinx.serialization.json.JsonNull) null else $v)" else v
  }

  private fun typeText(t: KmType): String {
    val n = when (val c = t.classifier) {
      is KmClassifier.Class -> c.name
      is KmClassifier.TypeAlias -> c.name
      is KmClassifier.TypeParameter -> "T"
    }.substringAfterLast('/')
    val args = if (t.arguments.isEmpty()) "" else t.arguments.joinToString(",", "<", ">") { a -> a.type?.let(::typeText) ?: "*" }
    return n + args + (if (t.isNullable) "?" else "")
  }

  // ---------------------------------------------------------------- 1. candidates

  private fun isComposable(descriptor: String?) = descriptor?.contains("Landroidx/compose/runtime/Composer;") == true

  private fun typeOk(fq: String): Boolean {
    if (fq in cfg.excludeTypes || fq in hand) return false
    val k = index.classes[fq] ?: return false
    return k.visibility == Visibility.PUBLIC && k.typeParameters.isEmpty() && fq.removePrefix(packageOf(fq) + ".").none { it == '.' }
  }

  private val memberNameDenylist = setOf("equals", "hashCode", "toString", "component1", "component2", "component3", "component4", "component5", "component6")

  private fun candidates(): List<Cand> {
    val out = mutableListOf<Cand>()
    fun returnOf(t: KmType): String? = fqOf(t)?.takeIf { it.startsWith("androidx.") && t.arguments.isEmpty() && !t.isNullable }
    for ((fq, k) in index.classes) {
      if (fq.split('.').any { it.isEmpty() }) continue
      val pkg = packageOf(fq)
      if (pkg !in cfg.packages || k.visibility != Visibility.PUBLIC) continue
      val simple = fq.removePrefix("$pkg.")
      val jvm = index.jvmName(fq)
      when {
        k.kind == ClassKind.COMPANION_OBJECT && simple.endsWith(".Companion") && simple.removeSuffix(".Companion").none { it == '.' } -> {
          for (f in k.functions) {
            if (f.visibility != Visibility.PUBLIC || f.receiverParameterType != null || f.isSuspend || f.typeParameters.isNotEmpty()) continue
            if (index.isHidden(jvm, f) || isComposable(f.signature?.descriptor)) continue
            val r = returnOf(f.returnType) ?: continue
            out += Cand(Kind.STATIC, pkg, fq, f.name, r, f.valueParameters)
          }
          for (p in k.properties) {
            if (p.visibility != Visibility.PUBLIC || p.receiverParameterType != null || isComposable(p.getterSignature?.descriptor)) continue
            val r = returnOf(p.returnType) ?: continue
            out += Cand(Kind.STATIC_PROP, pkg, fq, p.name, r, emptyList())
          }
        }
        simple.any { it == '.' } -> {}
        k.kind == ClassKind.CLASS && k.modality != Modality.ABSTRACT && k.modality != Modality.SEALED -> {
          for (c in k.constructors) {
            if (c.visibility != Visibility.PUBLIC || index.isHidden(jvm, c)) continue
            out += Cand(Kind.CTOR, pkg, fq, "<init>", fq, c.valueParameters)
          }
        }
        k.kind == ClassKind.ENUM_CLASS -> for (e in k.kmEnumEntries) out += Cand(Kind.STATIC_PROP, pkg, fq, e.name, fq, emptyList())
      }
    }
    for (pkg in cfg.packages) {
      for (pf in index.packageFunctions[pkg].orEmpty()) {
        val f = pf.fn
        if (f.receiverParameterType != null || f.isSuspend || f.typeParameters.isNotEmpty()) continue
        if (index.isHidden(pf.facade, f) || isComposable(f.signature?.descriptor)) continue
        val r = returnOf(f.returnType) ?: continue
        out += Cand(Kind.PACKAGE_FN, pkg, null, f.name, r, f.valueParameters)
      }
      for (p in index.packageProperties[pkg].orEmpty()) {
        if (p.receiverParameterType != null || isComposable(p.getterSignature?.descriptor)) continue
        val r = returnOf(p.returnType) ?: continue
        out += Cand(Kind.PACKAGE_PROP, pkg, null, p.name, r, emptyList())
      }
    }
    return out
  }

  /** Looks up the default texts a candidate needs; false (with the reason recorded) when it cannot cross. */
  private fun withDefaults(c: Cand): Boolean {
    if (c.params.none { it.declaresDefaultValue }) return true
    val owner = when (c.kind) {
      Kind.PACKAGE_FN, Kind.PACKAGE_PROP -> null
      else -> c.ownerFq!!.removePrefix(c.pkg + ".")
    }
    val found = src.defaultsOf(c.pkg, owner, c.name, c.params.map { it.name }, c.params.map { it.valueType.toRef() })
    // A data class's synthesized `copy` has no source: its defaults are "keep the receiver's value".
    if (found == null && c.params.filter { it.declaresDefaultValue }.all { copyRead(c, it) != null }) return true
    val (d, imp) = found ?: return false.also { reject("default expressions not found in the sources", c.key) }
    c.defaults = d
    c.imports = imp
    return true
  }

  /** The defaulted, carried parameters whose default text cannot be evaluated at the host's call site (design §5's presence split). */
  private fun splitParams(c: Cand): List<KmValueParameter> {
    val siblings = c.params.map { it.name }.toSet()
    return c.params.filter { p -> p.declaresDefaultValue && supported(p.valueType) && copyRead(c, p) == null && !hostEvaluable(c, c.defaults[p.name] ?: "", siblings) }
  }

  /**
   * `copy`'s defaults are "keep the receiver's value" (`color: Color = this.spanStyle.color`, which
   * names a member the host's call site cannot see). A public property of the receiver with the
   * parameter's name and type says the same thing, so the host reads that instead of splitting on
   * 24 parameters. CallRecordsTest compares such a copy with Compose's own.
   */
  private fun copyRead(c: Cand, p: KmValueParameter): String? {
    if (c.kind != Kind.MEMBER || c.name != "copy" || !p.declaresDefaultValue) return null
    val owner = index.classes[c.typeFq ?: return null] ?: return null
    val prop = owner.properties.firstOrNull { it.name == p.name && it.visibility == Visibility.PUBLIC && it.receiverParameterType == null } ?: return null
    return if (typeText(prop.returnType) == typeText(p.type)) "(on as ${c.typeFq}).${p.name}" else null
  }

  /** The guest's signature, for telling apart two Compose overloads that coincide once unsupported parameters are dropped. */
  private fun guestSig(c: Cand): String =
    (if (c.kind == Kind.CTOR || c.kind == Kind.PACKAGE_FN || c.kind == Kind.PACKAGE_PROP) "${if (c.kind == Kind.CTOR) packageOf(c.returns) else c.pkg}." else "${c.typeFq}.") +
      (if (c.kind == Kind.CTOR) simpleOf(c.returns) else c.name) +
      c.params.filter { supported(it.valueType) }.joinToString(",", "(", ")") { (if (it.varargElementType != null) "vararg " else "") + guestType(it.valueType, forceNullable = it.declaresDefaultValue) }

  /**
   * Whether the candidate can cross given the types mirrored so far: every parameter without a
   * default is supported (a defaulted one of an unsupported type is left out).
   */
  private fun carriable(c: Cand): Boolean = c.params.all { supported(it.valueType) || it.declaresDefaultValue }

  /** Whether the candidate's presence splits are within the limit of two (four call sites). */
  private fun splitsOk(c: Cand): Boolean = splitParams(c).size <= 2

  // ---------------------------------------------------------------- 2. the fixpoint

  private fun mirrorSet(fq: String): List<String> =
    listOf(fq) + index.allSupertypes(fq).filter { it.startsWith("androidx.") && typeOk(it) }

  fun generate(): ValuesOutput {
    val all = candidates().filter { cfg.excludeKeys[it.key] == null }
    val producers = all.filter { it.kind != Kind.MEMBER }
    val admitted = linkedMapOf<String, Cand>()
    val usable = producers.filter { c ->
      val ok = typeOk(c.returns) && (c.typeFq == null || typeOk(c.typeFq!!))
      if (!ok) reject("the type is nested, hidden, excluded or a hand codec", c.key)
      ok
    }.filter(::withDefaults)
    val set = linkedSetOf<String>()
    var changed = true
    while (changed) {
      changed = false
      mirrored = set
      for (c in usable) {
        if (c.key in admitted || !carriable(c)) continue
        admitted[c.key] = c
        for (t in mirrorSet(c.returns) + listOfNotNull(c.typeFq)) if (typeOk(t) && set.add(t)) changed = true
        changed = true
      }
    }
    mirrored = set
    for (c in usable) if (c.key !in admitted) reject("a required parameter's type has no producer", c.key)

    // Receiver call records: members of a mirrored type that return a mirrored type.
    val members = mutableListOf<Cand>()
    for (fq in set) {
      val k = index.classes.getValue(fq)
      val pkg = packageOf(fq)
      val jvm = index.jvmName(fq)
      for (f in k.functions) {
        if (f.visibility != Visibility.PUBLIC || f.receiverParameterType != null || f.isSuspend || f.typeParameters.isNotEmpty()) continue
        if (f.name in memberNameDenylist || index.isHidden(jvm, f) || isComposable(f.signature?.descriptor)) continue
        val r = fqOf(f.returnType)?.takeIf { it in set && f.returnType.arguments.isEmpty() && !f.returnType.isNullable } ?: continue
        // An override is the supertype mirror's member already (the host's call dispatches virtually).
        val sig = f.valueParameters.map { typeText(it.type) }
        val overrides = index.allSupertypes(fq).any { s ->
          s in set && index.classes[s]?.functions?.any { g -> g.name == f.name && g.valueParameters.map { typeText(it.type) } == sig } == true
        }
        if (overrides) continue
        members +=Cand(Kind.MEMBER, pkg, fq, f.name, r, f.valueParameters, f.isOperator)
      }
    }
    for (c in members.filter { cfg.excludeKeys[it.key] == null }) {
      if (!withDefaults(c)) continue
      if (!carriable(c)) {
        reject("a required parameter's type has no mirror", c.key)
        continue
      }
      admitted[c.key] = c
    }

    // Final filters, on the settled set of mirrors: the presence-split limit, and guest overloads that coincide.
    val sigs = mutableSetOf<String>()
    val final = admitted.values.sortedBy { it.key }.filter { c ->
      when {
        !splitsOk(c) -> false.also { reject("more than two defaults need a presence split", c.key) }
        !sigs.add(guestSig(c)) -> false.also { reject("its guest signature equals another overload's (dropped parameters)", c.key) }
        else -> true
      }
    }
    return emit(final, set)
  }

  // ---------------------------------------------------------------- 3. emission

  private class Guest(val decl: List<String>, val body: List<String>, val carried: List<KmValueParameter>)

  /** The guest's parameters (supported types only) and the statements that record them. */
  private fun guestParams(params: List<KmValueParameter>): Guest {
    val decl = mutableListOf<String>()
    val body = mutableListOf<String>()
    val carried = mutableListOf<KmValueParameter>()
    for (p in params) {
      val t = p.valueType
      if (!supported(t)) continue
      carried += p
      if (p.varargElementType != null) {
        decl += "vararg ${p.name}: ${guestType(t)}"
        body += "put(\"${p.name}\", kotlinx.serialization.json.JsonArray(${p.name}.map { ${enc(t, "it")} }))"
      } else if (p.declaresDefaultValue) {
        decl += "${p.name}: ${guestType(t, forceNullable = true)} = null"
        body += "if (${p.name} != null) put(\"${p.name}\", ${enc(t, p.name)})"
      } else {
        decl += "${p.name}: ${guestType(t)}"
        body += if (t.isNullable) {
          "put(\"${p.name}\", ${p.name}?.let { ${enc(t, "it")} } ?: kotlinx.serialization.json.JsonNull)"
        } else {
          "put(\"${p.name}\", ${enc(t, p.name)})"
        }
      }
    }
    return Guest(decl, body, carried)
  }

  /** The unary-minus-free expression of a Compose default in the guest, when the guest can write it. */
  private fun guestDefault(text: String, type: KmType): String? {
    val t = text.trim()
    return when {
      t == "null" -> "null"
      Regex("-?\\d[\\d_]*(\\.\\d+)?[fFL]?").matches(t) -> t
      t == "true" || t == "false" -> t
      Regex("\"[^\"\\\\]*\"").matches(t) -> t
      t == "Float.NaN" || t == "Float.POSITIVE_INFINITY" || t == "Float.NEGATIVE_INFINITY" -> t
      else -> {
        // `Type.Member`: a hand codec's constant, or a mirrored type's companion constant.
        val m = Regex("([A-Z][A-Za-z0-9]*)\\.([A-Z][A-Za-z0-9]*)").matchEntire(t) ?: return null
        val tf = fqOf(type) ?: return null
        val simple = tf.substringAfterLast('.')
        if (m.groupValues[1] != simple) return null
        when {
          tf in hand && m.groupValues[2] in setOf("Unspecified", "Zero", "Infinite", "Transparent", "Black", "Hairline", "Infinity") && tf in handConstants(m.groupValues[2]) ->
            "${hand.getValue(tf).guest}.${m.groupValues[2]}"
          tf in mirroredCompanionConstants -> mirroredCompanionConstants[tf]!!.takeIf { m.groupValues[2] in it }?.let { "${guestFq(tf)}.${m.groupValues[2]}" }
          else -> null
        }
      }
    }
  }

  /** Hand mirrors' constants the guest has (the codecs of keliver-compose-values, and the geometry types). */
  private fun handConstants(name: String): Set<String> = when (name) {
    "Unspecified" -> setOf("androidx.compose.ui.graphics.Color", "androidx.compose.ui.unit.Dp", "androidx.compose.ui.unit.TextUnit", "androidx.compose.ui.geometry.Offset", "androidx.compose.ui.geometry.Size")
    "Zero", "Infinite" -> setOf("androidx.compose.ui.geometry.Offset", "androidx.compose.ui.geometry.Size")
    "Transparent", "Black" -> setOf("androidx.compose.ui.graphics.Color")
    "Hairline", "Infinity" -> setOf("androidx.compose.ui.unit.Dp")
    else -> emptySet()
  }

  /** Mirrored type → its constant companion producers' names (set in [emit]). */
  private var mirroredCompanionConstants: Map<String, Set<String>> = emptyMap()

  private fun emit(admitted: List<Cand>, types: Set<String>): ValuesOutput {
    val lockBuilder = ValuesLockBuilder(oldLock)
    // Sorted so a regeneration with the same inputs is byte-identical.
    val order = admitted.sortedBy { it.key }
    val entries = order.associateWith { c ->
      // The wire of a call: the carried parameters (the lock records when each started to travel).
      lockBuilder.producer(c.key, c.params.filter { supported(it.valueType) }.map { it.name })
    }
    mirroredCompanionConstants = order.filter { it.kind == Kind.STATIC_PROP }.groupBy { it.typeFq!! }.mapValues { (_, v) -> v.map { it.name }.toSet() }

    // A type is an interface in the guest when it is abstract, open, or a supertype of another
    // mirrored type; a carrier class (`<Name>Call`) makes its values.
    fun abstractMirror(fq: String): Boolean {
      val k = index.classes.getValue(fq)
      return k.kind == ClassKind.INTERFACE || k.modality == Modality.ABSTRACT || k.modality == Modality.SEALED || k.modality == Modality.OPEN ||
        types.any { it != fq && fq in index.allSupertypes(it) }
    }

    // Readable constructor-backed properties (design §5, case 1): a property of the same name and
    // type as a constructor parameter of the type, with a default the guest can write.
    val readable = mutableMapOf<String, Map<String, String>>() // type → property → guest default text
    for (fq in types) {
      val k = index.classes.getValue(fq)
      val ctors = order.filter { it.kind == Kind.CTOR && it.returns == fq }
      if (ctors.isEmpty()) continue
      val props = mutableMapOf<String, String>()
      for (prop in k.properties.filter { it.visibility == Visibility.PUBLIC && it.receiverParameterType == null && !it.isVar }) {
        if (!supported(prop.returnType)) continue
        val declared = ctors.mapNotNull { c -> c.params.firstOrNull { it.name == prop.name && typeText(it.type) == typeText(prop.returnType) } }
        if (declared.isEmpty() || declared.size != ctors.count { c -> c.params.any { it.name == prop.name } }) continue
        val texts = ctors.mapNotNull { c ->
          val p = c.params.firstOrNull { it.name == prop.name } ?: return@mapNotNull null
          if (p.declaresDefaultValue) guestDefault(c.defaults[p.name] ?: return@mapNotNull "?", prop.returnType) ?: "?" else null
        }.toSet()
        if ("?" in texts || texts.size > 1) continue
        props[prop.name] = texts.singleOrNull() ?: ""
      }
      if (props.isNotEmpty()) readable[fq] = props
    }
    var readableCount = 0
    var stubCount = 0

    val guest = linkedMapOf<String, StringBuilder>()
    fun file(pkg: String) = guest.getOrPut(pkg) { StringBuilder() }
    val byType = order.groupBy { it.typeFq }

    for (fq in types.sorted()) {
      val k = index.classes.getValue(fq)
      val abstract = abstractMirror(fq)
      val supers = index.allSupertypes(fq).filter { it in types && abstractMirror(it) }
      val name = simpleOf(fq)
      val out = file(packageOf(fq))
      val superList = (if (abstract) listOf("dev.keliver.calls.runtime.CallRecorded") else listOf("dev.keliver.calls.runtime.CallValue(call, fieldValues)")) + supers.map(::guestFq)
      out.appendLine("/** Guest mirror of `$fq`: a recorded call the host replays (MS4-42, call records). */")
      if (abstract) {
        out.appendLine("public interface $name : ${superList.joinToString()} {")
      } else {
        out.appendLine("public class $name @dev.keliver.calls.runtime.InternalCallApi constructor(call: dev.keliver.calls.runtime.CallRecord, fieldValues: Map<String, Any?>? = null) : ${superList.joinToString()} {")
      }
      val props = readable[fq].orEmpty()
      for (prop in k.properties.filter { it.visibility == Visibility.PUBLIC && it.receiverParameterType == null }) {
        val inherited = index.allSupertypes(fq).any { s -> s in types && abstractMirror(s) && index.classes[s]?.properties?.any { it.name == prop.name } == true }
        if (inherited) continue
        if (prop.name in props) {
          val default = props.getValue(prop.name)
          val pt = guestType(prop.returnType)
          val read = "(readValues(\"$name.${prop.name}\")[\"${prop.name}\"] as $pt${if (pt.endsWith("?")) "" else "?"})"
          out.appendLine("  /** Readable: the guest built this value from a constructor call, so it holds the arguments (design §5, case 1). */")
          out.appendLine("  public val ${prop.name}: $pt get() = " + (if (default.isEmpty()) "$read!!" else "$read ?: $default"))
          readableCount++
        } else {
          val ctorBacked = order.any { it.kind == Kind.CTOR && it.returns == fq && it.params.any { p -> p.name == prop.name } }
          val why = if (ctorBacked) "constructor-backed, but its default or its types do not cross" else "computed by Compose on the host"
          out.appendLine("  @Deprecated(\"Keliver: `$name.${prop.name}` is not readable in the guest: this value is a recorded call ($why).\", level = DeprecationLevel.ERROR)")
          out.appendLine("  public val ${prop.name}: Nothing get() = error(\"call-recorded\")")
          stubCount++
        }
      }
      // Receiver call records: members that return a mirrored value.
      for (c in byType[fq].orEmpty().filter { it.kind == Kind.MEMBER }) out.append(memberFun(c, props, entries.getValue(c).tag, ::abstractMirror))
      val statics = byType[fq].orEmpty().filter { it.kind == Kind.STATIC || it.kind == Kind.STATIC_PROP }
      if (statics.isNotEmpty()) {
        out.appendLine("  public companion object {")
        for (c in statics) out.append(producerFun(c, entries.getValue(c).tag, "    ", readable, ::abstractMirror))
        out.appendLine("  }")
      }
      out.appendLine("}")
      if (abstract && order.any { it.returns == fq }) {
        out.appendLine("@dev.keliver.calls.runtime.InternalCallApi")
        out.appendLine("internal class ${name}Call(call: dev.keliver.calls.runtime.CallRecord, fieldValues: Map<String, Any?>? = null) : dev.keliver.calls.runtime.CallValue(call, fieldValues), $name")
      }
      out.appendLine()
    }
    // Constructors and top-level producers: top-level functions named as Compose names them.
    for (c in order.filter { it.kind == Kind.CTOR || it.kind == Kind.PACKAGE_FN || it.kind == Kind.PACKAGE_PROP }) {
      val pkg = if (c.kind == Kind.CTOR) packageOf(c.returns) else c.pkg
      file(pkg).append(producerFun(c, entries.getValue(c).tag, "", readable, ::abstractMirror)).appendLine()
    }

    val guestFiles = guest.mapKeys { (pkg, _) -> guestPkg(pkg).replace('.', '/') + "/GeneratedCalls.kt" }.mapValues { (path, body) ->
      header() + "@file:OptIn(dev.keliver.calls.runtime.InternalCallApi::class)\n\npackage ${path.substringBeforeLast('/').replace('/', '.')}\n\nimport dev.keliver.calls.runtime.readValues\n\n" + body.toString().trimEnd() + "\n"
    }

    // Host: one private function per producer (a JVM method is limited to 64 KB), one `when` over their tags.
    val host = StringBuilder()
    host.appendLine("/** Makes the call a guest recorded (MS4-42): the same constructor, factory, constant or member call. */")
    host.appendLine("public object GeneratedReplay {")
    host.appendLine("  public fun replay(json: JsonElement): Any {")
    host.appendLine("    val o = json.jsonObject")
    host.appendLine("    val a = o[\"args\"]?.jsonObject ?: JsonObject(emptyMap())")
    host.appendLine("    val on = o[\"on\"]?.let(::replay)")
    host.appendLine("    return when (val tag = o.getValue(\"call\").jsonPrimitive.int) {")
    for (c in order) host.appendLine("      ${entries.getValue(c).tag} -> c${entries.getValue(c).tag}(a, on) // ${c.key}")
    host.appendLine("      else -> error(\"unknown call \$tag\")")
    host.appendLine("    }")
    host.appendLine("  }")
    host.appendLine()
    for (c in order) {
      val tag = entries.getValue(c).tag
      val target = when (c.kind) {
        Kind.CTOR -> c.returns
        Kind.STATIC, Kind.STATIC_PROP -> "${c.typeFq}.${c.name}"
        Kind.PACKAGE_FN, Kind.PACKAGE_PROP -> "${c.pkg}.${c.name}"
        Kind.MEMBER -> "(on as ${c.typeFq}).${c.name}"
      }
      val body = if (c.isProperty) target else hostCall(target, c)
      host.appendLine("  private fun c$tag(a: JsonObject, on: Any?): Any = $body")
    }
    host.appendLine("}")
    val hostFile = StringBuilder(header(host = true))
    hostFile.appendLine("package ${cfg.hostPackage}")
    hostFile.appendLine()
    // The extension functions Compose's own defaults use (`0.dp`): imported, as in the file they were copied from.
    val imports = ("JsonElement JsonObject boolean double float int jsonArray jsonObject jsonPrimitive long".split(' ').map { "kotlinx.serialization.json.$it" } + hostImports).toSortedSet()
    imports.forEach { hostFile.appendLine("import $it") }
    hostFile.appendLine()
    hostFile.append(host)

    val report = ValuesReport(
      types = types.size,
      producers = order.count { it.kind != Kind.MEMBER },
      members = order.count { it.kind == Kind.MEMBER },
      readableProperties = readableCount,
      propertyStubs = stubCount,
      rejected = rejected,
    )
    val hostPath = cfg.hostPackage.replace('.', '/') + "/GeneratedReplay.kt"
    return ValuesOutput(guestFiles, mapOf(hostPath to hostFile.toString()), editor(order, entries, types, ::abstractMirror), lockBuilder.build(), report)
  }

  /** A top-level or companion producer in the guest. */
  private fun producerFun(c: Cand, tag: Int, indent: String, readable: Map<String, Map<String, String>>, abstractMirror: (String) -> Boolean): String {
    val type = guestFq(c.returns)
    val simpleReturn = simpleOf(c.returns)
    val carrier = if (abstractMirror(c.returns)) "${guestPkg(packageOf(c.returns))}.${simpleReturn}Call" else type
    val kdoc = "$indent/** `${c.key}`, recorded: the host makes the same call (lock tag $tag). */\n"
    if (c.isProperty) return kdoc + "${indent}public val ${c.name}: $type get() = $carrier(dev.keliver.calls.runtime.CallRecord($tag))\n"
    val name = if (c.kind == Kind.CTOR) simpleReturn else c.name
    val g = guestParams(c.params)
    val keep = readable[c.returns].orEmpty().keys
    val values = if (c.kind == Kind.CTOR && keep.isNotEmpty()) {
      ", fieldValues = buildMap<String, Any?> { " +g.carried.filter { it.name in keep }.joinToString("; ") { p ->
        if (p.declaresDefaultValue) "if (${p.name} != null) put(\"${p.name}\", ${p.name})" else "put(\"${p.name}\", ${p.name})"
      } + " }"
    } else {
      ""
    }
    return buildString {
      append(kdoc)
      append("${indent}public fun $name(\n")
      g.decl.forEach { append("$indent  $it,\n") }
      append("$indent): $type = $carrier(\n")
      append("$indent  dev.keliver.calls.runtime.CallRecord(\n")
      append("$indent    $tag,\n")
      append("$indent    kotlinx.serialization.json.buildJsonObject {\n")
      g.body.forEach { append("$indent      $it\n") }
      append("$indent    },\n")
      append("$indent  ),\n")
      if (values.isNotEmpty()) append("$indent  ${values.removePrefix(", ")},\n")
      append("$indent)\n")
    }
  }

  /** A receiver call record: a member of a mirrored type that returns a mirrored value. */
  private fun memberFun(c: Cand, readableProps: Map<String, String>, tag: Int, abstractMirror: (String) -> Boolean): String {
    val type = guestFq(c.returns)
    val g = guestParams(c.params)
    val carrier = if (abstractMirror(c.returns)) "${guestPkg(packageOf(c.returns))}.${simpleOf(c.returns)}Call" else type
    // `copy` of a type whose fields are readable keeps them readable: the new value is the old one's fields plus the passed.
    // Every parameter is a property of the type (a `brush`/`alpha` overload that is not is unknown): the
    // readable ones are applied, and the others are error stubs in the guest anyway.
    val propNames = index.classes.getValue(c.returns).properties.filter { it.visibility == Visibility.PUBLIC }.map { it.name }.toSet()
    val keepValues = c.name == "copy" && c.returns == c.typeFq && readableProps.isNotEmpty() && c.params.all { it.name in propNames }
    val values = if (keepValues) {
      "fieldValues = fieldValues?.let { old -> old + buildMap<String, Any?> { " + g.carried.filter { it.name in readableProps }.joinToString("; ") { p -> if (p.declaresDefaultValue) "if (${p.name} != null) put(\"${p.name}\", ${p.name})" else "put(\"${p.name}\", ${p.name})" } + " } }"
    } else {
      null
    }
    return buildString {
      append("  /** `${c.key}` on a recorded value: the call carries the receiver (lock tag $tag). */\n")
      append("  public ${if (c.isOperator) "operator " else ""}fun ${c.name}(\n")
      g.decl.forEach { append("    $it,\n") }
      append("  ): $type = $carrier(\n")
      append("    dev.keliver.calls.runtime.CallRecord(\n")
      append("      $tag,\n")
      append("      kotlinx.serialization.json.buildJsonObject {\n")
      g.body.forEach { append("        $it\n") }
      append("      },\n")
      append("      on = call,\n")
      append("    ),\n")
      if (values != null) append("    $values,\n")
      append("  )\n")
    }
  }

  // ---------------------------------------------------------------- host

  /** Whether a copied default compiles at the host's call site: not if it names a sibling parameter or a private member. */
  private fun hostEvaluable(c: Cand, text: String, siblings: Set<String>): Boolean {
    if (Regex("(?<![.\\w])([a-z][A-Za-z0-9_]*)\\b").findAll(text).any { it.value in siblings }) return false
    // A default that names an internal type (`TypographyTokens.DisplayLarge`) does not compile outside Compose.
    for (m in Regex("(?<![.\\w])([A-Z][A-Za-z0-9_]*)").findAll(text)) {
      val k = index.classes[resolveName(m.value, c.imports, c.pkg, c.typeFq)]
      if (k != null && k.visibility != Visibility.PUBLIC) return false
    }
    val owner = c.typeFq?.let { index.classes[it] } ?: return true
    // A bare member name (implicit receiver) is the type's own, which the host's call site does not have.
    val members = (owner.functions.map { it.name } + owner.properties.map { it.name }).toSet()
    if (Regex("(?<![.\\w])([a-z][A-Za-z0-9_]*)\\b").findAll(text).any { it.value in members }) return false
    val hidden = owner.functions.filter { it.visibility != Visibility.PUBLIC }.map { it.name }.toSet() +
      owner.properties.filter { it.visibility != Visibility.PUBLIC }.map { it.name }
    return Regex("\\.([a-z][A-Za-z0-9_]*)").findAll(text).none { it.groupValues[1] in hidden }
  }

  /** The extension functions the copied defaults use (`0.dp`), imported by the generated file as in the file they came from. */
  private val hostImports = linkedSetOf<String>()

  /** The fully qualified name a capitalised simple name in a default expression stands for (an import, the file's package, the owner's companion). */
  private fun resolveName(n: String, imports: Map<String, String>, pkg: String, ownerFq: String?): String = when {
    imports[n] != null -> imports.getValue(n)
    index.classes.containsKey("$pkg.$n") -> "$pkg.$n"
    index.packageProperties[pkg]?.any { it.name == n } == true -> "$pkg.$n"
    ownerFq != null && index.classes["$ownerFq.Companion"]?.properties?.any { it.name == n } == true -> "$ownerFq.$n"
    else -> n
  }

  /** A default expression from the sources, with simple names qualified so it compiles anywhere. */
  private fun qualify(text: String, imports: Map<String, String>, pkg: String, ownerFq: String?, receiver: String? = null): String {
    for (m in Regex("\\.([a-z][A-Za-z0-9_]*)\\b").findAll(text)) {
      val fq = imports[m.groupValues[1]] ?: continue
      if (fq.substringBeforeLast('.').let { p -> p.isNotEmpty() && p.none { it.isUpperCase() } }) hostImports += fq
    }
    val q = Regex("(?<![.\\w])([A-Z][A-Za-z0-9_]*)").replace(text) { m -> resolveName(m.value, imports, pkg, ownerFq) }
    // A member's `this.x` is the receiver the guest recorded the call on.
    return if (receiver == null) q else q.replace(Regex("\\bthis\\b"), "($receiver)")
  }

  /**
   * The host's call of [target]: each argument is the guest's value, else Compose's own default
   * expression. A default that does not compile at the host is a presence split: the argument is
   * left out of the call text when the guest did not send it (at most two per call).
   */
  private fun hostCall(target: String, c: Cand): String {
    val carried = c.params.filter { supported(it.valueType) }
    val siblings = c.params.map { it.name }.toSet()
    val ownerForDefaults = c.typeFq
    val split = carried.filter { p -> p.declaresDefaultValue && copyRead(c, p) == null && !hostEvaluable(c, c.defaults[p.name] ?: "", siblings) }
    val pkg = packageOf(c.typeFq ?: c.returns).let { if (c.typeFq == null) c.pkg else it }
    fun arg(p: KmValueParameter, present: Set<String>): String {
      val t = p.valueType
      if (p.varargElementType != null) {
        val elem = fqOf(t)
        val arr = when (elem) {
          "kotlin.Int" -> "toIntArray()"
          "kotlin.Float" -> "toFloatArray()"
          "kotlin.Long" -> "toLongArray()"
          "kotlin.Double" -> "toDoubleArray()"
          "kotlin.Boolean" -> "toBooleanArray()"
          else -> "toTypedArray()"
        }
        return "${p.name} = *a.getValue(\"${p.name}\").jsonArray.map { ${dec(t, "it")} }.$arr"
      }
      val decoded = dec(t, "it")
      return when {
        !p.declaresDefaultValue || p.name in present -> "${p.name} = a.getValue(\"${p.name}\").let { $decoded }"
        copyRead(c, p) != null -> "${p.name} = a[\"${p.name}\"]?.let { $decoded } ?: ${copyRead(c, p)}"
        else -> "${p.name} = a[\"${p.name}\"]?.let { $decoded } ?: (${qualify(c.defaults.getValue(p.name) ?: error("${c.key}: no default text for ${p.name}"), c.imports, pkg, ownerForDefaults, if (c.kind == Kind.MEMBER) "on as ${c.typeFq}" else null)})"
      }
    }
    fun call(present: Set<String>): String = "$target(\n" + carried.filter { it !in split || it.name in present }.joinToString(",\n") { "      ${arg(it, present)}" } + "\n    )"
    fun branch(rest: List<KmValueParameter>, present: Set<String>): String {
      if (rest.isEmpty()) return call(present)
      val p = rest.first()
      return "if (\"${p.name}\" in a) ${branch(rest.drop(1), present + p.name)} else ${branch(rest.drop(1), present)}"
    }
    // More than two presence splits are not generated (four call sites each): [splitsOk] left those producers out.
    return branch(split, emptySet())
  }

  // ---------------------------------------------------------------- editor

  /** The editor's view of the values (kinds): per type, its producers and their parameters. */
  private fun editor(order: List<Cand>, entries: Map<Cand, ValuesEntry>, types: Set<String>, abstractMirror: (String) -> Boolean): String {
    val typeObjects = types.sorted().associateWith { fq ->
      val mine = order.filter { it.returns == fq }
      JsonObject(
        mapOf(
          "kind" to JsonPrimitive("call-record"),
          "guest" to JsonPrimitive(guestFq(fq)),
          "interface" to JsonPrimitive(abstractMirror(fq)),
          "producers" to JsonArray(
            mine.map { c ->
              JsonObject(
                mapOf(
                  "tag" to JsonPrimitive(entries.getValue(c).tag),
                  "key" to JsonPrimitive(c.key),
                  "call" to JsonPrimitive(c.kind.name.lowercase()),
                  "params" to JsonArray(
                    c.params.filter { supported(it.valueType) }.map { p ->
                      JsonObject(
                        mapOf(
                          "name" to JsonPrimitive(p.name),
                          "type" to JsonPrimitive(typeText(p.valueType)),
                          "optional" to JsonPrimitive(p.declaresDefaultValue),
                          "vararg" to JsonPrimitive(p.varargElementType != null),
                        ),
                      )
                    },
                  ),
                ),
              )
            },
          ),
        ),
      )
    }
    return lockJson.encodeToString(JsonObject.serializer(), JsonObject(mapOf("types" to JsonObject(typeObjects)))) + "\n"
  }

  private fun header(host: Boolean = false) = """
    |// Code generated by keliver-compose-generator (MS4-42, call records, shared values lock). DO NOT EDIT.
    |@file:Suppress("ktlint", "RedundantVisibilityModifier", "UNUSED_PARAMETER", "DEPRECATION", "DEPRECATION_ERROR", "UNCHECKED_CAST", "OPT_IN_USAGE", "OPT_IN_USAGE_ERROR"${if (host) ", \"UNUSED_VARIABLE\"" else ""})
    |
  """.trimMargin()

  private val kotlin.metadata.KmProperty.isVar: Boolean get() = setter != null
}
