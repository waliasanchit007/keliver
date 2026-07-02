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
