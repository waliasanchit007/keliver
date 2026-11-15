/*
 * Copyright (C) 2026 Keliver contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.keliver.compose.gatea.profile

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/*
 * Summarises a V8 .cpuprofile (Node --cpu-prof) of the guest profile (docs/GUEST_PROFILE.md, part
 * 2a): self time by JS module file (the per-module Kotlin/JS output: one file per library), the top
 * functions by self time, and inclusive time for named function groups (a sample counts once for a
 * group when any frame on its stack matches).
 *
 * Args: <file.cpuprofile> [top N].
 */
fun main(args: Array<String>) {
  val file = File(args[0])
  val top = args.getOrNull(1)?.toIntOrNull() ?: 60
  val root = Json.parseToJsonElement(file.readText()).jsonObject

  class Node(val id: Int, val fn: String, val url: String, val line: Int, val children: List<Int>) {
    var parent: Node? = null
  }
  val nodes = HashMap<Int, Node>()
  for (n in root["nodes"]!!.jsonArray) {
    val o = n.jsonObject
    val cf = o["callFrame"]!!.jsonObject
    val children = (o["children"]?.jsonArray ?: emptyList()).map { it.jsonPrimitive.int }
    nodes[o["id"]!!.jsonPrimitive.int] = Node(
      id = o["id"]!!.jsonPrimitive.int,
      fn = cf["functionName"]!!.jsonPrimitive.content,
      url = cf["url"]!!.jsonPrimitive.content,
      line = cf["lineNumber"]!!.jsonPrimitive.int,
      children = children,
    )
  }
  for (n in nodes.values) for (c in n.children) nodes[c]!!.parent = n

  val samples = root["samples"]!!.jsonArray.map { it.jsonPrimitive.int }
  val deltas = root["timeDeltas"]!!.jsonArray.map { it.jsonPrimitive.int }
  // timeDeltas[i] is the time before sample i; attribute the gap after a sample to it.
  val weight = LongArray(samples.size) { i -> if (i + 1 < deltas.size) deltas[i + 1].toLong() else 0L }

  fun module(n: Node): String = when {
    n.url.isEmpty() -> n.fn // (garbage collector), (program), (idle), native builtins
    else -> n.url.substringAfterLast('/').removeSuffix(".js").removeSuffix(".mjs")
  }

  val total = weight.sum().toDouble()
  val busy = samples.indices.filter { nodes[samples[it]]!!.fn != "(idle)" }.sumOf { weight[it] }.toDouble()
  val selfByModule = HashMap<String, Long>()
  val selfByFn = HashMap<String, Long>()
  for (i in samples.indices) {
    val n = nodes[samples[i]]!!
    selfByModule.merge(module(n), weight[i], Long::plus)
    selfByFn.merge("${n.fn} @ ${module(n)}:${n.line + 1}", weight[i], Long::plus)
  }

  // Inclusive groups: a regex on "function @ module".
  val groups = linkedMapOf(
    "recorder: appendPropertyChange" to Regex("appendPropertyChange"),
    "recorder: appendModifierChange" to Regex("appendModifierChange"),
    "recorder: appendCreate/Add/Remove/Move" to Regex("append(Create|Add|Remove|Move)\\b"),
    "kotlinx encodeToDynamic (any)" to Regex("encodeToDynamic"),
    "kotlinx.serialization (module, inclusive)" to Regex("@ kotlinx-serialization"),
    "serializer lookup (serializer/serializerOrNull/compiledSerializer)" to Regex("^(serializer|serializerOrNull|serializerByKTypeImpl|compiledSerializerImpl|serializerByJsType|serializersModule|builtinSerializer|constructSerializerForGivenTypeArgs|createCache|findCachedSerializer|platformSpecificSerializerNotRegistered|get_js|createSerializer)[_0-9]*\\b"),
    "mirror codecs toJson (Color/TextUnit/TextStyle…)" to Regex("toJson"),
    "Compose runtime (module, inclusive)" to Regex("@ compose-multiplatform-core-compose-runtime-runtime\\b|@ .*compose-runtime-runtime\\.js|@ .*runtime-runtime"),
    "Recomposer/Composition (recompose, applyChanges)" to Regex("^(recompose|performRecompose|applyChanges|composeContent|doCompose|composeInitial|setContent|runRecomposeAndApplyChanges)[_0-9]*\\b"),
    "generated widget stubs (gatea-compose)" to Regex("@ keliver-compose-gatea-compose\\b|@ keliver-keliver-compose-gatea-compose"),
    "generated protocol widgets (gatea-protocol-guest)" to Regex("protocol-guest"),
    "lazy list (LazyDsl, lazylayout)" to Regex("Lazy|lazylayout|loadRange|Intervals|Items_"),
    "splitModifierEvents" to Regex("splitModifierEvents"),
    "Long emulation (stdlib Long)" to Regex("^(Long|add|subtract|multiply|divide|modulo|compare|equalsLong|fromInt|fromNumber|toNumber|negate|shiftLeft|shiftRight|shiftRightUnsigned|bitwiseAnd|bitwiseOr|bitwiseXor|invert|isNegative|isZero|isOdd|lessThan|greaterThan|numberToLong|toLong|longFromTwoInts|getHighBits|getLowBits|hashCodeLong|convertToByte|ULong|ulongCompare|ulongDivide|_ULong___init__)[_0-9]*\\b.*@ kotlin-kotlin-stdlib"),
    "JSON.stringify (bridge stand-in)" to Regex("^stringify\\b|JSON"),
    "driver (settle, ManualDispatcher)" to Regex("settle|ManualDispatcher|drain"),
  )
  val inclusive = LinkedHashMap<String, Long>().apply { groups.keys.forEach { put(it, 0L) } }
  for (i in samples.indices) {
    var n: Node? = nodes[samples[i]]
    val hit = HashSet<String>()
    while (n != null) {
      val key = "${n.fn} @ ${module(n)}"
      for ((g, r) in groups) if (g !in hit && r.containsMatchIn(key)) hit += g
      n = n.parent
    }
    for (g in hit) inclusive.merge(g, weight[i], Long::plus)
  }

  // Who pays for emulated Long: for samples inside the Long group, the nearest frame outside the
  // stdlib (and outside kotlinx.serialization, whose callers are the interesting part too).
  val longGroup = groups.getValue("Long emulation (stdlib Long)")
  val longCallers = HashMap<String, Long>()
  val longCallerModules = HashMap<String, Long>()
  for (i in samples.indices) {
    var n: Node? = nodes[samples[i]]
    var inLong = false
    while (n != null && module(n) == "kotlin-kotlin-stdlib") {
      if (longGroup.containsMatchIn("${n.fn} @ ${module(n)}")) inLong = true
      n = n.parent
    }
    if (!inLong || n == null) continue
    longCallers.merge("${n.fn} @ ${module(n)}:${n.line + 1}", weight[i], Long::plus)
    longCallerModules.merge(module(n), weight[i], Long::plus)
  }

  // A partition (each sample counted once), first rule that matches:
  //  harness → recording (properties / modifiers / structure) → serializer lookups → GC/program →
  //  the first module up the stack that is not the stdlib (stdlib time belongs to its caller), with
  //  Long arithmetic split out.
  val partition = HashMap<String, Long>()
  for (i in samples.indices) {
    val stack = ArrayList<Node>()
    var n: Node? = nodes[samples[i]]
    while (n != null) { stack += n; n = n.parent }
    val keys = stack.map { "${it.fn} @ ${module(it)}" }
    val leaf = stack.first()
    val bucket = when {
      // The batch callback is the anonymous function in guest-profile nearest the leaf.
      keys.take(3).any { it == " @ guest-profile" || it.startsWith("getStringHashCode") } -> "harness (batch JSON.stringify + hash)"
      keys.any { it.startsWith("appendPropertyChange") } -> "recording: property values"
      keys.any { it.startsWith("appendModifierChange") } -> "recording: modifiers"
      keys.any { Regex("^append(Create|Add|Remove|Move)|^emitChanges").containsMatchIn(it) } -> "recording: structure + emit"
      keys.any { groups.getValue("serializer lookup (serializer/serializerOrNull/compiledSerializer)").containsMatchIn(it) } -> "serializer lookups (widget constructors)"
      leaf.fn == "(garbage collector)" -> "GC"
      leaf.fn == "(program)" || leaf.fn == "(root)" -> "(program)"
      else -> {
        val owner = stack.firstOrNull { module(it) != "kotlin-kotlin-stdlib" }
        val ownerModule = owner?.let(::module) ?: "kotlin-kotlin-stdlib"
        val viaLong = stack.takeWhile { module(it) == "kotlin-kotlin-stdlib" }.any { longGroup.containsMatchIn("${it.fn} @ ${module(it)}") }
        val name = when {
          ownerModule.contains("compose-runtime-runtime") -> "Compose runtime"
          ownerModule.contains("collection") -> "Compose runtime: androidx.collection"
          ownerModule.contains("gatea-compose") -> "generated stubs (gatea-compose)"
          ownerModule.contains("gatea-protocol-guest") -> "generated protocol widgets"
          ownerModule.contains("compose-values") -> "mirror types (compose-values)"
          ownerModule.contains("lazylayout") -> "lazy layout window"
          ownerModule.startsWith("keliver-") -> "other Keliver runtime ($ownerModule)"
          ownerModule == "guest-profile" -> "harness (driver)"
          else -> ownerModule
        }
        if (viaLong) "$name — in emulated Long" else name
      }
    }
    partition.merge(bucket, weight[i], Long::plus)
  }

  fun pct(x: Long) = "%5.1f%%".format(100.0 * x / busy)
  println("\n== partition (each sample once; stdlib time goes to its first non-stdlib caller)")
  partition.entries.sortedByDescending { it.value }.forEach { println("${pct(it.value)}  ${it.key}") }
  println("\n== Long emulation (inside stdlib Long functions) by first caller module outside the stdlib")
  longCallerModules.entries.sortedByDescending { it.value }.take(15).forEach { println("${pct(it.value)}  ${it.key}") }
  println("\n== … by first caller function")
  longCallers.entries.sortedByDescending { it.value }.take(25).forEach { println("${pct(it.value)}  ${it.key}") }
  println("profile: ${file.name}; ${samples.size} samples; total ${"%.0f".format(total / 1000)} ms, busy (not idle) ${"%.0f".format(busy / 1000)} ms")
  println("\n== self time by module (share of busy)")
  selfByModule.entries.sortedByDescending { it.value }.take(30).forEach { println("${pct(it.value)}  ${it.key}") }
  println("\n== inclusive time by group (share of busy; groups overlap)")
  inclusive.forEach { (g, v) -> println("${pct(v)}  $g") }
  println("\n== top $top functions by self time")
  selfByFn.entries.sortedByDescending { it.value }.take(top).forEach { println("${pct(it.value)}  ${it.key}") }
}

@Suppress("unused")
private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.content
