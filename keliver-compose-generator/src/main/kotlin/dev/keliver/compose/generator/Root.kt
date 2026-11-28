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
import kotlinx.serialization.Serializable
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
    val types = c.dependencies.map { it.second }
    require(types.toSet().size == types.size) { "${file.name}: a group is listed twice: $types" }
  }
}

/**
 * The root's lock (MS4-06): each group's dependency tag, which offsets every one of the group's tags
 * on the wire. The root's `keliver-api.xml` holds only the schema type, so Redwood's API check never
 * sees these tags; this lock is their append-only record (review of #313, L1). A group keeps its tag
 * for good, and the level grows by one whenever a generation appends a group.
 */
@Serializable
data class RootLock(
  val group: String,
  val level: Int = 0,
  /** The group's schema type → its dependency tag and the level it was appended at. */
  val dependencies: Map<String, RootLockEntry> = emptyMap(),
)

@Serializable
data class RootLockEntry(val tag: Int, val since: Int)

/** [old] with [root]'s dependencies: a group already in the lock keeps its `since`, a new one is appended. */
fun rootLock(root: RootConfig, old: RootLock): RootLock {
  val newLevel = old.level + 1
  var appended = false
  val dependencies = root.dependencies.associate { (tag, type) ->
    type to (old.dependencies[type]?.copy(tag = tag) ?: RootLockEntry(tag, newLevel).also { appended = true })
  }
  return RootLock(old.group, if (appended) newLevel else old.level, dependencies.toSortedMap())
}

/** Appends only: every group in [old] stays, at its tag and level, and no tag is taken twice. */
fun checkRootAppendOnly(old: RootLock, new: RootLock): List<String> = buildList {
  for ((type, entry) in old.dependencies) {
    val now = new.dependencies[type]
    when {
      now == null -> add("group $type (dependency tag ${entry.tag}) was removed: a group's tag is on the wire of every bundle built with it")
      now.tag != entry.tag -> add("group $type changed its dependency tag from ${entry.tag} to ${now.tag}: every one of its tags would move on the wire")
      now.since != entry.since -> add("group $type changed its level")
    }
  }
  val oldTags = old.dependencies.values.map { it.tag }.toSet()
  for ((type, entry) in new.dependencies) {
    if (type !in old.dependencies && entry.tag in oldTags) add("group $type takes dependency tag ${entry.tag}, which another group had")
  }
  if (new.level < old.level) add("the level went backwards")
}

/** The root schema's source file: relative path → text. */
fun rootSchema(root: RootConfig): Pair<String, String> {
  val body = buildString {
    append(header(root.group))
    append("@file:Suppress(\"ktlint\")\n\npackage ${root.schemaPackage}\n\n")
    append("import dev.keliver.schema.Schema\nimport dev.keliver.schema.Schema.Dependency\n\n")
    append("/**\n * The generated root (keliver-compose-generator/${root.group}.json): no widgets of its own; each\n")
    append(" * product group is a dependency at its own tag, with its own lock and level. The tags are\n")
    append(" * recorded, append-only, in keliver-compose-generator/${root.group}.lock.json.\n */\n")
    append("@Schema(\n  members = [],\n  dependencies = [\n")
    root.dependencies.sortedBy { it.first }.forEach { (tag, type) -> append("    Dependency($tag, $type::class),\n") }
    append("  ],\n)\npublic interface ${root.schemaName}\n")
  }
  return "${root.schemaPackage.replace('.', '/')}/Generated.kt" to body
}

/** What one root generation (or check) found: the [problems] refuse it; [summary] is printed otherwise. */
class RootRun(val problems: List<String>, val summary: String)

/**
 * `--root <config> --lock <lock> --out-schema <dir> [--check]`: writes (or checks) the root schema
 * and its lock. Refused, in either mode, when the lock would change other than by appends; a check
 * also fails on a file that is out of date, or on a `Generated.kt` under the output root that the
 * root no longer writes (stale). A generation deletes the stale files.
 */
fun runRoot(configFile: File, lockFile: File, outSchema: File, check: Boolean): RootRun {
  val root = loadRootConfig(configFile)
  val old = if (lockFile.exists()) lockJson.decodeFromString(RootLock.serializer(), lockFile.readText()) else RootLock(root.group)
  require(old.group == root.group) { "$lockFile is group ${old.group}, the config is group ${root.group}" }
  val lock = rootLock(root, old)
  val (path, text) = rootSchema(root)
  val expected = mapOf(
    File(outSchema, path) to text,
    lockFile to lockJson.encodeToString(RootLock.serializer(), lock) + "\n",
  )
  val stale = outSchema.walk().filter { it.name == "Generated.kt" && it !in expected }.toList()
  val problems = checkRootAppendOnly(old, lock).toMutableList()
  val groups = root.dependencies.sortedBy { it.first }.joinToString { (tag, type) -> "${type.substringAfterLast('.')}@$tag" }
  if (check) {
    for ((file, content) in expected) if (!file.exists() || file.readText() != content) problems += "$file is out of date"
    stale.forEach { problems += "$it is stale" }
    return RootRun(problems, "checkComposeApi: up to date (root ${root.group}, level ${lock.level}, $groups)")
  }
  if (problems.isEmpty()) {
    stale.forEach { it.delete() }
    for ((file, content) in expected) {
      file.parentFile.mkdirs()
      file.writeText(content)
    }
  }
  return RootRun(problems, "generated root ${root.group}: level ${lock.level}, $groups")
}
