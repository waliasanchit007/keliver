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
import kotlin.system.exitProcess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The generated root schema (docs/MS4_PLAN.md, MS4-01; design §14): no widgets of its own, and each
 * product group as a Redwood dependency at its own tag, so a group's tags are offset by
 * `tag × 1,000,000` on the wire and each group keeps its own lock and level. The protocol modules
 * are generated for the root.
 */
class RootConfig(
  val group: String,
  val schemaPackage: String,
  val schemaName: String,
  /** Dependency tag → the group's schema type. */
  val dependencies: List<Pair<Int, String>>,
)

fun loadRootConfig(file: File): RootConfig {
  val o = Json.parseToJsonElement(file.readText()).jsonObject
  return RootConfig(
    group = o.getValue("group").jsonPrimitive.content,
    schemaPackage = o.getValue("schemaPackage").jsonPrimitive.content,
    schemaName = o.getValue("schemaName").jsonPrimitive.content,
    dependencies = o.getValue("dependencies").jsonArray.map {
      it.jsonObject.getValue("tag").jsonPrimitive.int to it.jsonObject.getValue("type").jsonPrimitive.content
    },
  ).also { c ->
    require(c.dependencies.isNotEmpty()) { "${file.name}: the root needs at least one group" }
    val tags = c.dependencies.map { it.first }
    require(tags.toSet().size == tags.size) { "${file.name}: a dependency tag is used twice: $tags" }
  }
}

/** The root schema's source file: relative path → text. */
fun rootSchema(root: RootConfig): Pair<String, String> {
  val body = buildString {
    append(header(root.group))
    append("@file:Suppress(\"ktlint\")\n\npackage ${root.schemaPackage}\n\n")
    append("import dev.keliver.schema.Schema\nimport dev.keliver.schema.Schema.Dependency\n\n")
    append("/**\n * The generated root (keliver-compose-generator/${root.group}.json): no widgets of its own; each\n")
    append(" * product group is a dependency at its own tag, with its own lock and level.\n */\n")
    append("@Schema(\n  members = [],\n  dependencies = [\n")
    root.dependencies.sortedBy { it.first }.forEach { (tag, type) -> append("    Dependency($tag, $type::class),\n") }
    append("  ],\n)\npublic interface ${root.schemaName}\n")
  }
  return "${root.schemaPackage.replace('.', '/')}/Generated.kt" to body
}

/** `--root <config> --out-schema <dir> [--check]`: writes (or checks) the root schema. */
fun root(configFile: File, outSchema: File, check: Boolean) {
  val root = loadRootConfig(configFile)
  val (path, text) = rootSchema(root)
  val file = File(outSchema, path)
  if (check) {
    if (!file.exists() || file.readText() != text) {
      System.err.println("checkComposeApi failed (run :keliver-compose-generator:generateComposeApi):\n  $file is out of date")
      exitProcess(1)
    }
    println("checkComposeApi: up to date (root ${root.group}, ${root.dependencies.size} groups)")
  } else {
    file.parentFile.mkdirs()
    file.writeText(text)
    println("generated root ${root.group}: ${root.dependencies.joinToString { (tag, type) -> "${type.substringAfterLast('.')}@$tag" }}")
  }
}
