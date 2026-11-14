/*
 * Android host. Loads the guest bundle from a URL (DevConfig.MANIFEST_URL)
 * over OkHttp and mounts the resulting Compose tree inside `MainActivity`.
 *
 * Two plugins matter for the codegen / Keliver side: the standard
 * Compose compiler (so `setContent { ... }` works) and Keliver's
 * `keliver-treehouse-host-composeui` runtime dep, which provides the
 * `TreehouseContent` composable used in `SampleHostApp.kt`.
 */
plugins {
  alias(libs.plugins.androidApplication)
  alias(libs.plugins.kotlinAndroid)
  alias(libs.plugins.composeCompiler)
  alias(libs.plugins.composeMultiplatform)
  // The Zipline IR plugin rewrites `zipline.take<T>` / `zipline.bind<T>`
  // call sites to use the generated Adapter. Without this here, the
  // host's `Spec.create { zipline.take("app") }` throws
  //   IllegalStateException: unexpected call to Zipline.take: is the
  //   Zipline plugin configured?
  // at runtime. Must be applied to EVERY module that contains a
  // take/bind call — host shell, host renderer, and the guest.
  alias(libs.plugins.zipline)
}

android {
  namespace = "dev.keliver.sample.host"
  compileSdk = libs.versions.android.compileSdk.get().toInt()

  defaultConfig {
    applicationId = "dev.keliver.sample"
    minSdk = libs.versions.android.minSdk.get().toInt()
    targetSdk = libs.versions.android.targetSdk.get().toInt()
    versionCode = 1
    versionName = "1.0"
  }

  buildFeatures {
    compose = true
  }

  // Matched by `:benchmarks`'s `benchmark` build type via
  // `matchingFallbacks`. Release-shape (R8 + profileinstaller
  // baked in) so the Macrobenchmark fixtures measure something
  // close to what end users see; debuggable so the system trace
  // can capture per-class startup markers; signed with the debug
  // key so adb can install without a release keystore.
  buildTypes {
    create("benchmark") {
      initWith(getByName("release"))
      // Macrobenchmark refuses to measure DEBUGGABLE builds —
      // they run slower than realistic and produce misleading
      // numbers. `isDebuggable = false` + signing with the
      // debug keystore is the standard pattern that lets us
      // adb-install without a release keystore + still pass
      // the macrobenchmark safety check.
      isDebuggable = false
      isMinifyEnabled = false
      signingConfig = signingConfigs.getByName("debug")
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
      // `:host-compose` declares only `debug` + `release`; tell the
      // dependency resolver to use its `release` artifact when this
      // module's `benchmark` variant is being built. Without this,
      // Gradle errors with "No matching variant" for project deps
      // when assembling `:host-android:assembleBenchmark`.
      matchingFallbacks += listOf("release")
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }

  kotlin {
    jvmToolchain(11)
  }
}

// -------------------------------------------------------------------------------------------------
// Gate B-e (prototype only, docs/GATE_B_RESULTS.md). With -PkeliverComposePrototype the debug and
// benchmark builds get GatebActivity (src/gateb), the generated group's renderers and the gateb
// guest bundle in their assets. -PkeliverGatebInRelease also puts them in the release build (for
// the app-size measurement); in prototype builds release is minified with R8. Never ship such a
// release: GatebActivity is exported and, with the `overLockScreen` extra, draws over the lock
// screen (benchmark-only). Without the property this block does nothing.
val composePrototype = providers.gradleProperty("keliverComposePrototype").isPresent
if (composePrototype) {
  val gatebInRelease = providers.gradleProperty("keliverGatebInRelease").isPresent
  val routeBuildTypes = listOf("debug", "benchmark") + if (gatebInRelease) listOf("release") else emptyList()
  val keliverVersion = libs.versions.keliver.get()
  val guest = project(":gateb-guest")

  // The bundle, from the guest's production build, as assets/gateb/*.
  val gatebAssets = tasks.register<Sync>("gatebBundleAssets") {
    dependsOn(":gateb-guest:compileProductionExecutableKotlinJsZipline")
    from(guest.layout.buildDirectory.dir("zipline/Production"))
    into(layout.buildDirectory.dir("generated/gatebAssets/gateb"))
  }
  // The native copy of the guest's screen: the same file, imports rewritten (only imports differ).
  val gatebNative = tasks.register<Sync>("gatebNativeScreen") {
    from(guest.file("src/jsMain/kotlin/dev/keliver/sample/gateb/AnimationBench.kt"))
    into(layout.buildDirectory.dir("generated/gatebNative/kotlin/dev/keliver/sample/gateb/nativecopy"))
    filter { line: String ->
      line.replace("import dev.keliver.compose.", "import androidx.compose.")
        .replace("package dev.keliver.sample.gateb", "package dev.keliver.sample.gateb.nativecopy")
    }
  }

  // The prototype stack's machines have JDK 17, not 11 (the bytecode target stays 11).
  extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension> {
    jvmToolchain(17)
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
  }

  android {
    buildTypes.getByName("release") {
      isMinifyEnabled = true
      signingConfig = signingConfigs.getByName("debug")
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "gateb-proguard-rules.pro")
    }
    for (type in routeBuildTypes) {
      sourceSets.getByName(type) {
        kotlin.srcDir("src/gateb/kotlin")
        kotlin.srcDir(layout.buildDirectory.dir("generated/gatebNative/kotlin"))
        manifest.srcFile("src/gateb/AndroidManifest.xml")
        assets.srcDir(layout.buildDirectory.dir("generated/gatebAssets"))
      }
    }
  }
  tasks.configureEach {
    if (name.startsWith("compile") && name.endsWith("Kotlin")) dependsOn(gatebNative)
    if (name.startsWith("merge") && name.endsWith("Assets")) dependsOn(gatebAssets)
    if (name.contains("lint", ignoreCase = true)) dependsOn(gatebAssets, gatebNative)
  }
  dependencies {
    for (type in routeBuildTypes) {
      add("${type}Implementation", project(":gateb-guest"))
      add("${type}Implementation", "dev.keliver:keliver-compose-gatea-composeui:$keliverVersion")
      add("${type}Implementation", "dev.keliver:keliver-compose-gatea-protocol-host:$keliverVersion")
      add("${type}Implementation", "dev.keliver:keliver-material-composeui:$keliverVersion")
      add("${type}Implementation", "org.jetbrains.compose.material3:material3:1.9.0")
      add("${type}Implementation", "org.jetbrains.compose.animation:animation:${libs.versions.composeMultiplatform.get()}")
    }
  }
}

dependencies {
  // The :host-compose KMP module pulls in :shared, :shared-protocol-host,
  // keliver-treehouse-host, keliver-treehouse-host-composeui, etc. via
  // `api` dependencies — keeping `:host-android` thin (just the Android
  // entry shell + HTTP client glue).
  implementation(project(":host-compose"))
  implementation(libs.keliver.http)
  implementation(libs.okhttp)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.activity.compose)
  // Required by AndroidX Macrobenchmark — emits the ProfileVerifier
  // markers that StartupTimingMetric uses to bracket cold-start.
  implementation(libs.androidx.profileinstaller)
}
