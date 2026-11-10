/*
 * Copyright (C) 2026 Keliver contributors.
 * Licensed under the Apache License, Version 2.0.
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
    val paramNames = spec.substringAfter('(').removeSuffix(")").split(',').map { it.trim() }.filter { it.isNotEmpty() }
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
        f.name == name && f.visibility == Visibility.PUBLIC && f.valueParameters.map { it.name } == paramNames
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
