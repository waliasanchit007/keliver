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
import kotlin.metadata.Visibility
import kotlin.metadata.declaresDefaultValue
import kotlin.metadata.isSuspend
import kotlin.metadata.visibility
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Spike (docs/COMPOSE_GENERIC_MECHANISMS.md, part a): the three generic mechanisms against the real
 * metadata of the Compose the generator reads. Writes the coverage report the doc quotes to
 * `build/generic-survey.md`.
 */
class GenericSurveyTest {
  private val index by lazy {
    GenericIndex((System.getProperty("keliver.composeApi") ?: error("the test task passes keliver.composeApi")).split(File.pathSeparator).map(::File))
  }
  private val survey by lazy { GenericSurvey(index) }

  @Test fun metadataCarriesWhatTheMechanismsNeed() {
    // 1. TextStyle's public constructors, with every parameter's "has a default" bit.
    val textStyle = index.classes.getValue("androidx.compose.ui.text.TextStyle")
    val jvm = index.jvmName("androidx.compose.ui.text.TextStyle")
    val visible = textStyle.constructors.filter { it.visibility == Visibility.PUBLIC && !index.isHidden(jvm, it) }
    assertTrue(visible.isNotEmpty())
    val allDefaults = visible.filter { c -> c.valueParameters.all { it.declaresDefaultValue } }
    assertTrue(allDefaults.any { c -> c.valueParameters.size > 20 }, "a TextStyle constructor with every field, all defaulted")
    println("TextStyle constructors: " + visible.map { c -> c.valueParameters.joinToString(",") { it.name + (if (it.declaresDefaultValue) "=" else "") } })
    // Hidden binary-compatibility constructors are told apart (synthetic in the bytecode).
    assertTrue(textStyle.constructors.size > visible.size, "TextStyle keeps hidden constructors")

    // 2. PagerState: currentPage, and animateScrollToPage as a suspend member.
    val pager = survey.stateMembers("androidx.compose.foundation.pager.PagerState")
    assertEquals(GenericSurvey.MemberCat.OBSERVE, pager.first { it.name == "currentPage" }.cat)
    assertEquals(GenericSurvey.MemberCat.COMMAND_AWAIT, pager.first { it.name == "animateScrollToPage" }.cat)
    assertTrue(index.classes.getValue("androidx.compose.foundation.pager.PagerState").functions.first { it.name == "animateScrollToPage" }.isSuspend)

    // 3. DrawScope: drawCircle/drawLine/drawPath members, Brush and Path parameters included.
    val draw = index.classes.getValue("androidx.compose.ui.graphics.drawscope.DrawScope")
    val names = draw.functions.map { it.name }.toSet()
    assertTrue(names.containsAll(listOf("drawCircle", "drawLine", "drawPath", "drawRect", "drawArc", "drawImage")))
    assertTrue(draw.functions.any { f -> f.name == "drawPath" && f.valueParameters.any { survey.typeText(it.type) == "Brush" } })
  }

  @Test fun coverageReport() {
    val report = StringBuilder()
    fun line(s: String = "") = report.appendLine(s)

    // ---- 1. Call records ----
    val packages = listOf(
      "androidx.compose.foundation", "androidx.compose.foundation.layout", "androidx.compose.foundation.lazy",
      "androidx.compose.foundation.lazy.grid", "androidx.compose.foundation.lazy.staggeredgrid", "androidx.compose.foundation.pager",
      "androidx.compose.foundation.text", "androidx.compose.foundation.text.input", "androidx.compose.foundation.selection",
      "androidx.compose.foundation.shape", "androidx.compose.material3", "androidx.compose.animation",
      "androidx.compose.ui.draw", "androidx.compose.ui.graphics", "androidx.compose.ui.text",
    )
    val surface = survey.apiSurface(packages)
    val typeUse = mutableMapOf<String, Int>()
    val catOfType = mutableMapOf<String, GenericSurvey.Cat>()
    val paramCats = mutableMapOf<GenericSurvey.Cat, Int>()
    var modifierParams = 0
    for ((_, f) in surface) {
      for (p in f.valueParameters) {
        val c = survey.category(p.type)
        paramCats.merge(c, 1, Int::plus)
        val t = survey.typeText(p.type)
        // A Modifier parameter is a chain: the existing modifier mechanism, not a value codec.
        if (t == "Modifier") {
          paramCats.merge(c, -1, Int::plus)
          modifierParams++
          continue
        }
        if (c != GenericSurvey.Cat.FUNCTION && c != GenericSurvey.Cat.PRIMITIVE) {
          typeUse.merge(t, 1, Int::plus)
          catOfType[t] = c
        }
      }
    }
    line("# Generic mechanisms: coverage from metadata")
    line()
    line("## 1. Call records")
    line()
    line("API surface: ${surface.size} public, non-hidden composables and Modifier extensions in ${packages.size} packages; ${surface.sumOf { it.second.valueParameters.size }} parameters.")
    line()
    line("| Parameter category | Parameters |")
    line("|---|---|")
    GenericSurvey.Cat.entries.forEach { line("| $it | ${paramCats[it] ?: 0} |") }
    line("| (Modifier chains, not counted above) | $modifierParams |")
    line()
    val byCat = catOfType.entries.groupBy({ it.value }, { it.key })
    line("Distinct non-primitive, non-function parameter types: ${catOfType.size}.")
    for ((cat, types) in byCat.toSortedMap()) {
      line()
      line("- **$cat** (${types.size}): " + types.sortedByDescending { typeUse[it] }.joinToString(", ") { "$it×${typeUse[it]}" })
    }
    // Producers of call-recorded types: how many are fully replayable (every parameter covered).
    val callTypes = catOfType.filterValues { it == GenericSurvey.Cat.CALL }.keys
    var producers = 0
    var replayable = 0
    var lambdaProducers = 0
    val fq = index.classes.keys.associateBy { it.substringAfterLast('.') }
    val seen = mutableSetOf<String>()
    for (t in callTypes) {
      val full = fq[t.substringBefore('<')] ?: continue
      for (p in survey.producers(full)) {
        if (!seen.add(p.id)) continue
        producers++
        val cats = p.params.map { survey.category(it) }
        when {
          cats.all { it.ordinal <= GenericSurvey.Cat.CALL.ordinal } -> replayable++
          GenericSurvey.Cat.FUNCTION in cats -> lambdaProducers++
        }
      }
    }
    line()
    line("Producers (public constructors, factories, constant properties) of the CALL types: $producers; replayable with every argument covered: $replayable; taking a lambda: $lambdaProducers; other uncovered argument: ${producers - replayable - lambdaProducers}.")
    // Property reads on call-recorded values: constructor-backed (could be read back from the record) or computed (error stubs).
    var ctorBacked = 0
    var computed = 0
    for (t in callTypes) {
      val k = index.classes[fq[t.substringBefore('<')] ?: continue] ?: continue
      val ctorParams = k.constructors.flatMap { c -> c.valueParameters.map { it.name } }.toSet()
      for (p in k.properties.filter { it.visibility == Visibility.PUBLIC }) if (p.name in ctorParams) ctorBacked++ else computed++
    }
    line("Public properties of the CALL types: $ctorBacked constructor-backed (readable from the record), $computed computed (error stubs, or host snapshot).")

    // ---- 2. Remote objects ----
    line()
    line("## 2. Remote objects")
    line()
    val states = listOf(
      "androidx.compose.foundation.lazy.LazyListState", "androidx.compose.foundation.lazy.grid.LazyGridState",
      "androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState", "androidx.compose.foundation.ScrollState",
      "androidx.compose.foundation.pager.PagerState", "androidx.compose.foundation.text.input.TextFieldState",
      "androidx.compose.material3.SheetState", "androidx.compose.material3.DrawerState", "androidx.compose.material3.SnackbarHostState",
      "androidx.compose.material3.DatePickerState", "androidx.compose.material3.DateRangePickerState", "androidx.compose.material3.TimePickerState",
      "androidx.compose.material3.TopAppBarState", "androidx.compose.material3.TooltipState", "androidx.compose.material3.SwipeToDismissBoxState",
      "androidx.compose.material3.SliderState", "androidx.compose.material3.RangeSliderState", "androidx.compose.material3.BottomSheetScaffoldState",
      "androidx.compose.foundation.interaction.MutableInteractionSource",
    )
    line("| State | Observed fields | Commands (suspend, awaited) | Commands (Unit) | Sync return (not generic) | Lambda arg (not generic) | Uncovered type |")
    line("|---|---|---|---|---|---|---|")
    val totals = IntArray(6)
    val notGeneric = mutableListOf<String>()
    for (s in states) {
      if (s !in index.classes) {
        line("| ${s.substringAfterLast('.')} | not on the classpath | | | | | |")
        continue
      }
      val m = survey.stateMembers(s)
      val counts = GenericSurvey.MemberCat.entries.map { c -> m.count { it.cat == c } }
      counts.forEachIndexed { i, n -> totals[i] += n }
      line("| ${s.substringAfterLast('.')} | " + counts.joinToString(" | ") + " |")
      m.filter { it.cat.ordinal >= GenericSurvey.MemberCat.SYNC_RETURN.ordinal }.forEach { notGeneric += "${s.substringAfterLast('.')}.${it.detail} (${it.cat})" }
    }
    line("| **total** | " + totals.joinToString(" | ") + " |")
    line()
    line("Members outside the mechanism:")
    notGeneric.forEach { line("- $it") }

    // ---- 3. Recorded interfaces ----
    line()
    line("## 3. Recorded interfaces")
    val recorded = setOf("androidx.compose.ui.graphics.Path")
    val hostRes = setOf("androidx.compose.ui.graphics.ImageBitmap", "androidx.compose.ui.graphics.Canvas", "androidx.compose.ui.graphics.drawscope.DrawContext", "androidx.compose.ui.graphics.layer.GraphicsLayer")
    for (scope in listOf(
      "androidx.compose.ui.graphics.drawscope.DrawScope" to listOf("androidx.compose.ui.graphics.drawscope"),
      "androidx.compose.ui.graphics.drawscope.ContentDrawScope" to listOf("androidx.compose.ui.graphics.drawscope"),
      "androidx.compose.ui.graphics.GraphicsLayerScope" to listOf("androidx.compose.ui.graphics"),
      "androidx.compose.ui.graphics.Path" to listOf("androidx.compose.ui.graphics"),
    )) {
      val members = survey.scopeMembers(scope.first, scope.second, recorded, hostRes)
      line()
      line("### ${scope.first.substringAfterLast('.')} (${members.size} members and extensions)")
      line()
      for ((cat, ms) in members.groupBy { it.cat }.toSortedMap()) {
        line("- **$cat** (${ms.size}): " + ms.joinToString(", ") { it.detail })
      }
    }

    val out = File("build/generic-survey.md")
    out.parentFile.mkdirs()
    out.writeText(report.toString())
    println(report)
    assertTrue(surface.size > 100)
  }
}
