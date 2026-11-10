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
class Output(val schema: Map<String, String>, val guest: Map<String, String>, val host: Map<String, String>, val editor: String, val lock: Lock)

fun generate(config: Config, oldLock: Lock, classpath: List<File>, sources: List<File>): Output {
  val api = ApiReader(classpath)
  val src = SourceReader(sources)
  val decls = config.declarations.map { api.find(it).also(src::complete) }
  val errorDecls = config.errors.map { (spec, message) -> api.find(spec).also(src::complete) to message }
  val lock = LockBuilder(oldLock)
  val plans = plan(config, decls, lock) + errorDecls.map { (d, message) -> Plan.ErrorStub(d, classify(d), "Keliver: $message") }
  val e = Emitter(config, plans)
  return Output(mapOf(e.schema()), e.guest(), mapOf(e.host()), e.editor(), lock.build())
}

fun main(argv: Array<String>) {
  val args = argv.toList()
  fun arg(name: String) = args[args.indexOf(name) + 1]
  val check = "--check" in args
  val config = loadConfig(File(arg("--config")))
  val lockFile = File(arg("--lock"))
  val oldLock = if (lockFile.exists()) lockJson.decodeFromString(Lock.serializer(), lockFile.readText()) else Lock(config.group)
  val classpath = arg("--classpath").split(File.pathSeparator).map(::File)
  val sources = arg("--sources").split(File.pathSeparator).map(::File)
  val out = generate(config, oldLock, classpath, sources)

  val roots = listOf(File(arg("--out-schema")) to out.schema, File(arg("--out-guest")) to out.guest, File(arg("--out-host")) to out.host)
  val expected = buildMap {
    for ((root, files) in roots) for ((path, text) in files) put(File(root, path), text)
    put(File(arg("--out-editor")), out.editor)
    put(lockFile, lockJson.encodeToString(Lock.serializer(), out.lock) + "\n")
  }
  // A Generated.kt the generator no longer writes is stale.
  val stale = roots.flatMap { (root, _) -> root.walk().filter { it.name == "Generated.kt" }.toList() }.filter { it !in expected }

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
