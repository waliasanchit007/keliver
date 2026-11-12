/*
 * Copyright (C) 2026 Square, Inc.
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
package dev.keliver.portal.compose.render

import dev.keliver.compose.gatea.composeui.samples.guest.SampleCard
import dev.keliver.portal.document.DocJson
import dev.keliver.portal.document.DocNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ED0's canvas criterion, on the editor's own target (wasmJs) and on the JVM: Gate A's sample
 * screen, read by portal-ingest into a document ([SAMPLE_CARD_DOCUMENT], 0 RawCode) and drawn by
 * the canvas through the generated stubs, sends the host exactly the protocol changes the compiled
 * screen sends. The host renderers draw from those changes alone, so the canvas draws what a
 * device draws ([CanvasPixelTest] checks the pixels on the JVM).
 */
class CanvasWireTest {
  private val title = "A long item title that will not fit on one line"
  private val quantity = "12 units"
  private val tags = listOf("Fragile", "Bulk", "In stock", "Ships today", "Gift wrap", "Returnable")

  @Test fun theCanvasSendsTheCompiledScreensChanges() {
    val compiled = GuestWire { SampleCard(title, quantity, tags) {} }
    val problems = mutableListOf<String>()
    val canvas = GuestWire { ComposeCanvas(sampleCardDocument(), env(problems)) }
    val expected = compiled.take()
    val actual = canvas.take()
    assertEquals(emptyList(), problems, "what the canvas did not draw")
    assertTrue(expected.length > 1000, "the compiled screen sends its tree")
    assertEquals(expected, actual, "the canvas's protocol changes")
    compiled.cancel()
    canvas.cancel()
  }

  @Test fun aChangedBindingRecomposesLikeTheCompiledScreen() {
    // Fewer tags: the Repeat's rows go, as the compiled loop's do.
    val few = tags.take(2)
    val compiled = GuestWire { SampleCard(title, quantity, few) {} }
    val problems = mutableListOf<String>()
    val canvas = GuestWire { ComposeCanvas(sampleCardDocument(), env(problems, few)) }
    assertEquals(compiled.take(), canvas.take())
    assertEquals(emptyList(), problems)
    compiled.cancel()
    canvas.cancel()
  }

  private fun env(problems: MutableList<String>, tagList: List<String> = tags) = CanvasEnv(
    bind = { mapOf("title" to title, "quantity" to quantity, "tags" to tagList)[it] },
    problem = { problems += it },
  )
}

internal fun sampleCardDocument(): DocNode = DocJson.decodeFromString(DocNode.serializer(), SAMPLE_CARD_DOCUMENT)
