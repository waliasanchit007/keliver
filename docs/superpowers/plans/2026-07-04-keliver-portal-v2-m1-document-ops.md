# V2 M1: UiDocument + Operation Engine Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The live semantic Document with a handle-based, sibling-anchored, transactional op engine in portal-server — portal UI becomes an op-emitting client with server-side undo; devices/preview keep working via the projected tree; the projected `.kt` is dual-written on every change.

**Architecture:** New MPP module `portal-document` (jvm+js+wasmJs, pure kotlinx-serialization) holds `DocNode`/`UiDocument`/`DocOp` + apply/invert + projection to the V1 `WidgetNode` interchange. `portal-relay` (the portal-server) hosts a `DocumentService` per screen: validate→apply→bump version→broadcast (SSE)→project to the existing draft store (device compat unchanged)→debounced whole-file `.kt` dual-write (labeled generated-until-M4). The wasm editor swaps local tree mutation for op submission + version-triggered refresh; undo/redo become server calls.

**Tech Stack:** Kotlin MPP, kotlinx-serialization (polymorphic sealed ops), JDK HttpServer SSE, existing portal-core (`WidgetNode`, `exportKotlin`, catalog), V1 editor (`Portal.kt`/`Ui` kit).

**Repo:** `/Users/sanchitwalia/AndroidStudioProjects/konduit`, branch `spike/keliver-web`. `export JAVA_HOME=$(/usr/libexec/java_home -v 17)` always. New js/wasm test deps ⇒ run `./gradlew kotlinUpgradeYarnLock` once after adding the module (S3 learning).

**Spec anchors:** design §1 (Document/DocNode/identity), §2 (ops/engine), amendments 3 (sibling anchors + envelope) & 8 (RawCode kindHint). RawCode *node type* and `ReplaceRaw` ship now for schema stability; they get exercised at M3/M5.

**File map**
```
portal-document/                                  NEW MPP module (jvm+js+wasmJs)
  build.gradle
  src/commonMain/kotlin/dev/keliver/portal/document/
    DocNode.kt        — DocNode (Widget|RawCode), PropValue (Lit|Bind|Action), contract types
    DocOp.kt          — sealed ops + OpEnvelope + OpBatch + results
    UiDocument.kt     — immutable document, apply(op): ApplyResult(newDoc, inverse), handle allocation
    Project.kt        — Document -> WidgetNode projection (needs :portal-core)
    DocJson.kt        — polymorphic Json for the wire
  src/commonTest/kotlin/dev/keliver/portal/document/
    ApplyInvertTest.kt, ProjectTest.kt, WireTest.kt
portal-relay/src/main/kotlin/DocumentService.kt   NEW — per-screen engine + undo stacks + SSE hub + dual-write
portal-relay/src/main/kotlin/Relay.kt             MODIFY — routes /doc /ops /undo /redo /doc-events; feed draft store from projection
portal-relay/build.gradle                         MODIFY — dep :portal-document
web-spike/src/wasmJsMain/kotlin/Portal.kt         MODIFY — ops out, SSE in, server undo
web-spike/build.gradle                            MODIFY — dep :portal-document
settings.gradle                                   MODIFY — include :portal-document
```

---

### Task 1: portal-document module — model + wire

**Files:** create `portal-document/build.gradle`, `DocNode.kt`, `DocOp.kt`, `DocJson.kt`; modify `settings.gradle`.

- [ ] **Step 1: build.gradle**
```gradle
// V2 M1 — the live semantic document + operation vocabulary (design §1-§2).
// Pure model: serializable, no server/UI deps; portal-core only for projection.
apply plugin: 'org.jetbrains.kotlin.multiplatform'
apply plugin: 'org.jetbrains.kotlin.plugin.serialization'

kotlin {
  jvm()
  js { browser() }
  wasmJs { browser() }
  sourceSets {
    commonMain {
      dependencies {
        api projects.portalCore
        implementation libs.kotlinx.serialization.json
      }
    }
    commonTest {
      dependencies {
        implementation libs.kotlin.test
      }
    }
  }
}
```
settings.gradle: after the `':portal-presenter-spike'` include add
`include ':portal-document' // V2 M1: live semantic document + op engine model`.

- [ ] **Step 2: DocNode.kt**
```kotlin
package dev.keliver.portal.document

import kotlinx.serialization.Serializable

/** Stable-for-the-document's-lifetime node handle. All ops target handles. */
@Serializable
@JvmInline
value class Handle(val v: Long)

@Serializable
sealed interface PropValue {
  @Serializable data class Lit(val tag: String, val s: String? = null, val i: Int? = null, val d: Double? = null, val b: Boolean? = null, val li: List<Int>? = null, val lf: List<Float>? = null) : PropValue
  @Serializable data class Bind(val field: String) : PropValue
  @Serializable data class Action(val name: String) : PropValue
}

/** Convenience constructors mirroring the V1 tree's prop kinds. */
fun lit(v: Any?): PropValue.Lit = when (v) {
  is String -> PropValue.Lit("s", s = v)
  is Int -> PropValue.Lit("i", i = v)
  is Double -> PropValue.Lit("d", d = v)
  is Boolean -> PropValue.Lit("b", b = v)
  is List<*> -> if (v.firstOrNull() is Float) {
    PropValue.Lit("lf", lf = v.filterIsInstance<Float>())
  } else {
    PropValue.Lit("li", li = v.filterIsInstance<Int>())
  }
  else -> PropValue.Lit("s", s = v?.toString() ?: "")
}

fun PropValue.Lit.toAny(): Any? = when (tag) {
  "s" -> s
  "i" -> i
  "d" -> d
  "b" -> b
  "li" -> li
  "lf" -> lf
  else -> null
}

@Serializable
sealed interface DocNode {
  val handle: Handle

  @Serializable
  data class Widget(
    override val handle: Handle,
    val type: String,                      // catalog simple name
    val props: Map<String, PropValue> = emptyMap(),
    val modifiers: Map<String, PropValue> = emptyMap(), // "Padding.allDp" -> Lit
    val children: List<DocNode> = emptyList(),
    val explicitId: String? = null,        // id("...") — file-boundary identity (M4)
  ) : DocNode

  @Serializable
  data class RawCode(
    override val handle: Handle,
    val text: String,                      // verbatim source — never lost
    val kindHint: String? = null,          // "condition"/"loop"/"effect" display-only
  ) : DocNode
}

@Serializable
data class Contract(
  val fields: Map<String, String> = emptyMap(), // name -> Kotlin type
  val actions: List<String> = emptyList(),
)
```

- [ ] **Step 3: DocOp.kt**
```kotlin
package dev.keliver.portal.document

import kotlinx.serialization.Serializable

/** Attribution for audit + undo grouping (design amendment 3). */
@Serializable
data class OpEnvelope(
  val session: String,
  val atMillis: Long,
  val label: String? = null,
)

/**
 * The op vocabulary (design §2). Positions are SIBLING-ANCHORED: `after` is
 * the preceding sibling's handle, null = first child. Never integer indexes.
 */
@Serializable
sealed interface DocOp {
  @Serializable data class InsertNode(val parent: Handle, val after: Handle?, val node: DocNode) : DocOp
  @Serializable data class DeleteNode(val target: Handle) : DocOp
  @Serializable data class MoveNode(val target: Handle, val newParent: Handle, val after: Handle?) : DocOp
  @Serializable data class SetProp(val target: Handle, val name: String, val value: PropValue) : DocOp
  @Serializable data class RemoveProp(val target: Handle, val name: String) : DocOp
  @Serializable data class SetModifier(val target: Handle, val name: String, val value: PropValue) : DocOp
  @Serializable data class RemoveModifier(val target: Handle, val name: String) : DocOp
  @Serializable data class RenameId(val target: Handle, val id: String?) : DocOp
  @Serializable data class ReplaceRaw(val target: Handle, val text: String) : DocOp
  @Serializable data class ContractEdit(val contract: Contract) : DocOp
}

/** A transaction: all-or-nothing, one version bump, one undo entry. */
@Serializable
data class OpBatch(
  val baseVersion: Long,
  val envelope: OpEnvelope,
  val ops: List<DocOp>,
)

@Serializable
data class OpAck(
  val ok: Boolean,
  val version: Long,
  val error: String? = null,
)
```

- [ ] **Step 4: DocJson.kt**
```kotlin
package dev.keliver.portal.document

import kotlinx.serialization.json.Json

/** One wire Json for documents/ops — sealed types carry class discriminators. */
val DocJson: Json = Json {
  ignoreUnknownKeys = true
  classDiscriminator = "kind"
  encodeDefaults = false
}
```

- [ ] **Step 5: WireTest.kt (failing first)**
```kotlin
package dev.keliver.portal.document

import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals

class WireTest {
  @Test fun opBatchRoundTripsPolymorphically() {
    val batch = OpBatch(
      baseVersion = 7,
      envelope = OpEnvelope(session = "agent:claude", atMillis = 123, label = "add button"),
      ops = listOf(
        DocOp.InsertNode(Handle(1), after = null, node = DocNode.Widget(Handle(9), "Button", mapOf("text" to lit("Buy")))),
        DocOp.SetProp(Handle(9), "text", PropValue.Bind("ctaLabel")),
        DocOp.ReplaceRaw(Handle(4), "if (b.x) { }"),
      ),
    )
    val json = DocJson.encodeToString(batch)
    assertEquals(batch, DocJson.decodeFromString<OpBatch>(json))
  }

  @Test fun docNodeRoundTrips() {
    val doc: DocNode = DocNode.Widget(
      Handle(1), "Column",
      children = listOf(DocNode.RawCode(Handle(2), "if (b.loading) { }", kindHint = "condition")),
    )
    assertEquals(doc, DocJson.decodeFromString<DocNode>(DocJson.encodeToString(doc)))
  }
}
```
- [ ] **Step 6: run RED then GREEN**: `./gradlew :portal-document:jvmTest` — first without the model files staged (skip if writing together), then expect `BUILD SUCCESSFUL`. Then `./gradlew kotlinUpgradeYarnLock -q` (new js targets).
- [ ] **Step 7: Commit** `feat(portal-document): document model + sibling-anchored op vocabulary + wire json`

### Task 2: UiDocument — apply/invert engine

**Files:** create `UiDocument.kt`, `ApplyInvertTest.kt`.

- [ ] **Step 1: failing tests** — `ApplyInvertTest.kt`
```kotlin
package dev.keliver.portal.document

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class ApplyInvertTest {
  private fun doc(): UiDocument {
    val root = DocNode.Widget(Handle(1), "Column", children = listOf(
      DocNode.Widget(Handle(2), "StyledText", mapOf("text" to lit("hi"))),
      DocNode.Widget(Handle(3), "Button", mapOf("text" to lit("Buy"))),
    ))
    return UiDocument(screen = "main", root = root, contract = Contract(), version = 0, nextHandle = 10)
  }

  @Test fun insertAfterSiblingAndInvert() {
    val d = doc()
    val spacer = DocNode.Widget(Handle(0), "Spacer") // handle 0 = allocate
    val r = d.apply(DocOp.InsertNode(Handle(1), after = Handle(2), node = spacer))
    val types = (r.doc.root as DocNode.Widget).children.map { (it as DocNode.Widget).type }
    assertEquals(listOf("StyledText", "Spacer", "Button"), types)
    val allocated = (r.doc.root as DocNode.Widget).children[1].handle
    assertEquals(Handle(10), allocated) // engine allocates from nextHandle
    // inverse restores exactly
    val back = r.doc.apply(r.inverse!!)
    assertEquals(d.root, back.doc.root)
  }

  @Test fun deleteInvertRestoresPositionAndSubtree() {
    val d = doc()
    val r = d.apply(DocOp.DeleteNode(Handle(2)))
    assertEquals(1, (r.doc.root as DocNode.Widget).children.size)
    val back = r.doc.apply(r.inverse!!)
    assertEquals(d.root, back.doc.root)
  }

  @Test fun moveToFrontAndInvert() {
    val d = doc()
    val r = d.apply(DocOp.MoveNode(Handle(3), newParent = Handle(1), after = null))
    assertEquals(listOf(Handle(3), Handle(2)), (r.doc.root as DocNode.Widget).children.map { it.handle })
    val back = r.doc.apply(r.inverse!!)
    assertEquals(d.root, back.doc.root)
  }

  @Test fun setPropInvertRestoresOldOrRemoves() {
    val d = doc()
    val r1 = d.apply(DocOp.SetProp(Handle(2), "text", lit("yo")))
    val back1 = r1.doc.apply(r1.inverse!!)
    assertEquals(d.root, back1.doc.root)
    val r2 = d.apply(DocOp.SetProp(Handle(2), "fontSize", lit(22))) // was absent
    assertIs<DocOp.RemoveProp>(r2.inverse)
  }

  @Test fun errorsAreExplicit() {
    val d = doc()
    val err = d.tryApply(DocOp.DeleteNode(Handle(99)))
    assertNull(err.result)
    assertEquals("unknown handle 99", err.error)
    // cycle guard
    val cyc = d.tryApply(DocOp.MoveNode(Handle(1), newParent = Handle(2), after = null))
    assertEquals("cannot move a node into its own subtree", cyc.error)
  }

  @Test fun batchIsAtomicWithSingleInverse() {
    val d = doc()
    val batch = listOf(
      DocOp.SetProp(Handle(2), "text", lit("A")),
      DocOp.DeleteNode(Handle(99)), // fails -> whole batch rejected
    )
    val res = d.applyBatch(batch)
    assertNull(res.result)
    val ok = d.applyBatch(listOf(DocOp.SetProp(Handle(2), "text", lit("A")), DocOp.DeleteNode(Handle(3))))
    val undone = ok.result!!.doc.apply(ok.result!!.inverseBatch)
    assertEquals(d.root, undone.doc.root)
  }
}
```
- [ ] **Step 2: RED run** `./gradlew :portal-document:jvmTest` → unresolved `UiDocument`.
- [ ] **Step 3: implement `UiDocument.kt`**
```kotlin
package dev.keliver.portal.document

import kotlinx.serialization.Serializable

@Serializable
data class UiDocument(
  val screen: String,
  val root: DocNode,
  val contract: Contract,
  val version: Long,
  val nextHandle: Long,
) {
  data class ApplyResult(val doc: UiDocument, val inverse: DocOp?)
  data class TryResult(val result: ApplyResult?, val error: String?)
  data class BatchResult(val doc: UiDocument, val inverseBatch: List<DocOp>)
  data class TryBatch(val result: BatchResult?, val error: String?)

  fun find(h: Handle): DocNode? = findIn(root, h)

  /** apply or throw — used by tests and internal replay. */
  fun apply(op: DocOp): ApplyResult {
    val t = tryApply(op)
    return t.result ?: throw IllegalArgumentException(t.error)
  }

  fun apply(ops: List<DocOp>): ApplyResult { // replay a stored inverse batch
    var d = this
    ops.forEach { d = d.apply(it).doc }
    return ApplyResult(d, null)
  }

  fun tryApply(op: DocOp): TryResult {
    fun err(m: String) = TryResult(null, m)
    return when (op) {
      is DocOp.InsertNode -> {
        val parent = find(op.parent) as? DocNode.Widget ?: return err("unknown handle ${op.parent.v}")
        if (op.after != null && parent.children.none { it.handle == op.after }) return err("anchor ${op.after.v} is not a child of ${op.parent.v}")
        val (renumbered, next) = allocate(op.node, nextHandle)
        val idx = if (op.after == null) 0 else parent.children.indexOfFirst { it.handle == op.after } + 1
        val newParent = parent.copy(children = parent.children.toMutableList().apply { add(idx, renumbered) })
        TryResult(ApplyResult(replaced(newParent).copy(nextHandle = next), DocOp.DeleteNode(renumbered.handle)), null)
      }
      is DocOp.DeleteNode -> {
        if (op.target == root.handle) return err("cannot delete the root")
        val parent = parentOf(root, op.target) ?: return err("unknown handle ${op.target.v}")
        val idx = parent.children.indexOfFirst { it.handle == op.target }
        val node = parent.children[idx]
        val after = if (idx == 0) null else parent.children[idx - 1].handle
        val newParent = parent.copy(children = parent.children.filterNot { it.handle == op.target })
        TryResult(ApplyResult(replaced(newParent), InsertExisting(parent.handle, after, node)), null)
      }
      is InsertExisting -> { // internal: undo of delete/move reinserts WITH original handles
        val parent = find(op.parent) as? DocNode.Widget ?: return err("unknown handle ${op.parent.v}")
        val idx = if (op.after == null) 0 else parent.children.indexOfFirst { it.handle == op.after } + 1
        val newParent = parent.copy(children = parent.children.toMutableList().apply { add(idx, op.node) })
        TryResult(ApplyResult(replaced(newParent), DocOp.DeleteNode(op.node.handle)), null)
      }
      is DocOp.MoveNode -> {
        val node = find(op.target) ?: return err("unknown handle ${op.target.v}")
        if (findIn(node, op.newParent) != null) return err("cannot move a node into its own subtree")
        val oldParent = parentOf(root, op.target) ?: return err("cannot move the root")
        val oldIdx = oldParent.children.indexOfFirst { it.handle == op.target }
        val oldAfter = if (oldIdx == 0) null else oldParent.children[oldIdx - 1].handle
        val del = apply(DocOp.DeleteNode(op.target)).doc
        val target = del.find(op.newParent) as? DocNode.Widget ?: return err("unknown handle ${op.newParent.v}")
        if (op.after != null && target.children.none { it.handle == op.after }) return err("anchor ${op.after.v} is not a child of ${op.newParent.v}")
        val res = del.apply(InsertExisting(op.newParent, op.after, node))
        TryResult(ApplyResult(res.doc, DocOp.MoveNode(op.target, oldParent.handle, oldAfter)), null)
      }
      is DocOp.SetProp -> withWidget(op.target) { w ->
        val old = w.props[op.name]
        val inv = if (old == null) DocOp.RemoveProp(op.target, op.name) else DocOp.SetProp(op.target, op.name, old)
        ApplyResult(replaced(w.copy(props = w.props + (op.name to op.value))), inv)
      }
      is DocOp.RemoveProp -> withWidget(op.target) { w ->
        val old = w.props[op.name]
        val inv = if (old == null) null else DocOp.SetProp(op.target, op.name, old)
        ApplyResult(replaced(w.copy(props = w.props - op.name)), inv)
      }
      is DocOp.SetModifier -> withWidget(op.target) { w ->
        val old = w.modifiers[op.name]
        val inv = if (old == null) DocOp.RemoveModifier(op.target, op.name) else DocOp.SetModifier(op.target, op.name, old)
        ApplyResult(replaced(w.copy(modifiers = w.modifiers + (op.name to op.value))), inv)
      }
      is DocOp.RemoveModifier -> withWidget(op.target) { w ->
        val old = w.modifiers[op.name]
        val inv = if (old == null) null else DocOp.SetModifier(op.target, op.name, old)
        ApplyResult(replaced(w.copy(modifiers = w.modifiers - op.name)), inv)
      }
      is DocOp.RenameId -> withWidget(op.target) { w ->
        ApplyResult(replaced(w.copy(explicitId = op.id)), DocOp.RenameId(op.target, w.explicitId))
      }
      is DocOp.ReplaceRaw -> {
        val raw = find(op.target) as? DocNode.RawCode ?: return err("handle ${op.target.v} is not RawCode")
        TryResult(ApplyResult(replaced(raw.copy(text = op.text)), DocOp.ReplaceRaw(op.target, raw.text)), null)
      }
      is DocOp.ContractEdit -> TryResult(ApplyResult(copy(contract = op.contract), DocOp.ContractEdit(contract)), null)
    }
  }

  fun applyBatch(ops: List<DocOp>): TryBatch {
    var d = this
    val inverses = mutableListOf<DocOp>()
    for (op in ops) {
      val t = d.tryApply(op)
      val r = t.result ?: return TryBatch(null, t.error)
      d = r.doc
      r.inverse?.let { inverses.add(0, it) } // reverse order for undo
    }
    return TryBatch(BatchResult(d, inverses), null)
  }

  fun apply(inverseBatch: List<DocOp>, dummy: Unit = Unit): ApplyResult = apply(inverseBatch)

  private inline fun withWidget(h: Handle, f: (DocNode.Widget) -> ApplyResult): TryResult {
    val w = find(h) as? DocNode.Widget ?: return TryResult(null, "unknown handle ${h.v}")
    return TryResult(f(w), null)
  }

  private fun replaced(newNode: DocNode): UiDocument = copy(root = replaceIn(root, newNode))
}

/** Internal op: reinsert an existing subtree (original handles) — undo of Delete/Move. */
@Serializable
internal data class InsertExisting(val parent: Handle, val after: Handle?, val node: DocNode) : DocOp

private fun findIn(n: DocNode, h: Handle): DocNode? {
  if (n.handle == h) return n
  if (n is DocNode.Widget) n.children.forEach { c -> findIn(c, h)?.let { return it } }
  return null
}

private fun parentOf(n: DocNode, h: Handle): DocNode.Widget? {
  if (n !is DocNode.Widget) return null
  if (n.children.any { it.handle == h }) return n
  n.children.forEach { c -> parentOf(c, h)?.let { return it } }
  return null
}

private fun replaceIn(n: DocNode, newNode: DocNode): DocNode = when {
  n.handle == newNode.handle -> newNode
  n is DocNode.Widget -> n.copy(children = n.children.map { replaceIn(it, newNode) })
  else -> n
}

/** Renumber an inserted subtree from [next]; returns node + next free handle. */
private fun allocate(n: DocNode, next: Long): Pair<DocNode, Long> = when (n) {
  is DocNode.RawCode -> n.copy(handle = Handle(next)) to next + 1
  is DocNode.Widget -> {
    var cursor = next + 1
    val kids = n.children.map { c -> allocate(c, cursor).also { cursor = it.second }.first }
    n.copy(handle = Handle(next), children = kids) to cursor
  }
}
```
Note the two API subtleties the tests pin down: **inverse of a Delete/Move reinserts with ORIGINAL handles** (`InsertExisting`, internal — never sent by clients; reject it at the server boundary), and **InsertNode allocates handles server-side** from `nextHandle` (clients pass handle 0).
- [ ] **Step 4: GREEN** `./gradlew :portal-document:jvmTest` (fix `apply(List)` overload collisions if the compiler complains — keep one `replay(ops: List<DocOp>)` name instead; update tests accordingly).
- [ ] **Step 5: Commit** `feat(portal-document): UiDocument apply/invert engine — atomic batches, sibling anchors, handle allocation`

### Task 3: Projection to the V1 interchange

**Files:** create `Project.kt`, `ProjectTest.kt`.

- [ ] **Step 1: failing test**
```kotlin
package dev.keliver.portal.document

import dev.keliver.portal.WidgetNode
import kotlin.test.Test
import kotlin.test.assertEquals

class ProjectTest {
  @Test fun projectsWidgetsBindsActionsModifiersAndRaw() {
    val doc = UiDocument(
      screen = "main",
      root = DocNode.Widget(Handle(1), "Column", children = listOf(
        DocNode.Widget(Handle(2), "StyledText", props = mapOf(
          "text" to PropValue.Bind("title"),
          "fontSize" to lit(22),
        )),
        DocNode.Widget(Handle(3), "Button",
          props = mapOf("text" to lit("Buy"), "onClick" to PropValue.Action("buy")),
          modifiers = mapOf("Padding.allDp" to lit(8))),
        DocNode.RawCode(Handle(4), "if (b.x) { }", kindHint = "condition"),
      )),
      contract = Contract(fields = mapOf("title" to "String"), actions = listOf("buy")),
      version = 3, nextHandle = 10,
    )
    val tree: WidgetNode = doc.toWidgetTree(mocks = mapOf("title" to "Hello"))
    assertEquals("Column", tree.type)
    val text = tree.children[0]
    assertEquals("Hello", text.props["text"])            // bind -> mock value
    assertEquals(22, text.props["fontSize"])
    val button = tree.children[1]
    assertEquals("act:buy", button.props["ev.onClick"])   // action wire tag (P3 convention)
    assertEquals(8, button.props["mod.Padding.allDp"])    // modifier namespaced prop
    val raw = tree.children[2]
    assertEquals("RawCode", raw.type)                     // interpreter placeholder
    assertEquals("condition", raw.props["kindHint"])
  }
}
```
(Anchor check before writing: `grep -n "ev\.\|act:" portal-core/src/commonMain/kotlin/dev/keliver/portal/Bindings.kt` — reuse the P3 wire conventions EXACTLY as found there; adjust the two assertions to the real prefixes if they differ.)
- [ ] **Step 2: implement `Project.kt`**
```kotlin
package dev.keliver.portal.document

import dev.keliver.portal.WidgetNode

/**
 * Projects the Document onto the V1 interchange tree: binds resolve via
 * [mocks] (editor preview) or fall back to "{field}", actions/modifiers use
 * the P3 wire conventions, RawCode becomes the interpreter placeholder type.
 */
fun UiDocument.toWidgetTree(mocks: Map<String, Any?> = emptyMap()): WidgetNode = nodeToTree(root, mocks)

private fun nodeToTree(n: DocNode, mocks: Map<String, Any?>): WidgetNode = when (n) {
  is DocNode.RawCode -> WidgetNode(
    type = "RawCode",
    props = buildMap {
      put("text", n.text)
      n.kindHint?.let { put("kindHint", it) }
    },
  )
  is DocNode.Widget -> WidgetNode(
    type = n.type,
    props = buildMap {
      n.props.forEach { (name, v) ->
        when (v) {
          is PropValue.Lit -> put(name, v.toAny())
          is PropValue.Bind -> put(name, mocks[v.field] ?: "{${v.field}}")
          is PropValue.Action -> put("ev.$name", "act:${v.name}")
        }
      }
      n.modifiers.forEach { (name, v) -> if (v is PropValue.Lit) put("mod.$name", v.toAny()) }
    },
    children = n.children.map { nodeToTree(it, mocks) },
  )
}
```
(Use the verified P3 prefixes from Step 1's grep. If the generated interpreter has no "RawCode" branch it renders the unknown-widget placeholder — acceptable M1 behavior, M5 adds a proper island box.)
- [ ] **Step 3: GREEN** `./gradlew :portal-document:jvmTest :portal-document:compileKotlinJs :portal-document:compileKotlinWasmJs`
- [ ] **Step 4: Commit** `feat(portal-document): projection to the V1 interchange (binds/actions/modifiers/raw)`

### Task 4: DocumentService in portal-server

**Files:** create `portal-relay/src/main/kotlin/DocumentService.kt`; modify `portal-relay/build.gradle` (`implementation projects.portalDocument`).

- [ ] **Step 1: implement** (JVM; JDK-only like Relay.kt)
```kotlin
/*
 * V2 M1 — the per-screen live Document engine (design §2): validate -> apply
 * -> bump version -> broadcast (SSE) -> project into the V1 draft store ->
 * debounced dual-write of the projected .kt (whole-file until M4).
 */
import dev.keliver.portal.document.DocJson
import dev.keliver.portal.document.DocNode
import dev.keliver.portal.document.DocOp
import dev.keliver.portal.document.Contract
import dev.keliver.portal.document.Handle
import dev.keliver.portal.document.OpAck
import dev.keliver.portal.document.OpBatch
import dev.keliver.portal.document.UiDocument
import dev.keliver.portal.document.toWidgetTree
import dev.keliver.portal.exportKotlin
import dev.keliver.portal.deserializeTree
import dev.keliver.portal.WidgetNode
import kotlinx.serialization.encodeToString
import java.io.File
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DocumentService(
  private val screenKey: String,                 // "project/screen"
  initial: UiDocument,
  private val onProjected: (WidgetNode) -> Unit, // feeds the existing draft store
  private val kotlinFile: File,                  // dual-written projection
) {
  @Volatile var doc: UiDocument = initial; private set
  private val undo = ConcurrentHashMap<String, ArrayDeque<List<DocOp>>>()   // session -> inverse batches
  private val redo = ConcurrentHashMap<String, ArrayDeque<List<DocOp>>>()
  private val listeners = CopyOnWriteArrayList<OutputStream>()             // SSE clients
  private val writer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "doc-writeback").apply { isDaemon = true } }
  private var pendingWrite: java.util.concurrent.ScheduledFuture<*>? = null

  @Synchronized
  fun submit(batch: OpBatch): OpAck {
    if (batch.baseVersion != doc.version) {
      return OpAck(false, doc.version, "stale base version ${batch.baseVersion}, document is at ${doc.version}")
    }
    if (batch.ops.any { it.javaClass.simpleName == "InsertExisting" }) {
      return OpAck(false, doc.version, "internal op not accepted")
    }
    val res = doc.applyBatch(batch.ops)
    val ok = res.result ?: return OpAck(false, doc.version, res.error)
    doc = ok.doc.copy(version = doc.version + 1)
    undo.getOrPut(batch.envelope.session) { ArrayDeque() }.apply {
      addLast(ok.inverseBatch)
      while (size > 100) removeFirst()
    }
    redo.remove(batch.envelope.session)
    changed()
    return OpAck(true, doc.version)
  }

  @Synchronized
  fun undo(session: String): OpAck {
    val inverses = undo[session]?.removeLastOrNull() ?: return OpAck(false, doc.version, "nothing to undo")
    val res = doc.applyBatch(inverses)
    val ok = res.result ?: return OpAck(false, doc.version, "undo failed: ${res.error}")
    redo.getOrPut(session) { ArrayDeque() }.addLast(ok.inverseBatch)
    doc = ok.doc.copy(version = doc.version + 1)
    changed()
    return OpAck(true, doc.version)
  }

  @Synchronized
  fun redo(session: String): OpAck {
    val inverses = redo[session]?.removeLastOrNull() ?: return OpAck(false, doc.version, "nothing to redo")
    val res = doc.applyBatch(inverses)
    val ok = res.result ?: return OpAck(false, doc.version, "redo failed: ${res.error}")
    undo.getOrPut(session) { ArrayDeque() }.addLast(ok.inverseBatch)
    doc = ok.doc.copy(version = doc.version + 1)
    changed()
    return OpAck(true, doc.version)
  }

  fun subscribe(out: OutputStream) {
    listeners += out
    sendEvent(out, doc.version) // greet with current version
  }

  private fun changed() {
    val tree = doc.toWidgetTree()
    onProjected(tree)
    listeners.removeAll { out -> runCatching { sendEvent(out, doc.version) }.isFailure }
    pendingWrite?.cancel(false)
    pendingWrite = writer.schedule({ writeKotlin() }, 400, TimeUnit.MILLISECONDS)
  }

  private fun sendEvent(out: OutputStream, version: Long) {
    out.write("data: {\"version\":$version}\n\n".toByteArray())
    out.flush()
  }

  private fun writeKotlin() {
    runCatching {
      kotlinFile.parentFile.mkdirs()
      val header = "// GENERATED projection (until M4 write-back) — screen $screenKey v${doc.version}\n"
      kotlinFile.writeText(header + exportKotlin(doc.toWidgetTree(), functionName = "PortalScreen"))
    }
  }

  companion object {
    /** M1 bootstrap: lift an existing V1 draft tree into a Document. */
    fun fromTree(screenKey: String, treeJson: String?, onProjected: (WidgetNode) -> Unit, kotlinFile: File): DocumentService {
      var next = 1L
      fun lift(n: WidgetNode): DocNode.Widget = DocNode.Widget(
        handle = Handle(next++),
        type = n.type,
        props = n.props.filterKeys { !it.startsWith("mod.") && !it.startsWith("ev.") }
          .mapValues { (_, v) -> dev.keliver.portal.document.lit(v) },
        modifiers = n.props.filterKeys { it.startsWith("mod.") }
          .map { (k, v) -> k.removePrefix("mod.") to dev.keliver.portal.document.lit(v) }.toMap(),
        children = n.children.map { lift(it) },
      )
      val root = treeJson?.takeIf { it.isNotBlank() && it != "{}" }
        ?.let { lift(deserializeTree(it)) }
        ?: DocNode.Widget(Handle(next++), "Column")
      val doc = UiDocument(screenKey, root, Contract(), version = 0, nextHandle = next)
      return DocumentService(screenKey, doc, onProjected, kotlinFile)
    }
  }
}
```
(Adapt the bind/action lift if Step 1 of Task 3 found different P3 prefixes; keep symmetrical with `toWidgetTree`.)
- [ ] **Step 2: compile** `./gradlew :portal-relay:compileKotlin` → green.
- [ ] **Step 3: Commit** `feat(portal-server): DocumentService — transactional ops, per-session undo/redo, SSE, projection + .kt dual-write`

### Task 5: Routes in Relay.kt

**Files:** modify `portal-relay/src/main/kotlin/Relay.kt`.

- [ ] **Step 1: wire routes.** Read Relay.kt around the `createContext("/draft")` block; add a `documents = ConcurrentHashMap<String, DocumentService>()` beside the store, a helper
```kotlin
fun docFor(project: String, screen: String): DocumentService =
  documents.getOrPut("$project/$screen") {
    DocumentService.fromTree(
      screenKey = "$project/$screen",
      treeJson = readDraft(project, screen),              // the existing draft-file read helper
      onProjected = { tree -> writeDraft(project, screen, serializeTree(tree)) }, // existing write helper (keeps /tree + devices live)
      kotlinFile = File(portalHome, "kotlin/$project/${screen}.kt"),
    )
  }
```
and contexts (same style as the existing ones — query params `project`/`screen`, CORS headers via the existing helper):
```kotlin
server.createContext("/doc") { ex ->        // GET -> DocJson(document)
  respond(ex, 200, DocJson.encodeToString(docFor(p(ex), s(ex)).doc))
}
server.createContext("/ops") { ex ->        // POST OpBatch -> OpAck (200 ok / 409 stale / 422 invalid)
  val ack = docFor(p(ex), s(ex)).submit(DocJson.decodeFromString(body(ex)))
  respond(ex, if (ack.ok) 200 else if (ack.error?.startsWith("stale") == true) 409 else 422, DocJson.encodeToString(ack))
}
server.createContext("/undo") { ex -> respond(ex, 200, DocJson.encodeToString(docFor(p(ex), s(ex)).undo(sess(ex)))) }
server.createContext("/redo") { ex -> respond(ex, 200, DocJson.encodeToString(docFor(p(ex), s(ex)).redo(sess(ex)))) }
server.createContext("/doc-events") { ex -> // SSE
  ex.responseHeaders.add("Content-Type", "text/event-stream")
  ex.responseHeaders.add("Cache-Control", "no-cache")
  ex.responseHeaders.add("Access-Control-Allow-Origin", "*")
  ex.sendResponseHeaders(200, 0)
  docFor(p(ex), s(ex)).subscribe(ex.responseBody)
  // do NOT close — stream stays open
}
```
with `sess(ex)` = `X-Portal-Session` header or `"anon"`. Match the file's existing helper names exactly (`respond`, param parsing) — read before editing and reuse.
- [ ] **Step 2: integration smoke** (server running):
```bash
./gradlew :portal-relay:installDist && PORTAL_REPO=$PWD portal-relay/build/install/portal-relay/bin/portal-relay &
sleep 2
curl -s "localhost:8077/doc?project=default&screen=main" | head -c 200
V=$(curl -s "localhost:8077/doc?project=default&screen=main" | python3 -c "import sys,json;print(json.load(sys.stdin)['version'])")
ROOT=$(curl -s "localhost:8077/doc?project=default&screen=main" | python3 -c "import sys,json;print(json.load(sys.stdin)['root']['handle'])")
curl -s -X POST "localhost:8077/ops?project=default&screen=main" -H "X-Portal-Session: cli" -d "{\"baseVersion\":$V,\"envelope\":{\"session\":\"cli\",\"atMillis\":0},\"ops\":[{\"kind\":\"...InsertNode\",\"parent\":$ROOT,\"after\":null,\"node\":{\"kind\":\"...Widget\",\"handle\":0,\"type\":\"Button\",\"props\":{\"text\":{\"kind\":\"...Lit\",\"tag\":\"s\",\"s\":\"FromOps\"}}}}]}"
curl -s "localhost:8077/tree" | grep -o "FromOps"        # projection reached the device path
curl -s -X POST "localhost:8077/undo?project=default&screen=main" -H "X-Portal-Session: cli"
ls ~/.keliver-portal/kotlin/default/                     # dual-written main.kt exists
```
(the `kind` discriminator values = fully qualified serial names printed by `/doc` — copy from actual output; stale-version and unknown-handle cases should return 409/422.)
- [ ] **Step 3: Commit** `feat(portal-server): /doc /ops /undo /redo /doc-events routes — op engine live behind the existing draft/tree device path`

### Task 6: Editor emits ops

**Files:** modify `web-spike/src/wasmJsMain/kotlin/Portal.kt`, `web-spike/build.gradle` (`implementation project(':portal-document')`).

- [ ] **Step 1: read Portal.kt fully** (it changed in P2/P3). The refactor contract:
  1. Keep `portalTree` (the render state) but it becomes a MIRROR: set only from server state.
  2. Add a client Document mirror: `var docState: UiDocument` fetched from `/doc` (DocJson over XHR like existing fetch helpers) at mount + on every SSE version event on `/doc-events` (EventSource).
  3. On refresh: `portalTree.value = docState.toWidgetTree(mocks = currentMocks)` (`currentMocks` = existing P3 mock map).
  4. Replace every mutation site — the greps show `applyTree(portalTree.value.insertChild(...))` (palette add, DnD insert/move, prop edit, delete) — with `sendOps(listOf(DocOp...))`: build ops against `docState` handles. Selection state becomes `selectedHandle: Handle?`; outline rows carry handles (walk `docState.root` instead of the WidgetNode tree — same recursion, `DocNode.Widget.children`).
  5. `sendOps` = POST `/ops` with `OpBatch(docState.version, OpEnvelope("editor", nowMillis()), ops)`; on 409 → refetch `/doc` and toast "rebased"; on 422 → toast error text.
  6. Undo/redo buttons → POST `/undo` / `/redo` (session "editor"); remove the local `undoStack` machinery (lines ~77-153).
  7. Prop-edit mapping: `editProp(...)` sites build `DocOp.SetProp(handle, name, lit(value))`; bind toggle → `SetProp(handle, name, PropValue.Bind(field))`; event wiring → `SetProp(handle, name, PropValue.Action(name))` (replacing the P3 `ev.`-prefix prop writes — projection reproduces the wire props for the preview/devices).
- [ ] **Step 2: compile + dist** `./gradlew :web-spike:compileKotlinWasmJs :web-spike:wasmJsBrowserDevelopmentExecutableDistribution`
- [ ] **Step 3: browser verify** (serve dist on :8096, chrome-ext): add a widget from the palette → appears in preview AND `curl /doc` shows it with a fresh handle; edit a prop → live; Undo → reverts (server-side); reload the page → state intact (server-held); second tab sees the first tab's edit via SSE within ~1s; device (if emulator up) mirrors via the unchanged `/tree`.
- [ ] **Step 4: Commit** `refactor(portal-editor): editor is an op-emitting client — server document, SSE refresh, server-side undo`

### Task 7: Gate sweep + docs + wrap

- [ ] **Step 1:** `./gradlew :portal-document:jvmTest :portal-document:compileKotlinJs :portal-document:compileKotlinWasmJs :portal-relay:installDist :web-spike:compileKotlinWasmJs :portal-schema-codegen:test :portal-schema-codegen:checkPortalCode` → all green.
- [ ] **Step 2:** update `docs/superpowers/specs/2026-07-04-keliver-portal-v2-validation-register.md` — append "M1 SHIPPED" line; update memory (M1 done, learnings).
- [ ] **Step 3:** Commit `feat(portal): V2 M1 complete — live document + op engine end to end` and push.

---

## Self-review notes (resolved)

- **Spec coverage:** §1 model ✓ (T1), handles+allocation ✓ (T2), §2 ops/transactions/undo/SSE ✓ (T2/T4/T5), envelope+sibling anchors ✓ (T1), projection/interchange ✓ (T3), dual-write ✓ (T4), editor-as-client ✓ (T6). RawCode + ReplaceRaw present in schema (exercised M3/M5). ContractEdit present; editor bindings panel migrates to it in T6 step 1.7.
- **Type consistency:** `Handle`, `PropValue`, `DocOp.*`, `UiDocument.applyBatch/TryBatch`, `OpBatch/OpAck`, `toWidgetTree(mocks)` used consistently across T2–T6; `InsertExisting` internal + server-rejected (T4 submit guard).
- **Known-risk steps carry verify-first instructions:** P3 wire prefixes (T3.1 grep), Relay helper names (T5.1 read-first), Portal.kt current shape (T6.1 read-first), serial-name discriminators (T5.2 copy from `/doc` output).
