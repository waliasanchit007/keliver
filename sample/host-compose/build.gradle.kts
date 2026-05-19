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
 *   - `SampleHostApp`: the mount-point composable. Wraps Konduit's
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

  // Konduit's iOS host code ships as a static .klib that we expose to
  // Swift via a single Kotlin framework named "KonduitSampleHost".
  listOf(iosArm64(), iosSimulatorArm64()).forEach {
    it.binaries.framework {
      baseName = "KonduitSampleHost"
      isStatic = true
    }
  }

  sourceSets {
    val commonMain by getting {
      dependencies {
        api(project(":shared"))
        api(project(":shared-widget"))
        api(project(":shared-protocol-host"))
        api(libs.konduit.treehouse)
        api(libs.konduit.treehouse.host)
        api(libs.konduit.treehouse.host.composeui)
        api(libs.konduit.widget)
        api(libs.konduit.widget.composeui)
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
  namespace = "dev.konduit.sample.host.compose"
  compileSdk = libs.versions.android.compileSdk.get().toInt()
  defaultConfig {
    minSdk = libs.versions.android.minSdk.get().toInt()
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
}
