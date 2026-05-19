/*
 * Android host. Loads the guest bundle from a URL (DevConfig.MANIFEST_URL)
 * over OkHttp and mounts the resulting Compose tree inside `MainActivity`.
 *
 * Two plugins matter for the codegen / Konduit side: the standard
 * Compose compiler (so `setContent { ... }` works) and Konduit's
 * `konduit-treehouse-host-composeui` runtime dep, which provides the
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
  namespace = "dev.konduit.sample.host"
  compileSdk = libs.versions.android.compileSdk.get().toInt()

  defaultConfig {
    applicationId = "dev.konduit.sample"
    minSdk = libs.versions.android.minSdk.get().toInt()
    targetSdk = libs.versions.android.targetSdk.get().toInt()
    versionCode = 1
    versionName = "1.0"
  }

  buildFeatures {
    compose = true
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
  // konduit-treehouse-host, konduit-treehouse-host-composeui, etc. via
  // `api` dependencies — keeping `:host-android` thin (just the Android
  // entry shell + HTTP client glue).
  implementation(project(":host-compose"))
  implementation(libs.okhttp)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.activity.compose)
}
