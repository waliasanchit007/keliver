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
package dev.keliver.gradle

/**
 * Pure scanning logic for [ValidateZiplineServiceShapesTask]. Detects
 * the KNOWN_BUGS U11 footgun — `ZiplineService` interface methods with
 * function-typed parameters compile cleanly but the runtime proxy
 * fails to construct on the guest side, making every method call a
 * silent no-op.
 *
 * Lifted from the inline Gradle task that originally shipped in the
 * ServerDrivenUI integration's `shared/build.gradle.kts`. The scanner
 * is intentionally regex-based rather than PSI-based — Keliver's build
 * graph is heavy enough already; this catches the 95% case (any code
 * an adopter would naively write) without dragging in `kotlin-compiler-embeddable`.
 *
 * Edge cases the scanner deliberately does NOT catch:
 * - Function-typed *return* values (e.g. `fun foo(): (Int) -> Int`) —
 *   these technically share the same Zipline-proxy-construction
 *   failure but are extremely rare in practice and noisy to detect.
 * - Function-typed properties on `ZiplineService` interfaces — same.
 * - Type aliases that resolve to function types — true PSI-resolution
 *   territory; explicitly out of scope.
 *
 * If an adopter hits one of these the documentation still applies;
 * the lint just doesn't catch it pre-build.
 */
internal object ZiplineShapeScanner {

  /**
   * Matches `interface <Name> : ZiplineService { … }` and also
   * `interface <Name> : app.cash.zipline.ZiplineService { … }`
   * (fully-qualified reference, used when the adopter chooses not
   * to import `ZiplineService`). Captures the interface name (group 1)
   * so we can attribute findings to a specific declaration.
   *
   * The optional `(?:[\w.]+\.)?` consumes any qualifier before
   * `ZiplineService`. Implementations / extra `: SomethingElse, ZiplineService`
   * forms aren't matched on purpose — interfaces with multiple
   * superinterfaces are rare in this codebase and the regex would
   * get hairy fast.
   */
  private val INTERFACE_HEADER = Regex(
    """interface\s+(\w+)\s*:\s*(?:[\w.]+\.)?ZiplineService\s*\{""",
  )

  /**
   * Matches a function-typed parameter inside a `fun` / `suspend fun`
   * signature. Group 0 is the full match (used in the diagnostic).
   *
   * Tolerated shapes:
   * - `cb: (Args) -> Ret` — vanilla
   * - `cb: (Args) -> Ret?` — nullable return type
   * - `cb: ((Args) -> Ret)` — function type wrapped in extra parens
   * - `cb: ((Args) -> Ret)?` — nullable lambda (the common idiom)
   * - `cb: ((A) -> B, X)` etc. — curried form with one nested arg paren
   *
   * Outer parens around the whole function type are optional
   * (`\(?` / `\)?`) so the regex catches both `T` and `(T)?` forms.
   */
  private val LAMBDA_PARAM = Regex(
    """(\w+)\s*:\s*\(?\s*(?:\([^()]*\)|\([^()]*\([^()]*\)[^()]*\))\s*->\s*\w+\??\s*\)?\??""",
  )

  /**
   * Scan a Kotlin source [content] for ZiplineService interface
   * declarations that have function-typed parameters. Returns one
   * [Finding] per offending parameter.
   */
  fun scan(content: String, sourceLabel: String = "<source>"): List<Finding> {
    val findings = mutableListOf<Finding>()
    INTERFACE_HEADER.findAll(content).forEach { match ->
      val name = match.groupValues[1]
      val bodyStart = match.range.last + 1
      // Walk brace depth to find the matching closing brace — a single
      // regex would break on nested function literals or anonymous
      // object expressions inside the interface body.
      var depth = 1
      var i = bodyStart
      while (i < content.length && depth > 0) {
        when (content[i]) {
          '{' -> depth++
          '}' -> depth--
        }
        i++
      }
      val body = content.substring(bodyStart, i - 1)
      LAMBDA_PARAM.findAll(body).forEach { hit ->
        findings += Finding(
          interfaceName = name,
          parameterSnippet = hit.value.trim(),
          sourceLabel = sourceLabel,
        )
      }
    }
    return findings
  }

  /** A single ZiplineService-with-function-typed-param violation. */
  data class Finding(
    val interfaceName: String,
    val parameterSnippet: String,
    val sourceLabel: String,
  )

  /**
   * Format a non-empty list of [findings] into the multi-line error
   * message thrown by the Gradle task. Includes the
   * KNOWN_BUGS pointer and the canonical workaround.
   */
  fun formatError(findings: List<Finding>): String = buildString {
    appendLine(
      "ZiplineService interface(s) have function-typed parameters " +
        "(Keliver KNOWN_BUGS U11).",
    )
    appendLine(
      "Zipline can only marshal @Serializable values or ZiplineService proxies; raw",
    )
    appendLine(
      "function types compile but the runtime proxy fails to construct on the guest",
    )
    appendLine(
      "side, making every method call a silent no-op.",
    )
    appendLine()
    appendLine(
      "Workaround: declare a callback ZiplineService (a separate interface " +
        "with one method,",
    )
    appendLine(
      "e.g. `interface MyCallback : ZiplineService { fun onResult(...) }`) and pass " +
        "that instead.",
    )
    appendLine(
      "The guest wraps the user's lambda in an anonymous impl; the host calls " +
        "onResult(...)",
    )
    appendLine("then close(). Reference shape: ServerDrivenUI `SnackbarResultCallback`.")
    appendLine()
    appendLine("Found:")
    findings.forEach { f ->
      appendLine("  - ${f.sourceLabel}: `${f.interfaceName}` has parameter `${f.parameterSnippet}`")
    }
  }
}
