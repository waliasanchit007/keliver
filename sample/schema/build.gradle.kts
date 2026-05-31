/*
 * Schema definition — pure-JVM module that declares the @Schema +
 * @Widget data classes. The four `shared-*` sibling modules consume
 * this via `redwoodSchema { source = project(":schema") }` and produce
 * the actual host/guest interfaces + protocol adapters.
 */
plugins {
  alias(libs.plugins.kotlinJvm)
  alias(libs.plugins.konduit.schema)
}

dependencies {
  implementation(libs.konduit.schema)
}

redwoodSchema {
  type = "dev.keliver.sample.schema.SampleSchema"
}
