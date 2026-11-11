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
package dev.keliver.compose.generator

import java.io.File
import kotlin.system.exitProcess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

fun loadConfig(file: File): Config {
  val o = Json.parseToJsonElement(file.readText()).jsonObject
  fun strings(key: String) = o[key]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
  return Config(
    group = o.getValue("group").jsonPrimitive.content,
    schemaPackage = o.getValue("schemaPackage").jsonPrimitive.content,
    schemaName = o.getValue("schemaName").jsonPrimitive.content,
    dependencies = o["dependencies"]?.jsonArray?.map {
      it.jsonObject.getValue("tag").jsonPrimitive.int to it.jsonObject.getValue("type").jsonPrimitive.content
    }.orEmpty(),
    declarations = strings("declarations"),
    errors = o["errors"]?.jsonArray?.map {
      it.jsonObject.getValue("spec").jsonPrimitive.content to it.jsonObject.getValue("message").jsonPrimitive.content
    }.orEmpty(),
    typeMessages = o["typeMessages"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content }.orEmpty(),
    icons = strings("icons"),
    extras = strings("extras"),
  )
}

/** Everything one generation writes: relative path under an output root → text. */
class Output(
  val schema: Map<String, String>,
  val guest: Map<String, String>,
  val host: Map<String, String>,
  val values: Map<String, String>,
  val editor: String,
  val lock: Lock,
)

fun generate(config: Config, oldLock: Lock, classpath: List<File>, sources: List<File>): Output {
  val api = ApiReader(classpath)
  val src = SourceReader(sources)
  val decls = config.declarations.map { api.find(it).also(src::complete) }
  val errorDecls = config.errors.map { (spec, message) -> api.find(spec).also(src::complete) to message }
  val defaults = DefaultsMirror(api, src, classpath, config.typeMessages)
  // Every *Defaults member a configured default names (this also registers the factory-made types),
  // then what those factories' own defaults name.
  for (d in decls) for (p in d.params) p.defaultText?.let { defaults.resolve(it, d.imports, d.pkg) }
  repeat(2) {
    for (f in defaults.all.filterIsInstance<DefaultsMirror.Member.Factory>().toList()) {
      for (o in f.overloads) for (p in o.params) p.defaultText?.let { defaults.resolve(it, o.imports, o.pkg) }
    }
  }
  // A hidden overload needs its visible successor configured: say which overloads Compose has now.
  val orphans = decls.filter { it.isHidden && successorOf(it, decls) == null }.map { d ->
    val visible = api.overloadSpecs(d.pkg, d.owner, d.name).filter { spec ->
      runCatching { api.find(spec).also(src::complete) }.getOrNull()?.isHidden == false
    }
    "${d.spec}: hidden in this Compose (${d.deprecationMessage ?: "deprecated, HIDDEN"}). Add its visible successor to " +
      "${config.group}.json (keep this line: old guests still send its tag). Visible overloads now:\n    " + visible.joinToString("\n    ")
  }
  if (orphans.isNotEmpty()) error(orphans.joinToString("\n"))
  val lock = LockBuilder(oldLock)
  val plans = plan(config, decls, lock, defaults) + errorDecls.map { (d, message) -> Plan.ErrorStub(d, classify(d), "Keliver: $message") }
  val factories = planFactories(config, defaults, lock)
  val e = Emitter(config, plans, factories, defaults, api::supertypes)
  return Output(mapOf(e.schema()), e.guest(), mapOf(e.host()), e.values(), e.editor(), lock.build())
}

fun main(argv: Array<String>) {
  val args = argv.toList()
  fun arg(name: String) = args[args.indexOf(name) + 1]
  val check = "--check" in args
  val config = loadConfig(File(arg("--config"))).also { it.input = if ("--input" in args) arg("--input") else null }
  val lockFile = File(arg("--lock"))
  val oldLock = if (lockFile.exists()) lockJson.decodeFromString(Lock.serializer(), lockFile.readText()) else Lock(config.group)
  val classpath = arg("--classpath").split(File.pathSeparator).map(::File)
  val sources = arg("--sources").split(File.pathSeparator).map(::File)
  val out = generate(config, oldLock, classpath, sources)

  val roots = listOf(
    File(arg("--out-schema")) to out.schema,
    File(arg("--out-guest")) to out.guest,
    File(arg("--out-host")) to out.host,
    File(arg("--out-values")) to out.values,
  )
  val expected = buildMap {
    for ((root, files) in roots) for ((path, text) in files) put(File(root, path), text)
    put(File(arg("--out-editor")), out.editor)
    put(lockFile, lockJson.encodeToString(Lock.serializer(), out.lock) + "\n")
  }
  // A Generated.kt (or GeneratedDefaults.kt) the generator no longer writes is stale.
  val stale = roots.flatMap { (root, _) -> root.walk().filter { it.name == "Generated.kt" || it.name == GENERATED_DEFAULTS }.toList() }
    .filter { it !in expected }

  val problems = checkAppendOnly(oldLock, out.lock).toMutableList()
  if (check) {
    for ((file, text) in expected) if (!file.exists() || file.readText() != text) problems += "$file is out of date"
    stale.forEach { problems += "$it is stale" }
    if (problems.isNotEmpty()) {
      System.err.println("checkComposeApi failed (run :keliver-compose-generator:generateComposeApi):\n  " + problems.joinToString("\n  "))
      exitProcess(1)
    }
    println("checkComposeApi: up to date (group ${config.group}, level ${out.lock.level})")
  } else {
    if (problems.isNotEmpty()) {
      System.err.println("refusing to write: the lock would change other than by appends:\n  " + problems.joinToString("\n  "))
      exitProcess(1)
    }
    stale.forEach { it.delete() }
    for ((file, text) in expected) {
      file.parentFile.mkdirs()
      file.writeText(text)
    }
    println("generated group ${config.group}: level ${out.lock.level}, ${out.lock.widgets.size} widgets, ${out.lock.modifiers.size} modifiers")
  }
}
