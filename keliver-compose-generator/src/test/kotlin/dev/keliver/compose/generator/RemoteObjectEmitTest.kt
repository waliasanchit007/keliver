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
    assertTrue("restore.saved?.get(\"initialPage\")" in guest)
    assertTrue("androidx.compose.foundation.pager.rememberPagerState(" in host)
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

  @Test fun aStateClassWithNoCrossingFactoryIsRefusedWithAClearMessage() {
    val e = kotlin.runCatching { emit(listOf("androidx.compose.foundation.pager.PagerState#rememberNoSuchThing")) }.exceptionOrNull()
    assertTrue(e?.message?.contains("Name the factory") == true, "$e")
  }

  @Test fun coverageOfTheNineteenStateClasses() {
    val survey = GenericSurvey(index)
    val builder = LockBuilder(Lock("t"))
    val emitter = RemoteObjectEmitter(index, src, config(), builder)
    val report = StringBuilder("| State | Observed | Awaited | Not awaited | Error stubs |\n|---|---|---|---|---|\n")
    val totals = IntArray(4)
    val stubs = mutableListOf<String>()
    for (fq in STATES) {
      val members = emitter.membersOf(fq)
      val c = intArrayOf(
        members.count { it.kind == RemoteObjectEmitter.Member.Kind.OBSERVED },
        members.count { it.kind == RemoteObjectEmitter.Member.Kind.AWAITED },
        members.count { it.kind == RemoteObjectEmitter.Member.Kind.UNAWAITED },
        members.count { it.kind == RemoteObjectEmitter.Member.Kind.STUB },
      )
      c.forEachIndexed { i, n -> totals[i] += n }
      report.append("| ${fq.substringAfterLast('.')} | ${c.joinToString(" | ")} |\n")
      members.filter { it.kind == RemoteObjectEmitter.Member.Kind.STUB }.forEach { stubs += "${fq.substringAfterLast('.')}: ${it.detail}" }
      // The survey (G0) classes a member of a call-recorded type as crossing; this emitter, until G1, as a stub.
      val surveyed = survey.stateMembers(fq)
      assertTrue(surveyed.isNotEmpty(), fq)
    }
    report.append("| **total** | ${totals.joinToString(" | ")} |\n\nError stubs:\n")
    stubs.forEach { report.append("- $it\n") }
    File("build/remote-objects.md").also { it.parentFile.mkdirs() }.writeText(report.toString())
    println(report)
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
