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
import kotlin.metadata.KmType
import kotlin.metadata.KmValueParameter
import kotlin.metadata.Modality
import kotlin.metadata.Visibility
import kotlin.metadata.declaresDefaultValue
import kotlin.metadata.isNullable
import kotlin.metadata.isSuspend
import kotlin.metadata.kind
import kotlin.metadata.modality
import kotlin.metadata.visibility

/**
 * Spike (docs/COMPOSE_GENERIC_MECHANISMS.md, part b): the three generic mechanisms, emitted from
 * metadata with no per-type code.
 *
 * - [callRecords]: for each configured producer (constructor, factory, constant), a guest mirror
 *   that records the call, and one host `replay` that makes the same call.
 * - [remoteObject]: a guest proxy of a host-owned state class and its host dispatcher.
 * - [recordedInterface]: a guest recording implementation of a scope interface and the host's
 *   replay onto the real scope.
 *
 * One rule for every defaulted parameter: the guest's parameter is nullable with a `null` default
 * ("not passed"), the wire leaves it out, and the host evaluates Compose's own default expression
 * (copied from the sources) in the same place Compose would.
 */
class GenericEmitter(private val index: GenericIndex, private val src: SourceReader) {
  /** Where the spike's guest mirrors live: `dev.keliver.spike.` + the package without `androidx.`. */
  fun guestPkg(pkg: String) = "dev.keliver.spike." + pkg.removePrefix("androidx.")

  fun guestFq(fq: String) = guestPkg(packageOf(fq)) + "." + fq.removePrefix(packageOf(fq) + ".")

  /** The hand codecs that stay (design §6): arithmetic value classes. Guest type, encoder, host decoder. */
  private class Hand(val guest: String, val enc: (String) -> String, val dec: (String) -> String)

  private val hand = mapOf(
    "androidx.compose.ui.graphics.Color" to Hand("dev.keliver.compose.ui.graphics.Color", { "dev.keliver.compose.ui.graphics.ColorCodec.toJson($it)" }, { "dev.keliver.spike.host.decodeColor($it)" }),
    "androidx.compose.ui.unit.Dp" to Hand("dev.keliver.compose.ui.unit.Dp", { "dev.keliver.compose.ui.unit.DpCodec.toJson($it)" }, { "dev.keliver.spike.host.decodeDp($it)" }),
    "androidx.compose.ui.unit.TextUnit" to Hand("dev.keliver.compose.ui.unit.TextUnit", { "dev.keliver.compose.ui.unit.TextUnitCodec.toJson($it)" }, { "dev.keliver.spike.host.decodeTextUnit($it)" }),
    "androidx.compose.ui.geometry.Offset" to Hand("dev.keliver.spike.compose.ui.geometry.Offset", { "$it.toJson()" }, { "dev.keliver.spike.host.decodeOffset($it)" }),
    "androidx.compose.ui.geometry.Size" to Hand("dev.keliver.spike.compose.ui.geometry.Size", { "$it.toJson()" }, { "dev.keliver.spike.host.decodeSize($it)" }),
  )

  private val primitives = mapOf("kotlin.Float" to "float", "kotlin.Int" to "int", "kotlin.Boolean" to "boolean", "kotlin.String" to "content", "kotlin.Long" to "long")

  /** Types with a generated call-record mirror (set by [callRecords]). */
  private var mirrored: Set<String> = emptySet()

  private fun fqOf(t: KmType): String? = when (val c = t.classifier) {
    is KmClassifier.Class -> c.name.replace('/', '.')
    else -> null
  }

  /** Whether [t] crosses in this spike: a primitive, a hand codec, a mirrored call type, or a List of one. */
  fun supported(t: KmType): Boolean {
    val fq = fqOf(t) ?: return false
    if (fq == "kotlin.collections.List") return t.arguments.singleOrNull()?.type?.let(::supported) == true
    return fq in primitives || fq in hand || fq in mirrored
  }

  fun guestType(t: KmType, forceNullable: Boolean = false): String {
    val fq = fqOf(t)!!
    val base = when {
      fq == "kotlin.collections.List" -> "kotlin.collections.List<${guestType(t.arguments.single().type!!)}>"
      fq in primitives -> fq
      fq in hand -> hand.getValue(fq).guest
      else -> guestFq(fq)
    }
    return if (t.isNullable || forceNullable) "$base?" else base
  }

  /** Guest: the JSON of [v] (non-null). */
  private fun enc(t: KmType, v: String): String {
    val fq = fqOf(t)!!
    return when {
      fq == "kotlin.collections.List" -> "kotlinx.serialization.json.JsonArray($v.map { ${enc(t.arguments.single().type!!, "it")} })"
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
      fq in primitives -> "$j.jsonPrimitive.${primitives.getValue(fq)}"
      fq in hand -> hand.getValue(fq).dec(j)
      else -> "(GeneratedReplay.replay($j) as ${hostType(t)})"
    }
    return if (t.isNullable) "(if ($j is kotlinx.serialization.json.JsonNull) null else $v)" else v
  }

  private fun hostType(t: KmType): String {
    val fq = fqOf(t)!!
    val args = if (t.arguments.isEmpty()) "" else t.arguments.joinToString(",", "<", ">") { a -> a.type?.let(::hostType) ?: "*" }
    return fq + args
  }

  /** A default expression from the sources, with simple names qualified so it compiles anywhere. */
  private fun qualify(text: String, imports: Map<String, String>, pkg: String, ownerFq: String?): String =
    Regex("(?<![.\\w])([A-Z][A-Za-z0-9_]*)").replace(text) { m ->
      val n = m.value
      when {
        imports[n] != null -> imports.getValue(n)
        index.classes.containsKey("$pkg.$n") -> "$pkg.$n"
        // A companion member used unqualified inside its class (`DefaultBlendMode` in DrawScope).
        ownerFq != null && index.classes["$ownerFq.Companion"]?.properties?.any { it.name == n } == true -> "$ownerFq.$n"
        else -> n
      }
    }

  // ---------------------------------------------------------------- 1. call records

  /** A configured producer: `pkg.Type#<init>(a,b)`, `pkg.Type.Companion#fn(a,b)`, `pkg.Type.Companion#Prop`, `pkg.fn(a:Type)`. */
  class Producer(
    val id: String,
    val returns: String,
    val ownerFq: String?,
    val name: String,
    val params: List<KmValueParameter>,
    val isProperty: Boolean,
    var defaults: Map<String, String?> = emptyMap(),
    var imports: Map<String, String> = emptyMap(),
  )

  fun resolveProducer(spec: String): Producer {
    val head = spec.substringBefore('(')
    val paramSpecs = if ('(' in spec) spec.substringAfter('(').removeSuffix(")").split(',').map { it.trim() }.filter { it.isNotEmpty() } else null
    val names = paramSpecs?.map { it.substringBefore(':') }
    fun matches(ps: List<KmValueParameter>) = names != null && ps.map { it.name } == names &&
      paramSpecs.zip(ps).all { (s, p) -> ':' !in s || fqOf(p.type)?.substringAfterLast('.') == s.substringAfter(':') }
    if ('#' !in head) {
      // A top-level factory: `androidx.compose.foundation.shape.RoundedCornerShape(size:Dp)`.
      val pkg = head.substringBeforeLast('.')
      val name = head.substringAfterLast('.')
      val pf = index.packageFunctions[pkg].orEmpty().single { it.fn.name == name && matches(it.fn.valueParameters) && !index.isHidden(it.facade, it.fn) }
      return Producer(spec, fqOf(pf.fn.returnType)!!, null, name, pf.fn.valueParameters, false)
    }
    val ownerFq = head.substringBefore('#')
    val member = head.substringAfter('#')
    val k = index.classes[ownerFq] ?: error("$spec: no class $ownerFq")
    val jvm = index.jvmName(ownerFq)
    return when {
      member == "<init>" -> {
        val c = k.constructors.single { it.visibility == Visibility.PUBLIC && matches(it.valueParameters) && !index.isHidden(jvm, it) }
        Producer(spec, ownerFq, ownerFq, "<init>", c.valueParameters, false)
      }
      names == null -> {
        val p = k.properties.single { it.name == member }
        Producer(spec, fqOf(p.returnType)!!, ownerFq, member, emptyList(), true)
      }
      else -> {
        val f = k.functions.single { it.name == member && matches(it.valueParameters) && !index.isHidden(jvm, it) }
        Producer(spec, fqOf(f.returnType)!!, ownerFq, member, f.valueParameters, false)
      }
    }
  }

  /** The class whose companion or constructor a producer belongs to (`Brush` for `Brush.Companion`). */
  private fun Producer.typeOwner(): String? = ownerFq?.removeSuffix(".Companion")

  /** What the emitter left out, for the report: `<producer>: <param> (<type>)`. */
  val omitted = mutableListOf<String>()

  /**
   * Guest mirrors (path → text, under the guest root) and the host's `GeneratedReplay` for
   * [specs]. Every return type and its Compose supertypes get a mirror.
   */
  fun callRecords(specs: List<String>): Pair<Map<String, String>, String> {
    val producers = specs.map(::resolveProducer)
    mirrored = producers.flatMap { p -> listOf(p.returns) + index.allSupertypes(p.returns).filter { it.startsWith("androidx.") && index.classes[it]?.visibility == Visibility.PUBLIC } }.toSet()
    for (p in producers) if (!p.isProperty) {
      val typeOwner = p.typeOwner()
      val pkg = if (typeOwner == null) p.id.substringBefore('(').substringBeforeLast('.') else packageOf(typeOwner)
      val owner = typeOwner?.removePrefix("$pkg.")?.let { if (p.ownerFq!!.endsWith(".Companion")) "$it.Companion" else it }
      val (d, imp) = src.defaultsOf(pkg, owner, p.name, p.params.map { it.name }, p.params.map { it.type.toRef() }) ?: error("${p.id}: not found in the sources")
      p.defaults = d
      p.imports = imp
    }

    val guest = linkedMapOf<String, StringBuilder>()
    fun file(pkg: String) = guest.getOrPut(pkg) { StringBuilder() }

    // One mirror per type: an interface for an abstract Compose type, a final class otherwise.
    for (fq in mirrored) {
      val k = index.classes.getValue(fq)
      val abstract = k.kind == ClassKind.INTERFACE || k.modality == Modality.ABSTRACT || k.modality == Modality.SEALED
      val supers = index.allSupertypes(fq).filter { it in mirrored && index.classes[it]?.let { s -> s.kind == ClassKind.INTERFACE || s.modality != Modality.FINAL } == true }
      val superList = (if (abstract) listOf("dev.keliver.spike.wire.CallRecorded") else listOf("dev.keliver.spike.wire.CallValue(call)")) + supers.map(::guestFq)
      val name = fq.removePrefix(packageOf(fq) + ".")
      val out = file(packageOf(fq))
      out.appendLine("/** Guest mirror of `$fq`: a recorded call the host replays (spike, call records). */")
      if (abstract) {
        out.appendLine("public interface $name : ${superList.joinToString()} {")
      } else {
        out.appendLine("public class $name @dev.keliver.spike.wire.InternalSpikeApi constructor(call: dev.keliver.spike.wire.CallRecord) : ${superList.joinToString()} {")
      }
      // Reads of a recorded value: the guest holds the call, not the value. Each public property
      // declared here is an error stub (inherited ones are stubbed once, on the mirror that declares them).
      for (prop in k.properties.filter { it.visibility == Visibility.PUBLIC && it.receiverParameterType == null }) {
        val inherited = index.allSupertypes(fq).any { s -> s in mirrored && index.classes[s]?.properties?.any { it.name == prop.name } == true }
        if (inherited) continue
        val ctorBacked = k.constructors.any { c -> c.valueParameters.any { it.name == prop.name } }
        val why = if (ctorBacked) "constructor-backed: readable once literal defaults are copied (design §5 case 1)" else "computed by Compose on the host"
        out.appendLine("  @Deprecated(\"Keliver: `${name}.${prop.name}` is not readable in the guest: this value is a recorded call ($why).\", level = DeprecationLevel.ERROR)")
        out.appendLine("  public val ${prop.name}: Nothing get() = error(\"call-recorded\")")
      }
      val companion = producers.filter { it.ownerFq == "$fq.Companion" }
      if (companion.isNotEmpty() || abstract) {
        out.appendLine("  public companion object {")
        for (p in companion) out.append(producerFun(p, indent = "    "))
        out.appendLine("  }")
      }
      out.appendLine("}")
      if (abstract && producers.any { it.returns == fq }) {
        out.appendLine("@dev.keliver.spike.wire.InternalSpikeApi")
        out.appendLine("internal class ${name.replace(".", "")}Call(call: dev.keliver.spike.wire.CallRecord) : dev.keliver.spike.wire.CallValue(call), $name")
      }
      out.appendLine()
    }
    // Constructors and top-level factories: top-level functions named as Compose names them.
    for (p in producers.filter { it.ownerFq == null || it.name == "<init>" }) {
      file(if (p.ownerFq == null) p.id.substringBefore('(').substringBeforeLast('.') else packageOf(p.returns)).append(producerFun(p, indent = "")).appendLine()
    }

    val guestFiles = guest.mapKeys { (pkg, _) -> guestPkg(pkg).replace('.', '/') + "/GeneratedCalls.kt" }.mapValues { (path, body) ->
      header() + "@file:OptIn(dev.keliver.spike.wire.InternalSpikeApi::class)\n\npackage ${path.substringBeforeLast('/').replace('/', '.')}\n\n" + body.toString().trimEnd() + "\n"
    }

    // Host: one `when` over every producer.
    val host = StringBuilder(header())
    host.appendLine("package dev.keliver.spike.host")
    host.appendLine()
    host.appendLine("import kotlinx.serialization.json.JsonElement")
    host.appendLine("import kotlinx.serialization.json.JsonObject")
    host.appendLine("import kotlinx.serialization.json.boolean")
    host.appendLine("import kotlinx.serialization.json.float")
    host.appendLine("import kotlinx.serialization.json.int")
    host.appendLine("import kotlinx.serialization.json.jsonArray")
    host.appendLine("import kotlinx.serialization.json.jsonObject")
    host.appendLine("import kotlinx.serialization.json.jsonPrimitive")
    host.appendLine()
    host.appendLine("/** Makes the call a guest recorded (spike, call records): the same constructor, factory or constant. */")
    host.appendLine("public object GeneratedReplay {")
    host.appendLine("  public fun replay(json: JsonElement): Any {")
    host.appendLine("    val o = json.jsonObject")
    host.appendLine("    val a = o[\"args\"]?.jsonObject ?: JsonObject(emptyMap())")
    host.appendLine("    return when (val id = o.getValue(\"call\").jsonPrimitive.content) {")
    for (p in producers) {
      val target = when {
        p.ownerFq == null -> p.id.substringBefore('(')
        p.name == "<init>" -> p.returns
        else -> "${p.ownerFq.removeSuffix(".Companion")}.${p.name}"
      }
      if (p.isProperty) {
        host.appendLine("      \"${p.id}\" -> $target")
      } else {
        host.appendLine("      \"${p.id}\" -> " + hostCall(target, p.id, p.params, p.defaults, p.imports, packageOf(p.typeOwner() ?: p.returns), p.typeOwner(), "      "))
      }
    }
    host.appendLine("      else -> error(\"unknown call \$id\")")
    host.appendLine("    }")
    host.appendLine("  }")
    host.appendLine("}")
    return guestFiles to host.toString()
  }

  private fun producerFun(p: Producer, indent: String): String {
    val type = guestFq(p.returns)
    val simpleReturn = p.returns.removePrefix(packageOf(p.returns) + ".")
    val abstract = index.classes.getValue(p.returns).let { it.kind == ClassKind.INTERFACE || it.modality == Modality.ABSTRACT || it.modality == Modality.SEALED }
    val ctor = if (abstract) "${guestPkg(packageOf(p.returns))}.${simpleReturn.replace(".", "")}Call" else type
    if (p.isProperty) return "$indent/** `${p.id}`, recorded. */\n${indent}public val ${p.name}: $type get() = $ctor(dev.keliver.spike.wire.CallRecord(\"${p.id}\"))\n"
    val name = if (p.name == "<init>") simpleReturn.substringAfterLast('.') else p.name
    val (params, body) = guestParams(p.id, p.params)
    return buildString {
      append("$indent/** `${p.id}`, recorded: the host makes the same call. */\n")
      append("${indent}public fun $name(\n")
      params.forEach { append("$indent  $it,\n") }
      append("$indent): $type = $ctor(\n")
      append("$indent  dev.keliver.spike.wire.CallRecord(\n")
      append("$indent    \"${p.id}\",\n")
      append("$indent    kotlinx.serialization.json.buildJsonObject {\n")
      body.forEach { append("$indent      $it\n") }
      append("$indent    },\n")
      append("$indent  ),\n")
      append("$indent)\n")
    }
  }

  /** The guest's parameters (supported types only) and the statements that record them. */
  private fun guestParams(id: String, params: List<KmValueParameter>): Pair<List<String>, List<String>> {
    val decl = mutableListOf<String>()
    val body = mutableListOf<String>()
    for (p in params) {
      if (!supported(p.type)) {
        require(p.declaresDefaultValue) { "$id: required parameter ${p.name} has a type this spike does not carry" }
        omitted += "$id: ${p.name} (${fqOf(p.type)})"
        continue
      }
      if (p.declaresDefaultValue) {
        decl += "${p.name}: ${guestType(p.type, forceNullable = true)} = null"
        body += "if (${p.name} != null) put(\"${p.name}\", ${enc(p.type, p.name)})"
      } else {
        decl += "${p.name}: ${guestType(p.type)}"
        body += if (p.type.isNullable) {
          "put(\"${p.name}\", ${p.name}?.let { ${enc(p.type, "it")} } ?: kotlinx.serialization.json.JsonNull)"
        } else {
          "put(\"${p.name}\", ${enc(p.type, p.name)})"
        }
      }
    }
    return decl to body
  }

  /**
   * Whether a copied default compiles at the host's call site: not if it names a sibling parameter
   * (`size = this.size.offsetSize(topLeft)`) or a member of [ownerFq] callers cannot see.
   */
  private fun hostEvaluable(text: String, siblings: Set<String>, ownerFq: String?): Boolean {
    if (Regex("(?<![.\\w])([a-z][A-Za-z0-9_]*)\\b").findAll(text).any { it.value in siblings }) return false
    val owner = ownerFq?.let { index.classes[it] } ?: return true
    val hidden = owner.functions.filter { it.visibility != Visibility.PUBLIC }.map { it.name }.toSet() +
      owner.properties.filter { it.visibility != Visibility.PUBLIC }.map { it.name }
    return Regex("\\.([a-z][A-Za-z0-9_]*)").findAll(text).none { it.groupValues[1] in hidden }
  }

  /**
   * The host's call of [target]: each argument is the guest's value, else Compose's own default
   * expression. A default that does not compile at the host (design §5 case 3) is a presence
   * split: the argument is left out of the call text when the guest did not send it. At most two
   * per call (four call sites); a third is left out of the guest's API and reported.
   */
  private fun hostCall(target: String, id: String, params: List<KmValueParameter>, defaults: Map<String, String?>, imports: Map<String, String>, pkg: String, ownerFq: String?, indent: String): String {
    val carried = params.filter { supported(it.type) }
    val siblings = params.map { it.name }.toSet()
    val split = carried.filter { p -> p.declaresDefaultValue && !hostEvaluable(defaults[p.name] ?: error("$id: no default text for ${p.name}"), siblings, ownerFq) }
    require(split.size <= 2) { "$id: more than two defaults need a presence split: ${split.map { it.name }}" }
    fun call(present: Set<String>, ind: String): String = "$target(\n" + carried.filter { it !in split || it.name in present }.joinToString(",\n") { p ->
      val decoded = dec(p.type, "it")
      when {
        !p.declaresDefaultValue || p.name in present -> "$ind  ${p.name} = a.getValue(\"${p.name}\").let { $decoded }"
        else -> "$ind  ${p.name} = a[\"${p.name}\"]?.let { $decoded } ?: (${qualify(defaults.getValue(p.name)!!, imports, pkg, ownerFq)})"
      }
    } + "\n$ind)"
    fun branch(rest: List<KmValueParameter>, present: Set<String>, ind: String): String {
      if (rest.isEmpty()) return call(present, ind)
      val p = rest.first()
      return "if (\"${p.name}\" in a) ${branch(rest.drop(1), present + p.name, ind)} else ${branch(rest.drop(1), present, ind)}"
    }
    return branch(split, emptySet(), indent)
  }

  // ---------------------------------------------------------------- 2. remote objects

  /**
   * The guest proxy and host dispatcher of host-owned state class [fq]. Every public member is
   * classified from metadata: properties of supported types are observed, suspend functions are
   * awaited commands, Unit functions are commands, and the rest are error stubs.
   */
  fun remoteObject(fq: String): Pair<String, String> {
    val k = index.classes.getValue(fq)
    val name = fq.substringAfterLast('.')
    val chain = listOf(fq) + index.allSupertypes(fq).filter { it.startsWith("androidx.") }
    val g = StringBuilder(header())
    val h = StringBuilder(header())
    g.appendLine("package ${guestPkg(packageOf(fq))}")
    g.appendLine()
    g.appendLine("import kotlinx.serialization.json.boolean")
    g.appendLine("import kotlinx.serialization.json.float")
    g.appendLine("import kotlinx.serialization.json.int")
    g.appendLine("import kotlinx.serialization.json.jsonPrimitive")
    g.appendLine()
    g.appendLine("/** Guest proxy of the host's `$fq` (spike, remote objects): fields observed on read, members as commands. */")
    g.appendLine("public class $name(public val remote: dev.keliver.spike.wire.RemoteObject) {")
    h.appendLine("package dev.keliver.spike.host")
    h.appendLine()
    h.appendLine("import kotlinx.serialization.json.JsonElement")
    h.appendLine("import kotlinx.serialization.json.JsonNull")
    h.appendLine("import kotlinx.serialization.json.JsonObject")
    h.appendLine("import kotlinx.serialization.json.JsonPrimitive")
    h.appendLine("import kotlinx.serialization.json.float")
    h.appendLine("import kotlinx.serialization.json.int")
    h.appendLine("import kotlinx.serialization.json.jsonPrimitive")
    h.appendLine()
    h.appendLine("/** Host dispatcher for `$fq` (spike, remote objects). */")
    h.appendLine("public object ${name}Remote : RemoteDispatcher<$fq> {")
    val reads = mutableListOf<String>()
    val calls = mutableListOf<String>()
    val seen = mutableSetOf<String>()
    for (owner in chain) {
      val km = index.classes[owner] ?: continue
      for (p in km.properties.filter { it.visibility == Visibility.PUBLIC && it.receiverParameterType == null }) {
        if (!seen.add("p:" + p.name)) continue
        val t = p.returnType
        if (supported(t) && (fqOf(t) in primitives)) {
          val zero = when (fqOf(t)) { "kotlin.Int" -> "0"; "kotlin.Float" -> "0f"; "kotlin.Boolean" -> "false"; "kotlin.Long" -> "0L"; else -> "\"\"" }
          g.appendLine("  /** The host's `${p.name}`, as last reported (observed from the first read; at most once a frame). */")
          g.appendLine("  public val ${p.name}: ${guestType(t)} get() = remote.read(\"${p.name}\")?.jsonPrimitive?.${primitives.getValue(fqOf(t)!!)} ?: $zero")
          reads += "      \"${p.name}\" -> JsonPrimitive(state.${p.name})"
        } else {
          g.appendLine("  @Deprecated(\"Keliver: `$name.${p.name}` (${fqOf(t)?.substringAfterLast('.')}) does not cross to the guest.\", level = DeprecationLevel.ERROR)")
          g.appendLine("  public val ${p.name}: Nothing get() = error(\"not remote\")")
        }
      }
      for (f in km.functions.filter { it.visibility == Visibility.PUBLIC && it.receiverParameterType == null }) {
        val key = f.name + "(" + f.valueParameters.joinToString(",") { it.name } + ")"
        if (!seen.add("f:$key")) continue
        val unit = fqOf(f.returnType) == "kotlin.Unit"
        val requiredOk = f.valueParameters.all { supported(it.type) || it.declaresDefaultValue }
        if (!requiredOk || !(f.isSuspend || unit) || (f.isSuspend && !unit)) {
          g.appendLine("  @Deprecated(\"Keliver: `$name.$key` does not cross the bridge (${if (!requiredOk) "an argument type" else "a synchronous result"}).\", level = DeprecationLevel.ERROR)")
          g.appendLine("  public fun ${f.name}(vararg args: Any?): Nothing = error(\"not remote\")")
          continue
        }
        val (params, body) = guestParams("$fq#$key", f.valueParameters)
        val (d, imp) = src.defaultsOf(packageOf(owner), owner.removePrefix(packageOf(owner) + "."), f.name, f.valueParameters.map { it.name }, f.valueParameters.map { it.type.toRef() }) ?: (emptyMap<String, String?>() to emptyMap())
        g.appendLine("  /** `$key` on the host's state; ${if (f.isSuspend) "returns when the host's call returns" else "sent, not awaited"}. */")
        g.appendLine("  public ${if (f.isSuspend) "suspend " else ""}fun ${f.name}(${params.joinToString()}) {")
        g.appendLine("    val args = kotlinx.serialization.json.buildJsonObject {")
        body.forEach { g.appendLine("      $it") }
        g.appendLine("    }")
        g.appendLine(if (f.isSuspend) "    remote.call(\"$key\", args)" else "    remote.send(\"$key\", args)")
        g.appendLine("  }")
        calls += "      \"$key\" -> {\n        " + hostCall("state.${f.name}", key, f.valueParameters, d, imp, packageOf(owner), owner, "        ") + "\n        JsonNull\n      }"
      }
    }
    g.appendLine("}")
    h.appendLine("  override fun read(state: $fq, name: String): JsonElement = when (name) {")
    reads.forEach { h.appendLine(it) }
    h.appendLine("    else -> error(\"unknown field \$name\")")
    h.appendLine("  }")
    h.appendLine()
    h.appendLine("  override suspend fun invoke(state: $fq, member: String, a: JsonObject): JsonElement = when (member) {")
    calls.forEach { h.appendLine(it) }
    h.appendLine("    else -> error(\"unknown member \$member\")")
    h.appendLine("  }")
    h.appendLine("}")
    if (k.visibility != Visibility.PUBLIC) error("$fq is not public")
    return g.toString() to h.toString()
  }

  // ---------------------------------------------------------------- 3. recorded interfaces

  /**
   * A guest mirror of scope interface [fq] with a recording implementation, and the host's replay
   * onto the real scope. Unit members with supported arguments record; reads of the host's
   * values ([hostValues], e.g. `size`) come from the host; everything else is an error stub.
   */
  fun recordedInterface(fq: String, hostValues: Set<String>): Pair<String, String> {
    val name = fq.substringAfterLast('.')
    val chain = listOf(fq) + index.allSupertypes(fq).filter { it.startsWith("androidx.") }
    val iface = StringBuilder()
    val rec = StringBuilder()
    val replay = mutableListOf<String>()
    val seen = mutableSetOf<String>()
    for (owner in chain) {
      val km = index.classes[owner] ?: continue
      for (p in km.properties.filter { it.visibility == Visibility.PUBLIC && it.receiverParameterType == null }) {
        if (!seen.add("p:" + p.name)) continue
        if (p.name in hostValues && supported(p.returnType)) {
          iface.appendLine("  /** The host's `${p.name}`, as of its last layout (one round trip after a change). */")
          iface.appendLine("  public val ${p.name}: ${guestType(p.returnType)}")
          rec.appendLine("  override val ${p.name}: ${guestType(p.returnType)} get() = host${p.name.replaceFirstChar { it.uppercase() }}")
        } else {
          iface.appendLine("  @Deprecated(\"Keliver: `$name.${p.name}` is the host's, read at draw time; it does not reach the guest's recording.\", level = DeprecationLevel.ERROR)")
          iface.appendLine("  public val ${p.name}: Nothing get() = error(\"not recorded\")")
        }
      }
      for (f in km.functions.filter { it.visibility == Visibility.PUBLIC && it.receiverParameterType == null }) {
        val key = f.name + "(" + f.valueParameters.joinToString(",") { it.name } + ")"
        if (!seen.add("f:$key")) continue
        val unit = fqOf(f.returnType) == "kotlin.Unit"
        if (!unit || !f.valueParameters.all { supported(it.type) || it.declaresDefaultValue }) {
          omitted += "$fq#$key: ${if (!unit) "returns a value" else "an argument type"}"
          continue
        }
        val (params, body) = guestParams("$fq#$key", f.valueParameters)
        val (d, imp) = src.defaultsOf(packageOf(owner), owner.removePrefix(packageOf(owner) + "."), f.name, f.valueParameters.map { it.name }, f.valueParameters.map { it.type.toRef() })
          ?: error("$key: not found in the sources")
        iface.appendLine("  /** `$key`, recorded and replayed on the host's real scope. */")
        iface.appendLine("  public fun ${f.name}(${params.joinToString()})")
        rec.appendLine("  override fun ${f.name}(${params.joinToString { it.substringBefore(" = ") }}) {")
        rec.appendLine("    commands += kotlinx.serialization.json.buildJsonObject {")
        rec.appendLine("      put(\"m\", kotlinx.serialization.json.JsonPrimitive(\"$key\"))")
        rec.appendLine("      put(\"a\", kotlinx.serialization.json.buildJsonObject {")
        body.forEach { rec.appendLine("        $it") }
        rec.appendLine("      })")
        rec.appendLine("    }")
        rec.appendLine("  }")
        replay += "      \"$key\" -> " + hostCall(f.name, key, f.valueParameters, d, imp, packageOf(owner), owner, "      ")
      }
    }
    val g = StringBuilder(header())
    g.appendLine("package ${guestPkg(packageOf(fq))}")
    g.appendLine()
    g.appendLine("/** Guest mirror of `$fq` (spike, recorded interfaces). */")
    g.appendLine("public interface $name {")
    g.append(iface)
    g.appendLine("}")
    g.appendLine()
    g.appendLine("/** Records every call into [commands]; the host replays them onto its real `$name`. */")
    g.appendLine("public class Recording$name(")
    hostValues.forEach { v ->
      val t = chain.firstNotNullOf { o -> index.classes[o]?.properties?.firstOrNull { it.name == v } }.returnType
      g.appendLine("  private val host${v.replaceFirstChar { it.uppercase() }}: ${guestType(t)},")
    }
    g.appendLine(") : $name {")
    g.appendLine("  public val commands: MutableList<kotlinx.serialization.json.JsonElement> = mutableListOf()")
    g.append(rec)
    g.appendLine("}")

    val h = StringBuilder(header())
    h.appendLine("package dev.keliver.spike.host")
    h.appendLine()
    h.appendLine("import kotlinx.serialization.json.JsonArray")
    h.appendLine("import kotlinx.serialization.json.JsonObject")
    h.appendLine("import kotlinx.serialization.json.boolean")
    h.appendLine("import kotlinx.serialization.json.float")
    h.appendLine("import kotlinx.serialization.json.int")
    h.appendLine("import kotlinx.serialization.json.jsonArray")
    h.appendLine("import kotlinx.serialization.json.jsonObject")
    h.appendLine("import kotlinx.serialization.json.jsonPrimitive")
    h.appendLine()
    h.appendLine("/** Replays a guest's recorded `$name` calls on the real scope, in order (spike, recorded interfaces). */")
    h.appendLine("public fun $fq.replay(commands: JsonArray) {")
    h.appendLine("  for (c in commands) {")
    h.appendLine("    val a = c.jsonObject[\"a\"]?.jsonObject ?: JsonObject(emptyMap())")
    h.appendLine("    when (val m = c.jsonObject.getValue(\"m\").jsonPrimitive.content) {")
    replay.forEach { h.appendLine(it) }
    h.appendLine("      else -> error(\"unknown command \$m\")")
    h.appendLine("    }")
    h.appendLine("  }")
    h.appendLine("}")
    return g.toString() to h.toString()
  }

  private fun header() = """
    |// Code generated by keliver-compose-generator's spike (docs/COMPOSE_GENERIC_MECHANISMS.md). DO NOT EDIT.
    |// Regenerate: ./gradlew :keliver-compose-generator:generateGenericSpike
    |@file:Suppress("ktlint", "RedundantVisibilityModifier", "UNUSED_PARAMETER", "DEPRECATION_ERROR")
    |
  """.trimMargin()
}

/** The spike's configuration: what part b proves. */
object GenericSpike {
  val calls = listOf(
    "androidx.compose.ui.text.TextStyle#<init>(color,fontSize,fontWeight,fontStyle,fontSynthesis,fontFamily,fontFeatureSettings,letterSpacing,baselineShift,textGeometricTransform,localeList,background,textDecoration,shadow,drawStyle,textAlign,textDirection,lineHeight,textIndent,platformStyle,lineHeightStyle,lineBreak,hyphens,textMotion)",
    "androidx.compose.ui.text.font.FontWeight#<init>(weight)",
    "androidx.compose.ui.text.font.FontWeight.Companion#Bold",
    "androidx.compose.ui.text.style.TextAlign.Companion#Center",
    "androidx.compose.ui.graphics.Brush.Companion#linearGradient(colors,start,end,tileMode)",
    "androidx.compose.ui.graphics.TileMode.Companion#Mirror",
    "androidx.compose.foundation.shape.RoundedCornerShape(size:Dp)",
    "androidx.compose.ui.graphics.StrokeCap.Companion#Round",
    "androidx.compose.ui.graphics.drawscope.Stroke#<init>(width,miter,cap,join,pathEffect)",
  )
  const val REMOTE = "androidx.compose.foundation.pager.PagerState"
  const val SCOPE = "androidx.compose.ui.graphics.drawscope.DrawScope"

  /** Generated sources: guest and host files by path relative to the spike module's `src/main/kotlin`. */
  fun generate(classpath: List<File>, sources: List<File>): Pair<Map<String, String>, List<String>> {
    val e = GenericEmitter(GenericIndex(classpath), SourceReader(sources))
    val (guestCalls, hostReplay) = e.callRecords(calls)
    val (guestRemote, hostRemote) = e.remoteObject(REMOTE)
    val (guestScope, hostScope) = e.recordedInterface(SCOPE, hostValues = setOf("size"))
    val out = linkedMapOf<String, String>()
    out += guestCalls
    out[e.guestPkg(packageOf(REMOTE)).replace('.', '/') + "/GeneratedRemote.kt"] = guestRemote
    out[e.guestPkg(packageOf(SCOPE)).replace('.', '/') + "/GeneratedRecording.kt"] = guestScope
    out["dev/keliver/spike/host/GeneratedReplay.kt"] = hostReplay
    out["dev/keliver/spike/host/GeneratedRemoteHost.kt"] = hostRemote
    out["dev/keliver/spike/host/GeneratedDrawReplay.kt"] = hostScope
    return out to e.omitted
  }
}

/** `generateGenericSpike`: writes (or, with `--check`, compares) the spike module's generated sources. */
object GenericSpikeMain {
  @JvmStatic fun main(argv: Array<String>) = genericSpikeMain(argv)
}

fun genericSpikeMain(argv: Array<String>) {
  val args = argv.toList()
  fun arg(name: String) = args[args.indexOf(name) + 1]
  val (files, omitted) = GenericSpike.generate(arg("--classpath").split(File.pathSeparator).map(::File), arg("--sources").split(File.pathSeparator).map(::File))
  val root = File(arg("--out"))
  val stale = files.filter { (path, text) -> File(root, path).let { !it.exists() || it.readText() != text } }.keys
  if ("--check" in args) {
    if (stale.isNotEmpty()) error("generateGenericSpike: out of date: $stale")
    return
  }
  for ((path, text) in files) File(root, path).apply { parentFile.mkdirs() }.writeText(text)
  println("generic spike: ${files.size} files; left out (types this spike does not carry):\n  " + omitted.joinToString("\n  "))
}
