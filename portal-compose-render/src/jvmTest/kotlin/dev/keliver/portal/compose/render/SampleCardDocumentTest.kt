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

import dev.keliver.portal.document.DocJson
import dev.keliver.portal.document.DocNode
import dev.keliver.portal.ingest.ComposeCatalog
import dev.keliver.portal.ingest.ComposeIngest
import dev.keliver.portal.ingest.Recognizer
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json

/**
 * The canvas tests' document is what portal-ingest reads from Gate A's sample screen with ED0 on:
 * this test reads the sample again and fails when the checked-in golden ([SAMPLE_CARD_DOCUMENT])
 * is not that reading (`-Ped0.updateGolden=true` rewrites it).
 */
class SampleCardDocumentTest {
  @Test fun theGoldenDocumentIsTheSampleAsIngested() {
    val root = ingestSampleCard()
    val json = Json(DocJson) { prettyPrint = true }.encodeToString(DocNode.serializer(), root)
    val text = buildString {
      append("// Written by SampleCardDocumentTest (jvmTest) from Gate A's sample through portal-ingest's ED0 flag. Do not edit:\n")
      append("// run :portal-compose-render:jvmTest -Ped0.updateGolden=true and review the diff.\n")
      append("package dev.keliver.portal.compose.render\n\n")
      append("internal const val SAMPLE_CARD_DOCUMENT: String = \"\"\"")
      append(json.replace("$", "\${'$'}"))
      append("\"\"\"\n")
    }
    val golden = File(System.getProperty("dev.keliver.portal.ed0.golden"))
    if (System.getProperty("dev.keliver.portal.ed0.updateGolden") == "true") golden.writeText(text)
    assertEquals(text, golden.readText(), "the golden document is stale: run with -Ped0.updateGolden=true")
  }
}

/** Gate A's sample screen read by portal-ingest with ED0 on (as a component: it takes parameters). */
internal fun ingestSampleCard(): DocNode {
  val catalog = ComposeCatalog.load(listOf(File(System.getProperty("dev.keliver.portal.ed0.editorJson"))))
  val source = File(System.getProperty("dev.keliver.portal.ed0.sample")).readText()
  val rec = ComposeIngest.with(catalog) { Recognizer.recognize("SampleCard.kt", source, asComponent = true) }
    ?: error("the sample did not read as a component")
  check(rec.rawReasons.isEmpty()) { "RawCode in the sample: ${rec.rawReasons}" }
  return rec.root
}
