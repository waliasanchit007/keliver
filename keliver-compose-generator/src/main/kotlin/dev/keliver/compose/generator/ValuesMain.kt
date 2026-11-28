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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The file name of the guest's generated mirrors, in each package. */
const val GENERATED_CALLS = "GeneratedCalls.kt"

/** The file name of the host's generated replay. */
const val GENERATED_REPLAY = "GeneratedReplay.kt"

fun loadValuesConfig(file: File): ValuesConfig {
  val o = Json.parseToJsonElement(file.readText()).jsonObject
  fun strings(key: String) = o[key]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
  return ValuesConfig(
    guestRoot = o.getValue("guestRoot").jsonPrimitive.content,
    hostPackage = o.getValue("hostPackage").jsonPrimitive.content,
    packages = strings("packages"),
    excludeTypes = strings("excludeTypes").toSet(),
    excludeKeys = o["excludeKeys"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content }.orEmpty(),
  )
}

/** What one values generation wrote (or, with `--check`, would have), for a test or the task's log. */
class ValuesRun(val problems: List<String>, val output: ValuesOutput)

/**
 * The shared `values` generation (MS4-42): `--values --config values.json --lock values.lock.json
 * --classpath … --sources … --out-guest <dir> --out-host <dir> --out-editor <file> [--check]`.
 * Like a widget group's run it touches only its own files (`GeneratedCalls.kt` under the guest root,
 * `GeneratedReplay.kt` under the host root), and never exits.
 */
fun runValues(args: List<String>): ValuesRun {
  fun arg(name: String) = args[args.indexOf(name) + 1]
  val check = "--check" in args
  val config = loadValuesConfig(File(arg("--config")))
  val lockFile = File(arg("--lock"))
  val oldLock = if (lockFile.exists()) lockJson.decodeFromString(ValuesLock.serializer(), lockFile.readText()) else ValuesLock()
  val classpath = arg("--classpath").split(File.pathSeparator).map(::File)
  val sources = arg("--sources").split(File.pathSeparator).map(::File)
  val out = CallRecordEmitter(GenericIndex(classpath), SourceReader(sources), config, oldLock).generate()

  val guestRoot = File(arg("--out-guest"))
  val hostRoot = File(arg("--out-host"))
  val editorFile = File(arg("--out-editor"))
  val expected = buildMap {
    for ((path, text) in out.guest) put(File(guestRoot, path), text)
    for ((path, text) in out.host) put(File(hostRoot, path), text)
    put(editorFile, out.editor)
    put(lockFile, lockJson.encodeToString(ValuesLock.serializer(), out.lock) + "\n")
  }
  // One of the values' own files the generator no longer writes is stale; nothing else is looked at.
  val stale = guestRoot.walk().filter { it.name == GENERATED_CALLS }.toList() + hostRoot.walk().filter { it.name == GENERATED_REPLAY }.toList()
  val staleFiles = stale.filter { it !in expected }

  val problems = checkValuesAppendOnly(oldLock, out.lock).toMutableList()
  if (check) {
    for ((file, text) in expected) if (!file.exists() || file.readText() != text) problems += "$file is out of date"
    staleFiles.forEach { problems += "$it is stale" }
    if (problems.isEmpty()) println("checkComposeApi: up to date (values, level ${out.lock.level})")
  } else if (problems.isEmpty()) {
    staleFiles.forEach { it.delete() }
    for ((file, text) in expected) {
      file.parentFile.mkdirs()
      file.writeText(text)
    }
    val r = out.report
    println("generated values: level ${out.lock.level}, ${r.types} types, ${r.producers} producers, ${r.members} receiver members, ${r.readableProperties} readable properties, ${r.propertyStubs} property stubs")
    for ((reason, what) in r.rejected) println("  not crossing ($reason): ${what.size}")
  }
  return ValuesRun(problems, out)
}
