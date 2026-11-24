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
import java.util.zip.ZipFile
import kotlin.metadata.ClassKind
import kotlin.metadata.KmClass
import kotlin.metadata.KmConstructor
import kotlin.metadata.KmFunction
import kotlin.metadata.KmProperty
import kotlin.metadata.Visibility
import kotlin.metadata.jvm.KotlinClassMetadata
import kotlin.metadata.jvm.Metadata
import kotlin.metadata.jvm.signature
import kotlin.metadata.kind
import kotlin.metadata.visibility
import org.jetbrains.org.objectweb.asm.AnnotationVisitor
import org.jetbrains.org.objectweb.asm.ClassReader
import org.jetbrains.org.objectweb.asm.ClassVisitor
import org.jetbrains.org.objectweb.asm.MethodVisitor
import org.jetbrains.org.objectweb.asm.Opcodes

/**
 * Spike (docs/COMPOSE_GENERIC_MECHANISMS.md): every Kotlin class and package function on the
 * Compose classpath, read once, so the three generic mechanisms can be checked against the whole
 * API rather than a configured list. [isHidden] tells apart a declaration Compose deprecated at
 * `HIDDEN` level (kept for binary compatibility), from the `@Deprecated` annotation in the bytecode,
 * as the sources reader does for the configured declarations.
 */
class GenericIndex(jars: List<File>) {
  class PackageFunction(val pkg: String, val facade: String, val fn: KmFunction)

  /** Classes by fully qualified name, nested classes joined by '.'. */
  val classes: Map<String, KmClass>

  /** Public top-level functions by package. */
  val packageFunctions: Map<String, List<PackageFunction>>

  /** Public top-level properties by package (`RectangleShape`, `CircleShape`). */
  val packageProperties: Map<String, List<KmProperty>>

  /** JVM `name+descriptor` of every `@Deprecated(level = HIDDEN)` method, by JVM class name. */
  private val hidden = mutableMapOf<String, MutableSet<String>>()

  init {
    val cls = linkedMapOf<String, KmClass>()
    val fns = linkedMapOf<String, MutableList<PackageFunction>>()
    val props = linkedMapOf<String, MutableList<KmProperty>>()
    for (jar in jars.filter { it.name.endsWith(".jar") }) {
      ZipFile(jar).use { zip ->
        for (e in zip.entries()) {
          if (!e.name.endsWith(".class") || e.name.startsWith("META-INF")) continue
          val bytes = zip.getInputStream(e).use { it.readBytes() }
          val jvmName = e.name.removeSuffix(".class")
          val md = read(bytes, jvmName) ?: continue
          when (val m = runCatching { KotlinClassMetadata.readLenient(md) }.getOrNull()) {
            is KotlinClassMetadata.Class -> cls[m.kmClass.name.replace('/', '.')] = m.kmClass
            is KotlinClassMetadata.FileFacade -> {
              val pkg = jvmName.substringBeforeLast('/').replace('/', '.')
              m.kmPackage.functions.filter { it.visibility == Visibility.PUBLIC }
                .forEach { fns.getOrPut(pkg) { mutableListOf() } += PackageFunction(pkg, jvmName, it) }
              props.getOrPut(pkg) { mutableListOf() } += m.kmPackage.properties.filter { it.visibility == Visibility.PUBLIC }
            }
            is KotlinClassMetadata.MultiFileClassPart -> {
              val pkg = jvmName.substringBeforeLast('/').replace('/', '.')
              m.kmPackage.functions.filter { it.visibility == Visibility.PUBLIC }
                .forEach { fns.getOrPut(pkg) { mutableListOf() } += PackageFunction(pkg, jvmName, it) }
              props.getOrPut(pkg) { mutableListOf() } += m.kmPackage.properties.filter { it.visibility == Visibility.PUBLIC }
            }
            else -> {}
          }
        }
      }
    }
    // Kotlin's class names put '.' between nested classes already (`a/b/Outer.Inner`).
    classes = cls
    packageFunctions = fns
    packageProperties = props
  }

  /** Whether [fn] (declared in JVM class [jvmOwner]) is `@Deprecated(level = HIDDEN)`. */
  fun isHidden(jvmOwner: String, fn: KmFunction): Boolean {
    val sig = fn.signature ?: return false
    return "${sig.name}${sig.descriptor}" in hidden[jvmOwner].orEmpty()
  }

  fun isHidden(jvmOwner: String, c: KmConstructor): Boolean {
    val sig = c.signature ?: return false
    return "${sig.name}${sig.descriptor}" in hidden[jvmOwner].orEmpty()
  }

  /** The JVM name of a class (`androidx/compose/ui/graphics/Brush$Companion`). */
  fun jvmName(fq: String): String {
    val pkg = packageOf(fq)
    return pkg.replace('.', '/') + "/" + fq.removePrefix("$pkg.").replace('.', '$')
  }

  /** [fq]'s supertypes, transitively, fully qualified. */
  fun allSupertypes(fq: String): Set<String> {
    val out = linkedSetOf<String>()
    val queue = ArrayDeque(listOf(fq))
    while (queue.isNotEmpty()) {
      val c = classes[queue.removeFirst()] ?: continue
      for (s in c.supertypes) {
        val n = (s.classifier as? kotlin.metadata.KmClassifier.Class)?.name?.replace('/', '.') ?: continue
        if (out.add(n)) queue += n
      }
    }
    return out
  }

  fun isObject(c: KmClass): Boolean = c.kind == ClassKind.OBJECT || c.kind == ClassKind.COMPANION_OBJECT

  private fun read(bytes: ByteArray, jvmName: String): Metadata? {
    var result: Metadata? = null
    ClassReader(bytes).accept(
      object : ClassVisitor(Opcodes.API_VERSION) {
        override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?): MethodVisitor? {
          // Not the synthetic flag alone: a constructor taking a value class is compiled as a
          // synthetic one with a DefaultConstructorMarker, and metadata points at it.
          return object : MethodVisitor(Opcodes.API_VERSION) {
            override fun visitAnnotation(d: String, visible: Boolean): AnnotationVisitor? {
              if (d != "Lkotlin/Deprecated;") return null
              return object : AnnotationVisitor(Opcodes.API_VERSION) {
                override fun visitEnum(n: String?, desc: String?, value: String?) {
                  if (n == "level" && value == "HIDDEN") hidden.getOrPut(jvmName) { mutableSetOf() } += name + descriptor
                }
              }
            }
          }
        }

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
