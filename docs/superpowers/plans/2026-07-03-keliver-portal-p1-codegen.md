# Portal Phase 1: Schema Codegen Engine — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the three hand-maintained 7-widget `when()`s (catalog / RenderNode interpreter / exportKotlin) with code GENERATED from the keliver-material + keliver-layout schema sources, shared via a new `portal-render` module — unlocking ~60 widgets + modifiers in the portal, web preview, device guest, and Kotlin exporter from one source of truth.

**Architecture:** New JVM tool module `portal-schema-codegen` calls keliver's own FIR schema parser (`dev.keliver.tooling.schema.parseProtocolSchema`) twice (material + layout), maps traits to portal prop kinds via one type table, and emits three committed files: `GeneratedCatalog.kt` + `GeneratedExporter.kt` (→ portal-core) and `GeneratedRenderNode.kt` (→ new `portal-render`, js+wasmJs, consumed by BOTH web-spike and portal-device-guest — ends the copy-paste). A `--check` mode gives CI staleness protection. Widgets whose *required* props aren't expressible in the tree wire format are excluded and reported.

**Tech Stack:** Kotlin JVM, `keliver-tooling-schema` parser (FqType/Widget/Trait model), plain string-building emitters (no KotlinPoet), kotlin.test, Gradle JavaExec.

**Repo:** `/Users/sanchitwalia/AndroidStudioProjects/konduit`, branch `spike/keliver-web`. All builds need `export JAVA_HOME=$(/usr/libexec/java_home -v 17)`.

**Locked design decisions**
- Generate from **material + layout** schemas; **skip lazylayout** (needs data binding = P3).
- Supported prop types: `String`, `Int`, `Boolean`, `Double`, `Float` (stored as Double in tree), `List<Int>`, `List<Float>`, `Dp` (Double), `Constraint`/`CrossAxisAlignment`/`MainAxisAlignment` (Int via tiny hand helpers in `RenderSupport.kt`). Anything else: prop **omitted** if it has a default; widget **excluded** if the prop is required.
- Nullable events → omitted from generated calls (null). Non-nullable event or >1 `@Children` slot → widget excluded (multi-slot = later phase).
- Simple-name collision across schemas → **layout wins** (matches today's "Column"/"Spacer" semantics), logged.
- Exporter emits **present props only, plus required props always** (compile-safe, minimal output).
- Catalog keeps today's public API (`editableProps(type)`) so `Portal.kt` keeps compiling, and adds `widgetSpecs` for the palette.
- Modifiers ride as namespaced props (`"mod.Padding.allDp" to 12`) — **no wire-format change**. Unscoped modifiers only; `object` modifiers become Bool flags.

**File map**
```
portal-schema-codegen/                       NEW jvm tool
  build.gradle
  src/main/kotlin/dev/keliver/portal/codegen/
    PropModel.kt      — type table + widget planning (pure, unit-tested)
    EmitCatalog.kt    — GeneratedCatalog.kt emitter (pure, unit-tested)
    EmitRenderNode.kt — GeneratedRenderNode.kt emitter (pure, unit-tested)
    EmitExporter.kt   — GeneratedExporter.kt emitter (pure, unit-tested)
    Main.kt           — parse schemas, run emitters, write/--check
  src/test/kotlin/dev/keliver/portal/codegen/
    FakeSchema.kt, PropModelTest.kt, EmitCatalogTest.kt,
    EmitRenderNodeTest.kt, EmitExporterTest.kt
portal-render/                               NEW js+wasmJs library
  build.gradle
  src/commonMain/kotlin/dev/keliver/portal/render/
    RenderSupport.kt        — hand-written enum helpers
    GeneratedRenderNode.kt  — GENERATED (committed)
portal-core/src/commonMain/kotlin/dev/keliver/portal/
  CatalogTypes.kt           — hand-written PropKind/PropSpec/WidgetSpec
  GeneratedCatalog.kt       — GENERATED (committed); replaces Catalog.kt (deleted)
  GeneratedExporter.kt      — GENERATED (committed); replaces Export.kt (deleted)
web-spike/            — delete RenderNode.kt, dep :portal-render, Portal.kt palette
portal-device-guest/  — delete RenderNode.kt, dep :portal-render
settings.gradle       — include the 2 new modules
.github/workflows/ci.yml — checkPortalCode guard
```

---

### Task 1: Module skeleton + schema smoke parse

**Files:**
- Create: `portal-schema-codegen/build.gradle`
- Create: `portal-schema-codegen/src/main/kotlin/dev/keliver/portal/codegen/Main.kt` (smoke version)
- Modify: `settings.gradle` (after the `':portal-device-android'` include line)

- [ ] **Step 1: Verify schema module facts** (source dir layout, package names, test-dep style)

Run:
```bash
cd /Users/sanchitwalia/AndroidStudioProjects/konduit
ls keliver-material-schema/src keliver-layout-schema/src
grep -rn "^package" keliver-material-schema/src -m1 --include=*.kt | head -3
grep -rn "^package" keliver-layout-schema/src -m1 --include=*.kt | head -3
grep -n "interface RedwoodLayout" -r keliver-layout-schema/src
sed -n '1,40p' keliver-material-schema/build.gradle 2>/dev/null || sed -n '1,40p' keliver-material-schema/build.gradle.kts
grep -n "testImplementation" keliver-tooling-codegen/build.gradle | head -5
```
Expected: source dirs (likely `src/main/kotlin` — schema modules are JVM), package `dev.keliver.material` / `dev.keliver.layout`, `RedwoodLayout` FQ name confirmed. **If paths/packages differ from this plan's assumptions, use the observed values in ALL subsequent steps.**

- [ ] **Step 2: Write `portal-schema-codegen/build.gradle`**

```gradle
// spike/keliver-web portal P1 — schema→portal codegen tool. Parses the
// keliver-material + keliver-layout schema SOURCES with keliver's own FIR
// parser and generates the portal widget catalog, the WidgetNode interpreter
// (portal-render), and the Kotlin exporter (portal-core). See
// docs/superpowers/specs/2026-07-03-keliver-portal-sota-design.md §2.
apply plugin: 'org.jetbrains.kotlin.jvm'
apply plugin: 'application'

application {
  mainClass = 'dev.keliver.portal.codegen.MainKt'
}

dependencies {
  implementation projects.keliverToolingSchema
  testImplementation libs.kotlin.test
}

// Classpath handed to the FIR parser: compiled schema modules + their deps
// (keliver-schema annotations, dependency schemas' embedded JSON).
configurations {
  schemaClasspath {
    canBeConsumed = false
    attributes {
      attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage, Usage.JAVA_RUNTIME))
    }
  }
}
dependencies {
  schemaClasspath projects.keliverMaterialSchema
  schemaClasspath projects.keliverLayoutSchema
}

def codegenArgs = { extra ->
  [
    '--material-sources', rootProject.file('keliver-material-schema/src/main/kotlin').absolutePath,
    '--layout-sources', rootProject.file('keliver-layout-schema/src/main/kotlin').absolutePath,
    '--classpath', configurations.schemaClasspath.asPath,
    '--out-core', rootProject.file('portal-core/src/commonMain/kotlin').absolutePath,
    '--out-render', rootProject.file('portal-render/src/commonMain/kotlin').absolutePath,
  ] + extra
}

tasks.register('generatePortalCode', JavaExec) {
  group = 'build'
  description = 'Regenerate GeneratedCatalog/GeneratedExporter/GeneratedRenderNode from the schemas'
  classpath = sourceSets.main.runtimeClasspath + configurations.schemaClasspath
  mainClass = 'dev.keliver.portal.codegen.MainKt'
  args = codegenArgs([])
}

tasks.register('checkPortalCode', JavaExec) {
  group = 'verification'
  description = 'Fail if committed generated portal code is stale vs the schemas'
  classpath = sourceSets.main.runtimeClasspath + configurations.schemaClasspath
  mainClass = 'dev.keliver.portal.codegen.MainKt'
  args = codegenArgs(['--check'])
}
```
(If `libs.kotlin.test` doesn't resolve, use the test-dep style observed in Step 1 from keliver-tooling-codegen; fallback `testImplementation kotlin('test')`. If the schema source dirs from Step 1 differ, fix the two `--*-sources` paths.)

- [ ] **Step 3: Add includes to `settings.gradle`**

After the line `include ':portal-device-android' // spike/keliver-web portal M2 Option X: Android Treehouse host`:
```gradle
include ':portal-schema-codegen' // spike/keliver-web portal P1: schema→portal codegen tool
include ':portal-render' // spike/keliver-web portal P1: generated WidgetNode interpreter (js+wasm, shared web+device)
```
(portal-render's build file arrives in Task 6; Gradle tolerates an include with just a directory — create `portal-render/` dir with an empty `build.gradle` stub now: `// placeholder — filled in P1 Task 6`.)

- [ ] **Step 4: Write smoke `Main.kt`**

`portal-schema-codegen/src/main/kotlin/dev/keliver/portal/codegen/Main.kt`:
```kotlin
package dev.keliver.portal.codegen

import dev.keliver.tooling.schema.FqType
import dev.keliver.tooling.schema.ProtocolSchemaSet
import dev.keliver.tooling.schema.parseProtocolSchema
import java.io.File

internal class Args(argv: Array<String>) {
  private val map = argv.toList().chunked(2).filter { it.size == 2 }.associate { it[0] to it[1] }
  val check: Boolean = argv.contains("--check")
  val materialSources = File(map.getValue("--material-sources"))
  val layoutSources = File(map.getValue("--layout-sources"))
  val classpath: List<File> = map.getValue("--classpath").split(File.pathSeparator).map(::File)
  val outCore = File(map.getValue("--out-core"))
  val outRender = File(map.getValue("--out-render"))
}

internal fun parseSchemas(args: Args): Pair<ProtocolSchemaSet, ProtocolSchemaSet> {
  val javaHome = File(System.getProperty("java.home"))
  val material = parseProtocolSchema(
    javaHome, listOf(args.materialSources), args.classpath,
    FqType(listOf("dev.keliver.material", "KeliverMaterial")),
  )
  val layout = parseProtocolSchema(
    javaHome, listOf(args.layoutSources), args.classpath,
    FqType(listOf("dev.keliver.layout", "RedwoodLayout")),
  )
  return material to layout
}

fun main(argv: Array<String>) {
  val args = Args(argv)
  val (material, layout) = parseSchemas(args)
  println("material widgets=${material.schema.widgets.size} modifiers=${material.schema.modifiers.size}")
  println("layout widgets=${layout.schema.widgets.size} modifiers=${layout.schema.modifiers.size}")
}
```
(Adjust the two `FqType` package strings to Step 1's observed packages if different. If `parseProtocolSchema`'s signature differs, mirror `keliver-tooling-schema` testParser usage exactly.)

- [ ] **Step 5: Smoke run**

Run:
```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
cd /Users/sanchitwalia/AndroidStudioProjects/konduit
./gradlew :portal-schema-codegen:run -q
```
Expected: `material widgets=60 modifiers=21` (± a couple) and `layout widgets=4 modifiers=9` (± a couple). No exceptions. Debug classpath/sources until green.

- [ ] **Step 6: Commit**
```bash
git add portal-schema-codegen settings.gradle portal-render
git commit -m "feat(portal-codegen): module skeleton + FIR schema smoke parse (material+layout)"
```

---

### Task 2: PropModel — type table + widget planning (TDD)

**Files:**
- Create: `portal-schema-codegen/src/main/kotlin/dev/keliver/portal/codegen/PropModel.kt`
- Create: `portal-schema-codegen/src/test/kotlin/dev/keliver/portal/codegen/FakeSchema.kt`
- Create: `portal-schema-codegen/src/test/kotlin/dev/keliver/portal/codegen/PropModelTest.kt`

- [ ] **Step 1: Write `FakeSchema.kt`** (test doubles over the parser's interfaces)

```kotlin
package dev.keliver.portal.codegen

import dev.keliver.tooling.schema.Deprecation
import dev.keliver.tooling.schema.FqType
import dev.keliver.tooling.schema.Widget

internal fun fq(vararg names: String, params: List<FqType> = emptyList(), nullable: Boolean = false) =
  FqType(names.toList(), parameterTypes = params, nullable = nullable)

internal data class FakeProperty(
  override val name: String,
  override val type: FqType,
  override val defaultExpression: String? = null,
  override val documentation: String? = null,
  override val deprecation: Deprecation? = null,
) : Widget.Property

internal data class FakeEvent(
  override val name: String,
  override val parameters: List<Widget.Event.Parameter> = emptyList(),
  override val isNullable: Boolean = true,
  override val defaultExpression: String? = "null",
  override val documentation: String? = null,
  override val deprecation: Deprecation? = null,
) : Widget.Event

internal data class FakeChildren(
  override val name: String,
  override val scope: FqType? = null,
  override val defaultExpression: String? = null,
  override val documentation: String? = null,
  override val deprecation: Deprecation? = null,
) : Widget.Children

internal data class FakeWidget(
  override val type: FqType,
  override val traits: List<Widget.Trait>,
  override val documentation: String? = null,
  override val deprecation: Deprecation? = null,
  override val internalComposable: Boolean = false,
) : Widget
```
(If `Widget.Property`/`Event`/`Children` declare additional members (e.g. protocol `tag`), the compiler will say so — add them to the fakes with dummy values. If `Event.Parameter` needs a fake, add `FakeParameter(name, type)`.)

- [ ] **Step 2: Write the failing tests** — `PropModelTest.kt`

```kotlin
package dev.keliver.portal.codegen

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PropModelTest {
  private fun plan(w: FakeWidget) = planWidget(composePackage = "dev.keliver.material.compose", category = "Material", widget = w)

  @Test fun allSupportedKindsMap() {
    val w = FakeWidget(fq("dev.keliver.material", "Thing"), listOf(
      FakeProperty("text", fq("kotlin", "String")),
      FakeProperty("count", fq("kotlin", "Int"), defaultExpression = "14"),
      FakeProperty("on", fq("kotlin", "Boolean"), defaultExpression = "false"),
      FakeProperty("ratio", fq("kotlin", "Double"), defaultExpression = "0.0"),
      FakeProperty("pos", fq("kotlin", "Float"), defaultExpression = "0f"),
      FakeProperty("colors", fq("kotlin.collections", "List", params = listOf(fq("kotlin", "Int"))), defaultExpression = "emptyList()"),
      FakeProperty("stops", fq("kotlin.collections", "List", params = listOf(fq("kotlin", "Float"))), defaultExpression = "emptyList()"),
      FakeProperty("height", fq("dev.keliver.ui", "Dp"), defaultExpression = "Dp(0.0)"),
      FakeProperty("width", fq("dev.keliver.layout.api", "Constraint"), defaultExpression = "Constraint.Wrap"),
    ))
    val inc = assertIs<WidgetPlan.Include>(plan(w))
    assertEquals(
      listOf(MappedKind.TEXT, MappedKind.INT, MappedKind.BOOL, MappedKind.DOUBLE, MappedKind.FLOAT,
        MappedKind.INT_LIST, MappedKind.FLOAT_LIST, MappedKind.DP, MappedKind.CONSTRAINT),
      inc.props.map { it.kind },
    )
    assertTrue(inc.props.first { it.name == "text" }.required)
    assertTrue(!inc.props.first { it.name == "count" }.required)
  }

  @Test fun requiredUnsupportedPropExcludesWidget() {
    val w = FakeWidget(fq("dev.keliver.material", "RichText"), listOf(
      FakeProperty("spans", fq("kotlin.collections", "List", params = listOf(fq("dev.keliver.material.api", "TextSpan")))),
    ))
    val ex = assertIs<WidgetPlan.Exclude>(plan(w))
    assertTrue(ex.reason.contains("spans"))
  }

  @Test fun optionalUnsupportedPropIsSkippedNotFatal() {
    val w = FakeWidget(fq("dev.keliver.material", "TextInput"), listOf(
      FakeProperty("state", fq("dev.keliver.material.api", "TextFieldState"), defaultExpression = "TextFieldState()"),
      FakeProperty("hint", fq("kotlin", "String"), defaultExpression = "\"\""),
    ))
    val inc = assertIs<WidgetPlan.Include>(plan(w))
    assertEquals(listOf("hint"), inc.props.map { it.name })
    assertEquals(listOf("state"), inc.skippedProps)
  }

  @Test fun childrenAndEvents() {
    val one = FakeWidget(fq("dev.keliver.material", "Card"), listOf(FakeChildren("children")))
    assertTrue(assertIs<WidgetPlan.Include>(plan(one)).hasChildren)

    val two = FakeWidget(fq("dev.keliver.material", "Scaffold"),
      listOf(FakeChildren("topBar"), FakeChildren("content")))
    assertIs<WidgetPlan.Exclude>(plan(two))

    val ev = FakeWidget(fq("dev.keliver.material", "Button"), listOf(
      FakeProperty("text", fq("kotlin", "String")),
      FakeEvent("onClick", isNullable = true),
    ))
    val inc = assertIs<WidgetPlan.Include>(plan(ev))
    assertEquals(listOf("onClick"), inc.events)

    val evReq = FakeWidget(fq("dev.keliver.material", "Weird"), listOf(FakeEvent("onThing", isNullable = false, defaultExpression = null)))
    assertIs<WidgetPlan.Exclude>(plan(evReq))
  }

  @Test fun defaultParsing() {
    assertEquals(14, defaultInt("14"))
    assertEquals(-1, defaultInt("-1"))
    assertEquals(0, defaultInt("Constraint.Wrap"))
    assertEquals(1, constraintDefault("Constraint.Fill"))
    assertEquals(0, constraintDefault("Constraint.Wrap"))
    assertEquals(3, crossAxisDefault("CrossAxisAlignment.Stretch"))
    assertEquals(true, defaultBool("true"))
    assertEquals(0.0, defaultDouble("0f"))
    assertEquals("", defaultString("\"\""))
    assertEquals("hi", defaultString("\"hi\""))
  }
}
```

- [ ] **Step 3: Run tests, verify they fail**

Run: `./gradlew :portal-schema-codegen:test 2>&1 | tail -20`
Expected: compilation failure — `planWidget`/`WidgetPlan`/`MappedKind` undefined.

- [ ] **Step 4: Implement `PropModel.kt`**

```kotlin
package dev.keliver.portal.codegen

import dev.keliver.tooling.schema.FqType
import dev.keliver.tooling.schema.Widget

enum class MappedKind { TEXT, INT, BOOL, DOUBLE, FLOAT, INT_LIST, FLOAT_LIST, DP, CONSTRAINT, CROSS_AXIS, MAIN_AXIS }

data class MappedProp(
  val name: String,
  val kind: MappedKind,
  val required: Boolean,
  val defaultExpr: String?,
) {
  val isColor: Boolean get() = kind == MappedKind.INT && name.endsWith("Argb")
}

sealed interface WidgetPlan {
  data class Include(
    val name: String,               // simple name, == WidgetNode.type
    val composePackage: String,     // e.g. dev.keliver.material.compose
    val category: String,           // "Material" / "Layout"
    val props: List<MappedProp>,
    val skippedProps: List<String>, // unsupported-with-default, omitted from calls
    val events: List<String>,       // nullable events, omitted from calls
    val hasChildren: Boolean,
  ) : WidgetPlan
  data class Exclude(val name: String, val reason: String) : WidgetPlan
}

private fun FqType.key(): String = names.joinToString(".")

internal fun mapType(t: FqType): MappedKind? = when {
  t.key() == "kotlin.String" -> MappedKind.TEXT
  t.key() == "kotlin.Int" -> MappedKind.INT
  t.key() == "kotlin.Boolean" -> MappedKind.BOOL
  t.key() == "kotlin.Double" -> MappedKind.DOUBLE
  t.key() == "kotlin.Float" -> MappedKind.FLOAT
  t.key() == "kotlin.collections.List" && t.parameterTypes.size == 1 &&
    t.parameterTypes[0].key() == "kotlin.Int" -> MappedKind.INT_LIST
  t.key() == "kotlin.collections.List" && t.parameterTypes.size == 1 &&
    t.parameterTypes[0].key() == "kotlin.Float" -> MappedKind.FLOAT_LIST
  t.key() == "dev.keliver.ui.Dp" -> MappedKind.DP
  t.key() == "dev.keliver.layout.api.Constraint" -> MappedKind.CONSTRAINT
  t.key() == "dev.keliver.layout.api.CrossAxisAlignment" -> MappedKind.CROSS_AXIS
  t.key() == "dev.keliver.layout.api.MainAxisAlignment" -> MappedKind.MAIN_AXIS
  else -> null
}

internal fun defaultInt(e: String?): Int = e?.trim()?.toIntOrNull() ?: 0
internal fun defaultBool(e: String?): Boolean = e?.trim() == "true"
internal fun defaultDouble(e: String?): Double = e?.trim()?.removeSuffix("f")?.toDoubleOrNull() ?: 0.0
internal fun defaultString(e: String?): String =
  e?.trim()?.takeIf { it.length >= 2 && it.startsWith('"') && it.endsWith('"') }?.removeSurrounding("\"") ?: ""
internal fun constraintDefault(e: String?): Int = if (e?.contains("Fill") == true) 1 else 0
internal fun crossAxisDefault(e: String?): Int = when {
  e == null -> 0
  e.contains("Center") -> 1
  e.contains("End") -> 2
  e.contains("Stretch") -> 3
  else -> 0
}
internal fun mainAxisDefault(e: String?): Int = when {
  e == null -> 0
  e.contains("SpaceBetween") -> 3
  e.contains("SpaceAround") -> 4
  e.contains("SpaceEvenly") -> 5
  e.contains("Center") -> 1
  e.contains("End") -> 2
  else -> 0
}

fun planWidget(composePackage: String, category: String, widget: Widget): WidgetPlan {
  val name = widget.type.names.last()
  val props = mutableListOf<MappedProp>()
  val skipped = mutableListOf<String>()
  val events = mutableListOf<String>()
  var childrenCount = 0
  for (trait in widget.traits) {
    when (trait) {
      is Widget.Property -> {
        val kind = mapType(trait.type)
        val required = trait.defaultExpression == null
        when {
          kind != null -> props += MappedProp(trait.name, kind, required, trait.defaultExpression)
          required -> return WidgetPlan.Exclude(name, "required prop '${trait.name}' has unsupported type ${trait.type}")
          else -> skipped += trait.name
        }
      }
      is Widget.Event -> {
        if (!trait.isNullable && trait.defaultExpression == null) {
          return WidgetPlan.Exclude(name, "required non-nullable event '${trait.name}'")
        }
        events += trait.name
      }
      is Widget.Children -> childrenCount++
    }
  }
  if (childrenCount > 1) return WidgetPlan.Exclude(name, "multiple children slots ($childrenCount)")
  return WidgetPlan.Include(name, composePackage, category, props, skipped, events, childrenCount == 1)
}
```
(If `Widget.Trait` has more subtypes than Property/Event/Children the `when` needs an `else -> {}` branch — the compiler will tell you.)

- [ ] **Step 5: Run tests to green**

Run: `./gradlew :portal-schema-codegen:test 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`, 5 tests passing.

- [ ] **Step 6: Commit**
```bash
git add portal-schema-codegen/src
git commit -m "feat(portal-codegen): PropModel — schema trait→portal kind table + widget planning (TDD)"
```

---

### Task 3: Catalog emitter (TDD)

**Files:**
- Create: `portal-core/src/commonMain/kotlin/dev/keliver/portal/CatalogTypes.kt`
- Create: `portal-schema-codegen/src/main/kotlin/dev/keliver/portal/codegen/EmitCatalog.kt`
- Create: `portal-schema-codegen/src/test/kotlin/dev/keliver/portal/codegen/EmitCatalogTest.kt`

- [ ] **Step 1: Write `CatalogTypes.kt`** (hand-written, stable — generated code references these)

```kotlin
package dev.keliver.portal

/** Editor-facing property kinds. Extended by codegen needs; keep in sync with the editor's property panel. */
enum class PropKind { Text, Int, Bool, Color, Double, IntList, FloatList }

data class PropSpec(val name: String, val kind: PropKind, val label: String)

data class WidgetSpec(
  val type: String,
  val category: String,
  val props: List<PropSpec>,
  val acceptsChildren: Boolean,
  /** Values a palette-add starts with: every required prop, sensible defaults. */
  val sampleProps: Map<String, Any?>,
)
```

Delete nothing yet — old `Catalog.kt` still owns `editableProps` until Task 7.
**Conflict check:** old `Catalog.kt` also declares `PropKind`/`PropSpec`. To keep both compiling until Task 7, put the NEW declarations in place of the old ones: delete the `PropKind`/`PropSpec` declarations from `Catalog.kt` (keep its `editableProps` function; it compiles against the new types — the old enum values are a subset) and add `CatalogTypes.kt` as above in the same commit.

- [ ] **Step 2: Failing test** — `EmitCatalogTest.kt`

```kotlin
package dev.keliver.portal.codegen

import kotlin.test.Test
import kotlin.test.assertContains

class EmitCatalogTest {
  private val text = WidgetPlan.Include(
    name = "StyledText", composePackage = "dev.keliver.material.compose", category = "Material",
    props = listOf(
      MappedProp("text", MappedKind.TEXT, required = true, defaultExpr = null),
      MappedProp("fontSize", MappedKind.INT, required = false, defaultExpr = "14"),
      MappedProp("colorArgb", MappedKind.INT, required = false, defaultExpr = "0"),
    ),
    skippedProps = emptyList(), events = emptyList(), hasChildren = false,
  )
  private val card = WidgetPlan.Include(
    name = "Card", composePackage = "dev.keliver.material.compose", category = "Material",
    props = emptyList(), skippedProps = emptyList(), events = emptyList(), hasChildren = true,
  )

  @Test fun emitsWidgetSpecsAndCompatFunction() {
    val src = emitCatalog(listOf(text, card))
    assertContains(src, "package dev.keliver.portal")
    assertContains(src, "val widgetSpecs: List<WidgetSpec> = listOf(")
    assertContains(src, "WidgetSpec(\"StyledText\", \"Material\", listOf(")
    assertContains(src, "PropSpec(\"text\", PropKind.Text, \"Text\")")
    assertContains(src, "PropSpec(\"colorArgb\", PropKind.Color, \"Color argb\")")
    assertContains(src, "acceptsChildren = true")
    assertContains(src, "\"text\" to \"New StyledText\"")   // required prop in sampleProps
    assertContains(src, "fun editableProps(type: String): List<PropSpec>")
  }
}
```

- [ ] **Step 3: Run to verify fail**: `./gradlew :portal-schema-codegen:test 2>&1 | tail -10` — expected: `emitCatalog` unresolved.

- [ ] **Step 4: Implement `EmitCatalog.kt`**

```kotlin
package dev.keliver.portal.codegen

internal fun catalogKind(p: MappedProp): String = when {
  p.isColor -> "Color"
  else -> when (p.kind) {
    MappedKind.TEXT -> "Text"
    MappedKind.INT, MappedKind.CONSTRAINT, MappedKind.CROSS_AXIS, MappedKind.MAIN_AXIS -> "Int"
    MappedKind.BOOL -> "Bool"
    MappedKind.DOUBLE, MappedKind.FLOAT, MappedKind.DP -> "Double"
    MappedKind.INT_LIST -> "IntList"
    MappedKind.FLOAT_LIST -> "FloatList"
  }
}

/** camelCase → "Camel case" */
internal fun humanize(name: String): String {
  val words = name.replace(Regex("([a-z0-9])([A-Z])"), "$1 $2").lowercase()
  return words.replaceFirstChar { it.uppercase() }
}

private fun sampleValue(widgetName: String, p: MappedProp): String? = when {
  !p.required -> null
  else -> when (p.kind) {
    MappedKind.TEXT -> "\"New $widgetName\""
    MappedKind.INT, MappedKind.CONSTRAINT, MappedKind.CROSS_AXIS, MappedKind.MAIN_AXIS -> "0"
    MappedKind.BOOL -> "false"
    MappedKind.DOUBLE, MappedKind.FLOAT, MappedKind.DP -> "0.0"
    MappedKind.INT_LIST -> "listOf<Int>()"
    MappedKind.FLOAT_LIST -> "listOf<Float>()"
  }
}

fun emitCatalog(widgets: List<WidgetPlan.Include>): String = buildString {
  appendLine("// GENERATED by :portal-schema-codegen — do not edit. Run ./gradlew :portal-schema-codegen:generatePortalCode")
  appendLine("package dev.keliver.portal")
  appendLine()
  appendLine("val widgetSpecs: List<WidgetSpec> = listOf(")
  for (w in widgets.sortedBy { it.name }) {
    append("  WidgetSpec(\"${w.name}\", \"${w.category}\", listOf(")
    if (w.props.isNotEmpty()) {
      appendLine()
      for (p in w.props) {
        appendLine("    PropSpec(\"${p.name}\", PropKind.${catalogKind(p)}, \"${humanize(p.name)}\"),")
      }
      append("  )")
    } else {
      append(")")
    }
    append(", acceptsChildren = ${w.hasChildren}, sampleProps = ")
    val samples = w.props.mapNotNull { p -> sampleValue(w.name, p)?.let { "\"${p.name}\" to $it" } }
    if (samples.isEmpty()) append("emptyMap()") else append("mapOf(${samples.joinToString(", ")})")
    appendLine("),")
  }
  appendLine(")")
  appendLine()
  appendLine("private val specsByType = widgetSpecs.associateBy { it.type }")
  appendLine()
  appendLine("fun widgetSpec(type: String): WidgetSpec? = specsByType[type]")
  appendLine()
  appendLine("fun editableProps(type: String): List<PropSpec> = specsByType[type]?.props ?: emptyList()")
}
```

- [ ] **Step 5: Run tests to green**: `./gradlew :portal-schema-codegen:test 2>&1 | tail -5` — expected `BUILD SUCCESSFUL`.

- [ ] **Step 6: Compile portal-core with the type split**: `./gradlew :portal-core:compileKotlinJvm 2>&1 | tail -5` — expected green (CatalogTypes.kt + trimmed Catalog.kt coexist).

- [ ] **Step 7: Commit**
```bash
git add portal-schema-codegen/src portal-core/src
git commit -m "feat(portal-codegen): catalog emitter (TDD) + portal-core CatalogTypes split"
```

---

### Task 4: RenderNode emitter (TDD)

**Files:**
- Create: `portal-schema-codegen/src/main/kotlin/dev/keliver/portal/codegen/EmitRenderNode.kt`
- Create: `portal-schema-codegen/src/test/kotlin/dev/keliver/portal/codegen/EmitRenderNodeTest.kt`

- [ ] **Step 1: Failing test** — `EmitRenderNodeTest.kt`

```kotlin
package dev.keliver.portal.codegen

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class EmitRenderNodeTest {
  private val text = WidgetPlan.Include(
    name = "StyledText", composePackage = "dev.keliver.material.compose", category = "Material",
    props = listOf(
      MappedProp("text", MappedKind.TEXT, required = true, defaultExpr = null),
      MappedProp("fontSize", MappedKind.INT, required = false, defaultExpr = "14"),
    ),
    skippedProps = emptyList(), events = listOf("onLongPress"), hasChildren = false,
  )
  private val column = WidgetPlan.Include(
    name = "Column", composePackage = "dev.keliver.layout.compose", category = "Layout",
    props = listOf(
      MappedProp("width", MappedKind.CONSTRAINT, required = false, defaultExpr = "Constraint.Wrap"),
      MappedProp("horizontalAlignment", MappedKind.CROSS_AXIS, required = false, defaultExpr = "CrossAxisAlignment.Start"),
    ),
    skippedProps = emptyList(), events = emptyList(), hasChildren = true,
  )
  private val spacer = WidgetPlan.Include(
    name = "Spacer", composePackage = "dev.keliver.layout.compose", category = "Layout",
    props = listOf(MappedProp("height", MappedKind.DP, required = false, defaultExpr = "Dp(0.0)")),
    skippedProps = emptyList(), events = emptyList(), hasChildren = false,
  )

  @Test fun emitsBranchesGettersAndChildren() {
    val src = emitRenderNode(listOf(text, column, spacer))
    assertContains(src, "package dev.keliver.portal.render")
    assertContains(src, "import dev.keliver.material.compose.StyledText")
    assertContains(src, "import dev.keliver.layout.compose.Column")
    assertContains(src, "import dev.keliver.ui.Dp")
    assertContains(src, "@Composable")
    assertContains(src, "fun RenderNode(node: WidgetNode)")
    assertContains(src, "\"StyledText\" -> StyledText(")
    assertContains(src, "text = node.str(\"text\"),")
    assertContains(src, "fontSize = node.int(\"fontSize\", 14),")
    assertContains(src, "width = constraintOf(node.int(\"width\", 0)),")
    assertContains(src, "horizontalAlignment = crossAxisOf(node.int(\"horizontalAlignment\", 0)),")
    assertContains(src, "height = Dp(node.dbl(\"height\", 0.0)),")
    assertContains(src, ") { node.children.forEach { RenderNode(it) } }")
    assertContains(src, "else -> StyledText(text = \"\\u26a0 unknown widget: \${node.type}\"")
    assertFalse("onLongPress" in src) // events omitted in P1
  }
}
```

- [ ] **Step 2: Run to verify fail** — `emitRenderNode` unresolved.

- [ ] **Step 3: Implement `EmitRenderNode.kt`**

```kotlin
package dev.keliver.portal.codegen

internal fun getterExpr(p: MappedProp): String = when (p.kind) {
  MappedKind.TEXT -> "node.str(\"${p.name}\", \"${defaultString(p.defaultExpr).replace("\\", "\\\\").replace("\"", "\\\"")}\")"
  MappedKind.INT -> "node.int(\"${p.name}\", ${defaultInt(p.defaultExpr)})"
  MappedKind.BOOL -> "node.bool(\"${p.name}\", ${defaultBool(p.defaultExpr)})"
  MappedKind.DOUBLE -> "node.dbl(\"${p.name}\", ${defaultDouble(p.defaultExpr)})"
  MappedKind.FLOAT -> "node.dbl(\"${p.name}\", ${defaultDouble(p.defaultExpr)}).toFloat()"
  MappedKind.INT_LIST -> "node.intList(\"${p.name}\")"
  MappedKind.FLOAT_LIST -> "node.floatList(\"${p.name}\")"
  MappedKind.DP -> "Dp(node.dbl(\"${p.name}\", ${defaultDouble(p.defaultExpr?.removePrefix("Dp(")?.removeSuffix(")"))}))"
  MappedKind.CONSTRAINT -> "constraintOf(node.int(\"${p.name}\", ${constraintDefault(p.defaultExpr)}))"
  MappedKind.CROSS_AXIS -> "crossAxisOf(node.int(\"${p.name}\", ${crossAxisDefault(p.defaultExpr)}))"
  MappedKind.MAIN_AXIS -> "mainAxisOf(node.int(\"${p.name}\", ${mainAxisDefault(p.defaultExpr)}))"
}

fun emitRenderNode(widgets: List<WidgetPlan.Include>): String = buildString {
  val sorted = widgets.sortedBy { it.name }
  appendLine("// GENERATED by :portal-schema-codegen — do not edit. Run ./gradlew :portal-schema-codegen:generatePortalCode")
  appendLine("package dev.keliver.portal.render")
  appendLine()
  appendLine("import androidx.compose.runtime.Composable")
  appendLine("import dev.keliver.portal.WidgetNode")
  appendLine("import dev.keliver.portal.bool")
  appendLine("import dev.keliver.portal.dbl")
  appendLine("import dev.keliver.portal.floatList")
  appendLine("import dev.keliver.portal.int")
  appendLine("import dev.keliver.portal.intList")
  appendLine("import dev.keliver.portal.str")
  if (sorted.any { w -> w.props.any { it.kind == MappedKind.DP } }) appendLine("import dev.keliver.ui.Dp")
  for (w in sorted) appendLine("import ${w.composePackage}.${w.name}")
  appendLine()
  appendLine("/** Interprets a portal WidgetNode tree as live keliver composables. */")
  appendLine("@Composable")
  appendLine("fun RenderNode(node: WidgetNode) {")
  appendLine("  when (node.type) {")
  for (w in sorted) {
    append("    \"${w.name}\" -> ${w.name}(")
    if (w.props.isNotEmpty()) {
      appendLine()
      for (p in w.props) appendLine("      ${p.name} = ${getterExpr(p)},")
      append("    )")
    } else {
      append(")")
    }
    if (w.hasChildren) append(" { node.children.forEach { RenderNode(it) } }")
    appendLine()
  }
  appendLine("    else -> StyledText(text = \"\\u26a0 unknown widget: \${node.type}\", colorArgb = -5238254)")
  appendLine("  }")
  appendLine("}")
}
```
(Note: the generated file relies on `constraintOf`/`crossAxisOf`/`mainAxisOf` living in the SAME package `dev.keliver.portal.render` — Task 6's `RenderSupport.kt`. The `else` branch assumes `StyledText` is always included — it is (all its props are supported kinds). Getter imports: if portal-core's getters are top-level in `dev.keliver.portal`, these imports are right; the compile gate in Task 7 confirms.)

- [ ] **Step 4: Run tests to green**: `./gradlew :portal-schema-codegen:test 2>&1 | tail -5`

- [ ] **Step 5: Commit**
```bash
git add portal-schema-codegen/src
git commit -m "feat(portal-codegen): RenderNode interpreter emitter (TDD)"
```

---

### Task 5: Exporter emitter (TDD)

**Files:**
- Create: `portal-schema-codegen/src/main/kotlin/dev/keliver/portal/codegen/EmitExporter.kt`
- Create: `portal-schema-codegen/src/test/kotlin/dev/keliver/portal/codegen/EmitExporterTest.kt`

- [ ] **Step 1: Failing test** — `EmitExporterTest.kt`

```kotlin
package dev.keliver.portal.codegen

import kotlin.test.Test
import kotlin.test.assertContains

class EmitExporterTest {
  private val text = WidgetPlan.Include(
    name = "StyledText", composePackage = "dev.keliver.material.compose", category = "Material",
    props = listOf(
      MappedProp("text", MappedKind.TEXT, required = true, defaultExpr = null),
      MappedProp("fontSize", MappedKind.INT, required = false, defaultExpr = "14"),
    ),
    skippedProps = emptyList(), events = emptyList(), hasChildren = false,
  )
  private val box = WidgetPlan.Include(
    name = "StyledBox", composePackage = "dev.keliver.material.compose", category = "Material",
    props = listOf(
      MappedProp("width", MappedKind.CONSTRAINT, required = false, defaultExpr = "Constraint.Wrap"),
      MappedProp("gradientStops", MappedKind.FLOAT_LIST, required = false, defaultExpr = "emptyList()"),
    ),
    skippedProps = emptyList(), events = emptyList(), hasChildren = true,
  )

  @Test fun emitsExporterWithPerWidgetBranches() {
    val src = emitExporter(listOf(text, box))
    assertContains(src, "package dev.keliver.portal")
    assertContains(src, "fun exportKotlin(tree: WidgetNode, functionName: String = \"ExportedScreen\"): String")
    // import map: type -> composable FQ name
    assertContains(src, "\"StyledText\" to \"dev.keliver.material.compose.StyledText\"")
    // required prop always emitted; optional only when present
    assertContains(src, "sb.append(\"\${indent}  text = \${fmtString(node.props[\"text\"] ?: \"\")},\\n\")")
    assertContains(src, "if (\"fontSize\" in node.props)")
    // children recursion + constraint literal + float list literal
    assertContains(src, "\"StyledBox\" ->")
    assertContains(src, "fmtConstraint")
    assertContains(src, "fmtFloatList")
    assertContains(src, "// unknown widget:")
  }
}
```

- [ ] **Step 2: Run to verify fail** — `emitExporter` unresolved.

- [ ] **Step 3: Implement `EmitExporter.kt`**

The generated file is self-contained (its own `fmt*` helpers), keeps the public `exportKotlin` signature, collects imports from the types actually used in the tree, always emits required props, emits optional props only when present:

```kotlin
package dev.keliver.portal.codegen

private fun literalExpr(p: MappedProp): String {
  val v = "node.props[\"${p.name}\"]"
  return when (p.kind) {
    MappedKind.TEXT -> "fmtString($v ?: \"\")"
    MappedKind.INT -> "fmtInt($v)"
    MappedKind.BOOL -> "fmtBool($v)"
    MappedKind.DOUBLE -> "fmtDouble($v)"
    MappedKind.FLOAT -> "fmtFloat($v)"
    MappedKind.INT_LIST -> "fmtIntList($v)"
    MappedKind.FLOAT_LIST -> "fmtFloatList($v)"
    MappedKind.DP -> "fmtDp($v)"
    MappedKind.CONSTRAINT -> "fmtConstraint($v)"
    MappedKind.CROSS_AXIS -> "fmtCrossAxis($v)"
    MappedKind.MAIN_AXIS -> "fmtMainAxis($v)"
  }
}

/** Support imports the exported code needs when a widget with this kind is used. */
private fun supportImports(w: WidgetPlan.Include): List<String> = buildList {
  if (w.props.any { it.kind == MappedKind.DP }) add("dev.keliver.ui.Dp")
  if (w.props.any { it.kind == MappedKind.CONSTRAINT }) add("dev.keliver.layout.api.Constraint")
  if (w.props.any { it.kind == MappedKind.CROSS_AXIS }) add("dev.keliver.layout.api.CrossAxisAlignment")
  if (w.props.any { it.kind == MappedKind.MAIN_AXIS }) add("dev.keliver.layout.api.MainAxisAlignment")
}

fun emitExporter(widgets: List<WidgetPlan.Include>): String = buildString {
  val sorted = widgets.sortedBy { it.name }
  appendLine("// GENERATED by :portal-schema-codegen — do not edit. Run ./gradlew :portal-schema-codegen:generatePortalCode")
  appendLine("package dev.keliver.portal")
  appendLine()
  appendLine("private val composableImport: Map<String, String> = mapOf(")
  for (w in sorted) appendLine("  \"${w.name}\" to \"${w.composePackage}.${w.name}\",")
  appendLine(")")
  appendLine()
  appendLine("private val supportImports: Map<String, List<String>> = mapOf(")
  for (w in sorted) {
    val sup = supportImports(w)
    if (sup.isNotEmpty()) appendLine("  \"${w.name}\" to listOf(${sup.joinToString(", ") { "\"$it\"" }}),")
  }
  appendLine(")")
  appendLine()
  appendLine(
    """
    |private fun collectTypes(node: WidgetNode, out: MutableSet<String>) {
    |  out += node.type
    |  node.children.forEach { collectTypes(it, out) }
    |}
    |
    |private fun fmtString(v: Any?): String = "\"" + v.toString().replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    |private fun fmtInt(v: Any?): String = ((v as? Int) ?: 0).toString()
    |private fun fmtBool(v: Any?): String = ((v as? Boolean) ?: false).toString()
    |private fun fmtDouble(v: Any?): String = ((v as? Double) ?: (v as? Int)?.toDouble() ?: 0.0).toString()
    |private fun fmtFloat(v: Any?): String = "${'$'}{fmtDouble(v)}f"
    |private fun fmtDp(v: Any?): String = "Dp(${'$'}{fmtDouble(v)})"
    |private fun fmtIntList(v: Any?): String = "listOf(" + ((v as? List<*>)?.joinToString(", ") ?: "") + ")"
    |private fun fmtFloatList(v: Any?): String = "listOf(" + ((v as? List<*>)?.joinToString(", ") { "${'$'}{it}f" } ?: "") + ")"
    |private fun fmtConstraint(v: Any?): String = if (((v as? Int) ?: 0) == 1) "Constraint.Fill" else "Constraint.Wrap"
    |private fun fmtCrossAxis(v: Any?): String = when ((v as? Int) ?: 0) {
    |  1 -> "CrossAxisAlignment.Center"; 2 -> "CrossAxisAlignment.End"; 3 -> "CrossAxisAlignment.Stretch"
    |  else -> "CrossAxisAlignment.Start"
    |}
    |private fun fmtMainAxis(v: Any?): String = when ((v as? Int) ?: 0) {
    |  1 -> "MainAxisAlignment.Center"; 2 -> "MainAxisAlignment.End"; 3 -> "MainAxisAlignment.SpaceBetween"
    |  4 -> "MainAxisAlignment.SpaceAround"; 5 -> "MainAxisAlignment.SpaceEvenly"
    |  else -> "MainAxisAlignment.Start"
    |}
    """.trimMargin(),
  )
  appendLine()
  appendLine("fun exportKotlin(tree: WidgetNode, functionName: String = \"ExportedScreen\"): String {")
  appendLine("  val used = mutableSetOf<String>()")
  appendLine("  collectTypes(tree, used)")
  appendLine("  val sb = StringBuilder()")
  appendLine("  sb.append(\"import androidx.compose.runtime.Composable\\n\")")
  appendLine("  val imports = (used.mapNotNull { composableImport[it] } + used.flatMap { supportImports[it] ?: emptyList() }).toSortedSet()")
  appendLine("  imports.forEach { sb.append(\"import \$it\\n\") }")
  appendLine("  sb.append(\"\\n@Composable\\nfun \$functionName() {\\n\")")
  appendLine("  emitNode(sb, tree, \"  \")")
  appendLine("  sb.append(\"}\\n\")")
  appendLine("  return sb.toString()")
  appendLine("}")
  appendLine()
  appendLine("private fun emitNode(sb: StringBuilder, node: WidgetNode, indent: String) {")
  appendLine("  when (node.type) {")
  for (w in sorted) {
    appendLine("    \"${w.name}\" -> {")
    appendLine("      sb.append(\"\${indent}${w.name}(\\n\")")
    for (p in w.props) {
      if (p.required) {
        appendLine("      sb.append(\"\${indent}  ${p.name} = \${${literalExpr(p)}},\\n\")")
      } else {
        appendLine("      if (\"${p.name}\" in node.props) sb.append(\"\${indent}  ${p.name} = \${${literalExpr(p)}},\\n\")")
      }
    }
    if (w.hasChildren) {
      appendLine("      sb.append(\"\$indent) {\\n\")")
      appendLine("      node.children.forEach { emitNode(sb, it, \"\$indent  \") }")
      appendLine("      sb.append(\"\$indent}\\n\")")
    } else {
      appendLine("      sb.append(\"\$indent)\\n\")")
    }
    appendLine("    }")
  }
  appendLine("    else -> sb.append(\"\$indent// unknown widget: \${node.type}\\n\")")
  appendLine("  }")
  appendLine("}")
}
```

- [ ] **Step 4: Run tests to green**: `./gradlew :portal-schema-codegen:test 2>&1 | tail -5`

- [ ] **Step 5: Commit**
```bash
git add portal-schema-codegen/src
git commit -m "feat(portal-codegen): Kotlin exporter emitter (TDD)"
```

---

### Task 6: portal-render module + RenderSupport.kt

**Files:**
- Modify: `portal-render/build.gradle` (replace the placeholder)
- Create: `portal-render/src/commonMain/kotlin/dev/keliver/portal/render/RenderSupport.kt`

- [ ] **Step 1: Write `portal-render/build.gradle`**

```gradle
// spike/keliver-web portal P1 — the GENERATED WidgetNode→keliver-material
// interpreter, shared by the web preview (wasmJs) and the device dev-guest (js).
apply plugin: 'org.jetbrains.kotlin.multiplatform'
apply plugin: 'org.jetbrains.kotlin.plugin.compose'
apply plugin: 'org.jetbrains.compose'

kotlin {
  js { browser() }
  wasmJs { browser() }
  sourceSets {
    commonMain {
      dependencies {
        api projects.portalCore
        api projects.keliverMaterialCompose
        api projects.keliverLayoutCompose
        implementation libs.jetbrains.compose.runtime
      }
    }
  }
}
```

- [ ] **Step 2: Write `RenderSupport.kt`** (hand-written enum bridges; tree stores Ints)

```kotlin
package dev.keliver.portal.render

import dev.keliver.layout.api.Constraint
import dev.keliver.layout.api.CrossAxisAlignment
import dev.keliver.layout.api.MainAxisAlignment

fun constraintOf(v: Int): Constraint = if (v == 1) Constraint.Fill else Constraint.Wrap

fun crossAxisOf(v: Int): CrossAxisAlignment = when (v) {
  1 -> CrossAxisAlignment.Center
  2 -> CrossAxisAlignment.End
  3 -> CrossAxisAlignment.Stretch
  else -> CrossAxisAlignment.Start
}

fun mainAxisOf(v: Int): MainAxisAlignment = when (v) {
  1 -> MainAxisAlignment.Center
  2 -> MainAxisAlignment.End
  3 -> MainAxisAlignment.SpaceBetween
  4 -> MainAxisAlignment.SpaceAround
  5 -> MainAxisAlignment.SpaceEvenly
  else -> MainAxisAlignment.Start
}
```
(If `MainAxisAlignment`/`CrossAxisAlignment` member names differ, `:portal-render:compileKotlinJs` in Task 7 will fail with the real names — fix the `when` branches AND the corresponding `fmtCrossAxis`/`fmtMainAxis` strings in `EmitExporter.kt` + `crossAxisDefault`/`mainAxisDefault` to match.)

- [ ] **Step 3: Commit**
```bash
git add portal-render
git commit -m "feat(portal-render): module skeleton + enum bridge helpers"
```

---

### Task 7: Main.kt generation + first real codegen run

**Files:**
- Modify: `portal-schema-codegen/src/main/kotlin/dev/keliver/portal/codegen/Main.kt` (full version)
- Delete: `portal-core/src/commonMain/kotlin/dev/keliver/portal/Catalog.kt`
- Delete: `portal-core/src/commonMain/kotlin/dev/keliver/portal/Export.kt`
- Generated: `portal-core/.../GeneratedCatalog.kt`, `portal-core/.../GeneratedExporter.kt`, `portal-render/.../GeneratedRenderNode.kt`

- [ ] **Step 1: Replace `Main.kt` with the full pipeline**

```kotlin
package dev.keliver.portal.codegen

import dev.keliver.tooling.schema.FqType
import dev.keliver.tooling.schema.ProtocolSchemaSet
import dev.keliver.tooling.schema.parseProtocolSchema
import java.io.File
import kotlin.system.exitProcess

internal class Args(argv: Array<String>) {
  private val map = argv.toList().chunked(2).filter { it.size == 2 }.associate { it[0] to it[1] }
  val check: Boolean = argv.contains("--check")
  val materialSources = File(map.getValue("--material-sources"))
  val layoutSources = File(map.getValue("--layout-sources"))
  val classpath: List<File> = map.getValue("--classpath").split(File.pathSeparator).map(::File)
  val outCore = File(map.getValue("--out-core"))
  val outRender = File(map.getValue("--out-render"))
}

internal fun parseSchemas(args: Args): Pair<ProtocolSchemaSet, ProtocolSchemaSet> {
  val javaHome = File(System.getProperty("java.home"))
  val material = parseProtocolSchema(
    javaHome, listOf(args.materialSources), args.classpath,
    FqType(listOf("dev.keliver.material", "KeliverMaterial")),
  )
  val layout = parseProtocolSchema(
    javaHome, listOf(args.layoutSources), args.classpath,
    FqType(listOf("dev.keliver.layout", "RedwoodLayout")),
  )
  return material to layout
}

fun main(argv: Array<String>) {
  val args = Args(argv)
  val (material, layout) = parseSchemas(args)

  val plans = mutableMapOf<String, WidgetPlan>() // simple name -> plan; layout wins collisions
  val excluded = mutableListOf<WidgetPlan.Exclude>()
  for (w in material.schema.widgets) {
    plans[w.type.names.last()] = planWidget("dev.keliver.material.compose", "Material", w)
  }
  for (w in layout.schema.widgets) {
    val name = w.type.names.last()
    if (name in plans) println("collision: '$name' defined in material too — layout wins")
    plans[name] = planWidget("dev.keliver.layout.compose", "Layout", w)
  }
  val includes = plans.values.filterIsInstance<WidgetPlan.Include>()
  excluded += plans.values.filterIsInstance<WidgetPlan.Exclude>()

  val outputs = mapOf(
    File(args.outCore, "dev/keliver/portal/GeneratedCatalog.kt") to emitCatalog(includes),
    File(args.outCore, "dev/keliver/portal/GeneratedExporter.kt") to emitExporter(includes),
    File(args.outRender, "dev/keliver/portal/render/GeneratedRenderNode.kt") to emitRenderNode(includes),
  )

  println("included=${includes.size} excluded=${excluded.size}")
  excluded.sortedBy { it.name }.forEach { println("  excluded ${it.name}: ${it.reason}") }
  includes.filter { it.skippedProps.isNotEmpty() }.sortedBy { it.name }.forEach {
    println("  ${it.name}: skipped props ${it.skippedProps}")
  }

  if (args.check) {
    val stale = outputs.filter { (f, content) -> !f.exists() || f.readText() != content }
    if (stale.isNotEmpty()) {
      stale.keys.forEach { println("STALE: $it") }
      println("Run ./gradlew :portal-schema-codegen:generatePortalCode and commit the result.")
      exitProcess(1)
    }
    println("generated portal code is up to date")
  } else {
    outputs.forEach { (f, content) ->
      f.parentFile.mkdirs()
      f.writeText(content)
      println("wrote $f")
    }
  }
}
```

- [ ] **Step 2: Delete the hand-written backends**
```bash
git rm portal-core/src/commonMain/kotlin/dev/keliver/portal/Catalog.kt \
       portal-core/src/commonMain/kotlin/dev/keliver/portal/Export.kt
```
(If Task 3 Step 1 left a trimmed `Catalog.kt` with only `editableProps`, it's fully deleted now — `GeneratedCatalog.kt` provides it.)

- [ ] **Step 3: Generate + compile everything**
```bash
./gradlew :portal-schema-codegen:generatePortalCode
./gradlew :portal-core:compileKotlinJvm :portal-core:compileKotlinJs :portal-core:compileKotlinWasmJs \
          :portal-render:compileKotlinJs :portal-render:compileKotlinWasmJs 2>&1 | tail -15
```
Expected first run: likely a handful of compile errors from real-schema surprises (enum member names, getter signatures, a widget the planner should exclude). Fix in the GENERATOR (never the generated files), re-run generate, re-compile, loop to green. Also re-run `./gradlew :portal-schema-codegen:test` after generator changes.
Sanity-check the report: `included` should be ≥ 50; every exclusion reason should be one of the three designed rules.

- [ ] **Step 4: Commit (generator fixes + generated code)**
```bash
git add -A portal-schema-codegen portal-core portal-render
git commit -m "feat(portal): first generated catalog/exporter/interpreter — ~60 widgets from schema"
```

---

### Task 8: Switch web-spike + portal-device-guest to generated code

**Files:**
- Delete: `web-spike/src/wasmJsMain/kotlin/RenderNode.kt`
- Delete: `portal-device-guest/src/jsMain/kotlin/dev/keliver/portaldevice/RenderNode.kt`
- Modify: `web-spike/build.gradle` (add `implementation project(':portal-render')` to wasmJsMain deps)
- Modify: `portal-device-guest/build.gradle` (add `implementation projects.portalRender` to jsMain deps)
- Modify: `web-spike/src/wasmJsMain/kotlin/Main.kt` and `web-spike/src/wasmJsMain/kotlin/Portal.kt` (imports + palette)
- Modify: `portal-device-guest/src/jsMain/kotlin/dev/keliver/portaldevice/RealPortalPresenter.kt` (import)

- [ ] **Step 1: Delete the two RenderNode copies, add deps, fix imports**

In both `Main.kt`/`RealPortalPresenter.kt` (and anywhere else `RenderNode` is referenced — `grep -rn "RenderNode" web-spike/src portal-device-guest/src`), import `dev.keliver.portal.render.RenderNode`.

- [ ] **Step 2: Read `web-spike/src/wasmJsMain/kotlin/Portal.kt`** and make two edits:

(a) **Palette from the catalog.** Where the palette buttons are built (the hardcoded list of ~4-5 types), replace with a `<select>` + Add button over the full catalog. Keep the existing insert helper (the same function the buttons call today, signature `insert(type: String, props: Map<String, Any?>)` or the local equivalent — adapt to what's there):

```kotlin
// Palette: full generated catalog, grouped by category.
val select = document.createElement("select") as HTMLSelectElement
for ((category, specs) in widgetSpecs.groupBy { it.category }) {
  val group = document.createElement("optgroup") as HTMLOptGroupElement
  group.label = category
  for (spec in specs) {
    val opt = document.createElement("option") as HTMLOptionElement
    opt.value = spec.type
    opt.textContent = spec.type + if (spec.acceptsChildren) " ▸" else ""
    group.appendChild(opt)
  }
  select.appendChild(group)
}
val addBtn = (document.createElement("button") as HTMLButtonElement).apply { textContent = "Add" }
addBtn.onclick = {
  widgetSpec(select.value)?.let { spec -> /* call the existing insert path with spec.type and spec.sampleProps */ }
  null
}
```
Keep the existing quick-add buttons and drag-drop as-is (they still work — their types exist in the catalog).

(b) **Property panel kinds.** Find the `when` over `PropKind` in the property-panel builder and add branches so it stays exhaustive with the new enum values:
```kotlin
PropKind.IntList, PropKind.FloatList -> { /* read-only row: */ label.textContent = spec.label + " (list — not editable yet)" }
```

- [ ] **Step 3: Compile both consumers**
```bash
./gradlew :web-spike:compileKotlinWasmJs :portal-device-guest:compileKotlinJs 2>&1 | tail -8
```
Expected: green. Fix import/name drift if not.

- [ ] **Step 4: Build the web dist (full check)**
```bash
./gradlew :web-spike:wasmJsBrowserDevelopmentExecutableDistribution 2>&1 | tail -5
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**
```bash
git add -A web-spike portal-device-guest
git commit -m "refactor(portal): web + device guest consume generated portal-render; catalog-driven palette"
```

---

### Task 9: Kitchen-sink export→compile verifier

**Files:**
- Modify: `web-spike-guest-compiler/src/main/kotlin/Main.kt` (or its actual main file — `grep -rn "exportKotlin" web-spike-guest-compiler/src`)

- [ ] **Step 1: Extend the verifier main to also export a kitchen-sink tree**

After the existing `Exported.kt` generation, add:
```kotlin
// Kitchen sink: one instance of EVERY included widget with its sampleProps —
// proves every generated exporter branch compiles against the real composables.
val kitchenSink = WidgetNode(
  type = "Column",
  props = emptyMap(),
  children = widgetSpecs.map { spec -> WidgetNode(type = spec.type, props = spec.sampleProps) },
)
val sinkFile = File(outDir, "ExportedKitchenSink.kt")
sinkFile.writeText("package exported\n\n" + exportKotlin(kitchenSink, functionName = "ExportedKitchenSink"))
println("wrote $sinkFile")
```
(Match the existing file's package/output conventions — mirror exactly how `Exported.kt` is written there, including any `rm` of prior output.)

- [ ] **Step 2: Run the loop**
```bash
rm -f web-spike-guest-compiler/src/main/kotlin/exported/Exported.kt web-spike-guest-compiler/src/main/kotlin/exported/ExportedKitchenSink.kt
./gradlew :web-spike-guest-compiler:run
./gradlew :web-spike-guest-compiler:compileKotlin 2>&1 | tail -8
```
(Adjust paths to the module's real layout.) Expected: compile GREEN — every widget branch of the generated exporter produces valid Kotlin against the real keliver-material/layout composables. Failures here mean a generator bug (arg name/type mismatch): fix generator → regenerate → rerun.

- [ ] **Step 3: Commit**
```bash
git add -A web-spike-guest-compiler
git commit -m "test(portal): kitchen-sink export→compile gate — every generated widget branch compiles"
```

---

### Task 10: Staleness guard in CI

**Files:**
- Modify: `.github/workflows/ci.yml`

- [ ] **Step 1: Find where the web-spike wasm guard runs**: `grep -n "web-spike\|compileKotlinWasmJs" .github/workflows/ci.yml`

- [ ] **Step 2: Add the check task** to the same gradle invocation (or an adjacent step):
```yaml
      - name: Portal generated code is fresh
        run: ./gradlew :portal-schema-codegen:checkPortalCode
```
(Match the workflow's existing step style/indentation and JAVA_HOME setup.)

- [ ] **Step 3: Verify locally**
```bash
./gradlew :portal-schema-codegen:checkPortalCode   # expect: up to date, exit 0
```
Then temporarily edit one generated file (add a blank line), re-run, expect FAIL exit 1, revert the edit.

- [ ] **Step 4: Commit**
```bash
git add .github/workflows/ci.yml
git commit -m "ci(portal): checkPortalCode staleness guard for generated portal code"
```

---

### Task 11: Modifiers (catalog + interpreter + exporter)

**Files:**
- Modify: `portal-schema-codegen/src/main/kotlin/dev/keliver/portal/codegen/` — `PropModel.kt` (modifier planning), `EmitCatalog.kt`, `EmitRenderNode.kt`, `EmitExporter.kt`, `Main.kt`
- Modify: `portal-core/.../CatalogTypes.kt` (add `ModifierSpec`)
- Tests: extend the three emitter test files

- [ ] **Step 1: VERIFY the generated modifier extension naming** (ground truth before coding)
```bash
./gradlew :keliver-material-compose:compileKotlinJs -q 2>&1 | tail -2
grep -rn "fun Modifier\." keliver-material-compose/build/generated --include=*.kt | head -20
grep -rn "fun Modifier\." keliver-layout-compose/build/generated --include=*.kt | head -20
```
Expected (per Redwood convention): unscoped extensions like `public fun Modifier.background(colorArgb: Int): Modifier` in `dev.keliver.material.compose`, and the `Modifier` type is `dev.keliver.Modifier`. **Record the exact function names, receiver package, and which modifiers are unscoped** — the steps below assume `Modifier.<decapitalizedName>(args)`; if reality differs, use the observed names everywhere below. If material/layout scoped modifiers dominate and unscoped extensions don't exist in a usable form, STOP this task, commit what exists, and record modifiers as a follow-up finding — Tasks 1-10 already deliver the P1 core.

- [ ] **Step 2: Add modifier planning to `PropModel.kt`** (+ test in `PropModelTest.kt`)

```kotlin
data class ModPlan(
  val name: String,             // e.g. "Padding"
  val extensionName: String,    // e.g. "padding" (decapitalized; adjust per Step 1)
  val composePackage: String,   // where the extension lives
  val props: List<MappedProp>,  // empty for object modifiers (flags)
)

fun planModifier(composePackage: String, modifier: dev.keliver.tooling.schema.Modifier): ModPlan? {
  if (modifier.scopes.isNotEmpty()) return null                 // unscoped only in P1
  val name = modifier.type.names.last()
  if (name == "Reuse") return null                              // internal
  val props = mutableListOf<MappedProp>()
  for (p in modifier.properties) {
    val kind = mapType(p.type) ?: return null                   // any unsupported prop -> skip modifier
    props += MappedProp(p.name, kind, required = p.defaultExpression == null, defaultExpr = p.defaultExpression)
  }
  return ModPlan(name, name.replaceFirstChar { it.lowercase() }, composePackage, props)
}
```
Test (add to `PropModelTest.kt`; create a `FakeModifier`/`FakeModifierProperty` in `FakeSchema.kt` implementing `dev.keliver.tooling.schema.Modifier` and its `Property`):
```kotlin
  @Test fun modifierPlanning() {
    val padding = FakeModifier(fq("dev.keliver.material", "Padding"),
      properties = listOf(FakeModifierProperty("allDp", fq("kotlin", "Int"))))
    val plan = planModifier("dev.keliver.material.compose", padding)!!
    assertEquals("padding", plan.extensionName)
    assertEquals(listOf("allDp"), plan.props.map { it.name })

    val scoped = FakeModifier(fq("dev.keliver.material", "Weight"),
      scopes = listOf(fq("dev.keliver.material", "RowScope")),
      properties = emptyList())
    assertEquals(null, planModifier("dev.keliver.material.compose", scoped))

    val flag = FakeModifier(fq("dev.keliver.material", "AnimateContentSize"), properties = emptyList())
    assertEquals("animateContentSize", planModifier("dev.keliver.material.compose", flag)!!.extensionName)
  }
```

- [ ] **Step 3: Catalog** — add to `CatalogTypes.kt`:
```kotlin
data class ModifierSpec(val name: String, val props: List<PropSpec>)
```
and to `emitCatalog` (now `emitCatalog(widgets, modifiers: List<ModPlan>)`):
```kotlin
  appendLine("val modifierSpecs: List<ModifierSpec> = listOf(")
  for (m in modifiers.sortedBy { it.name }) {
    append("  ModifierSpec(\"${m.name}\", listOf(")
    append(m.props.joinToString(", ") { p -> "PropSpec(\"${p.name}\", PropKind.${catalogKind(p)}, \"${humanize(p.name)}\")" })
    appendLine(")),")
  }
  appendLine(")")
```
Convention: a modifier `Padding(allDp)` rides on the node as prop key `"mod.Padding.allDp"`; flag modifiers as `"mod.AnimateContentSize" to true`.

- [ ] **Step 4: Interpreter** — `emitRenderNode(widgets, modifiers)` gains a private builder and every widget call gains `modifier = nodeModifier(node),` as its first arg (generated composables all take `modifier: Modifier = Modifier`):
```kotlin
  appendLine("private fun nodeModifier(node: WidgetNode): Modifier {")
  appendLine("  var m: Modifier = Modifier")
  for (mod in modifiers.sortedBy { it.name }) {
    if (mod.props.isEmpty()) {
      appendLine("  if (node.bool(\"mod.${mod.name}\")) m = m.${mod.extensionName}()")
    } else {
      val presence = mod.props.joinToString(" || ") { "\"mod.${mod.name}.${it.name}\" in node.props" }
      val args = mod.props.joinToString(", ") { p ->
        getterExpr(p.copy(name = "mod.${mod.name}.${p.name}"))
      }
      appendLine("  if ($presence) m = m.${mod.extensionName}($args)")
    }
  }
  appendLine("  return m")
  appendLine("}")
```
plus imports: `dev.keliver.Modifier` and each extension (`import ${mod.composePackage}.${mod.extensionName}`). (Adjust `dev.keliver.Modifier` to the receiver type observed in Step 1.) Add an emitter test asserting `nodeModifier` contains the padding line and that a widget branch starts with `modifier = nodeModifier(node),`.

- [ ] **Step 5: Exporter** — emit a modifier chain when mod-props are present. In the generated exporter add:
```kotlin
private fun modifierExpr(node: WidgetNode): String? {
  val parts = mutableListOf<String>()
  // one generated line per modifier, e.g.:
  // if ("mod.Padding.allDp" in node.props) parts += "padding(${fmtInt(node.props["mod.Padding.allDp"])})"
  // if (node.props["mod.AnimateContentSize"] == true) parts += "animateContentSize()"
  return if (parts.isEmpty()) null else "Modifier." + parts.joinToString(".")
}
```
(the emitter writes those `if` lines from the ModPlans), and in every widget branch, first:
```kotlin
      modifierExpr(node)?.let { sb.append("${'$'}{indent}  modifier = ${'$'}it,\n") }
```
Exported files then need `import dev.keliver.Modifier` + the extension imports — add them to the used-imports computation when any node has a `mod.` prop. Extend `EmitExporterTest` accordingly.

- [ ] **Step 6: Regenerate, compile, verify**
```bash
./gradlew :portal-schema-codegen:test
./gradlew :portal-schema-codegen:generatePortalCode
./gradlew :portal-core:compileKotlinJvm :portal-render:compileKotlinJs :portal-render:compileKotlinWasmJs :web-spike:compileKotlinWasmJs :portal-device-guest:compileKotlinJs 2>&1 | tail -8
```
Then extend the kitchen-sink tree (Task 9 file) — give the FIRST child node two modifier props:
```kotlin
  children = widgetSpecs.mapIndexed { i, spec ->
    val mods = if (i == 0) mapOf("mod.Padding.allDp" to 8, "mod.CornerRadius.radiusDp" to 4) else emptyMap()
    WidgetNode(type = spec.type, props = spec.sampleProps + mods)
  },
```
(use two modifier names that survived planning — check `modifierSpecs` in the generated catalog) and re-run the Task 9 loop to green.

- [ ] **Step 7: Commit**
```bash
git add -A portal-schema-codegen portal-core portal-render web-spike-guest-compiler
git commit -m "feat(portal): modifiers end-to-end — catalog + interpreter chain + exporter chain from schema"
```

---

### Task 12: Runtime verification + docs + wrap-up

**Files:**
- Modify: `docs/WEB_SPIKE_FINDINGS.md` (P1 status note)
- Memory files (outside repo)

- [ ] **Step 1: Web runtime check**
```bash
./gradlew :web-spike:wasmJsBrowserDevelopmentExecutableDistribution
cd web-spike/build/dist/wasmJs/developmentExecutable && python3 -m http.server 8096 &
```
Open via claude-in-chrome (if connected): navigate `http://localhost:8096/index.html?b=p1`, screenshot. Expected: portal loads, palette `<select>` shows dozens of widgets grouped Material/Layout, adding e.g. a `Card` or `Slider` renders in the preview, property edit still live. If the Chrome extension is unavailable, record that runtime web check is pending and rely on the compile + dist gates.

- [ ] **Step 2: Device runtime check** (emulator + relay + zipline server, if available)
```bash
export ANDROID_HOME=$HOME/Library/Android/sdk
$ANDROID_HOME/platform-tools/adb devices   # if no emulator: $ANDROID_HOME/emulator/emulator -avd Pixel_9 -no-window &
./gradlew :portal-relay:run &              # :8077
./gradlew :portal-device-guest:serveDevelopmentZipline --info &   # :8080 — rebuilt guest with portal-render
./gradlew :portal-device-android:installDebug
adb shell am start -n dev.keliver.portaldevice/dev.keliver.portaldevice.host.MainActivity
# push a tree using a NEWLY-unlocked widget type (e.g. Card wrapping a Slider) via curl POST :8077/tree
adb exec-out screencap -p > /tmp/p1_device.png
```
Expected: the new widget renders natively — proof the generated interpreter runs on-device. If the emulator is RAM-flaky and won't boot, note it and fall back to the js compile gate.

- [ ] **Step 3: Update `docs/WEB_SPIKE_FINDINGS.md`** — add a short "P1 codegen engine (done)" note under the roadmap section pointing at the SOTA spec + this plan.

- [ ] **Step 4: Final commit + memory**
```bash
git add -A docs
git commit -m "docs(portal): P1 codegen engine complete — findings + roadmap updated"
```
Update `project_keliver_web_spike.md` memory + `MEMORY.md` index: P1 DONE (widget counts, exclusion list summary, kitchen-sink gate, staleness CI), next = P2 editor redesign + persistence.

---

## Self-review notes (run after drafting — resolved)

- **Spec coverage:** §2 codegen (Tasks 2-7) ✓; three backends ✓; committed + staleness (Task 10) ✓; portal-render shared module (Tasks 6, 8) ✓; modifiers (Task 11) ✓; "new widget appears automatically" — follows from generation ✓. P1 does NOT cover bindings/publish/iOS (later phases, per spec §9).
- **Type consistency:** `WidgetPlan.Include(name, composePackage, category, props, skippedProps, events, hasChildren)` used identically in Tasks 2-5, 7, 11; `MappedProp(name, kind, required, defaultExpr)` + `getterExpr`/`catalogKind` shared; emitters take `List<WidgetPlan.Include>` (+ `List<ModPlan>` after Task 11 — signatures updated in Task 11 Steps 3-4).
- **Known-risk steps carry explicit verify-then-adjust instructions:** schema source paths (T1S1), parser signature (T1S4), enum member names (T6S2), modifier extension naming (T11S1 — with a STOP escape hatch).
