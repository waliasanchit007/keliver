/*
 * AndroidX Macrobenchmark fixtures for the sample. Runs as an
 * instrumented test against `:host-android` configured with
 * `<profileable android:shell="true">` so the system-level startup
 * trace can be captured.
 *
 * Module type is `com.android.test`, not `com.android.application`
 * or `com.android.library` — that's what Macrobenchmark expects.
 * Targets a separate, real (or emulated) Android device; the test
 * framework launches + measures + reports.
 *
 * Phase 2 of the Konduit performance workstream — Phase 1
 * (artifact sizes + build times) lives in `docs/PERFORMANCE.md`.
 * Methodology + target SLAs for the metrics measured here are
 * committed in the same doc.
 */
plugins {
  alias(libs.plugins.androidTest)
  alias(libs.plugins.kotlinAndroid)
}

android {
  namespace = "dev.konduit.sample.benchmarks"
  compileSdk = libs.versions.android.compileSdk.get().toInt()

  defaultConfig {
    // Macrobenchmark needs API 29+. minSdk 24 (the host-android
    // setting) would fail at instrumentation time.
    minSdk = 29
    targetSdk = libs.versions.android.targetSdk.get().toInt()

    // Macrobenchmark uses the standard AndroidX JUnit runner — NOT
    // `androidx.benchmark.junit4.AndroidBenchmarkRunner` (that's
    // microbenchmark, which lives in the SAME process as the code
    // being measured). Macrobenchmark runs in a separate APK and
    // drives the target app via UiAutomator.
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    // Allow benchmarks to run on emulators. The DEBUGGABLE check
    // is the most important one — it catches `isDebuggable = true`
    // builds, which produce misleading numbers. EMULATOR is
    // suppressed because we want CI / contributor-laptop runs to
    // produce *some* baseline numbers even if the emulator's
    // gfxinfo framestats polling is occasionally flaky. For
    // publishable per-release-tag numbers, run on a real device.
    testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] = "EMULATOR"
  }

  // Macrobenchmark requires a release-shape variant to measure
  // realistic numbers (R8-minified, with profileinstaller baked
  // in). The benchmark variant inherits everything from `release`
  // but stays signed with the debug key + uses the `benchmark`
  // buildType in `host-android`.
  buildTypes {
    create("benchmark") {
      isDebuggable = true
      signingConfig = signingConfigs.getByName("debug")
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

  // Macrobenchmark needs to know which app it's measuring.
  targetProjectPath = ":host-android"
  experimentalProperties["android.experimental.self-instrumenting"] = true
}

dependencies {
  implementation(libs.androidx.benchmark.macro.junit4)
  implementation(libs.androidx.test.ext.junit)
  implementation(libs.androidx.test.runner)
  implementation(libs.androidx.uiautomator)
  implementation(libs.junit)
}

// Run the cold-start benchmark via:
//   ./gradlew :benchmarks:connectedBenchmarkAndroidTest
//
// Reports land at:
//   benchmarks/build/outputs/connected_android_test_additional_output/
//     benchmark/<device>/<class>#<method>.perfetto-trace
//   benchmarks/build/outputs/androidTest-results/connected/.../
//     test-result.pb
// plus a human-readable summary in the gradle console output.
//
// CI prerequisites:
//   - A physical or emulated Android device with API 29+ attached
//     (USB or `adb connect`).
//   - The dev manifest server (e.g. `python3 -m http.server 8080`
//     from sample/guest/build/zipline/Development/) running on the
//     same network as the device. Cold-start measures bundle fetch
//     + parse latency.
