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
package dev.keliver.bridge.codegen

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEqualTo
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.configureKsp
import com.tschuchort.compiletesting.kspSourcesDir
import java.io.File
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.Test

/**
 * The processor, through kctfork: golden files for what it writes (each case under
 * `src/test/resources/golden/<case>/`: the sources in `input`, what it writes in `expected`), compiled together
 * with the fixture (without the Zipline compiler plugin: that proves valid Kotlin, not Zipline's
 * adapter); every refusal of section 3 of `docs/BRIDGE_CODEGEN_DESIGN.md` with its message; and
 * what changes `SHAPE`. `-PupdateGolden` rewrites the expected files.
 */
@OptIn(ExperimentalCompilerApi::class)
class KeliverBridgeCodegenTest {
  private class Compiled(val exitCode: KotlinCompilation.ExitCode, val messages: String, val generated: Map<String, String>)

  private fun compile(vararg sources: SourceFile): Compiled {
    val compilation = KotlinCompilation().apply {
      this.sources = sources.toList()
      inheritClassPath = true
      messageOutputStream = System.out
      jvmTarget = "11"
      configureKsp { symbolProcessorProviders.add(KeliverBridgeCodegenProvider()) }
    }
    val result = compilation.compile()
    val root = compilation.kspSourcesDir
    val generated = root.walkTopDown().filter { it.isFile && it.extension == "kt" }
      .associate { it.relativeTo(root).invariantSeparatorsPath.substringAfter("kotlin/") to it.readText() }
    return Compiled(result.exitCode, result.messages, generated)
  }

  private fun source(text: String) = SourceFile.kotlin("Contract.kt", text.trimIndent())

  // --- golden files ---

  @Test fun shopActions() = golden("shop")

  @Test fun theDefaults() = golden("defaults")

  private fun golden(case: String) {
    val dir = File(System.getProperty("keliver.bridge.goldenDir"), case)
    val inputs = File(dir, "input").listFiles()!!.sorted().map { SourceFile.kotlin(it.name, it.readText()) }
    val compiled = compile(*inputs.toTypedArray())
    assertThat(compiled.exitCode, "messages: ${compiled.messages}").isEqualTo(KotlinCompilation.ExitCode.OK)
    val expectedDir = File(dir, "expected")
    if (System.getProperty("keliver.bridge.updateGolden") == "true") {
      expectedDir.deleteRecursively()
      expectedDir.mkdirs()
      compiled.generated.forEach { (path, text) -> File(expectedDir, path).apply { parentFile.mkdirs() }.writeText(text) }
    }
    val expected = expectedDir.walkTopDown().filter { it.isFile }
      .associate { it.relativeTo(expectedDir).invariantSeparatorsPath to it.readText() }
    assertThat(compiled.generated.keys.sorted()).isEqualTo(expected.keys.sorted())
    for ((name, text) in expected) assertThat(compiled.generated[name], name).isEqualTo(text)
  }

  // --- refusals ---

  private fun refused(contract: String, message: String) {
    val compiled = compile(source(contract))
    assertThat(compiled.exitCode).isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR)
    assertThat(compiled.messages).contains("@KeliverBridge: $message")
  }

  private val header = """
    package com.example
    import dev.keliver.bridge.api.BridgeTimeout
    import dev.keliver.bridge.api.KeliverBridge
    import kotlinx.serialization.Serializable
  """.trimIndent()

  @Test fun aClass() = refused("$header\n@KeliverBridge class A { suspend fun a(): String = \"\" }", "only an interface can be a bridge")

  @Test fun aNestedInterface() = refused(
    "$header\nobject Outer { @KeliverBridge interface A { suspend fun a(): String } }",
    "A must be a top-level interface",
  )

  @Test fun aGenericInterface() = refused("$header\n@KeliverBridge interface A<T> { suspend fun a(): String }", "A must not be generic")

  @Test fun anInterfaceExtendingAnother() = refused(
    "$header\ninterface Base { suspend fun b(): String }\n@KeliverBridge interface A : Base { suspend fun a(): String }",
    "A must not extend another interface",
  )

  @Test fun aProperty() = refused("$header\n@KeliverBridge interface A { val x: String\n suspend fun a(): String }", "A must not declare properties")

  @Test fun noFunctions() = refused("$header\n@KeliverBridge interface A", "A declares no functions")

  @Test fun anOverload() = refused(
    "$header\n@KeliverBridge interface A { suspend fun a(): String\n suspend fun a(x: Int): String }",
    "A.a is overloaded",
  )

  @Test fun anExtensionFunction() = refused("$header\n@KeliverBridge interface A { suspend fun String.a(): String }", "A.a must not be an extension function")

  @Test fun aGenericFunction() = refused("$header\n@KeliverBridge interface A { suspend fun <T> a(x: T): String }", "A.a must not be generic")

  @Test fun aBlockingCallWithAResult() = refused("$header\n@KeliverBridge interface A { fun a(): String }", "A.a must be suspend, or return Unit")

  @Test fun aSuspendCallReturningAList() = refused(
    "$header\n@KeliverBridge interface A { suspend fun a(): List<String> }",
    "A.a must not return a collection from a suspend function (KNOWN_BUGS U1)",
  )

  @Test fun aSuspendCallReturningAMap() = refused(
    "$header\n@KeliverBridge interface A { suspend fun a(): Map<String, String> }",
    "A.a must not return a collection",
  )

  @Test fun aVararg() = refused("$header\n@KeliverBridge interface A { suspend fun a(vararg x: String) }", "A.a's parameter x must not be vararg")

  @Test fun aDefaultValue() = refused("$header\n@KeliverBridge interface A { suspend fun a(x: String = \"\") }", "A.a's parameter x must not have a default value")

  @Test fun aFunctionParameter() = refused(
    "$header\n@KeliverBridge interface A { suspend fun a(x: (String) -> Unit) }",
    "A.a's parameter x must not be a function (KNOWN_BUGS U11)",
  )

  @Test fun aFlowParameter() = refused(
    "$header\nimport kotlinx.coroutines.flow.Flow\n@KeliverBridge interface A { suspend fun a(x: Flow<String>) }",
    "A.a's parameter x must not be a Flow",
  )

  @Test fun aZiplineServiceParameter() = refused(
    "$header\nimport app.cash.zipline.ZiplineService\ninterface S : ZiplineService\n@KeliverBridge interface A { suspend fun a(x: S) }",
    "A.a's parameter x must not be a ZiplineService",
  )

  @Test fun aTypeThatIsNotAValue() = refused(
    "$header\nclass Plain(val x: String)\n@KeliverBridge interface A { suspend fun a(x: Plain) }",
    "A.a's parameter x is com.example.Plain: use a primitive, String, an enum, a @Serializable class, or a List or Map of those",
  )

  @Test fun aListOfSomethingThatIsNotAValue() = refused(
    "$header\nclass Plain(val x: String)\n@KeliverBridge interface A { fun a(x: List<Plain>) }",
    "A.a's parameter x is com.example.Plain",
  )

  @Test fun aSetParameter() = refused("$header\n@KeliverBridge interface A { fun a(x: Set<String>) }", "A.a's parameter x is kotlin.collections.Set")

  @Test fun aResultThatIsNotAValue() = refused(
    "$header\nclass Plain(val x: String)\n@KeliverBridge interface A { suspend fun a(): Plain }",
    "A.a's result is com.example.Plain",
  )

  @Test fun aNegativeLimit() = refused(
    "$header\n@KeliverBridge(timeoutSeconds = -1) interface A { suspend fun a(): String }",
    "timeoutSeconds must be 0 (no limit) or more: -1",
  )

  @Test fun aNegativeFunctionLimit() = refused(
    "$header\n@KeliverBridge interface A { @BridgeTimeout(-5) suspend fun a(): String }",
    "A.a's @BridgeTimeout must be 0 (no limit) or more: -5",
  )

  @Test fun versionZero() = refused("$header\n@KeliverBridge(version = 0) interface A { suspend fun a(): String }", "version must be 1 or more: 0")

  @Test fun aNameThatIsNotABindingName() = refused(
    "$header\n@KeliverBridge(name = \"shop actions\") interface A { suspend fun a(): String }",
    "name 'shop actions' must be letters, digits",
  )

  @Test fun aNullableListResult() = refused("$header\n@KeliverBridge interface A { suspend fun a(): List<String>? }", "A.a must not return a collection")

  @Test fun aSetResult() = refused("$header\n@KeliverBridge interface A { suspend fun a(): Set<String> }", "A.a must not return a collection")

  @Test fun aStarProjection() = refused("$header\n@KeliverBridge interface A { fun a(x: List<*>) }", "A.a's parameter x must not use a star projection")

  @Test fun aSuspendFunctionParameter() = refused(
    "$header\n@KeliverBridge interface A { suspend fun a(x: suspend () -> Unit) }",
    "A.a's parameter x must not be a function",
  )

  @Test fun aFunctionWithABody() = refused(
    "$header\n@KeliverBridge interface A { suspend fun a(): String = \"\" }",
    "A.a must not have a body",
  )

  @Test fun aPrivateFunctionWithABody() = refused(
    "$header\n@KeliverBridge interface A { suspend fun a(): String\n private fun b() {} }",
    "A.b must not have a body",
  )

  @Test fun anInternalInterface() = refused("$header\n@KeliverBridge internal interface A { suspend fun a(): String }", "A must be public")

  @Test fun aFunctionNamedClose() = refused("$header\n@KeliverBridge interface A { suspend fun close() }", "A.close clashes with ZiplineService.close()")

  @Test fun anObjectiveCMethodFamily() = refused(
    "$header\n@KeliverBridge interface A { suspend fun newOrder(id: String) }",
    "A.newOrder starts with an Objective-C method family",
  )

  @Test fun objectiveCMethodFamiliesAsKotlinNativeSeesThem() {
    for (name in listOf("copy", "init", "new2", "_copyX", "init_x", "alloc2")) {
      refused("$header\n@KeliverBridge interface A { suspend fun $name(id: String) }", "A.$name starts with an Objective-C method family")
    }
  }

  @Test fun aGenericTypealias() = refused(
    "$header\ntypealias Tags<V> = Map<String, V>\n@KeliverBridge interface A { fun a(x: Tags<String>) }",
    "A uses the generic typealias Tags: write the type it stands for",
  )

  @Test fun aKeywordAsAParameterNameIsWrittenInBackticks() {
    val compiled = compile(source("$header\n@KeliverBridge interface A { suspend fun a(`in`: String): String\n fun b(`object`: String) }"))
    assertThat(compiled.exitCode, "messages: ${compiled.messages}").isEqualTo(KotlinCompilation.ExitCode.OK)
  }

  @Test fun aNameLikeAFamilyButNotOneIsAccepted() {
    val compiled = compile(source("$header\n@KeliverBridge interface A { suspend fun newsletter(id: String)\n suspend fun initials(): String }"))
    assertThat(compiled.exitCode, "messages: ${compiled.messages}").isEqualTo(KotlinCompilation.ExitCode.OK)
  }

  @Test fun aParameterNamedLikeTheGeneratedCode() = refused(
    "$header\n@KeliverBridge interface A { suspend fun a(service: String) }",
    "A.a's parameter service clashes with a name the generated code uses",
  )

  @Test fun aTypealiasIsCheckedAsWhatItStandsFor() {
    val ok = compile(source("$header\ntypealias Props = Map<String, String>\n@KeliverBridge interface A { fun a(x: Props) }"))
    assertThat(ok.exitCode, "messages: ${ok.messages}").isEqualTo(KotlinCompilation.ExitCode.OK)
    refused(
      "$header\nclass Plain\ntypealias P = Plain\n@KeliverBridge interface A { fun a(x: P) }",
      "A.a's parameter x is com.example.Plain",
    )
  }

  // --- SHAPE ---

  private fun shape(contract: String): String {
    val compiled = compile(source("$header\n$contract"))
    assertThat(compiled.exitCode, "messages: ${compiled.messages}").isEqualTo(KotlinCompilation.ExitCode.OK)
    return Regex("""SHAPE: String = "([0-9a-f]{16})"""").find(compiled.generated.getValue("com/example/ABridge.kt"))!!.groupValues[1]
  }

  private val base = """
    @Serializable class Req(val id: String, val kind: Kind)
    enum class Kind { HOME, WORK }
    @KeliverBridge interface A { suspend fun a(request: Req): Boolean }
  """.trimIndent()

  @Test fun parameterNamesDoNotChangeTheShape() {
    assertThat(shape(base.replace("a(request: Req)", "a(other: Req)"))).isEqualTo(shape(base))
  }

  @Test fun theOrderOfFunctionsDoesNotChangeTheShape() {
    val two = "@KeliverBridge interface A { suspend fun a(): String\n suspend fun b(): String }"
    val swapped = "@KeliverBridge interface A { suspend fun b(): String\n suspend fun a(): String }"
    assertThat(shape(swapped)).isEqualTo(shape(two))
  }

  @Test fun theOrderOfFieldsAndConstantsDoesNotChangeTheShape() {
    val swapped = base.replace("val id: String, val kind: Kind", "val kind: Kind, val id: String").replace("HOME, WORK", "WORK, HOME")
    assertThat(shape(swapped)).isEqualTo(shape(base))
  }

  @Test fun inheritedFieldsAndSealedSubclassesChangeTheShape() {
    val sealed = """
      @Serializable sealed class Pick { abstract val at: Long }
      @Serializable class Home(override val at: Long, val door: String) : Pick()
      @Serializable class Work(override val at: Long) : Pick()
      @KeliverBridge interface A { suspend fun a(): Pick }
    """.trimIndent()
    val shape = shape(sealed)
    assertThat(shape(sealed.replace("val door: String", "val door: Int"))).isNotEqualTo(shape)
    assertThat(shape(sealed.replace("@Serializable class Work(override val at: Long) : Pick()", "@Serializable class Work(override val at: Long) : Pick()\n@Serializable class Away(override val at: Long) : Pick()"))).isNotEqualTo(shape)
    val open = """
      @Serializable open class Base(val id: String)
      @Serializable class Req(val extra: String) : Base("x")
      @KeliverBridge interface A { suspend fun a(r: Req): Boolean }
    """.trimIndent()
    assertThat(shape(open.replace("open class Base(val id: String)", "open class Base(val id: Int)").replace("Base(\"x\")", "Base(1)"))).isNotEqualTo(shape(open))
  }

  @Test fun whatCrossesTheWireChangesTheShape() {
    val shape = shape(base)
    for (
    changed in listOf(
      base.replace("val id: String", "val id: Int"),
      base.replace("val id: String", "@kotlinx.serialization.SerialName(\"key\") val id: String"),
      base.replace("@Serializable class Req", "@Serializable @kotlinx.serialization.SerialName(\"R\") class Req"),
      base.replace("HOME, WORK", "HOME, WORK, OTHER"),
      base.replace("HOME, WORK", "@kotlinx.serialization.SerialName(\"home\") HOME, WORK"),
      base.replace("Boolean }", "String }"),
      base.replace("suspend fun a(request: Req)", "suspend fun a(request: Req?)"),
    )
    ) {
      assertThat(shape(changed), changed).isNotEqualTo(shape)
    }
  }
}
