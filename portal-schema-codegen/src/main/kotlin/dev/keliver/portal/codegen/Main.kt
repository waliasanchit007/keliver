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

  // simple name -> plan; layout wins collisions (matches existing tree semantics).
  val plans = mutableMapOf<String, WidgetPlan>()
  for (w in material.schema.widgets) {
    plans[w.type.names.last()] = planWidget("dev.keliver.material.compose", "Material", w)
  }
  for (w in layout.schema.widgets) {
    val name = w.type.names.last()
    if (name in plans) println("collision: '$name' defined in material too — layout wins")
    plans[name] = planWidget("dev.keliver.layout.compose", "Layout", w)
  }
  val includes = plans.values.filterIsInstance<WidgetPlan.Include>()
  val excluded = plans.values.filterIsInstance<WidgetPlan.Exclude>()

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
