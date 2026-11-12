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
import java.util.zip.ZipFile
import kotlin.metadata.KmClassifier
import kotlin.metadata.KmFunction
import kotlin.metadata.KmType
import kotlin.metadata.Visibility
import kotlin.metadata.declaresDefaultValue
import kotlin.metadata.isInline
import kotlin.metadata.isNullable
import kotlin.metadata.isSuspend
import kotlin.metadata.jvm.KotlinClassMetadata
import kotlin.metadata.jvm.Metadata
import kotlin.metadata.jvm.annotations
import kotlin.metadata.visibility
import org.jetbrains.org.objectweb.asm.AnnotationVisitor
import org.jetbrains.org.objectweb.asm.ClassReader
import org.jetbrains.org.objectweb.asm.ClassVisitor
import org.jetbrains.org.objectweb.asm.Opcodes

/**
 * Reads declarations from the Kotlin metadata of Compose's JVM jars (docs/COMPOSE_GENERATOR_DESIGN.md
 * §1): names, parameter names and types (with `@Composable` on function types), which parameters
 * declare a default, `inline`. Default expressions come from the sources ([SourceReader]).
 */
class ApiReader(jars: List<File>) {
  private class Entry(val zip: ZipFile, val name: String)

  /** Class-file entries by package path (`androidx/compose/foundation/layout`). */
  private val byPackage: Map<String, List<Entry>> = jars.filter { it.name.endsWith(".jar") }
    .flatMap { jar ->
      val zip = ZipFile(jar)
      zip.entries().asSequence()
        .filter { it.name.endsWith(".class") && !it.name.startsWith("META-INF") }
        .map { Entry(zip, it.name) }
        .toList()
    }
    .groupBy { it.name.substringBeforeLast('/', "") }

  /** Finds the declaration a config spec names: `pkg.name(p1,p2)` or `pkg.Owner#name(p1,p2)`. */
  fun find(spec: String): DeclInfo {
    val head = spec.substringBefore('(')
    // A parameter is `name` or `name:SimpleType` (to tell `size(size: Dp)` from `size(size: DpSize)`).
    val paramSpecs = spec.substringAfter('(').removeSuffix(")").split(',').map { it.trim() }.filter { it.isNotEmpty() }
    val paramNames = paramSpecs.map { it.substringBefore(':') }
    val owner = head.substringBefore('#', "").takeIf { '#' in head }?.substringAfterLast('.')
    val name = head.substringAfterLast('#').substringAfterLast('.')
    val pkg = if (owner != null) head.substringBefore('#').substringBeforeLast('.') else head.substringBeforeLast('.')
    val pkgPath = pkg.replace('.', '/')
    val entries = byPackage[pkgPath] ?: error("$spec: no classes in package $pkg on the classpath")
    val candidates = mutableListOf<KmFunction>()
    for (e in entries) {
      val simple = e.name.substringAfterLast('/').removeSuffix(".class")
      if (owner != null && simple != owner) continue
      val md = readMetadata(e) ?: continue
      val functions = when (val m = KotlinClassMetadata.readLenient(md)) {
        is KotlinClassMetadata.FileFacade -> if (owner == null) m.kmPackage.functions else emptyList()
        is KotlinClassMetadata.MultiFileClassPart -> if (owner == null) m.kmPackage.functions else emptyList()
        is KotlinClassMetadata.Class -> if (owner != null) m.kmClass.functions else emptyList()
        else -> emptyList()
      }
      candidates += functions.filter { f ->
        f.name == name &&
          f.visibility == Visibility.PUBLIC &&
          f.valueParameters.map { it.name } == paramNames &&
          paramSpecs.zip(f.valueParameters).all { (ps, vp) -> ':' !in ps || vp.type.toRef().simpleName == ps.substringAfter(':') }
      }
    }
    val f = candidates.distinctBy { fn -> fn.valueParameters.joinToString { it.name + ":" + it.type.toRef().canonical() } }
      .singleOrNull() ?: error("$spec: ${candidates.size} public declarations match (want exactly 1)")
    return DeclInfo(
      spec = spec,
      pkg = pkg,
      name = name,
      owner = owner,
      receiver = f.receiverParameterType?.toRef(),
      params = f.valueParameters.map { ParamInfo(it.name, it.type.toRef(), it.declaresDefaultValue) },

      returns = f.returnType.toRef(),
      isInline = f.isInline,
    )
  }

  private fun classMetadata(classFq: String): kotlin.metadata.KmClass? {
    val pkg = packageOf(classFq)
    val simple = classFq.removePrefix("$pkg.").replace('.', '$')
    val e = byPackage[pkg.replace('.', '/')]?.firstOrNull { it.name.substringAfterLast('/') == "$simple.class" } ?: return null
    return (KotlinClassMetadata.readLenient(readMetadata(e) ?: return null) as? KotlinClassMetadata.Class)?.kmClass
  }

  /** Every public top-level function [name] in [pkg] (or member of [owner]), as config specs. */
  fun overloadSpecs(pkg: String, owner: String?, name: String): List<String> {
    val entries = byPackage[pkg.replace('.', '/')] ?: return emptyList()
    val specs = mutableListOf<String>()
    for (e in entries) {
      if (owner != null && e.name.substringAfterLast('/').removeSuffix(".class") != owner) continue
      val md = readMetadata(e) ?: continue
      val functions = when (val m = KotlinClassMetadata.readLenient(md)) {
        is KotlinClassMetadata.FileFacade -> if (owner == null) m.kmPackage.functions else emptyList()
        is KotlinClassMetadata.MultiFileClassPart -> if (owner == null) m.kmPackage.functions else emptyList()
        is KotlinClassMetadata.Class -> if (owner != null) m.kmClass.functions else emptyList()
        else -> emptyList()
      }
      functions.filter { it.name == name && it.visibility == Visibility.PUBLIC }.forEach { f ->
        specs += "$pkg.${owner?.let { "$it#" } ?: ""}$name(${f.valueParameters.joinToString(",") { it.name }})"
      }
    }
    return specs.distinct()
  }

  /** A class's direct supertypes (`FlowRowScope` → `RowScope`, …), fully qualified. */
  fun supertypes(classFq: String): List<String> = classMetadata(classFq)?.supertypes.orEmpty()
    .mapNotNull { (it.classifier as? KmClassifier.Class)?.name?.replace('/', '.') }

  /** A public property of an object (`ButtonDefaults.shape`): its type, or null if there is none. */
  fun property(ownerFq: String, name: String): TypeRef? = classMetadata(ownerFq)?.properties
    ?.firstOrNull { it.name == name && it.visibility == Visibility.PUBLIC }?.returnType?.toRef()

  /** Whether [classFq] is a class callers outside its module can name (null: not a class on the classpath). */
  fun isPublicClass(classFq: String): Boolean? = classMetadata(classFq)?.visibility?.let { it == Visibility.PUBLIC }

  /** A property of a class or object of any visibility (`FilledButtonTokens.ContainerElevation`): its type. */
  fun anyProperty(ownerFq: String, name: String): TypeRef? =
    classMetadata(ownerFq)?.properties?.firstOrNull { it.name == name }?.returnType?.toRef()

  /**
   * A top-level property of [pkg] (`androidx.compose.material3.DisabledAlpha`): the JVM class that
   * holds it, its type, and whether it is public; null if the package has none by that name.
   */
  fun topLevelProperty(pkg: String, name: String): Triple<String, TypeRef, Boolean>? {
    for (e in byPackage[pkg.replace('.', '/')].orEmpty()) {
      val md = readMetadata(e) ?: continue
      val props = when (val m = KotlinClassMetadata.readLenient(md)) {
        is KotlinClassMetadata.FileFacade -> m.kmPackage.properties
        is KotlinClassMetadata.MultiFileClassPart -> m.kmPackage.properties
        else -> continue
      }
      val p = props.firstOrNull { it.name == name } ?: continue
      return Triple(e.name.removeSuffix(".class").replace('/', '.'), p.returnType.toRef(), p.visibility == Visibility.PUBLIC)
    }
    return null
  }

  /** Every public overload of an object's function (`ButtonDefaults.buttonColors`), in declaration order. */
  fun functions(ownerFq: String, name: String): List<DeclInfo> {
    val km = classMetadata(ownerFq) ?: return emptyList()
    val pkg = packageOf(ownerFq)
    val owner = ownerFq.removePrefix("$pkg.")
    return km.functions.filter { it.name == name && it.visibility == Visibility.PUBLIC }.map { f ->
      DeclInfo(
        spec = "$pkg.$owner#$name(${f.valueParameters.joinToString(",") { it.name }})",
        pkg = pkg,
        name = name,
        owner = owner,
        receiver = f.receiverParameterType?.toRef(),
        params = f.valueParameters.map { ParamInfo(it.name, it.type.toRef(), it.declaresDefaultValue) },
        returns = f.returnType.toRef(),
        isInline = f.isInline,
      )
    }
  }

  private fun readMetadata(e: Entry): Metadata? {
    val bytes = e.zip.getInputStream(e.zip.getEntry(e.name)).use { it.readBytes() }
    var result: Metadata? = null
    ClassReader(bytes).accept(
      object : ClassVisitor(Opcodes.API_VERSION) {
        override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor? {
          if (descriptor != "Lkotlin/Metadata;") return null
          val values = mutableMapOf<String, Any?>()
          return object : AnnotationVisitor(Opcodes.API_VERSION) {
            override fun visit(name: String, value: Any?) {
              values[name] = value
            }
            override fun visitArray(name: String): AnnotationVisitor {
              val list = mutableListOf<String>()
              return object : AnnotationVisitor(Opcodes.API_VERSION) {
                override fun visit(n: String?, value: Any?) {
                  list += value as String
                }
                override fun visitEnd() {
                  values[name] = list.toTypedArray()
                }
              }
            }
            override fun visitEnd() {
              @Suppress("UNCHECKED_CAST")
              result = Metadata(
                kind = values["k"] as Int?,
                metadataVersion = values["mv"] as IntArray?,
                data1 = values["d1"] as Array<String>?,
                data2 = values["d2"] as Array<String>?,
                extraString = values["xs"] as String?,
                packageName = values["pn"] as String?,
                extraInt = values["xi"] as Int?,
              )
            }
          }
        }
      },
      ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
    )
    return result
  }
}

internal fun KmType.toRef(): TypeRef {
  val name = when (val c = classifier) {
    is KmClassifier.Class -> c.name
    is KmClassifier.TypeAlias -> c.name
    is KmClassifier.TypeParameter -> "T${c.id}"
  }.replace('/', '.')
  val annotationNames = annotations.map { it.className.replace('/', '.') }
  val args = arguments.map { it.type?.toRef() ?: TypeRef("kotlin.Any", nullable = true) }
  val isExtension = "kotlin.ExtensionFunctionType" in annotationNames
  if (isSuspend && args.size >= 2 && name.startsWith("kotlin.Function")) {
    // A suspend function type is FunctionN<…, Continuation<R>, Any?> in metadata.
    val ret = args[args.size - 2].args.firstOrNull() ?: TypeRef("kotlin.Unit")
    val params = args.dropLast(2)
    return TypeRef(
      fq = "kotlin.Function${params.size - (if (isExtension) 1 else 0)}",
      nullable = isNullable,
      args = (if (isExtension) params.drop(1) else params) + ret,
      composable = false,
      receiver = if (isExtension) params.first() else null,
      suspend = true,
    )
  }
  return if (isExtension && args.isNotEmpty()) {
    TypeRef(
      fq = name.replace(Regex("\\d+$")) { (it.value.toInt() - 1).toString() },
      nullable = isNullable,
      args = args.drop(1),
      composable = "androidx.compose.runtime.Composable" in annotationNames,
      receiver = args.first(),
      suspend = isSuspend,
    )
  } else {
    TypeRef(
      fq = name,
      nullable = isNullable,
      args = args,
      composable = "androidx.compose.runtime.Composable" in annotationNames,
      suspend = isSuspend,
    )
  }
}
