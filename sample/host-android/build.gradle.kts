/*
 * Android host. Loads the guest bundle from a URL (DevConfig.MANIFEST_URL)
 * over OkHttp and mounts the resulting Compose tree inside `MainActivity`.
 *
 * Two plugins matter for the codegen / Konduit side: the standard
 * Compose compiler (so `setContent { ... }` works) and Konduit's
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

dependencies {
  // The :host-compose KMP module pulls in :shared, :shared-protocol-host,
  // keliver-treehouse-host, keliver-treehouse-host-composeui, etc. via
  // `api` dependencies — keeping `:host-android` thin (just the Android
  // entry shell + HTTP client glue).
  implementation(project(":host-compose"))
  implementation(libs.okhttp)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.activity.compose)
  // Required by AndroidX Macrobenchmark — emits the ProfileVerifier
  // markers that StartupTimingMetric uses to bracket cold-start.
  implementation(libs.androidx.profileinstaller)
}
