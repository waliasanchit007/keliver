/*
 * Copyright (C) 2026 Keliver contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.keliver.compose.gatea.profile

import app.cash.zipline.QuickJs
import app.cash.zipline.Zipline
import app.cash.zipline.ZiplineManifest
import app.cash.zipline.loader.ZiplineFile
import java.io.File
import okio.Buffer
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking

/*
 * The guest profile in Zipline's own QuickJS (docs/GUEST_PROFILE.md, part 2b): the JVM build of
 * Zipline 1.28.0 ships the same QuickJS the phones run, built for this Mac. This loads the guest
 * profile's production .zipline bytecode (compiled by the Zipline Gradle plugin, as an app's bundle
 * is), module by module in the manifest's order, then drives the steps through
 * `globalThis.guestProfile(command)` and times each one on the JVM's clock.
 *
 * Args: <bundle dir> <out dir> <comma args>. Args: `gc=default+4+16+64` (MiB; `default` leaves
 * QuickJS's threshold as Zipline sets it), `modes=kotlinx+direct+none`, `warmup=3`, `iterations=30`,
 * `items=20`, `label=<text>` (names the output file), `retain=1` (NF1's retained back stack).
 * NF3: `prepared=0|1` (the prepared stack, at that depth), `press=1` (a timed touch-down step before
 * each open, and a series of 10 cancelled presses at the end), `viewport=<rows>` (the host's
 * viewport report after a list is created, timed as its own step), `sized=0` (no viewport-sized
 * first window).
 * `cycles=N` instead runs the memory-growth readings (`memoryCycles`; with `gcEvery`, `probeEvals`).
 * NF5: `nf5=fresh+rebind+shown` (with `heavy=0+1`) instead runs [nf5Runs], the go/no-go for
 * pre-composed destinations (docs/SHOW_BEFORE_COMPUTE.md, appendix).
 */
fun main(args: Array<String>) {
  val bundle = File(args[0])
  val out = File(args[1]).apply { mkdirs() }
  // Which engine this run loaded: 2021-03-27 as shipped, 2026-06-04 on NF2's fork (engine/zipline-fork).
  println("QJS engine: QuickJS ${QuickJs.version}")
  val a = args.getOrElse(2) { "" }.split(',').map { it.trim() }.filter { '=' in it }
    .associate { it.substringBefore('=') to it.substringAfter('=') }
  val gcs = (a["gc"] ?: "default").split('+')
  val modes = (a["modes"] ?: "kotlinx").split('+')
  val warmup = (a["warmup"] ?: "3").toInt()
  val iterations = (a["iterations"] ?: "30").toInt()
  val items = (a["items"] ?: "20").toInt()
  val label = a["label"] ?: "quickjs"
  withReload = a["reload"] == "1"
  retain = a["retain"] == "1"
  nf3Args = listOf("prepared", "press", "viewport", "sized").mapNotNull { k -> a[k]?.let { "$k=$it" } }.joinToString("") { ",$it" }
  press = a["press"] == "1"

  val manifest = ZiplineManifest.decodeJson(File(bundle, "manifest.zipline.json").readText())
  val executor = Executors.newSingleThreadExecutor()
  val dispatcher = executor.asCoroutineDispatcher()
  val nf5 = a["nf5"]
  if (nf5 != null) {
    // NF5's go/no-go (docs/SHOW_BEFORE_COMPUTE.md, appendix): `nf5=fresh+rebind+shown`, `heavy=0+1`.
    try {
      runBlocking(dispatcher) {
        nf5Runs(bundle, manifest, dispatcher, nf5.split('+'), (a["heavy"] ?: "0").split('+'), warmup, iterations, items, File(out, "$label.jsonl"))
      }
    } finally {
      dispatcher.close()
      executor.shutdown()
    }
    return
  }
  val cycles = a["cycles"]?.toInt()
  if (cycles != null) {
    // Memory growth per navigation (docs/GUEST_PROFILE.md): one line per open/back cycle.
    try {
      runBlocking(dispatcher) {
        for (mode in modes) {
          memoryCycles(bundle, manifest, mode, items, cycles, (a["gcEvery"] ?: "0").toInt(), (a["probeEvals"] ?: "0").toInt(), File(out, "$label-$mode.jsonl"), dispatcher)
        }
      }
    } finally {
      dispatcher.close()
      executor.shutdown()
    }
    return
  }
  val lines = ArrayList<String>()
  // Every step's batch (hash and byte count), per run: the encodings that record values must send
  // the same batches step for step. `none` records structure only, so it is not compared.
  var reference: Pair<String, List<String>>? = null
  var compared = 0
  try {
    runBlocking(dispatcher) {
      for (mode in modes) {
        for (gc in gcs) {
          val (line, batches) = runOne(bundle, manifest, dispatcher, mode, gc, warmup, iterations, items)
          println("QUICKJS_PROFILE $line")
          lines += line
          if (mode == "none") continue
          val ref = reference
          if (ref == null) {
            reference = "$mode/gc=$gc" to batches
          } else {
            check(ref.second.size == batches.size) { "$mode/gc=$gc ran ${batches.size} steps, ${ref.first} ${ref.second.size}" }
            for (i in batches.indices) {
              check(batches[i] == ref.second[i]) {
                "$mode/gc=$gc step $i sent a different batch than ${ref.first}: ${batches[i]} vs ${ref.second[i]}"
              }
            }
            compared++
          }
        }
      }
    }
  } finally {
    dispatcher.close()
    executor.shutdown()
  }
  val ref = reference
  println(
    if (ref == null || compared == 0) "QJS batches: nothing to compare"
    else "QJS batches: ${compared + 1} runs sent identical batches at all ${ref.second.size} steps (hash and bytes, against ${ref.first})",
  )
  File(out, "$label.jsonl").writeText(lines.joinToString("\n", postfix = "\n"))
}

private fun batchOf(stepJson: String): String {
  fun field(name: String) = Regex("\"$name\":\"?(-?[0-9a-z]+)").find(stepJson)!!.groupValues[1]
  return "${field("kind")}:${field("hash")}:${field("bytes")}"
}

private fun runOne(
  bundle: File,
  manifest: ZiplineManifest,
  dispatcher: kotlinx.coroutines.CoroutineDispatcher,
  mode: String,
  gc: String,
  warmup: Int,
  iterations: Int,
  items: Int,
): Pair<String, List<String>> {
  val zipline = Zipline.create(dispatcher)
  val batches = ArrayList<String>()
  try {
    val quickJs = zipline.quickJs
    val defaultThreshold = quickJs.gcThreshold
    if (gc != "default") quickJs.gcThreshold = gc.toLong() * 1024 * 1024

    val loadStart = System.nanoTime()
    for ((id, module) in manifest.modules) {
      // A .zipline file wraps the QuickJS bytecode (with the QuickJS version it was compiled for).
      val ziplineFile = ZiplineFile.read(Buffer().write(File(bundle, module.url).readBytes()))
      zipline.loadJsModule(ziplineFile.quickjsBytecode.toByteArray(), id)
    }
    if (quickJs.evaluate("typeof globalThis.guestProfile") != "function") {
      val main = manifest.mainFunction
      check(main != null) { "the bundle did not install guestProfile and names no main function" }
      quickJs.evaluate("require('${manifest.mainModuleId}').$main()", "QuickJsProfile.kt")
    }
    val loadMs = (System.nanoTime() - loadStart) / 1e6
    val afterLoad = quickJs.memoryUsage

    fun cmd(c: String): String = quickJs.evaluate("globalThis.guestProfile('$c')", "QuickJsProfile.kt") as String

    cmd("init:mode=$mode,items=$items,retain=${if (retain) 1 else 0}$nf3Args")
    val firstStart = System.nanoTime()
    val first = cmd("first")
    val firstMs = (System.nanoTime() - firstStart) / 1e6
    batches += batchOf(first)

    // NF3: the host's viewport report (a separate step, after the frame it follows), and the press.
    val pressMs = ArrayList<Double>()
    val pressGuest = ArrayList<Double>()
    val viewportGuest = ArrayList<Double>()
    var lastPress = ""
    var lastViewport = ""
    fun viewport(measured: Boolean) {
      val v = cmd("viewport")
      if (v == "{}") return
      batches += batchOf(v)
      lastViewport = v
      if (measured) viewportGuest += guestMs(v)
    }
    viewport(measured = false)

    val open = ArrayList<Double>()
    val back = ArrayList<Double>()
    val openGuest = ArrayList<Double>()
    val backGuest = ArrayList<Double>()
    var lastOpen = ""
    var lastBack = ""
    var lastReload = ""
    val reload = ArrayList<Double>()
    var memBeforeBack = 0L
    var memAfterBack = 0L
    var objsBeforeBack = 0L
    var objsAfterBack = 0L
    repeat(warmup + iterations) { i ->
      var t: Long
      if (press) {
        t = System.nanoTime()
        lastPress = cmd("press:${i % 5}")
        val p = (System.nanoTime() - t) / 1e6
        batches += batchOf(lastPress)
        if (i >= warmup) {
          pressMs += p
          pressGuest += guestMs(lastPress)
        }
      }
      t = System.nanoTime()
      lastOpen = cmd("open:${i % 5}")
      val o = (System.nanoTime() - t) / 1e6
      val m0 = quickJs.memoryUsage
      t = System.nanoTime()
      lastBack = cmd("back")
      val b = (System.nanoTime() - t) / 1e6
      val m1 = quickJs.memoryUsage
      batches += batchOf(lastOpen)
      batches += batchOf(lastBack)
      viewport(measured = i >= warmup)
      if (withReload) {
        t = System.nanoTime()
        lastReload = cmd("reload")
        val r = (System.nanoTime() - t) / 1e6
        batches += batchOf(lastReload)
        if (i >= warmup) reload += r
      }
      if (i >= warmup) {
        open += o
        back += b
        openGuest += guestMs(lastOpen)
        backGuest += guestMs(lastBack)
        memBeforeBack = m0.memoryUsedSize
        memAfterBack = m1.memoryUsedSize
        objsBeforeBack = m0.objectsCount
        objsAfterBack = m1.objectsCount
      }
    }
    // NF3: presses that are cancelled (the finger moved): what a wasted preparation costs, and its drop.
    val cancelledPressGuest = ArrayList<Double>()
    val cancelGuest = ArrayList<Double>()
    if (press) {
      repeat(10) { i ->
        val p = cmd("press:${i % 5}")
        val c = cmd("cancel:${i % 5}")
        batches += batchOf(p)
        batches += batchOf(c)
        cancelledPressGuest += guestMs(p)
        cancelGuest += guestMs(c)
      }
    }
    cmd("close")
    // One explicit full GC on this heap, timed: what one cycle collection costs here.
    val gcStart = System.nanoTime()
    quickJs.gc()
    val gcMs = (System.nanoTime() - gcStart) / 1e6
    val afterGc = quickJs.memoryUsage

    println(
      "QJS mode=$mode gc=$gc open=${median(open).r()} back=${median(back).r()} backMin=${back.min().r()} reload=${if (reload.isEmpty()) "-" else median(reload).r()} " +
        "first=${firstMs.r()} load=${loadMs.r()} heapBack=${memBeforeBack / 1024}K->${memAfterBack / 1024}K " +
        "threshold=${quickJs.gcThreshold / 1024}K explicitGc=${gcMs.r()} hash=${Regex("\"hash\":(-?[0-9]+)").find(lastBack)!!.groupValues[1]}",
    )
    if (nf3Args.isNotEmpty()) {
      println(
        "QJS NF3$nf3Args openGuest=${median(openGuest).r()} backGuest=${median(backGuest).r()} " +
          "press=${if (pressMs.isEmpty()) "-" else median(pressMs).r()} pressGuest=${if (pressGuest.isEmpty()) "-" else median(pressGuest).r()} " +
          "viewportGuest=${if (viewportGuest.isEmpty()) "-" else median(viewportGuest).r()} " +
          "cancelledPressGuest=${if (cancelledPressGuest.isEmpty()) "-" else median(cancelledPressGuest).r()} " +
          "cancelGuest=${if (cancelGuest.isEmpty()) "-" else median(cancelGuest).r()} " +
          "creates(open/back)=${createsOf(lastOpen)}/${createsOf(lastBack)}",
      )
    }
    return buildString {
      append("""{"steps":${batches.size},""")
      append(""""mode":"$mode","retain":$retain,"gc":"$gc","defaultThreshold":$defaultThreshold,"threshold":${quickJs.gcThreshold},""")
      append(""""loadMs":${loadMs.r()},"firstMs":${firstMs.r()},""")
      append(""""openMedianMs":${median(open).r()},"openMinMs":${open.min().r()},"openGuestMedianMs":${median(openGuest).r()},""")
      append(""""backMedianMs":${median(back).r()},"backMinMs":${back.min().r()},"backGuestMedianMs":${median(backGuest).r()},""")
      append(""""heapAfterLoad":${afterLoad.memoryUsedSize},"objectsAfterLoad":${afterLoad.objectsCount},""")
      append(""""heapBeforeBack":$memBeforeBack,"heapAfterBack":$memAfterBack,"objectsBeforeBack":$objsBeforeBack,"objectsAfterBack":$objsAfterBack,""")
      append(""""explicitGcMs":${gcMs.r()},"heapAfterGc":${afterGc.memoryUsedSize},"objectsAfterGc":${afterGc.objectsCount},""")
      if (reload.isNotEmpty()) append(""""reloadMedianMs":${median(reload).r()},"lastReload":$lastReload,""")
      if (nf3Args.isNotEmpty()) append(""""nf3":"${nf3Args.removePrefix(",")}",""")
      if (pressGuest.isNotEmpty()) {
        append(""""pressMedianMs":${median(pressMs).r()},"pressGuestMedianMs":${median(pressGuest).r()},"lastPress":$lastPress,""")
        append(""""cancelledPressGuestMedianMs":${median(cancelledPressGuest).r()},"cancelGuestMedianMs":${median(cancelGuest).r()},""")
      }
      if (viewportGuest.isNotEmpty()) append(""""viewportGuestMedianMs":${median(viewportGuest).r()},"lastViewport":$lastViewport,""")
      append(""""first":$first,"lastOpen":$lastOpen,"lastBack":$lastBack}""")
    } to batches
  } finally {
    zipline.close()
  }
}

/** Every numeric field of QuickJS's `JS_ComputeMemoryUsage` (Zipline's MemoryUsage), by name. */
private fun usage(m: app.cash.zipline.MemoryUsage): Map<String, Long> =
  m.javaClass.declaredFields
    .filter { it.type == java.lang.Long.TYPE || it.type == Integer.TYPE }
    .associate { f -> f.isAccessible = true; f.name to (f.get(m) as Number).toLong() }

private fun Map<String, Long>.json(): String =
  entries.joinToString(",", "{", "}") { (k, v) -> "\"$k\":$v" }

/**
 * `cycles=N` (with `gcEvery=K`, `retain=1`): memory growth per navigation. Loads the bundle, shows
 * the list, then runs N open/back cycles. After each open and each back it records QuickJS's whole
 * memory usage and the guest's own counters (`counts`); every K cycles (K > 0) it forces a full
 * collection (`JS_RunGC` through `quickJs.gc()`) and records again, so a reclaimed growth shows as
 * a drop at the GC. One JSON line per reading, in `<label>-<mode>.jsonl`.
 */
private fun memoryCycles(
  bundle: File,
  manifest: ZiplineManifest,
  mode: String,
  items: Int,
  cycles: Int,
  gcEvery: Int,
  probeEvals: Int,
  file: File,
  dispatcher: kotlinx.coroutines.CoroutineDispatcher,
) {
  val zipline = Zipline.create(dispatcher)
  try {
    val quickJs = zipline.quickJs
    for ((id, module) in manifest.modules) {
      val ziplineFile = ZiplineFile.read(Buffer().write(File(bundle, module.url).readBytes()))
      zipline.loadJsModule(ziplineFile.quickjsBytecode.toByteArray(), id)
    }
    if (quickJs.evaluate("typeof globalThis.guestProfile") != "function") {
      quickJs.evaluate("require('${manifest.mainModuleId}').${manifest.mainFunction}()", "QuickJsProfile.kt")
    }
    fun cmd(c: String): String = quickJs.evaluate("globalThis.guestProfile('$c')", "QuickJsProfile.kt") as String
    val lines = ArrayList<String>()
    fun read(cycle: Int, at: String) {
      val u = usage(quickJs.memoryUsage)
      lines += """{"mode":"$mode","retain":$retain,"cycle":$cycle,"at":"$at","threshold":${quickJs.gcThreshold},"qjs":${u.json()},"guest":${cmd("counts")}}"""
      println("QJS_MEM $mode retain=$retain cycle=$cycle at=$at used=${u["memoryUsedSize"]} objects=${u["objectsCount"]} " +
        "binary=${u["binaryObjectsCount"]}/${u["binaryObjectsSize"]} arrayElements=${u["fastArraysElementsCount"]}")
    }
    cmd("init:mode=$mode,items=$items,retain=${if (retain) 1 else 0}")
    cmd("first")
    read(0, "first")
    quickJs.gc()
    read(0, "gc")
    for (c in 1..cycles) {
      cmd("open:${(c - 1) % 5}")
      read(c, "open")
      cmd("back")
      // `probeEvals=K`: K extra empty evaluations per cycle, a control for what the harness's own
      // `evaluate` calls leave behind (docs/GUEST_PROFILE.md, "Memory growth per navigation").
      repeat(probeEvals) { quickJs.evaluate("0", "QuickJsProfile.kt") }
      read(c, "back")
      if (gcEvery > 0 && c % gcEvery == 0) {
        quickJs.gc()
        read(c, "gc")
      }
    }
    cmd("close")
    quickJs.gc()
    read(cycles, "closed+gc")
    file.writeText(lines.joinToString("\n", postfix = "\n"))
  } finally {
    zipline.close()
  }
}

/**
 * NF5's go/no-go (docs/SHOW_BEFORE_COMPUTE.md, appendix): for each variant (Nf5Stack.kt) and detail
 * weight, a fresh guest shows the list, then [warmup] + [iterations] open/back pairs on rows 0–4
 * (`shown` adds the timed `nf5prepare` step before each open). Wall time per step on the JVM's
 * clock (the harness's `evaluate` included) and the guest's own time (`ms`, without the batch's
 * JSON.stringify), median and p90; the last open's batch (creates, properties, modifiers,
 * structural changes, bytes). After the first variant's `first`, the list screen's first batch is
 * written as `<label>-list-wire.json` (the cached-frame replay's input).
 */
private fun nf5Runs(
  bundle: File,
  manifest: ZiplineManifest,
  dispatcher: kotlinx.coroutines.CoroutineDispatcher,
  variants: List<String>,
  heavies: List<String>,
  warmup: Int,
  iterations: Int,
  items: Int,
  file: File,
) {
  val lines = ArrayList<String>()
  var wireWritten = false
  for (heavy in heavies) {
    for (variant in variants) {
      val zipline = Zipline.create(dispatcher)
      try {
        val quickJs = zipline.quickJs
        for ((id, module) in manifest.modules) {
          val ziplineFile = ZiplineFile.read(Buffer().write(File(bundle, module.url).readBytes()))
          zipline.loadJsModule(ziplineFile.quickjsBytecode.toByteArray(), id)
        }
        if (quickJs.evaluate("typeof globalThis.guestProfile") != "function") {
          quickJs.evaluate("require('${manifest.mainModuleId}').${manifest.mainFunction}()", "QuickJsProfile.kt")
        }
        fun cmd(c: String): String = quickJs.evaluate("globalThis.guestProfile('$c')", "QuickJsProfile.kt") as String
        cmd("init:mode=kotlinx,items=$items,nf5=$variant,heavy=$heavy")
        var t = System.nanoTime()
        val first = cmd("first")
        val firstMs = (System.nanoTime() - t) / 1e6
        if (!wireWritten) {
          File(file.parentFile, file.nameWithoutExtension + "-list-wire.json").writeText(cmd("wire"))
          wireWritten = true
        }
        val wall = mutableMapOf<String, MutableList<Double>>()
        val guest = mutableMapOf<String, MutableList<Double>>()
        val last = mutableMapOf<String, String>()
        fun step(kind: String, c: String, measured: Boolean) {
          t = System.nanoTime()
          val r = cmd(c)
          val ms = (System.nanoTime() - t) / 1e6
          last[kind] = r
          if (measured) {
            wall.getOrPut(kind) { ArrayList() } += ms
            guest.getOrPut(kind) { ArrayList() } += guestMs(r)
          }
        }
        // The step that gives the detail its item must send that item's title: a wrong click (the
        // driver's clickables out of order) would otherwise time a step that opens nothing.
        fun checkTitle(i: Int) {
          val title = "Order #${1000 + i % 5}"
          check(title in cmd("wire")) { "nf5=$variant heavy=$heavy step $i: the batch does not show $title" }
        }
        repeat(warmup + iterations) { i ->
          val measured = i >= warmup
          if (variant == "shown") {
            step("prepare", "nf5prepare:${i % 5}", measured)
            checkTitle(i)
          }
          step("open", "open:${i % 5}", measured)
          if (variant != "shown") checkTitle(i)
          step("back", "back", measured)
          for (k in listOf("open", "back")) {
            check(Regex("\"bytes\":([0-9]+)").find(last[k]!!)!!.groupValues[1].toInt() > 0) { "nf5=$variant heavy=$heavy step $i: $k sent nothing" }
          }
        }
        cmd("close")
        val summary = wall.keys.sorted().joinToString(",") { k ->
          """"$k":{"n":${wall[k]!!.size},"wallMedianMs":${median(wall[k]!!).r()},"wallP90Ms":${p90(wall[k]!!).r()},""" +
            """"guestMedianMs":${median(guest[k]!!).r()},"guestP90Ms":${p90(guest[k]!!).r()},"last":${last[k]}}"""
        }
        val line = """{"nf5":"$variant","heavy":$heavy,"engine":"${QuickJs.version}","warmup":$warmup,"iterations":$iterations,""" +
          """"firstWallMs":${firstMs.r()},"first":$first,$summary}"""
        lines += line
        println("QUICKJS_NF5 $line")
        println(
          "QJS NF5 variant=$variant heavy=$heavy " + wall.keys.sorted().joinToString(" ") { k ->
            "$k=${median(wall[k]!!).r()}/p90 ${p90(wall[k]!!).r()} (guest ${median(guest[k]!!).r()}/p90 ${p90(guest[k]!!).r()}; " +
              "creates ${createsOf(last[k]!!)} bytes ${Regex("\"bytes\":([0-9]+)").find(last[k]!!)?.groupValues?.get(1)})"
          } + " first=${firstMs.r()}",
        )
      } finally {
        zipline.close()
      }
    }
  }
  file.writeText(lines.joinToString("\n", postfix = "\n"))
}

private fun p90(xs: List<Double>): Double {
  val s = xs.sorted()
  return s[minOf(s.size - 1, kotlin.math.ceil(0.9 * s.size).toInt() - 1)]
}

/** `reload=1`: also time the reload step (off by default; see GuestProfileMain.kt's `reload`). */
private var withReload = false

/** `retain=1`: NF1's retained stack, the list kept composed under the detail (the guest's `retain`). */
private var retain = false

/** NF3's options, passed on to the guest's `init` (",prepared=1,press=1,…"). */
private var nf3Args = ""

/** `press=1`: a `press` step before each `open`. */
private var press = false

private fun createsOf(json: String): String =
  Regex("\"creates\":([0-9]+)").find(json)?.groupValues?.get(1) ?: "-"

private fun guestMs(json: String): Double =
  Regex("\"ms\":([0-9.]+)").find(json)!!.groupValues[1].toDouble()

private fun median(xs: List<Double>): Double {
  val s = xs.sorted()
  return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
}

private fun Double.r(): String = "%.2f".format(this)
