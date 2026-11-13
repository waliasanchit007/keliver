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
    /**
     * For an overload Compose now hides (design §3): the visible overload its renderer calls, with
     * the parameters both share; the hidden one keeps its tags and wire shape but gets no stub.
     */
    val successor: DeclInfo? = null,
  ) : Plan {
    val hidden: Boolean get() = successor != null

    /** The parameters the renderer passes: all, or for a hidden overload those its successor has. */
    val called: List<Classified>
      get() = successor?.let { s -> params.filter { c -> s.params.any { it.name == c.param.name } } } ?: params

    val dropped: List<Classified> get() = params.filter { it.kind == ParamKind.DROP }
    val wire: List<Classified> get() = params.filter { it.kind == ParamKind.VALUE || it.kind == ParamKind.EVENT || it.kind == ParamKind.SLOT || it.kind == ParamKind.LAZY }

    /** The `LazyListState` parameter a lazy list's window follows. */
    val lazyState: Classified?
      get() = params.firstOrNull { it.param.type.fq == "androidx.compose.foundation.lazy.LazyListState" && it.kind == ParamKind.VALUE }
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
  /**
   * Gate B-e: declarations whose Float, Dp and Color parameters accept an animated reference
   * (`animate*AsState`): the stub sends the target and an `ExtraAnimated` element, and the renderer
   * wraps the argument in the real `animate*AsState`.
   */
  val animated: List<String> = emptyList(),
  /** Hand-written modifiers of the group (`ExtraAnimated`), as [extras] are widgets. */
  val extraModifiers: List<String> = emptyList(),
) {
  /** The Compose versions read (for the schema's header), or null. */
  var input: String? = null
}

const val DESIGN = "docs/COMPOSE_GENERATOR_DESIGN.md"

fun dropMessage(config: Config, c: Classified): String {
  c.reason?.takeIf { it.startsWith("default: ") }?.let {
    return "Keliver: '${c.param.name}': its $it ($DESIGN §5). Leave it out."
  }
  if (c.reason == "nullable with a non-null default") {
    return "Keliver: '${c.param.name}': an explicit null for a parameter whose default is not null needs a wire marker this type lacks ($DESIGN §5). Leave it out."
  }
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
  p.decl.mirrorPkg + "/" + (p.decl.owner ?: p.decl.widgetReceiver?.simpleName ?: "") + "#" + p.decl.name + "(" +
    p.params.filter { it.kind != ParamKind.DROP }.joinToString(",") { it.param.type.canonical() } + ")"

/** Parameters that travel as properties, children or events (the modifier has its own channel). */
fun wireNames(params: List<Classified>): List<String> =
  params.filter { it.kind == ParamKind.VALUE || it.kind == ParamKind.EVENT || it.kind == ParamKind.SLOT || it.kind == ParamKind.LAZY }
    .map { it.param.name }

/** Extra tagged properties a parameter needs: a lazy list's window (`content.before` …). */
fun syntheticNames(params: List<Classified>): List<String> = params.filter { it.kind == ParamKind.LAZY }
  .flatMap { c -> LAZY_SYNTHETIC.map { "${c.param.name}.$it" } }

val LAZY_SYNTHETIC: List<String> = listOf("before", "after", "viewport")

/**
 * The visible overload a hidden one maps to: a configured, visible declaration with the same
 * package, owner and name that takes every parameter the hidden one takes and gives each parameter
 * it adds a default (Compose's way of adding a parameter), so a call with the hidden one's
 * arguments resolves to it. The one adding the fewest wins.
 */
fun successorOf(hidden: DeclInfo, decls: List<DeclInfo>): DeclInfo? = decls
  .filter { !it.isHidden && it.pkg == hidden.pkg && it.owner == hidden.owner && it.name == hidden.name }
  .filter { d -> hidden.params.all { h -> d.params.any { it.name == h.name } } }
  .filter { d -> d.params.filter { p -> hidden.params.none { it.name == p.name } }.all { it.hasDefault } }
  .minByOrNull { d -> d.params.size }

/** What a plan's wire carries, parameter by parameter: equal shapes mean an equal wire apart from tags. */
private fun wireShape(p: Plan.Generated): List<String> = p.wire.map { c ->
  val t = c.param.type
  "${c.param.name}:${c.kind}:${TypeMap.wireType(t, t.nullable || c.defaultCase == DefaultCase.EXPRESSION)}:${c.defaultCase}:${c.explicitNull}"
}

/**
 * The entry each visible overload's stub sends (design §3): its own, unless a hidden predecessor's
 * wire carries exactly the same parameters, in which case the predecessor's. Compose adding a
 * parameter that does not travel yet (material3's `Text(autoSize)`, foundation's
 * `clickable(interactionSource)`) then changes nothing on the wire, and a guest built now still
 * renders on a host built before the new entry existed. The new entry stays in the lock; the stub
 * moves to it once one of the added parameters travels.
 */
fun sentAs(plans: List<Plan.Generated>): Map<Plan.Generated, Plan.Generated> = plans.filter { !it.hidden }.mapNotNull { v ->
  plans.filter { h -> h.hidden && h.successor == v.decl && wireShape(h) == wireShape(v) }
    .minByOrNull { it.entry.tag }
    ?.let { v to it }
}.toMap()

/** Why [expr] cannot be copied into a guest stub (it names a `*Defaults` member with no mirror), or null. */
fun defaultsProblem(defaults: DefaultsMirror?, expr: String, decl: DeclInfo): String? =
  defaults?.resolve(expr, decl.imports, decl.pkg)?.filterIsInstance<DefaultsMirror.Member.Unsupported>()?.firstOrNull()
    ?.let { "`${it.ownerFq.substringAfterLast('.')}.${it.name}`: ${it.reason}" }

/**
 * A `*Defaults` factory overload: a call the guest records and the host makes (design §6). As for a
 * widget's parameters (§5), the guest records only the arguments that differ from their defaults,
 * and the host evaluates each missing one with Compose's own default expression, in order, so a
 * default that depends on the theme or on an earlier argument (`contentColorFor(containerColor)`)
 * is the host's, as in native Compose.
 */
data class FactoryPlan(
  val decl: DeclInfo,
  val entry: LockEntry?,
  val params: List<Classified>,
  /** Each parameter's default in the guest: Compose's text, with non-public values snapshotted as mirrors. */
  val guestDefaults: Map<String, String>,
  /** The same defaults on the host: Compose's text, with non-public values snapshotted as Compose values. */
  val hostDefaults: Map<String, String> = emptyMap(),
  /** Why this overload is an ERROR stub instead, or null. */
  val error: String? = null,
)

fun planFactories(config: Config, defaults: DefaultsMirror, lock: LockBuilder): List<FactoryPlan> =
  defaults.all.filterIsInstance<DefaultsMirror.Member.Factory>().flatMap { f ->
    f.overloads.map { o ->
      val params = classify(o) { expr -> defaultsProblem(defaults, expr, o) }
      val guestDefaults = mutableMapOf<String, String>()
      val hostDefaults = mutableMapOf<String, String>()
      var problem = params.firstOrNull { it.kind != ParamKind.VALUE }?.let { c ->
        "Keliver: '${c.param.name}': ${c.reason ?: c.param.type.canonical()} cannot cross to the host ($DESIGN §6)."
      }
      for (c in params) {
        val text = c.param.defaultText ?: continue
        // Non-public values (`FilledButtonTokens.ContainerElevation`, `DisabledAlpha`) become literals.
        val guest = defaults.snapshotTokens(text, o.imports, o.pkg, mirror = true)
        val host = defaults.snapshotTokens(text, o.imports, o.pkg, mirror = false)
        if (guest == null || host == null) {
          // The ERROR stub then has no default here (the text would not compile in the guest).
          problem = problem ?: "Keliver: '${c.param.name}': its default `$text` names a value that could not be read ($DESIGN §5)."
          continue
        }
        guestDefaults[c.param.name] = guest
        hostDefaults[c.param.name] = host
      }
      val returns = o.returns.fq
      if (problem == null && returns !in TypeMap.opaque && returns != "androidx.compose.foundation.BorderStroke") {
        problem = "Keliver: a factory of ${o.returns.canonical()} is not mirrored ($DESIGN §6)."
      }
      if (problem == null && o.returns.nullable) problem = "Keliver: a factory that may return null is not mirrored."
      if (problem != null) {
        FactoryPlan(o, null, params, guestDefaults, hostDefaults, problem)
      } else {
        FactoryPlan(o, lock.factory(o, params.map { it.param.name }), params, guestDefaults, hostDefaults)
      }
    }
  }

fun plan(config: Config, decls: List<DeclInfo>, lock: LockBuilder, defaults: DefaultsMirror? = null): List<Plan> {
  val seen = mutableSetOf<String>()
  return decls.map { decl ->
    val params = classify(decl) { expr -> defaultsProblem(defaults, expr, decl) }
    val blocking = params.firstOrNull { it.kind == ParamKind.BLOCK }
    if (decl.isHidden) {
      // Compose kept this overload for binary compatibility only. An old guest may still send its
      // tag, so it keeps its lock entry and wire shape; its renderer calls the current overload.
      val entry = lock.existing(decl) ?: error(
        "${decl.spec}: hidden in this Compose (${decl.deprecationMessage ?: "deprecated, HIDDEN"}) and not in the lock; " +
          "configure its visible successor instead",
      )
      val successor = successorOf(decl, decls) ?: error(
        "${decl.spec}: hidden in this Compose (${decl.deprecationMessage ?: "deprecated, HIDDEN"}); " +
          "add its visible successor to the config so the hidden overload's renderer can call it",
      )
      require(blocking == null) { "${decl.spec}: hidden, and its ${blocking!!.param.name} cannot cross" }
      return@map Plan.Generated(decl, lock.keep(decl, wireNames(params)).also { check(it.tag == entry.tag) }, params, successor)
    }
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
          val animated = decl.spec in config.animated
          val entry = if (decl.kind == DeclKind.WIDGET) {
            lock.widget(decl, wireNames(params), syntheticNames(params), animated)
          } else {
            lock.modifier(decl, wireNames(params), animated)
          }
          Plan.Generated(decl, entry, params)
        }
      }
    }
  }
}
