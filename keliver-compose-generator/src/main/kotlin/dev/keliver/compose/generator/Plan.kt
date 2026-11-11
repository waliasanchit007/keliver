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

/** What the generator does with one declaration. */
sealed interface Plan {
  val decl: DeclInfo

  /** Generated: a wire widget or modifier, a stub, a renderer; [dropped] parameters also get an ERROR overload. */
  data class Generated(
    override val decl: DeclInfo,
    val entry: LockEntry,
    val params: List<Classified>,
  ) : Plan {
    val dropped: List<Classified> get() = params.filter { it.kind == ParamKind.DROP }
    val wire: List<Classified> get() = params.filter { it.kind == ParamKind.VALUE || it.kind == ParamKind.EVENT || it.kind == ParamKind.SLOT }
  }

  /** Not generated: one ERROR stub with Compose's signature and [message]. */
  data class ErrorStub(override val decl: DeclInfo, val params: List<Classified>, val message: String) : Plan
}

class Config(
  val group: String,
  val schemaPackage: String,
  val schemaName: String,
  val dependencies: List<Pair<Int, String>>,
  val declarations: List<String>,
  val errors: List<Pair<String, String>>,
  val typeMessages: Map<String, String>,
  val icons: List<String>,
  val extras: List<String>,
)

const val DESIGN = "docs/COMPOSE_GENERATOR_DESIGN.md"

fun dropMessage(config: Config, c: Classified): String {
  if (TypeMap.isMapped(c.param.type)) {
    return "Keliver: '${c.param.name}': a nullable modifier value is Gate B ($DESIGN §8). Leave it out."
  }
  val why = config.typeMessages[c.param.type.fq]
    ?: config.typeMessages[c.param.type.functionParams.firstOrNull()?.fq ?: ""]
    ?: "a ${c.param.type.canonical()} cannot cross to the host in this version of the generated API ($DESIGN §11)."
  return "Keliver: '${c.param.name}': $why"
}

/** The main stub's signature without dropped parameters, to find overloads that would clash. */
fun mainSignature(p: Plan.Generated): String =
  p.decl.mirrorPkg + "/" + (p.decl.owner ?: "") + "#" + p.decl.name + "(" +
    p.params.filter { it.kind != ParamKind.DROP }.joinToString(",") { it.param.type.canonical() } + ")"

fun plan(config: Config, decls: List<DeclInfo>, lock: LockBuilder): List<Plan> {
  val seen = mutableSetOf<String>()
  return decls.map { decl ->
    val params = classify(decl)
    val blocking = params.firstOrNull { it.kind == ParamKind.BLOCK }
    when {
      blocking != null -> Plan.ErrorStub(decl, params, dropMessage(config, blocking))
      decl.kind == DeclKind.WIDGET && !decl.isComposable -> Plan.ErrorStub(decl, params, "Keliver: not a composable widget.")
      params.count { it.kind == ParamKind.EVENT } > 1 && decl.kind != DeclKind.WIDGET ->
        Plan.ErrorStub(decl, params, "Keliver: a modifier with more than one lambda is Gate B ($DESIGN §8).")
      else -> {
        val probe = Plan.Generated(decl, LockEntry(0, "", emptyMap(), 0), params)
        if (!seen.add(mainSignature(probe))) {
          val dropped = probe.dropped.joinToString { "'${it.param.name}'" }
          Plan.ErrorStub(decl, params, "Keliver: this overload's $dropped cannot cross to the host, and without them it is another overload. Leave them out.")
        } else {
          val entry = if (decl.kind == DeclKind.WIDGET) lock.widget(decl) else lock.modifier(decl)
          Plan.Generated(decl, entry, params)
        }
      }
    }
  }
}
