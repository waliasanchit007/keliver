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
package dev.keliver.compose.core.composeui

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler

/**
 * G2 (MS4-43, review-337 L1): a member of a remote object that cannot cross is a compile error with a
 * message that says why, in the forms a screen writes: a property, a call, a call with a trailing
 * lambda (`state.scroll { }`), and a call with arguments. The snippets are compiled against the guest.
 */
class RemoteStubMessageTest {
  private fun compile(source: String): Pair<ExitCode, String> {
    val dir = Files.createTempDirectory("keliver-stub")
    val file = dir.resolve("Snippet.kt").also { Files.writeString(it, source) }
    val out = ByteArrayOutputStream()
    val code = K2JVMCompiler().exec(
      PrintStream(out),
      file.toString(),
      "-d",
      dir.resolve("out").toString(),
      "-classpath",
      System.getProperty("java.class.path"),
      "-no-stdlib",
      "-no-reflect",
    )
    return code to out.toString()
  }

  @Test fun aMemberThatCrossesCompiles() {
    val (code, text) = compile(
      """
      import dev.keliver.compose.foundation.pager.PagerState
      fun ok(s: PagerState) {
        s.requestScrollToPage(1)
        val page: Int = s.currentPage
      }
      """.trimIndent(),
    )
    assertEquals(ExitCode.OK, code, text)
  }

  @Test fun aMemberThatDoesNotCrossFailsWithTheKeliverMessageWhateverTheCallLooksLike() {
    val (code, text) = compile(
      """
      import dev.keliver.compose.foundation.pager.PagerState
      fun bad(s: PagerState) {
        s.scroll { }
        s.scroll(1, 2)
        s.dispatchRawDelta(1f)
        val info = s.layoutInfo
      }
      """.trimIndent(),
    )
    assertEquals(ExitCode.COMPILATION_ERROR, code, text)
    // `scroll { }` and `scroll(1, 2)`: both reach the stub, so both show the message about the argument type.
    assertTrue(Regex("""Snippet\.kt:3:.*Keliver: `PagerState\.scroll` takes an argument type \(`scrollPriority`: MutatePriority\)""").containsMatchIn(text), text)
    assertTrue(Regex("""Snippet\.kt:4:.*Keliver: `PagerState\.scroll` takes an argument type""").containsMatchIn(text), text)
    assertTrue("Keliver: `PagerState.dispatchRawDelta` returns a value synchronously" in text, text)
    assertTrue("Keliver: `PagerState.layoutInfo` (PagerLayoutInfo) is a type that does not cross to the guest" in text, text)
  }
}
