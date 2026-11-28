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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * MS4-43 (G2): the remote-object emitter on the real Compose metadata and sources. The product's
 * own generation (`core.json`'s `remoteObjects`) is checked by `checkComposeApi`; this tests the
 * rules, and writes `build/remote-objects.md`, the table the PR body quotes.
 */
class RemoteObjectEmitTest {
  private val classpath by lazy {
    (System.getProperty("keliver.composeApi") ?: error("the test task passes keliver.composeApi")).split(File.pathSeparator).map(::File)
  }
  private val sources by lazy {
    (System.getProperty("keliver.composeSources") ?: error("the test task passes keliver.composeSources")).split(File.pathSeparator).map(::File)
  }
  private val index by lazy { GenericIndex(classpath) }
  private val src by lazy { SourceReader(sources) }

  private fun config(group: String = "t") = Config(group, "dev.keliver.compose.t", "T", emptyList(), emptyList(), emptyList(), emptyMap(), emptyList(), emptyList())

  private fun emit(specs: List<String>, lock: Lock = Lock("t")): Pair<RemoteObjectEmitter.Result, Lock> {
    val builder = LockBuilder(lock)
    val result = RemoteObjectEmitter(index, src, config(), builder).generate(specs)
    return result to builder.build()
  }

  private val pager = "androidx.compose.foundation.pager.PagerState|initialPage=currentPage,initialPageOffsetFraction=currentPageOffsetFraction"

  @Test fun pagerStateProxyAndDispatcher() {
    val (result, lock) = emit(listOf(pager))
    val guest = result.guest.getValue("dev/keliver/compose/foundation/pager/GeneratedRemote.kt")
    val host = result.host.getValue("dev/keliver/compose/t/composeui/GeneratedRemote.kt")
    val entry = lock.remotes.getValue("androidx.compose.foundation.pager.PagerState")
    val currentPage = entry.params.getValue("currentPage")
    // A field is observed from its first read, by tag.
    assertTrue("public val currentPage: kotlin.Int get() = remote.read($currentPage)?.jsonPrimitive?.intOrNull ?: 0" in guest)
    // Awaited and unawaited commands; a source default that is a literal is copied, one that is not is left to Compose.
    assertTrue("public suspend fun animateScrollToPage(page: kotlin.Int, pageOffsetFraction: kotlin.Float = 0f)" in guest)
    assertTrue("public fun requestScrollToPage(page: kotlin.Int, pageOffsetFraction: kotlin.Float = 0.0f)" in guest)
    assertTrue("Left to Compose's defaults: animationSpec" in guest)
    // What cannot cross is a compile-time error that says why.
    assertTrue("`PagerState.dispatchRawDelta` returns a value synchronously" in guest)
    assertTrue("`PagerState.scroll` takes an argument type (`scrollPriority`" in guest)
    assertTrue("`PagerState.layoutInfo` (PagerLayoutInfo) is a type that does not cross" in guest)
    // The factory, its saver (process death), and the host's real factory call.
    assertTrue("rememberSaveable(saver = RemoteRestore.Saver)" in guest)
    // The factory seeds the fields from the restored (or initial) value, clears what was saved, and observes nothing at creation (M5, L5).
    assertTrue("r.seed($currentPage, restore.saved?.get(\"initialPage\")" in guest)
    assertTrue("restore.saved = null" in guest)
    assertTrue("r.read(" !in guest.substringAfter("public fun rememberPagerState"), "no field is observed from creation")
    assertTrue("put(\"initialPage\", peek($currentPage)" in guest, "a re-created host starts at the position the guest last knew")
    assertTrue("androidx.compose.foundation.pager.rememberPagerState(" in host)
    // The host keys by the object's id (M2), ignores an unknown field and fails an unknown command (M3).
    assertTrue("-> key(wire.id) {" in host)
    assertTrue("else -> null" in host)
    assertTrue("error(\"unknown" !in host)
    // A factory argument is read without throwing: a missing or mistyped one is the factory's default.
    assertTrue("(wire.init[\"initialPage\"] as? JsonPrimitive)?.intOrNull ?: 0" in host)
    assertTrue("\"state.animateScrollToPage(page = args.getValue(\"page\").jsonPrimitive.int" !in host)
    assertTrue("state.animateScrollToPage(page = args.getValue(\"page\").jsonPrimitive.int, pageOffsetFraction = args.getValue(\"pageOffsetFraction\").jsonPrimitive.float)" in host)
  }

  @Test fun theLockIsAppendOnlyAndTheWireShapeIsInIt() {
    val (_, first) = emit(listOf(pager))
    val entry = first.remotes.getValue("androidx.compose.foundation.pager.PagerState")
    assertEquals(1, entry.tag)
    assertEquals(1, first.level)
    assertTrue(entry.wire!!.keys.containsAll(entry.params.keys))
    // The same generation again changes nothing.
    val (_, again) = emit(listOf(pager), first)
    assertEquals(first, again)
    assertEquals(emptyList(), checkAppendOnly(first, again))
    // A second class appends the next tag and level, and keeps the first one's.
    val (_, both) = emit(listOf(pager, "androidx.compose.material3.SliderState#rememberSliderState"), first)
    assertEquals(first.remotes.getValue("androidx.compose.foundation.pager.PagerState"), both.remotes.getValue("androidx.compose.foundation.pager.PagerState"))
    assertEquals(2, both.remotes.getValue("androidx.compose.material3.SliderState").tag)
    assertEquals(2, both.level)
    assertEquals(emptyList(), checkAppendOnly(first, both))
    // A class dropped from the config, or a member's tag changed, is refused.
    assertTrue(checkAppendOnly(both, first).isNotEmpty())
    val moved = first.copy(remotes = mapOf(entry.let { "androidx.compose.foundation.pager.PagerState" to it.copy(params = it.params + ("currentPage" to 99)) }))
    assertTrue(checkAppendOnly(first, moved).any { "changed a parameter tag" in it })
  }

  private val pagerFq = "androidx.compose.foundation.pager.PagerState"

  @Test fun aMemberThatStopsCrossingFailsTheCheck() {
    val (_, first) = emit(listOf(pager))
    val entry = first.remotes.getValue(pagerFq)
    val members = entry.wire!!.keys.toList()
    // The same members and types: nothing to report.
    assertEquals(emptyList(), checkAppendOnly(first, LockBuilder(first).also { it.remote(pagerFq, members, entry.types) }.build()))
    // One member no longer crosses (Compose changed its type): the wire shrank, which the check refuses. Its tag stays reserved.
    val builder = LockBuilder(first)
    val shrunk = builder.remote(pagerFq, members - "currentPage", entry.types - "currentPage")
    assertEquals(entry.params.getValue("currentPage"), shrunk.params.getValue("currentPage"))
    assertTrue(checkAppendOnly(first, builder.build()).any { "stopped sending a parameter" in it })
  }

  @Test fun theFieldTypesAndTheFactorysArgumentsAreLocked() {
    val (_, first) = emit(listOf(pager))
    val entry = first.remotes.getValue(pagerFq)
    assertEquals("kotlin.Int", entry.types.getValue("currentPage"))
    assertEquals("kotlin.Float", entry.types.getValue("currentPageOffsetFraction"))
    assertEquals("kotlin.Int", entry.types.getValue("@init.initialPage"))
    assertEquals("()->kotlin.Int", entry.types.getValue("@init.pageCount"), "a lambda the guest samples")
    assertTrue(entry.types.keys.any { it.startsWith("animateScrollToPage(") }, "a command's result type: ${entry.types.keys}")
    val members = entry.wire!!.keys.toList()
    fun changed(types: Map<String, String>): List<String> {
      val b = LockBuilder(first)
      b.remote(pagerFq, members, types)
      return checkAppendOnly(first, b.build())
    }
    // A field's type, a factory argument's type, and a dropped factory argument each change the wire.
    assertTrue(changed(entry.types + ("currentPage" to "kotlin.Long")).any { "changed the type" in it })
    assertTrue(changed(entry.types + ("@init.initialPage" to "kotlin.Long")).any { "changed the type" in it })
    assertTrue(changed(entry.types - "@init.pageCount").any { "dropped one" in it })
    // A new factory argument is an append (a higher level), not a failure.
    val grown = LockBuilder(first).also { it.remote(pagerFq, members, entry.types + ("@init.extra" to "kotlin.Int")) }.build()
    assertEquals(emptyList(), checkAppendOnly(first, grown))
    assertEquals(first.level + 1, grown.level)
  }

  @Test fun aSettablePrimitiveIsAVarOnTheGuestAndASetCommandOnTheHost() {
    val (result, lock) = emit(listOf("androidx.compose.material3.TimePickerState|initialHour=hour,initialMinute=minute"))
    val guest = result.guest.getValue("dev/keliver/compose/material3/GeneratedRemote.kt")
    val host = result.host.getValue("dev/keliver/compose/t/composeui/GeneratedRemote.kt")
    val entry = lock.remotes.getValue("androidx.compose.material3.TimePickerState")
    val hour = entry.params.getValue("hour")
    val setHour = entry.params.getValue("hour=")
    assertTrue("public var hour: kotlin.Int" in guest, "a var, not a val")
    assertTrue("remote.assign($hour, kotlinx.serialization.json.JsonPrimitive(value), $setHour," in guest)
    assertTrue("$setHour -> {\n      state.hour = args.getValue(\"value\").jsonPrimitive.int" in host)
    assertEquals("kotlin.Int", entry.types.getValue("hour="))
    // A property that is only a val stays a val.
    val (pagerResult, _) = emit(listOf(pager))
    assertTrue("public val currentPage: kotlin.Int" in pagerResult.guest.getValue("dev/keliver/compose/foundation/pager/GeneratedRemote.kt"))
  }

  @Test fun aStubMessageNamesTheTypeAndTheStubTakesATrailingLambda() {
    val (result, _) = emit(listOf("androidx.compose.material3.SliderState#rememberSliderState"))
    val guest = result.guest.getValue("dev/keliver/compose/material3/GeneratedRemote.kt")
    assertTrue("(Function0<Unit>?) is a type that does not cross" in guest, "no half-cut generic like `Unit>?`")
    assertTrue("(Function1<Float, Unit>?) is a type that does not cross" in guest)
    assertTrue("(ClosedFloatingPointRange<Float>) is a type that does not cross" in guest)
    assertTrue(Regex("""\(\w+>[?)]""").find(guest) == null, "no message starts a type at a `>`")
    // `state.drag { … }` finds the stub, so the Keliver message shows.
    assertTrue("public fun drag(vararg args: Any?, block: Any? = null): Nothing" in guest)
  }

  @Test fun aFactoryWhoseArgumentIsAnEnumWithNoDefaultSaysSoAndWaitsForG1() {
    val e = kotlin.runCatching { emit(listOf("androidx.compose.material3.DrawerState")) }.exceptionOrNull()
    assertTrue("an argument type (`initialValue`: DrawerValue)" in e?.message.orEmpty(), "$e")
  }

  @Test fun theDateRangePickerRestoresItsSelection() {
    val specs = loadConfig(File("core.json")).remoteObjects
    val spec = specs.single { it.startsWith("androidx.compose.material3.DateRangePickerState") }
    val (result, _) = emit(listOf(spec))
    val guest = result.guest.getValue("dev/keliver/compose/material3/GeneratedRemote.kt")
    assertTrue("restore.saved?.get(\"initialSelectedStartDateMillis\")" in guest)
    assertTrue("restore.saved?.get(\"initialSelectedEndDateMillis\")" in guest)
  }

  @Test fun aStateClassWithNoCrossingFactoryIsRefusedWithAClearMessage() {
    val e = kotlin.runCatching { emit(listOf("androidx.compose.foundation.pager.PagerState#rememberNoSuchThing")) }.exceptionOrNull()
    assertTrue(e?.message?.contains("Name the factory") == true, "$e")
  }

  @Test fun coverageOfTheNineteenStateClasses() {
    val survey = GenericSurvey(index)
    val builder = LockBuilder(Lock("t"))
    val emitter = RemoteObjectEmitter(index, src, config(), builder)
    val report = StringBuilder("| State | Observed | Awaited | Not awaited | Setters | Error stubs |\n|---|---|---|---|---|---|\n")
    val totals = IntArray(5)
    val stubs = mutableListOf<String>()
    fun counts(members: List<RemoteObjectEmitter.Member>) = intArrayOf(
      members.count { it.kind == RemoteObjectEmitter.Member.Kind.OBSERVED },
      members.count { it.kind == RemoteObjectEmitter.Member.Kind.AWAITED },
      members.count { it.kind == RemoteObjectEmitter.Member.Kind.UNAWAITED },
      members.count { it.kind == RemoteObjectEmitter.Member.Kind.SETTER },
      members.count { it.kind == RemoteObjectEmitter.Member.Kind.STUB },
    )
    for (fq in STATES) {
      val members = emitter.membersOf(fq)
      val c = counts(members)
      c.forEachIndexed { i, n -> totals[i] += n }
      report.append("| ${fq.substringAfterLast('.')} | ${c.joinToString(" | ")} |\n")
      members.filter { it.kind == RemoteObjectEmitter.Member.Kind.STUB }.forEach { stubs += "${fq.substringAfterLast('.')}: ${it.detail}" }
      // The survey (G0) classes a member of a call-recorded type as crossing; this emitter, until G1, as a stub.
      val surveyed = survey.stateMembers(fq)
      assertTrue(surveyed.isNotEmpty(), fq)
    }
    report.append("| **classified, 19 classes** | ${totals.joinToString(" | ")} |\n\n")
    // The classes `core.json` generates today: the ones with a factory whose arguments cross (the rest wait for G1).
    val specs = loadConfig(File("core.json")).remoteObjects
    val generated = RemoteObjectEmitter(index, src, config(), LockBuilder(Lock("t"))).generate(specs).classes
    val generatedTotals = IntArray(5)
    for (c in generated) counts(c.members).forEachIndexed { i, n -> generatedTotals[i] += n }
    report.append("| **generated, ${generated.size} classes** | ${generatedTotals.joinToString(" | ")} |\n\nError stubs:\n")
    stubs.forEach { report.append("- $it\n") }
    File("build/remote-objects.md").also { it.parentFile.mkdirs() }.writeText(report.toString())
    println(report)
    // The numbers the PR and docs/MS4_PLAN.md's MS4-43 row quote: observed, awaited, not awaited, setters, error stubs.
    assertEquals(19, STATES.size)
    assertEquals(12, generated.size)
    assertEquals(listOf(71, 18, 8, 13, 71), totals.toList(), "classified over the 19 classes")
    assertEquals(listOf(52, 12, 7, 13, 42), generatedTotals.toList(), "generated over the 12 classes")
    // The seven classes that are not generated: LazyListState and ScrollState stay hand-written (docs/MS4_PLAN.md);
    // TextFieldState is hand-written (editing is a lambda); DrawerState (an enum argument with no default),
    // SnackbarHostState (no `remember` factory) and BottomSheetScaffoldState and MutableInteractionSource wait for G1.
    val waiting = STATES.filter { fq -> generated.none { it.fq == fq } }.map { it.substringAfterLast('.') }
    assertEquals(
      listOf("LazyListState", "ScrollState", "TextFieldState", "DrawerState", "SnackbarHostState", "BottomSheetScaffoldState", "MutableInteractionSource"),
      waiting,
    )
  }

  companion object {
    val STATES = listOf(
      "androidx.compose.foundation.lazy.LazyListState", "androidx.compose.foundation.lazy.grid.LazyGridState",
      "androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState", "androidx.compose.foundation.ScrollState",
      "androidx.compose.foundation.pager.PagerState", "androidx.compose.foundation.text.input.TextFieldState",
      "androidx.compose.material3.SheetState", "androidx.compose.material3.DrawerState", "androidx.compose.material3.SnackbarHostState",
      "androidx.compose.material3.DatePickerState", "androidx.compose.material3.DateRangePickerState", "androidx.compose.material3.TimePickerState",
      "androidx.compose.material3.TopAppBarState", "androidx.compose.material3.TooltipState", "androidx.compose.material3.SwipeToDismissBoxState",
      "androidx.compose.material3.SliderState", "androidx.compose.material3.RangeSliderState", "androidx.compose.material3.BottomSheetScaffoldState",
      "androidx.compose.foundation.interaction.MutableInteractionSource",
    )
  }
}
