/*
 * Root build script for the standalone Konduit sample. Declares every
 * plugin the subprojects might use with `apply false` so Gradle can
 * resolve them ONCE from pluginManagement and hand the same classloader
 * to every subproject. Otherwise each subproject's `plugins { ... }`
 * block ends up loading the Kotlin plugin into a separate classloader
 * and Gradle fails the build with "Kotlin plugin loaded multiple times".
 */
plugins {
  alias(libs.plugins.kotlinMultiplatform) apply false
  alias(libs.plugins.kotlinJvm) apply false
  alias(libs.plugins.kotlinAndroid) apply false
  alias(libs.plugins.kotlinSerialization) apply false
  alias(libs.plugins.androidApplication) apply false
  alias(libs.plugins.androidLibrary) apply false
  alias(libs.plugins.androidTest) apply false
  alias(libs.plugins.composeMultiplatform) apply false
  alias(libs.plugins.composeCompiler) apply false
  alias(libs.plugins.zipline) apply false
  alias(libs.plugins.ksp) apply false
  alias(libs.plugins.keliver.schema) apply false
  alias(libs.plugins.keliver.generator.compose) apply false
  alias(libs.plugins.keliver.generator.widget) apply false
  alias(libs.plugins.keliver.generator.modifiers) apply false
  alias(libs.plugins.keliver.generator.protocol.host) apply false
  alias(libs.plugins.keliver.generator.protocol.guest) apply false
}
