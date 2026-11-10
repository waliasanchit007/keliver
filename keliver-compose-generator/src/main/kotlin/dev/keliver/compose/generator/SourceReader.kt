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
// Kotlin 2.4 marks KotlinCoreEnvironment's API for rework (opt-in); as in portal-ingest's PsiEnv.
@file:OptIn(CoreEnvironmentDeprecation::class)

package dev.keliver.compose.generator

import java.io.File
import java.util.zip.ZipFile
import org.jetbrains.kotlin.CoreEnvironmentDeprecation
import org.jetbrains.kotlin.cli.create
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType

/**
 * Reads the sources jars (docs/COMPOSE_GENERATOR_DESIGN.md §1): each parameter's default
 * expression as written, whether the function is `@Composable`, its KDoc, and the file's imports
 * (so a copied default expression can be imported on both sides).
 */
class SourceReader(sourceJars: List<File>) {
  private val factory: KtPsiFactory by lazy {
    val env = KotlinCoreEnvironment.createForProduction(
      Disposer.newDisposable(),
      CompilerConfiguration.create(),
      EnvironmentConfigFiles.JVM_CONFIG_FILES,
    )
    KtPsiFactory(env.project)
  }

  /** Source entries by package path, common source sets first. */
  private val byPackage: Map<String, List<Pair<ZipFile, String>>> = sourceJars.flatMap { jar ->
    val zip = ZipFile(jar)
    zip.entries().asSequence().filter { it.name.endsWith(".kt") }.map { zip to it.name }.toList()
  }.groupBy { (_, name) -> name.substringAfter('/').substringBeforeLast('/', "") }
    .mapValues { (_, list) -> list.sortedBy { (_, n) -> if (n.startsWith("commonMain/")) 0 else 1 } }

  private val parsed = mutableMapOf<String, KtFile>()

  fun complete(decl: DeclInfo) {
    val files = byPackage[decl.pkg.replace('.', '/')] ?: error("${decl.spec}: no sources for ${decl.pkg}")
    for ((zip, name) in files) {
      val file = parsed.getOrPut(name) {
        factory.createFile(name.substringAfterLast('/'), zip.getInputStream(zip.getEntry(name)).use { it.readBytes().decodeToString() })
      }
      val fn = file.collectDescendantsOfType<KtNamedFunction>().firstOrNull { f ->
        f.name == decl.name &&
          f.valueParameters.map { it.name } == decl.params.map { it.name } &&
          f.valueParameters.zip(decl.params).all { (kp, p) -> kp.typeReference?.text?.let { sameType(it, p.type) } ?: true } &&
          (decl.owner == null && f.parent is KtFile || decl.owner != null && (f.parent.parent as? KtClass)?.name == decl.owner) &&
          (decl.receiver == null || f.receiverTypeReference != null)
      } ?: continue
      decl.isComposable = fn.annotationEntries.any { it.shortName?.asString() == "Composable" }
      decl.kdoc = fn.docComment?.getDefaultSection()?.getContent()?.trim()?.let(::firstSentence)
      decl.params.zip(fn.valueParameters).forEach { (p, kp) -> p.defaultText = kp.defaultValue?.text?.replace(Regex("\\s+"), " ") }
      decl.imports = file.importDirectives.mapNotNull { d ->
        val fq = d.importedFqName?.asString() ?: return@mapNotNull null
        (d.aliasName ?: fq.substringAfterLast('.')) to fq
      }.toMap()
      decl.sourcePath = name
      return
    }
    error("${decl.spec}: not found in the sources")
  }
}

internal fun firstSentence(text: String): String {
  val flat = text.replace(Regex("\\s+"), " ")
  val end = flat.indexOf(". ")
  return if (end < 0) flat.removeSuffix(".") + "." else flat.substring(0, end + 1)
}

/** Whether a source type text names [t] (by simple name, ignoring annotations and nullability). */
internal fun sameType(text: String, t: TypeRef): Boolean {
  val clean = text.replace(Regex("@[A-Za-z.]+(\\([^)]*\\))?\\s*"), "").trim().removeSuffix("?").trim()
  if (t.isFunction) return "->" in clean
  return clean.substringBefore('<').substringAfterLast('.') == t.simpleName || clean.startsWith("(")
}
