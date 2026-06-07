/*
 * Shared Compose-side host code. KMP module with three targets so the
 * same widget impls + mount-point composable + iOS view-controller
 * factory power both `:host-android` (via the `androidTarget`) and an
 * external Xcode iOS shell (via the iOS framework that `iosArm64` and
 * `iosSimulatorArm64` produce).
 *
 * What lives here:
 *
 *   - `CmpWidgetFactory`: binds each codegen'd widget interface
 *     (`BoxWidget<CmpRender>`, `TextWidget<CmpRender>`) to a concrete
 *     Compose implementation in `commonMain`. This is the host's
 *     "renderer" — it owns how the guest's tree turns into pixels.
 *
 *   - `SampleHostApp`: the mount-point composable. Wraps Keliver's
 *     `TreehouseContent` and is what both platforms call into.
 *
 *   - `MainViewController`: iOS entry. Returns a `UIViewController`
 *     that hosts the same `SampleHostApp` inside a
 *     `ComposeUIViewController`. The Swift side embeds this.
 */
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.kotlinMultiplatform)
  alias(libs.plugins.androidLibrary)
  alias(libs.plugins.composeMultiplatform)
  alias(libs.plugins.composeCompiler)
  // Required because `MainViewController.kt`'s iOS-side
  // `Spec.create { zipline.take(...) }` block calls into the
  // Zipline IR-plugin-rewritten functions. Without this the iOS
  // framework would compile but fail at runtime with
  // "unexpected call to Zipline.take" — same shape as host-android.
  alias(libs.plugins.zipline)
}

kotlin {
  androidTarget {
    compilerOptions {
      jvmTarget.set(JvmTarget.JVM_11)
    }
  }

  // Keliver's iOS host code ships as a static .klib that we expose to
  // Swift via a single Kotlin framework named "KeliverSampleHost".
  // The Xcode-side `embedAndSignAppleFrameworkForXcode` Gradle task
  // bundles this into the iosApp/ Xcode build automatically.
  listOf(iosArm64(), iosSimulatorArm64()).forEach { iosTarget ->
    iosTarget.binaries.framework {
      baseName = "KeliverSampleHost"
      isStatic = true
      // Must match an iOS-valid reverse-DNS bundle ID. K/N uses this
      // for the framework's Info.plist CFBundleIdentifier.
      binaryOption("bundleId", "dev.keliver.sample.host")
      // Expose the sample's own types to Swift so adopter Swift code
      // can reach `SampleAppService` etc. — without these, only
      // symbols declared in this module would be visible.
      export(project(":shared"))
      export(project(":shared-widget"))
      // Required for Zipline's SQLite-backed bundle cache on iOS.
      // Without it, the Xcode link step fails with
      // "Undefined symbols: sqlite3_*".
      linkerOpts("-lsqlite3")
    }
  }

  sourceSets {
    val commonMain by getting {
      dependencies {
        api(project(":shared"))
        api(project(":shared-widget"))
        api(project(":shared-protocol-host"))
        api(libs.keliver.treehouse)
        api(libs.keliver.treehouse.host)
        api(libs.keliver.treehouse.host.composeui)
        api(libs.keliver.widget)
        api(libs.keliver.widget.composeui)
        api(libs.keliver.http)
        api(libs.zipline)
        api(libs.zipline.loader)
        implementation(compose.runtime)
        implementation(compose.foundation)
        implementation(compose.material)
      }
    }
    // `iosMain` is auto-created by the default hierarchy template
    // (since `iosArm64Main` + `iosSimulatorArm64Main` both exist).
    // Source files under `src/iosMain/kotlin/...` are picked up
    // automatically — no manual dependsOn / sourceSet wiring needed.
  }
}

android {
  namespace = "dev.keliver.sample.host.compose"
  compileSdk = libs.versions.android.compileSdk.get().toInt()
  defaultConfig {
    minSdk = libs.versions.android.minSdk.get().toInt()
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
}
