import UIKit
import SwiftUI
import KonduitSampleHost

/**
 SwiftUI wrapper around the Kotlin-side `MainViewController()` factory.

 `KonduitSampleHost` is the framework baseName declared in
 `sample/host-compose/build.gradle.kts`. The Kotlin function lives at
 file level in `MainViewController.kt`, so K/N exports it on the
 `MainViewControllerKt` companion-object container.

 `.ignoresSafeArea()` lets the guest tree paint into the home-indicator
 area on modern iPhones; remove it if you want the standard SwiftUI
 inset behavior.
 */
struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

struct ContentView: View {
    var body: some View {
        ComposeView()
            .ignoresSafeArea()
    }
}
