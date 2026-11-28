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

import kotlin.metadata.KmClass
import kotlin.metadata.KmClassifier
import kotlin.metadata.KmFunction
import kotlin.metadata.KmType
import kotlin.metadata.KmValueParameter
import kotlin.metadata.Visibility
import kotlin.metadata.declaresDefaultValue
import kotlin.metadata.isNullable
import kotlin.metadata.isSuspend
import kotlin.metadata.jvm.signature
import kotlin.metadata.visibility

/**
 * MS4-43 (G2): remote objects, docs/COMPOSE_GENERIC_MECHANISMS.md section 1.2. A host-owned state
 * class (`PagerState`) gets, from its metadata and sources alone:
 *
 * - **a guest proxy** (the guest module, `dev.keliver.compose.<package>`, `GeneratedRemote.kt`): the
 *   class itself, whose fields are read from the host's last report (a field is *observed* from its
 *   first read), whose commands are sent to the host (a `suspend` one returns when the host's call
 *   returned, with its result; a plain `Unit` one is not awaited), and the class's `remember*`
 *   factory, which creates the proxy;
 * - **a host dispatcher** (the host module, `GeneratedRemote.kt`): reads a field and runs a command
 *   on the real state by tag, the host's own `remember*` factory call (so the real state restores
 *   itself from the host's saved state: the `Saver` is Compose's, and the process-death restore is
 *   `rememberSaveable`'s), and one registry over every remote object of the group;
 * - **the wire shape in the group's lock** (`remotes`): the class's tag (`RemoteObjectWire.type`)
 *   and each member's tag and starting level, append-only as widgets are. A member that cannot cross
 *   has no tag.
 *
 * What does not cross is an error stub with a message saying why: members that return a value
 * synchronously, take a lambda, or have a type that does not cross (this PR: Kotlin primitives and
 * `String`; the call records of G1 widen it).
 *
 * The emitter does not touch the widget emitter (`Emit.kt`). The binding to a widget is the
 * runtime's: a widget property of type `RemoteObjectWire`, an event of type `RemoteReport`.
 */
class RemoteObjectEmitter(private val index: GenericIndex, private val src: SourceReader, private val config: Config, private val lock: LockBuilder) {
  /** A class's members, as classified. */
  class Member(val key: String, val kind: Kind, val name: String, val detail: String) {
    enum class Kind { OBSERVED, AWAITED, UNAWAITED, STUB }
  }

  class ClassReport(val fq: String, val tag: Int, val members: List<Member>, val factory: String) {
    val observed get() = members.filter { it.kind == Member.Kind.OBSERVED }
    val awaited get() = members.filter { it.kind == Member.Kind.AWAITED }
    val unawaited get() = members.filter { it.kind == Member.Kind.UNAWAITED }
    val stubs get() = members.filter { it.kind == Member.Kind.STUB }
  }

  class Result(val guest: Map<String, String>, val host: Map<String, String>, val classes: List<ClassReport>)

  private class Prim(val accessor: String, val zero: String, val read: String)

  private val prims = mapOf(
    "kotlin.Int" to Prim("int", "0", "intOrNull"),
    "kotlin.Float" to Prim("float", "0f", "floatOrNull"),
    "kotlin.Long" to Prim("long", "0L", "longOrNull"),
    "kotlin.Double" to Prim("double", "0.0", "doubleOrNull"),
    "kotlin.Boolean" to Prim("boolean", "false", "booleanOrNull"),
    "kotlin.String" to Prim("content", "\"\"", "contentOrNull"),
  )

  private fun fqOf(t: KmType): String? = (t.classifier as? KmClassifier.Class)?.name?.replace('/', '.')

  private fun crosses(t: KmType): Boolean = fqOf(t) in prims && t.arguments.isEmpty()

  /** A type's text in a member's canonical signature. */
  private fun typeKey(t: KmType): String {
    val base = when (val c = t.classifier) {
      is KmClassifier.Class -> c.name.replace('/', '.')
      is KmClassifier.TypeAlias -> c.name.replace('/', '.')
      is KmClassifier.TypeParameter -> "T"
    }
    val args = if (t.arguments.isEmpty()) "" else t.arguments.joinToString(",", "<", ">") { a -> a.type?.let(::typeKey) ?: "*" }
    return base + args + (if (t.isNullable) "?" else "")
  }

  private fun simple(fq: String) = fq.substringAfterLast('.')

  /** The guest's package of a Compose package (`androidx.compose.foundation.pager` → `dev.keliver.compose.foundation.pager`). */
  private fun guestPackage(pkg: String) = "dev.keliver.compose." + pkg.removePrefix("androidx.compose.")

  private fun isComposable(f: KmFunction) = f.signature?.descriptor?.contains("Landroidx/compose/runtime/Composer;") == true

  /** The member text of the wire key: `name(p:Type,…)` for a command. */
  private fun commandKey(f: KmFunction) = f.name + "(" + f.valueParameters.joinToString(",") { it.name + ":" + typeKey(it.type) } + ")"

  private val literal = Regex("""-?\d+(\.\d+)?[fFL]?|true|false|null|"[^"\\]*"""")

  /** The state's source-declared default of [p], when it is a literal the guest can copy. */
  private class Params(val sent: List<KmValueParameter>, val defaults: Map<String, String>, val dropped: List<String>, val unsupported: String?)

  private fun paramsOf(pkg: String, owner: String?, name: String, ps: List<KmValueParameter>, allowSupplier: Boolean): Params {
    val found = src.defaultsOf(pkg, owner, name, ps.map { it.name }, ps.map { it.type.toRef() })
    val defaults = found?.first.orEmpty()
    val sent = mutableListOf<KmValueParameter>()
    val copied = linkedMapOf<String, String>()
    val dropped = mutableListOf<String>()
    for (p in ps) {
      val text = defaults[p.name]
      val crossing = crosses(p.type) || (allowSupplier && isSupplier(p.type))
      when {
        crossing && (text == null || literal.matches(text)) -> {
          sent += p
          if (text != null) copied[p.name] = text
        }
        p.declaresDefaultValue -> dropped += p.name
        else -> {
          val why = if (fqOf(p.type)?.startsWith("kotlin.Function") == true) "a lambda argument (`${p.name}`)" else "an argument type (`${p.name}`: ${simple(typeKey(p.type))})"
          return Params(emptyList(), emptyMap(), emptyList(), why)
        }
      }
    }
    return Params(sent, copied, dropped, null)
  }

  /** `() -> T` for a crossing T: the guest samples it (the factory's `pageCount`). */
  private fun isSupplier(t: KmType): Boolean =
    fqOf(t) == "kotlin.Function0" && t.arguments.singleOrNull()?.type?.let(::crosses) == true

  private fun guestTypeOf(t: KmType): String = fqOf(t)!! + if (t.isNullable) "?" else ""

  private fun encodeGuest(t: KmType, v: String): String =
    if (t.isNullable) "if ($v == null) kotlinx.serialization.json.JsonNull else kotlinx.serialization.json.JsonPrimitive($v)" else "kotlinx.serialization.json.JsonPrimitive($v)"

  private fun decodeGuest(t: KmType, j: String): String {
    val p = prims.getValue(fqOf(t)!!)
    val read = "$j?.jsonPrimitive?.${p.read}"
    return if (t.isNullable) read else "$read ?: ${p.zero}"
  }

  private fun decodeHost(t: KmType, j: String): String {
    val p = prims.getValue(fqOf(t)!!)
    return if (t.isNullable) "$j.takeUnless { it is kotlinx.serialization.json.JsonNull }?.jsonPrimitive?.${p.read}" else "$j.jsonPrimitive.${p.accessor}"
  }

  private fun encodeHost(t: KmType, v: String): String =
    if (t.isNullable) "$v?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull" else "kotlinx.serialization.json.JsonPrimitive($v)"

  /** The factory a remote object is created by: its `remember*` composable function. */
  private fun factoryOf(fq: String, name: String?): KmFunction {
    val pkg = packageOf(fq)
    val fname = name ?: "remember${simple(fq)}"
    val all = index.packageFunctions[pkg].orEmpty().filter { it.fn.name == fname && isComposable(it.fn) && !index.isHidden(it.facade, it.fn) }
    val usable = all.filter { pf -> paramsOf(pkg, null, fname, pf.fn.valueParameters, true).unsupported == null }
    require(usable.isNotEmpty()) {
      "$fq: no composable `$fname` in $pkg whose parameters cross (found ${all.size}). Name the factory in ${config.group}.json: `$fq#<function>`"
    }
    // Several overloads: the one that sends the most parameters, then the one with the most (a
    // deprecated older one has fewer); a tie is the config author's to settle.
    fun sent(pf: GenericIndex.PackageFunction) = paramsOf(pkg, null, fname, pf.fn.valueParameters, true).sent.size
    val best = usable.sortedWith(compareByDescending<GenericIndex.PackageFunction> { sent(it) }.thenByDescending { it.fn.valueParameters.size })
    require(best.size == 1 || sent(best[0]) != sent(best[1]) || best[0].fn.valueParameters.size != best[1].fn.valueParameters.size) {
      "$fq: several `$fname` overloads cross equally: ${best.map { pf -> pf.fn.valueParameters.joinToString(",") { it.name } }}. Say which in ${config.group}.json"
    }
    return best.first().fn
  }

  /** [fq]'s public members and its Compose supertypes', classified (what [generate] would write, without a factory). */
  fun membersOf(fq: String): List<Member> = classify(fq)

  /** Classifies the public members of [fq] and its Compose supertypes. */
  private fun classify(fq: String): List<Member> {
    val chain = listOf(fq) + index.allSupertypes(fq).filter { it.startsWith("androidx.") }
    val out = mutableListOf<Member>()
    val seen = mutableSetOf<String>()
    val sn = simple(fq)
    for (owner in chain) {
      val k = index.classes[owner] ?: continue
      val jvm = index.jvmName(owner)
      for (p in k.properties.filter { it.visibility == Visibility.PUBLIC && it.receiverParameterType == null }) {
        if (!seen.add("p:" + p.name)) continue
        out += if (crosses(p.returnType)) {
          Member(p.name, Member.Kind.OBSERVED, p.name, "${p.name}: ${typeKey(p.returnType)}")
        } else {
          Member(p.name, Member.Kind.STUB, p.name, "`$sn.${p.name}` (${simple(typeKey(p.returnType))}) is a type that does not cross to the guest")
        }
      }
      for (f in k.functions.filter { it.visibility == Visibility.PUBLIC && it.receiverParameterType == null && !index.isHidden(jvm, it) }) {
        val key = commandKey(f)
        if (!seen.add("f:$key")) continue
        val unit = fqOf(f.returnType) == "kotlin.Unit"
        val ps = paramsOf(packageOf(owner), owner.removePrefix(packageOf(owner) + "."), f.name, f.valueParameters, false)
        val name = f.name
        out += when {
          ps.unsupported != null -> Member(key, Member.Kind.STUB, name, "`$sn.$name` takes ${ps.unsupported}, which does not cross to the guest")
          !f.isSuspend && !unit -> Member(key, Member.Kind.STUB, name, "`$sn.$name` returns a value synchronously: the host's answer cannot be waited for in composition")
          f.isSuspend && !unit && !crosses(f.returnType) -> Member(key, Member.Kind.STUB, name, "`$sn.$name` returns ${simple(typeKey(f.returnType))}, a type that does not cross to the guest")
          f.isSuspend -> Member(key, Member.Kind.AWAITED, name, key)
          else -> Member(key, Member.Kind.UNAWAITED, name, key)
        }
      }
    }
    return out
  }

  /** The functions behind [classify]'s command members, by key. */
  private fun commandsOf(fq: String): Map<String, Pair<String, KmFunction>> {
    val chain = listOf(fq) + index.allSupertypes(fq).filter { it.startsWith("androidx.") }
    val out = linkedMapOf<String, Pair<String, KmFunction>>()
    for (owner in chain) {
      val k = index.classes[owner] ?: continue
      for (f in k.functions.filter { it.visibility == Visibility.PUBLIC && it.receiverParameterType == null }) out.putIfAbsent(commandKey(f), owner to f)
    }
    return out
  }

  private fun propertyType(fq: String, name: String): KmType {
    val chain = listOf(fq) + index.allSupertypes(fq).filter { it.startsWith("androidx.") }
    for (owner in chain) index.classes[owner]?.properties?.firstOrNull { it.name == name }?.let { return it.returnType }
    error("$fq.$name")
  }

  /** `<specs>`: each `fq` or `fq#factoryFunction`. */
  fun generate(specs: List<String>): Result {
    val guest = linkedMapOf<String, StringBuilder>()
    val host = StringBuilder()
    val classes = mutableListOf<ClassReport>()
    val hostBodies = mutableListOf<String>()
    val registry = mutableListOf<String>()
    val simpleNames = mutableSetOf<String>()
    for (spec in specs) {
      val head = spec.substringBefore('|')
      val fq = head.substringBefore('#')
      val factoryName = head.substringAfter('#', "").ifEmpty { null }
      // `|initialPage=currentPage,…`: the factory arguments the saved state restores, each from the field that holds it.
      val restore = spec.substringAfter('|', "").split(',').filter { it.isNotEmpty() }.associate { it.substringBefore('=') to it.substringAfter('=') }
      val k: KmClass = index.classes[fq] ?: error("$spec: $fq is not on the classpath")
      require(k.visibility == Visibility.PUBLIC) { "$fq is not public" }
      require(simpleNames.add(simple(fq))) { "two remote objects named ${simple(fq)}: the host's dispatcher names are their simple names" }
      val members = classify(fq)
      val crossing = members.filter { it.kind != Member.Kind.STUB }
      val entry = lock.remote(fq, crossing.map { it.key })
      val tag = { m: Member -> entry.params.getValue(m.key) }
      val factory = factoryOf(fq, factoryName)
      classes += ClassReport(fq, entry.tag, members, factory.name)

      val pkg = packageOf(fq)
      val sn = simple(fq)
      val commands = commandsOf(fq)
      val g = guest.getOrPut(guestPackage(pkg)) { StringBuilder() }
      guestClass(g, fq, sn, entry.tag, members, tag)
      val restoreTags = restore.mapValues { (param, field) ->
        val m = members.firstOrNull { it.kind == Member.Kind.OBSERVED && it.name == field } ?: error("$spec: restore `$param=$field`: $sn has no observed field `$field`")
        val p = factory.valueParameters.firstOrNull { it.name == param && crosses(it.type) } ?: error("$spec: restore `$param=$field`: ${factory.name} has no crossing parameter `$param`")
        require(typeKey(p.type) == typeKey(propertyType(fq, field))) { "$spec: restore `$param=$field`: the types differ (${typeKey(p.type)} and ${typeKey(propertyType(fq, field))})" }
        tag(m)
      }
      guestFactory(g, fq, sn, entry.tag, factory, pkg, restoreTags)
      hostBodies += hostDispatcher(fq, sn, members, tag, commands)
      registry += hostFactory(fq, sn, entry.tag, factory, pkg)
    }
    val guestFiles = guest.entries.associate { (pkg, body) ->
      (pkg.replace('.', '/') + "/GeneratedRemote.kt") to (guestHeader() + "package $pkg\n\n" + guestImports + "\n" + body)
    }
    val hostPkg = config.schemaPackage + ".composeui"
    host.append(hostHeader()).append("package $hostPkg\n\n").append(hostImports).append("\n")
    hostBodies.forEach { host.append(it).append("\n") }
    host.append("/** The group's remote objects by class tag (`RemoteObjectWire.type`): creates the real state and runs the guest's commands on it. */\n")
    host.append("internal object GeneratedRemoteObjects {\n")
    host.append("  /** The real state for [wire], or null for a class this host does not know (a newer guest). */\n")
    host.append("  @Composable\n  fun host(wire: RemoteObjectWire, report: (RemoteReport) -> Unit): Any? = when (wire.type) {\n")
    registry.forEach { host.append(it) }
    host.append("    else -> null\n  }\n}\n")
    val hostFiles = if (specs.isEmpty()) emptyMap() else mapOf(hostPkg.replace('.', '/') + "/GeneratedRemote.kt" to host.toString())
    return Result(if (specs.isEmpty()) emptyMap() else guestFiles, hostFiles, classes)
  }

  private fun guestHeader() = header(config.group) +
    "@file:Suppress(\"ktlint\", \"RedundantVisibilityModifier\", \"UNUSED_PARAMETER\", \"DEPRECATION_ERROR\")\n"

  private val guestImports = listOf(
    "androidx.compose.runtime.Composable",
    "androidx.compose.runtime.Stable",
    "androidx.compose.runtime.getValue",
    "androidx.compose.runtime.remember",
    "androidx.compose.runtime.rememberUpdatedState",
    "androidx.compose.runtime.saveable.rememberSaveable",
    "dev.keliver.compose.remote.RemoteObject",
    "dev.keliver.compose.remote.RemoteObjectWire",
    "dev.keliver.compose.remote.RemoteReport",
    "dev.keliver.compose.remote.RemoteRestore",
    "kotlinx.serialization.json.booleanOrNull",
    "kotlinx.serialization.json.contentOrNull",
    "kotlinx.serialization.json.doubleOrNull",
    "kotlinx.serialization.json.floatOrNull",
    "kotlinx.serialization.json.intOrNull",
    "kotlinx.serialization.json.jsonPrimitive",
    "kotlinx.serialization.json.longOrNull",
  ).joinToString("") { "import $it\n" }

  private fun guestClass(g: StringBuilder, fq: String, sn: String, typeTag: Int, members: List<Member>, tag: (Member) -> Int) {
    g.append("/**\n * The guest's proxy of the host's `$fq`: a field is read from the host's last report (and observed from its\n * first read), a command is sent to the host. Created by the factory below. Class tag $typeTag.\n */\n")
    g.append("@Stable\npublic class $sn(public val remote: RemoteObject) {\n")
    g.append("  /** The property the state's widget sends. */\n  public val wire: RemoteObjectWire get() = remote.wire\n\n")
    g.append("  /** The widget's event: the host's report. */\n  public fun onHostReport(report: RemoteReport) {\n    remote.onHostReport(report)\n  }\n")
    val commands = commandsOf(fq)
    val stubNames = mutableSetOf<String>()
    for (m in members) {
      g.append("\n")
      when (m.kind) {
        Member.Kind.OBSERVED -> {
          val t = propertyType(fq, m.name)
          g.append("  /** The host's `${m.name}`, as last reported (observed from the first read; at most once a frame). */\n")
          g.append("  public val ${m.name}: ${guestTypeOf(t)} get() = ${decodeGuest(t, "remote.read(${tag(m)})")}\n")
        }
        Member.Kind.AWAITED, Member.Kind.UNAWAITED -> {
          val (owner, f) = commands.getValue(m.key)
          val ps = paramsOf(packageOf(owner), owner.removePrefix(packageOf(owner) + "."), f.name, f.valueParameters, false)
          val params = ps.sent.joinToString(", ") { p -> "${p.name}: ${guestTypeOf(p.type)}" + (ps.defaults[p.name]?.let { " = $it" } ?: "") }
          val awaited = m.kind == Member.Kind.AWAITED
          val result = if (awaited && fqOf(f.returnType) != "kotlin.Unit") f.returnType else null
          val body = ps.sent.joinToString("\n") { p -> "      put(\"${p.name}\", ${encodeGuest(p.type, p.name)})" }
          g.append("  /** `${m.key}` on the host's state; ${if (awaited) "returns when the host's call returns" else "sent, not awaited"}.${if (ps.dropped.isNotEmpty()) " Left to Compose's defaults: ${ps.dropped.joinToString()}." else ""} */\n")
          g.append("  public ${if (awaited) "suspend " else ""}fun ${f.name}($params)${result?.let { ": ${guestTypeOf(it)}" } ?: ""} ")
          val send = "remote.${if (awaited) "call" else "send"}(${tag(m)}, kotlinx.serialization.json.buildJsonObject {\n$body\n    })"
          if (result != null) g.append("= ${decodeGuest(result, "($send)")}\n") else g.append("{\n    $send\n  }\n")
        }
        Member.Kind.STUB -> if (stubNames.add(m.name)) {
          val isProperty = m.key == m.name
          g.append("  @Deprecated(\"Keliver: ${m.detail}.\", level = DeprecationLevel.ERROR)\n")
          g.append(if (isProperty) "  public val ${m.name}: Nothing get() = error(\"not remote\")\n" else "  public fun ${m.name}(vararg args: Any?): Nothing = error(\"not remote\")\n")
        }
      }
    }
    g.append("}\n\n")
  }

  private fun guestFactory(g: StringBuilder, fq: String, sn: String, typeTag: Int, f: KmFunction, pkg: String, restore: Map<String, Int>) {
    val ps = paramsOf(pkg, null, f.name, f.valueParameters, true)
    val params = ps.sent.joinToString(", ") { p ->
      val t = if (isSupplier(p.type)) "() -> ${guestTypeOf(p.type.arguments.single().type!!)}" else guestTypeOf(p.type)
      "${p.name}: $t" + (ps.defaults[p.name]?.let { " = $it" } ?: "")
    }
    g.append("/**\n * Creates the proxy of a host `$sn`, made on the host by `${f.name}` with these arguments. A lambda is sampled: its value is sent\n * whenever it changes.")
    if (restore.isNotEmpty()) {
      g.append("\n * Process death: the host's state dies with the process, so this saves (as the guest's own saved state) the last values the host\n * reported of ${restore.keys.joinToString { "`$it`" }}'s fields, and passes them back as those arguments after a restore (so the fields are always observed).")
    }
    g.append("\n */\n@Composable\npublic fun ${f.name}($params): $sn {\n")
    for (p in ps.sent.filter { isSupplier(it.type) }) g.append("  val ${p.name}Latest by rememberUpdatedState(${p.name})\n")
    if (restore.isNotEmpty()) g.append("  val restore = rememberSaveable(saver = RemoteRestore.Saver) { RemoteRestore() }\n")
    g.append("  val remote = remember {\n    RemoteObject(RemoteObject.nextId(), $typeTag) {\n      kotlinx.serialization.json.buildJsonObject {\n")
    for (p in ps.sent) {
      val v = if (isSupplier(p.type)) "${p.name}Latest()" else p.name
      val t = if (isSupplier(p.type)) p.type.arguments.single().type!! else p.type
      val value = encodeGuest(t, v)
      g.append(if (p.name in restore) "        put(\"${p.name}\", restore.saved?.get(\"${p.name}\") ?: $value)\n" else "        put(\"${p.name}\", $value)\n")
    }
    g.append("      }\n    }")
    if (restore.isNotEmpty()) {
      g.append(".also { r ->\n")
      for (tag in restore.values) g.append("      r.read($tag)\n")
      g.append("      restore.live = {\n        kotlinx.serialization.json.buildJsonObject {\n")
      for ((p, tag) in restore) g.append("          r.read($tag)?.let { put(\"$p\", it) }\n")
      g.append("        }\n      }\n    }")
    }
    g.append("\n  }\n  return remember(remote) { $sn(remote) }\n}\n\n")
  }

  private val hostImports = listOf(
    "androidx.compose.runtime.Composable",
    "androidx.compose.runtime.getValue",
    "androidx.compose.runtime.rememberUpdatedState",
    "dev.keliver.compose.remote.RemoteObjectWire",
    "dev.keliver.compose.remote.RemoteReport",
    "kotlinx.serialization.json.JsonElement",
    "kotlinx.serialization.json.JsonNull",
    "kotlinx.serialization.json.JsonObject",
    "kotlinx.serialization.json.JsonPrimitive",
    "kotlinx.serialization.json.booleanOrNull",
    "kotlinx.serialization.json.boolean",
    "kotlinx.serialization.json.contentOrNull",
    "kotlinx.serialization.json.double",
    "kotlinx.serialization.json.doubleOrNull",
    "kotlinx.serialization.json.float",
    "kotlinx.serialization.json.floatOrNull",
    "kotlinx.serialization.json.int",
    "kotlinx.serialization.json.intOrNull",
    "kotlinx.serialization.json.jsonPrimitive",
    "kotlinx.serialization.json.long",
    "kotlinx.serialization.json.longOrNull",
  ).joinToString("") { "import $it\n" }

  private fun hostHeader() = header(config.group) +
    "@file:Suppress(\"ktlint\", \"RedundantVisibilityModifier\", \"UNUSED_PARAMETER\", \"DEPRECATION\", \"UNCHECKED_CAST\")\n" +
    "@file:OptIn(dev.keliver.RedwoodCodegenApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)\n"

  private fun hostDispatcher(fq: String, sn: String, members: List<Member>, tag: (Member) -> Int, commands: Map<String, Pair<String, KmFunction>>): String = buildString {
    append("/** Host dispatcher for `$fq`: reads a field and runs a command on the real state, by tag. */\n")
    append("internal object ${sn}Remote : RemoteDispatcher<$fq> {\n")
    append("  override fun read(state: $fq, member: Int): JsonElement = when (member) {\n")
    for (m in members.filter { it.kind == Member.Kind.OBSERVED }) {
      append("    ${tag(m)} -> ${encodeHost(propertyType(fq, m.name), "state.${m.name}")}\n")
    }
    append("    else -> error(\"unknown field \$member\")\n  }\n\n")
    append("  override suspend fun invoke(state: $fq, member: Int, args: JsonObject): JsonElement = when (member) {\n")
    for (m in members.filter { it.kind == Member.Kind.AWAITED || it.kind == Member.Kind.UNAWAITED }) {
      val (owner, f) = commands.getValue(m.key)
      val ps = paramsOf(packageOf(owner), owner.removePrefix(packageOf(owner) + "."), f.name, f.valueParameters, false)
      val call = "state.${f.name}(" + ps.sent.joinToString(", ") { p -> "${p.name} = ${decodeHost(p.type, "args.getValue(\"${p.name}\")")}" } + ")"
      val unit = fqOf(f.returnType) == "kotlin.Unit"
      append("    ${tag(m)} -> ${if (unit) "{\n      $call\n      JsonNull\n    }" else encodeHost(f.returnType, call)}\n")
    }
    append("    else -> error(\"unknown command \$member\")\n  }\n}\n")
  }

  private fun hostFactory(fq: String, sn: String, typeTag: Int, f: KmFunction, pkg: String): String {
    val ps = paramsOf(pkg, null, f.name, f.valueParameters, true)
    val args = ps.sent.joinToString(",\n          ") { p ->
      if (isSupplier(p.type)) {
        val t = p.type.arguments.single().type!!
        "${p.name} = { ${decodeHost(t, "init.value.getValue(\"${p.name}\")")} }"
      } else {
        "${p.name} = ${decodeHost(p.type, "wire.init.getValue(\"${p.name}\")")}"
      }
    }
    return "    $typeTag -> {\n      val init = rememberUpdatedState(wire.init)\n      val state = $pkg.${f.name}(\n          $args\n      )\n" +
      "      RemoteHost(wire, state, ${sn}Remote, report)\n      state\n    }\n"
  }
}
