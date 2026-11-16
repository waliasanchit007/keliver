/*
 * Copyright (C) 2026 Keliver contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.keliver.compose.gatea.profile

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
 */
fun main(args: Array<String>) {
  val bundle = File(args[0])
  val out = File(args[1]).apply { mkdirs() }
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
