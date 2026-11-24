/*
 * Copyright (C) 2026 Keliver contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.keliver.realguest

import app.cash.zipline.QuickJs
import app.cash.zipline.Zipline
import app.cash.zipline.ZiplineManifest
import app.cash.zipline.loader.ZiplineFile
import java.io.File
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import okio.Buffer

/*
 * Research spike (eff-guest): each variant guest links a subset of the real Compose Multiplatform
 * artifacts. This loads its production .zipline modules (the Zipline Gradle plugin's output, as an
 * app's bundle is) into the fork's QuickJS module by module, in the manifest's order, and runs the
 * guest's cases. It records, per variant:
 *  - the bytes of every module (.zipline bytecode, and the production .js it came from);
 *  - load time per module, cold (a fresh Zipline each time, median of 5);
 *  - the first module that fails to load, with the error;
 *  - every case's result or error, and the per-op time of the bench bodies (JVM clock).
 * Writes <out>/<variant>.json and prints one REALGUEST line per finding.
 */
class RealGuestQuickJsTest {
  private val out = File(System.getProperty("realguest.out")).apply { mkdirs() }

  @Test fun variants() {
    val variants = System.getProperty("realguest.variants").split(',')
    println("REALGUEST engine QuickJS ${QuickJs.version}")
    val failures = ArrayList<String>()
    for (v in variants) {
      val build = File(System.getProperty("realguest.build.$v"))
      val report = runVariant(v, build)
      File(out, "$v.json").writeText(report.json)
      // The finding this spike records: every case passes in QuickJS except the Skia-backed calls,
      // which fail at first use (not at load) because no Skia exists in the guest.
      val expectedFailures = report.caseNames.filter { it.startsWith("skia.") }.toSet()
      if (report.failed != expectedFailures) failures += "$v: failed ${report.failed}, expected $expectedFailures"
    }
    assertTrue(failures.isEmpty(), failures.joinToString("\n"))
  }

  private class Report(val json: String, val caseNames: List<String>, val failed: Set<String>)

  private fun runVariant(variant: String, build: File): Report {
    val bundle = File(build, "zipline/Production")
    check(File(bundle, "manifest.zipline.json").exists()) { "no bundle at $bundle" }
    val manifest = ZiplineManifest.decodeJson(File(bundle, "manifest.zipline.json").readText())
    val jsDir = build.walkTopDown().firstOrNull { it.isDirectory && it.path.endsWith("productionExecutable/kotlin") }

    // Sizes: bytecode per module, and the JavaScript it was compiled from.
    val sizes = manifest.modules.map { (id, module) ->
      val zip = File(bundle, module.url).length()
      val js = jsDir?.let { d -> File(d, id.removePrefix("./")).takeIf { it.exists() }?.length() } ?: -1L
      Triple(id, zip, js)
    }
    val totalZip = sizes.sumOf { it.second }
    val totalJs = sizes.sumOf { maxOf(0L, it.third) }
    println("REALGUEST $variant modules=${sizes.size} bytecode=$totalZip js=$totalJs")
    for ((id, zip, js) in sizes) println("REALGUEST $variant   module $id bytecode=$zip js=$js")

    val executor = Executors.newSingleThreadExecutor()
    val dispatcher = executor.asCoroutineDispatcher()
    try {
      return runBlocking(dispatcher) {
        // Cold loads: a fresh Zipline each time; the first run also records where loading fails.
        val loadTimes = ArrayList<Double>()
        val perModule = LinkedHashMap<String, MutableList<Double>>()
        var loadError: String? = null
        var failedModule: String? = null
        repeat(5) { round ->
          if (failedModule != null) return@repeat
          val zipline = Zipline.create(dispatcher)
          try {
            val t0 = System.nanoTime()
            for ((id, module) in manifest.modules) {
              val bytes = ZiplineFile.read(Buffer().write(File(bundle, module.url).readBytes())).quickjsBytecode.toByteArray()
              val t = System.nanoTime()
              try {
                zipline.loadJsModule(bytes, id)
              } catch (e: Throwable) {
                if (round == 0) {
                  failedModule = id
                  loadError = e.toString().take(2000)
                }
                break
              }
              perModule.getOrPut(id) { ArrayList() } += (System.nanoTime() - t) / 1e6
            }
            if (failedModule == null) loadTimes += (System.nanoTime() - t0) / 1e6
          } finally {
            zipline.close()
          }
        }
        if (failedModule != null) {
          println("REALGUEST $variant LOAD FAILED at module $failedModule: $loadError")
          return@runBlocking Report(
            """{"variant":"$variant","engine":"${QuickJs.version}","bytecode":$totalZip,"js":$totalJs,""" +
              """"modules":${sizesJson(sizes, perModule)},"loadFailedAt":${q(failedModule!!)},"loadError":${q(loadError!!)}}""",
            caseNames = emptyList(),
            failed = setOf("<load>"),
          )
        }
        println("REALGUEST $variant load median=${median(loadTimes).r()} ms (min ${loadTimes.min().r()}, n=${loadTimes.size})")

        // Cases and benches, in one fresh Zipline.
        val zipline = Zipline.create(dispatcher)
        try {
          val quickJs = zipline.quickJs
          for ((id, module) in manifest.modules) {
            zipline.loadJsModule(ZiplineFile.read(Buffer().write(File(bundle, module.url).readBytes())).quickjsBytecode.toByteArray(), id)
          }
          val heapAfterLoad = quickJs.memoryUsage.memoryUsedSize
          fun cmd(c: String): String = quickJs.evaluate("globalThis.realGuest('$c')", "RealGuestQuickJsTest.kt") as String
          // The skiko stub (variant.gradle) logs every member read and call: what module init touched.
          fun skikoTouched(): String = quickJs.evaluate("JSON.stringify(globalThis.__skikoTouched || null)", "RealGuestQuickJsTest.kt") as String
          val skikoAtLoad = skikoTouched()
          val skikoModule = manifest.modules.keys.any { it.contains("skiko") }
          println("REALGUEST $variant skiko module linked=$skikoModule, touched at load: $skikoAtLoad")
          val installed = quickJs.evaluate("typeof globalThis.realGuest") == "function"
          if (!installed) {
            val main = manifest.mainFunction
            if (main != null) quickJs.evaluate("require('${manifest.mainModuleId}').$main()", "RealGuestQuickJsTest.kt")
          }
          val t = System.nanoTime()
          val names = cmd("list").split(',')
          val firstCallMs = (System.nanoTime() - t) / 1e6
          val results = names.map { name ->
            val t1 = System.nanoTime()
            val r = try {
              cmd("run:$name")
            } catch (e: Throwable) {
              """{"ok":false,"error":${q("host: " + e.toString().take(1500))}}"""
            }
            val ms = (System.nanoTime() - t1) / 1e6
            val ok = r.startsWith("""{"ok":true""")
            println("REALGUEST $variant case $name ${if (ok) "OK" else "FAIL"} (${ms.r()} ms) ${r.take(600)}")
            Triple(name, r, ms)
          }
          val benches = cmd("benches").split(',').filter { it.isNotEmpty() }.map { name ->
            val n = if (name.startsWith("anim.infinite")) 600 else 2000
            // Warm up, then 5 timed runs of n ops.
            val warm = runCatching { cmd("bench:$name:$n") }
            if (warm.isFailure) {
              println("REALGUEST $variant bench $name FAIL ${warm.exceptionOrNull()}")
              return@map """${q(name)}:{"error":${q(warm.exceptionOrNull().toString().take(500))}}"""
            }
            val per = (1..5).map {
              val t2 = System.nanoTime()
              cmd("bench:$name:$n")
              (System.nanoTime() - t2) / 1e3 / n
            }
            println("REALGUEST $variant bench $name ${median(per).r()} us/op (n=$n, min ${per.min().r()})")
            """${q(name)}:{"n":$n,"usPerOpMedian":${median(per).r()},"usPerOpMin":${per.min().r()}}"""
          }
          val failed = results.filter { !it.second.startsWith("""{"ok":true""") }.map { it.first }.toSet()
          Report(
            buildString {
              append("""{"variant":"$variant","engine":"${QuickJs.version}","bytecode":$totalZip,"js":$totalJs,""")
              append(""""modules":${sizesJson(sizes, perModule)},""")
              append(""""loadMedianMs":${median(loadTimes).r()},"loadMinMs":${loadTimes.min().r()},"heapAfterLoad":$heapAfterLoad,"firstCallMs":${firstCallMs.r()},""")
              append(""""skikoLinked":$skikoModule,"skikoTouchedAtLoad":$skikoAtLoad,"skikoTouchedAfterCases":${skikoTouched()},""")
              append(""""cases":{${results.joinToString(",") { (n, r, ms) -> """${q(n)}:{"ms":${ms.r()},"r":$r}""" }}},""")
              append(""""benches":{${benches.joinToString(",")}}}""")
            },
            caseNames = names,
            failed = failed,
          )
        } finally {
          zipline.close()
        }
      }
    } finally {
      dispatcher.close()
      executor.shutdown()
    }
  }

  private fun sizesJson(sizes: List<Triple<String, Long, Long>>, loads: Map<String, List<Double>>): String =
    sizes.joinToString(",", "[", "]") { (id, zip, js) ->
      val l = loads[id]
      """{"id":${q(id)},"bytecode":$zip,"js":$js,"loadMedianMs":${if (l.isNullOrEmpty()) "null" else median(l).r()}}"""
    }

  private fun q(s: String): String =
    "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""

  private fun median(xs: List<Double>): Double {
    val s = xs.sorted()
    return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
  }

  private fun Double.r(): String = "%.3f".format(this)
}
