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
package dev.keliver.tooling.codegen

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.convert
import com.github.ajalt.clikt.parameters.arguments.help
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.help
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.options.split
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.path
import dev.keliver.tooling.schema.FqType
import dev.keliver.tooling.schema.ProtocolSchemaSet
import java.io.File
import java.net.URLClassLoader
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * `frame-rules`: writes a schema's review list (`<Schema>FrameRules.review.txt`, the values a `Shell`
 * frame keeps) or checks the checked-in one (docs/SHOW_BEFORE_COMPUTE.md 2.4). The check prints
 * the list as a warning; with `--strict` it fails until the checked-in list matches, so every value
 * on it is a reviewed choice.
 */
internal class FrameRulesCommand : CliktCommand(name = "frame-rules") {
  override fun help(context: Context) =
    "Write a schema's frame-rules review list, or check the checked-in one"

  private val review by option("--review")
    .path()
    .required()
    .help("The review list file (checked in)")

  private val mode by option("--mode")
    .choice("check", "generate")
    .default("check")

  private val strict by option("--strict")
    .flag()
    .help("Fail when the checked-in review list differs from the schema's")

  private val fixWith by option("--fix-with")
    .default("frame-rules --mode generate")
    .help("The command that rewrites the review list (named in messages)")

  private val classpath by option("-cp", "--class-path")
    .file()
    .split(File.pathSeparator)
    .required()

  private val schemaType by argument("schema")
    .help("Fully-qualified class name for the @Schema-annotated interface")
    .convert { FqType.bestGuess(it) }

  override fun run() {
    val classLoader = URLClassLoader(classpath.map { it.toURI().toURL() }.toTypedArray())
    val schemaSet = ProtocolSchemaSet.load(schemaType, classLoader)
    when (mode) {
      // A schema set without frame rules has no review list: no file is written for it.
      "generate" -> if (schemaSet.declaresFrameRules()) review.writeText(frameRulesReviewText(schemaSet))
      else -> {
        val recorded = if (review.exists()) parseFrameRulesReview(review.readText()) else null
        val report = checkFrameRulesReview(schemaSet, recorded, strict, review.toString(), fixWith)
        if (report.failed) throw PrintMessage(report.message, statusCode = 1, printError = true)
        if (report.message.isNotEmpty()) echo(report.message)
      }
    }
  }
}

/** What a check of the review list says, and whether it fails the build. */
internal class FrameRulesReviewReport(val message: String, val failed: Boolean)

/**
 * Checks [schema]'s review list against the [recorded] entries of the checked-in file (null when
 * there is none). Without [strict] it never fails: it prints the list as a warning, and says when the
 * checked-in file is out of date. With [strict] it fails while the checked-in list differs, naming
 * each value not yet reviewed and each entry no longer on the list.
 */
internal fun checkFrameRulesReview(
  schemaSet: ProtocolSchemaSet,
  recorded: List<String>?,
  strict: Boolean,
  file: String,
  fixWith: String,
): FrameRulesReviewReport {
  val expected = if (schemaSet.declaresFrameRules()) frameRulesReview(schemaSet) else emptyList()
  val added = expected - recorded.orEmpty().toSet()
  val removed = recorded.orEmpty() - expected.toSet()
  val name = "${schemaSet.schema.type.flatName}FrameRules"
  // A schema set without frame rules has no list, so no file is the right state for it.
  val upToDate = (recorded != null || expected.isEmpty()) && added.isEmpty() && removed.isEmpty()
  if (strict) {
    if (upToDate) return FrameRulesReviewReport("", failed = false)
    val message = buildString {
      append("e: $name (strict): the review list $file ")
      appendLine(if (recorded == null) "does not exist." else "is out of date.")
      if (added.isNotEmpty()) {
        appendLine("  Not reviewed: a new replayable member, or a value a Shell frame would keep as it is:")
        for (entry in added) appendLine("    $entry")
        appendLine(
          "  Classify each in the schema (@NotData for layout or style, @NeverCached for a value that must " +
            "not be stored, or leave its widget or modifier off @Replayable), or accept it as reviewed with " +
            "$fixWith.",
        )
      }
      if (removed.isNotEmpty()) {
        appendLine("  No longer on the list (run $fixWith):")
        for (entry in removed) appendLine("    $entry")
      }
    }
    return FrameRulesReviewReport(message.trimEnd(), failed = true)
  }
  val message = buildString {
    val values = expected.filter { it.substringAfter(": ").substringBefore(':').contains('.') }
    if (values.isNotEmpty()) {
      appendLine(
        "w: $name: ${values.size} value(s) of replayable widgets and modifiers are neither @NotData nor " +
          "@NeverCached, so a Shell frame keeps them as they are:",
      )
      for (entry in values) appendLine("    $entry")
    }
    if (!upToDate && (recorded != null || expected.isNotEmpty())) {
      appendLine("w: $name: the review list $file is ${if (recorded == null) "missing" else "out of date"} (run $fixWith).")
    }
  }
  return FrameRulesReviewReport(message.trimEnd(), failed = false)
}
